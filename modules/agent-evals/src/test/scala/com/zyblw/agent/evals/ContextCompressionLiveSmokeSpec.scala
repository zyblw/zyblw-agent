package com.zyblw.agent.evals

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.json.*
import zio.test.*

/** 验证真实 Context 压缩 smoke 的能力前置门禁、低敏报告、超时和取消语义；测试不访问公网。 */
object ContextCompressionLiveSmokeSpec extends ZIOSpecDefault:
  private val compliantModel = new ChatModel:
    val provider            = "live-context-stub"
    override val descriptor = ProviderDescriptor(
      provider,
      "Live Context Stub",
      "stub-context-protocol",
      ModelCapabilities(toolCalls = true, strictToolSchema = true, specificToolChoice = true)
    )
    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      ZIO.dieMessage("本测试只验证 Runner，模型调用由 Compressor 替身承担")

  /** 只保留固定约束与引用，故意不保留注入诱饵。 */
  private val compliantCompressor = new ContextCompressor:
    override val supportsModelAssisted: Boolean = true
    def compress(
        messages: Chunk[AgentMessage],
        targetTokens: Long,
        maxModelCalls: Int
    ): IO[ContextError, ContextCompressionResult] =
      val _ = (messages, targetTokens, maxModelCalls)
      ZIO.succeed(
        ContextCompressionResult(
          AgentMessage.system("回答语言固定为中文；knowledge://zyblw-smoke/source-1"),
          TokenUsage(100L, 40L),
          modelCalls = 1,
          compressorVersion = "live-context-stub-v1"
        )
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Context compression live smoke")(
    test("工具能力通过后运行固定数据集，报告不包含输入正文或禁止诱饵") {
      for
        report <- ContextCompressionLiveSmokeRunner.run(
          compliantModel,
          compliantCompressor,
          ContextCompressionLiveSmokeConfig(model = "stub-model", repetitions = 2)
        )
        json = report.toJson
      yield assertTrue(
        report.passed,
        report.eval.exists(_.attempts.length == 2),
        report.checks.map(_.name).toSet == Set(
          "capabilities.loaded",
          "capabilities.tool-calling",
          "compression.model-assisted-configured",
          "compression.repetitions-completed",
          "compression.quality-gates"
        ),
        !json.contains("这是固定基础设施测试"),
        !json.contains("SMOKE_INJECTION_DO_NOT_RETAIN"),
        !json.contains("回答语言固定为中文")
      )
    },
    test("模型未声明工具能力时在任何压缩调用前失败") {
      for
        calls <- Ref.make(0)
        model = new ChatModel:
          val provider            = "no-tools"
          override val descriptor = ProviderDescriptor(
            provider,
            "No Tools",
            "stub",
            ModelCapabilities(toolCalls = false)
          )
          def complete(request: ChatRequest): IO[AgentError, ChatResponse] = ZIO.dieMessage("不应调用")
        compressor = new ContextCompressor:
          def compress(
              messages: Chunk[AgentMessage],
              targetTokens: Long,
              maxModelCalls: Int
          ): IO[ContextError, ContextCompressionResult] =
            calls.update(_ + 1) *> ZIO.succeed(
              ContextCompressionResult(AgentMessage.system("unexpected"))
            )
        report <- ContextCompressionLiveSmokeRunner.run(
          model,
          compressor,
          ContextCompressionLiveSmokeConfig(model = "no-tools-model", repetitions = 1)
        )
        count <- calls.get
      yield assertTrue(
        count == 0,
        !report.passed,
        report.eval.isEmpty,
        report.checks.find(_.name == "capabilities.tool-calling").exists(!_.passed)
      )
    },
    test("能力错误和压缩 defect 只形成稳定分类，不把秘密正文写入报告") {
      val capabilityFailure = new ChatModel:
        val provider = "capability-failure"
        override def capabilities(model: Option[String]): IO[AgentError, ModelCapabilities] =
          ZIO.fail(AgentError.ModelFailure(provider, "secret provider response sk-live", retryable = true))
        def complete(request: ChatRequest): IO[AgentError, ChatResponse] = ZIO.dieMessage("不应调用")

      val defectCompressor = new ContextCompressor:
        override val supportsModelAssisted: Boolean = true
        def compress(
            messages: Chunk[AgentMessage],
            targetTokens: Long,
            maxModelCalls: Int
        ): IO[ContextError, ContextCompressionResult] =
          ZIO.dieMessage("secret compressor defect")

      for
        capabilityReport <- ContextCompressionLiveSmokeRunner.run(
          capabilityFailure,
          compliantCompressor,
          ContextCompressionLiveSmokeConfig(model = "capability-model", repetitions = 1)
        )
        defectReport <- ContextCompressionLiveSmokeRunner.run(
          compliantModel,
          defectCompressor,
          ContextCompressionLiveSmokeConfig(model = "defect-model", repetitions = 1)
        )
        rendered = capabilityReport.toJson + defectReport.toJson
      yield assertTrue(
        capabilityReport.errorCategory.contains(ErrorCategory.Unavailable.toString),
        capabilityReport.retryable.contains(true),
        defectReport.errorCategory.contains(ErrorCategory.Unexpected.toString),
        !rendered.contains("secret"),
        !rendered.contains("sk-live")
      )
    },
    test("调用方取消会中断压缩 Fiber，不生成失败报告或继续重复请求") {
      for
        started   <- Promise.make[Nothing, Unit]
        cancelled <- Promise.make[Nothing, Unit]
        calls     <- Ref.make(0)
        compressor = new ContextCompressor:
          override val supportsModelAssisted: Boolean = true
          def compress(
              messages: Chunk[AgentMessage],
              targetTokens: Long,
              maxModelCalls: Int
          ): IO[ContextError, ContextCompressionResult] =
            calls.updateAndGet(_ + 1).flatMap { count =>
              ZIO
                .acquireReleaseWith(ZIO.unit)(_ => cancelled.succeed(()).unit) { _ =>
                  started.succeed(()).unit *> ZIO.never
                }
                .when(count == 1) *>
                ZIO.dieMessage("取消后不应进入下一次重复")
            }
        fiber <- ContextCompressionLiveSmokeRunner
          .run(
            compliantModel,
            compressor,
            ContextCompressionLiveSmokeConfig(
              model = "cancel-model",
              repetitions = 3,
              maxLatency = 10.minutes
            )
          )
          .fork
        _         <- started.await
        exit      <- fiber.interrupt
        released  <- cancelled.poll
        callCount <- calls.get
      yield assertTrue(exit.causeOption.exists(_.isInterrupted), released.nonEmpty, callCount == 1)
    }
  )
