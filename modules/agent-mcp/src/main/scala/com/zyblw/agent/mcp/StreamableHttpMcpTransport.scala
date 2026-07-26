package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import java.net.URI
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** 为 MCP Streamable HTTP 提供可轮换 bearer token。
  *
  * token 在每次 HTTP 请求前读取，因此业务可连接 Secret Manager 或 OAuth token cache；框架不会把 token 放入配置摘要、URL、JSON-RPC `_meta`
  * 或遥测字段。
  */
trait McpBearerTokenProvider:
  /** 返回当前 bearer token；`None` 表示该 MCP endpoint 不需要 HTTP 认证。 */
  def bearerToken: IO[AgentError, Option[String]]

object McpBearerTokenProvider:
  /** 不发送 Authorization header。只适合受信本地网络或另有 mTLS 的 endpoint。 */
  val none: McpBearerTokenProvider = new McpBearerTokenProvider:
    def bearerToken: IO[AgentError, Option[String]] = ZIO.none

  /** 创建静态 token provider。生产建议由 Secret Manager layer 代替。 */
  def static(token: String): McpBearerTokenProvider = new McpBearerTokenProvider:
    def bearerToken: IO[AgentError, Option[String]] = ZIO.some(token)

/** Streamable HTTP transport 配置。
  *
  * @param endpoint
  *   MCP 单一 POST/GET endpoint；禁止 user-info、query 和 fragment
  * @param allowInsecureHttp
  *   是否允许明文 HTTP；默认仅 HTTPS，测试/localhost 需显式打开
  * @param inboundCapacity
  *   服务端通知/反向请求有界缓冲
  * @param maxJsonBytes
  *   application/json 响应体硬上限
  * @param maxSseLineChars
  *   SSE 单行字符上限
  * @param maxSseEventChars
  *   单个 SSE event 聚合 data 上限
  * @param maxSessionIdChars
  *   MCP-Session-Id 长度上限
  * @param defaultReconnectDelay
  *   SSE 未提供 retry 时的重连间隔
  * @param httpOperationTimeout
  *   建连、DELETE 等 transport 操作硬超时；业务 request 仍由 peer 单独控制
  * @param enableServerListener
  *   是否在 initialized 后用 GET 打开服务端主动消息流
  */
final case class StreamableHttpMcpTransportConfig(
    endpoint: String,
    allowInsecureHttp: Boolean = false,
    inboundCapacity: Int = 256,
    maxJsonBytes: Int = 4 * 1024 * 1024,
    maxSseLineChars: Int = 1024 * 1024,
    maxSseEventChars: Int = 4 * 1024 * 1024,
    maxSessionIdChars: Int = 1024,
    defaultReconnectDelay: Duration = 1.second,
    httpOperationTimeout: Duration = 30.seconds,
    enableServerListener: Boolean = true
):
  /** 验证 endpoint 与资源上限。
    *
    * query 被整体禁止，因而 token 不可能被误配置成 `?access_token=...`；规范要求 bearer 只出现在 header。
    */
  def validate: Either[AgentError, Unit] =
    scala.util
      .Try(URI.create(endpoint))
      .toEither
      .left
      .map(error =>
        AgentError.InvalidConfiguration(s"Invalid MCP HTTP endpoint: ${error.getClass.getSimpleName}")
      )
      .flatMap { uri =>
        val schemeAllowed = uri.getScheme == "https" || (allowInsecureHttp && uri.getScheme == "http")
        Either.cond(
          uri.isAbsolute && schemeAllowed && uri.getHost != null && uri.getUserInfo == null &&
            uri.getRawQuery == null && uri.getRawFragment == null &&
            inboundCapacity > 0 && maxJsonBytes > 0 && maxSseLineChars > 0 && maxSseEventChars > 0 &&
            maxSessionIdChars > 0 && defaultReconnectDelay > Duration.Zero && httpOperationTimeout > Duration.Zero,
          (),
          AgentError.InvalidConfiguration(
            "MCP HTTP endpoint must be absolute HTTPS without user-info/query/fragment and limits must be positive"
          )
        )
      }

/** SSE 断线恢复 cursor。retry 来自服务端 SSE 字段，但仍受正数校验。 */
final private case class McpHttpCursor(lastEventId: Option[String], retry: Duration)

