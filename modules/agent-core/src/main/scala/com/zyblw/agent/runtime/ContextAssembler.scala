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
  /** 保存本轮实际交付给模型的检索引用与证据摘要。
    *
    * 引用与上一轮完全相同时保持零写入，避免长会话里每轮都产生一次无意义的 version 递增；当本回合未触发任何检索评估时保留既有引用。
    */
  def persistCitations(
      state: AgentState,
      sources: ContextSources,
      evaluated: Boolean = false
  ): IO[AgentError, AgentState] =
    val hasEvaluation =
      evaluated || sources.retrieval.nonEmpty || sources.citations.nonEmpty || sources.retrievalEvidence.nonEmpty
    val nextCitations =
      if !hasEvaluation then state.citations
      else sources.citations.distinctBy(_.id).take(ContextAssembler.MaxCitations)
    val nextEvidence = sources.retrievalEvidence.orElse(state.retrievalEvidence)
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

  /** 根据 ContextManager 实际装箱结果，把检索到的候选引用与证据收敛为真正交付给模型的子集，并保持顺序连续编号。 */
  def deliveredSources(sources: ContextSources, prepared: PreparedContext): ContextSources =
    ContextAssembler.deliveredSources(sources, prepared)

  /** 构建上下文并按实际装箱结果原子保存引用证据、摘要边界与辅助模型用量。
    *
    * 如果 Worker 在压缩成功后、主模型请求前崩溃，恢复会从 `contextSummary` 的覆盖边界复用摘要，不会再次压缩相同前缀。 Usage 与摘要同事务提交，确保辅助模型调用不会逃逸 Run 的
    * token/model-call 预算。
    */
  def build(
      state: AgentState,
      agent: AgentDefinition,
      sources: ContextSources
  ): IO[AgentError, (AgentState, PreparedContext)] =
    val hadEvaluation =
      sources.retrieval.nonEmpty || sources.citations.nonEmpty || sources.retrievalEvidence.nonEmpty
    for
      prepared <- contextManager.build(state, agent, sources, agent.contextPolicy)
      delivered = deliveredSources(sources, prepared)
      cited <- persistCitations(state, delivered, evaluated = hadEvaluation)
      next  <- persistPrepared(cited, prepared)
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

  /** 根据 ContextManager 实际装箱结果，把检索到的候选引用与证据收敛为真正交付给模型的子集，并保持顺序连续编号。 */
  def deliveredSources(sources: ContextSources, prepared: PreparedContext): ContextSources =
    if sources.retrieval.isEmpty then sources
    else
      val deliveredSet          = prepared.deliveredDocumentIds.toSet
      val rawDeliveredRetrieval = sources.retrieval.filter(doc => deliveredSet.contains(doc.id))
      val rawDeliveredCitations =
        if sources.citations.length == sources.retrieval.length then
          sources.retrieval
            .zip(sources.citations)
            .collect { case (doc, cite) if deliveredSet.contains(doc.id) => cite }
        else
          sources.citations.filter(cite =>
            deliveredSet.contains(cite.id) ||
              deliveredSet.contains(cite.id.stripPrefix("evidence-")) ||
              deliveredSet.contains(cite.id.stripPrefix("cite-"))
          )
      val anyDocDropped      = rawDeliveredRetrieval.length < sources.retrieval.length
      val deliveredRetrieval = rawDeliveredRetrieval.zipWithIndex.map { case (doc, idx) =>
        val displayId = prepared.deliveredDocumentDisplayIds.getOrElse(
          doc.id,
          if anyDocDropped then ContextDocument.displayIdFor(doc.id, idx + 1) else doc.id
        )
        if displayId == doc.id then doc else doc.copy(id = displayId)
      }
      val deliveredCitations = rawDeliveredCitations.zipWithIndex.map { case (cite, idx) =>
        val matchedDocDisplayId =
          rawDeliveredRetrieval
            .lift(idx)
            .flatMap(doc => prepared.deliveredDocumentDisplayIds.get(doc.id))
            .orElse(prepared.deliveredDocumentDisplayIds.get(cite.id))
        val alignedId =
          if anyDocDropped then ContextDocument.alignCitationId(cite.id, matchedDocDisplayId, idx + 1)
          else cite.id
        if alignedId == cite.id then cite else cite.copy(id = alignedId)
      }
      if prepared.deliveredDocumentIds == sources.retrieval.map(_.id) &&
        deliveredRetrieval == sources.retrieval &&
        deliveredCitations == sources.citations
      then sources
      else
        val deliveredEvidence = sources.retrievalEvidence.map { ev =>
          val candidateCount = ev.candidateCount.max(sources.retrieval.length)
          val acceptedCount  =
            if sources.citations.nonEmpty then deliveredCitations.length
            else deliveredRetrieval.length
          val status =
            if acceptedCount == 0 && (ev.status == "Supported" || ev.status == "Accepted" || sources.citations.nonEmpty)
            then "NoAcceptedHits"
            else ev.status
          val topScore =
            if acceptedCount == 0 then None
            else if deliveredCitations.nonEmpty then deliveredCitations.map(_.score).maxOption
            else deliveredRetrieval.flatMap(_.score).maxOption.orElse(ev.topAcceptedScore)
          ev.copy(
            status = status,
            candidateCount = candidateCount,
            acceptedCount = acceptedCount,
            topAcceptedScore = topScore
          )
        }
        sources.copy(
          retrieval = deliveredRetrieval,
          citations = deliveredCitations,
          retrievalEvidence = deliveredEvidence
        )
