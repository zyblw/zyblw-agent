package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.sideeffects.*
import java.sql.{Connection, PreparedStatement, SQLException}
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import javax.sql.DataSource
import zio.*
import zio.json.*
import zio.json.ast.Json

/** PostgreSQL outbox 调度实现。
  *
  * 事件写入不在此类公开：只有 `PostgresTransactionalWriteExecutor` 能在业务 transaction 中创建事件。本类只负责提交后的
  * claim/heartbeat/publish 状态推进，因此网络调用期间不持有 Connection 或数据库行锁。
  */
final class PostgresOutboxStore(dataSource: DataSource) extends OutboxStore:
  /** 使用 `FOR UPDATE SKIP LOCKED` 在短事务中领取一批不同事件。 */
  def claim(
      owner: SideEffectWorkerId,
      batchSize: Int,
      leaseDuration: Duration,
      maxAttempts: Int
  ): IO[StoreError, Chunk[OutboxLease]] =
    validateClaim(batchSize, leaseDuration, maxAttempts) *> withConnection { connection =>
      ZIO.attemptBlocking {
        transaction(connection) {
          reclaimExpired(connection)
          deadLetterExhausted(connection, maxAttempts)
          val now        = databaseNow(connection)
          val candidates = selectCandidates(connection, batchSize, maxAttempts)
          Chunk.fromIterable(candidates.map { event =>
            val token      = SideEffectLeaseToken(java.util.UUID.randomUUID())
            val generation = event.generation + 1L
            val expiresAt  = now.plusMillis(leaseDuration.toMillis)
            claimOne(connection, event.eventId, owner, token, generation, leaseDuration)
            OutboxLease(
              event
                .copy(status = OutboxStatus.Publishing, attempt = event.attempt + 1, generation = generation),
              owner,
              token,
              generation,
              now,
              expiresAt
            )
          })
        }
      }
    }

  /** 只允许当前有效 lease 续租，并返回数据库权威过期时间。 */
  def heartbeat(lease: OutboxLease, extendBy: Duration): IO[StoreError, Instant] =
    ZIO.cond(extendBy > Duration.Zero, (), AgentError.PersistenceFailure("outbox extendBy 必须大于零")) *>
      withConnection { connection =>
        ZIO.attemptBlocking {
          val statement = connection.prepareStatement(
            """UPDATE agent_outbox_events
              |SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), heartbeat_at = CURRENT_TIMESTAMP
              |WHERE event_id = ?::uuid AND status = 'Publishing' AND lease_owner = ? AND lease_token = ?::uuid
              |  AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP
              |RETURNING lease_expires_at""".stripMargin
          )
          try
            statement.setLong(1, extendBy.toMillis)
            bindLease(statement, 2, lease)
            val result = statement.executeQuery()
            if result.next() then instant(result, 1) else throw LostOutboxLease(lease)
          finally statement.close()
        }
      }

  /** 以 fencing 条件完成事件；迟到 worker 更新零行并得到 OutboxLeaseLost。 */
  def markPublished(lease: OutboxLease): IO[StoreError, Unit] =
    transitionLeased(
      lease,
      """UPDATE agent_outbox_events
        |SET status = 'Published', published_at = CURRENT_TIMESTAMP, lease_owner = NULL, lease_token = NULL,
        |    lease_expires_at = NULL, heartbeat_at = NULL, last_failure = NULL
        |WHERE event_id = ?::uuid AND status = 'Publishing' AND lease_owner = ? AND lease_token = ?::uuid
        |  AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP""".stripMargin
    )

  /** 可重试失败释放租约并设置下次可见时间。 */
  def abandon(lease: OutboxLease, safeFailure: String, availableAt: Instant): IO[StoreError, Unit] =
    validateFailure(safeFailure) *> withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """UPDATE agent_outbox_events
            |SET status = 'Pending', available_at = ?, last_failure = ?, lease_owner = NULL, lease_token = NULL,
            |    lease_expires_at = NULL, heartbeat_at = NULL
            |WHERE event_id = ?::uuid AND status = 'Publishing' AND lease_owner = ? AND lease_token = ?::uuid
            |  AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP""".stripMargin
        )
        try
          setInstant(statement, 1, availableAt)
          statement.setString(2, safeFailure)
          bindLease(statement, 3, lease)
          if statement.executeUpdate() != 1 then throw LostOutboxLease(lease)
        finally statement.close()
      }
    }

  /** 永久失败进入 DeadLetter，同时释放租约字段。 */
  def deadLetter(lease: OutboxLease, safeFailure: String): IO[StoreError, Unit] =
    validateFailure(safeFailure) *> withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """UPDATE agent_outbox_events
            |SET status = 'DeadLetter', last_failure = ?, lease_owner = NULL, lease_token = NULL,
            |    lease_expires_at = NULL, heartbeat_at = NULL
            |WHERE event_id = ?::uuid AND status = 'Publishing' AND lease_owner = ? AND lease_token = ?::uuid
            |  AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP""".stripMargin
        )
        try
          statement.setString(1, safeFailure)
          bindLease(statement, 2, lease)
          if statement.executeUpdate() != 1 then throw LostOutboxLease(lease)
        finally statement.close()
      }
    }

  /** 按 eventId 查询，未知 ID 返回类型化错误。 */
  def get(eventId: OutboxEventId): IO[StoreError, OutboxEventRecord] = withConnection { connection =>
    ZIO.attemptBlocking {
      val statement = connection.prepareStatement(s"${selectColumns} WHERE event_id = ?::uuid")
      try
        statement.setString(1, eventId.asString)
        val result = statement.executeQuery()
        if result.next() then readEvent(result) else throw MissingOutboxEvent(eventId)
      finally statement.close()
    }
  }

  /** 按 operation/ordinal 查询，保持业务事务创建顺序。 */
  def list(operationId: BusinessOperationId): IO[StoreError, Chunk[OutboxEventRecord]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement =
          connection.prepareStatement(s"${selectColumns} WHERE operation_id = ?::uuid ORDER BY ordinal")
        try
          statement.setString(1, operationId.asString)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[OutboxEventRecord]()
          while result.next() do builder += readEvent(result)
          builder.result()
        finally statement.close()
      }
    }

  /** 参数门禁在借连接前完成，避免错误配置消耗数据库资源。 */
  private def validateClaim(batchSize: Int, leaseDuration: Duration, maxAttempts: Int): IO[StoreError, Unit] =
    ZIO.cond(
      batchSize > 0 && leaseDuration > Duration.Zero && maxAttempts > 0,
      (),
      AgentError.PersistenceFailure("outbox batchSize、leaseDuration、maxAttempts 必须为正数")
    )

  /** 错误栏只接受小型安全类别，禁止写入上游响应或堆栈。 */
  private def validateFailure(value: String): IO[StoreError, Unit] =
    ZIO.cond(
      value.trim.nonEmpty && value.length <= 200,
      (),
      AgentError.PersistenceFailure("outbox safeFailure 必须为 1-200 个字符")
    )

  /** 将崩溃 worker 的过期 Publishing 事件放回 Pending。 */
  private def reclaimExpired(connection: Connection): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_outbox_events
        |SET status = 'Pending', last_failure = 'lease-expired', lease_owner = NULL, lease_token = NULL,
        |    lease_expires_at = NULL, heartbeat_at = NULL, available_at = CURRENT_TIMESTAMP
        |WHERE status = 'Publishing' AND lease_expires_at <= CURRENT_TIMESTAMP""".stripMargin
    )
    try
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 达到自动尝试上限的待发送事件进入 DeadLetter，不再形成热循环。 */
  private def deadLetterExhausted(connection: Connection, maxAttempts: Int): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_outbox_events SET status = 'DeadLetter', last_failure = 'max-attempts-exceeded'
        |WHERE status = 'Pending' AND attempt >= ?""".stripMargin
    )
    try
      statement.setInt(1, maxAttempts)
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 锁定最早可见的热集合；不同 worker 会跳过已经锁住的行。 */
  private def selectCandidates(
      connection: Connection,
      batchSize: Int,
      maxAttempts: Int
  ): List[OutboxEventRecord] =
    val statement = connection.prepareStatement(
      s"""${selectColumns}
         |WHERE status = 'Pending' AND available_at <= CURRENT_TIMESTAMP AND attempt < ?
         |ORDER BY available_at ASC, created_at ASC, event_id ASC
         |FOR UPDATE SKIP LOCKED LIMIT ?""".stripMargin
    )
    try
      statement.setInt(1, maxAttempts)
      statement.setInt(2, batchSize)
      val result  = statement.executeQuery()
      val builder = List.newBuilder[OutboxEventRecord]
      while result.next() do builder += readEvent(result)
      builder.result()
    finally statement.close()

  /** 把已锁候选推进 Publishing；generation 和 attempt 在 claim 时各增加一次。 */
  private def claimOne(
      connection: Connection,
      eventId: OutboxEventId,
      owner: SideEffectWorkerId,
      token: SideEffectLeaseToken,
      generation: Long,
      leaseDuration: Duration
  ): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_outbox_events
        |SET status = 'Publishing', attempt = attempt + 1, generation = ?, lease_owner = ?, lease_token = ?::uuid,
        |    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), heartbeat_at = CURRENT_TIMESTAMP
        |WHERE event_id = ?::uuid AND status = 'Pending'""".stripMargin
    )
    try
      statement.setLong(1, generation)
      statement.setString(2, owner.value)
      statement.setString(3, token.asString)
      statement.setLong(4, leaseDuration.toMillis)
      statement.setString(5, eventId.asString)
      if statement.executeUpdate() != 1 then
        throw IllegalStateException(s"outbox claim 失败: ${eventId.asString}")
    finally statement.close()

  /** 执行无额外参数的 fenced 终态更新。 */
  private def transitionLeased(lease: OutboxLease, sql: String): IO[StoreError, Unit] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(sql)
        try
          bindLease(statement, 1, lease)
          if statement.executeUpdate() != 1 then throw LostOutboxLease(lease)
        finally statement.close()
      }
    }

  /** 按固定 event/owner/token/generation 顺序绑定 SQL 参数。 */
  private def bindLease(statement: PreparedStatement, start: Int, lease: OutboxLease): Unit =
    statement.setString(start, lease.event.eventId.asString)
    statement.setString(start + 1, lease.owner.value)
    statement.setString(start + 2, lease.token.asString)
    statement.setLong(start + 3, lease.generation)

  /** 从固定 SELECT 列顺序恢复完整领域记录。 */
  private def readEvent(result: java.sql.ResultSet): OutboxEventRecord =
    val eventId = OutboxEventId
      .fromString(result.getString("event_id"))
      .fold(error => throw IllegalStateException(error), identity)
    val operationId = BusinessOperationId
      .fromString(result.getString("operation_id"))
      .fold(error => throw IllegalStateException(error), identity)
    val runId =
      RunId.fromString(result.getString("run_id")).fold(error => throw IllegalStateException(error), identity)
    val payload =
      result.getString("payload").fromJson[Json].fold(error => throw IllegalStateException(error), identity)
    val headers = result
      .getString("headers")
      .fromJson[Map[String, String]]
      .fold(error => throw IllegalStateException(error), identity)
    OutboxEventRecord(
      eventId,
      operationId,
      runId,
      result.getString("tool_call_id"),
      result.getString("scope_key"),
      result.getInt("ordinal"),
      OutboxEventDraft(
        result.getString("destination"),
        result.getString("event_type"),
        result.getString("aggregate_type"),
        result.getString("aggregate_id"),
        result.getString("partition_key"),
        payload,
        headers
      ),
      OutboxStatus.valueOf(result.getString("status")),
      result.getInt("attempt"),
      result.getLong("generation"),
      instant(result, "available_at"),
      Option(result.getString("last_failure")),
      instant(result, "created_at"),
      Option(result.getObject("published_at", classOf[OffsetDateTime])).map(_.toInstant)
    )

  /** 查询数据库当前时间，避免 lease 对应用主机时钟产生信任。 */
  private def databaseNow(connection: Connection): Instant =
    val statement = connection.prepareStatement("SELECT CURRENT_TIMESTAMP")
    try
      val result = statement.executeQuery()
      result.next()
      instant(result, 1)
    finally statement.close()

  /** 事务模板确保 claim/reclaim/dead-letter 同生共死。 */
  private def transaction[A](connection: Connection)(body: => A): A =
    connection.setAutoCommit(false)
    try
      val value = body
      connection.commit()
      value
    catch
      case error: Throwable =>
        try connection.rollback()
        catch case rollbackError: Throwable => error.addSuppressed(rollbackError)
        throw error
    finally connection.setAutoCommit(true)

  /** 借用宿主 DataSource 连接，并将所有 JDBC 调用保持在 blocking executor。 */
  private def withConnection[A](use: Connection => Task[A]): IO[StoreError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(ZIO.attemptBlocking(dataSource.getConnection))(connection =>
            ZIO.attemptBlocking(connection.close()).orDie
          )
          .flatMap(use)
      }
      .mapError {
        case LostOutboxLease(lease) =>
          AgentError.OutboxLeaseLost(lease.event.eventId.asString, lease.owner.value, lease.generation)
        case MissingOutboxEvent(id) => AgentError.OutboxEventNotFound(id.asString)
        case error: StoreError      => error
        case sql: SQLException      => databaseError("PostgreSQL outbox 操作失败", sql)
        case error                  => AgentError.PersistenceFailure("PostgreSQL outbox 操作失败", Some(error))
      }

  /** 将 SQLSTATE 转成框架可重试分类。 */
  private def databaseError(operation: String, sql: SQLException): StoreError =
    val state     = Option(sql.getSQLState).getOrElse("unknown")
    val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
    AgentError.DatabaseFailure(operation, state, retryable, Some(sql))

  /** 写 TIMESTAMPTZ 参数。 */
  private def setInstant(statement: PreparedStatement, index: Int, value: Instant): Unit =
    statement.setObject(index, value.atOffset(ZoneOffset.UTC))

  /** 从 TIMESTAMPTZ 列读取 Instant。 */
  private def instant(result: java.sql.ResultSet, column: String): Instant =
    result.getObject(column, classOf[OffsetDateTime]).toInstant

  /** 从 TIMESTAMPTZ 序号列读取 Instant。 */
  private def instant(result: java.sql.ResultSet, column: Int): Instant =
    result.getObject(column, classOf[OffsetDateTime]).toInstant

  /** 所有读取共用固定列清单，避免 SELECT * 对 schema 顺序产生依赖。 */
  private val selectColumns =
    """SELECT event_id::text, operation_id::text, run_id::text, tool_call_id, scope_key, ordinal,
      |destination, event_type, aggregate_type, aggregate_id, partition_key, payload::text, headers::text,
      |status, attempt, generation, available_at, last_failure, created_at, published_at
      |FROM agent_outbox_events""".stripMargin

object PostgresOutboxStore:
  /** 使用宿主共享 DataSource 提供 OutboxStore。 */
  val layer: URLayer[DataSource, OutboxStore] =
    ZLayer.fromFunction((dataSource: DataSource) => new PostgresOutboxStore(dataSource))

final private case class LostOutboxLease(lease: OutboxLease)        extends RuntimeException
final private case class MissingOutboxEvent(eventId: OutboxEventId) extends RuntimeException
