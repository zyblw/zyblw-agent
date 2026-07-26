package com.zyblw.agent.http

import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.*
import com.zyblw.agent.inspection.*
import zio.*

/** 内部耐久领域模型到 HTTP v1 公共 DTO 的唯一投影边界。
  *
  * `AgentState` 和 `AgentEvent` 可以为恢复、调度与观测继续演进；外部客户端只看到本对象产生的稳定子集。任何新增领域事件 必须在这里显式决定公开哪些低敏信息，不能依赖自动 JSON
  * derivation 把工具参数、结果、隐藏推理或内部 metadata 带出。
  */
private[http] object AgentHttpProjection:

  /** 将授权后的权威状态投影为公共 Run 视图。
    * @param state
    *   已经通过 `RunAuthorization.read` 的 AgentState
    */
  def run(state: AgentState): RunView =
    val output = state.messages.reverse.find(_.role == MessageRole.Assistant).map(_.text).filter(_.nonEmpty)
    RunView(
      runId = state.runId.asString,
      threadId = state.threadId.fold("")(_.value),
      agentId = state.agentId.value,
      status = state.status.toString,
      steps = state.budget.steps,
      usage = usage(state.usage),
      output = output,
      pendingApproval = state.pendingApproval.map(approval),
      createdAtEpochMilli = state.createdAt.toEpochMilli,
      updatedAtEpochMilli = state.updatedAt.toEpochMilli,
      stateVersion = state.version.value
    )

  /** 将数据库事件信封转换成不依赖内部 ADT JSON 表示的公共事件。
    * @param persisted
    *   已按 runId/sequence 连续性验证的耐久事件
    */
  def event(persisted: PersistedAgentEvent): RunEventView =
    val base = RunEventView(
      eventId = persisted.eventId.asString,
      runId = persisted.runId.asString,
      sequence = persisted.sequence,
      eventType = RunTimeline.eventType(persisted.event),
      atEpochMilli = persisted.atEpochMilli
    )
    persisted.event match
      case AgentEvent.RunCreated(_, _, _) => base.copy(status = Some(RunStatus.Created.toString))
      case AgentEvent.RunStarted(_, _) | AgentEvent.RunResumed(_, _) =>
        base.copy(status = Some(RunStatus.Running.toString))
      case AgentEvent.StepStarted(_, step, _) => base.copy(step = Some(step))
      case AgentEvent.ContextPrepared(_, estimated, dropped, truncated, memories, retrieval, rot, _) =>
        base.copy(
          context = Some(
            ContextUsageView(estimated, dropped, truncated, memories, retrieval, rot.toList)
          )
        )
      case AgentEvent.ContextCompacted(_, covered, calls, value, version, _) =>
        base.copy(
          stage = Some("context-compaction"),
          category = Some(bounded(version)),
          usage = Some(
            UsageView(
              modelCalls = calls,
              toolCalls = 0,
              inputTokens = value.inputTokens,
              outputTokens = value.outputTokens,
              totalTokens = value.totalTokens,
              estimatedCost = "0",
              cachedInputTokens = value.cachedInputTokens,
              reasoningOutputTokens = value.reasoningOutputTokens
            )
          ),
          message = Some(s"已压缩 $covered 条历史消息")
        )
      case AgentEvent.ModelCallStarted(_, provider, model, _) =>
        base.copy(stage = Some("model"), category = Some(s"${bounded(provider)}:${bounded(model)}"))
      case AgentEvent.ModelTextDelta(_, value, _)         => base.copy(output = Some(value))
      case AgentEvent.ModelToolCallDelta(_, callId, _, _) =>
        base.copy(tool = Some(ToolProgressView(Some(callId), None, None)))
      case AgentEvent.ModelCallCompleted(_, value, _) =>
        base.copy(usage = Some(modelUsage(value)))
      case AgentEvent.ToolCallRequested(_, call, _) =>
        base.copy(tool = Some(ToolProgressView(Some(call.id), Some(call.name), None)))
      case AgentEvent.ToolBatchPlanned(_, _, batchCount, callCount, _) =>
        base.copy(stage = Some("tool-plan"), category = Some(s"batches=$batchCount,calls=$callCount"))
      case AgentEvent.ToolBatchStarted(_, _, batchIndex, _, _) =>
        base.copy(
          tool = Some(ToolProgressView(None, None, Some(batchIndex))),
          stage = Some("tool-batch-started")
        )
      case AgentEvent.ToolBatchCommitted(_, _, batchIndex, _, _) =>
        base.copy(
          tool = Some(ToolProgressView(None, None, Some(batchIndex))),
          stage = Some("tool-batch-committed")
        )
      case AgentEvent.ToolApprovalRequired(_, value, _) =>
        base.copy(status = Some(RunStatus.WaitingForApproval.toString), approval = Some(approval(value)))
      case AgentEvent.ToolExecutionStarted(_, callId, _) =>
        base.copy(
          tool = Some(ToolProgressView(Some(callId), None, None)),
          stage = Some("tool-execution-started")
        )
      case AgentEvent.ToolExecutionCompleted(_, callId, _, _) =>
        base.copy(
          tool = Some(ToolProgressView(Some(callId), None, None)),
          stage = Some("tool-execution-completed")
        )
      case AgentEvent.ToolExecutionFailed(_, callId, category, _) =>
        base.copy(
          tool = Some(ToolProgressView(Some(callId), None, None)),
          stage = Some("tool-execution-failed"),
          category = Some(bounded(category))
        )
      case AgentEvent.GuardrailEvaluated(_, stage, allowed, _) =>
        base.copy(stage = Some(bounded(stage)), category = Some(if allowed then "allowed" else "denied"))
      case AgentEvent.UsageUpdated(_, value, _)      => base.copy(usage = Some(usage(value)))
      case AgentEvent.CheckpointSaved(_, version, _) => base.copy(stateVersion = Some(version.value))
      case AgentEvent.RunSuspended(_, _, _)          => base.copy(status = Some(RunStatus.Suspended.toString))
      case AgentEvent.RunCompleted(_, answer, value, _) =>
        base.copy(
          status = Some(RunStatus.Completed.toString),
          output = Some(answer.text),
          usage = Some(usage(value))
        )
      case AgentEvent.RunFailed(_, category, safeMessage, _) =>
        base.copy(
          status = Some(RunStatus.Failed.toString),
          category = Some(bounded(category)),
          message = Some(bounded(safeMessage, 512))
        )
      case AgentEvent.RunCancelled(_, _) => base.copy(status = Some(RunStatus.Cancelled.toString))

  /** 从已经授权的状态和耐久事件页构造低敏 Inspector 视图。
    *
    * Inspector 不复用内部 AgentState JSON，也不会把 Event 中的动态正文复制到时间线。
    */
  def inspection(
      state: AgentState,
      events: Chunk[PersistedAgentEvent],
      afterSequence: Long
  ): RunInspectionView =
    val inspected = RunInspection.build(state, events, afterSequence)
    RunInspectionView(
      run = inspectionSummary(state),
      instructionFingerprint = inspected.instructionFingerprint,
      timeline = inspected.timeline.map(timeline).toList,
      diagnostics = inspected.diagnostics.map(diagnostic).toList,
      nextCursor = inspected.nextCursor,
      hasMore = inspected.hasMore,
      completeHistory = inspected.completeHistory,
      consistent = inspected.consistent
    )

  /** Inspector 不复用包含 threadId、最终答案和审批摘要的普通 RunView。 */
  private def inspectionSummary(state: AgentState): RunInspectionSummaryView =
    RunInspectionSummaryView(
      runId = state.runId.asString,
      agentId = state.agentId.value,
      status = state.status.toString,
      steps = state.budget.steps,
      usage = usage(state.usage),
      awaitingApproval = state.status == RunStatus.WaitingForApproval,
      createdAtEpochMilli = state.createdAt.toEpochMilli,
      updatedAtEpochMilli = state.updatedAt.toEpochMilli,
      stateVersion = state.version.value
    )

  /** 内部累计用量到精度稳定的公共表示。 */
  private def usage(value: UsageSummary): UsageView = UsageView(
    value.modelCalls,
    value.toolCalls,
    value.inputTokens,
    value.outputTokens,
    value.totalTokens,
    value.estimatedCost.bigDecimal.toPlainString,
    value.cachedInputTokens,
    value.reasoningOutputTokens
  )

  /** 单次模型调用用量不会伪造成累计调用次数之外的业务计费。 */
  private def modelUsage(value: TokenUsage): UsageView = UsageView(
    modelCalls = 1,
    toolCalls = 0,
    inputTokens = value.inputTokens,
    outputTokens = value.outputTokens,
    totalTokens = value.totalTokens,
    estimatedCost = "0",
    cachedInputTokens = value.cachedInputTokens,
    reasoningOutputTokens = value.reasoningOutputTokens
  )

  /** 核心 Inspector 条目到稳定 wire DTO；只复制 allow-list 字段。 */
  private def timeline(value: RunTimelineEntry): RunTimelineEntryView =
    RunTimelineEntryView(
      eventId = value.eventId.asString,
      sequence = value.sequence,
      eventType = value.eventType,
      phase = value.phase.toString,
      outcome = value.outcome.toString,
      atEpochMilli = value.atEpochMilli,
      elapsedMillis = value.elapsedMillis,
      step = value.step,
      toolName = value.toolName.map(bounded(_)),
      callId = value.callId.map(bounded(_)),
      category = value.category.map(bounded(_)),
      usage = value.usage.map(usage)
    )

  /** Inspector 诊断只允许固定 code/severity/message 和可选 sequence。 */
  private def diagnostic(value: RunDiagnostic): RunDiagnosticView =
    RunDiagnosticView(
      code = bounded(value.code),
      severity = value.severity.toString,
      message = bounded(value.message, 512),
      sequence = value.sequence
    )

  /** 审批视图只包含工具名和人工可理解摘要，不包含 arguments。 */
  private def approval(value: ApprovalRequest): ApprovalView = ApprovalView(
    value.id,
    value.toolCall.name,
    value.risk.toString,
    bounded(value.reason, 512),
    value.requestedAtEpochMilli
  )

  /** 防御自定义 Provider/工具把无界动态名称投影到公共事件。 */
  private def bounded(value: String, max: Int = 128): String = value.take(max)
