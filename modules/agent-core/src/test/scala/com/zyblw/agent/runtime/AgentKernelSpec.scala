package com.zyblw.agent.runtime

import com.zyblw.agent.composition.{
  ApprovalPolicyFingerprint,
  ApprovalSubject,
  AuthorizationFingerprint,
  RuntimeComposition,
  RuntimeProfile,
  ToolContractFingerprint
}
import com.zyblw.agent.core.*
import com.zyblw.agent.tools.{SideEffect, ToolMetadata, ToolPolicyConfig}
import java.time.Instant
import zio.*
import zio.json.ast.Json
import zio.test.*

object AgentKernelSpec extends ZIOSpecDefault:
  private val now   = Instant.parse("2026-09-14T00:00:00Z")
  private val runId = RunId(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"))

  private val kernelAgent = AgentDefinition(AgentId("kernel-spec"), "Kernel Spec", "内核测试")

  private def state(
      status: RunStatus = RunStatus.Running,
      limits: RunLimits = RunLimits(),
      usage: UsageSummary = UsageSummary(),
      failures: Int = 0,
      plan: Option[DurableToolPlan] = None
  ): AgentState =
    AgentState(
      runId,
      SessionId(java.util.UUID.fromString("22222222-2222-2222-2222-222222222222")),
      AgentId("kernel-spec"),
      status,
      Chunk(AgentMessage.user("input")),
      Chunk.empty,
      usage,
      BudgetState(limits, usage, 0),
      None,
      now,
      now,
      Version.initial,
      kernelAgent,
      RuntimeComposition.fingerprint(RuntimeProfile.default, kernelAgent, kernelAgent.modelSettings),
      ThreadId("kernel-thread"),
      consecutiveToolFailures = failures,
      pendingToolPlan = plan
    )

  /** 按批次里出现的工具名补齐契约指纹，让测试聚焦于 Kernel 决定而不是指纹装配。 */
  private def durablePlan(
      id: String,
      batches: Chunk[DurableToolBatch],
      approvalSubjects: Map[String, ApprovalSubject] = Map.empty
  ): DurableToolPlan =
    val names = batches.flatMap(_.items.map(_.call.name)).toSet
    DurableToolPlan(
      id,
      batches,
      toolContractFingerprints = names.map(name => name -> ToolContractFingerprint.missing(name)).toMap,
      approvalSubjects = approvalSubjects
    )

  /** 构造该调用当时的审批主体；本测试只关心它是否被冻结，不关心漂移细节。 */
  private def approvalSubject(call: ToolCall): ApprovalSubject =
    ApprovalSubject.of(
      call,
      ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite),
      ToolContractFingerprint.missing(call.name),
      ApprovalPolicyFingerprint.of(ToolPolicyConfig(), ToolName(call.name)),
      AuthorizationFingerprint.of(RunContext())
    )

  def spec = suite("AgentKernel")(
    test("恢复状态只产生纯决定，不执行 I/O") {
      assertTrue(
        AgentKernel.recoveryDecision(RunStatus.Created) == AgentKernel.RecoveryDecision.StartCreated,
        AgentKernel.recoveryDecision(RunStatus.Running) == AgentKernel.RecoveryDecision.RecoverRunning,
        AgentKernel.recoveryDecision(RunStatus.WaitingForApproval) ==
          AgentKernel.RecoveryDecision.RecoverSuspended,
        AgentKernel.recoveryDecision(RunStatus.Completed) == AgentKernel.RecoveryDecision.ReturnCompleted,
        AgentKernel.recoveryDecision(RunStatus.Cancelled) == AgentKernel.RecoveryDecision.ReturnCancelled,
        AgentKernel.recoveryDecision(RunStatus.Failed) == AgentKernel.RecoveryDecision.CloseUncertainModelCall
      )
    },
    test("下一动作与结算用量采用不同的等于上限语义") {
      val limits = RunLimits(maxInputTokens = 10, maxOutputTokens = 10, maxTotalTokens = 20)
      val usage  = UsageSummary(inputTokens = 10, outputTokens = 10)
      assertTrue(
        AgentKernel.validateUsage(limits, usage).isRight,
        AgentKernel.validateUsage(limits, usage, rejectAtLimit = true).left.exists(_.kind == "inputTokens"),
        AgentKernel.validateUsage(limits, usage.copy(outputTokens = 11)).left.exists(_.kind == "outputTokens")
      )
    },
    test("模型响应一次归约状态、预算和事件") {
      val response = ChatResponse(
        AgentMessage.assistant("done"),
        FinishReason.Stop,
        TokenUsage(8, 3, 2, 1)
      )
      val transition = AgentKernel
        .settleModelTurn(
          state(),
          response,
          "provider",
          "model",
          BigDecimal("0.25"),
          false,
          None,
          None,
          now
        )
        .toOption
        .get
      val next = transition.transition.state
      assertTrue(
        next.messages.last == response.message,
        next.usage.modelCalls == 1,
        next.usage.cachedInputTokens == 2,
        next.usage.reasoningOutputTokens == 1,
        next.usage.estimatedCost == BigDecimal("0.25"),
        next.budget.steps == 1,
        transition.transition.events.length == 1
      )
    },
    test("工具批次按 ordinal 确定性归约并推进游标") {
      val first  = DurableToolPlanItem(0, ToolCall("call-a", "a", Json.Obj()))
      val second = DurableToolPlanItem(1, ToolCall("call-b", "b", Json.Obj()))
      val batch  = DurableToolBatch(0, Chunk(first, second))
      val plan   = durablePlan("plan", Chunk(batch))
      val result = AgentKernel
        .commitToolBatch(
          state(plan = Some(plan)),
          batch,
          Chunk(
            second -> ToolResult(Json.Obj("value" -> Json.Str("b"))),
            first  -> ToolResult(Json.Obj("error" -> Json.Str("a")), isError = true)
          ),
          now
        )
        .toOption
        .get
      assertTrue(
        result.state.steps.collect { case AgentStep.ToolStep(_, call, _, _) => call.id } ==
          Chunk("call-a", "call-b"),
        result.state.pendingToolPlan.isEmpty,
        result.state.usage.toolCalls == 2,
        result.state.consecutiveToolFailures == 0,
        result.events.length == 3
      )
    },
    test("工具批次缺失 ordinal 时在 Driver 提交前失败") {
      val first  = DurableToolPlanItem(0, ToolCall("call-a", "a", Json.Obj()))
      val second = DurableToolPlanItem(1, ToolCall("call-b", "b", Json.Obj()))
      val batch  = DurableToolBatch(0, Chunk(first, second))
      val plan   = durablePlan("plan", Chunk(batch))
      val result = AgentKernel.commitToolBatch(
        state(plan = Some(plan)),
        batch,
        Chunk(first -> ToolResult(Json.Obj())),
        now
      )
      assertTrue(result.left.exists(_.message.contains("ordinal")))
    },
    test("完成和暂停迁移保留完整用量并生成单一领域事件") {
      val usage = UsageSummary(
        inputTokens = 8,
        outputTokens = 3,
        cachedInputTokens = 2,
        reasoningOutputTokens = 1
      )
      val current   = state(usage = usage)
      val answer    = AgentMessage.assistant("done")
      val completed = AgentKernel
        .complete(current.copy(messages = current.messages :+ answer), answer, now)
        .toOption
        .get
      val call                   = ToolCall("call-a", "write", Json.Obj())
      val (approval, suspension) = AgentKernel
        .suspend(current, call, ToolRisk.ApprovalWrite, "confirm", None, now)
        .toOption
        .get
      val completedOutcome = AgentKernel.completedOutcome(completed.state).toOption.get
      val suspendedOutcome = AgentKernel.suspendedOutcome(suspension.state).toOption.get
      assertTrue(
        completed.state.status == RunStatus.Completed,
        completed.events.length == 1,
        completedOutcome.usage == TokenUsage(8, 3, 2, 1),
        suspension.state.status == RunStatus.WaitingForApproval,
        suspension.state.pendingApproval.contains(approval),
        suspension.events.length == 2,
        suspension.events.exists {
          case _: AgentEvent.RunSuspended => true
          case _                          => false
        },
        suspendedOutcome.usage == TokenUsage(8, 3, 2, 1)
      )
    },
    test("失败和取消只生成合法终态迁移") {
      val running       = state()
      val failed        = AgentKernel.fail(running, AgentError.Unexpected("private"), now).get
      val budgetFailure = AgentKernel.fail(
        running.copy(messages = running.messages :+ AgentMessage.assistant("settled")),
        AgentError.BudgetExceeded("tokens", 10),
        now
      )
      assertTrue(
        failed.state.status == RunStatus.Failed,
        failed.events.headOption.exists {
          case AgentEvent.RunFailed(_, _, "运行失败", _) => true
          case _                                     => false
        },
        AgentKernel
          .fail(
            running.copy(messages = running.messages :+ AgentMessage.assistant("settled")),
            AgentError.Unexpected("ignored"),
            now
          )
          .isEmpty,
        budgetFailure.exists(_.state.status == RunStatus.BudgetExceeded),
        AgentKernel.cancel(running, now).exists(_.state.status == RunStatus.Cancelled),
        AgentKernel.cancel(state(status = RunStatus.Completed), now).isEmpty,
        AgentKernel
          .fail(state(status = RunStatus.Cancelled), AgentError.Unexpected("late"), now)
          .isEmpty
      )
    },
    test("模型提议的工具调用在计划持久化前受总预算约束") {
      val limits = RunLimits(maxToolCalls = 3)
      assertTrue(
        AgentKernel.validateToolCallBudget(limits, UsageSummary(toolCalls = 1), 2).isRight,
        AgentKernel
          .validateToolCallBudget(limits, UsageSummary(toolCalls = 2), 2)
          .left
          .exists(_.kind == "toolCalls")
      )
    },
    test("Kernel 拒绝从非法源状态生成完成、暂停和工具提交") {
      val call    = ToolCall("call-a", "write", Json.Obj())
      val item    = DurableToolPlanItem(0, call)
      val batch   = DurableToolBatch(0, Chunk(item))
      val done    = state(status = RunStatus.Completed, plan = Some(durablePlan("plan", Chunk(batch))))
      val waiting = state(status = RunStatus.WaitingForApproval)
      assertTrue(
        AgentKernel.complete(done, AgentMessage.assistant("late"), now).isLeft,
        AgentKernel.suspend(done, call, ToolRisk.ApprovalWrite, "late", None, now).isLeft,
        AgentKernel.suspend(waiting, call, ToolRisk.ApprovalWrite, "refresh", None, now).isRight,
        AgentKernel
          .commitToolBatch(done, batch, Chunk(item -> ToolResult(Json.Obj())), now)
          .isLeft,
        AgentKernel.completedOutcome(state()).isLeft,
        AgentKernel.suspendedOutcome(state()).isLeft
      )
    },
    test("模型响应和耐久工具计划必须一一对应") {
      val call     = ToolCall("call-a", "lookup", Json.Obj())
      val response = ChatResponse(
        AgentMessage.assistantToolCalls(Chunk(call)),
        FinishReason.ToolCalls,
        TokenUsage(4, 1)
      )
      val wrongCall = ToolCall("call-b", "lookup", Json.Obj())
      val wrongPlan = durablePlan(
        "wrong",
        Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, wrongCall))))
      )
      val validPlan = durablePlan(
        "valid",
        Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, call))))
      )
      def settle(plan: Option[DurableToolPlan]) =
        AgentKernel.settleModelTurn(
          state(),
          response,
          "provider",
          "model",
          BigDecimal(0),
          false,
          plan,
          None,
          now
        )
      assertTrue(
        settle(None).left.exists(_.message.contains("DurableToolPlan")),
        settle(Some(wrongPlan)).left.exists(_.message.contains("DurableToolPlan")),
        settle(Some(validPlan)).isRight,
        AgentKernel
          .settleModelTurn(
            state(),
            response.copy(message = AgentMessage.assistant("done"), finishReason = FinishReason.Stop),
            "provider",
            "model",
            BigDecimal(0),
            true,
            None,
            None,
            now
          )
          .left
          .exists(_.message.contains("预留"))
      )
    },
    test("已结算成功的工具调用被复用，结果未知的调用必须先经人工确认") {
      val settled = ToolResult(Json.Obj("ok" -> Json.Bool(true)))
      assertTrue(
        AgentKernel.replayDecision(
          Some(ledgerRecord(ToolExecutionStatus.Succeeded, result = Some(settled))),
          mayReplayAfterCrash = false,
          forceRetry = false
        ) == AgentKernel.ReplayDecision.ReuseSettled(settled),
        // Succeeded 却没有结果是持久化损坏，不能当作"这次重新执行一遍"。
        AgentKernel.replayDecision(
          Some(ledgerRecord(ToolExecutionStatus.Succeeded)),
          mayReplayAfterCrash = true,
          forceRetry = true
        ) == AgentKernel.ReplayDecision.MissingPendingWrite,
        AgentKernel.replayDecision(
          Some(ledgerRecord(ToolExecutionStatus.Running)),
          mayReplayAfterCrash = false,
          forceRetry = false
        ) == AgentKernel.ReplayDecision.RequiresConfirmation,
        AgentKernel.replayDecision(
          Some(ledgerRecord(ToolExecutionStatus.Unknown)),
          mayReplayAfterCrash = false,
          forceRetry = false
        ) == AgentKernel.ReplayDecision.RequiresConfirmation,
        // 人工明确批准重放，或工具自身声明可重放，两者任一成立即可执行。
        AgentKernel.replayDecision(
          Some(ledgerRecord(ToolExecutionStatus.Unknown)),
          mayReplayAfterCrash = false,
          forceRetry = true
        ) == AgentKernel.ReplayDecision.Execute(ledgerRecord(ToolExecutionStatus.Unknown)),
        AgentKernel.replayDecision(
          Some(ledgerRecord(ToolExecutionStatus.Running)),
          mayReplayAfterCrash = true,
          forceRetry = false
        ) == AgentKernel.ReplayDecision.Execute(ledgerRecord(ToolExecutionStatus.Running)),
        AgentKernel.replayDecision(
          None,
          mayReplayAfterCrash = true,
          forceRetry = true
        ) == AgentKernel.ReplayDecision.MissingPendingWrite
      )
    },
    test("崩溃恢复动作区分未产生副作用与结果未知") {
      import com.zyblw.agent.tools.ToolRecoveryPolicy
      def action(
          record: Option[ToolExecutionRecord],
          mayReplay: Boolean,
          suspended: Boolean,
          policy: ToolRecoveryPolicy = ToolRecoveryPolicy.NeverReplay
      ) = AgentKernel.toolRecoveryAction(record, mayReplay, policy, suspended)
      val running  = ledgerRecord(ToolExecutionStatus.Running)
      val prepared = ledgerRecord(ToolExecutionStatus.Prepared)
      assertTrue(
        // 暂停边界上尚未产生副作用：保持暂停，重启绝不等价于批准。
        action(None, true, true) == AgentKernel.ToolRecoveryAction.StaySuspended,
        action(Some(prepared), true, true) == AgentKernel.ToolRecoveryAction.StaySuspended,
        action(Some(ledgerRecord(ToolExecutionStatus.Failed)), true, true) ==
          AgentKernel.ToolRecoveryAction.StaySuspended,
        // Running 且不可重放：先把账本收口为 Unknown，再带原因暂停。
        action(Some(running), false, true) ==
          AgentKernel.ToolRecoveryAction.SettleUnknownThenSuspend(
            running,
            AgentKernel.uncertainToolReason(ToolExecutionStatus.Running, ToolRecoveryPolicy.NeverReplay)
          ),
        action(Some(ledgerRecord(ToolExecutionStatus.Unknown)), false, false) ==
          AgentKernel.ToolRecoveryAction.SuspendOnly(
            AgentKernel.uncertainToolReason(ToolExecutionStatus.Unknown, ToolRecoveryPolicy.NeverReplay)
          ),
        // 可重放工具不需要人工介入。
        action(Some(running), true, false) == AgentKernel.ToolRecoveryAction.Continue,
        action(None, true, false) == AgentKernel.ToolRecoveryAction.Continue,
        // RequiresApproval 给出与 NeverReplay 不同的文案：破坏性副作用即使批准过也要再确认。
        AgentKernel
          .uncertainToolReason(ToolExecutionStatus.Running, ToolRecoveryPolicy.RequiresApproval) !=
          AgentKernel.uncertainToolReason(ToolExecutionStatus.Running, ToolRecoveryPolicy.NeverReplay)
      )
    },
    test("工具失败状态由可重放性决定，而不是由错误类别决定") {
      assertTrue(
        AgentKernel.toolFailureStatus(mayReplayAfterCrash = true) == ToolExecutionStatus.Failed,
        AgentKernel.toolFailureStatus(mayReplayAfterCrash = false) == ToolExecutionStatus.Unknown
      )
    },
    test("审批要求是单调的：冻结要求不因策略放宽而消失") {
      import com.zyblw.agent.tools.ApprovalPolicy
      val call = ToolCall("call-a", "write", Json.Obj())
      val item = DurableToolPlanItem(0, call)
      // 空 approvalSubjects：本计划已明确冻结为"无需审批"。
      val frozen = durablePlan("plan", Chunk(DurableToolBatch(0, Chunk(item))))
      // 冻结了 call-a 的审批主体：规划时已判定必须人工授权。
      val required = frozen.copy(approvalSubjects = Map("call-a" -> approvalSubject(call)))
      val relaxed  = ToolPolicyConfig(approvalPolicy = ApprovalPolicy.Never)
      val strict   = ToolPolicyConfig(approvalPolicy = ApprovalPolicy.Always)
      assertTrue(
        // 规划时冻结为需要审批，事后策略放宽也不能取消。
        AgentKernel.requiresApproval(required, relaxed, "call-a", ToolRisk.ReadOnly),
        // 现场策略可以追加新的审批要求。
        AgentKernel.requiresApproval(frozen, strict, "call-a", ToolRisk.ReadOnly),
        // 两侧都不要求时才允许直接执行。
        !AgentKernel.requiresApproval(frozen, relaxed, "call-a", ToolRisk.ReadOnly)
      )
    },
    test("规划元数据只收紧并发，不放宽") {
      import com.zyblw.agent.tools.*
      val policy   = ToolPolicyConfig(approvalPolicy = ApprovalPolicy.RiskBased)
      val parallel = ToolMetadata(
        ToolRisk.ReadOnly,
        SideEffect.None,
        parallelism = ToolParallelism.ConflictAware,
        conflictAccesses = Set(ToolConflictAccess("docs", ToolAccessMode.Read)),
        requiredScopes = Set("docs:read")
      )
      val granted   = Set("docs:read")
      val demoted   = AgentKernel.planningMetadata(parallel, policy, Set.empty)
      val approving = AgentKernel.planningMetadata(
        parallel.copy(risk = ToolRisk.ApprovalWrite, sideEffect = SideEffect.NonIdempotentWrite),
        policy,
        granted
      )
      assertTrue(
        // 声明完整且 scope 齐备：保留真实元数据。
        AgentKernel.planningMetadata(parallel, policy, granted) == parallel,
        // 缺少 scope：降级为串行并清空冲突声明。
        demoted.parallelism == ToolParallelism.SequentialOnly,
        demoted.conflictAccesses.isEmpty,
        // 需要审批的工具永不并行，即使它声明了 ConflictAware。
        approving.parallelism == ToolParallelism.SequentialOnly,
        // 未声明并行的工具保持串行。
        AgentKernel
          .planningMetadata(ToolMetadata(ToolRisk.ReadOnly, SideEffect.None), policy, granted)
          .parallelism == ToolParallelism.SequentialOnly
      )
    },
    test("未结算模型账本按状态选择不同收口方式，Succeeded 残留游标必须显式拒绝") {
      val pending = PendingModelCall(
        ModelRequestId(java.util.UUID.fromString("33333333-3333-3333-3333-333333333333")),
        1,
        "fingerprint",
        CapturePolicy.MetadataOnly,
        "provider",
        "model"
      )
      def closure(status: Option[ModelCallStatus]) =
        AgentKernel.modelCallClosure(pending, status.map(modelRecord(pending, _)))
      assertTrue(
        closure(Some(ModelCallStatus.Dispatched)).isInstanceOf[
          AgentKernel.ModelCallClosure.SettleUnknownAndFail
        ],
        closure(Some(ModelCallStatus.Prepared)).isInstanceOf[
          AgentKernel.ModelCallClosure.SettleUnknownAndFail
        ],
        closure(Some(ModelCallStatus.Unknown)).isInstanceOf[AgentKernel.ModelCallClosure.FailIfRunning],
        closure(Some(ModelCallStatus.Failed)).isInstanceOf[AgentKernel.ModelCallClosure.Fail],
        closure(Some(ModelCallStatus.Succeeded)).isInstanceOf[AgentKernel.ModelCallClosure.Inconsistent],
        closure(None).isInstanceOf[AgentKernel.ModelCallClosure.MissingRecord],
        // 未结算判定必须涵盖三种可能已经发生外部调用的状态。
        AgentKernel.openModelCall(ModelCallStatus.Prepared),
        AgentKernel.openModelCall(ModelCallStatus.Dispatched),
        AgentKernel.openModelCall(ModelCallStatus.Unknown),
        !AgentKernel.openModelCall(ModelCallStatus.Succeeded),
        !AgentKernel.openModelCall(ModelCallStatus.Failed),
        // 中断与路由都无法证明请求未发出，因此一律记 Unknown。
        AgentKernel.interruptedModelCallStatus(interrupted = true, routed = false) ==
          ModelCallStatus.Unknown,
        AgentKernel.interruptedModelCallStatus(interrupted = false, routed = true) ==
          ModelCallStatus.Unknown,
        AgentKernel.interruptedModelCallStatus(interrupted = false, routed = false) ==
          ModelCallStatus.Failed
      )
    },
    test("审批恢复目标要求单调用批次与一致的待审批请求") {
      val call     = ToolCall("call-a", "write", Json.Obj())
      val item     = DurableToolPlanItem(0, call)
      val other    = DurableToolPlanItem(1, ToolCall("call-b", "write", Json.Obj()))
      val approval =
        ApprovalRequest("approval-1", runId, call, ToolRisk.ApprovalWrite, "需要确认", now.toEpochMilli)
      def waiting(plan: Option[DurableToolPlan], pending: Option[ApprovalRequest]) =
        state(status = RunStatus.WaitingForApproval, plan = plan)
          .copy(suspension = pending.map(request => SuspensionRecord.of(request)))
      val single = durablePlan("plan", Chunk(DurableToolBatch(0, Chunk(item))))
      val multi  = durablePlan("plan", Chunk(DurableToolBatch(0, Chunk(item, other))))
      assertTrue(
        AgentKernel.resumeTarget(waiting(Some(single), Some(approval))).map(_.item).contains(item),
        // 多调用批次无法唯一指向"人看到的那个副作用"。
        AgentKernel.resumeTarget(waiting(Some(multi), Some(approval))).isLeft,
        AgentKernel.resumeTarget(waiting(Some(single), None)).isLeft,
        AgentKernel.resumeTarget(waiting(None, Some(approval))).isLeft,
        // Running 状态下没有等待人工的边界。
        AgentKernel
          .resumeTarget(state(plan = Some(single)).copy(suspension = Some(SuspensionRecord.of(approval))))
          .isLeft
      )
    },
    test("耐久审批命令在重放窗口内不以后写覆盖前写") {
      val call     = ToolCall("call-a", "write", Json.Obj())
      val approval =
        ApprovalRequest("approval-1", runId, call, ToolRisk.ApprovalWrite, "需要确认", now.toEpochMilli)
      val approve                                                 = ApprovalDecision.Approve
      val reject                                                  = ApprovalDecision.Reject("不批准")
      def recorded(decision: ApprovalDecision, status: RunStatus) =
        state(status = status)
          .copy(steps = Chunk(AgentStep.ApprovalStep(1, approval, Some(decision), now.toEpochMilli)))
      assertTrue(
        // 尚未记录：正常应用。
        AgentKernel.approvalCommandAction(
          state(status = RunStatus.WaitingForApproval).copy(suspension = Some(SuspensionRecord.of(approval))),
          "approval-1",
          approve
        ) == AgentKernel.ApprovalCommandAction.Apply,
        // 历史决定与命令正文不同：必须拒绝。
        AgentKernel
          .approvalCommandAction(recorded(approve, RunStatus.Running), "approval-1", reject)
          .isInstanceOf[AgentKernel.ApprovalCommandAction.Conflict],
        // 决定已记录且 Run 仍在推进：先前命令在后续 loop 中崩溃，从账本继续恢复。
        AgentKernel.approvalCommandAction(recorded(approve, RunStatus.Running), "approval-1", approve) ==
          AgentKernel.ApprovalCommandAction.ResumeFromLedger,
        // 已产生稳定结果：幂等返回。
        AgentKernel.approvalCommandAction(recorded(approve, RunStatus.Completed), "approval-1", approve) ==
          AgentKernel.ApprovalCommandAction.AlreadyApplied,
        // 命令指向的审批不是当前待处理的那个。
        AgentKernel
          .approvalCommandAction(
            state(status = RunStatus.WaitingForApproval)
              .copy(suspension = Some(SuspensionRecord.of(approval))),
            "approval-2",
            approve
          )
          .isInstanceOf[AgentKernel.ApprovalCommandAction.Conflict]
      )
    },
    test("非审批挂起写入 Suspended 与 RunSuspended，不写入 ApprovalRequest") {
      val record = SuspensionRecord(
        Suspension.Timer,
        now,
        Some(now.plusSeconds(30)),
        SuspensionExpiry.FailRun
      )
      val transition = AgentKernel.suspendTransition(state(), record, now)
      val outcome    = AgentKernel.suspendedOutcome(transition.state).toOption.get
      assertTrue(
        transition.state.status == RunStatus.Suspended,
        transition.state.pendingApproval.isEmpty,
        transition.state.suspension.contains(record),
        transition.events.length == 1,
        transition.events.headOption.exists {
          case AgentEvent.RunSuspended(_, reason, _) => reason.contains("计时器")
          case _                                     => false
        },
        outcome.suspension == record,
        outcome.approval.isEmpty
      )
    },
    test("挂起到期 FailRun 与 ResumeWithDefault 是纯决定") {
      val deadline = now.plusSeconds(1)
      val failing  = state(status = RunStatus.WaitingForApproval).copy(
        suspension = Some(
          SuspensionRecord(
            Suspension.Approval(
              ApprovalRequest(
                "approval-1",
                runId,
                ToolCall("call-a", "write", Json.Obj()),
                ToolRisk.ApprovalWrite,
                "x",
                now.toEpochMilli
              )
            ),
            now,
            Some(deadline),
            SuspensionExpiry.FailRun
          )
        )
      )
      val resumable = state(status = RunStatus.Suspended).copy(
        suspension = Some(
          SuspensionRecord(Suspension.Timer, now, Some(deadline), SuspensionExpiry.ResumeWithDefault)
        )
      )
      val failAction   = AgentKernel.suspensionExpiry(failing, deadline).toOption.get
      val resumeAction = AgentKernel.suspensionExpiry(resumable, deadline).toOption.get
      assertTrue(
        failAction == AgentKernel.SuspensionExpiryAction.FailRun("approval"),
        resumeAction match
          case AgentKernel.SuspensionExpiryAction.Resume(transition) =>
            transition.state.status == RunStatus.Running && transition.state.suspension.isEmpty
          case _ => false
        ,
        AgentKernel.suspensionExpiry(failing, now).isLeft,
        AgentKernel.failureStatus(AgentError.SuspensionExpired(runId, "approval")) == RunStatus.TimedOut
      )
    }
  )

  private def ledgerRecord(
      status: ToolExecutionStatus,
      result: Option[ToolResult] = None,
      attempt: Int = 0
  ): ToolExecutionRecord =
    ToolExecutionRecord(
      runId,
      "batch-1",
      0,
      "call-a",
      "write",
      Some(s"${runId.asString}:call-a"),
      status,
      result,
      attempt,
      now.toEpochMilli
    )

  private def modelRecord(
      pending: PendingModelCall,
      status: ModelCallStatus
  ): ModelCallExecutionRecord =
    ModelCallExecutionRecord(
      runId = runId,
      requestId = pending.requestId,
      attempt = pending.attempt,
      status = status,
      provider = pending.provider,
      model = pending.model,
      capturePolicy = pending.capturePolicy,
      fingerprint = pending.fingerprint,
      messageCount = 1,
      toolCount = 0,
      lineage = ModelCallContextLineage(0L, 0, 0, 0, 0, None, None, Chunk.empty, None, None),
      instructionFingerprint = None,
      canonicalRequest = None,
      usage = None,
      finishReason = None,
      errorCategory = None,
      updatedAtEpochMilli = now.toEpochMilli,
      routeDecision = None
    )
