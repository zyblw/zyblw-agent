package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** MCP 2026 Streamable HTTP：只 POST，带 `Mcp-Method`/`Mcp-Name`，拒绝 session/GET/DELETE。 */
object Mcp2026HttpTransportSpec extends ZIOSpecDefault:

  final private case class RecordedHttp(
      method: String,
      rpcMethod: Option[String],
      mcpMethod: Option[String],
      mcpName: Option[String],
      protocol: Option[String],
      session: Option[String],
      lastEventId: Option[String]
  )

  private def rpcMethod(body: String): Option[String] =
    body
      .fromJson[Json]
      .toOption
      .collect { case obj: Json.Obj => obj }
      .flatMap(McpJson.field(_, "method"))
      .collect { case Json.Str(value) => value }

  private def requestId(body: String): Json =
    body
      .fromJson[Json]
      .toOption
      .collect { case obj: Json.Obj => obj }
      .flatMap(McpJson.field(_, "id"))
      .getOrElse(Json.Num(0))

  private def success(id: Json, result: Json): String =
    Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> id, "result" -> result).toJson

  private def record(request: Request, body: String, records: Ref[Chunk[RecordedHttp]]): UIO[Unit] =
    records.update(
      _ :+ RecordedHttp(
        request.method.toString,
        rpcMethod(body),
        request.headers.get("Mcp-Method"),
        request.headers.get("Mcp-Name"),
        request.headers.get("MCP-Protocol-Version"),
        request.headers.get("MCP-Session-Id").orElse(request.headers.get("Mcp-Session-Id")),
        request.headers.get("Last-Event-ID")
      )
    )

  private def complete(result: Json): Json =
    result match
      case obj: Json.Obj => Json.Obj(obj.fields :+ ("resultType" -> Json.Str("complete")))
      case other         => other

  private val clientConfig = Mcp2026ClientConfig(
    McpServerId("http-2026"),
    McpImplementation("zyblw-agent-test", "1.0.0"),
    requestTimeout = 3.seconds
  )

  private def config(port: Int): Mcp2026HttpTransportConfig =
    Mcp2026HttpTransportConfig(
      endpoint = s"http://127.0.0.1:$port/mcp",
      allowInsecureHttp = true,
      httpOperationTimeout = 3.seconds
    )

  def spec = suite("Mcp2026HttpTransport")(
    test("只 POST discover/list/call，并发送 Mcp-Method/Mcp-Name，关闭时不 DELETE") {
      for
        records <- Ref.make(Chunk.empty[RecordedHttp])
        routes = Routes(
          Method.POST / "mcp" -> handler { (request: Request) =>
            request.body.asString
              .flatMap { body =>
                val id     = requestId(body)
                val method = rpcMethod(body)
                val result = method match
                  case Some("server/discover") =>
                    complete(
                      Json.Obj(
                        "protocolVersion" -> Json.Str("2026-07-28"),
                        "serverInfo"      -> Json.Obj("name" -> Json.Str("stub"), "version" -> Json.Str("1")),
                        "capabilities"    -> Json.Obj("tools" -> Json.Obj())
                      )
                    )
                  case Some("tools/list") =>
                    complete(
                      Json.Obj(
                        "tools"      -> Json.Arr(),
                        "ttlMs"      -> Json.Num(0),
                        "cacheScope" -> Json.Str("public")
                      )
                    )
                  case Some("tools/call") =>
                    complete(
                      Json.Obj(
                        "content" -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("ok")))
                      )
                    )
                  case _ => complete(Json.Obj())
                record(request, body, records).as(Response.json(success(id, result)))
              }
              .mapError(error => Response.internalServerError(error.getMessage))
          },
          Method.GET / "mcp"    -> handler(Response.status(Status.MethodNotAllowed)),
          Method.DELETE / "mcp" -> handler(Response.status(Status.MethodNotAllowed))
        )
        result <- (for
          _      <- TestServer.addRoutes(routes)
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          value  <- ZIO.scoped {
            for
              transport <- Mcp2026HttpTransport.scoped(client, config(port))
              mcp       <- Mcp2026Client.make(transport, clientConfig)
              found     <- mcp.discover
              _         <- mcp.listTools
              _         <- mcp.callTool("lookup", Json.Obj("q" -> Json.Str("1")))
            yield found
          }
          sent <- records.get
        yield (value, sent)).provide(Client.default, TestServer.default)
        sent = result._2
      yield assertTrue(
        result._1.protocolVersion == "2026-07-28",
        sent.forall(_.method == Method.POST.toString),
        sent.map(_.rpcMethod) == Chunk(Some("server/discover"), Some("tools/list"), Some("tools/call")),
        sent.forall(_.protocol.contains("2026-07-28")),
        sent.forall(_.session.isEmpty),
        sent.forall(_.lastEventId.isEmpty),
        sent.exists(row => row.rpcMethod.contains("tools/call") && row.mcpName.contains("lookup")),
        sent.forall(row => row.mcpMethod == row.rpcMethod)
      )
    },
    test("服务端返回 session 头时 fail-closed，且客户端从不 initialize") {
      for
        records <- Ref.make(Chunk.empty[RecordedHttp])
        routes = Routes(
          Method.POST / "mcp" -> handler { (request: Request) =>
            request.body.asString
              .flatMap { body =>
                record(request, body, records).as(
                  Response
                    .json(success(requestId(body), complete(Json.Obj())))
                    .addHeader("MCP-Session-Id", "should-not-exist")
                )
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
              transport <- Mcp2026HttpTransport.scoped(client, config(port))
              mcp       <- Mcp2026Client.make(transport, clientConfig)
              failed    <- mcp.discover.exit
            yield failed
          }
          sent <- records.get
        yield (result, sent)).provide(Client.default, TestServer.default)
      yield assertTrue(
        exit._1.isFailure,
        exit._2.forall(!_.rpcMethod.contains("initialize")),
        exit._1.causeOption.flatMap(_.failureOption).exists {
          case AgentError.ExternalProtocolFailure(_, _, _, Some("stateless_session_forbidden"), _, _) => true
          case _                                                                                      => false
        }
      )
    },
    test("生产配置拒绝明文 HTTP、query token 和私网") {
      assertTrue(
        Mcp2026HttpTransportConfig("https://mcp.example/mcp").validate.isRight,
        Mcp2026HttpTransportConfig("http://mcp.example/mcp").validate.isLeft,
        Mcp2026HttpTransportConfig("https://mcp.example/mcp?token=1").validate.isLeft,
        Mcp2026HttpTransportConfig("http://127.0.0.1:9/mcp", allowInsecureHttp = true).validate.isRight,
        Mcp2026HttpTransportConfig("http://example.com/mcp", allowInsecureHttp = true).validate.isLeft
      )
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
