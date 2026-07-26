package com.zyblw.agent.http.host

import com.zyblw.agent.app.AgentApplication
import com.zyblw.agent.core.*
import com.zyblw.agent.http.AgentHttpApi
import javax.sql.DataSource
import zio.*
import zio.http.*
import zio.json.*

/** 宿主关键组件的低敏生命周期状态。
  *
  * 状态只描述进程内 Worker，不复制 Run/Command 状态。失败原因使用稳定 code，绝不保存 Provider 原文、SQL、命令正文或 Cause pretty-print。
  */
private[host] enum AgentHostState:
  case Starting
  case Running
  case Failed(code: String)
  case Stopping

/** 健康接口的稳定 JSON 协议；不会暴露 Worker ID、数据库地址、模型名称或密钥。 */
final case class AgentHostHealthResponse(
    service: String,
    version: String,
    environment: String,
    check: String,
    status: String,
    code: Option[String]
) derives JsonCodec

/** 由业务宿主提供的 readiness 检查。
  *
  * 生产实现通常执行数据库/command queue 的轻量只读探测；不建议在每个 Kubernetes readiness 请求中调用付费模型 Provider。 失败信息仅供内部日志/指标使用，HTTP
  * 响应只输出 `dependency_unavailable` 或 `dependency_timeout`。
  */
trait AgentHostReadiness:
  /** 检查当前实例是否具备接收新命令并由 Worker 推进的基础依赖。 */
  def check: IO[AgentError, Unit]

object AgentHostReadiness:
  /** 显式的无依赖检查，仅适用于教程、测试或已有外部 readiness 管理的嵌入式部署。 生产独立服务应提供真实数据库/队列探测，而不是依赖此默认值。
    */
  val alwaysReady: ULayer[AgentHostReadiness] = ZLayer.succeed(
    new AgentHostReadiness:
      def check: UIO[Unit] = ZIO.unit
  )

  /** 从一个已经完成脱敏和超时设计的 effect 构造检查层。 */
  def fromEffect(effect: IO[AgentError, Unit]): ULayer[AgentHostReadiness] =
    ZLayer.succeed(
      new AgentHostReadiness:
        def check: IO[AgentError, Unit] = effect
    )

  /** JDBC/PostgreSQL 独立部署的基础 readiness 实现。
    *
    * 每次检查只借用一个连接并执行 `SELECT 1`，连接和 statement 都在成功、失败与中断路径关闭。Host 外层还会施加 `readinessTimeout`，因此连接池耗尽不会让
    * Kubernetes 探针永久悬挂。错误摘要不包含 JDBC URL、用户名或 SQLState； 原始异常只保留在内部 cause chain。
    */
  val jdbc: URLayer[DataSource, AgentHostReadiness] = ZLayer.fromFunction { (dataSource: DataSource) =>
    new AgentHostReadiness:
      def check: IO[AgentError, Unit] = ZIO.scoped {
        ZIO
          .acquireRelease(
            ZIO.attemptBlockingInterrupt(dataSource.getConnection)
          )(connection => ZIO.attemptBlocking(connection.close()).ignore)
          .flatMap { connection =>
            ZIO
              .acquireRelease(
                ZIO.attemptBlockingInterrupt(connection.prepareStatement("SELECT 1"))
              )(statement => ZIO.attemptBlocking(statement.close()).ignore)
              .flatMap(statement =>
                ZIO.attemptBlockingInterrupt {
                  val result = statement.executeQuery()
                  try
                    if !result.next() || result.getInt(1) != 1 then
                      throw IllegalStateException("database readiness probe returned unexpected result")
                  finally result.close()
                }
              )
          }
          .unit
          .mapError(cause =>
            AgentError.PersistenceFailure("Agent readiness database probe failed", Some(cause))
          )
      }
  }

/** 附加业务路由集合。
  *
  * `AgentHttpApi` 路由始终由 Host 安装；Memory API、业务反馈、管理端或其他已鉴权 routes 通过该值显式合并。固定 `/health/live` 与 `/health/ready`
  * 路径由 Host 保留，业务不得重复定义。
  */
final case class AgentHttpAdditionalRoutes(routes: Routes[Any, Nothing])

object AgentHttpAdditionalRoutes:
  /** 没有额外业务 API 的显式空集合。 */
  val empty: ULayer[AgentHttpAdditionalRoutes] = ZLayer.succeed(AgentHttpAdditionalRoutes(Routes.empty))

  /** 把调用方已经消除错误通道的 routes 包装为依赖层。 */
  def layer(routes: Routes[Any, Nothing]): ULayer[AgentHttpAdditionalRoutes] =
    ZLayer.succeed(AgentHttpAdditionalRoutes(routes))

