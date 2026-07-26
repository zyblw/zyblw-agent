package com.zyblw.agent.examples

import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.anthropic.*
import com.zyblw.agent.integrations.gemini.*
import com.zyblw.agent.integrations.openai.*
import com.zyblw.agent.model.*
import com.zyblw.agent.testkit.*
import zio.*
import zio.http.*
import zio.json.*

/** 使用真实厂商凭据执行低成本 Provider smoke/eval 的命令行入口。
  *
  * 该程序不会读取任何业务数据，也不会输出模型正文、endpoint 或 API Key。每次默认产生一次 complete 和一次 stream 请求；只有显式设置
  * `ZYBLW_SMOKE_REPETITIONS` 才会增加调用数。失败报告仍会打印，随后进程以失败退出，便于 CI 将其作为发布门禁。
  *
  * 支持的 `ZYBLW_SMOKE_PROVIDER`：
  *
  *   - `deepseek`：`DEEPSEEK_API_KEY`、可选 `DEEPSEEK_MODEL`；
  *   - `glm`：`GLM_API_KEY`、可选 `GLM_MODEL`；
  *   - `openai-chat`：`OPENAI_API_KEY`、`OPENAI_MODEL`；
  *   - `openai-responses`：`OPENAI_API_KEY`、`OPENAI_MODEL`；
  *   - `anthropic`：`ANTHROPIC_API_KEY`、`ANTHROPIC_MODEL`；
  *   - `gemini`：`GEMINI_API_KEY`、`GEMINI_MODEL`。
  */
object ProviderSmokeExample extends ZIOAppDefault:

  /** 已完成配置加载但尚未发起网络请求的 Provider 目标。 */
  final private[examples] case class Target(model: ChatModel, modelId: String)

  /** 共享一个 ZIO HTTP Client，执行 smoke，输出低敏 JSON，并以报告结果决定进程退出状态。
    */
  val run: ZIO[Any, Any, Any] = program.provide(Client.default)

  private val program: ZIO[Client, AgentError | java.io.IOException, Unit] =
    for
      provider <- required("ZYBLW_SMOKE_PROVIDER").map(_.trim.toLowerCase)
      client   <- ZIO.service[Client]
      target   <- loadTarget(provider, client)
      config   <- loadSmokeConfig(target.modelId)
      report   <- LiveProviderSmokeRunner.run(target.model, config)
      _        <- Console.printLine(report.toJson)
      _        <- ZIO
        .fail(
          AgentError.InvalidConfiguration(
            s"Provider smoke failed: provider=${report.provider}, model=${report.model}"
          )
        )
        .unless(report.passed)
    yield ()

  /** 根据稳定选择名加载对应原生/兼容 Adapter；任何未知值在产生网络费用前失败。 */
  private[examples] def loadTarget(provider: String, client: Client): IO[AgentError, Target] = provider match
    case "deepseek" =>
      ProviderPresets.deepSeekFromEnvironment.map(config =>
        Target(OpenAICompatibleChatModel(client, config), config.defaultModel)
      )
    case "glm" =>
      ProviderPresets.glmFromEnvironment.map(config =>
        Target(OpenAICompatibleChatModel(client, config), config.defaultModel)
      )
    case "openai-chat" =>
      ProviderPresets.openAIFromEnvironment.map(config =>
        Target(OpenAICompatibleChatModel(client, config), config.defaultModel)
      )
    case "openai-responses" =>
      OpenAIResponsesConfig.fromEnvironment.map(config =>
        Target(OpenAIResponsesChatModel(client, config), config.defaultModel)
      )
    case "anthropic" =>
      AnthropicMessagesConfig.fromEnvironment.map(config =>
        Target(AnthropicMessagesChatModel(client, config), config.defaultModel)
      )
    case "gemini" =>
      GeminiInteractionsConfig.fromEnvironment.map(config =>
        Target(GeminiInteractionsChatModel(client, config), config.defaultModel)
      )
    case _ =>
      ZIO.fail(
        AgentError.InvalidConfiguration(
          "ZYBLW_SMOKE_PROVIDER 必须是 deepseek/glm/openai-chat/openai-responses/anthropic/gemini"
        )
      )

  /** 从可选环境变量加载门禁预算。解析失败会在任何 Provider 请求前终止。
    */
  private def loadSmokeConfig(model: String): IO[AgentError, LiveProviderSmokeConfig] =
    for
      repetitions        <- optionalInt("ZYBLW_SMOKE_REPETITIONS", 1, 1, 5)
      callTimeoutSeconds <- optionalLong("ZYBLW_SMOKE_CALL_TIMEOUT_SECONDS", 120L, 1L, 600L)
      maxLatencyMillis   <- optionalLong("ZYBLW_SMOKE_MAX_LATENCY_MILLIS", 60_000L, 1L, 600_000L)
      maxTotalTokens     <- optionalLong("ZYBLW_SMOKE_MAX_TOTAL_TOKENS", 2_000L, 1L, 1_000_000L)
      maxOutputTokens    <- optionalInt("ZYBLW_SMOKE_MAX_OUTPUT_TOKENS", 64, 1, 4096)
    yield LiveProviderSmokeConfig(
      model = model,
      repetitions = repetitions,
      callTimeout = callTimeoutSeconds.seconds,
      maxLatency = maxLatencyMillis.millis,
      maxTotalTokens = maxTotalTokens,
      maxOutputTokens = maxOutputTokens
    )

  /** 读取非空必需变量；错误只包含变量名。 */
  private[examples] def required(name: String): IO[AgentError, String] =
    ZIO
      .fromOption(sys.env.get(name).map(_.trim).filter(_.nonEmpty))
      .orElseFail(
        AgentError.InvalidConfiguration(s"Missing environment variable: $name")
      )

  /** 解析有界 Int，避免环境变量溢出或制造意外高费用。 */
  private[examples] def optionalInt(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): IO[AgentError, Int] =
    optionalLong(name, default.toLong, minimum.toLong, maximum.toLong).map(_.toInt)

  /** 解析有界 Long；变量原值不写入错误，防止错误配置中混入秘密。 */
  private[examples] def optionalLong(
      name: String,
      default: Long,
      minimum: Long,
      maximum: Long
  ): IO[AgentError, Long] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty) match
      case None        => ZIO.succeed(default)
      case Some(value) =>
        ZIO
          .attempt(value.toLong)
          .mapError(_ => AgentError.InvalidConfiguration(s"$name 必须是整数"))
          .flatMap(parsed =>
            if parsed >= minimum && parsed <= maximum then ZIO.succeed(parsed)
            else ZIO.fail(AgentError.InvalidConfiguration(s"$name 必须位于 $minimum..$maximum"))
          )

  /** 读取一组必须“全部存在或全部缺失”的变量。
    *
    * 价格表尤其不能只配置单价却漏掉币种/版本/预算，否则报告会把不同量纲混在一起。错误只列变量名，不回显值。
    */
  private[examples] def optionalGroup(names: Chunk[String]): IO[AgentError, Option[Map[String, String]]] =
    val values  = names.map(name => name -> sys.env.get(name).map(_.trim).filter(_.nonEmpty))
    val present = values.collect { case (name, Some(value)) => name -> value }
    if present.isEmpty then ZIO.none
    else if present.length == names.length then ZIO.some(present.toMap)
    else
      val missing = values.collect { case (name, None) => name }.mkString(",")
      ZIO.fail(AgentError.InvalidConfiguration(s"环境变量必须成组配置，缺少：$missing"))
