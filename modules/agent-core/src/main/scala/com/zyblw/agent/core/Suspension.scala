package com.zyblw.agent.core

import java.time.Instant
import zio.json.JsonCodec

/** 一个 Run 为什么停下来等外部输入。
  *
  * 四个分支的共同点是"必须有人或有事从外部推一把才能继续"，区别在于**推的人是谁、以及推不动时怎么办**。把它们统一成一个 ADT
  * 而不是四个布尔标记，是为了让状态机只有一个"等待原因"字段：两个并存的等待原因意味着两个互相矛盾的唤醒协议。
  *
  * 这里不合并 Agent Run 与 Workflow wait 两套 Store——它们的事务边界必须保持独立。统一的只有值类型与过期语义。
  */
enum Suspension derives JsonCodec:
  /** 等一次人工副作用授权。批准只对 `request.subject` 描述的那一件事生效。 */
  case Approval(request: ApprovalRequest)

  /** 等一次结构化人工输入（不是授权，而是补数据）。 */
  case HumanInput(taskType: String, prompt: String)

  /** 等一个外部信号；`name` 与 Workflow 侧的 signal 名同一形状。 */
  case ExternalSignal(name: String)

  /** 只等时间。用于"到点再继续"的主动退让，没有外部推送方。 */
  case Timer

object Suspension:
  extension (suspension: Suspension)
    /** 该等待原因对应的 Run 状态。
      *
      * `Approval` 单独映射到 `WaitingForApproval` 而不是 `Suspended`：审批是**唯一**能被 `resume(decision)` 推进的等待，控制面与 HTTP
      * 需要在不解包 payload 的情况下区分"等人批"和"等信号/等时间"。
      */
    def runStatus: RunStatus = suspension match
      case Suspension.Approval(_) => RunStatus.WaitingForApproval
      case _                      => RunStatus.Suspended

    /** 稳定的低敏 kind 编码，可作为 metric 维度、事件字段与数据库列值。不含 payload。 */
    def kind: String = suspension match
      case Suspension.Approval(_)       => "approval"
      case Suspension.HumanInput(_, _)  => "human-input"
      case Suspension.ExternalSignal(_) => "external-signal"
      case Suspension.Timer             => "timer"

    /** 供事件与日志使用的低敏原因。审批分支刻意只给出工具名与风险等级，不带 `reason` 正文——正文可能含模型生成的说明。 */
    def safeReason: String = suspension match
      case Suspension.Approval(request)       => s"等待审批: ${request.toolCall.name} (${request.risk})"
      case Suspension.HumanInput(taskType, _) => s"等待人工输入: $taskType"
      case Suspension.ExternalSignal(name)    => s"等待外部信号: $name"
      case Suspension.Timer                   => "等待计时器到期"

    /** 审批请求；非审批分支为 `None`。 */
    def approvalRequest: Option[ApprovalRequest] = suspension match
      case Suspension.Approval(request) => Some(request)
      case _                            => None

/** 到期而仍未被推进时，该 Run 应当走向哪里。
  *
  * 不提供"继续挂着"这个选项：一个既有 deadline 又允许无限期挂着的等待，等于没有 deadline。
  */
enum SuspensionExpiry derives JsonCodec:
  /** 到期即失败。默认选择：未获批的副作用授权过期后自动放行是权限提升。 */
  case FailRun

  /** 到期按预设默认值继续。只适用于"补数据"类等待，且默认值必须在挂起时就已确定。 */
  case ResumeWithDefault

/** 一次持久化的挂起事实。
  *
  * @param deadline
  *   绝对到期时刻。`None` 表示无限期等待——这是一个**显式选择**而不是默认值：调用方必须自己写下 `None` 才能得到一个永不过期的
  *   挂起。之前审批完全没有过期语义，进程退出后不再有任何计时器，一次审批可以无声地挂到永远。
  * @param expiryOutcome
  *   到期决议。与 `deadline` 一同 require：有 deadline 才谈得上到期动作
  */
final case class SuspensionRecord(
    kind: Suspension,
    createdAt: Instant,
    deadline: Option[Instant] = None,
    expiryOutcome: SuspensionExpiry = SuspensionExpiry.FailRun
) derives JsonCodec:
  require(
    deadline.forall(_.isAfter(createdAt)),
    "SuspensionRecord.deadline 必须晚于 createdAt"
  )
  require(
    deadline.nonEmpty || expiryOutcome == SuspensionExpiry.FailRun,
    "没有 deadline 时不能声明非默认的到期决议"
  )

  /** 该挂起对应的 Run 状态。 */
  def runStatus: RunStatus = kind.runStatus

  /** 是否在给定时刻已经过期。无 deadline 永不过期。 */
  def expiredAt(now: Instant): Boolean = deadline.exists(!_.isAfter(now))

object SuspensionRecord:
  /** 审批挂起。`ttl` 为 `None` 时保持无限期等待，与旧行为一致。 */
  def approval(
      request: ApprovalRequest,
      createdAt: Instant,
      deadline: Option[Instant] = None
  ): SuspensionRecord =
    SuspensionRecord(Suspension.Approval(request), createdAt, deadline, SuspensionExpiry.FailRun)

  /** 从已有审批请求重建挂起记录；创建时刻取请求上的时间戳。 */
  def of(request: ApprovalRequest): SuspensionRecord =
    approval(request, Instant.ofEpochMilli(request.requestedAtEpochMilli))
