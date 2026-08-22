package com.zyblw.agent.core

import com.zyblw.agent.composition.RuntimeCompositionFingerprint
import java.time.Instant
import zio.*
import zio.json.*

/** 汇总整个 Run 的模型和工具资源消耗。 */
final case class UsageSummary(
    modelCalls: Int = 0,
    toolCalls: Int = 0,
    inputTokens: Long = 0L,
    outputTokens: Long = 0L,
    cachedInputTokens: Long = 0L,
    reasoningOutputTokens: Long = 0L,
    estimatedCost: BigDecimal = BigDecimal(0)
):
  /** 返回当前累计输入与输出 token，作为总 token 预算的比较值。 */
  def totalTokens: Long = inputTokens + outputTokens

  /** 记录一次模型调用。
    *
    * @param usage
    *   Provider 报告或框架估算的本次 token 用量
    * @param cost
    *   本次估算费用；未知时保持零，不能假装为真实账单
    * @return
    *   模型调用次数、token 和费用均已累加的新摘要
    */
  def addModel(usage: TokenUsage, cost: BigDecimal = BigDecimal(0)): UsageSummary =
    copy(
      modelCalls = modelCalls + 1,
      inputTokens = inputTokens + usage.inputTokens,
      outputTokens = outputTokens + usage.outputTokens,
      cachedInputTokens = cachedInputTokens + usage.cachedInputTokens,
      reasoningOutputTokens = reasoningOutputTokens + usage.reasoningOutputTokens,
      estimatedCost = estimatedCost + cost
    )

  /** 一次性记录若干个辅助模型调用。
    *
    * Context 压缩器可能在主模型调用之前完成一次独立模型请求。该请求同样消耗 token 和 Provider 配额，不能隐藏在 `PreparedContext` 中而绕过 Run
    * 预算。调用次数由压缩器的结构化结果给出；零表示确定性本地压缩。
    *
    * @param usage
    *   这些调用合计的 Provider usage
    * @param calls
    *   实际模型调用数；必须非负
    * @param cost
    *   已知的合计估算费用；没有价格表时保持零，不能伪造账单值
    * @return
    *   已累加模型调用次数、token 与费用的新摘要
    */
  def addModels(usage: TokenUsage, calls: Int, cost: BigDecimal = BigDecimal(0)): UsageSummary =
    require(calls >= 0, "辅助模型调用次数不能为负数")
    copy(
      modelCalls = modelCalls + calls,
      inputTokens = inputTokens + usage.inputTokens,
      outputTokens = outputTokens + usage.outputTokens,
      cachedInputTokens = cachedInputTokens + usage.cachedInputTokens,
      reasoningOutputTokens = reasoningOutputTokens + usage.reasoningOutputTokens,
      estimatedCost = estimatedCost + cost
    )

object UsageSummary:
  given JsonCodec[BigDecimal]   = RunLimits.given_JsonCodec_BigDecimal
  given JsonCodec[UsageSummary] = DeriveJsonCodec.gen[UsageSummary]

final case class BudgetState(limits: RunLimits, consumed: UsageSummary, steps: Int):
  /** 返回剩余步骤数，并通过 `max(0)` 保证展示值不会为负。 */
  def remainingSteps: Int = (limits.maxSteps - steps).max(0)

  /** 返回剩余总 token；达到或超过上限时为零。 */
  def remainingTotalTokens: Long = (limits.maxTotalTokens - consumed.totalTokens).max(0L)

object BudgetState:
  given JsonCodec[BudgetState] = DeriveJsonCodec.gen[BudgetState]

/** 已持久化的历史摘要边界。
  *
  * 摘要不是普通 metadata：它决定恢复后的下一次模型输入，因此必须与 `AgentState` 通过同一个乐观锁事务提交。 `coveredMessages` 表示摘要覆盖 `messages` 的连续前缀
  * `[0, coveredMessages)`；Runtime 只会把其后的新淘汰消息追加进下一次 压缩，避免每个回合重复调用付费模型。
  *
  * @param summary
  *   已经安全包装的摘要正文；仍可能包含业务事实，HTTP/Telemetry 不得直接投影
  * @param coveredMessages
  *   已被摘要覆盖的消息前缀长度
  * @param sourceDigest
  *   被覆盖消息按稳定渲染计算的 SHA-256，用于发现消息历史被异常改写
  * @param compressorVersion
  *   生成该摘要的压缩协议/Prompt 版本，只允许低敏稳定版本名
  */
final case class ContextSummaryCheckpoint(
    summary: String,
    coveredMessages: Int,
    sourceDigest: String,
    compressorVersion: String
) derives JsonCodec:
  require(summary.trim.nonEmpty, "Context summary 不能为空")
  require(coveredMessages > 0, "Context summary 至少覆盖一条消息")
  require(sourceDigest.matches("[0-9a-f]{64}"), "Context summary sourceDigest 必须是小写 SHA-256")
  require(
    compressorVersion.matches("[A-Za-z0-9._-]{1,100}"),
    "Context summary compressorVersion 只能包含安全版本字符"
  )

