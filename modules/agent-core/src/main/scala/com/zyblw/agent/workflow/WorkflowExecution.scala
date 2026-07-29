package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{LeaseToken, WorkerId}
import java.time.Instant
import zio.*

/** 一次节点访问的稳定执行身份；`step` 是节点入口游标，`visit` 防止有界循环的不同轮次混淆。 */
final case class WorkflowExecutionKey(
    runId: RunId,
    workflowId: WorkflowId,
    definitionVersion: WorkflowVersion,
    sessionId: SessionId,
    nodeId: NodeId,
    step: Int,
    visit: Int
):
  require(step >= 0, "Workflow execution step 不能为负数")
  require(visit > 0, "Workflow execution visit 必须大于零")

/** 节点执行事实的生命周期。
  *
  * `Prepared` 表示节点结果已经耐久保存但尚未与 checkpoint 一起提交；进程崩溃后新 owner 可直接复用结果，不再调用节点。
  */
enum WorkflowExecutionStatus:
  case Running, Prepared, Committed

/** 可审计的节点执行台账记录；状态正文只通过 Store 返回，日志和错误不得自动展开 `outcome`。 */
final case class WorkflowExecutionRecord[S](
    key: WorkflowExecutionKey,
    status: WorkflowExecutionStatus,
    outcome: Option[NodeOutcome[S]],
    generation: Long,
    owner: WorkerId,
    token: LeaseToken,
    claimedAt: Instant,
    expiresAt: Option[Instant],
    updatedAt: Instant,
    completedAt: Option[Instant]
):
  require(generation > 0, "Workflow execution generation 必须大于零")
  require(
    (status == WorkflowExecutionStatus.Running && outcome.isEmpty) ||
      (status != WorkflowExecutionStatus.Running && outcome.nonEmpty),
    "Workflow execution status 与 outcome 不一致"
  )
  require(
    (status == WorkflowExecutionStatus.Committed) == completedAt.nonEmpty,
    "Workflow execution committed 状态与 completedAt 不一致"
  )
  require(
    (status == WorkflowExecutionStatus.Committed) == expiresAt.isEmpty,
    "Workflow execution committed 状态不能保留有效期"
  )

/** claim 后用于所有 heartbeat、prepare 与 commit 的完整 fencing 凭证。 */
final case class WorkflowExecutionLease(
    key: WorkflowExecutionKey,
    owner: WorkerId,
    token: LeaseToken,
    generation: Long,
    claimedAt: Instant,
    expiresAt: Instant
):
  require(generation > 0, "Workflow execution lease generation 必须大于零")
  require(expiresAt.isAfter(claimedAt), "Workflow execution lease 必须晚于 claimedAt")

/** claim 不把“已有 owner 正在执行”伪装成空队列，也不把已提交事实重新授权。 */
enum WorkflowExecutionClaim[S]:
  case Acquired(lease: WorkflowExecutionLease, prepared: Option[NodeOutcome[S]])
  case Busy[S](owner: WorkerId, generation: Long, expiresAt: Instant) extends WorkflowExecutionClaim[S]
  case Committed[S](generation: Long, completedAt: Instant)           extends WorkflowExecutionClaim[S]

/** Durable Workflow 的执行所有权与心跳策略。 */
final case class WorkflowExecutionPolicy(
    owner: WorkerId,
    leaseDuration: Duration = 30.seconds,
    heartbeatInterval: Duration = 10.seconds
):
  require(leaseDuration > Duration.Zero, "Workflow execution leaseDuration 必须大于零")
  require(heartbeatInterval > Duration.Zero, "Workflow execution heartbeatInterval 必须大于零")
  require(
    heartbeatInterval < leaseDuration,
    "Workflow execution heartbeatInterval 必须小于 leaseDuration"
  )

/** 节点执行台账、pending result 与 checkpoint 的统一耐久边界。
  *
  * 实现必须：
  *   - 对 `(runId, step, nodeId)` 串行 claim，并同时比较 owner、token、generation 与未过期时间；
  *   - `prepare` 先耐久保存节点结果；
  *   - `commit` 在同一个原子临界区/数据库事务内提交全部 execution 与 checkpoint；
  *   - 过期 `Prepared` 被新 owner claim 时保留 outcome，以关闭“节点成功、checkpoint 未提交”的重复执行窗口。
  *
  * 这不会自动使节点内部的外部副作用 exactly-once；不可幂等副作用仍应使用业务幂等键或 outbox/inbox。
  */
trait WorkflowExecutionStore[S] extends WorkflowCheckpointStore[S]:
  def claim(
      key: WorkflowExecutionKey,
      owner: WorkerId,
      leaseDuration: Duration
  ): IO[StoreError, WorkflowExecutionClaim[S]]

  def heartbeat(
      lease: WorkflowExecutionLease,
      leaseDuration: Duration
  ): IO[StoreError, WorkflowExecutionLease]

  def prepare(
      lease: WorkflowExecutionLease,
      outcome: NodeOutcome[S]
  ): IO[StoreError, WorkflowExecutionRecord[S]]

  def commit(
      leases: NonEmptyChunk[WorkflowExecutionLease],
      checkpoint: WorkflowCheckpoint[S]
  ): IO[StoreError, Unit]

  def get(key: WorkflowExecutionKey): IO[StoreError, Option[WorkflowExecutionRecord[S]]]

