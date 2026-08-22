package com.zyblw.agent.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import zio.*
import zio.json.*

/** 主模型请求进入耐久账本时保存多少正文。
  *
  * 生产默认 [[MetadataOnly]]：只保存指纹、计数与模型标识。[[Replayable]] 额外保存可重建的
  * `CanonicalModelRequest`，仅用于评测、排障授权和确定性测试。[[Disabled]] 不写账本，恢复语义退回 0.6.2。
  */
enum CapturePolicy derives JsonCodec:
  case Disabled, MetadataOnly, Replayable

/** 主模型调用账本状态。Prepared/Dispatched 表示 Intent 已提交、Provider 结果尚未结算。 */
enum ModelCallStatus derives JsonCodec:
  case Prepared, Dispatched, Succeeded, Failed, Unknown

/** Provider 中立的规范请求；`toChatRequest` 必须能复现当时发给 ChatModel 的值。 */
final case class CanonicalModelRequest(
    messages: Chunk[AgentMessage],
    tools: Chunk[ToolDefinition],
    settings: ModelSettings
) derives JsonCodec:
  def toChatRequest: ChatRequest = ChatRequest(messages, tools, settings)

object CanonicalModelRequest:
  def from(request: ChatRequest): CanonicalModelRequest =
    CanonicalModelRequest(request.messages, request.tools, request.settings)

  /** 对将要 dispatch 的 ChatRequest 计算稳定 SHA-256，不把正文写入日志。 */
  def fingerprint(request: ChatRequest, instructionFingerprint: Option[String]): String =
    digest(s"${instructionFingerprint.getOrElse("")}\n${request.toJson}")

  /** 只摘要本次实际发给模型的工具定义，恢复不得用 live registry 替换。 */
  def toolDefinitionsFingerprint(tools: Chunk[ToolDefinition]): String =
    digest(tools.toJson)

  private def digest(canonical: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

/** Context 组成指针；不含 Memory/RAG/消息正文。 */
final case class ModelCallContextLineage(
    estimatedTokens: Long,
    droppedMessages: Int,
    truncatedToolResults: Int,
    droppedMemories: Int,
    droppedRetrieval: Int,
    summaryCoveredMessages: Option[Int] = None,
    summarySourceDigest: Option[String] = None,
    /** world-state section 差量决策，格式 `id:rendered|unchanged|suppressed:...`；不含正文。 */
    sectionDecisions: Chunk[String] = Chunk.empty,
    /** 本次实际 dispatch 的完整 ModelSettings 规范摘要；旧账本缺失时为 None。 */
    effectiveModelSettingsFingerprint: Option[String] = None,
    /** 本次实际发给模型的工具定义指纹；恢复不得改用 live registry 推断历史合同。 */
    toolDefinitionsFingerprint: Option[String] = None
) derives JsonCodec

/** 主模型调用账本。`canonicalRequest` 仅在 Replayable 时出现，恢复不得把它投影到公共 API。 */
final case class ModelCallExecutionRecord(
    runId: RunId,
    requestId: ModelRequestId,
    attempt: Int,
    status: ModelCallStatus,
    provider: String,
    model: String,
    capturePolicy: CapturePolicy,
    fingerprint: String,
    messageCount: Int,
    toolCount: Int,
    lineage: ModelCallContextLineage,
    instructionFingerprint: Option[String],
    canonicalRequest: Option[CanonicalModelRequest],
    usage: Option[TokenUsage] = None,
    finishReason: Option[FinishReason] = None,
    errorCategory: Option[String] = None,
    updatedAtEpochMilli: Long
) derives JsonCodec:
  def toChatRequest: Either[String, ChatRequest] =
    for
      _         <- verifyFrozenTools
      canonical <- canonicalRequest.toRight(
        s"CapturePolicy ${capturePolicy} 未保存 CanonicalModelRequest，不能声称 exact replay"
      )
    yield canonical.toChatRequest

  /** Replayable 账本必须自洽：lineage 中的工具指纹只能来自当时保存的定义，不能事后用 live registry 重算。 */
  def verifyFrozenTools: Either[String, Unit] =
    (lineage.toolDefinitionsFingerprint, canonicalRequest) match
      case (Some(expected), Some(canonical)) =>
        val actual = CanonicalModelRequest.toolDefinitionsFingerprint(canonical.tools)
        Either.cond(
          actual == expected,
          (),
          s"工具定义指纹与 CanonicalModelRequest 不一致 expected=${expected.take(12)} actual=${actual.take(12)}"
        )
      case _ => Right(())

/** 当前未结算的主模型调用游标；不携带 prompt 正文。 */
final case class PendingModelCall(
    requestId: ModelRequestId,
    attempt: Int,
    fingerprint: String,
    capturePolicy: CapturePolicy,
    provider: String,
    model: String
) derives JsonCodec
