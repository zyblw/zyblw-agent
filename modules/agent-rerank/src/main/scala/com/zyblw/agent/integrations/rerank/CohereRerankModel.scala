package com.zyblw.agent.integrations.rerank

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.net.URI
import java.nio.charset.StandardCharsets
import scala.util.Try
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** Cohere v2 `/rerank` 的生产配置。
  *
  * @param baseUrl
  *   API 根地址；官方 SaaS 为 `https://api.cohere.com`，路径由 Adapter 固定追加 `/v2/rerank`
  * @param apiKey
  *   Bearer token，只能来自 Secret Manager 或环境变量
  * @param model
  *   精确模型版本；升级后必须建立新评测基线
  * @param maxCandidates
  *   单请求候选硬上限；官方建议不超过 1000，本配置也不允许更大值
  * @param maxQueryCodePoints
  *   发送到外部 Provider 的 query 最大 Unicode code point 数
  * @param maxDocumentCodePoints
  *   每份候选文档最大 Unicode code point 数；上层 ModelReranker 负责安全截断
  * @param maxTokensPerDocument
  *   Cohere `max_tokens_per_doc`，模型会在服务端按该 token 上限截断
  * @param requestTimeout
  *   包含连接、响应头和完整 Body 的单次尝试墙钟预算
  * @param maxResponseBytes
  *   响应 Body 最大字节数，防止异常网关返回无界 HTML/JSON
  * @param maxAttempts
  *   只对 408/409/425/429/5xx 和 transport error 的最大尝试次数
  * @param initialBackoff
  *   第一次重试前等待时间
  * @param maxBackoff
  *   指数退避上限
  * @param clientName
  *   可选 `X-Client-Name`，只接受低基数安全字符
  * @param allowInsecureHttp
  *   仅供本机 stub 测试；生产必须保持 false
  */
final case class CohereRerankConfig(
    baseUrl: String,
    apiKey: String,
    model: String = CohereRerankConfig.DefaultModel,
    maxCandidates: Int = 100,
    maxQueryCodePoints: Int = 16_000,
    maxDocumentCodePoints: Int = 32_000,
    maxTokensPerDocument: Int = 4096,
    requestTimeout: Duration = 10.seconds,
    maxResponseBytes: Int = 256 * 1024,
    maxAttempts: Int = 3,
    initialBackoff: Duration = 100.millis,
    maxBackoff: Duration = 2.seconds,
    clientName: Option[String] = Some("zyblw-agent"),
    allowInsecureHttp: Boolean = false
):
  require(baseUrl.trim.nonEmpty, "Cohere baseUrl 不能为空")
  require(apiKey.trim.nonEmpty, "Cohere apiKey 不能为空")
  require(model.trim.nonEmpty && model.length <= 200, "Cohere model 长度必须位于 1..200")
  require(maxCandidates > 0 && maxCandidates <= 1000, "Cohere maxCandidates 必须位于 1..1000")
  require(maxQueryCodePoints > 0 && maxDocumentCodePoints > 0, "Cohere query/document 上限必须为正数")
  require(maxTokensPerDocument > 0, "Cohere maxTokensPerDocument 必须为正数")
  require(requestTimeout > Duration.Zero && maxResponseBytes > 0, "Cohere timeout/response limit 必须为正数")
  require(maxAttempts > 0 && maxAttempts <= 10, "Cohere maxAttempts 必须位于 1..10")
  require(initialBackoff > Duration.Zero && maxBackoff >= initialBackoff, "Cohere backoff 配置无效")
  require(clientName.forall(_.matches("[A-Za-z0-9._-]{1,100}")), "Cohere clientName 只能包含安全低基数字符")

  /** 固定的 v2 rerank endpoint；业务不能通过 option 覆盖协议路径。 */
  val rerankUrl: String = s"${baseUrl.stripSuffix("/")}/v2/rerank"

  /** 在任何网络请求前验证 URL 信任边界。
    *
    * 禁止 user-info、query 和 fragment，避免凭据或路由参数被静默带入目标地址。HTTP 只允许显式测试配置。
    */
  private[rerank] def validateEndpoint: IO[RetrievalError, Unit] =
    ZIO
      .fromEither(Try(URI.create(rerankUrl)).toEither)
      .mapError(_ => AgentError.RetrievalFailed("Cohere rerank endpoint 不是合法 URI"))
      .flatMap { uri =>
        val schemeAllowed = uri.getScheme == "https" || (allowInsecureHttp && uri.getScheme == "http")
        val valid = uri.isAbsolute && schemeAllowed && uri.getHost != null && uri.getUserInfo == null &&
          uri.getQuery == null && uri.getFragment == null
        ZIO
          .fail(AgentError.RetrievalFailed("Cohere rerank endpoint 违反 HTTPS/host/query 安全边界"))
          .unless(valid)
          .unit
      }

  /** 配置日志摘要永远不包含 API Key。 */
  override def toString: String =
    s"CohereRerankConfig(baseUrl=$baseUrl, apiKey=<redacted>, model=$model, maxCandidates=$maxCandidates, " +
      s"maxAttempts=$maxAttempts, requestTimeout=$requestTimeout)"

