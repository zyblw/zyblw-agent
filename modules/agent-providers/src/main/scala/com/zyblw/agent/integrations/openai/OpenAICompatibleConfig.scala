package com.zyblw.agent.integrations.openai

// Provider 配置与预设：API Key 必须来自环境变量或 Secret Manager，禁止写入仓库和日志。

import com.zyblw.agent.core.*
import zio.*

final case class OpenAICompatibleConfig(
    baseUrl: String,
    apiKey: String,
    defaultModel: String,
    organization: Option[String] = None,
    requestTimeout: Duration = 90.seconds,
    compatibility: OpenAICompatibility = OpenAICompatibility.openAI,
    defaultOptions: Map[String, zio.json.ast.Json] = Map.empty
):
  // 构造阶段快速失败，避免带着空 URL、模型或密钥启动服务。
  require(baseUrl.nonEmpty, "baseUrl must not be empty")
  require(apiKey.nonEmpty, "apiKey must not be empty")
  require(defaultModel.nonEmpty, "defaultModel must not be empty")

  val chatCompletionsUrl: String = s"${baseUrl.stripSuffix("/")}/chat/completions"

  /** 返回脱敏配置摘要，永不打印真实 API Key。 */
  override def toString: String =
    s"OpenAICompatibleConfig(baseUrl=$baseUrl, apiKey=<redacted>, defaultModel=$defaultModel, " +
      s"provider=${compatibility.descriptor.id}, requestTimeout=$requestTimeout)"

object OpenAICompatibleConfig:
  /** 通用 OpenAI-compatible 配置描述。
    *
    * API Key 使用 `Config.Secret` 读取，在配置错误、测试报告和调试输出中保持脱敏；只有构造 HTTP Adapter 时才展开为 String。当前键名保持既有 `OPENAI_*`
    * 部署协议，默认 ZIO Provider 会读取同名环境变量或系统属性。
    */
  val environmentConfig: Config[OpenAICompatibleConfig] =
    (
      Config.string("OPENAI_BASE_URL").withDefault("https://api.openai.com/v1") ++
        Config.secret("OPENAI_API_KEY") ++
        Config.string("OPENAI_MODEL") ++
        Config.string("OPENAI_ORGANIZATION").optional
    ).mapAttempt { case (baseUrl, apiKey, model, organization) =>
      OpenAICompatibleConfig(baseUrl, apiKey.stringValue, model, organization.filter(_.trim.nonEmpty))
    }

  /** 从当前 ZIO `ConfigProvider` 创建配置；默认 Provider 对应环境变量/系统属性。 */
  def fromEnvironment: IO[AgentError, OpenAICompatibleConfig] =
    ZIO
      .config(environmentConfig)
      .mapError(error => AgentError.InvalidConfiguration(s"OpenAI-compatible 配置无效: $error"))

object ProviderPresets:
  val DeepSeekDefaultModel = "deepseek-v4-flash"
  val GlmDefaultModel      = "glm-4.7-flash"

  /** 构造 OpenAI 官方端点配置。 */
  def openAI(apiKey: String, model: String): OpenAICompatibleConfig =
    OpenAICompatibleConfig(
      baseUrl = "https://api.openai.com/v1",
      apiKey = apiKey,
      defaultModel = model,
      compatibility = OpenAICompatibility.openAI
    )

  /** 构造 DeepSeek 配置；baseUrl 可用于官方区域端点或受控代理。 */
  def deepSeek(
      apiKey: String,
      model: String = DeepSeekDefaultModel,
      thinking: Boolean = true
  ): OpenAICompatibleConfig =
    OpenAICompatibleConfig(
      baseUrl = "https://api.deepseek.com",
      apiKey = apiKey,
      defaultModel = model,
      compatibility = OpenAICompatibility.deepSeek,
      defaultOptions = Map(
        "thinking" -> zio.json.ast.Json.Obj(
          "type" -> zio.json.ast.Json.Str(if thinking then "enabled" else "disabled")
        )
      )
    )

  /** 构造 GLM 配置并应用其兼容能力限制。 */
  def glm(apiKey: String, model: String = GlmDefaultModel): OpenAICompatibleConfig =
    OpenAICompatibleConfig(
      baseUrl = "https://open.bigmodel.cn/api/paas/v4",
      apiKey = apiKey,
      defaultModel = model,
      compatibility = OpenAICompatibility.glm
    )

  /** DeepSeek 的 ZIO Config 描述；密钥保持为 `Config.Secret` 直到构造 Adapter。 */
  val deepSeekEnvironmentConfig: Config[OpenAICompatibleConfig] =
    (
      Config.secret("DEEPSEEK_API_KEY") ++
        Config.string("DEEPSEEK_MODEL").withDefault(DeepSeekDefaultModel)
    ).mapAttempt { case (key, model) => deepSeek(key.stringValue, model) }

  /** GLM 的 ZIO Config 描述。 */
  val glmEnvironmentConfig: Config[OpenAICompatibleConfig] =
    (
      Config.secret("GLM_API_KEY") ++
        Config.string("GLM_MODEL").withDefault(GlmDefaultModel)
    ).mapAttempt { case (key, model) => glm(key.stringValue, model) }

  /** 从当前 ZIO ConfigProvider 读取 DeepSeek 密钥和模型。 */
  def deepSeekFromEnvironment: IO[AgentError, OpenAICompatibleConfig] =
    load("DeepSeek", deepSeekEnvironmentConfig)

  /** 从当前 ZIO ConfigProvider 读取 GLM 密钥和模型。 */
  def glmFromEnvironment: IO[AgentError, OpenAICompatibleConfig] =
    load("GLM", glmEnvironmentConfig)

  /** 从当前 ZIO ConfigProvider 读取 OpenAI 密钥和模型。 */
  def openAIFromEnvironment: IO[AgentError, OpenAICompatibleConfig] =
    OpenAICompatibleConfig.fromEnvironment.map(config =>
      openAI(config.apiKey, config.defaultModel).copy(
        baseUrl = config.baseUrl,
        organization = config.organization,
        requestTimeout = config.requestTimeout
      )
    )

  /** 统一把 ZIO Config 错误收敛为框架 typed error，错误文本不展开 Secret。 */
  private def load(
      name: String,
      config: Config[OpenAICompatibleConfig]
  ): IO[AgentError, OpenAICompatibleConfig] =
    ZIO.config(config).mapError(error => AgentError.InvalidConfiguration(s"$name 配置无效: $error"))
