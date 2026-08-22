package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.harness.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 对同一组 Goal budget 状态机不变量分别运行内存与 PostgreSQL HarnessStore Adapter。 */
object HarnessBudgetStoreConformanceSpec extends ZIOSpecDefault:
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
    maxRuns = 3,
    maxModelCalls = 6,
    maxToolCalls = 6,
    maxInputTokens = 300,
    maxOutputTokens = 150,
    maxTotalTokens = 360,
    maxEstimatedCost = Some(BigDecimal("6.00"))
  )

  private val usage = UsageSummary(
    modelCalls = 1,
    toolCalls = 1,
    inputTokens = 60,
    outputTokens = 20,
    cachedInputTokens = 10,
    reasoningOutputTokens = 5,
    estimatedCost = BigDecimal("0.75")
  )

  private val postgres: ZLayer[Any, Throwable, HarnessStore] =
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
      yield PostgresHarnessStore(dataSource)
    }

  private def createBudget(store: HarnessStore, thread: String): UIO[GoalId] =
    (for
      goalId = stableGoalId(thread)
      _ <- store.saveGoal(0L, Goal(goalId, ThreadId(thread), "Harness budget conformance"))
      _ <- store.configureGoalBudget(goalId, policy)
    yield goalId).orDie

  private def stableGoalId(name: String): GoalId =
    GoalId(UUID.nameUUIDFromBytes(s"goal:$name".getBytes(StandardCharsets.UTF_8)))

  private def stableRunId(name: String): RunId =
    RunId(UUID.nameUUIDFromBytes(s"run:$name".getBytes(StandardCharsets.UTF_8)))

  private def contract(name: String, layer: ZLayer[Any, Throwable, HarnessStore]) =
    suite(name)(
      test("策略不可覆盖、稳定 Run 预留幂等且并发不能透支") {
        for
          store  <- ZIO.service[HarnessStore]
          goalId <- createBudget(store, s"$name-concurrent")
          first  = stableRunId(s"$name-concurrent-first")
          second = stableRunId(s"$name-concurrent-second")
          third  = stableRunId(s"$name-concurrent-third")
          fourth = stableRunId(s"$name-concurrent-fourth")
          replay   <- store.configureGoalBudget(goalId, policy)
          conflict <- store.configureGoalBudget(goalId, policy.copy(maxRuns = 4)).either
          reserved <- store.reserveGoalBudget(goalId, first, limits)
          repeated <- store.reserveGoalBudget(goalId, first, limits)
          outcomes <- ZIO.collectAllPar(
            Chunk(
              store.reserveGoalBudget(goalId, second, limits).either,
              store.reserveGoalBudget(goalId, third, limits).either,
              store.reserveGoalBudget(goalId, fourth, limits).either
            )
          )
          snapshot <- store.getGoalBudget(goalId)
          _        <- ZIO.foreachDiscard(Chunk(first, second, third, fourth))(runId =>
            store.releaseGoalBudget(goalId, runId).ignore
          )
        yield assertTrue(
          replay.policy == policy,
          conflict == Left(AgentError.HarnessBudgetPolicyConflict(goalId.asString)),
          reserved == repeated,
          outcomes.count(_.isRight) == 2,
          outcomes.count(_.left.exists(_.isInstanceOf[AgentError.HarnessBudgetExceeded])) == 1,
          snapshot.exists(_.reserved.runs == 3L),
          snapshot.exists(_.remainingRuns == 0L)
        )
      },
      test("结算与释放只允许合法幂等重放，失败不改变账本") {
        for
          store  <- ZIO.service[HarnessStore]
          goalId <- createBudget(store, s"$name-transitions")
          settledRun  = stableRunId(s"$name-transitions-settled")
          releasedRun = stableRunId(s"$name-transitions-released")
          invalidRun  = stableRunId(s"$name-transitions-invalid")
          _              <- store.reserveGoalBudget(goalId, settledRun, limits)
          settled        <- store.settleGoalBudget(goalId, settledRun, usage)
          replay         <- store.settleGoalBudget(goalId, settledRun, usage)
          changed        <- store.settleGoalBudget(goalId, settledRun, usage.copy(modelCalls = 2)).either
          releaseDone    <- store.releaseGoalBudget(goalId, settledRun).either
          _              <- store.reserveGoalBudget(goalId, releasedRun, limits)
          released       <- store.releaseGoalBudget(goalId, releasedRun)
          releasedAgain  <- store.releaseGoalBudget(goalId, releasedRun)
          settleReleased <- store.settleGoalBudget(goalId, releasedRun, usage).either
          _              <- store.reserveGoalBudget(goalId, invalidRun, limits)
          invalid        <- store.settleGoalBudget(goalId, invalidRun, usage.copy(modelCalls = -1)).either
          preserved      <- store.getGoalBudgetReservation(goalId, invalidRun)
          snapshot       <- store.getGoalBudget(goalId)
          _              <- store.releaseGoalBudget(goalId, invalidRun)
        yield assertTrue(
          settled == replay,
          changed.left.exists(_.isInstanceOf[AgentError.HarnessBudgetConflict]),
          releaseDone.left.exists(_.isInstanceOf[AgentError.HarnessBudgetConflict]),
          released == releasedAgain,
          settleReleased.left.exists(_.isInstanceOf[AgentError.HarnessBudgetConflict]),
          invalid.left.exists(_.isInstanceOf[AgentError.HarnessBudgetUsageInvalid]),
          preserved.exists(_.status == GoalBudgetReservationStatus.Reserved),
          snapshot.exists(_.reserved.runs == 1L),
          snapshot.exists(_.consumed.runs == 1L)
        )
      },
      test("状态过滤与排他复合游标稳定分页，RunId 不能跨 Goal 换绑") {
        for
          store      <- ZIO.service[HarnessStore]
          firstGoal  <- createBudget(store, s"$name-page-first")
          secondGoal <- createBudget(store, s"$name-page-second")
          first  = stableRunId(s"$name-page-first")
          second = stableRunId(s"$name-page-second")
          third  = stableRunId(s"$name-page-third")
          _       <- store.reserveGoalBudget(firstGoal, first, limits)
          _       <- store.reserveGoalBudget(firstGoal, second, limits)
          _       <- store.reserveGoalBudget(firstGoal, third, limits)
          rebound <- store.reserveGoalBudget(secondGoal, first, limits).either
          _       <- store.releaseGoalBudget(firstGoal, first)
          page1   <- store.listGoalBudgetReservations(GoalBudgetReservationStatus.Reserved, None, 1)
          page2   <- store.listGoalBudgetReservations(
            GoalBudgetReservationStatus.Reserved,
            page1.lastOption.map(GoalBudgetReservationCursor.from),
            1
          )
          released <- store.listGoalBudgetReservations(GoalBudgetReservationStatus.Released, None, 8)
          invalid  <- store.listGoalBudgetReservations(GoalBudgetReservationStatus.Reserved, None, 513).either
        yield assertTrue(
          rebound.left.exists(_.isInstanceOf[AgentError.HarnessBudgetConflict]),
          page1.length == 1,
          page2.length == 1,
          page1.head.runId != page2.head.runId,
          Set(page1.head.runId, page2.head.runId) == Set(second, third),
          released.exists(_.runId == first),
          invalid.isLeft
        )
      }
    ).provideLayerShared(layer) @@ TestAspect.sequential

  def spec =
    suite("Harness budget store conformance")(
      contract("in-memory", HarnessStore.inMemory) @@ TestAspect.timeout(10.seconds),
      contract("postgres", postgres) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@
        TestAspect.timeout(2.minutes)
    )
