package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.openai.{
  OpenAICompatibility,
  OpenAICompatibleChatModel,
  OpenAICompatibleConfig
}
import com.zyblw.agent.model.*
import java.net.URI
import scala.util.Try
import zio.*
import zio.http.Client
import zio.json.*

/** OpenAI-compatible 根端点的共享信任边界。 */
private[integrations] object ProviderEndpointUrl:
  def isAllowed(value: String): Boolean =
    Try(URI(value)).toOption.exists { uri =>
      val scheme = Option(uri.getScheme).map(_.toLowerCase)
      val host   = Option(uri.getHost).map(_.toLowerCase)
      val port   = uri.getPort
      value == value.trim &&
      uri.isAbsolute &&
      host.exists(_.nonEmpty) &&
      (scheme.contains("https") || (scheme.contains("http") && host.contains("127.0.0.1"))) &&
      uri.getRawUserInfo == null &&
      uri.getRawQuery == null &&
      uri.getRawFragment == null &&
      (port == -1 || (port >= 1 && port <= 65535))
    }

/** 配置可写的模型能力子集；未列出的字段保持 Adapter 默认。 */
final case class ProviderEndpointCapabilities(
    toolCalls: Boolean = true,
    streaming: Boolean = true,
    vision: Boolean = false,
    thinking: Boolean = false,
    parallelToolCalls: Boolean = false,
    usageReporting: Boolean = true,
    reasoningTokens: Boolean = false,
    maxInputTokens: Option[Long] = None,
    maxOutputTokens: Option[Long] = None,
    reasoningEfforts: Set[ReasoningEffort] = Set.empty
) derives JsonCodec:
  require(maxInputTokens.forall(_ > 0L), "maxInputTokens 必须大于 0")
  require(maxOutputTokens.forall(_ > 0L), "maxOutputTokens 必须大于 0")

  /** 只覆盖端点 JSON 可配置的字段；strict schema、tool choice 等协议能力继承 Adapter 档案。 */
  def applyTo(base: ModelCapabilities): ModelCapabilities =
    base.copy(
      toolCalls = toolCalls,
      streaming = streaming,
      vision = vision,
      thinking = thinking,
      parallelToolCalls = parallelToolCalls,
      usageReporting = usageReporting,
      reasoningTokens = reasoningTokens,
      maxInputTokens = maxInputTokens,
      maxOutputTokens = maxOutputTokens,
      reasoningEfforts = reasoningEfforts
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
  * @param compatibilityProfile
  *   显式选择已验证的 wire 方言；适用于 providerId 为业务别名或网关名的场景
  */
final case class ProviderEndpointDeclaration(
    providerId: String,
    baseUrl: String,
    apiKeyEnv: String,
    defaultModel: String,
    protocol: String = "openai-compatible",
    models: Chunk[ProviderEndpointModel] = Chunk.empty,
    compatibilityProfile: Option[String] = None
) derives JsonCodec:
  require(providerId.matches("[a-z][a-z0-9_-]{0,31}"), "providerId 必须是 1..32 的小写标识")
  require(
    ProviderEndpoints.isAllowedBaseUrl(baseUrl),
    "baseUrl 必须是无 user-info/query/fragment 的 https URL，或本机 127.0.0.1 http URL"
  )
  require(CredentialReference.isValid(CredentialReference.environment(apiKeyEnv)), "apiKeyEnv 必须是安全的环境变量名")
  require(defaultModel.trim.nonEmpty, "defaultModel 不能为空")
  require(protocol == "openai-compatible" || protocol == "relay", "protocol 只支持 openai-compatible 或 relay")
  require(
    compatibilityProfile.forall(ProviderEndpoints.SupportedCompatibilityProfiles.contains),
    s"compatibilityProfile 只支持 ${ProviderEndpoints.SupportedCompatibilityProfiles.toList.sorted.mkString(", ")}"
  )
  require(models.map(_.name).toSet.size == models.length, "同一端点的模型名必须唯一")
  require(models.isEmpty || models.exists(_.name == defaultModel), "模型清单非空时必须包含 defaultModel")

/** 配置驱动的多端点装配：一个 JSON/环境片段即可注册多个中转站或官方兼容端点。 */
final case class ProviderEndpointsConfig(
    defaultProvider: String,
    endpoints: Chunk[ProviderEndpointDeclaration]
) derives JsonCodec:
  require(endpoints.nonEmpty, "至少声明一个 Provider 端点")
  require(endpoints.map(_.providerId).toSet.size == endpoints.length, "Provider 端点 id 必须唯一")
  require(endpoints.exists(_.providerId == defaultProvider), "defaultProvider 必须出现在端点列表中")

object ProviderEndpointsConfig:
  val EnvironmentVariable: String = "ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON"

  /** 从当前 ZIO ConfigProvider 读取整段 JSON 字符串。键名：`ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON`。 */
  def fromEnvironment: IO[AgentError, ProviderEndpointsConfig] =
    ZIO
      .config(Config.string(EnvironmentVariable))
      .mapError(error => AgentError.InvalidConfiguration(s"Provider 端点配置无效: $error"))
      .flatMap(parse)

  /** 可选加载入口：业务宿主在未配置多端点时可以显式回落到自己的单 Provider 装配。 */
  def fromEnvironmentOption: IO[AgentError, Option[ProviderEndpointsConfig]] =
    ZIO
      .config(Config.string(EnvironmentVariable).optional)
      .mapError(error => AgentError.InvalidConfiguration(s"Provider 端点配置无效: $error"))
      .flatMap {
        case Some(raw) if raw.trim.nonEmpty => parse(raw).map(Some(_))
        case _                              => ZIO.none
      }

  /** JSON 解码和 case class 语义约束都收敛为 typed 启动错误，不允许非法声明变成 defect。 */
  private def parse(raw: String): IO[AgentError, ProviderEndpointsConfig] =
    ZIO
      .attempt(raw.fromJson[ProviderEndpointsConfig])
      .mapError(_ => AgentError.InvalidConfiguration("Provider 端点 JSON 结构或约束无效"))
      .flatMap(result =>
        ZIO.fromEither(result).mapError(_ => AgentError.InvalidConfiguration("Provider 端点 JSON 结构或约束无效"))
      )

/** 把声明转换成可路由的 `ChatModel` 与 `ProviderRegistry`。 */
object ProviderEndpoints:
  val SupportedCompatibilityProfiles: Set[String] =
    Set("openai", "deepseek", "glm", "qwen", "kimi", "generic")

  /** 严格校验运维声明的端点根 URL。
    *
    * 端点是受信配置而非模型输入，但仍禁止把凭据放进 user-info/query，以及容易被字符串前缀校验误判的 `127.0.0.1.evil.example`。远端只允许 HTTPS；明文 HTTP
    * 仅用于本机确定性测试。
    */
  private[integrations] def isAllowedBaseUrl(value: String): Boolean =
    ProviderEndpointUrl.isAllowed(value)

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
      compatibility = compatibilityFor(declaration)
      descriptor    = compatibility.descriptor.copy(
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

  private[integrations] def compatibilityFor(
      declaration: ProviderEndpointDeclaration
  ): OpenAICompatibility =
    val profile = declaration.compatibilityProfile.getOrElse {
      if declaration.protocol == "relay" then "generic" else declaration.providerId
    }
    val base = profile match
      case "openai"   => OpenAICompatibility.openAI
      case "deepseek" => OpenAICompatibility.deepSeek
      case "glm"      => OpenAICompatibility.glm
      case "qwen"     => OpenAICompatibility.qwen
      case "kimi"     => OpenAICompatibility.kimi
      case _          => OpenAICompatibility.relay(declaration.providerId)
    if base.descriptor.id == declaration.providerId then base
    else base.copy(descriptor = base.descriptor.copy(id = declaration.providerId))
