package com.zyblw.agent.integrations.anthropic

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.json.ast.Json

/** Anthropic Messages 原生协议配置。
  *
  * @param baseUrl
  *   Anthropic API 根地址，通常是 `https://api.anthropic.com/v1`
  * @param apiKey
  *   `x-api-key` 凭据；只能来自环境变量或 Secret Manager
  * @param defaultModel
  *   请求没有指定模型时使用的模型 ID
  * @param anthropicVersion
  *   必需的 `anthropic-version` 协议版本
  * @param defaultMaxTokens
  *   Messages API 必填的最大输出 token；请求可通过 settings 覆盖
  * @param requestTimeout
  *   完整响应或 SSE 流的总超时
  * @param defaultOptions
  *   部署级扩展字段，例如 thinking 配置；不得覆盖保留协议字段
  */
final case class AnthropicMessagesConfig(
    baseUrl: String,
    apiKey: String,
    defaultModel: String,
    anthropicVersion: String = "2023-06-01",
    defaultMaxTokens: Int = 4096,
    requestTimeout: Duration = 90.seconds,
    defaultOptions: Map[String, Json] = Map.empty
):
  require(baseUrl.nonEmpty, "baseUrl must not be empty")
  require(apiKey.nonEmpty, "apiKey must not be empty")
  require(defaultModel.nonEmpty, "defaultModel must not be empty")
  require(anthropicVersion.nonEmpty, "anthropicVersion must not be empty")
  require(defaultMaxTokens > 0, "defaultMaxTokens must be positive")

  /** Messages 创建端点。 */
  val messagesUrl: String = s"${baseUrl.stripSuffix("/")}/messages"

  /** 日志摘要永远不包含 API Key。 */
  override def toString: String =
    s"AnthropicMessagesConfig(baseUrl=$baseUrl, apiKey=<redacted>, defaultModel=$defaultModel, " +
      s"anthropicVersion=$anthropicVersion, defaultMaxTokens=$defaultMaxTokens, requestTimeout=$requestTimeout)"

object AnthropicMessagesConfig:
  /** Anthropic Messages 的 ZIO Config 描述。
    *
    * 使用大写键保持现有环境变量契约；API Key 先进入 `Config.Secret`，只有构造 HTTP Adapter 配置时才展开。
    */
  val environmentConfig: Config[AnthropicMessagesConfig] =
    (
      Config.string("ANTHROPIC_BASE_URL").withDefault("https://api.anthropic.com/v1") ++
        Config.secret("ANTHROPIC_API_KEY") ++
        Config.string("ANTHROPIC_MODEL") ++
        Config.string("ANTHROPIC_VERSION").withDefault("2023-06-01") ++
        Config.int("ANTHROPIC_MAX_TOKENS").withDefault(4096) ++
        Config.duration("ANTHROPIC_REQUEST_TIMEOUT").withDefault(90.seconds)
    ).mapAttempt { case (baseUrl, apiKey, model, version, maxTokens, timeout) =>
      AnthropicMessagesConfig(baseUrl, apiKey.stringValue, model, version, maxTokens, timeout)
    }

  /** 从当前 ZIO ConfigProvider 创建配置，并在应用启动阶段报告缺失项。 */
  def fromEnvironment: IO[AgentError, AnthropicMessagesConfig] =
    ZIO
      .config(environmentConfig)
      .mapError(error => AgentError.InvalidConfiguration(s"Anthropic Messages 配置无效: $error"))

/** Anthropic Messages 的保守能力声明；具体模型可在未来由能力矩阵覆盖。 */
private[anthropic] object AnthropicMessagesDescriptor:
  val value: ProviderDescriptor = ProviderDescriptor(
    id = "anthropic",
    displayName = "Anthropic Messages",
    protocol = "anthropic-messages",
    capabilities = ModelCapabilities(
      toolCalls = true,
      strictToolSchema = false,
      specificToolChoice = true,
      developerRole = false,
      thinking = true,
      vision = true,
      streaming = true,
      parallelToolCalls = true,
      usageReporting = true
    )
  )
