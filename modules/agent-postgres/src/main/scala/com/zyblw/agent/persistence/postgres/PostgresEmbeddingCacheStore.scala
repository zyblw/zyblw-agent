package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.sql.{Connection, SQLException, Timestamp}
import java.time.Instant
import javax.sql.DataSource
import zio.*

/** PostgreSQL Embedding 精确缓存的批处理配置。
  *
  * @param readBatchSize
  *   单条 `unnest` 查询包含的最大 key 数，限制 JDBC 参数数组与数据库工作内存
  * @param writeBatchSize
  *   单次 `executeBatch` 包含的最大 upsert 数；一次 `put` 的所有批次仍位于同一事务
  * @param maxKeysPerCall
  *   单次 SPI 调用的最大 key/entry 数，防止意外输入制造超长事务或堆内存尖峰
  */
final case class PostgresEmbeddingCacheConfig(
    readBatchSize: Int = 256,
    writeBatchSize: Int = 256,
    maxKeysPerCall: Int = 10_000
):
  require(readBatchSize >= 1 && readBatchSize <= 1000, "Embedding cache readBatchSize 必须位于 1..1000")
  require(writeBatchSize >= 1 && writeBatchSize <= 1000, "Embedding cache writeBatchSize 必须位于 1..1000")
  require(maxKeysPerCall >= readBatchSize && maxKeysPerCall >= writeBatchSize, "maxKeysPerCall 不能小于读写批次")

/** 使用普通 PostgreSQL `REAL[]` 保存精确 Embedding 缓存。
  *
  * 这里刻意不使用 pgvector：缓存只做完整键的等值命中，不进行相似度搜索；`REAL[]` 允许 OpenAI、Gemini、 DeepSeek 或本地模型在同一张表中使用不同维度。主键包含
  * tenant/purpose/provider/model/dimension/keyVersion/hash， 因而不会发生跨租户、跨用途或跨模型复用。读取不更新 last-access 时间，避免热门 key
  * 造成无意义写放大和表膨胀。
  *
  * @param dataSource
  *   由宿主应用创建并监控的共享连接池
  * @param config
  *   JDBC 批处理上限
  */
