package com.zyblw.agent.inspection

import com.zyblw.agent.core.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.test.*

object IncidentPackSpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
  private val sessionId = SessionId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
  private val startedAt = Instant.parse("2026-08-22T00:00:00Z")

  private val state = AgentState(
    runId = runId,
    sessionId = sessionId,
    agentId = AgentId("incident-agent"),
    status = RunStatus.Failed,
    messages = Chunk(AgentMessage.user("secret-prompt")),
    steps = Chunk.empty,
    usage = UsageSummary(),
    budget = BudgetState(RunLimits(), UsageSummary(), 0),
    pendingApproval = None,
    createdAt = startedAt,
    updatedAt = startedAt,
    version = Version.initial,
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

  def spec = suite("IncidentPack")(
    test("导出 JSON 不含 prompt，密钥子串使编码失败") {
      val inspection = RunInspection.build(state, events)
      val pack       = IncidentPack.build(inspection, generatedAtEpochMilli = startedAt.toEpochMilli)
      val encoded    = IncidentPack.encode(pack)
      val leaked     = IncidentPack.encode(
        pack.copy(compositionFingerprint = Some("secret-prompt")),
        Chunk("secret-prompt")
      )
      assertTrue(
        encoded.exists(json => json.contains(runId.asString) && !json.contains("secret-prompt")),
        leaked == Left("incident-pack-secret-leak"),
        pack.modelCalls.isEmpty,
        pack.schemaVersion == 1
      )
    },
    test("模型账本摘要只有指纹前缀和计数") {
      val record = ModelCallExecutionRecord(
        runId = runId,
        requestId = ModelRequestId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")),
        attempt = 1,
        status = ModelCallStatus.Unknown,
        provider = "openai",
        model = "gpt-test",
        capturePolicy = CapturePolicy.Replayable,
        fingerprint = "ab" * 32,
        messageCount = 3,
        toolCount = 2,
        lineage = ModelCallContextLineage(1, 0, 0, 0, 0),
        instructionFingerprint = None,
        canonicalRequest = Some(
          CanonicalModelRequest(Chunk(AgentMessage.user("hidden-body")), Chunk.empty, ModelSettings())
        ),
        updatedAtEpochMilli = 0L
      )
      val inspection = RunInspection.build(state, events)
      val pack       = IncidentPack.build(inspection, Chunk(record), generatedAtEpochMilli = 1L)
      val encoded    = IncidentPack.encode(pack).getOrElse("")
      assertTrue(
        pack.modelCalls.headOption.exists(_.fingerprintPrefix == "abababababab"),
        !encoded.contains("hidden-body"),
        encoded.contains("Unknown")
      )
    }
  )
