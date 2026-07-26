package com.zyblw.agent.http.contract

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*
import zio.json.*
import zio.schema.*

/** zyblw-agent HTTP 公共协议的版本与固定名称。
  *
  * URL 主版本用于承载不兼容变更；`ApiVersionHeader` 让客户端和网关在诊断时确认实际响应协议。补丁/次版本只允许增加可选 字段、增加 endpoint
  * 或放宽输入，不能删除字段、改名、改变既有字段类型或收窄状态码语义。
  */
object AgentHttpProtocol:
  /** 当前公开主版本；首次正式发布前不保留无版本路径。 */
  val MajorVersion: Int = 1

  /** OpenAPI `info.version`，与 URL 主版本分开表达契约修订。 */
  val ContractVersion: String = "1.1.0"

  /** 所有 Agent API 响应都携带的低敏版本头。 */
  val ApiVersionHeader: String = "X-Zyblw-Agent-Api-Version"

  /** 版本头稳定值。 */
  val ApiVersionHeaderValue: String = MajorVersion.toString

  /** 公开 JSON API 的路径前缀，健康检查仍保留在 `/health`。 */
  val BasePath: String = s"/api/v$MajorVersion"

/** v1 wire 输入的稳定资源上限。
  *
  * 它们属于协议而不是某个 Provider：先在 HTTP 边界限制无界正文，Runtime 后续仍会按模型 Context/Token 预算进行第二次
  * 治理。修改上限时要同时评估客户端兼容、数据库索引、日志脱敏和拒绝服务风险。
  */
object AgentHttpLimits:
  /** Agent 控制面单个 JSON 请求体的最大 UTF-8 字节数。 */
  val JsonBodyBytes: Long = 256L * 1024L

  /** URL 中稳定 Agent ID 的最大 Unicode 字符数。 */
  val AgentIdChars: Int = 128

  /** 业务线程 ID 的最大 Unicode 字符数。 */
  val ThreadIdChars: Int = 256

  /** 单次用户输入的最大 Unicode 字符数；不等同于模型 token 数。 */
  val InputChars: Int = 65_536

  /** 创建请求幂等键最大字符数。 */
  val IdempotencyKeyChars: Int = 256

  /** 人工取消、拒绝和重试原因的最大字符数。 */
  val ReasonChars: Int = 2_048

  /** 显式重试业务 requestId 最大字符数。 */
  val RequestIdChars: Int = 256

/** 创建异步 Run 的稳定请求。
  *
  * @param threadId
  *   业务会话线程 ID，由客户端稳定生成；不能为空
  * @param input
  *   本轮用户输入；身份、tenant 和 scopes 不允许出现在正文
  */
final case class CreateRunRequest(threadId: String, input: String) derives JsonCodec
object CreateRunRequest:
  given Schema[CreateRunRequest] = DeriveSchema.gen[CreateRunRequest]

/** 审批命令。
  *
  * @param decision
  *   只允许 `approve` 或 `reject`，由实现层 fail-closed 校验
  * @param reason
  *   拒绝或人工决策的低敏说明；不得携带密钥和完整医疗隐私
  */
final case class ApprovalCommand(decision: String, reason: Option[String] = None) derives JsonCodec
object ApprovalCommand:
  given Schema[ApprovalCommand] = DeriveSchema.gen[ApprovalCommand]

/** @param reason 取消 Run 的可选低敏原因；身份仍从可信认证上下文取得。 */
final case class CancelCommand(reason: Option[String] = None) derives JsonCodec
object CancelCommand:
  given Schema[CancelCommand] = DeriveSchema.gen[CancelCommand]

/** 显式重试请求。
  * @param requestId
  *   本次人工操作的稳定幂等 ID，HTTP 重试必须复用
  * @param reason
  *   低敏运维原因
  */
final case class RetryRunCommand(requestId: String, reason: String) derives JsonCodec
object RetryRunCommand:
  given Schema[RetryRunCommand] = DeriveSchema.gen[RetryRunCommand]

