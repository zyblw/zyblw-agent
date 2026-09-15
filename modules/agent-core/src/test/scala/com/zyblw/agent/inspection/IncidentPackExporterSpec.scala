package com.zyblw.agent.inspection

import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunStore
import java.time.Instant
import java.util.UUID
import zio.*
import zio.test.*

object IncidentPackExporterSpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
  private val sessionId = SessionId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
  private val startedAt = Instant.parse("2026-08-22T00:00:00Z")

  private val exportAgent = AgentDefinition(AgentId("incident-export"), "Incident Export", "导出事故包")

  private val state = AgentState(
    runId = runId,
    sessionId = sessionId,
    agentId = AgentId("incident-export"),
    status = RunStatus.Failed,
    messages = Chunk(AgentMessage.user("secret-prompt")),
    steps = Chunk.empty,
    usage = UsageSummary(),
    budget = BudgetState(RunLimits(), UsageSummary(), 0),
    suspension = None,
    createdAt = startedAt,
    updatedAt = startedAt,
    version = Version.initial,
    definition = exportAgent,
    composition =
      RuntimeComposition.fingerprint(RuntimeProfile.default, exportAgent, exportAgent.modelSettings),
    threadId = ThreadId("incident-export-thread"),
    lastEventSequence = 0L
  )

  private val created = PersistedAgentEvent(
    EventId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
    runId,
    0L,
    AgentEvent.RunCreated(runId, sessionId, startedAt.toEpochMilli),
    startedAt.toEpochMilli
  )

  def spec = suite("IncidentPackExporter")(
    test("从 RunStore 导出的事故包不含 prompt") {
      (for
        store   <- ZIO.service[RunStore]
        _       <- store.createWithEvents(state, NonEmptyChunk(created))
        encoded <- IncidentPackExporter.encodeFromStore(
          store,
          runId,
          startedAt.toEpochMilli,
          Chunk("secret-prompt")
        )
      yield assertTrue(
        encoded.exists(json => json.contains(runId.asString) && !json.contains("secret-prompt"))
      )).provide(RunStore.inMemory)
    }
  )
