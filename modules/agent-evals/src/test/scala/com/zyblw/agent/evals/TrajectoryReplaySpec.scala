package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import java.util.UUID
import zio.*
import zio.test.*

/** 确定性轨迹评分：Replayable 必须重建，公共投影不得含 prompt。 */
object TrajectoryReplaySpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val requestId = ModelRequestId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
  private val recorded  = ChatRequest(Chunk(AgentMessage.user("私密问题")), Chunk.empty, ModelSettings())
  private val canonical = CanonicalModelRequest.from(recorded)

  private def ledger(policy: CapturePolicy, canonicalRequest: Option[CanonicalModelRequest]) =
    Chunk(
      ModelCallExecutionRecord(
        runId,
        requestId,
        attempt = 1,
        status = ModelCallStatus.Succeeded,
        provider = "scripted",
        model = "test",
        capturePolicy = policy,
        fingerprint = "a" * 64,
        messageCount = 1,
        toolCount = 0,
        lineage = ModelCallContextLineage(1, 0, 0, 0, 0),
        instructionFingerprint = None,
        canonicalRequest = canonicalRequest,
        updatedAtEpochMilli = 0L
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TrajectoryReplay")(
    test("Replayable 账本与 Fake Model 记录深比较相等且 Inspector 不含 prompt") {
      val evidence = TrajectoryReplay.evidence(
        Chunk(recorded),
        ledger(CapturePolicy.Replayable, Some(canonical)),
        """{"phase":"Model","fingerprintPrefix":"aaaa"}""",
        secrets = List("私密问题")
      )
      val grades = TrajectoryReplay.grades(evidence)
      assertTrue(
        grades.forall(_.passed),
        grades.map(_.dimension) == Chunk(TrajectoryReplay.Dimension, TrajectoryReplay.SafetyDimension),
        grades.forall(!_.details.contains("私密问题"))
      )
    },
    test("MetadataOnly 不得声称 exact replay") {
      val evidence = TrajectoryReplay.evidence(
        Chunk(recorded),
        ledger(CapturePolicy.MetadataOnly, None),
        "{}",
        secrets = List("私密问题")
      )
      assertTrue(TrajectoryReplay.grade(evidence).passed, !evidence.reconstructedEqualsRecorded)
    },
    test("Disabled 不得写账本或声称 exact replay") {
      val evidence = TrajectoryReplay.evidence(
        Chunk(recorded),
        Chunk.empty,
        "{}",
        secrets = List("私密问题")
      )
      assertTrue(
        TrajectoryReplay.grade(evidence).passed,
        evidence.ledgerSize == 0,
        !evidence.reconstructedEqualsRecorded
      )
    },
    test("Replayable 重建失败与 Inspector 泄漏分别命中轨迹和安全门禁") {
      val leaked = TrajectoryReplay.evidence(
        Chunk(recorded),
        ledger(CapturePolicy.Replayable, Some(canonical)),
        """{"prompt":"私密问题"}""",
        secrets = List("私密问题")
      )
      val mismatch = TrajectoryReplay.evidence(
        Chunk(recorded.copy(messages = Chunk(AgentMessage.user("另一句")))),
        ledger(CapturePolicy.Replayable, Some(canonical)),
        "{}",
        secrets = List("私密问题")
      )
      assertTrue(
        TrajectoryReplay.grade(leaked).passed,
        !TrajectoryReplay.safetyGrade(leaked).passed,
        !TrajectoryReplay.grade(mismatch).passed,
        TrajectoryReplay.safetyGrade(mismatch).passed
      )
    }
  )
