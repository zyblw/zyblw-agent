package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 结构 RAG 评测金标样本。 */
final case class StructuralTestCase(
    id: String,
    query: String,
    expectedDocumentIds: Set[String],
    expectedSectionIds: Set[String],
    expectedChunkIds: Set[String] = Set.empty,
    recipe: RetrievalRecipe = RetrievalRecipe.BookGrounded,
    tags: Set[String] = Set.empty,
    expectedNodesByDocument: Map[String, Set[String]] = Map.empty
) derives JsonCodec

/** 单个用例的评测度量结果。 */
final case class StructuralCaseMetrics(
    caseId: String,
    documentRecall: Double,
    nodeRecall: Double,
    chunkRecall: Double,
    citationPrecision: Double,
    hitCount: Int,
    citationCount: Int,
    evidenceStatus: RetrievalEvidenceStatus,
    durationMs: Long,
    selectedNodeIds: Set[String],
    expectedNodeCount: Int = 0,
    expectedChunkCount: Int = 0
) derives JsonCodec

/** 多级评估汇总报告。 */
final case class StructuralEvalReport(
    totalCases: Int,
    documentRecall: Double,
    nodeRecall: Double,
    chunkRecall: Double,
    citationPrecision: Double,
    groundedRate: Double,
    averageDurationMs: Double,
    cases: Chunk[StructuralCaseMetrics],
    evaluatedNodeCases: Int = 0,
    evaluatedChunkCases: Int = 0
) derives JsonCodec

object StructuralRagEval:
  def evaluateCase(
      testCase: StructuralTestCase,
      retriever: Retriever,
      scope: RetrievalScope,
      limit: Int = 5
  ): IO[RetrievalError, StructuralCaseMetrics] =
    val request = RetrievalRequest(
      testCase.query,
      scope,
      limit,
      recipe = Some(testCase.recipe)
    )
    val qualifiedGold =
      if testCase.expectedNodesByDocument.nonEmpty then testCase.expectedNodesByDocument
      else testCase.expectedDocumentIds.headOption.map(_ -> testCase.expectedSectionIds).toMap
    val validGold = testCase.expectedDocumentIds.nonEmpty &&
      (testCase.expectedSectionIds.isEmpty || testCase.expectedDocumentIds.size == 1 || testCase.expectedNodesByDocument.nonEmpty) &&
      qualifiedGold.keySet.subsetOf(testCase.expectedDocumentIds)
    ZIO.fail(AgentError.RetrievalFailed("evaluation gold documents/nodes invalid")).unless(validGold) *>
      retriever.retrieve(request).timed.map { case (elapsed, result) =>
        val duration        = elapsed.toMillis
        val retrievedDocIds = result.hits.map(_.chunk.documentId).toSet
        val docRecall       =
          if testCase.expectedDocumentIds.isEmpty then 1.0
          else
            val hit = retrievedDocIds.intersect(testCase.expectedDocumentIds).size
            hit.toDouble / testCase.expectedDocumentIds.size.toDouble

        val selectedNodes     = result.diagnostics.structureSelections.map(_.nodeId).toSet
        val expectedNodes     = qualifiedGold.iterator.flatMap((doc, ids) => ids.map(doc -> _)).toSet
        val selectedQualified =
          result.diagnostics.structureSelections.map(s => s.documentId -> s.nodeId).toSet
        val nodeRecall =
          if expectedNodes.isEmpty then 0.0
          else selectedQualified.intersect(expectedNodes).size.toDouble / expectedNodes.size

        val retrievedChunkIds = result.hits.map(_.chunk.id).toSet
        val chunkRecall       =
          if testCase.expectedChunkIds.isEmpty then 0.0
          else
            val hit = retrievedChunkIds.intersect(testCase.expectedChunkIds).size
            hit.toDouble / testCase.expectedChunkIds.size.toDouble

        val citationPrecision =
          if result.citations.isEmpty then 0.0
          else
            val validCites = result.citations.count { c =>
              c.chunkId.exists(id =>
                result.hits
                  .find(_.chunk.id == id)
                  .exists(h =>
                    testCase.expectedDocumentIds.contains(h.chunk.documentId) &&
                      (testCase.expectedChunkIds.isEmpty || testCase.expectedChunkIds.contains(h.chunk.id)) &&
                      c.sourceUri == h.chunk.sourceUri && c.excerpt.nonEmpty && h.chunk.displayText
                        .contains(c.excerpt) &&
                      c.pageNumbers.toSet
                        .equals(h.chunk.lineage.fold(Set.empty[Int])(_.pageNumbers.toSet)) &&
                      c.origins == h.chunk.lineage.fold(Chunk.empty[DocumentOrigin])(_.origins)
                  )
              )
            }
            validCites.toDouble / result.citations.length.toDouble

        StructuralCaseMetrics(
          testCase.id,
          docRecall,
          nodeRecall,
          chunkRecall,
          citationPrecision,
          result.hits.length,
          result.citations.length,
          result.evidence.status,
          duration,
          selectedNodes,
          expectedNodes.size,
          testCase.expectedChunkIds.size
        )
      }

  def evaluateAll(
      cases: Chunk[StructuralTestCase],
      retriever: Retriever,
      scope: RetrievalScope,
      limit: Int = 5
  ): IO[RetrievalError, StructuralEvalReport] =
    if cases.isEmpty then ZIO.succeed(StructuralEvalReport(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Chunk.empty))
    else
      ZIO.foreach(cases)(evaluateCase(_, retriever, scope, limit)).map { metrics =>
        val n          = metrics.length.toDouble
        val nodeCases  = metrics.filter(_.expectedNodeCount > 0)
        val chunkCases = metrics.filter(_.expectedChunkCount > 0)
        StructuralEvalReport(
          metrics.length,
          metrics.map(_.documentRecall).sum / n,
          if nodeCases.isEmpty then 0.0 else nodeCases.map(_.nodeRecall).sum / nodeCases.length,
          if chunkCases.isEmpty then 0.0 else chunkCases.map(_.chunkRecall).sum / chunkCases.length,
          metrics.map(_.citationPrecision).sum / n,
          metrics
            .count(m =>
              m.evidenceStatus == RetrievalEvidenceStatus.Supported && m.citationPrecision > 0.0 && m.documentRecall > 0.0
            )
            .toDouble / n,
          metrics.map(_.durationMs.toDouble).sum / n,
          metrics,
          nodeCases.length,
          chunkCases.length
        )
      }
