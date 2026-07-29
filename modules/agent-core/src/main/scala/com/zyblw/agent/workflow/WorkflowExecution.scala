package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{LeaseToken, WorkerId}
import java.time.Instant
import zio.*

/** 一次节点访问的稳定执行身份；`step` 是节点入口游标，`visit` 防止有界循环的不同轮次混淆。
  *
  * @param runId
  *   当前 Workflow Run
  * @param workflowId
  *   创建 Run 时冻结的 Workflow identity
  * @param definitionVersion
  *   创建 Run 时冻结的定义版本
  * @param sessionId
  *   与 checkpoint 相同的业务会话身份
  * @param nodeId
  *   本次访问的节点
  * @param step
  *   进入节点前的全局非负步骤号
  * @param visit
  *   当前节点从 1 开始的访问次数
  */
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

/** 可审计的节点执行台账记录；状态正文只通过 Store 返回，日志和错误不得自动展开 `outcome`。
  *
  * @param key
  *   完整节点访问身份
  * @param status
  *   当前耐久阶段
  * @param outcome
  *   Prepared/Committed 才存在的节点结果，可能包含敏感应用状态
  * @param generation
  *   过期重领时严格递增的 fencing 代数
  * @param owner
  *   当前或最后持有该 execution 的 worker
  * @param token
  *   每次 claim 随机换发的 fencing token，不得进入外部 timeline
  * @param claimedAt
  *   当前 generation 的领取时间
  * @param expiresAt
  *   Running/Prepared 的租约截止；Committed 必须为空
  * @param updatedAt
  *   最近一次耐久状态更新时间
  * @param completedAt
  *   Committed 的完成时间
  */
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

/** claim 后用于所有 heartbeat、prepare 与 commit 的完整 fencing 凭证。
  *
  * 凭证必须作为整体传递，不能只比较 owner 或 generation。任何字段不匹配、租约过期或 execution 已提交都应使写入 fail-closed。
  */
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

/** execution timeline 的稳定分页游标。
  *
  * `step` 单独不足以覆盖未来可能共享同一逻辑步骤的并行节点，因此游标同时携带 `nodeId`，并按 `(step, nodeId)` 做 排他翻页。游标只描述位置，不授予读取权限。
  *
  * @param step
  *   上一页最后一条 execution 的非负步骤号
  * @param nodeId
  *   上一页最后一条 execution 的稳定节点 ID
  */
final case class WorkflowTimelineCursor(step: Int, nodeId: NodeId):
  require(step >= 0, "Workflow timeline cursor step 不能为负数")

/** 面向 Inspector、CLI 与运维查询的低敏节点执行投影。
  *
  * 该投影故意不包含节点输入、状态正文、pending outcome、lease token 或工具结果；需要恢复时必须读取权威 checkpoint 和 execution record，不能从 timeline
  * 反推状态。`owner` 用于判断抢占与僵尸 worker，仍应只向经过租户授权的运维 调用方暴露。
  *
  * @param cursor
  *   当前记录的稳定分页位置
  * @param visit
  *   当前节点在有界循环中的第几次访问
  * @param status
  *   Running、Prepared 或 Committed 耐久阶段
  * @param generation
  *   每次过期重领都会递增的 fencing 代数
  * @param owner
  *   当前或最后持有 execution lease 的 worker
  * @param outcomeAvailable
  *   是否已有耐久 outcome；只暴露存在性，不暴露正文
  */
final case class WorkflowExecutionTimelineEntry(
    cursor: WorkflowTimelineCursor,
    visit: Int,
    status: WorkflowExecutionStatus,
    generation: Long,
    owner: WorkerId,
    claimedAt: Instant,
    expiresAt: Option[Instant],
    updatedAt: Instant,
    completedAt: Option[Instant],
    outcomeAvailable: Boolean
)

object WorkflowExecutionTimelineEntry:
  /** 从权威 execution record 生成低敏投影，不复制状态或 fencing token。 */
  def fromRecord[S](record: WorkflowExecutionRecord[S]): WorkflowExecutionTimelineEntry =
    WorkflowExecutionTimelineEntry(
      WorkflowTimelineCursor(record.key.step, record.key.nodeId),
      record.key.visit,
      record.status,
      record.generation,
      record.owner,
      record.claimedAt,
      record.expiresAt,
      record.updatedAt,
      record.completedAt,
      record.outcome.nonEmpty
    )

/** claim 不把“已有 owner 正在执行”伪装成空队列，也不把已提交事实重新授权。 */
enum WorkflowExecutionClaim[S]:
  case Acquired(lease: WorkflowExecutionLease, prepared: Option[NodeOutcome[S]])
  case Busy[S](owner: WorkerId, generation: Long, expiresAt: Instant) extends WorkflowExecutionClaim[S]
  case Committed[S](generation: Long, completedAt: Instant)           extends WorkflowExecutionClaim[S]

