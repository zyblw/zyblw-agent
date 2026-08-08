package com.zyblw.agent.integrations

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.anthropic.*
import com.zyblw.agent.integrations.gemini.*
import com.zyblw.agent.integrations.openai.*
import com.zyblw.agent.model.*
import zio.*

/** 凭据来源的可展示引用。
  *
  * 引用是一个**指针而不是值**:管理台要回答的是"这个 Provider 的 Key 从哪里注入",答案是变量名。把值、前缀、后缀
  * 或任何由值派生的特征放进来,都会让一个只读运维界面变成凭据的旁路读取通道——而这条通道的授权门槛远低于密钥后端本身。
  */
object CredentialReference:
  /** 环境变量/系统属性来源的方案名;与 ZIO 默认 `ConfigProvider` 的读取方式一致。 */
  val EnvironmentScheme: String = "env"

  /** 装配方未声明来源时使用的占位引用。 */
  val Unspecified: String = "unspecified"

  /** 构造一个环境变量引用,例如 `env:DEEPSEEK_API_KEY`。 */
  def environment(variable: String): String = s"$EnvironmentScheme:$variable"

  /** 引用必须是低基数标签,不能是任意字符串。
    *
    * 这条校验不是格式洁癖:它是"引用里不会出现密钥"的唯一机器可验证近似。真实 Key 含有大小写混排的长随机段, 几乎不可能同时满足"短方案名 + 冒号 +
    * 受限字符集"的形状,因此把误传的值挡在装配期而不是等它出现在管理台上。
    */
  def isValid(value: String): Boolean =
    value == Unspecified || value.matches("[a-z][a-z0-9-]{0,15}:[A-Za-z0-9_./-]{1,120}")

/** 一个已装配 Provider 的显式声明。
  *
  * 目录需要三件 `ChatModel` SPI 不提供的事实:部署默认模型名、凭据来自哪里、凭据是否就位。它们都躺在各 Provider 自己的配置 case class 里,而 SPI
  * 刻意不暴露配置——一个模型实现不应该被迫公开它的 endpoint 和密钥字段。
  *
  * 因此这里要求装配方**显式声明**,而不是让目录用反射或类型分支去猜。猜测只对框架内置的四个适配器有效,自定义 `ChatModel`
  * 会静默退化成"没有默认模型、凭据状态未知";而显式声明对所有实现一视同仁,也让声明与真实装配在同一处 代码里可比对。
  *
  * @param chatModel
  *   已装配的适配器实例;目录只读取它的 `provider` 与 `descriptor`
  * @param defaultModel
  *   请求未指定模型时该 Provider 实际发出的模型名,取自其配置的 `defaultModel`
  * @param credentialReference
  *   凭据来源的可展示引用,通常来自各配置伴生对象的 `credentialReference`
  * @param credentialPresent
  *   装配时是否解析到非空凭据
  */
final case class ProviderRegistration(
    chatModel: ChatModel,
    defaultModel: String,
    credentialReference: String,
    credentialPresent: Boolean
):
  require(defaultModel.trim.nonEmpty, "Provider 默认模型不能为空")
  // 失败消息不回显 credentialReference:如果调用方误把 Key 本身传进来,回显会让这条防线自己成为泄漏点。
  require(CredentialReference.isValid(credentialReference), "凭据引用必须是低基数的 scheme:name 标签")

  /** 路由名;与 `ModelSettings.provider` 同一命名空间。 */
  def provider: String = chatModel.provider

  /** 投影为管理面契约的凭据状态。 */
  def credential: ModelCredentialStatus =
    ModelCredentialStatus(credentialPresent, credentialReference)

object ProviderRegistration:
  /** 声明一个 OpenAI-compatible 适配器。
    *
    * 引用必须显式传入:OpenAI、DeepSeek 与 GLM 共用同一配置类型却使用三个不同的变量,任何默认值都会对其中两个说谎。 可用常量见
    * `OpenAICompatibleConfig.credentialReference` 与 `ProviderPresets.deepSeekCredentialReference`。
    */
  def openAICompatible(
      chatModel: ChatModel,
      config: OpenAICompatibleConfig,
      credentialReference: String
  ): ProviderRegistration =
    ProviderRegistration(chatModel, config.defaultModel, credentialReference, present(config.apiKey))

  /** 声明一个 OpenAI Responses 适配器。 */
  def openAIResponses(
      chatModel: ChatModel,
      config: OpenAIResponsesConfig,
      credentialReference: String = OpenAIResponsesConfig.credentialReference
  ): ProviderRegistration =
    ProviderRegistration(chatModel, config.defaultModel, credentialReference, present(config.apiKey))

  /** 声明一个 Anthropic Messages 适配器。 */
  def anthropicMessages(
      chatModel: ChatModel,
      config: AnthropicMessagesConfig,
      credentialReference: String = AnthropicMessagesConfig.credentialReference
  ): ProviderRegistration =
    ProviderRegistration(chatModel, config.defaultModel, credentialReference, present(config.apiKey))

  /** 声明一个 Gemini Interactions 适配器。 */
  def geminiInteractions(
      chatModel: ChatModel,
      config: GeminiInteractionsConfig,
      credentialReference: String = GeminiInteractionsConfig.credentialReference
  ): ProviderRegistration =
    ProviderRegistration(chatModel, config.defaultModel, credentialReference, present(config.apiKey))

  /** 凭据是否就位;只看非空,不看内容、长度或格式。 */
  private def present(apiKey: String): Boolean = apiKey.trim.nonEmpty

