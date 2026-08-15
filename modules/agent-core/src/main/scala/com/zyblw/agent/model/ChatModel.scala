package com.zyblw.agent.model

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

enum ToolCallingCapability:
  case Unsupported, Basic, Parallel

enum StructuredOutputCapability:
  case Unsupported, JsonObject, JsonSchemaStrict

/** 模型能力必须按 Provider + Model 描述，runtime 不再假设所有兼容接口都支持相同参数。
  */
final case class ModelCapabilities(
    toolCalls: Boolean = true,
    strictToolSchema: Boolean = false,
    specificToolChoice: Boolean = false,
    developerRole: Boolean = false,
    thinking: Boolean = false,
    vision: Boolean = false,
    streaming: Boolean = false,
    audio: Boolean = false,
    parallelToolCalls: Boolean = false,
    usageReporting: Boolean = true,
    maxInputTokens: Option[Long] = None,
    maxOutputTokens: Option[Long] = None
):
  /** 把布尔能力归纳为“不支持/单调用/并行调用”三级，供 Runtime 做模式选择。 */
  def toolCalling: ToolCallingCapability =
    if !toolCalls then ToolCallingCapability.Unsupported
    else if parallelToolCalls then ToolCallingCapability.Parallel
    else ToolCallingCapability.Basic

  /** 根据严格 Schema 能力给出结构化输出等级；不支持严格 Schema 时只承诺 JSON 对象。 */
  def structuredOutput: StructuredOutputCapability =
    if strictToolSchema then StructuredOutputCapability.JsonSchemaStrict
    else StructuredOutputCapability.JsonObject

final case class ProviderDescriptor(
    id: String,
    displayName: String,
    protocol: String,
    capabilities: ModelCapabilities,
    models: Map[String, ModelCapabilities] = Map.empty
):
  /** 查询指定模型能力；模型没有单独条目时回退到 Provider 默认能力。
    * @param model
    *   请求模型；`None` 表示使用 Provider 默认模型
    */
  def capabilitiesFor(model: Option[String]): ModelCapabilities =
    model.flatMap(models.get).getOrElse(capabilities)

/** Provider 流必须输出可以独立组装的语义事件，而不是暴露网络 chunk。 */
enum ModelStreamEvent:
  case ResponseStarted(providerRequestId: Option[String])
  case TextDelta(value: String)
  case ReasoningDelta(value: String)
  case ToolCallStarted(callId: String, name: String)
  case ToolCallDelta(callId: String, name: Option[String], argumentsDelta: String)
  case ToolCallCompleted(call: ToolCall)
  case UsageUpdated(usage: TokenUsage)
  case Completed(response: ChatResponse)

/** 最小稳定模型 SPI。Provider 适配器必须让取消传播到 HTTP/流，并把厂商错误映射为 `AgentError`。
  */
trait ChatModel:
  /** Provider 路由名称，必须与 `ModelSettings.provider` 使用同一命名空间。 */
  def provider: String

  /** 返回 Provider 描述；自定义实现应覆盖默认的保守能力。 */
  def descriptor: ProviderDescriptor =
    ProviderDescriptor(provider, provider, "custom", ModelCapabilities())

  /** 获取模型能力。默认从静态 descriptor 读取；动态 Provider 可覆盖并查询远端模型清单。
    * @param model
    *   可选模型名
    */
  def capabilities(model: Option[String]): IO[AgentError, ModelCapabilities] =
    ZIO.succeed(descriptor.capabilitiesFor(model))

  /** 完成一次非流式调用。
    * @param request
    *   厂商无关请求
    * @return
    *   完整助手消息、结束原因和用量
    *
    * 配置、协议或网络失败通过 ZIO 的类型化 `AgentError` 错误通道返回，不使用异常声明。
    */
  def complete(request: ChatRequest): IO[AgentError, ChatResponse]

  /** 流式调用入口。默认把 `complete` 包装成单个 Completed 事件；真正流式 Provider 应覆盖。 消费者停止拉取时，适配器必须让中断传播到 HTTP 请求。
    */
  def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
    ZStream.fromZIO(complete(request)).map(ModelStreamEvent.Completed(_))

object ChatModel:
  /** 从 ZIO 环境取得 ChatModel 并执行非流式请求，方便业务代码使用服务模式。 */
  def complete(request: ChatRequest): ZIO[ChatModel, AgentError, ChatResponse] =
    ZIO.serviceWithZIO[ChatModel](_.complete(request))

  /** 从 ZIO 环境取得 ChatModel 并返回事件流。 */
  def stream(request: ChatRequest): ZStream[ChatModel, AgentError, ModelStreamEvent] =
    ZStream.serviceWithStream[ChatModel](_.stream(request))

