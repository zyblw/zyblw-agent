package com.zyblw.agent.rag

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import zio.*

final case class ChunkRepresentations(
    displayText: String,
    denseText: String,
    lexicalText: String,
    displaySha256: String,
    denseSha256: String,
    lexicalSha256: String
):
  require(displayText.nonEmpty, "displayText 不能为空")
  require(denseText.nonEmpty && lexicalText.nonEmpty, "dense/lexical 文本不能为空")
  require(displaySha256.matches("[0-9a-f]{64}"), "displaySha256 必须是小写 SHA-256")
  require(denseSha256.matches("[0-9a-f]{64}"), "denseSha256 必须是小写 SHA-256")
  require(lexicalSha256.matches("[0-9a-f]{64}"), "lexicalSha256 必须是小写 SHA-256")

object ChunkRepresentations:
  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  def of(displayText: String, denseText: String, lexicalText: String): ChunkRepresentations =
    ChunkRepresentations(
      displayText,
      denseText,
      lexicalText,
      sha256(displayText),
      sha256(denseText),
      sha256(lexicalText)
    )

  def uniform(text: String): ChunkRepresentations = of(text, text, text)

final case class CandidateBudgets(
    perBranch: Int = 80,
    fusion: Int = 80,
    rerankSeeds: Int = 8,
    expansion: Int = 12,
    maxEvidenceTokens: Long = 6_000L,
    maxChunksPerSource: Int = 2
):
  require(maxChunksPerSource > 0 && maxChunksPerSource <= 1000, "Source diversity budget invalid")
  require(perBranch > 0 && fusion > 0 && rerankSeeds > 0, "候选预算必须为正")
  require(perBranch <= 1000 && fusion <= 1000 && rerankSeeds <= 1000, "Candidate budget exceeds hard limit")
  require(
    expansion >= 0 && expansion <= 100 && maxEvidenceTokens > 0L && maxEvidenceTokens <= 1000000L,
    "expansion/token 预算无效"
  )

enum EvidenceDecision:
  case KeptSeed
  case KeptExpanded
  case DroppedDuplicate
  case DroppedBudget
  case DroppedDiversity
  case DroppedWithdrawn

final case class EvidenceItem(
    chunk: DocumentChunk,
    score: Double,
    seedChunkId: String,
    decision: EvidenceDecision,
    signals: Map[String, Double] = Map.empty
):
  def displayText: String = chunk.displayText

/** Safe retrieval provenance. No query, document body, credential or permission labels. */
final case class EvidenceSelection(
    documentId: String,
    chunkId: String,
    seedChunkId: String,
    decision: EvidenceDecision
)

final case class RetrievalDiagnostics(
    profileId: Option[String] = None,
    knowledgeSpaceId: Option[String] = None,
    degradedStages: Chunk[String] = Chunk.empty,
    selections: Chunk[EvidenceSelection] = Chunk.empty,
    maxEvidenceTokens: Long = 0L
)

final case class EvidenceBundle(
    items: Chunk[EvidenceItem],
    citations: Chunk[Citation],
    evidence: RetrievalEvidence,
    profileId: Option[IndexProfileId] = None,
    knowledgeSpaceId: Option[KnowledgeSpaceId] = None,
    degradedStages: Chunk[String] = Chunk.empty,
    budgets: CandidateBudgets = CandidateBudgets()
):
  def toRetrievalResult: RetrievalResult =
    val kept = items.filter(item =>
      item.decision == EvidenceDecision.KeptSeed || item.decision == EvidenceDecision.KeptExpanded
    )
    RetrievalResult(
      kept.map(item => RetrievalHit(item.chunk, item.score, item.signals)),
      citations,
      evidence,
      RetrievalDiagnostics(
        profileId.map(_.value),
        knowledgeSpaceId.map(_.value),
        degradedStages,
        items.map(item =>
          EvidenceSelection(item.chunk.documentId, item.chunk.id, item.seedChunkId, item.decision)
        ),
        budgets.maxEvidenceTokens
      )
    )

