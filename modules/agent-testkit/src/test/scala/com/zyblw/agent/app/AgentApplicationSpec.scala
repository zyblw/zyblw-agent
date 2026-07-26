package com.zyblw.agent.app

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.runtime.*
import com.zyblw.agent.scheduler.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 验证业务易用层没有牺牲耐久语义。
  *
  * 测试不只断言“能构造 Layer”，还沿真实 `submit → command queue → WorkerHost → fenced Runtime → AgentState` 路径运行，防止便利 API
  * 悄悄退回同步 Runtime 或创建两套互不相识的内存 Store。
  */
object AgentApplicationSpec extends ZIOSpecDefault:

  /** 构造无需工具即可结束的一轮模型响应。 */
  private def finalResponse(text: String = "done"): ChatResponse =
    ChatResponse(AgentMessage.assistant(text), FinishReason.Stop, TokenUsage(4, 2))

  /** 创建仅用于测试装配的无工具 Agent。 */
  private val simpleAgent = AgentDefinition(
    AgentId("app-simple"),
    "Application 装配测试",
    "直接完成请求，不调用工具。"
  )

  /** 所有测试使用较短轮询；心跳仍保持严格小于 lease。 */
  private val workerConfig = WorkerHostConfig(
    leaseDuration = 5.seconds,
    heartbeatEvery = 1.second,
    pollEvery = 10.millis,
    retryDelay = Duration.Zero,
    maxAttempts = 3
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentApplication")(
    test("默认内存 Starter 只提交命令，Worker claim 后才推进到 Completed") {
      val layer = AgentApplication.inMemoryDefaults(
        WorkerId("local-default-worker"),
        AgentApplicationConfig(worker = workerConfig)
      )
      (for
        app     <- ZIO.service[AgentApplication]
        command <- app.submit(
          simpleAgent,
          RunRequest(ThreadId("app-default-thread"), AgentMessage.user("hello")),
          "app-default-request"
        )
        before  <- app.inspect(command.runId)
        handled <- app.claimOnce
        after   <- app.inspect(command.runId)
        receipt <- app.inspectCommand(command.commandId, RunContext())
      yield assertTrue(
        command.status == RunCommandStatus.Queued,
        before.status == RunStatus.Created,
        handled,
        after.status == RunStatus.Completed,
        after.messages.lastOption.exists(_.text == "done"),
        receipt.status == RunCommandStatus.Completed
      )).provide(
        ScriptedChatModel.layer(Chunk(finalResponse())),
        RegisteredToolRegistry.fromTools(Nil),
        layer
      )
    },
    test("审批工具经相同 Application 提交决定并恢复，业务副作用只执行一次") {
      final case class DraftInput(id: String) derives JsonCodec
      final case class DraftOutput(updated: Boolean) derives JsonCodec

      for
        executions <- Ref.make(0)
        tool = Tool.json[Any, DraftInput, Nothing, DraftOutput](
          ToolName("update_draft"),
          "更新一份测试草稿",
          TestSchemas.stringObject("id", "草稿 ID"),
          None,
          ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.IdempotentWrite)
        )((_, _) => executions.update(_ + 1).as(DraftOutput(updated = true)))
        registered <- RegisteredTool.make(tool)
        script = Chunk(
          ChatResponse(
            AgentMessage.assistantToolCalls(
              Chunk(ToolCall("draft-call", "update_draft", Json.Obj("id" -> Json.Str("draft-1"))))
            ),
            FinishReason.ToolCalls,
            TokenUsage(5, 2)
          ),
          finalResponse("approved")
        )
        config = AgentApplicationConfig(
          toolPolicy = ToolPolicyConfig(allowedTools = Set(ToolName("update_draft"))),
          worker = workerConfig
        )
        result <- (for
          app   <- ZIO.service[AgentApplication]
          start <- app.submit(
            AgentDefinition(
              AgentId("app-approval"),
              "审批装配测试",
              "更新草稿前等待审批。",
              Set("update_draft")
            ),
            RunRequest(ThreadId("app-approval-thread"), AgentMessage.user("更新草稿")),
            "app-approval-request"
          )
          firstClaim <- app.claimOnce
          waiting    <- app.inspect(start.runId)
          approval   <- ZIO.fromOption(waiting.pendingApproval).orElseFail(AgentError.Unexpected("缺少审批"))
          resume     <- app.decide(start.runId, ApprovalDecision.Approve, RunContext())
          nextClaim  <- app.claimOnce
          completed  <- app.inspect(start.runId)
          startCmd   <- app.inspectCommand(start.commandId, RunContext())
          resumeCmd  <- app.inspectCommand(resume.commandId, RunContext())
          count      <- executions.get
        yield (
          firstClaim,
          waiting,
          approval,
          nextClaim,
          completed,
          startCmd,
          resumeCmd,
          count
        )).provide(
          ScriptedChatModel.layer(script),
          RegisteredToolRegistry.fromTools(List(registered)),
          AgentApplication.inMemoryDefaults(WorkerId("approval-worker"), config)
        )
        (firstClaim, waiting, approval, nextClaim, completed, startCmd, resumeCmd, count) = result
      yield assertTrue(
        firstClaim,
        waiting.status == RunStatus.WaitingForApproval,
        approval.toolCall.name == "update_draft",
        nextClaim,
        completed.status == RunStatus.Completed,
        completed.messages.lastOption.exists(_.text == "approved"),
        startCmd.status == RunCommandStatus.Completed,
        resumeCmd.status == RunCommandStatus.Completed,
        count == 1
      )
    },
    test("durable 装配强制使用调用方提供的 Store、Context、Guardrail 和 Observer") {
      for
        resolves <- Ref.make(0)
        observed <- Ref.make(Chunk.empty[AgentEvent])
        resolver = new ContextSourceResolver:
          def resolve(state: AgentState, definition: AgentDefinition): IO[ContextError, ContextSources] =
            resolves.update(_ + 1).as(ContextSources(safetyInstructions = Chunk("保持低风险测试输出")))
        observer = new RunObserver:
          def emit(event: AgentEvent): UIO[Unit] = observed.update(_ :+ event)
        result <- (for
          app     <- ZIO.service[AgentApplication]
          command <- app.submit(
            simpleAgent,
            RunRequest(ThreadId("durable-layer-thread"), AgentMessage.user("hello")),
            "durable-layer-request"
          )
          _      <- app.claimOnce
          state  <- app.inspect(command.runId)
          count  <- resolves.get
          events <- observed.get
        yield (state, count, events)).provide(
          ScriptedChatModel.layer(Chunk(finalResponse("durable"))),
          RegisteredToolRegistry.fromTools(Nil),
          AgentPersistence.inMemory,
          ZLayer.succeed[ContextSourceResolver](resolver),
          ZLayer.succeed[GuardrailEngine](
            GuardrailEngine(ConfiguredGuardrails(Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty))
          ),
          ZLayer.succeed[RunObserver](observer),
          AgentApplication.durable(
            WorkerId("durable-layer-worker"),
            AgentApplicationConfig(worker = workerConfig)
          )
        )
        (state, count, events) = result
      yield assertTrue(
        state.status == RunStatus.Completed,
        state.messages.lastOption.exists(_.text == "durable"),
        count == 1,
        events.exists(_.isInstanceOf[AgentEvent.ContextPrepared]),
        events.exists(_.isInstanceOf[AgentEvent.RunCompleted])
      )
    },
    test("WithContextCompressor 入口让 ModelAssisted 策略真实进入主循环并持久化辅助 usage") {
      final case class LookupInput(query: String) derives JsonCodec
      final case class LookupOutput(content: String) derives JsonCodec

      for
        compressionCalls <- Ref.make(0)
        compressor = new ContextCompressor:
          override val supportsModelAssisted: Boolean = true

          def compress(
              messages: Chunk[AgentMessage],
              targetTokens: Long,
              maxModelCalls: Int
          ): IO[ContextError, ContextCompressionResult] =
            compressionCalls.updateAndGet(_ + 1).map { _ =>
              ContextCompressionResult(
                AgentMessage.system(s"application-summary-${messages.size}"),
                TokenUsage(7L, 3L),
                modelCalls = 1,
                compressorVersion = "application-test-v1"
              )
            }
        tool = Tool.json[Any, LookupInput, Nothing, LookupOutput](
          ToolName("lookup_context"),
          "返回一段足以触发第二回合历史压缩的测试资料",
          TestSchemas.stringObject("query", "查询词"),
          None,
          ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
        ) { (_, _) =>
          ZIO.succeed(LookupOutput("资料" * 90))
        }
        registered <- RegisteredTool.make(tool)
        script = Chunk(
          ChatResponse(
            AgentMessage.assistantToolCalls(
              Chunk(ToolCall("context-call", "lookup_context", Json.Obj("query" -> Json.Str("test"))))
            ),
            FinishReason.ToolCalls,
            TokenUsage(5L, 2L)
          ),
          finalResponse("context-complete").copy(usage = TokenUsage(6L, 3L))
        )
        contextPolicy = ContextPolicy(
          budget = ContextBudget(
            total = 1_000L,
            system = 100L,
            tools = 100L,
            recentMessages = 120L,
            memory = 100L,
            retrieval = 100L,
            outputReserve = 100L,
            safetyMargin = 100L
          ),
          maxToolResultCharacters = 1_000,
          historyCompression = CompressionMode.ModelAssisted,
          toolOutputCompression = CompressionMode.Deterministic
        )
        definition = AgentDefinition(
          AgentId("app-context-compression"),
          "应用压缩装配测试",
          "使用工具后完成回答。",
          allowedTools = Set("lookup_context"),
          contextPolicy = contextPolicy
        )
        config = AgentApplicationConfig(
          toolPolicy = ToolPolicyConfig(allowedTools = Set(ToolName("lookup_context"))),
          worker = workerConfig
        )
        result <- (for
          app     <- ZIO.service[AgentApplication]
          command <- app.submit(
            definition,
            RunRequest(
              ThreadId("app-context-thread"),
              AgentMessage.user("需要保留的初始问题" + "上下文" * 36)
            ),
            "app-context-request"
          )
          handled <- app.claimOnce
          state   <- app.inspect(command.runId)
          calls   <- compressionCalls.get
        yield (handled, state, calls)).provide(
          ScriptedChatModel.layer(script),
          RegisteredToolRegistry.fromTools(List(registered)),
          ZLayer.succeed[ContextCompressor](compressor),
          ContextSourceResolver.empty,
          GuardrailEngine.empty,
          RunObserver.noop,
          AgentApplication.inMemoryWithContextCompressor(
            WorkerId("context-compression-worker"),
            config
          )
        )
        (handled, state, calls) = result
      yield assertTrue(
        handled,
        state.status == RunStatus.Completed,
        state.messages.lastOption.exists(_.text == "context-complete"),
        calls == 1,
        state.contextSummary.exists(_.compressorVersion == "application-test-v1"),
        state.usage.modelCalls == 3,
        state.usage.inputTokens == 18L,
        state.usage.outputTokens == 8L
      )
    },
    test("取消命令抢占活动 lease、中断模型 Fiber，并由下一次 claim 收敛到 Cancelled") {
      val cancellationConfig = AgentApplicationConfig(
        worker = WorkerHostConfig(
          leaseDuration = 1.second,
          heartbeatEvery = 20.millis,
          pollEvery = 10.millis,
          retryDelay = Duration.Zero,
          maxAttempts = 3
        )
      )
      for
        modelStarted   <- Promise.make[Nothing, Unit]
        modelCancelled <- Promise.make[Nothing, Unit]
        model = new ChatModel:
          val provider = "cancellable-app-model"
          def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
            // acquireReleaseWith 先注册 finalizer 再发布 started，避免取消测试本身出现注册竞态。
            ZIO.acquireReleaseWith(ZIO.unit)(_ => modelCancelled.succeed(()).unit) { _ =>
              modelStarted.succeed(()).unit *> ZIO.never
            }
        result <- (for
          app   <- ZIO.service[AgentApplication]
          start <- app.submit(
            simpleAgent,
            RunRequest(ThreadId("cancel-thread"), AgentMessage.user("start")),
            "cancel-start-request"
          )
          executing   <- app.claimOnce.fork
          _           <- modelStarted.await.timeoutFail(AgentError.Unexpected("模型未开始"))(2.seconds)
          cancel      <- app.cancel(start.runId, Some("用户主动停止"), RunContext())
          firstExit   <- executing.await.timeoutFail(AgentError.Unexpected("旧 lease 未被抢占"))(2.seconds)
          interrupted <- modelCancelled.await.timeoutFail(AgentError.Unexpected("模型未收到取消"))(2.seconds)
          handled     <- app.claimOnce
          state       <- app.inspect(start.runId)
          cancelState <- app.inspectCommand(cancel.commandId, RunContext())
        yield (firstExit, interrupted, handled, state, cancelState)).provide(
          ZLayer.succeed[ChatModel](model),
          RegisteredToolRegistry.fromTools(Nil),
          AgentApplication.inMemoryDefaults(WorkerId("cancel-worker"), cancellationConfig)
        )
        (firstExit, _, handled, state, cancelState) = result
      yield assertTrue(
        firstExit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.LeaseLost]),
        handled,
        state.status == RunStatus.Cancelled,
        cancelState.status == RunCommandStatus.Completed
      )
    } @@ TestAspect.withLiveClock,
    test("scoped Worker 在宿主 Scope 关闭时被结构化中断") {
      val layer = AgentApplication.inMemoryDefaults(
        WorkerId("scoped-worker"),
        AgentApplicationConfig(worker = workerConfig.copy(pollEvery = 5.seconds))
      )
      (for
        app   <- ZIO.service[AgentApplication]
        fiber <- ZIO.scoped {
          app.startWorkerScoped
        }
        exit <- fiber.await
      yield assertTrue(exit.causeOption.exists(_.isInterrupted))).provide(
        ScriptedChatModel.layer(Chunk.empty),
        RegisteredToolRegistry.fromTools(Nil),
        layer
      )
    }
  ) @@ TestAspect.timeout(20.seconds)
