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
    val kind       =
      if normalized.contains(" vs ") || normalized.contains("对比") then QueryKind.Comparison
      else if normalized.endsWith("？") || normalized.endsWith("?") || normalized.contains("为什么") ||
        normalized.contains("如何")
      then QueryKind.Explanation
      else if normalized.length <= 24 then QueryKind.Exact
      else QueryKind.Keyword
    RetrievalPlan(
      kind = kind,
      subqueries = subqueriesFor(kind, normalized),
      mode = mode,
      includeSparse = sparseEnabled && kind != QueryKind.Exact,
      includeExact = kind == QueryKind.Exact,
      budgets = budgets
    )

  private def subqueriesFor(kind: QueryKind, normalized: String): Chunk[String] =
    val split = kind match
      case QueryKind.Comparison =>
        val parts =
          if normalized.contains(" vs ") then normalized.split(" vs ", 2)
          else normalized.split("对比", 2)
        Chunk.fromArray(parts.map(_.trim).filter(_.nonEmpty)).take(2)
      case _ => Chunk(normalized)
    val queries = if split.isEmpty then Chunk(normalized) else split
    queries.take(3)

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
