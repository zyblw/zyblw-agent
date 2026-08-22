package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.artifacts.*
import com.zyblw.agent.core.*
import com.zyblw.agent.harness.*
import com.zyblw.agent.memory.RunStore
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.test.*

/** 真实 PostgreSQL 16 验证 Harness CAS、外键、预算账本与恢复对账。 */
object PostgresHarnessStoreIntegrationSpec extends ZIOSpecDefault:
  private val artifact = ArtifactReference(
    ArtifactScope.Session(SessionId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000020"))),
    ArtifactName("reports/postgres-result.pdf"),
    version = 3L,
    mediaType = "application/pdf",
    byteSize = 2048L,
    sha256 = "c" * 64
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

  private val dataSourceLayer: ZLayer[Any, Throwable, DataSource] =
    ZLayer.scoped {
      for
        container <- ZIO.acquireRelease(
          ZIO.attemptBlocking {
            val value =
              PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"))
            value.start()
            value
          }
        )(value => ZIO.attemptBlocking(value.stop()).orDie)
        dataSource <- ZIO.attempt {
          val value = PGSimpleDataSource()
          value.setURL(container.jdbcUrl)
          value.setUser(container.username)
          value.setPassword(container.password)
          value: DataSource
        }
        _ <- AgentPostgresMigrations.migrate(dataSource)
      yield dataSource
    }

  private val storeLayer: ZLayer[Any, Throwable, HarnessStore & RunStore & DataSource] =
    dataSourceLayer >+> PostgresHarnessStore.layer >+> PostgresRunStore.layer

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("PostgresHarnessStore")(
      test("Goal CAS 冲突不得覆盖，Plan 必须挂在 Goal 上") {
        for
          store    <- ZIO.service[HarnessStore]
          goalId   <- GoalId.random
          planId   <- PlanId.random
          todoId   <- TodoId.random
          created  <- store.saveGoal(0L, Goal(goalId, ThreadId("pg-thread"), "整理引用"))
          updated  <- store.saveGoal(created.revision, created.copy(status = GoalStatus.Active))
          conflict <- store.saveGoal(created.revision, created.copy(status = GoalStatus.Completed)).either
          plan     <- store.savePlan(
            0L,
            Plan(planId, goalId, "先检索", Chunk(TodoItem(todoId, "检索原文")))
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
          loaded <- store.getPlan(planId)
        yield assertTrue(
          updated.status == GoalStatus.Active,
          conflict == Left(AgentError.HarnessRevisionConflict("goal", 1L, 2L)),
          loaded.exists(item => item.id == plan.id && item.todos == plan.todos && item.revision == 1L),
          missing == Left(
            AgentError.HarnessNotFound("goal", "00000000-0000-0000-0000-000000000002")
          )
        )
      },
      test("Skill 指纹冲突拒绝覆盖，相同指纹幂等") {
        val first  = SkillDescriptor.make("reader", "1", "inline", SkillTrust.Reviewed, "第一版")
        val second = SkillDescriptor.make("reader", "1", "inline", SkillTrust.Reviewed, "第二版")
        for
          store      <- ZIO.service[HarnessStore]
          saved      <- store.saveSkill(first)
          conflict   <- store.saveSkill(second).either
          idempotent <- store.saveSkill(first)
        yield assertTrue(
          saved.fingerprint == first.fingerprint,
          conflict == Left(AgentError.SkillFingerprintConflict("reader", "1")),
          idempotent.fingerprint == first.fingerprint
        )
      },
      test("Steer 只追加到 Goal，缺失 Goal 拒绝，序号单调") {
        for
          store    <- ZIO.service[HarnessStore]
          goalId   <- GoalId.random
          firstId  <- InteractionId.random
          secondId <- InteractionId.random
          _        <- store.saveGoal(0L, Goal(goalId, ThreadId("pg-thread"), "整理引用", GoalStatus.Active))
          first    <- store.appendInteraction(
            InteractionInput(firstId, goalId, InteractionKind.Steer, "先列出处")
          )
          second <- store.appendInteraction(
            InteractionInput(secondId, goalId, InteractionKind.FollowUp, "再补煎服法")
          )
          thirdId <- InteractionId.random
          third   <- store.appendInteraction(
            InteractionInput(thirdId, goalId, InteractionKind.UserMessage, "最后整理成表格")
          )
          latest  <- store.listInteractions(goalId, beforeSequence = None, limit = 2)
          earlier <- store.listInteractions(goalId, beforeSequence = Some(third.sequence), limit = 2)
          invalid <- store.listInteractions(goalId, beforeSequence = None, limit = 513).exit
          loaded  <- store.getGoal(goalId)
          missing <- store
            .appendInteraction(
              InteractionInput(
                InteractionId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")),
                GoalId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")),
                InteractionKind.UserMessage,
                "孤儿交互"
              )
            )
            .either
        yield assertTrue(
          loaded.exists(_.status == GoalStatus.Active),
          first.sequence == 1L,
          second.sequence == 2L,
          latest.map(_.kind) == Chunk(InteractionKind.FollowUp, InteractionKind.UserMessage),
          latest.map(_.body) == Chunk("再补煎服法", "最后整理成表格"),
          earlier.map(_.kind) == Chunk(InteractionKind.Steer, InteractionKind.FollowUp),
          invalid.isFailure,
          missing == Left(
            AgentError.HarnessNotFound("goal", "00000000-0000-0000-0000-000000000002")
          )
        )
      },
      test("Goal/Plan/Todo 的 ArtifactReference 经 V008 完整往返，且不保存正文") {
        for
          store     <- ZIO.service[HarnessStore]
          goalId    <- GoalId.random
          planId    <- PlanId.random
          todoId    <- TodoId.random
          savedGoal <- store.saveGoal(
            0L,
            Goal(goalId, ThreadId("pg-artifact-thread"), "生成报告", artifacts = Chunk(artifact))
          )
          savedPlan <- store.savePlan(
            0L,
            Plan(
              planId,
              goalId,
              "导出报告",
              Chunk(TodoItem(todoId, "导出 PDF", artifacts = Chunk(artifact))),
              artifacts = Chunk(artifact)
            )
          )
          loadedGoal <- store.getGoal(goalId)
          loadedPlan <- store.getPlan(planId)
        yield assertTrue(
          loadedGoal.contains(savedGoal),
          loadedPlan.contains(savedPlan),
          loadedPlan.exists(_.todos.head.artifacts == Chunk(artifact)),
          !savedPlan.toJson.contains("bytes"),
          !savedPlan.toJson.contains("metadata")
        )
      },
      test("数据库中的 ArtifactReference JSON 损坏时读取 fail-closed") {
        for
          store      <- ZIO.service[HarnessStore]
          dataSource <- ZIO.service[DataSource]
          goalId     <- GoalId.random
          _          <- store.saveGoal(
            0L,
            Goal(goalId, ThreadId("pg-artifact-corruption"), "损坏引用", artifacts = Chunk(artifact))
          )
          _      <- corruptGoalArtifacts(dataSource, goalId)
          loaded <- store.getGoal(goalId).either
        yield assertTrue(
          loaded.left.exists {
            case AgentError.PersistenceFailure(operation, _) => operation == "get harness goal"
            case _                                           => false
          }
        )
      },
      test("两个 Store 实例并发创建同一 Goal 只有一个成功") {
        for
          store  <- ZIO.service[HarnessStore]
          goalId <- GoalId.random
          draft = Goal(goalId, ThreadId("pg-thread"), "并发目标")
          results <- store.saveGoal(0L, draft).either.zipPar(store.saveGoal(0L, draft).either)
          loaded  <- store.getGoal(goalId)
        yield
          val successes = Chunk(results._1, results._2).collect { case Right(goal) => goal }
          val conflicts = Chunk(results._1, results._2).collect {
            case Left(error: AgentError.HarnessRevisionConflict) => error
          }
          assertTrue(successes.length == 1, conflicts.length == 1, loaded.contains(successes.head))
      },
      test("V010 Goal budget 行锁保证并发预留不透支，策略不可覆盖") {
        for
          store      <- ZIO.service[HarnessStore]
          goalId     <- GoalId.random
          first      <- RunId.random
          second     <- RunId.random
          third      <- RunId.random
          _          <- store.saveGoal(0L, Goal(goalId, ThreadId("pg-budget"), "受预算约束的长任务"))
          configured <- store.configureGoalBudget(goalId, budgetPolicy)
          replayed   <- store.configureGoalBudget(goalId, budgetPolicy)
          conflict   <- store
            .configureGoalBudget(goalId, budgetPolicy.copy(maxRuns = 3))
            .either
          reserved   <- store.reserveGoalBudget(goalId, first, runLimits)
          replay     <- store.reserveGoalBudget(goalId, first, runLimits)
          concurrent <- store
            .reserveGoalBudget(goalId, second, runLimits)
            .either
            .zipPar(store.reserveGoalBudget(goalId, third, runLimits).either)
          snapshot  <- store.getGoalBudget(goalId)
          firstPage <- store.listGoalBudgetReservations(
            GoalBudgetReservationStatus.Reserved,
            after = None,
            limit = 1
          )
          secondPage <- store.listGoalBudgetReservations(
            GoalBudgetReservationStatus.Reserved,
            after = firstPage.lastOption.map(GoalBudgetReservationCursor.from),
            limit = 2
          )
        yield
          val outcomes = Chunk(concurrent._1, concurrent._2)
          assertTrue(
            configured == replayed,
            conflict == Left(AgentError.HarnessBudgetPolicyConflict(goalId.asString)),
            reserved == replay,
            outcomes.count(_.isRight) == 1,
            outcomes.count(_.left.exists(_.isInstanceOf[AgentError.HarnessBudgetExceeded])) == 1,
            snapshot.exists(_.reserved.runs == 2L),
            snapshot.exists(_.remainingRuns == 0L),
            firstPage.length == 1,
            secondPage.length == 1,
            (firstPage ++ secondPage).map(_.runId).distinct.length == 2
          )
      },
      test("V010 结算/释放幂等并保留超过 Run 预留的真实 usage") {
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
        for
          store         <- ZIO.service[HarnessStore]
          goalId        <- GoalId.random
          settledRun    <- RunId.random
          releasedRun   <- RunId.random
          exceededRun   <- RunId.random
          _             <- store.saveGoal(0L, Goal(goalId, ThreadId("pg-budget-settle"), "预算结算"))
          _             <- store.configureGoalBudget(goalId, policy)
          _             <- store.reserveGoalBudget(goalId, settledRun, runLimits)
          settled       <- store.settleGoalBudget(goalId, settledRun, usage)
          replay        <- store.settleGoalBudget(goalId, settledRun, usage)
          _             <- store.reserveGoalBudget(goalId, releasedRun, runLimits)
          released      <- store.releaseGoalBudget(goalId, releasedRun)
          releasedAgain <- store.releaseGoalBudget(goalId, releasedRun)
          _             <- store.reserveGoalBudget(goalId, exceededRun, runLimits)
          exceeded      <- store.settleGoalBudget(
            goalId,
            exceededRun,
            usage.copy(modelCalls = 3, estimatedCost = BigDecimal("2.50"))
          )
          snapshot <- store.getGoalBudget(goalId)
        yield assertTrue(
          settled.status == GoalBudgetReservationStatus.Settled,
          settled == replay,
          released.status == GoalBudgetReservationStatus.Released,
          released == releasedAgain,
          exceeded.status == GoalBudgetReservationStatus.Exceeded,
          snapshot.exists(_.reserved == GoalBudgetAmount.zero),
          snapshot.exists(_.consumed.runs == 2L),
          snapshot.exists(_.consumed.modelCalls == 4L),
          snapshot.exists(_.consumed.estimatedCost == BigDecimal("3.25"))
        )
      },
      test("V010 reservation JSON 损坏时读取 fail-closed") {
        for
          store      <- ZIO.service[HarnessStore]
          dataSource <- ZIO.service[DataSource]
          goalId     <- GoalId.random
          runId      <- RunId.random
          _          <- store.saveGoal(0L, Goal(goalId, ThreadId("pg-budget-corruption"), "损坏预算"))
          _          <- store.configureGoalBudget(goalId, budgetPolicy)
          _          <- store.reserveGoalBudget(goalId, runId, runLimits)
          _          <- corruptBudgetLimits(dataSource, runId)
          loaded     <- store.getGoalBudgetReservation(goalId, runId).either
        yield assertTrue(
          loaded.left.exists {
            case AgentError.PersistenceFailure(operation, _) =>
              operation == "get harness budget reservation"
            case _ => false
          }
        )
      },
      test("V010 Reconciler 从耐久 Run 终态结算遗留 Reserved") {
        for
          harness  <- ZIO.service[HarnessStore]
          runs     <- ZIO.service[RunStore]
          goalId   <- GoalId.random
          runId    <- createRun(runs)
          _        <- harness.saveGoal(0L, Goal(goalId, ThreadId("pg-budget-reconcile"), "崩溃恢复对账"))
          _        <- harness.configureGoalBudget(goalId, budgetPolicy)
          _        <- harness.reserveGoalBudget(goalId, runId, runLimits)
          _        <- completeRun(runs, runId)
          before   <- harness.getGoalBudgetReservation(goalId, runId)
          worker   <- HarnessBudgetReconciler.make(harness, runs)
          report   <- worker.reconcileNext(8)
          after    <- harness.getGoalBudgetReservation(goalId, runId)
          snapshot <- harness.getGoalBudget(goalId)
        yield assertTrue(
          before.exists(_.status == GoalBudgetReservationStatus.Reserved),
          report.scanned == 1,
          report.settled == 1,
          report.failedRunIds.isEmpty,
          after.exists(_.status == GoalBudgetReservationStatus.Settled),
          snapshot.exists(_.reserved == GoalBudgetAmount.zero),
          snapshot.exists(_.consumed.runs == 1L)
        )
      }
    ).provideLayer(storeLayer) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@
      TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)

  private def corruptGoalArtifacts(dataSource: DataSource, goalId: GoalId): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          "UPDATE harness_goals SET artifacts_json = '[{}]'::jsonb WHERE goal_id = ?::uuid"
        )
        try
          statement.setObject(1, java.util.UUID.fromString(goalId.asString))
          if statement.executeUpdate() != 1 then throw IllegalStateException("未找到 Harness Goal")
        finally statement.close()
      finally connection.close()
    }

  private def corruptBudgetLimits(dataSource: DataSource, runId: RunId): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          "UPDATE harness_budget_reservations SET limits_json = '{}'::jsonb WHERE run_id = ?::uuid"
        )
        try
          statement.setObject(1, java.util.UUID.fromString(runId.asString))
          if statement.executeUpdate() != 1 then throw IllegalStateException("未找到 Harness budget reservation")
        finally statement.close()
      finally connection.close()
    }

  private def createRun(store: RunStore): UIO[RunId] =
    (for
      runId   <- RunId.random
      session <- SessionId.random
      eventId <- EventId.random
      now     <- Clock.instant
      state = AgentState(
        runId,
        session,
        AgentId("postgres-budget-reconciler"),
        RunStatus.Created,
        Chunk(AgentMessage.user("test")),
        Chunk.empty,
        UsageSummary(),
        BudgetState(runLimits, UsageSummary(), 0),
        None,
        now,
        now,
        Version.initial,
        lastEventSequence = 0L
      )
      created = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, session, now.toEpochMilli),
        now.toEpochMilli
      )
      _ <- store.createWithEvents(state, NonEmptyChunk(created))
    yield runId).orDie

  private def completeRun(store: RunStore, runId: RunId): IO[StoreError, Unit] =
    val usage = UsageSummary(
      modelCalls = 1,
      toolCalls = 1,
      inputTokens = 60,
      outputTokens = 20,
      estimatedCost = BigDecimal("0.75")
    )
    for
      current <- store.load(runId)
      eventId <- EventId.random
      now     <- Clock.instant
      answer = AgentMessage.assistant("done")
      event  = PersistedAgentEvent(
        eventId,
        runId,
        current.lastEventSequence + 1L,
        AgentEvent.RunCompleted(runId, answer, usage, now.toEpochMilli),
        now.toEpochMilli
      )
      completed = current.copy(
        status = RunStatus.Completed,
        messages = current.messages :+ answer,
        usage = usage,
        budget = current.budget.copy(consumed = usage),
        lastEventSequence = event.sequence,
        updatedAt = now
      )
      _ <- store.commit(current.version, completed, NonEmptyChunk(event))
    yield ()
