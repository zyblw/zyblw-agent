package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.anthropic.*
import com.zyblw.agent.integrations.gemini.*
import com.zyblw.agent.integrations.openai.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.Client

/** 业务应用装配主流文本 Provider 的声明式配置。
  *
  * 该类型只减少 ZLayer 样板，不隐藏协议差异：DeepSeek/GLM 仍使用各自 compatibility profile， OpenAI Responses、Anthropic Messages 与
  * Gemini Interactions 都是独立原生 Adapter， 不会被伪装为 OpenAI-compatible。
  *
  * @param defaultProvider
  *   `ModelSettings.provider=None` 时选择的稳定 Provider ID
  * @param openAICompatible
  *   DeepSeek、GLM 和确实兼容 `/chat/completions` 的配置
  * @param openAIResponses
  *   OpenAI `/responses` 原生配置
  * @param anthropicMessages
  *   Anthropic `/messages` 原生配置
  * @param geminiInteractions
  *   Google `/interactions` 原生配置；单路由只允许一个稳定 `gemini` ID
  */
final case class ProviderRouterConfig(
    defaultProvider: String,
    openAICompatible: Chunk[OpenAICompatibleConfig] = Chunk.empty,
    openAIResponses: Chunk[OpenAIResponsesConfig] = Chunk.empty,
    anthropicMessages: Chunk[AnthropicMessagesConfig] = Chunk.empty,
    geminiInteractions: Option[GeminiInteractionsConfig] = None
):
  require(defaultProvider.trim.nonEmpty, "defaultProvider 不能为空")

/** 跨 integration 模块的统一业务装配入口。 */
object ProviderRouter:
  /** 使用一个共享 ZIO HTTP Client 创建全部 Adapter，再由 `RoutedChatModel` 校验 ID 唯一性和默认路由。
    *
    * @param client
    *   应用级共享 Client；连接池、TLS 和超时策略由宿主统一治理
    * @param config
    *   Provider 集合与默认选择
    * @return
    *   业务只需依赖的单一 `ChatModel`
    */
  def make(client: Client, config: ProviderRouterConfig): IO[AgentError, ChatModel] =
    val compatible: Iterable[ChatModel] = config.openAICompatible.map(OpenAICompatibleChatModel(client, _))
    val responses: Iterable[ChatModel]  = config.openAIResponses.map(OpenAIResponsesChatModel(client, _))
    val anthropic: Iterable[ChatModel]  = config.anthropicMessages.map(AnthropicMessagesChatModel(client, _))
    val gemini: Iterable[ChatModel] = config.geminiInteractions.map(GeminiInteractionsChatModel(client, _))
    RoutedChatModel.make(config.defaultProvider, compatible ++ responses ++ anthropic ++ gemini)

  /** 把 ProviderRouter 暴露为 ZLayer。
    *
    * 使用示例： {{ val modelLayer = ProviderRouter.layer( ProviderRouterConfig( defaultProvider = "deepseek",
    * openAICompatible = Chunk(ProviderPresets.deepSeek(deepSeekKey)), anthropicMessages =
    * Chunk(AnthropicMessagesConfig(anthropicUrl, anthropicKey, claudeModel)), geminiInteractions =
    * Some(GeminiInteractionsConfig(geminiUrl, geminiKey, geminiModel)) ) ) }}
    *
    * @param config
    *   已从 Secret Manager/环境变量构造的配置
    */
  def layer(config: ProviderRouterConfig): ZLayer[Client, AgentError, ChatModel] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[Client](client => make(client, config)))
