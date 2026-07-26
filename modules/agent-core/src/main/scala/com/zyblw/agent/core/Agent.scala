package com.zyblw.agent.core

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

final case class ApprovalRequest(
    id: String,
    runId: RunId,
    toolCall: ToolCall,
    risk: ToolRisk,
    reason: String,
    requestedAtEpochMilli: Long
) derives JsonCodec

enum RunOutcome derives JsonCodec:
  case Completed(runId: RunId, threadId: ThreadId, answer: AgentMessage, usage: TokenUsage, steps: Int)
  case Suspended(runId: RunId, threadId: ThreadId, approval: ApprovalRequest, usage: TokenUsage, steps: Int)

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
  */
final case class DurableToolPlan(id: String, batches: Chunk[DurableToolBatch], nextBatchIndex: Int = 0)
    derives JsonCodec:
  require(id.trim.nonEmpty, "工具计划 ID 不能为空")
  require(batches.nonEmpty, "工具计划至少包含一个批次")
  require(nextBatchIndex >= 0 && nextBatchIndex <= batches.length, "nextBatchIndex 超出工具计划范围")

  /** 返回当前待执行批次；全部提交完成后返回 None。 */
  def currentBatch: Option[DurableToolBatch] = batches.lift(nextBatchIndex)

  /** 推进到下一批；返回新值而不修改当前计划。 */
  def advance: DurableToolPlan = copy(nextBatchIndex = nextBatchIndex + 1)
