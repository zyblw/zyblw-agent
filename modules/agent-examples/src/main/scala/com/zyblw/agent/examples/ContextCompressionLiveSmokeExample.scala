package com.zyblw.agent.examples

import com.zyblw.agent.context.llm.*
import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import java.io.IOException
import zio.*
import zio.http.*
import zio.json.*

/** 使用真实 Provider 运行 Context 压缩工具协议和质量门禁。
  *
  * 支持与 `ProviderSmokeExample` 相同的 DeepSeek、GLM、OpenAI Chat/Responses、Anthropic 和 Gemini 目标。输入完全是固定 假数据；报告不包含
  * Prompt、Provider 响应、摘要正文、API Key 或 endpoint。
  *
  * 本入口刻意关闭 `deterministicFallbackOnValidationExhausted`：真实 Provider 若不遵守 strict tool、逐字证据或引用协议， smoke
  * 必须失败，不能用本地确定性摘要制造假绿。
  */
object ContextCompressionLiveSmokeExample extends ZIOAppDefault:
  /** 可选价格配置；五个变量必须全部出现，避免币种、价格版本或预算缺失。 */
  private val pricingVariables = Chunk(
    "ZYBLW_CONTEXT_SMOKE_PRICING_CURRENCY",
    "ZYBLW_CONTEXT_SMOKE_PRICING_VERSION",
    "ZYBLW_CONTEXT_SMOKE_INPUT_COST_PER_MILLION_MICROUNITS",
    "ZYBLW_CONTEXT_SMOKE_OUTPUT_COST_PER_MILLION_MICROUNITS",
    "ZYBLW_CONTEXT_SMOKE_MAX_COST_MICROUNITS"
  )

  /** 价格估算器和对应的单次成本门禁。 */
  final private case class Pricing(
      estimator: ContextCompressionCostEstimator,
      maxCostMicrounits: Option[Long]
  )

  /** 共享一个 scoped ZIO HTTP Client；进程结束会关闭连接池和所有未完成请求。 */
  val run: ZIO[Any, Any, Any] = program.provide(Client.default)

  private val program: ZIO[Client, AgentError | IOException, Unit] =
    for
      provider <- ProviderSmokeExample.required("ZYBLW_SMOKE_PROVIDER").map(_.trim.toLowerCase)
      client   <- ZIO.service[Client]
      target   <- ProviderSmokeExample.loadTarget(provider, client)
      config   <- loadConfig(target.modelId)
      pricing  <- loadPricing
      compressor = LlmContextCompressor(
        target.model,
        LlmContextCompressorConfig(
          modelSettings = ModelSettings(
            provider = Some(target.model.provider),
            model = Some(target.modelId),
            maxOutputTokens = Some(config.targetTokens.min(Int.MaxValue.toLong).toInt)
          ),
          maxMessages = 8,
          maxInputCodePoints = 10_000,
          maxItems = 8,
          maxEvidenceQuoteCodePoints = 500,
          maxReferencesPerItem = 4,
          maxArgumentsCharacters = 10_000,
          requestTimeout = config.maxLatency,
          maxSchemaRepairs = config.maxModelCallsPerAttempt - 1,
          compressorVersion = "context-live-smoke-v1",
          allowStandaloneToolOutput = false,
          deterministicFallbackOnValidationExhausted = false
        )
      )
      report <- ContextCompressionLiveSmokeRunner.run(
        target.model,
        compressor,
        config.copy(maxEstimatedCostMicrounits = pricing.maxCostMicrounits),
        pricing.estimator
      )
      _ <- Console.printLine(report.toJson)
      _ <- ZIO
        .fail(
          AgentError.InvalidConfiguration(
            s"Context compression live smoke failed: provider=${report.provider}, model=${report.model}"
          )
        )
        .unless(report.passed)
    yield ()

  /** 从低敏有界环境变量加载重复、超时、Token、调用次数和稳定性门禁。 */
  private def loadConfig(model: String): IO[AgentError, ContextCompressionLiveSmokeConfig] =
    for
      repetitions  <- ProviderSmokeExample.optionalInt("ZYBLW_CONTEXT_SMOKE_REPETITIONS", 3, 1, 5)
      targetTokens <- ProviderSmokeExample.optionalLong("ZYBLW_CONTEXT_SMOKE_TARGET_TOKENS", 256L, 1L, 4096L)
      maxCalls     <- ProviderSmokeExample.optionalInt("ZYBLW_CONTEXT_SMOKE_MAX_MODEL_CALLS", 2, 1, 4)
      maxLatencyMillis <- ProviderSmokeExample.optionalLong(
        "ZYBLW_CONTEXT_SMOKE_MAX_LATENCY_MILLIS",
        60_000L,
        1_000L,
        600_000L
      )
      maxInputTokens <- ProviderSmokeExample.optionalLong(
        "ZYBLW_CONTEXT_SMOKE_MAX_INPUT_TOKENS",
        4_000L,
        1L,
        1_000_000L
      )
      maxOutputTokens <- ProviderSmokeExample.optionalLong(
        "ZYBLW_CONTEXT_SMOKE_MAX_OUTPUT_TOKENS",
        800L,
        1L,
        100_000L
      )
      maxSummaryCodePoints <- ProviderSmokeExample.optionalInt(
        "ZYBLW_CONTEXT_SMOKE_MAX_SUMMARY_CODE_POINTS",
        2_000,
        1,
        100_000
      )
      stabilityPercent <- ProviderSmokeExample.optionalInt(
        "ZYBLW_CONTEXT_SMOKE_MIN_STABILITY_PERCENT",
        100,
        0,
        100
      )
    yield ContextCompressionLiveSmokeConfig(
      model = model,
      repetitions = repetitions,
      targetTokens = targetTokens,
      maxModelCallsPerAttempt = maxCalls,
      maxLatency = maxLatencyMillis.millis,
      maxInputTokens = maxInputTokens,
      maxOutputTokens = maxOutputTokens,
      maxSummaryCodePoints = maxSummaryCodePoints,
      minStabilityRate = stabilityPercent.toDouble / 100.0
    )

  /** 加载带版本价格表。
    *
    * 全部缺失时仍可做协议/质量 smoke，但成本门禁明确关闭；预发布 CI 应始终成组配置这五项。
    */
  private def loadPricing: IO[AgentError, Pricing] =
    ProviderSmokeExample.optionalGroup(pricingVariables).flatMap {
      case None         => ZIO.succeed(Pricing(ContextCompressionCostEstimator.unpriced, None))
      case Some(values) =>
        for
          inputPrice  <- parseNonNegativeLong(values, "ZYBLW_CONTEXT_SMOKE_INPUT_COST_PER_MILLION_MICROUNITS")
          outputPrice <- parseNonNegativeLong(
            values,
            "ZYBLW_CONTEXT_SMOKE_OUTPUT_COST_PER_MILLION_MICROUNITS"
          )
          maxCost <- parseNonNegativeLong(values, "ZYBLW_CONTEXT_SMOKE_MAX_COST_MICROUNITS")
          currency = values("ZYBLW_CONTEXT_SMOKE_PRICING_CURRENCY")
          version  = values("ZYBLW_CONTEXT_SMOKE_PRICING_VERSION")
          estimator <- ZIO
            .attempt(
              ContextCompressionCostEstimator.fixedTokenPrice(
                currency,
                version,
                inputPrice,
                outputPrice
              )
            )
            .mapError(_ => AgentError.InvalidConfiguration("Context smoke 价格币种、版本或单价格式非法"))
        yield Pricing(estimator, Some(maxCost))
    }

  /** 解析非负价格整数；不把原值复制进错误信息。 */
  private def parseNonNegativeLong(values: Map[String, String], name: String): IO[AgentError, Long] =
    ZIO
      .attempt(values(name).toLong)
      .mapError(_ => AgentError.InvalidConfiguration(s"$name 必须是非负整数"))
      .flatMap(value =>
        if value >= 0L then ZIO.succeed(value)
        else ZIO.fail(AgentError.InvalidConfiguration(s"$name 必须是非负整数"))
      )
