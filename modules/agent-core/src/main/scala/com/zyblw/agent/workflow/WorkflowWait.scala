package com.zyblw.agent.workflow

import com.zyblw.agent.core.RunId
import com.zyblw.agent.memory.{LeaseToken, WorkerId}
import java.time.Instant

/** Workflow 外部 signal 的稳定名称。
  *
  * 名称属于 Workflow 定义契约，不能使用用户正文、凭据或高基数字符串。外部输入必须通过 `WorkflowSignalName.fromString` 校验；源码常量可以使用
  * `WorkflowSignalName.apply`。
  */
opaque type WorkflowSignalName = String
object WorkflowSignalName:
  private val Valid = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}".r

  def fromString(value: String): Either[String, WorkflowSignalName] =
    val normalized = Option(value).fold("")(_.trim)
    Either.cond(
      Valid.matches(normalized),
      normalized,
      "WorkflowSignalName 必须是 1..160 位字母、数字、点、下划线或连字符"
    )

  def apply(value: String): WorkflowSignalName =
    fromString(value).fold(message => throw new IllegalArgumentException(message), identity)

  extension (name: WorkflowSignalName) def value: String = name

/** 外部发送方生成的 signal 幂等 ID。
  *
  * 同一个等待内重复提交相同 ID 与相同 payload 必须返回原 receipt；相同 ID 携带不同 payload 必须 fail-closed。
  */
opaque type WorkflowSignalId = String
object WorkflowSignalId:
  private val Valid = "[A-Za-z0-9][A-Za-z0-9._:-]{0,199}".r

  def fromString(value: String): Either[String, WorkflowSignalId] =
    val normalized = Option(value).fold("")(_.trim)
    Either.cond(
      Valid.matches(normalized),
      normalized,
      "WorkflowSignalId 必须是 1..200 位受限字符"
    )

  def apply(value: String): WorkflowSignalId =
    fromString(value).fold(message => throw new IllegalArgumentException(message), identity)

  extension (id: WorkflowSignalId) def value: String = id

/** 一次耐久等待的稳定身份。
  *
  * `step/nodeId` 指向产生等待结果的节点 execution；因此节点结果重放不会注册第二个逻辑等待。
  */
final case class WorkflowWaitKey(runId: RunId, step: Int, nodeId: NodeId):
  require(step >= 0, "Workflow wait step 不能为负数")

/** 等待条件。
  *
  * Signal 等待允许外部事件在 deadline 前胜出；Timer 只能由 deadline 到期决议。deadline 使用绝对时间，进程重启不会 重新开始计时。
  */
enum WorkflowWaitCondition:
  case Signal(name: WorkflowSignalName)
  case Timer

/** 节点请求 Runtime 原子注册的耐久等待。
  *
  * deadline 在公共构造边界统一收敛到毫秒精度。这样内存 Store、JSON outcome 与 PostgreSQL `TIMESTAMPTZ` 对同一个请求拥有 完全一致的幂等身份，不会因为 JVM
  * 纳秒与数据库微秒精度不同而把安全重试误判为冲突。
  */
final case class WorkflowWaitRequest private (condition: WorkflowWaitCondition, deadline: Instant)

object WorkflowWaitRequest:
  def apply(condition: WorkflowWaitCondition, deadline: Instant): WorkflowWaitRequest =
    new WorkflowWaitRequest(condition, Instant.ofEpochMilli(deadline.toEpochMilli))

/** 等待记录的权威状态。`Consumed` 表示恢复后的下一 checkpoint 已经提交，不能再次唤醒。 */
enum WorkflowWaitStatus:
  case Pending, Signaled, TimedOut, Consumed

/** signal 胜出后保存的有界结果。payload 可能包含业务数据，不能进入日志、timeline 或通用指标。 */
final case class WorkflowSignalValue(
    id: WorkflowSignalId,
    name: WorkflowSignalName,
    payload: String,
    receivedAt: Instant
)

