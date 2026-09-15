package com.zyblw.agent.runtime

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.UntrustedSnippet
import zio.*

/** 模型调用前的上下文装配与耐久收口。
  *
  * 两次写入都在主模型请求之前完成：引用与证据是"模型看到了哪些外部资料"的事实，压缩摘要边界与辅助模型用量是"为了装配 这次上下文已经花掉多少预算"的事实。任一项在崩溃后丢失都会让恢复无法解释或重复计费。
  */
final private[agent] class ContextAssembler(
    contextManager: ContextManager,
    committer: RunCommitter
):
  /** 保存本轮检索引用与证据摘要。
    *
    * 引用与上一轮完全相同时保持零写入，避免长会话里每轮都产生一次无意义的 version 递增。
    */
  def persistCitations(state: AgentState, sources: ContextSources): IO[AgentError, AgentState] =
    val nextCitations = sources.citations.distinctBy(_.id).take(ContextAssembler.MaxCitations)
    val nextEvidence  = sources.retrievalEvidence.orElse(state.retrievalEvidence)
    if nextCitations == state.citations && nextEvidence == state.retrievalEvidence then ZIO.succeed(state)
    else
      for
        now <- RunClock.instant
        evidence = nextEvidence.getOrElse(RunRetrievalEvidence("NotEvaluated", 0, 0))
        next     = state.copy(
          citations = nextCitations,
          retrievalEvidence = nextEvidence,
          updatedAt = now
        )
        saved <- committer.commitAll(
          state,
          next,
          NonEmptyChunk(AgentEvent.RetrievalCited(state.runId, nextCitations, evidence, now.toEpochMilli))
        )
      yield saved

  /** 构建上下文并原子保存摘要边界与辅助模型用量。
    *
    * 如果 Worker 在压缩成功后、主模型请求前崩溃，恢复会从 `contextSummary` 的覆盖边界复用摘要，不会再次压缩相同前缀。 Usage 与摘要同事务提交，确保辅助模型调用不会逃逸 Run 的
    * token/model-call 预算。
    */
  def build(
      state: AgentState,
      agent: AgentDefinition,
      sources: ContextSources
  ): IO[AgentError, (AgentState, PreparedContext)] =
    for
      prepared <- contextManager.build(state, agent, sources, agent.contextPolicy)
      next     <- persistPrepared(state, prepared)
    yield (next, prepared)

  /** 检索文档、非 Metadata section 和 Memory 都按不可信数据检查；Metadata 目录不含正文。 */
  def untrustedSnippets(sources: ContextSources): Chunk[UntrustedSnippet] =
    sources.retrieval.map(document =>
      UntrustedSnippet.fromDocument(document.id, document.content, Set("retrieval", document.source))
    ) ++ sources.sections
      .filter(_.sensitivity != ContextPayloadSensitivity.Metadata)
      .map(section =>
        UntrustedSnippet.fromDocument(s"section:${section.id}", section.payload, Set("section"))
      ) ++ sources.memories.map(memory =>
      UntrustedSnippet.fromDocument(s"memory:${memory.key}", memory.content, Set("memory"))
    )

  private def persistPrepared(
      state: AgentState,
      prepared: PreparedContext
  ): IO[AgentError, AgentState] =
    val calls = prepared.usage.compressionModelCalls
    if prepared.summaryUpdate.isEmpty && calls == 0 then
      ZIO.succeed(state.copy(worldSectionCursors = prepared.worldSectionCursors))
    else
      for
        usage <- ZIO
          .attempt(state.usage.addModels(prepared.compressionUsage, calls))
          .mapError(error => AgentError.Unexpected("累计 Context 压缩用量失败", Some(error)))
        _   <- ZIO.fromEither(AgentKernel.validateUsage(state.budget.limits, usage))
        now <- RunClock.instant
        checkpoint = prepared.summaryUpdate.orElse(state.contextSummary)
        covered    = checkpoint.fold(0)(_.coveredMessages)
        version    = checkpoint.fold("tool-output-v1")(_.compressorVersion)
        next       = state.copy(
          usage = usage,
          budget = state.budget.copy(consumed = usage),
          contextSummary = checkpoint,
          worldSectionCursors = prepared.worldSectionCursors,
          updatedAt = now
        )
        compacted = AgentEvent.ContextCompacted(
          state.runId,
          covered,
          calls,
          prepared.compressionUsage,
          version,
          now.toEpochMilli
        )
        events =
          if calls > 0 then
            NonEmptyChunk(compacted, AgentEvent.UsageUpdated(state.runId, usage, now.toEpochMilli))
          else NonEmptyChunk(compacted)
        saved <- committer.commitAll(state, next, events)
      yield saved

private[agent] object ContextAssembler:
  /** 引用数量上限：Citation 进入耐久状态，长会话必须有界。 */
  private val MaxCitations = 32
