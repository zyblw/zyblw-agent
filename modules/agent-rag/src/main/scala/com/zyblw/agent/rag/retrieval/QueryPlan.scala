package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

enum QueryKind:
  case Keyword, Exact, Comparison, Explanation, Unknown

final case class RetrievalPlan(
    kind: QueryKind,
    subqueries: Chunk[String],
    mode: RetrievalMode,
    includeSparse: Boolean,
    includeExact: Boolean,
    budgets: CandidateBudgets
):
  require(subqueries.nonEmpty && subqueries.length <= 3, "RetrievalPlan 子查询必须是 1..3 条")
  require(subqueries.forall(_.trim.nonEmpty), "子查询不能为空")

object QueryNormalizer:
  def normalize(query: String): String =
    query.trim.replaceAll("\\s+", " ")

/** 确定性 planner：不能改 tenant/permissions/space。 */
object DeterministicQueryPlanner:
  def plan(
      query: String,
      mode: RetrievalMode,
      budgets: CandidateBudgets = CandidateBudgets(),
      sparseEnabled: Boolean = false
  ): RetrievalPlan =
    val normalized = QueryNormalizer.normalize(query)
    val comparison = comparisonParts(normalized)
    val kind       =
      if comparison.size == 2 then QueryKind.Comparison
      else if normalized.endsWith("？") || normalized.endsWith("?") || normalized.contains("为什么") ||
        normalized.contains("如何")
      then QueryKind.Explanation
      else if normalized.length <= 24 then QueryKind.Exact
      else QueryKind.Keyword
    RetrievalPlan(
      kind = kind,
      subqueries = subqueriesFor(kind, normalized, comparison),
      mode = mode,
      includeSparse = sparseEnabled && kind != QueryKind.Exact,
      includeExact =
        kind == QueryKind.Exact || (kind == QueryKind.Comparison && comparison.forall(_.length <= 16)),
      budgets = budgets
    )

  private def subqueriesFor(
      kind: QueryKind,
      normalized: String,
      comparison: List[String]
  ): Chunk[String] =
    val split = kind match
      case QueryKind.Comparison => Chunk.fromIterable(comparison)
      case _                    => Chunk(normalized)
    val queries = if split.isEmpty then Chunk(normalized) else split
    queries.take(3)

  private val comparisonTail    = """(?:之处)?(?:是什么|有哪些|有何不同|有何区别|在哪|在哪里|呢)?[？?。!！\s]*$"""
  private val leadingComparison = s"""(?U)^(?:对比|比较)(.+?)(?:与|和|跟)(.+?)[？?。!！\\s]*$$""".r
  private val inlineComparison  = s"""(?U)^(.+?)\\s*(?:对比|比较)\\s*(.+?)[？?。!！\\s]*$$""".r
  private val betweenComparison =
    s"""(?U)^(.+?)(?:与|和|跟)(.+?)(?:的)?(?:异同|区别|差异|不同|对比|比较)$comparisonTail""".r
  private val versusComparison = s"""(?i)^(.+?)\\s+vs\\s+(.+?)[？?。!！\\s]*$$""".r

  /** 只在出现明确比较谓词时拆两侧，避免把普通“人与自然”误判成比较题。 */
  private def comparisonParts(query: String): List[String] =
    val found = query match
      case leadingComparison(left, right) => List(left, right)
      case inlineComparison(left, right)  => List(left, right)
      case betweenComparison(left, right) => List(left, right)
      case versusComparison(left, right)  => List(left, right)
      case _                              => Nil
    found
      .map(_.trim.replaceAll("^[《（(]|[》）)]$", ""))
      .map(_.replaceAll("(?:有什么|有何|有哪些)$", "").trim)
      .map(_.replaceAll("[？?。!！\\s]+$", "").trim)
      .filter(value => value.codePointCount(0, value.length) >= 2)
      .distinct
      .take(2)

final case class QueryAssistConfig(
    enabled: Boolean = false,
    maxOutputTokens: Int = 128,
    timeout: Duration = 8.seconds
):
  require(maxOutputTokens > 0, "assist maxOutputTokens 必须为正")

final case class QueryRewrite(
    original: String,
    rewritten: String,
    cacheKey: String
):
  require(rewritten.trim.nonEmpty, "rewrite 结果不能为空")
  require(cacheKey.trim.nonEmpty, "rewrite cacheKey 不能为空")

/** 模型辅助 rewrite；默认关闭，开启也不得改 RetrievalScope。 */
trait QueryAssist:
  def rewrite(query: String, config: QueryAssistConfig): IO[RetrievalError, QueryRewrite]

object QueryAssist:
  val disabled: QueryAssist = (query: String, _: QueryAssistConfig) =>
    val normalized = QueryNormalizer.normalize(query)
    ZIO.succeed(
      QueryRewrite(query, normalized, s"assist:off:${IngestionKeys.sha256(normalized)}")
    )
