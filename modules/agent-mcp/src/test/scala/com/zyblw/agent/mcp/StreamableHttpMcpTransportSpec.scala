package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** MCP Streamable HTTP 的真实 ZIO HTTP stub 契约测试。
  *
  * 覆盖单 endpoint POST/GET/DELETE、双 Accept、session/version header、Bearer、JSON、SSE、Last-Event-ID 恢复、404 session
  * 失效和取消传播。
  */
object StreamableHttpMcpTransportSpec extends ZIOSpecDefault:

  /** 服务端收到的低敏请求投影；不保存 Authorization 的实际 token。 */
  final private case class RecordedHttp(
      method: String,
      rpcMethod: Option[String],
      accept: Option[String],
      protocol: Option[String],
      session: Option[String],
      lastEventId: Option[String],
      authenticated: Boolean
  )

  /** 从请求正文提取 JSON-RPC method。 */
  private def rpcMethod(body: String): Option[String] =
    body
      .fromJson[Json]
      .toOption
      .collect { case obj: Json.Obj => obj }
      .flatMap(McpJson.field(_, "method"))
      .collect { case Json.Str(value) =>
        value
      }

  /** 从请求正文提取 id，响应必须原样回填。 */
  private def requestId(body: String): Json =
    body
      .fromJson[Json]
      .toOption
      .collect { case obj: Json.Obj => obj }
      .flatMap(McpJson.field(_, "id"))
      .getOrElse(Json.Num(0))

  /** 构造 JSON-RPC 成功响应。 */
  private def success(id: Json, result: Json): String =
    Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> id, "result" -> result).toJson

  /** 初始化固定响应。 */
  private def initialize(id: Json): String = success(
    id,
    Json.Obj(
      "protocolVersion" -> Json.Str("2025-11-25"),
      "capabilities"    -> Json.Obj(
        "tools"     -> Json.Obj(),
        "resources" -> Json.Obj(),
        "prompts"   -> Json.Obj()
      ),
      "serverInfo" -> Json.Obj("name" -> Json.Str("http-stub"), "version" -> Json.Str("1.0.0"))
    )
  )

  /** 记录 headers，但只记录 Authorization 是否等于测试期望值，不把 token 放入 Ref。 */
  private def record(request: Request, body: String, records: Ref[Chunk[RecordedHttp]]): UIO[Unit] =
    records.update(
      _ :+ RecordedHttp(
        request.method.toString,
        rpcMethod(body),
        request.headers.get("Accept"),
        request.headers.get("MCP-Protocol-Version"),
        request.headers.get("MCP-Session-Id"),
        request.headers.get("Last-Event-ID"),
        request.headers.get("Authorization").contains("Bearer contract-secret")
      )
    )

  /** 正常服务器：tools/call 直接 SSE；tools/list 先断流，再由带 Last-Event-ID 的 GET 完成。
    */
  private def normalRoutes(
      records: Ref[Chunk[RecordedHttp]],
      resumedRequestId: Ref[Option[Json]]
  ): Routes[Any, Response] = Routes(
    Method.POST / "mcp" -> handler { (request: Request) =>
      request.body.asString
        .flatMap { body =>
          val method   = rpcMethod(body)
          val id       = requestId(body)
          val response = method match
            case Some("initialize") =>
              Response.json(initialize(id)).addHeader("MCP-Session-Id", "session-contract-1")
            case Some("notifications/initialized") => Response(status = Status.Accepted)
            case Some("tools/list")                =>
              val priming = "id: list-cursor-1\ndata:\n\n"
              Response(
                status = Status.Ok,
                headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
                body = Body.fromStreamChunked(
                  ZStream.fromIterable(priming.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
              )
            case Some("tools/call") =>
              val result = Json.Obj(
                "content" -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("ok"))),
                "structuredContent" -> Json.Obj("ok" -> Json.Bool(true))
              )
              val stream = s"id: call-finished\ndata: ${success(id, result)}\n\n"
              Response(
                status = Status.Ok,
                headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
                body = Body.fromStreamChunked(
                  ZStream.fromIterable(stream.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
              )
            case Some("notifications/cancelled") => Response(status = Status.Accepted)
            case _                               => Response.json(success(id, Json.Obj()))
          record(request, body, records) *>
            ZIO.when(method.contains("tools/list"))(resumedRequestId.set(Some(id))) *>
            ZIO.succeed(response)
        }
        .mapError(error => Response.internalServerError(error.getMessage))
    },
    Method.GET / "mcp" -> handler { (request: Request) =>
      val last = request.headers.get("Last-Event-ID")
      record(request, "", records) *>
        (last match
          // Last-Event-ID 是服务端定义的不透明 cursor；stub 只要求存在，避免把 header 渲染细节写死。
          case Some(_) =>
            resumedRequestId.get.map { id =>
              val result = Json.Obj(
                "tools" -> Json.Arr(
                  Json.Obj(
                    "name"        -> Json.Str("echo"),
                    "description" -> Json.Str("Echo"),
                    "inputSchema" -> Json.Obj("type" -> Json.Str("object"))
                  )
                )
              )
              val stream = s"id: list-finished\ndata: ${success(id.getOrElse(Json.Num(0)), result)}\n\n"
              Response(
                status = Status.Ok,
                headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
                body = Body.fromStreamChunked(
                  ZStream.fromIterable(stream.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                )
              )
            }
          case _ => ZIO.succeed(Response(status = Status.MethodNotAllowed)))
    },
    Method.DELETE / "mcp" -> handler { (request: Request) =>
      record(request, "", records).as(Response(status = Status.NoContent))
    }
  )

  /** 为当前 TestServer port 创建明确允许本地 HTTP 的 transport 配置。 */
  private def config(port: Int, listener: Boolean = true): StreamableHttpMcpTransportConfig =
    StreamableHttpMcpTransportConfig(
      endpoint = s"http://127.0.0.1:$port/mcp",
      allowInsecureHttp = true,
      defaultReconnectDelay = 10.millis,
      httpOperationTimeout = 3.seconds,
      enableServerListener = listener
    )

  /** Client 使用的稳定版配置。 */
  private val clientConfig = McpClientConfig(
    McpServerId("http-contract"),
    McpImplementation("zyblw-agent-test", "1.0.0"),
    requestTimeout = 3.seconds
  )

  def spec = suite("StreamableHttpMcpTransport")(
    test("JSON 初始化、SSE 调用、Last-Event-ID 恢复和 DELETE session 全部遵循稳定协议") {
      for
        records   <- Ref.make(Chunk.empty[RecordedHttp])
        resumedId <- Ref.make(Option.empty[Json])
        result    <- (for
          _      <- TestServer.addRoutes(normalRoutes(records, resumedId))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          value  <- ZIO.scoped {
            for
              transport <- StreamableHttpMcpTransport.scoped(
                client,
                config(port),
                McpBearerTokenProvider.static("contract-secret")
              )
              mcp    <- DefaultMcpClient.scoped(transport, clientConfig)
              tools  <- mcp.listTools
              called <- mcp.callTool("echo", Json.Obj("value" -> Json.Str("hello")))
            yield tools -> called
          }
          sent <- records.get
        yield (value, sent)).provide(Client.default, TestServer.default)
        sent              = result._2
        initializeRecord  = sent.find(_.rpcMethod.contains("initialize"))
        initializedRecord = sent.find(_.rpcMethod.contains("notifications/initialized"))
        resumed           = sent.find(_.lastEventId.contains("list-cursor-1"))
        deleted           = sent.find(_.method == Method.DELETE.toString)
      yield assertTrue(
        result._1._1.map(_.name) == Chunk("echo"),
        result._1._2.value == Json.Obj(
          "content"           -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("ok"))),
          "structuredContent" -> Json.Obj("ok" -> Json.Bool(true))
        ),
        initializeRecord.exists(_.accept.contains("application/json, text/event-stream")),
        initializeRecord.exists(_.protocol.isEmpty),
        initializeRecord.exists(_.session.isEmpty),
        initializeRecord.exists(_.authenticated),
        initializedRecord.exists(_.protocol.contains("2025-11-25")),
        initializedRecord.exists(_.session.contains("session-contract-1")),
        resumed.exists(_.lastEventId.contains("list-cursor-1")),
        resumed.exists(_.protocol.contains("2025-11-25")),
        deleted.exists(_.session.contains("session-contract-1")),
        sent.forall(_.authenticated)
      )
    },
    test("404 使 session 失效并返回稳定、可判定的错误") {
      for
        records <- Ref.make(Chunk.empty[RecordedHttp])
        routes = Routes(
          Method.POST / "mcp" -> handler { (request: Request) =>
            request.body.asString
              .flatMap { body =>
                val id       = requestId(body)
                val method   = rpcMethod(body)
                val response = method match
                  case Some("initialize") =>
                    Response.json(initialize(id)).addHeader("MCP-Session-Id", "expiring-session")
                  case Some("notifications/initialized") => Response(status = Status.Accepted)
                  case Some("tools/list")                => Response(status = Status.NotFound)
                  case _                                 => Response(status = Status.Accepted)
                record(request, body, records).as(response)
              }
              .mapError(error => Response.internalServerError(error.getMessage))
          }
        )
        exit <- (for
          _      <- TestServer.addRoutes(routes)
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          result <- ZIO.scoped {
            for
              transport <- StreamableHttpMcpTransport.scoped(client, config(port, listener = false))
              mcp       <- DefaultMcpClient.scoped(transport, clientConfig)
              exit      <- mcp.listTools.exit
              session   <- transport.currentSessionId
            yield exit -> session
          }
        yield result).provide(Client.default, TestServer.default)
      yield assertTrue(
        exit._1.causeOption.flatMap(_.failureOption).exists {
          case AgentError.ExternalProtocolFailure(_, "http/response", _, Some("session_expired"), false, _) =>
            true
          case _ => false
        },
        exit._2.isEmpty
      )
    },
    test("404 后串行重建 session，握手兼容时自动重放原请求") {
      for
        initializeCount <- Ref.make(0)
        listCount       <- Ref.make(0)
        records         <- Ref.make(Chunk.empty[RecordedHttp])
        routes = Routes(
          Method.POST / "mcp" -> handler { (request: Request) =>
            request.body.asString
              .flatMap { body =>
                val id = requestId(body)
                rpcMethod(body) match
                  case Some("initialize") =>
                    initializeCount.updateAndGet(_ + 1).flatMap { attempt =>
                      val session = if attempt == 1 then "session-old" else "session-new"
                      record(request, body, records).as(
                        Response.json(initialize(id)).addHeader("MCP-Session-Id", session)
                      )
                    }
                  case Some("notifications/initialized") =>
                    record(request, body, records).as(Response(status = Status.Accepted))
                  case Some("tools/list") =>
                    listCount.updateAndGet(_ + 1).flatMap { attempt =>
                      val response =
                        if attempt == 1 then Response(status = Status.NotFound)
                        else Response.json(success(id, Json.Obj("tools" -> Json.Arr())))
                      record(request, body, records).as(response)
                    }
                  case _ => record(request, body, records).as(Response(status = Status.Accepted))
              }
              .mapError(error => Response.internalServerError(error.getMessage))
          },
          Method.DELETE / "mcp" -> handler(Response(status = Status.NoContent))
        )
        result <- (for
          _      <- TestServer.addRoutes(routes)
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          tools  <- ZIO.scoped {
            for
              transport <- StreamableHttpMcpTransport.scoped(client, config(port, listener = false))
              mcp       <- DefaultMcpClient.scoped(transport, clientConfig)
              tools     <- mcp.listTools
            yield tools
          }
          inits <- initializeCount.get
          lists <- listCount.get
          sent  <- records.get
        yield (tools, inits, lists, sent)).provide(Client.default, TestServer.default)
        sessions = result._4.filter(_.rpcMethod.contains("tools/list")).flatMap(_.session)
      yield assertTrue(
        result._1.isEmpty,
        result._2 == 2,
        result._3 == 2,
        sessions == Chunk("session-old", "session-new"),
        result._4.count(_.rpcMethod.contains("notifications/initialized")) == 2
      )
    },
    test("HTTP 超时中断 POST，并通过第二个 POST 传播 notifications/cancelled") {
      for
        cancelled <- Promise.make[Nothing, Unit]
        routes = Routes(
          Method.POST / "mcp" -> handler { (request: Request) =>
            request.body.asString
              .flatMap { body =>
                rpcMethod(body) match
                  case Some("slow")                    => ZIO.never
                  case Some("notifications/cancelled") =>
                    cancelled.succeed(()).as(Response(status = Status.Accepted))
                  case _ => ZIO.succeed(Response(status = Status.Accepted))
              }
              .mapError(error => Response.internalServerError(error.getMessage))
          }
        )
        result <- (for
          _      <- TestServer.addRoutes(routes)
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          value  <- ZIO.scoped {
            for
              transport <- StreamableHttpMcpTransport.scoped(client, config(port, listener = false))
              exit      <- transport.request("slow", Json.Obj(), 100.millis).exit
              observed  <- cancelled.await.timeout(2.seconds)
              pending   <- transport.pendingCount
            yield (exit, observed, pending)
          }
        yield value).provide(Client.default, TestServer.default)
      yield assertTrue(result._1.isFailure, result._2.nonEmpty, result._3 == 0)
    },
    test("endpoint 禁止 query/user-info 和默认明文 HTTP") {
      val withTokenQuery =
        StreamableHttpMcpTransportConfig("https://example.com/mcp?access_token=secret").validate
      val withUserInfo = StreamableHttpMcpTransportConfig("https://user:pass@example.com/mcp").validate
      val insecure     = StreamableHttpMcpTransportConfig("http://example.com/mcp").validate
      val secure       = StreamableHttpMcpTransportConfig("https://example.com/mcp").validate
      assertTrue(withTokenQuery.isLeft, withUserInfo.isLeft, insecure.isLeft, secure.isRight)
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