/** Host 必须安装的主 Agent API routes；单独包装后，核心生命周期测试无需伪造整套 Runtime。 */
final case class AgentHttpPrimaryRoutes(routes: Routes[Any, Nothing])

object AgentHttpPrimaryRoutes:
  /** 从现有异步 AgentHttpApi 投影 routes，不复制任何 handler 或状态机。 */
  val fromApi: URLayer[AgentHttpApi, AgentHttpPrimaryRoutes] =
    ZLayer.fromFunction((api: AgentHttpApi) => AgentHttpPrimaryRoutes(api.routes))

/** 与 HTTP Server 同生共死的关键后台进程。
  *
  * 除主 `WorkerHost` 外，独立部署可加入 outbox publisher、memory retention 等进程。只有确实必须随实例存活的任务才应放入 此集合；普通定时维护任务失败不一定值得关闭整个
  * HTTP 服务，应由业务按可靠性需求决定。
  *
  * @param name
  *   低基数稳定名称，只用于诊断，不含 pod ID、租户或 request ID
  * @param run
  *   永不正常返回的后台 effect；完成或失败都被视为关键进程退出
  */
final case class AgentHostProcess private (name: String, run: IO[AgentError, Nothing])

object AgentHostProcess:
  /** 校验名称后创建关键进程描述。 */
  def make(
      name: String,
      run: IO[AgentError, Nothing]
  ): IO[AgentError.InvalidConfiguration, AgentHostProcess] =
    val normalized = name.trim
    if normalized.nonEmpty && normalized.length <= 64 && normalized.forall(character =>
        character.isLetterOrDigit || character == '-' || character == '_'
      )
    then ZIO.succeed(AgentHostProcess(normalized, run))
    else ZIO.fail(AgentError.InvalidConfiguration("Agent Host process name 仅允许 1..64 个字母、数字、- 或 _"))

/** 至少包含一个关键进程且名称唯一的不可变集合。 */
final case class AgentHostProcesses private (values: NonEmptyChunk[AgentHostProcess])

object AgentHostProcesses:
  /** 校验非空和重名后构造集合。
    * @param processes
    *   主 Worker 以及业务显式选择与 Host 共命运的其他后台进程
    */
  def make(processes: Chunk[AgentHostProcess]): IO[AgentError.InvalidConfiguration, AgentHostProcesses] =
    for
      values <- ZIO
        .fromOption(NonEmptyChunk.fromChunk(processes))
        .orElseFail(
          AgentError.InvalidConfiguration("Agent HTTP Host 至少需要一个关键后台进程")
        )
      names = values.map(_.name)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("Agent HTTP Host 关键后台进程名称不能重复"))
        .when(names.distinct.length != names.length)
    yield AgentHostProcesses(values)

  /** 从已验证集合创建 Layer。 */
  def layer(processes: Chunk[AgentHostProcess]): Layer[AgentError.InvalidConfiguration, AgentHostProcesses] =
    ZLayer.fromZIO(make(processes))

  /** 把 AgentApplication 的耐久 command Worker 设为第一个关键进程，并可追加业务进程。 追加 effect 应已经捕获所需依赖，且遵守 Scope/中断传播语义。
    */
  def fromApplication(
      additional: Chunk[AgentHostProcess] = Chunk.empty
  ): ZLayer[AgentApplication, AgentError.InvalidConfiguration, AgentHostProcesses] =
    ZLayer.fromZIO {
      ZIO.service[AgentApplication].flatMap { application =>
        AgentHostProcess.make("command-worker", application.runWorker).flatMap { worker =>
          make(worker +: additional)
        }
      }
    }

/** 可替换 HTTP Server 边界。
  *
  * 生产实现委托 ZIO HTTP `Server.serve`；独立 SPI 让 Host 生命周期、Worker 失败传播和 route 组合可以在不绑定端口的测试中 确定性验证。
  */
trait AgentHttpServer:
  /** 服务 routes 直到 Fiber 被中断或底层 Server 失败。 */
  def serve(routes: Routes[Any, Nothing]): IO[Throwable, Nothing]

object AgentHttpServer:
  /** 捕获宿主提供的 ZIO HTTP Server service，不创建第二套 Server.Config。 */
  val zioHttp: URLayer[Server, AgentHttpServer] = ZLayer.fromFunction { (server: Server) =>
    new AgentHttpServer:
      def serve(routes: Routes[Any, Nothing]): IO[Throwable, Nothing] =
        Server.serve(routes).provideEnvironment(ZEnvironment(server))
  }

