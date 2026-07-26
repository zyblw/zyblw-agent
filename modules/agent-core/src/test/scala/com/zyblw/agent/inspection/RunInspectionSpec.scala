package com.zyblw.agent.inspection

import com.zyblw.agent.core.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object RunInspectionSpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.randomUUID())
  private val sessionId = SessionId(UUID.randomUUID())
  private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

  private val definition = AgentDefinition(
    AgentId("inspection-agent"),
    "Inspection Agent",
    "legacy",
    instructionSet = Some(
      InstructionSet(
        Chunk(InstructionBlock("agent.core", InstructionAuthority.System, "safe instruction", "1"))
      )
    )
  )

  private def event(sequence: Long, value: AgentEvent): PersistedAgentEvent =
    PersistedAgentEvent(
      EventId(UUID.randomUUID()),
      runId,
      sequence,
      value,
      startedAt.toEpochMilli + sequence * 10L
    )

  private val events = Chunk(
    event(0L, AgentEvent.RunCreated(runId, sessionId, startedAt.toEpochMilli)),
    event(1L, AgentEvent.RunStarted(runId, startedAt.toEpochMilli + 10L)),
    event(
      2L,
      AgentEvent.ToolCallRequested(
        runId,
        ToolCall("call-1", "search_articles", Json.Obj("secret" -> Json.Str("must-not-leak"))),
        startedAt.toEpochMilli + 20L
      )
    ),
    event(
      3L,
      AgentEvent.ToolExecutionCompleted(
        runId,
        "call-1",
        ToolResult(Json.Obj("private" -> Json.Str("result-secret"))),
        startedAt.toEpochMilli + 30L
      )
    ),
    event(
      4L,
      AgentEvent.RunCompleted(
        runId,
        AgentMessage.assistant("private-answer"),
        UsageSummary(modelCalls = 1, toolCalls = 1, inputTokens = 20L, outputTokens = 5L),
        startedAt.toEpochMilli + 40L
      )
    )
  )

  private val usage = UsageSummary(modelCalls = 1, toolCalls = 1, inputTokens = 20L, outputTokens = 5L)
  private val state = AgentState(
    runId = runId,
    sessionId = sessionId,
    agentId = definition.id,
    status = RunStatus.Completed,
    messages = Chunk(AgentMessage.user("private-input"), AgentMessage.assistant("private-answer")),
    steps = Chunk.empty,
    usage = usage,
    budget = BudgetState(RunLimits(), usage, 1),
    pendingApproval = None,
    createdAt = startedAt,
    updatedAt = startedAt.plusMillis(40L),
    version = Version(4L),
    threadId = Some(ThreadId("inspection-thread")),
    definition = Some(definition),
    lastEventSequence = 4L
  )

  def spec = suite("RunInspection")(
    test("完整时间线不泄漏正文、工具参数、结果或指令") {
      val inspection = RunInspection.build(state, events)
      val encoded    = inspection.toJson
      assertTrue(
        inspection.completeHistory,
        inspection.consistent,
        inspection.nextCursor == 4L,
        !inspection.hasMore,
        inspection.instructionFingerprint.exists(_.matches("[0-9a-f]{64}")),
        encoded.contains("search_articles"),
        encoded.contains("call-1"),
        !encoded.contains("must-not-leak"),
        !encoded.contains("result-secret"),
        !encoded.contains("private-answer"),
        !encoded.contains("private-input"),
        !encoded.contains("safe instruction")
      )
    },
    test("分页结果给出 nextCursor 且不错误执行全历史诊断") {
      val firstPage = RunInspection.build(state, events.take(2))
      assertTrue(
        firstPage.hasMore,
        firstPage.nextCursor == 1L,
        !firstPage.completeHistory,
        firstPage.diagnostics.exists(_.code == "inspection_page_truncated"),
        !firstPage.diagnostics.exists(_.code == "terminal_event_missing")
      )
    },
    test("发现 sequence 缺口、usage 漂移和审批状态漂移") {
      val brokenState = state.copy(
        status = RunStatus.WaitingForApproval,
        usage = usage.copy(inputTokens = 21L),
        pendingApproval = None
      )
      val brokenEvents = Chunk(events(0), events(2))
      val inspection   = RunInspection.build(brokenState, brokenEvents)
      val codes        = inspection.diagnostics.map(_.code).toSet
      assertTrue(
        !inspection.consistent,
        codes.contains("event_sequence_gap"),
        codes.contains("budget_usage_mismatch"),
        codes.contains("waiting_without_approval")
      )
    }
  )
