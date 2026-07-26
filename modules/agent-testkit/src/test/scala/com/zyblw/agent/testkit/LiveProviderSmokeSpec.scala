package com.zyblw.agent.testkit

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

/** 验证真实 Provider Smoke Runner 的门禁、低敏报告和错误收敛；测试本身不访问公网。 */
object LiveProviderSmokeSpec extends ZIOSpecDefault:

  private def response(text: String, usage: TokenUsage = TokenUsage(12, 3)): ChatResponse =
    ChatResponse(AgentMessage.assistant(text), FinishReason.Stop, usage)

  /** 同步与流式都返回标记，并声明真实 Adapter 应有的 streaming/usage 能力。 */
  private val compliant = new ChatModel:
    val provider            = "live-stub"
    override val descriptor = ProviderDescriptor(
      provider,
      "Live Stub",
      "stub-protocol",
      ModelCapabilities(streaming = true, usageReporting = true)
    )
    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      ZIO.succeed(response("ZYBLW_SMOKE_OK PRIVATE_PROVIDER_TEXT"))
    override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
      val value = response("ZYBLW_SMOKE_OK PRIVATE_STREAM_TEXT")
      ZStream(
        ModelStreamEvent.TextDelta("ZYBLW_SMOKE_OK PRIVATE_DELTA"),
        ModelStreamEvent.UsageUpdated(value.usage),
        ModelStreamEvent.Completed(value)
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Live Provider smoke")(
    test("成功路径验证 complete/stream、usage、token 与延迟，报告不保存模型正文或 prompt") {
      for
        report <- LiveProviderSmokeRunner.run(
          compliant,
          LiveProviderSmokeConfig(model = "stub-model", repetitions = 2, maxTotalTokens = 100L)
        )
        json = report.toJson
      yield assertTrue(
        report.passed,
        report.calls.length == 4,
        report.calls.count(_.kind == "complete") == 2,
        report.calls.count(_.kind == "stream") == 2,
        report.calls.forall(_.markerObserved),
        !json.contains("PRIVATE_PROVIDER_TEXT"),
        !json.contains("PRIVATE_STREAM_TEXT"),
        !json.contains("PRIVATE_DELTA"),
        !json.contains("只输出")
      )
    },
    test("标记缺失、重复 Completed、usage 为零与 token 超限分别形成失败门禁") {
      val invalid = new ChatModel:
        val provider            = "invalid-stub"
        override val descriptor = ProviderDescriptor(
          provider,
          "Invalid Stub",
          "stub",
          ModelCapabilities(streaming = true, usageReporting = true)
        )
        private val value                                                = response("wrong", TokenUsage())
        def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
          ZIO.succeed(response("wrong", TokenUsage(2, 2)))
        override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
          ZStream(ModelStreamEvent.Completed(value), ModelStreamEvent.Completed(value))
      for
        report <- LiveProviderSmokeRunner.run(
          invalid,
          LiveProviderSmokeConfig(model = "invalid-model", maxTotalTokens = 1L)
        )
        failed = report.checks.filterNot(_.passed).map(_.name).toSet
      yield assertTrue(
        !report.passed,
        failed.contains("output.marker-observed"),
        failed.contains("stream.completed-exactly-once"),
        failed.contains("usage.reported"),
        failed.contains("budget.tokens")
      )
    },
    test("Provider 错误只保留 category/retryable，原始错误正文和密钥不进入报告") {
      val failing = new ChatModel:
        val provider            = "failing-stub"
        override val descriptor = ProviderDescriptor(
          provider,
          "Failing Stub",
          "stub",
          ModelCapabilities(streaming = true, usageReporting = true)
        )
        def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
          ZIO.fail(
            AgentError.ModelFailure(provider, "secret sk-provider-key response body", retryable = true)
          )
        override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
          ZStream.fail(AgentError.ModelFailure(provider, "another secret body", retryable = true))
      for
        report <- LiveProviderSmokeRunner.run(failing, LiveProviderSmokeConfig(model = "failing-model"))
        json = report.toJson
      yield assertTrue(
        !report.passed,
        report.calls.forall(call =>
          !call.succeeded && call.errorCategory.contains(ErrorCategory.Unavailable.toString)
        ),
        report.calls.forall(_.retryable.contains(true)),
        !json.contains("secret"),
        !json.contains("sk-provider-key"),
        !json.contains("response body")
      )
    },
    test("能力查询失败时不发起计费调用，并生成可保存的失败报告") {
      for
        calls <- Ref.make(0)
        model = new ChatModel:
          val provider = "capability-failure"
          override def capabilities(model: Option[String]): IO[AgentError, ModelCapabilities] =
            ZIO.fail(AgentError.ModelFailure(provider, "secret capability response", retryable = false))
          def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
            calls.update(_ + 1) *> ZIO.succeed(response("ZYBLW_SMOKE_OK"))
        report <- LiveProviderSmokeRunner.run(model, LiveProviderSmokeConfig(model = "model"))
        count  <- calls.get
      yield assertTrue(
        count == 0,
        !report.passed,
        report.calls.map(_.kind) == Chunk("capabilities"),
        !report.toJson.contains("secret capability response")
      )
    },
    test("调用方取消时中断底层模型调用，不生成失败报告或继续发起 stream") {
      for
        completeStarted   <- Promise.make[Nothing, Unit]
        completeCancelled <- Promise.make[Nothing, Unit]
        streamCalls       <- Ref.make(0)
        model = new ChatModel:
          val provider            = "cancellation-stub"
          override val descriptor = ProviderDescriptor(
            provider,
            "Cancellation Stub",
            "stub",
            ModelCapabilities(streaming = true, usageReporting = true)
          )
          def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
            // 先注册 release finalizer，再发布“调用已开始”信号，避免全量并发测试中
            // 取消恰好落在 Promise 完成与 onInterrupt 注册之间而制造假阴性。
            ZIO.acquireReleaseWith(ZIO.unit)(_ => completeCancelled.succeed(()).unit) { _ =>
              completeStarted.succeed(()).unit *> ZIO.never
            }
          override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
            ZStream.fromZIO(streamCalls.update(_ + 1)).drain
        fiber <- LiveProviderSmokeRunner
          .run(model, LiveProviderSmokeConfig(model = "cancel-model"))
          .fork
        _           <- completeStarted.await
        exit        <- fiber.interrupt
        cancelled   <- completeCancelled.poll
        streamCount <- streamCalls.get
      yield assertTrue(
        exit.causeOption.exists(_.isInterrupted),
        cancelled.nonEmpty,
        streamCount == 0
      )
    }
  )