/** session 恢复所需的最小握手快照。
  *
  * initialize 参数和结果都属于协议控制面；它们不会写日志。恢复时新结果必须与 `expectedResult` 的关键协商 字段兼容，才能继续使用既有 `McpClient.session`。
  */
final private case class McpHttpHandshake(
    initializeParams: Option[Json.Obj] = None,
    expectedResult: Option[Json] = None,
    initializedSent: Boolean = false
)

/** MCP 2025-11-25 Streamable HTTP transport。 */
final class StreamableHttpMcpTransport private (
    peer: McpJsonRpcPeer,
    client: Client,
    config: StreamableHttpMcpTransportConfig,
    credentials: McpBearerTokenProvider,
    negotiatedVersion: Ref[Option[McpProtocolVersion]],
    versionReady: Promise[Nothing, McpProtocolVersion],
    sessionId: Ref[Option[String]],
    handshake: Ref.Synchronized[McpHttpHandshake],
    closed: Ref[Boolean]
) extends McpTransport:

  def request(method: String, params: Json.Obj, timeout: Duration): IO[AgentError, Json] =
    ZIO.when(method == "initialize")(handshake.update(_.copy(initializeParams = Some(params)))) *>
      peer.request(method, params, timeout).tap { result =>
        ZIO.when(method == "initialize")(
          handshake.update(current =>
            if current.expectedResult.isEmpty then current.copy(expectedResult = Some(result)) else current
          )
        )
      }

  def notify(method: String, params: Json.Obj): IO[AgentError, Unit] =
    peer.notify(method, params) *>
      ZIO.when(method == "notifications/initialized")(handshake.update(_.copy(initializedSent = true))).unit

  def respond(id: McpRequestId, result: Either[McpRpcError, Json]): IO[AgentError, Unit] =
    peer.respond(id, result)

  def inbound: ZStream[Any, AgentError, McpInbound] = peer.inbound

  /** 保存协商版本并唤醒可选 GET listener；重复调用只保留第一次 Promise 完成。 */
  override def negotiated(version: McpProtocolVersion): IO[AgentError, Unit] =
    negotiatedVersion.set(Some(version)) *> versionReady.succeed(version).unit

  /** 幂等关闭：若服务端分配了 session，先尽力 DELETE，再结束 peer。 DELETE 失败不会让应用 shutdown 卡住或覆盖原始错误。
    */
  def close: UIO[Unit] =
    closed.getAndSet(true).flatMap { alreadyClosed =>
      ZIO
        .unless(alreadyClosed)(
          StreamableHttpMcpTransport
            .deleteSession(client, config, credentials, negotiatedVersion, sessionId)
            .ignore *>
            peer.close
        )
        .unit
    }

  /** 测试与健康诊断读取当前 session；不得把值写入日志或 metric label。 */
  private[mcp] def currentSessionId: UIO[Option[String]] = sessionId.get

  /** 验证超时/取消后没有遗留 Promise。 */
  private[mcp] def pendingCount: UIO[Int] = peer.pendingCount

