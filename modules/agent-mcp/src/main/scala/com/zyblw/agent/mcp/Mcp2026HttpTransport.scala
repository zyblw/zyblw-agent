package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import java.net.URI
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** MCP `2026-07-28` Streamable HTTP：只 POST，每请求带 `Mcp-Method`/`Mcp-Name`，不建立 session。
  *
  * 与 `StreamableHttpMcpTransport` 刻意分开：2025 transport 仍负责 initialize/GET listener/Last-Event-ID/DELETE。
  * 本实现不会发送这些 stateful 头，也不会在关闭时 DELETE。权威变更见
  * https://modelcontextprotocol.io/specification/2026-07-28/changelog
  */
final case class Mcp2026HttpTransportConfig(
    endpoint: String,
    allowInsecureHttp: Boolean = false,
    allowPrivateNetwork: Boolean = false,
    inboundCapacity: Int = 256,
    maxJsonBytes: Int = 4 * 1024 * 1024,
    httpOperationTimeout: Duration = 30.seconds
):
  def validate: Either[AgentError, URI] =
    if allowInsecureHttp then validateLoopbackHttp
    else Mcp2026Endpoint.validate(endpoint, allowPrivateNetwork)

  /** 测试夹具才允许明文 loopback；生产路径必须走 HTTPS + SSRF 门禁。 */
  private def validateLoopbackHttp: Either[AgentError, URI] =
    scala.util
      .Try(URI.create(endpoint))
      .toEither
      .left
      .map(_ => AgentError.InvalidConfiguration("mcp-2026-endpoint-invalid"))
      .flatMap { uri =>
        val host     = Option(uri.getHost).getOrElse("")
        val loopback =
          host == "127.0.0.1" || host == "localhost" || host == "::1"
        Either.cond(
          uri.isAbsolute &&
            uri.getScheme == "http" &&
            uri.getUserInfo == null &&
            uri.getRawQuery == null &&
            uri.getRawFragment == null &&
            loopback,
          uri,
          AgentError.InvalidConfiguration("mcp-2026-endpoint-ssrf")
        )
      }

/** 无状态 MCP 2026 HTTP transport。关闭时只结束 peer，不发送 GET/DELETE。 */
final class Mcp2026HttpTransport private (
    peer: McpJsonRpcPeer,
    closed: Ref[Boolean]
) extends McpTransport:
  def request(method: String, params: Json.Obj, timeout: Duration): IO[AgentError, Json] =
    ZIO.fromEither(Mcp2026Protocol.rejectLegacyMethod(method)) *> peer.request(method, params, timeout)

  def notify(method: String, params: Json.Obj): IO[AgentError, Unit] =
    ZIO.fromEither(Mcp2026Protocol.rejectLegacyMethod(method)) *> peer.notify(method, params)

  def respond(id: McpRequestId, result: Either[McpRpcError, Json]): IO[AgentError, Unit] =
    peer.respond(id, result)

  def inbound: ZStream[Any, AgentError, McpInbound] = peer.inbound

  def close: UIO[Unit] =
    closed.getAndSet(true).flatMap { alreadyClosed =>
      ZIO.unless(alreadyClosed)(peer.close).unit
    }

  private[mcp] def pendingCount: UIO[Int] = peer.pendingCount

