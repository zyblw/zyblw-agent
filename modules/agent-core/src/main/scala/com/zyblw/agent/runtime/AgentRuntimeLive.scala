package com.zyblw.agent.runtime

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.tools.*
import java.util.concurrent.TimeUnit
import zio.*
import zio.json.ast.Json
import zio.stream.*

/** 直接以 `AgentState`/`RunStore` 为事实来源的唯一生产运行时。
  *
  * 这里不存在第二套 checkpoint 或 metadata JSON 投影；每次状态转换都通过 `RunStore.commit(expectedVersion, state, events)`
  * 乐观锁事务保存。Context、Guardrail、类型化工具注册表、统一 ToolExecutor 和工具执行账本都处于同一个循环内，因此测试与生产使用的是同一条路径。
  */
final class AgentRuntimeLive(
    model: ChatModel,
    registry: RegisteredToolRegistry,
    store: RunStore,
    contextManager: ContextManager,
    contextSources: ContextSourceResolver,
    guardrails: GuardrailEngine,
    toolPolicies: ToolPolicySource,
    modelPolicies: ModelPolicySource,
    observer: RunObserver,
    eventQueue: FiberRef[Option[Queue[Take[AgentError, AgentEvent]]]],
    activeRuns: Ref[Map[RunId, Fiber.Runtime[AgentError, RunOutcome]]],
    executionLease: FiberRef[Option[RunCommandLease]]
) extends AgentRuntime,
      LeaseAwareAgentRuntime:

  /** 创建初始状态、执行输入 Guardrail，并在总时限内推动状态机。
    * @param agent
    *   会被保存为定义快照，确保恢复不受部署后配置漂移影响
    * @param request
    *   包含线程、输入、可信业务上下文和本次预算
    */
  def run(agent: AgentDefinition, request: RunRequest): IO[AgentError, RunOutcome] =
    RunId.random.flatMap(runWithId(_, agent, request))

  /** 由同步入口与流式入口共享的创建流程；显式传入 RunId，确保首个事件就能向 SSE 客户端暴露稳定标识。
    * @param runId
    *   在任何状态写入或模型调用前生成的运行标识
    * @param agent
    *   本次运行冻结保存的 Agent 定义
    * @param request
    *   用户输入、可信权限上下文和预算
    */
  private def runWithId(
      runId: RunId,
      agent: AgentDefinition,
      request: RunRequest
  ): IO[AgentError, RunOutcome] =
    for
      now     <- Clock.instant
      eventId <- EventId.random
      initial = RunInitialization.initialState(
        runId,
        agent,
        request,
        toolPolicies.current().maxCallsPerRun,
        now
      )
      createdEvent = AgentEvent.RunCreated(runId, initial.sessionId, now.toEpochMilli)
      persisted    = PersistedAgentEvent(eventId, runId, 0L, createdEvent, now.toEpochMilli)
      _       <- store.createWithEvents(initial, NonEmptyChunk(persisted))
      _       <- emit(createdEvent)
      outcome <- startCreated(initial)
    yield outcome

  /** 把已经耐久保存的 Created 状态推进为 Running，并进入主循环。
    *
    * 同步本地入口和分布式 `Start` 命令共用该方法。分布式调用时 `executionLease` 已由 `executeLeased` 绑定，所以 `save` 自动走
    * `commitFenced`；HTTP 请求 Fiber 不会进入这里。
    *
    * @param initial
    *   必须包含首条输入、Agent 定义、threadId 与完整预算的 Created 状态
    */
  private def startCreated(initial: AgentState): IO[AgentError, RunOutcome] =
    for
      _ <- ZIO
        .fail(AgentError.InvalidResume(initial.runId, s"Start 只接受 Created，实际为 ${initial.status}"))
        .unless(initial.status == RunStatus.Created)
      input <- ZIO
        .fromOption(initial.messages.headOption)
        .orElseFail(AgentError.InvalidConfiguration(s"Run ${initial.runId.asString} 缺少首条输入消息"))
      outcome <- (for
        inputDecisions <- guardrails.checkInput(input, guardrailContext(initial))
        _              <- emitGuardrails(initial.runId, "input", inputDecisions)
        startedAt      <- Clock.instant
        running        <- save(
          initial,
          initial.copy(status = RunStatus.Running, updatedAt = startedAt),
          AgentEvent.RunStarted(initial.runId, startedAt.toEpochMilli)
        )
        result <- loop(running)
      yield result)
        .timeoutFail(
          AgentError.BudgetExceeded("durationMillis", initial.budget.limits.maxDuration.toMillis)
        )(initial.budget.limits.maxDuration)
        .tapError(error => markFailed(initial.runId, error))
    yield outcome

  /** 为新 Run 创建有界事件队列；流消费者的 Scope 是执行 Fiber 的唯一父生命周期。
    * @param agent
    *   要冻结保存的 Agent 定义
    * @param request
    *   本次用户输入、上下文与预算
    */
  def runEvents(agent: AgentDefinition, request: RunRequest): ZStream[Any, AgentError, AgentEvent] =
    ZStream.unwrapScoped {
      for
        runId  <- RunId.random
        stream <- streamEffect(runId, runWithId(runId, agent, request))
      yield stream
    }

  /** 将审批恢复放入与新 Run 相同的结构化事件流生命周期。
    * @param runId
    *   等待审批的运行
    * @param decision
    *   明确的审批决定
    */
  def resumeEvents(runId: RunId, decision: ApprovalDecision): ZStream[Any, AgentError, AgentEvent] =
    ZStream.unwrapScoped(streamEffect(runId, resume(runId, decision)))

  /** 将崩溃恢复放入结构化事件流；关闭 HTTP/SSE 连接会中断恢复 Fiber。
    * @param runId
    *   需要依据状态与工具账本恢复的运行
    */
  def recoverEvents(runId: RunId): ZStream[Any, AgentError, AgentEvent] =
    ZStream.unwrapScoped(streamEffect(runId, recover(runId)))

  /** 持久化人工决定后恢复工具游标。
    * @param runId
    *   必须处于 WaitingForApproval
    * @param decision
    *   批准会先保存 RunResumed，再允许副作用；拒绝会回填结构化工具错误
    */
  def resume(runId: RunId, decision: ApprovalDecision): IO[AgentError, RunOutcome] =
    for
      state <- store.load(runId)
      plan  <- ZIO.fromOption(state.pendingToolPlan).orElseFail(AgentError.InvalidResume(runId, "没有待处理工具计划"))
      batch <- ZIO.fromOption(plan.currentBatch).orElseFail(AgentError.InvalidResume(runId, "工具计划已经完成"))
      item  <- ZIO.fromOption(batch.items.headOption).orElseFail(AgentError.InvalidResume(runId, "当前工具批次为空"))
      _     <- ZIO.fail(AgentError.InvalidResume(runId, "审批批次必须只包含一个工具调用")).unless(batch.items.length == 1)
      approval <- ZIO.fromOption(state.pendingApproval).orElseFail(AgentError.InvalidResume(runId, "缺少待审批请求"))
      _        <- ZIO
        .fail(AgentError.InvalidResume(runId, "审批请求与当前工具批次不一致"))
        .unless(approval.toolCall.id == item.call.id)
      _ <- ZIO
        .fail(AgentError.InvalidResume(runId, s"状态为 ${state.status}"))
        .unless(state.status == RunStatus.WaitingForApproval)
      next <- decision match
        case ApprovalDecision.Approve =>
          for
            now <- Clock.instant
            approvalStep = AgentStep.ApprovalStep(
              state.steps.length + 1,
              approval,
              Some(decision),
              now.toEpochMilli
            )
            approved <- save(
              state,
              state.copy(
                status = RunStatus.Running,
                pendingApproval = None,
                steps = state.steps :+ approvalStep,
                updatedAt = now
              ),
              AgentEvent.RunResumed(runId, now.toEpochMilli)
            )
            next <- processToolPlan(approved, approvedCallIds = Set(item.call.id))
          yield next
        case ApprovalDecision.Reject(reason) =>
          val result       = ToolResult(Json.Obj("error" -> Json.Str(s"审批拒绝: $reason")), isError = true)
          val approvalStep = AgentStep.ApprovalStep(
            state.steps.length + 1,
            approval,
            Some(decision),
            approval.requestedAtEpochMilli
          )
          appendToolBatchResults(
            state.copy(
              status = RunStatus.Running,
              pendingApproval = None,
              steps = state.steps :+ approvalStep
            ),
            batch,
            Chunk(item -> result)
          ).flatMap(next => processToolPlan(next))
    yield next

  /** 依据状态与执行账本恢复，而不是重新从用户输入开始。
    * @param runId
    *   已存在的耐久 Run；终态只返回结果或 typed conflict
    */
  def recover(runId: RunId): IO[AgentError, RunOutcome] =
    for
      state  <- store.load(runId)
      result <- state.status match
        case RunStatus.Completed => completedOutcome(state)
        case RunStatus.Cancelled => ZIO.fail(AgentError.Cancelled(runId))
        case RunStatus.Failed | RunStatus.TimedOut | RunStatus.BudgetExceeded =>
          ZIO.fail(AgentError.InvalidResume(runId, s"终态 ${state.status} 不能自动恢复"))
        case RunStatus.WaitingForApproval | RunStatus.Suspended => recoverPending(state)
        case RunStatus.Created                                  => startCreated(state)
        case RunStatus.Running                                  =>
          state.pendingToolPlan match
            case Some(_) => recoverToolPlan(state)
            case None    => loop(state)
    yield result

  /** 执行 WorkerHost claim 的类型化耐久命令，并把 fencing 凭证绑定到当前 Fiber 及其所有子 Fiber。
    *
    * `FiberRef.locally` 在成功、失败或中断时恢复旧值；因此审批、取消、恢复过程中每次 `saveEvents` 都会自动选择 `commitFenced`，而同一个 Runtime
    * 实例处理下一条命令时不会继承上一条命令的权限。
    *
    * @param lease
    *   command、owner、token、generation 与过期时间组成的数据库租约
    */
  def executeLeased(lease: RunCommandLease): IO[AgentError, Unit] =
    executionLease.locally(Some(lease)) {
      lease.command.payload match
        case RunCommandPayload.Start                                => startCommand(lease.runId)
        case RunCommandPayload.Recover                              => recoverCommand(lease.runId)
        case RunCommandPayload.Retry(_)                             => recoverCommand(lease.runId)
        case RunCommandPayload.Cancel(_)                            => cancel(lease.runId)
        case RunCommandPayload.ResumeApproval(approvalId, decision) =>
          resumeApprovalCommand(lease.runId, approvalId, decision)
    }

  /** 幂等执行新建命令。
    *
    * Worker 可能在提交 RunStarted、完成模型循环或进入审批状态后崩溃，而命令还未 `complete`。重领 Start 时不能重新 写第二个 RunStarted：Created
    * 才执行首次启动；Running 从状态/工具账本恢复；其余状态表示本次启动已产生稳定结果。
    */
  private def startCommand(runId: RunId): IO[AgentError, Unit] =
    store.load(runId).flatMap { state =>
      state.status match
        case RunStatus.Created => startCreated(state).unit
        case RunStatus.Running => recoverCommand(runId)
        case RunStatus.WaitingForApproval | RunStatus.Suspended | RunStatus.Completed | RunStatus.Cancelled |
            RunStatus.Failed | RunStatus.TimedOut | RunStatus.BudgetExceeded =>
          ZIO.unit
    }

  /** 已取消 Run 对恢复命令而言是幂等完成，而不是需要无限重试的 worker 错误。 */
  private def recoverCommand(runId: RunId): IO[AgentError, Unit] =
    recover(runId).unit.catchSome { case _: AgentError.Cancelled => ZIO.unit }

  /** 以 approvalId 绑定决定，并处理“状态已提交、命令尚未 complete 时进程崩溃”的重放窗口。
    *
    * 如果同一审批步骤已经存在：Running/Created 表示先前命令在后续 loop 中崩溃，需要从账本继续恢复；终态或已经进入
    * 下一次审批则说明该决定已经完成，直接幂等返回。若历史决定与命令正文不同，必须拒绝而不能以后写覆盖前写。
    */
  private def resumeApprovalCommand(
      runId: RunId,
      approvalId: String,
      decision: ApprovalDecision
  ): IO[AgentError, Unit] =
    for
      state <- store.load(runId)
      historical = state.steps.collectFirst {
        case AgentStep.ApprovalStep(_, request, Some(recorded), _) if request.id == approvalId => recorded
      }
      _ <- historical match
        case Some(recorded) if recorded != decision =>
          ZIO.fail(AgentError.InvalidResume(runId, s"审批 $approvalId 已记录另一决定"))
        case Some(_) if state.status == RunStatus.Created || state.status == RunStatus.Running =>
          recoverCommand(runId)
        case Some(_) => ZIO.unit
        case None    =>
          for
            pending <- ZIO
              .fromOption(state.pendingApproval)
              .orElseFail(AgentError.InvalidResume(runId, "缺少待审批请求"))
            _ <- ZIO.fail(AgentError.InvalidResume(runId, "审批命令与当前请求不一致")).unless(pending.id == approvalId)
            _ <- resume(runId, decision).unit
          yield ()
    yield ()

  /** 按 RunId 读取状态快照；不修改版本、取消位或恢复游标。
    * @param runId
    *   目标运行
    */
  def inspect(runId: RunId): IO[AgentError, AgentState] = store.load(runId)

  /** 从 RunStore 的单调序号事件日志读取可断点续传的精选事件。
    * @param runId
    *   目标运行
    * @param afterSequence
    *   客户端已确认的最后序号，返回值严格大于它
    */
  def persistedEvents(
      runId: RunId,
      afterSequence: Long,
      limit: Int
  ): IO[AgentError, Chunk[PersistedAgentEvent]] =
    store.events(runId, afterSequence, limit)

  /** 对非终态 Run 写入可跨进程观察的取消位，并提交 Cancelled 状态事件。
    * @param runId
    *   目标 Run；终态调用是幂等空操作
    */
  def cancel(runId: RunId): IO[AgentError, Unit] =
    for
      state <- store.load(runId)
      terminal = Set(
        RunStatus.Completed,
        RunStatus.Failed,
        RunStatus.Cancelled,
        RunStatus.TimedOut,
        RunStatus.BudgetExceeded
      )
      _ <- ZIO.unless(terminal.contains(state.status)) {
        for
          _     <- store.requestCancellation(runId)
          fiber <- activeRuns.modify(current => (current.get(runId), current - runId))
          _     <- ZIO.foreachDiscard(fiber)(_.interrupt)
          _     <- markCancelled(runId)
        yield ()
      }
    yield ()

  /** 单 Agent 主循环：预算/取消/Run Guardrail→上下文→能力校验→模型→状态事务→工具或完成。
    * @param state
    *   当前版本的唯一状态事实，递归前必须由 `save` 返回新版本
    */
  private def loop(state: AgentState): IO[AgentError, RunOutcome] =
    for
      _            <- ensureBudget(state)
      runDecisions <- guardrails.checkRun(state, guardrailContext(state))
      _            <- emitGuardrails(state.runId, "run", runDecisions)
      cancelled    <- store.cancellationRequested(state.runId)
      _            <- ZIO.fail(AgentError.Cancelled(state.runId)).when(cancelled)
      agent        <- definition(state)
      sources      <- contextSources.resolve(state, agent)
      prepared     <- contextManager.build(state, agent, sources, agent.contextPolicy)
      contextState <- persistPreparedContext(state, prepared)
      // Context 压缩已经计入模型调用预算；主模型开始前再次检查，避免辅助调用用掉最后额度后继续越界。
      _         <- ensureBudget(contextState)
      contextAt <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _         <- emit(
        AgentEvent.ContextPrepared(
          contextState.runId,
          prepared.usage.estimatedTokens,
          prepared.usage.droppedMessages,
          prepared.usage.truncatedToolResults,
          prepared.usage.droppedMemories,
          prepared.usage.droppedRetrieval,
          prepared.debug.rotSignals.map(_.code).distinct.sorted,
          contextAt
        )
      )
      allowed = agent.allowedTools.map(ToolName(_))
      definitions <- registry.definitions(allowed)
      // 部署级模型覆盖在这里叠加到 Agent 自己的设置上，之后的能力校验、事件、步骤记录与计费全部使用
      // 叠加后的结果。在更靠后的位置应用会让 CapabilityValidator 校验一个并非实际发送的模型。
      settings = modelPolicies.current().applyTo(agent.modelSettings)
      request  = ChatRequest(prepared.messages, definitions, settings)
      capabilities <- model.capabilities(settings.model)
      _            <- CapabilityValidator.validate(request, capabilities)
      startedAt    <- Clock.currentTime(TimeUnit.MILLISECONDS)
      resolvedProvider = settings.provider.getOrElse(model.provider)
      resolvedModel    = settings.model.getOrElse("default")
      _ <- emit(AgentEvent.StepStarted(contextState.runId, contextState.budget.steps + 1, startedAt))
      _ <- emit(
        AgentEvent.ModelCallStarted(
          contextState.runId,
          resolvedProvider,
          resolvedModel,
          startedAt
        )
      )
      response <- invokeModel(contextState, request)
      _        <- ZIO
        .fail(AgentError.BudgetExceeded("toolCallsPerStep", toolPolicies.current().maxCallsPerStep.toLong))
        .when(response.message.toolCalls.length > toolPolicies.current().maxCallsPerStep)
      now <- Clock.instant
      step = AgentStep.ModelStep(
        contextState.steps.length + 1,
        model.provider,
        resolvedModel,
        response.usage,
        response.finishReason,
        now.toEpochMilli
      )
      // 用实际路由到的 provider/model 查价，而不是 ChatModel.provider——后者在多 Provider 部署里是 "router"，
      // 拿它查价会永远查不到条目，让成本看板静默停留在零。
      usage = contextState.usage.addModel(
        response.usage,
        modelPolicies.prices.estimate(resolvedProvider, resolvedModel, response.usage)
      )
      _ <- ensureUsageBudget(contextState.budget.limits, usage)
      _ <- ZIO
        .fail(AgentError.BudgetExceeded("toolCalls", contextState.budget.limits.maxToolCalls))
        .when(usage.toolCalls + response.message.toolCalls.length > contextState.budget.limits.maxToolCalls)
      pending <- createDurableToolPlan(contextState, response.message.toolCalls)
      updated0 = contextState.copy(
        messages = contextState.messages :+ response.message,
        steps = contextState.steps :+ step,
        usage = usage,
        budget = contextState.budget.copy(consumed = usage, steps = contextState.budget.steps + 1),
        updatedAt = now,
        pendingToolPlan = pending
      )
      events = NonEmptyChunk(
        AgentEvent.ModelCallCompleted(contextState.runId, response.usage, now.toEpochMilli),
        pending.toList.map(plan =>
          AgentEvent.ToolBatchPlanned(
            contextState.runId,
            plan.id,
            plan.batches.length,
            response.message.toolCalls.length,
            now.toEpochMilli
          )
        )*
      )
      updated <- saveEvents(contextState, updated0, events)
      _       <- emit(AgentEvent.UsageUpdated(contextState.runId, usage, now.toEpochMilli))
      outcome <-
        if response.message.toolCalls.isEmpty then complete(updated, response.message)
        else processToolPlan(updated)
    yield outcome

  /** 在主模型调用前原子保存 Context 摘要边界和辅助模型用量。
    *
    * 如果 Worker 在压缩成功后、主模型请求前崩溃，恢复会从 `contextSummary` 的覆盖边界复用摘要，不会再次压缩相同前缀。 Usage 与摘要同事务提交，确保辅助模型调用不会逃逸 Run
    * token/model-call 预算。没有摘要更新且没有模型调用时保持零写入。
    *
    * @param state
    *   构建 Context 时读取的权威状态
    * @param prepared
    *   ContextManager 返回的消息、用量和可选 checkpoint
    * @return
    *   可能递增了 version/lastEventSequence/usage 的新权威状态
    */
  private def persistPreparedContext(
      state: AgentState,
      prepared: PreparedContext
  ): IO[AgentError, AgentState] =
    val calls = prepared.usage.compressionModelCalls
    if prepared.summaryUpdate.isEmpty && calls == 0 then ZIO.succeed(state)
    else
      for
        usage <- ZIO
          .attempt(state.usage.addModels(prepared.compressionUsage, calls))
          .mapError(error => AgentError.Unexpected("累计 Context 压缩用量失败", Some(error)))
        _   <- ensureUsageBudget(state.budget.limits, usage)
        now <- Clock.instant
        checkpoint = prepared.summaryUpdate.orElse(state.contextSummary)
        covered    = checkpoint.fold(0)(_.coveredMessages)
        version    = checkpoint.fold("tool-output-v1")(_.compressorVersion)
        next       = state.copy(
          usage = usage,
          budget = state.budget.copy(consumed = usage),
          contextSummary = checkpoint,
          updatedAt = now
        )
        compacted = AgentEvent.ContextCompacted(
          state.runId,
          covered,
          calls,
          prepared.compressionUsage,
          version,
          now.toEpochMilli
        )
        events =
          if calls > 0 then
            NonEmptyChunk(compacted, AgentEvent.UsageUpdated(state.runId, usage, now.toEpochMilli))
          else NonEmptyChunk(compacted)
        saved <- saveEvents(state, next, events)
      yield saved

  /** 从耐久计划的当前批次开始递归推进，直到计划清空并返回模型循环。
    *
    * @param state
    *   已保存模型响应、完整计划和 nextBatchIndex 的状态
    * @param approvedCallIds
    *   本次 resume 明确批准重放的 callId；后续普通批次不会继承该集合
    * @return
    *   遇到审批时返回 Suspended；全部工具与后续模型调用完成时返回 Completed
    */
  private def processToolPlan(
      state: AgentState,
      approvedCallIds: Set[String] = Set.empty
  ): IO[AgentError, RunOutcome] =
    state.pendingToolPlan.flatMap(_.currentBatch) match
      case None        => loop(state.copy(pendingToolPlan = None))
      case Some(batch) =>
        executeDurableBatch(state, batch, approvedCallIds).flatMap {
          case Left(outcome) => ZIO.succeed(outcome)
          case Right(next)   => processToolPlan(next)
        }

  /** 把 Provider 一次返回的调用转换成可持久化的确定性批次计划。
    *
    * Runtime 在此采用“安全能力门禁”：只有工具显式声明冲突组、允许自动重试、不需要审批且当前调用者 已具备全部 scope 时，规划器才看见它的 ConflictAware
    * 元数据。任何未知、漏配或高风险工具都会被降级为 SequentialOnly，因此即使模型一次输出多个调用，也不会把不确定副作用静默并行化。
    *
    * @param state
    *   提供可信 scope 与审批策略上下文
    * @param calls
    *   Provider 给出的原始顺序调用
    * @return
    *   空调用返回 None；否则返回带随机 planId 和连续批次的耐久计划
    */
  private def createDurableToolPlan(
      state: AgentState,
      calls: Chunk[ToolCall]
  ): IO[AgentError, Option[DurableToolPlan]] =
    if calls.isEmpty then ZIO.none
    else
      for
        invocations <- ZIO.foreach(calls.zipWithIndex) { case (call, ordinal) =>
          registry.get(ToolName(call.name)).either.map {
            case Right(tool) =>
              val canRunInParallel =
                tool.metadata.conflictAwareParallel &&
                  tool.metadata.automaticallyRetryable &&
                  approvalReason(tool.metadata).isEmpty &&
                  tool.metadata.requiredScopes.subsetOf(state.runContext.scopes)
              val metadata =
                if canRunInParallel then tool.metadata
                else
                  tool.metadata.copy(
                    parallelism = ToolParallelism.SequentialOnly,
                    conflictAccesses = Set.empty
                  )
              PlannedToolInvocation(ordinal, call, planningView(tool, metadata))
            case Left(_) =>
              PlannedToolInvocation(ordinal, call, unknownPlanningTool(call))
          }
        }
        planned <- ZIO.fromEither(ToolBatchPlanner.plan(invocations))
        planId  <- Random.nextUUID.map(_.toString)
        batches = planned.batches.zipWithIndex.map { case (batch, index) =>
          DurableToolBatch(
            index,
            batch.invocations.toChunk.map(invocation =>
              DurableToolPlanItem(invocation.ordinal, invocation.call)
            )
          )
        }
      yield Some(DurableToolPlan(planId, batches))

  /** 执行一个耐久 super-step：先完成注册、权限、审批和 Guardrail 门禁，再一次性写入整批 Prepared pending writes， 最后并行执行并按 ordinal
    * 原子提交全部结果。若进程在若干工具成功后崩溃，成功结果只存在于工具账本，恢复时会 复用它们并补齐剩余调用；AgentState 在整批齐备前不会看到半批消息。
    *
    * @param state
    *   当前批次尚未提交的状态
    * @param batch
    *   当前耐久批次
    * @param approvedCallIds
    *   本次人工操作明确允许重放的调用；只影响当前 resume，不会扩大其他调用权限
    * @return
    *   Left 表示等待人工审批；Right 表示批次已原子提交并可继续下一批
    */
  private def executeDurableBatch(
      state: AgentState,
      batch: DurableToolBatch,
      approvedCallIds: Set[String]
  ): IO[AgentError, Either[RunOutcome, AgentState]] =
    for
      plan <- ZIO
        .fromOption(state.pendingToolPlan)
        .orElseFail(AgentError.PersistenceFailure("执行工具批次时缺少 DurableToolPlan"))
      _ <- ZIO
        .fail(AgentError.PersistenceFailure("执行批次不是当前恢复游标"))
        .unless(plan.currentBatch.contains(batch))
      at <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _  <- emit(AgentEvent.ToolBatchStarted(state.runId, plan.id, batch.index, batch.items.length, at))
      _  <- ZIO.foreachDiscard(batch.items)(item =>
        emit(AgentEvent.ToolCallRequested(state.runId, item.call, at))
      )
      resolved <- ZIO.foreach(batch.items)(item =>
        registry.get(ToolName(item.call.name)).either.map(item -> _)
      )
      gate   <- firstBatchGate(state, batch, resolved, approvedCallIds)
      result <- gate match
        case Some(Left(outcome))     => ZIO.succeed(Left(outcome))
        case Some(Right(toolResult)) =>
          appendToolBatchResults(state, batch, Chunk(batch.items.head -> toolResult)).map(Right(_))
        case None =>
          val tools = resolved.map { case (item, value) => item -> value.toOption.get }
          for
            before <- ZIO.foreach(tools) { case (item, _) =>
              guardrails
                .checkTool(item.call, None, guardrailContext(state))
                .tap(
                  emitGuardrails(state.runId, "tool.before", _)
                )
            }
            _ = before
            now <- Clock.currentTime(TimeUnit.MILLISECONDS)
            batchId  = batch.executionBatchId(plan.id)
            prepared = NonEmptyChunk
              .fromChunk(tools.map { case (item, _) =>
                ToolExecutionRecord(
                  state.runId,
                  batchId,
                  item.ordinal,
                  item.call.id,
                  item.call.name,
                  Some(s"${state.runId.asString}:${item.call.id}"),
                  ToolExecutionStatus.Prepared,
                  None,
                  0,
                  now
                )
              })
              .get
            _ <- store.prepareToolExecutions(prepared)
            invocations = tools.map { case (item, tool) =>
              PlannedToolInvocation(item.ordinal, item.call, tool)
            }
            executionPlan = ToolExecutionPlan(
              Chunk(ToolExecutionBatch(NonEmptyChunk.fromChunk(invocations).get)),
              invocations.length
            )
            report <- ToolBatchExecutor.execute(executionPlan, toolPolicies.current().maxParallelism) {
              invocation =>
                val item = DurableToolPlanItem(invocation.ordinal, invocation.call)
                executeToolResult(
                  state,
                  item,
                  invocation.tool,
                  forceRetry = approvedCallIds.contains(invocation.call.id)
                )
            }
            ordered <- ZIO.foreach(report.outcomes) { outcome =>
              ZIO
                .fromEither(outcome.result)
                .map(result => DurableToolPlanItem(outcome.ordinal, outcome.call) -> result)
            }
            _ <- ZIO.foreachDiscard(ordered) { case (item, toolResult) =>
              guardrails
                .checkTool(item.call, Some(toolResult), guardrailContext(state))
                .flatMap(
                  emitGuardrails(state.runId, "tool.after", _)
                )
            }
            next <- appendToolBatchResults(state, batch, ordered)
          yield Right(next)
    yield result

  /** 检查整个批次在副作用发生前必须满足的动态门禁。
    *
    * @return
    *   None 表示整批可执行；Some(Left) 表示暂停审批；Some(Right) 表示单调用应以结构化错误提交。
    */
  private def firstBatchGate(
      state: AgentState,
      batch: DurableToolBatch,
      resolved: Chunk[(DurableToolPlanItem, Either[AgentError.ToolNotFound, RegisteredTool])],
      approvedCallIds: Set[String]
  ): IO[AgentError, Option[Either[RunOutcome, ToolResult]]] =
    val missing = resolved.collectFirst { case (item, Left(error)) => item -> error }
    missing match
      case Some((item, error)) =>
        ensureSingletonBatch(state.runId, batch, "未知工具") *>
          ZIO.succeed(Some(Right(errorToolResult(item.call.name, error.message))))
      case None =>
        val tools = resolved.map { case (item, value) => item -> value.toOption.get }
        tools.collectFirst {
          case (item, tool) if !tool.metadata.requiredScopes.subsetOf(state.runContext.scopes) =>
            val missingScopes = tool.metadata.requiredScopes -- state.runContext.scopes
            item -> errorToolResult(
              item.call.name,
              s"缺少工具权限 scope: ${missingScopes.toList.sorted.mkString(",")}"
            )
        } match
          case Some((_, result)) =>
            ensureSingletonBatch(state.runId, batch, "权限不足工具") *> ZIO.succeed(Some(Right(result)))
          case None =>
            tools.collectFirst {
              case (item, tool)
                  if approvalReason(tool.metadata).nonEmpty &&
                    !approvedCallIds.contains(item.call.id) &&
                    !wasApproved(state, item.call.id) =>
                (item, tool, approvalReason(tool.metadata).get)
            } match
              case Some((item, tool, reason)) =>
                ensureSingletonBatch(state.runId, batch, "需要审批的工具") *>
                  suspend(state, item.call, tool.metadata.risk, reason).map(outcome => Some(Left(outcome)))
              case None => ZIO.none

  /** 单调用错误批次是安全降级；若元数据漂移让并行批次出现动态门禁，则拒绝半批提交。 */
  private def ensureSingletonBatch(
      runId: RunId,
      batch: DurableToolBatch,
      gate: String
  ): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidResume(runId, s"$gate 出现在多调用批次，工具元数据可能已漂移"))
      .unless(batch.items.length == 1)
      .unit

  /** 查询状态历史中是否已经持久化过该调用的批准决定；恢复不能把重启误认为批准。 */
  private def wasApproved(state: AgentState, callId: String): Boolean =
    state.steps.exists {
      case AgentStep.ApprovalStep(_, request, Some(ApprovalDecision.Approve), _) =>
        request.toolCall.id == callId
      case _ => false
    }

  /** 创建仅用于规划的元数据视图；真正执行仍委托给原注册工具。 */
  private def planningView(tool: RegisteredTool, effectiveMetadata: ToolMetadata): RegisteredTool =
    new RegisteredTool:
      val definition = tool.definition
      val metadata   = effectiveMetadata
      def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
        tool.invoke(arguments, context)

  /** 未知工具在规划阶段必须表现为 SequentialOnly，实际门禁会生成 ToolNotFound 结果。 */
  private def unknownPlanningTool(call: ToolCall): RegisteredTool =
    new RegisteredTool:
      val definition = ToolDefinition(call.name, "未注册工具", Json.Obj(), None)
      val metadata   = ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
      def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
        ZIO.fail(AgentError.ToolNotFound(call.name))

  /** 将可公开的工具门禁失败转换成模型可消费的稳定 JSON 结果。 */
  private def errorToolResult(toolName: String, message: String): ToolResult =
    ToolResult(Json.Obj("tool" -> Json.Str(toolName), "error" -> Json.Str(message)), isError = true)

  /** 根据执行账本选择“复用成功结果、拒绝不安全重放或真正执行”。
    * @param state
    *   当前耐久状态
    * @param call
    *   要处理的模型工具调用
    * @param forceRetry
    *   仅在人工明确批准不确定副作用重放时为 true
    */
  private def executeToolResult(
      state: AgentState,
      item: DurableToolPlanItem,
      tool: RegisteredTool,
      forceRetry: Boolean
  ): IO[AgentError, ToolResult] =
    for
      existing <- store.getToolExecution(state.runId, item.call.id)
      result   <- existing match
        case Some(record) if record.status == ToolExecutionStatus.Succeeded =>
          ZIO
            .fromOption(record.result)
            .orElseFail(
              AgentError.PersistenceFailure(s"工具 ${item.call.name}/${item.call.id} 标记为成功但缺少结果")
            )
        case Some(record)
            if Set(ToolExecutionStatus.Running, ToolExecutionStatus.Unknown).contains(record.status) &&
              !tool.metadata.automaticallyRetryable && !forceRetry =>
          ZIO.fail(
            AgentError.InvalidResume(state.runId, s"工具 ${item.call.name}/${item.call.id} 执行结果不确定，需要人工确认")
          )
        case Some(record) => runTool(state, item.call, tool, record)
        case None => ZIO.fail(AgentError.PersistenceFailure(s"工具 ${item.call.id} 缺少 Prepared pending write"))
    yield result

  /** 执行账本的两阶段边界：先 Prepared，再 Running，最终写 Succeeded/Failed。
    * @param state
    *   提供可信 run/thread/context
    * @param call
    *   已通过注册、scope、Guardrail 和审批的调用
    * @param tool
    *   与调用名称匹配的注册工具
    */
  private def runTool(
      state: AgentState,
      call: ToolCall,
      tool: RegisteredTool,
      existing: ToolExecutionRecord
  ): IO[AgentError, ToolResult] =
    for
      now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      running = existing.copy(
        status = ToolExecutionStatus.Running,
        attempt = existing.attempt + 1,
        updatedAtEpochMilli = now
      )
      active   <- store.transitionToolExecution(existing.status, existing.attempt, running)
      _        <- emit(AgentEvent.ToolExecutionStarted(state.runId, call.id, now))
      executor <- ToolExecutor.make(toolPolicies.current().copy(allowedTools = Set(ToolName(call.name))))
      result   <- executor
        .execute(tool, call, executionContext(state, call))
        .foldZIO(
          error =>
            for
              at <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
              failureStatus =
                if tool.metadata.automaticallyRetryable then ToolExecutionStatus.Failed
                else ToolExecutionStatus.Unknown
              _ <- store.transitionToolExecution(
                ToolExecutionStatus.Running,
                active.attempt,
                active.copy(status = failureStatus, updatedAtEpochMilli = at)
              )
              _ <- emit(AgentEvent.ToolExecutionFailed(state.runId, call.id, error.category.toString, at))
              message = if error.safeToExpose then error.message else s"工具 ${call.name} 执行失败"
            yield ToolResult(Json.Obj("error" -> Json.Str(message)), isError = true),
          value =>
            Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { at =>
              store
                .transitionToolExecution(
                  ToolExecutionStatus.Running,
                  active.attempt,
                  active.copy(
                    status = ToolExecutionStatus.Succeeded,
                    result = Some(value),
                    updatedAtEpochMilli = at
                  )
                )
                .as(value)
            }
        )
    yield result

  /** 将工具结果、步骤、用量和下一个恢复游标作为一个状态转换提交。
    * @param state
    *   当前批次提交前的状态版本
    * @param batch
    *   必须与 DurableToolPlan.currentBatch 完全一致
    * @param results
    *   可以按任意 Fiber 完成顺序传入，提交前会按 ordinal 排序
    */
  private def appendToolBatchResults(
      state: AgentState,
      batch: DurableToolBatch,
      results: Chunk[(DurableToolPlanItem, ToolResult)]
  ): IO[AgentError, AgentState] =
    for
      now  <- Clock.instant
      plan <- ZIO
        .fromOption(state.pendingToolPlan)
        .orElseFail(AgentError.PersistenceFailure("提交工具批次时缺少 DurableToolPlan"))
      _ <- ZIO
        .fail(AgentError.PersistenceFailure("提交的工具批次与当前恢复游标不一致"))
        .unless(plan.currentBatch.contains(batch))
      ordered = results.sortBy(_._1.ordinal)
      _ <- ZIO
        .fail(AgentError.PersistenceFailure("工具批次结果数量或 ordinal 不完整"))
        .unless(ordered.map(_._1.ordinal) == batch.items.sortBy(_.ordinal).map(_.ordinal))
      usage = state.usage.copy(toolCalls = state.usage.toolCalls + ordered.length)
      steps = ordered.zipWithIndex.map { case ((item, result), offset) =>
        AgentStep.ToolStep(state.steps.length + offset + 1, item.call, result, now.toEpochMilli)
      }
      messages = ordered.map { case (item, result) =>
        AgentMessage.tool(item.call.id, item.call.name, result)
      }
      failureCount = ordered.foldLeft(state.consecutiveToolFailures) { case (count, (_, result)) =>
        if result.isError then count + 1 else 0
      }
      advanced = plan.advance
      nextPlan = Option.when(advanced.currentBatch.nonEmpty)(advanced)
      events   = NonEmptyChunk
        .fromChunk(
          ordered.map { case (item, result) =>
            AgentEvent.ToolExecutionCompleted(state.runId, item.call.id, result, now.toEpochMilli)
          } :+ AgentEvent
            .ToolBatchCommitted(state.runId, plan.id, batch.index, ordered.length, now.toEpochMilli)
        )
        .get
      next <- saveEvents(
        state,
        state.copy(
          messages = state.messages ++ messages,
          steps = state.steps ++ steps,
          usage = usage,
          budget = state.budget.copy(consumed = usage),
          pendingApproval = None,
          pendingToolPlan = nextPlan,
          consecutiveToolFailures = failureCount,
          updatedAt = now
        ),
        events
      )
    yield next

  /** 在任何受控副作用之前保存 ApprovalRequest 和完整工具游标。
    * @param risk
    *   工具声明的真实风险，不使用固定占位风险
    * @param reason
    *   给审批人的可理解原因
    */
  private def suspend(
      state: AgentState,
      call: ToolCall,
      risk: ToolRisk,
      reason: String
  ): IO[AgentError, RunOutcome] =
    for
      now <- Clock.instant
      approval = ApprovalRequest(
        s"approval-${state.runId.asString}-${call.id}",
        state.runId,
        call,
        risk,
        reason,
        now.toEpochMilli
      )
      suspended <- save(
        state,
        state.copy(
          status = RunStatus.WaitingForApproval,
          pendingApproval = Some(approval),
          updatedAt = now
        ),
        AgentEvent.ToolApprovalRequired(state.runId, approval, now.toEpochMilli)
      )
      thread <- threadId(suspended)
    yield RunOutcome.Suspended(
      state.runId,
      thread,
      approval,
      TokenUsage(suspended.usage.inputTokens, suspended.usage.outputTokens),
      suspended.budget.steps
    )

  /** 在输出 Guardrail 通过后提交 Completed，并构造稳定公开结果。 */
  private def complete(state: AgentState, answer: AgentMessage): IO[AgentError, RunOutcome] =
    for
      decisions <- guardrails.checkOutput(answer, guardrailContext(state))
      _         <- emitGuardrails(state.runId, "output", decisions)
      now       <- Clock.instant
      saved     <- save(
        state,
        state.copy(status = RunStatus.Completed, updatedAt = now),
        AgentEvent.RunCompleted(state.runId, answer, state.usage, now.toEpochMilli)
      )
      thread <- threadId(saved)
    yield RunOutcome.Completed(
      saved.runId,
      thread,
      answer,
      TokenUsage(saved.usage.inputTokens, saved.usage.outputTokens),
      saved.budget.steps
    )

  /** 原子提交下一状态与一个精选领域事件。
    * @param current
    *   提供 expected version 与上一事件序号
    * @param next
    *   尚未推进版本/事件序号的下一状态
    * @param event
    *   与该状态转换不可分割的领域事件
    */
  private def save(current: AgentState, next: AgentState, event: AgentEvent): IO[AgentError, AgentState] =
    saveEvents(current, next, NonEmptyChunk(event))

  /** 在一个乐观锁事务中提交状态与多条连续事件。
    *
    * 批次完成时，每个 ToolExecutionCompleted 与 ToolBatchCommitted 必须和 AgentState 的消息、步骤及游标推进 同生共死；逐条 save
    * 会暴露半批状态，因此这里一次分配连续 sequence 并调用单次 RunStore.commit。
    *
    * @param current
    *   提供 expectedVersion 与上一条事件序号
    * @param next
    *   尚未更新 version/lastEventSequence 的目标状态
    * @param events
    *   至少一条、严格按业务发生顺序排列的领域事件
    */
  private def saveEvents(
      current: AgentState,
      next: AgentState,
      events: NonEmptyChunk[AgentEvent]
  ): IO[AgentError, AgentState] =
    for
      eventIds <- ZIO.foreach(events)(_ => EventId.random)
      now      <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      stamped   = events.map(stampEvent(_, now))
      persisted = NonEmptyChunk
        .fromChunk(stamped.toChunk.zip(eventIds.toChunk).zipWithIndex.map { case ((event, eventId), index) =>
          val sequence = current.lastEventSequence + index.toLong + 1L
          PersistedAgentEvent(eventId, current.runId, sequence, event, now)
        })
        .get
      lastSequence = persisted.last.sequence
      durable      = next.copy(lastEventSequence = lastSequence)
      lease   <- executionLease.get
      version <- lease match
        case Some(value) => store.commitFenced(value, current.version, durable, persisted)
        case None        => store.commit(current.version, durable, persisted)
      saved = durable.copy(version = version)
      _ <- ZIO.foreachDiscard(stamped)(emit)
      _ <- emit(AgentEvent.CheckpointSaved(current.runId, version, now))
    yield saved

  /** 恢复等待审批状态；普通重启绝不等价于自动批准。 */
  private def recoverPending(state: AgentState): IO[AgentError, RunOutcome] =
    state.pendingToolPlan.flatMap(_.currentBatch) match
      case None        => ZIO.fail(AgentError.InvalidResume(state.runId, "暂停状态缺少工具恢复游标"))
      case Some(batch) =>
        for
          item <- ZIO
            .fromOption(batch.items.headOption)
            .orElseFail(AgentError.InvalidResume(state.runId, "暂停工具批次为空"))
          _ <- ZIO.fail(AgentError.InvalidResume(state.runId, "审批恢复批次必须为单调用")).unless(batch.items.length == 1)
          tool    <- registry.get(ToolName(item.call.name))
          record  <- store.getToolExecution(state.runId, item.call.id)
          outcome <- record match
            case None => suspendedOutcome(state)
            case Some(value)
                if Set(ToolExecutionStatus.Prepared, ToolExecutionStatus.Failed).contains(value.status) =>
              suspendedOutcome(state)
            case Some(value)
                if value.status == ToolExecutionStatus.Running && !tool.metadata.automaticallyRetryable =>
              for
                now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                _   <- store.transitionToolExecution(
                  ToolExecutionStatus.Running,
                  value.attempt,
                  value.copy(status = ToolExecutionStatus.Unknown, updatedAtEpochMilli = now)
                )
                out <- suspend(
                  state.copy(status = RunStatus.Running),
                  item.call,
                  tool.metadata.risk,
                  "进程中断时非幂等工具处于 Running，外部副作用结果未知；确认后才允许重放"
                )
              yield out
            case Some(value)
                if value.status == ToolExecutionStatus.Unknown && !tool.metadata.automaticallyRetryable =>
              suspend(
                state.copy(status = RunStatus.Running),
                item.call,
                tool.metadata.risk,
                "非幂等工具执行结果未知；请先核对外部系统，再决定是否重放"
              )
            case _ => recoverToolPlan(state.copy(status = RunStatus.Running))
        yield outcome

  /** 恢复已经持久化批准或执行中的工具边界。
    * @param pending
    *   当前调用和剩余调用的确定性游标
    */
  private def recoverToolPlan(state: AgentState): IO[AgentError, RunOutcome] =
    state.pendingToolPlan.flatMap(_.currentBatch) match
      case None =>
        loop(state.copy(status = RunStatus.Running, pendingApproval = None, pendingToolPlan = None))
      case Some(batch) if batch.items.length == 1 =>
        val item = batch.items.head
        registry.get(ToolName(item.call.name)).either.flatMap {
          case Left(_)     => processToolPlan(state.copy(status = RunStatus.Running, pendingApproval = None))
          case Right(tool) =>
            store.getToolExecution(state.runId, item.call.id).flatMap {
              case Some(record)
                  if record.status == ToolExecutionStatus.Running && !tool.metadata.automaticallyRetryable =>
                for
                  now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                  _   <- store.transitionToolExecution(
                    ToolExecutionStatus.Running,
                    record.attempt,
                    record.copy(status = ToolExecutionStatus.Unknown, updatedAtEpochMilli = now)
                  )
                  out <- suspend(
                    state.copy(status = RunStatus.Running, pendingApproval = None),
                    item.call,
                    tool.metadata.risk,
                    "进程中断时非幂等工具处于 Running，外部副作用结果未知；核对外部系统后才能决定是否重放"
                  )
                yield out
              case Some(record)
                  if record.status == ToolExecutionStatus.Unknown && !tool.metadata.automaticallyRetryable =>
                suspend(
                  state.copy(status = RunStatus.Running, pendingApproval = None),
                  item.call,
                  tool.metadata.risk,
                  "非幂等工具执行结果未知；请先核对外部系统，再决定是否重放"
                )
              case _ => processToolPlan(state.copy(status = RunStatus.Running, pendingApproval = None))
            }
        }
      case Some(_) => processToolPlan(state.copy(status = RunStatus.Running, pendingApproval = None))

  /** 从已完成状态重建公开结果；缺少最终助手消息视为持久化损坏。 */
  private def completedOutcome(state: AgentState): IO[AgentError, RunOutcome] =
    for
      thread <- threadId(state)
      answer <- ZIO
        .fromOption(state.messages.reverse.find(_.role == MessageRole.Assistant))
        .orElseFail(AgentError.PersistenceFailure(s"已完成 Run ${state.runId.asString} 缺少最终助手消息"))
    yield RunOutcome.Completed(
      state.runId,
      thread,
      answer,
      TokenUsage(
        state.usage.inputTokens,
        state.usage.outputTokens,
        state.usage.cachedInputTokens,
        state.usage.reasoningOutputTokens
      ),
      state.budget.steps
    )

  /** 从等待状态重建审批响应，不推动工具执行。 */
  private def suspendedOutcome(state: AgentState): IO[AgentError, RunOutcome] =
    for
      thread   <- threadId(state)
      approval <- ZIO
        .fromOption(state.pendingApproval)
        .orElseFail(AgentError.InvalidResume(state.runId, "等待审批状态缺少 ApprovalRequest"))
    yield RunOutcome.Suspended(
      state.runId,
      thread,
      approval,
      TokenUsage(
        state.usage.inputTokens,
        state.usage.outputTokens,
        state.usage.cachedInputTokens,
        state.usage.reasoningOutputTokens
      ),
      state.budget.steps
    )

  /** 尽力把主失败写成终态与安全事件；记录失败不能覆盖调用方收到的原始 AgentError。
    * @param runId
    *   失败 Run
    * @param error
    *   决定 Failed/Cancelled/BudgetExceeded 的 typed error
    */
  private def markFailed(runId: RunId, error: AgentError): UIO[Unit] =
    (for
      current <- store.load(runId)
      now     <- Clock.instant
      status = error match
        case _: AgentError.Cancelled      => RunStatus.Cancelled
        case _: AgentError.BudgetExceeded => RunStatus.BudgetExceeded
        case _                            => RunStatus.Failed
      event = error match
        case _: AgentError.Cancelled => AgentEvent.RunCancelled(runId, now.toEpochMilli)
        case _                       =>
          AgentEvent.RunFailed(
            runId,
            error.category.toString,
            if error.safeToExpose then error.message else "运行失败",
            now.toEpochMilli
          )
      _ <- save(
        current,
        current.copy(status = status, updatedAt = now),
        event
      )
    yield ()).ignore

  /** 消费 Provider 的语义流并实时转发安全增量。
    *
    * `ReasoningDelta` 被明确丢弃，防止隐藏推理进入日志或 HTTP；最终必须收到 `Completed`，否则说明 Provider 适配器违反协议。ZStream 被中断时，取消会继续传播到
    * Provider 的 HTTP 请求。
    *
    * @param state
    *   当前 Run，用于给增量事件关联稳定 RunId
    * @param request
    *   已经过上下文构建和能力校验的模型请求
    */
  private def invokeModel(state: AgentState, request: ChatRequest): IO[AgentError, ChatResponse] =
    for
      completed <- Ref.make(Option.empty[ChatResponse])
      _         <- model
        .stream(request)
        .mapZIO {
          case ModelStreamEvent.ResponseStarted(_) => ZIO.unit
          case ModelStreamEvent.TextDelta(value)   =>
            Clock
              .currentTime(TimeUnit.MILLISECONDS)
              .flatMap(at => emit(AgentEvent.ModelTextDelta(state.runId, value, at)))
          case ModelStreamEvent.ReasoningDelta(_)                  => ZIO.unit
          case ModelStreamEvent.ToolCallStarted(_, _)              => ZIO.unit
          case ModelStreamEvent.ToolCallDelta(callId, _, fragment) =>
            Clock
              .currentTime(TimeUnit.MILLISECONDS)
              .flatMap(at => emit(AgentEvent.ModelToolCallDelta(state.runId, callId, fragment, at)))
          case ModelStreamEvent.ToolCallCompleted(_) => ZIO.unit
          case ModelStreamEvent.UsageUpdated(usage)  =>
            Clock
              .currentTime(TimeUnit.MILLISECONDS)
              .flatMap(at =>
                emit(
                  AgentEvent.UsageUpdated(
                    state.runId,
                    UsageSummary(
                      inputTokens = usage.inputTokens,
                      outputTokens = usage.outputTokens,
                      cachedInputTokens = usage.cachedInputTokens,
                      reasoningOutputTokens = usage.reasoningOutputTokens
                    ),
                    at
                  )
                )
              )
          case ModelStreamEvent.Completed(response) => completed.set(Some(response))
        }
        .runDrain
      response <- completed.get.someOrFail(AgentError.InvalidModelResponse("模型流结束但没有 Completed 事件"))
    yield response

  /** 把一个 Run effect 转换成带背压的 `AgentEvent` 流。
    *
    * @param runId
    *   活动 Fiber 表的键，也是取消 API 的定位键
    * @param effect
    *   新建、审批恢复或崩溃恢复 effect
    * @return
    *   绑定当前 Scope 的事件流；释放 Scope 会中断 Fiber、持久化取消并关闭队列
    *
    * 终止 `Take` 由 daemon offer 投递：正常消费者继续拉取时它最终进入队列；消费者已断开且队列已满时， Scope finalizer 会 shutdown 队列并唤醒该
    * offer，避免不可中断的 `onExit` 自身阻塞。
    */
  private def streamEffect(
      runId: RunId,
      effect: IO[AgentError, RunOutcome]
  ): ZIO[Scope, Nothing, ZStream[Any, AgentError, AgentEvent]] =
    for
      queue <- Queue.bounded[Take[AgentError, AgentEvent]](256)
      fiber <- eventQueue
        .locally(Some(queue))(
          effect
            .onInterrupt(markCancelled(runId))
            .onExit {
              case Exit.Success(_)     => queue.offer(Take.end).forkDaemon.unit
              case Exit.Failure(cause) => queue.offer(Take.failCause(cause)).forkDaemon.unit
            }
        )
        .forkScoped
      _ <- activeRuns.update(_.updated(runId, fiber))
      _ <- ZIO.addFinalizer(activeRuns.update(_ - runId) *> fiber.interrupt.unit *> queue.shutdown)
    yield ZStream.fromQueue(queue).flattenTake

  /** 发布进程内实时事件。观察者与 SSE 共用同一领域类型，但只有 `RunStore` 中的精选事件承担审计职责。
    * @param event
    *   已经过安全边界筛选、可以暴露给业务客户端的事件
    */
  private def emit(event: AgentEvent): UIO[Unit] =
    observer.emit(event) *> eventQueue.get.flatMap {
      case Some(queue) => queue.offer(Take.single(event)).unit
      case None        => ZIO.unit
    }

  /** 把 Guardrail 的每条命名判定转换成可观测事件。
    * @param runId
    *   当前运行
    * @param stage
    *   `input`、`run`、`tool.before`、`tool.after` 或 `output`
    * @param decisions
    *   GuardrailEngine 返回的规则名和判定
    */
  private def emitGuardrails(
      runId: RunId,
      stage: String,
      decisions: Chunk[(String, GuardrailDecision)]
  ): UIO[Unit] =
    ZIO.foreachDiscard(decisions) { case (name, decision) =>
      Clock
        .currentTime(TimeUnit.MILLISECONDS)
        .flatMap(at => emit(AgentEvent.GuardrailEvaluated(runId, s"$stage:$name", decision.allowed, at)))
    }

  /** 将 Fiber 中断或外部取消请求收敛为幂等耐久终态。 并发取消可能与完成提交竞争；乐观锁失败会被忽略，最终状态始终由数据库中先成功的终态决定。
    */
  private def markCancelled(runId: RunId): UIO[Unit] =
    (for
      state <- store.load(runId)
      terminal = Set(
        RunStatus.Completed,
        RunStatus.Failed,
        RunStatus.Cancelled,
        RunStatus.TimedOut,
        RunStatus.BudgetExceeded
      )
      _ <- ZIO.unless(terminal.contains(state.status)) {
        for
          now <- Clock.instant
          _   <- save(
            state,
            state.copy(status = RunStatus.Cancelled, updatedAt = now),
            AgentEvent.RunCancelled(runId, now.toEpochMilli)
          )
        yield ()
      }
    yield ()).ignore

  /** 为构造时尚未取得当前时间的事件补入真实时间戳。 */
  private def stampEvent(event: AgentEvent, at: Long): AgentEvent =
    event match
      case AgentEvent.RunCancelled(runId, _) => AgentEvent.RunCancelled(runId, at)
      case other                             => other

  /** 在发起下一次外部动作之前检查步骤、模型、工具、失败循环与 token 硬上限。 */
  private def ensureBudget(state: AgentState): IO[AgentError, Unit] =
    val limits = state.budget.limits
    ZIO
      .fail(AgentError.BudgetExceeded("steps", limits.maxSteps))
      .when(state.budget.steps >= limits.maxSteps)
      .unit *>
      ZIO
        .fail(AgentError.BudgetExceeded("modelCalls", limits.maxModelCalls))
        .when(state.usage.modelCalls >= limits.maxModelCalls)
        .unit *>
      ZIO
        .fail(AgentError.BudgetExceeded("toolCalls", limits.maxToolCalls))
        .when(state.usage.toolCalls >= limits.maxToolCalls)
        .unit *>
      ZIO
        .fail(AgentError.BudgetExceeded("repeatedActions", limits.maxRepeatedActions))
        .when(state.consecutiveToolFailures >= limits.maxRepeatedActions)
        .unit *>
      ensureUsageBudget(limits, state.usage, rejectAtLimit = true)

  /** 检查 Provider 已报告的累计用量，防止一次响应把输入、输出、总 token 或费用预算直接冲穿。
    * @param limits
    *   创建 Run 时冻结的硬限制
    * @param usage
    *   包含本次模型响应后的累计用量
    * @param rejectAtLimit
    *   在下一动作前检查时，达到上限即停止；刚收到响应时只有超过上限才判失败
    */
  private def ensureUsageBudget(
      limits: RunLimits,
      usage: UsageSummary,
      rejectAtLimit: Boolean = false
  ): IO[AgentError, Unit] =
    def exceeded[A](actual: A, limit: A)(using ordering: Ordering[A]): Boolean =
      if rejectAtLimit then ordering.gteq(actual, limit) else ordering.gt(actual, limit)

    ZIO
      .fail(AgentError.BudgetExceeded("inputTokens", limits.maxInputTokens))
      .when(exceeded(usage.inputTokens, limits.maxInputTokens))
      .unit *>
      ZIO
        .fail(AgentError.BudgetExceeded("outputTokens", limits.maxOutputTokens))
        .when(exceeded(usage.outputTokens, limits.maxOutputTokens))
        .unit *>
      ZIO
        .fail(AgentError.BudgetExceeded("tokens", limits.maxTotalTokens))
        .when(exceeded(usage.totalTokens, limits.maxTotalTokens))
        .unit *>
      ZIO.foreachDiscard(limits.maxEstimatedCost) { limit =>
        val limitMicros = (limit * BigDecimal(1_000_000)).setScale(0, BigDecimal.RoundingMode.CEILING).toLong
        ZIO
          .fail(AgentError.BudgetExceeded("estimatedCostMicros", limitMicros))
          .when(exceeded(usage.estimatedCost, limit))
          .unit
      }

  /** 根据集中审批策略与工具风险决定是否暂停。
    *
    * @param metadata
    *   工具作者声明的风险与副作用；模型不能修改
    * @return
    *   `None` 表示允许继续，`Some` 中的中文原因会进入 ApprovalRequest
    */
  private def approvalReason(metadata: ToolMetadata): Option[String] =
    toolPolicies.current().approvalPolicy match
      case ApprovalPolicy.Never     => None
      case ApprovalPolicy.Always    => Some("当前运行策略要求所有工具调用经过人工审批")
      case ApprovalPolicy.RiskBased =>
        metadata.risk match
          case ToolRisk.ReadOnly | ToolRisk.UserScopedRead => None
          case _                                           => Some("工具具有写入、破坏或管理副作用")

  /** 读取创建 Run 时冻结的 AgentDefinition；缺失说明状态版本损坏或迁移不完整。 */
  private def definition(state: AgentState): IO[AgentError, AgentDefinition] =
    ZIO
      .fromOption(state.definition)
      .orElseFail(AgentError.InvalidConfiguration(s"Run ${state.runId.asString} 缺少 Agent 定义快照"))

  /** 读取业务线程 ID；Durable Runtime 创建的状态必须始终包含该字段。 */
  private def threadId(state: AgentState): IO[AgentError, ThreadId] =
    ZIO
      .fromOption(state.threadId)
      .orElseFail(AgentError.InvalidConfiguration(s"Run ${state.runId.asString} 缺少 ThreadId"))

  /** 从可信状态构造工具上下文；模型无法修改其中的租户、用户和 scopes。 */
  private def executionContext(state: AgentState, call: ToolCall): ToolExecutionContext =
    ToolExecutionContext(
      state.runId,
      state.threadId.getOrElse(ThreadId(state.sessionId.asString)),
      call.id,
      state.runContext
    )

  /** 构造各阶段 Guardrail 共用的只读可信上下文。 */
  private def guardrailContext(state: AgentState): GuardrailContext =
    GuardrailContext(state.runId, state.runContext, state.agentId)