/** 异步控制命令的 `202 Accepted` 回执。
  *
  * `Accepted` 只表示命令已耐久接收，不表示 Agent 已完成；调用方应查询 `commandId` 或 `runId`。
  *
  * @param commandId
  *   本次耐久命令的唯一 ID，用于查询排队、执行、完成或死信状态
  * @param runId
  *   命令所属 Run；创建命令第一次返回时客户端应同时保存它
  * @param commandType
  *   稳定命令类别，例如 Start、Cancel、Recover 或 Retry
  * @param status
  *   接收时状态，通常为 Queued；它不是 RunStatus
  */
final case class CommandReceipt(commandId: String, runId: String, commandType: String, status: String)
    derives JsonCodec
object CommandReceipt:
  given Schema[CommandReceipt] = DeriveSchema.gen[CommandReceipt]

/** 不暴露内部 payload、lease token、generation 或完整失败正文的命令查询视图。
  *
  * @param commandId
  *   命令唯一 ID
  * @param runId
  *   目标 Run ID
  * @param commandType
  *   稳定命令类别
  * @param status
  *   当前耐久队列状态
  * @param attempt
  *   当前人工重试周期内已经发生的自动 claim 次数
  * @param manualRetryCount
  *   从 DeadLetter 被人工重新排队的累计次数
  * @param lastFailure
  *   最近一次低敏失败摘要；不会包含 Provider 原文、用户输入或堆栈
  */
final case class CommandView(
    commandId: String,
    runId: String,
    commandType: String,
    status: String,
    attempt: Int,
    manualRetryCount: Int,
    lastFailure: Option[String]
) derives JsonCodec
object CommandView:
  given Schema[CommandView] = DeriveSchema.gen[CommandView]

/** Run 对外可展示的模型/工具累计用量；费用使用十进制字符串避免 JSON 浮点损失。
  *
  * @param modelCalls
  *   已完成的模型调用次数
  * @param toolCalls
  *   已计费/计入预算的工具调用次数
  * @param inputTokens
  *   Provider 报告的累计输入 token
  * @param outputTokens
  *   Provider 报告的累计输出 token
  * @param totalTokens
  *   输入与输出 token 之和
  * @param estimatedCost
  *   按配置价格估算的十进制费用字符串；币种由宿主定价策略约定
  */
final case class UsageView(
    modelCalls: Int,
    toolCalls: Int,
    inputTokens: Long,
    outputTokens: Long,
    totalTokens: Long,
    estimatedCost: String,
    /** inputTokens 中由 Provider 明确报告的缓存命中子集；未知时为零。 */
    cachedInputTokens: Long = 0L,
    /** outputTokens 中由 Provider 明确报告的推理 token 子集；不包含隐藏推理正文。 */
    reasoningOutputTokens: Long = 0L
) derives JsonCodec
object UsageView:
  given Schema[UsageView] = DeriveSchema.gen[UsageView]

/** 等待人工介入时公开的最小审批视图，不包含完整工具参数。
  *
  * @param approvalId
  *   恢复命令必须关联的稳定审批 ID
  * @param toolName
  *   模型提议调用的工具名称
  * @param risk
  *   工具注册时确定的风险等级，不由模型自行声明
  * @param reason
  *   经过长度限制的低敏审批原因
  * @param requestedAtEpochMilli
  *   审批请求写入耐久状态的 UTC epoch 毫秒
  */
final case class ApprovalView(
    approvalId: String,
    toolName: String,
    risk: String,
    reason: String,
    requestedAtEpochMilli: Long
) derives JsonCodec
object ApprovalView:
  given Schema[ApprovalView] = DeriveSchema.gen[ApprovalView]

