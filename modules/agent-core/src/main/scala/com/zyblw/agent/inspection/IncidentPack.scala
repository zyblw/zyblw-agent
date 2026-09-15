package com.zyblw.agent.inspection

import com.zyblw.agent.composition.CompositionComparisonView
import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 事故包中的模型账本摘要；不含 prompt、工具参数或 Canonical 正文。
  *
  * 事故包本身消费 [[RunTrajectory]]；本类型保留给只关心路由摘要的调用方，字段与轨迹行对齐。
  */
final case class ModelCallIncidentSummary(
    requestId: String,
    status: String,
    provider: String,
    model: String,
    capturePolicy: String,
    fingerprintPrefix: String,
    messageCount: Int,
    toolCount: Int,
    requestedProfile: Option[String] = None,
    routePolicyVersion: Option[String] = None,
    routeDecisionCodes: Chunk[String] = Chunk.empty,
    pricingFingerprintPrefix: Option[String] = None
) derives JsonCodec

object ModelCallIncidentSummary:
  def from(record: ModelCallExecutionRecord): ModelCallIncidentSummary =
    from(ModelCallTrajectoryView.from(record))

  def from(view: ModelCallTrajectoryView): ModelCallIncidentSummary =
    ModelCallIncidentSummary(
      requestId = view.requestId,
      status = view.status,
      provider = view.provider,
      model = view.model,
      capturePolicy = view.capturePolicy,
      fingerprintPrefix = view.fingerprintPrefix,
      messageCount = view.messageCount,
      toolCount = view.toolCount,
      requestedProfile = view.requestedProfile,
      routePolicyVersion = view.routePolicyVersion,
      routeDecisionCodes = view.routeDecisionCodes,
      pricingFingerprintPrefix = view.pricingFingerprintPrefix
    )

/** 可安全导出的事故包。只消费 [[RunTrajectory]]，不另开事实源。
  *
  * `composition` 从"一个 64 位十六进制字符串"升级为结构化对照：原先只有指纹时，事故包能证明"组合变了"却完全说不出变了什么， 排查必须回到源码或数据库。
  */
final case class IncidentPack(
    schemaVersion: Int,
    generatedAtEpochMilli: Long,
    trajectory: RunTrajectory
) derives JsonCodec:
  def runId: String                               = trajectory.run.runId
  def status: String                              = trajectory.run.status
  def composition: CompositionComparisonView      = trajectory.composition
  def inspection: RunInspection                   = trajectory.inspection
  def modelCalls: Chunk[ModelCallIncidentSummary] = trajectory.modelCalls.map(ModelCallIncidentSummary.from)
  def toolLedger: Chunk[ToolExecutionTrajectoryView] = trajectory.toolLedger
  def commands: Chunk[CommandTrajectoryView]         = trajectory.commands
  def suspensions: Chunk[SuspensionTrajectoryView]   = trajectory.suspensions

object IncidentPack:
  /** 与 `AgentState` 一同归位：本版本不读取任何历史 IncidentPack 形状。 */
  val SchemaVersion: Int = 1

  def build(trajectory: RunTrajectory, generatedAtEpochMilli: Long): IncidentPack =
    IncidentPack(
      schemaVersion = SchemaVersion,
      generatedAtEpochMilli = generatedAtEpochMilli,
      trajectory = trajectory
    )

  /** JSON 不得包含调用方声明的密钥或 prompt 子串。 */
  def encode(pack: IncidentPack, forbidden: Chunk[String] = Chunk.empty): Either[String, String] =
    val json = pack.toJson
    val leak = forbidden.map(_.trim).filter(_.nonEmpty).find(token => json.contains(token))
    leak match
      case Some(_) => Left("incident-pack-secret-leak")
      case None    => Right(json)