object Mcp2026HttpTransport:
  private val ProtocolHeader = "MCP-Protocol-Version"

  def scoped(
      client: Client,
      config: Mcp2026HttpTransportConfig,
      credentials: McpBearerTokenProvider = McpBearerTokenProvider.none
  ): ZIO[Scope, AgentError, Mcp2026HttpTransport] =
    for
      _       <- ZIO.fromEither(config.validate)
      peerRef <- Ref.make(Option.empty[McpJsonRpcPeer])
      closed  <- Ref.make(false)
      send = (message: Json.Obj) =>
        peerRef.get.flatMap {
          case Some(peer) => deliverPost(client, config, credentials, peer, message)
          case None       =>
            ZIO.fail(
              AgentError.ExternalProtocolFailure(
                "mcp",
                "http/send",
                "MCP 2026 HTTP peer is not initialized",
                Some("peer_not_ready")
              )
            )
        }
      peer <- McpJsonRpcPeer.make(send, config.inboundCapacity)
      _    <- peerRef.set(Some(peer))
      transport = Mcp2026HttpTransport(peer, closed)
      _ <- ZIO.addFinalizer(transport.close)
    yield transport

  private def deliverPost(
      client: Client,
      config: Mcp2026HttpTransportConfig,
      credentials: McpBearerTokenProvider,
      peer: McpJsonRpcPeer,
      message: Json.Obj
  ): IO[AgentError, Unit] =
    for
      method <- rpcMethod(message)
      _      <- ZIO.fromEither(Mcp2026Protocol.rejectLegacyMethod(method))
      params = rpcParams(message)
      request <- buildRequest(
        config,
        credentials,
        method,
        Mcp2026Protocol.nameFromParams(method, params),
        message
      )
      _ <- executeResponse(client, config, peer, request)
    yield ()

  private def buildRequest(
      config: Mcp2026HttpTransportConfig,
      credentials: McpBearerTokenProvider,
      method: String,
      name: Option[String],
      message: Json.Obj
  ): IO[AgentError, Request] =
    for
      token <- credentials.bearerToken
      _     <- ZIO.foreachDiscard(token) { value =>
        ZIO
          .fail(AgentError.InvalidConfiguration("Invalid MCP bearer token"))
          .when(value.trim.isEmpty || value.exists(char => char == '\r' || char == '\n'))
      }
      headers = Mcp2026Protocol.httpHeaders(method, name)
      _ <- ZIO.fromEither(Mcp2026Protocol.rejectSessionHeaders(headers))
      base = Request
        .post(config.endpoint, Body.fromString(message.toJson))
        .addHeader("Accept", "application/json")
        .addHeader(Header.ContentType(MediaType.application.json))
        .addHeader(ProtocolHeader, Mcp2026Protocol.Version.value)
        .addHeader(Mcp2026Protocol.HeaderMethod, method)
      named = name.fold(base)(value => base.addHeader(Mcp2026Protocol.HeaderName, value))
    yield token.fold(named)(value => named.addHeader(Header.Authorization.Bearer(value)))

  private def executeResponse(
      client: Client,
      config: Mcp2026HttpTransportConfig,
      peer: McpJsonRpcPeer,
      request: Request
  ): IO[AgentError, Unit] =
    client
      .stream(request) { response =>
        val status = response.status.code
        if status == 202 then ZStream.succeed(())
        else if response.status.isSuccess then
          ZStream.fromZIO(rejectStatefulHeaders(response)) *>
            (if response.hasJsonContentType then
               ZStream.fromZIO(readJson(response.body, config).flatMap(peer.accept))
             else ZStream.fail(httpFailure(status, "unsupported_content_type")))
        else ZStream.fail(httpFailure(status, "http_error"))
      }
      .runDrain
      .timeoutFail(
        AgentError.ExternalProtocolFailure(
          "mcp",
          "http/response",
          "MCP 2026 HTTP operation timed out",
          Some("http_timeout"),
          retryable = true
        )
      )(config.httpOperationTimeout)
      .mapError(mapHttpThrowable)

  private def rejectStatefulHeaders(response: Response): IO[AgentError, Unit] =
    val headers = List(
      Mcp2026Protocol.ForbiddenSessionHeader,
      "MCP-Session-Id",
      Mcp2026Protocol.ForbiddenLastEventId
    ).flatMap(name => response.headers.get(name).map(name -> _)).toMap
    ZIO.fromEither(Mcp2026Protocol.rejectSessionHeaders(headers))

  private def readJson(body: Body, config: Mcp2026HttpTransportConfig): IO[AgentError, Json.Obj] =
    for
      bytes <- body.asStream.take(config.maxJsonBytes.toLong + 1L).runCollect.mapError(mapHttpThrowable)
      _     <- ZIO
        .fail(
          AgentError.ExternalProtocolFailure(
            "mcp",
            "http/json",
            "MCP JSON response exceeded configured limit",
            Some("body_too_large")
          )
        )
        .when(bytes.length > config.maxJsonBytes)
      text <- ZIO
        .attempt {
          val decoder = java.nio.charset.StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          decoder.decode(java.nio.ByteBuffer.wrap(bytes.toArray)).toString
        }
        .mapError(error =>
          AgentError.ExternalProtocolFailure(
            "mcp",
            "http/json",
            "MCP JSON response is not valid UTF-8",
            Some("invalid_utf8"),
            cause = Some(error)
          )
        )
      json <- ZIO.fromEither(McpJson.parseLine(text, "http/json"))
    yield json

  private def rpcMethod(message: Json.Obj): IO[AgentError, String] =
    McpJson.field(message, "method") match
      case Some(Json.Str(value)) => ZIO.succeed(value)
      case _                     =>
        ZIO.fail(
          AgentError.ExternalProtocolFailure(
            "mcp",
            "http/send",
            "MCP envelope missing method",
            Some("missing_method")
          )
        )

  private def rpcParams(message: Json.Obj): Json.Obj =
    McpJson.field(message, "params") match
      case Some(obj: Json.Obj) => obj
      case _                   => Json.Obj()

  private def httpFailure(status: Int, code: String): AgentError =
    AgentError.ExternalProtocolFailure(
      "mcp",
      "http/response",
      s"MCP 2026 HTTP request failed with status $status",
      Some(code),
      retryable = status == 408 || status == 409 || status == 425 || status == 429 || status >= 500
    )

  private def mapHttpThrowable(error: Throwable): AgentError = error match
    case value: AgentError => value
    case other             =>
      AgentError.ExternalProtocolFailure(
        "mcp",
        "http/transport",
        "MCP 2026 HTTP transport failed",
        Some("transport_failure"),
        retryable = true,
        cause = Some(other)
      )