/** 权威耐久等待记录。 */
final case class WorkflowWaitRecord(
    key: WorkflowWaitKey,
    workflowId: WorkflowId,
    definitionVersion: WorkflowVersion,
    sessionId: com.zyblw.agent.core.SessionId,
    condition: WorkflowWaitCondition,
    deadline: Instant,
    status: WorkflowWaitStatus,
    signal: Option[WorkflowSignalValue],
    createdAt: Instant,
    resolvedAt: Option[Instant],
    consumedAt: Option[Instant]
):
  require(
    status != WorkflowWaitStatus.Signaled || signal.nonEmpty,
    "Signaled wait 必须携带 signal"
  )
  require(status != WorkflowWaitStatus.TimedOut || signal.isEmpty, "TimedOut wait 不能携带 signal")
  require(
    (status == WorkflowWaitStatus.Pending) == resolvedAt.isEmpty,
    "Pending wait 不能拥有 resolvedAt，终态 wait 必须拥有 resolvedAt"
  )
  require(
    (status == WorkflowWaitStatus.Consumed) == consumedAt.nonEmpty,
    "只有 Consumed wait 可以拥有 consumedAt"
  )

/** 恢复节点可观察的低层唤醒原因。节点应把业务判断写成显式状态转换，不依赖错误字符串。 */
enum WorkflowWakeup:
  case SignalReceived(key: WorkflowWaitKey, value: WorkflowSignalValue)
  case DeadlineElapsed(key: WorkflowWaitKey, deadline: Instant)

/** signal 接收结果；所有分支都可以安全重试且不暴露 payload。 */
enum WorkflowSignalDisposition:
  case Accepted, Duplicate, Late, AlreadyResolved

final case class WorkflowSignalReceipt(
    waitKey: WorkflowWaitKey,
    signalId: WorkflowSignalId,
    disposition: WorkflowSignalDisposition,
    receivedAt: Instant
)

/** 已决议 wait 的排他恢复租约。
  *
  * Signaled/TimedOut wait 本身就是耐久 wake command；Store 在该事实行上签发 owner/token/generation，避免“先决议 wait、再写
  * command”形成双写崩溃窗口。只有持有当前未过期租约的 Worker 才能在 checkpoint 提交时消费 wait。
  */
final case class WorkflowWakeupLease(
    record: WorkflowWaitRecord,
    owner: WorkerId,
    token: LeaseToken,
    generation: Long,
    leaseExpiresAt: Instant
):
  require(
    record.status == WorkflowWaitStatus.Signaled || record.status == WorkflowWaitStatus.TimedOut,
    "Workflow wakeup lease 只能引用 Signaled/TimedOut wait"
  )
  require(generation > 0L, "Workflow wakeup generation 必须大于零")
  require(leaseExpiresAt.isAfter(record.resolvedAt.get), "Workflow wakeup lease 必须晚于 wait 决议时间")

  def key: WorkflowWaitKey = record.key

/** checkpoint commit 同时需要完成的 wait 状态转换。
  *
  * 一个恢复节点可以在同一事务消费旧等待并注册新等待，从而支持“提醒后继续等”而不产生 crash gap。
  */
final case class WorkflowWaitCommit(
    consume: Option[WorkflowWakeupLease],
    register: Option[(WorkflowExecutionKey, WorkflowWaitRequest)]
)

object WorkflowWaitCommit:
  val empty: WorkflowWaitCommit = WorkflowWaitCommit(None, None)

/** 将权威等待记录转换为节点可见唤醒原因。Pending/Consumed 都不能驱动恢复。 */
object WorkflowWakeup:
  def fromRecord(record: WorkflowWaitRecord): Option[WorkflowWakeup] = record.status match
    case WorkflowWaitStatus.Signaled =>
      record.signal.map(value => WorkflowWakeup.SignalReceived(record.key, value))
    case WorkflowWaitStatus.TimedOut => Some(WorkflowWakeup.DeadlineElapsed(record.key, record.deadline))
    case WorkflowWaitStatus.Pending | WorkflowWaitStatus.Consumed => None
