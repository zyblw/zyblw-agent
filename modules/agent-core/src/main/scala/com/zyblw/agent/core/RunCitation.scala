package com.zyblw.agent.core

import zio.Chunk
import zio.json.*

/** 引用证据来源。站内知识、公开网页与无检索的模型知识必须分开，避免把网页摘要画成本站典籍。 */
enum CitationSourceType derives JsonCodec:
  case Site, Web, Model

object CitationSourceType:
  def fromWire(value: String): CitationSourceType =
    value.trim.toLowerCase match
      case "web"   => Web
      case "model" => Model
      case _       => Site

/** 进入 Run 状态与公共 HTTP 的有界引用。excerpt 必须已经截断，禁止携带 chunk 全文或私有 metadata。 */
final case class RunCitation(
    id: String,
    sourceUri: String,
    excerpt: String,
    score: Double,
    pageNumbers: Chunk[Int] = Chunk.empty,
    chunkId: Option[String] = None,
    documentId: Option[String] = None,
    sourceType: CitationSourceType = CitationSourceType.Site,
    sourceKind: Option[String] = None
) derives JsonCodec:
  require(id.trim.nonEmpty && id.length <= 64, "RunCitation.id 无效")
  require(sourceUri.trim.nonEmpty && sourceUri.length <= 8192, "RunCitation.sourceUri 无效")
  require(excerpt.length <= 500, "RunCitation.excerpt 不得超过 500 字符")
  require(java.lang.Double.isFinite(score), "RunCitation.score 必须有限")
  require(pageNumbers.forall(_ > 0), "RunCitation.pageNumbers 必须从 1 开始")
  require(
    sourceKind.forall(RunCitation.PublicSourceKinds.contains),
    "RunCitation.sourceKind 必须是 book/article/internal/knowledge"
  )

object RunCitation:
  /** 可进入公共引用契约的站内种类；不是 catalog_role，也不是权限标签。 */
  val PublicSourceKinds: Set[String] = Set("book", "article", "internal", "knowledge")

  /** 只投影 allowlist；未知或私有 metadata 视为缺省。 */
  def publicSourceKind(raw: Option[String]): Option[String] =
    raw.map(_.trim).filter(PublicSourceKinds.contains)

final case class RunRetrievalEvidence(
    status: String,
    candidateCount: Int,
    acceptedCount: Int,
    topAcceptedScore: Option[Double] = None
) derives JsonCodec:
  require(status.trim.nonEmpty && status.length <= 64, "RunRetrievalEvidence.status 无效")
  require(candidateCount >= 0 && acceptedCount >= 0 && acceptedCount <= candidateCount, "检索证据数量无效")