/** Durable Workflow 的执行所有权与心跳策略。
  *
  * @param owner
  *   当前进程启动唯一的可信 Worker ID
  * @param leaseDuration
  *   Worker 无心跳后允许被其它实例重领的时间
  * @param heartbeatInterval
  *   活跃节点续租间隔，必须严格小于 leaseDuration
  */
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
  /** 取得或重领一个节点 execution 的排他执行权。
    *
    * @param key
    *   包含 Run、定义版本、节点、步骤和访问次数的完整执行身份
    * @param owner
    *   可信 worker 身份，不能来自模型输出
    * @param leaseDuration
    *   本次租约有效期；必须由 heartbeat 在到期前续租
    */
  def claim(
      key: WorkflowExecutionKey,
      owner: WorkerId,
      leaseDuration: Duration
  ): IO[StoreError, WorkflowExecutionClaim[S]]

  /** 仅在 owner/token/generation 均匹配且租约未过期时续租。 */
  def heartbeat(
      lease: WorkflowExecutionLease,
      leaseDuration: Duration
  ): IO[StoreError, WorkflowExecutionLease]

  /** 在提交 checkpoint 前耐久保存节点 outcome，关闭“节点成功但 checkpoint 丢失”的重复执行窗口。 */
  def prepare(
      lease: WorkflowExecutionLease,
      outcome: NodeOutcome[S]
  ): IO[StoreError, WorkflowExecutionRecord[S]]

  /** 在一个原子边界内把全部 Prepared execution 标记为 Committed 并推进 checkpoint。 */
  def commit(
      leases: NonEmptyChunk[WorkflowExecutionLease],
      checkpoint: WorkflowCheckpoint[S]
  ): IO[StoreError, Unit]

  /** 按完整 execution identity 读取权威账本记录；该记录可能包含状态正文，不适合直接暴露给外部协议。 */
  def get(key: WorkflowExecutionKey): IO[StoreError, Option[WorkflowExecutionRecord[S]]]

  /** 按 `(step, nodeId)` 稳定顺序读取低敏 execution timeline。
    *
    * `after` 为排他游标，`limit` 必须位于 1..500。Store 不负责租户授权；HTTP/CLI Adapter 必须先用可信身份验证该 `runId` 的读取权限。
    */
  def timeline(
      runId: RunId,
      after: Option[WorkflowTimelineCursor] = None,
      limit: Int = 100
  ): IO[StoreError, Chunk[WorkflowExecutionTimelineEntry]] =
    val _ = (runId, after, limit)
    ZIO.fail(
      AgentError.PersistenceFailure(
        "WorkflowExecutionStore Adapter 尚未实现低敏 execution timeline"
      )
    )

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
                if executionRunIdentityConflicts(current, key) then
                  ZIO.fail(conflict(key, "run-execution-identity"))
                else
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

        override def timeline(
            runId: RunId,
            after: Option[WorkflowTimelineCursor],
            limit: Int
        ): IO[StoreError, Chunk[WorkflowExecutionTimelineEntry]] =
          validateTimelineLimit(limit) *>
            state.get.map { current =>
              Chunk.fromIterable(
                current.executions.valuesIterator
                  .filter(_.key.runId == runId)
                  .filter(record => after.forall(cursor => isAfter(record.key, cursor)))
                  .toList
                  .sortBy(record => record.key.step -> record.key.nodeId.value)
                  .take(limit)
                  .map(record => WorkflowExecutionTimelineEntry.fromRecord(record))
              )
            }
    }
  }

  private def validateDuration(duration: Duration): IO[StoreError, Unit] =
    if duration > Duration.Zero then ZIO.unit
    else ZIO.fail(AgentError.PersistenceFailure("workflow execution leaseDuration 必须大于零"))

  private def validateTimelineLimit(limit: Int): IO[StoreError, Unit] =
    if limit >= 1 && limit <= 500 then ZIO.unit
    else ZIO.fail(AgentError.PersistenceFailure("workflow execution timeline limit 必须位于 1..500"))

  private def isAfter(key: WorkflowExecutionKey, cursor: WorkflowTimelineCursor): Boolean =
    key.step > cursor.step ||
      (key.step == cursor.step && key.nodeId.value > cursor.nodeId.value)

  /** 同一 Run 的 checkpoint 与所有节点 execution 必须共享冻结的 Workflow/version/session identity。 */
  private def executionRunIdentityConflicts[S](
      state: MemoryState[S],
      key: WorkflowExecutionKey
  ): Boolean =
    val checkpointConflict = state.checkpoints.get(key.runId).exists { checkpoint =>
      checkpoint.workflowId != key.workflowId ||
      checkpoint.definitionVersion != key.definitionVersion ||
      checkpoint.sessionId != key.sessionId
    }
    val executionConflict = state.executions.valuesIterator.exists { record =>
      record.key.runId == key.runId &&
      slot(record.key) != slot(key) &&
      (record.key.workflowId != key.workflowId ||
        record.key.definitionVersion != key.definitionVersion ||
        record.key.sessionId != key.sessionId)
    }
    checkpointConflict || executionConflict

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
