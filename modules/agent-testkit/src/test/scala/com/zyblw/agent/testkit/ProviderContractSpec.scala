package com.zyblw.agent.testkit

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** ProviderContract 2.0 自身的确定性回归测试；真实 Adapter 还必须增加 HTTP stub wire test。 */
object ProviderContractSpec extends ZIOSpecDefault:
  /** 同时实现 complete/stream 的最小合规 Provider，用于隔离测试公共契约逻辑。 */
  private val compliantModel = new ChatModel:
    val provider            = "contract-stub"
    override val descriptor = ProviderDescriptor(
      provider,
      "Contract Stub",
      "stub",
      ModelCapabilities(toolCalls = true, streaming = true, usageReporting = true)
    )
    private val response = ChatResponse(AgentMessage.assistant("ok"), FinishReason.Stop, TokenUsage(4, 2))
    def complete(request: ChatRequest): IO[AgentError, ChatResponse] = ZIO.succeed(response)
    override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
      ZStream(
        ModelStreamEvent.ResponseStarted(Some("stub-request")),
        ModelStreamEvent.TextDelta("ok"),
        ModelStreamEvent.UsageUpdated(response.usage),
        ModelStreamEvent.Completed(response)
      )

  /** 请求内放入工具回填，确保契约覆盖第二轮模型调用而非只测第一次文本响应。 */
  private val request = ChatRequest(
    messages = Chunk(
      AgentMessage.user("敏感问题，不得出现在 cassette"),
      AgentMessage.assistantToolCalls(Chunk(ToolCall("call-1", "lookup", Json.Obj("q" -> Json.Str("敏感参数"))))),
      AgentMessage.tool("call-1", "lookup", ToolResult(Json.Obj("answer" -> Json.Str("敏感结果"))))
    ),
    tools = Chunk(ToolDefinition("lookup", "查询", Json.Obj("type" -> Json.Str("object")))),
    settings = ModelSettings(model = Some("secret-business-model"))
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ProviderContract 2.0")(
    test("成功、usage、工具回填与 Completed 顺序通过统一契约") {
      for
        cassette <- ProviderCassette.inMemory(ProviderCassettePolicy.Redacted)
        report   <- ProviderContract.verify(compliantModel, request, cassette)
        entries  <- cassette.entries
      yield assertTrue(
        report.passed,
        report.emittedCompleted,
        report.usageReported,
        report.toolCallsRoundTripped,
        entries.size == 2,
        entries.forall(_.model == "<redacted>"),
        entries.forall(_.toolNames.isEmpty),
        entries.forall(_.requestFingerprint.length == 64),
        !entries.toString.contains("敏感")
      )
    },
    test("故障分类和 Transport 取消传播都形成明确门禁结果") {
      for
        cancelled <- Promise.make[Nothing, Unit]
        cassette  <- ProviderCassette.inMemory(ProviderCassettePolicy.Redacted)
        failure = ProviderFailureProbe(
          "http-429",
          ErrorCategory.Unavailable,
          expectedRetryable = true,
          ZIO.fail(AgentError.ModelFailure("stub", "rate limited", retryable = true))
        )
        cancellation = ProviderCancellationProbe(
          "cancel-http",
          ZIO.never.onInterrupt(cancelled.succeed(())),
          cancelled.isDone
        )
        suite <- ProviderContract.verifySuite(
          compliantModel,
          request,
          Chunk(failure),
          Some(cancellation),
          cassette
        )
      yield assertTrue(suite.passed, suite.failures.head.passed, suite.cancellation.exists(_.passed))
    }
  )
