package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.sql.{Connection, PreparedStatement}
import java.time.{Instant, ZoneOffset}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** JDBC PostgreSQL RunStore。
  *
  * 连接由宿主 DataSource 管理；所有阻塞 JDBC 操作都放入 `attemptBlocking`，并通过 Scope 保证关闭。
  */
final class PostgresRunStore(dataSource: DataSource) extends RunStore:

  /** 在一个短事务中插入初始状态和首批领域事件，避免出现“Run 可查询但没有 RunCreated”或相反的半提交。
    *
    * @param state
    *   Runtime 构造的初始不可变状态
    * @param events
    *   以 sequence 从零开始的非空事件批次
    */
  def createWithEvents(state: AgentState, events: NonEmptyChunk[PersistedAgentEvent]): IO[StoreError, Unit] =
    RunStore.validateEventBatch(state, events, requireStartAtZero = true) *> withConnection { connection =>
      ZIO
        .attemptBlocking {
          connection.setAutoCommit(false)
          try
            val insertRun = connection.prepareStatement(
              """INSERT INTO agent_runs
              |(run_id, session_id, agent_id, status, version, schema_version, state_json, created_at, updated_at)
              |VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?::jsonb, ?, ?)""".stripMargin
            )
            try
              insertRun.setString(1, state.runId.asString)
              insertRun.setString(2, state.sessionId.asString)
              insertRun.setString(3, state.agentId.value)
              insertRun.setString(4, state.status.toString)
              insertRun.setLong(5, state.version.value)
              insertRun.setInt(6, state.schemaVersion)
              insertRun.setString(7, state.toJson)
              setInstant(insertRun, 8, state.createdAt)
              setInstant(insertRun, 9, state.updatedAt)
              insertRun.executeUpdate()
            finally insertRun.close()

            val insertEvent = connection.prepareStatement(
              """INSERT INTO agent_events(event_id, run_id, sequence, event_type, payload, created_at)
              |VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?)""".stripMargin
            )
            try
              events.foreach { event =>
                insertEvent.setString(1, event.eventId.asString)
                insertEvent.setString(2, state.runId.asString)
                insertEvent.setLong(3, event.sequence)
                insertEvent.setString(4, event.event.productPrefix)
                insertEvent.setString(5, event.toJson)
                setInstant(insertEvent, 6, Instant.ofEpochMilli(event.atEpochMilli))
                insertEvent.addBatch()
              }
              insertEvent.executeBatch()
            finally insertEvent.close()
            connection.commit()
          catch
            case error: Throwable =>
              try connection.rollback()
              catch case rollbackError: Throwable => error.addSuppressed(rollbackError)
              throw error
          finally connection.setAutoCommit(true)
        }
        .mapError(error => databaseError("事务创建 Agent Run 与初始事件失败", error))
    }

  /** 按 UUID 加载 JSONB 状态并用 zio-json 解码；不存在返回 RunNotFound。 */
  def load(runId: RunId): IO[StoreError, AgentState] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement =
          connection.prepareStatement("SELECT state_json::text FROM agent_runs WHERE run_id = ?::uuid")
        try
          statement.setString(1, runId.asString)
          val result = statement.executeQuery()
          if result.next() then
            result
              .getString(1)
              .fromJson[AgentState]
              .fold(error => throw IllegalStateException(error), identity)
          else throw java.util.NoSuchElementException(runId.asString)
        finally statement.close()
      }
      .mapError {
        case _: java.util.NoSuchElementException => AgentError.RunNotFound(runId)
        case error => AgentError.PersistenceFailure("加载 Agent Run 失败", Some(error))
      }
  }

  /** 使用 `WHERE version = expectedVersion` 原子更新；受影响行数为零时再读取实际版本， 从而区分乐观锁冲突与 Run 不存在。
    */
  def save(expectedVersion: Version, state: AgentState): IO[StoreError, Version] = withConnection {
    connection =>
      val next    = expectedVersion.next
      val updated = state.copy(version = next, updatedAt = Instant.now())
      ZIO
        .attemptBlocking {
          val statement = connection.prepareStatement(
            """UPDATE agent_runs SET status = ?, version = ?, schema_version = ?, state_json = ?::jsonb, updated_at = ?
          |WHERE run_id = ?::uuid AND version = ?""".stripMargin
          )
          try
            statement.setString(1, updated.status.toString)
            statement.setLong(2, next.value)
            statement.setInt(3, updated.schemaVersion)
            statement.setString(4, updated.toJson)
            setInstant(statement, 5, updated.updatedAt)
            statement.setString(6, state.runId.asString)
            statement.setLong(7, expectedVersion.value)
            statement.executeUpdate()
          finally statement.close()
        }
        .flatMap { changed =>
          if changed == 1 then ZIO.succeed(next)
          else
            currentVersion(connection, state.runId)
              .flatMap(actual => ZIO.fail(AgentError.OptimisticLock(expectedVersion, actual)))
        }
  }

  /** 在同一个短事务中提交状态 CAS 与事件追加。
    *
    * @param expectedVersion
    *   Runtime 读取到的状态版本
    * @param state
    *   新的完整状态快照；本方法会把其版本更新为 `expectedVersion.next`
    * @param events
    *   与状态转换对应的事件；事件 ID 冲突可幂等忽略，但 sequence 冲突会令事务失败
    * @return
    *   成功提交后的版本
    *
    * 事务中不执行模型调用、网络调用或工具业务逻辑，避免长期占用连接和行锁。
    */
  def commit(
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent]
  ): IO[StoreError, Version] = commitInternal(None, expectedVersion, state, events)

  /** 以数据库租约行锁保护状态提交。
    *
    * `lockActiveLease` 在事务开始时对匹配的 queue 行取得 `FOR SHARE` 锁，并一直持有到状态与事件提交完成。 claim、heartbeat、complete、abandon
    * 都需要更新同一 queue 行，因此不能在验证后抢先改变 generation；这消除了 “应用层先检查租约、随后普通 commit”之间的 TOCTOU 窗口。
    */
  def commitFenced(
      lease: RunCommandLease,
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent]
  ): IO[StoreError, Version] =
    if lease.runId != state.runId then
      ZIO.fail(
        AgentError.LeaseLost(state.runId, lease.owner.value, lease.generation, "租约与 AgentState 不属于同一 Run")
      )
    else commitInternal(Some(lease), expectedVersion, state, events)

  /** 普通提交与 fenced 提交共享完全相同的状态/事件事务，只在事务入口增加可选租约锁。 */
  private def commitInternal(
      lease: Option[RunCommandLease],
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent]
  ): IO[StoreError, Version] =
    RunStore.validateEventBatch(state, events, requireStartAtZero = false) *> withConnection { connection =>
      val next    = expectedVersion.next
      val updated = state.copy(version = next, updatedAt = Instant.now())
      ZIO
        .attemptBlocking {
          connection.setAutoCommit(false)
          try
            lease.foreach(value => lockActiveLease(connection, value))
            val update = connection.prepareStatement(
              """UPDATE agent_runs SET status = ?, version = ?, schema_version = ?, state_json = ?::jsonb, updated_at = ?
            |WHERE run_id = ?::uuid AND version = ?""".stripMargin
            )
            val changed =
              try
                update.setString(1, updated.status.toString)
                update.setLong(2, next.value)
                update.setInt(3, updated.schemaVersion)
                update.setString(4, updated.toJson)
                setInstant(update, 5, updated.updatedAt)
                update.setString(6, state.runId.asString)
                update.setLong(7, expectedVersion.value)
                update.executeUpdate()
              finally update.close()

            if changed != 1 then
              val query = connection.prepareStatement("SELECT version FROM agent_runs WHERE run_id = ?::uuid")
              try
                query.setString(1, state.runId.asString)
                val result = query.executeQuery()
                if result.next() then throw VersionConflict(result.getLong(1))
                else throw MissingRun(state.runId)
              finally query.close()

            val insert = connection.prepareStatement(
              """INSERT INTO agent_events(event_id, run_id, sequence, event_type, payload, created_at)
            |VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?)
            |ON CONFLICT (event_id) DO NOTHING""".stripMargin
            )
            try
              events.foreach { event =>
                insert.setString(1, event.eventId.asString)
                insert.setString(2, state.runId.asString)
                insert.setLong(3, event.sequence)
                insert.setString(4, event.event.productPrefix)
                insert.setString(5, event.toJson)
                setInstant(insert, 6, Instant.ofEpochMilli(event.atEpochMilli))
                insert.addBatch()
              }
              insert.executeBatch()
            finally insert.close()

            connection.commit()
            next
          catch
            case error: Throwable =>
              try connection.rollback()
              catch case rollbackError: Throwable => error.addSuppressed(rollbackError)
              throw error
          finally connection.setAutoCommit(true)
        }
        .mapError {
          case VersionConflict(actual) => AgentError.OptimisticLock(expectedVersion, Version(actual))
          case MissingRun(runId)       => AgentError.RunNotFound(runId)
          case LostStateLease(value)   =>
            AgentError.LeaseLost(value.runId, value.owner.value, value.generation, "AgentState 提交时租约已过期或被抢占")
          case error => databaseError("事务提交 Agent 状态与事件失败", error)
        }
    }

  /** 批量追加事件；event_id 冲突时忽略以提供幂等重试。 */
  def appendEvents(runId: RunId, events: NonEmptyChunk[PersistedAgentEvent]): IO[StoreError, Unit] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """INSERT INTO agent_events(event_id, run_id, sequence, event_type, payload, created_at)
          |VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?)
          |ON CONFLICT (event_id) DO NOTHING""".stripMargin
        )
        try
          events.foreach { event =>
            statement.setString(1, event.eventId.asString)
            statement.setString(2, runId.asString)
            statement.setLong(3, event.sequence)
            statement.setString(4, event.event.productPrefix)
            statement.setString(5, event.toJson)
            setInstant(statement, 6, Instant.ofEpochMilli(event.atEpochMilli))
            statement.addBatch()
          }
          statement.executeBatch()
          ()
        finally statement.close()
      }
    }

  /** 按 sequence 游标有界读取事件，供恢复、审计和跨节点 SSE 增量订阅使用。 */
  def events(runId: RunId, afterSequence: Long, limit: Int): IO[StoreError, Chunk[PersistedAgentEvent]] =
    if limit <= 0 then ZIO.fail(AgentError.PersistenceFailure("事件查询 limit 必须为正数"))
    else
      withConnection { connection =>
        ZIO.attemptBlocking {
          val statement = connection.prepareStatement(
            "SELECT payload::text FROM agent_events WHERE run_id = ?::uuid AND sequence > ? ORDER BY sequence LIMIT ?"
          )
          try
            statement.setString(1, runId.asString)
            statement.setLong(2, afterSequence)
            statement.setInt(3, limit)
            val result  = statement.executeQuery()
            val builder = ChunkBuilder.make[PersistedAgentEvent]()
            while result.next() do
              builder += result
                .getString(1)
                .fromJson[PersistedAgentEvent]
                .fold(error => throw IllegalStateException(error), identity)
            builder.result()
          finally statement.close()
        }
      }

  /** 持久化取消位；重复请求仍保持 true。 */
  def requestCancellation(runId: RunId): IO[StoreError, Unit] = withConnection { connection =>
    executeUpdate(
      connection,
      "UPDATE agent_runs SET cancel_requested = TRUE WHERE run_id = ?::uuid",
      runId.asString
    )
      .flatMap(count => if count == 1 then ZIO.unit else ZIO.fail(AgentError.RunNotFound(runId)))
  }

  /** 查询取消位；未知 Run 显式失败。 */
  def cancellationRequested(runId: RunId): IO[StoreError, Boolean] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement =
          connection.prepareStatement("SELECT cancel_requested FROM agent_runs WHERE run_id = ?::uuid")
        try
          statement.setString(1, runId.asString)
          val result = statement.executeQuery()
          if result.next() then result.getBoolean(1)
          else throw java.util.NoSuchElementException(runId.asString)
        finally statement.close()
      }
      .mapError {
        case _: java.util.NoSuchElementException => AgentError.RunNotFound(runId)
        case error                               => AgentError.PersistenceFailure("读取取消状态失败", Some(error))
      }
  }

  /** 在一个短事务中插入整批 Prepared pending writes。 `ON CONFLICT DO NOTHING` 只为恢复幂等，绝不把已有 Succeeded/Running 状态回退成
    * Prepared。
    */
  def prepareToolExecutions(records: NonEmptyChunk[ToolExecutionRecord]): IO[StoreError, Unit] =
    RunStore.validateToolBatch(records) *> withConnection { connection =>
      ZIO
        .attemptBlocking {
          connection.setAutoCommit(false)
          val statement = connection.prepareStatement(
            """INSERT INTO tool_executions
          |(run_id, batch_id, ordinal, call_id, tool_name, idempotency_key, status, attempt, record_json, updated_at)
          |VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
          |ON CONFLICT (run_id, call_id) DO NOTHING""".stripMargin
          )
          try
            records.foreach { record =>
              statement.setString(1, record.runId.asString)
              statement.setString(2, record.batchId)
              statement.setInt(3, record.ordinal)
              statement.setString(4, record.callId)
              statement.setString(5, record.toolName)
              statement.setString(6, record.idempotencyKey.orNull)
              statement.setString(7, record.status.toString)
              statement.setInt(8, record.attempt)
              statement.setString(9, record.toJson)
              setInstant(statement, 10, Instant.ofEpochMilli(record.updatedAtEpochMilli))
              statement.addBatch()
            }
            statement.executeBatch()
            // ON CONFLICT 只允许“同一逻辑调用的恢复重放”。若 Provider 在同一 Run 中复用了 callId，
            // 不能把旧批次的 Succeeded 结果错误嫁接到新调用，因此提交前逐条核对不可变身份字段。
            val verify = connection.prepareStatement(
              "SELECT record_json::text FROM tool_executions WHERE run_id = ?::uuid AND call_id = ?"
            )
            try
              records.foreach { expected =>
                verify.setString(1, expected.runId.asString)
                verify.setString(2, expected.callId)
                val result   = verify.executeQuery()
                val existing =
                  if result.next() then
                    result
                      .getString(1)
                      .fromJson[ToolExecutionRecord]
                      .fold(error => throw IllegalStateException(error), identity)
                  else throw ToolLedgerIdentityConflict(expected.callId)
                if !RunStore.sameToolExecutionIdentity(existing, expected) then
                  throw ToolLedgerIdentityConflict(expected.callId)
              }
            finally verify.close()
            connection.commit()
            ()
          catch
            case error: Throwable =>
              try connection.rollback()
              catch case rollbackError: Throwable => error.addSuppressed(rollbackError)
              throw error
          finally statement.close()
        }
        .ensuring(ZIO.attemptBlocking(connection.setAutoCommit(true)).orDie)
        .mapError {
          case ToolLedgerIdentityConflict(callId) =>
            AgentError.PersistenceFailure(s"工具 callId $callId 已属于其他批次或 ordinal，拒绝错误复用账本")
          case error => databaseError("批量准备工具执行账本失败", error)
        }
    }

  /** 通过 status+attempt 条件 UPDATE 推进账本，防止迟到 Fiber 覆盖更新结果。
    */
  def transitionToolExecution(
      expectedStatus: ToolExecutionStatus,
      expectedAttempt: Int,
      next: ToolExecutionRecord
  ): IO[StoreError, ToolExecutionRecord] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(
          """UPDATE tool_executions SET status = ?, attempt = ?, record_json = ?::jsonb, updated_at = ?
          |WHERE run_id = ?::uuid AND call_id = ? AND status = ? AND attempt = ?
          |  AND batch_id = ? AND ordinal = ? AND tool_name = ?
          |  AND idempotency_key IS NOT DISTINCT FROM ?""".stripMargin
        )
        try
          statement.setString(1, next.status.toString)
          statement.setInt(2, next.attempt)
          statement.setString(3, next.toJson)
          setInstant(statement, 4, Instant.ofEpochMilli(next.updatedAtEpochMilli))
          statement.setString(5, next.runId.asString)
          statement.setString(6, next.callId)
          statement.setString(7, expectedStatus.toString)
          statement.setInt(8, expectedAttempt)
          statement.setString(9, next.batchId)
          statement.setInt(10, next.ordinal)
          statement.setString(11, next.toolName)
          statement.setString(12, next.idempotencyKey.orNull)
          if statement.executeUpdate() == 1 then next
          else throw ToolLedgerConflict
        finally statement.close()
      }
      .mapError {
        case ToolLedgerConflict =>
          AgentError.ToolExecutionConflict(next.runId, next.callId, expectedStatus.toString, expectedAttempt)
        case error => databaseError("推进工具执行账本失败", error)
      }
  }

  /** 查询指定工具调用的最新执行记录。 */
  def getToolExecution(runId: RunId, callId: String): IO[StoreError, Option[ToolExecutionRecord]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          "SELECT record_json::text FROM tool_executions WHERE run_id = ?::uuid AND call_id = ?"
        )
        try
          statement.setString(1, runId.asString)
          statement.setString(2, callId)
          val result = statement.executeQuery()
          Option.when(result.next())(
            result
              .getString(1)
              .fromJson[ToolExecutionRecord]
              .fold(error => throw IllegalStateException(error), identity)
          )
        finally statement.close()
      }
    }

  /** 按 batch_id 查询 pending writes，并用 ordinal 恢复 Provider 原始顺序。 */
  def getToolExecutions(runId: RunId, batchId: String): IO[StoreError, Chunk[ToolExecutionRecord]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          "SELECT record_json::text FROM tool_executions WHERE run_id = ?::uuid AND batch_id = ? ORDER BY ordinal"
        )
        try
          statement.setString(1, runId.asString)
          statement.setString(2, batchId)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[ToolExecutionRecord]()
          while result.next() do
            builder += result
              .getString(1)
              .fromJson[ToolExecutionRecord]
              .fold(error => throw IllegalStateException(error), identity)
          builder.result()
        finally statement.close()
      }
    }

  /** 外键均配置 `ON DELETE CASCADE`，因此删除主 Run 即可原子清理事件、步骤和审批记录。 */
  def delete(runId: RunId): IO[StoreError, Unit] = withConnection { connection =>
    executeUpdate(connection, "DELETE FROM agent_runs WHERE run_id = ?::uuid", runId.asString)
      .flatMap(count => if count == 1 then ZIO.unit else ZIO.fail(AgentError.RunNotFound(runId)))
  }

  /** 读取当前版本，专用于生成准确的 OptimisticLock 错误。 */
  private def currentVersion(connection: Connection, runId: RunId): IO[StoreError, Version] =
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement("SELECT version FROM agent_runs WHERE run_id = ?::uuid")
        try
          statement.setString(1, runId.asString)
          val result = statement.executeQuery()
          if result.next() then Version(result.getLong(1))
          else throw java.util.NoSuchElementException(runId.asString)
        finally statement.close()
      }
      .mapError {
        case _: java.util.NoSuchElementException => AgentError.RunNotFound(runId)
        case error                               => AgentError.PersistenceFailure("读取版本失败", Some(error))
      }

  /** 在当前事务中验证并锁住有效租约行。
    *
    * `FOR SHARE` 不会阻塞其他只读诊断，但会阻止 claim/heartbeat/complete/abandon 对该行的 UPDATE，直到短事务提交。 参数必须同时匹配
    * runId、owner、随机 token、generation 和数据库权威时钟下的未过期条件。
    */
  private def lockActiveLease(connection: Connection, lease: RunCommandLease): Unit =
    val statement = connection.prepareStatement(
      """SELECT 1 FROM agent_run_dispatch
        |WHERE run_id = ?::uuid AND status = 'Leased' AND current_command_id = ?::uuid
        |  AND lease_owner = ? AND lease_token = ?::uuid AND generation = ?
        |  AND lease_expires_at > CURRENT_TIMESTAMP
        |FOR SHARE""".stripMargin
    )
    try
      statement.setString(1, lease.runId.asString)
      statement.setString(2, lease.commandId.asString)
      statement.setString(3, lease.owner.value)
      statement.setString(4, lease.token.value)
      statement.setLong(5, lease.generation)
      val result = statement.executeQuery()
      if !result.next() then throw LostStateLease(lease)
    finally statement.close()

  /** 执行单参数更新并返回受影响行数。 */
  private def executeUpdate(connection: Connection, sql: String, value: String): Task[Int] =
    ZIO.attemptBlocking {
      val statement = connection.prepareStatement(sql)
      try
        statement.setString(1, value)
        statement.executeUpdate()
      finally statement.close()
    }

  /** 从宿主 DataSource 借用连接并在 Scope 结束时归还。 JDBC 是阻塞 API，所以获取、使用和关闭都放入 attemptBlocking，不占用 ZIO 计算线程池。
    */
  private def withConnection[A](use: Connection => Task[A]): IO[StoreError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(
            ZIO
              .attemptBlocking(dataSource.getConnection)
              .mapError(error => AgentError.PersistenceFailure("获取数据库连接失败", Some(error)))
          )(connection => ZIO.attemptBlocking(connection.close()).orDie)
          .flatMap(use)
      }
      .mapError {
        case error: StoreError => error
        case error             => AgentError.PersistenceFailure(error.getMessage, Some(error))
      }

  /** 将 JDBC 异常的 SQLSTATE 分类为稳定、可用于重试决策的框架错误。 */
  private def databaseError(operation: String, error: Throwable): StoreError =
    error match
      case sql: java.sql.SQLException =>
        val state     = Option(sql.getSQLState).getOrElse("unknown")
        val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
        AgentError.DatabaseFailure(operation, state, retryable, Some(sql))
      case other => AgentError.PersistenceFailure(operation, Some(other))

  /** 以 UTC `OffsetDateTime` 写入 PostgreSQL `TIMESTAMPTZ`。 PostgreSQL JDBC 不会为裸 `Instant` 自动推断 SQL
    * 类型；集中转换可避免不同方法出现隐蔽的运行时差异。
    *
    * @param statement
    *   当前预编译语句
    * @param index
    *   JDBC 从 1 开始的参数位置
    * @param value
    *   与时区无关的绝对时间点
    */
  private def setInstant(statement: PreparedStatement, index: Int, value: Instant): Unit =
    statement.setObject(index, value.atOffset(ZoneOffset.UTC))

final private case class VersionConflict(actual: Long)              extends RuntimeException
final private case class MissingRun(runId: RunId)                   extends RuntimeException
private case object ToolLedgerConflict                              extends RuntimeException
final private case class ToolLedgerIdentityConflict(callId: String) extends RuntimeException
final private case class LostStateLease(lease: RunCommandLease)     extends RuntimeException

object PostgresRunStore:
  val layer: URLayer[DataSource, RunStore] = ZLayer.fromFunction(PostgresRunStore.apply)