object ContextAssembler:
  private val DefaultTokenCounter: TokenCounter = TokenCounter.Cl100k

  /** 去重、token/多样性裁剪、附带 expansion，citation 只用 displayText。 */
  def assemble(
      seeds: Chunk[RetrievalHit],
      expanded: Chunk[RetrievalHit],
      evidence: RetrievalEvidence,
      budgets: CandidateBudgets,
      profileId: Option[IndexProfileId] = None,
      knowledgeSpaceId: Option[KnowledgeSpaceId] = None,
      degradedStages: Chunk[String] = Chunk.empty,
      withdrawnDocumentIds: Set[String] = Set.empty,
      tokenCounter: TokenCounter = DefaultTokenCounter
  ): EvidenceBundle =
    val ranked = seeds.take(budgets.rerankSeeds).map(_ -> EvidenceDecision.KeptSeed) ++
      expanded
        .filterNot(hit =>
          seeds.exists(seed => seed.chunk.documentId == hit.chunk.documentId && seed.chunk.id == hit.chunk.id)
        )
        .distinctBy(hit => hit.chunk.documentId -> hit.chunk.id)
        .take(budgets.expansion)
        .map(_ -> EvidenceDecision.KeptExpanded)
    val selected = ranked.foldLeft(AssembleState()) { case (state, (hit, keep)) =>
      val sourceKey = Option(hit.chunk.sourceUri).filter(_.nonEmpty).getOrElse(hit.chunk.documentId)
      val tokens    = tokenCounter.count(hit.chunk.displayText).toLong
      if state.items.exists(item =>
          kept(item) && item.chunk.documentId == hit.chunk.documentId && item.chunk.id == hit.chunk.id
        )
      then state.drop(hit, keep, EvidenceDecision.DroppedDuplicate)
      else if keep == EvidenceDecision.KeptExpanded &&
        !state.items.exists(item =>
          item.decision == EvidenceDecision.KeptSeed &&
            item.chunk.documentId == hit.chunk.documentId && hit.chunk.lineage
              .flatMap(_.seedChunkId)
              .contains(item.chunk.id)
        )
      then state.drop(hit, keep, EvidenceDecision.DroppedBudget)
      else if withdrawnDocumentIds.contains(hit.chunk.documentId) then
        state.drop(hit, keep, EvidenceDecision.DroppedWithdrawn)
      else if state.perSource.getOrElse(sourceKey, 0) >= budgets.maxChunksPerSource then
        state.drop(hit, keep, EvidenceDecision.DroppedDiversity)
      else if state.tokens + tokens > budgets.maxEvidenceTokens then
        state.drop(hit, keep, EvidenceDecision.DroppedBudget)
      else state.keep(hit, keep, sourceKey, tokens)
    }
    val items     = selected.items
    val citations = items.filter(kept).zipWithIndex.map { case (item, index) =>
      val origins = item.chunk.lineage.fold(Chunk.empty[DocumentOrigin])(_.origins)
      Citation(
        id = s"cite-${index + 1}",
        sourceUri = item.chunk.sourceUri,
        excerpt = item.displayText.take(500),
        score = item.score,
        pageNumbers = origins.map(_.pageNumber).distinct,
        origins = origins
      )
    }
    val reasons = Chunk.fromIterable(
      List(
        Option.when(selected.dropped.contains(EvidenceDecision.DroppedBudget))("dropped-budget"),
        Option.when(selected.dropped.contains(EvidenceDecision.DroppedDiversity))("dropped-diversity"),
        Option.when(selected.dropped.contains(EvidenceDecision.DroppedWithdrawn))("dropped-withdrawn")
      ).flatten
    )
    val acceptedSeeds = items.filter(_.decision == EvidenceDecision.KeptSeed)
    val finalEvidence = evidence.copy(
      status =
        if acceptedSeeds.isEmpty && evidence.status == RetrievalEvidenceStatus.Supported then
          RetrievalEvidenceStatus.NoAcceptedHits
        else evidence.status,
      acceptedCount = acceptedSeeds.length,
      topAcceptedScore = acceptedSeeds
        .map(item => DefaultRetriever.relevanceScore(RetrievalHit(item.chunk, item.score, item.signals)))
        .maxOption
    )
    EvidenceBundle(
      items,
      citations,
      finalEvidence,
      profileId,
      knowledgeSpaceId,
      degradedStages ++ reasons,
      budgets
    )

  private def kept(item: EvidenceItem): Boolean =
    item.decision == EvidenceDecision.KeptSeed || item.decision == EvidenceDecision.KeptExpanded

  final private case class AssembleState(
      items: Chunk[EvidenceItem] = Chunk.empty,
      tokens: Long = 0L,
      perSource: Map[String, Int] = Map.empty,
      dropped: Set[EvidenceDecision] = Set.empty
  ):
    def keep(hit: RetrievalHit, keep: EvidenceDecision, sourceKey: String, added: Long): AssembleState =
      val seed = hit.chunk.lineage.flatMap(_.seedChunkId).getOrElse(hit.chunk.id)
      copy(
        items = items :+ EvidenceItem(hit.chunk, hit.score, seed, keep, hit.signals),
        tokens = tokens + added,
        perSource = perSource.updated(sourceKey, perSource.getOrElse(sourceKey, 0) + 1)
      )

    def drop(hit: RetrievalHit, keep: EvidenceDecision, reason: EvidenceDecision): AssembleState =
      val seed = hit.chunk.lineage.flatMap(_.seedChunkId).getOrElse(hit.chunk.id)
      val _    = keep
      copy(
        items = items :+ EvidenceItem(hit.chunk, hit.score, seed, reason, hit.signals),
        dropped = dropped + reason
      )
