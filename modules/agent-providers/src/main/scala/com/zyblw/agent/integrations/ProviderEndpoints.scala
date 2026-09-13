package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.openai.{
  OpenAICompatibility,
  OpenAICompatibleChatModel,
  OpenAICompatibleConfig
}
import com.zyblw.agent.model.*
import zio.*
import zio.http.Client
import zio.json.*

/** 配置可写的模型能力子集；未列出的字段保持 Adapter 默认。 */
final case class ProviderEndpointCapabilities(
    toolCalls: Boolean = true,
    streaming: Boolean = true,
    vision: Boolean = false,
    thinking: Boolean = false,
    usageReporting: Boolean = true
) derives JsonCodec:
  /** 只覆盖端点 JSON 可配置的字段；strict schema、tool choice 等协议能力继承 Adapter 档案。 */
  def applyTo(base: ModelCapabilities): ModelCapabilities =
    base.copy(
      toolCalls = toolCalls,
      streaming = streaming,
      vision = vision,
      thinking = thinking,
      usageReporting = usageReporting
    )

/** 配置声明的单个模型能力与可选计价标签。价格数字不进日志。 */
final case class ProviderEndpointModel(
    name: String,
    capabilities: ProviderEndpointCapabilities = ProviderEndpointCapabilities(),
    priceInputPerMillion: Option[String] = None,
    priceOutputPerMillion: Option[String] = None
) derives JsonCodec:
  require(name.trim.nonEmpty, "端点模型名不能为空")

/** 一个可路由的 OpenAI-compatible 端点，包括中转站。
  *
  * @param providerId
  *   与 `ModelSettings.provider` 同一命名空间
  * @param baseUrl
  *   Chat Completions 根路径，例如 `https://gateway.example/v1`
  * @param apiKeyEnv
  *   密钥环境变量名；值从不进入配置对象的 `toString`
  * @param defaultModel
  *   请求未指定模型时发送的名称
  * @param protocol
  *   `openai-compatible` 或 `relay`
  */
final case class ProviderEndpointDeclaration(
    providerId: String,
    baseUrl: String,
    apiKeyEnv: String,
    defaultModel: String,
    protocol: String = "openai-compatible",
    models: Chunk[ProviderEndpointModel] = Chunk.empty
) derives JsonCodec:
  require(providerId.matches("[a-z][a-z0-9_-]{0,31}"), "providerId 必须是 1..32 的小写标识")
  require(
    baseUrl.startsWith("https://") || baseUrl.startsWith("http://127.0.0.1"),
    "baseUrl 必须是 https 或本机 http"
  )
  require(CredentialReference.isValid(CredentialReference.environment(apiKeyEnv)), "apiKeyEnv 必须是安全的环境变量名")
  require(defaultModel.trim.nonEmpty, "defaultModel 不能为空")
  require(protocol == "openai-compatible" || protocol == "relay", "protocol 只支持 openai-compatible 或 relay")

/** 配置驱动的多端点装配：一个 JSON/环境片段即可注册多个中转站或官方兼容端点。 */
final case class ProviderEndpointsConfig(
    defaultProvider: String,
    endpoints: Chunk[ProviderEndpointDeclaration]
) derives JsonCodec:
  require(endpoints.nonEmpty, "至少声明一个 Provider 端点")
  require(endpoints.map(_.providerId).toSet.size == endpoints.length, "Provider 端点 id 必须唯一")
  require(endpoints.exists(_.providerId == defaultProvider), "defaultProvider 必须出现在端点列表中")

object ProviderEndpointsConfig:
  /** 从当前 ZIO ConfigProvider 读取整段 JSON 字符串。键名：`ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON`。 */
  def fromEnvironment: IO[AgentError, ProviderEndpointsConfig] =
    ZIO
      .config(Config.string("ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON"))
      .mapError(error => AgentError.InvalidConfiguration(s"Provider 端点配置无效: $error"))
      .flatMap { raw =>
        ZIO
          .fromEither(raw.fromJson[ProviderEndpointsConfig])
          .mapError(error => AgentError.InvalidConfiguration(s"Provider 端点 JSON 无效: $error"))
      }

/** 把声明转换成可路由的 `ChatModel` 与 `ProviderRegistry`。 */
object ProviderEndpoints:
  /** 读取每个端点的密钥、构造兼容 Adapter，并用模型清单填充 descriptor。 */
  def assemble(
      config: ProviderEndpointsConfig
  ): ZIO[Client, AgentError, (RoutedChatModel, ProviderRegistry)] =
    ZIO
      .foreach(config.endpoints)(materialize)
      .flatMap { registrations =>
        val models = registrations.map(_.chatModel)
        for
          router   <- RoutedChatModel.make(config.defaultProvider, models)
          registry <- ProviderRegistry.make(router, registrations)
        yield router -> registry
      }

  def layer(config: ProviderEndpointsConfig): ZLayer[Client, AgentError, ChatModel & ProviderRegistry] =
    ZLayer.fromZIOEnvironment {
      assemble(config).map { case (router, registry) =>
        ZEnvironment[ChatModel](router).add[ProviderRegistry](registry)
      }
    }

  private def materialize(
      declaration: ProviderEndpointDeclaration
  ): ZIO[Client, AgentError, ProviderRegistration] =
    for
      apiKey <- ZIO
        .config(Config.secret(declaration.apiKeyEnv))
        .mapError(error => AgentError.InvalidConfiguration(s"${declaration.providerId} 密钥无效: $error"))
      client <- ZIO.service[Client]
      compatibility = declaration.protocol match
        case "relay" => OpenAICompatibility.relay(declaration.providerId)
        case _       => compatibilityFor(declaration.providerId)
      descriptor = compatibility.descriptor.copy(
        models = declaration.models
          .map(model => model.name -> model.capabilities.applyTo(compatibility.descriptor.capabilities))
          .toMap
      )
      cfg = OpenAICompatibleConfig(
        baseUrl = declaration.baseUrl,
        apiKey = apiKey.stringValue,
        defaultModel = declaration.defaultModel,
        compatibility = compatibility.copy(descriptor = descriptor)
      )
      model = OpenAICompatibleChatModel(client, cfg)
    yield ProviderRegistration.openAICompatible(
      model,
      cfg,
      CredentialReference.environment(declaration.apiKeyEnv)
    )

  private def compatibilityFor(id: String): OpenAICompatibility = id match
    case "openai"   => OpenAICompatibility.openAI
    case "deepseek" => OpenAICompatibility.deepSeek
    case "glm"      => OpenAICompatibility.glm
    case "qwen"     => OpenAICompatibility.qwen
    case other      => OpenAICompatibility.relay(other)
