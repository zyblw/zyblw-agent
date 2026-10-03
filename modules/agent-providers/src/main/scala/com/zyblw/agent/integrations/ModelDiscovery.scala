package com.zyblw.agent.integrations

import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.openai.OpenAICompatibleConfig
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import scala.util.Try

/** 发现到的模型用途。 */
enum DiscoveredModelKind(val id: String):
  case Chat      extends DiscoveredModelKind("chat")
  case Embedding extends DiscoveredModelKind("embedding")
  case Rerank    extends DiscoveredModelKind("rerank")

/** 从上游目录归一化得到的模型候选。
  *
  * 候选只是"可以登记"的建议:能力与价格来自上游的自我声明,登记前由运维确认。价格单位统一为**每百万 token 的 USD**,
  * 与 OpenRouter 原始的每 token 单价相比少了一次在前端做浮点乘法的机会。
  */
final case class DiscoveredModel(
    id: String,
    name: String,
    kind: DiscoveredModelKind,
    contextLength: Option[Long] = None,
    maxOutputTokens: Option[Long] = None,
    vision: Boolean = false,
    toolCalls: Boolean = false,
    reasoning: Boolean = false,
    inputPerMillionUsd: Option[BigDecimal] = None,
    outputPerMillionUsd: Option[BigDecimal] = None,
    cachedInputPerMillionUsd: Option[BigDecimal] = None
):
  /** 投影为连接登记使用的能力声明。 */
  def capabilities: ProviderEndpointCapabilities =
    ProviderEndpointCapabilities(
      toolCalls = toolCalls,
      streaming = true,
      vision = vision,
      thinking = reasoning,
      reasoningTokens = reasoning,
      maxInputTokens = contextLength.filter(_ > 0L),
      maxOutputTokens = maxOutputTokens.filter(_ > 0L)
    )

/** 上游模型目录的读取与归一化。
  *
  * 两种来源:OpenRouter 的 `/models`(带能力与价格的富目录),以及任意 OpenAI-compatible 网关的 `/models`(只有 id)。
  * 解析对单条坏数据宽容(跳过),对整体结构错误严格(失败),因为上游目录里偶有字段缺失的条目,而它们不应该让整张目录不可用。
  */
