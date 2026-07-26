package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.test.*

object RunStoreSpec extends ZIOSpecDefault:
  private def state(runId: RunId, sessionId: SessionId): AgentState =
    val limits = RunLimits()
    AgentState(
      runId,
      sessionId,
      AgentId("store-test"),
      RunStatus.Created,
      Chunk.empty,
      Chunk.empty,
      UsageSummary(),
      BudgetState(limits, UsageSummary(), 0),
      None,
      Instant.EPOCH,
      Instant.EPOCH,
      Version.initial
    )

  def spec = suite("InMemoryRunStore")(
    test("乐观锁版本单调递增并拒绝陈旧写入") {
      (for
        store     <- ZIO.service[RunStore]
        runId     <- RunId.random
        sessionId <- SessionId.random
        eventId   <- EventId.random
        event   = PersistedAgentEvent(eventId, runId, 0L, AgentEvent.RunCreated(runId, sessionId, 0L), 0L)
        initial = state(runId, sessionId).copy(lastEventSequence = 0L)
        _     <- store.createWithEvents(initial, NonEmptyChunk(event))
        next  <- store.save(Version.initial, initial.copy(status = RunStatus.Running))
        stale <- store.save(Version.initial, initial).exit
      yield assertTrue(next.value == 1L, stale.isFailure)).provide(RunStore.inMemory)
    },
    test("相同 eventId 重复追加保持幂等") {
      (for
        store     <- ZIO.service[RunStore]
        runId     <- RunId.random
        sessionId <- SessionId.random
        eventId   <- EventId.random
        event = PersistedAgentEvent(eventId, runId, 0L, AgentEvent.RunCreated(runId, sessionId, 0L), 0L)
        _ <- store
          .createWithEvents(state(runId, sessionId).copy(lastEventSequence = 0L), NonEmptyChunk(event))
        _      <- store.appendEvents(runId, NonEmptyChunk(event))
        _      <- store.appendEvents(runId, NonEmptyChunk(event))
        events <- store.events(runId)
      yield assertTrue(events.length == 1)).provide(RunStore.inMemory)
    },
    test("创建时拒绝错误 runId 或非零起始 sequence") {
      (for
        store      <- ZIO.service[RunStore]
        runId      <- RunId.random
        otherRunId <- RunId.random
        sessionId  <- SessionId.random
        eventId    <- EventId.random
        invalid = PersistedAgentEvent(
          eventId,
          otherRunId,
          2L,
          AgentEvent.RunCreated(otherRunId, sessionId, 0L),
          0L
        )
        exit <- store
          .createWithEvents(state(runId, sessionId).copy(lastEventSequence = 2L), NonEmptyChunk(invalid))
          .exit
        absent <- store.load(runId).exit
      yield assertTrue(exit.isFailure, absent.isFailure)).provide(RunStore.inMemory)
    },
    test("批量 Prepared 可幂等恢复，但拒绝把重复 callId 嫁接到其他批次") {
      (for
        store <- ZIO.service[RunStore]
        runId <- RunId.random
        first = ToolExecutionRecord(
          runId,
          "plan-a:0",
          0,
          "stable-call",
          "lookup",
          Some("stable-call"),
          ToolExecutionStatus.Prepared,
          None,
          0,
          0L
        )
        _         <- store.prepareToolExecutions(NonEmptyChunk(first))
        _         <- store.prepareToolExecutions(NonEmptyChunk(first))
        preserved <- store.getToolExecution(runId, "stable-call")
        conflict  <- store
          .prepareToolExecutions(NonEmptyChunk(first.copy(batchId = "plan-b:0", ordinal = 1)))
          .exit
        after <- store.getToolExecution(runId, "stable-call")
      yield assertTrue(
        preserved.contains(first),
        conflict.isFailure,
        after.contains(first)
      )).provide(RunStore.inMemory)
    }
  )
