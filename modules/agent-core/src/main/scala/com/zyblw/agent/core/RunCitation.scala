package com.zyblw.agent.core

import zio.Chunk
import zio.json.*

/** 进入 Run 状态与公共 HTTP 的有界引用。excerpt 必须已经截断，禁止携带 chunk 全文或私有 metadata。 */
final case class RunCitation(
    id: String,
    sourceUri: String,
    excerpt: String,
    score: Double,
    pageNumbers: Chunk[Int] = Chunk.empty,
    chunkId: Option[String] = None,
    documentId: Option[String] = None
) derives JsonCodec:
  require(id.trim.nonEmpty && id.length <= 64, "RunCitation.id 无效")
  require(sourceUri.trim.nonEmpty && sourceUri.length <= 8192, "RunCitation.sourceUri 无效")
  require(excerpt.length <= 500, "RunCitation.excerpt 不得超过 500 字符")
  require(java.lang.Double.isFinite(score), "RunCitation.score 必须有限")
  require(pageNumbers.forall(_ > 0), "RunCitation.pageNumbers 必须从 1 开始")

final case class RunRetrievalEvidence(
    status: String,
    candidateCount: Int,
    acceptedCount: Int,
    topAcceptedScore: Option[Double] = None
) derives JsonCodec:
  require(status.trim.nonEmpty && status.length <= 64, "RunRetrievalEvidence.status 无效")
  require(candidateCount >= 0 && acceptedCount >= 0 && acceptedCount <= candidateCount, "检索证据数量无效")
