package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 对同一组 RunStore 不变量分别运行内存与 PostgreSQL Adapter。
  *
  * 覆盖 `agent_runs`、`agent_events`、`tool_executions` 与 `model_call_executions`，含组合漂移事件耐久读回。
  */
object RunStoreConformanceSpec extends ZIOSpecDefault:
  private val postgres: ZLayer[Any, Throwable, RunStore] =
    ZLayer.scoped {
      for
        container <- ZIO.acquireRelease(
          ZIO.attemptBlocking {
            val value =
              PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:18-alpine"))
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

  private val conformanceAgent =
    AgentDefinition(AgentId("store-conformance"), "Store Conformance", "存储一致性测试")

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
      Version.initial,
      conformanceAgent,
      RuntimeComposition
        .fingerprint(RuntimeProfile.default, conformanceAgent, conformanceAgent.modelSettings),
      ThreadId("store-conformance-thread")
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
            updatedAtEpochMilli = 1L,
            routeDecision = Some(
              RouteDecision(
                ModelRequirement(),
                ModelRef("contract", "model"),
                "v0",
                "a" * 64,
                Chunk(ModelCandidateDecision(ModelRef("contract", "model"), Chunk.empty)),
                false,
                1,
                1,
                "b" * 64,
                None,
                None
              )
            )
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
          changedDecision = RouteDecision(
            ModelRequirement(),
            ModelRef("contract", "model"),
            "v1",
            "a" * 64,
            Chunk(ModelCandidateDecision(ModelRef("contract", "model"), Chunk.empty)),
            false,
            1,
            1,
            "b" * 64,
            None,
            None
          )
          routeConflict <- store
            .commit(
              before.version,
              before,
              NonEmptyChunk(prepared),
              Some(
                ModelCallWrite.Transition(
                  ModelCallStatus.Dispatched,
                  1,
                  record.copy(status = ModelCallStatus.Succeeded, routeDecision = Some(changedDecision))
                )
              )
            )
            .exit
          after     <- store.load(runId)
          events    <- store.events(runId)
          ledger    <- store.getModelCall(runId, requestId)
          settledId <- EventId.random
          settledEvent = PersistedAgentEvent(
            settledId,
            runId,
            after.lastEventSequence + 1,
            AgentEvent.ModelCallCompleted(runId, TokenUsage(1, 1), 2L),
            2L
          )
          _ <- store.commit(
            after.version,
            after.copy(lastEventSequence = settledEvent.sequence, pendingModelCall = None),
            NonEmptyChunk(settledEvent),
            Some(
              ModelCallWrite.Transition(
                ModelCallStatus.Dispatched,
                1,
                record.copy(status = ModelCallStatus.Succeeded, usage = Some(TokenUsage(1, 1)))
              )
            )
          )
          settled <- store.getModelCall(runId, requestId)
        yield assertTrue(
          conflict.isFailure,
          routeConflict.isFailure,
          settled.exists(r =>
            r.status == ModelCallStatus.Succeeded && r.routeDecision == record.routeDecision
          ),
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
          listed    <- store.listToolExecutions(runId)
        yield assertTrue(
          orphanRejected.isFailure,
          identityConflict.isFailure,
          statusConflict.isFailure,
          preserved.contains(tool),
          listed == Chunk(tool)
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
      },
      test("组合漂移事件按原样耐久，可供轨迹与事故包读回") {
        import com.zyblw.agent.composition.{CapabilityKind, CompositionDriftField}
        for
          store <- ZIO.service[RunStore]
          runId = stableRunId("composition-drift")
          sessionId <- SessionId.random
          createdId = stableEventId("composition-drift-created")
          driftId   = stableEventId("composition-drift-detected")
          created   = PersistedAgentEvent(
            createdId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, sessionId, 0L),
            0L
          )
          initial = state(runId, sessionId).copy(lastEventSequence = 0L)
          _ <- store.createWithEvents(initial, NonEmptyChunk(created))
          field = CompositionDriftField("allowedTools", CapabilityKind.Tool, securityRelevant = true)
          drift = PersistedAgentEvent(
            driftId,
            runId,
            1L,
            AgentEvent.CompositionDriftDetected(runId, "incompatible", Chunk(field), 1L),
            1L
          )
          _      <- store.commit(Version.initial, initial.copy(lastEventSequence = 1L), NonEmptyChunk(drift))
          events <- store.events(runId)
        yield assertTrue(
          events.collect {
            case PersistedAgentEvent(_, _, _, AgentEvent.CompositionDriftDetected(_, kind, changed, _), _) =>
              kind -> changed.map(_.field)
          } == Chunk("incompatible" -> Chunk("allowedTools"))
        )
      }
    ).provideLayerShared(layer)

  def spec =
    suite("RunStore conformance")(
      contract("in-memory", RunStore.inMemory),
      contract("postgres", postgres) @@ PostgresIntegrationAspect.enabled @@
        TestAspect.timeout(2.minutes)
    )
