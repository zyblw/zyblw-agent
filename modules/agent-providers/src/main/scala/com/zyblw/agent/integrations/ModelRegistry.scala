package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.anthropic.*
import com.zyblw.agent.integrations.gemini.*
import com.zyblw.agent.integrations.openai.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.Client
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 模型连接使用的 wire 协议。
  *
  * 代理/聚合平台(OpenRouter、One API、各类中转站)几乎都暴露 OpenAI Chat Completions,因此它们是
  * `OpenAICompatible` + `compatibilityProfile = generic` 的一个连接,而不是一个新协议。
  */
enum ModelWireProtocol(val id: String):
  case OpenAICompatible   extends ModelWireProtocol("openai-compatible")
  case OpenAIResponses    extends ModelWireProtocol("openai-responses")
  case AnthropicMessages  extends ModelWireProtocol("anthropic-messages")
  case GeminiInteractions extends ModelWireProtocol("gemini-interactions")

object ModelWireProtocol:
  def fromId(id: String): Option[ModelWireProtocol] = values.find(_.id == id)

/** 一个由值(而不是环境变量)描述的模型连接。
  *
  * 宿主从自己的存储(数据库、管理台)读出连接后构造本类型;框架只负责把它装配成适配器。`apiKey` 是值本身,
  * 因此 `toString` 必须脱敏,而 `credentialReference` 只是可展示的低基数标签,例如 `db:connection/deepseek`。
  *
  * @param providerId
  *   路由名,与 `ModelSettings.provider` 同一命名空间
  * @param compatibilityProfile
  *   仅对 `OpenAICompatible` 有效;为空时按 providerId 推断,未知 id 回落到 `generic`
  * @param models
  *   已登记的模型及其能力;为空时目录只展示 `defaultModel`
  * @param extraHeaders
  *   非机密请求头,例如 OpenRouter 的 `HTTP-Referer`/`X-Title`
  */
final case class ModelConnectionSpec(
    providerId: String,
    displayName: String,
    protocol: ModelWireProtocol,
    baseUrl: String,
    apiKey: String,
    defaultModel: String,
    compatibilityProfile: Option[String] = None,
    models: Chunk[ProviderEndpointModel] = Chunk.empty,
    extraHeaders: Map[String, String] = Map.empty,
    requestTimeout: Duration = 90.seconds,
    credentialReference: String = CredentialReference.Unspecified
):
  require(providerId.matches("[a-z][a-z0-9_-]{0,31}"), "providerId 必须是 1..32 的小写标识")
  require(ProviderEndpointUrl.isAllowed(baseUrl), "baseUrl 必须是无 user-info/query/fragment 的 https URL 或本机 http URL")
  require(defaultModel.trim.nonEmpty, "defaultModel 不能为空")
  require(
    compatibilityProfile.forall(ProviderEndpoints.SupportedCompatibilityProfiles.contains),
    "compatibilityProfile 不受支持"
  )
  require(models.map(_.name).toSet.size == models.length, "同一连接的模型名必须唯一")
  require(CredentialReference.isValid(credentialReference), "凭据引用必须是低基数的 scheme:name 标签")

  override def toString: String =
    s"ModelConnectionSpec(providerId=$providerId, protocol=${protocol.id}, baseUrl=$baseUrl, apiKey=<redacted>, " +
      s"defaultModel=$defaultModel, models=${models.length})"

  /** 适配器缓存键。包含密钥,因此只在进程内比较,从不输出。 */
  private[integrations] def cacheKey: String =
    val material = Chunk(
      providerId,
      displayName,
      protocol.id,
      baseUrl,
      apiKey,
      defaultModel,
      compatibilityProfile.getOrElse(""),
      models.map(model => s"${model.name}=${model.capabilities}").mkString(","),
      extraHeaders.toList.sorted.mkString(","),
      requestTimeout.toMillis.toString
    ).mkString("\u0000")
    MessageDigest
      .getInstance("SHA-256")
      .digest(material.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"$byte%02x")
      .mkString

/** 某一版本的模型连接集合。
  *
  * @param revision
  *   单调递增的版本号;版本不变时注册表不重建
  * @param defaultProvider
  *   请求未指定 provider 时的路由;为空时取排序后的第一个连接
  */
final case class ModelRegistrySnapshot(
    revision: Long,
    defaultProvider: Option[String],
    connections: Chunk[ModelConnectionSpec]
):
  require(connections.map(_.providerId).toSet.size == connections.length, "连接 providerId 必须唯一")
  require(
    defaultProvider.forall(id => connections.exists(_.providerId == id)),
    "defaultProvider 必须是已登记的连接"
  )

object ModelRegistrySnapshot:
  val empty: ModelRegistrySnapshot = ModelRegistrySnapshot(0L, None, Chunk.empty)

