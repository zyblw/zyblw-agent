package com.zyblw.agent.harness

import com.zyblw.agent.artifacts.*
import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.test.*

/** Goal/Plan CAS、Plan 非权限、Active 不启动 Runtime。 */
object HarnessStoreSpec extends ZIOSpecDefault:
  private val artifact = ArtifactReference(
    ArtifactScope.Session(SessionId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000010"))),
    ArtifactName("reports/result.pdf"),
    version = 2L,
    mediaType = "application/pdf",
    byteSize = 42L,
    sha256 = "a" * 64
  )

  private val runLimits = RunLimits(
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

  private val budgetPolicy = GoalBudgetPolicy(
    maxRuns = 2,
    maxModelCalls = 4,
    maxToolCalls = 4,
    maxInputTokens = 200,
    maxOutputTokens = 100,
    maxTotalTokens = 240,
    maxEstimatedCost = Some(BigDecimal("4.00"))
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("HarnessStore")(
    test("创建与 CAS 更新 Goal，冲突不得覆盖") {
      (for
        store <- ZIO.service[HarnessStore]
        id    <- GoalId.random
        draft = Goal(id, ThreadId("thread-a"), "完成病历摘要")
        created  <- store.saveGoal(0L, draft)
        updated  <- store.saveGoal(created.revision, created.copy(status = GoalStatus.Active))
        conflict <- store.saveGoal(created.revision, created.copy(status = GoalStatus.Completed)).either
        loaded   <- store.getGoal(id)
      yield assertTrue(
        created.revision == 1L,
        updated.status == GoalStatus.Active,
        updated.revision == 2L,
        conflict == Left(AgentError.HarnessRevisionConflict("goal", 1L, 2L)),
        loaded.contains(updated)
      )).provideLayer(HarnessStore.inMemory)
    },
    test("Goal Active 只写入任务状态，装配中没有 AgentRuntime") {
      (for
        store <- ZIO.service[HarnessStore]
        id    <- GoalId.random
        saved <- store.saveGoal(0L, Goal(id, ThreadId("thread-a"), "整理引用", GoalStatus.Active))
      yield assertTrue(saved.status == GoalStatus.Active)).provideLayer(HarnessStore.inMemory)
    },
    test("Plan 必须挂在已有 Goal 上，且不能扩大工具白名单") {
      val agent = AgentDefinition(AgentId("harness-agent"), "Harness", "回答", allowedTools = Set("echo"))
      (for
        store  <- ZIO.service[HarnessStore]
        goalId <- GoalId.random
        planId <- PlanId.random
        todoId <- TodoId.random
        _      <- store.saveGoal(0L, Goal(goalId, ThreadId("thread-a"), "目标"))
        plan   <- store.savePlan(
          0L,
          Plan(planId, goalId, "先检索再总结", Chunk(TodoItem(todoId, "检索经典")))
        )
        missing <- store
          .savePlan(
            0L,
            Plan(
              PlanId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")),
              GoalId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")),
              "孤儿计划"
            )
          )
          .either
        json = plan.toJson
      yield assertTrue(
        Plan.effectiveTools(agent, plan) == Set("echo"),
        !json.contains("allowedTools"),
        !json.contains("scope"),
        missing == Left(
          AgentError.HarnessNotFound("goal", "00000000-0000-0000-0000-000000000002")
        )
      )).provideLayer(HarnessStore.inMemory)
    },
    test("Goal/Plan/Todo 只保存有界 ArtifactReference，旧 JSON 缺少字段时读取为空") {
      val legacyTodo =
        s"""{"id":"00000000-0000-0000-0000-000000000011","title":"旧任务","status":"Pending"}"""
      val legacyGoal =
        s"""{"id":"00000000-0000-0000-0000-000000000012","threadId":"thread-a","objective":"旧目标","status":"Draft","runId":null,"revision":0,"updatedAtEpochMilli":0}"""
      val legacyPlan =
        s"""{"id":"00000000-0000-0000-0000-000000000013","goalId":"00000000-0000-0000-0000-000000000012","summary":"旧计划","todos":[],"revision":0,"updatedAtEpochMilli":0}"""
      (for
        store  <- ZIO.service[HarnessStore]
        goalId <- GoalId.random
        planId <- PlanId.random
        todoId <- TodoId.random
        _      <- store.saveGoal(
          0L,
          Goal(goalId, ThreadId("thread-artifact"), "生成报告", artifacts = Chunk(artifact))
        )
        plan <- store.savePlan(
          0L,
          Plan(
            planId,
            goalId,
            "写报告",
            Chunk(TodoItem(todoId, "导出 PDF", artifacts = Chunk(artifact))),
            artifacts = Chunk(artifact)
          )
        )
        loadedGoal <- store.getGoal(goalId)
        loadedPlan <- store.getPlan(planId)
      yield assertTrue(
        loadedGoal.exists(_.artifacts == Chunk(artifact)),
        loadedPlan.exists(_.artifacts == Chunk(artifact)),
        loadedPlan.exists(_.todos.head.artifacts == Chunk(artifact)),
        legacyTodo.fromJson[TodoItem].exists(_.artifacts.isEmpty),
        legacyGoal.fromJson[Goal].exists(_.artifacts.isEmpty),
        legacyPlan.fromJson[Plan].exists(_.artifacts.isEmpty),
        !plan.toJson.contains("bytes"),
        !plan.toJson.contains("metadata")
      )).provideLayer(HarnessStore.inMemory)
    },
    test("Goal budget 配置与预留幂等，并发 Run 不能共同透支") {
      (for
        store      <- ZIO.service[HarnessStore]
        goalId     <- GoalId.random
        first      <- RunId.random
        second     <- RunId.random
        third      <- RunId.random
        _          <- store.saveGoal(0L, Goal(goalId, ThreadId("budget-thread"), "受预算约束的长任务"))
        configured <- store.configureGoalBudget(goalId, budgetPolicy)
        repeated   <- store.configureGoalBudget(goalId, budgetPolicy)
        conflict   <- store
          .configureGoalBudget(goalId, budgetPolicy.copy(maxRuns = 3))
          .either
        firstReservation <- store.reserveGoalBudget(goalId, first, runLimits)
        firstReplay      <- store.reserveGoalBudget(goalId, first, runLimits)
        concurrent       <- store
          .reserveGoalBudget(goalId, second, runLimits)
          .either
          .zipPar(store.reserveGoalBudget(goalId, third, runLimits).either)
        snapshot <- store.getGoalBudget(goalId)
      yield
        val outcomes = Chunk(concurrent._1, concurrent._2)
        assertTrue(
          configured == repeated,
          conflict == Left(AgentError.HarnessBudgetPolicyConflict(goalId.asString)),
          firstReservation == firstReplay,
          outcomes.count(_.isRight) == 1,
          outcomes.count(_.left.exists(_.isInstanceOf[AgentError.HarnessBudgetExceeded])) == 1,
          snapshot.exists(_.reserved.runs == 2L),
          snapshot.exists(_.remainingRuns == 0L)
        )
      ).provideLayer(HarnessStore.inMemory)
    },
    test("Goal budget 结算记录真实 usage，释放可重放，超额事实不会被回滚隐藏") {
      val policy = budgetPolicy.copy(
        maxRuns = 3,
        maxModelCalls = 6,
        maxToolCalls = 6,
        maxInputTokens = 300,
        maxOutputTokens = 150,
        maxTotalTokens = 360,
        maxEstimatedCost = Some(BigDecimal("6.00"))
      )
      val usage = UsageSummary(
        modelCalls = 1,
        toolCalls = 1,
        inputTokens = 60,
        outputTokens = 20,
        cachedInputTokens = 10,
        reasoningOutputTokens = 5,
        estimatedCost = BigDecimal("0.75")
      )
      val exceededUsage = usage.copy(modelCalls = 3, estimatedCost = BigDecimal("2.50"))
      (for
        store         <- ZIO.service[HarnessStore]
        goalId        <- GoalId.random
        settledRun    <- RunId.random
        releasedRun   <- RunId.random
        exceededRun   <- RunId.random
        _             <- store.saveGoal(0L, Goal(goalId, ThreadId("budget-settle"), "结算长任务"))
        _             <- store.configureGoalBudget(goalId, policy)
        _             <- store.reserveGoalBudget(goalId, settledRun, runLimits)
        settled       <- store.settleGoalBudget(goalId, settledRun, usage)
        replay        <- store.settleGoalBudget(goalId, settledRun, usage)
        _             <- store.reserveGoalBudget(goalId, releasedRun, runLimits)
        released      <- store.releaseGoalBudget(goalId, releasedRun)
        releaseReplay <- store.releaseGoalBudget(goalId, releasedRun)
        _             <- store.reserveGoalBudget(goalId, exceededRun, runLimits)
        exceeded      <- store.settleGoalBudget(goalId, exceededRun, exceededUsage)
        snapshot      <- store.getGoalBudget(goalId)
      yield assertTrue(
        settled.status == GoalBudgetReservationStatus.Settled,
        settled == replay,
        released.status == GoalBudgetReservationStatus.Released,
        released == releaseReplay,
        exceeded.status == GoalBudgetReservationStatus.Exceeded,
        snapshot.exists(_.reserved == GoalBudgetAmount.zero),
        snapshot.exists(_.consumed.runs == 2L),
        snapshot.exists(_.consumed.modelCalls == 4L),
        snapshot.exists(_.consumed.estimatedCost == BigDecimal("3.25"))
      )).provideLayer(HarnessStore.inMemory)
    },
    test("启用费用总限时拒绝无费用额度的 Run，损坏 usage fail-closed 且保留预留") {
      (for
        store     <- ZIO.service[HarnessStore]
        goalId    <- GoalId.random
        noCost    <- RunId.random
        invalid   <- RunId.random
        _         <- store.saveGoal(0L, Goal(goalId, ThreadId("budget-validation"), "校验预算"))
        _         <- store.configureGoalBudget(goalId, budgetPolicy)
        unbounded <- store
          .reserveGoalBudget(goalId, noCost, runLimits.copy(maxEstimatedCost = None))
          .either
        _        <- store.reserveGoalBudget(goalId, invalid, runLimits)
        rejected <- store
          .settleGoalBudget(goalId, invalid, UsageSummary(modelCalls = -1))
          .either
        reservation <- store.getGoalBudgetReservation(goalId, invalid)
      yield assertTrue(
        unbounded.left.exists {
          case error: AgentError.HarnessBudgetExceeded => error.dimension == "estimatedCost"
          case _                                       => false
        },
        rejected.left.exists(_.isInstanceOf[AgentError.HarnessBudgetUsageInvalid]),
        reservation.exists(_.status == GoalBudgetReservationStatus.Reserved)
      )).provideLayer(HarnessStore.inMemory)
    }
  )