/** 已装配路由拓扑与其 Provider 声明的校验结果。
  *
  * 单独成一个环境服务而不是让目录和管理服务各自接收一遍 `Chunk[ProviderRegistration]`:两者都需要同一份声明,而
  * 分别传入意味着可以传入两份不同的清单,让"目录里能选到的模型"与"探活能打到的模型"悄悄分叉。
  *
  * @param chatModel
  *   宿主装配的顶层模型;探活刻意走它而不是绕过它直连后端,否则探活证明的"可达"与生产路径并不是同一条
  * @param defaultProvider
  *   路由默认 Provider;单 Provider 部署即它自己
  * @param registrations
  *   按 Provider 名排序的声明,使派生视图对前端 diff 稳定
  */
final class ProviderRegistry private (
    val chatModel: ChatModel,
    val defaultProvider: String,
    val registrations: Chunk[ProviderRegistration]
):
  /** 查询某个 Provider 的部署默认模型。 */
  def defaultModelOf(provider: String): Option[String] =
    registrations.find(_.provider == provider).map(_.defaultModel)

object ProviderRegistry:
  /** 校验声明与真实路由拓扑一致后构造注册表。
    *
    * 两个方向都必须相等,而不只是"声明是路由的子集":
    *   - 少声明一个可路由 Provider,会让目录漏掉它,而目录同时是运行时覆盖的写入校验依据——运维会被告知一个明明 可用的 Provider "未注册"。
    *   - 多声明一个不可路由 Provider,会让管理台展示一个选中后每次调用都以 `ProviderNotFound` 失败的选项。
    *
    * 两种偏差都在装配期以 `InvalidConfiguration` 快速失败,而不是等到运维在界面上踩中。
    */
  def make(
      chatModel: ChatModel,
      registrations: Iterable[ProviderRegistration]
  ): IO[AgentError, ProviderRegistry] =
    val declared                  = Chunk.fromIterable(registrations)
    val (routerDefault, routable) = topology(chatModel)
    val names                     = declared.map(_.provider)
    val duplicates = names.groupBy(identity).collect { case (name, group) if group.length > 1 => name }
    val missing    = routable.diff(names.toSet)
    val unroutable = names.toSet.diff(routable)
    if declared.isEmpty then invalid("Provider 注册表不能为空")
    else if duplicates.nonEmpty then invalid(s"Provider 重复声明: ${sorted(duplicates)}")
    else if missing.nonEmpty then invalid(s"可路由但未声明的 Provider: ${sorted(missing)}")
    else if unroutable.nonEmpty then invalid(s"已声明但不可路由的 Provider: ${sorted(unroutable)}")
    else
      ZIO.succeed(
        new ProviderRegistry(chatModel, routerDefault, declared.sortBy(_.provider))
      )

  /** 标准装配;顶层 `ChatModel` 由 `ProviderRouter.layer` 之类的入口提供。 */
  def layer(
      registrations: Iterable[ProviderRegistration]
  ): ZLayer[ChatModel, AgentError, ProviderRegistry] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[ChatModel](make(_, registrations)))

  /** 读取路由拓扑。
    *
    * 只区分"多 Provider 路由器"与"单 Provider 直连"两种形态,因为 SPI 只有这两种:`RoutedChatModel` 公开了默认名与
    * 后端映射,其余任何实现都只代表它自己。这不是按厂商类型做的分支链,新增适配器不需要在这里改一行。
    */
  private def topology(chatModel: ChatModel): (String, Set[String]) = chatModel match
    case router: RoutedChatModel => router.defaultProvider -> router.providers.keySet
    case single                  => single.provider        -> Set(single.provider)

  private def sorted(names: Iterable[String]): String = names.toList.sorted.mkString(", ")

  private def invalid(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.InvalidConfiguration(message))
