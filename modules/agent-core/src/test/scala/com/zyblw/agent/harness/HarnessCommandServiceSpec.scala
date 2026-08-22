package com.zyblw.agent.harness

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.tools.{ToolPolicyConfig, ToolPolicySource}
import zio.*
import zio.test.*

object HarnessCommandServiceSpec extends ZIOSpecDefault:
  private val limits = RunLimits(
    maxSteps = 4,
    maxModelCalls = 2,
    maxToolCalls = 2,
    maxRepeatedActions = 2,
    maxInputTokens = 100,
    maxOutputTokens = 50,
    maxTotalTokens = 120,
    maxEstimatedCost = Some(BigDecimal("2.00")),
    maxDuration = 1.minute
  )

  private val policy = GoalBudgetPolicy(
    maxRuns = 1,
    maxModelCalls = 2,
    maxToolCalls = 2,
    maxInputTokens = 100,
    maxOutputTokens = 50,
    maxTotalTokens = 120,
    maxEstimatedCost = Some(BigDecimal("2.00"))
  )

  private val persistence =
    ZLayer.make[RunStore & RunCommandStore & HarnessStore & RunSubmissionStore](
      RunStore.inMemory,
      RunCommandStore.inMemory,
      HarnessStore.inMemory,
      RunSubmissionStore.inMemoryWithHarness
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("HarnessCommandService")(
    test("预算预留与异步 Start 使用同一稳定 Run，HTTP 重放不重复占用") {
      (for
        runs        <- ZIO.service[RunStore]
        harness     <- ZIO.service[HarnessStore]
        submissions <- ZIO.service[RunSubmissionStore]
        goalId      <- GoalId.random
        _           <- harness.saveGoal(0L, Goal(goalId, ThreadId("harness-start"), "异步长任务"))
        _           <- harness.configureGoalBudget(goalId, policy)
        service = HarnessCommandServiceLive(
          submissions,
          ToolPolicySource.static(ToolPolicyConfig.secureDefault)
        )
        agent   = AgentDefinition(AgentId("harness-start"), "Harness Start", "完成任务")
        request = RunRequest(ThreadId("harness-start"), AgentMessage.user("开始"), limits = limits)
        first       <- service.submitStart(goalId, agent, request, "stable-request")
        replay      <- service.submitStart(goalId, agent, request, "stable-request")
        state       <- runs.load(first.runId)
        reservation <- harness.getGoalBudgetReservation(goalId, first.runId)
        snapshot    <- harness.getGoalBudget(goalId)
      yield assertTrue(
        first == replay,
        state.status == RunStatus.Created,
        state.budget.limits == limits,
        reservation.exists(_.status == GoalBudgetReservationStatus.Reserved),
        reservation.exists(_.limits == state.budget.limits),
        snapshot.exists(_.reserved.runs == 1L)
      )).provideLayer(persistence)
    },
    test("额度不足不创建孤儿 Run，同一幂等键不能换绑另一个 Goal") {
      (for
        runs        <- ZIO.service[RunStore]
        harness     <- ZIO.service[HarnessStore]
        submissions <- ZIO.service[RunSubmissionStore]
        firstGoal   <- GoalId.random
        secondGoal  <- GoalId.random
        _           <- harness.saveGoal(0L, Goal(firstGoal, ThreadId("first-goal"), "第一个 Goal"))
        _           <- harness.saveGoal(0L, Goal(secondGoal, ThreadId("second-goal"), "第二个 Goal"))
        _           <- harness.configureGoalBudget(firstGoal, policy)
        _           <- harness.configureGoalBudget(secondGoal, policy)
        service = HarnessCommandServiceLive(
          submissions,
          ToolPolicySource.static(ToolPolicyConfig.secureDefault)
        )
        agent   = AgentDefinition(AgentId("harness-admission"), "Harness Admission", "完成任务")
        request = RunRequest(ThreadId("harness-admission"), AgentMessage.user("开始"), limits = limits)
        accepted       <- service.submitStart(firstGoal, agent, request, "same-key")
        rebound        <- service.submitStart(secondGoal, agent, request, "same-key").either
        exhausted      <- service.submitStart(firstGoal, agent, request, "new-key").either
        firstSnapshot  <- harness.getGoalBudget(firstGoal)
        secondSnapshot <- harness.getGoalBudget(secondGoal)
        acceptedState  <- runs.load(accepted.runId)
      yield assertTrue(
        rebound.left.exists(_.isInstanceOf[AgentError.RunSubmissionConflict]),
        exhausted.left.exists(_.isInstanceOf[AgentError.HarnessBudgetExceeded]),
        firstSnapshot.exists(_.reserved.runs == 1L),
        secondSnapshot.exists(_.reserved == GoalBudgetAmount.zero),
        acceptedState.status == RunStatus.Created
      )).provideLayer(persistence)
    }
  )