object StreamableHttpMcpTransport:
  private val SessionHeader  = "MCP-Session-Id"
  private val ProtocolHeader = "MCP-Protocol-Version"

  /** 从共享 ZIO HTTP Client 创建 scoped transport。
    *
    * `Client` 由宿主应用统一配置连接池、TLS、代理和 DNS；transport 本身不创建第二套线程池。内部 supervisor 在协议协商完成前阻塞于 Promise，不会提前发送缺少版本
    * header 的 GET。
    */
  def scoped(
      client: Client,
      config: StreamableHttpMcpTransportConfig,
      credentials: McpBearerTokenProvider = McpBearerTokenProvider.none
  ): ZIO[Scope, AgentError, StreamableHttpMcpTransport] =
    for
      _                 <- ZIO.fromEither(config.validate)
      peerRef           <- Ref.make(Option.empty[McpJsonRpcPeer])
      negotiatedVersion <- Ref.make(Option.empty[McpProtocolVersion])
      versionReady      <- Promise.make[Nothing, McpProtocolVersion]
      sessionId         <- Ref.make(Option.empty[String])
      handshake         <- Ref.Synchronized.make(McpHttpHandshake())
      recoveryLock      <- Semaphore.make(1L)
      closed            <- Ref.make(false)
      send = (message: Json.Obj) =>
        peerRef.get.flatMap {
          case Some(peer) =>
            deliverPost(
              client,
              config,
              credentials,
              negotiatedVersion,
              sessionId,
              handshake,
              recoveryLock,
              peer,
              message
            )
          case None =>
            ZIO.fail(
              AgentError.ExternalProtocolFailure(
                "mcp",
                "http/send",
                "MCP HTTP peer is not initialized",
                Some("peer_not_ready")
              )
            )
        }
      peer <- McpJsonRpcPeer.make(send, config.inboundCapacity)
      _    <- peerRef.set(Some(peer))
      transport = StreamableHttpMcpTransport(
        peer,
        client,
        config,
        credentials,
        negotiatedVersion,
        versionReady,
        sessionId,
        handshake,
        closed
      )
      _ <- ZIO.when(config.enableServerListener)(
        listenerSupervisor(client, config, credentials, negotiatedVersion, versionReady, sessionId, peer)
          .catchAll(error => peer.failAll(error))
          .forkScoped
      )
      _ <- ZIO.addFinalizer(transport.close)
    yield transport

  /** POST 一条 JSON-RPC message，并根据 Content-Type 解析 JSON 或 SSE。 */
  private def deliverPost(
      client: Client,
      config: StreamableHttpMcpTransportConfig,
      credentials: McpBearerTokenProvider,
      version: Ref[Option[McpProtocolVersion]],
      session: Ref[Option[String]],
      handshake: Ref.Synchronized[McpHttpHandshake],
      recoveryLock: Semaphore,
      peer: McpJsonRpcPeer,
      message: Json.Obj
  ): IO[AgentError, Unit] =
    for
      method      <- ZIO.fromEither(McpJson.optionalString(message, "method", "http/send"))
      usedSession <- session.get
      _           <- deliverPostOnce(
        client,
        config,
        credentials,
        version,
        session,
        peer,
        message,
        includeSessionContext = !method.contains("initialize")
      ).catchSome {
        case AgentError.ExternalProtocolFailure(_, _, _, Some("session_expired"), _, _)
            if !method.contains("initialize") && usedSession.nonEmpty =>
          recoveryLock.withPermit {
            session.get.flatMap {
              // 另一个 Fiber 已经用新的 session 完成恢复，本 Fiber 只需重放原请求。
              case Some(current) if !usedSession.contains(current) => ZIO.unit
              case _ => recoverSession(config, session, handshake, peer)
            }
          } *> deliverPostOnce(
            client,
            config,
            credentials,
            version,
            session,
            peer,
            message,
            includeSessionContext = true
          )
      }
    yield ()

  /** 单次 POST 发送；session 恢复由外层 `deliverPost` 串行协调，避免递归重试风暴。 */
  private def deliverPostOnce(
      client: Client,
      config: StreamableHttpMcpTransportConfig,
      credentials: McpBearerTokenProvider,
      version: Ref[Option[McpProtocolVersion]],
      session: Ref[Option[String]],
      peer: McpJsonRpcPeer,
      message: Json.Obj,
      includeSessionContext: Boolean
  ): IO[AgentError, Unit] =
    for
      request <- buildRequest(
        Request.post(config.endpoint, Body.fromString(message.toJson)),
        credentials,
        version,
        session,
        "application/json, text/event-stream",
        includeContentType = true,
        lastEventId = None,
        includeSessionContext = includeSessionContext
      )
      cursor <- Ref.make(McpHttpCursor(None, config.defaultReconnectDelay))
      _      <- executeResponse(client, config, session, peer, request, cursor, allowMethodNotAllowed = false)
      requestId <- ZIO.fromEither(optionalRequestId(message))
      _         <- ZIO.foreachDiscard(requestId) { id =>
        resumePostedStream(client, config, credentials, version, session, peer, id, cursor)
      }
    yield ()

  /** 用缓存的 initialize 参数建立新 session，并验证协商结果没有改变。
    *
    * 这里通过同一个 JSON-RPC peer 发出新的 initialize，所以 request id、超时和响应解析仍走统一状态机。 新结果若改变协议版本、capabilities 或
    * serverInfo，恢复会 fail-closed，要求业务重建整个 McpClient。
    */
  private def recoverSession(
      config: StreamableHttpMcpTransportConfig,
      session: Ref[Option[String]],
      handshake: Ref.Synchronized[McpHttpHandshake],
      peer: McpJsonRpcPeer
  ): IO[AgentError, Unit] =
    for
      snapshot <- handshake.get
      params   <- ZIO
        .fromOption(snapshot.initializeParams)
        .orElseFail(
          AgentError.ExternalProtocolFailure(
            "mcp",
            "http/session-recovery",
            "MCP session cannot be recovered before initialize is cached",
            Some("recovery_unavailable")
          )
        )
      expected <- ZIO
        .fromOption(snapshot.expectedResult)
        .orElseFail(
          AgentError.ExternalProtocolFailure(
            "mcp",
            "http/session-recovery",
            "MCP session cannot be recovered before initialize result is cached",
            Some("recovery_unavailable")
          )
        )
      _ <- ZIO
        .fail(
          AgentError.ExternalProtocolFailure(
            "mcp",
            "http/session-recovery",
            "MCP session cannot be recovered before initialized notification was sent",
            Some("recovery_unavailable")
          )
        )
        .unless(snapshot.initializedSent)
      _      <- session.set(None)
      result <- peer.request("initialize", params, config.httpOperationTimeout)
      _      <- ZIO
        .fail(
          AgentError.ExternalProtocolFailure(
            "mcp",
            "http/session-recovery",
            "MCP server changed negotiated identity or capabilities during session recovery",
            Some("handshake_changed")
          )
        )
        .unless(handshakeCompatible(expected, result))
      _ <- peer.notify("notifications/initialized", Json.Obj())
    yield ()

  /** 比较会影响既有 client 安全语义的 initialize 字段；instructions/_meta 变化不会自动扩大权限。 */
  private def handshakeCompatible(expected: Json, actual: Json): Boolean = (expected, actual) match
    case (left: Json.Obj, right: Json.Obj) =>
      List("protocolVersion", "capabilities", "serverInfo").forall(name =>
        McpJson.field(left, name) == McpJson.field(right, name)
      )
    case _ => false

  /** 若 POST SSE 在最终 response 前断开且给出 event id，通过 GET + Last-Event-ID 恢复同一流。 */
  private def resumePostedStream(
      client: Client,
      config: StreamableHttpMcpTransportConfig,
      credentials: McpBearerTokenProvider,
      version: Ref[Option[McpProtocolVersion]],
      session: Ref[Option[String]],
      peer: McpJsonRpcPeer,
      requestId: McpRequestId,
      cursor: Ref[McpHttpCursor]
  ): IO[AgentError, Unit] =
    peer.isPending(requestId).flatMap {
      case false => ZIO.unit
      case true  =>
        cursor.get.flatMap {
          case McpHttpCursor(None, _) => ZIO.unit
          case state                  =>
            ZIO.sleep(state.retry) *>
              buildRequest(
                Request.get(config.endpoint),
                credentials,
                version,
                session,
                "text/event-stream",
                includeContentType = false,
                lastEventId = state.lastEventId,
                includeSessionContext = true
              ).flatMap(request =>
                executeResponse(client, config, session, peer, request, cursor, allowMethodNotAllowed = false)
              ) *> resumePostedStream(client, config, credentials, version, session, peer, requestId, cursor)
        }
    }

  /** initialized 后持续维护独立 GET SSE，用于服务端主动通知和反向请求。 */
  private def listenerSupervisor(
      client: Client,
      config: StreamableHttpMcpTransportConfig,
      credentials: McpBearerTokenProvider,
      version: Ref[Option[McpProtocolVersion]],
      versionReady: Promise[Nothing, McpProtocolVersion],
      session: Ref[Option[String]],
      peer: McpJsonRpcPeer
  ): IO[AgentError, Unit] =
    for
      _      <- versionReady.await
      cursor <- Ref.make(McpHttpCursor(None, config.defaultReconnectDelay))
      _      <- listenLoop(client, config, credentials, version, session, peer, cursor)
    yield ()

  /** GET 返回 405 表示服务端没有独立 listener，此时正常停止；断流则按 retry 重连。 */
  private def listenLoop(
      client: Client,
      config: StreamableHttpMcpTransportConfig,
      credentials: McpBearerTokenProvider,
      version: Ref[Option[McpProtocolVersion]],
      session: Ref[Option[String]],
      peer: McpJsonRpcPeer,
      cursor: Ref[McpHttpCursor]
  ): IO[AgentError, Unit] =
    for
      state   <- cursor.get
      _       <- ZIO.sleep(state.retry).when(state.lastEventId.nonEmpty)
      request <- buildRequest(
        Request.get(config.endpoint),
        credentials,
        version,
        session,
        "text/event-stream",
        includeContentType = false,
        lastEventId = state.lastEventId,
        includeSessionContext = true
      )
      supported <- executeResponse(
        client,
        config,
        session,
        peer,
        request,
        cursor,
        allowMethodNotAllowed = true
      )
      _ <- ZIO.when(supported)(listenLoop(client, config, credentials, version, session, peer, cursor))
    yield ()

  /** 执行一次 HTTP 请求并在 response Body 的 Scope 内消费完 JSON/SSE。
    *
    * @return
    *   `false` 仅表示 GET 收到规范允许的 405；其他成功均为 true
    */
  private def executeResponse(
      client: Client,
      config: StreamableHttpMcpTransportConfig,
      session: Ref[Option[String]],
      peer: McpJsonRpcPeer,
      request: Request,
      cursor: Ref[McpHttpCursor],
      allowMethodNotAllowed: Boolean
  ): IO[AgentError, Boolean] =
    client
      .stream(request) { response =>
        val status = response.status.code
        if status == 405 && allowMethodNotAllowed then ZStream.succeed(false)
        else if status == 202 then ZStream.succeed(true)
        else if response.status.isSuccess then
          val capture = ZStream.fromZIO(captureSession(response, config, session))
          if response.hasContentType(MediaType.text.`event-stream`) then
            capture *> McpSse
              .events(response.body.asStream, config.maxSseLineChars, config.maxSseEventChars)
              .mapZIO(event => routeSseEvent(event, peer, cursor))
              .drain ++ ZStream.succeed(true)
          else if response.hasJsonContentType then
            capture *> ZStream.fromZIO(readJson(response.body, config).flatMap(peer.accept)).drain ++ ZStream
              .succeed(true)
          else ZStream.fail(httpFailure(status, "unsupported_content_type"))
        else
          ZStream.fromZIO(
            ZIO.when(status == 404)(session.set(None)) *>
              ZIO.fail(httpFailure(status, if status == 404 then "session_expired" else "http_error"))
          )
      }
      .runLast
      .someOrFail(
        AgentError.ExternalProtocolFailure(
          "mcp",
          "http/response",
          "MCP HTTP response stream was empty",
          Some("empty_response")
        )
      )
      .timeoutFail(
        AgentError.ExternalProtocolFailure(
          "mcp",
          "http/response",
          "MCP HTTP operation timed out",
          Some("http_timeout"),
          retryable = true
        )
      )(config.httpOperationTimeout)
      .mapError(mapHttpThrowable)

  /** 处理 SSE cursor/retry，并把非空 data 作为 JSON-RPC envelope 路由。 */
  private def routeSseEvent(
      event: McpSseEvent,
      peer: McpJsonRpcPeer,
      cursor: Ref[McpHttpCursor]
  ): IO[AgentError, Unit] =
    cursor.update(current =>
      McpHttpCursor(event.id.orElse(current.lastEventId), event.retry.getOrElse(current.retry))
    ) *>
      ZIO
        .unless(event.data.isEmpty)(
          ZIO.fromEither(McpJson.parseLine(event.data, "http/sse")).flatMap(peer.accept)
        )
        .unit

  /** application/json body 的字节上限与严格 UTF-8/JSON-RPC 解码。 */
  private def readJson(body: Body, config: StreamableHttpMcpTransportConfig): IO[AgentError, Json.Obj] =
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

  /** 初始化响应可建立 session；值必须是可见 ASCII 且受长度上限保护。 */
  private def captureSession(
      response: Response,
      config: StreamableHttpMcpTransportConfig,
      session: Ref[Option[String]]
  ): IO[AgentError, Unit] =
    response.headers.get(SessionHeader) match
      case None        => ZIO.unit
      case Some(value) =>
        val valid = value.nonEmpty && value.length <= config.maxSessionIdChars && value.forall(char =>
          char >= 0x21 && char <= 0x7e
        )
        if valid then session.set(Some(value))
        else
          ZIO.fail(
            AgentError.ExternalProtocolFailure(
              "mcp",
              "http/session",
              "MCP-Session-Id contains invalid characters or exceeds configured limit",
              Some("invalid_session_id")
            )
          )

  /** 构造认证与会话 headers；token 中的 CR/LF 会在发送前被拒绝。 */
  private def buildRequest(
      base: Request,
      credentials: McpBearerTokenProvider,
      version: Ref[Option[McpProtocolVersion]],
      session: Ref[Option[String]],
      accept: String,
      includeContentType: Boolean,
      lastEventId: Option[String],
      includeSessionContext: Boolean
  ): IO[AgentError, Request] =
    for
      token          <- credentials.bearerToken
      protocol       <- if includeSessionContext then version.get else ZIO.none
      currentSession <- if includeSessionContext then session.get else ZIO.none
      _              <- ZIO.foreachDiscard(token) { value =>
        ZIO
          .fail(AgentError.InvalidConfiguration("Invalid MCP bearer token"))
          .when(
            value.trim.isEmpty || value.exists(char => char == '\r' || char == '\n')
          )
      }
      withAccept  = base.addHeader("Accept", accept)
      withContent =
        if includeContentType then withAccept.addHeader(Header.ContentType(MediaType.application.json))
        else withAccept
      withAuth = token.fold(withContent)(value => withContent.addHeader(Header.Authorization.Bearer(value)))
      withProtocol = protocol.fold(withAuth)(value => withAuth.addHeader(ProtocolHeader, value.value))
      withSession  = currentSession.fold(withProtocol)(value => withProtocol.addHeader(SessionHeader, value))
      complete     = lastEventId.fold(withSession)(value => withSession.addHeader("Last-Event-ID", value))
    yield complete

  /** 从客户端 envelope 提取可选 request id；notification/response 不需要 POST 断线恢复。 */
  private def optionalRequestId(message: Json.Obj): Either[AgentError, Option[McpRequestId]] =
    if McpJson.field(message, "method").isEmpty then Right(None)
    else
      McpJson.field(message, "id") match
        case None        => Right(None)
        case Some(value) => McpRequestId.fromJson(value).map(Some(_))

  /** session 关闭使用 DELETE；200/202/204/405 都视为完成。 */
  private def deleteSession(
      client: Client,
      config: StreamableHttpMcpTransportConfig,
      credentials: McpBearerTokenProvider,
      version: Ref[Option[McpProtocolVersion]],
      session: Ref[Option[String]]
  ): IO[AgentError, Unit] =
    session.get.flatMap {
      case None    => ZIO.unit
      case Some(_) =>
        buildRequest(
          Request.delete(config.endpoint),
          credentials,
          version,
          session,
          "application/json",
          includeContentType = false,
          lastEventId = None,
          includeSessionContext = true
        ).flatMap(request =>
          client
            .batched(request)
            .timeoutFail(
              AgentError.ExternalProtocolFailure(
                "mcp",
                "http/delete",
                "MCP session DELETE timed out",
                Some("http_timeout"),
                true
              )
            )(config.httpOperationTimeout)
            .mapError(mapHttpThrowable)
            .flatMap { response =>
              if response.status.isSuccess || response.status.code == 405 then session.set(None)
              else ZIO.fail(httpFailure(response.status.code, "session_delete_failed"))
            }
        )
    }

  /** HTTP 状态只进入低敏错误；响应 body 不拼入 message。 */
  private def httpFailure(status: Int, code: String): AgentError =
    AgentError.ExternalProtocolFailure(
      "mcp",
      "http/response",
      s"MCP HTTP request failed with status $status",
      Some(code),
      retryable = status == 408 || status == 409 || status == 425 || status == 429 || status >= 500
    )

  /** 保留已有 typed error；网络/TLS/连接池异常映射为可重试 transport failure。 */
  private def mapHttpThrowable(error: Throwable): AgentError = error match
    case value: AgentError => value
    case other             =>
      AgentError.ExternalProtocolFailure(
        "mcp",
        "http/transport",
        "MCP HTTP transport failed",
        Some("transport_failure"),
        retryable = true,
        cause = Some(other)
      )
