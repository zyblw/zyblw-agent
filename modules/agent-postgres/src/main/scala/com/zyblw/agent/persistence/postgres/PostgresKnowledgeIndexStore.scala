package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.sql.{Connection, ResultSet, SQLException, Timestamp}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL/pgvector 知识索引版本库。
  *
  * 慢速 Embedding 调用发生在 `KnowledgeIndexer` 中，不占用这里的数据库事务。`stage` 使用可重放 upsert， `activate` 则通过文档级 advisory
  * transaction lock 把“校验块数、废弃旧版本、替换正式块、切 active manifest”放进同一短事务，因此查询只能看到旧完整版本或新完整版本。
  *
  * @param dataSource
  *   宿主共享连接池；框架不创建隐藏连接池
  * @param dimension
  *   optional pgvector migration 的固定 `vector(N)` 维度
  */
final class PostgresKnowledgeIndexStore(dataSource: DataSource, dimension: Int) extends KnowledgeIndexStore:
  require(dimension > 0, "PostgreSQL knowledge dimension 必须为正数")

  /** 分配文档版本，使用 transaction-scoped advisory lock 解决“文档尚无行可锁”的首次并发创建问题。 同一 ingestionId 的失败版本会清空暂存区并恢复
    * Building；已被新版本替代的旧 ingestion 不允许复活。
    */
  def begin(request: BeginKnowledgeIndex): IO[RetrievalError, KnowledgeIndexBuild] =
    if request.embedding.dimension != dimension then
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"Embedding 维度 ${request.embedding.dimension} != PostgreSQL 索引维度 $dimension"
        )
      )
    else
      withTransaction { connection =>
        for
          _        <- lockDocument(connection, request.key)
          existing <- selectByIngestion(connection, request.key, request.ingestionId, forUpdate = true)
          build    <- existing match
            case Some(manifest) if !sameRequest(manifest, request) =>
              ZIO.fail(
                AgentError.RetrievalFailed(
                  s"knowledge ingestionId 已绑定不同请求: ${request.key.documentId}"
                )
              )
            case Some(manifest) if manifest.status == KnowledgeIndexStatus.Superseded =>
              ZIO.fail(
                AgentError.RetrievalFailed(
                  s"knowledge ingestion 已被较新版本替代: ${request.key.documentId}"
                )
              )
            case Some(manifest) if manifest.status == KnowledgeIndexStatus.Retired =>
              ZIO.fail(
                AgentError.RetrievalFailed(
                  s"knowledge ingestion 已被下线: ${request.key.documentId}"
                )
              )
            case Some(manifest) if manifest.status == KnowledgeIndexStatus.Failed =>
              clearStaging(connection, manifest.build) *>
                updateStatus(connection, manifest.build, "building", active = false, None) *>
                ZIO.succeed(manifest.build)
            case Some(manifest) => ZIO.succeed(manifest.build)
            case None           => createBuild(connection, request)
        yield build
      }

  /** 幂等写入一个暂存批次。
    *
    * 先在取得连接前验证租户、文档、版本与向量维度，再在事务中锁 manifest 并确认仍是 Building。相同 chunkId 的重试覆盖暂存值，不会累计重复行。
    */
  def stage(build: KnowledgeIndexBuild, chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit] =
    validateStagedChunks(build, chunks) *> withTransaction { connection =>
      for
        manifest <- selectExact(connection, build, forUpdate = true).someOrFail(
          AgentError.RetrievalFailed("knowledge build 不存在")
        )
        _ <- ensureSameBuild(manifest.build, build)
        _ <- ZIO
          .fail(
            AgentError.RetrievalFailed(
              s"knowledge build 状态不允许暂存: ${manifest.status}"
            )
          )
          .unless(manifest.status == KnowledgeIndexStatus.Building)
        _ <- insertStaging(connection, build, chunks)
        _ <- touchBuild(connection, build)
      yield ()
    }

  /** 原子发布一个完整版本。
    *
    * @param build
    *   `begin` 返回的不可变句柄
    * @param expectedChunkCount
    *   切分后的精确块数；与暂存表不一致时整个事务回滚
    */
  def activate(
      build: KnowledgeIndexBuild,
      expectedChunkCount: Int
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    if expectedChunkCount < 0 then ZIO.fail(AgentError.RetrievalFailed("expectedChunkCount 不能为负数"))
    else
      withTransaction { connection =>
        for
          _        <- lockDocument(connection, build.key)
          manifest <- selectExact(connection, build, forUpdate = true).someOrFail(
            AgentError.RetrievalFailed("knowledge build 不存在")
          )
          _      <- ensureSameBuild(manifest.build, build)
          result <-
            if manifest.status == KnowledgeIndexStatus.Ready && manifest.active then ZIO.succeed(manifest)
            else if manifest.status != KnowledgeIndexStatus.Building then
              ZIO.fail(
                AgentError.RetrievalFailed(
                  s"knowledge build 状态不允许发布: ${manifest.status}"
                )
              )
            else publishBuilding(connection, build, expectedChunkCount)
        yield result
      }

  /** 只把仍为 Building 的版本标记失败；迟到清理不能覆盖已经 Ready 的版本。 */
  def markFailed(build: KnowledgeIndexBuild, failureCode: String): IO[RetrievalError, Unit] =
    val safeCode = if failureCode.matches("[A-Za-z0-9_.-]{1,64}") then failureCode else "unknown"
    withConnection { connection =>
      jdbc("mark failed") {
        val statement = connection.prepareStatement(
          """UPDATE zyblw_agent_knowledge.agent_knowledge_documents
            |SET status = 'failed', active = FALSE, failure_code = ?, updated_at = now()
            |WHERE tenant_id = ? AND document_id = ? AND index_version = ? AND status = 'building'""".stripMargin
        )
        try
          statement.setString(1, safeCode)
          statement.setString(2, build.key.tenantId.value)
          statement.setString(3, build.key.documentId)
          statement.setLong(4, build.version)
          statement.executeUpdate()
          ()
        finally statement.close()
      }
    }

  /** 查询当前 active manifest；唯一部分索引保证至多一行。 */
  def active(key: KnowledgeDocumentKey): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    withConnection(connection =>
      selectOne(
        connection,
        s"$manifestSelect WHERE tenant_id = ? AND document_id = ? AND active = TRUE",
        statement =>
          statement.setString(1, key.tenantId.value)
          statement.setString(2, key.documentId)
      )
    )

  /** 按幂等键读取任意状态 manifest，供 worker 崩溃恢复。 */
  def find(
      key: KnowledgeDocumentKey,
      ingestionId: String
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    withConnection(connection => selectByIngestion(connection, key, ingestionId, forUpdate = false))

  /** 在文档 advisory lock 和 manifest 行锁下执行乐观下线，并删除正式检索块。 相同 expected version 已 Retired 时幂等返回；存在更新 active version
    * 时拒绝迟到删除。
    */
  def retire(
      key: KnowledgeDocumentKey,
      expectedActiveVersion: Long
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    if expectedActiveVersion <= 0L then ZIO.fail(AgentError.RetrievalFailed("expectedActiveVersion 必须为正数"))
    else
      withTransaction { connection =>
        for
          _       <- lockDocument(connection, key)
          active  <- selectActiveManifest(connection, key, forUpdate = true)
          retired <- active match
            case Some(manifest) if manifest.build.version != expectedActiveVersion =>
              ZIO.fail(AgentError.RetrievalFailed("knowledge retire active version 前置条件失败"))
            case Some(_) =>
              for
                _ <- deletePublished(connection, key)
                _ <- jdbc("retire manifest") {
                  val statement = connection.prepareStatement(
                    """UPDATE zyblw_agent_knowledge.agent_knowledge_documents
                                    |SET status = 'retired', active = FALSE, updated_at = CURRENT_TIMESTAMP
                                    |WHERE tenant_id = ? AND document_id = ? AND index_version = ?
                                    |  AND status = 'ready' AND active = TRUE""".stripMargin
                  )
                  try
                    statement.setString(1, key.tenantId.value)
                    statement.setString(2, key.documentId)
                    statement.setLong(3, expectedActiveVersion)
                    if statement.executeUpdate() != 1 then
                      throw IllegalStateException("retire manifest CAS 失败")
                    ()
                  finally statement.close()
                }
                value <- selectVersion(connection, key, expectedActiveVersion, forUpdate = false)
                  .someOrFail(AgentError.RetrievalFailed("retire 后 manifest 丢失"))
              yield value
            case None =>
              selectVersion(connection, key, expectedActiveVersion, forUpdate = true).flatMap {
                case Some(manifest) if manifest.status == KnowledgeIndexStatus.Retired =>
                  ZIO.succeed(manifest)
                case _ => ZIO.fail(AgentError.RetrievalFailed("knowledge retire 目标不是当前 active 版本"))
              }
        yield retired
      }

  /** 使用稳定顺序和 SKIP LOCKED 有界删除非活动终态 manifest；staging 由外键级联清理。 Ready 与 Building 不在候选状态中，避免 retention 与发布/恢复竞争。
    */
  def purgeInactive(updatedBefore: java.time.Instant, limit: Int): IO[RetrievalError, Long] =
    if limit <= 0 then ZIO.succeed(0L)
    else
      withTransaction { connection =>
        jdbc("purge inactive manifests") {
          val statement = connection.prepareStatement(
            """WITH candidates AS (
            |  SELECT tenant_id, document_id, index_version
            |  FROM zyblw_agent_knowledge.agent_knowledge_documents
            |  WHERE active = FALSE
            |    AND status IN ('superseded', 'failed', 'retired')
            |    AND updated_at < ?
            |  ORDER BY updated_at, tenant_id, document_id, index_version
            |  FOR UPDATE SKIP LOCKED
            |  LIMIT ?
            |)
            |DELETE FROM zyblw_agent_knowledge.agent_knowledge_documents document
            |USING candidates candidate
            |WHERE document.tenant_id = candidate.tenant_id
            |  AND document.document_id = candidate.document_id
            |  AND document.index_version = candidate.index_version""".stripMargin
          )
          try
            statement.setTimestamp(1, Timestamp.from(updatedBefore))
            statement.setInt(2, limit)
            statement.executeUpdate().toLong
          finally statement.close()
        }
      }

  /** 在文档级 advisory lock 下校验 active 前置条件、计算下一版本并插入 Building manifest。 */
  private def createBuild(
      connection: Connection,
      request: BeginKnowledgeIndex
  ): IO[RetrievalError, KnowledgeIndexBuild] =
    for
      current <- selectActiveVersion(connection, request.key)
      _       <- ZIO
        .fail(
          AgentError.RetrievalFailed(
            s"knowledge active version 前置条件失败: ${request.key.documentId}"
          )
        )
        .unless(matchesExpectation(request.expectation, current))
      next <- nextVersion(connection, request.key)
      build = KnowledgeIndexBuild(
        request.key,
        next,
        request.ingestionId,
        request.contentHash,
        request.embedding,
        request.indexingStrategy
      )
      _ <- jdbc("insert manifest") {
        val statement = connection.prepareStatement(
          """INSERT INTO zyblw_agent_knowledge.agent_knowledge_documents
                 |(tenant_id, document_id, index_version, ingestion_id, source_uri, content_hash,
                 | permissions, metadata, embedding_provider, embedding_model, embedding_dimension,
                 | embedding_max_batch_size, embedding_supports_dimensions, indexing_strategy,
                 | status, active, chunk_count)
                 |VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, 'building', FALSE, 0)""".stripMargin
        )
        try
          statement.setString(1, request.key.tenantId.value)
          statement.setString(2, request.key.documentId)
          statement.setLong(3, next)
          statement.setString(4, request.ingestionId)
          statement.setString(5, request.sourceUri)
          statement.setString(6, request.contentHash)
          statement.setArray(7, connection.createArrayOf("text", request.permissions.toArray))
          statement.setString(8, request.metadata.toJson)
          statement.setString(9, request.embedding.provider)
          statement.setString(10, request.embedding.model)
          statement.setInt(11, request.embedding.dimension)
          statement.setInt(12, request.embedding.maxBatchSize)
          statement.setBoolean(13, request.embedding.supportsDimensions)
          statement.setString(14, request.indexingStrategy)
          statement.executeUpdate()
          ()
        finally statement.close()
      }
    yield build

  /** 校验暂存数量并执行正式快照替换；调用方已经持有文档级锁和 manifest 行锁。 */
  private def publishBuilding(
      connection: Connection,
      build: KnowledgeIndexBuild,
      expectedChunkCount: Int
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    for
      count <- stagedCount(connection, build)
      _     <- ZIO
        .fail(
          AgentError.RetrievalFailed(
            s"knowledge staged chunk 数量 $count != $expectedChunkCount"
          )
        )
        .unless(count == expectedChunkCount)
      _ <- jdbc("supersede active manifest") {
        val statement = connection.prepareStatement(
          """UPDATE zyblw_agent_knowledge.agent_knowledge_documents
                 |SET status = 'superseded', active = FALSE, updated_at = now()
                 |WHERE tenant_id = ? AND document_id = ? AND active = TRUE AND index_version <> ?""".stripMargin
        )
        try
          statement.setString(1, build.key.tenantId.value)
          statement.setString(2, build.key.documentId)
          statement.setLong(3, build.version)
          statement.executeUpdate()
          ()
        finally statement.close()
      }
      _ <- jdbc("delete old published chunks") {
        val statement = connection.prepareStatement(
          "DELETE FROM zyblw_agent_knowledge.agent_knowledge_chunks WHERE tenant_id = ? AND document_id = ?"
        )
        try
          statement.setString(1, build.key.tenantId.value)
          statement.setString(2, build.key.documentId)
          statement.executeUpdate()
          ()
        finally statement.close()
      }
      _ <- jdbc("publish staged chunks") {
        val statement = connection.prepareStatement(
          """INSERT INTO zyblw_agent_knowledge.agent_knowledge_chunks
                 |(tenant_id, chunk_id, document_id, index_version, chunk_text, search_text,
                 | source_uri, permissions, metadata, embedding, parent_id, lineage_ordinal,
                 | previous_chunk_id, next_chunk_id, heading_path, page_numbers, origins, block_ids)
                 |SELECT tenant_id, chunk_id, document_id, index_version, chunk_text, search_text,
                 |       source_uri, permissions, metadata, embedding, parent_id, lineage_ordinal,
                 |       previous_chunk_id, next_chunk_id, heading_path, page_numbers, origins, block_ids
                 |FROM zyblw_agent_knowledge.agent_knowledge_chunk_staging
                 |WHERE tenant_id = ? AND document_id = ? AND index_version = ?""".stripMargin
        )
        try
          statement.setString(1, build.key.tenantId.value)
          statement.setString(2, build.key.documentId)
          statement.setLong(3, build.version)
          val inserted = statement.executeUpdate()
          if inserted != expectedChunkCount then
            throw IllegalStateException(s"published chunk count $inserted != $expectedChunkCount")
          ()
        finally statement.close()
      }
      _     <- updateStatus(connection, build, "ready", active = true, None, expectedChunkCount)
      _     <- clearStaging(connection, build)
      ready <- selectExact(connection, build, forUpdate = false).someOrFail(
        AgentError.RetrievalFailed("发布后 manifest 丢失")
      )
    yield ready

  /** 批量 upsert 暂存向量；调用时 manifest 已在同一事务内锁定。 */
  private def insertStaging(
      connection: Connection,
      build: KnowledgeIndexBuild,
      chunks: Chunk[IndexedChunk]
  ): IO[RetrievalError, Unit] =
    if chunks.isEmpty then ZIO.unit
    else
      jdbc("stage chunks") {
        val statement = connection.prepareStatement(
          """INSERT INTO zyblw_agent_knowledge.agent_knowledge_chunk_staging
          |(tenant_id, document_id, index_version, chunk_id, chunk_text, search_text,
          | source_uri, permissions, metadata, embedding, parent_id, lineage_ordinal,
          | previous_chunk_id, next_chunk_id, heading_path, page_numbers, origins, block_ids)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::public.vector, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
          |ON CONFLICT (tenant_id, document_id, index_version, chunk_id) DO UPDATE SET
          |chunk_text = EXCLUDED.chunk_text,
          |search_text = EXCLUDED.search_text,
          |source_uri = EXCLUDED.source_uri,
          |permissions = EXCLUDED.permissions,
          |metadata = EXCLUDED.metadata,
          |embedding = EXCLUDED.embedding,
          |parent_id = EXCLUDED.parent_id,
          |lineage_ordinal = EXCLUDED.lineage_ordinal,
          |previous_chunk_id = EXCLUDED.previous_chunk_id,
          |next_chunk_id = EXCLUDED.next_chunk_id,
          |heading_path = EXCLUDED.heading_path,
          |page_numbers = EXCLUDED.page_numbers,
          |origins = EXCLUDED.origins,
          |block_ids = EXCLUDED.block_ids,
          |updated_at = now()""".stripMargin
        )
        try
          chunks.foreach { indexed =>
            val chunk = indexed.chunk
            statement.setString(1, build.key.tenantId.value)
            statement.setString(2, build.key.documentId)
            statement.setLong(3, build.version)
            statement.setString(4, chunk.id)
            statement.setString(5, chunk.text)
            statement.setString(6, chunk.searchText.getOrElse(chunk.text))
            statement.setString(7, chunk.sourceUri)
            statement.setArray(8, connection.createArrayOf("text", chunk.permissions.toArray))
            statement.setString(9, chunk.metadata.toJson)
            statement.setString(10, vectorLiteral(indexed.embedding))
            bindLineage(statement, connection, 11, chunk.lineage)
            statement.addBatch()
          }
          statement.executeBatch()
          ()
        finally statement.close()
      }

  /** 在进入数据库前验证所有暂存块，避免半个 JDBC batch 才发现归属或维度错误。 */
  private def validateStagedChunks(
      build: KnowledgeIndexBuild,
      chunks: Chunk[IndexedChunk]
  ): IO[RetrievalError, Unit] =
    chunks.find { indexed =>
      val chunk = indexed.chunk
      chunk.tenantId != build.key.tenantId ||
      chunk.documentId != build.key.documentId ||
      chunk.indexVersion != build.version ||
      indexed.embedding.values.length != dimension
    } match
      case Some(value) =>
        ZIO.fail(
          AgentError.RetrievalFailed(
            s"knowledge staged chunk 契约不匹配: ${value.chunk.id}"
          )
        )
      case None => ZIO.unit

  /** 以固定列顺序绑定可选谱系。空谱系写 SQL NULL/空数组，不伪造页码或父子关系。 */
  private def bindLineage(
      statement: java.sql.PreparedStatement,
      connection: Connection,
      start: Int,
      lineage: Option[ChunkLineage]
  ): Unit =
    statement.setString(start, lineage.flatMap(_.parentId).orNull)
    statement.setObject(start + 1, lineage.map(value => Int.box(value.ordinal)).orNull)
    statement.setString(start + 2, lineage.flatMap(_.previousChunkId).orNull)
    statement.setString(start + 3, lineage.flatMap(_.nextChunkId).orNull)
    statement.setArray(
      start + 4,
      connection.createArrayOf("text", lineage.fold(Chunk.empty[String])(_.headingPath).toArray)
    )
    statement.setArray(
      start + 5,
      connection.createArrayOf("integer", lineage.fold(Chunk.empty[Int])(_.pageNumbers).map(Int.box).toArray)
    )
    statement.setString(start + 6, lineage.fold(Chunk.empty[DocumentOrigin])(_.origins).toJson)
    statement.setArray(
      start + 7,
      connection.createArrayOf("text", lineage.fold(Chunk.empty[String])(_.blockIds).toArray)
    )

  /** 清理一个版本的全部暂存行；用于失败重试和成功发布。 */
  private def clearStaging(connection: Connection, build: KnowledgeIndexBuild): IO[RetrievalError, Unit] =
    jdbc("clear staging") {
      val statement = connection.prepareStatement(
        "DELETE FROM zyblw_agent_knowledge.agent_knowledge_chunk_staging WHERE tenant_id = ? AND document_id = ? AND index_version = ?"
      )
      try
        statement.setString(1, build.key.tenantId.value)
        statement.setString(2, build.key.documentId)
        statement.setLong(3, build.version)
        statement.executeUpdate()
        ()
      finally statement.close()
    }

  /** 更新 manifest 状态；可选 chunkCount 只在正式发布时写入。 */
  private def updateStatus(
      connection: Connection,
      build: KnowledgeIndexBuild,
      status: String,
      active: Boolean,
      failureCode: Option[String],
      chunkCount: Int = 0
  ): IO[RetrievalError, Unit] = jdbc("update manifest status") {
    val statement = connection.prepareStatement(
      """UPDATE zyblw_agent_knowledge.agent_knowledge_documents
        |SET status = ?, active = ?, failure_code = ?, chunk_count = ?, updated_at = now()
        |WHERE tenant_id = ? AND document_id = ? AND index_version = ?""".stripMargin
    )
    try
      statement.setString(1, status)
      statement.setBoolean(2, active)
      statement.setString(3, failureCode.orNull)
      statement.setInt(4, chunkCount)
      statement.setString(5, build.key.tenantId.value)
      statement.setString(6, build.key.documentId)
      statement.setLong(7, build.version)
      if statement.executeUpdate() != 1 then throw IllegalStateException("manifest update affected no row")
      ()
    finally statement.close()
  }

  /** 只刷新 Building manifest 的恢复扫描时间，不改变业务状态。 */
  private def touchBuild(connection: Connection, build: KnowledgeIndexBuild): IO[RetrievalError, Unit] =
    jdbc("touch build") {
      val statement = connection.prepareStatement(
        """UPDATE zyblw_agent_knowledge.agent_knowledge_documents SET updated_at = now()
        |WHERE tenant_id = ? AND document_id = ? AND index_version = ? AND status = 'building'""".stripMargin
      )
      try
        statement.setString(1, build.key.tenantId.value)
        statement.setString(2, build.key.documentId)
        statement.setLong(3, build.version)
        if statement.executeUpdate() != 1 then
          throw IllegalStateException("building manifest touch affected no row")
        ()
      finally statement.close()
    }

  /** 读取暂存块精确数量。 */
  private def stagedCount(connection: Connection, build: KnowledgeIndexBuild): IO[RetrievalError, Int] =
    jdbc("count staging") {
      val statement = connection.prepareStatement(
        """SELECT count(*) FROM zyblw_agent_knowledge.agent_knowledge_chunk_staging
        |WHERE tenant_id = ? AND document_id = ? AND index_version = ?""".stripMargin
      )
      try
        statement.setString(1, build.key.tenantId.value)
        statement.setString(2, build.key.documentId)
        statement.setLong(3, build.version)
        val result = statement.executeQuery()
        if !result.next() then throw IllegalStateException("count staging returned no row")
        result.getInt(1)
      finally statement.close()
    }

  /** 文档锁 key 使用长度前缀避免简单字符串拼接碰撞。 */
  private def lockDocument(connection: Connection, key: KnowledgeDocumentKey): IO[RetrievalError, Unit] =
    jdbc("lock document") {
      val statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
      try
        val tenant = key.tenantId.value
        statement.setString(1, s"${tenant.length}:$tenant:${key.documentId}")
        statement.executeQuery()
        ()
      finally statement.close()
    }

  /** 查询 active version，调用方持有文档 advisory lock。 */
  private def selectActiveVersion(
      connection: Connection,
      key: KnowledgeDocumentKey
  ): IO[RetrievalError, Option[Long]] =
    jdbc("select active version") {
      val statement = connection.prepareStatement(
        "SELECT index_version FROM zyblw_agent_knowledge.agent_knowledge_documents WHERE tenant_id = ? AND document_id = ? AND active = TRUE"
      )
      try
        statement.setString(1, key.tenantId.value)
        statement.setString(2, key.documentId)
        val result = statement.executeQuery()
        if result.next() then Some(result.getLong(1)) else None
      finally statement.close()
    }

  /** 计算文档下一递增版本；文档 advisory lock 保证并发安全。 */
  private def nextVersion(connection: Connection, key: KnowledgeDocumentKey): IO[RetrievalError, Long] =
    jdbc("next version") {
      val statement = connection.prepareStatement(
        "SELECT COALESCE(max(index_version), 0) + 1 FROM zyblw_agent_knowledge.agent_knowledge_documents WHERE tenant_id = ? AND document_id = ?"
      )
      try
        statement.setString(1, key.tenantId.value)
        statement.setString(2, key.documentId)
        val result = statement.executeQuery()
        if !result.next() then throw IllegalStateException("next version returned no row")
        result.getLong(1)
      finally statement.close()
    }

  /** 按 ingestionId 查询 manifest；`forUpdate` 只允许在显式事务中使用。 */
  private def selectByIngestion(
      connection: Connection,
      key: KnowledgeDocumentKey,
      ingestionId: String,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    val lock = if forUpdate then " FOR UPDATE" else ""
    selectOne(
      connection,
      s"$manifestSelect WHERE tenant_id = ? AND document_id = ? AND ingestion_id = ?$lock",
      statement =>
        statement.setString(1, key.tenantId.value)
        statement.setString(2, key.documentId)
        statement.setString(3, ingestionId)
    )

  /** 按完整构建键查询 manifest。 */
  private def selectExact(
      connection: Connection,
      build: KnowledgeIndexBuild,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    val lock = if forUpdate then " FOR UPDATE" else ""
    selectOne(
      connection,
      s"$manifestSelect WHERE tenant_id = ? AND document_id = ? AND index_version = ?$lock",
      statement =>
        statement.setString(1, build.key.tenantId.value)
        statement.setString(2, build.key.documentId)
        statement.setLong(3, build.version)
    )

  /** 按 key/version 查询 manifest，供下线幂等恢复使用。 */
  private def selectVersion(
      connection: Connection,
      key: KnowledgeDocumentKey,
      version: Long,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    val lock = if forUpdate then " FOR UPDATE" else ""
    selectOne(
      connection,
      s"$manifestSelect WHERE tenant_id = ? AND document_id = ? AND index_version = ?$lock",
      statement =>
        statement.setString(1, key.tenantId.value)
        statement.setString(2, key.documentId)
        statement.setLong(3, version)
    )

  /** 查询并可锁定当前 active manifest。 */
  private def selectActiveManifest(
      connection: Connection,
      key: KnowledgeDocumentKey,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    val lock = if forUpdate then " FOR UPDATE" else ""
    selectOne(
      connection,
      s"$manifestSelect WHERE tenant_id = ? AND document_id = ? AND active = TRUE$lock",
      statement =>
        statement.setString(1, key.tenantId.value)
        statement.setString(2, key.documentId)
    )

  /** 删除某租户文档当前正式块；调用者已持有文档 advisory lock。 */
  private def deletePublished(connection: Connection, key: KnowledgeDocumentKey): IO[RetrievalError, Unit] =
    jdbc("delete published chunks") {
      val statement = connection.prepareStatement(
        "DELETE FROM zyblw_agent_knowledge.agent_knowledge_chunks WHERE tenant_id = ? AND document_id = ?"
      )
      try
        statement.setString(1, key.tenantId.value)
        statement.setString(2, key.documentId)
        statement.executeUpdate()
        ()
      finally statement.close()
    }

  /** 通用单行 manifest 查询，集中维护 ResultSet 解码顺序。 */
  private def selectOne(
      connection: Connection,
      sql: String,
      bind: java.sql.PreparedStatement => Unit
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] = jdbc("select manifest") {
    val statement = connection.prepareStatement(sql)
    try
      bind(statement)
      val result = statement.executeQuery()
      if result.next() then Some(readManifest(result)) else None
    finally statement.close()
  }

  /** 把数据库行解码为类型化 manifest；未知状态或脏 metadata 会作为协议错误失败。 */
  private def readManifest(result: ResultSet): KnowledgeIndexManifest =
    val key        = KnowledgeDocumentKey(TenantId(result.getString(1)), result.getString(2))
    val descriptor = EmbeddingProviderDescriptor(
      result.getString(9),
      result.getString(10),
      result.getInt(11),
      result.getInt(12),
      result.getBoolean(13)
    )
    val build = KnowledgeIndexBuild(
      key,
      result.getLong(3),
      result.getString(4),
      result.getString(6),
      descriptor,
      result.getString(14)
    )
    val permissions = result.getArray(7).getArray.asInstanceOf[Array[AnyRef]].iterator.map(_.toString).toSet
    val metadata    = result
      .getString(8)
      .fromJson[Map[String, String]]
      .fold(
        error => throw IllegalStateException(s"knowledge manifest metadata 解码失败: $error"),
        identity
      )
    val status = result.getString(15) match
      case "building"   => KnowledgeIndexStatus.Building
      case "ready"      => KnowledgeIndexStatus.Ready
      case "superseded" => KnowledgeIndexStatus.Superseded
      case "failed"     => KnowledgeIndexStatus.Failed
      case "retired"    => KnowledgeIndexStatus.Retired
      case other        => throw IllegalStateException(s"未知 knowledge status: $other")
    KnowledgeIndexManifest(
      build,
      result.getString(5),
      permissions,
      metadata,
      status,
      result.getBoolean(16),
      result.getInt(17),
      Option(result.getString(18)),
      result.getTimestamp(19).toInstant,
      result.getTimestamp(20).toInstant
    )

  /** 同 ingestionId 的所有不可变字段都必须一致。 */
  private def sameRequest(manifest: KnowledgeIndexManifest, request: BeginKnowledgeIndex): Boolean =
    manifest.build.contentHash == request.contentHash &&
      manifest.build.embedding == request.embedding &&
      manifest.build.indexingStrategy == request.indexingStrategy &&
      manifest.sourceUri == request.sourceUri &&
      manifest.permissions == request.permissions &&
      manifest.metadata == request.metadata

  /** 防止调用方用同版本号但不同内容伪造 build。 */
  private def ensureSameBuild(
      actual: KnowledgeIndexBuild,
      supplied: KnowledgeIndexBuild
  ): IO[RetrievalError, Unit] =
    if actual == supplied then ZIO.unit
    else ZIO.fail(AgentError.RetrievalFailed("knowledge build 内容与存储不一致"))

  /** 校验 active 乐观前置条件。 */
  private def matchesExpectation(expectation: ActiveVersionExpectation, active: Option[Long]): Boolean =
    expectation match
      case ActiveVersionExpectation.AnyVersion      => true
      case ActiveVersionExpectation.NoActiveVersion => active.isEmpty
      case ActiveVersionExpectation.Exact(version)  => active.contains(version)

  /** 将 Float 向量编码为 pgvector 的受控文本输入格式。 */
  private def vectorLiteral(embedding: Embedding): String = embedding.values.mkString("[", ",", "]")

  /** 从宿主连接池按 Scope 借还连接；关闭失败不覆盖主要业务结果。
    * @param use
    *   单次短数据库操作或短事务
    */
  private def withConnection[A](use: Connection => IO[RetrievalError, A]): IO[RetrievalError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(jdbc("acquire connection")(dataSource.getConnection))(connection =>
          ZIO.attemptBlocking(connection.close()).ignore
        )
        .flatMap(use)
    }

  /** 运行可中断事务：业务阶段可被取消，commit/rollback 与 autoCommit 恢复位于不可中断边界。
    */
  private def withTransaction[A](use: Connection => IO[RetrievalError, A]): IO[RetrievalError, A] =
    withConnection { connection =>
      for
        previous <- jdbc("read auto commit")(connection.getAutoCommit)
        _        <- jdbc("begin transaction")(connection.setAutoCommit(false))
        result   <- ZIO
          .uninterruptibleMask { restore =>
            restore(use(connection)).exit.flatMap {
              case Exit.Success(value) => jdbc("commit transaction")(connection.commit()).as(value)
              case Exit.Failure(cause) =>
                jdbc("rollback transaction")(connection.rollback()).ignore *> ZIO.refailCause(cause)
            }
          }
          .ensuring(jdbc("restore auto commit")(connection.setAutoCommit(previous)).ignore)
      yield result
    }

  /** 在 blocking executor 执行 JDBC，并仅暴露 SQLSTATE 分类，不记录 SQL 参数或正文。 */
  private def jdbc[A](operation: String)(effect: => A): IO[RetrievalError, A] =
    ZIO.attemptBlocking(effect).mapError(error => databaseError(operation, error))

  /** 08/40/53 与数据库重启 SQLSTATE 可重试；约束和协议错误保持不可重试。 */
  private def databaseError(operation: String, error: Throwable): RetrievalError =
    val sqlState = error match
      case sql: SQLException => Option(sql.getSQLState).getOrElse("unknown")
      case _                 => "not-sql"
    val retryable = sqlState.startsWith("08") || sqlState.startsWith("40") || sqlState.startsWith("53") ||
      Set("57P01", "57P02", "57P03").contains(sqlState)
    AgentError.RetrievalFailed(s"PostgreSQL knowledge $operation 失败 (sqlState=$sqlState)", retryable)

  /** ResultSet 列顺序的唯一来源；修改 migration 字段时应同步更新 `readManifest`。 */
  private val manifestSelect =
    """SELECT tenant_id, document_id, index_version, ingestion_id, source_uri, content_hash,
      |       permissions, metadata::text, embedding_provider, embedding_model, embedding_dimension,
      |       embedding_max_batch_size, embedding_supports_dimensions, indexing_strategy,
      |       status, active, chunk_count, failure_code, created_at, updated_at
      |FROM zyblw_agent_knowledge.agent_knowledge_documents""".stripMargin

object PostgresKnowledgeIndexStore:
  /** 构造与 optional migration 固定维度一致的 Store Layer。 */
  def layer(dimension: Int): URLayer[DataSource, KnowledgeIndexStore] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresKnowledgeIndexStore(dataSource, dimension))
