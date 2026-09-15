package com.zyblw.agent.inspection

import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{RunCommandPayload, RunCommandRecord, RunCommandStatus, RunStore}
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.test.*

object RunTrajectorySpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
  private val sessionId = SessionId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
  private val startedAt = Instant.parse("2026-09-15T00:00:00Z")
  private val agent     = AgentDefinition(AgentId("trajectory-agent"), "Trajectory Agent", "回答问题")

  private val state = AgentState(
    runId = runId,
    sessionId = sessionId,
    agentId = AgentId("trajectory-agent"),
    status = RunStatus.Failed,
    messages = Chunk(AgentMessage.user("secret-prompt")),
    steps = Chunk.empty,
    usage = UsageSummary(),
    budget = BudgetState(RunLimits(), UsageSummary(), 0),
    suspension = None,
    createdAt = startedAt,
    updatedAt = startedAt,
    version = Version.initial,
    definition = agent,
    composition = RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings),
    threadId = ThreadId("trajectory-thread"),
    lastEventSequence = 0L
  )

  private val events = Chunk(
    PersistedAgentEvent(
      EventId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
      runId,
      0L,
      AgentEvent.RunCreated(runId, sessionId, startedAt.toEpochMilli),
      startedAt.toEpochMilli
    )
  )

  def spec = suite("RunTrajectory")(
    test("工具账本暴露 attempt 与 Unknown，不携带结果正文") {
      val tool = ToolExecutionRecord(
        runId,
        "plan-a:0",
        0,
        "call-1",
        "lookup",
        Some("call-1"),
        ToolExecutionStatus.Unknown,
        Some(ToolResult.Inline(zio.json.ast.Json.Str("secret-tool-body"), isError = true)),
        attempt = 2,
        updatedAtEpochMilli = 7L
      )
      val trajectory = RunTrajectory.build(state, events, toolLedger = Chunk(tool))
      val encoded    = trajectory.toJson
      assertTrue(
        trajectory.toolLedger.headOption.exists(row =>
          row.status == "Unknown" && row.attempt == 2 && row.isError.contains(true) && !row.externalized
        ),
        !encoded.contains("secret-tool-body"),
        !encoded.contains("secret-prompt")
      )
    },
    test("模型账本投影保留 sectionDecisions 与 digest 前缀，不含 Canonical 正文") {
      val record = ModelCallExecutionRecord(
        runId,
        ModelRequestId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")),
        1,
        ModelCallStatus.Succeeded,
        "scripted",
        "test",
        CapturePolicy.Replayable,
        "ab" * 32,
        2,
        1,
        ModelCallContextLineage(
          estimatedTokens = 40,
          droppedMessages = 1,
          truncatedToolResults = 0,
          droppedMemories = 0,
          droppedRetrieval = 0,
          summaryCoveredMessages = Some(3),
          summarySourceDigest = Some("d" * 64),
          sectionDecisions = Chunk("goal:rendered:aaaa", "policy:unchanged:bbbb")
        ),
        None,
        Some(CanonicalModelRequest(Chunk(AgentMessage.user("hidden-body")), Chunk.empty, ModelSettings())),
        updatedAtEpochMilli = 1L
      )
      val trajectory = RunTrajectory.build(state, events, modelCalls = Chunk(record))
      val encoded    = trajectory.toJson
      assertTrue(
        trajectory.modelCalls.headOption.exists { row =>
          row.lineage.sectionDecisions == Chunk("goal:rendered:aaaa", "policy:unchanged:bbbb") &&
          row.lineage.summarySourceDigestPrefix.contains("d" * 16)
        },
        !encoded.contains("hidden-body")
      )
    },
    test("控制面 DeadLetter 与数据面时间线同包可见") {
      val command = RunCommandRecord(
        CommandId(UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")),
        runId,
        RunCommandPayload.Recover,
        "recover:0",
        RunCommandStatus.DeadLetter,
        0,
        Instant.EPOCH,
        3,
        1,
        Some("composition-incompatible"),
        startedAt,
        startedAt
      )
      val trajectory = RunTrajectory.build(state, events, commands = Chunk(command))
      assertTrue(
        trajectory.commands.headOption.exists { row =>
          row.status == "DeadLetter" && row.manualRetryCount == 1 &&
          row.lastFailure.contains("composition-incompatible") && row.payloadKind == "Recover"
        },
        trajectory.timeline.headOption.exists(_.eventType == "RunCreated")
      )
    },
    test("从 RunStore 装配的轨迹含工具账本且事故包消费同一投影") {
      val tool = ToolExecutionRecord(
        runId,
        "plan-a:0",
        0,
        "call-1",
        "lookup",
        None,
        ToolExecutionStatus.Prepared,
        None,
        0,
        1L
      )
      (for
        store <- ZIO.service[RunStore]
        _     <- store.createWithEvents(state, NonEmptyChunk(events.head))
        _     <- store.prepareToolExecutions(NonEmptyChunk(tool))
        built <- RunTrajectoryExporter.fromStore(store, runId)
        pack = IncidentPack.build(built, generatedAtEpochMilli = startedAt.toEpochMilli)
      yield assertTrue(
        built.toolLedger.map(_.status) == Chunk("Prepared"),
        pack.toolLedger == built.toolLedger,
        pack.inspection.runId == runId
      )).provide(RunStore.inMemory)
    }
  )
