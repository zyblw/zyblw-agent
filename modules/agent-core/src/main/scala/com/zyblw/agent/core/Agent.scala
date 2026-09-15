package com.zyblw.agent.core

import com.zyblw.agent.composition.{ApprovalSubject, ToolContractFingerprint}
import zio.*
import zio.json.*

/** Agent 的声明式定义；模型、工具与 Context 策略都是冻结配置，不通过继承改变运行循环。
  *
  * @param id
  *   稳定 Agent ID
  * @param name
  *   业务展示名称
  * @param instructions
  *   最高优先级 Agent 指令
  * @param allowedTools
  *   模型可见工具白名单；空集合默认不暴露工具
  * @param modelSettings
  *   Provider/model/temperature 等模型配置
  * @param contextPolicy
  *   Context 总量、分区、工具结果和历史压缩策略；创建 Run 时随 definition 快照持久化
  * @param metadata
  *   不参与权限决策的低敏版本/展示元数据
  * @param instructionSet
  *   已校验的分层指令；None 仅用于兼容早期直接构造的定义
  */
final case class AgentDefinition(
    id: AgentId,
    name: String,
    instructions: String,
    allowedTools: Set[String] = Set.empty,
    modelSettings: ModelSettings = ModelSettings(),
    contextPolicy: ContextPolicy = ContextPolicy(),
    metadata: Map[String, String] = Map.empty,
    instructionSet: Option[InstructionSet] = None
) derives JsonCodec

/** 当前请求的用户、租户和授权 scope；由业务认证层构造，模型不能修改。 */
final case class RunContext(
    userId: Option[String] = None,
    tenantId: Option[String] = None,
    scopes: Set[String] = Set.empty,
    attributes: Map[String, String] = Map.empty
) derives JsonCodec

/** 发起一次运行所需的会话、输入、权限上下文与完整硬预算。
  *
  * `RunRequest` 直接携带 Runtime 实际执行的 `RunLimits`，不再经过精简预算模型的二次投影。这样模型调用、工具调用、 输入/输出
  * Token、费用和总时长只有一个权威定义，业务提交、幂等指纹与崩溃恢复看到的是同一份不可变配置。
  *
  * @param threadId
  *   业务会话的稳定线程 ID
  * @param input
  *   本次 Run 的首条用户消息
  * @param context
  *   由认证层构造的可信用户、租户与权限上下文
  * @param limits
  *   本次 Run 的完整硬预算；工具治理层还会把工具调用数收紧到部署级上限
  */
final case class RunRequest(
    threadId: ThreadId,
    input: AgentMessage,
    context: RunContext = RunContext(),
    limits: RunLimits = RunLimits()
)

enum ApprovalDecision derives JsonCodec:
  case Approve
  case Reject(reason: String)

/** 一次待人工决定的副作用授权请求。
  *
  * @param subject
  *   本次请求所授权的具体副作用。批准只对该主体生效：工具契约、参数、执行环境、授权上下文或审批策略任一变化，都会让这条 批准记录不再匹配后续调用。`None`
  *   表示这次请求授权的不是一个新副作用，而是"是否允许重放一个结果未知的副作用"——崩溃 恢复类暂停就属于这一类，它的重放边界由 `ToolRecoveryPolicy` 与执行账本决定，不由主体决定。
  */
final case class ApprovalRequest(
    id: String,
    runId: RunId,
    toolCall: ToolCall,
    risk: ToolRisk,
    reason: String,
    requestedAtEpochMilli: Long,
    subject: Option[ApprovalSubject] = None
) derives JsonCodec

enum RunOutcome derives JsonCodec:
  case Completed(runId: RunId, threadId: ThreadId, answer: AgentMessage, usage: TokenUsage, steps: Int)

  /** Run 停在一个等待边界上。
    *
    * 携带完整 [[SuspensionRecord]] 而不是只携带 `ApprovalRequest`：调用方需要知道自己在等什么、等到什么时候、以及到期会发生
    * 什么，才能决定是去催审批、去发信号还是单纯等时间。
    */
  case Suspended(
      runId: RunId,
      threadId: ThreadId,
      suspension: SuspensionRecord,
      usage: TokenUsage,
      steps: Int
  )

object RunOutcome:
  extension (outcome: RunOutcome.Suspended)
    /** 待人工决定的审批请求；非审批类等待为 `None`。 */
    def approval: Option[ApprovalRequest] = outcome.suspension.kind.approvalRequest

