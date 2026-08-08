package com.zyblw.agent.runtime

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.scheduler.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** 新一代耐久 Runtime 的确定性契约测试。
  *
  * 测试直接读取 `RunStore` 与工具执行账本，避免只验证最终字符串而遗漏 状态游标、乐观锁和副作用恢复语义。
  */
object AgentRuntimeSpec extends ZIOSpecDefault:
  final case class EchoInput(value: String) derives JsonCodec
  final case class EchoOutput(value: String) derives JsonCodec

  private val agent = AgentDefinition(
    AgentId("durable-test-agent"),
    "Durable Test Agent",
    "按需使用工具，并根据工具结果回答。",
    allowedTools = Set("echo")
  )

  /** 构造一次模型工具调用响应。
    * @param callId
    *   稳定调用 ID，也是执行账本的复合键组成部分
    * @param value
    *   传给 echo 工具的业务输入
    */
  private def toolResponse(callId: String, value: String): ChatResponse =
    ChatResponse(
      AgentMessage.assistantToolCalls(Chunk(ToolCall(callId, "echo", Json.Obj("value" -> Json.Str(value))))),
      FinishReason.ToolCalls,
      TokenUsage(4, 2)
    )

  /** 构造同一模型响应中的两个工具调用，用于验证批次 pending writes 与并行恢复。 */
  private def parallelToolResponse(firstId: String, secondId: String): ChatResponse =
    ChatResponse(
      AgentMessage.assistantToolCalls(
        Chunk(
          ToolCall(firstId, "echo", Json.Obj("value" -> Json.Str("fast"))),
          ToolCall(secondId, "echo", Json.Obj("value" -> Json.Str("slow")))
        )
      ),
      FinishReason.ToolCalls,
      TokenUsage(6, 3)
    )

  /** 构造循环结束响应，便于每个测试清楚声明模型脚本。 */
  private def finalResponse(text: String = "done"): ChatResponse =
    ChatResponse(AgentMessage.assistant(text), FinishReason.Stop, TokenUsage(3, 2))

  /** 从完成或暂停两种公开结果中取得共同的 RunId，避免测试依赖具体分支的字段访问。 */
  private def runIdOf(outcome: RunOutcome): RunId = outcome match
    case RunOutcome.Completed(runId, _, _, _, _) => runId
    case RunOutcome.Suspended(runId, _, _, _, _) => runId

  /** 创建捕获 ZIO 环境后的类型化 echo 工具。
    * @param risk
    *   工具风险等级，决定是否进入人工审批
    * @param sideEffect
    *   副作用语义，决定崩溃恢复时是否允许自动重放
    * @param executions
    *   每次真正进入业务执行时递增，用于验证特定恢复路径没有重复进入工具。 这只证明框架账本在该测试边界内的结果复用，不表示跨第三方副作用具有 exactly-once 语义。
    */
  private def registeredEcho(
      risk: ToolRisk,
      sideEffect: SideEffect,
      executions: Ref[Int]
  ): UIO[RegisteredTool] =
    val tool = Tool.json[Any, EchoInput, AgentError.ToolExecutionFailed, EchoOutput](
      ToolName("echo"),
      "返回输入内容",
      TestSchemas.stringObject("value", "需要回显的文本"),
      None,
      ToolMetadata(risk, sideEffect)
    )((input, _) => executions.update(_ + 1).as(EchoOutput(input.value)))
    RegisteredTool.make(tool)

  /** 组装测试使用的完整 ZLayer 图。
    * @param model
    *   可确定性编排并记录请求的模型替身
    * @param tool
    *   已捕获依赖的工具；不需要工具的测试仍传空集合
    */
  private def layers(
      model: ChatModel,
      tools: Iterable[RegisteredTool],
      guardrailEngine: GuardrailEngine = GuardrailEngine(
        ConfiguredGuardrails(Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty)
      ),
      toolPolicy: ToolPolicyConfig = ToolPolicyConfig.secureDefault,
      modelPolicies: ModelPolicySource = ModelPolicySource.default
  ) =
    ZLayer.make[AgentRuntime & RunStore](
      ZLayer.succeed[ChatModel](model),
      RegisteredToolRegistry.fromTools(tools),
      RunStore.inMemory,
      TokenCounter.approximate,
      ContextCompressor.deterministic,
      DefaultContextManager.layer,
      ZLayer.succeed(guardrailEngine),
      ZLayer.succeed(ToolPolicySource.static(toolPolicy)),
      ZLayer.succeed(modelPolicies),
      RunObserver.noop,
      AgentRuntimeLive.layer
    )

  /** 用显式 ContextManager 与 Observer 组装 Runtime。
    *
    * 这个入口专门验证两条容易在重构中被破坏的契约：`AgentDefinition.contextPolicy` 必须原样传入 ContextManager，并且构建结果必须形成不含正文的
    * `ContextPrepared` 语义事件。
    *
    * @param model
    *   确定性模型替身
    * @param manager
    *   记录实际 ContextPolicy 的测试实现
    * @param observer
    *   捕获 Runtime 低敏事件的观察者
    */
  private def layersWithContextManager(model: ChatModel, manager: ContextManager, observer: RunObserver) =
    ZLayer.make[AgentRuntime & RunStore](
      ZLayer.succeed[ChatModel](model),
      RegisteredToolRegistry.fromTools(Nil),
      RunStore.inMemory,
      ZLayer.succeed[ContextManager](manager),
      GuardrailEngine.empty,
      ZLayer.succeed(ToolPolicySource.static(ToolPolicyConfig.secureDefault)),
      ModelPolicySource.defaultLayer,
      ZLayer.succeed[RunObserver](observer),
      AgentRuntimeLive.layer
    )

  /** 组装显式动态上下文来源的生产路径，验证 Runtime 不再硬编码空 ContextSources。 */
  private def layersWithContextSources(model: ChatModel, resolver: ContextSourceResolver) =
    ZLayer.make[AgentRuntime & RunStore](
      ZLayer.succeed[ChatModel](model),
      RegisteredToolRegistry.fromTools(Nil),
      RunStore.inMemory,
      TokenCounter.approximate,
      ContextCompressor.deterministic,
      DefaultContextManager.layer,
      ZLayer.succeed[ContextSourceResolver](resolver),
      GuardrailEngine.empty,
      ZLayer.succeed(ToolPolicySource.static(ToolPolicyConfig.secureDefault)),
      ModelPolicySource.defaultLayer,
      RunObserver.noop,
      AgentRuntimeLive.layerWithContextSources
    )

  /** 在同一 ZLayer 图中共享 RunStore、命令队列和原子提交 Adapter，用于验证 HTTP 之外的真实异步启动主路径。
    */
  private def durableControlLayers(model: ChatModel) =
    ZLayer.make[
      AgentRuntime & LeaseAwareAgentRuntime & RunStore & RunCommandStore & RunSubmissionStore &
        AgentCommandService
    ](
      ZLayer.succeed[ChatModel](model),
      RegisteredToolRegistry.fromTools(Nil),
      RunStore.inMemory,
      RunCommandStore.inMemory,
      RunSubmissionStore.inMemory,
      TokenCounter.approximate,
      ContextCompressor.deterministic,
      DefaultContextManager.layer,
      ZLayer.succeed(
        GuardrailEngine(ConfiguredGuardrails(Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty))
      ),
      ZLayer.succeed(ToolPolicySource.static(ToolPolicyConfig.secureDefault)),
      ModelPolicySource.defaultLayer,
      RunObserver.noop,
      AgentRuntimeLive.layer,
      AgentCommandServiceLive.layer
    )

  /** 定义耐久状态机、审批和崩溃恢复的核心回归用例。 */
  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentRuntime")(
    test("模型覆盖到达真实请求，未覆盖字段沿用 Agent 定义，价格表折算进 estimatedCost") {
      // 这个测试守住"保存成功却毫无效果"这一整类缺陷：只断言覆盖被存下来无法证明它影响了任何一次模型调用，
      // 因此这里断言的是 ChatModel 实际收到的 settings 与状态里的累计费用。
      val defined = agent.copy(modelSettings =
        ModelSettings(provider = Some("primary"), model = Some("defined-model"), temperature = Some(0.9))
      )
      val policies = ModelPolicySource.static(
        ModelPolicy(provider = Some("fallback"), model = Some("cheap-model")),
        ModelPriceBook.of(
          ("fallback", "cheap-model", ModelPrice(BigDecimal(2), BigDecimal(10))),
          ("primary", "defined-model", ModelPrice(BigDecimal(1000), BigDecimal(9000)))
        )
      )
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("switched")))
        result <- (for
          runtime  <- ZIO.service[AgentRuntime]
          runs     <- ZIO.service[RunStore]
          outcome  <- runtime.run(defined, RunRequest(ThreadId("model-switch"), AgentMessage.user("你好")))
          state    <- runs.load(runIdOf(outcome))
          requests <- model.recordedRequests
        yield (state, requests)).provideLayer(layers(model, Nil, modelPolicies = policies))
        (state, requests) = result
        settings          = requests.head.settings
      yield assertTrue(
        requests.length == 1,
        // Provider 与模型被覆盖成故障切换目标。
        settings.provider.contains("fallback"),
        settings.model.contains("cheap-model"),
        // 未覆盖的温度必须保留 Agent 自己的取值，而不是被抹成 Provider 默认。
        settings.temperature.contains(0.9),
        // 步骤记录的模型名也必须是实际使用的那个，否则事后排查会指向一个从未被调用的模型。
        state.steps.collect { case step: AgentStep.ModelStep => step.model } == Chunk("cheap-model"),
        // finalResponse 的用量是 TokenUsage(3, 2)：3 * 2 / 1e6 + 2 * 10 / 1e6 = 0.000026。
        // 按被覆盖前的 primary 单价会得到 0.021，两者不可能混淆。
        state.usage.estimatedCost == BigDecimal("0.000026")
      )
    },
    test("Start 命令由 WorkerHost 在 lease fencing 下创建 RunStarted 并完成，HTTP 无需持有执行 Fiber") {
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("async-completed")))
        result <- (for
          service  <- ZIO.service[AgentCommandService]
          runtime  <- ZIO.service[LeaseAwareAgentRuntime]
          runs     <- ZIO.service[RunStore]
          commands <- ZIO.service[RunCommandStore]
          record   <- service.submitStart(
            agent,
            RunRequest(ThreadId("durable-async-start"), AgentMessage.user("异步执行")),
            "async-client-key"
          )
          before <- runs.load(record.runId)
          host   <- WorkerHost
            .make(WorkerId("async-start-worker"), WorkerHostConfig())
            .provide(ZLayer.succeed(commands), ZLayer.succeed(runtime))
          claimed      <- host.claimOnce
          after        <- runs.load(record.runId)
          savedCommand <- commands.get(record.commandId)
          events       <- runs.events(record.runId)
        yield (record, before, claimed, after, savedCommand, events))
          .provideLayer(durableControlLayers(model))
      yield assertTrue(
        result._1.payload == RunCommandPayload.Start,
        result._2.status == RunStatus.Created,
        result._3,
        result._4.status == RunStatus.Completed,
        result._5.status == RunCommandStatus.Completed,
        result._6.head.event.isInstanceOf[AgentEvent.RunCreated],
        result._6.exists(_.event.isInstanceOf[AgentEvent.RunStarted]),
        result._6.last.event.isInstanceOf[AgentEvent.RunCompleted]
      )
    },
    test("Worker 在 RunStarted 后崩溃时，过期 Start 由新 generation 从 Running 状态恢复而不重复创建") {
      for
        firstCallEntered <- Promise.make[Nothing, Unit]
        calls            <- Ref.make(0)
        crashOnceModel = new ChatModel:
          val provider                                                      = "crash-once"
          def complete(_request: ChatRequest): IO[AgentError, ChatResponse] =
            calls.getAndUpdate(_ + 1).flatMap {
              case 0 => firstCallEntered.succeed(()).unit *> ZIO.never
              case _ => ZIO.succeed(finalResponse("recovered-after-crash"))
            }
        result <- (for
          service  <- ZIO.service[AgentCommandService]
          runtime  <- ZIO.service[LeaseAwareAgentRuntime]
          runs     <- ZIO.service[RunStore]
          commands <- ZIO.service[RunCommandStore]
          record   <- service.submitStart(
            agent,
            RunRequest(ThreadId("start-crash-recovery"), AgentMessage.user("恢复测试")),
            "crash-client-key"
          )
          config = WorkerHostConfig(
            leaseDuration = 1.second,
            heartbeatEvery = 200.millis,
            pollEvery = 10.millis,
            retryDelay = Duration.Zero,
            maxAttempts = 3
          )
          firstHost <- WorkerHost
            .make(WorkerId("worker-before-crash"), config)
            .provide(ZLayer.succeed(commands), ZLayer.succeed(runtime))
          fiber      <- firstHost.claimOnce.fork
          _          <- firstCallEntered.await
          running    <- runs.load(record.runId)
          _          <- fiber.interrupt
          _          <- TestClock.adjust(2.seconds)
          secondHost <- WorkerHost
            .make(WorkerId("worker-after-crash"), config)
            .provide(ZLayer.succeed(commands), ZLayer.succeed(runtime))
          reclaimed <- secondHost.claimOnce
          completed <- runs.load(record.runId)
          command   <- commands.get(record.commandId)
          events    <- runs.events(record.runId)
          callCount <- calls.get
        yield (running, reclaimed, completed, command, events, callCount))
          .provideLayer(durableControlLayers(crashOnceModel))
      yield assertTrue(
        result._1.status == RunStatus.Running,
        result._2,
        result._3.status == RunStatus.Completed,
        result._4.status == RunCommandStatus.Completed,
        result._4.attempt == 2,
        result._5.count(_.event.isInstanceOf[AgentEvent.RunCreated]) == 1,
        result._5.count(_.event.isInstanceOf[AgentEvent.RunStarted]) == 1,
        result._6 == 2
      )
    },
    test("直接使用 AgentState/RunStore 完成运行且不产生旧 metadata checkpoint 投影") {
      for
        model   <- ScriptedChatModel.make(Chunk(finalResponse("完成")))
        outcome <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          result  <- runtime.run(agent, RunRequest(ThreadId("durable-direct"), AgentMessage.user("你好")))
          runId = runIdOf(result)
          state  <- store.load(runId)
          events <- store.events(runId)
        yield (result, state, events)).provideLayer(layers(model, Nil))
      yield assertTrue(
        outcome._1.isInstanceOf[RunOutcome.Completed],
        outcome._2.status == RunStatus.Completed,
        outcome._2.metadata.isEmpty,
        outcome._2.lastEventSequence == 3L,
        outcome._3.length == 4,
        outcome._3.head.event.isInstanceOf[AgentEvent.RunCreated]
      )
    },
    test("Runtime 使用冻结在 AgentDefinition 中的 ContextPolicy 并发出低敏 ContextPrepared 事件") {
      for
        model    <- ScriptedChatModel.make(Chunk(finalResponse("context-policy-applied")))
        received <- Ref.make(Option.empty[ContextPolicy])
        observed <- Ref.make(Chunk.empty[AgentEvent])
        policy  = ContextPolicy(maxToolResultCharacters = 321)
        manager = new ContextManager:
          def build(
              state: AgentState,
              definition: AgentDefinition,
              sources: ContextSources,
              actualPolicy: ContextPolicy
          ): IO[ContextError, PreparedContext] =
            received
              .set(Some(actualPolicy))
              .as(
                PreparedContext(
                  state.messages,
                  ContextUsage(17L, 2, 1, usedSummary = false, droppedMemories = 3, droppedRetrieval = 4),
                  ContextDebugView(
                    inputBudgetTokens = 100L,
                    estimatedTokens = 17L,
                    sections = Chunk.empty,
                    rotSignals = Chunk(
                      ContextRotSignal(
                        "context-history-heavy-drop",
                        ContextRotSeverity.Warning,
                        "测试只记录稳定诊断代码"
                      )
                    )
                  )
                )
              )
        observer = new RunObserver:
          def emit(event: AgentEvent): UIO[Unit] = observed.update(_ :+ event)
        configuredAgent = agent.copy(contextPolicy = policy)
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          outcome <- runtime.run(
            configuredAgent,
            RunRequest(ThreadId("agent-context-policy"), AgentMessage.user("验证上下文策略"))
          )
          actual <- received.get
          events <- observed.get
        yield (outcome, actual, events)).provideLayer(layersWithContextManager(model, manager, observer))
        contextEvent = result._3.collectFirst { case value: AgentEvent.ContextPrepared => value }
      yield assertTrue(
        result._1.isInstanceOf[RunOutcome.Completed],
        result._2.contains(policy),
        contextEvent.exists(_.estimatedTokens == 17L),
        contextEvent.exists(_.droppedMessages == 2),
        contextEvent.exists(_.truncatedToolResults == 1),
        contextEvent.exists(_.droppedMemories == 3),
        contextEvent.exists(_.droppedRetrieval == 4),
        contextEvent.exists(_.rotSignalCodes == Chunk("context-history-heavy-drop"))
      )
    },
    test("Runtime 在主模型前原子持久化摘要 checkpoint 与辅助模型 usage") {
      for
        model    <- ScriptedChatModel.make(Chunk(finalResponse("context-compacted")))
        observed <- Ref.make(Chunk.empty[AgentEvent])
        manager = new ContextManager:
          def build(
              state: AgentState,
              definition: AgentDefinition,
              sources: ContextSources,
              actualPolicy: ContextPolicy
          ): IO[ContextError, PreparedContext] =
            val _ = (definition, sources, actualPolicy)
            ZIO.succeed(
              PreparedContext(
                state.messages,
                ContextUsage(
                  estimatedTokens = 20L,
                  droppedMessages = 1,
                  truncatedToolResults = 0,
                  usedSummary = true,
                  compressionModelCalls = 1,
                  compressionInputTokens = 9L,
                  compressionOutputTokens = 4L
                ),
                summaryUpdate = Some(
                  ContextSummaryCheckpoint(
                    "[不可信历史摘要]\n已确认事实",
                    coveredMessages = 1,
                    sourceDigest = "0" * 64,
                    compressorVersion = "runtime-test-v1"
                  )
                ),
                compressionUsage = TokenUsage(9L, 4L)
              )
            )
        observer = new RunObserver:
          def emit(event: AgentEvent): UIO[Unit] = observed.update(_ :+ event)
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(
            agent,
            RunRequest(ThreadId("context-checkpoint"), AgentMessage.user("长历史"))
          )
          state   <- store.load(runIdOf(outcome))
          events  <- store.events(state.runId)
          emitted <- observed.get
        yield (state, events, emitted)).provideLayer(layersWithContextManager(model, manager, observer))
        compacted = result._2.collectFirst {
          case event if event.event.isInstanceOf[AgentEvent.ContextCompacted] => event
        }
      yield assertTrue(
        result._1.contextSummary.exists(_.compressorVersion == "runtime-test-v1"),
        result._1.usage.modelCalls == 2,
        result._1.usage.inputTokens == 12L,
        result._1.usage.outputTokens == 6L,
        result._1.lastEventSequence == 5L,
        compacted.nonEmpty,
        result._3.exists(_.isInstanceOf[AgentEvent.ContextCompacted])
      )
    },
    test("写工具先暂停，审批后只执行一次并把成功结果写入账本") {
      for
        executions <- Ref.make(0)
        tool       <- registeredEcho(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite, executions)
        model      <- ScriptedChatModel.make(Chunk(toolResponse("write-1", "draft"), finalResponse()))
        result     <- (for
          runtime   <- ZIO.service[AgentRuntime]
          store     <- ZIO.service[RunStore]
          suspended <- runtime.run(agent, RunRequest(ThreadId("durable-approval"), AgentMessage.user("写入草稿")))
          runId = runIdOf(suspended)
          recovered <- runtime.recover(runId)
          before    <- executions.get
          completed <- runtime.resume(runId, ApprovalDecision.Approve)
          after     <- executions.get
          ledger    <- store.getToolExecution(runId, "write-1")
          state     <- store.load(runId)
        yield (suspended, recovered, completed, before, after, ledger, state))
          .provideLayer(layers(model, List(tool)))
      yield assertTrue(
        result._1.isInstanceOf[RunOutcome.Suspended],
        result._2.isInstanceOf[RunOutcome.Suspended],
        result._3.isInstanceOf[RunOutcome.Completed],
        result._4 == 0,
        result._5 == 1,
        result._6.exists(_.status == ToolExecutionStatus.Succeeded),
        result._7.pendingToolPlan.isEmpty
      )
    },
    test("恢复发现非幂等 Running 记录时转为 Unknown 并要求人工核对") {
      for
        executions <- Ref.make(0)
        tool       <- registeredEcho(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite, executions)
        model      <- ScriptedChatModel.make(Chunk(toolResponse("unknown-1", "pay"), finalResponse()))
        result     <- (for
          runtime   <- ZIO.service[AgentRuntime]
          store     <- ZIO.service[RunStore]
          suspended <- runtime
            .run(agent, RunRequest(ThreadId("durable-recovery"), AgentMessage.user("执行副作用")))
          runId = runIdOf(suspended)
          now   <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          state <- store.load(runId)
          plan  <- ZIO.fromOption(state.pendingToolPlan).orElseFail(AgentError.Unexpected("测试状态缺少工具计划"))
          batch <- ZIO.fromOption(plan.currentBatch).orElseFail(AgentError.Unexpected("测试状态缺少当前批次"))
          prepared = ToolExecutionRecord(
            runId,
            batch.executionBatchId(plan.id),
            0,
            "unknown-1",
            "echo",
            Some("unknown-1"),
            ToolExecutionStatus.Prepared,
            None,
            0,
            now
          )
          _ <- store.prepareToolExecutions(NonEmptyChunk(prepared))
          _ <- store.transitionToolExecution(
            ToolExecutionStatus.Prepared,
            0,
            prepared.copy(status = ToolExecutionStatus.Running, attempt = 1)
          )
          recovered <- runtime.recover(runId)
          ledger    <- store.getToolExecution(runId, "unknown-1")
          count     <- executions.get
        yield (recovered, ledger, count)).provideLayer(layers(model, List(tool)))
      yield assertTrue(
        result._1.isInstanceOf[RunOutcome.Suspended],
        result._2.exists(_.status == ToolExecutionStatus.Unknown),
        result._3 == 0
      )
    },
    test("已批准的非幂等工具在 Running 时崩溃，恢复不会把历史批准当成自动重放授权") {
      for
        executions <- Ref.make(0)
        started    <- Promise.make[Nothing, Unit]
        tool       <- {
          val typed = Tool.json[Any, EchoInput, AgentError.ToolExecutionFailed, EchoOutput](
            ToolName("echo"),
            "模拟不可确定副作用",
            TestSchemas.stringObject("value", "副作用输入"),
            None,
            ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
          ) { (_, _) =>
            executions.update(_ + 1) *> started.succeed(()).unit *> ZIO.never
          }
          RegisteredTool.make(typed)
        }
        model  <- ScriptedChatModel.make(Chunk(toolResponse("approved-unknown", "write"), finalResponse()))
        result <- (for
          runtime   <- ZIO.service[AgentRuntime]
          store     <- ZIO.service[RunStore]
          suspended <- runtime.run(agent, RunRequest(ThreadId("approved-unknown"), AgentMessage.user("执行写入")))
          runId = runIdOf(suspended)
          resumeFiber <- runtime.resume(runId, ApprovalDecision.Approve).fork
          _           <- started.await
          _           <- resumeFiber.interrupt
          running     <- store.getToolExecution(runId, "approved-unknown")
          recovered   <- runtime.recover(runId)
          unknown     <- store.getToolExecution(runId, "approved-unknown")
          count       <- executions.get
        yield (running, recovered, unknown, count)).provideLayer(layers(model, List(tool)))
      yield assertTrue(
        result._1.exists(_.status == ToolExecutionStatus.Running),
        result._2.isInstanceOf[RunOutcome.Suspended],
        result._3.exists(_.status == ToolExecutionStatus.Unknown),
        result._4 == 1
      )
    },
    test("并行批次部分成功后中断，恢复复用成功账本并只重试未完成调用") {
      for
        executions  <- Ref.make(Map.empty[String, Int])
        runSeen     <- Promise.make[Nothing, RunId]
        fastDone    <- Promise.make[Nothing, Unit]
        slowStarted <- Promise.make[Nothing, Unit]
        releaseSlow <- Promise.make[Nothing, Unit]
        tool        <- {
          val typed = Tool.json[Any, EchoInput, AgentError.ToolExecutionFailed, EchoOutput](
            ToolName("echo"),
            "可恢复并行回显",
            TestSchemas.stringObject("value", "需要回显的文本"),
            None,
            ToolMetadata(
              ToolRisk.ReadOnly,
              SideEffect.None,
              parallelism = ToolParallelism.ConflictAware,
              conflictAccesses = Set(ToolConflictAccess("test.echo", ToolAccessMode.Read))
            )
          ) { (input, context) =>
            runSeen.succeed(context.runId).unit *>
              executions.update(current =>
                current.updated(input.value, current.getOrElse(input.value, 0) + 1)
              ) *>
              (if input.value == "fast" then fastDone.succeed(()).unit
               else slowStarted.succeed(()).unit *> releaseSlow.await) *>
              ZIO.succeed(EchoOutput(input.value))
          }
          RegisteredTool.make(typed)
        }
        model <- ScriptedChatModel.make(
          Chunk(parallelToolResponse("parallel-fast", "parallel-slow"), finalResponse("恢复完成"))
        )
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          fiber   <- runtime
            .run(agent, RunRequest(ThreadId("durable-partial-batch"), AgentMessage.user("并行执行")))
            .fork
          runId <- runSeen.await
          _     <- fastDone.await
          _     <- slowStarted.await
          _     <- (ZIO.yieldNow *> store.getToolExecution(runId, "parallel-fast"))
            .repeatUntil(_.exists(_.status == ToolExecutionStatus.Succeeded))
          _           <- fiber.interrupt
          beforeState <- store.load(runId)
          beforePlan  <- ZIO
            .fromOption(beforeState.pendingToolPlan)
            .orElseFail(AgentError.Unexpected("中断后缺少批次计划"))
          beforeBatch <- ZIO
            .fromOption(beforePlan.currentBatch)
            .orElseFail(AgentError.Unexpected("中断后缺少当前批次"))
          beforeLedger <- store.getToolExecutions(runId, beforeBatch.executionBatchId(beforePlan.id))
          _            <- releaseSlow.succeed(())
          recovered    <- runtime.recover(runId)
          afterState   <- store.load(runId)
          fastLedger   <- store.getToolExecution(runId, "parallel-fast")
          slowLedger   <- store.getToolExecution(runId, "parallel-slow")
          counts       <- executions.get
          toolOrder = afterState.messages.filter(_.role == MessageRole.Tool).flatMap(_.toolCallId)
        yield (recovered, beforeState, beforeLedger, afterState, fastLedger, slowLedger, counts, toolOrder))
          .provideLayer(layers(model, List(tool)))
      yield assertTrue(
        result._1.isInstanceOf[RunOutcome.Completed],
        result._2.status == RunStatus.Running,
        result._2.pendingToolPlan.nonEmpty,
        result._3.map(_.status).toSet == Set(ToolExecutionStatus.Succeeded, ToolExecutionStatus.Running),
        result._4.pendingToolPlan.isEmpty,
        result._5.exists(record => record.status == ToolExecutionStatus.Succeeded && record.attempt == 1),
        result._6.exists(record => record.status == ToolExecutionStatus.Succeeded && record.attempt == 2),
        result._7 == Map("fast" -> 1, "slow" -> 2),
        result._8 == Chunk("parallel-fast", "parallel-slow")
      )
    },
    test("模型一次提出的工具数会在任何 Prepared 或副作用之前接受总预算门禁") {
      for
        executions <- Ref.make(0)
        tool       <- registeredEcho(ToolRisk.ReadOnly, SideEffect.None, executions)
        model      <- ScriptedChatModel.make(Chunk(parallelToolResponse("budget-1", "budget-2")))
        exit       <- ZIO
          .serviceWithZIO[AgentRuntime](
            _.run(agent, RunRequest(ThreadId("tool-budget-preflight"), AgentMessage.user("超出工具预算")))
          )
          .exit
          .provideLayer(
            layers(
              model,
              List(tool),
              toolPolicy = ToolPolicyConfig.secureDefault.copy(maxCallsPerRun = 1)
            )
          )
        count <- executions.get
        budgetFailed = exit match
          case Exit.Failure(cause) =>
            cause.failureOption.exists {
              case AgentError.BudgetExceeded("toolCalls", 1L) => true
              case _                                          => false
            }
          case Exit.Success(_) => false
      yield assertTrue(
        budgetFailed,
        count == 0
      )
    },
    test("runEvents 输出统一 AgentEvent 文本增量和耐久完成状态") {
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("流式完成")))
        events <- AgentRuntime
          .runEvents(agent, RunRequest(ThreadId("durable-events"), AgentMessage.user("你好")))
          .runCollect
          .provideLayer(layers(model, Nil))
      yield assertTrue(
        events.exists(_.isInstanceOf[AgentEvent.RunCreated]),
        events.exists(_.isInstanceOf[AgentEvent.ModelTextDelta]),
        events.exists(_.isInstanceOf[AgentEvent.RunCompleted]),
        events.exists(_.isInstanceOf[AgentEvent.CheckpointSaved])
      )
    },
    test("显式 ContextSourceResolver 的记忆、引用和安全约束进入真实模型请求") {
      val resolver = new ContextSourceResolver:
        def resolve(state: AgentState, definition: AgentDefinition): UIO[ContextSources] =
          ZIO.succeed(
            ContextSources(
              memories = Chunk(ContextMemory("学习偏好", "偏好引用经典原文", 0.9)),
              retrieval = Chunk(ContextDocument("cite-1", "阴阳者，天地之道也。", "book://huangdi", Some(0.95))),
              safetyInstructions = Chunk("资料不足时明确说明")
            )
          )
      for
        model <- ScriptedChatModel.make(Chunk(finalResponse("有引用的回答")))
        _     <- AgentRuntime
          .run(agent, RunRequest(ThreadId("context-source-runtime"), AgentMessage.user("解释阴阳")))
          .provideLayer(layersWithContextSources(model, resolver))
        requests <- model.recordedRequests
        text = requests.head.messages.map(_.text).mkString("\n")
      yield assertTrue(
        text.contains("资料不足时明确说明"),
        text.contains("偏好引用经典原文"),
        text.contains("[cite-1] 阴阳者，天地之道也。"),
        text.contains("来源: book://huangdi")
      )
    },
    test("显式取消中断活动模型 Fiber，并把同一 AgentState 持久化为 Cancelled") {
      val slowModel = new ChatModel:
        val provider                                                                          = "slow"
        def complete(request: ChatRequest): IO[AgentError, ChatResponse]                      = ZIO.never
        override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
          ZStream.succeed(ModelStreamEvent.TextDelta("started")) ++ ZStream.never
      (for
        runtime  <- ZIO.service[AgentRuntime]
        started  <- Promise.make[Nothing, RunId]
        consumer <- runtime
          .runEvents(agent, RunRequest(ThreadId("durable-cancel"), AgentMessage.user("等待")))
          .tap {
            case AgentEvent.RunStarted(runId, _) => started.succeed(runId).unit
            case _                               => ZIO.unit
          }
          .runDrain
          .fork
        runId <- started.await
        _     <- runtime.cancel(runId)
        state <- runtime.inspect(runId)
        exit  <- consumer.await
      yield assertTrue(
        state.status == RunStatus.Cancelled,
        exit.isFailure
      )).provideLayer(layers(slowModel, Nil))
    },
    test("输入 Guardrail 拒绝仍保留 RunCreated 与 Failed 耐久审计") {
      val rejecting = new InputGuardrail:
        val name = "reject-test"
        def evaluate(
            message: AgentMessage,
            context: GuardrailContext
        ): IO[GuardrailError, GuardrailDecision] =
          ZIO.succeed(GuardrailDecision(allowed = false, reason = Some("测试拒绝")))
      val engine = GuardrailEngine(
        ConfiguredGuardrails(
          Chunk(rejecting -> GuardrailMode.Blocking),
          Chunk.empty,
          Chunk.empty,
          Chunk.empty
        )
      )
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("不应调用")))
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          created <- Promise.make[Nothing, RunId]
          exit    <- runtime
            .runEvents(agent, RunRequest(ThreadId("guardrail-reject"), AgentMessage.user("拒绝")))
            .tap {
              case AgentEvent.RunCreated(runId, _, _) => created.succeed(runId).unit
              case _                                  => ZIO.unit
            }
            .runDrain
            .exit
          runId  <- created.await
          state  <- store.load(runId)
          events <- store.events(runId)
        yield (exit, state, events)).provideLayer(layers(model, Nil, engine))
      yield assertTrue(
        result._1.isFailure,
        result._2.status == RunStatus.Failed,
        result._3.head.event.isInstanceOf[AgentEvent.RunCreated],
        result._3.last.event.isInstanceOf[AgentEvent.RunFailed]
      )
    },
    test("消费者提前关闭 SSE Scope 不会阻塞终止事件投递，并持久化取消") {
      val noisyModel = new ChatModel:
        val provider                                                                          = "noisy"
        def complete(request: ChatRequest): IO[AgentError, ChatResponse]                      = ZIO.never
        override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
          ZStream
            .fromIterable(0 until 1000)
            .map(index => ModelStreamEvent.TextDelta(index.toString)) ++ ZStream.never
      (for
        runtime <- ZIO.service[AgentRuntime]
        first   <- runtime
          .runEvents(agent, RunRequest(ThreadId("early-disconnect"), AgentMessage.user("立即断开")))
          .runHead
          .timeoutFail(AgentError.Unexpected("关闭 SSE Scope 超时"))(2.seconds)
        runId <- first match
          case Some(AgentEvent.RunCreated(id, _, _)) => ZIO.succeed(id)
          case other => ZIO.fail(AgentError.Unexpected(s"首事件不是 RunCreated: $other"))
        state <- runtime.inspect(runId)
      yield assertTrue(state.status == RunStatus.Cancelled)).provideLayer(layers(noisyModel, Nil))
    }
  )
