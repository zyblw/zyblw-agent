package com.zyblw.agent.integrations

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import zio.*

/** 连通性探活的预算。
  *
  * 探活是管理台上一个可以被反复点击的按钮,因此它的成本必须由框架而不是操作者的手速决定:输出上限压到几个 token, 并且有独立于 Adapter 自身超时的外层硬超时——一个卡在 TCP 建连的
  * Provider 不应该让管理台请求一直挂着。
  *
  * @param timeout
  *   单次探活的端到端硬超时;应小于宿主 HTTP 层的请求超时,否则运维只会看到网关超时而拿不到分类码
  * @param maxOutputTokens
  *   发送给 Provider 的输出上限;探活不读正文,因此只需要大于零
  */
final case class ModelProbeConfig(
    timeout: Duration = 20.seconds,
    maxOutputTokens: Int = 16
):
  require(timeout > Duration.Zero, "探活超时必须大于零")
  require(maxOutputTokens > 0, "探活输出上限必须为正数")

/** `ModelAdminService` 的 Provider 侧实现。
  *
  * 目录读取与探活留在 `agent-providers`:只有这里同时认识路由拓扑、各适配器的能力声明和真实 HTTP 调用路径。 `agent-core` 因此不会为了一个运维界面反向依赖 ZIO
  * HTTP,HTTP 层也不会因为挂载管理路由而被迫引入全部适配器。
  */
final class ModelAdminLive private (
    registry: ProviderRegistry,
    catalogService: ModelCatalog,
    policies: ModelPolicySource,
    config: ModelProbeConfig
) extends ModelAdminService:
  import ModelAdminLive.*

  /** 组合目录快照与当前生效工作点。
    *
    * `priceCurrency` 与 `pricedOptionCount` 从 `options` 自身派生,而不是再查一次价格表:视图里展示的货币必须和视图里
    * 列出的单价出自同一次投影,否则一次装配错误会表现为"每条单价是美元、页头却写着另一种货币"这种无法排查的现象。
    */
  def catalog: IO[AgentError, ModelCatalogView] =
    for
      options   <- catalogService.options
      default   <- catalogService.defaultProvider
      embedding <- catalogService.embedding
      // 工作点在一次请求内只读一次,避免生效 Provider 与生效模型来自两个不同版本的运行时覆盖。
      policy = policies.current()
      priced = options.flatMap(_.price)
    yield ModelCatalogView(
      options = options,
      defaultProvider = default,
      effectiveProvider = policy.provider,
      effectiveModel = policy.model,
      embedding = embedding,
      priceCurrency = priced.headOption.map(_.currency),
      pricedOptionCount = priced.length
    )

  /** 对一个已注册组合执行一次最小连通性调用。
    *
    * 探活刻意**不叠加运行时模型覆盖**:它要回答的是"我切到这个组合会不会通",而不是"当前生效组合通不通"。用生效工作点 改写目标会让运维在切换前无法验证目标本身。
    */
  def probe(request: ModelProbeRequest): IO[AgentError, ModelProbeResult] =
    val provider = request.provider.trim
    val model    = request.model.map(_.trim).filter(_.nonEmpty).orElse(registry.defaultModelOf(provider))
    for
      options <- catalogService.options
      // 未注册组合在任何网络请求之前失败。放行会让一次拼写错误变成一次真实计费调用,而它的失败原因还会被 Provider
      // 的错误信息掩盖成"模型不存在",看不出是本地目录里就没有。
      providerRegistered = options.exists(_.provider == provider)
      modelRegistered = model.exists(name => options.exists(o => o.provider == provider && o.model == name))
      result <- (providerRegistered, model, modelRegistered) match
        case (true, Some(name), true)  => run(provider, name)
        case (true, Some(name), false) => ZIO.succeed(rejected(provider, name, ModelNotFound))
        case (true, None, _)           => ZIO.succeed(rejected(provider, UnresolvedModel, ModelNotFound))
        case (false, Some(name), _)    => ZIO.succeed(rejected(provider, name, ProviderNotFound))
        case (false, None, _)          => ZIO.succeed(rejected(provider, UnresolvedModel, ProviderNotFound))
    yield result

  /** 执行一次计时调用并只保留低敏观测值。 */
  private def run(provider: String, model: String): UIO[ModelProbeResult] =
    for
      started <- Clock.nanoTime
      // `.timeout` 会中断底层调用,因此超时不会留下一个继续计费的后台请求。
      exit     <- registry.chatModel.complete(probeRequest(provider, model)).timeout(config.timeout).exit
      finished <- Clock.nanoTime
      latency = ((finished - started) / 1_000_000L).max(0L)
      result <- exit match
        // 调用方取消不能被伪装成一次探活失败,否则管理台断开连接后仍会显示一个凭空产生的故障。
        case Exit.Failure(cause) if cause.isInterrupted => ZIO.interrupt
        case Exit.Success(Some(response))               =>
          ZIO.succeed(
            ModelProbeResult(
              provider = provider,
              model = model,
              succeeded = true,
              latencyMillis = latency,
              inputTokens = response.usage.inputTokens.max(0L),
              outputTokens = response.usage.outputTokens.max(0L),
              failureCode = None
            )
          )
        case Exit.Success(None)  => ZIO.succeed(failed(provider, model, latency, Timeout))
        case Exit.Failure(cause) => ZIO.succeed(failed(provider, model, latency, failureCode(cause)))
    yield result

  /** 固定探活请求。
    *
    * 提示词是一个不含任何业务数据的 ASCII 常量,温度为零,不提供工具。`toolChoice = None` 是必需的而不是省钱:声明了 工具调用能力的 Provider 在没有工具定义时收到 `Auto`
    * 仍然合法,但显式关闭可以让探活对"能力协商"这一步的结论只 取决于 Provider 与模型本身。
    */
  private def probeRequest(provider: String, model: String): ChatRequest = ChatRequest(
    messages = Chunk(AgentMessage.user(ProbePrompt)),
    settings = ModelSettings(
      provider = Some(provider),
      model = Some(model),
      temperature = Some(0.0),
      maxOutputTokens = Some(config.maxOutputTokens),
      toolChoice = ToolChoice.None,
      metadata = Map("purpose" -> ProbePurpose)
    )
  )

