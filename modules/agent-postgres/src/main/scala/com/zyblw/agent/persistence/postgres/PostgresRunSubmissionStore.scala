package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import java.time.{Instant, ZoneOffset}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL 异步 Run 创建 Adapter。
  *
  * 一个事务依次写入 `agent_runs`、`agent_events`、`agent_run_commands` 与 `agent_run_dispatch`。事务提交后 Worker 才可能 claim
  * Start；事务回滚时四类事实全部不可见，因此不会出现“可查询但永远不会执行”的孤儿 Run。
  *
  * @param dataSource
  *   由宿主应用配置连接池的 DataSource；本类不自行创建或泄漏 JDBC 连接
  */
final class PostgresRunSubmissionStore(dataSource: DataSource) extends RunSubmissionStore:
  /** 内部异常：同一作用域/键已绑定另一请求指纹。 */
  final private case class StartConflict(key: String) extends RuntimeException

  /** 内部异常：数据库中出现了 Run 但缺失 Start 命令，说明事务外写入或数据损坏。 */
  final private case class MissingStartCommand(runId: RunId) extends RuntimeException

  /** 原子提交或幂等读取既有提交。
    *
    * 并发相同请求依赖唯一索引 `(start_scope_hash, start_idempotency_key)` 串行化。PostgreSQL 的 `ON CONFLICT` 会等待
    * 对手事务完成；随后当前事务读取其 requestHash 和 Start 命令，所以两个 HTTP 节点会返回同一个 runId/commandId。
    */
  def submitStart(submission: RunStartSubmission): IO[StoreError, RunCommandRecord] =
    RunStore.validateEventBatch(
      submission.state,
      NonEmptyChunk(submission.createdEvent),
      requireStartAtZero = true
    ) *>
      CommandId.random.flatMap { commandId =>
        withTransaction { connection =>
          ZIO
            .attemptBlocking {
              val inserted = insertRun(connection, submission)
              if inserted then
                insertCreatedEvent(connection, submission.createdEvent)
                val command = insertStartCommand(connection, commandId, submission.state.runId)
                insertDispatch(connection, submission.state.runId)
                command
              else
                val (knownRunId, knownHash) = loadSubmission(connection, submission)
                if knownHash != submission.requestHash then throw StartConflict(submission.idempotencyKey)
                loadStartCommand(connection, knownRunId)
            }
            .mapError {
              case StartConflict(key)      => AgentError.RunSubmissionConflict(key)
              case MissingStartCommand(id) => AgentError.PersistenceFailure(s"Run ${id.asString} 缺少 Start 命令")
              case error                   => databaseError("原子提交 Agent Run 与 Start 命令失败", error)
            }
        }
      }

  /** 插入带全局幂等信息的 Created 状态；冲突时返回 false，不覆盖原状态。 */
  private def insertRun(connection: Connection, submission: RunStartSubmission): Boolean =
    val state     = submission.state
    val statement = connection.prepareStatement(
      """INSERT INTO agent_runs
        |(run_id, session_id, agent_id, status, version, schema_version, state_json, cancel_requested,
        | start_scope_hash, start_idempotency_key, start_request_hash, created_at, updated_at)
        |VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?::jsonb, FALSE, ?, ?, ?, ?, ?)
        |ON CONFLICT (start_scope_hash, start_idempotency_key) WHERE start_scope_hash IS NOT NULL DO NOTHING
        |RETURNING run_id""".stripMargin
    )
    try
      statement.setString(1, state.runId.asString)
      statement.setString(2, state.sessionId.asString)
      statement.setString(3, state.agentId.value)
      statement.setString(4, state.status.toString)
      statement.setLong(5, state.version.value)
      statement.setInt(6, state.schemaVersion)
      statement.setString(7, state.toJson)
      statement.setString(8, submission.submissionScopeHash)
      statement.setString(9, submission.idempotencyKey)
      statement.setString(10, submission.requestHash)
      setInstant(statement, 11, state.createdAt)
      setInstant(statement, 12, state.updatedAt)
      statement.executeQuery().next()
    finally statement.close()

  /** 写入 sequence=0 的 RunCreated；调用前已经由 SPI 校验事件归属和连续性。 */
  private def insertCreatedEvent(connection: Connection, event: PersistedAgentEvent): Unit =
    val statement = connection.prepareStatement(
      """INSERT INTO agent_events(event_id, run_id, sequence, event_type, payload, created_at)
        |VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?)""".stripMargin
    )
    try
      statement.setString(1, event.eventId.asString)
      statement.setString(2, event.runId.asString)
      statement.setLong(3, event.sequence)
      statement.setString(4, event.event.productPrefix)
      statement.setString(5, event.toJson)
      setInstant(statement, 6, Instant.ofEpochMilli(event.atEpochMilli))
      val _ = statement.executeUpdate()
    finally statement.close()

  /** Start 命令只引用已冻结状态，不重复保存输入和 Agent 定义。 */
  private def insertStartCommand(
      connection: Connection,
      commandId: CommandId,
      runId: RunId
  ): RunCommandRecord =
    val payload   = RunCommandPayload.Start
    val statement = connection.prepareStatement(
      """INSERT INTO agent_run_commands
        |(command_id, run_id, command_type, payload, idempotency_key, status, priority, available_at)
        |VALUES (?::uuid, ?::uuid, 'Start', ?::jsonb, 'start', 'Queued', 0, CURRENT_TIMESTAMP)
        |RETURNING command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
        |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at""".stripMargin
    )
    try
      statement.setString(1, commandId.asString)
      statement.setString(2, runId.asString)
      statement.setString(3, payload.toJson)
      val result = statement.executeQuery()
      if result.next() then decodeRecord(result) else throw MissingStartCommand(runId)
    finally statement.close()

  /** 每个 Run 创建唯一 dispatcher，并立即标记 Queued 供 WorkerHost claim。 */
  private def insertDispatch(connection: Connection, runId: RunId): Unit =
    val statement = connection.prepareStatement(
      "INSERT INTO agent_run_dispatch(run_id, status) VALUES (?::uuid, 'Queued')"
    )
    try
      statement.setString(1, runId.asString)
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 读取唯一索引对应的既有 runId/requestHash；仅在 INSERT 冲突后调用。 */
  private def loadSubmission(connection: Connection, submission: RunStartSubmission): (RunId, String) =
    val statement = connection.prepareStatement(
      """SELECT run_id, start_request_hash FROM agent_runs
        |WHERE start_scope_hash = ? AND start_idempotency_key = ?""".stripMargin
    )
    try
      statement.setString(1, submission.submissionScopeHash)
      statement.setString(2, submission.idempotencyKey)
      val result = statement.executeQuery()
      if result.next() then
        val runId = RunId
          .fromString(result.getString("run_id"))
          .fold(error => throw IllegalStateException(error), identity)
        runId -> result.getString("start_request_hash").trim
      else throw IllegalStateException("幂等唯一索引冲突后未找到既有 Run")
    finally statement.close()

  /** 返回既有 Start 命令；排序只是数据损坏时保持诊断结果确定，不允许创建第二条。 */
  private def loadStartCommand(connection: Connection, runId: RunId): RunCommandRecord =
    val statement = connection.prepareStatement(
      """SELECT command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
        |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at
        |FROM agent_run_commands WHERE run_id = ?::uuid AND command_type = 'Start'
        |ORDER BY created_at ASC, command_id ASC LIMIT 1""".stripMargin
    )
    try
      statement.setString(1, runId.asString)
      val result = statement.executeQuery()
      if result.next() then decodeRecord(result) else throw MissingStartCommand(runId)
    finally statement.close()

  /** 将 JDBC 行还原为框架命令；未知枚举或坏 JSON 作为持久化损坏失败。 */
  private def decodeRecord(result: ResultSet): RunCommandRecord =
    val commandId = CommandId
      .fromString(result.getString("command_id"))
      .fold(error => throw IllegalStateException(error), identity)
    val runId =
      RunId.fromString(result.getString("run_id")).fold(error => throw IllegalStateException(error), identity)
    val payload = result
      .getString("payload")
      .fromJson[RunCommandPayload]
      .fold(error => throw IllegalStateException(error), identity)
    RunCommandRecord(
      commandId,
      runId,
      payload,
      result.getString("idempotency_key"),
      RunCommandStatus.valueOf(result.getString("status")),
      result.getInt("priority"),
      instant(result, "available_at"),
      result.getInt("attempt"),
      result.getInt("manual_retry_count"),
      Option(result.getString("last_failure")),
      instant(result, "created_at"),
      instant(result, "updated_at")
    )

  /** 在 Scope 中借还连接，并把事务成功/失败分别映射为 commit/rollback。 */
  private def withTransaction[A](use: Connection => Task[A]): IO[StoreError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(ZIO.attemptBlocking(dataSource.getConnection))(connection =>
            ZIO.attemptBlocking(connection.close()).orDie
          )
          .flatMap { connection =>
            ZIO.attemptBlocking(connection.setAutoCommit(false)) *>
              use(connection)
                .tapBoth(
                  _ => ZIO.attemptBlocking(connection.rollback()).orDie,
                  _ => ZIO.attemptBlocking(connection.commit()).orDie
                )
                .ensuring(ZIO.attemptBlocking(connection.setAutoCommit(true)).ignore)
          }
      }
      .mapError(error => databaseError("执行 Run 创建事务失败", error))

  /** 保留 SQLSTATE 与可重试分类，但不把 SQL 或参数暴露给调用方。 */
  private def databaseError(operation: String, error: Throwable): StoreError = error match
    case known: StoreError => known
    case sql: SQLException =>
      val state     = Option(sql.getSQLState).getOrElse("unknown")
      val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "53300"
      AgentError.DatabaseFailure(operation, state, retryable, Some(sql))
    case other => AgentError.PersistenceFailure(operation, Some(other))

  /** 统一按 UTC 写入 TIMESTAMPTZ，避免宿主 JVM 默认时区影响恢复测试。 */
  private def setInstant(statement: PreparedStatement, index: Int, value: Instant): Unit =
    statement.setObject(index, value.atOffset(ZoneOffset.UTC))

  /** PostgreSQL 驱动可能返回 OffsetDateTime 或 Timestamp；统一转换成 Instant。 */
  private def instant(result: ResultSet, column: String): Instant =
    Option(result.getObject(column, classOf[java.time.OffsetDateTime]))
      .map(_.toInstant)
      .getOrElse(result.getTimestamp(column).toInstant)

object PostgresRunSubmissionStore:
  /** 生产 ZLayer；与 RunStore/RunCommandStore 共享同一个宿主 DataSource 和连接池策略。 */
  val layer: URLayer[DataSource, RunSubmissionStore] = ZLayer.fromFunction(PostgresRunSubmissionStore.apply)
