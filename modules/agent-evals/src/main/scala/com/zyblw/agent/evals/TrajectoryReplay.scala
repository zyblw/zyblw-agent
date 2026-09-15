package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import com.zyblw.agent.inspection.RunTrajectory
import zio.*
import zio.json.*

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

  /** 用 Fake Model 记录、账本与公共轨迹投影生成评测证据。不读取用户消息进入 details。
    *
    * Replayable 深比较仍使用账本中的 CanonicalModelRequest（投影层故意不含正文）；脱敏门禁检查 [[RunTrajectory]] JSON， 两个断言互相独立。
    */
  def evidence(
      recorded: Chunk[ChatRequest],
      ledger: Chunk[ModelCallExecutionRecord],
      trajectory: RunTrajectory,
      secrets: Iterable[String]
  ): TrajectoryReplayEvidence =
    evidence(recorded, ledger, trajectory.toJson, secrets)

  /** 兼容只持有 JSON 字符串的调用方；新代码应传入 [[RunTrajectory]]。 */
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
