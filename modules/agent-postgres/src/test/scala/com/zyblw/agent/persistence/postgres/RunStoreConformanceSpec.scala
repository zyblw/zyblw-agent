package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 对同一组 RunStore 不变量分别运行内存与 PostgreSQL Adapter。 */
object RunStoreConformanceSpec extends ZIOSpecDefault:
  private val postgres: ZLayer[Any, Throwable, RunStore] =
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
        persistence <- PostgresAgentPersistence.migratedLayer.build.provideSome[Scope](
          ZLayer.succeed[DataSource](dataSource)
        )
      yield persistence.get[RunStore]
    }

  private def state(runId: RunId, sessionId: SessionId): AgentState =
    AgentState(
      runId,
      sessionId,
      AgentId("store-conformance"),
      RunStatus.Created,
      Chunk.empty,
      Chunk.empty,
      UsageSummary(),
      BudgetState(RunLimits(), UsageSummary(), 0),
      None,
      Instant.EPOCH,
      Instant.EPOCH,
      Version.initial
    )

  private def stableRunId(name: String): RunId =
    RunId(UUID.nameUUIDFromBytes(s"run:$name".getBytes(StandardCharsets.UTF_8)))

  private def stableEventId(name: String): EventId =
    EventId(UUID.nameUUIDFromBytes(s"event:$name".getBytes(StandardCharsets.UTF_8)))

  private def contract(name: String, layer: ZLayer[Any, Throwable, RunStore]) =
    suite(name)(
      test("ModelCall 冲突使状态、事件与账本一起回滚") {
        for
          store <- ZIO.service[RunStore]
          runId = stableRunId("model-call-atomicity")
          sessionId <- SessionId.random
          requestId <- ModelRequestId.random
          createdId  = stableEventId("model-call-created")
          preparedId = stableEventId("model-call-prepared")
          created    = PersistedAgentEvent(
            createdId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, sessionId, 0L),
            0L
          )
          initial = state(runId, sessionId).copy(lastEventSequence = 0L)
          _ <- store.createWithEvents(initial, NonEmptyChunk(created))
          fingerprint = "c" * 64
          record      = ModelCallExecutionRecord(
            runId,
            requestId,
            1,
            ModelCallStatus.Dispatched,
            "contract",
            "model",
            CapturePolicy.MetadataOnly,
            fingerprint,
            1,
            0,
            ModelCallContextLineage(1, 0, 0, 0, 0),
            None,
            None,
            updatedAtEpochMilli = 1L
          )
          prepared = PersistedAgentEvent(
            preparedId,
            runId,
            1L,
            AgentEvent.ModelCallPrepared(
              runId,
              requestId.asString,
              "contract",
              "model",
              fingerprint,
              "MetadataOnly",
              1,
              0,
              1L
            ),
            1L
          )
          _ <- store.commit(
            Version.initial,
            initial.copy(
              status = RunStatus.Running,
              lastEventSequence = 1L,
              pendingModelCall = Some(
                PendingModelCall(
                  requestId,
                  1,
                  fingerprint,
                  CapturePolicy.MetadataOnly,
                  "contract",
                  "model"
                )
              )
            ),
            NonEmptyChunk(prepared),
            Some(ModelCallWrite.Insert(record))
          )
          before   <- store.load(runId)
          conflict <- store
            .commit(
              before.version,
              before,
              NonEmptyChunk(prepared),
              Some(ModelCallWrite.Insert(record.copy(provider = "other")))
            )
            .exit
          after  <- store.load(runId)
          events <- store.events(runId)
          ledger <- store.getModelCall(runId, requestId)
        yield assertTrue(
          conflict.isFailure,
          after == before,
          events.map(_.sequence) == Chunk(0L, 1L),
          ledger.contains(record)
        )
      },
      test("事件分页、幂等追加和级联删除保持一致") {
        for
          store <- ZIO.service[RunStore]
          runId = stableRunId("events-pagination-delete")
          sessionId <- SessionId.random
          createdId = stableEventId("pagination-created")
          firstId   = stableEventId("pagination-first")
          secondId  = stableEventId("pagination-second")
          created   = PersistedAgentEvent(
            createdId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, sessionId, 0L),
            0L
          )
          initial = state(runId, sessionId).copy(lastEventSequence = 0L)
          _ <- store.createWithEvents(initial, NonEmptyChunk(created))
          first  = PersistedAgentEvent(firstId, runId, 1L, AgentEvent.StepStarted(runId, 1, 1L), 1L)
          second = PersistedAgentEvent(secondId, runId, 2L, AgentEvent.StepStarted(runId, 2, 2L), 2L)
          _ <- store.commit(
            Version.initial,
            initial.copy(lastEventSequence = 2L),
            NonEmptyChunk(first, second)
          )
          _     <- store.appendEvents(runId, NonEmptyChunk(created))
          page1 <- store.events(runId, -1L, 2)
          page2 <- store.events(runId, page1.last.sequence, 2)
          _     <- store.requestCancellation(runId)
          tool = ToolExecutionRecord(
            runId,
            "contract:0",
            0,
            "tool-call",
            "lookup",
            Some("tool-call"),
            ToolExecutionStatus.Prepared,
            None,
            0,
            2L
          )
          _             <- store.prepareToolExecutions(NonEmptyChunk(tool))
          _             <- store.delete(runId)
          missingState  <- store.load(runId).exit
          missingEvents <- store.events(runId)
          missingCancel <- store.cancellationRequested(runId).exit
          missingTool   <- store.getToolExecution(runId, tool.callId)
        yield assertTrue(
          page1.map(_.sequence) == Chunk(0L, 1L),
          page2.map(_.sequence) == Chunk(2L),
          missingState.isFailure,
          missingEvents.isEmpty,
          missingCancel.isFailure,
          missingTool.isEmpty
        )
      },
      test("工具账本幂等写入并拒绝身份或状态漂移") {
        for
          store <- ZIO.service[RunStore]
          runId       = stableRunId("tool-ledger")
          orphanRunId = stableRunId("tool-ledger-orphan")
          orphan      = ToolExecutionRecord(
            orphanRunId,
            "orphan:0",
            0,
            "orphan-call",
            "lookup",
            Some("orphan-call"),
            ToolExecutionStatus.Prepared,
            None,
            0,
            0L
          )
          orphanRejected <- store.prepareToolExecutions(NonEmptyChunk(orphan)).exit
          sessionId      <- SessionId.random
          createdId = stableEventId("tool-ledger-created")
          created   = PersistedAgentEvent(
            createdId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, sessionId, 0L),
            0L
          )
          _ <- store.createWithEvents(
            state(runId, sessionId).copy(lastEventSequence = 0L),
            NonEmptyChunk(created)
          )
          tool = ToolExecutionRecord(
            runId,
            "contract:0",
            0,
            "stable-call",
            "lookup",
            Some("stable-call"),
            ToolExecutionStatus.Prepared,
            None,
            0,
            0L
          )
          _                <- store.prepareToolExecutions(NonEmptyChunk(tool))
          _                <- store.prepareToolExecutions(NonEmptyChunk(tool))
          identityConflict <- store
            .prepareToolExecutions(NonEmptyChunk(tool.copy(batchId = "other:0")))
            .exit
          statusConflict <- store
            .transitionToolExecution(
              ToolExecutionStatus.Running,
              1,
              tool.copy(status = ToolExecutionStatus.Succeeded, attempt = 2)
            )
            .exit
          preserved <- store.getToolExecution(runId, tool.callId)
        yield assertTrue(
          orphanRejected.isFailure,
          identityConflict.isFailure,
          statusConflict.isFailure,
          preserved.contains(tool)
        )
      },
      test("EventId 跨 Run 或 payload 复用必须失败且不污染另一 Run") {
        for
          store <- ZIO.service[RunStore]
          firstRunId  = stableRunId("event-identity-first")
          secondRunId = stableRunId("event-identity-second")
          firstSession  <- SessionId.random
          secondSession <- SessionId.random
          firstCreated = PersistedAgentEvent(
            stableEventId("event-identity-first-created"),
            firstRunId,
            0L,
            AgentEvent.RunCreated(firstRunId, firstSession, 0L),
            0L
          )
          secondCreated = PersistedAgentEvent(
            stableEventId("event-identity-second-created"),
            secondRunId,
            0L,
            AgentEvent.RunCreated(secondRunId, secondSession, 0L),
            0L
          )
          _ <- store.createWithEvents(
            state(firstRunId, firstSession).copy(lastEventSequence = 0L),
            NonEmptyChunk(firstCreated)
          )
          _ <- store.createWithEvents(
            state(secondRunId, secondSession).copy(lastEventSequence = 0L),
            NonEmptyChunk(secondCreated)
          )
          sharedId = stableEventId("event-identity-shared")
          first = PersistedAgentEvent(sharedId, firstRunId, 1L, AgentEvent.StepStarted(firstRunId, 1, 1L), 1L)
          reused = PersistedAgentEvent(
            sharedId,
            secondRunId,
            0L,
            AgentEvent.StepStarted(secondRunId, 2, 2L),
            2L
          )
          _ <- store.commit(
            Version.initial,
            state(firstRunId, firstSession).copy(lastEventSequence = 1L),
            NonEmptyChunk(first)
          )
          conflict     <- store.appendEvents(secondRunId, NonEmptyChunk(reused)).exit
          firstEvents  <- store.events(firstRunId)
          secondEvents <- store.events(secondRunId)
        yield assertTrue(
          conflict.isFailure,
          firstEvents.map(_.eventId) == Chunk(firstCreated.eventId, sharedId),
          secondEvents.map(_.eventId) == Chunk(secondCreated.eventId)
        )
      },
      test("save 不能漂移事件游标，commit 必须紧接已提交 sequence") {
        for
          store <- ZIO.service[RunStore]
          runId = stableRunId("event-sequence-continuity")
          sessionId <- SessionId.random
          created = PersistedAgentEvent(
            stableEventId("event-sequence-created"),
            runId,
            0L,
            AgentEvent.RunCreated(runId, sessionId, 0L),
            0L
          )
          initial = state(runId, sessionId).copy(lastEventSequence = 0L)
          _         <- store.createWithEvents(initial, NonEmptyChunk(created))
          saveDrift <- store.save(Version.initial, initial.copy(lastEventSequence = 9L)).exit
          gap = PersistedAgentEvent(
            stableEventId("event-sequence-gap"),
            runId,
            2L,
            AgentEvent.StepStarted(runId, 2, 2L),
            2L
          )
          gapCommit <- store
            .commit(Version.initial, initial.copy(lastEventSequence = 2L), NonEmptyChunk(gap))
            .exit
          appendAhead        <- store.appendEvents(runId, NonEmptyChunk(gap.copy(sequence = 1L))).exit
          appendNegative     <- store.appendEvents(runId, NonEmptyChunk(gap.copy(sequence = -1L))).exit
          invalidCursor      <- store.events(runId, afterSequence = -2L, limit = 1).exit
          invalidLimit       <- store.events(runId, afterSequence = -1L, limit = 4097).exit
          afterFailure       <- store.load(runId)
          eventsAfterFailure <- store.events(runId)
          next = PersistedAgentEvent(
            stableEventId("event-sequence-next"),
            runId,
            1L,
            AgentEvent.StepStarted(runId, 1, 1L),
            1L
          )
          committedVersion <- store.commit(
            Version.initial,
            initial.copy(lastEventSequence = 1L),
            NonEmptyChunk(next)
          )
          finalEvents <- store.events(runId)
        yield assertTrue(
          saveDrift.isFailure,
          gapCommit.isFailure,
          appendAhead.isFailure,
          appendNegative.isFailure,
          invalidCursor.isFailure,
          invalidLimit.isFailure,
          afterFailure == initial,
          eventsAfterFailure.map(_.sequence) == Chunk(0L),
          committedVersion == Version(1L),
          finalEvents.map(_.sequence) == Chunk(0L, 1L)
        )
      },
      test("同一 version 与前序 sequence 的并发 commit 只能有一个胜者") {
        for
          store <- ZIO.service[RunStore]
          runId = stableRunId("event-sequence-concurrent-cas")
          sessionId <- SessionId.random
          created = PersistedAgentEvent(
            stableEventId("event-sequence-concurrent-created"),
            runId,
            0L,
            AgentEvent.RunCreated(runId, sessionId, 0L),
            0L
          )
          initial = state(runId, sessionId).copy(lastEventSequence = 0L)
          _ <- store.createWithEvents(initial, NonEmptyChunk(created))
          first = PersistedAgentEvent(
            stableEventId("event-sequence-concurrent-first"),
            runId,
            1L,
            AgentEvent.StepStarted(runId, 1, 1L),
            1L
          )
          second = PersistedAgentEvent(
            stableEventId("event-sequence-concurrent-second"),
            runId,
            1L,
            AgentEvent.StepStarted(runId, 2, 2L),
            2L
          )
          outcomes <- ZIO.collectAllPar(
            Chunk(
              store.commit(Version.initial, initial.copy(lastEventSequence = 1L), NonEmptyChunk(first)).exit,
              store.commit(Version.initial, initial.copy(lastEventSequence = 1L), NonEmptyChunk(second)).exit
            )
          )
          saved  <- store.load(runId)
          events <- store.events(runId)
        yield assertTrue(
          outcomes.count(_.isSuccess) == 1,
          outcomes.count(_.isFailure) == 1,
          saved.version == Version(1L),
          saved.lastEventSequence == 1L,
          events.map(_.sequence) == Chunk(0L, 1L),
          Set(first.eventId, second.eventId).contains(events.last.eventId)
        )
      }
    ).provideLayerShared(layer)

  def spec =
    suite("RunStore conformance")(
      contract("in-memory", RunStore.inMemory),
      contract("postgres", postgres) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@
        TestAspect.timeout(2.minutes)
    )