/** Run 查询的版本化公共投影。
  *
  * 它故意不直接序列化框架内部的耐久状态对象：消息历史、工具参数、内部步骤、Context metadata 和恢复游标都不是公共协议。
  *
  * @param runId
  *   Run 唯一 ID
  * @param threadId
  *   业务会话线程 ID
  * @param agentId
  *   创建 Run 时冻结的 Agent ID
  * @param status
  *   当前 RunStatus 字符串
  * @param steps
  *   已消耗的 Agent loop 步数
  * @param usage
  *   当前累计用量与费用估算
  * @param output
  *   最近可公开的助手输出；尚未产生时为空
  * @param pendingApproval
  *   等待人工处理时的最小审批摘要
  * @param createdAtEpochMilli
  *   Run 创建 UTC epoch 毫秒
  * @param updatedAtEpochMilli
  *   最近一次权威状态提交 UTC epoch 毫秒
  * @param stateVersion
  *   PostgreSQL/RunStore 乐观锁版本，仅用于观察新旧，不可当作 lease token
  */
final case class RunView(
    runId: String,
    threadId: String,
    agentId: String,
    status: String,
    steps: Int,
    usage: UsageView,
    output: Option[String],
    pendingApproval: Option[ApprovalView],
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long,
    stateVersion: Long
) derives JsonCodec
object RunView:
  given Schema[RunView] = DeriveSchema.gen[RunView]

/** 公开事件中的工具进度；只提供稳定标识，不返回 arguments 或 ToolResult 正文。
  *
  * @param callId
  *   Provider 生成或框架规范化的工具调用 ID
  * @param toolName
  *   注册工具名称；纯增量事件可能尚未取得名称
  * @param batchIndex
  *   耐久并行计划中的零基批次位置
  */
final case class ToolProgressView(callId: Option[String], toolName: Option[String], batchIndex: Option[Int])
    derives JsonCodec
object ToolProgressView:
  given Schema[ToolProgressView] = DeriveSchema.gen[ToolProgressView]

/** 公开事件中的 Context 预算摘要，不包含 Memory/RAG/Prompt 正文。
  *
  * @param estimatedTokens
  *   ContextEngine 对最终请求的确定性 token 估算
  * @param droppedMessages
  *   因预算裁剪的历史消息数
  * @param truncatedToolResults
  *   被摘要或截断的工具结果数
  * @param droppedMemories
  *   未被注入的长期记忆数
  * @param droppedRetrieval
  *   未被注入的 RAG 片段数
  * @param rotSignalCodes
  *   Context Rot 检测产生的固定低基数信号码
  */
final case class ContextUsageView(
    estimatedTokens: Long,
    droppedMessages: Int,
    truncatedToolResults: Int,
    droppedMemories: Int,
    droppedRetrieval: Int,
    rotSignalCodes: List[String]
) derives JsonCodec
object ContextUsageView:
  given Schema[ContextUsageView] = DeriveSchema.gen[ContextUsageView]

/** 耐久事件的稳定公共信封。
  *
  * 新事件类型可以通过 `eventType` 增加；客户端必须忽略未知类型。可选字段允许不同事件共享一个向前兼容结构。最终回答可在 `output` 出现，但工具参数、工具结果、隐藏推理、Provider
  * 原文和内部 AgentMessage 编码不会外泄。
  *
  * @param eventId
  *   Event Store 的幂等事件 ID
  * @param runId
  *   所属 Run ID
  * @param sequence
  *   Run 内从零递增的耐久序号，也是 SSE id
  * @param eventType
  *   v1 显式映射的稳定事件名称
  * @param atEpochMilli
  *   事件发生 UTC epoch 毫秒
  * @param status
  *   与本事件有关的 RunStatus
  * @param step
  *   Agent loop 步数
  * @param output
  *   可直接展示的文本增量或最终输出
  * @param message
  *   经过安全策略与长度裁剪的可展示消息
  * @param category
  *   固定错误/决策类别或低敏摘要
  * @param stage
  *   model/tool/guardrail 等稳定阶段
  * @param stateVersion
  *   checkpoint 提交后的乐观锁版本
  * @param usage
  *   模型调用或 Run 累计用量
  * @param approval
  *   等待人工介入的摘要
  * @param tool
  *   工具执行进度摘要
  * @param context
  *   Context 预算与裁剪摘要
  */
