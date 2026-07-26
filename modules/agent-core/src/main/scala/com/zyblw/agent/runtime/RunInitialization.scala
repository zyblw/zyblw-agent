package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunStartSubmission
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 新 Run 的纯准备阶段。
  *
  * HTTP 与本地同步 Runtime 都通过这里构造同一种初始 AgentState，避免两个入口在预算、会话派生或 schemaVersion 上逐渐
  * 漂移。该对象只生成不可变值，不调用模型、不执行工具，也不写数据库；真正原子写入由 `RunSubmissionStore` 完成。
  */
object RunInitialization:
  /** 构造可由 `RunSubmissionStore` 原子提交的完整事实集合。
    *
    * @param agent
    *   本次运行冻结的 Agent 定义；后续部署修改注册表不会改变既有 Run 的恢复语义
    * @param request
    *   线程、首条消息、可信身份上下文和调用预算
    * @param idempotencyKey
    *   客户端稳定创建键；HTTP 网络重试必须复用
    * @param maxToolCalls
    *   工具治理层允许的 Run 级调用上限，用于生成统一 RunLimits
    * @return
    *   随机 runId/eventId 与确定性 sessionId、请求指纹组成的待提交值
    */
  def prepare(
      agent: AgentDefinition,
      request: RunRequest,
      idempotencyKey: String,
      maxToolCalls: Int
  ): IO[AgentError, RunStartSubmission] =
    for
      normalizedKey <- validateIdempotencyKey(idempotencyKey)
      runId         <- RunId.random
      eventId       <- EventId.random
      now           <- Clock.instant
      scopeHash     <- sha256(canonicalScope(agent.id, request.context))
      requestHash   <- requestFingerprint(agent, request)
      state = initialState(runId, agent, request, maxToolCalls, now)
      event = PersistedAgentEvent(
        eventId,
        runId,
        sequence = 0L,
        AgentEvent.RunCreated(runId, state.sessionId, now.toEpochMilli),
        now.toEpochMilli
      )
    yield RunStartSubmission(state, event, scopeHash, normalizedKey, requestHash)

  /** 创建状态为 Created、事件游标为 0 的初始快照。
    *
    * @param runId
    *   本次运行唯一 ID
    * @param agent
    *   Agent 定义快照
    * @param request
    *   用户输入和可信上下文
    * @param maxToolCalls
    *   工具调用硬上限
    * @param now
    *   数据库提交前取得的框架时间
    */
  def initialState(
      runId: RunId,
      agent: AgentDefinition,
      request: RunRequest,
      maxToolCalls: Int,
      now: java.time.Instant
  ): AgentState =
    val usage = UsageSummary()
    AgentState(
      runId = runId,
      sessionId = sessionFrom(request.threadId),
      agentId = agent.id,
      status = RunStatus.Created,
      messages = Chunk(request.input),
      steps = Chunk.empty,
      usage = usage,
      budget = BudgetState(effectiveLimits(request.limits, maxToolCalls), usage, 0),
      pendingApproval = None,
      createdAt = now,
      updatedAt = now,
      version = Version.initial,
      threadId = Some(request.threadId),
      definition = Some(agent),
      runContext = request.context,
      lastEventSequence = 0L
    )

  /** 从业务 ThreadId 确定性派生 SessionId，使跨重启会话关联不依赖进程内缓存。 */
  def sessionFrom(threadId: ThreadId): SessionId =
    SessionId(UUID.nameUUIDFromBytes(threadId.value.getBytes(StandardCharsets.UTF_8)))

  /** 将调用方预算与部署级工具治理上限合并成唯一有效预算。
    *
    * @param requested
    *   调用方为本次 Run 明确给出的完整预算
    * @param configuredMaxToolCalls
    *   部署配置允许的工具调用数上限
    * @return
    *   保留调用方所有预算维度、仅把工具调用数收紧为两者较小值的新值
    */
  def effectiveLimits(requested: RunLimits, configuredMaxToolCalls: Int): RunLimits =
    requested.copy(maxToolCalls = math.min(requested.maxToolCalls, configuredMaxToolCalls))

  /** 请求指纹不包含随机 runId、eventId 或创建时间，因此同一 HTTP 请求的重试会得到相同值。 JSON 在哈希前递归排序对象字段，避免 Scala Map 迭代顺序造成伪冲突。
    */
  private def requestFingerprint(agent: AgentDefinition, request: RunRequest): IO[AgentError, String] =
    for
      agentJson   <- decodeForFingerprint("AgentDefinition", agent.toJson)
      inputJson   <- decodeForFingerprint("AgentMessage", request.input.toJson)
      contextJson <- decodeForFingerprint("RunContext", request.context.toJson)
      limitsJson  <- decodeForFingerprint("RunLimits", request.limits.toJson)
      value = Json.Obj(
        "agent"    -> agentJson,
        "threadId" -> Json.Str(request.threadId.value),
        "input"    -> inputJson,
        "context"  -> contextJson,
        "limits"   -> limitsJson
      )
      hash <- sha256(canonicalize(value).toJson)
    yield hash

  /** 幂等作用域只使用可信身份与 Agent；原始值先长度编码再哈希，不直接写入调度表。 */
  private def canonicalScope(agentId: AgentId, context: RunContext): String =
    List(
      "tenant" -> context.tenantId.getOrElse("anonymous"),
      "user"   -> context.userId.getOrElse("anonymous"),
      "agent"  -> agentId.value
    ).map { case (name, value) => s"$name:${value.length}:$value" }.mkString("|")

  /** 解析由框架自身 JsonCodec 生成的 JSON；失败代表编解码器缺陷，转成类型化配置错误。 */
  private def decodeForFingerprint(label: String, value: String): IO[AgentError, Json] =
    ZIO
      .fromEither(value.fromJson[Json])
      .mapError(error => AgentError.InvalidConfiguration(s"计算 $label 请求指纹失败: $error"))

  /** 递归排序对象字段；数组顺序具有业务含义，因此只递归处理而不排序。 */
  private def canonicalize(value: Json): Json = value match
    case Json.Obj(fields) =>
      Json.Obj(
        Chunk.fromIterable(
          fields.toList.map { case (name, child) => name -> canonicalize(child) }.sortBy(_._1)
        )
      )
    case Json.Arr(values) => Json.Arr(values.map(canonicalize))
    case scalar           => scalar

  /** 使用 JDK SHA-256 返回固定 64 位小写十六进制；只存摘要，不保存原始提示词或身份拼接串。 */
  private def sha256(value: String): IO[AgentError, String] =
    ZIO
      .attempt {
        MessageDigest
          .getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8))
          .map(byte => f"${byte & 0xff}%02x")
          .mkString
      }
      .mapError(error => AgentError.Unexpected("无法计算 Run 幂等摘要", Some(error)))

  /** 拒绝空白、过长或含控制字符的客户端键，避免日志污染和异常索引膨胀。 */
  private def validateIdempotencyKey(value: String): IO[AgentError, String] =
    val normalized = value.trim
    if normalized.nonEmpty && normalized.length <= 200 && !normalized.exists(char =>
        java.lang.Character.isISOControl(char.toInt)
      )
    then ZIO.succeed(normalized)
    else ZIO.fail(AgentError.InvalidConfiguration("Idempotency-Key 必须为 1..200 个非控制字符"))
