package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.json.*

/** Worker 的稳定标识。
  *
  * @param value
  *   非空部署实例标识，推荐“应用实例名 + 启动 UUID”；不能包含密钥或用户隐私
  */
final case class WorkerId(value: String) derives JsonCodec:
  require(value.trim.nonEmpty, "WorkerId 不能为空")

/** 每次 claim 随机生成的不可猜测令牌。
  *
  * generation 防止旧轮次恢复写入，token 防止同一 generation 被其他进程伪造；数据库必须同时比较二者。
  *
  * @param value
  *   UUID 文本
  */
final case class LeaseToken(value: String) derives JsonCodec

object LeaseToken:
  /** 为一次新 claim 生成随机 token；恢复已有租约时不得重新生成。 */
  def random: UIO[LeaseToken] = Random.nextUUID.map(uuid => LeaseToken(uuid.toString))

/** Runtime 控制面的耐久命令正文。
  *
  * 命令只描述“允许 Runtime 做什么”，不直接携带数据库连接、Provider 客户端或任意可执行代码。审批命令绑定 approvalId，防止旧页面提交的决定应用到后来生成的另一条审批请求。
  */
enum RunCommandPayload derives JsonCodec:
  /** 启动已经原子写入 `AgentState(Created)` 的新 Run。
    *
    * 输入、Agent 定义和可信权限上下文只保存在 AgentState 中，命令不重复携带这些大对象，避免两份事实发生漂移。 Worker claim 后必须先加载 Created 状态，再在 lease
    * fencing 下提交 RunStarted。
    */
  case Start

  /** 根据 AgentState 与工具账本继续被崩溃、部署或租约转移打断的工作。 */
  case Recover

  /** 对指定审批请求应用一次不可变决定。 */
  case ResumeApproval(approvalId: String, decision: ApprovalDecision)

  /** 抢占当前分布式执行并把 Run 收敛到 Cancelled；reason 只允许保存安全、非敏感摘要。 */
  case Cancel(reason: Option[String])

  /** 运维人员明确要求再次恢复非终态 Run；reason 用于审计，不能作为模型指令。 */
  case Retry(reason: String)

  /** 返回稳定数据库类型名，避免依赖 Scala 枚举序列化实现细节建立索引。 */
  def commandType: String = this match
    case Start                => "Start"
    case Recover              => "Recover"
    case ResumeApproval(_, _) => "ResumeApproval"
    case Cancel(_)            => "Cancel"
    case Retry(_)             => "Retry"

/** 一条命令自身的持久化生命周期；dispatcher 的租约状态与它分开保存。 */
enum RunCommandStatus derives JsonCodec:
  /** 等待 availableAt 到达。 */
  case Queued

  /** 当前 dispatcher 租约正在执行这条命令。 */
  case Leased

  /** Runtime 与 fenced complete 均已完成。 */
  case Completed

  /** 自动尝试耗尽或遇到永久错误，等待显式人工重试。 */
  case DeadLetter

  /** 取消命令完成后，尚未执行的旧命令被明确终止。 */
  case Superseded

/** 可查询、可审计的命令记录。
  *
  * @param commandId
  *   命令唯一 ID
  * @param runId
  *   目标 Agent Run
  * @param payload
  *   类型化命令正文
  * @param idempotencyKey
  *   Run 范围内的业务幂等键；相同 key 只能绑定完全相同的 payload
  * @param status
  *   当前生命周期
  * @param priority
  *   数值越大越优先；取消命令通常使用高优先级
  * @param availableAt
  *   最早可 claim 时间
  * @param attempt
  *   当前人工重试周期内的自动 claim 次数
  * @param manualRetryCount
  *   从 DeadLetter 被人工重新排队的次数
  * @param lastFailure
  *   最近一次脱敏错误类别
  * @param createdAt
  *   首次提交时间
  * @param updatedAt
  *   最近状态变化时间
  */
