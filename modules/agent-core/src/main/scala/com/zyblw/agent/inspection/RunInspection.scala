package com.zyblw.agent.inspection

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** Run 时间线中的稳定阶段。
  *
  * 该分类只描述控制面发生了什么，不包含 Prompt、消息正文、工具参数、工具结果或隐藏推理，可安全作为调试 API 和 运维界面的基础读模型。
  */
enum RunTimelinePhase derives JsonCodec:
  case Lifecycle, Context, Model, Tool, Guardrail, Approval, Persistence

/** 时间线事件的低敏结果语义。 */
enum RunTimelineOutcome derives JsonCodec:
  case Started, Progress, Succeeded, Failed, Waiting, Cancelled

/** Inspector 发现的一致性问题严重度。 */
enum RunDiagnosticSeverity derives JsonCodec:
  case Info, Warning, Error

/** 一个不携带业务正文的时间线条目。
  *
  * `category` 只接收框架错误分类、Guardrail 决策或固定阶段标签；HTTP 投影仍会执行长度限制。模型文本 delta、 ToolResult、工具 arguments 与
  * RunFailed.safeMessage 均不会进入该结构。
  */
final case class RunTimelineEntry(
    eventId: EventId,
    sequence: Long,
    eventType: String,
    phase: RunTimelinePhase,
    outcome: RunTimelineOutcome,
    atEpochMilli: Long,
    elapsedMillis: Long,
    step: Option[Int] = None,
    toolName: Option[String] = None,
    callId: Option[String] = None,
    category: Option[String] = None,
    usage: Option[UsageSummary] = None
) derives JsonCodec

/** Inspector 的机械一致性诊断。
  *
  * 诊断消息只能描述结构问题，不能拼接用户输入、Provider 原文、工具参数或数据库异常。
  */
final case class RunDiagnostic(
    code: String,
    severity: RunDiagnosticSeverity,
    message: String,
    sequence: Option[Long] = None
) derives JsonCodec

/** 从权威状态与一页耐久事件生成的可分页 Run 检查结果。
  *
  * Inspector 是只读投影，不是第二套状态机，也不承担 Event Sourcing 重放。`hasMore` 为 true 时，调用方使用 `nextCursor` 继续读取；只有完整读取
  * `[0, state.lastEventSequence]` 后，终态事件等全历史诊断才会启用。
  */
final case class RunInspection(
    runId: RunId,
    status: RunStatus,
    stateVersion: Version,
    lastEventSequence: Long,
    instructionFingerprint: Option[String],
    timeline: Chunk[RunTimelineEntry],
    diagnostics: Chunk[RunDiagnostic],
    nextCursor: Long,
    hasMore: Boolean,
    completeHistory: Boolean
) derives JsonCodec:
  /** 没有 Error 级诊断表示本页及可验证的状态不变量一致。 */
  def consistent: Boolean = !diagnostics.exists(_.severity == RunDiagnosticSeverity.Error)

