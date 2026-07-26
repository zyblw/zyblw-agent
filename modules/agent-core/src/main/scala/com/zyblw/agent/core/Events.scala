package com.zyblw.agent.core

import zio.*
import zio.json.*

/** Runtime 与 Event Store 使用的内部运行事件。
  *
  * 该 ADT 为恢复、审计和本机细粒度观察保留完整领域信息，其中 `ToolCallRequested`、`ToolExecutionCompleted` 等事件可能 包含工具参数或结果，因此绝不能直接当成互联网
  * API、日志或第三方 Telemetry 的安全格式。HTTP/SSE 必须经过 `AgentHttpProjection` 转成版本化 `RunEventView`；其他 exporter 也必须建立自己的
  * allow-list 投影与脱敏边界。
  *
  * 新增事件时还要同步检查：状态恢复 reducer、数据库 codec、公共 HTTP 投影、Telemetry 投影和契约测试。编译器只能帮助 找到穷尽匹配，不能替代数据分级判断。
  */
enum AgentEvent derives JsonCodec:
  case RunCreated(runId: RunId, sessionId: SessionId, atEpochMilli: Long)
  case RunStarted(runId: RunId, atEpochMilli: Long)
  case RunResumed(runId: RunId, atEpochMilli: Long)
  case StepStarted(runId: RunId, step: Int, atEpochMilli: Long)

  /** 一次模型调用前的低敏 Context 组成摘要。
    *
    * 该事件只有 token/计数和固定 rot signal code，不包含 prompt、Memory、RAG query/document、工具结果或来源 ID， 因而可以被公共投影安全采用；是否进入
    * SSE、Trace 或 Metrics 仍由各出口自己的 allow-list 决定，完整正文只存在于 本次调用内存中。
    */
  case ContextPrepared(
      runId: RunId,
      estimatedTokens: Long,
      droppedMessages: Int,
      truncatedToolResults: Int,
      droppedMemories: Int,
      droppedRetrieval: Int,
      rotSignalCodes: Chunk[String],
      atEpochMilli: Long
  )

  /** Context 历史摘要边界和辅助模型用量已经与 AgentState 原子提交。
    *
    * 事件不包含摘要、源哈希或消息正文。`coveredMessages` 只表示连续前缀长度；`compressorVersion` 受构造器安全字符约束， 可用于回放和回归定位，但不能由模型自由生成。
    */
  case ContextCompacted(
      runId: RunId,
      coveredMessages: Int,
      modelCalls: Int,
      usage: TokenUsage,
      compressorVersion: String,
      atEpochMilli: Long
  )
  case ModelCallStarted(runId: RunId, provider: String, model: String, atEpochMilli: Long)
  case ModelTextDelta(runId: RunId, value: String, atEpochMilli: Long)
  case ModelToolCallDelta(runId: RunId, callId: String, fragment: String, atEpochMilli: Long)
  case ModelCallCompleted(runId: RunId, usage: TokenUsage, atEpochMilli: Long)
  case ToolCallRequested(runId: RunId, call: ToolCall, atEpochMilli: Long)
  case ToolBatchPlanned(runId: RunId, planId: String, batchCount: Int, callCount: Int, atEpochMilli: Long)
  case ToolBatchStarted(runId: RunId, planId: String, batchIndex: Int, callCount: Int, atEpochMilli: Long)
  case ToolBatchCommitted(runId: RunId, planId: String, batchIndex: Int, callCount: Int, atEpochMilli: Long)
  case ToolApprovalRequired(runId: RunId, approval: ApprovalRequest, atEpochMilli: Long)
  case ToolExecutionStarted(runId: RunId, callId: String, atEpochMilli: Long)
  case ToolExecutionCompleted(runId: RunId, callId: String, result: ToolResult, atEpochMilli: Long)
  case ToolExecutionFailed(runId: RunId, callId: String, category: String, atEpochMilli: Long)
  case GuardrailEvaluated(runId: RunId, stage: String, allowed: Boolean, atEpochMilli: Long)
  case UsageUpdated(runId: RunId, usage: UsageSummary, atEpochMilli: Long)
  case CheckpointSaved(runId: RunId, version: Version, atEpochMilli: Long)
  case RunSuspended(runId: RunId, reason: String, atEpochMilli: Long)
  case RunCompleted(runId: RunId, answer: AgentMessage, usage: UsageSummary, atEpochMilli: Long)
  case RunFailed(runId: RunId, category: String, safeMessage: String, atEpochMilli: Long)
  case RunCancelled(runId: RunId, atEpochMilli: Long)

/** 需要永久保存的精选领域事件，使用唯一 ID 和单调序号支持幂等追加。 */
final case class PersistedAgentEvent(
    eventId: EventId,
    runId: RunId,
    sequence: Long,
    event: AgentEvent,
    atEpochMilli: Long
) derives JsonCodec

/** 观测事件不携带原始密钥、Authorization 或未脱敏正文。 */
final case class TelemetryEvent(
    name: String,
    runId: Option[RunId],
    traceId: Option[String],
    attributes: Map[String, String],
    measurements: Map[String, Double] = Map.empty,
    atEpochMilli: Long,
    /** 持续时间 observation 的开始时间。None 表示离散事件；Some 时 exporter 以该时间开始、以 atEpochMilli 结束。
      * 该字段只保存运行时钟，不保存业务正文，也不承担崩溃恢复职责。
      */
    startedAtEpochMilli: Option[Long] = None
) derives JsonCodec
