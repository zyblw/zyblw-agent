package com.zyblw.agent.runtime

import com.zyblw.agent.composition.{
  ApprovalPolicyFingerprint,
  ApprovalSubject,
  AuthorizationFingerprint,
  RuntimeComposition,
  RuntimeCompositionFingerprint,
  RuntimeProfile,
  ToolContractFingerprint
}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import java.util.concurrent.atomic.AtomicReference
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 创建时冻结组合，恢复时对模型覆盖与缺失工具 fail-closed。 */
object RuntimeCompositionRuntimeSpec extends ZIOSpecDefault:
  private val agent = AgentDefinition(
    AgentId("composition-runtime"),
    "Composition Runtime",
    "直接完成请求。",
    allowedTools = Set("echo"),
    modelSettings = ModelSettings(provider = Some("primary"), model = Some("defined-model"))
  )

  private def finalResponse(text: String): ChatResponse =
    ChatResponse(AgentMessage.assistant(text), FinishReason.Stop, TokenUsage(3, 2))

  private def layers(
      model: ChatModel,
      modelPolicies: ModelPolicySource,
      tools: Iterable[RegisteredTool] = Nil,
      toolPolicy: ToolPolicyConfig = ToolPolicyConfig.secureDefault
  ) =
    TestAgentRuntime.inMemory(model, tools, modelPolicies = modelPolicies, toolPolicy = toolPolicy)

  private def echoTool(
      description: String,
      toolMetadata: ToolMetadata = ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
  ): RegisteredTool = new RegisteredTool:
    val definition = ToolDefinition(
      "echo",
      description,
      Json.Obj("type" -> Json.Str("object")),
      None
    )
    val metadata                                                                           = toolMetadata
    def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
      ZIO.succeed(ToolResult(arguments))

  private def seedCreated(
      store: RunStore,
      composition: RuntimeCompositionFingerprint
  ): IO[AgentError, RunId] =
    for
      now     <- Clock.instant
      runId   <- RunId.random
      eventId <- EventId.random
      state = RunInitialization.initialState(
        runId,
        agent,
        RunRequest(ThreadId("composition-runtime"), AgentMessage.user("hello")),
        32,
        now,
        composition
      )
      event = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, state.sessionId, now.toEpochMilli),
        now.toEpochMilli
      )
      _ <- store.createWithEvents(state, NonEmptyChunk(event))
    yield runId

  /** 构造一个与 Runtime 现场计算方式一致的审批主体，供“规划时冻结”场景使用。 */
  private def approvalSubjectFor(
      tool: RegisteredTool,
      policy: ToolPolicyConfig,
      call: ToolCall = echoCall,
      runContext: RunContext = RunContext()
  ): ApprovalSubject =
    ApprovalSubject.of(
      call = call,
      metadata = tool.metadata,
      toolContract = ToolContractFingerprint.registered(tool),
      policy = ApprovalPolicyFingerprint.of(policy, ToolName(call.name)),
      authorization = AuthorizationFingerprint.of(runContext)
    )

  private val echoCall = ToolCall("call-echo", "echo", Json.Obj())

  private def seedRunning(
      store: RunStore,
      composition: RuntimeCompositionFingerprint,
      toolContractFingerprints: Map[String, ToolContractFingerprint] = Map(
        "echo" -> ToolContractFingerprint.missing("echo")
      ),
      approvalSubjects: Map[String, ApprovalSubject] = Map.empty
  ): IO[AgentError, RunId] =
    for
      now     <- Clock.instant
      runId   <- RunId.random
      eventId <- EventId.random
      created = RunInitialization.initialState(
        runId,
        agent,
        RunRequest(ThreadId("composition-runtime"), AgentMessage.user("hello")),
        32,
        now,
        composition
      )
      plan = DurableToolPlan(
        "plan-echo",
        Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, echoCall)))),
        toolContractFingerprints = toolContractFingerprints,
        approvalSubjects = approvalSubjects
      )
      state = created.copy(
        status = RunStatus.Running,
        updatedAt = now,
        pendingToolPlan = Some(plan)
      )
      event = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, state.sessionId, now.toEpochMilli),
        now.toEpochMilli
      )
      _ <- store.createWithEvents(state, NonEmptyChunk(event))
    yield runId

  def spec: Spec[TestEnvironment & Scope, Any] = suite("RuntimeComposition recovery")(
    test("同步 run 冻结组合指纹") {
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("ok")))
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("freeze-run"), AgentMessage.user("hi")))
          runId = outcome match
            case RunOutcome.Completed(id, _, _, _, _) => id
            case RunOutcome.Suspended(id, _, _, _, _) => id
          state <- store.load(runId)
        yield state)
          .provideLayer(layers(model, ModelPolicySource.default, tools = List(echoTool("stable echo"))))
      yield assertTrue(
        result.composition ==
          RuntimeComposition.freeze(RuntimeProfile.default, agent, ModelPolicySource.default)
      )
    },
    test("连续运行中模型覆盖漂移在下一次调用前 fail-closed") {
      val overlay = new AtomicReference(ModelPolicy(maxOutputTokens = Some(128)))
      val source  = new ModelPolicySource:
        def current(): ModelPolicy = overlay.get()
      val policy = ToolPolicyConfig(allowedTools = Set(ToolName("echo")))
      val tool   = echoTool("stable echo")
      for
        calls <- Ref.make(0)
        model = new ChatModel:
          val provider                                                     = "scripted"
          def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
            calls.getAndUpdate(_ + 1).flatMap {
              case 0 =>
                ZIO.succeed(overlay.set(ModelPolicy(maxOutputTokens = Some(64)))) *>
                  ZIO.succeed(
                    ChatResponse(
                      AgentMessage.assistantToolCalls(Chunk(echoCall)),
                      FinishReason.ToolCalls,
                      TokenUsage(3, 2)
                    )
                  )
              case _ => ZIO.succeed(finalResponse("must-not-run"))
            }
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          exit    <- runtime
            .run(agent, RunRequest(ThreadId("mid-run-model-drift"), AgentMessage.user("run")))
            .exit
          count <- calls.get
        yield (exit, count)).provideLayer(layers(model, source, List(tool), policy))
        (exit, count) = result
      yield assertTrue(
        count == 1,
        exit.causeOption.flatMap(_.failureOption).exists {
          case AgentError.CompositionIncompatible(_, changed, _) =>
            changed.map(_.field).contains("modelRef")
          case _ => false
        }
      )
    },
    test("漂移拒绝会发出 CompositionDriftDetected，只含 kind 与属性名") {
      val frozen  = RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings)
      val drifted = ModelPolicySource.static(ModelPolicy(model = Some("cheap-model")))
      for
        model    <- ScriptedChatModel.make(Chunk(finalResponse("should-not-run")))
        observed <- Ref.make(Chunk.empty[AgentEvent])
        recorder = ZLayer.succeed[RunObserver](event => observed.update(_ :+ event))
        _ <- (for
          store   <- ZIO.service[RunStore]
          runtime <- ZIO.service[AgentRuntime]
          runId   <- seedCreated(store, frozen)
          _       <- runtime.recover(runId).exit
        yield ()).provideLayer(
          TestAgentRuntime.inMemory(model, Nil, modelPolicies = drifted, observer = recorder)
        )
        events <- observed.get
      yield assertTrue(
        events
          .collect { case drift: AgentEvent.CompositionDriftDetected => drift }
          .exists(drift =>
            drift.kind == "incompatible" && drift.changedFields.map(_.field).contains("modelRef")
          ),
        // 事件不得携带任何一侧取值：这里 cheap-model 是"新模型名"，出现即为泄漏。
        !events.exists {
          case drift: AgentEvent.CompositionDriftDetected =>
            drift.toString.contains("cheap-model") || drift.toString.contains("defined-model")
          case _ => false
        }
      )
    },
    test("生效模型覆盖变化时恢复 fail-closed，且不把 Run 标为 Failed") {
      val frozen  = RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings)
      val drifted = ModelPolicySource.static(ModelPolicy(model = Some("cheap-model")))
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("should-not-run")))
        result <- (for
          store   <- ZIO.service[RunStore]
          runtime <- ZIO.service[AgentRuntime]
          runId   <- seedCreated(store, frozen)
          exit    <- runtime.recover(runId).exit
          state   <- store.load(runId)
        yield (exit, state)).provideLayer(layers(model, drifted))
        (exit, state) = result
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[AgentError.CompositionIncompatible]),
        state.status == RunStatus.Created
      )
    },
    test("冻结工具从注册表消失时恢复 fail-closed") {
      val frozen = RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings)
      for
        model <- ScriptedChatModel.make(Chunk(finalResponse("should-not-run")))
        exit  <- (for
          store   <- ZIO.service[RunStore]
          runtime <- ZIO.service[AgentRuntime]
          runId   <- seedRunning(store, frozen)
          result  <- runtime.recover(runId).exit
        yield result).provideLayer(layers(model, ModelPolicySource.default))
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists {
          case AgentError.CompositionIncompatible(_, changed, _) =>
            changed.map(_.field).contains("requiredToolsMissing")
          case _ => false
        }
      )
    },
    test("同名工具 Schema 或安全契约漂移时在副作用前 fail-closed") {
      val frozenComposition =
        RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings)
      val originalTool    = echoTool("original contract")
      val changedTool     = echoTool("changed contract")
      val frozenContracts = Map("echo" -> ToolContractFingerprint.registered(originalTool))
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("should-not-run")))
        result <- (for
          store   <- ZIO.service[RunStore]
          runtime <- ZIO.service[AgentRuntime]
          runId   <- seedRunning(store, frozenComposition, frozenContracts)
          exit    <- runtime.recover(runId).exit
          state   <- store.load(runId)
        yield (exit, state)).provideLayer(layers(model, ModelPolicySource.default, List(changedTool)))
        (exit, state) = result
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists {
          case AgentError.CompositionIncompatible(_, changed, _) =>
            changed.map(_.field).contains("toolContractFingerprint")
          case _ => false
        },
        state.pendingToolPlan.exists(_.toolContractFingerprints == frozenContracts),
        state.steps.isEmpty
      )
    },
    test("新建工具计划自动冻结实际注册契约指纹") {
      val approvalTool = echoTool(
        "approval contract",
        ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
      )
      val toolResponse = ChatResponse(
        AgentMessage.assistantToolCalls(Chunk(ToolCall("call-echo", "echo", Json.Obj()))),
        FinishReason.ToolCalls,
        TokenUsage(3, 2)
      )
      val policy = ToolPolicyConfig(allowedTools = Set(ToolName("echo")))
      for
        model  <- ScriptedChatModel.make(Chunk(toolResponse))
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime
            .run(agent, RunRequest(ThreadId("freeze-tool-contract"), AgentMessage.user("run")))
          runId = outcome match
            case RunOutcome.Completed(id, _, _, _, _) => id
            case RunOutcome.Suspended(id, _, _, _, _) => id
          state <- store.load(runId)
        yield (outcome, state)).provideLayer(
          layers(model, ModelPolicySource.default, List(approvalTool), policy)
        )
        (outcome, state) = result
      yield assertTrue(
        outcome.isInstanceOf[RunOutcome.Suspended],
        state.pendingToolPlan.exists(
          _.toolContractFingerprints.get("echo").contains(ToolContractFingerprint.registered(approvalTool))
        ),
        state.pendingToolPlan.exists(_.frozenApprovalCallIds == Set("call-echo")),
        state.pendingToolPlan
          .flatMap(_.approvalSubjects.get("call-echo"))
          .contains(approvalSubjectFor(approvalTool, policy)),
        // 暂停请求必须带上主体，否则批准无法绑定到具体副作用。
        state.pendingApproval
          .flatMap(_.subject)
          .contains(approvalSubjectFor(approvalTool, policy))
      )
    },
    test("规划时冻结的审批要求不能被部署策略放宽取消") {
      val frozenComposition =
        RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings)
      val writeTool = echoTool(
        "stable write contract",
        ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
      )
      val contracts = Map("echo" -> ToolContractFingerprint.registered(writeTool))
      val strict    = ToolPolicyConfig(allowedTools = Set(ToolName("echo")))
      val relaxed   = ToolPolicyConfig(
        allowedTools = Set(ToolName("echo")),
        approvalPolicy = ApprovalPolicy.Never
      )
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("should-not-run")))
        result <- (for
          store   <- ZIO.service[RunStore]
          runtime <- ZIO.service[AgentRuntime]
          runId   <- seedRunning(
            store,
            frozenComposition,
            contracts,
            approvalSubjects = Map("call-echo" -> approvalSubjectFor(writeTool, strict))
          )
          outcome <- runtime.recover(runId)
          state   <- store.load(runId)
        yield (outcome, state)).provideLayer(
          layers(model, ModelPolicySource.default, List(writeTool), relaxed)
        )
        (outcome, state) = result
      yield assertTrue(
        outcome.isInstanceOf[RunOutcome.Suspended],
        state.status == RunStatus.WaitingForApproval,
        state.pendingApproval.exists(_.toolCall.id == "call-echo")
      )
    },
    test("组合指纹一致的 Created Run 可以正常恢复到完成") {
      val frozen = RuntimeComposition.freeze(RuntimeProfile.default, agent, ModelPolicySource.default)
      for
        model  <- ScriptedChatModel.make(Chunk(finalResponse("ok")))
        result <- (for
          store   <- ZIO.service[RunStore]
          runtime <- ZIO.service[AgentRuntime]
          runId   <- seedCreated(store, frozen)
          outcome <- runtime.recover(runId)
          state   <- store.load(runId)
        yield (outcome, state)).provideLayer(
          layers(model, ModelPolicySource.default, tools = List(echoTool("stable echo")))
        )
        (outcome, state) = result
      yield assertTrue(
        outcome.isInstanceOf[RunOutcome.Completed],
        state.status == RunStatus.Completed,
        state.composition == frozen
      )
    }
  )
