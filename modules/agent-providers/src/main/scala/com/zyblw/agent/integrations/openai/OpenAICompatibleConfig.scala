package com.zyblw.agent.integrations.openai

// Provider 配置与预设：API Key 必须来自环境变量或 Secret Manager，禁止写入仓库和日志。

import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.{CredentialReference, ProviderEndpointUrl}
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
  require(
    ProviderEndpointUrl.isAllowed(baseUrl),
    "baseUrl must be an HTTPS root URL without user-info/query/fragment, or local 127.0.0.1 HTTP"
  )
  require(apiKey.nonEmpty, "apiKey must not be empty")
  require(defaultModel.nonEmpty, "defaultModel must not be empty")

  val chatCompletionsUrl: String = s"${baseUrl.stripSuffix("/")}/chat/completions"

  /** 返回脱敏配置摘要，永不打印真实 API Key。 */
  override def toString: String =
    s"OpenAICompatibleConfig(baseUrl=$baseUrl, apiKey=<redacted>, defaultModel=$defaultModel, " +
      s"provider=${compatibility.descriptor.id}, requestTimeout=$requestTimeout)"

object OpenAICompatibleConfig:
  /** 本 loader 读取 API Key 的环境变量名。
    *
    * 单独声明而不是让管理面按 Provider ID 猜测：同一个 `OpenAICompatibleConfig` 类型被多个兼容 Provider 复用， 猜测会在"运维明明配了
    * Key、界面却说来源是另一个变量"时把排障引向错误的方向。
    */
  val ApiKeyVariable: String = "OPENAI_API_KEY"

  /** 可展示的凭据引用；只含变量名，不含值。 */
  val credentialReference: String = CredentialReference.environment(ApiKeyVariable)

  /** 通用 OpenAI-compatible 配置描述。
    *
    * API Key 使用 `Config.Secret` 读取，在配置错误、测试报告和调试输出中保持脱敏；只有构造 HTTP Adapter 时才展开为 String。当前键名保持既有 `OPENAI_*`
    * 部署协议，默认 ZIO Provider 会读取同名环境变量或系统属性。
    */
  val environmentConfig: Config[OpenAICompatibleConfig] =
    (
      Config.string("OPENAI_BASE_URL").withDefault("https://api.openai.com/v1") ++
        Config.secret(ApiKeyVariable) ++
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
  val KimiBaseUrl          = "https://api.moonshot.ai/v1"

  /** 各 OpenAI-compatible Provider 的 API Key 变量名；共用配置类型但不共用凭据。 */
  val DeepSeekApiKeyVariable: String = "DEEPSEEK_API_KEY"
  val GlmApiKeyVariable: String      = "GLM_API_KEY"
  val QwenApiKeyVariable: String     = "QWEN_API_KEY"
  val KimiApiKeyVariable: String     = "MOONSHOT_API_KEY"

  /** 可展示的凭据引用；只含变量名，不含值。 */
  val deepSeekCredentialReference: String = CredentialReference.environment(DeepSeekApiKeyVariable)
  val glmCredentialReference: String      = CredentialReference.environment(GlmApiKeyVariable)
  val qwenCredentialReference: String     = CredentialReference.environment(QwenApiKeyVariable)
  val kimiCredentialReference: String     = CredentialReference.environment(KimiApiKeyVariable)

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

  /** 构造 Qwen 配置。端点和模型必须由部署显式给出，因为 Model Studio API Key 与区域端点绑定，模型 ID 也会演进。 */
  def qwen(apiKey: String, baseUrl: String, model: String): OpenAICompatibleConfig =
    OpenAICompatibleConfig(
      baseUrl = baseUrl,
      apiKey = apiKey,
      defaultModel = model,
      compatibility = OpenAICompatibility.qwen
    )

  /** 构造 Moonshot Kimi 配置。模型必须显式给出，避免框架把会演进的营销别名固化为长期默认。 */
  def kimi(
      apiKey: String,
      model: String,
      baseUrl: String = KimiBaseUrl
  ): OpenAICompatibleConfig =
    OpenAICompatibleConfig(
      baseUrl = baseUrl,
      apiKey = apiKey,
      defaultModel = model,
      compatibility = OpenAICompatibility.kimi
    )

  /** DeepSeek 的 ZIO Config 描述；密钥保持为 `Config.Secret` 直到构造 Adapter。 */
  val deepSeekEnvironmentConfig: Config[OpenAICompatibleConfig] =
    (
      Config.secret(DeepSeekApiKeyVariable) ++
        Config.string("DEEPSEEK_MODEL").withDefault(DeepSeekDefaultModel)
    ).mapAttempt { case (key, model) => deepSeek(key.stringValue, model) }

  /** GLM 的 ZIO Config 描述。 */
  val glmEnvironmentConfig: Config[OpenAICompatibleConfig] =
    (
      Config.secret(GlmApiKeyVariable) ++
        Config.string("GLM_MODEL").withDefault(GlmDefaultModel)
    ).mapAttempt { case (key, model) => glm(key.stringValue, model) }

  /** Qwen 的 ZIO Config 描述。区域端点与模型都必须显式配置，避免错误区域或过期模型静默上线。 */
  val qwenEnvironmentConfig: Config[OpenAICompatibleConfig] =
    (
      Config.secret(QwenApiKeyVariable) ++
        Config.string("QWEN_BASE_URL") ++
        Config.string("QWEN_MODEL")
    ).mapAttempt { case (key, baseUrl, model) => qwen(key.stringValue, baseUrl, model) }

  /** Kimi 的 ZIO Config 描述。模型显式配置；官方根端点可按部署需要覆盖。 */
  val kimiEnvironmentConfig: Config[OpenAICompatibleConfig] =
    (
      Config.secret(KimiApiKeyVariable) ++
        Config.string("KIMI_MODEL") ++
        Config.string("KIMI_BASE_URL").withDefault(KimiBaseUrl)
    ).mapAttempt { case (key, model, baseUrl) => kimi(key.stringValue, model, baseUrl) }

  /** 从当前 ZIO ConfigProvider 读取 DeepSeek 密钥和模型。 */
  def deepSeekFromEnvironment: IO[AgentError, OpenAICompatibleConfig] =
    load("DeepSeek", deepSeekEnvironmentConfig)

  /** 从当前 ZIO ConfigProvider 读取 GLM 密钥和模型。 */
  def glmFromEnvironment: IO[AgentError, OpenAICompatibleConfig] =
    load("GLM", glmEnvironmentConfig)

  /** 从当前 ZIO ConfigProvider 读取 Qwen 密钥、区域端点和模型。 */
  def qwenFromEnvironment: IO[AgentError, OpenAICompatibleConfig] =
    load("Qwen", qwenEnvironmentConfig)

  /** 从当前 ZIO ConfigProvider 读取 Kimi 密钥、模型和可选端点。 */
  def kimiFromEnvironment: IO[AgentError, OpenAICompatibleConfig] =
    load("Kimi", kimiEnvironmentConfig)

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
