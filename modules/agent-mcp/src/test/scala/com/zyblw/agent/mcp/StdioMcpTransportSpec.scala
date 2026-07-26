package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import com.zyblw.agent.workspace.*
import java.nio.file.Path
import zio.*
import zio.json.ast.Json
import zio.test.*

/** stdio transport 的真实子进程契约测试。
  *
  * 这些测试覆盖 Process/pipe/UTF-8/换行/终止和 ZIO Fiber 取消边界，不能由脚本化 transport 代替。
  */
object StdioMcpTransportSpec extends ZIOSpecDefault:

  /** 测试专用 Sandbox launcher。
    *
    * 它使用真实 `JdkSandboxProcessSessionFactory` 启动同一个 JDK stub，但跳过 OCI CLI；OCI hardening argv 已由
    * `OciSandboxSpec` 独立验证，这里专注验证 MCP 是否正确使用双向 session 与 Scope 回收。
    */
  final private class DirectSandboxLauncher(
      mode: String,
      sessionRef: Ref[Option[SandboxProcessSession]]
  ) extends SandboxSessionLauncher:
    def open(command: SandboxSessionCommand): ZIO[Scope, AgentError, SandboxProcessSession] =
      JdkSandboxProcessSessionFactory()
        .open(
          SandboxProcessSessionRequest(
            Chunk(javaExecutable, "-cp", testClassPath, classOf[McpStdioStub].getName, mode),
            Map.empty,
            500.millis
          )
        )
        .tap(session => sessionRef.set(Some(session)))

  /** 取得当前 JDK 的绝对 java 路径，避免依赖被清空后的 PATH。 */
  private val javaExecutable: String =
    Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString

  /** 测试 stub 的 class 目录；stub 只依赖 JDK，因此无需拼接完整 SBT classpath。 */
  private val testClassPath: String =
    classOf[McpStdioStub].getProtectionDomain.getCodeSource.getLocation.toURI.getPath

  /** 为指定故障模式创建最小权限进程配置。 */
  private def transportConfig(mode: String, maxLineChars: Int = 1024 * 1024): StdioMcpTransportConfig =
    StdioMcpTransportConfig(
      command = Chunk(javaExecutable, "-cp", testClassPath, classOf[McpStdioStub].getName, mode),
      inheritParentEnvironment = false,
      maxLineChars = maxLineChars,
      shutdownGrace = 500.millis,
      terminateGrace = 500.millis
    )

  /** 与真实 stub 协商稳定版 MCP 的 client 配置。 */
  private val clientConfig = McpClientConfig(
    McpServerId("stdio-contract"),
    McpImplementation("zyblw-agent-test", "1.0.0"),
    requestTimeout = 2.seconds
  )

  def spec = suite("StdioMcpTransport")(
    test("真实进程完成 initialize、工具发现和工具调用，并在 Scope 结束后退出") {
      for
        transportRef <- Ref.make(Option.empty[StdioMcpTransport])
        result       <- ZIO.scoped {
          for
            transport <- StdioMcpTransport.scoped(transportConfig("normal"))
            _         <- transportRef.set(Some(transport))
            client    <- DefaultMcpClient.scoped(transport, clientConfig)
            tools     <- client.listTools
            called    <- client.callTool("echo", Json.Obj("value" -> Json.Str("hello")))
          yield tools -> called
        }
        transport <- transportRef.get.someOrFail(new RuntimeException("transport missing"))
        alive     <- transport.isProcessAlive
      yield assertTrue(
        result._1.map(_.name) == Chunk("echo"),
        result._2.value == Json.Obj(
          "content"           -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("ok"))),
          "structuredContent" -> Json.Obj("ok" -> Json.Bool(true))
        ),
        !alive
      )
    },
    test("请求硬超时发送取消通知并清理 pending") {
      ZIO.scoped {
        for
          transport <- StdioMcpTransport.scoped(transportConfig("normal"))
          timedOut  <- transport.request("slow", Json.Obj(), 100.millis).exit
          statusRaw <- transport.request("cancellation/status", Json.Obj(), 2.seconds)
          pending   <- transport.pendingCount
          cancelled = statusRaw match
            case obj: Json.Obj => McpJson.field(obj, "cancelled").contains(Json.Bool(true))
            case _             => false
        yield assertTrue(
          timedOut.causeOption.flatMap(_.failureOption).exists {
            case AgentError.ExternalProtocolFailure(_, "slow", _, Some("request_timeout"), true, _) => true
            case _                                                                                  => false
          },
          cancelled,
          pending == 0
        )
      }
    },
    test("调用 Fiber 中断同样传播 cancelled，且不伪装成普通失败") {
      ZIO.scoped {
        for
          transport <- StdioMcpTransport.scoped(transportConfig("normal"))
          fiber     <- transport.request("slow", Json.Obj(), 10.seconds).fork
          _         <- ZIO.sleep(100.millis)
          exit      <- fiber.interrupt
          statusRaw <- transport.request("cancellation/status", Json.Obj(), 2.seconds)
          pending   <- transport.pendingCount
          cancelled = statusRaw match
            case obj: Json.Obj => McpJson.field(obj, "cancelled").contains(Json.Bool(true))
            case _             => false
        yield assertTrue(exit.isInterrupted, cancelled, pending == 0)
      }
    },
    test("stdout 非 JSON 内容是致命协议错误，stderr 敏感正文不进入错误") {
      ZIO.scoped {
        for
          transport <- StdioMcpTransport.scoped(transportConfig("malformed"))
          exit      <- transport.request("any", Json.Obj(), 2.seconds).exit
          rendered = exit.causeOption.flatMap(_.failureOption).map(_.message).getOrElse("")
        yield assertTrue(
          exit.isFailure,
          rendered.contains("invalid JSON payload"),
          !rendered.contains("sensitive-stderr-content")
        )
      }
    },
    test("超长 stdout 在分配无界字符串前被拒绝") {
      ZIO.scoped {
        for
          transport <- StdioMcpTransport.scoped(transportConfig("oversized", maxLineChars = 256))
          exit      <- transport.request("any", Json.Obj(), 2.seconds).exit
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists {
            case AgentError.ExternalProtocolFailure(_, "stdio/stdout", _, Some("line_too_long"), _, _) => true
            case _ => false
          }
        )
      }
    },
    test("Sandbox session 路径完成 MCP 协商与工具调用，并随 Scope 回收真实进程") {
      for
        sessionRef <- Ref.make(Option.empty[SandboxProcessSession])
        launcher = DirectSandboxLauncher("normal", sessionRef)
        result <- ZIO
          .scoped {
            for
              transport <- StdioMcpTransport.sandboxedScoped(
                SandboxSessionCommand(
                  "/usr/local/bin/mcp-server",
                  Chunk("--stdio"),
                  WorkspacePath("mcp")
                )
              )
              client <- DefaultMcpClient.scoped(transport, clientConfig)
              tools  <- client.listTools
              called <- client.callTool("echo", Json.Obj("value" -> Json.Str("sandbox")))
            yield tools -> called
          }
          .provideLayer(ZLayer.succeed(launcher: SandboxSessionLauncher))
        session <- sessionRef.get.someOrFail(new RuntimeException("sandbox session missing"))
        alive   <- session.isAlive
      yield assertTrue(
        result._1.map(_.name) == Chunk("echo"),
        result._2.value == Json.Obj(
          "content"           -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("ok"))),
          "structuredContent" -> Json.Obj("ok" -> Json.Bool(true))
        ),
        !alive
      )
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
