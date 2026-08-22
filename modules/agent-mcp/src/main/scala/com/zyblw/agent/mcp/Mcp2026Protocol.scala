package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import java.net.{InetAddress, URI}
import zio.Chunk
import zio.json.ast.Json

/** MCP `2026-07-28` 无状态核心：每请求 `_meta`、`server/discover`、`Mcp-Method`/`Mcp-Name`。
  *
  * 这是独立于 `DefaultMcpClient` 的专用契约。2025 客户端协商到本版本必须继续 fail-closed，不能用 initialize/session 假装会讲新 transport。权威变更见
  * https://modelcontextprotocol.io/specification/2026-07-28/changelog
  */
object Mcp2026Protocol:
  val Version: McpProtocolVersion = McpProtocolVersion.Known2026_07_28

  val ProtocolVersionKey: String    = "io.modelcontextprotocol/protocolVersion"
  val ClientCapabilitiesKey: String = "io.modelcontextprotocol/clientCapabilities"
  val ClientInfoKey: String         = "io.modelcontextprotocol/clientInfo"
  val ServerInfoKey: String         = "io.modelcontextprotocol/serverInfo"

  val HeaderMethod: String           = "Mcp-Method"
  val HeaderName: String             = "Mcp-Name"
  val ForbiddenSessionHeader: String = "Mcp-Session-Id"
  val ForbiddenLastEventId: String   = "Last-Event-ID"

  val UnsupportedProtocolVersion: Int = -32022
  val HeaderMismatch: Int             = -32020

  val LegacyMethods: Set[String] = Set(
    "initialize",
    "notifications/initialized",
    "ping",
    "logging/setLevel",
    "resources/subscribe",
    "resources/unsubscribe"
  )

  enum ResultType:
    case Complete, InputRequired

  final case class DiscoverResult(
      protocolVersion: String,
      serverName: String,
      serverVersion: String,
      capabilities: Json.Obj
  )

  final case class CacheHint(ttlMs: Long, cacheScope: String)

  val AllowedInputMethods: Set[String] =
    Set("elicitation/create", "sampling/createMessage", "roots/list")

  val MrtrMethods: Set[String] = Set("tools/call", "prompts/get", "resources/read")

  val ListenTypes: Set[String] =
    Set("toolsListChanged", "promptsListChanged", "resourcesListChanged", "resourceSubscriptions")

  val SubscriptionIdKey: String = "io.modelcontextprotocol/subscriptionId"

  final case class InputRequest(id: String, method: String, params: Json.Obj)

  final case class InputRequired(requests: List[InputRequest], requestState: Option[String])

  final case class ListenAck(accepted: Set[String], subscriptionId: Option[String])

  def requestMeta(clientInfo: McpImplementation, capabilities: Json.Obj = Json.Obj()): Json.Obj =
    Json.Obj(
      ProtocolVersionKey    -> Json.Str(Version.value),
      ClientCapabilitiesKey -> capabilities,
      ClientInfoKey         -> Json.Obj(
        "name"    -> Json.Str(clientInfo.name),
        "version" -> Json.Str(clientInfo.version)
      )
    )

  def withMeta(params: Json.Obj, meta: Json.Obj): Json.Obj =
    Json.Obj(params.fields :+ ("_meta" -> meta))

  def httpHeaders(method: String, name: Option[String] = None): Map[String, String] =
    Map(HeaderMethod -> method) ++ name.filter(_.nonEmpty).map(HeaderName -> _)

  /** `Mcp-Name` 来自工具/Prompt 名或资源 URI，而不是 HTTP 路径。 */
  def nameFromParams(method: String, params: Json.Obj): Option[String] =
    val name = McpJson.optionalString(params, "name", method).toOption.flatten.filter(_.nonEmpty)
    val uri  = McpJson.optionalString(params, "uri", method).toOption.flatten.filter(_.nonEmpty)
    method match
      case "resources/read" | "resources/templates/list" => uri.orElse(name)
      case _                                             => name

  def rejectLegacyMethod(method: String): Either[AgentError, Unit] =
    Either.cond(
      !LegacyMethods.contains(method),
      (),
      McpJson.protocolError(
        method,
        s"MCP 2026-07-28 removed $method; use server/discover and per-request _meta",
        Some("legacy_stateful_method")
      )
    )

  def rejectSessionHeaders(headers: Map[String, String]): Either[AgentError, Unit] =
    val normalized = headers.map { case (key, value) => key.toLowerCase -> value }
    if normalized.contains(ForbiddenSessionHeader.toLowerCase) then
      Left(
        McpJson.protocolError(
          "transport",
          "MCP 2026-07-28 forbids Mcp-Session-Id",
          Some("stateless_session_forbidden")
        )
      )
    else if normalized.contains(ForbiddenLastEventId.toLowerCase) then
      Left(
        McpJson.protocolError(
          "transport",
          "MCP 2026-07-28 removed SSE Last-Event-ID resumability",
          Some("stateless_resume_forbidden")
        )
      )
    else Right(())

  def parseDiscover(result: Json): Either[AgentError, DiscoverResult] =
    asObject(result, "server/discover").flatMap { obj =>
      for
        version <- McpJson.requiredString(obj, "protocolVersion", "server/discover")
        _       <- Either.cond(
          version == Version.value,
          (),
          McpJson.protocolError(
            "server/discover",
            s"UnsupportedProtocolVersion: $version",
            Some("unsupported_protocol_version")
          )
        )
        info <- McpJson.required(obj, "serverInfo", "server/discover").flatMap {
          case Json.Obj(fields) => Right(Json.Obj(fields))
          case _ => Left(McpJson.protocolError("server/discover", "serverInfo must be an object"))
        }
        name <- McpJson.requiredString(info, "name", "server/discover")
        ver  <- McpJson.requiredString(info, "version", "server/discover")
        capsJson = McpJson.field(obj, "capabilities").getOrElse(Json.Obj())
        caps <- capsJson match
          case Json.Obj(fields) => Right(Json.Obj(fields))
          case _ => Left(McpJson.protocolError("server/discover", "capabilities must be an object"))
      yield DiscoverResult(version, name, ver, caps)
    }

  def resultType(result: Json): Either[AgentError, ResultType] =
    asObject(result, "result").flatMap { obj =>
      McpJson.requiredString(obj, "resultType", "result").flatMap {
        case "complete"       => Right(ResultType.Complete)
        case "input_required" => Right(ResultType.InputRequired)
        case other            =>
          Left(McpJson.protocolError("result", s"unknown resultType: $other", Some("unknown_result_type")))
      }
    }

  def cacheHint(result: Json): Either[AgentError, Option[CacheHint]] =
    asObject(result, "result").flatMap { obj =>
      val ttl   = McpJson.optionalLong(obj, "ttlMs", "result")
      val scope = McpJson.optionalString(obj, "cacheScope", "result")
      (ttl, scope) match
        case (Right(None), Right(None)) => Right(None)
        case (Right(Some(ms)), Right(Some(value))) if ms >= 0L && (value == "public" || value == "private") =>
          Right(Some(CacheHint(ms, value)))
        case (Left(error), _) => Left(error)
        case (_, Left(error)) => Left(error)
        case _                =>
          Left(McpJson.protocolError("result", "ttlMs and cacheScope must appear together and be valid"))
    }

  def parseInputRequired(result: Json): Either[AgentError, InputRequired] =
    resultType(result).flatMap {
      case ResultType.InputRequired =>
        asObject(result, "input_required").flatMap { obj =>
          for
            state    <- McpJson.optionalString(obj, "requestState", "input_required")
            requests <- parseInputRequests(obj)
            _        <- Either.cond(
              requests.nonEmpty || state.nonEmpty,
              (),
              McpJson.protocolError(
                "input_required",
                "inputRequests or requestState is required",
                Some("input_required_empty")
              )
            )
          yield InputRequired(requests, state)
        }
      case _ =>
        Left(
          McpJson.protocolError(
            "input_required",
            "resultType must be input_required",
            Some("not_input_required")
          )
        )
    }

  def retryParams(
      original: Json.Obj,
      required: InputRequired,
      responses: Json.Obj
  ): Either[AgentError, Json.Obj] =
    val responseIds = responses.fields.map(_._1).toSet
    val requestIds  = required.requests.map(_.id).toSet
    if required.requests.nonEmpty && responseIds != requestIds then
      Left(
        McpJson.protocolError(
          "input_required",
          "inputResponses keys must match inputRequests",
          Some("input_responses_mismatch")
        )
      )
    else
      val stripped = Json.Obj(original.fields.filterNot { case (name, _) =>
        name == "inputResponses" || name == "requestState"
      })
      val withResponses =
        if responses.fields.isEmpty then stripped
        else Json.Obj(stripped.fields :+ ("inputResponses" -> responses))
      required.requestState match
        case Some(state) => Right(Json.Obj(withResponses.fields :+ ("requestState" -> Json.Str(state))))
        case None        => Right(withResponses)

  def listenParams(types: Set[String]): Either[AgentError, Json.Obj] =
    val unknown = types.diff(ListenTypes)
    if types.isEmpty then
      Left(
        McpJson
          .protocolError("subscriptions/listen", "listen types must not be empty", Some("listen_types_empty"))
      )
    else if unknown.nonEmpty then
      Left(
        McpJson.protocolError(
          "subscriptions/listen",
          "unknown listen types",
          Some("listen_types_unknown")
        )
      )
    else
      Right(
        Json.Obj("types" -> Json.Arr(Chunk.fromIterable(types.toList.sorted.map(Json.Str(_)))))
      )

  def parseListenAck(result: Json): Either[AgentError, ListenAck] =
    resultType(result).flatMap {
      case ResultType.Complete =>
        asObject(result, "subscriptions/listen").flatMap { obj =>
          val accepted = McpJson.field(obj, "accepted").orElse(McpJson.field(obj, "types")) match
            case Some(Json.Arr(values)) =>
              val names = values.collect { case Json.Str(value) => value }.toSet
              Either.cond(
                names.nonEmpty && names.subsetOf(ListenTypes),
                names,
                McpJson.protocolError(
                  "subscriptions/listen",
                  "accepted types are invalid",
                  Some("listen_ack_invalid")
                )
              )
            case None => Right(Set.empty[String])
            case _    =>
              Left(
                McpJson.protocolError(
                  "subscriptions/listen",
                  "accepted must be an array",
                  Some("listen_ack_invalid")
                )
              )
          val subscription = McpJson.field(obj, "_meta") match
            case Some(meta: Json.Obj) =>
              McpJson.optionalString(meta, SubscriptionIdKey, "subscriptions/listen")
            case _ => McpJson.optionalString(obj, "subscriptionId", "subscriptions/listen")
          for
            types <- accepted
            id    <- subscription
          yield ListenAck(types, id)
        }
      case _ =>
        Left(
          McpJson.protocolError(
            "subscriptions/listen",
            "listen ack must be complete",
            Some("listen_not_complete")
          )
        )
    }

  private def parseInputRequests(obj: Json.Obj): Either[AgentError, List[InputRequest]] =
    McpJson.field(obj, "inputRequests") match
      case None                   => Right(Nil)
      case Some(Json.Obj(fields)) =>
        fields.foldLeft[Either[AgentError, List[InputRequest]]](Right(Nil)) { (acc, field) =>
          acc.flatMap { current =>
            val (id, value) = field
            value match
              case request: Json.Obj =>
                McpJson.requiredString(request, "method", "input_required").flatMap { method =>
                  Either.cond(
                    id.nonEmpty && AllowedInputMethods.contains(method),
                    current :+ InputRequest(
                      id,
                      method,
                      McpJson.field(request, "params") match
                        case Some(params: Json.Obj) => params
                        case _                      => Json.Obj()
                    ),
                    McpJson.protocolError(
                      "input_required",
                      "unsupported or empty input request",
                      Some("input_request_unsupported")
                    )
                  )
                }
              case _ =>
                Left(McpJson.protocolError("input_required", "inputRequests values must be objects"))
          }
        }
      case Some(_) =>
        Left(McpJson.protocolError("input_required", "inputRequests must be an object"))

  private def asObject(result: Json, operation: String): Either[AgentError, Json.Obj] =
    result match
      case obj: Json.Obj => Right(obj)
      case _             => Left(McpJson.protocolError(operation, "result must be an object"))

