package com.zyblw.agent.harness

import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{ModelCallWrite, RunStore}
import java.time.Instant
import zio.*
import zio.test.*

object HarnessBudgetReconcilerSpec extends ZIOSpecDefault:
  private val reconcilerAgent =
    AgentDefinition(AgentId("budget-reconciler"), "Budget Reconciler", "预算对账")

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

  private val usage = UsageSummary(
    modelCalls = 1,
    toolCalls = 1,
    inputTokens = 60,
    outputTokens = 20,
    estimatedCost = BigDecimal("0.75")
  )

  private val layer = ZLayer.make[RunStore & HarnessStore](RunStore.inMemory, HarnessStore.inMemory)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("HarnessBudgetReconciler")(
    test("终态仍有 Unknown 模型调用时保留 Goal 预留") {
      (for
        runs      <- ZIO.service[RunStore]
        harness   <- ZIO.service[HarnessStore]
        goal      <- GoalId.random
        id        <- createRun(runs, RunStatus.Completed)
        state     <- runs.load(id)
        requestId <- ModelRequestId.random
        eventId   <- EventId.random
        record = ModelCallExecutionRecord(
          id,
          requestId,
          1,
          ModelCallStatus.Unknown,
          "p",
          "m",
          CapturePolicy.MetadataOnly,
          "a" * 64,
          1,
          0,
          ModelCallContextLineage(1, 0, 0, 0, 0),
          None,
          None,
          updatedAtEpochMilli = 1L
        )
        event = PersistedAgentEvent(
          eventId,
          id,
          state.lastEventSequence + 1,
          AgentEvent.ModelCallUnknown(id, requestId.asString, 1L),
          1L
        )
        _ <- runs.commit(
          state.version,
          state.copy(lastEventSequence = event.sequence),
          NonEmptyChunk(event),
          Some(ModelCallWrite.Insert(record))
        )
        _ <- harness.saveGoal(0L, Goal(goal, ThreadId("unknown"), "unknown cost"))
        _ <- harness
          .configureGoalBudget(goal, GoalBudgetPolicy(1, 2, 2, 100, 50, 120, Some(BigDecimal("2.00"))))
        _           <- harness.reserveGoalBudget(goal, id, limits)
        reconciler  <- HarnessBudgetReconciler.make(harness, runs)
        report      <- reconciler.reconcileNext()
        reservation <- harness.getGoalBudgetReservation(goal, id)
      yield assertTrue(
        report.pending == 1,
        report.settled == 0,
        reservation.exists(_.status == GoalBudgetReservationStatus.Reserved)
      )).provideLayer(layer)
    },
    test("只结算终态，缺失/非终态保持 Reserved，游标到末尾后安全回绕") {
      (for
        runs       <- ZIO.service[RunStore]
        harness    <- ZIO.service[HarnessStore]
        goalId     <- GoalId.random
        terminal   <- createRun(runs, RunStatus.Completed)
        running    <- createRun(runs, RunStatus.Running)
        missingRun <- RunId.random
        _          <- harness.saveGoal(0L, Goal(goalId, ThreadId("reconcile"), "预算恢复对账"))
        _          <- harness.configureGoalBudget(
          goalId,
          GoalBudgetPolicy(3, 6, 6, 300, 150, 360, Some(BigDecimal("6.00")))
        )
        _                   <- harness.reserveGoalBudget(goalId, terminal, limits)
        _                   <- harness.reserveGoalBudget(goalId, running, limits)
        _                   <- harness.reserveGoalBudget(goalId, missingRun, limits)
        reconciler          <- HarnessBudgetReconciler.make(harness, runs)
        first               <- reconciler.reconcileNext(10)
        terminalReservation <- harness.getGoalBudgetReservation(goalId, terminal)
        runningReservation  <- harness.getGoalBudgetReservation(goalId, running)
        missingReservation  <- harness.getGoalBudgetReservation(goalId, missingRun)
        _                   <- completeRun(runs, running)
        second              <- reconciler.reconcileNext(10)
        runningSettled      <- harness.getGoalBudgetReservation(goalId, running)
        snapshot            <- harness.getGoalBudget(goalId)
      yield assertTrue(
        first.scanned == 3,
        first.settled == 1,
        first.pending == 1,
        first.failedRunIds == Chunk(missingRun),
        terminalReservation.exists(_.status == GoalBudgetReservationStatus.Settled),
        runningReservation.exists(_.status == GoalBudgetReservationStatus.Reserved),
        missingReservation.exists(_.status == GoalBudgetReservationStatus.Reserved),
        second.wrapped,
        second.scanned == 2,
        second.settled == 1,
        second.failedRunIds == Chunk(missingRun),
        runningSettled.exists(_.status == GoalBudgetReservationStatus.Settled),
        snapshot.exists(_.consumed.runs == 2L),
        snapshot.exists(_.reserved.runs == 1L)
      )).provideLayer(layer)
    }
  )

  private def createRun(store: RunStore, status: RunStatus): UIO[RunId] =
    (for
      runId   <- RunId.random
      session <- SessionId.random
      eventId <- EventId.random
      now     <- Clock.instant
      state = AgentState(
        runId,
        session,
        AgentId("budget-reconciler"),
        RunStatus.Created,
        Chunk(AgentMessage.user("test")),
        Chunk.empty,
        UsageSummary(),
        BudgetState(limits, UsageSummary(), 0),
        None,
        now,
        now,
        Version.initial,
        reconcilerAgent,
        RuntimeComposition
          .fingerprint(RuntimeProfile.default, reconcilerAgent, reconcilerAgent.modelSettings),
        ThreadId("budget-reconciler-thread"),
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
      _ <-
        if RunStatus.isTerminal(status) then completeRun(store, runId)
        else store.save(Version.initial, state.copy(status = status)).unit
    yield runId).orDie

  private def completeRun(store: RunStore, runId: RunId): IO[StoreError, Unit] =
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
        updatedAt = Instant.ofEpochMilli(now.toEpochMilli)
      )
      _ <- store.commit(current.version, completed, NonEmptyChunk(event))
    yield ()
