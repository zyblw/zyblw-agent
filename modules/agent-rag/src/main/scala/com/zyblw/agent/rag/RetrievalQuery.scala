package com.zyblw.agent.rag

import zio.Chunk

/** 知识再识别使用的检索策略。
  *
  * `Hybrid` 是默认生产路径：向量与 lexical 候选做 RRF。`Phrase` 用 trigram 近似找回原文短语，弥补中文 bigram FTS 对整句再识别的精度不足。
  */
enum RetrievalMode:
  case Hybrid, VectorOnly, LexicalOnly, Phrase

/** 在 tenant/permission 授权之后、排序之前下推的结构化过滤。
  *
  * 空集合表示该维度不约束。调用方不能用过滤条件绕过 ACL：Store 必须先应用租户与权限，再应用本过滤。
  */
final case class RetrievalFilter(
    documentIds: Set[String] = Set.empty,
    chunkIds: Set[String] = Set.empty,
    pages: Set[Int] = Set.empty,
    headingPrefix: Chunk[String] = Chunk.empty,
    metadataEquals: Map[String, String] = Map.empty
):
  require(documentIds.forall(_.trim.nonEmpty), "documentIds 不能包含空字符串")
  require(chunkIds.forall(_.trim.nonEmpty), "chunkIds 不能包含空字符串")
  require(pages.forall(_ > 0), "pages 必须是从 1 开始的页码")
  require(headingPrefix.forall(_.trim.nonEmpty), "headingPrefix 不能包含空标题")
  require(
    metadataEquals.forall((key, value) => key.trim.nonEmpty && value.trim.nonEmpty),
    "metadataEquals 键值不能为空"
  )

  def isEmpty: Boolean =
    documentIds.isEmpty && chunkIds.isEmpty && pages.isEmpty && headingPrefix.isEmpty && metadataEquals.isEmpty

  /** 在已经授权的块上应用结构化过滤；不得单独当作授权检查。 */
  def matches(chunk: DocumentChunk): Boolean =
    (documentIds.isEmpty || documentIds.contains(chunk.documentId)) &&
      (chunkIds.isEmpty || chunkIds.contains(chunk.id)) &&
      (pages.isEmpty || chunk.lineage.exists(lineage => lineage.pageNumbers.exists(pages.contains))) &&
      (headingPrefix.isEmpty || headingMatches(chunk)) &&
      (metadataEquals.isEmpty || metadataEquals.forall((key, value) =>
        chunk.metadata.get(key).contains(value)
      ))

  private def headingMatches(chunk: DocumentChunk): Boolean =
    val path = chunk.lineage.fold(Chunk.empty[String])(_.headingPath)
    path.length >= headingPrefix.length &&
    headingPrefix.zipWithIndex.forall((title, index) => path.lift(index).contains(title))

object RetrievalFilter:
  val empty: RetrievalFilter = RetrievalFilter()

/** 一次经过认证上下文构造的检索请求。`scope` 必须来自服务端，不能接受模型填写的 tenant。 */
final case class RetrievalRequest(
    text: String,
    scope: RetrievalScope,
    limit: Int,
    mode: RetrievalMode = RetrievalMode.Hybrid,
    filter: RetrievalFilter = RetrievalFilter.empty
):
  require(limit >= 0, "RetrievalRequest.limit 不能为负数")

object RetrievalScoring:
  /** 短语再识别分数：精确包含为 1，否则用 3-gram Dice。 */
  def phraseScore(needle: String, haystack: String): Double =
    val query = Option(needle).getOrElse("").trim.toLowerCase(java.util.Locale.ROOT)
    val text  = Option(haystack).getOrElse("").toLowerCase(java.util.Locale.ROOT)
    if query.isEmpty || text.isEmpty then 0.0
    else if text.contains(query) then 1.0
    else
      val grams = (value: String) =>
        if value.length < 2 then Set(value)
        else value.sliding(3).toSet
      val left  = grams(query)
      val right = grams(text)
      if left.isEmpty || right.isEmpty then 0.0
      else 2.0 * left.intersect(right).size.toDouble / (left.size + right.size).toDouble

  /** 无外部词典的 lexical 重叠分数，供内存 Store 与 PostgreSQL `LexicalOnly` 对齐。 */
  def lexicalScore(queryTokens: String, searchText: String): Double =
    val query = queryTokens.split("\\s+").iterator.filter(_.nonEmpty).toSet
    val text  = Option(searchText).getOrElse("").split("\\s+").iterator.filter(_.nonEmpty).toSet
    if query.isEmpty || text.isEmpty then 0.0
    else query.intersect(text).size.toDouble / query.size.toDouble

  def cosine(left: Embedding, right: Embedding): Double =
    val pairs = left.values.zip(right.values)
    val dot   = pairs.foldLeft(0.0)((sum, pair) => sum + pair._1.toDouble * pair._2.toDouble)
    val normL = math.sqrt(left.values.foldLeft(0.0)((sum, value) => sum + value.toDouble * value.toDouble))
    val normR = math.sqrt(right.values.foldLeft(0.0)((sum, value) => sum + value.toDouble * value.toDouble))
    if normL == 0.0 || normR == 0.0 then 0.0 else dot / (normL * normR)

  def rank(
      mode: RetrievalMode,
      queryText: String,
      query: Embedding,
      authorized: Iterator[IndexedChunk]
  ): Vector[RetrievalHit] =
    val scored = authorized.map { item =>
      val searchText       = item.chunk.searchText.getOrElse(item.chunk.text)
      val vector           = cosine(query, item.embedding)
      val lexical          = lexicalScore(queryText, searchText)
      val phrase           = phraseScore(queryText, searchText)
      val (score, signals) = mode match
        case RetrievalMode.VectorOnly =>
          vector -> Map("vectorScore" -> vector)
        case RetrievalMode.LexicalOnly =>
          lexical -> Map("textScore" -> lexical)
        case RetrievalMode.Phrase =>
          phrase -> Map("phraseScore" -> phrase)
        case RetrievalMode.Hybrid =>
          val fused = vector + lexical
          fused -> Map("vectorScore" -> vector, "textScore" -> lexical)
      RetrievalHit(item.chunk, score, signals)
    }.toVector
    scored.sortBy(hit => (-hit.score, hit.chunk.documentId, hit.chunk.id))
