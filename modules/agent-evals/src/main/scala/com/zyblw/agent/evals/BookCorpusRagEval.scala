package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*

/** 公开可复现的书籍问答评测语料：每种查询模式各一组 gold 问题。 */
object BookCorpusRagEval:
  val DatasetId: String      = "book-corpus-public"
  val DatasetVersion: String = "2026-08-23"
  private val tenant         = TenantId("eval-tenant")
  private val read           = Set("knowledge:read")
  val scope: RetrievalScope  = RetrievalScope(tenant, read, Some("book-eval"))

  val suwen: DocumentChunk = DocumentChunk(
    "chunk-suwen-yinyang",
    "suwen",
    "阴阳者，天地之道也，万物之纲纪，变化之父母。",
    "book://suwen/yinyang",
    tenant,
    read,
    metadata = Map("classic" -> "suwen"),
    lineage = Some(
      ChunkLineage(
        parentId = Some("suwen"),
        ordinal = 0,
        headingPath = Chunk("素问", "阴阳应象大论"),
        origins = Chunk(DocumentOrigin(12))
      )
    )
  )

  val shanghan: DocumentChunk = DocumentChunk(
    "chunk-shanghan-guizhi",
    "shanghan",
    "太阳中风，阳浮而阴弱，啬啬恶寒，淅淅恶风，翕翕发热，鼻鸣干呕者，桂枝汤主之。",
    "book://shanghan/guizhi",
    tenant,
    read,
    metadata = Map("classic" -> "shanghan"),
    lineage = Some(
      ChunkLineage(
        parentId = Some("shanghan"),
        ordinal = 0,
        headingPath = Chunk("伤寒论", "太阳病"),
        origins = Chunk(DocumentOrigin(3))
      )
    )
  )

  val decoy: DocumentChunk = DocumentChunk(
    "chunk-other-tenant",
    "other",
    "这段属于其他租户，不得被召回。",
    "book://other/secret",
    TenantId("other-tenant"),
    Set("secret:read")
  )

  def fixtures: Chunk[DocumentChunk] = Chunk(suwen, shanghan, decoy)

  private val thresholds = RagEvalThresholds(
    minRecallAtK = 1.0,
    minPrecisionAtK = 0.5,
    minMrr = 1.0,
    minNdcg = 0.7,
    minCitationSupport = 1.0,
    maxLatencyMillis = 2_000L
  )

  val cases: Chunk[RagEvalCase] = Chunk(
    RagEvalCase(
      "hybrid-yinyang",
      DatasetVersion,
      "阴阳者天地之道",
      scope,
      Set(suwen.id),
      Set(decoy.id),
      Set(suwen.sourceUri),
      mode = RetrievalMode.Hybrid,
      thresholds = thresholds
    ),
    RagEvalCase(
      "vector-guizhi",
      DatasetVersion,
      "桂枝汤主治太阳中风",
      scope,
      Set(shanghan.id),
      Set(decoy.id),
      Set(shanghan.sourceUri),
      mode = RetrievalMode.VectorOnly,
      thresholds = thresholds
    ),
    RagEvalCase(
      "lexical-恶风",
      DatasetVersion,
      "淅淅恶风",
      scope,
      Set(shanghan.id),
      Set(decoy.id),
      Set(shanghan.sourceUri),
      mode = RetrievalMode.LexicalOnly,
      thresholds = thresholds
    ),
    RagEvalCase(
      "phrase-桂枝汤主之",
      DatasetVersion,
      "桂枝汤主之",
      scope,
      Set(shanghan.id),
      Set(decoy.id),
      Set(shanghan.sourceUri),
      mode = RetrievalMode.Phrase,
      thresholds = thresholds
    ),
    RagEvalCase(
      "page-filter",
      DatasetVersion,
      "阴阳",
      scope,
      Set(suwen.id),
      Set(decoy.id),
      Set(suwen.sourceUri),
      mode = RetrievalMode.Hybrid,
      filter = RetrievalFilter(pages = Set(12)),
      thresholds = thresholds
    ),
    RagEvalCase(
      "metadata-filter",
      DatasetVersion,
      "中风发热",
      scope,
      Set(shanghan.id),
      Set(decoy.id),
      Set(shanghan.sourceUri),
      mode = RetrievalMode.Hybrid,
      filter = RetrievalFilter(metadataEquals = Map("classic" -> "shanghan")),
      thresholds = thresholds
    ),
    RagEvalCase(
      "chunk-fetch",
      DatasetVersion,
      suwen.id,
      scope,
      Set(suwen.id),
      Set(decoy.id),
      Set(suwen.sourceUri),
      mode = RetrievalMode.LexicalOnly,
      filter = RetrievalFilter(chunkIds = Set(suwen.id)),
      thresholds = thresholds
    )
  )
