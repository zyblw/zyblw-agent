package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.composition.ApprovalSubject
import com.zyblw.agent.tools.{
  ApprovalPolicy,
  ToolMetadata,
  ToolParallelism,
  ToolPolicyConfig,
  ToolRecoveryPolicy
}
import java.time.Instant
import zio.*

/** Agent Runtime 的纯决策内核。
  *
  * 本对象只把已经取得的不可变事实归约为决定或下一状态；不读取 Clock、不调用模型/工具、不访问 Store，也不启动 Fiber。 `AgentRuntimeDriver` 是唯一 effectful
  * shell，负责执行这些决定并通过 fencing/CAS 提交。
  */
private[agent] object AgentKernel:
  enum RecoveryDecision:
    case ReturnCompleted
    case ReturnCancelled
    case CloseUncertainModelCall
    case RecoverSuspended
    case StartCreated
    case RecoverRunning

  final case class Transition(
      state: AgentState,
      events: NonEmptyChunk[AgentEvent]
  )

  /** 一次审批恢复的合法目标：待处理计划、当前批次、被授权的调用与人看到的那个请求。 */
  final case class ResumeTarget(
      plan: DurableToolPlan,
      batch: DurableToolBatch,
      item: DurableToolPlanItem,
      approval: ApprovalRequest
  )

  /** 耐久审批命令在重放窗口内的处置。
    *
    * "状态已提交、命令尚未 complete 时进程崩溃"会让同一条命令被重领。这里区分四种情况，避免以后写覆盖前写。
    */
  enum ApprovalCommandAction:
    /** 尚未记录决定，正常应用。 */
    case Apply

    /** 决定已记录且 Run 仍在推进：先前命令在后续 loop 中崩溃，需要从账本继续恢复。 */
    case ResumeFromLedger

    /** 决定已记录且已产生稳定结果，幂等返回。 */
    case AlreadyApplied

    /** 历史决定与命令正文不同，必须拒绝。 */
    case Conflict(reason: String)

  /** 一条挂起到期后应当发生什么。 */
  enum SuspensionExpiryAction:
    /** 按预设默认值回到 Running。 */
    case Resume(transition: Transition)

    /** 到期失败。`kind` 是低敏挂起类别，供失败原因与指标使用，不含 payload。 */
    case FailRun(kind: String)

  /** 校验一次审批恢复的前提，并返回被授权的那个具体调用。
    *
    * 单调用要求不是实现便利：一个等待人工的边界必须能唯一指向"人看到的那个副作用"。
    */
  def resumeTarget(state: AgentState): Either[AgentError, ResumeTarget] =
    for
      plan  <- state.pendingToolPlan.toRight(AgentError.InvalidResume(state.runId, "没有待处理工具计划"))
      batch <- plan.currentBatch.toRight(AgentError.InvalidResume(state.runId, "工具计划已经完成"))
      item  <- batch.items.headOption.toRight(AgentError.InvalidResume(state.runId, "当前工具批次为空"))
      _     <- Either.cond(
        batch.items.length == 1,
        (),
        AgentError.InvalidResume(state.runId, "审批批次必须只包含一个工具调用")
      )
      approval <- state.pendingApproval.toRight(AgentError.InvalidResume(state.runId, "缺少待审批请求"))
      _        <- Either.cond(
        approval.toolCall.id == item.call.id,
        (),
        AgentError.InvalidResume(state.runId, "审批请求与当前工具批次不一致")
      )
      _ <- Either.cond(
        state.status == RunStatus.WaitingForApproval,
        (),
        AgentError.InvalidResume(state.runId, s"状态为 ${state.status}")
      )
    yield ResumeTarget(plan, batch, item, approval)

  /** 判定一条耐久审批命令此刻应该做什么。 */
  def approvalCommandAction(
      state: AgentState,
      approvalId: String,
      decision: ApprovalDecision
  ): ApprovalCommandAction =
    val historical = state.steps.collectFirst {
      case AgentStep.ApprovalStep(_, request, Some(recorded), _) if request.id == approvalId => recorded
    }
    historical match
      case Some(recorded) if recorded != decision =>
        ApprovalCommandAction.Conflict(s"审批 $approvalId 已记录另一决定")
      case Some(_) if state.status == RunStatus.Created || state.status == RunStatus.Running =>
        ApprovalCommandAction.ResumeFromLedger
      case Some(_) => ApprovalCommandAction.AlreadyApplied
      case None    =>
        state.pendingApproval match
          case Some(pending) if pending.id == approvalId => ApprovalCommandAction.Apply
          case Some(_)                                   =>
            ApprovalCommandAction.Conflict("审批命令与当前请求不一致")
          case None => ApprovalCommandAction.Conflict("缺少待审批请求")

  final case class ModelTurnTransition(
      transition: Transition,
      usage: UsageSummary,
      modelCallSettlement: Option[ModelCallExecutionRecord]
  )

  /** 仅根据耐久状态选择恢复路径；执行与 I/O 留给 Driver。 */
  def recoveryDecision(status: RunStatus): RecoveryDecision = status match
    case RunStatus.Completed                                              => RecoveryDecision.ReturnCompleted
    case RunStatus.Cancelled                                              => RecoveryDecision.ReturnCancelled
    case RunStatus.Failed | RunStatus.TimedOut | RunStatus.BudgetExceeded =>
      RecoveryDecision.CloseUncertainModelCall
    case RunStatus.WaitingForApproval | RunStatus.Suspended => RecoveryDecision.RecoverSuspended
    case RunStatus.Created                                  => RecoveryDecision.StartCreated
    case RunStatus.Running                                  => RecoveryDecision.RecoverRunning

  /** 下一次外部动作前的硬预算门禁。达到上限即停止。 */
  def validateBeforeAction(state: AgentState): Either[AgentError.BudgetExceeded, Unit] =
    val limits = state.budget.limits
    for
      _ <- below("steps", state.budget.steps, limits.maxSteps)
      _ <- below("modelCalls", state.usage.modelCalls, limits.maxModelCalls)
      _ <- below("toolCalls", state.usage.toolCalls, limits.maxToolCalls)
      _ <- below("repeatedActions", state.consecutiveToolFailures, limits.maxRepeatedActions)
      _ <- validateUsage(limits, state.usage, rejectAtLimit = true)
    yield ()

  /** Provider 结算后的用量门禁。刚收到的结果允许恰好等于上限，超过才失败。 */
  def validateUsage(
      limits: RunLimits,
      usage: UsageSummary,
      rejectAtLimit: Boolean = false
  ): Either[AgentError.BudgetExceeded, Unit] =
    def accepted[A](actual: A, limit: A)(using ordering: Ordering[A]): Boolean =
      if rejectAtLimit then ordering.lt(actual, limit) else ordering.lteq(actual, limit)

    for
      _ <- requireLimit("inputTokens", usage.inputTokens, limits.maxInputTokens, accepted)
      _ <- requireLimit("outputTokens", usage.outputTokens, limits.maxOutputTokens, accepted)
      _ <- requireLimit("tokens", usage.totalTokens, limits.maxTotalTokens, accepted)
      _ <- limits.maxEstimatedCost.fold[Either[AgentError.BudgetExceeded, Unit]](Right(())) { limit =>
        val limitMicros =
          (limit * BigDecimal(1_000_000)).setScale(0, BigDecimal.RoundingMode.CEILING).toLong
        Either.cond(
          accepted(usage.estimatedCost, limit),
          (),
          AgentError.BudgetExceeded("estimatedCostMicros", limitMicros)
        )
      }
    yield ()

  /** 把一次已完成的 Provider 响应归约为可原子提交的状态、事件与模型账本结算。 */
  def settleModelTurn(
      state: AgentState,
      response: ChatResponse,
      provider: String,
      model: String,
      estimatedCost: BigDecimal,
      routed: Boolean,
      pendingToolPlan: Option[DurableToolPlan],
      modelCallRecord: Option[ModelCallExecutionRecord],
      at: Instant
  ): Either[AgentError, ModelTurnTransition] =
    val usage = modelTurnUsage(state, response.usage, estimatedCost, routed)
    for
      _ <- requireStatus(state, "结算模型响应", Set(RunStatus.Running))
      _ <- Either.cond(
        !routed || state.usage.modelCalls > 0,
        (),
        AgentError.PersistenceFailure("路由模型响应缺少预留的模型调用用量")
      )
      _ <- validateToolCallBudget(state.budget.limits, usage, response.message.toolCalls.length)
      _ <- validateToolPlan(response.message.toolCalls, pendingToolPlan)
      _ <- Either.cond(
        modelCallRecord.forall(_.status == ModelCallStatus.Dispatched),
        (),
        AgentError.PersistenceFailure("模型结算记录不是 Dispatched")
      )
    yield
      val step = AgentStep.ModelStep(
        state.steps.length + 1,
        provider,
        model,
        response.usage,
        response.finishReason,
        at.toEpochMilli
      )
      val next = state.copy(
        messages = state.messages :+ response.message,
        steps = state.steps :+ step,
        usage = usage,
        budget = state.budget.copy(consumed = usage, steps = state.budget.steps + 1),
        updatedAt = at,
        pendingToolPlan = pendingToolPlan,
        pendingModelCall = None
      )
      val events = NonEmptyChunk(
        AgentEvent.ModelCallCompleted(state.runId, response.usage, at.toEpochMilli),
        pendingToolPlan.toList.map(plan =>
          AgentEvent.ToolBatchPlanned(
            state.runId,
            plan.id,
            plan.batches.length,
            response.message.toolCalls.length,
            at.toEpochMilli
          )
        )*
      )
      val modelSettlement = modelCallRecord.map { record =>
        record.copy(
          status = ModelCallStatus.Succeeded,
          usage = Some(response.usage),
          finishReason = Some(response.finishReason),
          updatedAtEpochMilli = at.toEpochMilli
        )
      }
      ModelTurnTransition(Transition(next, events), usage, modelSettlement)

  /** 计算一次主模型响应结算后的累计用量。路由请求已经在 Intent 阶段预留一次调用，结算时先抵消再写入真实 usage。 */
  def modelTurnUsage(
      state: AgentState,
      responseUsage: TokenUsage,
      estimatedCost: BigDecimal,
      routed: Boolean
  ): UsageSummary =
    val base =
      if routed then state.usage.copy(modelCalls = state.usage.modelCalls - 1)
      else state.usage
    base.addModel(responseUsage, estimatedCost)

  /** 在构造工具计划之前拒绝超出 Run 总工具预算的模型提议。 */
  def validateToolCallBudget(
      limits: RunLimits,
      usage: UsageSummary,
      proposedCalls: Int
  ): Either[AgentError.BudgetExceeded, Unit] =
    Either.cond(
      usage.toolCalls + proposedCalls <= limits.maxToolCalls,
      (),
      AgentError.BudgetExceeded("toolCalls", limits.maxToolCalls)
    )

  /** 把一个完整工具批次归约为下一游标；结果顺序由 ordinal 决定，不受 Fiber 完成顺序影响。 */
  def commitToolBatch(
      state: AgentState,
      batch: DurableToolBatch,
      results: Chunk[(DurableToolPlanItem, ToolResult)],
      at: Instant
  ): Either[AgentError, Transition] =
    for
      _    <- requireStatus(state, "提交工具批次", Set(RunStatus.Running))
      plan <- state.pendingToolPlan.toRight(AgentError.PersistenceFailure("提交工具批次时缺少 DurableToolPlan"))
      _    <- Either.cond(
        plan.currentBatch.contains(batch),
        (),
        AgentError.PersistenceFailure("提交的工具批次与当前恢复游标不一致")
      )
      ordered = results.sortBy(_._1.ordinal)
      _ <- Either.cond(
        ordered.map(_._1.ordinal) == batch.items.sortBy(_.ordinal).map(_.ordinal),
        (),
        AgentError.PersistenceFailure("工具批次结果数量或 ordinal 不完整")
      )
    yield
      val usage = state.usage.copy(toolCalls = state.usage.toolCalls + ordered.length)
      val steps = ordered.zipWithIndex.map { case ((item, result), offset) =>
        AgentStep.ToolStep(state.steps.length + offset + 1, item.call, result, at.toEpochMilli)
      }
      val messages = ordered.map { case (item, result) =>
        AgentMessage.tool(item.call.id, item.call.name, result)
      }
      val failureCount = ordered.foldLeft(state.consecutiveToolFailures) { case (count, (_, result)) =>
        if result.isError then count + 1 else 0
      }
      val advanced = plan.advance
      val nextPlan = Option.when(advanced.currentBatch.nonEmpty)(advanced)
      val events   = NonEmptyChunk
        .fromChunk(
          ordered.map { case (item, result) =>
            AgentEvent.ToolExecutionCompleted(state.runId, item.call.id, result, at.toEpochMilli)
          } :+ AgentEvent.ToolBatchCommitted(
            state.runId,
            plan.id,
            batch.index,
            ordered.length,
            at.toEpochMilli
          )
        )
        .get
      Transition(
        state.copy(
          messages = state.messages ++ messages,
          steps = state.steps ++ steps,
          usage = usage,
          budget = state.budget.copy(consumed = usage),
          suspension = None,
          pendingToolPlan = nextPlan,
          consecutiveToolFailures = failureCount,
          updatedAt = at
        ),
        events
      )

  def completedOutcome(state: AgentState): Either[AgentError, RunOutcome.Completed] =
    for
      _ <- requireStatus(state, "重建完成结果", Set(RunStatus.Completed))
      thread = state.threadId
      answer <- state.messages.reverse
        .find(_.role == MessageRole.Assistant)
        .toRight(
          AgentError.PersistenceFailure(s"已完成 Run ${state.runId.asString} 缺少最终助手消息")
        )
    yield RunOutcome.Completed(
      state.runId,
      thread,
      answer,
      tokenUsage(state.usage),
      state.budget.steps
    )

  def suspendedOutcome(state: AgentState): Either[AgentError, RunOutcome.Suspended] =
    for
      _ <- requireStatus(
        state,
        "重建暂停结果",
        Set(RunStatus.WaitingForApproval, RunStatus.Suspended)
      )
      record <- state.suspension.toRight(
        AgentError.InvalidResume(state.runId, "暂停状态缺少 SuspensionRecord")
      )
      _ <- Either.cond(
        record.runStatus == state.status,
        (),
        AgentError.InvalidResume(state.runId, s"挂起类别 ${record.kind.kind} 与状态 ${state.status} 不一致")
      )
    yield RunOutcome.Suspended(
      state.runId,
      state.threadId,
      record,
      tokenUsage(state.usage),
      state.budget.steps
    )

  /** 输出 Guardrail 已通过后，把 Run 收口为 Completed。 */
  def complete(state: AgentState, answer: AgentMessage, at: Instant): Either[AgentError, Transition] =
    requireStatus(state, "完成 Run", Set(RunStatus.Running)).map { _ =>
      Transition(
        state.copy(status = RunStatus.Completed, suspension = None, updatedAt = at),
        NonEmptyChunk(AgentEvent.RunCompleted(state.runId, answer, state.usage, at.toEpochMilli))
      )
    }

  /** 在任何受控副作用之前生成绑定具体主体的审批请求和暂停状态。
    *
    * @param deadline
    *   审批过期时刻。`None` 保持无限期等待；给出时刻则由 `SuspensionExpiryWorker` 在到期后按 `FailRun` 收口——未获批的副作用授权
    *   过期后自动放行等于权限提升，因此审批类挂起没有 `ResumeWithDefault` 选项。
    */
  def suspend(
      state: AgentState,
      call: ToolCall,
      risk: ToolRisk,
      reason: String,
      subject: Option[ApprovalSubject],
      at: Instant,
      deadline: Option[Instant] = None
  ): Either[AgentError, (ApprovalRequest, Transition)] =
    requireStatus(
      state,
      "暂停等待审批",
      Set(RunStatus.Running, RunStatus.WaitingForApproval, RunStatus.Suspended)
    ).map { _ =>
      val approval = ApprovalRequest(
        approvalRequestId(state.runId, call, subject),
        state.runId,
        call,
        risk,
        reason,
        at.toEpochMilli,
        subject
      )
      approval -> suspendTransition(state, SuspensionRecord.approval(approval, at, deadline), at)
    }

  /** 把任意等待原因写成挂起状态。
    *
    * 这是**唯一**的挂起写入点：状态与事件由 `SuspensionRecord` 一并决定，因此不可能出现"状态是 Suspended 但事件说在等审批" 这类自相矛盾的组合。审批分支额外发一条
    * `ToolApprovalRequired`，因为控制面需要拿到完整请求才能展示待批项。
    */
  def suspendTransition(state: AgentState, record: SuspensionRecord, at: Instant): Transition =
    val base   = AgentEvent.RunSuspended(state.runId, record.kind.safeReason, at.toEpochMilli)
    val events = record.kind.approvalRequest.fold(NonEmptyChunk(base))(approval =>
      NonEmptyChunk(base, AgentEvent.ToolApprovalRequired(state.runId, approval, at.toEpochMilli))
    )
    Transition(
      state.copy(status = record.runStatus, suspension = Some(record), updatedAt = at),
      events
    )

  /** 挂起到期后的收口决议。
    *
    * 纯函数：Worker 只负责发现"这条挂起过期了"，走向哪里由这里决定。`ResumeWithDefault` 回到 `Running` 并清空挂起，让主循环 从既有游标继续；`FailRun`
    * 交给调用方走统一的失败收口，不在这里伪造一个 `RunFailed`。
    */
  def suspensionExpiry(state: AgentState, at: Instant): Either[AgentError, SuspensionExpiryAction] =
    state.suspension match
      case None                                  => Left(AgentError.InvalidResume(state.runId, "没有待过期的挂起"))
      case Some(record) if !record.expiredAt(at) =>
        Left(AgentError.InvalidResume(state.runId, "挂起尚未到期"))
      case Some(record) =>
        Right(record.expiryOutcome match
          case SuspensionExpiry.FailRun           => SuspensionExpiryAction.FailRun(record.kind.kind)
          case SuspensionExpiry.ResumeWithDefault =>
            SuspensionExpiryAction.Resume(
              Transition(
                state.copy(status = RunStatus.Running, suspension = None, updatedAt = at),
                NonEmptyChunk(AgentEvent.RunResumed(state.runId, at.toEpochMilli))
              )
            ))

  /** 最终答案已经结算时保留它；预算失败除外，因为预算是不可绕过的硬终态。 */
  def fail(state: AgentState, error: AgentError, at: Instant): Option[Transition] =
    Option.when(
      !terminalStatus(state.status) &&
        (settledFinalAnswer(state).isEmpty || error.isInstanceOf[AgentError.BudgetExceeded])
    ) {
      val event = error match
        case _: AgentError.Cancelled => AgentEvent.RunCancelled(state.runId, at.toEpochMilli)
        case _                       =>
          AgentEvent.RunFailed(
            state.runId,
            error.category.toString,
            if error.safeToExpose then error.message else "运行失败",
            at.toEpochMilli
          )
      Transition(
        state.copy(status = failureStatus(error), suspension = None, updatedAt = at),
        NonEmptyChunk(event)
      )
    }

  /** 取消为幂等终态迁移；已经终结的 Run 不再生成事件。 */
  def cancel(state: AgentState, at: Instant): Option[Transition] =
    Option.unless(terminalStatus(state.status))(
      Transition(
        state.copy(status = RunStatus.Cancelled, suspension = None, updatedAt = at),
        NonEmptyChunk(AgentEvent.RunCancelled(state.runId, at.toEpochMilli))
      )
    )

  def settledFinalAnswer(state: AgentState): Option[AgentMessage] =
    state.messages.lastOption.filter(message =>
      message.role == MessageRole.Assistant && message.toolCalls.isEmpty
    )

  def terminalStatus(status: RunStatus): Boolean = RunStatus.isTerminal(status)

  def failureStatus(error: AgentError): RunStatus = error match
    case _: AgentError.Cancelled         => RunStatus.Cancelled
    case _: AgentError.BudgetExceeded    => RunStatus.BudgetExceeded
    case _: AgentError.SuspensionExpired => RunStatus.TimedOut
    case _                               => RunStatus.Failed

  def approvalReason(policy: ToolPolicyConfig, risk: ToolRisk): Option[String] =
    policy.approvalPolicy match
      case ApprovalPolicy.Never     => None
      case ApprovalPolicy.Always    => Some("当前运行策略要求所有工具调用经过人工审批")
      case ApprovalPolicy.RiskBased =>
        risk match
          case ToolRisk.ReadOnly | ToolRisk.UserScopedRead => None
          case _                                           => Some("工具具有写入、破坏或管理副作用")

  def pendingToolNames(state: AgentState): Set[String] =
    state.pendingToolPlan.fold(Set.empty[String])(_.batches.flatMap(_.items.map(_.call.name)).toSet)

  /** 恢复时对一条未结算模型账本的收口方式。
    *
    * 模型调用与工具调用一样是不确定的外部副作用：请求可能已经发出并计费，而进程在收到响应前消失。因此四种账本状态各有 不同处置，任何一种都不允许静默重放。
    */
  enum ModelCallClosure:
    /** `Prepared`/`Dispatched`：请求可能已经发出，把账本标为 Unknown 并让 Run 进入 Failed。 */
    case SettleUnknownAndFail(record: ModelCallExecutionRecord, reason: String)

    /** 账本已是 `Unknown`：只需把仍在 Running 的 Run 收口为 Failed。 */
    case FailIfRunning(reason: String)

    /** 账本无法结算但状态已知，直接收口为 Failed。 */
    case Fail(reason: String)

    /** `Succeeded` 却仍留着状态游标：这是内部不一致，必须显式拒绝而不是猜测。 */
    case Inconsistent(reason: String)

    /** 游标指向的账本记录不存在：持久化损坏。 */
    case MissingRecord(reason: String)

  /** 判定一条未结算模型账本应如何收口。 */
  def modelCallClosure(
      pending: PendingModelCall,
      existing: Option[ModelCallExecutionRecord]
  ): ModelCallClosure =
    existing match
      case Some(record)
          if record.status == ModelCallStatus.Prepared || record.status == ModelCallStatus.Dispatched =>
        ModelCallClosure.SettleUnknownAndFail(record, "模型调用在 Provider 结算前中断，结果未知，未自动重放")
      case Some(record) if record.status == ModelCallStatus.Unknown =>
        ModelCallClosure.FailIfRunning("模型调用结果未知，未自动重放")
      case Some(record) if record.status == ModelCallStatus.Succeeded =>
        ModelCallClosure.Inconsistent(s"模型调用 ${pending.requestId.asString} 已结算成功但状态游标未清除")
      case Some(_) => ModelCallClosure.Fail("模型调用未能结算")
      case None    =>
        ModelCallClosure.MissingRecord(s"模型调用 ${pending.requestId.asString} 缺少账本记录")

  /** 一条模型账本是否仍未结算，因此禁止把 Run 当作可正常推进或可忽略的终态。 */
  def openModelCall(status: ModelCallStatus): Boolean =
    status == ModelCallStatus.Prepared ||
      status == ModelCallStatus.Dispatched ||
      status == ModelCallStatus.Unknown

  /** 进程内调用失败时账本应写入的状态。
    *
    * 中断与路由调用都记为 `Unknown`：前者无法判断请求是否已经发出，后者已经预留了调用额度。
    */
  def interruptedModelCallStatus(interrupted: Boolean, routed: Boolean): ModelCallStatus =
    if interrupted || routed then ModelCallStatus.Unknown else ModelCallStatus.Failed

  /** 依据工具执行账本决定是否允许再次执行一个调用。
    *
    * 这是"绝不静默重放不确定副作用"的唯一判定点。`Running` 与 `Unknown` 都表示外部系统可能已经改变，因此只有工具 明确声明崩溃后可重放，或人工已经批准这次重放，才允许继续。
    */
  enum ReplayDecision:
    /** 账本已有成功结果，直接复用；禁止为同一个 callId 产生第二次副作用。 */
    case ReuseSettled(result: ToolResult)

    /** 允许真正执行；`record` 是 CAS 的起点。 */
    case Execute(record: ToolExecutionRecord)

    /** 结果不确定且不可自动重放，必须先由人工确认。 */
    case RequiresConfirmation

    /** 缺少 Prepared pending write，属于持久化损坏而不是"这次不需要执行"。 */
    case MissingPendingWrite

  /** 崩溃恢复时对一个已冻结工具边界的处置。
    *
    * 与 [[ReplayDecision]] 的区别在于：这里判断的是"恢复应该走哪条路"，而 `ReplayDecision` 判断的是"这次执行是否允许 发生"。二者都不读 Clock 或 Store。
    */
  enum ToolRecoveryAction:
    /** 账本尚未产生副作用，Run 仍应保持暂停等待人工。 */
    case StaySuspended

    /** 账本停在 Running/Unknown 且不可自动重放：先把它收口为 Unknown，再带原因重新暂停。 */
    case SettleUnknownThenSuspend(record: ToolExecutionRecord, reason: String)

    /** 账本状态未知但工具本身不可自动重放，无需再改账本，直接带原因暂停。 */
    case SuspendOnly(reason: String)

    /** 边界安全，可以继续推进耐久计划。 */
    case Continue

  /** 判定一次工具调用在当前账本下允许发生什么。
    *
    * @param existing
    *   该 callId 在账本中的记录；`None` 表示批次 Prepared 写入缺失
    * @param mayReplayAfterCrash
    *   工具作者声明的崩溃后可重放性；模型不能修改
    * @param forceRetry
    *   仅在人工明确批准重放一个结果未知的副作用时为 true
    */
  def replayDecision(
      existing: Option[ToolExecutionRecord],
      mayReplayAfterCrash: Boolean,
      forceRetry: Boolean
  ): ReplayDecision =
    existing match
      case Some(record) if record.status == ToolExecutionStatus.Succeeded =>
        record.result.fold[ReplayDecision](ReplayDecision.MissingPendingWrite)(ReplayDecision.ReuseSettled(_))
      case Some(record) if uncertainExecution(record.status) && !mayReplayAfterCrash && !forceRetry =>
        ReplayDecision.RequiresConfirmation
      case Some(record) => ReplayDecision.Execute(record)
      case None         => ReplayDecision.MissingPendingWrite

  /** 判定崩溃恢复在一个工具边界上应该走哪条路。
    *
    * @param existing
    *   该 callId 的账本记录；`None` 表示尚未写入 pending write
    * @param mayReplayAfterCrash
    *   工具是否声明崩溃后可安全重放
    * @param recoveryPolicy
    *   决定暂停原因文案的恢复策略，与在线重试策略无关
    */
  def toolRecoveryAction(
      existing: Option[ToolExecutionRecord],
      mayReplayAfterCrash: Boolean,
      recoveryPolicy: ToolRecoveryPolicy,
      suspendedBoundary: Boolean
  ): ToolRecoveryAction =
    existing match
      case None if suspendedBoundary => ToolRecoveryAction.StaySuspended
      case Some(record)
          if suspendedBoundary &&
            Set(ToolExecutionStatus.Prepared, ToolExecutionStatus.Failed).contains(record.status) =>
        ToolRecoveryAction.StaySuspended
      case Some(record) if record.status == ToolExecutionStatus.Running && !mayReplayAfterCrash =>
        ToolRecoveryAction.SettleUnknownThenSuspend(
          record,
          uncertainToolReason(ToolExecutionStatus.Running, recoveryPolicy)
        )
      case Some(record) if record.status == ToolExecutionStatus.Unknown && !mayReplayAfterCrash =>
        ToolRecoveryAction.SuspendOnly(uncertainToolReason(ToolExecutionStatus.Unknown, recoveryPolicy))
      case _ => ToolRecoveryAction.Continue

  /** 崩溃恢复暂停原因由 [[ToolRecoveryPolicy]] 决定，不复用在线 [[com.zyblw.agent.tools.ToolRetryPolicy]] 文案。 */
  def uncertainToolReason(status: ToolExecutionStatus, recoveryPolicy: ToolRecoveryPolicy): String =
    (status, recoveryPolicy) match
      case (ToolExecutionStatus.Running, ToolRecoveryPolicy.RequiresApproval) =>
        "进程中断时破坏性工具处于 Running，外部副作用结果未知；即使此前已批准也必须再次确认后才允许重放"
      case (_, ToolRecoveryPolicy.RequiresApproval) =>
        "破坏性工具执行结果未知；请先核对外部系统，再决定是否重放"
      case (ToolExecutionStatus.Running, _) =>
        "进程中断时非幂等工具处于 Running，外部副作用结果未知；确认后才允许重放"
      case _ =>
        "非幂等工具执行结果未知；请先核对外部系统，再决定是否重放"

  /** 工具执行失败后账本应写入的状态：可重放工具写 Failed，其余必须写 Unknown。 */
  def toolFailureStatus(mayReplayAfterCrash: Boolean): ToolExecutionStatus =
    if mayReplayAfterCrash then ToolExecutionStatus.Failed else ToolExecutionStatus.Unknown

  private def uncertainExecution(status: ToolExecutionStatus): Boolean =
    status == ToolExecutionStatus.Running || status == ToolExecutionStatus.Unknown

  /** 规划器可以看到的工具元数据视图。
    *
    * 这是"安全能力门禁"：只有工具显式声明冲突组、允许崩溃后重放、不需要审批，且当前调用者已具备全部所需 scope 时，规划 器才看见它真实的 ConflictAware
    * 元数据。任何未知、漏配或高风险工具都会被降级为 `SequentialOnly` 并清空冲突声明， 因此即使模型一次输出多个调用，也不会把不确定副作用静默并行化。
    *
    * 降级只会收紧并发，不会放宽：这是"并行是显式选择而非默认"的唯一实现点。
    *
    * @param grantedScopes
    *   Run 创建时冻结的可信 scope；模型无法修改
    */
  def planningMetadata(
      metadata: ToolMetadata,
      policy: ToolPolicyConfig,
      grantedScopes: Set[String]
  ): ToolMetadata =
    val safeToParallelize =
      metadata.conflictAwareParallel &&
        metadata.mayReplayAfterCrash &&
        approvalReason(policy, metadata.risk).isEmpty &&
        metadata.requiredScopes.subsetOf(grantedScopes)
    if safeToParallelize then metadata
    else metadata.copy(parallelism = ToolParallelism.SequentialOnly, conflictAccesses = Set.empty)

  /** 一次调用是否必须经过人工授权。这是安全关键谓词，只允许存在这一份实现。
    *
    * 两个来源都必须成立才算"不需要审批"：规划时冻结的审批要求不会因为策略事后放宽而消失（单调审批），而现场策略可以 追加新的审批要求。任何一侧要求审批，结果就是要求审批。
    *
    * @param plan
    *   规划时冻结了审批要求的耐久计划
    * @param policy
    *   本次判定使用的生效策略快照；与冻结进 [[ApprovalSubject]] 的策略指纹必须来自同一次读取
    */
  def requiresApproval(
      plan: DurableToolPlan,
      policy: ToolPolicyConfig,
      callId: String,
      risk: ToolRisk
  ): Boolean =
    plan.frozenApprovalCallIds.contains(callId) || approvalReason(policy, risk).nonEmpty

  /** 审批主体参与稳定标识，主体漂移后旧页面提交的决定无法命中新请求。 */
  def approvalRequestId(runId: RunId, call: ToolCall, subject: Option[ApprovalSubject]): String =
    val base = s"approval-${runId.asString}-${call.id}"
    subject.fold(base)(value => s"$base-${value.value.take(16)}")

  private def tokenUsage(usage: UsageSummary): TokenUsage =
    TokenUsage(
      usage.inputTokens,
      usage.outputTokens,
      usage.cachedInputTokens,
      usage.reasoningOutputTokens,
      usage.cacheWriteInputTokens
    )

  private def validateToolPlan(
      calls: Chunk[ToolCall],
      plan: Option[DurableToolPlan]
  ): Either[AgentError.PersistenceFailure, Unit] =
    val expected = calls.zipWithIndex.map { case (call, ordinal) => DurableToolPlanItem(ordinal, call) }
    val batches  = plan.fold(Chunk.empty[DurableToolBatch])(_.batches)
    val actual   = batches.flatMap(_.items)
    val indexes  = batches.map(_.index)
    val expectedIndexes    = Chunk.fromIterable(batches.indices)
    val startsAtFirstBatch = plan.forall(_.nextBatchIndex == 0)
    Either.cond(
      actual == expected && indexes == expectedIndexes && startsAtFirstBatch && plan.isDefined == calls.nonEmpty,
      (),
      AgentError.PersistenceFailure("模型响应与待提交 DurableToolPlan 不一致")
    )

  private def requireStatus(
      state: AgentState,
      action: String,
      allowed: Set[RunStatus]
  ): Either[AgentError.InvalidResume, Unit] =
    Either.cond(
      allowed.contains(state.status),
      (),
      AgentError.InvalidResume(state.runId, s"状态 ${state.status} 不能执行：$action")
    )

  private def below(kind: String, actual: Int, limit: Int): Either[AgentError.BudgetExceeded, Unit] =
    Either.cond(actual < limit, (), AgentError.BudgetExceeded(kind, limit.toLong))

  private def requireLimit(
      kind: String,
      actual: Long,
      limit: Long,
      accepted: (Long, Long) => Boolean
  ): Either[AgentError.BudgetExceeded, Unit] =
    Either.cond(accepted(actual, limit), (), AgentError.BudgetExceeded(kind, limit))
