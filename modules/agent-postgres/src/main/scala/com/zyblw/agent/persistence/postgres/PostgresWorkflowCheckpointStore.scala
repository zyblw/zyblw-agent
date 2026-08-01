package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{LeaseToken, WorkerId}
import com.zyblw.agent.workflow.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{Connection, ResultSet, SQLException, Types}
import java.time.{Instant, OffsetDateTime}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL Workflow checkpoint 的容量边界。
  *
  * @param maxCheckpointBytes
  *   完整 checkpoint JSON 的 UTF-8 上限；编码和读取都执行相同限制
  * @param maxVisitEntries
  *   单个 checkpoint 可保存的节点访问计数上限
  */
final case class PostgresWorkflowCheckpointStoreConfig(
    maxCheckpointBytes: Int = 2 * 1024 * 1024,
    maxVisitEntries: Int = 10_000,
    maxSignalBytes: Int = 64 * 1024
):
  require(
    maxCheckpointBytes >= 1024 && maxCheckpointBytes <= 16 * 1024 * 1024,
    "maxCheckpointBytes 必须位于 1KiB..16MiB"
  )
  require(maxVisitEntries >= 1 && maxVisitEntries <= 100_000, "maxVisitEntries 必须位于 1..100000")
  require(maxSignalBytes >= 0 && maxSignalBytes <= 64 * 1024, "maxSignalBytes 必须位于 0..64KiB")

/** 使用 PostgreSQL 保存声明式 Workflow 的完整恢复快照。
  *
  * 每个 `runId` 只有一行权威 checkpoint。写入允许相同内容幂等重放，或在相同 workflow/version/session identity 内推进到更大 step；陈旧写入、同 step
  * 不同内容和 identity 漂移均 fail-closed。完整 JSON 同时保存确定性 TEXT、JSONB 分析投影和 SHA-256，读取时重新验证容量、checksum、领域约束与冗余列。
  *
  * 同一 Adapter 还实现 `WorkflowExecutionStore`：节点 pending outcome、execution ledger、checkpoint 与 durable wait 可在一个
  * 事务中 fenced 提交。节点内部外部副作用仍必须使用业务幂等键或 outbox/inbox。
  */
