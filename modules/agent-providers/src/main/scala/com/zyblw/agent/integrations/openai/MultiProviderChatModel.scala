package com.zyblw.agent.integrations.openai

// 从多个 OpenAI-compatible 配置构建路由模型；实际选择仍由请求中的 provider 字段显式控制。

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.Client

object MultiProviderChatModel:
  /** 使用共享的 ZIO HTTP Client 构建 OpenAI-compatible Provider，并校验默认 Provider 必须存在。
    *
    * @param client
    *   应用级共享 HTTP Client，所有 Provider 复用其连接池
    * @param defaultProvider
    *   请求未指定 provider 时使用的路由 ID
    * @param configs
    *   OpenAI-compatible 配置，例如 DeepSeek、GLM 和兼容代理
    */
  def make(
      client: Client,
      defaultProvider: String,
      configs: Iterable[OpenAICompatibleConfig]
  ): IO[AgentError, ChatModel] =
    val models = configs.map(config => OpenAICompatibleChatModel(client, config))
    RoutedChatModel.make(defaultProvider, models)

  /** 构造包含 OpenAI Responses 原生协议的统一路由器。
    *
    * compatible 与 Responses 使用不同 Provider ID，因此可以在迁移期并存；重复 ID 和不存在的默认 Provider 仍由
    * [[com.zyblw.agent.model.RoutedChatModel.make]] 在 ZLayer 装配阶段拒绝。
    *
    * @param client
    *   应用级共享 ZIO HTTP Client
    * @param defaultProvider
    *   默认路由 ID，例如 `deepseek` 或 `openai-responses`
    * @param compatibleConfigs
    *   DeepSeek、GLM 等 `/chat/completions` 配置
    * @param responsesConfigs
    *   OpenAI `/responses` 原生配置；通常只有一项
    */
  def makeWithResponses(
      client: Client,
      defaultProvider: String,
      compatibleConfigs: Iterable[OpenAICompatibleConfig],
      responsesConfigs: Iterable[OpenAIResponsesConfig]
  ): IO[AgentError, ChatModel] =
    val models: Iterable[ChatModel] =
      compatibleConfigs.map(config => OpenAICompatibleChatModel(client, config)) ++
        responsesConfigs.map(config => OpenAIResponsesChatModel(client, config))
    RoutedChatModel.make(defaultProvider, models)

  /** 把仅包含兼容协议的多 Provider 路由器暴露为 ZLayer。 */
  def layer(
      defaultProvider: String,
      configs: Iterable[OpenAICompatibleConfig]
  ): ZLayer[Client, AgentError, ChatModel] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[Client](client => make(client, defaultProvider, configs)))

  /** 把兼容协议与 Responses 原生协议共同装配为一个 ChatModel 路由层。
    *
    * @param defaultProvider
    *   默认 Provider 路由 ID
    * @param compatibleConfigs
    *   OpenAI-compatible 配置集合
    * @param responsesConfigs
    *   Responses 原生配置集合
    */
  def layerWithResponses(
      defaultProvider: String,
      compatibleConfigs: Iterable[OpenAICompatibleConfig],
      responsesConfigs: Iterable[OpenAIResponsesConfig]
  ): ZLayer[Client, AgentError, ChatModel] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[Client](client =>
        makeWithResponses(client, defaultProvider, compatibleConfigs, responsesConfigs)
      )
    )
