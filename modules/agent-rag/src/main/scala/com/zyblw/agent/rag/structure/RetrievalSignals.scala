package com.zyblw.agent.rag

/** 强类型检索信号常量与提取工具。
  *
  * 保持底层 signals 为 Map[String, Double] 序列化兼容的同时，杜绝魔法字符串与命名漂移。
  */
object RetrievalSignals:
  val VectorScore          = "vectorScore"
  val TextScore            = "textScore"
  val PhraseScore          = "phraseScore"
  val SparseScore          = "sparseScore"
  val RetrievalScore       = "retrievalScore"
  val ContextExpanded      = "contextExpanded"
  val ContextNeighbor      = "context.neighbor"
  val ContextHeading       = "context.heading"
  val ContextParentSibling = "context.parentSibling"
  val ContextStructureNode = "context.structureNode"
  val StructuralValue      = "structuralValue"
  val StructuralRank       = "structuralRank"
  val RerankScore          = "rerankScore"
  val RerankFallback       = "rerankFallback"

  def hasLexicalSupport(hit: RetrievalHit): Boolean =
    hit.signals.get(PhraseScore).contains(1.0) || hit.signals.get(TextScore).exists(_ > 0.0)

  def isExpanded(hit: RetrievalHit): Boolean =
    hit.signals.get(ContextExpanded).contains(1.0)

  def isStructureExpanded(hit: RetrievalHit): Boolean =
    hit.signals.get(ContextStructureNode).contains(1.0)
