package com.zyblw.agent.runtime

import com.zyblw.agent.composition.RuntimeComposition
import com.zyblw.agent.context.PreparedContext
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{ModelCallWrite, RunStore}
import com.zyblw.agent.model.*
import java.time.Instant
import zio.*

/** 主模型调用的 Intent → Effect → Settlement 边界。
  *
  * 模型调用与工具调用一样是不确定的外部副作用：请求可能已经发出并计费，而进程在收到响应前消失。因此调用前先持久化 Intent（`Dispatched` 账本 + `pendingModelCall`
  * 游标），调用后必定结算为 `Succeeded`、`Failed` 或 `Unknown`，绝不留下悬挂的 `Dispatched` 让恢复误以为可以重放。
  */
final private[agent] class ModelCallCoordinator(
    store: RunStore,
    committer: RunCommitter,
    publisher: RunEventPublisher,
    capturePolicy: CapturePolicy
):
  import ModelCallCoordinator.Dispatched

  /** 持久化 Intent，再按 CapturePolicy 重建请求并调用 Provider。
    *
    * `Disabled` 下没有账本可写，保持即时调用；但一旦启用了路由，必须至少升级到 `MetadataOnly`——否则无法解释一次回答 来自哪个候选模型。
    */
  def invoke(
      state: AgentState,
      agent: AgentDefinition,
      prepared: PreparedContext,
      routing: ModelRouterGateway.Routing,
      provider: String,
      modelName: String,
      startedAt: Long
  ): IO[AgentError, Dispatched] =
    val effectiveCapture =
      if routing.routed && capturePolicy == CapturePolicy.Disabled then CapturePolicy.MetadataOnly
      else capturePolicy
    effectiveCapture match
      case CapturePolicy.Disabled =>
        publisher.emit(AgentEvent.ModelCallStarted(state.runId, provider, modelName, startedAt)) *>
          stream(state, routing.request, routing.adapter).map(Dispatched(state, _, None))
      case policy =>
        persistThenInvoke(state, agent, prepared, routing, provider, modelName, startedAt, policy)

  /** 恢复时找出仍未结算的模型账本；`pendingModelCall` 优先，其次扫描账本。
    *
    * 两个来源都要看：游标是快路径，但状态提交与账本写入之间的窗口里进程可能消失，此时只有账本知道有一次调用悬挂。
    */
  def findOpen(state: AgentState): IO[AgentError, Option[PendingModelCall]] =
    state.pendingModelCall match
      case Some(pending) => ZIO.some(pending)
      case None          =>
        store.getModelCalls(state.runId).map { calls =>
          calls.find(record => AgentKernel.openModelCall(record.status)).map(pendingFrom)
        }

  /** TX1 之后崩溃：把 Dispatched/Prepared 标为 Unknown，Run 进入 Failed，禁止自动重放。
    *
    * 返回类型是 `Nothing`：本方法先收口账本，然后必定以 `ModelCallUncertain` 失败。
    */
  def closeUncertain(state: AgentState, pending: PendingModelCall): IO[AgentError, Nothing] =
    for
      existing <- store.getModelCall(state.runId, pending.requestId)
      at       <- RunClock.instant
      now = at.toEpochMilli
      _ <- ZIO.foreachDiscard(existing) { record =>
        ZIO
          .fromEither(record.verifyFrozenTools)
          .mapError(reason => AgentError.PersistenceFailure(s"模型账本工具合同损坏: $reason"))
      }
      _ <- AgentKernel.modelCallClosure(pending, existing) match
        case AgentKernel.ModelCallClosure.SettleUnknownAndFail(record, reason) =>
          failConflict(
            state,
            reason,
            at,
            Chunk(AgentEvent.ModelCallUnknown(state.runId, pending.requestId.asString, now)),
            Some(
              ModelCallWrite.Transition(
                record.status,
                record.attempt,
                record.copy(status = ModelCallStatus.Unknown, updatedAtEpochMilli = now)
              )
            )
          )
        case AgentKernel.ModelCallClosure.FailIfRunning(reason) =>
          failConflict(state, reason, at, Chunk.empty, None).when(state.status == RunStatus.Running).unit
        case AgentKernel.ModelCallClosure.Fail(reason) =>
          failConflict(state, reason, at, Chunk.empty, None)
        case AgentKernel.ModelCallClosure.Inconsistent(reason) =>
          ZIO.fail(AgentError.InvalidResume(state.runId, reason))
        case AgentKernel.ModelCallClosure.MissingRecord(reason) =>
          ZIO.fail(AgentError.InvalidResume(state.runId, reason))
      outcome <- ZIO.fail(AgentError.ModelCallUncertain(state.runId, pending.requestId.asString))
    yield outcome

  /** 消费 Provider 的语义流并实时转发安全增量。
    *
    * `ReasoningDelta` 被明确丢弃，防止隐藏推理进入日志或 HTTP；最终必须收到 `Completed`，否则说明 Provider 适配器违反 协议。ZStream 被中断时，取消会继续传播到
    * Provider 的 HTTP 请求。
    */
  def stream(
      state: AgentState,
      request: ChatRequest,
      adapter: ChatModel
  ): IO[AgentError, ChatResponse] =
    for
      completed <- Ref.make(Option.empty[ChatResponse])
      _         <- adapter
        .stream(request)
        .mapZIO {
          case ModelStreamEvent.ResponseStarted(_) => ZIO.unit
          case ModelStreamEvent.TextDelta(value)   =>
            emitNow(at => AgentEvent.ModelTextDelta(state.runId, value, at))
          case ModelStreamEvent.ReasoningDelta(_)                  => ZIO.unit
          case ModelStreamEvent.ToolCallStarted(_, _)              => ZIO.unit
          case ModelStreamEvent.ToolCallDelta(callId, _, fragment) =>
            emitNow(at => AgentEvent.ModelToolCallDelta(state.runId, callId, fragment, at))
          case ModelStreamEvent.ToolCallCompleted(_) => ZIO.unit
          case ModelStreamEvent.UsageUpdated(usage)  =>
            emitNow(at =>
              AgentEvent.UsageUpdated(
                state.runId,
                UsageSummary(
                  inputTokens = usage.inputTokens,
                  outputTokens = usage.outputTokens,
                  cachedInputTokens = usage.cachedInputTokens,
                  reasoningOutputTokens = usage.reasoningOutputTokens,
                  cacheWriteInputTokens = usage.cacheWriteInputTokens
                ),
                at
              )
            )
          case ModelStreamEvent.Completed(response) => completed.set(Some(response))
        }
        .runDrain
      response <- completed.get.someOrFail(AgentError.InvalidModelResponse("模型流结束但没有 Completed 事件"))
    yield response

  private def persistThenInvoke(
      state: AgentState,
      agent: AgentDefinition,
      prepared: PreparedContext,
      routing: ModelRouterGateway.Routing,
      provider: String,
      modelName: String,
      startedAt: Long,
      policy: CapturePolicy
  ): IO[AgentError, Dispatched] =
    val request = routing.request
    for
      requestId <- ModelRequestId.random
      at        <- RunClock.instant
      now           = at.toEpochMilli
      instructionFp = agent.instructionSet.map(_.fingerprint)
      fingerprint   = CanonicalModelRequest.fingerprint(request, instructionFp)
      record        = ModelCallExecutionRecord(
        runId = state.runId,
        requestId = requestId,
        attempt = 1,
        status = ModelCallStatus.Dispatched,
        provider = provider,
        model = modelName,
        capturePolicy = policy,
        fingerprint = fingerprint,
        messageCount = request.messages.length,
        toolCount = request.tools.length,
        lineage = lineageOf(state, prepared, request, routing.capabilities),
        instructionFingerprint = instructionFp,
        canonicalRequest =
          Option.when(policy == CapturePolicy.Replayable)(CanonicalModelRequest.from(request)),
        updatedAtEpochMilli = now,
        routeDecision = routing.decision
      )
      // 路由请求在 Intent 阶段预留一次模型调用；结算时先抵消再写入真实 usage。
      dispatchedUsage =
        if routing.routed then state.usage.copy(modelCalls = state.usage.modelCalls + 1)
        else state.usage
      intentState <- committer.commitAll(
        state,
        state.copy(
          usage = dispatchedUsage,
          budget = state.budget.copy(consumed = dispatchedUsage),
          pendingModelCall = Some(PendingModelCall(requestId, 1, fingerprint, policy, provider, modelName)),
          updatedAt = at
        ),
        NonEmptyChunk(
          AgentEvent.ModelCallPrepared(
            state.runId,
            requestId.asString,
            provider,
            modelName,
            fingerprint,
            policy.toString,
            request.messages.length,
            request.tools.length,
            now
          )
        ),
        Some(ModelCallWrite.Insert(record))
      )
      _ <- publisher.emit(AgentEvent.ModelCallStarted(state.runId, provider, modelName, startedAt))
      // Replayable 从账本重建请求：如果重建结果与原请求不等价，问题必须在这里暴露，而不是在事后回放时。
      dispatch <-
        if policy == CapturePolicy.Replayable then
          ZIO.fromEither(record.toChatRequest).mapError(AgentError.InvalidConfiguration(_))
        else ZIO.succeed(request)
      response <- stream(intentState, dispatch, routing.adapter).catchAllCause { cause =>
        settleInterrupted(intentState, record, cause).uninterruptible *> ZIO.failCause(cause)
      }
    yield Dispatched(intentState, response, Some(record))

  /** 进程内调用失败时结算账本，避免留下可被误重放的 Dispatched。崩溃路径走 [[closeUncertain]]。
    *
    * 中断与路由调用都记为 `Unknown`：前者无法判断请求是否已经发出，后者已经预留了调用额度。
    */
  private def settleInterrupted(
      state: AgentState,
      record: ModelCallExecutionRecord,
      cause: Cause[AgentError]
  ): IO[AgentError, Unit] =
    RunClock.instant.flatMap { at =>
      val now    = at.toEpochMilli
      val status = AgentKernel
        .interruptedModelCallStatus(cause.isInterrupted, record.routeDecision.nonEmpty)
      val event =
        if status == ModelCallStatus.Unknown then
          AgentEvent.ModelCallUnknown(state.runId, record.requestId.asString, now)
        else AgentEvent.ModelCallCompleted(state.runId, TokenUsage(), now)
      committer
        .commitAll(
          state,
          state.copy(pendingModelCall = None, updatedAt = at),
          NonEmptyChunk(event),
          Some(
            ModelCallWrite.Transition(
              ModelCallStatus.Dispatched,
              record.attempt,
              record.copy(
                status = status,
                errorCategory = cause.failureOption.map(_.category.toString),
                updatedAtEpochMilli = now
              )
            )
          )
        )
        .unit
    }

  /** 把 Run 收口为 Failed 并解释原因；模型账本变更与状态在同一事务提交。 */
  private def failConflict(
      state: AgentState,
      reason: String,
      at: Instant,
      extraEvents: Chunk[AgentEvent],
      modelCall: Option[ModelCallWrite]
  ): IO[AgentError, Unit] =
    committer
      .commitAll(
        state,
        state.copy(status = RunStatus.Failed, pendingModelCall = None, updatedAt = at),
        NonEmptyChunk
          .fromChunk(
            extraEvents :+ AgentEvent
              .RunFailed(state.runId, ErrorCategory.Conflict.toString, reason, at.toEpochMilli)
          )
          .get,
        modelCall
      )
      .unit

  private def lineageOf(
      state: AgentState,
      prepared: PreparedContext,
      request: ChatRequest,
      capabilities: ModelCapabilities
  ): ModelCallContextLineage =
    ModelCallContextLineage(
      estimatedTokens = prepared.usage.estimatedTokens,
      droppedMessages = prepared.usage.droppedMessages,
      truncatedToolResults = prepared.usage.truncatedToolResults,
      droppedMemories = prepared.usage.droppedMemories,
      droppedRetrieval = prepared.usage.droppedRetrieval,
      summaryCoveredMessages = prepared.summaryUpdate
        .map(_.coveredMessages)
        .orElse(state.contextSummary.map(_.coveredMessages)),
      summarySourceDigest =
        prepared.summaryUpdate.map(_.sourceDigest).orElse(state.contextSummary.map(_.sourceDigest)),
      sectionDecisions = prepared.sectionDecisions.map(_.lineageEntry),
      effectiveModelSettingsFingerprint = Some(RuntimeComposition.modelSettingsFingerprint(request.settings)),
      toolDefinitionsFingerprint = Some(CanonicalModelRequest.toolDefinitionsFingerprint(request.tools)),
      promptCompilerVersion = Some(prepared.promptLineage.compilerVersion),
      promptLayoutVersion = Some(prepared.promptLineage.layoutVersion),
      stablePrefixMessages = Some(prepared.promptLineage.stablePrefixMessages),
      stablePrefixFingerprint = Some(
        CanonicalModelRequest.stablePrefixFingerprint(request, prepared.promptLineage.stablePrefixMessages)
      ),
      promptPlanFingerprint = Some(prepared.promptLineage.planFingerprint),
      modelCapabilitiesFingerprint = Some(capabilities.fingerprint)
    )

  private def pendingFrom(record: ModelCallExecutionRecord): PendingModelCall =
    PendingModelCall(
      record.requestId,
      record.attempt,
      record.fingerprint,
      record.capturePolicy,
      record.provider,
      record.model
    )

  private def emitNow(event: Long => AgentEvent): UIO[Unit] =
    RunClock.millis.flatMap(at => publisher.emit(event(at)))

private[agent] object ModelCallCoordinator:
  /** 一次已完成的 Provider 调用：Intent 提交后的状态、响应与待结算账本。
    *
    * `record` 为 `None` 表示 `CapturePolicy.Disabled`，此时没有账本需要结算。
    */
  final case class Dispatched(
      state: AgentState,
      response: ChatResponse,
      record: Option[ModelCallExecutionRecord]
  )
