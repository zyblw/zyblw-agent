package com.zyblw.agent.inspection

import com.zyblw.agent.composition.{CompositionComparisonView, RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.test.*

object IncidentPackSpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
  private val sessionId = SessionId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
  private val startedAt = Instant.parse("2026-08-22T00:00:00Z")

  private val incidentAgent = AgentDefinition(AgentId("incident-agent"), "Incident Agent", "回答问题")

  private val state = AgentState(
    runId = runId,
    sessionId = sessionId,
    agentId = AgentId("incident-agent"),
    status = RunStatus.Failed,
    messages = Chunk(AgentMessage.user("secret-prompt")),
    steps = Chunk.empty,
    usage = UsageSummary(),
    budget = BudgetState(RunLimits(), UsageSummary(), 0),
    suspension = None,
    createdAt = startedAt,
    updatedAt = startedAt,
    version = Version.initial,
    definition = incidentAgent,
    composition =
      RuntimeComposition.fingerprint(RuntimeProfile.default, incidentAgent, incidentAgent.modelSettings),
    threadId = ThreadId("incident-thread"),
    lastEventSequence = 0L
  )

  /** 离线导出：只有冻结侧，没有现场组合。 */
  private val composition = CompositionComparisonView.of(state.composition, None)

  private val events = Chunk(
    PersistedAgentEvent(
      EventId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
      runId,
      0L,
      AgentEvent.RunCreated(runId, sessionId, startedAt.toEpochMilli),
      startedAt.toEpochMilli
    )
  )

  private def trajectory(
      modelCalls: Chunk[ModelCallExecutionRecord] = Chunk.empty,
      compositionView: CompositionComparisonView = composition
  ): RunTrajectory =
    RunTrajectory.build(state, events, modelCalls).copy(composition = compositionView)

  def spec = suite("IncidentPack")(
    test("导出 JSON 不含 prompt，密钥子串使编码失败") {
      val pack    = IncidentPack.build(trajectory(), startedAt.toEpochMilli)
      val encoded = IncidentPack.encode(pack)
      val leaked  = IncidentPack.encode(
        pack.copy(trajectory =
          pack.trajectory.copy(composition =
            composition.copy(frozen = composition.frozen.copy(profileId = "secret-prompt"))
          )
        ),
        Chunk("secret-prompt")
      )
      assertTrue(
        encoded.exists(json => json.contains(runId.asString) && !json.contains("secret-prompt")),
        leaked == Left("incident-pack-secret-leak"),
        pack.modelCalls.isEmpty,
        pack.schemaVersion == IncidentPack.SchemaVersion
      )
    },
    test("没有现场组合读出口时只带冻结侧，不臆造兼容结论") {
      val pack = IncidentPack.build(trajectory(), generatedAtEpochMilli = 1L)
      assertTrue(
        pack.composition.live.isEmpty,
        pack.composition.driftKind.isEmpty,
        pack.composition.changedFields.isEmpty,
        // 结构化以后 allowedTools 这类装配身份可读，不再只有一个 64 位摘要。
        pack.composition.frozen.fingerprintPrefix == state.composition.value.take(16)
      )
    },
    test("带现场组合时报出结构化 diff") {
      val drifted = RuntimeComposition.fingerprint(
        RuntimeProfile.default,
        incidentAgent.copy(allowedTools = Set("echo")),
        incidentAgent.modelSettings
      )
      val pack = IncidentPack.build(
        trajectory(compositionView = CompositionComparisonView.of(state.composition, Some(drifted))),
        generatedAtEpochMilli = 1L
      )
      assertTrue(
        pack.composition.driftKind.contains("incompatible"),
        pack.composition.changedFields.map(_.field).toSet == Set("allowedTools"),
        pack.composition.live.exists(_.allowedTools == Chunk("echo"))
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
      val pack    = IncidentPack.build(trajectory(modelCalls = Chunk(record)), generatedAtEpochMilli = 1L)
      val encoded = IncidentPack.encode(pack).getOrElse("")
      assertTrue(
        pack.modelCalls.headOption.exists(_.fingerprintPrefix == "abababababab"),
        !encoded.contains("hidden-body"),
        encoded.contains("Unknown")
      )
    },
    test("路由摘要只暴露低敏解释码和版本") {
      val decision = RouteDecision(
        ModelRequirement(ModelProfile.Reasoning),
        ModelRef("provider-a", "model-a"),
        "policy-v1",
        "a" * 64,
        Chunk(
          ModelCandidateDecision(ModelRef("provider-a", "model-a"), Chunk.empty),
          ModelCandidateDecision(ModelRef("provider-b", "model-b"), Chunk("vision"))
        ),
        explicitModelPinned = false,
        estimatedInputTokens = 20,
        maxOutputTokens = 10,
        pricingFingerprint = "b" * 64,
        selectedPrice = Some(ModelPrice(1, 2)),
        estimatedCost = Some(BigDecimal("0.00004"))
      )
      val record = ModelCallExecutionRecord(
        runId,
        ModelRequestId(UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")),
        1,
        ModelCallStatus.Succeeded,
        "provider-a",
        "model-a",
        CapturePolicy.MetadataOnly,
        "c" * 64,
        1,
        0,
        ModelCallContextLineage(20, 0, 0, 0, 0),
        None,
        None,
        updatedAtEpochMilli = startedAt.toEpochMilli,
        routeDecision = Some(decision)
      )
      val summary = ModelCallIncidentSummary.from(record)
      assertTrue(
        summary.requestedProfile.contains("Reasoning"),
        summary.routePolicyVersion.contains("policy-v1"),
        summary.routeDecisionCodes.contains("selected:provider-a/model-a"),
        summary.routeDecisionCodes.contains("skip:provider-b/model-b:vision"),
        summary.pricingFingerprintPrefix.contains("b" * 12),
        !summary.toJson.contains("inputPerMillionTokens")
      )
    }
  )