object CohereRerankConfig:
  /** 本 loader 读取 API Key 的环境变量名。 */
  val ApiKeyVariable: String = "COHERE_API_KEY"

  /** 默认模型版本；升级模型必须重建评测基线，因此这里不跟随厂商"最新版"漂移。 */
  val DefaultModel: String = "rerank-v4.0-pro"

  /** Cohere v2 Rerank 的 ZIO Config 描述。
    *
    * 只暴露部署真正需要调的项：凭据、endpoint、模型版本，以及候选量/超时/重试这三项直接决定外发数据量、尾延迟和 费用的预算。`allowInsecureHttp`
    * 刻意**不**从配置读取——一个能通过环境变量把 Bearer token 发到明文 HTTP 的开关， 迟早会有人在生产上打开它来"临时排障"。
    *
    * API Key 使用 `Config.Secret`，加载失败的错误文本不会展开它。
    */
  val environmentConfig: Config[CohereRerankConfig] =
    (
      Config.string("COHERE_BASE_URL").withDefault("https://api.cohere.com") ++
        Config.secret(ApiKeyVariable) ++
        Config.string("COHERE_RERANK_MODEL").withDefault(DefaultModel) ++
        Config.int("COHERE_RERANK_MAX_CANDIDATES").withDefault(100) ++
        Config.duration("COHERE_RERANK_REQUEST_TIMEOUT").withDefault(10.seconds) ++
        Config.int("COHERE_RERANK_MAX_ATTEMPTS").withDefault(3)
    ).mapAttempt { case (baseUrl, apiKey, model, maxCandidates, timeout, maxAttempts) =>
      CohereRerankConfig(
        baseUrl = baseUrl,
        apiKey = apiKey.stringValue,
        model = model,
        maxCandidates = maxCandidates,
        requestTimeout = timeout,
        maxAttempts = maxAttempts
      )
    }

  /** 从当前 ZIO `ConfigProvider` 构造配置；缺失凭据在装配期失败，而不是等第一次检索降级。 */
  def fromEnvironment: IO[AgentError, CohereRerankConfig] =
    ZIO
      .config(environmentConfig)
      .mapError(error => AgentError.InvalidConfiguration(s"Cohere Rerank 配置无效: $error"))

/** Cohere v2 Rerank 的 ZIO HTTP Adapter。
  *
  * Adapter 只发送 query 和候选正文，不发送 tenantId、documentId、sourceUri 或业务 metadata。Cohere 响应使用数组 index， 本实现把 index
  * 映射回请求内无业务含义的 `candidate-N`。上层 `ModelReranker` 仍会再次验证候选子集和授权边界。
  *
  * @param client
  *   宿主 Scope 管理的共享 ZIO HTTP Client
  * @param config
  *   endpoint、凭据、模型、数据外发上限和可靠性策略
  */