/** 2026 Streamable HTTP endpoint：禁止 session/query token，默认拒绝链路本地与云元数据地址。 */
object Mcp2026Endpoint:
  def validate(endpoint: String, allowPrivateNetwork: Boolean = false): Either[AgentError, URI] =
    scala.util
      .Try(URI.create(endpoint))
      .toEither
      .left
      .map(_ => AgentError.InvalidConfiguration("mcp-2026-endpoint-invalid"))
      .flatMap { uri =>
        val host    = Option(uri.getHost).getOrElse("")
        val blocked =
          !allowPrivateNetwork && (isBlockedHost(host) || isLinkLocalOrMetadata(host))
        Either.cond(
          uri.isAbsolute &&
            uri.getScheme == "https" &&
            uri.getUserInfo == null &&
            uri.getRawQuery == null &&
            uri.getRawFragment == null &&
            host.nonEmpty &&
            !blocked,
          uri,
          AgentError.InvalidConfiguration("mcp-2026-endpoint-ssrf")
        )
      }

  private def isBlockedHost(host: String): Boolean =
    val normalized = host.toLowerCase
    normalized == "localhost" ||
    normalized.endsWith(".localhost") ||
    normalized == "0.0.0.0" ||
    normalized == "::1"

  private def isLinkLocalOrMetadata(host: String): Boolean =
    val normalized = host.toLowerCase
    normalized == "169.254.169.254" ||
    normalized.startsWith("10.") ||
    normalized.startsWith("192.168.") ||
    normalized.startsWith("169.254.") ||
    scala.util
      .Try(InetAddress.getByName(host))
      .toOption
      .exists(address =>
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
      )