/** 宿主提供的连接快照来源;实现应当廉价(读内存快照),由宿主自己负责刷新节奏。 */
trait ModelRegistrySource:
  def current: UIO[ModelRegistrySnapshot]

object ModelRegistrySource:
  def static(snapshot: ModelRegistrySnapshot): ModelRegistrySource =
    new ModelRegistrySource:
      val current: UIO[ModelRegistrySnapshot] = ZIO.succeed(snapshot)

  def fromRef(ref: Ref[ModelRegistrySnapshot]): ModelRegistrySource =
    new ModelRegistrySource:
      def current: UIO[ModelRegistrySnapshot] = ref.get

/** 把连接值装配成 `ProviderRegistration`。 */
object ChatAdapterFactory:
  def build(client: Client, spec: ModelConnectionSpec): IO[AgentError, ProviderRegistration] =
    ZIO
      .attempt(unsafeBuild(client, spec))
      .mapError {
        case error: IllegalArgumentException =>
          AgentError.InvalidConfiguration(s"${spec.providerId} 连接无效: ${error.getMessage.stripPrefix("requirement failed: ")}")
        case _ => AgentError.InvalidConfiguration(s"${spec.providerId} 连接无效")
      }

  private def unsafeBuild(client: Client, spec: ModelConnectionSpec): ProviderRegistration =
    spec.protocol match
      case ModelWireProtocol.OpenAICompatible =>
        val compatibility = ProviderEndpoints.compatibilityFor(
          ProviderEndpointDeclaration(
            providerId = spec.providerId,
            baseUrl = spec.baseUrl,
            apiKeyEnv = "UNUSED_DB_CREDENTIAL",
            defaultModel = spec.defaultModel,
            protocol = if spec.compatibilityProfile.contains("generic") then "relay" else "openai-compatible",
            compatibilityProfile = spec.compatibilityProfile
          )
        )
        val base       = compatibility.descriptor
        val descriptor = base.copy(displayName = spec.displayName, models = modelsOf(spec, base.capabilities))
        val config     = OpenAICompatibleConfig(
          baseUrl = spec.baseUrl,
          apiKey = spec.apiKey,
          defaultModel = spec.defaultModel,
          requestTimeout = spec.requestTimeout,
          compatibility = compatibility.copy(descriptor = descriptor),
          extraHeaders = spec.extraHeaders
        )
        ProviderRegistration.openAICompatible(OpenAICompatibleChatModel(client, config), config, spec.credentialReference)
      case ModelWireProtocol.OpenAIResponses =>
        val config = OpenAIResponsesConfig(
          baseUrl = spec.baseUrl,
          apiKey = spec.apiKey,
          defaultModel = spec.defaultModel,
          requestTimeout = spec.requestTimeout
        )
        registration(spec, OpenAIResponsesChatModel(client, config), config.apiKey)
      case ModelWireProtocol.AnthropicMessages =>
        val config = AnthropicMessagesConfig(
          baseUrl = spec.baseUrl,
          apiKey = spec.apiKey,
          defaultModel = spec.defaultModel,
          requestTimeout = spec.requestTimeout
        )
        registration(spec, AnthropicMessagesChatModel(client, config), config.apiKey)
      case ModelWireProtocol.GeminiInteractions =>
        val config = GeminiInteractionsConfig(
          baseUrl = spec.baseUrl,
          apiKey = spec.apiKey,
          defaultModel = spec.defaultModel,
          requestTimeout = spec.requestTimeout
        )
        registration(spec, GeminiInteractionsChatModel(client, config), config.apiKey)

  private def registration(spec: ModelConnectionSpec, inner: ChatModel, apiKey: String): ProviderRegistration =
    val base       = inner.descriptor
    val descriptor = base.copy(
      id = spec.providerId,
      displayName = spec.displayName,
      models = modelsOf(spec, base.capabilities)
    )
    ProviderRegistration(
      AliasedChatModel(spec.providerId, descriptor, inner),
      spec.defaultModel,
      spec.credentialReference,
      apiKey.trim.nonEmpty
    )

  private def modelsOf(spec: ModelConnectionSpec, base: ModelCapabilities): Map[String, ModelCapabilities] =
    spec.models.map(model => model.name -> model.capabilities.applyTo(base)).toMap

/** 以连接的路由名暴露一个固定 id 的原生适配器,使同一协议可以登记多个连接(例如两个 Anthropic 账号)。 */
final private[integrations] class AliasedChatModel(
    val provider: String,
    override val descriptor: ProviderDescriptor,
    inner: ChatModel
) extends ChatModel:
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] = inner.complete(request)

  override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] = inner.stream(request)

/** 一个版本的已装配状态。
  *
  * @param failures
  *   未能装配的连接及其不含密钥的原因;这些连接不进入路由
  */
