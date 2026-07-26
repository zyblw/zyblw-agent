package com.zyblw.agent.examples

import com.zyblw.agent.context.llm.*
import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import com.zyblw.agent.testkit.ScriptedChatModel
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 可直接运行的 Context 压缩质量评测示例。
  *
  * 与 `ContextCompressionExample` 验证 Runtime 集成不同，本示例专门展示发布门禁：
  *
  *   1. 从版本化、严格 UTF-8 的中文 JSON 数据集加载用例；
  *   2. 使用真实 `LlmContextCompressor` 执行三次重复压缩；
  *   3. 检查关键事实、来源引用、提示注入、稳定性、延迟、Token、调用次数和估算成本；
  *   4. 只打印脱敏报告，报告中没有原始消息、证据正文或 Provider 错误消息；
  *   5. 任一硬门禁失败时以失败 effect 终止，CI 可以直接据此阻止发布。
  *
  * 示例使用 `ScriptedChatModel`，不会访问公网。生产评测只需替换为 DeepSeek、GLM、OpenAI、Anthropic 或 Gemini
  * `ChatModel`，并把示例价格表替换为当前模型的带版本价格表。
  */
object ContextCompressionEvalExample extends ZIOAppDefault:
  private val resourceName = "context-compression-eval-sample.json"

  /** 模拟模型通过唯一 strict tool 提交两条逐字证据。
    *
    * sourceMessageIndex 必须与数据集消息顺序一致；引用还必须逐字存在于同一来源消息中，否则 `LlmContextCompressor` 会在本地拒绝，而不是相信模型自报成功。
    */
  private val validResponse = ChatResponse(
    AgentMessage.assistantToolCalls(
      Chunk(
        ToolCall(
          "context-eval-summary",
          "submit_context_summary",
          Json.Obj(
            "items" -> Json.Arr(
              Json.Obj(
                "kind"               -> Json.Str("constraint"),
                "sourceMessageIndex" -> Json.Num(0),
                "evidenceQuote"      -> Json.Str("必须使用中文回答"),
                "priority"           -> Json.Num(5),
                "references"         -> Json.Arr()
              ),
              Json.Obj(
                "kind"               -> Json.Str("citation"),
                "sourceMessageIndex" -> Json.Num(1),
                "evidenceQuote"      -> Json.Str("本轮可信学习资料来源是 knowledge://suwen/chapter-1。"),
                "priority"           -> Json.Num(5),
                "references"         -> Json.Arr(Json.Str("knowledge://suwen/chapter-1"))
              )
            )
          )
        )
      )
    ),
    FinishReason.ToolCalls,
    TokenUsage(100L, 50L)
  )

  /** 压缩模型配置固定 temperature=0，并禁用 repair，便于示例准确展示三次调用。 */
  private val compressorConfig = LlmContextCompressorConfig(
    modelSettings = ModelSettings(maxOutputTokens = Some(256)),
    maxSchemaRepairs = 0,
    compressorVersion = "context-eval-example-v1",
    deterministicFallbackOnValidationExhausted = false
  )

  /** 运行评测并打印低敏 JSON。
    *
    * `requests` 只用来证明重复次数确实转化为三次模型调用；请求正文不会被打印。
    */
  def run: ZIO[Any, Any, Any] =
    for
      cases <- ContextCompressionEvalDataset.loadResource(resourceName, getClass.getClassLoader)
      repetitions = cases.map(_.repetitions).sum
      model <- ScriptedChatModel.make(Chunk.fill(repetitions)(validResponse))
      compressor = LlmContextCompressor(model, compressorConfig)
      estimator  = ContextCompressionCostEstimator.fixedTokenPrice(
        currency = "USD",
        pricingVersion = "example-price-2026-07",
        inputCostPerMillionTokensMicrounits = 500000L,
        outputCostPerMillionTokensMicrounits = 1000000L
      )
      report   <- ContextCompressionEvalRunner(maxParallelism = 1, estimator).run(compressor, cases)
      requests <- model.recordedRequests
      _        <- Console.printLine(
        s"Context 压缩评测：passed=${report.passed}, passRate=${report.passRate}, " +
          s"cases=${report.reports.length}, modelCalls=${requests.length}"
      )
      _ <- Console.printLine(report.toJsonPretty)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("context-compression-eval-gate-failed"))
        .unless(report.passed)
    yield ()