object WorkflowExecutionStore:
  final private case class MemoryExecutionSlot(runId: RunId, step: Int, nodeId: NodeId)

  final private case class MemoryState[S](
      checkpoints: Map[RunId, WorkflowCheckpoint[S]],
      executions: Map[MemoryExecutionSlot, WorkflowExecutionRecord[S]]
  )

  /** 单进程开发与测试实现；`Ref.Synchronized` 让 ledger 与 checkpoint 在同一原子临界区推进。 */
  def inMemory[S: Tag]: ULayer[WorkflowExecutionStore[S]] = ZLayer.fromZIO {
    Ref.Synchronized.make(MemoryState[S](Map.empty, Map.empty)).map { state =>
      new WorkflowExecutionStore[S]:
        override def save(runId: RunId, checkpoint: WorkflowCheckpoint[S]): IO[StoreError, Unit] =
          state.modifyZIO { current =>
            advanceCheckpoint(current.checkpoints, runId, checkpoint).map { checkpoints =>
              () -> current.copy(checkpoints = checkpoints)
            }
          }

        override def load(runId: RunId): UIO[Option[WorkflowCheckpoint[S]]] =
          state.get.map(_.checkpoints.get(runId))

        override def claim(
            key: WorkflowExecutionKey,
            owner: WorkerId,
            leaseDuration: Duration
        ): IO[StoreError, WorkflowExecutionClaim[S]] =
          validateDuration(leaseDuration) *> Clock.instant.flatMap { now =>
            LeaseToken.random.flatMap { token =>
              state.modifyZIO { current =>
                val executionSlot = slot(key)
                current.executions.get(executionSlot) match
                  case Some(record) if record.key != key =>
                    ZIO.fail(conflict(key, "execution-identity"))
                  case Some(record) if record.status == WorkflowExecutionStatus.Committed =>
                    ZIO.succeed(
                      WorkflowExecutionClaim.Committed(
                        record.generation,
                        record.completedAt.getOrElse(record.updatedAt)
                      ) -> current
                    )
                  case Some(record) if record.expiresAt.exists(_.isAfter(now)) =>
                    ZIO.succeed(
                      WorkflowExecutionClaim.Busy(
                        record.owner,
                        record.generation,
                        record.expiresAt.getOrElse(record.updatedAt)
                      ) -> current
                    )
                  case existing =>
                    val generation = existing.fold(1L)(_.generation + 1L)
                    val expiresAt  = now.plusMillis(leaseDuration.toMillis)
                    val lease      =
                      WorkflowExecutionLease(key, owner, token, generation, now, expiresAt)
                    val prepared = existing.flatMap(_.outcome)
                    val status   =
                      if prepared.isDefined then WorkflowExecutionStatus.Prepared
                      else WorkflowExecutionStatus.Running
                    val record = WorkflowExecutionRecord(
                      key,
                      status,
                      prepared,
                      generation,
                      owner,
                      token,
                      now,
                      Some(expiresAt),
                      now,
                      None
                    )
                    ZIO.succeed(
                      WorkflowExecutionClaim.Acquired(lease, prepared) ->
                        current.copy(executions = current.executions.updated(executionSlot, record))
                    )
              }
            }
          }

        override def heartbeat(
            lease: WorkflowExecutionLease,
            leaseDuration: Duration
        ): IO[StoreError, WorkflowExecutionLease] =
          validateDuration(leaseDuration) *> Clock.instant.flatMap { now =>
            state.modifyZIO { current =>
              val executionSlot = slot(lease.key)
              current.executions.get(executionSlot) match
                case Some(record) if owns(record, lease, now) =>
                  val renewed = lease.copy(expiresAt = now.plusMillis(leaseDuration.toMillis))
                  val updated = record.copy(expiresAt = Some(renewed.expiresAt), updatedAt = now)
                  ZIO.succeed(
                    renewed -> current.copy(executions = current.executions.updated(executionSlot, updated))
                  )
                case _ => ZIO.fail(lost(lease))
            }
          }

        override def prepare(
            lease: WorkflowExecutionLease,
            outcome: NodeOutcome[S]
        ): IO[StoreError, WorkflowExecutionRecord[S]] =
          Clock.instant.flatMap { now =>
            state.modifyZIO { current =>
              val executionSlot = slot(lease.key)
              current.executions.get(executionSlot) match
                case Some(record)
                    if owns(record, lease, now) &&
                      record.status == WorkflowExecutionStatus.Running =>
                  val prepared = record.copy(
                    status = WorkflowExecutionStatus.Prepared,
                    outcome = Some(outcome),
                    updatedAt = now
                  )
                  ZIO.succeed(
                    prepared -> current.copy(executions = current.executions.updated(executionSlot, prepared))
                  )
                case Some(record)
                    if owns(record, lease, now) &&
                      record.status == WorkflowExecutionStatus.Prepared &&
                      record.outcome.contains(outcome) =>
                  ZIO.succeed(record -> current)
                case Some(record) if owns(record, lease, now) =>
                  ZIO.fail(conflict(lease.key, s"prepare-from-${record.status.toString}"))
                case _ => ZIO.fail(lost(lease))
            }
          }

        override def commit(
            leases: NonEmptyChunk[WorkflowExecutionLease],
            checkpoint: WorkflowCheckpoint[S]
        ): IO[StoreError, Unit] =
          Clock.instant.flatMap { now =>
            state.modifyZIO { current =>
              val values = Chunk.fromIterable(leases)
              validateCommitIdentity(values, checkpoint) *>
                ZIO
                  .foreach(values)(lease =>
                    ZIO
                      .fromOption(current.executions.get(slot(lease.key)))
                      .orElseFail(lost(lease))
                  )
                  .flatMap { records =>
                    val activePrepared = records.zip(values).forall { case (record, lease) =>
                      owns(record, lease, now) && record.status == WorkflowExecutionStatus.Prepared
                    }
                    val idempotentCommitted = records.zip(values).forall { case (record, lease) =>
                      sameFence(record, lease) && record.status == WorkflowExecutionStatus.Committed
                    }
                    val checkpointAlreadyCommitted =
                      current.checkpoints.get(checkpointRunId(values)).contains(checkpoint)
                    if !activePrepared && !(idempotentCommitted && checkpointAlreadyCommitted) then
                      ZIO.fail(conflict(values.head.key, "commit-fence-or-status"))
                    else if idempotentCommitted then ZIO.succeed(() -> current)
                    else
                      advanceCheckpoint(
                        current.checkpoints,
                        checkpointRunId(values),
                        checkpoint
                      ).map { checkpoints =>
                        val executions =
                          records.zip(values).foldLeft(current.executions) { case (acc, (record, lease)) =>
                            acc.updated(
                              slot(lease.key),
                              record.copy(
                                status = WorkflowExecutionStatus.Committed,
                                expiresAt = None,
                                updatedAt = now,
                                completedAt = Some(now)
                              )
                            )
                          }
                        () -> current.copy(checkpoints = checkpoints, executions = executions)
                      }
                  }
            }
          }

        override def get(key: WorkflowExecutionKey): IO[StoreError, Option[WorkflowExecutionRecord[S]]] =
          state.get.flatMap { current =>
            current.executions.get(slot(key)) match
              case Some(record) if record.key != key =>
                ZIO.fail(conflict(key, "execution-identity"))
              case value => ZIO.succeed(value)
          }
    }
  }

  private def validateDuration(duration: Duration): IO[StoreError, Unit] =
    if duration > Duration.Zero then ZIO.unit
    else ZIO.fail(AgentError.PersistenceFailure("workflow execution leaseDuration 必须大于零"))

  private def validateCommitIdentity[S](
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
    if valid then ZIO.unit else ZIO.fail(conflict(first, "checkpoint-identity"))

  private def checkpointRunId(leases: Chunk[WorkflowExecutionLease]): RunId = leases.head.key.runId

  private def slot(key: WorkflowExecutionKey): MemoryExecutionSlot =
    MemoryExecutionSlot(key.runId, key.step, key.nodeId)

  private def advanceCheckpoint[S](
      checkpoints: Map[RunId, WorkflowCheckpoint[S]],
      runId: RunId,
      checkpoint: WorkflowCheckpoint[S]
  ): IO[StoreError, Map[RunId, WorkflowCheckpoint[S]]] =
    checkpoints.get(runId) match
      case None                                     => ZIO.succeed(checkpoints.updated(runId, checkpoint))
      case Some(existing) if existing == checkpoint => ZIO.succeed(checkpoints)
      case Some(existing)
          if sameCheckpointIdentity(existing, checkpoint) &&
            existing.cursor != WorkflowCursor.Completed &&
            checkpoint.step > existing.step =>
        ZIO.succeed(checkpoints.updated(runId, checkpoint))
      case Some(_) => ZIO.fail(AgentError.WorkflowCheckpointConflict(runId, "non-monotonic-write"))

  private def sameCheckpointIdentity[S](
      left: WorkflowCheckpoint[S],
      right: WorkflowCheckpoint[S]
  ): Boolean =
    left.workflowId == right.workflowId &&
      left.definitionVersion == right.definitionVersion &&
      left.sessionId == right.sessionId

  private def owns[S](
      record: WorkflowExecutionRecord[S],
      lease: WorkflowExecutionLease,
      now: Instant
  ): Boolean =
    sameFence(record, lease) &&
      record.status != WorkflowExecutionStatus.Committed &&
      record.expiresAt.exists(_.isAfter(now))

  private def sameFence[S](
      record: WorkflowExecutionRecord[S],
      lease: WorkflowExecutionLease
  ): Boolean =
    record.key == lease.key &&
      record.owner == lease.owner &&
      record.token == lease.token &&
      record.generation == lease.generation

  private def conflict(key: WorkflowExecutionKey, reason: String): StoreError =
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