enum ToolRisk derives JsonCodec:
  case ReadOnly, UserScopedRead, DraftWrite, ApprovalWrite, AdminApproval

final case class ToolExecutionContext(
    runId: RunId,
    threadId: ThreadId,
    callId: String,
    runContext: RunContext
) derives JsonCodec

enum RunStatus derives JsonCodec:
  case Created, Running, WaitingForApproval, Suspended, Completed, Failed, Cancelled, TimedOut, BudgetExceeded

object RunStatus:
  /** 终态不会再由 Recover/Resume 推进；Harness 预算对账只能在这些状态结算。 */
  def isTerminal(status: RunStatus): Boolean = status match
    case RunStatus.Completed | RunStatus.Failed | RunStatus.Cancelled | RunStatus.TimedOut |
        RunStatus.BudgetExceeded =>
      true
    case _ => false

/** 一次模型响应中某个工具调用的确定性位置。
  *
  * @param ordinal
  *   在原始 Provider 响应中的零基序号；恢复和结果提交都以它排序
  * @param call
  *   原始、尚未信任的工具调用
  */
final case class DurableToolPlanItem(ordinal: Int, call: ToolCall) derives JsonCodec:
  require(ordinal >= 0, "工具计划 ordinal 不能为负数")

/** 一个可并行执行、可独立恢复的工具 super-step。
  *
  * @param index
  *   在整个计划中的零基批次序号
  * @param items
  *   非空工具调用；批次内部已经通过静态读写冲突规划
  */
final case class DurableToolBatch(index: Int, items: Chunk[DurableToolPlanItem]) derives JsonCodec:
  require(index >= 0, "工具批次 index 不能为负数")
  require(items.nonEmpty, "工具批次不能为空")

  /** 返回数据库工具账本使用的稳定批次 ID。 */
  def executionBatchId(planId: String): String = s"$planId:$index"

/** 保存到 AgentState 的完整工具执行计划。
  *
  * @param id
  *   本次模型响应对应的随机计划 ID
  * @param batches
  *   按 Provider 顺序形成的连续批次
  * @param nextBatchIndex
  *   下一个尚未提交到 AgentState 的批次位置
  * @param toolContractFingerprints
  *   按工具名冻结的低敏 Schema/安全元数据摘要；必须覆盖计划中的每个工具
  * @param approvalSubjects
  *   按 callId 冻结的审批主体，只包含规划时判定需要人工授权的调用。空 Map 表示"已明确冻结且本计划无需审批"，与"尚未 冻结"是两件不同的事，因此该字段不是 `Option`
  */
final case class DurableToolPlan(
    id: String,
    batches: Chunk[DurableToolBatch],
    toolContractFingerprints: Map[String, ToolContractFingerprint],
    approvalSubjects: Map[String, ApprovalSubject],
    nextBatchIndex: Int = 0
) derives JsonCodec:
  private val plannedCallIds: Set[String]   = batches.flatMap(_.items.map(_.call.id)).toSet
  private val plannedToolNames: Set[String] = batches.flatMap(_.items.map(_.call.name)).toSet

  require(id.trim.nonEmpty, "工具计划 ID 不能为空")
  require(batches.nonEmpty, "工具计划至少包含一个批次")
  require(nextBatchIndex >= 0 && nextBatchIndex <= batches.length, "nextBatchIndex 超出工具计划范围")
  // 契约指纹必须逐个冻结：漏掉任何一个都会让该工具的 Schema 或安全元数据在恢复时无法核对。
  require(toolContractFingerprints.keySet == plannedToolNames, "工具契约指纹必须覆盖且仅覆盖当前计划中的工具")
  require(approvalSubjects.keySet.subsetOf(plannedCallIds), "审批主体只能引用当前计划中的调用")
  require(
    approvalSubjects.forall { case (callId, subject) => subject.callId == callId },
    "审批主体必须与其 callId 键一致"
  )

  /** 规划时冻结为必须人工授权的调用。
    *
    * 该集合是单调的：事后放宽策略不能让已冻结的审批要求消失。
    */
  def frozenApprovalCallIds: Set[String] = approvalSubjects.keySet

  /** 返回当前待执行批次；全部提交完成后返回 None。 */
  def currentBatch: Option[DurableToolBatch] = batches.lift(nextBatchIndex)

  /** 推进到下一批；返回新值而不修改当前计划。 */
  def advance: DurableToolPlan = copy(nextBatchIndex = nextBatchIndex + 1)
