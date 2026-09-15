package com.zyblw.agent.memory

import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.test.*

object RunStoreSpec extends ZIOSpecDefault:
  private val storeAgent = AgentDefinition(AgentId("store-test"), "Store Test", "持久化测试")

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
      Version.initial,
      storeAgent,
      RuntimeComposition.fingerprint(RuntimeProfile.default, storeAgent, storeAgent.modelSettings),
      ThreadId("store-thread")
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
        store     <- ZIO.service[RunStore]
        runId     <- RunId.random
        sessionId <- SessionId.random
        eventId   <- EventId.random
        created = PersistedAgentEvent(eventId, runId, 0L, AgentEvent.RunCreated(runId, sessionId, 0L), 0L)
        _ <- store.createWithEvents(
          state(runId, sessionId).copy(lastEventSequence = 0L),
          NonEmptyChunk(created)
        )
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
    },
    test("模型调用账本与状态同一 commit 写入，CAS 拒绝错误身份") {
      (for
        store      <- ZIO.service[RunStore]
        runId      <- RunId.random
        sessionId  <- SessionId.random
        requestId  <- ModelRequestId.random
        eventId    <- EventId.random
        preparedId <- EventId.random
        settledId  <- EventId.random
        created = PersistedAgentEvent(eventId, runId, 0L, AgentEvent.RunCreated(runId, sessionId, 0L), 0L)
        initial = state(runId, sessionId).copy(lastEventSequence = 0L)
        _ <- store.createWithEvents(initial, NonEmptyChunk(created))
        fingerprint = "a" * 64
        record      = ModelCallExecutionRecord(
          runId,
          requestId,
          1,
          ModelCallStatus.Dispatched,
          "scripted",
          "default",
          CapturePolicy.MetadataOnly,
          fingerprint,
          1,
          0,
          ModelCallContextLineage(1, 0, 0, 0, 0),
          None,
          None,
          updatedAtEpochMilli = 1L
        )
        preparedEvent = PersistedAgentEvent(
          preparedId,
          runId,
          1L,
          AgentEvent.ModelCallPrepared(
            runId,
            requestId.asString,
            "scripted",
            "default",
            fingerprint,
            "MetadataOnly",
            1,
            0,
            1L
          ),
          1L
        )
        nextState = initial.copy(
          status = RunStatus.Running,
          lastEventSequence = 1L,
          pendingModelCall = Some(
            PendingModelCall(requestId, 1, fingerprint, CapturePolicy.MetadataOnly, "scripted", "default")
          )
        )
        _ <- store.commit(
          Version.initial,
          nextState,
          NonEmptyChunk(preparedEvent),
          Some(ModelCallWrite.Insert(record))
        )
        loaded      <- store.getModelCall(runId, requestId)
        afterInsert <- store.load(runId)
        conflict    <- store
          .commit(
            afterInsert.version,
            afterInsert,
            NonEmptyChunk(preparedEvent),
            Some(ModelCallWrite.Insert(record.copy(provider = "other")))
          )
          .exit
        current             <- store.load(runId)
        eventsAfterConflict <- store.events(runId)
        ledgerAfterConflict <- store.getModelCall(runId, requestId)
        settled             <- store.commit(
          current.version,
          current.copy(
            lastEventSequence = current.lastEventSequence + 1L,
            pendingModelCall = None,
            status = RunStatus.Completed
          ),
          NonEmptyChunk(
            PersistedAgentEvent(
              settledId,
              runId,
              current.lastEventSequence + 1L,
              AgentEvent.ModelCallCompleted(runId, TokenUsage(), 2L),
              2L
            )
          ),
          Some(
            ModelCallWrite.Transition(
              ModelCallStatus.Dispatched,
              1,
              record.copy(status = ModelCallStatus.Succeeded, updatedAtEpochMilli = 2L)
            )
          )
        )
      yield assertTrue(
        loaded.contains(record),
        conflict.isFailure,
        current == afterInsert,
        eventsAfterConflict.map(_.sequence) == Chunk(0L, 1L),
        ledgerAfterConflict.contains(record),
        settled.value == current.version.value + 1L
      )).provide(RunStore.inMemory)
    },
    test("listToolExecutions 跨批次返回全部账本行") {
      (for
        store     <- ZIO.service[RunStore]
        runId     <- RunId.random
        sessionId <- SessionId.random
        eventId   <- EventId.random
        created = PersistedAgentEvent(eventId, runId, 0L, AgentEvent.RunCreated(runId, sessionId, 0L), 0L)
        _ <- store.createWithEvents(
          state(runId, sessionId).copy(lastEventSequence = 0L),
          NonEmptyChunk(created)
        )
        first = ToolExecutionRecord(
          runId,
          "plan-a:0",
          0,
          "call-a",
          "lookup",
          None,
          ToolExecutionStatus.Prepared,
          None,
          0,
          0L
        )
        second = ToolExecutionRecord(
          runId,
          "plan-b:0",
          0,
          "call-b",
          "write",
          None,
          ToolExecutionStatus.Prepared,
          None,
          0,
          1L
        )
        _      <- store.prepareToolExecutions(NonEmptyChunk(first))
        _      <- store.prepareToolExecutions(NonEmptyChunk(second))
        listed <- store.listToolExecutions(runId)
      yield assertTrue(
        listed.map(_.callId) == Chunk("call-a", "call-b"),
        listed.map(_.batchId) == Chunk("plan-a:0", "plan-b:0")
      )).provide(RunStore.inMemory)
    }
  )
