package com.zyblw.agent.http.host

import com.zyblw.agent.core.*
import zio.*
import zio.http.*
import zio.test.*

/** 验证 HTTP Server、关键后台进程、健康路由和 Scope 构成一个一致的部署生命周期。
  *
  * 测试使用纯 `AgentHttpServer` stub 捕获 routes，不绑定真实端口；ZIO HTTP handler 仍通过 `Routes.runZIO` 执行，因此可
  * 确定性覆盖状态码、JSON、缓存头、readiness 超时和附加路由组合。
  */
object AgentHttpHostSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentHttpHost")(
    test("启动关键进程后 live/ready 成功，主 API 与附加业务 routes 均被安装") {
      for
        processStarted <- Promise.make[Nothing, Unit]
        processStopped <- Promise.make[Nothing, Unit]
        serverStopped  <- Promise.make[Nothing, Unit]
        captured       <- Promise.make[Nothing, Routes[Any, Nothing]]
        readinessCalls <- Ref.make(0)
        process        <- AgentHostProcess.make(
          "command-worker",
          (processStarted.succeed(()).unit *> ZIO.never)
            .onInterrupt(processStopped.succeed(()).unit)
        )
        processes <- AgentHostProcesses.make(Chunk(process))
        readiness = new AgentHostReadiness:
          def check: IO[AgentError, Unit] = readinessCalls.update(_ + 1)
        server  = capturingServer(captured, serverStopped)
        primary = AgentHttpPrimaryRoutes(
          Routes(Method.GET / "agents" / "probe" -> Handler.text("agent-api"))
        )
        additional = AgentHttpAdditionalRoutes(
          Routes(Method.GET / "business" / "ping" -> Handler.text("pong"))
        )
        config = AgentHttpHostConfig("agent-host-test", "1.0.0", "test", 1.second)
        result <- ZIO.scoped {
          (for
            host             <- ZIO.service[AgentHttpHost]
            fiber            <- host.serve.forkScoped
            routes           <- captured.await
            _                <- processStarted.await
            live             <- routes.runZIO(Request.get(URL.root / "health" / "live"))
            liveBody         <- live.body.asString
            ready            <- routes.runZIO(Request.get(URL.root / "health" / "ready"))
            readyBody        <- ready.body.asString
            apiResponse      <- routes.runZIO(Request.get(URL.root / "agents" / "probe"))
            businessResponse <- routes.runZIO(Request.get(URL.root / "business" / "ping"))
            businessBody     <- businessResponse.body.asString
            calls            <- readinessCalls.get
            _                <- fiber.interrupt
            _                <- processStopped.await
            _                <- serverStopped.await
          yield assertTrue(
            live.status == Status.Ok,
            liveBody.contains("\"check\":\"live\""),
            liveBody.contains("\"status\":\"up\""),
            !liveBody.contains("command-worker"),
            live.rawHeader("Cache-Control").contains("no-store"),
            ready.status == Status.Ok,
            readyBody.contains("\"status\":\"ready\""),
            apiResponse.status == Status.Ok,
            businessResponse.status == Status.Ok,
            businessBody == "pong",
            calls == 1
          )).provideSomeLayer[Scope](hostLayer(processes, primary, readiness, additional, server, config))
        }
      yield result
    },
    test("readiness 依赖失败只返回稳定 code，不泄漏数据库或 Provider 原文") {
      val privateFailure = "jdbc:postgresql://private-host/secret-db"
      for
        captured      <- Promise.make[Nothing, Routes[Any, Nothing]]
        serverStopped <- Promise.make[Nothing, Unit]
        process       <- AgentHostProcess.make("command-worker", ZIO.never)
        processes     <- AgentHostProcesses.make(Chunk(process))
        readiness = new AgentHostReadiness:
          def check: IO[AgentError, Unit] = ZIO.fail(AgentError.PersistenceFailure(privateFailure))
        server     = capturingServer(captured, serverStopped)
        primary    = AgentHttpPrimaryRoutes(Routes.empty)
        additional = AgentHttpAdditionalRoutes(Routes.empty)
        config     = AgentHttpHostConfig(readinessTimeout = 1.second)
        assertion <- ZIO.scoped {
          (for
            host   <- ZIO.service[AgentHttpHost]
            fiber  <- host.serve.forkScoped
            routes <- captured.await
            ready  <- routes.runZIO(Request.get(URL.root / "health" / "ready"))
            body   <- ready.body.asString
            _      <- fiber.interrupt
            _      <- serverStopped.await
          yield assertTrue(
            ready.status == Status.ServiceUnavailable,
            body.contains("dependency_unavailable"),
            !body.contains(privateFailure),
            !body.contains("postgresql")
          )).provideSomeLayer[Scope](hostLayer(processes, primary, readiness, additional, server, config))
        }
      yield assertion
    },
    test("readiness 有硬超时，不允许悬挂探针长期占用连接") {
      for
        captured      <- Promise.make[Nothing, Routes[Any, Nothing]]
        serverStopped <- Promise.make[Nothing, Unit]
        process       <- AgentHostProcess.make("command-worker", ZIO.never)
        processes     <- AgentHostProcesses.make(Chunk(process))
        readiness = new AgentHostReadiness:
          def check: IO[AgentError, Unit] = ZIO.never
        server = capturingServer(captured, serverStopped)
        config = AgentHttpHostConfig(readinessTimeout = 1.second)
        assertion <- ZIO.scoped {
          (for
            host         <- ZIO.service[AgentHttpHost]
            fiber        <- host.serve.forkScoped
            routes       <- captured.await
            requestFiber <- routes.runZIO(Request.get(URL.root / "health" / "ready")).fork
            _            <- TestClock.adjust(1.second)
            ready        <- requestFiber.join
            body         <- ready.body.asString
            _            <- fiber.interrupt
            _            <- serverStopped.await
          yield assertTrue(
            ready.status == Status.ServiceUnavailable,
            body.contains("dependency_timeout")
          )).provideSomeLayer[Scope](
            hostLayer(
              processes,
              AgentHttpPrimaryRoutes(Routes.empty),
              readiness,
              AgentHttpAdditionalRoutes(Routes.empty),
              server,
              config
            )
          )
        }
      yield assertion
    },
    test("任一关键进程失败会关闭 Server、标记 liveness 并返回安全 Host 错误") {
      val privateFailure = "provider-response-with-private-prompt"
      for
        captured      <- Promise.make[Nothing, Routes[Any, Nothing]]
        serverStopped <- Promise.make[Nothing, Unit]
        release       <- Promise.make[Nothing, Unit]
        process       <- AgentHostProcess.make(
          "command-worker",
          release.await *> ZIO.fail(AgentError.PersistenceFailure(privateFailure))
        )
        processes <- AgentHostProcesses.make(Chunk(process))
        server    = capturingServer(captured, serverStopped)
        readiness = new AgentHostReadiness:
          def check: UIO[Unit] = ZIO.unit
        assertion <- ZIO.scoped {
          (for
            host   <- ZIO.service[AgentHttpHost]
            fiber  <- host.serve.forkScoped
            routes <- captured.await
            _      <- release.succeed(())
            exit   <- fiber.await
            _      <- serverStopped.await
            live   <- routes.runZIO(Request.get(URL.root / "health" / "live"))
            body   <- live.body.asString
            rendered = exit.toString
          yield assertTrue(
            exit.isFailure,
            !rendered.contains(privateFailure),
            live.status == Status.ServiceUnavailable,
            body.contains("process_typed_failure"),
            !body.contains(privateFailure)
          )).provideSomeLayer[Scope](
            hostLayer(
              processes,
              AgentHttpPrimaryRoutes(Routes.empty),
              readiness,
              AgentHttpAdditionalRoutes(Routes.empty),
              server,
              AgentHttpHostConfig()
            )
          )
        }
      yield assertion
    },
    test("Server 自身失败会中断关键进程并保留 typed protocol error") {
      val bindFailure = new RuntimeException("private-bind-address")
      for
        processStopped <- Promise.make[Nothing, Unit]
        process        <- AgentHostProcess.make(
          "command-worker",
          ZIO.never.onInterrupt(processStopped.succeed(()).unit)
        )
        processes <- AgentHostProcesses.make(Chunk(process))
        server = new AgentHttpServer:
          def serve(routes: Routes[Any, Nothing]): IO[Throwable, Nothing] = ZIO.fail(bindFailure)
        readiness = new AgentHostReadiness:
          def check: UIO[Unit] = ZIO.unit
        exit <- ZIO.scoped {
          ZIO
            .serviceWithZIO[AgentHttpHost](_.serve)
            .provideSomeLayer[Scope](
              hostLayer(
                processes,
                AgentHttpPrimaryRoutes(Routes.empty),
                readiness,
                AgentHttpAdditionalRoutes(Routes.empty),
                server,
                AgentHttpHostConfig()
              )
            )
            .exit
        }
        _ <- processStopped.await
        error = exit match
          case Exit.Failure(cause) => cause.failureOption
          case Exit.Success(_)     => None
      yield assertTrue(
        error.exists(_.isInstanceOf[AgentError.ExternalProtocolFailure]),
        error.exists(_.message == "Agent HTTP Server 启动或运行失败"),
        error.exists(_.getCause == bindFailure)
      )
    },
    test("关键进程集合拒绝空集合和重复名称") {
      for
        processA    <- AgentHostProcess.make("worker", ZIO.never)
        processB    <- AgentHostProcess.make("worker", ZIO.never)
        empty       <- AgentHostProcesses.make(Chunk.empty).exit
        duplicate   <- AgentHostProcesses.make(Chunk(processA, processB)).exit
        invalidName <- AgentHostProcess.make("tenant/动态-worker", ZIO.never).exit
      yield assertTrue(empty.isFailure, duplicate.isFailure, invalidName.isFailure)
    }
  )

  /** 创建 Host 所需的完整依赖图；所有依赖都是当前测试独占值。 */
  private def hostLayer(
      processes: AgentHostProcesses,
      primary: AgentHttpPrimaryRoutes,
      readiness: AgentHostReadiness,
      additional: AgentHttpAdditionalRoutes,
      server: AgentHttpServer,
      config: AgentHttpHostConfig
  ): ULayer[AgentHttpHost] = ZLayer.make[AgentHttpHost](
    ZLayer.succeed(processes),
    ZLayer.succeed(primary),
    ZLayer.succeed(readiness),
    ZLayer.succeed(additional),
    ZLayer.succeed(server),
    ZLayer.succeed(config),
    AgentHttpHost.live
  )

  /** 捕获 Host 安装的 routes，并在 Server effect 被中断时发出可验证信号。 */
  private def capturingServer(
      captured: Promise[Nothing, Routes[Any, Nothing]],
      stopped: Promise[Nothing, Unit]
  ): AgentHttpServer = new AgentHttpServer:
    def serve(routes: Routes[Any, Nothing]): IO[Throwable, Nothing] =
      (captured.succeed(routes).unit *> ZIO.never).onInterrupt(stopped.succeed(()).unit)
