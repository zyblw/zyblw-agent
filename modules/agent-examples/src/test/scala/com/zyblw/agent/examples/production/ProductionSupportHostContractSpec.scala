package com.zyblw.agent.examples.production

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.http.*
import com.zyblw.agent.memory.RunCommandStatus
import com.zyblw.agent.model.{ChatModel, RoutedChatModel}
import zio.json.*
import com.zyblw.agent.scheduler.WorkerHostConfig
import com.zyblw.agent.tools.*
import zio.*
import zio.http.*
import zio.test.*

/** 生产参考宿主的确定性契约：配置 fail-closed、未注册工具提前拒绝、查询→审批退款主线。 */
object ProductionSupportHostContractSpec extends ZIOSpecDefault:
  private val fastWorker = WorkerHostConfig(
    leaseDuration = 5.seconds,
    heartbeatEvery = 1.second,
    pollEvery = 10.millis,
    retryDelay = Duration.Zero,
    maxAttempts = 3
  )

  private val contractConfig = ProductionSupportConfig(
    command = ProductionSupportCommand.Serve,
    mode = ProductionSupportMode.Contract,
    httpPort = 18080,
    workerId = "support-contract",
    jdbcUrl = None,
    dbUser = None,
    dbPasswordEnv = Some("ZYBLW_AGENT_DB_PASSWORD"),
    authMode = ProductionAuthMode.AnonymousContract,
    otlpEndpoint = None,
    worker = fastWorker
  )

  private val actor = RunContext(Some("user-demo"), Some("tenant-demo"), Set.empty)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ProductionSupportHost")(
    test("未知启动命令在进入装配前失败") {
      ProductionSupportConfig.load(Chunk("demo")).either.map { result =>
        assertTrue(result.left.exists(_.message.contains("未知命令")))
      }
    },
    test("status 命令在缺少数据库配置时 fail-closed") {
      ProductionSupportConfig.load(Chunk("status")).map { loaded =>
        assertTrue(loaded.command == ProductionSupportCommand.Status)
      } *> contractConfig
        .copy(
          command = ProductionSupportCommand.Status,
          mode = ProductionSupportMode.Live,
          authMode = ProductionAuthMode.TrustedHeaders
        )
        .requireDurableDatabase
        .either
        .map { result =>
          assertTrue(result.left.exists(_.message.contains("live 模式必须提供")))
        }
    },
    test("live 模式缺少数据库配置时 fail-closed，不回退内存") {
      val live =
        contractConfig.copy(mode = ProductionSupportMode.Live, authMode = ProductionAuthMode.TrustedHeaders)
      live.requireDurableDatabase.either.map { result =>
        assertTrue(result.left.exists(_.message.contains("live 模式必须提供")))
      }
    },
    test("live 生产装配优先使用配置驱动的多端点路由") {
      val endpoints =
        """{"defaultProvider":"relay-deepseek","endpoints":[{"providerId":"relay-deepseek","baseUrl":"https://gateway.example/v1","apiKeyEnv":"RELAY_API_KEY","defaultModel":"deepseek-test","protocol":"relay","compatibilityProfile":"deepseek","models":[{"name":"deepseek-test","capabilities":{"toolCalls":true,"streaming":true,"thinking":true}}]}]}"""
      (for model <- ZIO.service[ChatModel]
      yield assertTrue(
        model.isInstanceOf[RoutedChatModel],
        model.asInstanceOf[RoutedChatModel].defaultProvider == "relay-deepseek"
      )).provide(
        Client.default,
        ProductionSupportLayers.liveModel
      ).provide(
        Runtime.setConfigProvider(
          ConfigProvider.fromMap(
            Map(
              "ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON" -> endpoints,
              "RELAY_API_KEY"                       -> "test-secret"
            )
          )
        )
      )
    },
    test("空白多端点变量使用显式单端点配置") {
      (for model <- ZIO.service[ChatModel]
      yield assertTrue(
        !model.isInstanceOf[RoutedChatModel],
        model.provider == "openai"
      )).provide(
        Client.default,
        ProductionSupportLayers.liveModel
      ).provide(
        Runtime.setConfigProvider(
          ConfigProvider.fromMap(
            Map(
              "ZYBLW_AGENT_PROVIDER_ENDPOINTS_JSON" -> "",
              "OPENAI_BASE_URL"                     -> "https://api.example.com/v1",
              "OPENAI_API_KEY"                      -> "test-secret",
              "OPENAI_MODEL"                        -> "test-model"
            )
          )
        )
      )
    },
    test("可信身份解析拒绝缺少租户或用户头的请求") {
      val trusted = contractConfig.copy(authMode = ProductionAuthMode.TrustedHeaders)
      (for
        resolver <- ZIO.service[AgentRequestContextResolver]
        missing  <- resolver.resolve(Request.get(URL.root)).either
        complete <- resolver.resolve(
          Request
            .get(URL.root)
            .addHeader("X-Tenant-Id", "tenant-demo")
            .addHeader("X-User-Id", "user-demo")
            .addHeader("X-Scopes", "orders:read,refunds:write")
        )
      yield assertTrue(
        missing.left.exists(_.message.contains("X-Tenant-Id")),
        complete.tenantId.contains("tenant-demo"),
        complete.userId.contains("user-demo"),
        complete.scopes == Set("orders:read", "refunds:write")
      )).provide(ProductionSupportLayers.identity(trusted))
    },
    test("提交前拒绝白名单里尚未注册的工具") {
      val extraPolicy = ToolPolicyConfig(
        allowedTools = Set(ToolName("lookup_order"), ToolName("issue_refund"), ToolName("missing_tool")),
        approvalPolicy = ApprovalPolicy.RiskBased
      )
      (for
        app   <- ZIO.service[AgentApplication]
        agent <- AgentDefinitionBuilder(ProductionSupportLayers.agentId, "客户支持助手")
          .withInstructions("不要调用未注册工具。")
          .allowTools(List(ToolName("lookup_order"), ToolName("issue_refund"), ToolName("missing_tool")))
          .buildFor(extraPolicy)
        result <- app
          .submit(
            agent,
            RunRequest(ThreadId("support-unregistered"), AgentMessage.user("查询订单"), actor),
            "support-unregistered-1"
          )
          .either
      yield assertTrue(
        result.left.exists {
          case AgentError.InvalidConfiguration(message) => message.contains("missing_tool")
          case _                                        => false
        }
      )).provide(
        ProductionSupportLayers.scriptedModel,
        ProductionSupportLayers.inMemoryToolsLayer,
        ProductionSupportLayers.inMemoryApplication(contractConfig)
      )
    },
    test("contract 主线先查询订单，再在人工审批后完成退款") {
      (for
        app     <- ZIO.service[AgentApplication]
        agent   <- ProductionSupportLayers.supportAgent(contractConfig.application)
        _       <- app.startWorkerScoped
        command <- app.submit(
          agent,
          RunRequest(ThreadId("support-refund"), AgentMessage.user("请处理订单 A-100 的退款"), actor),
          "support-refund-1"
        )
        waiting <- awaitStatus(app, command.runId, RunStatus.WaitingForApproval)
        _       <- app.decide(command.runId, ApprovalDecision.Approve, actor)
        done    <- awaitStatus(app, command.runId, RunStatus.Completed)
      yield assertTrue(
        command.status == RunCommandStatus.Queued,
        waiting.status == RunStatus.WaitingForApproval,
        waiting.pendingApproval.nonEmpty,
        done.status == RunStatus.Completed,
        done.messages.lastOption.exists(_.text.contains("A-100"))
      )).provideSome[Scope](
        ProductionSupportLayers.scriptedModel,
        ProductionSupportLayers.inMemoryToolsLayer,
        ProductionSupportLayers.inMemoryApplication(contractConfig)
      )
    } @@ TestAspect.withLiveClock,
    test("生产宿主装配 Memory 治理路由，缺少身份头在 Resolver 即 fail-closed") {
      val trusted = contractConfig.copy(authMode = ProductionAuthMode.TrustedHeaders)
      (for
        api    <- ZIO.service[MemoryHttpApi]
        denied <- api.routes.runZIO(Request.post(URL.root / "api" / "v1" / "memory" / "list", Body.empty))
        listed <- api.routes.runZIO(
          Request
            .post(URL.root / "api" / "v1" / "memory" / "list", Body.fromString(MemoryListRequest(10).toJson))
            .addHeader("X-Tenant-Id", "tenant-demo")
            .addHeader("X-User-Id", "user-demo")
        )
      yield assertTrue(denied.status == Status.BadRequest, listed.status == Status.Ok)).provideSome[Scope](
        ProductionSupportLayers.inMemoryMemory,
        ProductionSupportLayers.identity(trusted),
        MemoryHttpApi.layer
      )
    }
  ) @@ TestAspect.timeout(20.seconds)

  private def awaitStatus(
      app: AgentApplication,
      runId: RunId,
      expected: RunStatus
  ): IO[AgentError, AgentState] =
    app
      .inspect(runId)
      .flatMap { state =>
        if state.status == expected then ZIO.succeed(state)
        else if terminal(state.status) && state.status != expected then
          ZIO.fail(AgentError.Unexpected(s"Run 以 ${state.status} 结束，期望 $expected"))
        else ZIO.sleep(20.millis) *> awaitStatus(app, runId, expected)
      }
      .timeoutFail(AgentError.Unexpected(s"等待 $expected 超时"))(8.seconds)

  private def terminal(status: RunStatus): Boolean =
    status match
      case RunStatus.Completed | RunStatus.Failed | RunStatus.Cancelled | RunStatus.TimedOut |
          RunStatus.BudgetExceeded =>
        true
      case _ => false