final class PostgresEmbeddingCacheStore(
    dataSource: DataSource,
    config: PostgresEmbeddingCacheConfig = PostgresEmbeddingCacheConfig()
) extends EmbeddingCacheStore:

  /** 批量读取仍在有效期内的向量。
    *
    * 调用方可以传入重复 key；实现会先去重，再拆成有界批次。每批用六个等长数组 `unnest` 成临时关系， 相比拼接 OR 条件更容易保持 SQL 固定、参数化和可观测。
    *
    * @param keys
    *   完整缓存键，tenantId 是不可省略的隔离维度
    * @param now
    *   过期判定时刻
    * @return
    *   只包含命中且未过期的 key；miss 不以空向量表示
    */
  def get(
      keys: Chunk[EmbeddingCacheKey],
      now: Instant
  ): IO[RetrievalError, Map[EmbeddingCacheKey, Embedding]] =
    val unique = Chunk.fromIterable(keys.distinct)
    rejectOversized(unique.length, "read") *> validateKeys(unique) *>
      ZIO.foldLeft(unique.grouped(config.readBatchSize).toVector)(Map.empty[EmbeddingCacheKey, Embedding]) {
        (all, batch) =>
          readBatch(batch, now).map(all ++ _)
      }

  /** 在一个短事务中幂等写入全部缓存项。
    *
    * 冲突时刷新向量和过期时间，因此缓存算法版本升级只需改变 keyVersion，不需要覆盖旧契约。所有输入会在 借连接前验证维度和有限浮点数，避免坏向量进入数据库后才由约束回滚。
    *
    * @param entries
    *   待写缓存项；空集合是成功的 no-op
    */
  def put(entries: Chunk[EmbeddingCacheEntry]): IO[RetrievalError, Unit] =
    rejectOversized(entries.length, "write") *> validateEntries(entries) *>
      ZIO.whenDiscard(entries.nonEmpty) {
        withTransaction { connection =>
          ZIO.foreachDiscard(entries.grouped(config.writeBatchSize).toVector)(batch =>
            writeBatch(connection, batch)
          )
        }
      }

  /** 使用 `FOR UPDATE SKIP LOCKED` 有界清理过期项，可让多个 maintenance worker 安全并行。
    *
    * @param now
    *   `expires_at <= now` 的行可删除
    * @param limit
    *   单事务最多删除行数；非正数直接返回 0
    */
  def purgeExpired(now: Instant, limit: Int): IO[RetrievalError, Long] =
    if limit <= 0 then ZIO.succeed(0L)
    else
      withTransaction { connection =>
        jdbc("purge expired cache") {
          val statement = connection.prepareStatement(
            """WITH candidates AS (
            |  SELECT tenant_id, purpose, provider, model, dimension, key_version, content_hash
            |  FROM agent_embedding_cache
            |  WHERE expires_at <= ?
            |  ORDER BY expires_at, tenant_id, purpose, provider, model, dimension, key_version, content_hash
            |  FOR UPDATE SKIP LOCKED
            |  LIMIT ?
            |)
            |DELETE FROM agent_embedding_cache cache
            |USING candidates candidate
            |WHERE cache.tenant_id = candidate.tenant_id
            |  AND cache.purpose = candidate.purpose
            |  AND cache.provider = candidate.provider
            |  AND cache.model = candidate.model
            |  AND cache.dimension = candidate.dimension
            |  AND cache.key_version = candidate.key_version
            |  AND cache.content_hash = candidate.content_hash""".stripMargin
          )
          try
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setInt(2, limit)
            statement.executeUpdate().toLong
          finally statement.close()
        }
      }

  /** 执行一批固定 SQL 的缓存读取，并验证数据库中的数组仍符合模型维度契约。 */
  private def readBatch(
      batch: Chunk[EmbeddingCacheKey],
      now: Instant
  ): IO[RetrievalError, Map[EmbeddingCacheKey, Embedding]] =
    withConnection { connection =>
      jdbc("read embedding cache") {
        val statement = connection.prepareStatement(
          """WITH requested(tenant_id, purpose, provider, model, dimension, key_version, content_hash) AS (
            |  SELECT * FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::int[], ?::text[], ?::text[])
            |)
            |SELECT cache.tenant_id, cache.purpose, cache.provider, cache.model, cache.dimension,
            |       cache.key_version, cache.content_hash, cache.embedding
            |FROM requested
            |JOIN agent_embedding_cache cache USING
            |  (tenant_id, purpose, provider, model, dimension, key_version, content_hash)
            |WHERE cache.expires_at > ?""".stripMargin
        )
        val arrays = Chunk(
          connection.createArrayOf("text", batch.map(_.tenantId.value).toArray),
          connection
            .createArrayOf("text", batch.map(_.purpose.toString.toLowerCase(java.util.Locale.ROOT)).toArray),
          connection.createArrayOf("text", batch.map(_.provider).toArray),
          connection.createArrayOf("text", batch.map(_.model).toArray),
          connection.createArrayOf("int4", batch.map(key => Integer.valueOf(key.dimension)).toArray),
          connection.createArrayOf("text", batch.map(_.keyVersion).toArray),
          connection.createArrayOf("text", batch.map(_.contentHash).toArray)
        )
        try
          arrays.zipWithIndex.foreach((array, index) => statement.setArray(index + 1, array))
          statement.setTimestamp(8, Timestamp.from(now))
          val result  = statement.executeQuery()
          val builder = Map.newBuilder[EmbeddingCacheKey, Embedding]
          while result.next() do
            val dimension = result.getInt(5)
            val key       = EmbeddingCacheKey(
              TenantId(result.getString(1)),
              purposeFromDatabase(result.getString(2)),
              result.getString(3),
              result.getString(4),
              dimension,
              result.getString(6),
              result.getString(7)
            )
            val sqlArray = result.getArray(8)
            val values   =
              try decodeFloats(sqlArray.getArray)
              finally
                try sqlArray.free()
                catch case _: Throwable => ()
            if values.length != dimension || values.exists(value => !java.lang.Float.isFinite(value)) then
              throw IllegalStateException("embedding cache 数据库向量违反维度或有限值契约")
            builder += key -> Embedding(values)
          builder.result()
        finally
          arrays.foreach(array =>
            try array.free()
            catch case _: Throwable => ()
          )
          statement.close()
      }
    }

  /** 把一批 upsert 发给 PostgreSQL；SQL 数组在 `executeBatch` 完成后才释放。 */
  private def writeBatch(
      connection: Connection,
      batch: Chunk[EmbeddingCacheEntry]
  ): IO[RetrievalError, Unit] =
    jdbc("write embedding cache") {
      val statement = connection.prepareStatement(
        """INSERT INTO agent_embedding_cache
          |(tenant_id, purpose, provider, model, dimension, key_version, content_hash, embedding, expires_at)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT (tenant_id, purpose, provider, model, dimension, key_version, content_hash) DO UPDATE SET
          |embedding = EXCLUDED.embedding,
          |expires_at = EXCLUDED.expires_at,
          |updated_at = CURRENT_TIMESTAMP""".stripMargin
      )
      val arrays = scala.collection.mutable.ArrayBuffer.empty[java.sql.Array]
      try
        batch.foreach { entry =>
          statement.setString(1, entry.key.tenantId.value)
          statement.setString(2, entry.key.purpose.toString.toLowerCase(java.util.Locale.ROOT))
          statement.setString(3, entry.key.provider)
          statement.setString(4, entry.key.model)
          statement.setInt(5, entry.key.dimension)
          statement.setString(6, entry.key.keyVersion)
          statement.setString(7, entry.key.contentHash)
          val vector = connection.createArrayOf("real", entry.embedding.values.map(Float.box).toArray)
          arrays += vector
          statement.setArray(8, vector)
          statement.setTimestamp(9, Timestamp.from(entry.expiresAt))
          statement.addBatch()
        }
        statement.executeBatch()
        ()
      finally
        arrays.foreach(array =>
          try array.free()
          catch case _: Throwable => ()
        )
        statement.close()
    }

  /** JDBC 驱动可能返回 Float、Double 等 Number 包装类型，因此通过反射数组统一解码。 */
  private def decodeFloats(raw: Any): Chunk[Float] =
    val length = java.lang.reflect.Array.getLength(raw)
    Chunk.fromIterable((0 until length).map { index =>
      java.lang.reflect.Array.get(raw, index) match
        case number: java.lang.Number => number.floatValue()
        case _                        => throw IllegalStateException("embedding cache 包含非数值数组元素")
    })

  /** 数据库枚举使用固定小写 ASCII，不依赖 JVM 默认 Locale（例如土耳其 locale 会把 i 转成非 ASCII 字符）。 */
  private def purposeFromDatabase(value: String): EmbeddingPurpose = value match
    case "query"    => EmbeddingPurpose.Query
    case "indexing" => EmbeddingPurpose.Indexing
    case "memory"   => EmbeddingPurpose.Memory
    case other      => throw IllegalStateException(s"embedding cache 包含未知 purpose: $other")

  /** 在访问数据库之前验证所有键，错误不会泄漏正文，因为表中只保存 hash。 */
  private def validateKeys(keys: Chunk[EmbeddingCacheKey]): IO[RetrievalError, Unit] =
    ZIO.fromEither(keys.foldLeft[Either[RetrievalError, Unit]](Right(())) { (result, key) =>
      result.flatMap(_ => validateKey(key))
    })

  /** 在建立 JDBC 数组或开启写事务前拒绝无界批量，调用方可自行分片并施加背压。 */
  private def rejectOversized(size: Int, operation: String): IO[RetrievalError, Unit] =
    if size <= config.maxKeysPerCall then ZIO.unit
    else
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"Embedding cache $operation 数量 $size 超过单次上限 ${config.maxKeysPerCall}"
        )
      )

  /** 验证 key 字段长度、维度与 SHA-256 格式，与 V001 CHECK 约束保持双层防御。 */
  private def validateKey(key: EmbeddingCacheKey): Either[RetrievalError, Unit] =
    Either.cond(
      key.tenantId.value.length <= 1000 && key.provider.trim.nonEmpty && key.provider.length <= 200 &&
        key.model.trim.nonEmpty && key.model.length <= 200 && key.dimension > 0 &&
        key.keyVersion.trim.nonEmpty && key.keyVersion.length <= 100 && key.contentHash.matches(
          "[0-9a-f]{64}"
        ),
      (),
      AgentError.RetrievalFailed("Embedding cache key 不符合数据库契约")
    )

  /** 验证缓存项的 key、向量维度、有限值和过期时间字段。 */
  private def validateEntries(entries: Chunk[EmbeddingCacheEntry]): IO[RetrievalError, Unit] =
    ZIO.fromEither(entries.foldLeft[Either[RetrievalError, Unit]](Right(())) { (result, entry) =>
      result.flatMap(_ => validateKey(entry.key)).flatMap { _ =>
        Either.cond(
          entry.embedding.values.length == entry.key.dimension &&
            entry.embedding.values.forall(java.lang.Float.isFinite),
          (),
          AgentError.RetrievalFailed("Embedding cache 向量维度不一致或包含非有限值")
        )
      }
    })

  /** 从宿主连接池按 Scope 借还连接；Fiber 被取消时仍保证归还。 */
  private def withConnection[A](use: Connection => IO[RetrievalError, A]): IO[RetrievalError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(jdbc("acquire connection")(dataSource.getConnection))(connection =>
          ZIO.attemptBlocking(connection.close()).ignore
        )
        .flatMap(use)
    }

  /** 业务阶段可中断，commit/rollback/autoCommit 恢复进入不可中断终结区。 */
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

  /** 在 blocking executor 执行 JDBC，并把 SQLSTATE 分类成稳定的 RetrievalError。 */
  private def jdbc[A](operation: String)(effect: => A): IO[RetrievalError, A] =
    ZIO.attemptBlocking(effect).mapError(error => databaseError(operation, error))

  /** 连接、事务回滚、资源耗尽与数据库重启错误可重试；数据/约束错误不可重试。 */
  private def databaseError(operation: String, error: Throwable): RetrievalError =
    val sqlState = error match
      case sql: SQLException => Option(sql.getSQLState).getOrElse("unknown")
      case _                 => "not-sql"
    val retryable = sqlState.startsWith("08") || sqlState.startsWith("40") || sqlState.startsWith("53") ||
      Set("57P01", "57P02", "57P03").contains(sqlState)
    AgentError.RetrievalFailed(s"PostgreSQL embedding cache $operation 失败 (sqlState=$sqlState)", retryable)

object PostgresEmbeddingCacheStore:
  /** 使用默认批次配置构造可替换的 ZLayer。 */
  val layer: URLayer[DataSource, EmbeddingCacheStore] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresEmbeddingCacheStore(dataSource): EmbeddingCacheStore
    )

  /** 使用显式批次配置构造 Layer，适合按连接池和数据库参数做部署调优。 */
  def configured(config: PostgresEmbeddingCacheConfig): URLayer[DataSource, EmbeddingCacheStore] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresEmbeddingCacheStore(dataSource, config): EmbeddingCacheStore
    )
