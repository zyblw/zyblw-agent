package com.zyblw.agent.runtime

import com.zyblw.agent.composition.RuntimeProfile
import com.zyblw.agent.context.ContextSourceResolver
import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.stream.*

/** 直接以 `AgentState`/`RunStore` 为事实来源的唯一生产运行时。
  *
  * 本类只做两件事：实现公开的 [[AgentRuntime]] 与 [[LeaseAwareAgentRuntime]] 契约，以及编排单 Agent 主循环。所有具体 职责都委托给按"变化原因"划分的协作者：
  *
  *   - [[RunCommitter]] 拥有全部 `AgentState` 耐久写入与 fencing 选择
  *   - [[ToolLedger]] 拥有工具执行账本与生命周期广播
  *   - [[CompositionGuard]] 拥有组合冻结与漂移门禁
  *   - [[ContextAssembler]] 拥有上下文装配与引用/摘要耐久化
  *   - [[ModelRouterGateway]] 与 [[ModelCallCoordinator]] 拥有模型路由与 Intent/Settlement
  *   - [[ToolAuthorizationGate]] 拥有副作用前的全部授权判定
  *   - [[RunTerminator]] 拥有完成、暂停、失败与取消
  *   - [[RunEventPublisher]] 拥有进程内实时事件与流生命周期
  *
  * 纯决策（状态迁移、预算、恢复分派、重放门禁）在 [[AgentKernel]]，本类不重复实现任何一条。
  *
  * 主循环 `loop → processToolPlan → executeDurableBatch → loop` 是相互递归的：一次模型回合可能产生工具批次，批次提交后
  * 又回到模型回合。它们共享同一个终止条件与预算游标，因此保持在一处。
  */