final case class RunCommandRecord(
    commandId: CommandId,
    runId: RunId,
    payload: RunCommandPayload,
    idempotencyKey: String,
    status: RunCommandStatus,
    priority: Int,
    availableAt: Instant,
    attempt: Int,
    manualRetryCount: Int,
    lastFailure: Option[String],
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec:
  require(idempotencyKey.trim.nonEmpty, "命令幂等键不能为空")
  require(attempt >= 0 && manualRetryCount >= 0, "命令尝试次数不能为负数")

/** dispatcher claim 后交给 WorkerHost 与 Runtime 的完整 fencing 凭证。
  *
  * @param command
  *   本轮要执行的不可变命令快照
  * @param owner
  *   当前持有 worker
  * @param token
  *   本轮随机 token
  * @param generation
  *   每个 Run dispatcher 每次 claim 严格递增的 fencing 序号
  * @param claimedAt
  *   数据库权威 claim 时间
  * @param expiresAt
  *   当前租约到期时间
  */
final case class RunCommandLease(
    command: RunCommandRecord,
    owner: WorkerId,
    token: LeaseToken,
    generation: Long,
    claimedAt: Instant,
    expiresAt: Instant
) derives JsonCodec:
  /** 便捷返回目标 Run，避免调用方重复穿透 command。 */
  def runId: RunId = command.runId

  /** 便捷返回命令 ID，用于 SQL fencing。 */
  def commandId: CommandId = command.commandId

/** 多 worker 耐久控制命令队列 SPI。
  *
  * 实现必须使用“每 Run 一个 dispatcher”串行化命令：不同 Run 可并行，同一个 Run 的审批、恢复、取消绝不能并发推进 AgentState。所有
  * claim/heartbeat/complete/abandon/deadLetter 都必须比较 owner、token、generation、commandId 和未过期时间。
  */
trait RunCommandStore:
  /** 幂等提交命令。
    *
    * @param runId
    *   已存在的 Agent Run
    * @param payload
    *   类型化命令正文
    * @param idempotencyKey
    *   Run 范围内稳定业务键；重复正文返回原记录，不同正文返回 CommandIdempotencyConflict
    * @param priority
    *   调度优先级
    * @param availableAt
    *   最早执行时间；Instant.EPOCH 表示立即
    * @return
    *   新建或幂等复用的命令记录
    */
  def submit(
      runId: RunId,
      payload: RunCommandPayload,
      idempotencyKey: String,
      priority: Int = 0,
      availableAt: Instant = Instant.EPOCH
  ): IO[StoreError, RunCommandRecord]

  /** 原子领取一个可执行命令；没有候选时返回 None。 */
  def claim(
      owner: WorkerId,
      leaseDuration: Duration,
      maxAttempts: Int
  ): IO[StoreError, Option[RunCommandLease]]

  /** 续租当前命令并返回数据库权威的新过期时间。 */
  def heartbeat(lease: RunCommandLease, leaseDuration: Duration): IO[StoreError, RunCommandLease]

  /** fenced 完成；Cancel 完成时还必须把同 Run 其余 Queued 命令推进为 Superseded。 */
  def complete(lease: RunCommandLease): IO[StoreError, Unit]

  /** 对可重试错误 fenced 释放命令并安排下次自动尝试。 */
  def abandon(lease: RunCommandLease, retryAt: Instant, safeReason: String): IO[StoreError, Unit]

  /** 对永久错误 fenced 推入 DeadLetter，避免无意义热重试。 */
  def deadLetter(lease: RunCommandLease, safeReason: String): IO[StoreError, Unit]

  /** 只允许人工把 DeadLetter 命令重新排队，并保留 manualRetryCount 审计。 */
  def retry(commandId: CommandId, availableAt: Instant = Instant.EPOCH): IO[StoreError, RunCommandRecord]

  /** 按命令 ID 查询最新记录。 */
  def get(commandId: CommandId): IO[StoreError, RunCommandRecord]

  /** 按创建时间和命令 ID 稳定排序查询一个 Run 的全部命令。 */
  def list(runId: RunId): IO[StoreError, Chunk[RunCommandRecord]]

object RunCommandStore:
  /** 内存 dispatcher 状态；只在该 Adapter 的单个 Ref 临界区中使用。 */
  private enum DispatchStatus:
    case Idle, Queued, Leased

  /** 每个 Run 的串行化租约槽。 */
  final private case class Dispatch(status: DispatchStatus, generation: Long, lease: Option[RunCommandLease])

  /** 命令和 dispatcher 必须位于同一个 Ref 中，才能模拟 PostgreSQL 的跨表事务。 */
  final private case class MemoryState(
      commands: Map[CommandId, RunCommandRecord],
      dispatches: Map[RunId, Dispatch]
  )

  /** 单进程测试实现。
    *
    * 它使用 `Ref.Synchronized` 原子更新命令与 dispatcher，并使用 ZIO Clock/Random 支持 TestClock；生产保证仍以 PostgreSQL 事务和约束为准。
    */
  val inMemory: ULayer[RunCommandStore] = ZLayer.fromZIO {
    Ref.Synchronized.make(MemoryState(Map.empty, Map.empty)).map { state =>
      new RunCommandStore:
        def submit(
            runId: RunId,
            payload: RunCommandPayload,
            idempotencyKey: String,
            priority: Int,
            availableAt: Instant
        ): IO[StoreError, RunCommandRecord] =
          validateIdempotencyKey(idempotencyKey) *> Clock.instant.flatMap { now =>
            CommandId.random.flatMap { commandId =>
              state.modifyZIO { current =>
                current.commands.valuesIterator
                  .find(record => record.runId == runId && record.idempotencyKey == idempotencyKey) match
                  case Some(existing) if existing.payload == payload => ZIO.succeed(existing -> current)
                  case Some(_) => ZIO.fail(AgentError.CommandIdempotencyConflict(runId, idempotencyKey))
                  case None    =>
                    val effectiveAt = if availableAt == Instant.EPOCH then now else availableAt
                    val record      = RunCommandRecord(
                      commandId,
                      runId,
                      payload,
                      idempotencyKey,
                      RunCommandStatus.Queued,
                      priority,
                      effectiveAt,
                      attempt = 0,
                      manualRetryCount = 0,
                      lastFailure = None,
                      createdAt = now,
                      updatedAt = now
                    )
                    val existingDispatch =
                      current.dispatches.getOrElse(runId, Dispatch(DispatchStatus.Idle, 0L, None))
                    val (commandsAfterPreemption, dispatch) = payload match
                      case RunCommandPayload.Cancel(_) if existingDispatch.status == DispatchStatus.Leased =>
                        val requeued = existingDispatch.lease.fold(current.commands) { active =>
                          current.commands.updatedWith(active.commandId)(
                            _.map(_.copy(status = RunCommandStatus.Queued, updatedAt = now))
                          )
                        }
                        requeued -> existingDispatch.copy(status = DispatchStatus.Queued, lease = None)
                      case _ =>
                        val nextStatus =
                          if existingDispatch.status == DispatchStatus.Leased then DispatchStatus.Leased
                          else DispatchStatus.Queued
                        current.commands -> existingDispatch.copy(status = nextStatus)
                    val next = current.copy(
                      commands = commandsAfterPreemption.updated(commandId, record),
                      dispatches = current.dispatches.updated(runId, dispatch)
                    )
                    ZIO.succeed(record -> next)
              }
            }
          }

        def claim(
            owner: WorkerId,
            leaseDuration: Duration,
            maxAttempts: Int
        ): IO[StoreError, Option[RunCommandLease]] =
          validatePolicy(leaseDuration, maxAttempts) *> Clock.instant.flatMap { now =>
            LeaseToken.random.flatMap { token =>
              state.modify { current =>
                val reclaimed    = reclaimExpired(current, now)
                val exhaustedIds = reclaimed.commands.valuesIterator
                  .filter(record =>
                    record.status == RunCommandStatus.Queued && record.availableAt
                      .compareTo(now) <= 0 && record.attempt >= maxAttempts
                  )
                  .map(_.commandId)
                  .toSet
                val afterDeadLetters = reclaimed.copy(commands = reclaimed.commands.map { case (id, record) =>
                  if exhaustedIds.contains(id) then
                    id -> record.copy(
                      status = RunCommandStatus.DeadLetter,
                      lastFailure = Some("max-attempts-exceeded"),
                      updatedAt = now
                    )
                  else id -> record
                })
                val normalized = normalizeDispatches(afterDeadLetters)
                val candidate  = normalized.commands.valuesIterator
                  .filter(record =>
                    record.status == RunCommandStatus.Queued && record.availableAt.compareTo(now) <= 0
                  )
                  .filter(record =>
                    normalized.dispatches.get(record.runId).forall(_.status != DispatchStatus.Leased)
                  )
                  .toVector
                  .sortBy(record =>
                    (
                      -record.priority,
                      record.availableAt.toEpochMilli,
                      record.createdAt.toEpochMilli,
                      record.commandId.asString
                    )
                  )
                  .headOption
                candidate match
                  case None         => None -> normalized
                  case Some(record) =>
                    val dispatch =
                      normalized.dispatches.getOrElse(record.runId, Dispatch(DispatchStatus.Queued, 0L, None))
                    val claimedRecord = record.copy(
                      status = RunCommandStatus.Leased,
                      attempt = record.attempt + 1,
                      updatedAt = now
                    )
                    val lease = RunCommandLease(
                      claimedRecord,
                      owner,
                      token,
                      dispatch.generation + 1L,
                      now,
                      now.plusMillis(leaseDuration.toMillis)
                    )
                    val next = normalized.copy(
                      commands = normalized.commands.updated(record.commandId, claimedRecord),
                      dispatches = normalized.dispatches
                        .updated(record.runId, Dispatch(DispatchStatus.Leased, lease.generation, Some(lease)))
                    )
                    Some(lease) -> next
              }
            }
          }

        def heartbeat(lease: RunCommandLease, leaseDuration: Duration): IO[StoreError, RunCommandLease] =
          validateDuration(leaseDuration) *> Clock.instant.flatMap { now =>
            state.modifyZIO { current =>
              if ownsActiveLease(current, lease, now) then
                val renewed  = lease.copy(expiresAt = now.plusMillis(leaseDuration.toMillis))
                val dispatch = current.dispatches(lease.runId).copy(lease = Some(renewed))
                ZIO.succeed(
                  renewed -> current.copy(dispatches = current.dispatches.updated(lease.runId, dispatch))
                )
              else ZIO.fail(lost(lease, "heartbeat 被抢占、过期或命令已终结"))
            }
          }

        def complete(lease: RunCommandLease): IO[StoreError, Unit] = Clock.instant.flatMap { now =>
          state.modifyZIO { current =>
            if !ownsActiveLease(current, lease, now) then ZIO.fail(lost(lease, "迟到完成被 fencing 拒绝"))
            else
              val completed = current.commands.updatedWith(lease.commandId)(
                _.map(_.copy(status = RunCommandStatus.Completed, updatedAt = now))
              )
              val afterSupersede = lease.command.payload match
                case RunCommandPayload.Cancel(_) =>
                  completed.map { case (id, record) =>
                    if record.runId == lease.runId && id != lease.commandId && record.status == RunCommandStatus.Queued
                    then id -> record.copy(status = RunCommandStatus.Superseded, updatedAt = now)
                    else id -> record
                  }
                case _ => completed
              val hasQueued = afterSupersede.valuesIterator
                .exists(record => record.runId == lease.runId && record.status == RunCommandStatus.Queued)
              val dispatch = current
                .dispatches(lease.runId)
                .copy(
                  status = if hasQueued then DispatchStatus.Queued else DispatchStatus.Idle,
                  lease = None
                )
              ZIO.succeed(
                () -> current.copy(
                  commands = afterSupersede,
                  dispatches = current.dispatches.updated(lease.runId, dispatch)
                )
              )
          }
        }

        def abandon(lease: RunCommandLease, retryAt: Instant, safeReason: String): IO[StoreError, Unit] =
          release(lease, RunCommandStatus.Queued, retryAt, safeReason)

        def deadLetter(lease: RunCommandLease, safeReason: String): IO[StoreError, Unit] =
          Clock.instant.flatMap(now => release(lease, RunCommandStatus.DeadLetter, now, safeReason))

        def retry(commandId: CommandId, availableAt: Instant): IO[StoreError, RunCommandRecord] =
          Clock.instant.flatMap { now =>
            state.modifyZIO { current =>
              current.commands.get(commandId) match
                case None => ZIO.fail(AgentError.CommandNotFound(commandId))
                case Some(record) if record.status != RunCommandStatus.DeadLetter =>
                  ZIO.fail(AgentError.InvalidCommandTransition(commandId, record.status.toString, "retry"))
                case Some(record) =>
                  val effectiveAt = if availableAt == Instant.EPOCH then now else availableAt
                  val retried     = record.copy(
                    status = RunCommandStatus.Queued,
                    availableAt = effectiveAt,
                    attempt = 0,
                    manualRetryCount = record.manualRetryCount + 1,
                    lastFailure = None,
                    updatedAt = now
                  )
                  val dispatch =
                    current.dispatches.getOrElse(record.runId, Dispatch(DispatchStatus.Idle, 0L, None))
                  val awakened =
                    if dispatch.status == DispatchStatus.Leased then dispatch
                    else dispatch.copy(status = DispatchStatus.Queued)
                  val next = current.copy(
                    commands = current.commands.updated(commandId, retried),
                    dispatches = current.dispatches.updated(record.runId, awakened)
                  )
                  ZIO.succeed(retried -> next)
            }
          }

        def get(commandId: CommandId): IO[StoreError, RunCommandRecord] =
          state.get.flatMap(current =>
            ZIO.fromOption(current.commands.get(commandId)).orElseFail(AgentError.CommandNotFound(commandId))
          )

        def list(runId: RunId): IO[StoreError, Chunk[RunCommandRecord]] =
          state.get.map(current =>
            Chunk.fromIterable(
              current.commands.valuesIterator
                .filter(_.runId == runId)
                .toVector
                .sortBy(r => (r.createdAt, r.commandId.asString))
            )
          )

        /** fenced 释放的共同实现；命令与 dispatcher 在同一个 Ref 临界区内一起变化。 */
        private def release(
            lease: RunCommandLease,
            status: RunCommandStatus,
            availableAt: Instant,
            safeReason: String
        ): IO[StoreError, Unit] = Clock.instant.flatMap { now =>
          state.modifyZIO { current =>
            if !ownsActiveLease(current, lease, now) then
              ZIO.fail(lost(lease, s"${status.toString} 释放被 fencing 拒绝"))
            else
              val updated = current.commands.updatedWith(lease.commandId)(
                _.map(
                  _.copy(
                    status = status,
                    availableAt = availableAt,
                    lastFailure = Some(safeReason.take(512)),
                    updatedAt = now
                  )
                )
              )
              val hasQueued = updated.valuesIterator
                .exists(record => record.runId == lease.runId && record.status == RunCommandStatus.Queued)
              val dispatch = current
                .dispatches(lease.runId)
                .copy(
                  status = if hasQueued then DispatchStatus.Queued else DispatchStatus.Idle,
                  lease = None
                )
              ZIO.succeed(
                () -> current
                  .copy(commands = updated, dispatches = current.dispatches.updated(lease.runId, dispatch))
              )
          }
        }
    }
  }

  /** 把过期租约的当前命令恢复为 Queued；旧 lease 随 dispatcher 清空立即失效。 */
  private def reclaimExpired(current: MemoryState, now: Instant): MemoryState =
    current.dispatches.foldLeft(current) { case (acc, (runId, dispatch)) =>
      dispatch.lease match
        case Some(lease) if !lease.expiresAt.isAfter(now) =>
          val commands = acc.commands.updatedWith(lease.commandId)(
            _.map(
              _.copy(
                status = RunCommandStatus.Queued,
                lastFailure = Some("lease-expired-and-reclaimed"),
                updatedAt = now
              )
            )
          )
          acc.copy(
            commands = commands,
            dispatches =
              acc.dispatches.updated(runId, dispatch.copy(status = DispatchStatus.Queued, lease = None))
          )
        case _ => acc
    }

  /** 根据是否仍有 Queued 命令修正非 Leased dispatcher，防止 DeadLetter 后留下无效热轮询状态。 */
  private def normalizeDispatches(current: MemoryState): MemoryState =
    val normalized = current.dispatches.map { case (runId, dispatch) =>
      if dispatch.status == DispatchStatus.Leased then runId -> dispatch
      else
        val hasQueued = current.commands.valuesIterator.exists(record =>
          record.runId == runId && record.status == RunCommandStatus.Queued
        )
        runId -> dispatch.copy(
          status = if hasQueued then DispatchStatus.Queued else DispatchStatus.Idle,
          lease = None
        )
    }
    current.copy(dispatches = normalized)

  /** 同时比较 command、owner、token、generation 与过期时间。 */
  private def ownsActiveLease(current: MemoryState, lease: RunCommandLease, now: Instant): Boolean =
    current.dispatches.get(lease.runId).exists { dispatch =>
      dispatch.status == DispatchStatus.Leased && dispatch.lease.exists { active =>
        active.commandId == lease.commandId && active.owner == lease.owner && active.token == lease.token &&
        active.generation == lease.generation && active.expiresAt.isAfter(now)
      }
    }

  /** 构造统一 fencing 错误。 */
  private def lost(lease: RunCommandLease, reason: String): AgentError.LeaseLost =
    AgentError.LeaseLost(lease.runId, lease.owner.value, lease.generation, reason)

  /** 幂等键是协议字段，空白值必须在进入存储前拒绝。 */
  private def validateIdempotencyKey(value: String): IO[StoreError, Unit] =
    if value.trim.nonEmpty then ZIO.unit else ZIO.fail(AgentError.PersistenceFailure("命令幂等键不能为空"))

  /** 防止零时长租约造成 claim/heartbeat 热循环。 */
  private def validateDuration(duration: Duration): IO[StoreError, Unit] =
    if duration > Duration.Zero then ZIO.unit
    else ZIO.fail(AgentError.PersistenceFailure("leaseDuration 必须大于零"))

  /** 同时校验租约时长和自动尝试上限。 */
  private def validatePolicy(duration: Duration, maxAttempts: Int): IO[StoreError, Unit] =
    validateDuration(
      duration
    ) *> (if maxAttempts > 0 then ZIO.unit else ZIO.fail(AgentError.PersistenceFailure("maxAttempts 必须大于零")))
