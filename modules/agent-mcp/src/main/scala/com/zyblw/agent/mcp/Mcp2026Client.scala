package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import zio.*
import zio.json.ast.Json

/** 只讲 MCP `2026-07-28` 的无状态客户端。不会发送 initialize/initialized，也不会保存 session。 */
final case class Mcp2026ClientConfig(
    serverId: McpServerId,
    clientInfo: McpImplementation,
    requestTimeout: Duration = 60.seconds,
    expectedProtocol: McpProtocolVersion = Mcp2026Protocol.Version
):
  def validate: Either[AgentError, Unit] =
    Either.cond(
      expectedProtocol == Mcp2026Protocol.Version &&
        clientInfo.name.trim.nonEmpty &&
        clientInfo.version.trim.nonEmpty &&
        requestTimeout > Duration.Zero,
      (),
      AgentError.InvalidConfiguration("Invalid MCP 2026 client configuration")
    )

final class Mcp2026Client(transport: McpTransport, config: Mcp2026ClientConfig):
  private val meta: Json.Obj = Mcp2026Protocol.requestMeta(config.clientInfo)

  def discover: IO[AgentError, Mcp2026Protocol.DiscoverResult] =
    call("server/discover", Json.Obj()).flatMap { result =>
      requireComplete("server/discover")(result) *>
        ZIO.fromEither(Mcp2026Protocol.parseDiscover(result))
    }

  def listTools: IO[AgentError, Json.Obj] =
    call("tools/list", Json.Obj()).flatMap { result =>
      requireComplete("tools/list")(result) *> requireCompleteObject("tools/list")(result)
    }

  def callTool(name: String, arguments: Json.Obj): IO[AgentError, Json.Obj] =
    invokeTool(name, arguments).flatMap {
      case Right(result) => ZIO.succeed(result)
      case Left(_)       =>
        ZIO.fail(
          McpJson.protocolError(
            "tools/call",
            "input_required is not auto-completed by this client",
            Some("input_required")
          )
        )
    }

  def invokeTool(
      name: String,
      arguments: Json.Obj,
      responses: Option[Json.Obj] = None,
      requestState: Option[String] = None
  ): IO[AgentError, Either[Mcp2026Protocol.InputRequired, Json.Obj]] =
    if name.trim.isEmpty then ZIO.fail(AgentError.InvalidConfiguration("mcp-2026-tool-name"))
    else
      val base          = Json.Obj("name" -> Json.Str(name), "arguments" -> arguments)
      val withResponses =
        responses.fold(base)(value => Json.Obj(base.fields :+ ("inputResponses" -> value)))
      val params = requestState.fold(withResponses)(state =>
        Json.Obj(withResponses.fields :+ ("requestState" -> Json.Str(state)))
      )
      call("tools/call", params).flatMap(decodeMrtr("tools/call"))

  def retryTool(
      name: String,
      arguments: Json.Obj,
      required: Mcp2026Protocol.InputRequired,
      responses: Json.Obj
  ): IO[AgentError, Json.Obj] =
    ZIO
      .fromEither(
        Mcp2026Protocol.retryParams(
          Json.Obj("name" -> Json.Str(name), "arguments" -> arguments),
          required,
          responses
        )
      )
      .flatMap { params =>
        call("tools/call", params).flatMap(decodeMrtr("tools/call")).flatMap {
          case Right(result) => ZIO.succeed(result)
          case Left(_)       =>
            ZIO.fail(
              McpJson.protocolError("tools/call", "retry still input_required", Some("input_required"))
            )
        }
      }

  def listen(types: Set[String]): IO[AgentError, Mcp2026Protocol.ListenAck] =
    ZIO.fromEither(Mcp2026Protocol.listenParams(types)).flatMap { params =>
      call("subscriptions/listen", params).flatMap { result =>
        ZIO.fromEither(Mcp2026Protocol.parseListenAck(result))
      }
    }

  private def decodeMrtr(
      operation: String
  )(result: Json): IO[AgentError, Either[Mcp2026Protocol.InputRequired, Json.Obj]] =
    ZIO.fromEither(Mcp2026Protocol.resultType(result)).flatMap {
      case Mcp2026Protocol.ResultType.Complete =>
        requireCompleteObject(operation)(result).map(Right(_))
      case Mcp2026Protocol.ResultType.InputRequired =>
        if !Mcp2026Protocol.MrtrMethods.contains(operation) then
          ZIO.fail(
            McpJson
              .protocolError(operation, "input_required not allowed on this method", Some("mrtr_forbidden"))
          )
        else ZIO.fromEither(Mcp2026Protocol.parseInputRequired(result).map(Left(_)))
    }

  private def call(method: String, params: Json.Obj): IO[AgentError, Json] =
    ZIO.fromEither(config.validate) *>
      ZIO.fromEither(Mcp2026Protocol.rejectLegacyMethod(method)) *>
      transport.request(method, Mcp2026Protocol.withMeta(params, meta), config.requestTimeout)

  private def requireComplete(operation: String)(result: Json): IO[AgentError, Unit] =
    ZIO.fromEither(Mcp2026Protocol.resultType(result)).flatMap {
      case Mcp2026Protocol.ResultType.Complete      => ZIO.unit
      case Mcp2026Protocol.ResultType.InputRequired =>
        ZIO.fail(
          McpJson
            .protocolError(operation, "input_required not allowed on this method", Some("mrtr_forbidden"))
        )
    }

  private def requireCompleteObject(operation: String)(result: Json): IO[AgentError, Json.Obj] =
    result match
      case obj: Json.Obj => ZIO.succeed(obj)
      case _             => ZIO.fail(McpJson.protocolError(operation, "result must be an object"))

object Mcp2026Client:
  def make(transport: McpTransport, config: Mcp2026ClientConfig): IO[AgentError, Mcp2026Client] =
    ZIO.fromEither(config.validate).as(Mcp2026Client(transport, config))
