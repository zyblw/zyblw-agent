package com.zyblw.agent.inspection

import com.zyblw.agent.admin.RunSummaryView
import com.zyblw.agent.composition.{CompositionComparisonView, LiveComposition, RuntimeCompositionFingerprint}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{RunCommandRecord, RunCommandStore, RunStore}
import zio.*
import zio.json.*

/** 主模型账本中 Context 组成指针的低敏投影。
  *
  * `sectionDecisions` 与 `summarySourceDigestPrefix` 是 world-state 差量决策的唯读出口；管理面模型行和事故包摘要此前把它们全部丢弃。
  */
final case class ModelCallLineageView(
    estimatedTokens: Long,
    droppedMessages: Int,
    truncatedToolResults: Int,
    droppedMemories: Int,
    droppedRetrieval: Int,
    summaryCoveredMessages: Option[Int],
    summarySourceDigestPrefix: Option[String],
    sectionDecisions: Chunk[String],
    effectiveModelSettingsFingerprintPrefix: Option[String],
    toolDefinitionsFingerprintPrefix: Option[String],
    promptCompilerVersion: Option[String] = None,
    promptLayoutVersion: Option[String] = None,
    stablePrefixMessages: Option[Int] = None,
    stablePrefixFingerprintPrefix: Option[String] = None,
    promptPlanFingerprintPrefix: Option[String] = None
) derives JsonCodec

object ModelCallLineageView:
  val PrefixLength: Int = 16

  def from(lineage: ModelCallContextLineage): ModelCallLineageView =
    ModelCallLineageView(
      estimatedTokens = lineage.estimatedTokens,
      droppedMessages = lineage.droppedMessages,
      truncatedToolResults = lineage.truncatedToolResults,
      droppedMemories = lineage.droppedMemories,
      droppedRetrieval = lineage.droppedRetrieval,
      summaryCoveredMessages = lineage.summaryCoveredMessages,
      summarySourceDigestPrefix = lineage.summarySourceDigest.map(_.take(PrefixLength)),
      sectionDecisions = lineage.sectionDecisions,
      effectiveModelSettingsFingerprintPrefix =
        lineage.effectiveModelSettingsFingerprint.map(_.take(PrefixLength)),
      toolDefinitionsFingerprintPrefix = lineage.toolDefinitionsFingerprint.map(_.take(PrefixLength)),
      promptCompilerVersion = lineage.promptCompilerVersion,
      promptLayoutVersion = lineage.promptLayoutVersion,
      stablePrefixMessages = lineage.stablePrefixMessages,
      stablePrefixFingerprintPrefix = lineage.stablePrefixFingerprint.map(_.take(PrefixLength)),
      promptPlanFingerprintPrefix = lineage.promptPlanFingerprint.map(_.take(PrefixLength))
    )

/** 主模型账本轨迹行；不含 CanonicalModelRequest。 */
final case class ModelCallTrajectoryView(
    requestId: String,
    attempt: Int,
    status: String,
    provider: String,
    model: String,
    capturePolicy: String,
    fingerprintPrefix: String,
    messageCount: Int,
    toolCount: Int,
    lineage: ModelCallLineageView,
    errorCategory: Option[String],
    requestedProfile: Option[String] = None,
    routePolicyVersion: Option[String] = None,
    routeDecisionCodes: Chunk[String] = Chunk.empty,
    pricingFingerprintPrefix: Option[String] = None
) derives JsonCodec

object ModelCallTrajectoryView:
  val FingerprintPrefixLength: Int = 12

  def from(record: ModelCallExecutionRecord): ModelCallTrajectoryView =
    ModelCallTrajectoryView(
      requestId = record.requestId.asString,
      attempt = record.attempt,
      status = record.status.toString,
      provider = record.provider,
      model = record.model,
      capturePolicy = record.capturePolicy.toString,
      fingerprintPrefix = record.fingerprint.take(FingerprintPrefixLength),
      messageCount = record.messageCount,
      toolCount = record.toolCount,
      lineage = ModelCallLineageView.from(record.lineage),
      errorCategory = record.errorCategory,
      requestedProfile = record.routeDecision.map(_.requirement.profile.toString),
      routePolicyVersion = record.routeDecision.map(_.policyVersion),
      routeDecisionCodes = record.routeDecision.fold(Chunk.empty)(_.decisionCodes),
      pricingFingerprintPrefix = record.routeDecision.map(_.pricingFingerprint.take(FingerprintPrefixLength))
    )

/** 工具账本轨迹行。
  *
  * `attempt` 与 `Unknown` 用来区分“没执行”和“执行了但结果未知”；时间线事件与既有管理面都不读这两个字段。不携带工具参数或结果正文。
  */
final case class ToolExecutionTrajectoryView(
    batchId: String,
    ordinal: Int,
    callId: String,
    toolName: String,
    status: String,
    attempt: Int,
    isError: Option[Boolean],
    externalized: Boolean,
    updatedAtEpochMilli: Long
) derives JsonCodec