final class CohereRerankModel(client: Client, config: CohereRerankConfig) extends RerankerModel:
  val descriptor: RerankerDescriptor = RerankerDescriptor(
    provider = "cohere-rerank-v2",
    model = config.model,
    maxCandidates = config.maxCandidates,
    maxQueryCodePoints = config.maxQueryCodePoints,
    maxDocumentCodePoints = config.maxDocumentCodePoints
  )

  /** 校验调用契约、编码一次稳定 JSON，并仅对明确瞬时失败执行有界指数退避。
    *
    * 重试复用完全相同的 query、候选顺序和 topN。Rerank 是只读计算，不会制造业务副作用；Fiber interruption 不会进入 catchAll 错误通道，因此客户端取消会立即关闭
    * HTTP Scope，而不是继续后台重试。
    */
  def score(request: RerankRequest): IO[RetrievalError, RerankResponse] =
    for
      _ <- config.validateEndpoint
      _ <- validateRequest(request)
      payload = encodeRequest(request).toJson
      result <- attempt(request, payload, config.maxAttempts, config.initialBackoff)
    yield result

  /** 递归实现有界 retry，使 retryable 分类、次数和退避上限在中文注释中保持可读。 */
  private def attempt(
      request: RerankRequest,
      payload: String,
      remaining: Int,
      delay: Duration
  ): IO[RetrievalError, RerankResponse] =
    callOnce(request, payload).catchAll { error =>
      if error.retryable && remaining > 1 then
        ZIO.sleep(delay) *> attempt(request, payload, remaining - 1, (delay * 2).min(config.maxBackoff))
      else ZIO.fail(error)
    }

  /** 执行一次完整 HTTP 尝试。
    *
    * 使用 streaming Client + `ZIO.scoped`，让超时或调用 Fiber 中断时连接 Body 立即关闭；Body 只读取到 `max+1` 字节，
    * 超限不分配无界字符串。错误响应正文会排空但不会进入错误、日志或 trace。
    */
  private def callOnce(request: RerankRequest, payload: String): IO[RetrievalError, RerankResponse] =
    val httpRequest = Request
      .post(config.rerankUrl, Body.fromString(payload))
      .addHeader(Header.Authorization.Bearer(config.apiKey))
      .addHeader(Header.ContentType(MediaType.application.json))
    val withClientName =
      config.clientName.fold(httpRequest)(value => httpRequest.addHeader("X-Client-Name", value))
    client
      .stream(withClientName) { response =>
        ZStream.fromZIO(readBounded(response).map(body => response.status -> body))
      }
      .runHead
      .someOrFail(AgentError.RetrievalFailed("Cohere rerank 响应流为空", retryable = true))
      .mapError(mapTransportError)
      .timeoutFail(timeoutError)(config.requestTimeout)
      .flatMap { case (status, body) =>
        if status.isSuccess then decodeResponse(request, body)
        else ZIO.fail(httpError(status.code))
      }

  /** 读取有界 UTF-8 JSON；非法 UTF-8 最终会在 JSON 解码处作为协议失败，不会被用于诊断正文。 */
  private def readBounded(response: Response): IO[RetrievalError, String] =
    response.body.asStream
      .take(config.maxResponseBytes.toLong + 1L)
      .runCollect
      .mapError(mapTransportError)
      .flatMap { bytes =>
        if bytes.length > config.maxResponseBytes then
          ZIO.fail(AgentError.RetrievalFailed("Cohere rerank 响应超过配置字节上限"))
        else ZIO.succeed(String(bytes.toArray, StandardCharsets.UTF_8))
      }

  /** 在网络前拒绝空输入、超量、重复临时 ID 和不合法原始排名/分数。 */
  private def validateRequest(request: RerankRequest): IO[RetrievalError, Unit] =
    val ids   = request.candidates.map(_.candidateId)
    val valid = request.query.trim.nonEmpty && request.candidates.nonEmpty &&
      request.candidates.length <= config.maxCandidates && request.topN > 0 &&
      request.topN <= request.candidates.length && ids.distinct.length == ids.length &&
      request.candidates.forall(candidate =>
        candidate.candidateId.matches("candidate-[0-9]+") && candidate.text.trim.nonEmpty &&
          candidate.originalRank > 0 && java.lang.Double.isFinite(candidate.originalScore)
      )
    ZIO.fail(AgentError.RetrievalFailed("Cohere rerank 请求违反 query/candidate/topN 契约")).unless(valid).unit

  /** Cohere wire 不接受业务 ID；documents 数组的位置就是唯一映射依据。 */
  private def encodeRequest(request: RerankRequest): Json.Obj = Json.Obj(
    Chunk(
      "model"              -> Json.Str(config.model),
      "query"              -> Json.Str(request.query),
      "documents"          -> Json.Arr(request.candidates.map(candidate => Json.Str(candidate.text))),
      "top_n"              -> Json.Num(request.topN),
      "max_tokens_per_doc" -> Json.Num(config.maxTokensPerDocument)
    )
  )

  /** 解码排序结果、请求 ID 和 billed search units；不把计费单元伪装成 token usage。 */
  private def decodeResponse(request: RerankRequest, body: String): IO[RetrievalError, RerankResponse] =
    for
      json <- ZIO
        .fromEither(body.fromJson[Json])
        .mapError(_ => AgentError.RetrievalFailed("Cohere rerank 响应不是合法 JSON"))
      results <- ZIO
        .fromOption(arrayField(json, "results"))
        .orElseFail(
          AgentError.RetrievalFailed("Cohere rerank 响应缺少 results 数组")
        )
      decoded <- ZIO.foreach(results)(decodeResult(request, _))
      indices = decoded.map(_._1)
      _ <- ZIO
        .fail(AgentError.RetrievalFailed("Cohere rerank 响应包含重复或超量 index"))
        .unless(indices.distinct.length == indices.length && decoded.length <= request.topN)
      billing <- decodeBilling(json)
      requestId = stringField(json, "id").filter(_.matches("[A-Za-z0-9._:-]{1,200}"))
      scores    = decoded.map { case (index, relevance) =>
        RerankScore(request.candidates(index).candidateId, relevance)
      }
    yield RerankResponse(scores, usage = None, providerRequestId = requestId, billing = billing)

  /** 单个结果的 index 必须落在请求数组内，相关度必须是有限 `[0,1]` 数。 */
  private def decodeResult(request: RerankRequest, json: Json): IO[RetrievalError, (Int, Double)] =
    (intField(json, "index"), doubleField(json, "relevance_score")) match
      case (Some(index), Some(score))
          if index >= 0 && index < request.candidates.length && java.lang.Double.isFinite(score) &&
            score >= 0.0 && score <= 1.0 =>
        ZIO.succeed(index -> score)
      case _ => ZIO.fail(AgentError.RetrievalFailed("Cohere rerank result 的 index/relevance_score 无效"))

  /** billed_units 存在时必须包含非负整数 search_units；缺失则诚实保持 None。 */
  private def decodeBilling(json: Json): IO[RetrievalError, Option[RerankBilling]] =
    field(json, "meta").flatMap(meta => field(meta, "billed_units")) match
      case None        => ZIO.none
      case Some(units) =>
        longField(units, "search_units") match
          case Some(value) if value >= 0L => ZIO.some(RerankBilling(value))
          case _ => ZIO.fail(AgentError.RetrievalFailed("Cohere rerank billed_units.search_units 无效"))

  /** Transport/TLS/Body 错误标为可重试，但不复制底层异常 message。 */
  private def mapTransportError(error: Throwable): RetrievalError = error match
    case known: RetrievalError => known
    case other                 =>
      AgentError.RetrievalFailed(
        s"Cohere rerank transport failure: ${other.getClass.getSimpleName}",
        retryable = true
      )

  /** 只根据 HTTP 状态分类；响应正文可能回显输入或网关信息，绝不进入错误。 */
  private def httpError(status: Int): RetrievalError = AgentError.RetrievalFailed(
    s"Cohere rerank HTTP $status",
    retryable = status == 408 || status == 409 || status == 425 || status == 429 || status >= 500
  )

  /** 总尝试超时保持可重试，交由上层 FailOpen/FailClosed 决定质量降级。 */
  private def timeoutError: RetrievalError =
    AgentError.RetrievalFailed("Cohere rerank request timed out", retryable = true)

  /** 安全读取 JSON object 字段。 */
  private def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.find(_._1 == name).map(_._2)
    case _                => None

  /** 安全读取字符串字段。 */
  private def stringField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(value) => value }

  /** 安全读取数组字段。 */
  private def arrayField(json: Json, name: String): Option[Chunk[Json]] =
    field(json, name).collect { case Json.Arr(values) => values }

  /** 只接受 JSON 精确 Int，拒绝 1.5 或溢出。 */
  private def intField(json: Json, name: String): Option[Int] =
    field(json, name).collect { case Json.Num(value) => Try(value.intValueExact()).toOption }.flatten

  /** 相关度允许十进制/整数 JSON number，统一读取为 Double 后再验证有限范围。 */
  private def doubleField(json: Json, name: String): Option[Double] =
    field(json, name).collect { case Json.Num(value) => value.doubleValue }

  /** 只接受 JSON 精确 Long 计费单元。 */
  private def longField(json: Json, name: String): Option[Long] =
    field(json, name).collect { case Json.Num(value) => Try(value.longValueExact()).toOption }.flatten

object CohereRerankModel:
  /** 已有配置时只要求宿主提供共享 ZIO HTTP Client。 */
  def configured(config: CohereRerankConfig): URLayer[Client, RerankerModel] =
    ZLayer.fromFunction((client: Client) => CohereRerankModel(client, config): RerankerModel)