final case class LiveModelRegistryState(
    revision: Long,
    registry: Option[ProviderRegistry],
    failures: Map[String, String]
)

/** 随 `ModelRegistrySource` 版本变化而重建路由的注册表。
  *
  * 每次调用先读快照版本;版本变化时只重建缓存键变化的适配器(换 Key、换 URL、改能力),其余连接复用原实例。
  * 进行中的调用持有旧版本的路由器,因此改配置不会打断已经发出的请求。
  */
final class LiveModelRegistry private (
    client: Client,
    source: ModelRegistrySource,
    state: Ref.Synchronized[(LiveModelRegistryState, Map[String, (String, ProviderRegistration)])]
):
  /** 当前版本的已装配状态。 */
  def current: UIO[LiveModelRegistryState] =
    source.current.flatMap { snapshot =>
      state.modifyZIO { case current @ (built, cache) =>
        if built.revision == snapshot.revision then ZIO.succeed(built -> current)
        else rebuild(snapshot, cache).map(next => next._1 -> next)
      }
    }

  /** 当前版本的已校验注册表;没有任何可用连接时为 None。 */
  def registry: UIO[Option[ProviderRegistry]] = current.map(_.registry)

  /** 顶层模型;每次调用路由到调用时刻的版本。 */
  val chatModel: ChatModel = new ChatModel:
    val provider: String                        = "router"
    override val descriptor: ProviderDescriptor =
      ProviderDescriptor(provider, "Live model registry", "router", ModelCapabilities())

    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      routed.flatMap(_.complete(request))

    override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
      ZStream.unwrap(routed.map(_.stream(request)))

  /** 用尚未保存的连接值执行一次探活;不进入注册表,也不影响当前路由。 */
  def probeSpec(
      spec: ModelConnectionSpec,
      model: Option[String],
      config: ModelProbeConfig = ModelProbeConfig()
  ): IO[AgentError, com.zyblw.agent.admin.ModelProbeResult] =
    ChatAdapterFactory
      .build(client, spec)
      .flatMap(registration =>
        ModelAdminLive.probeWith(
          registration.chatModel,
          spec.providerId,
          model.map(_.trim).filter(_.nonEmpty).getOrElse(spec.defaultModel),
          config
        )
      )

  private def routed: IO[AgentError, ChatModel] =
    registry.flatMap {
      case Some(value) => ZIO.succeed(value.chatModel)
      case None        =>
        ZIO.fail(AgentError.InvalidConfiguration("尚未配置可用的模型连接,请在管理台「模型」页添加连接并绑定角色"))
    }

  private def rebuild(
      snapshot: ModelRegistrySnapshot,
      cache: Map[String, (String, ProviderRegistration)]
  ): UIO[(LiveModelRegistryState, Map[String, (String, ProviderRegistration)])] =
    ZIO
      .foreach(snapshot.connections) { spec =>
        cache.get(spec.providerId) match
          case Some((key, registration)) if key == spec.cacheKey => ZIO.succeed(spec.providerId -> Right(spec.cacheKey -> registration))
          case _ => ChatAdapterFactory.build(client, spec).either.map(result => spec.providerId -> result.map(spec.cacheKey -> _))
      }
      .flatMap { results =>
        val built    = results.collect { case (id, Right(entry)) => id -> entry }.toMap
        val failures = results.collect { case (id, Left(error)) => id -> error.message }.toMap
        val ids      = built.keys.toList.sorted
        val default  = snapshot.defaultProvider.filter(built.contains).orElse(ids.headOption)
        default match
          case None =>
            ZIO.succeed(LiveModelRegistryState(snapshot.revision, None, failures) -> built)
          case Some(defaultId) =>
            val registrations = built.values.map(_._2)
            (for
              router   <- RoutedChatModel.make(defaultId, registrations.map(_.chatModel))
              registry <- ProviderRegistry.make(router, registrations)
            yield registry).either.map {
              case Right(registry) => LiveModelRegistryState(snapshot.revision, Some(registry), failures) -> built
              case Left(error)     =>
                LiveModelRegistryState(snapshot.revision, None, failures.updated("*", error.message)) -> built
            }
      }

object LiveModelRegistry:
  def make(client: Client, source: ModelRegistrySource): UIO[LiveModelRegistry] =
    Ref.Synchronized
      .make(LiveModelRegistryState(-1L, None, Map.empty) -> Map.empty[String, (String, ProviderRegistration)])
      .map(new LiveModelRegistry(client, source, _))

  val layer: URLayer[Client & ModelRegistrySource, LiveModelRegistry] =
    ZLayer.fromZIO(
      for
        client <- ZIO.service[Client]
        source <- ZIO.service[ModelRegistrySource]
        live   <- make(client, source)
      yield live
    )