final case class RunEventView(
    eventId: String,
    runId: String,
    sequence: Long,
    eventType: String,
    atEpochMilli: Long,
    status: Option[String] = None,
    step: Option[Int] = None,
    output: Option[String] = None,
    message: Option[String] = None,
    category: Option[String] = None,
    stage: Option[String] = None,
    stateVersion: Option[Long] = None,
    usage: Option[UsageView] = None,
    approval: Option[ApprovalView] = None,
    tool: Option[ToolProgressView] = None,
    context: Option[ContextUsageView] = None
) derives JsonCodec
object RunEventView:
  given Schema[RunEventView] = DeriveSchema.gen[RunEventView]

/** Run Inspector 中的低敏时间线条目。
  *
  * 该 DTO 不包含模型文本、工具参数、工具结果、Prompt 或失败正文；需要展示最终业务输出时使用授权后的 `RunView`。
  */
final case class RunTimelineEntryView(
    eventId: String,
    sequence: Long,
    eventType: String,
    phase: String,
    outcome: String,
    atEpochMilli: Long,
    elapsedMillis: Long,
    step: Option[Int] = None,
    toolName: Option[String] = None,
    callId: Option[String] = None,
    category: Option[String] = None,
    usage: Option[UsageView] = None
) derives JsonCodec
object RunTimelineEntryView:
  given Schema[RunTimelineEntryView] = DeriveSchema.gen[RunTimelineEntryView]

/** Inspector 发现的固定结构一致性诊断；消息不会拼接业务正文或内部异常。 */
final case class RunDiagnosticView(
    code: String,
    severity: String,
    message: String,
    sequence: Option[Long] = None
) derives JsonCodec
object RunDiagnosticView:
  given Schema[RunDiagnosticView] = DeriveSchema.gen[RunDiagnosticView]

/** Inspector 专用的无正文 Run 摘要。
  *
  * 普通 `RunView` 可以在业务授权后返回 threadId、最终答案与审批摘要；运维 Inspector 不需要这些正文，因此使用独立 DTO， 避免 Timeline 权限隐式扩大成业务内容读取权限。
  */
final case class RunInspectionSummaryView(
    runId: String,
    agentId: String,
    status: String,
    steps: Int,
    usage: UsageView,
    awaitingApproval: Boolean,
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long,
    stateVersion: Long
) derives JsonCodec
object RunInspectionSummaryView:
  given Schema[RunInspectionSummaryView] = DeriveSchema.gen[RunInspectionSummaryView]

/** 面向本地调试、运维和未来 Run Studio 的聚合读模型。
  *
  * `run` 来自权威状态；`timeline` 来自一页耐久事件；`diagnostics` 只做机械一致性检查，不重新执行模型或工具。 `hasMore=true` 时继续携带
  * `Last-Event-ID=nextCursor` 请求下一页。
  */
final case class RunInspectionView(
    run: RunInspectionSummaryView,
    instructionFingerprint: Option[String],
    timeline: List[RunTimelineEntryView],
    diagnostics: List[RunDiagnosticView],
    nextCursor: Long,
    hasMore: Boolean,
    completeHistory: Boolean,
    consistent: Boolean
) derives JsonCodec
object RunInspectionView:
  given Schema[RunInspectionView] = DeriveSchema.gen[RunInspectionView]

/** 对外错误只包含稳定分类和安全消息，不包含 SQL、堆栈、Provider 正文或内部 cause。
  *
  * @param category
  *   typed AgentError 的稳定高层分类，客户端应优先依据 HTTP status 与该值处理
  * @param message
  *   允许展示的低敏消息；不可依赖其自然语言文本做程序分支
  */