/** 独立 Agent 服务的生产宿主。
  *
  * Host 同时拥有 HTTP Server effect 与 Worker Fiber 的 Scope：任一关键组件失败都会中断另一方并让进程退出，由 Kubernetes、 systemd 或其他
  * supervisor 重启；不会出现“端口仍返回 200，但后台 Worker 已永久死亡”的半活实例。
  */
trait AgentHttpHost:
  /** 组合后的 routes，便于嵌入式服务器安装、直接 route 测试和文档生成。 */
  def routes: Routes[Any, Nothing]

  /** 启动后台 Worker 与 HTTP Server，并持续运行直到外部中断或关键组件失败。
    *
    * Host 内部创建自己的子 Scope；Server 失败、关键进程失败或调用 Fiber 中断都会立即关闭该 Scope，中断 Worker、Provider 流与工具 Fiber，不必等待更外层应用
    * Scope 随后结束。
    */
  def serve: IO[AgentError, Nothing]

final private class AgentHttpHostLive(
    processes: AgentHostProcesses,
    primary: AgentHttpPrimaryRoutes,
    readiness: AgentHostReadiness,
    additional: AgentHttpAdditionalRoutes,
    server: AgentHttpServer,
    config: AgentHttpHostConfig,
    state: Ref[AgentHostState]
) extends AgentHttpHost:

  /** 健康路由优先合并，确保保留路径不会被附加业务路由覆盖。 */
  lazy val routes: Routes[Any, Nothing] = healthRoutes ++ primary.routes ++ additional.routes

  def serve: IO[AgentError, Nothing] = ZIO.scoped {
    (for
      fibers <- ZIO.foreach(processes.values)(process => process.run.forkScoped.map(process -> _))
      _      <- state.set(AgentHostState.Running)
      serverRun  = server.serve(routes).mapError(serverFailure)
      processRun = monitorProcesses(fibers)
      result <- serverRun.raceFirst(processRun)
    yield result)
      .onInterrupt(state.set(AgentHostState.Stopping))
      .ensuring(
        state.update {
          case failed: AgentHostState.Failed => failed
          case _                             => AgentHostState.Stopping
        }
      )
  }

  /** Worker 不应正常结束；任何 success、typed failure、defect 或独立中断都使 Host 失败。 外部关闭 Host 时整个 race 会被一起中断，因此不会把正常 Scope
    * 关闭误报为 Worker 故障。
    */
  private def monitorProcess(
      process: AgentHostProcess,
      worker: Fiber.Runtime[AgentError, Nothing]
  ): IO[AgentError, Nothing] = worker.await.flatMap { exit =>
    val code = exit match
      case Exit.Failure(cause) if cause.failureOption.nonEmpty => "process_typed_failure"
      case Exit.Failure(cause) if cause.defects.nonEmpty       => "process_defect"
      case Exit.Failure(_)                                     => "process_interrupted"
      case Exit.Success(_)                                     => "process_completed"
    state.set(AgentHostState.Failed(code)) *>
      ZIO.fail(
        AgentError.Unexpected(
          s"Agent HTTP Host 的关键后台进程已停止: process=${process.name}, code=$code"
        )
      )
  }

  /** 任意关键进程率先退出都会终止 Host；raceAll 会在获胜后中断其余 monitor Fiber。 */
  private def monitorProcesses(
      fibers: NonEmptyChunk[(AgentHostProcess, Fiber.Runtime[AgentError, Nothing])]
  ): IO[AgentError, Nothing] =
    val monitors = fibers.map((process, fiber) => monitorProcess(process, fiber))
    monitors.head.raceAll(monitors.tail)

  /** Server 原始异常只进入 cause chain，公开错误使用稳定、不含地址或 TLS 细节的摘要。 */
  private def serverFailure(cause: Throwable): AgentError =
    AgentError.ExternalProtocolFailure(
      protocol = "zio-http",
      operation = "serve",
      message = "Agent HTTP Server 启动或运行失败",
      code = Some("server_failure"),
      retryable = true,
      cause = Some(cause)
    )

  /** liveness 只判断关键 Worker 生命周期；依赖抖动由 readiness 表达。 */
  private val liveRoute: Route[Any, Nothing] =
    Method.GET / "health" / "live" -> handler {
      state.get.map {
        case AgentHostState.Running  => healthResponse("live", Status.Ok, "up", None)
        case AgentHostState.Starting =>
          healthResponse("live", Status.ServiceUnavailable, "down", Some("starting"))
        case AgentHostState.Stopping =>
          healthResponse("live", Status.ServiceUnavailable, "down", Some("stopping"))
        case AgentHostState.Failed(code) =>
          healthResponse("live", Status.ServiceUnavailable, "down", Some(code))
      }
    }

  /** readiness 同时要求 Worker 正常和业务依赖在有限时间内通过探测。 */
  private val readyRoute: Route[Any, Nothing] =
    Method.GET / "health" / "ready" -> handler {
      state.get.flatMap {
        case AgentHostState.Running =>
          readiness.check
            .timeout(config.readinessTimeout)
            .map {
              case Some(_) => healthResponse("ready", Status.Ok, "ready", None)
              case None    =>
                healthResponse("ready", Status.ServiceUnavailable, "not_ready", Some("dependency_timeout"))
            }
            .catchAll(_ =>
              ZIO.succeed(
                healthResponse(
                  "ready",
                  Status.ServiceUnavailable,
                  "not_ready",
                  Some("dependency_unavailable")
                )
              )
            )
        case AgentHostState.Starting =>
          ZIO.succeed(healthResponse("ready", Status.ServiceUnavailable, "not_ready", Some("starting")))
        case AgentHostState.Stopping =>
          ZIO.succeed(healthResponse("ready", Status.ServiceUnavailable, "not_ready", Some("stopping")))
        case AgentHostState.Failed(code) =>
          ZIO.succeed(healthResponse("ready", Status.ServiceUnavailable, "not_ready", Some(code)))
      }
    }

  /** Host 保留的健康路由集合。 */
  private val healthRoutes: Routes[Any, Nothing] = Routes(liveRoute, readyRoute)

  /** 统一生成 no-store JSON 健康响应，防止代理缓存旧的 ready 状态。 */
  private def healthResponse(
      check: String,
      status: Status,
      health: String,
      code: Option[String]
  ): Response =
    Response(
      status = status,
      body = Body.fromString(
        AgentHostHealthResponse(
          config.serviceName,
          config.serviceVersion,
          config.environment,
          check,
          health,
          code
        ).toJson
      )
    ).addHeader(Header.ContentType(MediaType.application.json))
      .addHeader("Cache-Control", "no-store")

