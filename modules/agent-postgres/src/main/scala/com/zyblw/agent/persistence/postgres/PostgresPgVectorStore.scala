package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.sql.Connection
import javax.sql.DataSource
import zio.*
import zio.json.*

/** 基于 PostgreSQL/pgvector 的多租户向量库。
  *
  * `tenant_id` 与 `permissions <@ caller_permissions` 在 SQL 中先于向量排序生效，未授权文档不会进入候选结果。 Adapter 只依赖 JDBC
  * `DataSource`，连接池由宿主应用（例如 HikariCP）统一配置，框架不会私建第二个连接池。
  *
  * @param dataSource
  *   宿主提供的 PostgreSQL DataSource
  * @param dimension
  *   当前知识表声明的固定向量维度，必须与 optional migration 和 Embedding Provider 一致
  * @param hybridConfig
  *   全文候选、向量候选、RRF 权重和 pgvector 迭代扫描配置
  */
final class PostgresPgVectorStore(
    dataSource: DataSource,
    dimension: Int,
    hybridConfig: PostgresHybridSearchConfig = PostgresHybridSearchConfig()
) extends VectorStore:
  require(dimension > 0, "pgvector dimension 必须为正数")

  private val chunkSelectColumns: String =
    """chunk_id, document_id, chunk_text, search_text, source_uri, permissions, metadata::text, index_version,
      |       parent_id, lineage_ordinal, previous_chunk_id, next_chunk_id, heading_path,
      |       page_numbers, origins::text, block_ids, knowledge_space_id, profile_id""".stripMargin

  private def visibilitySql(relation: String = "agent_knowledge_profile_chunks"): String =
    s"""AND $relation.knowledge_space_id = ?
       |AND $relation.profile_id = COALESCE(?, (
       |  SELECT s.active_profile_id FROM zyblw_agent_knowledge.agent_knowledge_spaces s
       |  WHERE s.tenant_id = $relation.tenant_id
       |    AND s.knowledge_space_id = $relation.knowledge_space_id
       |), 'default')
       |AND NOT EXISTS (
       |  SELECT 1 FROM zyblw_agent_knowledge.agent_knowledge_withdrawn w
       |  WHERE w.tenant_id = $relation.tenant_id
       |    AND w.document_id = $relation.document_id
       |)""".stripMargin

  private def bindVisibility(
      statement: java.sql.PreparedStatement,
      start: Int,
      scope: RetrievalScope
  ): Int =
    statement.setString(start, scope.spaceId.value)
    scope.pinnedProfileId match
      case Some(id) => statement.setString(start + 1, id.value)
      case None     => statement.setNull(start + 1, java.sql.Types.VARCHAR)
    start + 2

  /** 批量 upsert 文档块。
    *
    * @param chunks
    *   已包含正文、租户、权限、metadata 与 embedding 的块
    * @return
    *   批次全部成功时完成；任一向量维度错误会在取得数据库连接前失败
    */
  def upsert(chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit] =
    ZIO.foreachDiscard(chunks)(validateDimension) *> withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """INSERT INTO zyblw_agent_knowledge.agent_knowledge_profile_chunks
            |(tenant_id, knowledge_space_id, profile_id, chunk_id, document_id, index_version, chunk_text, search_text, source_uri, permissions, metadata,
            | embedding, parent_id, lineage_ordinal, previous_chunk_id, next_chunk_id, heading_path,
            | page_numbers, origins, block_ids)
            |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::public.vector, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            |ON CONFLICT (tenant_id, knowledge_space_id, profile_id, document_id, chunk_id) DO UPDATE SET
            |index_version = EXCLUDED.index_version,
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
            statement.setString(1, chunk.tenantId.value)
            statement.setString(2, chunk.knowledgeSpaceId.getOrElse(KnowledgeSpaceId("default")).value)
            statement.setString(3, chunk.profileId.getOrElse(IndexProfileId("default")).value)
            statement.setString(4, chunk.id)
            statement.setString(5, chunk.documentId)
            statement.setLong(6, chunk.indexVersion)
            statement.setString(7, chunk.displayText)
            statement.setString(8, chunk.searchText.getOrElse(chunk.displayText))
            statement.setString(9, chunk.sourceUri)
            statement.setArray(10, connection.createArrayOf("text", chunk.permissions.toArray))
            statement.setString(11, chunk.metadata.toJson)
            statement.setString(12, vectorLiteral(indexed.embedding))
            bindLineage(statement, connection, 13, chunk.lineage)
            statement.addBatch()
          }
          statement.executeBatch()
          ()
        finally statement.close()
      }
    }

  /** 在授权边界内执行 cosine 相似度查询。
    *
    * @param query
    *   查询向量，维度必须与表一致
    * @param scope
    *   可信租户与调用者权限集合，不能从模型输出构造
    * @param limit
    *   最大命中数；非正数直接返回空集合
    */
  def search(query: Embedding, scope: RetrievalScope, limit: Int): IO[RetrievalError, Chunk[RetrievalHit]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else
      validateQueryDimension(query) *> withConnection { connection =>
        ZIO.attemptBlocking {
          val sql =
            s"""SELECT $chunkSelectColumns,
            |       1 - (embedding <=> ?::public.vector) AS score
            |FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
            |WHERE tenant_id = ? AND permissions <@ ?::text[]
            |${visibilitySql()}
            |ORDER BY embedding <=> ?::public.vector
            |LIMIT ?""".stripMargin
          val statement = connection.prepareStatement(sql)
          try
            val vector = vectorLiteral(query)
            statement.setString(1, vector)
            statement.setString(2, scope.tenantId.value)
            statement.setArray(3, connection.createArrayOf("text", scope.permissions.toArray))
            val next = bindVisibility(statement, 4, scope)
            statement.setString(next, vector)
            statement.setInt(next + 1, limit)
            val result  = statement.executeQuery()
            val builder = ChunkBuilder.make[RetrievalHit]()
            while result.next() do
              builder += RetrievalHit(readChunk(result, scope), result.getDouble("score"))
            builder.result()
          finally statement.close()
        }
      }

  /** 在一条 SQL 中获取向量候选与 PostgreSQL FTS 候选，再以加权 Reciprocal Rank Fusion 合并。
    *
    * 两个候选 CTE 都先应用 `tenant_id` 与 `permissions <@ caller_permissions`；未授权行不会进入向量距离、 全文排名或 RRF。RRF
    * 使用名次而不是直接混合不可比的 cosine/ts_rank 数值，并以 chunk_id 作为稳定 tie-breaker，保证回放和 eval 顺序确定。
    *
    * @param queryText
    *   原始检索文本；`websearch_to_tsquery` 能安全接受普通用户输入
    * @param query
    *   与知识索引同维度的查询向量
    * @param scope
    *   认证层提供的租户和权限集合
    * @param limit
    *   最终命中数；候选池由 hybridConfig 独立放大
    */
  override def searchHybrid(
      queryText: String,
      query: Embedding,
      scope: RetrievalScope,
      limit: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else if queryText.trim.isEmpty then search(query, scope, limit)
    else
      validateQueryDimension(query) *> withConnection { connection =>
        ZIO.attemptBlocking {
          val previousAutoCommit = connection.getAutoCommit
          connection.setAutoCommit(false)
          try
            if hybridConfig.enableHnswIterativeScan then
              val setting = connection.createStatement()
              try
                setting.execute("SET LOCAL hnsw.iterative_scan = strict_order")
                ()
              finally setting.close()

            val sql =
              s"""WITH search_query AS (
              |  SELECT websearch_to_tsquery(?::regconfig, ?) AS value
              |),
              |vector_hits AS MATERIALIZED (
              |  SELECT document_id, chunk_id,
              |         row_number() OVER (ORDER BY embedding <=> ?::public.vector, document_id, chunk_id) AS vector_rank,
              |         1 - (embedding <=> ?::public.vector) AS vector_score
              |  FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
              |  WHERE tenant_id = ? AND permissions <@ ?::text[]
              |  ${visibilitySql()}
              |  ORDER BY embedding <=> ?::public.vector, document_id, chunk_id
              |  LIMIT ?
              |),
              |text_hits AS MATERIALIZED (
              |  SELECT document_id, chunk_id,
              |         row_number() OVER (
              |           ORDER BY ts_rank_cd(search_vector, search_query.value, 32) DESC, document_id, chunk_id
              |         ) AS text_rank,
              |         ts_rank_cd(search_vector, search_query.value, 32) AS text_score
              |  FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks CROSS JOIN search_query
              |  WHERE tenant_id = ?
              |    AND permissions <@ ?::text[]
              |    ${visibilitySql()}
              |    AND search_vector @@ search_query.value
              |  ORDER BY text_score DESC, document_id, chunk_id
              |  LIMIT ?
              |),
              |ranks AS (
              |  SELECT COALESCE(vector_hits.document_id, text_hits.document_id) AS document_id,
              |         COALESCE(vector_hits.chunk_id, text_hits.chunk_id) AS chunk_id,
              |         vector_rank, text_rank, vector_score, text_score
              |  FROM vector_hits FULL OUTER JOIN text_hits USING (document_id, chunk_id)
              |),
              |fused AS (
              |  SELECT *,
              |         CASE WHEN vector_rank IS NULL THEN 0.0
              |              ELSE ?::double precision / (?::double precision + vector_rank) END +
              |         CASE WHEN text_rank IS NULL THEN 0.0
              |              ELSE ?::double precision / (?::double precision + text_rank) END AS fused_score
              |  FROM ranks
              |)
              |SELECT c.chunk_id, c.document_id, c.chunk_text, c.search_text, c.source_uri,
              |       c.permissions, c.metadata::text, f.fused_score,
              |       f.vector_score, f.text_score, f.vector_rank, f.text_rank, c.index_version,
              |       c.parent_id, c.lineage_ordinal, c.previous_chunk_id, c.next_chunk_id,
              |       c.heading_path, c.page_numbers, c.origins::text, c.block_ids,
              |       c.knowledge_space_id, c.profile_id
              |FROM fused f
              |JOIN zyblw_agent_knowledge.agent_knowledge_profile_chunks c
              |  ON c.tenant_id = ? AND c.document_id = f.document_id AND c.chunk_id = f.chunk_id
              |${visibilitySql("c")}
              |ORDER BY f.fused_score DESC, c.document_id, c.chunk_id
              |LIMIT ?""".stripMargin
            val statement = connection.prepareStatement(sql)
            try
              val vector      = vectorLiteral(query)
              val permissions = connection.createArrayOf("text", scope.permissions.toArray)
              statement.setString(1, hybridConfig.textSearchConfig)
              statement.setString(2, queryText)
              statement.setString(3, vector)
              statement.setString(4, vector)
              statement.setString(5, scope.tenantId.value)
              statement.setArray(6, permissions)
              var next = bindVisibility(statement, 7, scope)
              statement.setString(next, vector)
              statement.setInt(next + 1, hybridConfig.vectorCandidateCount(limit))
              statement.setString(next + 2, scope.tenantId.value)
              statement.setArray(next + 3, permissions)
              next = bindVisibility(statement, next + 4, scope)
              statement.setInt(next, hybridConfig.textCandidateCount(limit))
              statement.setDouble(next + 1, hybridConfig.vectorWeight)
              statement.setDouble(next + 2, hybridConfig.rrfK)
              statement.setDouble(next + 3, hybridConfig.textWeight)
              statement.setDouble(next + 4, hybridConfig.rrfK)
              statement.setString(next + 5, scope.tenantId.value)
              next = bindVisibility(statement, next + 6, scope)
              statement.setInt(next, limit)
              val result  = statement.executeQuery()
              val builder = ChunkBuilder.make[RetrievalHit]()
              while result.next() do
                val chunk   = readChunk(result, scope)
                val signals = Map.newBuilder[String, Double]
                Option(result.getObject(9)).foreach(_ => signals += "vectorScore" -> result.getDouble(9))
                Option(result.getObject(10)).foreach(_ => signals += "textScore" -> result.getDouble(10))
                Option(result.getObject(11)).foreach(_ => signals += "vectorRank" -> result.getDouble(11))
                Option(result.getObject(12)).foreach(_ => signals += "textRank" -> result.getDouble(12))
                builder += RetrievalHit(chunk, result.getDouble(8), signals.result())
              connection.commit()
              builder.result()
            finally statement.close()
          catch
            case error: Throwable =>
              connection.rollback()
              throw error
          finally connection.setAutoCommit(previousAutoCommit)
        }
      }

  override def searchFiltered(
      mode: RetrievalMode,
      queryText: String,
      query: Embedding,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      limit: Int,
      sparseQuery: Option[SparseEmbedding] = None
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else if filter.isEmpty && mode == RetrievalMode.VectorOnly then search(query, scope, limit)
    else if filter.isEmpty && mode == RetrievalMode.Hybrid then
      searchHybrid(queryText, query, scope, limit).flatMap { fused =>
        fuseSparse(fused, sparseQuery, scope, limit)
      }
    else
      mode match
        case RetrievalMode.Hybrid if queryText.trim.nonEmpty =>
          filteredHybrid(queryText, query, scope, filter, limit)
        case RetrievalMode.LexicalOnly =>
          filteredLexical(queryText, scope, filter, limit)
        case RetrievalMode.Phrase =>
          filteredPhrase(queryText, scope, filter, limit)
        case RetrievalMode.Hybrid | RetrievalMode.VectorOnly =>
          filteredVector(query, scope, filter, limit)

  override def fetchChunks(
      chunkIds: Set[String],
      scope: RetrievalScope
  ): IO[RetrievalError, Chunk[DocumentChunk]] =
    if chunkIds.isEmpty then ZIO.succeed(Chunk.empty)
    else
      withConnection { connection =>
        ZIO.attemptBlocking {
          val statement = connection.prepareStatement(
            s"""SELECT $chunkSelectColumns
              |FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
              |WHERE tenant_id = ? AND permissions <@ ?::text[] AND chunk_id = ANY(?)
              |${visibilitySql()}
              |ORDER BY chunk_id""".stripMargin
          )
          try
            statement.setString(1, scope.tenantId.value)
            statement.setArray(2, connection.createArrayOf("text", scope.permissions.toArray))
            statement.setArray(3, connection.createArrayOf("text", chunkIds.toArray))
            bindVisibility(statement, 4, scope)
            val result  = statement.executeQuery()
            val builder = ChunkBuilder.make[DocumentChunk]()
            while result.next() do builder += readChunk(result, scope)
            builder.result()
          finally statement.close()
        }
      }

  /** 在 rerank 之后读取相邻块与同父级块。SQL 重新应用 tenant/permission，扩展行不会因为种子命中已授权就被隐式信任。 */
  override def expandContext(
      seeds: Chunk[RetrievalHit],
      scope: RetrievalScope,
      config: RetrievalExpansionConfig
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    if seeds.isEmpty || config.maxAdditionalChunks == 0 then ZIO.succeed(Chunk.empty)
    else
      val seedKeys = seeds.map(hit => hit.chunk.documentId -> hit.chunk.id).toSet
      val neighborScores: Map[(String, String), Double] =
        if config.neighborRadius == 0 then Map.empty
        else
          seeds
            .flatMap(seed =>
              seed.chunk.lineage
                .fold(Chunk.empty[String])(lineage =>
                  Chunk.fromIterable(lineage.previousChunkId) ++ Chunk.fromIterable(lineage.nextChunkId)
                )
                .map(id => (seed.chunk.documentId -> id) -> seed.score)
            )
            .toList
            .groupMapReduce(_._1)(_._2)(math.max)
      val parentScores: Map[(String, String), Double] = seeds
        .flatMap(hit =>
          hit.chunk.lineage
            .flatMap(_.parentId)
            .map(parentId => (hit.chunk.documentId -> parentId) -> hit.score)
        )
        .groupBy(_._1)
        .collect {
          case (parentKey, values) if values.length >= config.parentHitThreshold =>
            parentKey -> values.map(_._2).max
        }
      if neighborScores.isEmpty && parentScores.isEmpty then ZIO.succeed(Chunk.empty)
      else
        withConnection { connection =>
          ZIO.attemptBlocking {
            val sql =
              s"""WITH seed_key AS (
                |  SELECT * FROM unnest(?::text[], ?::text[]) AS key(document_id, chunk_id)
                |), neighbor_key AS (
                |  SELECT * FROM unnest(?::text[], ?::text[]) AS key(document_id, chunk_id)
                |), parent_key AS (
                |  SELECT * FROM unnest(?::text[], ?::text[]) AS key(document_id, parent_id)
                |)
                |SELECT $chunkSelectColumns
                |FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks chunk
                |WHERE tenant_id = ?
                |  AND permissions <@ ?::text[]
                |  ${visibilitySql("chunk")}
                |  AND NOT EXISTS (
                |    SELECT 1 FROM seed_key
                |    WHERE seed_key.document_id = chunk.document_id AND seed_key.chunk_id = chunk.chunk_id
                |  )
                |  AND (
                |    EXISTS (
                |      SELECT 1 FROM neighbor_key
                |      WHERE neighbor_key.document_id = chunk.document_id AND neighbor_key.chunk_id = chunk.chunk_id
                |    )
                |    OR EXISTS (
                |      SELECT 1 FROM parent_key
                |      WHERE parent_key.document_id = chunk.document_id
                |        AND parent_key.parent_id = chunk.parent_id
                |    )
                |  )
                |ORDER BY CASE WHEN EXISTS (
                |           SELECT 1 FROM neighbor_key
                |           WHERE neighbor_key.document_id = chunk.document_id
                |             AND neighbor_key.chunk_id = chunk.chunk_id
                |         ) THEN 0 ELSE 1 END,
                |         parent_id NULLS LAST, lineage_ordinal NULLS LAST, chunk_id
                |LIMIT ?""".stripMargin
            val statement = connection.prepareStatement(sql)
            try
              val seeds     = seedKeys.toVector.sorted
              val neighbors = neighborScores.keys.toVector.sorted
              val parents   = parentScores.keys.toVector.sorted
              statement.setArray(1, connection.createArrayOf("text", seeds.map(_._1).toArray))
              statement.setArray(2, connection.createArrayOf("text", seeds.map(_._2).toArray))
              statement.setArray(3, connection.createArrayOf("text", neighbors.map(_._1).toArray))
              statement.setArray(4, connection.createArrayOf("text", neighbors.map(_._2).toArray))
              statement.setArray(5, connection.createArrayOf("text", parents.map(_._1).toArray))
              statement.setArray(6, connection.createArrayOf("text", parents.map(_._2).toArray))
              statement.setString(7, scope.tenantId.value)
              statement.setArray(8, connection.createArrayOf("text", scope.permissions.toArray))
              val vis = bindVisibility(statement, 9, scope)
              statement.setInt(vis, (config.maxAdditionalChunks * 4).max(config.maxAdditionalChunks))
              val result = statement.executeQuery()
              val found  = Vector.newBuilder[DocumentChunk]
              while result.next() do found += readChunk(result, scope)
              val authorized   = found.result()
              val neighborHits = authorized
                .flatMap(chunk =>
                  neighborScores
                    .get(chunk.documentId -> chunk.id)
                    .map(score => expandedHit(chunk, score, "neighbor", config))
                )
              val neighborKeys = neighborHits.map(hit => hit.chunk.documentId -> hit.chunk.id).toSet
              val siblingHits  = parentScores.toVector.sortBy(_._1).flatMap { case (parentKey, score) =>
                authorized
                  .filter(chunk =>
                    chunk.documentId == parentKey._1 && chunk.lineage
                      .flatMap(_.parentId)
                      .contains(parentKey._2)
                  )
                  .filterNot(chunk => neighborKeys.contains(chunk.documentId -> chunk.id))
                  .sortBy(chunk => (chunk.lineage.fold(Int.MaxValue)(_.ordinal), chunk.id))
                  .take(config.maxSiblingsPerParent)
                  .map(chunk => expandedHit(chunk, score, "parentSibling", config))
              }
              Chunk.fromIterable(
                (neighborHits ++ siblingHits)
                  .distinctBy(hit => hit.chunk.documentId -> hit.chunk.id)
                  .take(config.maxAdditionalChunks)
              )
            finally statement.close()
          }
        }

  /** 删除一个租户下指定原始文档的所有块。
    * @param documentId
    *   原始文档稳定 ID
    * @param tenantId
    *   强制租户边界；相同 documentId 在其他租户中的数据不会受影响
    */
  def deleteByDocument(documentId: String, tenantId: TenantId): IO[RetrievalError, Unit] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          "DELETE FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks WHERE tenant_id = ? AND document_id = ?"
        )
        try
          statement.setString(1, tenantId.value)
          statement.setString(2, documentId)
          statement.executeUpdate()
          ()
        finally statement.close()
      }
    }

  private val FilterSql: String =
    """AND (cardinality(?::text[]) = 0 OR document_id = ANY(?))
      |AND (cardinality(?::text[]) = 0 OR chunk_id = ANY(?))
      |AND (cardinality(?::int[]) = 0 OR page_numbers && ?)
      |AND (cardinality(?::text[]) = 0 OR heading_path[1:cardinality(?::text[])] = ?)
      |AND (?::jsonb = '{}'::jsonb OR metadata @> ?::jsonb)""".stripMargin

  private def bindFilter(
      statement: java.sql.PreparedStatement,
      connection: Connection,
      start: Int,
      filter: RetrievalFilter
  ): Int =
    val documents = connection.createArrayOf("text", filter.documentIds.toArray)
    val chunks    = connection.createArrayOf("text", filter.chunkIds.toArray)
    val pages     = connection.createArrayOf("integer", filter.pages.map(Int.box).toArray)
    val headings  = connection.createArrayOf("text", filter.headingPrefix.toArray)
    val metadata  = filter.metadataEquals.toJson
    statement.setArray(start, documents)
    statement.setArray(start + 1, documents)
    statement.setArray(start + 2, chunks)
    statement.setArray(start + 3, chunks)
    statement.setArray(start + 4, pages)
    statement.setArray(start + 5, pages)
    statement.setArray(start + 6, headings)
    statement.setArray(start + 7, headings)
    statement.setArray(start + 8, headings)
    statement.setString(start + 9, metadata)
    statement.setString(start + 10, metadata)
    start + 11

  private def filteredVector(
      query: Embedding,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      limit: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    validateQueryDimension(query) *> withConnection { connection =>
      ZIO.attemptBlocking {
        val sql =
          s"""SELECT $chunkSelectColumns,
             |       1 - (embedding <=> ?::public.vector) AS score
             |FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
             |WHERE tenant_id = ? AND permissions <@ ?::text[]
             |${visibilitySql()}
             |$FilterSql
             |ORDER BY embedding <=> ?::public.vector
             |LIMIT ?""".stripMargin
        val statement = connection.prepareStatement(sql)
        try
          val vector = vectorLiteral(query)
          statement.setString(1, vector)
          statement.setString(2, scope.tenantId.value)
          statement.setArray(3, connection.createArrayOf("text", scope.permissions.toArray))
          val vis  = bindVisibility(statement, 4, scope)
          val next = bindFilter(statement, connection, vis, filter)
          statement.setString(next, vector)
          statement.setInt(next + 1, limit)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[RetrievalHit]()
          while result.next() do builder += RetrievalHit(readChunk(result, scope), result.getDouble("score"))
          builder.result()
        finally statement.close()
      }
    }

  private def filteredLexical(
      queryText: String,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      limit: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val sql =
          s"""SELECT $chunkSelectColumns,
             |       ts_rank_cd(search_vector, websearch_to_tsquery(?::regconfig, ?), 32) AS score
             |FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
             |WHERE tenant_id = ? AND permissions <@ ?::text[]
             |  AND search_vector @@ websearch_to_tsquery(?::regconfig, ?)
             |${visibilitySql()}
             |$FilterSql
             |ORDER BY score DESC, document_id, chunk_id
             |LIMIT ?""".stripMargin
        val statement = connection.prepareStatement(sql)
        try
          statement.setString(1, hybridConfig.textSearchConfig)
          statement.setString(2, queryText)
          statement.setString(3, scope.tenantId.value)
          statement.setArray(4, connection.createArrayOf("text", scope.permissions.toArray))
          statement.setString(5, hybridConfig.textSearchConfig)
          statement.setString(6, queryText)
          val vis  = bindVisibility(statement, 7, scope)
          val next = bindFilter(statement, connection, vis, filter)
          statement.setInt(next, limit)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[RetrievalHit]()
          while result.next() do
            val score = result.getDouble("score")
            builder += RetrievalHit(readChunk(result, scope), score, Map("textScore" -> score))
          builder.result()
        finally statement.close()
      }
    }

  private def filteredPhrase(
      queryText: String,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      limit: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val sql =
          s"""SELECT $chunkSelectColumns,
             |       similarity(search_text, ?) AS score
             |FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
             |WHERE tenant_id = ? AND permissions <@ ?::text[]
             |  AND search_text % ?
             |${visibilitySql()}
             |$FilterSql
             |ORDER BY score DESC, document_id, chunk_id
             |LIMIT ?""".stripMargin
        val statement = connection.prepareStatement(sql)
        try
          statement.setString(1, queryText)
          statement.setString(2, scope.tenantId.value)
          statement.setArray(3, connection.createArrayOf("text", scope.permissions.toArray))
          statement.setString(4, queryText)
          val vis  = bindVisibility(statement, 5, scope)
          val next = bindFilter(statement, connection, vis, filter)
          statement.setInt(next, limit)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[RetrievalHit]()
          while result.next() do
            val score = result.getDouble("score")
            builder += RetrievalHit(readChunk(result, scope), score, Map("phraseScore" -> score))
          builder.result()
        finally statement.close()
      }
    }

  private def filteredHybrid(
      queryText: String,
      query: Embedding,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      limit: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    validateQueryDimension(query) *> withConnection { connection =>
      ZIO.attemptBlocking {
        val sql =
          s"""WITH search_query AS (
             |  SELECT websearch_to_tsquery(?::regconfig, ?) AS value
             |),
             |vector_hits AS MATERIALIZED (
             |  SELECT document_id, chunk_id,
             |         row_number() OVER (ORDER BY embedding <=> ?::public.vector, document_id, chunk_id) AS vector_rank,
             |         1 - (embedding <=> ?::public.vector) AS vector_score
             |  FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
             |  WHERE tenant_id = ? AND permissions <@ ?::text[]
             |  ${visibilitySql()}
             |  $FilterSql
             |  ORDER BY embedding <=> ?::public.vector, document_id, chunk_id
             |  LIMIT ?
             |),
             |text_hits AS MATERIALIZED (
             |  SELECT document_id, chunk_id,
             |         row_number() OVER (
             |           ORDER BY ts_rank_cd(search_vector, search_query.value, 32) DESC, document_id, chunk_id
             |         ) AS text_rank,
             |         ts_rank_cd(search_vector, search_query.value, 32) AS text_score
             |  FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks CROSS JOIN search_query
             |  WHERE tenant_id = ?
             |    AND permissions <@ ?::text[]
             |    ${visibilitySql()}
             |    AND search_vector @@ search_query.value
             |    $FilterSql
             |  ORDER BY text_score DESC, document_id, chunk_id
             |  LIMIT ?
             |),
             |ranks AS (
             |  SELECT COALESCE(vector_hits.document_id, text_hits.document_id) AS document_id,
             |         COALESCE(vector_hits.chunk_id, text_hits.chunk_id) AS chunk_id,
             |         vector_rank, text_rank, vector_score, text_score
             |  FROM vector_hits FULL OUTER JOIN text_hits USING (document_id, chunk_id)
             |),
             |fused AS (
             |  SELECT *,
             |         CASE WHEN vector_rank IS NULL THEN 0.0
             |              ELSE ?::double precision / (?::double precision + vector_rank) END +
             |         CASE WHEN text_rank IS NULL THEN 0.0
             |              ELSE ?::double precision / (?::double precision + text_rank) END AS fused_score
             |  FROM ranks
             |)
             |SELECT c.chunk_id, c.document_id, c.chunk_text, c.search_text, c.source_uri,
             |       c.permissions, c.metadata::text, f.fused_score,
             |       f.vector_score, f.text_score, f.vector_rank, f.text_rank, c.index_version,
             |       c.parent_id, c.lineage_ordinal, c.previous_chunk_id, c.next_chunk_id,
             |       c.heading_path, c.page_numbers, c.origins::text, c.block_ids,
             |       c.knowledge_space_id, c.profile_id
             |FROM fused f
             |JOIN zyblw_agent_knowledge.agent_knowledge_profile_chunks c
             |  ON c.tenant_id = ? AND c.document_id = f.document_id AND c.chunk_id = f.chunk_id
             |${visibilitySql("c")}
             |ORDER BY f.fused_score DESC, c.document_id, c.chunk_id
             |LIMIT ?""".stripMargin
        val statement = connection.prepareStatement(sql)
        try
          val vector      = vectorLiteral(query)
          val permissions = connection.createArrayOf("text", scope.permissions.toArray)
          statement.setString(1, hybridConfig.textSearchConfig)
          statement.setString(2, queryText)
          statement.setString(3, vector)
          statement.setString(4, vector)
          statement.setString(5, scope.tenantId.value)
          statement.setArray(6, permissions)
          var next = bindVisibility(statement, 7, scope)
          next = bindFilter(statement, connection, next, filter)
          statement.setString(next, vector)
          statement.setInt(next + 1, hybridConfig.vectorCandidateCount(limit))
          statement.setString(next + 2, scope.tenantId.value)
          statement.setArray(next + 3, permissions)
          next = bindVisibility(statement, next + 4, scope)
          next = bindFilter(statement, connection, next, filter)
          statement.setInt(next, hybridConfig.textCandidateCount(limit))
          statement.setDouble(next + 1, hybridConfig.vectorWeight)
          statement.setDouble(next + 2, hybridConfig.rrfK)
          statement.setDouble(next + 3, hybridConfig.textWeight)
          statement.setDouble(next + 4, hybridConfig.rrfK)
          statement.setString(next + 5, scope.tenantId.value)
          next = bindVisibility(statement, next + 6, scope)
          statement.setInt(next, limit)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[RetrievalHit]()
          while result.next() do
            val chunk   = readChunk(result, scope)
            val signals = Map.newBuilder[String, Double]
            Option(result.getObject("vector_score")).foreach(_ =>
              signals += "vectorScore" -> result.getDouble("vector_score")
            )
            Option(result.getObject("text_score")).foreach(_ =>
              signals += "textScore" -> result.getDouble("text_score")
            )
            Option(result.getObject("vector_rank")).foreach(_ =>
              signals += "vectorRank" -> result.getDouble("vector_rank")
            )
            Option(result.getObject("text_rank")).foreach(_ =>
              signals += "textRank" -> result.getDouble("text_rank")
            )
            builder += RetrievalHit(chunk, result.getDouble("fused_score"), signals.result())
          builder.result()
        finally statement.close()
      }
    }

  private def fuseSparse(
      fused: Chunk[RetrievalHit],
      sparseQuery: Option[SparseEmbedding],
      scope: RetrievalScope,
      limit: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    if !hybridConfig.sparseEnabled || sparseQuery.isEmpty then ZIO.succeed(fused)
    else if sparseQuery.exists(_.requireWithinNnz(hybridConfig.maxSparseNnz).isLeft) then
      ZIO.fail(AgentError.RetrievalFailed("sparse NNZ 超过容量门禁"))
    else
      withConnection { connection =>
        ZIO.attemptBlocking {
          val sql =
            s"""SELECT $chunkSelectColumns, sparse_embedding
               |FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
               |WHERE tenant_id = ? AND permissions <@ ?::text[]
               |${visibilitySql()}
               |AND sparse_embedding IS NOT NULL
               |LIMIT ?""".stripMargin
          val statement = connection.prepareStatement(sql)
          try
            statement.setString(1, scope.tenantId.value)
            statement.setArray(2, connection.createArrayOf("text", scope.permissions.toArray))
            val vis = bindVisibility(statement, 3, scope)
            statement.setInt(vis, hybridConfig.sparseCandidateCount(limit))
            val result  = statement.executeQuery()
            val builder = ChunkBuilder.make[(DocumentChunk, SparseEmbedding)]()
            while result.next() do
              Option(result.getString("sparse_embedding")).flatMap(parseSparse).foreach { sparse =>
                builder += readChunk(result, scope) -> sparse
              }
            val querySparse = sparseQuery.get
            val ranked      = builder
              .result()
              .map { case (chunk, sparse) =>
                val score = RetrievalScoring.sparseScore(querySparse, sparse)
                RetrievalHit(chunk, score, Map("sparseScore" -> score))
              }
              .sortBy(hit => (-hit.score, hit.chunk.documentId, hit.chunk.id))
              .zipWithIndex
            val sparseById = ranked.map { case (hit, index) =>
              (hit.chunk.documentId, hit.chunk.id) -> (index + 1, hit)
            }.toMap
            val k      = hybridConfig.rrfK
            val merged = fused.map { hit =>
              sparseById.get(hit.chunk.documentId -> hit.chunk.id) match
                case Some((rank, sparseHit)) =>
                  hit.copy(
                    score = hit.score + hybridConfig.sparseWeight / (k + rank),
                    signals = hit.signals ++ sparseHit.signals + ("sparseRank" -> rank.toDouble)
                  )
                case None => hit
            }
            val extras = ranked.collect {
              case (hit, index)
                  if !fused.exists(existing =>
                    existing.chunk.documentId == hit.chunk.documentId && existing.chunk.id == hit.chunk.id
                  ) =>
                hit.copy(
                  score = hybridConfig.sparseWeight / (k + index + 1),
                  signals = hit.signals + ("sparseRank" -> (index + 1).toDouble)
                )
            }
            Chunk.fromIterable(
              (merged ++ extras).sortBy(hit => (-hit.score, hit.chunk.documentId, hit.chunk.id)).take(limit)
            )
          finally statement.close()
        }
      }

  private def parseSparse(text: String): Option[SparseEmbedding] =
    text.split("\\|", 2) match
      case Array(dim, rest) =>
        dim.toIntOption.flatMap { dimension =>
          val entries = Chunk.fromIterator(
            rest.split(",").iterator.filter(_.nonEmpty).flatMap { pair =>
              pair.split(":") match
                case Array(index, value) =>
                  for
                    i <- index.toIntOption
                    v <- value.toFloatOption
                  yield SparseEmbeddingEntry(i, v)
                case _ => None
            }
          )
          SparseEmbedding.validated(dimension, entries).toOption
        }
      case _ => None

  /** 校验单个待写入块的向量维度，错误中包含 chunk ID 便于定位脏数据。 */
  private def validateDimension(indexed: IndexedChunk): IO[RetrievalError, Unit] =
    if indexed.embedding.values.length == dimension then ZIO.unit
    else
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"chunk ${indexed.chunk.id} embedding 维度 ${indexed.embedding.values.length} != $dimension"
        )
      )

  /** 校验查询向量维度，避免由 PostgreSQL 抛出难以理解的 operator 错误。 */
  private def validateQueryDimension(embedding: Embedding): IO[RetrievalError, Unit] =
    if embedding.values.length == dimension then ZIO.unit
    else ZIO.fail(AgentError.RetrievalFailed(s"query embedding 维度 ${embedding.values.length} != $dimension"))

  override def assertEmbeddingIdentity(
      tenantId: TenantId,
      descriptor: EmbeddingProviderDescriptor,
      spaceId: KnowledgeSpaceId = KnowledgeSpaceId("default"),
      profileId: Option[IndexProfileId] = None
  ): IO[RetrievalError, Unit] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT 1
            |FROM zyblw_agent_knowledge.agent_knowledge_spaces s
            |JOIN zyblw_agent_knowledge.agent_knowledge_profiles p
            |  ON p.tenant_id = s.tenant_id
            | AND p.knowledge_space_id = s.knowledge_space_id
            | AND p.profile_id = COALESCE(?, s.active_profile_id)
            |WHERE s.tenant_id = ?
            |  AND s.knowledge_space_id = ?
            |  AND (p.embedding_provider <> ? OR p.embedding_model <> ? OR p.embedding_dimension <> ?)
            |LIMIT 1""".stripMargin
        )
        try
          profileId match
            case Some(id) => statement.setString(1, id.value)
            case None     => statement.setNull(1, java.sql.Types.VARCHAR)
          statement.setString(2, tenantId.value)
          statement.setString(3, spaceId.value)
          statement.setString(4, descriptor.provider)
          statement.setString(5, descriptor.model)
          statement.setInt(6, descriptor.dimension)
          val result = statement.executeQuery()
          try result.next()
          finally result.close()
        finally statement.close()
      }
    }.flatMap { mismatched =>
      ZIO
        .fail(
          AgentError.RetrievalFailed(
            s"query embedding identity ${descriptor.provider}:${descriptor.model}:${descriptor.dimension} 与 pinned/active 索引不一致"
          )
        )
        .when(mismatched)
        .unit
    }

  override def resolveActiveProfile(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId
  ): IO[RetrievalError, Option[IndexProfileId]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT active_profile_id FROM zyblw_agent_knowledge.agent_knowledge_spaces
            |WHERE tenant_id = ? AND knowledge_space_id = ?""".stripMargin
        )
        try
          statement.setString(1, tenantId.value)
          statement.setString(2, spaceId.value)
          val result = statement.executeQuery()
          try
            if result.next() then Option(result.getString(1)).filter(_.trim.nonEmpty).map(IndexProfileId(_))
            else None
          finally result.close()
        finally statement.close()
      }
    }

  /** 从带统一列名的检索 ResultSet 重建知识块，确保向量、hybrid 和谱系查询使用同一解码逻辑。 */
  private def readChunk(result: java.sql.ResultSet, scope: RetrievalScope): DocumentChunk =
    val permissions =
      result.getArray("permissions").getArray.asInstanceOf[Array[AnyRef]].iterator.map(_.toString).toSet
    val metadata = result
      .getString("metadata")
      .fromJson[Map[String, String]]
      .fold(error => throw IllegalStateException(s"知识块 metadata 解码失败: $error"), identity)
    DocumentChunk
      .fromText(
        id = result.getString("chunk_id"),
        documentId = result.getString("document_id"),
        text = result.getString("chunk_text"),
        sourceUri = result.getString("source_uri"),
        tenantId = scope.tenantId,
        permissions = permissions,
        metadata = metadata,
        searchText = Option(result.getString("search_text")),
        indexVersion = result.getLong("index_version"),
        lineage = decodeLineage(result)
      )
      .copy(
        knowledgeSpaceId =
          Option(result.getString("knowledge_space_id")).filter(_.nonEmpty).map(KnowledgeSpaceId(_)),
        profileId = Option(result.getString("profile_id")).filter(_.nonEmpty).map(IndexProfileId(_))
      )

  /** 谱系整体不存在时返回 None；损坏的 origins JSON 会终止查询，不静默丢失引用几何。 */
  private def decodeLineage(result: java.sql.ResultSet): Option[ChunkLineage] =
    val ordinalValue = Option(result.getObject("lineage_ordinal")).map(_.asInstanceOf[Number].intValue())
    val origins      = result
      .getString("origins")
      .fromJson[Chunk[DocumentOrigin]]
      .fold(error => throw IllegalStateException(s"知识块 origins 解码失败: $error"), identity)
    val pageNumbers     = sqlIntChunk(result.getArray("page_numbers"))
    val resolvedOrigins =
      if origins.nonEmpty then origins else pageNumbers.map(page => DocumentOrigin(page))
    val headingPath = sqlStringChunk(result.getArray("heading_path"))
    val blockIds    = sqlStringChunk(result.getArray("block_ids"))
    val parent      = Option(result.getString("parent_id"))
    val previous    = Option(result.getString("previous_chunk_id"))
    val next        = Option(result.getString("next_chunk_id"))
    val present     = ordinalValue.nonEmpty || parent.nonEmpty || previous.nonEmpty || next.nonEmpty ||
      headingPath.nonEmpty || resolvedOrigins.nonEmpty || blockIds.nonEmpty
    Option.when(present)(
      ChunkLineage(
        parent,
        ordinalValue.getOrElse(0),
        previous,
        next,
        headingPath,
        resolvedOrigins,
        blockIds
      )
    )

  private def sqlStringChunk(value: java.sql.Array): Chunk[String] =
    Option(value).fold(Chunk.empty[String])(array =>
      Chunk.fromArray(array.getArray.asInstanceOf[Array[AnyRef]]).map(_.toString)
    )

  private def sqlIntChunk(value: java.sql.Array): Chunk[Int] =
    Option(value).fold(Chunk.empty[Int])(array =>
      Chunk.fromArray(array.getArray.asInstanceOf[Array[AnyRef]]).map(_.asInstanceOf[Number].intValue())
    )

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

  private def expandedHit(
      chunk: DocumentChunk,
      seedScore: Double,
      reason: String,
      config: RetrievalExpansionConfig
  ): RetrievalHit =
    RetrievalHit(
      chunk,
      seedScore * config.expandedScoreFactor,
      Map("contextExpanded" -> 1.0, s"context.$reason" -> 1.0)
    )

  /** 把 Float 向量编码为 pgvector 文本输入格式；数值来自类型化 Float，不拼接外部原始字符串。 */
  private def vectorLiteral(embedding: Embedding): String = embedding.values.mkString("[", ",", "]")

  /** 在 ZIO blocking executor 上借用并归还 JDBC 连接。
    * @param use
    *   只允许执行短数据库操作；模型调用和 rerank 不应放入连接 Scope
    */
  private def withConnection[A](use: Connection => Task[A]): IO[RetrievalError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(ZIO.attemptBlocking(dataSource.getConnection))(connection =>
            ZIO.attemptBlocking(connection.close()).orDie
          )
          .flatMap(use)
      }
      .mapError(error =>
        AgentError.RetrievalFailed(s"pgvector 数据库操作失败: ${error.getMessage}", retryable = true)
      )

object PostgresPgVectorStore:
  /** 创建可配置维度的 VectorStore Layer。
    * @param dimension
    *   必须与已经执行的 pgvector migration 中 `vector(N)` 的 N 完全一致
    */
  def layer(
      dimension: Int,
      hybridConfig: PostgresHybridSearchConfig = PostgresHybridSearchConfig()
  ): URLayer[DataSource, VectorStore] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresPgVectorStore(dataSource, dimension, hybridConfig)
    )

/** PostgreSQL hybrid search 的确定性策略。
  *
  * @param textSearchConfig
  *   PostgreSQL regconfig；必须与 migration 生成 `search_vector` 时使用的配置完全一致。 默认基线使用 `simple`；若部署 pg_jieba，应复制并调整基线
  *   SQL 后再改为 `jiebacfg`
  * @param vectorCandidateMultiplier
  *   最终 limit 的向量候选放大倍数
  * @param textCandidateMultiplier
  *   最终 limit 的全文候选放大倍数
  * @param rrfK
  *   RRF 平滑常数；越大越降低头部名次差异
  * @param vectorWeight
  *   向量名次权重
  * @param textWeight
  *   全文名次权重
  * @param enableHnswIterativeScan
  *   是否在短事务中启用严格迭代扫描；要求 pgvector >= 0.8
  */
final case class PostgresHybridSearchConfig(
    textSearchConfig: String = "simple",
    vectorCandidateMultiplier: Int = 4,
    textCandidateMultiplier: Int = 4,
    rrfK: Double = 60.0,
    vectorWeight: Double = 1.0,
    textWeight: Double = 1.0,
    enableHnswIterativeScan: Boolean = true,
    sparseEnabled: Boolean = false,
    sparseWeight: Double = 1.0,
    sparseCandidateMultiplier: Int = 4,
    maxSparseNnz: Int = 128
):
  require(textSearchConfig.trim.nonEmpty, "textSearchConfig 不能为空")
  require(vectorCandidateMultiplier > 0, "vectorCandidateMultiplier 必须为正数")
  require(textCandidateMultiplier > 0, "textCandidateMultiplier 必须为正数")
  require(rrfK > 0.0, "rrfK 必须为正数")
  require(vectorWeight >= 0.0 && textWeight >= 0.0 && vectorWeight + textWeight > 0.0, "RRF 权重至少一个为正数")
  require(sparseWeight >= 0.0, "sparseWeight 不能为负数")
  require(sparseCandidateMultiplier > 0, "sparseCandidateMultiplier 必须为正数")
  require(maxSparseNnz > 0, "maxSparseNnz 必须为正数")

  /** 计算向量候选数。
    *
    * 使用 Long 做中间乘法并封顶 Int.MaxValue，避免恶意或错误的超大 limit 在取得连接后抛出算术缺陷。 正常 HTTP 层仍应设置远小于该上限的查询限制；这里是存储 Adapter
    * 的最后一道防御。
    */
  def vectorCandidateCount(limit: Int): Int = cappedProduct(limit, vectorCandidateMultiplier)

  /** 与 `vectorCandidateCount` 相同规则计算全文候选数。 */
  def textCandidateCount(limit: Int): Int = cappedProduct(limit, textCandidateMultiplier)

  def sparseCandidateCount(limit: Int): Int = cappedProduct(limit, sparseCandidateMultiplier)

  /** 非正 limit 归零；正数乘法在 Long 空间完成并封顶 JDBC Int 参数范围。 */
  private def cappedProduct(limit: Int, multiplier: Int): Int =
    if limit <= 0 then 0 else Math.min(limit.toLong * multiplier.toLong, Int.MaxValue.toLong).toInt