final class PostgresWorkflowCheckpointStore[S: JsonCodec](
    dataSource: DataSource,
    config: PostgresWorkflowCheckpointStoreConfig = PostgresWorkflowCheckpointStoreConfig()
) extends WorkflowExecutionStore[S]:
  import PostgresWorkflowCheckpointStore.*

  override def save(runId: RunId, checkpoint: WorkflowCheckpoint[S]): IO[StoreError, Unit] =
    for
      encoded <- encode(checkpoint)
      result  <- withConnection { connection =>
        upsert(connection, runId, checkpoint, encoded).flatMap {
          case true  => ZIO.succeed(SaveResult.Written)
          case false =>
            select(connection, runId).map {
              case Some(row) => SaveResult.Existing(row)
              case None      => SaveResult.ConflictRowMissing
            }
        }
      }
      _ <- result match
        case SaveResult.Written       => ZIO.unit
        case SaveResult.Existing(row) =>
          decode(row).flatMap { stored =>
            if stored == checkpoint then ZIO.unit
            else ZIO.fail(AgentError.WorkflowCheckpointConflict(runId, "non-monotonic-write"))
          }
        case SaveResult.ConflictRowMissing =>
          ZIO.fail(corrupted("conflict-row-missing"))
    yield ()

  override def load(runId: RunId): IO[StoreError, Option[WorkflowCheckpoint[S]]] =
    withConnection(connection => select(connection, runId))
      .flatMap(row => ZIO.foreach(row)(decode))

  override def claim(
      key: WorkflowExecutionKey,
      owner: WorkerId,
      leaseDuration: Duration
  ): IO[StoreError, WorkflowExecutionClaim[S]] =
    validateDuration(leaseDuration) *> LeaseToken.random.flatMap { token =>
      withTransaction { connection =>
        for
          _         <- validateExecutionRunIdentity(connection, key)
          inserted  <- insertExecution(connection, key, owner, token, leaseDuration)
          reclaimed <-
            if inserted then ZIO.succeed(false)
            else reclaimExecution(connection, key, owner, token, leaseDuration)
          record <- selectExecution(connection, key, forUpdate = true).flatMap {
            case Some(row) => decodeExecution(row)
            case None      => ZIO.fail(executionCorrupted("claim-row-missing"))
          }
          _ <- ZIO
            .fail(executionConflict(key, "execution-identity"))
            .unless(record.key == key)
          acquired = inserted || reclaimed
          result <-
            if acquired then
              ZIO
                .fromOption(record.expiresAt)
                .mapError(_ => executionCorrupted("acquired-expires-at"))
                .map(expiresAt =>
                  WorkflowExecutionClaim.Acquired(
                    WorkflowExecutionLease(
                      record.key,
                      record.owner,
                      record.token,
                      record.generation,
                      record.claimedAt,
                      expiresAt
                    ),
                    record.outcome
                  )
                )
            else
              record.status match
                case WorkflowExecutionStatus.Committed =>
                  ZIO.succeed(
                    WorkflowExecutionClaim.Committed(
                      record.generation,
                      record.completedAt.getOrElse(record.updatedAt)
                    )
                  )
                case _ =>
                  ZIO
                    .fromOption(record.expiresAt)
                    .mapError(_ => executionCorrupted("busy-expires-at"))
                    .map(expiresAt => WorkflowExecutionClaim.Busy(record.owner, record.generation, expiresAt))
        yield result
      }
    }

  override def heartbeat(
      lease: WorkflowExecutionLease,
      leaseDuration: Duration
  ): IO[StoreError, WorkflowExecutionLease] =
    validateDuration(leaseDuration) *> withConnection { connection =>
      jdbc("execution-heartbeat") {
        val statement = connection.prepareStatement(
          """UPDATE agent_workflow_node_executions
            |SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
            |    heartbeat_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
            |  AND status IN ('Running', 'Prepared')
            |  AND lease_owner = ? AND lease_token = ?::uuid AND generation = ?
            |  AND lease_expires_at > CURRENT_TIMESTAMP
            |RETURNING lease_expires_at""".stripMargin
        )
        try
          statement.setLong(1, leaseDuration.toMillis)
          bindFence(statement, 2, lease)
          val result = statement.executeQuery()
          if result.next() then lease.copy(expiresAt = result.getObject(1, classOf[OffsetDateTime]).toInstant)
          else throw LostWorkflowExecutionLease(lease)
        finally statement.close()
      }
    }

  override def prepare(
      lease: WorkflowExecutionLease,
      outcome: NodeOutcome[S]
  ): IO[StoreError, WorkflowExecutionRecord[S]] =
    for
      encoded <- encodeOutcome(outcome)
      record  <- withTransaction { connection =>
        updatePrepared(connection, lease, encoded).flatMap {
          case true =>
            selectExecution(connection, lease.key, forUpdate = true).flatMap {
              case Some(row) => decodeExecution(row)
              case None      => ZIO.fail(executionCorrupted("prepare-row-missing"))
            }
          case false =>
            selectExecution(connection, lease.key, forUpdate = true).flatMap {
              case Some(row) =>
                decodeExecution(row).flatMap { existing =>
                  if sameFence(existing, lease) &&
                    row.leaseActive &&
                    existing.status == WorkflowExecutionStatus.Prepared &&
                    existing.outcome.contains(outcome)
                  then ZIO.succeed(existing)
                  else ZIO.fail(lost(lease))
                }
              case None => ZIO.fail(lost(lease))
            }
        }
      }
    yield record

  override def commit(
      leases: NonEmptyChunk[WorkflowExecutionLease],
      checkpoint: WorkflowCheckpoint[S],
      waitCommit: WorkflowWaitCommit
  ): IO[StoreError, Unit] =
    val values = Chunk.fromIterable(leases)
    for
      _       <- validateCommitIdentity(values, checkpoint)
      encoded <- encode(checkpoint)
      _       <- withTransaction { connection =>
        for
          records <- ZIO.foreach(values) { lease =>
            selectExecution(connection, lease.key, forUpdate = true).flatMap {
              case Some(row) => decodeExecution(row).map(_ -> row.leaseActive)
              case None      => ZIO.fail(lost(lease))
            }
          }
          fencedRecords  = records.zip(values)
          activePrepared = fencedRecords.forall { case (record, active, lease) =>
            active && sameFence(record, lease) && record.status == WorkflowExecutionStatus.Prepared
          }
          idempotentCommitted = fencedRecords.forall { case (record, _, lease) =>
            sameFence(record, lease) && record.status == WorkflowExecutionStatus.Committed
          }
          _ <-
            if activePrepared || idempotentCommitted then ZIO.unit
            else ZIO.fail(executionConflict(values.head.key, "commit-fence-or-status"))
          checkpointWritten <- upsert(
            connection,
            values.head.key.runId,
            checkpoint,
            encoded
          )
          _ <-
            if checkpointWritten then ZIO.unit
            else
              select(connection, values.head.key.runId).flatMap {
                case Some(row) =>
                  decode(row).flatMap { existing =>
                    if existing == checkpoint then ZIO.unit
                    else
                      ZIO.fail(
                        AgentError.WorkflowCheckpointConflict(
                          values.head.key.runId,
                          "non-monotonic-write"
                        )
                      )
                  }
                case None => ZIO.fail(corrupted("commit-checkpoint-row-missing"))
              }
          _ <- applyWaitCommit(connection, values, waitCommit)
          _ <-
            if idempotentCommitted then ZIO.unit
            else ZIO.foreachDiscard(values)(lease => markCommitted(connection, lease))
        yield ()
      }
    yield ()

  override def get(
      key: WorkflowExecutionKey
  ): IO[StoreError, Option[WorkflowExecutionRecord[S]]] =
    withConnection(connection => selectExecution(connection, key, forUpdate = false))
      .flatMap(row => ZIO.foreach(row)(decodeExecution))
      .flatMap {
        case Some(record) if record.key != key =>
          ZIO.fail(executionConflict(key, "execution-identity"))
        case value => ZIO.succeed(value)
      }

  override def timeline(
      runId: RunId,
      after: Option[WorkflowTimelineCursor],
      limit: Int
  ): IO[StoreError, Chunk[WorkflowExecutionTimelineEntry]] =
    validateTimelineLimit(limit) *>
      withConnection(connection => selectExecutionTimeline(connection, runId, after, limit))
        .flatMap(rows => ZIO.foreach(rows)(decodeExecution))
        .map(_.map(record => WorkflowExecutionTimelineEntry.fromRecord(record)))

  override def currentWait(runId: RunId): IO[StoreError, Option[WorkflowWaitRecord]] =
    withConnection(connection => selectCurrentWait(connection, runId, forUpdate = false))
      .flatMap(row => ZIO.foreach(row)(decodeWait))

  override def signal(
      waitKey: WorkflowWaitKey,
      signalId: WorkflowSignalId,
      name: WorkflowSignalName,
      payload: String
  ): IO[StoreError, WorkflowSignalReceipt] =
    for
      payloadHash <- validateSignalPayload(payload)
      receipt     <- withTransaction { connection =>
        for
          existing <- selectSignal(connection, waitKey, signalId)
          result   <- existing match
            case Some(row) =>
              if row.name == name.value && row.payloadSha256 == payloadHash && row.payload == payload then
                ZIO.succeed(
                  WorkflowSignalReceipt(
                    waitKey,
                    signalId,
                    WorkflowSignalDisposition.Duplicate,
                    row.receivedAt
                  )
                )
              else ZIO.fail(waitConflict(waitKey.runId, "signal-id-payload-conflict"))
            case None => receiveSignal(connection, waitKey, signalId, name, payload, payloadHash)
        yield result
      }
    yield receipt

  override def expireDue(limit: Int): IO[StoreError, Chunk[WorkflowWaitRecord]] =
    validateWaitLimit(limit) *> withTransaction(connection => expireWaitRows(connection, limit))
      .flatMap(rows => ZIO.foreach(rows)(decodeWait))

  override def claimWakeups(
      workflowId: WorkflowId,
      definitionVersion: WorkflowVersion,
      owner: WorkerId,
      leaseDuration: Duration,
      limit: Int
  ): IO[StoreError, Chunk[WorkflowWakeupLease]] =
    validateDuration(leaseDuration) *> validateWaitLimit(limit) *>
      withTransaction { connection =>
        selectWakeCandidates(connection, workflowId, definitionVersion, limit).flatMap { rows =>
          ZIO.foreach(rows) { row =>
            for
              record <- decodeWait(row)
              token  <- LeaseToken.random
              lease  <- claimWakeRow(connection, record, owner, token, leaseDuration)
            yield lease
          }
        }
      }

  override def heartbeatWakeup(
      lease: WorkflowWakeupLease,
      leaseDuration: Duration
  ): IO[StoreError, WorkflowWakeupLease] =
    validateDuration(leaseDuration) *> withConnection { connection =>
      jdbc("wait-wake-heartbeat") {
        val statement = connection.prepareStatement(
          """UPDATE agent_workflow_waits
            |SET wake_lease_expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond'),
            |    wake_heartbeat_at = clock_timestamp()
            |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
            |  AND status IN ('Signaled', 'TimedOut')
            |  AND wake_owner = ? AND wake_token = ?::uuid AND wake_generation = ?
            |  AND wake_lease_expires_at > clock_timestamp()
            |RETURNING wake_lease_expires_at""".stripMargin
        )
        try
          statement.setLong(1, leaseDuration.toMillis)
          bindWakeFence(statement, 2, lease)
          val result = statement.executeQuery()
          if result.next() then
            lease.copy(leaseExpiresAt = result.getObject(1, classOf[OffsetDateTime]).toInstant)
          else throw LostWorkflowWakeLease(lease)
        finally statement.close()
      }
    }

  override def abandonWakeup(
      lease: WorkflowWakeupLease,
      availableAt: Instant
  ): IO[StoreError, Unit] =
    withConnection { connection =>
      jdbc("wait-wake-abandon") {
        val statement = connection.prepareStatement(
          """UPDATE agent_workflow_waits
            |SET wake_available_at = GREATEST(?, clock_timestamp()),
            |    wake_owner = NULL, wake_token = NULL, wake_claimed_at = NULL,
            |    wake_lease_expires_at = NULL, wake_heartbeat_at = NULL
            |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
            |  AND status IN ('Signaled', 'TimedOut')
            |  AND wake_owner = ? AND wake_token = ?::uuid AND wake_generation = ?
            |  AND wake_lease_expires_at > clock_timestamp()""".stripMargin
        )
        try
          statement.setObject(1, OffsetDateTime.ofInstant(availableAt, java.time.ZoneOffset.UTC))
          bindWakeFence(statement, 2, lease)
          if statement.executeUpdate() != 1 then throw LostWorkflowWakeLease(lease)
        finally statement.close()
      }
    }

  /** 在短事务中排他锁定当前 Engine 可处理的 wakeup。锁只保护领取状态转换，不覆盖后续 Workflow 执行。 */
  private def selectWakeCandidates(
      connection: Connection,
      workflowId: WorkflowId,
      definitionVersion: WorkflowVersion,
      limit: Int
  ): IO[StoreError, Chunk[StoredWaitRow]] =
    jdbc("wait-wake-candidates") {
      val statement = connection.prepareStatement(
        """SELECT run_id::text, step, node_id, workflow_id, definition_version, session_id::text,
          | condition_kind, signal_name, deadline, status, accepted_signal_id,
          | accepted_signal_payload, accepted_signal_sha256, signal_received_at,
          | created_at, resolved_at, consumed_at
          |FROM agent_workflow_waits
          |WHERE workflow_id = ? AND definition_version = ?
          |  AND status IN ('Signaled', 'TimedOut')
          |  AND wake_available_at <= clock_timestamp()
          |  AND (wake_token IS NULL OR wake_lease_expires_at <= clock_timestamp())
          |ORDER BY wake_available_at ASC, resolved_at ASC, run_id ASC, step ASC,
          |         node_id COLLATE "C" ASC
          |FOR UPDATE SKIP LOCKED
          |LIMIT ?""".stripMargin
      )
      try
        statement.setString(1, workflowId.value)
        statement.setInt(2, definitionVersion.value)
        statement.setInt(3, limit)
        val result  = statement.executeQuery()
        val builder = ChunkBuilder.make[StoredWaitRow]()
        while result.next() do builder += readWaitRow(result)
        builder.result()
      finally statement.close()
    }

  /** 对已经 `FOR UPDATE` 锁定的 wait 换发随机 token 并递增 generation。WHERE 条件仍完整重验，避免未来调用路径绕过锁。 */
  private def claimWakeRow(
      connection: Connection,
      record: WorkflowWaitRecord,
      owner: WorkerId,
      token: LeaseToken,
      leaseDuration: Duration
  ): IO[StoreError, WorkflowWakeupLease] =
    jdbc("wait-wake-claim") {
      val statement = connection.prepareStatement(
        """UPDATE agent_workflow_waits
          |SET wake_generation = wake_generation + 1,
          |    wake_owner = ?, wake_token = ?::uuid,
          |    wake_claimed_at = clock_timestamp(),
          |    wake_lease_expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond'),
          |    wake_heartbeat_at = clock_timestamp()
          |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
          |  AND status IN ('Signaled', 'TimedOut')
          |  AND wake_available_at <= clock_timestamp()
          |  AND (wake_token IS NULL OR wake_lease_expires_at <= clock_timestamp())
          |RETURNING wake_generation, wake_lease_expires_at""".stripMargin
      )
      try
        statement.setString(1, owner.value)
        statement.setString(2, token.value)
        statement.setLong(3, leaseDuration.toMillis)
        statement.setString(4, record.key.runId.asString)
        statement.setInt(5, record.key.step)
        statement.setString(6, record.key.nodeId.value)
        val result = statement.executeQuery()
        if result.next() then
          WorkflowWakeupLease(
            record,
            owner,
            token,
            result.getLong(1),
            result.getObject(2, classOf[OffsetDateTime]).toInstant
          )
        else throw IllegalStateException("locked workflow wake claim lost")
      finally statement.close()
    }

  private def selectCurrentWait(
      connection: Connection,
      runId: RunId,
      forUpdate: Boolean
  ): IO[StoreError, Option[StoredWaitRow]] =
    jdbc("wait-current") {
      val lock      = if forUpdate then " FOR UPDATE" else ""
      val statement = connection.prepareStatement(
        s"""SELECT run_id::text, step, node_id, workflow_id, definition_version, session_id::text,
           | condition_kind, signal_name, deadline, status, accepted_signal_id,
           | accepted_signal_payload, accepted_signal_sha256, signal_received_at,
           | created_at, resolved_at, consumed_at
           |FROM agent_workflow_waits
           |WHERE run_id = ?::uuid AND status <> 'Consumed'
           |ORDER BY step ASC, node_id COLLATE "C" ASC
           |LIMIT 2$lock""".stripMargin
      )
      try
        statement.setString(1, runId.asString)
        val result = statement.executeQuery()
        val rows   = ChunkBuilder.make[StoredWaitRow]()
        while result.next() do rows += readWaitRow(result)
        rows.result()
      finally statement.close()
    }.flatMap {
      case rows if rows.length > 1 => ZIO.fail(waitConflict(runId, "multiple-active-waits"))
      case rows                    => ZIO.succeed(rows.headOption)
    }

  private def selectWait(
      connection: Connection,
      key: WorkflowWaitKey,
      forUpdate: Boolean
  ): IO[StoreError, Option[StoredWaitRow]] =
    jdbc("wait-load") {
      val lock      = if forUpdate then " FOR UPDATE" else ""
      val statement = connection.prepareStatement(
        s"""SELECT run_id::text, step, node_id, workflow_id, definition_version, session_id::text,
           | condition_kind, signal_name, deadline, status, accepted_signal_id,
           | accepted_signal_payload, accepted_signal_sha256, signal_received_at,
           | created_at, resolved_at, consumed_at
           |FROM agent_workflow_waits
           |WHERE run_id = ?::uuid AND step = ? AND node_id = ?$lock""".stripMargin
      )
      try
        statement.setString(1, key.runId.asString)
        statement.setInt(2, key.step)
        statement.setString(3, key.nodeId.value)
        val result = statement.executeQuery()
        if result.next() then Some(readWaitRow(result)) else None
      finally statement.close()
    }

  private def readWaitRow(result: ResultSet): StoredWaitRow =
    StoredWaitRow(
      result.getString(1),
      result.getInt(2),
      result.getString(3),
      result.getString(4),
      result.getInt(5),
      result.getString(6),
      result.getString(7),
      Option(result.getString(8)),
      result.getObject(9, classOf[OffsetDateTime]).toInstant,
      result.getString(10),
      Option(result.getString(11)),
      Option(result.getString(12)),
      Option(result.getString(13)),
      Option(result.getObject(14, classOf[OffsetDateTime])).map(_.toInstant),
      result.getObject(15, classOf[OffsetDateTime]).toInstant,
      Option(result.getObject(16, classOf[OffsetDateTime])).map(_.toInstant),
      Option(result.getObject(17, classOf[OffsetDateTime])).map(_.toInstant)
    )

  private def decodeWait(row: StoredWaitRow): IO[StoreError, WorkflowWaitRecord] =
    for
      runId      <- ZIO.fromEither(RunId.fromString(row.runId)).mapError(_ => waitCorrupted("run-id"))
      nodeId     <- ZIO.fromEither(NodeId.fromString(row.nodeId)).mapError(_ => waitCorrupted("node-id"))
      workflowId <- ZIO
        .fromEither(WorkflowId.fromString(row.workflowId))
        .mapError(_ => waitCorrupted("workflow-id"))
      definitionVersion <- ZIO
        .fromEither(WorkflowVersion.fromInt(row.definitionVersion))
        .mapError(_ => waitCorrupted("definition-version"))
      sessionId <- ZIO
        .fromEither(SessionId.fromString(row.sessionId))
        .mapError(_ => waitCorrupted("session-id"))
      condition <- row.conditionKind match
        case "Signal" =>
          ZIO
            .fromOption(row.signalName)
            .flatMap(name => ZIO.fromEither(WorkflowSignalName.fromString(name)))
            .map(WorkflowWaitCondition.Signal.apply)
            .mapError(_ => waitCorrupted("signal-name"))
        case "Timer" if row.signalName.isEmpty => ZIO.succeed(WorkflowWaitCondition.Timer)
        case _                                 => ZIO.fail(waitCorrupted("condition"))
      status <- row.status match
        case "Pending"  => ZIO.succeed(WorkflowWaitStatus.Pending)
        case "Signaled" => ZIO.succeed(WorkflowWaitStatus.Signaled)
        case "TimedOut" => ZIO.succeed(WorkflowWaitStatus.TimedOut)
        case "Consumed" => ZIO.succeed(WorkflowWaitStatus.Consumed)
        case _          => ZIO.fail(waitCorrupted("status"))
      signal <- (
        row.acceptedSignalId,
        row.acceptedSignalPayload,
        row.acceptedSignalSha256,
        row.signalReceivedAt
      ) match
        case (Some(id), Some(payload), Some(checksum), Some(receivedAt)) =>
          for
            signalId <- ZIO
              .fromEither(WorkflowSignalId.fromString(id))
              .mapError(_ => waitCorrupted("accepted-signal-id"))
            signalName <- condition match
              case WorkflowWaitCondition.Signal(name) => ZIO.succeed(name)
              case WorkflowWaitCondition.Timer        => ZIO.fail(waitCorrupted("timer-signal"))
            _ <- ZIO
              .fail(waitCorrupted("accepted-signal-payload"))
              .unless(
                payload.getBytes(StandardCharsets.UTF_8).length <= config.maxSignalBytes &&
                  checksum == sha256(payload.getBytes(StandardCharsets.UTF_8))
              )
          yield Some(WorkflowSignalValue(signalId, signalName, payload, receivedAt))
        case (None, None, None, None) => ZIO.none
        case _                        => ZIO.fail(waitCorrupted("partial-signal"))
      record <- ZIO
        .attempt(
          WorkflowWaitRecord(
            WorkflowWaitKey(runId, row.step, nodeId),
            workflowId,
            definitionVersion,
            sessionId,
            condition,
            row.deadline,
            status,
            signal,
            row.createdAt,
            row.resolvedAt,
            row.consumedAt
          )
        )
        .mapError(_ => waitCorrupted("invalid-domain"))
    yield record

  private def selectSignal(
      connection: Connection,
      key: WorkflowWaitKey,
      signalId: WorkflowSignalId
  ): IO[StoreError, Option[StoredSignalRow]] =
    jdbc("signal-load") {
      val statement = connection.prepareStatement(
        """SELECT signal_name, payload, payload_sha256, disposition, received_at
          |FROM agent_workflow_signals
          |WHERE run_id = ?::uuid AND wait_step = ? AND wait_node_id = ? AND signal_id = ?""".stripMargin
      )
      try
        statement.setString(1, key.runId.asString)
        statement.setInt(2, key.step)
        statement.setString(3, key.nodeId.value)
        statement.setString(4, signalId.value)
        val result = statement.executeQuery()
        if result.next() then
          Some(
            StoredSignalRow(
              result.getString(1),
              result.getString(2),
              result.getString(3),
              result.getString(4),
              result.getObject(5, classOf[OffsetDateTime]).toInstant
            )
          )
        else None
      finally statement.close()
    }

  private def receiveSignal(
      connection: Connection,
      key: WorkflowWaitKey,
      signalId: WorkflowSignalId,
      name: WorkflowSignalName,
      payload: String,
      payloadHash: String
  ): IO[StoreError, WorkflowSignalReceipt] =
    for
      row <- selectWait(connection, key, forUpdate = true)
        .someOrFail(waitConflict(key.runId, "wait-not-found"))
      wait <- decodeWait(row)
      _    <- wait.condition match
        case WorkflowWaitCondition.Timer =>
          ZIO.fail(waitConflict(key.runId, "timer-does-not-accept-signal"))
        case WorkflowWaitCondition.Signal(expected) if expected != name =>
          ZIO.fail(waitConflict(key.runId, "signal-name-mismatch"))
        case WorkflowWaitCondition.Signal(_) => ZIO.unit
      now         <- databaseNow(connection)
      disposition <- wait.status match
        case WorkflowWaitStatus.Pending if now.isBefore(wait.deadline) =>
          updateWaitSignaled(connection, key, signalId, payload, payloadHash, now)
            .as(WorkflowSignalDisposition.Accepted)
        case WorkflowWaitStatus.Pending =>
          updateWaitTimedOut(connection, key, now).as(WorkflowSignalDisposition.Late)
        case _ => ZIO.succeed(WorkflowSignalDisposition.AlreadyResolved)
      receipt = WorkflowSignalReceipt(key, signalId, disposition, now)
      _ <- insertSignal(connection, receipt, name, payload, payloadHash)
    yield receipt

  private def updateWaitSignaled(
      connection: Connection,
      key: WorkflowWaitKey,
      signalId: WorkflowSignalId,
      payload: String,
      payloadHash: String,
      now: Instant
  ): IO[StoreError, Unit] =
    jdbc("wait-signal") {
      val statement = connection.prepareStatement(
        """UPDATE agent_workflow_waits
          |SET status = 'Signaled', accepted_signal_id = ?, accepted_signal_payload = ?,
          |    accepted_signal_sha256 = ?, signal_received_at = ?, resolved_at = ?,
          |    wake_available_at = ?
          |WHERE run_id = ?::uuid AND step = ? AND node_id = ? AND status = 'Pending'""".stripMargin
      )
      try
        statement.setString(1, signalId.value)
        statement.setString(2, payload)
        statement.setString(3, payloadHash)
        statement.setObject(4, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
        statement.setObject(5, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
        statement.setObject(6, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
        statement.setString(7, key.runId.asString)
        statement.setInt(8, key.step)
        statement.setString(9, key.nodeId.value)
        if statement.executeUpdate() != 1 then throw IllegalStateException("wait signal transition lost")
      finally statement.close()
    }

  private def updateWaitTimedOut(
      connection: Connection,
      key: WorkflowWaitKey,
      now: Instant
  ): IO[StoreError, Unit] =
    jdbc("wait-timeout") {
      val statement = connection.prepareStatement(
        """UPDATE agent_workflow_waits
          |SET status = 'TimedOut', resolved_at = ?, wake_available_at = ?
          |WHERE run_id = ?::uuid AND step = ? AND node_id = ? AND status = 'Pending'""".stripMargin
      )
      try
        statement.setObject(1, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
        statement.setObject(2, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
        statement.setString(3, key.runId.asString)
        statement.setInt(4, key.step)
        statement.setString(5, key.nodeId.value)
        if statement.executeUpdate() != 1 then throw IllegalStateException("wait timeout transition lost")
      finally statement.close()
    }

  private def insertSignal(
      connection: Connection,
      receipt: WorkflowSignalReceipt,
      name: WorkflowSignalName,
      payload: String,
      payloadHash: String
  ): IO[StoreError, Unit] =
    jdbc("signal-insert") {
      val statement = connection.prepareStatement(
        """INSERT INTO agent_workflow_signals
          |(run_id, wait_step, wait_node_id, signal_id, signal_name, payload, payload_sha256,
          | disposition, received_at)
          |VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      )
      try
        statement.setString(1, receipt.waitKey.runId.asString)
        statement.setInt(2, receipt.waitKey.step)
        statement.setString(3, receipt.waitKey.nodeId.value)
        statement.setString(4, receipt.signalId.value)
        statement.setString(5, name.value)
        statement.setString(6, payload)
        statement.setString(7, payloadHash)
        statement.setString(8, receipt.disposition.toString)
        statement.setObject(9, OffsetDateTime.ofInstant(receipt.receivedAt, java.time.ZoneOffset.UTC))
        statement.executeUpdate()
      finally statement.close()
    }.unit

  private def expireWaitRows(connection: Connection, limit: Int): IO[StoreError, Chunk[StoredWaitRow]] =
    jdbc("wait-expire-due") {
      val statement = connection.prepareStatement(
        """WITH due AS (
          |  SELECT run_id, step, node_id
          |  FROM agent_workflow_waits
          |  WHERE status = 'Pending' AND deadline <= clock_timestamp()
          |  ORDER BY deadline ASC, run_id ASC, step ASC, node_id COLLATE "C" ASC
          |  FOR UPDATE SKIP LOCKED
          |  LIMIT ?
          |), updated AS (
          |  UPDATE agent_workflow_waits AS waits
          |  SET status = 'TimedOut', resolved_at = clock_timestamp(),
          |      wake_available_at = clock_timestamp()
          |  FROM due
          |  WHERE waits.run_id = due.run_id AND waits.step = due.step AND waits.node_id = due.node_id
          |    AND waits.status = 'Pending'
          |  RETURNING waits.*
          |)
          |SELECT run_id::text, step, node_id, workflow_id, definition_version, session_id::text,
          | condition_kind, signal_name, deadline, status, accepted_signal_id,
          | accepted_signal_payload, accepted_signal_sha256, signal_received_at,
          | created_at, resolved_at, consumed_at
          |FROM updated
          |ORDER BY deadline ASC, run_id ASC, step ASC, node_id COLLATE "C" ASC""".stripMargin
      )
      try
        statement.setInt(1, limit)
        val result  = statement.executeQuery()
        val builder = ChunkBuilder.make[StoredWaitRow]()
        while result.next() do builder += readWaitRow(result)
        builder.result()
      finally statement.close()
    }

  private def databaseNow(connection: Connection): IO[StoreError, Instant] =
    jdbc("database-clock") {
      val statement = connection.prepareStatement("SELECT clock_timestamp()")
      try
        val result = statement.executeQuery()
        if !result.next() then throw IllegalStateException("database clock row missing")
        result.getObject(1, classOf[OffsetDateTime]).toInstant
      finally statement.close()
    }

  private def insertExecution(
      connection: Connection,
      key: WorkflowExecutionKey,
      owner: WorkerId,
      token: LeaseToken,
      leaseDuration: Duration
  ): IO[StoreError, Boolean] =
    jdbc("execution-claim-insert") {
      val statement = connection.prepareStatement(
        """INSERT INTO agent_workflow_node_executions
          |(run_id, workflow_id, definition_version, session_id, node_id, step, visit, status,
          | generation, lease_owner, lease_token, claimed_at, lease_expires_at, heartbeat_at,
          | created_at, updated_at)
          |VALUES (?::uuid, ?, ?, ?::uuid, ?, ?, ?, 'Running', 1, ?, ?::uuid,
          | CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
          | CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          |ON CONFLICT (run_id, step, node_id) DO NOTHING""".stripMargin
      )
      try
        bindExecutionKey(statement, 1, key)
        statement.setString(8, owner.value)
        statement.setString(9, token.value)
        statement.setLong(10, leaseDuration.toMillis)
        statement.executeUpdate() == 1
      finally statement.close()
    }

  /** 在 claim 事务中串行化同一 Run 的首次 identity，并拒绝跨 step 的 Workflow/version/session 漂移。
    *
    * 0.3 基线没有额外 Run header 表，因此使用 PostgreSQL transaction advisory lock 关闭两个不同节点并发首次插入时的 check-then-insert
    * 竞争。哈希碰撞只会让无关 Run 暂时串行，不会产生错误授权。
    */
  private def validateExecutionRunIdentity(
      connection: Connection,
      key: WorkflowExecutionKey
  ): IO[StoreError, Unit] =
    jdbc("execution-run-identity") {
      val lock = connection.prepareStatement(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))"
      )
      try
        lock.setString(1, key.runId.asString)
        lock.execute()
      finally lock.close()

      val statement = connection.prepareStatement(
        """SELECT workflow_id, definition_version, session_id::text
          |FROM agent_workflow_checkpoints
          |WHERE run_id = ?::uuid
          |UNION
          |SELECT workflow_id, definition_version, session_id::text
          |FROM agent_workflow_node_executions
          |WHERE run_id = ?::uuid AND NOT (step = ? AND node_id = ?)""".stripMargin
      )
      try
        statement.setString(1, key.runId.asString)
        statement.setString(2, key.runId.asString)
        statement.setInt(3, key.step)
        statement.setString(4, key.nodeId.value)
        val result   = statement.executeQuery()
        var conflict = false
        while result.next() do
          conflict ||= result.getString(1) != key.workflowId.value ||
            result.getInt(2) != key.definitionVersion.value ||
            result.getString(3) != key.sessionId.asString
        conflict
      finally statement.close()
    }.flatMap { conflict =>
      ZIO
        .fail(executionConflict(key, "run-execution-identity"))
        .when(conflict)
        .unit
    }

  private def reclaimExecution(
      connection: Connection,
      key: WorkflowExecutionKey,
      owner: WorkerId,
      token: LeaseToken,
      leaseDuration: Duration
  ): IO[StoreError, Boolean] =
    jdbc("execution-claim-reclaim") {
      val statement = connection.prepareStatement(
        """UPDATE agent_workflow_node_executions
          |SET generation = generation + 1, lease_owner = ?, lease_token = ?::uuid,
          |    claimed_at = CURRENT_TIMESTAMP,
          |    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
          |    heartbeat_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
          |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
          |  AND workflow_id = ? AND definition_version = ? AND session_id = ?::uuid AND visit = ?
          |  AND status IN ('Running', 'Prepared')
          |  AND lease_expires_at <= CURRENT_TIMESTAMP""".stripMargin
      )
      try
        statement.setString(1, owner.value)
        statement.setString(2, token.value)
        statement.setLong(3, leaseDuration.toMillis)
        statement.setString(4, key.runId.asString)
        statement.setInt(5, key.step)
        statement.setString(6, key.nodeId.value)
        statement.setString(7, key.workflowId.value)
        statement.setInt(8, key.definitionVersion.value)
        statement.setString(9, key.sessionId.asString)
        statement.setInt(10, key.visit)
        statement.executeUpdate() == 1
      finally statement.close()
    }

  private def updatePrepared(
      connection: Connection,
      lease: WorkflowExecutionLease,
      encoded: EncodedOutcome
  ): IO[StoreError, Boolean] =
    jdbc("execution-prepare") {
      val statement = connection.prepareStatement(
        """UPDATE agent_workflow_node_executions
          |SET status = 'Prepared', outcome_sha256 = ?, outcome_payload = ?,
          |    outcome_json = ?::jsonb, updated_at = CURRENT_TIMESTAMP
          |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
          |  AND status = 'Running'
          |  AND lease_owner = ? AND lease_token = ?::uuid AND generation = ?
          |  AND lease_expires_at > CURRENT_TIMESTAMP""".stripMargin
      )
      try
        statement.setString(1, encoded.sha256)
        statement.setString(2, encoded.json)
        statement.setString(3, encoded.json)
        bindFence(statement, 4, lease)
        statement.executeUpdate() == 1
      finally statement.close()
    }

  private def markCommitted(
      connection: Connection,
      lease: WorkflowExecutionLease
  ): IO[StoreError, Unit] =
    jdbc("execution-commit") {
      val statement = connection.prepareStatement(
        """UPDATE agent_workflow_node_executions
          |SET status = 'Committed', lease_expires_at = NULL, heartbeat_at = NULL,
          |    completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
          |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
          |  AND status = 'Prepared'
          |  AND lease_owner = ? AND lease_token = ?::uuid AND generation = ?
          |  AND lease_expires_at > CURRENT_TIMESTAMP""".stripMargin
      )
      try
        bindFence(statement, 1, lease)
        if statement.executeUpdate() != 1 then throw LostWorkflowExecutionLease(lease)
      finally statement.close()
    }

  private def selectExecution(
      connection: Connection,
      key: WorkflowExecutionKey,
      forUpdate: Boolean
  ): IO[StoreError, Option[StoredExecutionRow]] =
    jdbc("execution-load") {
      val lock      = if forUpdate then " FOR UPDATE" else ""
      val statement = connection.prepareStatement(
        s"""SELECT workflow_id, definition_version, session_id::text, node_id, step, visit, status,
           | generation, lease_owner, lease_token::text, claimed_at, lease_expires_at,
           | updated_at, completed_at, outcome_sha256, outcome_payload,
           | COALESCE(lease_expires_at > CURRENT_TIMESTAMP, FALSE) AS lease_active
           |FROM agent_workflow_node_executions
           |WHERE run_id = ?::uuid AND step = ? AND node_id = ?$lock""".stripMargin
      )
      try
        statement.setString(1, key.runId.asString)
        statement.setInt(2, key.step)
        statement.setString(3, key.nodeId.value)
        val result = statement.executeQuery()
        if result.next() then Some(readExecutionRow(key.runId, result)) else None
      finally statement.close()
    }

  /** 使用 `(step, node_id)` 排他游标读取稳定页面。
    *
    * 查询只选择 execution ledger 列；`run_id, step, node_id` 主键同时承担过滤和有序扫描索引。外层继续复用 `decodeExecution`，因此 timeline
    * 与单条读取具有相同的 checksum、枚举和领域约束校验。
    */
  private def selectExecutionTimeline(
      connection: Connection,
      runId: RunId,
      after: Option[WorkflowTimelineCursor],
      limit: Int
  ): IO[StoreError, Chunk[StoredExecutionRow]] =
    jdbc("execution-timeline") {
      val cursorClause =
        if after.isDefined then """ AND (step > ? OR
            |      (step = ? AND node_id COLLATE "C" > (?::text COLLATE "C")))""".stripMargin
        else ""
      val statement = connection.prepareStatement(
        s"""SELECT workflow_id, definition_version, session_id::text, node_id, step, visit, status,
           | generation, lease_owner, lease_token::text, claimed_at, lease_expires_at,
           | updated_at, completed_at, outcome_sha256, outcome_payload,
           | COALESCE(lease_expires_at > CURRENT_TIMESTAMP, FALSE) AS lease_active
           |FROM agent_workflow_node_executions
           |WHERE run_id = ?::uuid$cursorClause
           |ORDER BY step ASC, node_id COLLATE "C" ASC
           |LIMIT ?""".stripMargin
      )
      try
        statement.setString(1, runId.asString)
        val limitIndex = after match
          case Some(cursor) =>
            statement.setInt(2, cursor.step)
            statement.setInt(3, cursor.step)
            statement.setString(4, cursor.nodeId.value)
            5
          case None => 2
        statement.setInt(limitIndex, limit)
        val result  = statement.executeQuery()
        val builder = ChunkBuilder.make[StoredExecutionRow]()
        while result.next() do builder += readExecutionRow(runId, result)
        builder.result()
      finally statement.close()
    }

  private def readExecutionRow(runId: RunId, result: ResultSet): StoredExecutionRow =
    StoredExecutionRow(
      runId,
      result.getString(1),
      result.getInt(2),
      result.getString(3),
      result.getString(4),
      result.getInt(5),
      result.getInt(6),
      result.getString(7),
      result.getLong(8),
      result.getString(9),
      result.getString(10),
      result.getObject(11, classOf[OffsetDateTime]).toInstant,
      Option(result.getObject(12, classOf[OffsetDateTime])).map(_.toInstant),
      result.getObject(13, classOf[OffsetDateTime]).toInstant,
      Option(result.getObject(14, classOf[OffsetDateTime])).map(_.toInstant),
      Option(result.getString(15)),
      Option(result.getString(16)),
      result.getBoolean(17)
    )

  private def encodeOutcome(outcome: NodeOutcome[S]): IO[StoreError, EncodedOutcome] =
    ZIO
      .attempt {
        val stored = outcome match
          case NodeOutcome.Succeeded(state) =>
            StoredOutcome(CurrentOutcomeSchemaVersion, "Succeeded", state, None, None, None, None)
          case NodeOutcome.Suspended(state, reason) =>
            StoredOutcome(CurrentOutcomeSchemaVersion, "Suspended", state, Some(reason), None, None, None)
          case NodeOutcome.Awaiting(state, request) =>
            val (condition, signalName) = request.condition match
              case WorkflowWaitCondition.Signal(name) => "Signal" -> Some(name.value)
              case WorkflowWaitCondition.Timer        => "Timer"  -> None
            StoredOutcome(
              CurrentOutcomeSchemaVersion,
              "Awaiting",
              state,
              None,
              Some(condition),
              signalName,
              Some(request.deadline.toEpochMilli)
            )
        val json  = stored.toJson
        val bytes = json.getBytes(StandardCharsets.UTF_8)
        EncodedOutcome(json, bytes.length, sha256(bytes))
      }
      .mapError(_ => AgentError.PersistenceFailure("PostgreSQL workflow outcome encode-failed"))
      .flatMap { encoded =>
        ZIO
          .fail(AgentError.PersistenceFailure("PostgreSQL workflow outcome record-too-large"))
          .when(encoded.byteLength > config.maxCheckpointBytes)
          .as(encoded)
      }

  private def decodeExecution(row: StoredExecutionRow): IO[StoreError, WorkflowExecutionRecord[S]] =
    for
      workflowId <- ZIO
        .fromEither(WorkflowId.fromString(row.workflowId))
        .mapError(_ => executionCorrupted("workflow-id"))
      definitionVersion <- ZIO
        .fromEither(WorkflowVersion.fromInt(row.definitionVersion))
        .mapError(_ => executionCorrupted("definition-version"))
      sessionId <- ZIO
        .fromEither(SessionId.fromString(row.sessionId))
        .mapError(_ => executionCorrupted("session-id"))
      nodeId <- ZIO
        .fromEither(NodeId.fromString(row.nodeId))
        .mapError(_ => executionCorrupted("node-id"))
      status <- row.status match
        case "Running"   => ZIO.succeed(WorkflowExecutionStatus.Running)
        case "Prepared"  => ZIO.succeed(WorkflowExecutionStatus.Prepared)
        case "Committed" => ZIO.succeed(WorkflowExecutionStatus.Committed)
        case _           => ZIO.fail(executionCorrupted("status"))
      owner <- ZIO
        .attempt(WorkerId(row.owner))
        .mapError(_ => executionCorrupted("owner"))
      outcome <-
        if status == WorkflowExecutionStatus.Running then
          ZIO
            .fail(executionCorrupted("running-outcome-present"))
            .when(row.outcomeJson.nonEmpty || row.outcomeSha256.nonEmpty)
            .as(None)
        else decodeOutcome(row)
      key = WorkflowExecutionKey(
        row.runId,
        workflowId,
        definitionVersion,
        sessionId,
        nodeId,
        row.step,
        row.visit
      )
      record <- ZIO
        .attempt(
          WorkflowExecutionRecord(
            key,
            status,
            outcome,
            row.generation,
            owner,
            LeaseToken(row.token),
            row.claimedAt,
            row.expiresAt,
            row.updatedAt,
            row.completedAt
          )
        )
        .mapError(_ => executionCorrupted("invalid-domain"))
    yield record

  private def decodeOutcome(row: StoredExecutionRow): IO[StoreError, Option[NodeOutcome[S]]] =
    val json     = row.outcomeJson.getOrElse("")
    val checksum = row.outcomeSha256.getOrElse("")
    val bytes    = json.getBytes(StandardCharsets.UTF_8)
    val actual   = sha256(bytes)
    for
      _ <- ZIO
        .fail(executionCorrupted("outcome-record-too-large"))
        .when(bytes.length > config.maxCheckpointBytes)
      _ <- ZIO
        .fail(executionCorrupted("outcome-checksum"))
        .unless(
          checksum.matches("[0-9a-f]{64}") &&
            MessageDigest.isEqual(
              actual.getBytes(StandardCharsets.US_ASCII),
              checksum.getBytes(StandardCharsets.US_ASCII)
            )
        )
      stored <- ZIO
        .fromEither(json.fromJson[StoredOutcome[S]])
        .mapError(_ => executionCorrupted("outcome-json"))
      _ <- ZIO
        .fail(executionCorrupted("outcome-schema-version"))
        .unless(stored.schemaVersion == CurrentOutcomeSchemaVersion)
      outcome <- stored.kind match
        case "Succeeded"
            if stored.reason.isEmpty && stored.waitCondition.isEmpty && stored.deadlineEpochMilli.isEmpty =>
          ZIO.succeed(NodeOutcome.Succeeded(stored.state))
        case "Suspended" if stored.waitCondition.isEmpty && stored.deadlineEpochMilli.isEmpty =>
          ZIO
            .fromOption(stored.reason)
            .map(reason => NodeOutcome.Suspended(stored.state, reason))
            .orElseFail(executionCorrupted("outcome-reason"))
        case "Awaiting" if stored.reason.isEmpty =>
          for
            deadlineMillis <- ZIO
              .fromOption(stored.deadlineEpochMilli)
              .orElseFail(executionCorrupted("outcome-wait-deadline"))
            deadline <- ZIO
              .attempt(Instant.ofEpochMilli(deadlineMillis))
              .mapError(_ => executionCorrupted("outcome-wait-deadline"))
            condition <- stored.waitCondition match
              case Some("Signal") =>
                ZIO
                  .fromOption(stored.signalName)
                  .flatMap(name => ZIO.fromEither(WorkflowSignalName.fromString(name)))
                  .map(WorkflowWaitCondition.Signal.apply)
                  .mapError(_ => executionCorrupted("outcome-wait-signal"))
              case Some("Timer") if stored.signalName.isEmpty => ZIO.succeed(WorkflowWaitCondition.Timer)
              case _ => ZIO.fail(executionCorrupted("outcome-wait-condition"))
          yield NodeOutcome.Awaiting(stored.state, WorkflowWaitRequest(condition, deadline))
        case _ => ZIO.fail(executionCorrupted("outcome-kind"))
    yield Some(outcome)

  private def validateDuration(duration: Duration): IO[StoreError, Unit] =
    if duration.toMillis > 0L then ZIO.unit
    else ZIO.fail(AgentError.PersistenceFailure("workflow execution leaseDuration 必须至少为 1 毫秒"))

  private def validateTimelineLimit(limit: Int): IO[StoreError, Unit] =
    if limit >= 1 && limit <= 500 then ZIO.unit
    else
      ZIO.fail(
        AgentError.PersistenceFailure(
          "PostgreSQL workflow execution timeline limit 必须位于 1..500"
        )
      )

  private def validateWaitLimit(limit: Int): IO[StoreError, Unit] =
    if limit >= 1 && limit <= 500 then ZIO.unit
    else ZIO.fail(AgentError.PersistenceFailure("PostgreSQL workflow wait limit 必须位于 1..500"))

  private def validateSignalPayload(payload: String): IO[StoreError, String] =
    val bytes = Option(payload).map(_.getBytes(StandardCharsets.UTF_8))
    bytes match
      case Some(value) if value.length <= config.maxSignalBytes => ZIO.succeed(sha256(value))
      case _ => ZIO.fail(AgentError.PersistenceFailure("PostgreSQL workflow signal payload 超过容量上限"))

  private def validateCommitIdentity(
      leases: Chunk[WorkflowExecutionLease],
      checkpoint: WorkflowCheckpoint[S]
  ): IO[StoreError, Unit] =
    val first = leases.head.key
    val valid = leases.nonEmpty &&
      leases.map(_.key).distinct.length == leases.length &&
      leases.forall { lease =>
        val key = lease.key
        key.runId == first.runId &&
        key.workflowId == checkpoint.workflowId &&
        key.definitionVersion == checkpoint.definitionVersion &&
        key.sessionId == checkpoint.sessionId &&
        checkpoint.step > key.step
      }
    if valid then ZIO.unit else ZIO.fail(executionConflict(first, "checkpoint-identity"))

  private def applyWaitCommit(
      connection: Connection,
      leases: Chunk[WorkflowExecutionLease],
      commit: WorkflowWaitCommit
  ): IO[StoreError, Unit] =
    val runId = leases.head.key.runId
    for
      _ <- commit.consume match
        case None                                    => ZIO.unit
        case Some(lease) if lease.key.runId != runId =>
          ZIO.fail(waitConflict(runId, "consume-run-mismatch"))
        case Some(lease) => consumeWait(connection, lease)
      _ <- commit.register match
        case None                       => ZIO.unit
        case Some((execution, request)) =>
          val identityMatches = execution.runId == runId &&
            execution.workflowId == leases.head.key.workflowId &&
            execution.definitionVersion == leases.head.key.definitionVersion &&
            execution.sessionId == leases.head.key.sessionId
          if !identityMatches || !leases.exists(_.key == execution) then
            ZIO.fail(waitConflict(runId, "register-execution-mismatch"))
          else registerWait(connection, execution, request)
    yield ()

  /** 只有当前未过期 wake lease 可以消费 wait；状态转换与 checkpoint/execution commit 共用外层事务。 */
  private def consumeWait(connection: Connection, lease: WorkflowWakeupLease): IO[StoreError, Unit] =
    jdbc("wait-consume") {
      val statement = connection.prepareStatement(
        """UPDATE agent_workflow_waits
          |SET status = 'Consumed', consumed_at = clock_timestamp(), wake_available_at = NULL,
          |    wake_owner = NULL, wake_token = NULL, wake_claimed_at = NULL,
          |    wake_lease_expires_at = NULL, wake_heartbeat_at = NULL
          |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
          |  AND status IN ('Signaled', 'TimedOut')
          |  AND wake_owner = ? AND wake_token = ?::uuid AND wake_generation = ?
          |  AND wake_lease_expires_at > clock_timestamp()""".stripMargin
      )
      try
        bindWakeFence(statement, 1, lease)
        statement.executeUpdate()
      finally statement.close()
    }.flatMap {
      case 1 => ZIO.unit
      case _ =>
        selectWakeStatus(connection, lease.key).flatMap {
          case Some(("Consumed", generation)) if generation == lease.generation => ZIO.unit
          case Some(("Pending", _))                                             =>
            ZIO.fail(waitConflict(lease.key.runId, "consume-pending-wait"))
          case Some(_) => ZIO.fail(wakeupLost(lease))
          case None    => ZIO.fail(waitConflict(lease.key.runId, "consume-wait-not-found"))
        }
    }

  private def selectWakeStatus(
      connection: Connection,
      key: WorkflowWaitKey
  ): IO[StoreError, Option[(String, Long)]] =
    jdbc("wait-wake-status") {
      val statement = connection.prepareStatement(
        """SELECT status, wake_generation
          |FROM agent_workflow_waits
          |WHERE run_id = ?::uuid AND step = ? AND node_id = ?
          |FOR UPDATE""".stripMargin
      )
      try
        statement.setString(1, key.runId.asString)
        statement.setInt(2, key.step)
        statement.setString(3, key.nodeId.value)
        val result = statement.executeQuery()
        if result.next() then Some(result.getString(1) -> result.getLong(2)) else None
      finally statement.close()
    }

  private def registerWait(
      connection: Connection,
      execution: WorkflowExecutionKey,
      request: WorkflowWaitRequest
  ): IO[StoreError, Unit] =
    val key = WorkflowWaitKey(execution.runId, execution.step, execution.nodeId)
    for
      now <- databaseNow(connection)
      _   <- ZIO
        .fail(waitConflict(key.runId, "register-deadline-not-future"))
        .unless(request.deadline.isAfter(now))
      active <- selectCurrentWait(connection, key.runId, forUpdate = true)
      _      <- active match
        case Some(row) if row.step != key.step || row.nodeId != key.nodeId.value =>
          ZIO.fail(waitConflict(key.runId, "active-wait-exists"))
        case _ => ZIO.unit
      inserted <- jdbc("wait-register") {
        val statement = connection.prepareStatement(
          """INSERT INTO agent_workflow_waits
            |(run_id, step, node_id, workflow_id, definition_version, session_id,
            | condition_kind, signal_name, deadline, status, created_at)
            |VALUES (?::uuid, ?, ?, ?, ?, ?::uuid, ?, ?, ?, 'Pending', ?)
            |ON CONFLICT (run_id, step, node_id) DO NOTHING""".stripMargin
        )
        try
          statement.setString(1, execution.runId.asString)
          statement.setInt(2, execution.step)
          statement.setString(3, execution.nodeId.value)
          statement.setString(4, execution.workflowId.value)
          statement.setInt(5, execution.definitionVersion.value)
          statement.setString(6, execution.sessionId.asString)
          request.condition match
            case WorkflowWaitCondition.Signal(name) =>
              statement.setString(7, "Signal")
              statement.setString(8, name.value)
            case WorkflowWaitCondition.Timer =>
              statement.setString(7, "Timer")
              statement.setNull(8, Types.VARCHAR)
          statement.setObject(9, OffsetDateTime.ofInstant(request.deadline, java.time.ZoneOffset.UTC))
          statement.setObject(10, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
          statement.executeUpdate() == 1
        finally statement.close()
      }
      _ <-
        if inserted then ZIO.unit
        else
          selectWait(connection, key, forUpdate = true)
            .someOrFail(waitConflict(key.runId, "register-row-missing"))
            .flatMap(decodeWait)
            .flatMap { existing =>
              val same = existing.workflowId == execution.workflowId &&
                existing.definitionVersion == execution.definitionVersion &&
                existing.sessionId == execution.sessionId &&
                existing.condition == request.condition &&
                existing.deadline == request.deadline
              ZIO.fail(waitConflict(key.runId, "wait-identity-conflict")).unless(same)
            }
    yield ()

  private def sameFence(
      record: WorkflowExecutionRecord[S],
      lease: WorkflowExecutionLease
  ): Boolean =
    record.key == lease.key &&
      record.owner == lease.owner &&
      record.token == lease.token &&
      record.generation == lease.generation

  private def bindExecutionKey(
      statement: java.sql.PreparedStatement,
      start: Int,
      key: WorkflowExecutionKey
  ): Unit =
    statement.setString(start, key.runId.asString)
    statement.setString(start + 1, key.workflowId.value)
    statement.setInt(start + 2, key.definitionVersion.value)
    statement.setString(start + 3, key.sessionId.asString)
    statement.setString(start + 4, key.nodeId.value)
    statement.setInt(start + 5, key.step)
    statement.setInt(start + 6, key.visit)

  private def bindFence(
      statement: java.sql.PreparedStatement,
      start: Int,
      lease: WorkflowExecutionLease
  ): Unit =
    statement.setString(start, lease.key.runId.asString)
    statement.setInt(start + 1, lease.key.step)
    statement.setString(start + 2, lease.key.nodeId.value)
    statement.setString(start + 3, lease.owner.value)
    statement.setString(start + 4, lease.token.value)
    statement.setLong(start + 5, lease.generation)

  /** 按 key + owner + token + generation 绑定完整 wakeup fencing 凭证。 */
  private def bindWakeFence(
      statement: java.sql.PreparedStatement,
      start: Int,
      lease: WorkflowWakeupLease
  ): Unit =
    statement.setString(start, lease.key.runId.asString)
    statement.setInt(start + 1, lease.key.step)
    statement.setString(start + 2, lease.key.nodeId.value)
    statement.setString(start + 3, lease.owner.value)
    statement.setString(start + 4, lease.token.value)
    statement.setLong(start + 5, lease.generation)

  private def executionConflict(key: WorkflowExecutionKey, reason: String): StoreError =
    AgentError.WorkflowCheckpointConflict(
      key.runId,
      s"execution:${key.nodeId.value}:${key.step}:$reason"
    )

  private def lost(lease: WorkflowExecutionLease): StoreError =
    AgentError.LeaseLost(
      lease.key.runId,
      lease.owner.value,
      lease.generation,
      s"workflow-node=${lease.key.nodeId.value},step=${lease.key.step}"
    )

  private def wakeupLost(lease: WorkflowWakeupLease): StoreError =
    AgentError.LeaseLost(
      lease.key.runId,
      lease.owner.value,
      lease.generation,
      s"workflow-wakeup=${lease.key.nodeId.value},step=${lease.key.step}"
    )

  private def waitConflict(runId: RunId, reason: String): StoreError =
    AgentError.WorkflowCheckpointConflict(runId, s"wait:$reason")

  private def encode(checkpoint: WorkflowCheckpoint[S]): IO[StoreError, EncodedCheckpoint] =
    validateCheckpoint(checkpoint, corrupted = false) *>
      ZIO
        .attempt {
          val stored = StoredCheckpoint(
            schemaVersion = CurrentSchemaVersion,
            workflowId = checkpoint.workflowId.value,
            definitionVersion = checkpoint.definitionVersion.value,
            sessionId = checkpoint.sessionId.asString,
            cursor = checkpoint.cursor match
              case WorkflowCursor.At(node)  => StoredCursor("At", Some(node.value))
              case WorkflowCursor.Completed => StoredCursor("Completed", None),
            state = checkpoint.state,
            step = checkpoint.step,
            visits = Chunk.fromIterable(
              checkpoint.visits.toList
                .sortBy(_._1.value)
                .map { case (node, count) => StoredVisit(node.value, count) }
            )
          )
          val json  = stored.toJson
          val bytes = json.getBytes(StandardCharsets.UTF_8)
          EncodedCheckpoint(json, bytes.length, sha256(bytes))
        }
        .mapError(_ => AgentError.PersistenceFailure("PostgreSQL workflow checkpoint encode-failed"))
        .flatMap { encoded =>
          ZIO
            .fail(AgentError.PersistenceFailure("PostgreSQL workflow checkpoint record-too-large"))
            .when(encoded.byteLength > config.maxCheckpointBytes)
            .as(encoded)
        }

  private def upsert(
      connection: Connection,
      runId: RunId,
      checkpoint: WorkflowCheckpoint[S],
      encoded: EncodedCheckpoint
  ): IO[StoreError, Boolean] =
    jdbc("save") {
      val statement = connection.prepareStatement(
        """INSERT INTO agent_workflow_checkpoints
          |(run_id, schema_version, workflow_id, definition_version, session_id,
          | cursor_kind, node_id, step, checkpoint_sha256, checkpoint_payload, checkpoint_json, updated_at)
          |VALUES (?::uuid, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
          |ON CONFLICT (run_id) DO UPDATE SET
          | schema_version = EXCLUDED.schema_version,
          | workflow_id = EXCLUDED.workflow_id,
          | definition_version = EXCLUDED.definition_version,
          | session_id = EXCLUDED.session_id,
          | cursor_kind = EXCLUDED.cursor_kind,
          | node_id = EXCLUDED.node_id,
          | step = EXCLUDED.step,
          | checkpoint_sha256 = EXCLUDED.checkpoint_sha256,
          | checkpoint_payload = EXCLUDED.checkpoint_payload,
          | checkpoint_json = EXCLUDED.checkpoint_json,
          | updated_at = CURRENT_TIMESTAMP
          |WHERE agent_workflow_checkpoints.workflow_id = EXCLUDED.workflow_id
          |  AND agent_workflow_checkpoints.definition_version = EXCLUDED.definition_version
          |  AND agent_workflow_checkpoints.session_id = EXCLUDED.session_id
          |  AND agent_workflow_checkpoints.cursor_kind <> 'Completed'
          |  AND agent_workflow_checkpoints.step < EXCLUDED.step""".stripMargin
      )
      try
        statement.setString(1, runId.asString)
        statement.setInt(2, CurrentSchemaVersion)
        statement.setString(3, checkpoint.workflowId.value)
        statement.setInt(4, checkpoint.definitionVersion.value)
        statement.setString(5, checkpoint.sessionId.asString)
        checkpoint.cursor match
          case WorkflowCursor.At(node) =>
            statement.setString(6, "At")
            statement.setString(7, node.value)
          case WorkflowCursor.Completed =>
            statement.setString(6, "Completed")
            statement.setNull(7, Types.VARCHAR)
        statement.setInt(8, checkpoint.step)
        statement.setString(9, encoded.sha256)
        statement.setString(10, encoded.json)
        statement.setString(11, encoded.json)
        statement.executeUpdate() == 1
      finally statement.close()
    }

  private def select(connection: Connection, runId: RunId): IO[StoreError, Option[StoredRow]] =
    jdbc("load") {
      val statement = connection.prepareStatement(
        """SELECT workflow_id, definition_version, session_id::text, cursor_kind, node_id, step,
          |       checkpoint_sha256, checkpoint_payload
          |FROM agent_workflow_checkpoints
          |WHERE run_id = ?::uuid""".stripMargin
      )
      try
        statement.setString(1, runId.asString)
        val result = statement.executeQuery()
        if result.next() then Some(readRow(result)) else None
      finally statement.close()
    }

  private def readRow(result: ResultSet): StoredRow =
    StoredRow(
      workflowId = result.getString(1),
      definitionVersion = result.getInt(2),
      sessionId = result.getString(3),
      cursorKind = result.getString(4),
      nodeId = Option(result.getString(5)),
      step = result.getInt(6),
      sha256 = result.getString(7),
      json = result.getString(8)
    )

  private def decode(row: StoredRow): IO[StoreError, WorkflowCheckpoint[S]] =
    val json     = Option(row.json).getOrElse("")
    val checksum = Option(row.sha256).getOrElse("")
    val bytes    = json.getBytes(StandardCharsets.UTF_8)
    for
      _ <- ZIO
        .fail(corrupted("record-too-large"))
        .when(bytes.length > config.maxCheckpointBytes)
      _ <- ZIO
        .fail(corrupted("invalid-checksum"))
        .unless(checksum.matches("[0-9a-f]{64}"))
      actualHash = sha256(bytes)
      _ <- ZIO
        .fail(corrupted("checksum-mismatch"))
        .unless(
          MessageDigest.isEqual(
            actualHash.getBytes(StandardCharsets.US_ASCII),
            checksum.getBytes(StandardCharsets.US_ASCII)
          )
        )
      stored <- ZIO
        .fromEither(json.fromJson[StoredCheckpoint[S]])
        .mapError(_ => corrupted("invalid-checkpoint-json"))
      checkpoint <- fromStored(stored)
      _          <- validateCheckpoint(checkpoint, corrupted = true)
      columnsMatch =
        stored.schemaVersion == CurrentSchemaVersion &&
          checkpoint.workflowId.value == row.workflowId &&
          checkpoint.definitionVersion.value == row.definitionVersion &&
          checkpoint.sessionId.asString == row.sessionId &&
          checkpoint.step == row.step &&
          (checkpoint.cursor match
            case WorkflowCursor.At(node)  => row.cursorKind == "At" && row.nodeId.contains(node.value)
            case WorkflowCursor.Completed => row.cursorKind == "Completed" && row.nodeId.isEmpty)
      _ <- ZIO.fail(corrupted("column-checkpoint-mismatch")).unless(columnsMatch)
    yield checkpoint

  private def fromStored(stored: StoredCheckpoint[S]): IO[StoreError, WorkflowCheckpoint[S]] =
    for
      _ <- ZIO
        .fail(corrupted("schema-version"))
        .unless(stored.schemaVersion == CurrentSchemaVersion)
      workflowId <- ZIO
        .fromEither(WorkflowId.fromString(stored.workflowId))
        .mapError(_ => corrupted("workflow-id"))
      definitionVersion <- ZIO
        .fromEither(WorkflowVersion.fromInt(stored.definitionVersion))
        .mapError(_ => corrupted("definition-version"))
      sessionId <- ZIO
        .fromEither(SessionId.fromString(stored.sessionId))
        .mapError(_ => corrupted("session-id"))
      cursor <- stored.cursor match
        case StoredCursor("At", Some(nodeId)) =>
          ZIO
            .fromEither(NodeId.fromString(nodeId))
            .map(WorkflowCursor.At.apply)
            .mapError(_ => corrupted("node-id"))
        case StoredCursor("Completed", None) => ZIO.succeed(WorkflowCursor.Completed)
        case _                               => ZIO.fail(corrupted("cursor"))
      _ <- ZIO.fail(corrupted("step")).when(stored.step < 0)
      _ <- ZIO
        .fail(corrupted("visit-count"))
        .when(
          stored.visits.length > config.maxVisitEntries ||
            stored.visits.exists(_.count <= 0)
        )
      visits <- ZIO.foreach(stored.visits) { visit =>
        ZIO
          .fromEither(NodeId.fromString(visit.nodeId))
          .map(_ -> visit.count)
          .mapError(_ => corrupted("visit-node-id"))
      }
      _ <- ZIO
        .fail(corrupted("duplicate-visit-node"))
        .unless(visits.map(_._1).distinct.length == visits.length)
      _ <- ZIO
        .fail(corrupted("step-visit-mismatch"))
        .unless(visits.map(_._2.toLong).sum == stored.step.toLong)
    yield WorkflowCheckpoint(
      workflowId,
      definitionVersion,
      sessionId,
      cursor,
      stored.state,
      stored.step,
      visits.toMap
    )

  private def validateCheckpoint(
      checkpoint: WorkflowCheckpoint[S],
      corrupted: Boolean
  ): IO[StoreError, Unit] =
    val invalid =
      checkpoint.step < 0 ||
        checkpoint.visits.size > config.maxVisitEntries ||
        checkpoint.visits.values.exists(_ <= 0) ||
        checkpoint.visits.valuesIterator.map(_.toLong).sum != checkpoint.step.toLong
    if !invalid then ZIO.unit
    else if corrupted then ZIO.fail(PostgresWorkflowCheckpointStore.corrupted("invalid-domain"))
    else ZIO.fail(AgentError.PersistenceFailure("PostgreSQL workflow checkpoint invalid-domain"))

  private def withConnection[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(jdbc("acquire-connection")(dataSource.getConnection))(connection =>
          ZIO.attemptBlocking(connection.close()).ignore
        )
        .flatMap(use)
    }

  /** execution ledger 与 checkpoint 必须共享同一短事务；任何 fenced 校验或解码失败都会回滚。 */
  private def withTransaction[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    withConnection { connection =>
      jdbc("begin-transaction")(connection.setAutoCommit(false)) *>
        use(connection)
          .tapBoth(
            _ => ZIO.attemptBlocking(connection.rollback()).orDie,
            _ => ZIO.attemptBlocking(connection.commit()).orDie
          )
          .ensuring(ZIO.attemptBlocking(connection.setAutoCommit(true)).orDie)
    }

  private def jdbc[A](operation: String)(effect: => A): IO[StoreError, A] =
    ZIO.attemptBlocking(effect).mapError(error => databaseError(operation, error))

  private def databaseError(operation: String, error: Throwable): StoreError = error match
    case LostWorkflowExecutionLease(lease) => lost(lease)
    case LostWorkflowWakeLease(lease)      => wakeupLost(lease)
    case sql: SQLException                 =>
      val state     = Option(sql.getSQLState).getOrElse("unknown")
      val retryable =
        state.startsWith("08") ||
          state.startsWith("40") ||
          state.startsWith("53") ||
          state == "57014" ||
          Set("57P01", "57P02", "57P03").contains(state)
      AgentError.DatabaseFailure(
        s"PostgreSQL workflow checkpoint $operation 失败",
        state,
        retryable,
        Some(sql)
      )
    case other =>
      AgentError.PersistenceFailure(
        s"PostgreSQL workflow checkpoint $operation 失败",
        Some(other)
      )

object PostgresWorkflowCheckpointStore:
  private val CurrentSchemaVersion        = 1
  private val CurrentOutcomeSchemaVersion = 2

  final private case class StoredCursor(kind: String, nodeId: Option[String]) derives JsonCodec
  final private case class StoredVisit(nodeId: String, count: Int) derives JsonCodec
  final private case class StoredCheckpoint[S](
      schemaVersion: Int,
      workflowId: String,
      definitionVersion: Int,
      sessionId: String,
      cursor: StoredCursor,
      state: S,
      step: Int,
      visits: Chunk[StoredVisit]
  ) derives JsonCodec

  final private case class EncodedCheckpoint(json: String, byteLength: Int, sha256: String)
  final private case class StoredOutcome[S](
      schemaVersion: Int,
      kind: String,
      state: S,
      reason: Option[String],
      waitCondition: Option[String],
      signalName: Option[String],
      deadlineEpochMilli: Option[Long]
  ) derives JsonCodec
  final private case class EncodedOutcome(json: String, byteLength: Int, sha256: String)
  final private case class StoredExecutionRow(
      runId: RunId,
      workflowId: String,
      definitionVersion: Int,
      sessionId: String,
      nodeId: String,
      step: Int,
      visit: Int,
      status: String,
      generation: Long,
      owner: String,
      token: String,
      claimedAt: Instant,
      expiresAt: Option[Instant],
      updatedAt: Instant,
      completedAt: Option[Instant],
      outcomeSha256: Option[String],
      outcomeJson: Option[String],
      leaseActive: Boolean
  )
  final private case class StoredWaitRow(
      runId: String,
      step: Int,
      nodeId: String,
      workflowId: String,
      definitionVersion: Int,
      sessionId: String,
      conditionKind: String,
      signalName: Option[String],
      deadline: Instant,
      status: String,
      acceptedSignalId: Option[String],
      acceptedSignalPayload: Option[String],
      acceptedSignalSha256: Option[String],
      signalReceivedAt: Option[Instant],
      createdAt: Instant,
      resolvedAt: Option[Instant],
      consumedAt: Option[Instant]
  )
  final private case class StoredSignalRow(
      name: String,
      payload: String,
      payloadSha256: String,
      disposition: String,
      receivedAt: Instant
  )
  private enum SaveResult:
    case Written
    case Existing(row: StoredRow)
    case ConflictRowMissing

  final private case class StoredRow(
      workflowId: String,
      definitionVersion: Int,
      sessionId: String,
      cursorKind: String,
      nodeId: Option[String],
      step: Int,
      sha256: String,
      json: String
  )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def corrupted(code: String): StoreError =
    AgentError.DatabaseFailure(
      s"PostgreSQL workflow checkpoint 数据损坏 (code=$code)",
      "data-corruption",
      retryable = false
    )

  private def executionCorrupted(code: String): StoreError =
    AgentError.DatabaseFailure(
      s"PostgreSQL workflow execution 数据损坏 (code=$code)",
      "data-corruption",
      retryable = false
    )

  private def waitCorrupted(code: String): StoreError =
    AgentError.DatabaseFailure(
      s"PostgreSQL workflow wait 数据损坏 (code=$code)",
      "data-corruption",
      retryable = false
    )

  final private case class LostWorkflowExecutionLease(lease: WorkflowExecutionLease) extends RuntimeException
  final private case class LostWorkflowWakeLease(lease: WorkflowWakeupLease)         extends RuntimeException

  def layer[S: JsonCodec: Tag]: URLayer[DataSource, WorkflowCheckpointStore[S]] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresWorkflowCheckpointStore[S](dataSource): WorkflowCheckpointStore[S]
    )

  def configured[S: JsonCodec: Tag](
      config: PostgresWorkflowCheckpointStoreConfig
  ): URLayer[DataSource, WorkflowCheckpointStore[S]] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresWorkflowCheckpointStore[S](dataSource, config): WorkflowCheckpointStore[S]
    )

  /** 节点执行台账、pending outcome 与 checkpoint 的统一耐久层。 */
  def executionLayer[S: JsonCodec: Tag]: URLayer[DataSource, WorkflowExecutionStore[S]] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresWorkflowCheckpointStore[S](dataSource): WorkflowExecutionStore[S]
    )

  def configuredExecution[S: JsonCodec: Tag](
      config: PostgresWorkflowCheckpointStoreConfig
  ): URLayer[DataSource, WorkflowExecutionStore[S]] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresWorkflowCheckpointStore[S](dataSource, config): WorkflowExecutionStore[S]
    )