object ModelDiscovery:
  val OpenRouterBaseUrl: String = "https://openrouter.ai/api/v1"

  private val MaxBodyBytes: Int = 16 * 1024 * 1024

  /** 读取 OpenRouter 公共目录;Key 可选(目录本身公开)。 */
  def openRouter(
      client: Client,
      apiKey: Option[String] = None,
      baseUrl: String = OpenRouterBaseUrl,
      timeout: Duration = 20.seconds
  ): IO[AgentError, Chunk[DiscoveredModel]] =
    fetch(client, s"${baseUrl.stripSuffix("/")}/models", apiKey, Map.empty, timeout).flatMap(body =>
      ZIO.fromEither(parseOpenRouter(body)).mapError(AgentError.InvalidConfiguration(_))
    )

  /** 读取 OpenAI-compatible 网关的 `/models`。 */
  def openAICompatible(
      client: Client,
      baseUrl: String,
      apiKey: String,
      extraHeaders: Map[String, String] = Map.empty,
      timeout: Duration = 20.seconds
  ): IO[AgentError, Chunk[DiscoveredModel]] =
    if !ProviderEndpointUrl.isAllowed(baseUrl) then ZIO.fail(AgentError.InvalidConfiguration("baseUrl 不被允许"))
    else
      fetch(client, s"${baseUrl.stripSuffix("/")}/models", Some(apiKey), extraHeaders, timeout).flatMap(body =>
        ZIO.fromEither(parseOpenAIModels(body)).mapError(AgentError.InvalidConfiguration(_))
      )

  /** 解析 OpenRouter `/models` 响应。 */
  def parseOpenRouter(body: String): Either[String, Chunk[DiscoveredModel]] =
    data(body).map(_.flatMap(openRouterModel).sortBy(_.id))

  /** 解析 OpenAI `/models` 响应;只有 id 可信,用途按命名惯例推断。 */
  def parseOpenAIModels(body: String): Either[String, Chunk[DiscoveredModel]] =
    data(body).map(
      _.flatMap(entry => string(entry, "id"))
        .distinct
        .map(id => DiscoveredModel(id = id, name = id, kind = kindByName(id)))
        .sortBy(_.id)
    )

  private def data(body: String): Either[String, Chunk[Json.Obj]] =
    body.fromJson[Json] match
      case Left(_)     => Left("模型目录不是合法 JSON")
      case Right(json) =>
        json match
          case obj: Json.Obj =>
            obj.get("data") match
              case Some(Json.Arr(items)) => Right(items.collect { case item: Json.Obj => item })
              case _                     => Left("模型目录缺少 data 数组")
          case Json.Arr(items) => Right(items.collect { case item: Json.Obj => item })
          case _               => Left("模型目录结构无法识别")

  private def openRouterModel(entry: Json.Obj): Option[DiscoveredModel] =
    string(entry, "id").map { id =>
      val architecture = obj(entry, "architecture")
      val inputs       = strings(architecture, "input_modalities")
      val outputs      = strings(architecture, "output_modalities")
      val parameters   = strings(Some(entry), "supported_parameters")
      val pricing      = obj(entry, "pricing")
      val kind         =
        if outputs.exists(_.startsWith("embedding")) then DiscoveredModelKind.Embedding else kindByName(id)
      DiscoveredModel(
        id = id,
        name = string(entry, "name").getOrElse(id),
        kind = kind,
        contextLength = long(Some(entry), "context_length"),
        maxOutputTokens = long(obj(entry, "top_provider"), "max_completion_tokens"),
        vision = inputs.contains("image"),
        toolCalls = parameters.contains("tools"),
        reasoning = parameters.contains("reasoning") || parameters.contains("include_reasoning"),
        inputPerMillionUsd = perMillion(pricing, "prompt"),
        outputPerMillionUsd = perMillion(pricing, "completion"),
        cachedInputPerMillionUsd = perMillion(pricing, "input_cache_read")
      )
    }

  private def kindByName(id: String): DiscoveredModelKind =
    val lower = id.toLowerCase
    if lower.contains("rerank") then DiscoveredModelKind.Rerank
    else if lower.contains("embed") then DiscoveredModelKind.Embedding
    else DiscoveredModelKind.Chat

  /** 每 token 单价 → 每百万 token;负数(OpenRouter 用 -1 表示"可变价格")与非数字视为未知。 */
  private def perMillion(pricing: Option[Json.Obj], field: String): Option[BigDecimal] =
    pricing
      .flatMap(_.get(field))
      .flatMap {
        case Json.Str(value) => Try(BigDecimal(value.trim)).toOption
        case Json.Num(value) => Some(BigDecimal(value))
        case _               => None
      }
      .filter(_ >= 0)
      .map(value => (value * 1_000_000).bigDecimal.stripTrailingZeros)
      .map(BigDecimal(_))

  private def obj(entry: Json.Obj, field: String): Option[Json.Obj] =
    entry.get(field).collect { case value: Json.Obj => value }

  private def string(entry: Json.Obj, field: String): Option[String] =
    entry.get(field).collect { case Json.Str(value) if value.trim.nonEmpty => value.trim }

  private def strings(entry: Option[Json.Obj], field: String): Set[String] =
    entry.flatMap(_.get(field)).collect { case Json.Arr(items) => items.collect { case Json.Str(v) => v }.toSet }
      .getOrElse(Set.empty)

  private def long(entry: Option[Json.Obj], field: String): Option[Long] =
    entry.flatMap(_.get(field)).collect { case Json.Num(value) => value.longValue }

  private def fetch(
      client: Client,
      url: String,
      apiKey: Option[String],
      extraHeaders: Map[String, String],
      timeout: Duration
  ): IO[AgentError, String] =
    val base = Request.get(url).addHeader(Header.Accept(MediaType.application.json))
    val auth = apiKey.filter(_.trim.nonEmpty).fold(base)(key => base.addHeader(Header.Authorization.Bearer(key)))
    val request = extraHeaders
      .filterNot((name, _) => OpenAICompatibleConfig.ReservedHeaders.contains(name.toLowerCase))
      .foldLeft(auth) { case (req, (name, value)) => req.addHeader(name, value) }
    client
      .batched(request)
      .flatMap { response =>
        if response.status.code == 401 || response.status.code == 403 then
          ZIO.fail(AgentError.ModelFailure("discovery", "上游拒绝了凭据", retryable = false))
        else if !response.status.isSuccess then
          ZIO.fail(AgentError.ModelFailure("discovery", s"上游目录返回 HTTP ${response.status.code}", retryable = true))
        else response.body.asString
      }
      .timeoutFail(AgentError.ModelFailure("discovery", "读取模型目录超时", retryable = true))(timeout)
      .mapError {
        case error: AgentError => error
        case _                 => AgentError.ModelFailure("discovery", "无法连接上游模型目录", retryable = true)
      }
      .filterOrFail(_.length <= MaxBodyBytes)(AgentError.InvalidConfiguration("模型目录过大"))
