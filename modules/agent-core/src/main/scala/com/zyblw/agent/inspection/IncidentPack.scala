package com.zyblw.agent.inspection

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 事故包中的模型账本摘要；不含 prompt、工具参数或 Canonical 正文。 */
final case class ModelCallIncidentSummary(
    requestId: String,
    status: String,
    provider: String,
    model: String,
    capturePolicy: String,
    fingerprintPrefix: String,
    messageCount: Int,
    toolCount: Int
) derives JsonCodec

object ModelCallIncidentSummary:
  def from(record: ModelCallExecutionRecord): ModelCallIncidentSummary =
    ModelCallIncidentSummary(
      requestId = record.requestId.asString,
      status = record.status.toString,
      provider = record.provider,
      model = record.model,
      capturePolicy = record.capturePolicy.toString,
      fingerprintPrefix = record.fingerprint.take(12),
      messageCount = record.messageCount,
      toolCount = record.toolCount
    )

/** 可安全导出的事故包。只含 Inspector 时间线、组合指纹和模型账本摘要。 */
final case class IncidentPack(
    schemaVersion: Int,
    generatedAtEpochMilli: Long,
    runId: String,
    status: String,
    compositionFingerprint: Option[String],
    inspection: RunInspection,
    modelCalls: Chunk[ModelCallIncidentSummary]
) derives JsonCodec

object IncidentPack:
  val SchemaVersion: Int = 1

  def build(
      inspection: RunInspection,
      modelCalls: Chunk[ModelCallExecutionRecord] = Chunk.empty,
      compositionFingerprint: Option[String] = None,
      generatedAtEpochMilli: Long
  ): IncidentPack =
    IncidentPack(
      schemaVersion = SchemaVersion,
      generatedAtEpochMilli = generatedAtEpochMilli,
      runId = inspection.runId.asString,
      status = inspection.status.toString,
      compositionFingerprint = compositionFingerprint,
      inspection = inspection,
      modelCalls = modelCalls.map(ModelCallIncidentSummary.from)
    )

  /** JSON 不得包含调用方声明的密钥或 prompt 子串。 */
  def encode(pack: IncidentPack, forbidden: Chunk[String] = Chunk.empty): Either[String, String] =
    val json = pack.toJson
    val leak = forbidden.map(_.trim).filter(_.nonEmpty).find(token => json.contains(token))
    leak match
      case Some(_) => Left("incident-pack-secret-leak")
      case None    => Right(json)
