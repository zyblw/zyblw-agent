package com.zyblw.agent.http

import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.runtime.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*
import zio.test.*

/** 直接调用 `Routes.runZIO` 验证 HTTP 适配器，不启动端口或复制 Runtime 状态机。
  *
  * 测试的关键不是模拟模型，而是证明 HTTP 状态页与耐久事件查询使用统一 `AgentState`/`PersistedAgentEvent`，控制请求 则进入 `AgentCommandService`
  * 并返回可查询的 202 回执；两条路径都没有退回已删除的 checkpoint/trace 投影，也没有 在 HTTP Fiber 中旁路调用 Runtime 的恢复方法。
  */
object AgentHttpApiSpec extends ZIOSpecDefault:
  /** v1 是首次正式发布前确定的公共协议根路径。测试集中复用该值，既减少路径拼写噪音，也让升级到 v2 时必须显式 新建一组契约测试，而不是不知不觉覆盖已有客户端依赖的路径。
    */
  private val v1 = URL.root / "api" / "v1"

  private val runId     = RunId(UUID.randomUUID())
  private val sessionId = SessionId(UUID.randomUUID())
  private val now       = Instant.parse("2026-01-01T00:00:00Z")
  private val state     = AgentState(
    runId,
    sessionId,
    AgentId("http-test"),
    RunStatus.Running,
    Chunk(AgentMessage.user("test")),
    Chunk.empty,
    UsageSummary(inputTokens = 10L, outputTokens = 5L),
    BudgetState(RunLimits(), UsageSummary(inputTokens = 10L, outputTokens = 5L), 2),
    None,
    now,
    now,
    com.zyblw.agent.core.Version.initial,
    threadId = Some(ThreadId("http-thread")),
    lastEventSequence = 0L
  )
  private val created = PersistedAgentEvent(
    EventId(UUID.randomUUID()),
    runId,
    0L,
    AgentEvent.RunCreated(runId, sessionId, now.toEpochMilli),
    now.toEpochMilli
  )
  private val streamRunId     = RunId(UUID.randomUUID())
  private val streamSessionId = SessionId(UUID.randomUUID())
  private val streamState     = state.copy(
    runId = streamRunId,
    sessionId = streamSessionId,
    status = RunStatus.Completed,
    lastEventSequence = 1L
  )
  private val streamCreated = PersistedAgentEvent(
    EventId(UUID.randomUUID()),
    streamRunId,
    0L,
    AgentEvent.RunCreated(streamRunId, streamSessionId, now.toEpochMilli),
    now.toEpochMilli
  )
  private val streamCompleted = PersistedAgentEvent(
    EventId(UUID.randomUUID()),
    streamRunId,
    1L,
    AgentEvent.RunCompleted(streamRunId, AgentMessage.assistant("done"), UsageSummary(), now.toEpochMilli),
    now.toEpochMilli
  )
  private val securedRunId = RunId(UUID.randomUUID())
  private val securedState = state.copy(
    runId = securedRunId,
    runContext = RunContext(userId = Some("user-a"), tenantId = Some("tenant-a"))
  )
  private val commandId      = CommandId(UUID.randomUUID())
  private val startRunId     = RunId(UUID.randomUUID())
  private val startCommandId = CommandId(UUID.randomUUID())
  private val recoverCommand = RunCommandRecord(
    commandId,
    runId,
    RunCommandPayload.Recover,
    "recover:0",
    RunCommandStatus.Queued,
    0,
    now,
    0,
    0,
    None,
    now,
    now
  )
  private val startCommand = RunCommandRecord(
    startCommandId,
    startRunId,
    RunCommandPayload.Start,
    "start",
    RunCommandStatus.Queued,
    0,
    now,
    0,
    0,
    None,
    now,
    now
  )

  /** 只实现 HTTP 用例需要的结果；任何未预期调用都会以 typed error 失败，使测试保持严格。 */
  private val stubRuntime = new com.zyblw.agent.runtime.AgentRuntime:
    private def unexpected[A](name: String): IO[AgentError, A] =
      ZIO.fail(AgentError.Unexpected(s"测试不应调用 $name"))

    def run(agent: AgentDefinition, request: RunRequest): IO[AgentError, RunOutcome] = unexpected("run")
    def resume(runId: RunId, decision: ApprovalDecision): IO[AgentError, RunOutcome] = unexpected("resume")
    def recover(runId: RunId): IO[AgentError, RunOutcome]                            = unexpected("recover")
    def runEvents(agent: AgentDefinition, request: RunRequest): ZStream[Any, AgentError, AgentEvent] =
      ZStream.empty
    def resumeEvents(runId: RunId, decision: ApprovalDecision): ZStream[Any, AgentError, AgentEvent] =
      ZStream.empty
    def recoverEvents(id: RunId): ZStream[Any, AgentError, AgentEvent] =
      ZStream.succeed(AgentEvent.RunResumed(id, now.toEpochMilli))
    def cancel(runId: RunId): IO[AgentError, Unit]     = ZIO.unit
    def inspect(id: RunId): IO[AgentError, AgentState] =
      if id == runId then ZIO.succeed(state)
      else if id == streamRunId then ZIO.succeed(streamState)
      else if id == securedRunId then ZIO.succeed(securedState)
      else ZIO.fail(AgentError.RunNotFound(id))
    def persistedEvents(
        id: RunId,
        afterSequence: Long,
        limit: Int
    ): IO[AgentError, Chunk[PersistedAgentEvent]] =
      if id == runId then ZIO.succeed(Chunk(created).filter(_.sequence > afterSequence).take(limit))
      else if id == streamRunId then
        ZIO.succeed(Chunk(streamCreated, streamCompleted).filter(_.sequence > afterSequence).take(limit))
      else if id == securedRunId then ZIO.succeed(Chunk.empty)
      else ZIO.fail(AgentError.RunNotFound(id))

  private val agents = new AgentRegistry:
    def get(id: AgentId): IO[AgentError, AgentDefinition] =
      if id == AgentId("http-test") then ZIO.succeed(AgentDefinition(id, "HTTP Test", "test"))
      else ZIO.fail(AgentError.InvalidConfiguration(s"测试未注册 Agent: ${id.value}"))

  private val contexts = new AgentRequestContextResolver:
    def resolve(request: Request): IO[AgentError, RunContext] =
      ZIO.succeed(
        RunContext(
          userId = request.rawHeader("X-Test-User"),
          tenantId = request.rawHeader("X-Test-Tenant"),
          scopes =
            request.rawHeader("X-Test-Scopes").toSet.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty))
        )
      )

  /** 控制面 stub 只允许 recover 与查询，证明 HTTP 不再直接调用 Runtime.recoverEvents。 */
  private val stubCommands = new AgentCommandService:
    private def unexpected[A](name: String): IO[AgentError, A] =
      ZIO.fail(AgentError.Unexpected(s"测试不应调用 $name"))
    def submitStart(agent: AgentDefinition, request: RunRequest, idempotencyKey: String) =
      if agent.id == AgentId("http-test") && request.threadId == ThreadId(
          "new-thread"
        ) && idempotencyKey == "http-start-key"
      then ZIO.succeed(startCommand)
      else unexpected("submitStart 参数")
    def submitApproval(id: RunId, decision: ApprovalDecision, actor: RunContext) = unexpected(
      "submitApproval"
    )
    def submitCancel(id: RunId, reason: Option[String], actor: RunContext) = unexpected("submitCancel")
    def submitRecover(id: RunId, actor: RunContext)                        =
      if id == runId then ZIO.succeed(recoverCommand) else ZIO.fail(AgentError.RunNotFound(id))
    def submitRetry(id: RunId, requestId: String, reason: String, actor: RunContext) = unexpected(
      "submitRetry"
    )
    def retryDeadLetter(id: CommandId, actor: RunContext, availableAt: Instant) = unexpected(
      "retryDeadLetter"
    )
    def inspect(id: CommandId, actor: RunContext) =
      if id == commandId then ZIO.succeed(recoverCommand) else ZIO.fail(AgentError.CommandNotFound(id))
    def list(id: RunId, actor: RunContext) =
      if id == runId then ZIO.succeed(Chunk(recoverCommand)) else ZIO.fail(AgentError.RunNotFound(id))

  private val api = AgentHttpApi(
    stubRuntime,
    stubCommands,
    agents,
    contexts,
    DurableRunEventStream.make(stubRuntime, DurableRunEventStreamConfig(10.millis, batchSize = 2))
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentHttpApi")(
    test("创建端点只提交 Start 命令并立即返回 202，不调用 Runtime.run/runEvents") {
      val request = Request
        .post(
          v1 / "agents" / "http-test" / "runs",
          Body.fromString(CreateRunRequest("new-thread", "hello").toJson)
        )
        .addHeader("Idempotency-Key", "http-start-key")
      for
        response <- api.routes.runZIO(request)
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Accepted,
        body.contains(startRunId.asString),
        body.contains(startCommandId.asString),
        body.contains("Start"),
        body.contains("Queued"),
        response
          .rawHeader(AgentHttpProtocol.ApiVersionHeader)
          .contains(AgentHttpProtocol.ApiVersionHeaderValue)
      )
    },
    test("创建端点缺少 Idempotency-Key 时在入队前拒绝") {
      val request = Request.post(
        v1 / "agents" / "http-test" / "runs",
        Body.fromString(CreateRunRequest("new-thread", "hello").toJson)
      )
      for
        response <- api.routes.runZIO(request)
        body     <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, body.contains("Idempotency-Key"))
    },
    test("空白 ThreadId 和过长幂等键作为 400 返回，不会成为未捕获 Fiber defect") {
      val blankThread = Request
        .post(
          v1 / "agents" / "http-test" / "runs",
          Body.fromString(CreateRunRequest("   ", "hello").toJson)
        )
        .addHeader("Idempotency-Key", "validation-1")
      val longKey = Request
        .post(
          v1 / "agents" / "http-test" / "runs",
          Body.fromString(CreateRunRequest("new-thread", "hello").toJson)
        )
        .addHeader("Idempotency-Key", "x" * (AgentHttpLimits.IdempotencyKeyChars + 1))
      for
        blankResponse <- api.routes.runZIO(blankThread)
        blankBody     <- blankResponse.body.asString
        longResponse  <- api.routes.runZIO(longKey)
        longBody      <- longResponse.body.asString
      yield assertTrue(
        blankResponse.status == Status.BadRequest,
        blankBody.contains("threadId 不能为空"),
        longResponse.status == Status.BadRequest,
        longBody.contains("Idempotency-Key 超过最大长度")
      )
    },
    test("超大 JSON 在有界 ZStream 读取阶段拒绝，不进入 DTO 解码或命令提交") {
      val oversizedInput = "x" * (AgentHttpLimits.JsonBodyBytes.toInt + 1)
      val request        = Request
        .post(
          v1 / "agents" / "http-test" / "runs",
          Body.fromString(CreateRunRequest("new-thread", oversizedInput).toJson)
        )
        .addHeader("Idempotency-Key", "oversized-body-1")
      for
        response <- api.routes.runZIO(request)
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("JSON 请求体不能超过"),
        !body.contains(oversizedInput.take(64))
      )
    },
    test("状态与耐久事件端点直接读取统一 Runtime 契约") {
      for
        statusResponse  <- api.routes.runZIO(Request.get(v1 / "runs" / runId.asString))
        statusBody      <- statusResponse.body.asString
        eventResponse   <- api.routes.runZIO(Request.get(v1 / "runs" / runId.asString / "events"))
        eventBody       <- eventResponse.body.asString
        inspectResponse <- api.routes.runZIO(Request.get(v1 / "runs" / runId.asString / "inspection"))
        inspectBody     <- inspectResponse.body.asString
      yield assertTrue(
        statusResponse.status == Status.Ok,
        statusBody.contains("http-thread"),
        statusBody.contains("http-test"),
        statusBody.contains("15"),
        eventResponse.status == Status.Ok,
        eventBody.contains("\"eventType\":\"RunCreated\""),
        !eventBody.contains("\"event\":"),
        !eventBody.contains("PersistedAgentEvent"),
        inspectResponse.status == Status.Ok,
        inspectBody.contains("\"phase\":\"Lifecycle\""),
        inspectBody.contains("\"completeHistory\":true"),
        inspectBody.contains("\"consistent\":true"),
        !inspectBody.contains("http-thread"),
        !inspectBody.contains("\"output\":"),
        !inspectBody.contains("\"pendingApproval\":"),
        !inspectBody.contains("\"messages\":"),
        !inspectBody.contains("\"definition\":")
      )
    },
    test("SSE 使用 Last-Event-ID 从任意节点恢复耐久序号，并在终态完整发送后结束") {
      val request = Request
        .get(v1 / "runs" / streamRunId.asString / "events" / "stream")
        .addHeader("Last-Event-ID", "0")
      for
        response <- api.routes.runZIO(request)
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        response.header(Header.ContentType).exists(_.mediaType == MediaType.text.`event-stream`),
        body.contains("id:1") || body.contains("id: 1"),
        body.contains("RunCompleted"),
        !body.contains("RunCreated"),
        response.rawHeader("Cache-Control").contains("no-cache, no-transform"),
        response.rawHeader("X-Accel-Buffering").contains("no")
      )
    },
    test("SSE 在创建流前拒绝非法 Last-Event-ID") {
      val request = Request
        .get(v1 / "runs" / streamRunId.asString / "events" / "stream")
        .addHeader("Last-Event-ID", "not-a-sequence")
      for
        response <- api.routes.runZIO(request)
        body     <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, body.contains("Last-Event-ID"))
    },
    test("SSE 在创建响应前拒绝超过权威最后序号的游标") {
      val request = Request
        .get(v1 / "runs" / streamRunId.asString / "events" / "stream")
        .addHeader("Last-Event-ID", "999")
      for
        response <- api.routes.runZIO(request)
        body     <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, body.contains("超过 Run 当前最后序号"))
    },
    test("状态、JSON 事件和 SSE 都在读取正文前执行 tenant/user 归属校验") {
      val authorized = Request
        .get(v1 / "runs" / securedRunId.asString)
        .addHeader("X-Test-Tenant", "tenant-a")
        .addHeader("X-Test-User", "user-a")
      for
        deniedState   <- api.routes.runZIO(Request.get(v1 / "runs" / securedRunId.asString))
        deniedEvents  <- api.routes.runZIO(Request.get(v1 / "runs" / securedRunId.asString / "events"))
        deniedInspect <- api.routes.runZIO(Request.get(v1 / "runs" / securedRunId.asString / "inspection"))
        deniedStream  <- api.routes.runZIO(
          Request.get(v1 / "runs" / securedRunId.asString / "events" / "stream")
        )
        allowedState <- api.routes.runZIO(authorized)
      yield assertTrue(
        deniedState.status == Status.Forbidden,
        deniedEvents.status == Status.Forbidden,
        deniedInspect.status == Status.Forbidden,
        deniedStream.status == Status.Forbidden,
        allowedState.status == Status.Ok
      )
    },
    test("recover 端点只耐久提交命令并返回 202 回执") {
      for
        response <- api.routes.runZIO(Request.post(v1 / "runs" / runId.asString / "recover", Body.empty))
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Accepted,
        response.header(Header.ContentType).exists(_.mediaType == MediaType.application.json),
        body.contains(commandId.asString),
        body.contains("Recover"),
        body.contains("Queued")
      )
    },
    test("命令查询不暴露完整 payload") {
      for
        response <- api.routes.runZIO(Request.get(v1 / "commands" / commandId.asString))
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains(commandId.asString),
        body.contains("manualRetryCount"),
        !body.contains("idempotencyKey")
      )
    },
    test("OpenAPI 文档由公共契约生成；未版本化旧路径不会形成隐式兼容负担") {
      for
        specification <- api.routes.runZIO(Request.get(v1 / "openapi.json"))
        body          <- specification.body.asString
        unversioned   <- api.routes.runZIO(Request.get(URL.root / "runs" / runId.asString))
      yield assertTrue(
        specification.status == Status.Ok,
        specification.header(Header.ContentType).exists(_.mediaType == MediaType.application.json),
        specification.rawHeader("Cache-Control").contains("no-store"),
        body.contains("/api/v1/runs/{runId}"),
        unversioned.status == Status.NotFound
      )
    }
  )
