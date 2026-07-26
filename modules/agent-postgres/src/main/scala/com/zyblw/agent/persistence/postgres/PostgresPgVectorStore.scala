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
          """INSERT INTO agent_knowledge_chunks
            |(tenant_id, chunk_id, document_id, index_version, chunk_text, search_text, source_uri, permissions, metadata, embedding)
            |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
            |ON CONFLICT (tenant_id, chunk_id) DO UPDATE SET
            |document_id = EXCLUDED.document_id,
            |index_version = EXCLUDED.index_version,
            |chunk_text = EXCLUDED.chunk_text,
            |search_text = EXCLUDED.search_text,
            |source_uri = EXCLUDED.source_uri,
            |permissions = EXCLUDED.permissions,
            |metadata = EXCLUDED.metadata,
            |embedding = EXCLUDED.embedding,
            |updated_at = now()""".stripMargin
        )
        try
          chunks.foreach { indexed =>
            val chunk = indexed.chunk
            statement.setString(1, chunk.tenantId.value)
            statement.setString(2, chunk.id)
            statement.setString(3, chunk.documentId)
            statement.setLong(4, chunk.indexVersion)
            statement.setString(5, chunk.text)
            statement.setString(6, chunk.searchText.getOrElse(chunk.text))
            statement.setString(7, chunk.sourceUri)
            statement.setArray(8, connection.createArrayOf("text", chunk.permissions.toArray))
            statement.setString(9, chunk.metadata.toJson)
            statement.setString(10, vectorLiteral(indexed.embedding))
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
            """SELECT chunk_id, document_id, chunk_text, search_text, source_uri, permissions, metadata::text, index_version,
            |       1 - (embedding <=> ?::vector) AS score
            |FROM agent_knowledge_chunks
            |WHERE tenant_id = ? AND permissions <@ ?::text[]
            |ORDER BY embedding <=> ?::vector
            |LIMIT ?""".stripMargin
          val statement = connection.prepareStatement(sql)
          try
            val vector = vectorLiteral(query)
            statement.setString(1, vector)
            statement.setString(2, scope.tenantId.value)
            statement.setArray(3, connection.createArrayOf("text", scope.permissions.toArray))
            statement.setString(4, vector)
            statement.setInt(5, limit)
            val result  = statement.executeQuery()
            val builder = ChunkBuilder.make[RetrievalHit]()
            while result.next() do
              val permissions =
                result.getArray(6).getArray.asInstanceOf[Array[AnyRef]].iterator.map(_.toString).toSet
              val metadata = result
                .getString(7)
                .fromJson[Map[String, String]]
                .fold(
                  error => throw IllegalStateException(s"知识块 metadata 解码失败: $error"),
                  identity
                )
              val chunk = DocumentChunk(
                id = result.getString(1),
                documentId = result.getString(2),
                text = result.getString(3),
                sourceUri = result.getString(5),
                tenantId = scope.tenantId,
                permissions = permissions,
                metadata = metadata,
                searchText = Option(result.getString(4)),
                indexVersion = result.getLong(8)
              )
              builder += RetrievalHit(chunk, result.getDouble(9))
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
              """WITH search_query AS (
              |  SELECT websearch_to_tsquery(?::regconfig, ?) AS value
              |),
              |vector_hits AS MATERIALIZED (
              |  SELECT chunk_id,
              |         row_number() OVER (ORDER BY embedding <=> ?::vector, chunk_id) AS vector_rank,
              |         1 - (embedding <=> ?::vector) AS vector_score
              |  FROM agent_knowledge_chunks
              |  WHERE tenant_id = ? AND permissions <@ ?::text[]
              |  ORDER BY embedding <=> ?::vector, chunk_id
              |  LIMIT ?
              |),
              |text_hits AS MATERIALIZED (
              |  SELECT chunk_id,
              |         row_number() OVER (
              |           ORDER BY ts_rank_cd(search_vector, search_query.value, 32) DESC, chunk_id
              |         ) AS text_rank,
              |         ts_rank_cd(search_vector, search_query.value, 32) AS text_score
              |  FROM agent_knowledge_chunks CROSS JOIN search_query
              |  WHERE tenant_id = ?
              |    AND permissions <@ ?::text[]
              |    AND search_vector @@ search_query.value
              |  ORDER BY text_score DESC, chunk_id
              |  LIMIT ?
              |),
              |ranks AS (
              |  SELECT COALESCE(vector_hits.chunk_id, text_hits.chunk_id) AS chunk_id,
              |         vector_rank, text_rank, vector_score, text_score
              |  FROM vector_hits FULL OUTER JOIN text_hits USING (chunk_id)
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
              |       f.vector_score, f.text_score, f.vector_rank, f.text_rank, c.index_version
              |FROM fused f
              |JOIN agent_knowledge_chunks c ON c.tenant_id = ? AND c.chunk_id = f.chunk_id
              |ORDER BY f.fused_score DESC, c.chunk_id
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
              statement.setString(7, vector)
              statement.setInt(8, hybridConfig.vectorCandidateCount(limit))
              statement.setString(9, scope.tenantId.value)
              statement.setArray(10, permissions)
              statement.setInt(11, hybridConfig.textCandidateCount(limit))
              statement.setDouble(12, hybridConfig.vectorWeight)
              statement.setDouble(13, hybridConfig.rrfK)
              statement.setDouble(14, hybridConfig.textWeight)
              statement.setDouble(15, hybridConfig.rrfK)
              statement.setString(16, scope.tenantId.value)
              statement.setInt(17, limit)
              val result  = statement.executeQuery()
              val builder = ChunkBuilder.make[RetrievalHit]()
              while result.next() do
                val resultPermissions =
                  result.getArray(6).getArray.asInstanceOf[Array[AnyRef]].iterator.map(_.toString).toSet
                val metadata = result
                  .getString(7)
                  .fromJson[Map[String, String]]
                  .fold(
                    error => throw IllegalStateException(s"知识块 metadata 解码失败: $error"),
                    identity
                  )
                val chunk = DocumentChunk(
                  id = result.getString(1),
                  documentId = result.getString(2),
                  text = result.getString(3),
                  sourceUri = result.getString(5),
                  tenantId = scope.tenantId,
                  permissions = resultPermissions,
                  metadata = metadata,
                  searchText = Option(result.getString(4)),
                  indexVersion = result.getLong(13)
                )
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
          "DELETE FROM agent_knowledge_chunks WHERE tenant_id = ? AND document_id = ?"
        )
        try
          statement.setString(1, tenantId.value)
          statement.setString(2, documentId)
          statement.executeUpdate()
          ()
        finally statement.close()
      }
    }

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
    enableHnswIterativeScan: Boolean = true
):
  require(textSearchConfig.trim.nonEmpty, "textSearchConfig 不能为空")
  require(vectorCandidateMultiplier > 0, "vectorCandidateMultiplier 必须为正数")
  require(textCandidateMultiplier > 0, "textCandidateMultiplier 必须为正数")
  require(rrfK > 0.0, "rrfK 必须为正数")
  require(vectorWeight >= 0.0 && textWeight >= 0.0 && vectorWeight + textWeight > 0.0, "RRF 权重至少一个为正数")

  /** 计算向量候选数。
    *
    * 使用 Long 做中间乘法并封顶 Int.MaxValue，避免恶意或错误的超大 limit 在取得连接后抛出算术缺陷。 正常 HTTP 层仍应设置远小于该上限的查询限制；这里是存储 Adapter
    * 的最后一道防御。
    */
  def vectorCandidateCount(limit: Int): Int = cappedProduct(limit, vectorCandidateMultiplier)

  /** 与 `vectorCandidateCount` 相同规则计算全文候选数。 */
  def textCandidateCount(limit: Int): Int = cappedProduct(limit, textCandidateMultiplier)

  /** 非正 limit 归零；正数乘法在 Long 空间完成并封顶 JDBC Int 参数范围。 */
  private def cappedProduct(limit: Int, multiplier: Int): Int =
    if limit <= 0 then 0 else Math.min(limit.toLong * multiplier.toLong, Int.MaxValue.toLong).toInt
