package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.sql.{Connection, PreparedStatement, ResultSet}
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

  /** 按 UUID 加载 JSONB 状态并用 zio-json 解码；不存在返回 RunNotFound。
    *
    * JSON 快照与关系列共同构成持久化信封。读取时交叉核对稳定身份、状态、版本和 schemaVersion，避免人工修库、旧版本缺陷或不完整迁移造成的静默漂移。
    */
  def load(runId: RunId): IO[StoreError, AgentState] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT run_id::text, session_id::text, agent_id, status, version, schema_version, state_json::text
            |FROM agent_runs WHERE run_id = ?::uuid""".stripMargin
        )
        try
          statement.setString(1, runId.asString)
          val result = statement.executeQuery()
          if result.next() then decodeStateEnvelope(result, runId)
          else throw java.util.NoSuchElementException(runId.asString)
        finally statement.close()
      }
      .mapError {
        case _: java.util.NoSuchElementException => AgentError.RunNotFound(runId)
        case error: PersistedEnvelopeConflict => AgentError.PersistenceFailure(error.getMessage, Some(error))
        case error                            => AgentError.PersistenceFailure("加载 Agent Run 失败", Some(error))
      }
  }

  /** 使用 `WHERE version = expectedVersion` 原子更新；无事件的 save 不能改变 `lastEventSequence`。受影响行数为零时再读取实际位置，
    * 从而区分乐观锁冲突、事件游标漂移与 Run 不存在。
    */
  def save(expectedVersion: Version, state: AgentState): IO[StoreError, Version] = withConnection {
    connection =>
      val next    = expectedVersion.next
      val updated = state.copy(version = next, updatedAt = Instant.now())
      ZIO
        .attemptBlocking {
          val statement = connection.prepareStatement(
            """UPDATE agent_runs SET status = ?, version = ?, schema_version = ?, state_json = ?::jsonb, updated_at = ?
          |WHERE run_id = ?::uuid AND version = ?
          |  AND COALESCE(state_json ->> 'lastEventSequence', '-1')::bigint = ?""".stripMargin
          )
          try
            statement.setString(1, updated.status.toString)
            statement.setLong(2, next.value)
            statement.setInt(3, updated.schemaVersion)
            statement.setString(4, updated.toJson)
            setInstant(statement, 5, updated.updatedAt)
            statement.setString(6, state.runId.asString)
            statement.setLong(7, expectedVersion.value)
            statement.setLong(8, state.lastEventSequence)
            statement.executeUpdate()
          finally statement.close()
        }
        .flatMap { changed =>
          if changed == 1 then ZIO.succeed(next)
          else
            currentRunPosition(connection, state.runId).flatMap { case (actualVersion, actualSequence) =>
              if actualVersion != expectedVersion then
                ZIO.fail(AgentError.OptimisticLock(expectedVersion, actualVersion))
              else
                ZIO.fail(
                  AgentError.PersistenceFailure(
                    s"save 不能改变事件游标: runId=${state.runId.asString}, expected=$actualSequence, incoming=${state.lastEventSequence}"
                  )
                )
            }
        }
  }

  /** 在同一个短事务中提交状态 CAS 与事件追加。
    *
    * @param expectedVersion
    *   Runtime 读取到的状态版本
    * @param state
    *   新的完整状态快照；本方法会把其版本更新为 `expectedVersion.next`
    * @param events
    *   与状态转换对应的事件；只有完整事件相同的事件 ID 冲突可幂等忽略，身份漂移或 sequence 冲突会令事务失败
    * @return
    *   成功提交后的版本
    *
    * 事务中不执行模型调用、网络调用或工具业务逻辑，避免长期占用连接和行锁。
    */
  def commit(
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent],
      modelCall: Option[ModelCallWrite]
  ): IO[StoreError, Version] = commitInternal(None, expectedVersion, state, events, modelCall)

  /** 以数据库租约行锁保护状态提交。
    *
    * `lockActiveLease` 在事务开始时对匹配的 queue 行取得 `FOR SHARE` 锁，并一直持有到状态与事件提交完成。 claim、heartbeat、complete、abandon
    * 都需要更新同一 queue 行，因此不能在验证后抢先改变 generation；这消除了 “应用层先检查租约、随后普通 commit”之间的 TOCTOU 窗口。
    */
  def commitFenced(
      lease: RunCommandLease,
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent],
      modelCall: Option[ModelCallWrite]
  ): IO[StoreError, Version] =
    if lease.runId != state.runId then
      ZIO.fail(
        AgentError.LeaseLost(state.runId, lease.owner.value, lease.generation, "租约与 AgentState 不属于同一 Run")
      )
    else commitInternal(Some(lease), expectedVersion, state, events, modelCall)

  /** 普通提交与 fenced 提交共享完全相同的状态/事件事务，只在事务入口增加可选租约锁。 */
  private def commitInternal(
      lease: Option[RunCommandLease],
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent],
      modelCall: Option[ModelCallWrite]
  ): IO[StoreError, Version] =
    RunStore.validateEventBatch(state, events, requireStartAtZero = false) *> withConnection { connection =>
      val next                  = expectedVersion.next
      val updated               = state.copy(version = next, updatedAt = Instant.now())
      val previousEventSequence = events.head.sequence - 1L
      ZIO
        .attemptBlocking {
          connection.setAutoCommit(false)
          try
            lease.foreach(value => lockActiveLease(connection, value))
            val update = connection.prepareStatement(
              """UPDATE agent_runs SET status = ?, version = ?, schema_version = ?, state_json = ?::jsonb, updated_at = ?
            |WHERE run_id = ?::uuid AND version = ?
            |  AND COALESCE(state_json ->> 'lastEventSequence', '-1')::bigint = ?""".stripMargin
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
                update.setLong(8, previousEventSequence)
                update.executeUpdate()
              finally update.close()

            if changed != 1 then
              val query = connection.prepareStatement(
                """SELECT version, COALESCE(state_json ->> 'lastEventSequence', '-1')::bigint
                  |FROM agent_runs WHERE run_id = ?::uuid""".stripMargin
              )
              try
                query.setString(1, state.runId.asString)
                val result = query.executeQuery()
                if result.next() then
                  val actualVersion  = result.getLong(1)
                  val actualSequence = result.getLong(2)
                  if actualVersion != expectedVersion.value then throw VersionConflict(actualVersion)
                  else throw EventSequenceConflict(previousEventSequence, actualSequence)
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

            verifyEventIdentities(connection, events)

            writeModelCall(connection, modelCall)

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
          case ModelCallLedgerConflict(requestId, expectedStatus, expectedAttempt) =>
            AgentError.ModelCallConflict(state.runId, requestId, expectedStatus, expectedAttempt)
          case ModelCallIdentityConflict(requestId) =>
            AgentError.PersistenceFailure(s"模型调用 $requestId 已属于其他 fingerprint 或模型，拒绝错误复用账本")
          case EventIdentityConflict(eventId) =>
            AgentError.PersistenceFailure(s"事件 $eventId 已属于其他 Run、sequence 或 payload，拒绝错误幂等复用")
          case EventSequenceConflict(expected, actual) =>
            AgentError.PersistenceFailure(
              s"事件批次没有紧接已提交游标: runId=${state.runId.asString}, expectedPrevious=$expected, actualPrevious=$actual"
            )
          case error => databaseError("事务提交 Agent 状态与事件失败", error)
        }
    }

  /** 幂等重放/补齐不超过状态游标的事件；新状态转换必须使用 `commit`。 */
  def appendEvents(runId: RunId, events: NonEmptyChunk[PersistedAgentEvent]): IO[StoreError, Unit] =
    RunStore.validateAppendedEvents(runId, events) *> withConnection { connection =>
      ZIO
        .attemptBlocking {
          connection.setAutoCommit(false)
          try
            lockEventAppendBoundary(connection, runId, events.map(_.sequence).max)
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
            finally statement.close()
            verifyEventIdentities(connection, events)
            connection.commit()
          catch
            case error: Throwable =>
              try connection.rollback()
              catch case rollbackError: Throwable => error.addSuppressed(rollbackError)
              throw error
          finally connection.setAutoCommit(true)
        }
        .mapError {
          case MissingRun(missingRunId)                 => AgentError.RunNotFound(missingRunId)
          case EventAppendAhead(stateLast, incomingMax) =>
            AgentError.PersistenceFailure(
              s"appendEvents 不能推进状态事件游标: runId=${runId.asString}, stateLast=$stateLast, incomingMax=$incomingMax"
            )
          case EventIdentityConflict(eventId) =>
            AgentError.PersistenceFailure(s"事件 $eventId 已属于其他 Run、sequence 或 payload，拒绝错误幂等复用")
          case error => databaseError("追加 Agent 事件失败", error)
        }
    }

  /** `ON CONFLICT DO NOTHING` 之后核对不可变事件身份，防止错误 EventId 被静默吞掉。 */
  private def verifyEventIdentities(
      connection: Connection,
      events: NonEmptyChunk[PersistedAgentEvent]
  ): Unit =
    val verify =
      connection.prepareStatement("SELECT payload::text FROM agent_events WHERE event_id = ?::uuid")
    try
      events.foreach { expected =>
        verify.setString(1, expected.eventId.asString)
        val result   = verify.executeQuery()
        val existing =
          if result.next() then
            result
              .getString(1)
              .fromJson[PersistedAgentEvent]
              .fold(error => throw IllegalStateException(error), identity)
          else throw EventIdentityConflict(expected.eventId.asString)
        if existing != expected then throw EventIdentityConflict(expected.eventId.asString)
      }
    finally verify.close()

  /** 锁住 Run 直到事件重放事务结束，确保验证过的状态游标不会与并发 commit 交错。 */
  private def lockEventAppendBoundary(connection: Connection, runId: RunId, incomingMax: Long): Unit =
    val statement = connection.prepareStatement(
      """SELECT COALESCE(state_json ->> 'lastEventSequence', '-1')::bigint
        |FROM agent_runs WHERE run_id = ?::uuid FOR SHARE""".stripMargin
    )
    try
      statement.setString(1, runId.asString)
      val result = statement.executeQuery()
      if !result.next() then throw MissingRun(runId)
      val stateLast = result.getLong(1)
      if incomingMax > stateLast then throw EventAppendAhead(stateLast, incomingMax)
    finally statement.close()

  /** 按 sequence 游标有界读取事件，供恢复、审计和跨节点 SSE 增量订阅使用。 */
  def events(runId: RunId, afterSequence: Long, limit: Int): IO[StoreError, Chunk[PersistedAgentEvent]] =
    RunStore.validateEventPage(afterSequence, limit) *>
      withConnection { connection =>
        ZIO.attemptBlocking {
          val statement = connection.prepareStatement(
            """SELECT event_id::text, run_id::text, sequence, event_type, payload::text
              |FROM agent_events
              |WHERE run_id = ?::uuid AND sequence > ?
              |ORDER BY sequence LIMIT ?""".stripMargin
          )
          try
            statement.setString(1, runId.asString)
            statement.setLong(2, afterSequence)
            statement.setInt(3, limit)
            val result  = statement.executeQuery()
            val builder = ChunkBuilder.make[PersistedAgentEvent]()
            while result.next() do builder += decodeEventEnvelope(result, runId)
            builder.result()
          finally statement.close()
        }
      }

  /** 解码并验证 AgentState 的关系列信封。时间字段不参与比较，因为 JDBC/PostgreSQL 时间精度可能低于 JSON 的 Instant 精度。 */
  private def decodeStateEnvelope(result: ResultSet, expectedRunId: RunId): AgentState =
    val state = result
      .getString("state_json")
      .fromJson[AgentState]
      .fold(error => throw IllegalStateException(error), identity)
    val recordId = expectedRunId.asString
    requireEnvelope("AgentState", recordId, "requestedRunId", expectedRunId.asString, state.runId.asString)
    requireEnvelope("AgentState", recordId, "runId", result.getString("run_id"), state.runId.asString)
    requireEnvelope(
      "AgentState",
      recordId,
      "sessionId",
      result.getString("session_id"),
      state.sessionId.asString
    )
    requireEnvelope("AgentState", recordId, "agentId", result.getString("agent_id"), state.agentId.value)
    requireEnvelope("AgentState", recordId, "status", result.getString("status"), state.status.toString)
    requireEnvelope("AgentState", recordId, "version", result.getLong("version"), state.version.value)
    requireEnvelope(
      "AgentState",
      recordId,
      "schemaVersion",
      result.getInt("schema_version"),
      state.schemaVersion
    )
    state

  /** 解码并验证事件的不可变关系列信封，防止 sequence、类型或身份列与 JSON payload 分叉。 */
  private def decodeEventEnvelope(result: ResultSet, expectedRunId: RunId): PersistedAgentEvent =
    val event = result
      .getString("payload")
      .fromJson[PersistedAgentEvent]
      .fold(error => throw IllegalStateException(error), identity)
    val recordId = result.getString("event_id")
    requireEnvelope("AgentEvent", recordId, "requestedRunId", expectedRunId.asString, event.runId.asString)
    requireEnvelope("AgentEvent", recordId, "eventId", recordId, event.eventId.asString)
    requireEnvelope("AgentEvent", recordId, "runId", result.getString("run_id"), event.runId.asString)
    requireEnvelope("AgentEvent", recordId, "sequence", result.getLong("sequence"), event.sequence)
    requireEnvelope(
      "AgentEvent",
      recordId,
      "eventType",
      result.getString("event_type"),
      event.event.productPrefix
    )
    event

  private def requireEnvelope[A](
      recordType: String,
      recordId: String,
      field: String,
      persisted: A,
      decoded: A
  ): Unit =
    if persisted != decoded then throw PersistedEnvelopeConflict(recordType, recordId, field)

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
              """SELECT run_id::text, batch_id, ordinal, call_id, tool_name, idempotency_key,
                | status, attempt, record_json::text
                |FROM tool_executions WHERE run_id = ?::uuid AND call_id = ?""".stripMargin
            )
            try
              records.foreach { expected =>
                verify.setString(1, expected.runId.asString)
                verify.setString(2, expected.callId)
                val result   = verify.executeQuery()
                val existing =
                  if result.next() then decodeToolEnvelope(result, expected.runId, Some(expected.callId))
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
          """SELECT run_id::text, batch_id, ordinal, call_id, tool_name, idempotency_key,
            | status, attempt, record_json::text
            |FROM tool_executions WHERE run_id = ?::uuid AND call_id = ?""".stripMargin
        )
        try
          statement.setString(1, runId.asString)
          statement.setString(2, callId)
          val result = statement.executeQuery()
          Option.when(result.next())(decodeToolEnvelope(result, runId, Some(callId)))
        finally statement.close()
      }
    }

  /** 按 batch_id 查询 pending writes，并用 ordinal 恢复 Provider 原始顺序。 */
  def getToolExecutions(runId: RunId, batchId: String): IO[StoreError, Chunk[ToolExecutionRecord]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT run_id::text, batch_id, ordinal, call_id, tool_name, idempotency_key,
            | status, attempt, record_json::text
            |FROM tool_executions WHERE run_id = ?::uuid AND batch_id = ? ORDER BY ordinal""".stripMargin
        )
        try
          statement.setString(1, runId.asString)
          statement.setString(2, batchId)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[ToolExecutionRecord]()
          while result.next() do builder += decodeToolEnvelope(result, runId, None)
          builder.result()
        finally statement.close()
      }
    }

  def getModelCall(
      runId: RunId,
      requestId: ModelRequestId
  ): IO[StoreError, Option[ModelCallExecutionRecord]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT run_id::text, request_id::text, attempt, status, provider, model, capture_policy,
            | fingerprint, message_count, tool_count, record_json::text
            |FROM model_call_executions WHERE run_id = ?::uuid AND request_id = ?::uuid""".stripMargin
        )
        try
          statement.setString(1, runId.asString)
          statement.setString(2, requestId.asString)
          val result = statement.executeQuery()
          Option.when(result.next())(decodeModelCallEnvelope(result, runId, Some(requestId)))
        finally statement.close()
      }
    }

  def getModelCalls(runId: RunId): IO[StoreError, Chunk[ModelCallExecutionRecord]] =
    withConnection { connection =>
      ZIO.attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT run_id::text, request_id::text, attempt, status, provider, model, capture_policy,
            | fingerprint, message_count, tool_count, record_json::text
            |FROM model_call_executions WHERE run_id = ?::uuid ORDER BY updated_at""".stripMargin
        )
        try
          statement.setString(1, runId.asString)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[ModelCallExecutionRecord]()
          while result.next() do builder += decodeModelCallEnvelope(result, runId, None)
          builder.result()
        finally statement.close()
      }
    }

  private def writeModelCall(connection: Connection, write: Option[ModelCallWrite]): Unit =
    write.foreach {
      case ModelCallWrite.Insert(record) =>
        val insert = connection.prepareStatement(
          """INSERT INTO model_call_executions
            |(run_id, request_id, attempt, status, provider, model, capture_policy, fingerprint,
            | message_count, tool_count, record_json, updated_at)
            |VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            |ON CONFLICT (run_id, request_id) DO NOTHING""".stripMargin
        )
        try
          bindModelCall(insert, record)
          insert.executeUpdate()
        finally insert.close()
        val verify = connection.prepareStatement(
          """SELECT run_id::text, request_id::text, attempt, status, provider, model, capture_policy,
            | fingerprint, message_count, tool_count, record_json::text
            |FROM model_call_executions WHERE run_id = ?::uuid AND request_id = ?::uuid""".stripMargin
        )
        try
          verify.setString(1, record.runId.asString)
          verify.setString(2, record.requestId.asString)
          val result   = verify.executeQuery()
          val existing =
            if result.next() then decodeModelCallEnvelope(result, record.runId, Some(record.requestId))
            else throw ModelCallIdentityConflict(record.requestId.asString)
          if !RunStore.sameModelCallIdentity(existing, record) then
            throw ModelCallIdentityConflict(record.requestId.asString)
        finally verify.close()
      case ModelCallWrite.Transition(expectedStatus, expectedAttempt, next) =>
        val update = connection.prepareStatement(
          """UPDATE model_call_executions
            |SET status = ?, attempt = ?, record_json = ?::jsonb, updated_at = ?
            |WHERE run_id = ?::uuid AND request_id = ?::uuid AND status = ? AND attempt = ?
            |  AND provider = ? AND model = ? AND fingerprint = ? AND capture_policy = ?
            |  AND COALESCE(record_json -> 'routeDecision', 'null'::jsonb)
            |      = COALESCE(?::jsonb -> 'routeDecision', 'null'::jsonb)""".stripMargin
        )
        try
          update.setString(1, next.status.toString)
          update.setInt(2, next.attempt)
          update.setString(3, next.toJson)
          setInstant(update, 4, Instant.ofEpochMilli(next.updatedAtEpochMilli))
          update.setString(5, next.runId.asString)
          update.setString(6, next.requestId.asString)
          update.setString(7, expectedStatus.toString)
          update.setInt(8, expectedAttempt)
          update.setString(9, next.provider)
          update.setString(10, next.model)
          update.setString(11, next.fingerprint)
          update.setString(12, next.capturePolicy.toString)
          update.setString(13, next.toJson)
          if update.executeUpdate() != 1 then
            throw ModelCallLedgerConflict(next.requestId.asString, expectedStatus.toString, expectedAttempt)
        finally update.close()
    }

  private def bindModelCall(statement: PreparedStatement, record: ModelCallExecutionRecord): Unit =
    statement.setString(1, record.runId.asString)
    statement.setString(2, record.requestId.asString)
    statement.setInt(3, record.attempt)
    statement.setString(4, record.status.toString)
    statement.setString(5, record.provider)
    statement.setString(6, record.model)
    statement.setString(7, record.capturePolicy.toString)
    statement.setString(8, record.fingerprint)
    statement.setInt(9, record.messageCount)
    statement.setInt(10, record.toolCount)
    statement.setString(11, record.toJson)
    setInstant(statement, 12, Instant.ofEpochMilli(record.updatedAtEpochMilli))

  private def decodeToolEnvelope(
      result: ResultSet,
      expectedRunId: RunId,
      expectedCallId: Option[String]
  ): ToolExecutionRecord =
    val record = result
      .getString("record_json")
      .fromJson[ToolExecutionRecord]
      .fold(error => throw IllegalStateException(error), identity)
    val recordId = s"${expectedRunId.asString}/${result.getString("call_id")}"
    requireEnvelope(
      "ToolExecution",
      recordId,
      "requestedRunId",
      expectedRunId.asString,
      record.runId.asString
    )
    expectedCallId.foreach(value =>
      requireEnvelope("ToolExecution", recordId, "requestedCallId", value, record.callId)
    )
    requireEnvelope("ToolExecution", recordId, "runId", result.getString("run_id"), record.runId.asString)
    requireEnvelope("ToolExecution", recordId, "batchId", result.getString("batch_id"), record.batchId)
    requireEnvelope("ToolExecution", recordId, "ordinal", result.getInt("ordinal"), record.ordinal)
    requireEnvelope("ToolExecution", recordId, "callId", result.getString("call_id"), record.callId)
    requireEnvelope("ToolExecution", recordId, "toolName", result.getString("tool_name"), record.toolName)
    requireEnvelope(
      "ToolExecution",
      recordId,
      "idempotencyKey",
      Option(result.getString("idempotency_key")),
      record.idempotencyKey
    )
    requireEnvelope("ToolExecution", recordId, "status", result.getString("status"), record.status.toString)
    requireEnvelope("ToolExecution", recordId, "attempt", result.getInt("attempt"), record.attempt)
    record

  private def decodeModelCallEnvelope(
      result: ResultSet,
      expectedRunId: RunId,
      expectedRequestId: Option[ModelRequestId]
  ): ModelCallExecutionRecord =
    val record = result
      .getString("record_json")
      .fromJson[ModelCallExecutionRecord]
      .fold(error => throw IllegalStateException(error), identity)
    val recordId = s"${expectedRunId.asString}/${result.getString("request_id")}"
    requireEnvelope("ModelCall", recordId, "requestedRunId", expectedRunId.asString, record.runId.asString)
    expectedRequestId.foreach(value =>
      requireEnvelope("ModelCall", recordId, "requestedRequestId", value.asString, record.requestId.asString)
    )
    requireEnvelope("ModelCall", recordId, "runId", result.getString("run_id"), record.runId.asString)
    requireEnvelope(
      "ModelCall",
      recordId,
      "requestId",
      result.getString("request_id"),
      record.requestId.asString
    )
    requireEnvelope("ModelCall", recordId, "attempt", result.getInt("attempt"), record.attempt)
    requireEnvelope("ModelCall", recordId, "status", result.getString("status"), record.status.toString)
    requireEnvelope("ModelCall", recordId, "provider", result.getString("provider"), record.provider)
    requireEnvelope("ModelCall", recordId, "model", result.getString("model"), record.model)
    requireEnvelope(
      "ModelCall",
      recordId,
      "capturePolicy",
      result.getString("capture_policy"),
      record.capturePolicy.toString
    )
    requireEnvelope("ModelCall", recordId, "fingerprint", result.getString("fingerprint"), record.fingerprint)
    requireEnvelope(
      "ModelCall",
      recordId,
      "messageCount",
      result.getInt("message_count"),
      record.messageCount
    )
    requireEnvelope("ModelCall", recordId, "toolCount", result.getInt("tool_count"), record.toolCount)
    record

  /** 外键均配置 `ON DELETE CASCADE`，因此删除主 Run 即可原子清理事件、步骤和审批记录。 */
  def delete(runId: RunId): IO[StoreError, Unit] = withConnection { connection =>
    executeUpdate(connection, "DELETE FROM agent_runs WHERE run_id = ?::uuid", runId.asString)
      .flatMap(count => if count == 1 then ZIO.unit else ZIO.fail(AgentError.RunNotFound(runId)))
  }

  /** 读取当前版本与事件游标，专用于准确区分 OptimisticLock、非法 save 与 RunNotFound。 */
  private def currentRunPosition(connection: Connection, runId: RunId): IO[StoreError, (Version, Long)] =
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT version, COALESCE(state_json ->> 'lastEventSequence', '-1')::bigint
            |FROM agent_runs WHERE run_id = ?::uuid""".stripMargin
        )
        try
          statement.setString(1, runId.asString)
          val result = statement.executeQuery()
          if result.next() then Version(result.getLong(1)) -> result.getLong(2)
          else throw java.util.NoSuchElementException(runId.asString)
        finally statement.close()
      }
      .mapError {
        case _: java.util.NoSuchElementException => AgentError.RunNotFound(runId)
        case error                               => AgentError.PersistenceFailure("读取版本与事件游标失败", Some(error))
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

final private case class VersionConflict(actual: Long)                        extends RuntimeException
final private case class MissingRun(runId: RunId)                             extends RuntimeException
private case object ToolLedgerConflict                                        extends RuntimeException
final private case class ToolLedgerIdentityConflict(callId: String)           extends RuntimeException
final private case class LostStateLease(lease: RunCommandLease)               extends RuntimeException
final private case class ModelCallIdentityConflict(requestId: String)         extends RuntimeException
final private case class EventIdentityConflict(eventId: String)               extends RuntimeException
final private case class EventSequenceConflict(expected: Long, actual: Long)  extends RuntimeException
final private case class EventAppendAhead(stateLast: Long, incomingMax: Long) extends RuntimeException
final private case class PersistedEnvelopeConflict(recordType: String, recordId: String, field: String)
    extends RuntimeException(s"$recordType 持久化信封不一致: id=$recordId, field=$field")
final private case class ModelCallLedgerConflict(
    requestId: String,
    expectedStatus: String,
    expectedAttempt: Int
) extends RuntimeException

object PostgresRunStore:
  val layer: URLayer[DataSource, RunStore] = ZLayer.fromFunction(PostgresRunStore.apply)