object ToolExecutionTrajectoryView:
  def from(record: ToolExecutionRecord): ToolExecutionTrajectoryView =
    ToolExecutionTrajectoryView(
      batchId = record.batchId,
      ordinal = record.ordinal,
      callId = record.callId,
      toolName = record.toolName,
      status = record.status.toString,
      attempt = record.attempt,
      isError = record.result.map(_.isError),
      externalized = record.result.exists(_.isExternalized),
      updatedAtEpochMilli = record.updatedAtEpochMilli
    )

/** 当前挂起事实的低敏投影；不含审批原因正文或人工输入 prompt。 */
final case class SuspensionTrajectoryView(
    kind: String,
    createdAtEpochMilli: Long,
    deadlineEpochMilli: Option[Long],
    expiryOutcome: String,
    toolName: Option[String],
    risk: Option[String]
) derives JsonCodec

object SuspensionTrajectoryView:
  def from(record: SuspensionRecord): SuspensionTrajectoryView =
    SuspensionTrajectoryView(
      kind = record.kind.kind,
      createdAtEpochMilli = record.createdAt.toEpochMilli,
      deadlineEpochMilli = record.deadline.map(_.toEpochMilli),
      expiryOutcome = record.expiryOutcome.toString,
      toolName = record.kind.approvalRequest.map(_.toolCall.name),
      risk = record.kind.approvalRequest.map(_.risk.toString)
    )

/** 控制面命令轨迹行。DeadLetter、lastFailure 与 manualRetryCount 与数据面时间线交叉可见。 */
final case class CommandTrajectoryView(
    commandId: String,
    payloadKind: String,
    status: String,
    attempt: Int,
    manualRetryCount: Int,
    lastFailure: Option[String],
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long
) derives JsonCodec

object CommandTrajectoryView:
  def from(record: RunCommandRecord): CommandTrajectoryView =
    CommandTrajectoryView(
      commandId = record.commandId.asString,
      payloadKind = record.payload.commandType,
      status = record.status.toString,
      attempt = record.attempt,
      manualRetryCount = record.manualRetryCount,
      lastFailure = record.lastFailure,
      createdAtEpochMilli = record.createdAt.toEpochMilli,
      updatedAtEpochMilli = record.updatedAt.toEpochMilli
    )

/** 一次 Run 的只读轨迹投影。不是第二事实源：所有字段都从权威状态、耐久事件、账本和命令队列派生。 */
final case class RunTrajectory(
    run: RunSummaryView,
    inspection: RunInspection,
    modelCalls: Chunk[ModelCallTrajectoryView],
    toolLedger: Chunk[ToolExecutionTrajectoryView],
    suspensions: Chunk[SuspensionTrajectoryView],
    commands: Chunk[CommandTrajectoryView],
    composition: CompositionComparisonView
) derives JsonCodec:
  def timeline: Chunk[RunTimelineEntry] = inspection.timeline
  def diagnostics: Chunk[RunDiagnostic] = inspection.diagnostics

object RunTrajectory:
  def build(
      state: AgentState,
      events: Chunk[PersistedAgentEvent],
      modelCalls: Chunk[ModelCallExecutionRecord] = Chunk.empty,
      toolLedger: Chunk[ToolExecutionRecord] = Chunk.empty,
      commands: Chunk[RunCommandRecord] = Chunk.empty,
      live: Option[RuntimeCompositionFingerprint] = None
  ): RunTrajectory =
    RunTrajectory(
      run = RunSummaryView.from(state),
      inspection = RunInspection.build(state, events),
      modelCalls = modelCalls.map(ModelCallTrajectoryView.from),
      toolLedger = toolLedger.map(ToolExecutionTrajectoryView.from),
      suspensions = Chunk.fromIterable(state.suspension.map(SuspensionTrajectoryView.from)),
      commands = commands.map(CommandTrajectoryView.from),
      composition = CompositionComparisonView.of(state.composition, live)
    )

/** 从权威存储装配轨迹。投影层不写回任何 Store。 */
object RunTrajectoryExporter:
  def fromStore(
      store: RunStore,
      runId: RunId,
      live: Option[LiveComposition] = None,
      commands: Option[RunCommandStore] = None
  ): IO[StoreError, RunTrajectory] =
    for
      state       <- store.load(runId)
      events      <- loadTimeline(store, runId, state.lastEventSequence)
      modelCalls  <- store.getModelCalls(runId)
      tools       <- store.listToolExecutions(runId)
      commandRows <- commands match
        case Some(commandStore) => commandStore.list(runId)
        case None               => ZIO.succeed(Chunk.empty[RunCommandRecord])
    yield RunTrajectory.build(
      state,
      events,
      modelCalls,
      tools,
      commandRows,
      live.map(_.freeze(state.definition))
    )

  private def loadTimeline(
      store: RunStore,
      runId: RunId,
      lastSequence: Long
  ): IO[StoreError, Chunk[PersistedAgentEvent]] =
    def page(after: Long, acc: Chunk[PersistedAgentEvent]): IO[StoreError, Chunk[PersistedAgentEvent]] =
      store.events(runId, after, 512).flatMap { batch =>
        val next = acc ++ batch
        if batch.isEmpty || batch.last.sequence >= lastSequence || batch.length < 512 then ZIO.succeed(next)
        else page(batch.last.sequence, next)
      }
    page(-1L, Chunk.empty)