enum AgentStep derives JsonCodec:
  case ModelStep(
      index: Int,
      provider: String,
      model: String,
      usage: TokenUsage,
      finishReason: FinishReason,
      atEpochMilli: Long
  )
  case ToolStep(index: Int, call: ToolCall, result: ToolResult, atEpochMilli: Long)
  case GuardrailStep(index: Int, stage: String, allowed: Boolean, reason: Option[String], atEpochMilli: Long)
  case ApprovalStep(
      index: Int,
      request: ApprovalRequest,
      decision: Option[ApprovalDecision],
      atEpochMilli: Long
  )
  case HandoffStep(index: Int, source: AgentId, target: AgentId, depth: Int, atEpochMilli: Long)

/** Runtime 的唯一可恢复事实状态模型。
  *
  * 同步调用、SSE、HTTP 查询、审批与崩溃恢复最终都读取或推进该结构，但集群控制面不会在 HTTP 请求 Fiber 中直接修改 状态：审批、取消、恢复和显式重试先写入
  * `RunCommandStore`，WorkerHost 获得 lease 后再调用 Runtime。这样可以把 “希望执行什么”的控制意图与“已经发生什么”的 AgentState
  * 事实分开，同时仍只保留一套状态机。
  */
final case class AgentState(
    runId: RunId,
    sessionId: SessionId,
    agentId: AgentId,
    status: RunStatus,
    messages: Chunk[AgentMessage],
    steps: Chunk[AgentStep],
    usage: UsageSummary,
    budget: BudgetState,
    pendingApproval: Option[ApprovalRequest],
    createdAt: Instant,
    updatedAt: Instant,
    version: Version,
    metadata: Map[String, String] = Map.empty,
    schemaVersion: Int = AgentState.CurrentSchemaVersion,
    /** 业务会话的稳定线程 ID；Runtime 创建新状态时必须填写。 */
    threadId: Option[ThreadId] = None,
    /** 创建 Run 时使用的 Agent 定义快照，确保部署配置变化后仍可准确恢复。 */
    definition: Option[AgentDefinition] = None,
    /** 由认证业务层提供的可信用户、租户和 scope 快照。 */
    runContext: RunContext = RunContext(),
    /** 模型一次返回多个工具调用时，保存完整冲突批次和下一批游标。 */
    pendingToolPlan: Option[DurableToolPlan] = None,
    /** 连续工具失败计数，用于终止无进展循环。 */
    consecutiveToolFailures: Int = 0,
    /** 最近一次已经原子持久化的历史摘要边界。
      *
      * 该字段不能放入 `metadata`：Runtime 需要验证源前缀哈希，并在 Worker 恢复时从准确边界继续压缩。
      */
    contextSummary: Option[ContextSummaryCheckpoint] = None,
    /** 最近一次持久化领域事件的序号；新事件必须严格递增，初始值 -1 表示尚无事件。 */
    lastEventSequence: Long = -1L,
    /** 主模型 Intent 已提交、Settlement 尚未完成时的恢复游标；不含 prompt。 */
    pendingModelCall: Option[PendingModelCall] = None,
    /** 创建 Run 时冻结的运行组合指纹；缺省表示 0.6.2 之前的状态，恢复不做漂移拒绝。 */
    composition: Option[RuntimeCompositionFingerprint] = None,
    /** 上一回合 world-state section 指纹；不含正文。缺省表示尚未使用差量渲染。 */
    worldSectionCursors: Chunk[ContextSectionCursor] = Chunk.empty,
    /** 最近一次被接受的有界引用；只保留 seed 命中，上限由 reducer 截断。 */
    citations: Chunk[RunCitation] = Chunk.empty,
    retrievalEvidence: Option[RunRetrievalEvidence] = None
)

/** 上一回合 world-state section 的低敏游标。只保存身份与指纹，不保存正文。 */
final case class ContextSectionCursor(id: String, version: String, fingerprint: String) derives JsonCodec:
  require(id.trim.nonEmpty && id.length <= 64 && !id.contains('@'), "ContextSectionCursor.id 必须为 1..64 且不含 @")
  require(version.matches("[A-Za-z0-9._-]{1,32}"), "ContextSectionCursor.version 只能包含安全版本字符")
  require(fingerprint.matches("[0-9a-f]{64}"), "ContextSectionCursor.fingerprint 必须是 SHA-256 十六进制")

object AgentState:
  /** v6 把审批从 callId 升级为 [[com.zyblw.agent.composition.ApprovalSubject]]，让批准绑定具体副作用；v5 增加完整工具契约 指纹与单调审批要求；v4
    * 及更早状态只按旧恢复门禁读取。
    */
  val CurrentSchemaVersion: Int = 7

  given JsonCodec[AgentState] = DeriveJsonCodec.gen[AgentState]

enum ToolExecutionStatus derives JsonCodec:
  case Prepared, Running, Succeeded, Failed, Unknown

/** 工具执行账本可在崩溃恢复时判断是否允许重放。 */
final case class ToolExecutionRecord(
    runId: RunId,
    batchId: String,
    ordinal: Int,
    callId: String,
    toolName: String,
    idempotencyKey: Option[String],
    status: ToolExecutionStatus,
    result: Option[ToolResult],
    attempt: Int,
    updatedAtEpochMilli: Long
) derives JsonCodec:
  require(batchId.trim.nonEmpty && callId.trim.nonEmpty && toolName.trim.nonEmpty, "工具账本标识不能为空")
  require(ordinal >= 0 && attempt >= 0, "工具账本 ordinal/attempt 不能为负数")
