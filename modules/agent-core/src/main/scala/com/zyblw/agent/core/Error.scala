package com.zyblw.agent.core

/** 供重试、HTTP 映射和遥测聚合使用的稳定错误分类。 */
enum ErrorCategory:
  case Configuration, Authentication, Authorization, Validation, RateLimit, Timeout
  case Unavailable, ContextLimit, Safety, Persistence, Conflict, Cancelled, Unexpected

/** 所有可恢复框架错误的公共契约。Defect 不应被捕获后伪装成业务错误。
  */
sealed trait AgentError extends Throwable:
  def message: String
  def category: ErrorCategory
  def retryable: Boolean                = false
  def safeToExpose: Boolean             = false
  def diagnostic: Map[String, String]   = Map.empty
  final override def getMessage: String = message

sealed trait ModelError     extends AgentError
sealed trait ProviderError  extends AgentError
sealed trait ToolError      extends AgentError
sealed trait StoreError     extends AgentError
sealed trait ContextError   extends AgentError
sealed trait GuardrailError extends AgentError
sealed trait WorkflowError  extends AgentError
sealed trait RetrievalError extends AgentError

object AgentError:
  final case class InvalidConfiguration(message: String) extends ProviderError:
    val category              = ErrorCategory.Configuration
    override val safeToExpose = true

  final case class ModelFailure(
      provider: String,
      message: String,
      override val retryable: Boolean,
      cause: Option[Throwable] = None,
      code: Option[String] = None
  ) extends ModelError:
    val category            = if retryable then ErrorCategory.Unavailable else ErrorCategory.Validation
    override val diagnostic = Map("provider" -> provider) ++ code.map("providerCode" -> _)
    override def getCause: Throwable | Null = cause.orNull

  final case class InvalidModelResponse(message: String) extends ModelError:
    val category = ErrorCategory.Validation

  final case class ProviderNotFound(provider: String) extends ProviderError:
    val message  = s"Model provider not found: $provider"
    val category = ErrorCategory.Configuration

  final case class UnsupportedModelCapability(provider: String, capability: String, details: String)
      extends ProviderError:
    val message               = s"Provider '$provider' does not support $capability: $details"
    val category              = ErrorCategory.Validation
    override val safeToExpose = true

  /** 外部结构化协议在协商、编码或解码阶段失败。
    *
    * 该错误用于 MCP、A2A 等“不是模型正文、也不是数据库”的协议边界。把它单独建模的原因是： 调用方必须能区分远端返回了合法业务失败、远端暂时不可达，以及远端违反协议三种情况；如果全部压成
    * `Unexpected`，重试器和监控都会做出错误决策。
    *
    * @param protocol
    *   协议稳定名称，例如 `mcp`
    * @param operation
    *   失败的协议操作，例如 `initialize`、`tools/list` 或 `stdio/read`
    * @param message
    *   已脱敏的诊断摘要；不得包含访问令牌、完整请求参数、工具结果或用户正文
    * @param code
    *   远端或本地解析器给出的稳定错误码；JSON-RPC 数字码可转为字符串保存
    * @param retryable
    *   同一请求在不修改输入时是否可能通过稍后重试成功
    * @param cause
    *   仅供内部 cause chain 使用的原始异常；HTTP 层不得直接序列化它
    */
  final case class ExternalProtocolFailure(
      protocol: String,
      operation: String,
      message: String,
      code: Option[String] = None,
      override val retryable: Boolean = false,
      cause: Option[Throwable] = None
  ) extends AgentError:
    val category            = if retryable then ErrorCategory.Unavailable else ErrorCategory.Validation
    override val diagnostic =
      Map("protocol" -> protocol, "operation" -> operation) ++ code.map("protocolCode" -> _)
    override def getCause: Throwable | Null = cause.orNull

  final case class ToolNotFound(name: String) extends ToolError:
    val message               = s"Tool not found: $name"
    val category              = ErrorCategory.Validation
    override val safeToExpose = true

  final case class ToolInputInvalid(name: String, details: String) extends ToolError:
    val message               = s"Invalid input for tool '$name': $details"
    val category              = ErrorCategory.Validation
    override val safeToExpose = true

  final case class ToolExecutionFailed(name: String, details: String, override val retryable: Boolean = false)
      extends ToolError:
    val message  = s"Tool '$name' failed: $details"
    val category = if retryable then ErrorCategory.Unavailable else ErrorCategory.Validation

  final case class PermissionDenied(name: String, reason: String) extends ToolError:
    val message               = s"Tool '$name' denied: $reason"
    val category              = ErrorCategory.Authorization
    override val safeToExpose = true

  final case class GuardrailRejected(stage: String, reason: String) extends GuardrailError:
    val message               = s"Guardrail rejected at $stage: $reason"
    val category              = ErrorCategory.Safety
    override val safeToExpose = true

  final case class BudgetExceeded(kind: String, limit: Long) extends AgentError:
    val message               = s"Agent budget exceeded: $kind=$limit"
    val category              = ErrorCategory.ContextLimit
    override val safeToExpose = true

  final case class RunNotFound(runId: RunId) extends StoreError:
    val message  = s"Run not found: ${runId.asString}"
    val category = ErrorCategory.Persistence

  final case class InvalidResume(runId: RunId, details: String) extends AgentError:
    val message               = s"Cannot resume ${runId.asString}: $details"
    val category              = ErrorCategory.Conflict
    override val safeToExpose = true

  final case class OptimisticLock(expected: Version, actual: Version) extends StoreError:
    val message            = s"Optimistic lock conflict: expected=${expected.value}, actual=${actual.value}"
    val category           = ErrorCategory.Conflict
    override val retryable = true

  /** 工具执行账本的 compare-and-set 失败，说明另一个 Fiber/worker 已推进同一调用。 Runtime 应重新读取账本并决定复用结果、等待或按幂等策略恢复，不能覆盖较新的状态。
    */
  final case class ToolExecutionConflict(
      runId: RunId,
      callId: String,
      expectedStatus: String,
      expectedAttempt: Int
  ) extends StoreError:
    val message =
      s"Tool execution conflict: run=${runId.asString}, call=$callId, expected=$expectedStatus/$expectedAttempt"
    val category           = ErrorCategory.Conflict
    override val retryable = true

  /** Worker 持有的租约已经失效。
    *
    * @param runId
    *   被调度的 Agent Run
    * @param owner
    *   原租约所属 worker；用于日志和指标定位，不能据此重新授权
    * @param generation
    *   fencing generation；旧 generation 的完成或心跳必须被存储层拒绝
    * @param reason
    *   失效原因，例如超时、被其他 worker 抢占或队列任务已终结
    */
  final case class LeaseLost(runId: RunId, owner: String, generation: Long, reason: String)
      extends StoreError:
    val message  = s"Lease lost for ${runId.asString}: owner=$owner, generation=$generation, reason=$reason"
    val category = ErrorCategory.Conflict
    override val retryable  = true
    override val diagnostic = Map("owner" -> owner, "generation" -> generation.toString)

  /** 同一个 Run 被重复加入调度队列；调用方应读取既有条目，而不是创建第二份工作。 */
  final case class RunAlreadyQueued(runId: RunId) extends StoreError:
    val message               = s"Run already queued: ${runId.asString}"
    val category              = ErrorCategory.Conflict
    override val safeToExpose = true

  /** 请求查询了不存在的耐久控制命令。 */
  final case class CommandNotFound(commandId: CommandId) extends StoreError:
    val message  = s"Command not found: ${commandId.asString}"
    val category = ErrorCategory.Persistence

  /** 同一个业务幂等键已经绑定到另一份命令正文。
    *
    * 该错误不能通过覆盖旧记录解决，否则两次相反的审批决定可能共享一个幂等键并以后写覆盖前写。
    */
  final case class CommandIdempotencyConflict(runId: RunId, idempotencyKey: String) extends StoreError:
    val message               = s"Command idempotency conflict: run=${runId.asString}, key=$idempotencyKey"
    val category              = ErrorCategory.Conflict
    override val safeToExpose = true

  /** 新建 Run 的客户端幂等键已经绑定到另一份语义不同的请求。
    *
    * 与 `CommandIdempotencyConflict` 不同，此时客户端还没有拿到 runId，所以冲突必须在“认证主体 + Agent”作用域内
    * 判断。错误消息故意不返回主体哈希、原始输入或请求指纹，避免把内部鉴权和提示词内容带到 HTTP 响应。
    *
    * @param idempotencyKey
    *   客户端通过 `Idempotency-Key` 提供的稳定不透明键
    */
  final case class RunSubmissionConflict(idempotencyKey: String) extends StoreError:
    val message               = s"Run submission idempotency conflict: key=$idempotencyKey"
    val category              = ErrorCategory.Conflict
    override val safeToExpose = true

  /** 命令不处在所请求操作允许的状态，例如对非 DeadLetter 命令执行人工重试。 */
  final case class InvalidCommandTransition(commandId: CommandId, status: String, operation: String)
      extends StoreError:
    val message =
      s"Invalid command transition: command=${commandId.asString}, status=$status, operation=$operation"
    val category              = ErrorCategory.Conflict
    override val safeToExpose = true

  /** 同一业务作用域和幂等键已绑定不同请求正文，必须拒绝覆盖原操作。 */
  final case class BusinessIdempotencyConflict(operation: String, idempotencyKey: String) extends StoreError:
    val message               = s"Business idempotency conflict: operation=$operation, key=$idempotencyKey"
    val category              = ErrorCategory.Conflict
    override val safeToExpose = true

  /** outbox 事件不存在，通常表示调用方持有陈旧或伪造的事件 ID。 */
  final case class OutboxEventNotFound(eventId: String) extends StoreError:
    val message  = s"Outbox event not found: $eventId"
    val category = ErrorCategory.Persistence

  /** outbox 发布者的 token/generation 已过期或被其他 worker 抢占。 */
  final case class OutboxLeaseLost(eventId: String, owner: String, generation: Long) extends StoreError:
    val message             = s"Outbox lease lost: event=$eventId, owner=$owner, generation=$generation"
    val category            = ErrorCategory.Conflict
    override val retryable  = true
    override val diagnostic = Map("owner" -> owner, "generation" -> generation.toString)

  /** 同一 consumer/messageId 收到内容不同的消息，说明上游违反稳定消息 ID 契约。 */
  final case class InboxMessageConflict(consumer: String, messageId: String) extends StoreError:
    val message               = s"Inbox message conflict: consumer=$consumer, message=$messageId"
    val category              = ErrorCategory.Conflict
    override val safeToExpose = true

  /** 请求了不存在的补偿动作。 */
  final case class CompensationNotFound(compensationId: String) extends StoreError:
    val message  = s"Compensation not found: $compensationId"
    val category = ErrorCategory.Persistence

  /** 补偿 worker 持有的 token/generation 已经过期，旧 worker 必须停止提交任何完成、重试或死信状态。
    *
    * `retryable=true` 只表示调度器可由新的有效 lease 再次领取该计划；它不授权当前旧 lease 原地重试。
    */
  final case class CompensationLeaseLost(compensationId: String, owner: String, generation: Long)
      extends StoreError:
    val message =
      s"Compensation lease lost: compensation=$compensationId, owner=$owner, generation=$generation"
    val category            = ErrorCategory.Conflict
    override val retryable  = true
    override val diagnostic = Map("owner" -> owner, "generation" -> generation.toString)

  /** 补偿动作不在当前操作允许的状态。 */
  final case class InvalidCompensationTransition(compensationId: String, status: String, operation: String)
      extends StoreError:
    val message =
      s"Invalid compensation transition: compensation=$compensationId, status=$status, operation=$operation"
    val category              = ErrorCategory.Conflict
    override val safeToExpose = true

  final case class PersistenceFailure(message: String, cause: Option[Throwable] = None) extends StoreError:
    val category                            = ErrorCategory.Persistence
    override val retryable                  = true
    override def getCause: Throwable | Null = cause.orNull

  /** 长期记忆 compare-and-set 发现陈旧版本；调用方必须重新读取并重新执行合并策略。 */
  final case class MemoryVersionConflict(scope: String, key: String, expected: Long, actual: Long)
      extends StoreError:
    val message  = s"Memory version conflict: scope=$scope, key=$key, expected=$expected, actual=$actual"
    val category = ErrorCategory.Conflict
    override val retryable = true

  /** 记忆候选未通过确定性治理策略；reason 是稳定安全码，不包含原始对话或记忆正文。 */
  final case class MemoryPolicyRejected(key: String, reason: String) extends StoreError:
    val message               = s"Memory policy rejected: key=$key, reason=$reason"
    val category              = ErrorCategory.Validation
    override val safeToExpose = true

  /** 记忆不存在或已经过期/删除；错误不包含记忆正文。 */
  final case class MemoryNotFound(scope: String, key: String) extends StoreError:
    val message               = s"Memory not found: scope=$scope, key=$key"
    val category              = ErrorCategory.Persistence
    override val safeToExpose = true

  /** 认证主体无权读取或修改目标 MemoryScope。
    *
    * scope 只包含框架 canonical 诊断标签，不包含正文；action 是 read/manage 两个稳定值，便于 HTTP 映射为 403。
    */
  final case class MemoryAccessDenied(scope: String, action: String) extends StoreError:
    val message               = s"Memory access denied: scope=$scope, action=$action"
    val category              = ErrorCategory.Authorization
    override val safeToExpose = true

  /** 记忆提炼器失败；message 只能是安全分类，不能包含原始对话。 */
  final case class MemoryExtractionFailed(message: String, override val retryable: Boolean = false)
      extends StoreError:
    val category = if retryable then ErrorCategory.Unavailable else ErrorCategory.Validation

  /** 保留 PostgreSQL SQLSTATE 的持久化错误，便于重试器区分瞬时连接/序列化失败与约束错误。 `message` 不包含 SQL、参数或凭据，可安全进入内部日志；对外仍应映射为通用错误。
    */
  final case class DatabaseFailure(
      message: String,
      sqlState: String,
      override val retryable: Boolean,
      cause: Option[Throwable] = None
  ) extends StoreError:
    val category                            = ErrorCategory.Persistence
    override val diagnostic                 = Map("sqlState" -> sqlState)
    override def getCause: Throwable | Null = cause.orNull

  final case class ContextBuildFailed(message: String) extends ContextError:
    val category = ErrorCategory.ContextLimit

  /** 独立 Context 压缩模型或本地证据校验失败。
    *
    * 与普通 `ContextBuildFailed` 分开后，调度器可以保留 Provider 的瞬时失败语义，而不会把 schema/证据错误错误重试。 message
    * 只能使用稳定错误码，不能包含被压缩历史、工具结果、模型参数或 Provider 原始响应。
    */
  final case class ContextCompressionFailed(message: String, override val retryable: Boolean = false)
      extends ContextError:
    val category = if retryable then ErrorCategory.Unavailable else ErrorCategory.Validation

  final case class RetrievalFailed(message: String, override val retryable: Boolean = false)
      extends RetrievalError:
    val category = if retryable then ErrorCategory.Unavailable else ErrorCategory.Validation

  /** 租户在当前治理窗口内耗尽 Embedding 配额；调用方应等待窗口更新或申请显式扩容。 */
  final case class EmbeddingQuotaExceeded(metric: String, limit: Long) extends RetrievalError:
    val message               = s"Embedding quota exceeded: $metric=$limit"
    val category              = ErrorCategory.RateLimit
    override val safeToExpose = true

  final case class WorkflowFailed(node: String, message: String) extends WorkflowError:
    val category            = ErrorCategory.Unexpected
    override val diagnostic = Map("node" -> node)

  final case class Cancelled(runId: RunId) extends AgentError:
    val message               = s"Run cancelled: ${runId.asString}"
    val category              = ErrorCategory.Cancelled
    override val safeToExpose = true

  final case class Unexpected(message: String, cause: Option[Throwable] = None) extends AgentError:
    val category                            = ErrorCategory.Unexpected
    override def getCause: Throwable | Null = cause.orNull