object AgentHttpHost:
  /** 从环境取得 Host 并开始服务。 */
  val serve: ZIO[AgentHttpHost, AgentError, Nothing] =
    ZIO.serviceWithZIO[AgentHttpHost](_.serve)

  /** 读取已组合 routes；嵌入既有 Server 时可以只安装 routes 而不调用本模块的 `serve`。 */
  val routes: URIO[AgentHttpHost, Routes[Any, Nothing]] =
    ZIO.serviceWith[AgentHttpHost](_.routes)

  /** 生产 Host 装配层。
    *
    * `AgentHostReadiness`、附加 routes、Server/Server.Config 和所有 AgentApplication 依赖必须由业务显式提供；框架不会在 生产装配中偷偷注入
    * always-ready、匿名认证、内存 Store 或默认端口。
    */
  val live: URLayer[
    AgentHostProcesses & AgentHttpPrimaryRoutes & AgentHostReadiness & AgentHttpAdditionalRoutes &
      AgentHttpServer & AgentHttpHostConfig,
    AgentHttpHost
  ] = ZLayer.fromZIO {
    for
      processes  <- ZIO.service[AgentHostProcesses]
      primary    <- ZIO.service[AgentHttpPrimaryRoutes]
      readiness  <- ZIO.service[AgentHostReadiness]
      additional <- ZIO.service[AgentHttpAdditionalRoutes]
      server     <- ZIO.service[AgentHttpServer]
      config     <- ZIO.service[AgentHttpHostConfig]
      state      <- Ref.make[AgentHostState](AgentHostState.Starting)
    yield AgentHttpHostLive(processes, primary, readiness, additional, server, config, state)
  }

  /** 最常见的独立部署装配：从 AgentApplication 和 AgentHttpApi 自动投影主 Worker 与 routes。 业务仍必须显式提供 readiness、附加 routes、Server
    * 和 Host 配置。
    */
  val fromApplication: ZLayer[
    AgentApplication & AgentHttpApi & AgentHostReadiness & AgentHttpAdditionalRoutes & AgentHttpServer &
      AgentHttpHostConfig,
    AgentError.InvalidConfiguration,
    AgentHttpHost
  ] = ZLayer.makeSome[
    AgentApplication & AgentHttpApi & AgentHostReadiness & AgentHttpAdditionalRoutes & AgentHttpServer &
      AgentHttpHostConfig,
    AgentHttpHost
  ](
    AgentHostProcesses.fromApplication(),
    AgentHttpPrimaryRoutes.fromApi,
    live
  )
