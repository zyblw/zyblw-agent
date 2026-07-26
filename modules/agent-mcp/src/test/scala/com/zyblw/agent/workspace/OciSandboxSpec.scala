package com.zyblw.agent.workspace

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, Path}
import scala.util.Try
import zio.*
import zio.test.*

/** OCI Sandbox 的策略与真实进程生命周期测试。
  *
  * 测试分成两层：记录型 runner 验证不会因 CLI 版本变化而掩盖的安全 argv；JDK stub 验证并行排空、总输出预算、 非零退出码和墙钟超时。默认测试不要求本机安装
  * Docker，真实容器混沌测试属于单独的环境门禁。
  */
object OciSandboxSpec extends ZIOSpecDefault:
  private val immutableImage = "registry.example/zyblw/sandbox@sha256:" + "a" * 64

  /** 当前 JDK 的绝对 java 路径，真实 runner 清空 PATH 后仍可确定启动。 */
  private val javaExecutable: String =
    Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString

  /** JDK-only 测试 stub 所在的 test-classes 目录。 */
  private val testClassPath: String =
    classOf[SandboxProcessStub].getProtectionDomain.getCodeSource.getLocation.toURI.getPath

  /** 创建配置测试目录和一个可执行占位 runtime；记录型 runner 不会真正执行该文件。 */
  private def sandboxFixture: ZIO[Scope, Throwable, (Path, Path)] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val root    = Files.createTempDirectory("zyblw-oci-workspace-")
        val runtime = Files.createTempFile("zyblw-oci-runtime-", ".bin")
        Files.createDirectory(root.resolve("work"))
        if !runtime.toFile.setExecutable(true) then
          throw IllegalStateException("cannot mark runtime executable")
        root -> runtime
      }
    ) { case (root, runtime) =>
      ZIO.attemptBlocking {
        Files.deleteIfExists(root.resolve("work"))
        Files.deleteIfExists(root)
        Files.deleteIfExists(runtime)
        ()
      }.orDie
    }

  /** 记录低层请求但不启动容器，用于精确断言策略编译结果。 */
  final private class RecordingRunner(requests: Ref[Chunk[SandboxProcessRequest]])
      extends SandboxProcessRunner:
    def run(request: SandboxProcessRequest): IO[com.zyblw.agent.core.AgentError, SandboxResult] =
      requests.update(_ :+ request).as(SandboxResult(0, "ok", "", truncated = false))

  /** 不启动真实 Docker 的 scoped session factory，用来验证长连接与一次性命令复用完全相同的 hardening argv。 */
  final private class RecordingSessionFactory(requests: Ref[Chunk[SandboxProcessSessionRequest]])
      extends SandboxProcessSessionFactory:
    def open(
        request: SandboxProcessSessionRequest
    ): ZIO[Scope, com.zyblw.agent.core.AgentError, SandboxProcessSession] =
      requests
        .update(_ :+ request)
        .as(new SandboxProcessSession:
          val stdin: ByteArrayOutputStream                        = ByteArrayOutputStream()
          val stdout: ByteArrayInputStream                        = ByteArrayInputStream(Array.emptyByteArray)
          val stderr: ByteArrayInputStream                        = ByteArrayInputStream(Array.emptyByteArray)
          def awaitExit: IO[com.zyblw.agent.core.AgentError, Int] = ZIO.succeed(0)
          def isAlive: UIO[Boolean]                               = ZIO.succeed(true)
          def close: UIO[Unit]                                    = ZIO.unit)

  /** 判断 argv 中是否存在相邻的选项和值。 */
  private def containsPair(arguments: Chunk[String], option: String, value: String): Boolean =
    arguments.sliding(2).exists(pair => pair == Chunk(option, value))

  def spec = suite("OCI Sandbox")(
    test("生成默认断网、只读、最小权限和资源限额 argv，secret 值不进入进程参数") {
      ZIO.scoped {
        for
          fixture <- sandboxFixture
          (root, runtime) = fixture
          requests <- Ref.make(Chunk.empty[SandboxProcessRequest])
          executor = OciSandboxExecutor(
            OciSandboxConfig(runtime, immutableImage, root),
            RecordingRunner(requests)
          )
          result <- executor.execute(
            SandboxCommand(
              executable = "/usr/bin/tool",
              arguments = Chunk("--mode", "safe"),
              workingDirectory = WorkspacePath("work"),
              environment = Map("API_TOKEN" -> "super-secret-value"),
              timeout = 10.seconds,
              maxOutputBytes = 4096
            )
          )
          captured <- requests.get.map(_.head)
          args = captured.arguments
        yield assertTrue(
          result.exitCode == 0,
          containsPair(args, "--network", "none"),
          containsPair(args, "--ipc", "none"),
          args.contains("--read-only"),
          containsPair(args, "--cap-drop", "ALL"),
          containsPair(args, "--security-opt", "no-new-privileges=true"),
          containsPair(args, "--pull", "never"),
          containsPair(args, "--user", "65532:65532"),
          containsPair(args, "--env", "API_TOKEN"),
          args.contains(immutableImage),
          !args.contains("super-secret-value"),
          captured.environment.get("API_TOKEN").contains("super-secret-value")
        )
      }
    },
    test("可漂移镜像 tag 和 root 容器用户在配置构造期即被拒绝") {
      val root    = Path.of("/tmp/zyblw-workspace")
      val runtime = Path.of("/usr/bin/docker")
      assertTrue(
        Try(OciSandboxConfig(runtime, "example/sandbox:latest", root)).isFailure,
        Try(OciSandboxConfig(runtime, immutableImage, root, containerUser = "0:0")).isFailure
      )
    },
    test("模型不能通过运行时保留环境变量或扩大预算改变宿主策略") {
      ZIO.scoped {
        for
          fixture <- sandboxFixture
          (root, runtime) = fixture
          requests <- Ref.make(Chunk.empty[SandboxProcessRequest])
          executor = OciSandboxExecutor(
            OciSandboxConfig(runtime, immutableImage, root),
            RecordingRunner(requests)
          )
          reserved <- executor
            .execute(
              SandboxCommand(
                "/usr/bin/tool",
                Chunk.empty,
                WorkspacePath("work"),
                environment = Map("DOCKER_HOST" -> "tcp://attacker")
              )
            )
            .exit
          oversized <- executor
            .execute(
              SandboxCommand(
                "/usr/bin/tool",
                Chunk.empty,
                WorkspacePath("work"),
                timeout = 10.minutes
              )
            )
            .exit
          captured <- requests.get
        yield assertTrue(reserved.isFailure, oversized.isFailure, captured.isEmpty)
      }
    },
    test("scoped 双向会话复用同一 OCI hardening 策略且不把 secret 写入 argv") {
      ZIO.scoped {
        for
          fixture <- sandboxFixture
          (root, runtime) = fixture
          processCalls <- Ref.make(Chunk.empty[SandboxProcessRequest])
          sessionCalls <- Ref.make(Chunk.empty[SandboxProcessSessionRequest])
          executor = OciSandboxExecutor(
            OciSandboxConfig(runtime, immutableImage, root),
            RecordingRunner(processCalls),
            RecordingSessionFactory(sessionCalls)
          )
          session <- executor.open(
            SandboxSessionCommand(
              "/usr/local/bin/mcp-server",
              Chunk("--stdio"),
              WorkspacePath("work"),
              Map("MCP_TOKEN" -> "session-secret")
            )
          )
          alive        <- session.isAlive
          captured     <- sessionCalls.get.map(_.head)
          oneShotCalls <- processCalls.get
        yield assertTrue(
          alive,
          containsPair(captured.arguments, "--network", "none"),
          captured.arguments.contains("--read-only"),
          containsPair(captured.arguments, "--env", "MCP_TOKEN"),
          !captured.arguments.contains("session-secret"),
          captured.environment.get("MCP_TOKEN").contains("session-secret"),
          oneShotCalls.isEmpty
        )
      }
    },
    test("JDK runner 并行排空 stdout/stderr，共享总输出预算并保留退出码") {
      val request = SandboxProcessRequest(
        arguments =
          Chunk(javaExecutable, "-cp", testClassPath, classOf[SandboxProcessStub].getName, "output"),
        environment = Map.empty,
        timeout = 5.seconds,
        maxOutputBytes = 10,
        shutdownGrace = 200.millis
      )
      for
        result <- JdkSandboxProcessRunner().run(request)
        retained = result.stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8).length +
          result.stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      yield assertTrue(result.exitCode == 7, retained == 10, result.truncated)
    },
    test("JDK runner 对慢进程实施墙钟硬超时并返回可重试 typed error") {
      val request = SandboxProcessRequest(
        arguments = Chunk(javaExecutable, "-cp", testClassPath, classOf[SandboxProcessStub].getName, "slow"),
        environment = Map.empty,
        timeout = 100.millis,
        maxOutputBytes = 1024,
        shutdownGrace = 100.millis
      )
      JdkSandboxProcessRunner().run(request).exit.map { exit =>
        assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists {
            case com.zyblw.agent.core.AgentError.ExternalProtocolFailure(
                  "oci-sandbox",
                  "wait",
                  _,
                  Some("timeout"),
                  true,
                  _
                ) =>
              true
            case _ => false
          }
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