object RunInspection:

  /** 构造低敏检查读模型。
    *
    * @param state
    *   已从 RunStore 读取并完成业务授权的权威快照
    * @param events
    *   `afterSequence` 之后按 sequence 升序读取的一页耐久事件
    * @param afterSequence
    *   调用方已经确认的最后序号；首次读取为 -1
    */
  def build(
      state: AgentState,
      events: Chunk[PersistedAgentEvent],
      afterSequence: Long = -1L
  ): RunInspection =
    val timeline          = events.map(event => RunTimeline.project(event, state.createdAt.toEpochMilli))
    val nextCursor        = events.lastOption.fold(afterSequence)(_.sequence)
    val hasMore           = nextCursor < state.lastEventSequence
    val expectedFirst     = afterSequence + 1L
    val sequences         = events.map(_.sequence)
    val expectedSequences = Chunk.fromIterable(
      Vector.tabulate(events.length)(index => expectedFirst + index.toLong)
    )
    val orderedAndContinuous = sequences == expectedSequences
    val sameRun              = events.forall(_.runId == state.runId)
    val completeHistory      =
      afterSequence == -1L &&
        !hasMore &&
        state.lastEventSequence == nextCursor &&
        (state.lastEventSequence == -1L || (events.nonEmpty && events.head.sequence == 0L)) &&
        orderedAndContinuous &&
        sameRun

    val diagnostics =
      pageDiagnostics(state, events, afterSequence, orderedAndContinuous, sameRun, hasMore) ++
        stateDiagnostics(state) ++
        Option.when(completeHistory)(fullHistoryDiagnostics(state, events)).getOrElse(Chunk.empty)

    RunInspection(
      runId = state.runId,
      status = state.status,
      stateVersion = state.version,
      lastEventSequence = state.lastEventSequence,
      instructionFingerprint = state.definition.flatMap(_.instructionSet).map(_.fingerprint),
      timeline = timeline,
      diagnostics = diagnostics,
      nextCursor = nextCursor,
      hasMore = hasMore,
      completeHistory = completeHistory
    )

  private def pageDiagnostics(
      state: AgentState,
      events: Chunk[PersistedAgentEvent],
      afterSequence: Long,
      orderedAndContinuous: Boolean,
      sameRun: Boolean,
      hasMore: Boolean
  ): Chunk[RunDiagnostic] =
    val wrongRun =
      Option.when(!sameRun)(
        RunDiagnostic(
          "event_run_mismatch",
          RunDiagnosticSeverity.Error,
          "事件页包含不属于目标 Run 的事件。"
        )
      )
    val gap =
      Option.when(!orderedAndContinuous)(
        RunDiagnostic(
          "event_sequence_gap",
          RunDiagnosticSeverity.Error,
          "事件 sequence 未从游标后连续递增。",
          events.headOption.map(_.sequence)
        )
      )
    val cursorAhead =
      events.lastOption
        .filter(_.sequence > state.lastEventSequence)
        .map(event =>
          RunDiagnostic(
            "event_cursor_ahead_of_state",
            RunDiagnosticSeverity.Error,
            "事件序号超过权威状态记录的最后序号。",
            Some(event.sequence)
          )
        )
    val missingPage =
      Option.when(
        events.isEmpty && afterSequence < state.lastEventSequence
      )(
        RunDiagnostic(
          "event_page_missing",
          RunDiagnosticSeverity.Error,
          "权威状态表明仍有事件，但当前事件页为空。"
        )
      )
    val partial =
      Option.when(hasMore)(
        RunDiagnostic(
          "inspection_page_truncated",
          RunDiagnosticSeverity.Info,
          "当前只检查了部分时间线，请使用 nextCursor 继续读取。"
        )
      )
    Chunk.fromIterable(List(wrongRun, gap, cursorAhead, missingPage, partial).flatten)

  private def stateDiagnostics(state: AgentState): Chunk[RunDiagnostic] =
    val approvalMismatch =
      if state.status == RunStatus.WaitingForApproval && state.pendingApproval.isEmpty then
        Some(
          RunDiagnostic(
            "waiting_without_approval",
            RunDiagnosticSeverity.Error,
            "Run 正在等待审批，但权威状态没有待审批记录。"
          )
        )
      else if state.status != RunStatus.WaitingForApproval && state.pendingApproval.nonEmpty then
        Some(
          RunDiagnostic(
            "approval_outside_waiting_state",
            RunDiagnosticSeverity.Error,
            "Run 不在等待审批状态，但仍保留待审批记录。"
          )
        )
      else None
    val usageMismatch =
      Option.when(state.usage != state.budget.consumed)(
        RunDiagnostic(
          "budget_usage_mismatch",
          RunDiagnosticSeverity.Error,
          "Run usage 与预算累计值不一致。"
        )
      )
    val legacyDefinition =
      Option.when(state.definition.isEmpty)(
        RunDiagnostic(
          "definition_snapshot_missing",
          RunDiagnosticSeverity.Warning,
          "Run 缺少创建时的 AgentDefinition 快照，可能来自旧版数据。"
        )
      )
    val unversionedInstructions =
      Option.when(state.definition.exists(_.instructionSet.isEmpty))(
        RunDiagnostic(
          "instruction_fingerprint_missing",
          RunDiagnosticSeverity.Warning,
          "AgentDefinition 使用旧式单块指令，无法关联 instruction fingerprint 趋势。"
        )
      )
    Chunk.fromIterable(
      List(approvalMismatch, usageMismatch, legacyDefinition, unversionedInstructions).flatten
    )

  private def fullHistoryDiagnostics(
      state: AgentState,
      events: Chunk[PersistedAgentEvent]
  ): Chunk[RunDiagnostic] =
    val hasCreated   = events.exists(_.event.isInstanceOf[AgentEvent.RunCreated])
    val terminalType = state.status match
      case RunStatus.Completed      => Some("RunCompleted")
      case RunStatus.Failed         => Some("RunFailed")
      case RunStatus.Cancelled      => Some("RunCancelled")
      case RunStatus.TimedOut       => Some("RunFailed")
      case RunStatus.BudgetExceeded => Some("RunFailed")
      case _                        => None
    val missingCreated =
      Option.when(state.lastEventSequence >= 0L && !hasCreated)(
        RunDiagnostic(
          "run_created_event_missing",
          RunDiagnosticSeverity.Error,
          "完整时间线缺少 RunCreated 事件。"
        )
      )
    val missingTerminal =
      terminalType
        .filterNot(expected => events.exists(event => RunTimeline.eventType(event.event) == expected))
        .map { expected =>
          RunDiagnostic(
            "terminal_event_missing",
            RunDiagnosticSeverity.Error,
            s"Run 已处于终态，但完整时间线缺少 $expected 事件。"
          )
        }
    Chunk.fromIterable(List(missingCreated, missingTerminal).flatten)