object ModelAdminLive:
  /** 目标组合不在目录中的稳定失败码。 */
  val ProviderNotFound: String = "provider-not-found"

  /** Provider 已注册，但目标模型不在其目录中。 */
  val ModelNotFound: String = "model-not-found"

  /** 凭据被 Provider 拒绝。 */
  val Unauthorized: String = "unauthorized"

  /** 探活超过配置的硬超时。 */
  val Timeout: String = "timeout"

  /** 被 Provider 限流。 */
  val RateLimited: String = "rate-limited"

  /** 请求与目标模型的能力不匹配。 */
  val Capability: String = "capability"

  /** 无法归入以上任何一类。 */
  val Unexpected: String = "unexpected"

  /** 固定探活提示词;不含业务数据,也不要求模型回声任何标记(探活不读正文)。 */
  private val ProbePrompt: String = "ping"

  /** 探活请求的稳定用途标签,便于在 Provider 侧账单与本地遥测中把探活流量与业务流量分开。 */
  private val ProbePurpose: String = "model-probe-v1"

  /** 请求未给模型名、该 Provider 也没有声明默认模型时的占位模型名。 */
  private val UnresolvedModel: String = ""

  /** 构造服务。 */
  def make(
      registry: ProviderRegistry,
      catalog: ModelCatalog,
      policies: ModelPolicySource,
      config: ModelProbeConfig = ModelProbeConfig()
  ): ModelAdminService = new ModelAdminLive(registry, catalog, policies, config)

  /** 标准装配。
    *
    * `ModelPolicySource` 通常来自 `RuntimeSettingsService.modelPolicySource`,而后者又依赖 `ModelCatalog`。依赖方向 因此必须是 目录
    * → 运行时设置 → 本服务:目录自己不读工作点,才不会在装配图里形成环。
    */
  def layer(
      config: ModelProbeConfig = ModelProbeConfig()
  ): URLayer[ProviderRegistry & ModelCatalog & ModelPolicySource, ModelAdminService] =
    ZLayer.fromFunction((registry: ProviderRegistry, catalog: ModelCatalog, policies: ModelPolicySource) =>
      make(registry, catalog, policies, config)
    )

  /** 目录未注册目标组合时的结果;`latencyMillis` 为零表示未发生任何网络往返。 */
  private def rejected(provider: String, model: String, code: String): ModelProbeResult =
    failed(provider, model, 0L, code)

  private def failed(
      provider: String,
      model: String,
      latencyMillis: Long,
      code: String
  ): ModelProbeResult = ModelProbeResult(
    provider = provider,
    model = model,
    succeeded = false,
    latencyMillis = latencyMillis,
    inputTokens = 0L,
    outputTokens = 0L,
    failureCode = Some(code)
  )

  /** 把失败归纳为稳定分类码。
    *
    * 只读取错误的**类型与分类**,绝不读取 `message`。内置 Chat Adapter 已用 `ModelHttpFailure` 把 HTTP 状态归入
    * Authentication/Authorization/RateLimit 等稳定分类且不保留正文；这里仍不能假设自定义 `ChatModel` 也遵守同一
    * 脱敏规则。分类码保持低基数,可以直接进入指标标签而不会把时序库打爆。
    */
  private[integrations] def failureCode(cause: Cause[AgentError]): String =
    cause.failureOption match
      case Some(_: AgentError.ProviderNotFound)           => ProviderNotFound
      case Some(_: AgentError.UnsupportedModelCapability) => Capability
      case Some(error)                                    => categoryCode(error.category)
      // Defect 与非 typed 失败没有可信分类;归入 unexpected 而不是猜一个更具体的码。
      case None => Unexpected

  private def categoryCode(category: ErrorCategory): String = category match
    case ErrorCategory.Authentication | ErrorCategory.Authorization => Unauthorized
    case ErrorCategory.RateLimit                                    => RateLimited
    case ErrorCategory.Timeout                                      => Timeout
    case ErrorCategory.Configuration                                => "configuration"
    case ErrorCategory.Validation                                   => "invalid-request"
    case ErrorCategory.Unavailable                                  => "unavailable"
    case ErrorCategory.ContextLimit                                 => "context-limit"
    case ErrorCategory.Safety                                       => "safety"
    case ErrorCategory.Persistence                                  => "persistence"
    case ErrorCategory.Conflict                                     => "conflict"
    case ErrorCategory.Cancelled                                    => "cancelled"
    case ErrorCategory.Unexpected                                   => Unexpected