/** 命名更明确的新 API；旧代码可以继续依赖 `ChatModel`。 */
trait ModelProvider extends ChatModel:
  /** 类型安全 Provider ID；`provider` 字符串由此统一派生。 */
  def providerId: ProviderId
  final override def provider: String = providerId.value

/** 对运行要求执行能力协商，任何降级都必须由调用方显式选择。 */
object CapabilityValidator:
  /** 在网络调用前验证请求与模型能力，避免 Provider 静默忽略工具或 tool choice。
    * @param request
    *   即将发送的请求
    * @param capabilities
    *   选中 Provider+Model 的能力
    */
  def validate(request: ChatRequest, capabilities: ModelCapabilities): IO[AgentError, Unit] =
    val provider = request.settings.provider.getOrElse("default")
    if request.tools.nonEmpty && !capabilities.toolCalls then
      ZIO.fail(AgentError.UnsupportedModelCapability(provider, "tool calling", "请求包含工具定义"))
    else if request.settings.toolChoice.isInstanceOf[ToolChoice.Specific] && !capabilities.specificToolChoice
    then ZIO.fail(AgentError.UnsupportedModelCapability(provider, "specific tool choice", "请求指定了工具"))
    else if hasImageUrl(request) && !capabilities.vision then
      ZIO.fail(AgentError.UnsupportedModelCapability(provider, "vision", "请求包含图片"))
    else ZIO.unit

  private def hasImageUrl(request: ChatRequest): Boolean =
    request.messages.exists(_.content.exists {
      case ContentPart.ImageUrl(_, _) => true
      case _                          => false
    })

final class RoutedChatModel private (
    val defaultProvider: String,
    val providers: Map[String, ChatModel]
) extends ChatModel:
  val provider: String                        = "router"
  override val descriptor: ProviderDescriptor =
    ProviderDescriptor(provider, "Routed model", "router", ModelCapabilities())

  /** 选择 Provider、执行能力校验，再转发完整请求。 */
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    select(request).flatMap(model =>
      model.capabilities(request.settings.model).flatMap(CapabilityValidator.validate(request, _)) *> model
        .complete(request)
    )

  /** 选择 Provider 并转发其原始语义事件流，不额外缓冲 token。 */
  override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
    ZStream.unwrap(
      select(request).flatMap(model =>
        model
          .capabilities(request.settings.model)
          .flatMap(CapabilityValidator.validate(request, _))
          .as(model.stream(request))
      )
    )

  /** 根据请求 provider 选择具体模型；未配置的名称以 ProviderNotFound 失败。 */
  private def select(request: ChatRequest): IO[AgentError, ChatModel] =
    val selected = request.settings.provider.getOrElse(defaultProvider)
    ZIO.fromOption(providers.get(selected)).orElseFail(AgentError.ProviderNotFound(selected))

object RoutedChatModel:
  /** 构建多 Provider 路由器并校验名称唯一性及默认 Provider。
    * @param defaultProvider
    *   请求未指定 provider 时使用的名称
    * @param models
    *   已初始化的模型实现
    */
  def make(defaultProvider: String, models: Iterable[ChatModel]): IO[AgentError, RoutedChatModel] =
    val entries    = models.iterator.map(model => model.provider -> model).toList
    val duplicates =
      entries.groupMapReduce(_._1)(_ => 1)(_ + _).collect { case (id, count) if count > 1 => id }
    if duplicates.nonEmpty then
      ZIO.fail(
        AgentError.InvalidConfiguration(
          s"Duplicate model providers: ${duplicates.toList.sorted.mkString(", ")}"
        )
      )
    else
      val byId = entries.toMap
      ZIO
        .fail(AgentError.InvalidConfiguration(s"Default provider not configured: $defaultProvider"))
        .unless(byId.contains(defaultProvider))
        .as(RoutedChatModel(defaultProvider, byId))

  /** 把 `make` 暴露为可组合 ZLayer，失败会在应用装配阶段显式出现。 */
  def layer(defaultProvider: String, models: Iterable[ChatModel]): ZLayer[Any, AgentError, ChatModel] =
    ZLayer.fromZIO(make(defaultProvider, models))

/** 可从文档或配置导出的 Provider/Model 能力矩阵。 */
final case class CapabilityMatrix(entries: Map[(String, String), ModelCapabilities]):
  /** 按 Provider 和模型精确查询能力；没有条目返回 None，不做隐式猜测。 */
  def get(provider: String, model: String): Option[ModelCapabilities] = entries.get(provider -> model)