object RunTimeline:

  /** 把内部事件投影为低敏时间线。
    *
    * 该匹配故意穷尽 `AgentEvent`。新增事件时编译器会要求维护者明确阶段、结果与数据公开边界。
    */
  def project(persisted: PersistedAgentEvent, runCreatedAtEpochMilli: Long): RunTimelineEntry =
    val base = RunTimelineEntry(
      eventId = persisted.eventId,
      sequence = persisted.sequence,
      eventType = eventType(persisted.event),
      phase = phase(persisted.event),
      outcome = outcome(persisted.event),
      atEpochMilli = persisted.atEpochMilli,
      elapsedMillis = (persisted.atEpochMilli - runCreatedAtEpochMilli).max(0L)
    )
    persisted.event match
      case AgentEvent.StepStarted(_, step, _)                    => base.copy(step = Some(step))
      case AgentEvent.ContextCompacted(_, _, calls, usage, _, _) =>
        base.copy(
          usage = Some(
            UsageSummary(
              modelCalls = calls,
              inputTokens = usage.inputTokens,
              outputTokens = usage.outputTokens,
              cachedInputTokens = usage.cachedInputTokens,
              reasoningOutputTokens = usage.reasoningOutputTokens
            )
          )
        )
      case AgentEvent.ModelCallStarted(_, provider, model, _) =>
        base.copy(category = Some(s"$provider:$model"))
      case AgentEvent.ModelCallCompleted(_, usage, _) =>
        base.copy(
          usage = Some(
            UsageSummary(
              modelCalls = 1,
              inputTokens = usage.inputTokens,
              outputTokens = usage.outputTokens,
              cachedInputTokens = usage.cachedInputTokens,
              reasoningOutputTokens = usage.reasoningOutputTokens
            )
          )
        )
      case AgentEvent.ModelToolCallDelta(_, callId, _, _) => base.copy(callId = Some(callId))
      case AgentEvent.ToolCallRequested(_, call, _)       =>
        base.copy(toolName = Some(call.name), callId = Some(call.id))
      case AgentEvent.ToolExecutionStarted(_, callId, _)          => base.copy(callId = Some(callId))
      case AgentEvent.ToolExecutionCompleted(_, callId, _, _)     => base.copy(callId = Some(callId))
      case AgentEvent.ToolExecutionFailed(_, callId, category, _) =>
        base.copy(callId = Some(callId), category = Some(category))
      case AgentEvent.GuardrailEvaluated(_, stage, allowed, _) =>
        base.copy(category = Some(s"$stage:${if allowed then "allowed" else "denied"}"))
      case AgentEvent.UsageUpdated(_, usage, _)    => base.copy(usage = Some(usage))
      case AgentEvent.RunFailed(_, category, _, _) => base.copy(category = Some(category))
      case _                                       => base

  /** 内部事件到稳定公开名称的单一映射，避免 HTTP、CLI 与调试界面因重构产生不同名称。 */
  def eventType(event: AgentEvent): String = event match
    case _: AgentEvent.RunCreated             => "RunCreated"
    case _: AgentEvent.RunStarted             => "RunStarted"
    case _: AgentEvent.RunResumed             => "RunResumed"
    case _: AgentEvent.StepStarted            => "StepStarted"
    case _: AgentEvent.ContextPrepared        => "ContextPrepared"
    case _: AgentEvent.ContextCompacted       => "ContextCompacted"
    case _: AgentEvent.ModelCallStarted       => "ModelCallStarted"
    case _: AgentEvent.ModelTextDelta         => "ModelTextDelta"
    case _: AgentEvent.ModelToolCallDelta     => "ModelToolCallDelta"
    case _: AgentEvent.ModelCallCompleted     => "ModelCallCompleted"
    case _: AgentEvent.ToolCallRequested      => "ToolCallRequested"
    case _: AgentEvent.ToolBatchPlanned       => "ToolBatchPlanned"
    case _: AgentEvent.ToolBatchStarted       => "ToolBatchStarted"
    case _: AgentEvent.ToolBatchCommitted     => "ToolBatchCommitted"
    case _: AgentEvent.ToolApprovalRequired   => "ToolApprovalRequired"
    case _: AgentEvent.ToolExecutionStarted   => "ToolExecutionStarted"
    case _: AgentEvent.ToolExecutionCompleted => "ToolExecutionCompleted"
    case _: AgentEvent.ToolExecutionFailed    => "ToolExecutionFailed"
    case _: AgentEvent.GuardrailEvaluated     => "GuardrailEvaluated"
    case _: AgentEvent.UsageUpdated           => "UsageUpdated"
    case _: AgentEvent.CheckpointSaved        => "CheckpointSaved"
    case _: AgentEvent.RunSuspended           => "RunSuspended"
    case _: AgentEvent.RunCompleted           => "RunCompleted"
    case _: AgentEvent.RunFailed              => "RunFailed"
    case _: AgentEvent.RunCancelled           => "RunCancelled"

  private def phase(event: AgentEvent): RunTimelinePhase = event match
    case _: AgentEvent.RunCreated | _: AgentEvent.RunStarted | _: AgentEvent.RunResumed |
        _: AgentEvent.StepStarted | _: AgentEvent.RunSuspended | _: AgentEvent.RunCompleted |
        _: AgentEvent.RunFailed | _: AgentEvent.RunCancelled =>
      RunTimelinePhase.Lifecycle
    case _: AgentEvent.ContextPrepared | _: AgentEvent.ContextCompacted =>
      RunTimelinePhase.Context
    case _: AgentEvent.ModelCallStarted | _: AgentEvent.ModelTextDelta | _: AgentEvent.ModelToolCallDelta |
        _: AgentEvent.ModelCallCompleted =>
      RunTimelinePhase.Model
    case _: AgentEvent.ToolCallRequested | _: AgentEvent.ToolBatchPlanned | _: AgentEvent.ToolBatchStarted |
        _: AgentEvent.ToolBatchCommitted | _: AgentEvent.ToolExecutionStarted |
        _: AgentEvent.ToolExecutionCompleted | _: AgentEvent.ToolExecutionFailed =>
      RunTimelinePhase.Tool
    case _: AgentEvent.ToolApprovalRequired                         => RunTimelinePhase.Approval
    case _: AgentEvent.GuardrailEvaluated                           => RunTimelinePhase.Guardrail
    case _: AgentEvent.UsageUpdated | _: AgentEvent.CheckpointSaved =>
      RunTimelinePhase.Persistence

  private def outcome(event: AgentEvent): RunTimelineOutcome = event match
    case _: AgentEvent.RunCreated | _: AgentEvent.RunStarted | _: AgentEvent.RunResumed |
        _: AgentEvent.StepStarted | _: AgentEvent.ModelCallStarted | _: AgentEvent.ToolBatchStarted |
        _: AgentEvent.ToolExecutionStarted =>
      RunTimelineOutcome.Started
    case _: AgentEvent.ContextPrepared | _: AgentEvent.ModelTextDelta | _: AgentEvent.ModelToolCallDelta |
        _: AgentEvent.ToolCallRequested | _: AgentEvent.ToolBatchPlanned | _: AgentEvent.GuardrailEvaluated |
        _: AgentEvent.UsageUpdated | _: AgentEvent.CheckpointSaved =>
      RunTimelineOutcome.Progress
    case _: AgentEvent.ContextCompacted | _: AgentEvent.ModelCallCompleted |
        _: AgentEvent.ToolBatchCommitted | _: AgentEvent.ToolExecutionCompleted |
        _: AgentEvent.RunCompleted =>
      RunTimelineOutcome.Succeeded
    case _: AgentEvent.ToolApprovalRequired | _: AgentEvent.RunSuspended =>
      RunTimelineOutcome.Waiting
    case _: AgentEvent.ToolExecutionFailed | _: AgentEvent.RunFailed =>
      RunTimelineOutcome.Failed
    case _: AgentEvent.RunCancelled => RunTimelineOutcome.Cancelled
