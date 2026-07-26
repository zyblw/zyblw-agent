package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import zio.*
import zio.json.ast.Json
import zio.test.*

/** MCP 2025-11-25 客户端生命周期和协议契约测试。
  *
  * 测试使用脚本化 transport，但被测对象仍是完整 `DefaultMcpClient`；因此 initialize 顺序、能力协商、 cursor 分页、结果类型检查和 Scope 关闭都会真实执行。
  */
object DefaultMcpClientSpec extends ZIOSpecDefault:

  /** 创建稳定版初始化结果，能力参数允许每个测试精确控制可调用面。 */
  private def initializeResult(capabilities: Json.Obj, version: String = "2025-11-25"): Json.Obj =
    Json.Obj(
      "protocolVersion" -> Json.Str(version),
      "capabilities"    -> capabilities,
      "serverInfo"      -> Json.Obj(
        "name"    -> Json.Str("contract-server"),
        "version" -> Json.Str("1.0.0")
      ),
      "instructions" -> Json.Str("仅用于契约测试")
    )

  /** 所有测试共享的本地 client 配置；serverId 是本地可信标识。 */
  private val config = McpClientConfig(
    McpServerId("trusted-contract-server"),
    McpImplementation("zyblw-agent-test", "1.0.0")
  )

  def spec = suite("DefaultMcpClient")(
    test("initialize/initialized、能力快照与 Scope 关闭形成完整生命周期") {
      for
        transport <- ScriptedMcpTransport.successful(
          Map(
            "initialize" -> Chunk(
              initializeResult(
                Json.Obj(
                  "tools"     -> Json.Obj("listChanged" -> Json.Bool(true)),
                  "resources" -> Json.Obj("subscribe" -> Json.Bool(true)),
                  "prompts"   -> Json.Obj()
                )
              )
            )
          )
        )
        session       <- ZIO.scoped(DefaultMcpClient.scoped(transport, config).map(_.session))
        requests      <- transport.requests
        notifications <- transport.notificationsSent
        version       <- transport.version
        closed        <- transport.isClosed
      yield assertTrue(
        session.serverId == McpServerId("trusted-contract-server"),
        session.serverInfo.name == "contract-server",
        session.capabilities.tools,
        session.capabilities.toolsListChanged,
        session.capabilities.resourcesSubscribe,
        requests.map(_.method) == Chunk("initialize"),
        notifications.map(_._1) == Chunk("notifications/initialized"),
        version.contains(McpProtocolVersion.Stable2025_11_25),
        closed
      )
    },
    test("工具目录分页、结构化结果和 cursor 参数保持确定性") {
      val firstPage = Json.Obj(
        "tools" -> Json.Arr(
          Json.Obj(
            "name"        -> Json.Str("lookup"),
            "description" -> Json.Str("查询知识"),
            "inputSchema" -> Json.Obj("type" -> Json.Str("object"))
          )
        ),
        "nextCursor" -> Json.Str("page-2")
      )
      val secondPage = Json.Obj(
        "tools" -> Json.Arr(
          Json.Obj(
            "name"         -> Json.Str("summarize"),
            "inputSchema"  -> Json.Obj("type" -> Json.Str("object")),
            "outputSchema" -> Json.Obj("type" -> Json.Str("object"))
          )
        )
      )
      val callResult = Json.Obj(
        "content" -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("不进入日志的工具正文"))),
        "structuredContent" -> Json.Obj("answer" -> Json.Str("ok")),
        "isError"           -> Json.Bool(false)
      )
      for
        transport <- ScriptedMcpTransport.successful(
          Map(
            "initialize" -> Chunk(initializeResult(Json.Obj("tools" -> Json.Obj()))),
            "tools/list" -> Chunk(firstPage, secondPage),
            "tools/call" -> Chunk(callResult)
          )
        )
        result <- ZIO.scoped {
          for
            client <- DefaultMcpClient.scoped(transport, config)
            tools  <- client.listTools
            called <- client.callTool("lookup", Json.Obj("query" -> Json.Str("阴阳")))
          yield tools -> called
        }
        requests <- transport.requests
        secondCursor = requests
          .filter(_.method == "tools/list")
          .lift(1)
          .flatMap(request =>
            McpJson.field(request.params, "cursor").collect { case Json.Str(value) => value }
          )
      yield assertTrue(
        result._1.map(_.name) == Chunk("lookup", "summarize"),
        result._1(1).outputSchema.nonEmpty,
        result._2.value == Json.Obj(
          "content" -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("不进入日志的工具正文"))),
          "structuredContent" -> Json.Obj("answer" -> Json.Str("ok"))
        ),
        !result._2.isError,
        secondCursor.contains("page-2")
      )
    },
    test("未协商能力时 fail-closed，且不会向远端发送越权方法") {
      for
        transport <- ScriptedMcpTransport.successful(
          Map("initialize" -> Chunk(initializeResult(Json.Obj())))
        )
        exit <- ZIO.scoped {
          DefaultMcpClient.scoped(transport, config).flatMap(_.listTools).exit
        }
        requests <- transport.requests
      yield assertTrue(
        exit.isFailure,
        requests.map(_.method) == Chunk("initialize"),
        exit.causeOption.flatMap(_.failureOption).exists {
          case AgentError
                .ExternalProtocolFailure(_, "tools/list", _, Some("capability_not_negotiated"), _, _) =>
            true
          case _ => false
        }
      )
    },
    test("重复 cursor 被识别为协议环，避免无限分页") {
      val page = Json.Obj("resources" -> Json.Arr(), "nextCursor" -> Json.Str("same"))
      for
        transport <- ScriptedMcpTransport.successful(
          Map(
            "initialize"     -> Chunk(initializeResult(Json.Obj("resources" -> Json.Obj()))),
            "resources/list" -> Chunk(page, page)
          )
        )
        exit <- ZIO.scoped(DefaultMcpClient.scoped(transport, config).flatMap(_.listResources).exit)
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists {
          case AgentError.ExternalProtocolFailure(_, "resources/list", _, Some("cursor_cycle"), _, _) => true
          case _                                                                                      => false
        }
      )
    },
    test("反向 sampling 在未声明能力时由能力门禁拒绝，handler 无法绕过") {
      for
        handlerCalls <- Ref.make(0)
        transport    <- ScriptedMcpTransport.successful(
          Map("initialize" -> Chunk(initializeResult(Json.Obj())))
        )
        handler = new McpClientRequestHandler:
          def handle(serverId: McpServerId, method: String, params: Json.Obj) =
            handlerCalls.update(_ + 1).as(Right(Json.Obj()))
        _ <- ZIO.scoped {
          for
            _ <- DefaultMcpClient.scoped(transport, config, handler)
            _ <- transport.offerInbound(
              McpInbound.Request(McpRequestId.numeric(99L), "sampling/createMessage", Json.Obj())
            )
            _ <- transport.responsesSent.repeatUntil(_.nonEmpty).unit
          yield ()
        }
        calls     <- handlerCalls.get
        responses <- transport.responsesSent
      yield assertTrue(
        calls == 0,
        responses.headOption.exists(_._2.left.exists(_.code == -32601))
      )
    }
  )
