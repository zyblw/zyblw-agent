package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import zio.*

/** Replayable 轨迹是否能从账本重建当时发给模型的请求，且公共投影不含敏感子串。 */
final case class TrajectoryReplayEvidence(
    capturePolicy: CapturePolicy,
    ledgerSize: Int,
    reconstructedEqualsRecorded: Boolean,
    inspectionLeakedSecrets: Boolean
):
  require(ledgerSize >= 0, "ledgerSize 不能为负数")

object TrajectoryReplay:
  val Dimension: String       = "trajectory-replay"
  val SafetyDimension: String = "inspection-redaction-safety"

  /** 用 Fake Model 记录、账本与 Inspector JSON 生成评测证据。不读取用户消息进入 details。 */
  def evidence(
      recorded: Chunk[ChatRequest],
      ledger: Chunk[ModelCallExecutionRecord],
      inspectionJson: String,
      secrets: Iterable[String]
  ): TrajectoryReplayEvidence =
    val reconstructed = ledger.flatMap(record => Chunk.fromIterable(record.toChatRequest.toOption))
    TrajectoryReplayEvidence(
      capturePolicy = ledger.headOption.map(_.capturePolicy).getOrElse(CapturePolicy.Disabled),
      ledgerSize = ledger.length,
      reconstructedEqualsRecorded = reconstructed == recorded && recorded.nonEmpty,
      inspectionLeakedSecrets = secrets.exists(secret => secret.nonEmpty && inspectionJson.contains(secret))
    )

  /** Replayable 必须深比较重建成功；MetadataOnly/Disabled 不得声称 exact replay。 */
  def grade(evidence: TrajectoryReplayEvidence): EvalGrade =
    val replayOk = evidence.capturePolicy match
      case CapturePolicy.Replayable   => evidence.reconstructedEqualsRecorded && evidence.ledgerSize >= 1
      case CapturePolicy.MetadataOnly =>
        evidence.ledgerSize >= 1 && !evidence.reconstructedEqualsRecorded
      case CapturePolicy.Disabled => evidence.ledgerSize == 0 && !evidence.reconstructedEqualsRecorded
    EvalGrade(
      Dimension,
      replayOk,
      if replayOk then 1.0 else 0.0,
      s"policy=${evidence.capturePolicy};ledger=${evidence.ledgerSize};reconstructed=${evidence.reconstructedEqualsRecorded}"
    )

  /** Inspector 脱敏是独立 Safety 门禁，不能被轨迹重建成功掩盖。 */
  def safetyGrade(evidence: TrajectoryReplayEvidence): EvalGrade =
    val passed = !evidence.inspectionLeakedSecrets
    EvalGrade(
      SafetyDimension,
      passed,
      if passed then 1.0 else 0.0,
      s"leaked=${evidence.inspectionLeakedSecrets}"
    )

  /** 轨迹重建与公共投影脱敏分别评分。 */
  def grades(evidence: TrajectoryReplayEvidence): Chunk[EvalGrade] =
    Chunk(grade(evidence), safetyGrade(evidence))
