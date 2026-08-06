package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{RunCommandQueueSnapshot, RunCommandRecord, RunCommandStore}
import zio.*
import zio.json.*

/** 死信命令的运维视图。
  *
  * 视图刻意不包含 `RunCommandPayload` 正文：审批决定、取消原因和重试理由属于业务事实，值班人员判断“要不要重排” 只需要命令类型、失败分类和尝试次数。需要正文时应打开对应 Run 的
  * inspection 视图，由那里再做一次授权。
  */
final case class DeadLetterCommandView(
    commandId: String,
    runId: String,
    commandType: String,
    attempt: Int,
    manualRetryCount: Int,
    lastFailure: Option[String],
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long
) derives JsonCodec

object DeadLetterCommandView:
  /** 内存与 PostgreSQL Adapter 共用的唯一投影。 */
  def from(record: RunCommandRecord): DeadLetterCommandView = DeadLetterCommandView(
    commandId = record.commandId.asString,
    runId = record.runId.asString,
    commandType = record.payload.commandType,
    attempt = record.attempt,
    manualRetryCount = record.manualRetryCount,
    lastFailure = record.lastFailure,
    createdAtEpochMilli = record.createdAt.toEpochMilli,
    updatedAtEpochMilli = record.updatedAt.toEpochMilli
  )

/** 队列快照的 wire 形态。
  *
  * `RunCommandQueueSnapshot` 使用 `Instant`，这里统一转成 epochMilli，与其它管理视图保持同一时间表示， 避免 TypeScript 客户端在同一个页面里同时解析 ISO
  * 字符串和数字时间戳。
  */
final case class QueueSnapshotView(
    capturedAtEpochMilli: Long,
    queuedCommands: Long,
    dispatchableRuns: Long,
    leasedRuns: Long,
    expiredLeases: Long,
    deadLetterCommands: Long,
    oldestDispatchableAgeMillis: Option[Long]
) derives JsonCodec

object QueueSnapshotView:
  /** 领域快照到 wire 视图的唯一投影。 */
  def from(snapshot: RunCommandQueueSnapshot): QueueSnapshotView = QueueSnapshotView(
    capturedAtEpochMilli = snapshot.capturedAt.toEpochMilli,
    queuedCommands = snapshot.queuedCommands,
    dispatchableRuns = snapshot.dispatchableRuns,
    leasedRuns = snapshot.leasedRuns,
    expiredLeases = snapshot.expiredLeases,
    deadLetterCommands = snapshot.deadLetterCommands,
    oldestDispatchableAgeMillis = snapshot.oldestDispatchableAgeMillis
  )

/** 一次死信重排的结果。 */
final case class CommandRetryResult(
    commandId: String,
    runId: String,
    status: String,
    manualRetryCount: Int
) derives JsonCodec

/** 队列运维 SPI。
  *
  * `RunCommandStore` 已经提供 `queueSnapshot` 与按 ID 重排，缺的是“列出当前所有死信”。该查询只对管理台有意义， 因此放在管理面 SPI 而不是扩展已发布的
  * `RunCommandStore` trait——给已发布 trait 增加抽象方法会让所有外部实现 无法编译。
  */
trait OpsAdminService:
  /** 读取队列聚合快照。 */
  def queueSnapshot: IO[AgentError, QueueSnapshotView]

  /** 按更新时间倒序列出等待人工处理的死信命令。 */
  def deadLetters(limit: Int): IO[AgentError, Chunk[DeadLetterCommandView]]

  /** 把一条死信命令重新排队。 */
  def retryDeadLetter(commandId: String): IO[AgentError, CommandRetryResult]

object OpsAdminService:
  /** 单页死信条数上限。 */
  val MaxDeadLetterLimit: Int = 200

  /** 基于 `RunCommandStore` 与一个死信查询函数构造服务。
    *
    * 死信查询单独作为参数传入，是因为它是唯一无法由已发布 `RunCommandStore` 契约提供的能力： PostgreSQL Adapter 用一条带索引的 SQL 实现它，内存实现则遍历自身 Ref。
    *
    * 查询直接返回视图而不是 `RunCommandRecord`，这样 SQL 实现可以只 SELECT 投影需要的列。
    * 强制先构造完整记录会把审批决定与取消原因等业务正文读进管理面进程，而视图本来就刻意不含它们。
    *
    * @param store
    *   已装配的命令队列存储
    * @param deadLetterQuery
    *   返回按更新时间倒序的死信视图；实现可假定 limit 已被收敛
    */
  def fromCommandStore(
      store: RunCommandStore,
      deadLetterQuery: Int => IO[StoreError, Chunk[DeadLetterCommandView]]
  ): OpsAdminService = new OpsAdminService:
    def queueSnapshot: IO[AgentError, QueueSnapshotView] = store.queueSnapshot.map(QueueSnapshotView.from)

    def deadLetters(limit: Int): IO[AgentError, Chunk[DeadLetterCommandView]] =
      deadLetterQuery(limit.max(1).min(MaxDeadLetterLimit))

    def retryDeadLetter(commandId: String): IO[AgentError, CommandRetryResult] =
      for
        id     <- ZIO.fromEither(CommandId.fromString(commandId)).mapError(AgentError.InvalidConfiguration(_))
        record <- store.retry(id)
      yield CommandRetryResult(
        commandId = record.commandId.asString,
        runId = record.runId.asString,
        status = record.status.toString,
        manualRetryCount = record.manualRetryCount
      )