final case class ErrorResponse(category: String, message: String) derives JsonCodec
object ErrorResponse:
  given Schema[ErrorResponse] = DeriveSchema.gen[ErrorResponse]

/** 声明式 Agent HTTP v1 契约与 OpenAPI 单一事实源。
  *
  * 当前 Runtime Adapter 仍需要原始 `Request` 交给宿主身份解析器，因此 route handler 暂不全部改写为 Endpoint implementation；
  * 但所有公开路径、请求/响应 Schema 和 OpenAPI 都从这里定义，契约测试会验证实际 routes 与本清单一致。认证方案由宿主 决定，OpenAPI 不虚假声明固定 Bearer/JWT 机制。
  */
object AgentHttpContract:
  import zio.http.codec.PathCodec.string

  val createRunPattern       = Method.POST / "api" / "v1" / "agents" / string("agentId") / "runs"
  val getRunPattern          = Method.GET / "api" / "v1" / "runs" / string("runId")
  val cancelRunPattern       = Method.DELETE / "api" / "v1" / "runs" / string("runId")
  val approveRunPattern      = Method.POST / "api" / "v1" / "runs" / string("runId") / "approval"
  val recoverRunPattern      = Method.POST / "api" / "v1" / "runs" / string("runId") / "recover"
  val retryRunPattern        = Method.POST / "api" / "v1" / "runs" / string("runId") / "retry"
  val getCommandPattern      = Method.GET / "api" / "v1" / "commands" / string("commandId")
  val retryCommandPattern    = Method.POST / "api" / "v1" / "commands" / string("commandId") / "retry"
  val listRunCommandsPattern = Method.GET / "api" / "v1" / "runs" / string("runId") / "commands"
  val inspectRunPattern      = Method.GET / "api" / "v1" / "runs" / string("runId") / "inspection"
  val listRunEventsPattern   = Method.GET / "api" / "v1" / "runs" / string("runId") / "events"
  val streamRunEventsPattern = Method.GET / "api" / "v1" / "runs" / string("runId") / "events" / "stream"

  private val errorDoc = Doc.p("稳定错误分类与可安全展示的消息；不会返回内部异常或 Provider 正文。")

  val createRun = Endpoint(createRunPattern)
    .header(HttpCodec.headerAs[String]("Idempotency-Key") ?? Doc.p("客户端生成的稳定幂等键；重试必须复用，最大 256 字符。"))
    .in[CreateRunRequest](Doc.p("JSON 最大 256 KiB；线程最大 256 字符，输入最大 65,536 字符；身份由宿主认证上下文提供。"))
    .out[CommandReceipt](Status.Accepted, Doc.p("命令已耐久接收，尚未代表 Run 完成。"))
    .outError[ErrorResponse](Status.BadRequest, errorDoc)
    .tag("Runs") ?? Doc.p("异步创建 Agent Run；路径 agentId 最大 128 字符。")

  val getRun = Endpoint(getRunPattern)
    .out[RunView](Doc.p("授权后的稳定 Run 投影。"))
    .outError[ErrorResponse](Status.NotFound, errorDoc)
    .tag("Runs") ?? Doc.p("读取 Run 状态、用量、输出与待审批摘要。")

  val cancelRun = Endpoint(cancelRunPattern)
    .in[CancelCommand](Doc.p("取消原因；允许 reason 为空，存在时最大 2,048 字符。"))
    .out[CommandReceipt](Status.Accepted)
    .outError[ErrorResponse](Status.BadRequest, errorDoc)
    .tag("Runs") ?? Doc.p("耐久提交取消命令。")

  val approveRun = Endpoint(approveRunPattern)
    .in[ApprovalCommand](Doc.p("decision 为 approve/reject；可选 reason 最大 2,048 字符。"))
    .out[CommandReceipt](Status.Accepted)
    .outError[ErrorResponse](Status.BadRequest, errorDoc)
    .tag("Approvals") ?? Doc.p("批准或拒绝当前等待中的工具动作。")

  val recoverRun = Endpoint(recoverRunPattern)
    .out[CommandReceipt](Status.Accepted)
    .outError[ErrorResponse](Status.NotFound, errorDoc)
    .tag("Runs") ?? Doc.p("从最近耐久边界恢复 Run。")

  val retryRun = Endpoint(retryRunPattern)
    .in[RetryRunCommand](Doc.p("requestId 最大 256 字符，reason 最大 2,048 字符。"))
    .out[CommandReceipt](Status.Accepted)
    .outError[ErrorResponse](Status.BadRequest, errorDoc)
    .tag("Runs") ?? Doc.p("提交带业务幂等 ID 的显式重试。")

  val getCommand = Endpoint(getCommandPattern)
    .out[CommandView]
    .outError[ErrorResponse](Status.NotFound, errorDoc)
    .tag("Commands") ?? Doc.p("查询异步命令状态。")

  val retryCommand = Endpoint(retryCommandPattern)
    .out[CommandReceipt](Status.Accepted)
    .outError[ErrorResponse](Status.Conflict, errorDoc)
    .tag("Commands") ?? Doc.p("人工重试 DeadLetter 命令。")

  val listRunCommands = Endpoint(listRunCommandsPattern)
    .out[List[CommandView]]
    .outError[ErrorResponse](Status.NotFound, errorDoc)
    .tag("Commands") ?? Doc.p("列出一个 Run 的低敏命令视图。")

  val inspectRun = Endpoint(inspectRunPattern)
    .header(HttpCodec.headerAs[String]("Last-Event-ID").optional ?? Doc.p("已检查的最后 sequence；缺失表示从头读取。"))
    .out[RunInspectionView](Doc.p("权威 Run 摘要、低敏时间线、instruction fingerprint 和机械一致性诊断。"))
    .outError[ErrorResponse](Status.BadRequest, errorDoc)
    .tag("Inspection") ?? Doc.p("分页检查 Run，不返回 Prompt、工具参数/结果、健康正文或隐藏推理。")

  val listRunEvents = Endpoint(listRunEventsPattern)
    .header(HttpCodec.headerAs[String]("Last-Event-ID").optional ?? Doc.p("已确认的最后 sequence；缺失表示从头读取。"))
    .out[List[RunEventView]]
    .outError[ErrorResponse](Status.BadRequest, errorDoc)
    .tag("Events") ?? Doc.p("一次性读取耐久公共事件页。")

  val streamRunEvents = Endpoint(streamRunEventsPattern)
    .header(HttpCodec.headerAs[String]("Last-Event-ID").optional ?? Doc.p("SSE 断点续传 sequence。"))
    .outStream[ServerSentEvent[String]](MediaType.text.`event-stream`)
    .outError[ErrorResponse](Status.BadRequest, errorDoc)
    .tag("Events") ?? Doc.p("跨 Worker 可恢复的耐久 SSE；SSE data 是 RunEventView JSON。")

  /** OpenAPI 只包含稳定 Agent 控制面；Memory 用户治理仍处于独立 Beta 协议，不在 v1 稳定承诺中。 */
  val endpoints: List[Endpoint[?, ?, ?, ?, ?]] = List(
    createRun,
    getRun,
    cancelRun,
    approveRun,
    recoverRun,
    retryRun,
    getCommand,
    retryCommand,
    listRunCommands,
    inspectRun,
    listRunEvents,
    streamRunEvents
  )

  /** 由 Endpoint 单一事实源生成的 OpenAPI 3 文档。 */
  lazy val openApi: OpenAPI =
    OpenAPIGen.fromEndpoints("zyblw-agent API", AgentHttpProtocol.ContractVersion, endpoints)

  /** 用于 HTTP 响应、发布快照和契约测试的确定性 pretty JSON。 */
  lazy val openApiJson: String = openApi.toJsonPretty