object AgentRuntimeLive:
  /** 以 scoped layer 创建运行时；FiberRef 隔离每个流的事件队列，活动 Fiber 表支持同进程精确取消。
    */
  val layer: URLayer[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & GuardrailEngine & ToolPolicySource &
      ModelPolicySource & RunObserver,
    AgentRuntime & LeaseAwareAgentRuntime
  ] = makeLayer(ContextSourceResolver.emptyValue)

  /** 生产知识 Agent 使用的装配入口：每个模型回合都会从显式 resolver 读取 Memory/RAG 来源。
    *
    * 默认 `layer` 仍适合纯工具 Agent；接入长期记忆或知识库时必须选择本层，避免“配置了 Retriever 但 Runtime 从未读取”的静默失效。
    */
  val layerWithContextSources: URLayer[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & ContextSourceResolver & GuardrailEngine &
      ToolPolicySource & ModelPolicySource & RunObserver,
    AgentRuntime & LeaseAwareAgentRuntime
  ] = ZLayer.scoped {
    for
      resolver <- ZIO.service[ContextSourceResolver]
      runtime  <- build(resolver)
    yield runtime
  }

  /** 用指定 ContextSourceResolver 构造纯工具 Agent 层，并集中管理 Runtime 的 scoped 资源。 */
  private def makeLayer(resolver: ContextSourceResolver): URLayer[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & GuardrailEngine & ToolPolicySource &
      ModelPolicySource & RunObserver,
    AgentRuntime & LeaseAwareAgentRuntime
  ] = ZLayer.scoped(build(resolver))

  /** 创建 FiberRef、活动 Fiber 注册表和租约上下文。 */
  private def build(resolver: ContextSourceResolver): ZIO[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & GuardrailEngine & ToolPolicySource &
      ModelPolicySource & RunObserver & Scope,
    Nothing,
    AgentRuntimeLive
  ] =
    for
      model          <- ZIO.service[ChatModel]
      registry       <- ZIO.service[RegisteredToolRegistry]
      store          <- ZIO.service[RunStore]
      contextManager <- ZIO.service[ContextManager]
      guardrails     <- ZIO.service[GuardrailEngine]
      toolPolicies   <- ZIO.service[ToolPolicySource]
      modelPolicies  <- ZIO.service[ModelPolicySource]
      observer       <- ZIO.service[RunObserver]
      eventQueue     <- FiberRef.make(Option.empty[Queue[Take[AgentError, AgentEvent]]])
      activeRuns     <- Ref.make(Map.empty[RunId, Fiber.Runtime[AgentError, RunOutcome]])
      executionLease <- FiberRef.make(Option.empty[RunCommandLease])
    yield new AgentRuntimeLive(
      model,
      registry,
      store,
      contextManager,
      resolver,
      guardrails,
      toolPolicies,
      modelPolicies,
      observer,
      eventQueue,
      activeRuns,
      executionLease
    )
