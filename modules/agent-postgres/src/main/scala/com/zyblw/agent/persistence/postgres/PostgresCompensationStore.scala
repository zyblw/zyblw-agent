package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.sideeffects.*
import java.sql.{Connection, PreparedStatement, SQLException}
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import javax.sql.DataSource
import zio.*
import zio.json.*
import zio.json.ast.Json

/** PostgreSQL 补偿计划存储；计划由原业务事务注册，只有显式 activate 后才可 claim。 */
final class PostgresCompensationStore(dataSource: DataSource) extends CompensationStore:
  /** Registered -> Pending；Pending/Running/Succeeded 保持幂等，终态 Cancelled/DeadLetter 拒绝重新激活。 */
  def activate(compensationId: CompensationId, availableAt: Instant): IO[StoreError, CompensationRecord] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        transaction(connection) {
          val current = loadForUpdate(connection, compensationId)
          current.status match
            case CompensationStatus.Registered =>
              val statement = connection.prepareStatement(
                """UPDATE agent_compensations SET status = 'Pending', available_at = ?
                  |WHERE compensation_id = ?::uuid AND status = 'Registered'""".stripMargin
              )
              try
                setInstant(statement, 1, availableAt)
                statement.setString(2, compensationId.asString)
                if statement.executeUpdate() != 1 then throw InvalidCompensation(current, "activate")
              finally statement.close()
              current.copy(status = CompensationStatus.Pending, availableAt = availableAt)
            case CompensationStatus.Pending | CompensationStatus.Running | CompensationStatus.Succeeded =>
              current
            case other => throw InvalidCompensation(current, s"activate-from-$other")
        }
      }
    }

  /** 只允许 Registered/Pending 计划取消；重复 Cancelled 保持幂等。 */
  def cancel(compensationId: CompensationId): IO[StoreError, CompensationRecord] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        transaction(connection) {
          val current = loadForUpdate(connection, compensationId)
          current.status match
            case CompensationStatus.Cancelled                               => current
            case CompensationStatus.Registered | CompensationStatus.Pending =>
              val statement = connection.prepareStatement(
                """UPDATE agent_compensations SET status = 'Cancelled', completed_at = CURRENT_TIMESTAMP
                |WHERE compensation_id = ?::uuid AND status IN ('Registered', 'Pending')""".stripMargin
              )
              try
                statement.setString(1, compensationId.asString)
                if statement.executeUpdate() != 1 then throw InvalidCompensation(current, "cancel")
              finally statement.close()
              current.copy(status = CompensationStatus.Cancelled, completedAt = Some(databaseNow(connection)))
            case other => throw InvalidCompensation(current, s"cancel-from-$other")
        }
      }
    }

  /** 回收过期 Running 后，用 SKIP LOCKED 领取 Pending 计划。 */
  def claim(
      owner: SideEffectWorkerId,
      batchSize: Int,
      leaseDuration: Duration,
      maxAttempts: Int
  ): IO[StoreError, Chunk[CompensationLease]] =
    ZIO.cond(
      batchSize > 0 && leaseDuration > Duration.Zero && maxAttempts > 0,
      (),
      AgentError.PersistenceFailure("补偿 batchSize、leaseDuration、maxAttempts 必须为正数")
    ) *>
      withConnection { connection =>
        ZIO.attemptBlocking {
          transaction(connection) {
            reclaimExpired(connection)
            deadLetterExhausted(connection, maxAttempts)
            val now        = databaseNow(connection)
            val candidates = selectCandidates(connection, batchSize, maxAttempts)
            Chunk.fromIterable(candidates.map { record =>
              val token      = SideEffectLeaseToken(java.util.UUID.randomUUID())
              val generation = record.generation + 1L
              claimOne(connection, record.compensationId, owner, token, generation, leaseDuration)
              CompensationLease(
                record.copy(
                  status = CompensationStatus.Running,
                  attempt = record.attempt + 1,
                  generation = generation
                ),
                owner,
                token,
                generation,
                now,
                now.plusMillis(leaseDuration.toMillis)
              )
            })
          }
        }
      }

  /** 延长仍有效的补偿 lease。 */
  def heartbeat(lease: CompensationLease, extendBy: Duration): IO[StoreError, Instant] =
    ZIO.cond(extendBy > Duration.Zero, (), AgentError.PersistenceFailure("补偿 extendBy 必须大于零")) *>
      withConnection { connection =>
        ZIO.attemptBlocking {
          val statement = connection.prepareStatement(
            """UPDATE agent_compensations
              |SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), heartbeat_at = CURRENT_TIMESTAMP
              |WHERE compensation_id = ?::uuid AND status = 'Running' AND lease_owner = ? AND lease_token = ?::uuid
              |  AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP
              |RETURNING lease_expires_at""".stripMargin
          )
          try
            statement.setLong(1, extendBy.toMillis)
            bindLease(statement, 2, lease)
            val result = statement.executeQuery()
            if result.next() then instant(result, 1) else throw LostCompensationLease(lease)
          finally statement.close()
        }
      }

  /** 成功补偿并释放 lease。 */
  def complete(lease: CompensationLease): IO[StoreError, Unit] =
    transition(
      lease,
      """UPDATE agent_compensations
        |SET status = 'Succeeded', completed_at = CURRENT_TIMESTAMP, last_failure = NULL,
        |    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, heartbeat_at = NULL
        |WHERE compensation_id = ?::uuid AND status = 'Running' AND lease_owner = ? AND lease_token = ?::uuid
        |  AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP""".stripMargin
    )

  /** 可重试失败回到 Pending。 */
  def abandon(lease: CompensationLease, safeFailure: String, availableAt: Instant): IO[StoreError, Unit] =
    validateFailure(safeFailure) *> withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """UPDATE agent_compensations
            |SET status = 'Pending', available_at = ?, last_failure = ?, lease_owner = NULL, lease_token = NULL,
            |    lease_expires_at = NULL, heartbeat_at = NULL
            |WHERE compensation_id = ?::uuid AND status = 'Running' AND lease_owner = ? AND lease_token = ?::uuid
            |  AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP""".stripMargin
        )
        try
          setInstant(statement, 1, availableAt)
          statement.setString(2, safeFailure)
          bindLease(statement, 3, lease)
          if statement.executeUpdate() != 1 then throw LostCompensationLease(lease)
        finally statement.close()
      }
    }

  /** 永久失败进入 DeadLetter。 */
  def deadLetter(lease: CompensationLease, safeFailure: String): IO[StoreError, Unit] =
    validateFailure(safeFailure) *> withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """UPDATE agent_compensations
            |SET status = 'DeadLetter', last_failure = ?, lease_owner = NULL, lease_token = NULL,
            |    lease_expires_at = NULL, heartbeat_at = NULL
            |WHERE compensation_id = ?::uuid AND status = 'Running' AND lease_owner = ? AND lease_token = ?::uuid
            |  AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP""".stripMargin
        )
        try
          statement.setString(1, safeFailure)
          bindLease(statement, 2, lease)
          if statement.executeUpdate() != 1 then throw LostCompensationLease(lease)
        finally statement.close()
      }
    }

  /** 查询单个计划。 */
  def get(compensationId: CompensationId): IO[StoreError, CompensationRecord] = withConnection { connection =>
    ZIO.attemptBlocking(load(connection, compensationId))
  }

  /** 读取并锁定计划，供 activate/cancel 状态机使用。 */
  private def loadForUpdate(connection: Connection, id: CompensationId): CompensationRecord =
    loadWithSuffix(connection, id, " FOR UPDATE")

  /** 普通读取计划。 */
  private def load(connection: Connection, id: CompensationId): CompensationRecord =
    loadWithSuffix(connection, id, "")

  /** 集中实现固定列读取，未知 ID 抛内部 sentinel。 */
  private def loadWithSuffix(connection: Connection, id: CompensationId, suffix: String): CompensationRecord =
    val statement = connection.prepareStatement(s"${selectColumns} WHERE compensation_id = ?::uuid$suffix")
    try
      statement.setString(1, id.asString)
      val result = statement.executeQuery()
      if result.next() then readRecord(result) else throw MissingCompensation(id)
    finally statement.close()

  /** 回收过期 worker。补偿 handler 必须幂等，因崩溃窗口可能重放。 */
  private def reclaimExpired(connection: Connection): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_compensations
        |SET status = 'Pending', last_failure = 'lease-expired', available_at = CURRENT_TIMESTAMP,
        |    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, heartbeat_at = NULL
        |WHERE status = 'Running' AND lease_expires_at <= CURRENT_TIMESTAMP""".stripMargin
    )
    try
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 自动尝试耗尽后转 DeadLetter。 */
  private def deadLetterExhausted(connection: Connection, maxAttempts: Int): Unit =
    val statement = connection.prepareStatement(
      "UPDATE agent_compensations SET status = 'DeadLetter', last_failure = 'max-attempts-exceeded' WHERE status = 'Pending' AND attempt >= ?"
    )
    try
      statement.setInt(1, maxAttempts)
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 非阻塞选择 Pending 热集合。 */
  private def selectCandidates(
      connection: Connection,
      batchSize: Int,
      maxAttempts: Int
  ): List[CompensationRecord] =
    val statement = connection.prepareStatement(
      s"""${selectColumns}
         |WHERE status = 'Pending' AND available_at <= CURRENT_TIMESTAMP AND attempt < ?
         |ORDER BY available_at ASC, created_at ASC, compensation_id ASC
         |FOR UPDATE SKIP LOCKED LIMIT ?""".stripMargin
    )
    try
      statement.setInt(1, maxAttempts)
      statement.setInt(2, batchSize)
      val result  = statement.executeQuery()
      val builder = List.newBuilder[CompensationRecord]
      while result.next() do builder += readRecord(result)
      builder.result()
    finally statement.close()

  /** 把锁定候选推进 Running 并写入 token/generation。 */
  private def claimOne(
      connection: Connection,
      id: CompensationId,
      owner: SideEffectWorkerId,
      token: SideEffectLeaseToken,
      generation: Long,
      duration: Duration
  ): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_compensations
        |SET status = 'Running', attempt = attempt + 1, generation = ?, lease_owner = ?, lease_token = ?::uuid,
        |    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), heartbeat_at = CURRENT_TIMESTAMP
        |WHERE compensation_id = ?::uuid AND status = 'Pending'""".stripMargin
    )
    try
      statement.setLong(1, generation)
      statement.setString(2, owner.value)
      statement.setString(3, token.asString)
      statement.setLong(4, duration.toMillis)
      statement.setString(5, id.asString)
      if statement.executeUpdate() != 1 then throw IllegalStateException(s"补偿 claim 失败: ${id.asString}")
    finally statement.close()

  /** fenced 无额外参数状态推进。 */
  private def transition(lease: CompensationLease, sql: String): IO[StoreError, Unit] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(sql)
        try
          bindLease(statement, 1, lease)
          if statement.executeUpdate() != 1 then throw LostCompensationLease(lease)
        finally statement.close()
      }
    }

  /** 绑定 compensation/owner/token/generation。 */
  private def bindLease(statement: PreparedStatement, start: Int, lease: CompensationLease): Unit =
    statement.setString(start, lease.record.compensationId.asString)
    statement.setString(start + 1, lease.owner.value)
    statement.setString(start + 2, lease.token.asString)
    statement.setLong(start + 3, lease.generation)

  /** 恢复补偿领域记录。 */
  private def readRecord(result: java.sql.ResultSet): CompensationRecord =
    val id = CompensationId
      .fromString(result.getString("compensation_id"))
      .fold(error => throw IllegalStateException(error), identity)
    val operationId = BusinessOperationId
      .fromString(result.getString("operation_id"))
      .fold(error => throw IllegalStateException(error), identity)
    val runId =
      RunId.fromString(result.getString("run_id")).fold(error => throw IllegalStateException(error), identity)
    val payload =
      result.getString("payload").fromJson[Json].fold(error => throw IllegalStateException(error), identity)
    CompensationRecord(
      id,
      operationId,
      runId,
      result.getString("scope_key"),
      CompensationDraft(result.getString("handler_name"), payload),
      CompensationStatus.valueOf(result.getString("status")),
      result.getInt("attempt"),
      result.getLong("generation"),
      instant(result, "available_at"),
      Option(result.getString("last_failure")),
      instant(result, "created_at"),
      Option(result.getObject("completed_at", classOf[OffsetDateTime])).map(_.toInstant)
    )

  /** 错误字段长度门禁。 */
  private def validateFailure(value: String): IO[StoreError, Unit] =
    ZIO.cond(
      value.trim.nonEmpty && value.length <= 200,
      (),
      AgentError.PersistenceFailure("补偿 safeFailure 必须为 1-200 个字符")
    )

  /** 数据库权威当前时间。 */
  private def databaseNow(connection: Connection): Instant =
    val statement = connection.prepareStatement("SELECT CURRENT_TIMESTAMP")
    try
      val result = statement.executeQuery()
      result.next()
      instant(result, 1)
    finally statement.close()

  /** 短事务模板。 */
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

  /** 借用并归还宿主连接。 */
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
        case MissingCompensation(id)                => AgentError.CompensationNotFound(id.asString)
        case InvalidCompensation(record, operation) =>
          AgentError.InvalidCompensationTransition(
            record.compensationId.asString,
            record.status.toString,
            operation
          )
        case LostCompensationLease(lease) =>
          AgentError.CompensationLeaseLost(
            lease.record.compensationId.asString,
            lease.owner.value,
            lease.generation
          )
        case error: StoreError => error
        case sql: SQLException =>
          val state     = Option(sql.getSQLState).getOrElse("unknown")
          val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
          AgentError.DatabaseFailure("PostgreSQL 补偿存储操作失败", state, retryable, Some(sql))
        case error => AgentError.PersistenceFailure("PostgreSQL 补偿存储操作失败", Some(error))
      }

  /** 写 TIMESTAMPTZ。 */
  private def setInstant(statement: PreparedStatement, index: Int, value: Instant): Unit =
    statement.setObject(index, value.atOffset(ZoneOffset.UTC))

  /** 按名称读取 TIMESTAMPTZ。 */
  private def instant(result: java.sql.ResultSet, column: String): Instant =
    result.getObject(column, classOf[OffsetDateTime]).toInstant

  /** 按序号读取 TIMESTAMPTZ。 */
  private def instant(result: java.sql.ResultSet, column: Int): Instant =
    result.getObject(column, classOf[OffsetDateTime]).toInstant

  /** 固定列清单。 */
  private val selectColumns =
    """SELECT compensation_id::text, operation_id::text, run_id::text, scope_key, handler_name, payload::text,
      |status, attempt, generation, available_at, last_failure, created_at, completed_at
      |FROM agent_compensations""".stripMargin

object PostgresCompensationStore:
  /** 使用宿主共享 DataSource 提供 CompensationStore。 */
  val layer: URLayer[DataSource, CompensationStore] =
    ZLayer.fromFunction((dataSource: DataSource) => new PostgresCompensationStore(dataSource))

final private case class MissingCompensation(id: CompensationId) extends RuntimeException
final private case class InvalidCompensation(record: CompensationRecord, operation: String)
    extends RuntimeException
final private case class LostCompensationLease(lease: CompensationLease) extends RuntimeException