final class AgentRuntimeDriver(
    model: ChatModel,
    registry: RegisteredToolRegistry,
    store: RunStore,
    guardrails: GuardrailEngine,
    toolPolicies: ToolPolicySource,
    modelPolicies: ModelPolicySource,
    contextSources: ContextSourceResolver,
    committer: RunCommitter,
    toolLedger: ToolLedger,
    guard: CompositionGuard,
    contextAssembler: ContextAssembler,
    router: ModelRouterGateway,
    modelCalls: ModelCallCoordinator,
    authorization: ToolAuthorizationGate,
    terminator: RunTerminator,
    publisher: RunEventPublisher,
    toolExecutor: com.zyblw.agent.tools.ToolExecutor
) extends AgentRuntime,
      LeaseAwareAgentRuntime:
  import AgentRuntimeDriver.PlannedCall
  import ToolAuthorizationGate.{GateDecision, ResumeGrant}

  /** 创建初始状态、执行输入 Guardrail，并在总时限内推动状态机。 */
  def run(agent: AgentDefinition, request: RunRequest): IO[AgentError, RunOutcome] =
    guard.resolveRole(agent).flatMap(resolved => RunId.random.flatMap(runWithId(_, resolved, request)))

  /** 由同步入口与流式入口共享的创建流程；显式传入 RunId，确保首个事件就能向 SSE 客户端暴露稳定标识。 */
  private def runWithId(
      runId: RunId,
      agent: AgentDefinition,
      request: RunRequest
  ): IO[AgentError, RunOutcome] =
    for
      now <- RunClock.instant
      _   <- registry.requireRegistered(agent.allowedTools)
      initial = RunInitialization.initialState(
        runId,
        agent,
        request,
        toolPolicies.current().maxCallsPerRun,
        now,
        guard.freeze(agent)
      )
      // 首次写入与后续提交共用同一边界，因此创建也会发射 RunCreated 与 CheckpointSaved，没有绕过提交边界的路径。
      created <- committer.create(
        initial.copy(lastEventSequence = -1L),
        NonEmptyChunk(AgentEvent.RunCreated(runId, initial.sessionId, now.toEpochMilli))
      )
      outcome <- startCreated(created)
    yield outcome

  /** 把已经耐久保存的 Created 状态推进为 Running，并进入主循环。
    *
    * 同步本地入口和分布式 `Start` 命令共用该方法。分布式调用时租约已由 `executeLeased` 绑定，所以 [[RunCommitter]] 自动 走 `commitFenced`；HTTP 请求
    * Fiber 不会进入这里。
    */
  private def startCreated(initial: AgentState): IO[AgentError, RunOutcome] =
    for
      _ <- ZIO
        .fail(AgentError.InvalidResume(initial.runId, s"Start 只接受 Created，实际为 ${initial.status}"))
        .unless(initial.status == RunStatus.Created)
      _     <- guard.ensureCompatible(initial)
      input <- ZIO
        .fromOption(initial.messages.headOption)
        .orElseFail(AgentError.InvalidConfiguration(s"Run ${initial.runId.asString} 缺少首条输入消息"))
      outcome <- (for
        inputDecisions <- guardrails.checkInput(input, guardrailContext(initial))
        _              <- publisher.emitGuardrails(initial.runId, "input", inputDecisions)
        startedAt      <- RunClock.instant
        running        <- committer.commit(
          initial,
          initial.copy(status = RunStatus.Running, updatedAt = startedAt),
          AgentEvent.RunStarted(initial.runId, startedAt.toEpochMilli)
        )
        result <- loop(running)
      yield result)
        .timeoutFail(
          AgentError.BudgetExceeded("durationMillis", initial.budget.limits.maxDuration.toMillis)
        )(initial.budget.limits.maxDuration)
        .tapError(error => terminator.markFailed(initial.runId, error))
    yield outcome

  /** 为新 Run 创建有界事件队列；流消费者的 Scope 是执行 Fiber 的唯一父生命周期。 */
  def runEvents(agent: AgentDefinition, request: RunRequest): ZStream[Any, AgentError, AgentEvent] =
    ZStream.unwrapScoped {
      for
        runId    <- RunId.random
        resolved <- guard.resolveRole(agent)
        stream   <- publisher.stream(
          runId,
          runWithId(runId, resolved, request),
          terminator.markCancelled
        )
      yield stream
    }

  /** 将审批恢复放入与新 Run 相同的结构化事件流生命周期。 */
  def resumeEvents(runId: RunId, decision: ApprovalDecision): ZStream[Any, AgentError, AgentEvent] =
    ZStream.unwrapScoped(publisher.stream(runId, resume(runId, decision), terminator.markCancelled))

  /** 将崩溃恢复放入结构化事件流；关闭 HTTP/SSE 连接会中断恢复 Fiber。 */
  def recoverEvents(runId: RunId): ZStream[Any, AgentError, AgentEvent] =
    ZStream.unwrapScoped(publisher.stream(runId, recover(runId), terminator.markCancelled))

  /** 持久化人工决定后恢复工具游标。
    *
    * 批准会先保存 RunResumed 再允许副作用；拒绝会回填结构化工具错误。批准前会重新校验审批主体：人不能替一件他没看过的 事情背书。
    */
  def resume(runId: RunId, decision: ApprovalDecision): IO[AgentError, RunOutcome] =
    for
      state  <- store.load(runId)
      target <- ZIO.fromEither(AgentKernel.resumeTarget(state))
      next   <- decision match
        case ApprovalDecision.Approve                 => approveResume(state, target)
        case reject @ ApprovalDecision.Reject(reason) => rejectResume(state, target, reject, reason)
    yield next

  private def approveResume(
      state: AgentState,
      target: AgentKernel.ResumeTarget
  ): IO[AgentError, RunOutcome] =
    authorization.revalidate(state, target.approval, target.item.call).flatMap {
      // 主体已变化：带新主体重新请求审批，而不是直接失败——失败会让暂停停留在旧主体上，形成永远无法通过的死锁。
      case Left(revalidation) =>
        terminator.suspend(
          state,
          target.item.call,
          revalidation.risk,
          revalidation.reason,
          Some(revalidation.subject)
        )
      case Right(grant) =>
        for
          now <- RunClock.instant
          approvalStep = AgentStep.ApprovalStep(
            state.steps.length + 1,
            target.approval,
            Some(ApprovalDecision.Approve),
            now.toEpochMilli
          )
          approved <- committer.commit(
            state,
            state.copy(
              status = RunStatus.Running,
              suspension = None,
              steps = state.steps :+ approvalStep,
              updatedAt = now
            ),
            AgentEvent.RunResumed(state.runId, now.toEpochMilli)
          )
          next <- processToolPlan(approved, grant)
        yield next
    }

  private def rejectResume(
      state: AgentState,
      target: AgentKernel.ResumeTarget,
      decision: ApprovalDecision,
      reason: String
  ): IO[AgentError, RunOutcome] =
    val result       = ToolAuthorizationGate.errorToolResult(target.item.call.name, s"审批拒绝: $reason")
    val approvalStep = AgentStep.ApprovalStep(
      state.steps.length + 1,
      target.approval,
      Some(decision),
      target.approval.requestedAtEpochMilli
    )
    appendToolBatchResults(
      state.copy(
        status = RunStatus.Running,
        suspension = None,
        steps = state.steps :+ approvalStep
      ),
      target.batch,
      Chunk(target.item -> result)
    ).flatMap(next => processToolPlan(next))

  /** 依据状态与执行账本恢复，而不是重新从用户输入开始。 */
  def recover(runId: RunId): IO[AgentError, RunOutcome] =
    for
      state  <- store.load(runId)
      result <- AgentKernel.recoveryDecision(state.status) match
        case AgentKernel.RecoveryDecision.ReturnCompleted =>
          ZIO.fromEither(AgentKernel.completedOutcome(state))
        case AgentKernel.RecoveryDecision.ReturnCancelled         => ZIO.fail(AgentError.Cancelled(runId))
        case AgentKernel.RecoveryDecision.CloseUncertainModelCall => closeUncertainModelCall(state)
        case AgentKernel.RecoveryDecision.RecoverSuspended        =>
          guard.ensureCompatible(state) *> recoverPending(state)
        case AgentKernel.RecoveryDecision.StartCreated   => startCreated(state)
        case AgentKernel.RecoveryDecision.RecoverRunning =>
          guard.ensureCompatible(state) *> recoverRunning(state)
    yield result

  /** 执行 WorkerHost claim 的类型化耐久命令，并把 fencing 凭证绑定到当前 Fiber 及其所有子 Fiber。 */
  def executeLeased(lease: RunCommandLease): IO[AgentError, Unit] =
    committer.withLease(lease) {
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
    * Worker 可能在提交 RunStarted、完成模型循环或进入审批状态后崩溃，而命令还未 `complete`。重领 Start 时不能重新写 第二个 RunStarted：Created
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
    recover(runId).unit.catchSome {
      case _: AgentError.Cancelled          => ZIO.unit
      case _: AgentError.ModelCallUncertain => ZIO.unit
    }

  /** 以 approvalId 绑定决定，并处理"状态已提交、命令尚未 complete 时进程崩溃"的重放窗口。
    *
    * 如果同一审批步骤已经存在：Running/Created 表示先前命令在后续 loop 中崩溃，需要从账本继续恢复；终态或已经进入
    * 下一次审批则说明该决定已经完成，直接幂等返回。若历史决定与命令正文不同，必须拒绝而不能以后写覆盖前写。
    */
  private def resumeApprovalCommand(
      runId: RunId,
      approvalId: String,
      decision: ApprovalDecision
  ): IO[AgentError, Unit] =
    store.load(runId).flatMap { state =>
      AgentKernel.approvalCommandAction(state, approvalId, decision) match
        case AgentKernel.ApprovalCommandAction.Conflict(reason) =>
          ZIO.fail(AgentError.InvalidResume(runId, reason))
        case AgentKernel.ApprovalCommandAction.ResumeFromLedger => recoverCommand(runId)
        case AgentKernel.ApprovalCommandAction.AlreadyApplied   => ZIO.unit
        case AgentKernel.ApprovalCommandAction.Apply            => resume(runId, decision).unit
    }

  /** 按 RunId 读取状态快照；不修改版本、取消位或恢复游标。 */
  def inspect(runId: RunId): IO[AgentError, AgentState] = store.load(runId)

  /** 从 RunStore 的单调序号事件日志读取可断点续传的精选事件。 */
  def persistedEvents(
      runId: RunId,
      afterSequence: Long,
      limit: Int
  ): IO[AgentError, Chunk[PersistedAgentEvent]] =
    store.events(runId, afterSequence, limit)

  /** 对非终态 Run 写入可跨进程观察的取消位，并提交 Cancelled 状态事件。 */
  def cancel(runId: RunId): IO[AgentError, Unit] = terminator.cancel(runId)

  /** 单 Agent 主循环：预算/取消/Run Guardrail → 上下文 → 能力校验 → 模型 → 状态事务 → 工具或完成。
    *
    * @param state
    *   当前版本的唯一状态事实，递归前必须由提交边界返回新版本
    */
  private def loop(state: AgentState): IO[AgentError, RunOutcome] =
    for
      _            <- ensureBudget(state)
      runDecisions <- guardrails.checkRun(state, guardrailContext(state))
      _            <- publisher.emitGuardrails(state.runId, "run", runDecisions)
      cancelled    <- store.cancellationRequested(state.runId)
      _            <- ZIO.fail(AgentError.Cancelled(state.runId)).when(cancelled)
      agent = state.definition
      sources            <- contextSources.resolve(state, agent)
      retrievalDecisions <- guardrails.checkRetrieval(
        contextAssembler.untrustedSnippets(sources),
        guardrailContext(state)
      )
      _         <- publisher.emitGuardrails(state.runId, "retrieval", retrievalDecisions)
      cited     <- contextAssembler.persistCitations(state, sources)
      assembled <- contextAssembler.build(cited, agent, sources)
      (contextState, prepared) = assembled
      // Context 压缩已经计入模型调用预算；主模型开始前再次检查，避免辅助调用用掉最后额度后继续越界。
      _         <- ensureBudget(contextState)
      contextAt <- RunClock.millis
      _         <- publisher.emit(
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
      definitions <- registry.definitions(agent.allowedTools.map(ToolName(_)))
      // 每个调用捕获一次 live 工作点并与创建时组合比较；同一份值随后用于能力校验、账本和 dispatch，消除检查后再次读取造成的漂移窗口。
      prices = modelPolicies.prices
      settings <- guard.effectiveModelSettings(contextState, agent, prices)
      routing  <- router.route(
        contextState,
        ChatRequest(prepared.messages, definitions, settings),
        prepared.usage.estimatedTokens +
          (if definitions.nonEmpty then agent.contextPolicy.budget.tools else 0L),
        prices
      )
      startedAt <- RunClock.millis
      // 用实际路由到的 provider/model 查价，而不是 ChatModel.provider——后者在多 Provider 部署里是 "router"。
      resolvedProvider = routing.request.settings.provider.getOrElse(model.provider)
      resolvedModel    = routing.request.settings.model.getOrElse("default")
      _ <- publisher.emit(
        AgentEvent.StepStarted(contextState.runId, contextState.budget.steps + 1, startedAt)
      )
      dispatched <- modelCalls.invoke(
        contextState,
        agent,
        prepared,
        routing,
        resolvedProvider,
        resolvedModel,
        startedAt
      )
      response = dispatched.response
      _ <- ZIO
        .fail(AgentError.BudgetExceeded("toolCallsPerStep", toolPolicies.current().maxCallsPerStep.toLong))
        .when(response.message.toolCalls.length > toolPolicies.current().maxCallsPerStep)
      estimatedCost = prices.estimate(resolvedProvider, resolvedModel, response.usage)
      _ <- ZIO.fromEither(
        AgentKernel.validateToolCallBudget(
          dispatched.state.budget.limits,
          AgentKernel.modelTurnUsage(dispatched.state, response.usage, estimatedCost, routing.routed),
          response.message.toolCalls.length
        )
      )
      pending   <- createDurableToolPlan(dispatched.state, response.message.toolCalls)
      now       <- RunClock.instant
      modelTurn <- ZIO.fromEither(
        AgentKernel.settleModelTurn(
          dispatched.state,
          response,
          resolvedProvider,
          resolvedModel,
          estimatedCost,
          routing.routed,
          pending,
          dispatched.record,
          now
        )
      )
      updated <- committer.commitTransition(
        dispatched.state,
        modelTurn.transition,
        modelTurn.modelCallSettlement.map(record =>
          ModelCallWrite.Transition(ModelCallStatus.Dispatched, record.attempt, record)
        )
      )
      _       <- publisher.emit(AgentEvent.UsageUpdated(updated.runId, modelTurn.usage, now.toEpochMilli))
      _       <- ZIO.fromEither(AgentKernel.validateUsage(updated.budget.limits, modelTurn.usage))
      outcome <-
        if response.message.toolCalls.isEmpty then terminator.complete(updated, response.message)
        else processToolPlan(updated)
    yield outcome

  /** 从耐久计划的当前批次开始递归推进，直到计划清空并返回模型循环。
    *
    * @param grant
    *   本次 resume 的人工授权范围；后续普通批次不会继承它
    */
  private def processToolPlan(
      state: AgentState,
      grant: ResumeGrant = ResumeGrant.none
  ): IO[AgentError, RunOutcome] =
    state.pendingToolPlan.flatMap(_.currentBatch) match
      case None        => loop(state.copy(pendingToolPlan = None))
      case Some(batch) =>
        executeDurableBatch(state, batch, grant).flatMap {
          case Left(outcome) => ZIO.succeed(outcome)
          case Right(next)   => processToolPlan(next)
        }

  /** 把 Provider 一次返回的调用转换成可持久化的确定性批次计划。
    *
    * 整个规划过程只读一次生效策略：审批判定与冻结进主体的策略指纹必须来自同一份配置，否则管理面在两次读取之间替换配置 会让计划带着自相矛盾的审批事实落库。
    */
  private def createDurableToolPlan(
      state: AgentState,
      calls: Chunk[ToolCall]
  ): IO[AgentError, Option[DurableToolPlan]] =
    if calls.isEmpty then ZIO.none
    else
      val policy = toolPolicies.current()
      for
        resolved <- ZIO.foreach(calls.zipWithIndex) { case (call, ordinal) =>
          registry.get(ToolName(call.name)).either.map(plannedCall(state, policy, call, ordinal, _))
        }
        planned <- ZIO.fromEither(ToolBatchPlanner.plan(resolved.map(_.invocation)))
        planId  <- Random.nextUUID.map(_.toString)
      yield Some(
        DurableToolPlan(
          planId,
          planned.batches.zipWithIndex.map { case (batch, index) =>
            DurableToolBatch(
              index,
              batch.invocations.toChunk.map(invocation =>
                DurableToolPlanItem(invocation.ordinal, invocation.call)
              )
            )
          },
          toolContractFingerprints = resolved.map(e => e.invocation.call.name -> e.contract).toMap,
          approvalSubjects = resolved.flatMap(e => e.approvalSubject.map(e.invocation.call.id -> _)).toMap
        )
      )

  /** 规划单个调用：冻结契约指纹、审批主体，并按"安全能力门禁"决定是否允许并行。
    *
    * 只有工具显式声明冲突组、允许自动重试、不需要审批且当前调用者已具备全部 scope 时，规划器才看见它的 ConflictAware 元数据。任何未知、漏配或高风险工具都会被降级为
    * SequentialOnly，因此即使模型一次输出多个调用，也不会把不确定副作用 静默并行化。
    */
  private def plannedCall(
      state: AgentState,
      policy: ToolPolicyConfig,
      call: ToolCall,
      ordinal: Int,
      resolved: Either[AgentError.ToolNotFound, RegisteredTool]
  ): PlannedCall =
    resolved match
      case Right(tool) =>
        val metadata =
          AgentKernel.planningMetadata(tool.metadata, policy, state.runContext.scopes)
        val contract = com.zyblw.agent.composition.ToolContractFingerprint.registered(tool)
        PlannedCall(
          PlannedToolInvocation(ordinal, call, planningView(tool, metadata)),
          contract,
          Option.when(AgentKernel.approvalReason(policy, tool.metadata.risk).nonEmpty)(
            authorization.subjectOf(state, policy, call, tool.metadata, contract)
          )
        )
      case Left(_) =>
        val unknown  = unknownPlanningTool(call)
        val contract = com.zyblw.agent.composition.ToolContractFingerprint.missing(call.name)
        PlannedCall(
          PlannedToolInvocation(ordinal, call, unknown),
          contract,
          Option.when(AgentKernel.approvalReason(policy, unknown.metadata.risk).nonEmpty)(
            authorization.subjectOf(state, policy, call, unknown.metadata, contract)
          )
        )

  /** 执行一个耐久 super-step：先完成注册、权限、审批和 Guardrail 门禁，再一次性写入整批 Prepared pending write， 最后并行执行并按 ordinal 原子提交全部结果。
    *
    * 若进程在若干工具成功后崩溃，成功结果只存在于工具账本，恢复时会复用它们并补齐剩余调用；`AgentState` 在整批齐备前 不会看到半批消息。
    *
    * @return
    *   Left 表示等待人工审批；Right 表示批次已原子提交并可继续下一批
    */
  private def executeDurableBatch(
      state: AgentState,
      batch: DurableToolBatch,
      grant: ResumeGrant
  ): IO[AgentError, Either[RunOutcome, AgentState]] =
    for
      plan <- ZIO
        .fromOption(state.pendingToolPlan)
        .orElseFail(AgentError.PersistenceFailure("执行工具批次时缺少 DurableToolPlan"))
      _ <- ZIO
        .fail(AgentError.PersistenceFailure("执行批次不是当前恢复游标"))
        .unless(plan.currentBatch.contains(batch))
      resolved <- ZIO.foreach(batch.items)(item =>
        registry.get(ToolName(item.call.name)).either.map(item -> _)
      )
      _  <- authorization.validateContracts(state, plan, resolved)
      at <- RunClock.millis
      _  <- publisher.emit(
        AgentEvent.ToolBatchStarted(state.runId, plan.id, batch.index, batch.items.length, at)
      )
      _ <- ZIO.foreachDiscard(batch.items)(item =>
        publisher.emit(AgentEvent.ToolCallRequested(state.runId, item.call, at))
      )
      gate   <- authorization.gate(state, plan, batch, resolved, grant)
      result <- gate match
        case GateDecision.NeedsApproval(item, risk, reason, subject) =>
          terminator.suspend(state, item.call, risk, reason, Some(subject)).map(Left(_))
        case GateDecision.ErrorResult(item, toolResult) =>
          appendToolBatchResults(state, batch, Chunk(item -> toolResult)).map(Right(_))
        case GateDecision.Executable(tools) => runBatch(state, plan, batch, tools, grant).map(Right(_))
    yield result

  private def runBatch(
      state: AgentState,
      plan: DurableToolPlan,
      batch: DurableToolBatch,
      tools: Chunk[(DurableToolPlanItem, RegisteredTool)],
      grant: ResumeGrant
  ): IO[AgentError, AgentState] =
    for
      // Blocking 规则的阻断由 GuardrailEngine 自身以 GuardrailRejected 失败完成，因此这里不检查返回的判定：
      // 判定只用于 emitGuardrails 的可观测投影。
      _ <- ZIO.foreachDiscard(tools) { case (item, _) =>
        guardrails
          .checkTool(item.call, None, guardrailContext(state))
          .flatMap(publisher.emitGuardrails(state.runId, "tool.before", _))
      }
      now <- RunClock.millis
      _   <- toolLedger.prepareBatch(
        state.runId,
        batch.executionBatchId(plan.id),
        tools.map { case (item, _) => item },
        now
      )
      invocations = tools.map { case (item, tool) =>
        PlannedToolInvocation(item.ordinal, item.call, tool)
      }
      report <- ToolBatchExecutor.execute(
        ToolExecutionPlan(
          Chunk(ToolExecutionBatch(NonEmptyChunk.fromChunk(invocations).get)),
          invocations.length
        ),
        toolPolicies.current().maxParallelism
      ) { invocation =>
        executeToolResult(
          state,
          DurableToolPlanItem(invocation.ordinal, invocation.call),
          invocation.tool,
          forceRetry = grant.forceRetryCallIds.contains(invocation.call.id)
        )
      }
      ordered <- ZIO.foreach(report.outcomes) { outcome =>
        ZIO
          .fromEither(outcome.result)
          .map(result => DurableToolPlanItem(outcome.ordinal, outcome.call) -> result)
      }
      next <- appendToolBatchResults(state, batch, ordered)
    yield next

  /** 工具结果同时按"工具后置规则"和"不可信远端内容"检查：前者是业务约束，后者防注入。 */
  private def checkToolOutput(
      state: AgentState,
      item: DurableToolPlanItem,
      toolResult: ToolResult
  ): IO[AgentError, Unit] =
    val remote = UntrustedRemoteMessage.fromPayload(
      s"tool:${item.call.name}",
      toolResult.inspectionJson.toJson,
      Set("tool-output")
    )
    for
      after           <- guardrails.checkTool(item.call, Some(toolResult), guardrailContext(state))
      _               <- publisher.emitGuardrails(state.runId, "tool.after", after)
      remoteDecisions <- guardrails.checkRemote(remote, guardrailContext(state))
      _               <- publisher.emitGuardrails(state.runId, "remote", remoteDecisions)
    yield ()

  /** 创建仅用于规划的元数据视图；真正执行仍委托给原注册工具。 */
  private def planningView(tool: RegisteredTool, effectiveMetadata: ToolMetadata): RegisteredTool =
    new RegisteredTool:
      val definition = tool.definition
      val metadata   = effectiveMetadata
      def invoke(
          arguments: Json,
          context: ToolExecutionContext
      ): IO[AgentError, ToolResult] =
        tool.invoke(arguments, context)

  /** 未知工具在规划阶段必须表现为 SequentialOnly，实际门禁会生成 ToolNotFound 结果。 */
  private def unknownPlanningTool(call: ToolCall): RegisteredTool =
    new RegisteredTool:
      val definition = ToolDefinition(call.name, "未注册工具", Json.Obj(), None)
      val metadata   = ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
      def invoke(
          arguments: Json,
          context: ToolExecutionContext
      ): IO[AgentError, ToolResult] =
        ZIO.fail(AgentError.ToolNotFound(call.name))

  /** 根据执行账本选择"复用成功结果、拒绝不安全重放或真正执行"。 */
  private def executeToolResult(
      state: AgentState,
      item: DurableToolPlanItem,
      tool: RegisteredTool,
      forceRetry: Boolean
  ): IO[AgentError, ToolResult] =
    for
      existing <- toolLedger.get(state.runId, item.call.id)
      result   <- AgentKernel
        .replayDecision(existing, tool.metadata.mayReplayAfterCrash, forceRetry) match
        case AgentKernel.ReplayDecision.ReuseSettled(settled) => ZIO.succeed(settled)
        case AgentKernel.ReplayDecision.Execute(record)       => runTool(state, item.call, tool, record)
        case AgentKernel.ReplayDecision.RequiresConfirmation  =>
          ZIO.fail(
            AgentError.InvalidResume(state.runId, s"工具 ${item.call.name}/${item.call.id} 执行结果不确定，需要人工确认")
          )
        case AgentKernel.ReplayDecision.MissingPendingWrite =>
          ZIO.fail(
            AgentError.PersistenceFailure(s"工具 ${item.call.id} 缺少 Prepared pending write 或成功结果")
          )
    yield result

  /** 执行账本的两阶段边界：先 Prepared，再 Running，最终写 Succeeded/Failed/Unknown。 */
  private def runTool(
      state: AgentState,
      call: ToolCall,
      tool: RegisteredTool,
      existing: ToolExecutionRecord
  ): IO[AgentError, ToolResult] =
    for
      now    <- RunClock.millis
      active <- toolLedger.begin(existing, now)
      _      <- publisher.emit(AgentEvent.ToolExecutionStarted(state.runId, call.id, now))
      executor = toolExecutor.narrowed(Set(ToolName(call.name)))
      context  = executionContext(state, call)
      result <- executor
        .execute(tool, call, context)
        .foldZIO(
          error =>
            for
              at <- RunClock.millis
              _  <- toolLedger.fail(active, tool.metadata.mayReplayAfterCrash, error.category.toString, at)
              _  <- publisher.emit(
                AgentEvent.ToolExecutionFailed(state.runId, call.id, error.category.toString, at)
              )
              message = if error.safeToExpose then error.message else s"工具 ${call.name} 执行失败"
            yield ToolResult(
              Json.Obj("error" -> Json.Str(message)),
              isError = true
            ),
          full =>
            for
              item = DurableToolPlanItem(existing.ordinal, call)
              _            <- checkToolOutput(state, item, full)
              externalized <- executor.externalize(
                call,
                context,
                full,
                state.definition.contextPolicy.maxToolResultCharacters
              )
              at <- RunClock.millis
              _  <- toolLedger.succeed(active, externalized, at)
            yield externalized
        )
    yield result

  /** 将工具结果、步骤、用量和下一个恢复游标作为一个状态转换提交。 */
  private def appendToolBatchResults(
      state: AgentState,
      batch: DurableToolBatch,
      results: Chunk[(DurableToolPlanItem, ToolResult)]
  ): IO[AgentError, AgentState] =
    for
      now        <- RunClock.instant
      transition <- ZIO.fromEither(AgentKernel.commitToolBatch(state, batch, results, now))
      next       <- committer.commitTransition(state, transition)
    yield next

  /** Running 恢复：未结算模型优先于工具游标；已结算的最终助手消息直接 Complete，禁止再调 Provider。 */
  private def recoverRunning(state: AgentState): IO[AgentError, RunOutcome] =
    modelCalls.findOpen(state).flatMap {
      case Some(pending) => modelCalls.closeUncertain(state, pending)
      case None          =>
        ZIO.fromEither(AgentKernel.validateUsage(state.budget.limits, state.usage)).tapError {
          terminator.markFailed(state.runId, _)
        } *>
          (state.pendingToolPlan match
            case Some(_) => recoverToolPlan(state)
            case None    =>
              AgentKernel.settledFinalAnswer(state) match
                case Some(answer) => terminator.complete(state, answer)
                case None         => loop(state))
    }

  /** Failed 终态若仍有未结算模型账本，先收口 Unknown，禁止把它当成可忽略的普通终态。 */
  private def closeUncertainModelCall(state: AgentState): IO[AgentError, RunOutcome] =
    modelCalls.findOpen(state).flatMap {
      case Some(pending) => modelCalls.closeUncertain(state, pending)
      case None          => ZIO.fail(AgentError.InvalidResume(state.runId, s"终态 ${state.status} 不能自动恢复"))
    }

  /** 恢复等待审批状态；普通重启绝不等价于自动批准。
    *
    * 这里要求单调用批次：一个等待人工的边界必须能唯一指向"人看到的那个副作用"，多调用批次无法建立这种对应关系。
    */
  private def recoverPending(state: AgentState): IO[AgentError, RunOutcome] =
    state.pendingToolPlan.flatMap(_.currentBatch) match
      case None        => ZIO.fail(AgentError.InvalidResume(state.runId, "暂停状态缺少工具恢复游标"))
      case Some(batch) =>
        for
          item <- ZIO
            .fromOption(batch.items.headOption)
            .orElseFail(AgentError.InvalidResume(state.runId, "暂停工具批次为空"))
          _ <- ZIO
            .fail(AgentError.InvalidResume(state.runId, "审批恢复批次必须为单调用"))
            .unless(batch.items.length == 1)
          tool    <- registry.get(ToolName(item.call.name))
          outcome <- resumeToolBoundary(state, item, tool, suspendedBoundary = true, recoverToolPlan)
        yield outcome

  /** 恢复已经持久化批准或执行中的工具边界。 */
  private def recoverToolPlan(state: AgentState): IO[AgentError, RunOutcome] =
    val resumed = state.copy(status = RunStatus.Running, suspension = None)
    state.pendingToolPlan.flatMap(_.currentBatch) match
      case None                                   => loop(resumed.copy(pendingToolPlan = None))
      case Some(batch) if batch.items.length == 1 =>
        val item = batch.items.head
        registry.get(ToolName(item.call.name)).either.flatMap {
          // 工具已从注册表消失：交给既有的契约校验与 ToolNotFound 门禁处理，这里不重复判定。
          case Left(_)     => processToolPlan(resumed)
          case Right(tool) =>
            resumeToolBoundary(resumed, item, tool, suspendedBoundary = false, processToolPlan(_))
        }
      // 多调用批次的每个成员都在账本里有独立 pending write，恢复由 executeDurableBatch 逐个复用或补齐。
      case Some(_) => processToolPlan(resumed)

  /** 按内核给出的恢复动作处置一个工具边界。
    *
    * 判定本身是纯的（[[AgentKernel.toolRecoveryAction]]），这里只负责执行它：写账本、暂停或继续推进。
    *
    * @param suspendedBoundary
    *   true 表示来自 WaitingForApproval 恢复：尚未产生副作用时应保持暂停，而不是继续推进
    */
  private def resumeToolBoundary(
      state: AgentState,
      item: DurableToolPlanItem,
      tool: RegisteredTool,
      suspendedBoundary: Boolean,
      onContinue: AgentState => IO[AgentError, RunOutcome]
  ): IO[AgentError, RunOutcome] =
    val running = state.copy(status = RunStatus.Running, suspension = None)
    toolLedger.get(state.runId, item.call.id).flatMap { record =>
      AgentKernel.toolRecoveryAction(
        record,
        tool.metadata.mayReplayAfterCrash,
        tool.metadata.recoveryPolicy,
        suspendedBoundary
      ) match
        case AgentKernel.ToolRecoveryAction.StaySuspended =>
          ZIO.fromEither(AgentKernel.suspendedOutcome(state))
        case AgentKernel.ToolRecoveryAction.SettleUnknownThenSuspend(open, reason) =>
          for
            now <- RunClock.millis
            _   <- toolLedger.settleUnknown(open, now)
            out <- terminator.suspend(running, item.call, tool.metadata.risk, reason)
          yield out
        case AgentKernel.ToolRecoveryAction.SuspendOnly(reason) =>
          terminator.suspend(running, item.call, tool.metadata.risk, reason)
        case AgentKernel.ToolRecoveryAction.Continue => onContinue(running)
    }

  /** 在发起下一次外部动作之前检查步骤、模型、工具、失败循环与 token 硬上限。 */
  private def ensureBudget(state: AgentState): IO[AgentError, Unit] =
    ZIO.fromEither(AgentKernel.validateBeforeAction(state))

  /** 从可信状态构造工具上下文；模型无法修改其中的租户、用户和 scopes。 */
  private def executionContext(state: AgentState, call: ToolCall): ToolExecutionContext =
    ToolExecutionContext(
      state.runId,
      state.threadId,
      call.id,
      state.runContext
    )

  /** 构造各阶段 Guardrail 共用的只读可信上下文。 */
  private def guardrailContext(state: AgentState): GuardrailContext =
    GuardrailContext(state.runId, state.runContext, state.agentId)

object AgentRuntimeDriver:
  /** 装配入口在 [[AgentRuntimeDriverLayers]]，这里只做转发以保持调用方稳定。 */
  export AgentRuntimeDriverLayers.{layer, layerWithContextSources, layerWithProfile, layerWithCapture}

  /** 规划阶段对单个模型调用得出的确定性结论。
    *
    * @param contract
    *   规划时冻结的工具契约指纹
    * @param approvalSubject
    *   规划时判定需要人工授权时冻结的审批主体；None 表示当时不需要审批
    */
  final private case class PlannedCall(
      invocation: PlannedToolInvocation,
      contract: com.zyblw.agent.composition.ToolContractFingerprint,
      approvalSubject: Option[com.zyblw.agent.composition.ApprovalSubject]
  )
