package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*

/** RAG 发布门禁的确定性阈值。
  *
  * 排名质量指标不能互相替代：Recall 关注是否找全，MRR 关注第一个正确结果是否足够靠前，NDCG 关注整个前 K 顺序。授权、禁止片段和引用支持属于硬安全门禁，即使平均排名分很高也不能放行。
  *
  * @param minRecallAtK
  *   期望相关片段在前 K 结果中的最低召回率
  * @param minPrecisionAtK
  *   前 K 结果中相关片段的最低比例
  * @param minMrr
  *   第一个相关结果倒数排名的最低值
  * @param minNdcg
  *   二元相关性 NDCG@K 的最低值
  * @param minCitationSupport
  *   模型化引用中能由返回片段机械支持的最低比例
  * @param maxLatencyMillis
  *   单次检索端到端最大延迟
  */
final case class RagEvalThresholds(
    minRecallAtK: Double = 1.0,
    minPrecisionAtK: Double = 0.25,
    minMrr: Double = 0.5,
    minNdcg: Double = 0.7,
    minCitationSupport: Double = 1.0,
    maxLatencyMillis: Long = 3000L
):
  require(
    List(minRecallAtK, minPrecisionAtK, minMrr, minNdcg, minCitationSupport)
      .forall(value => java.lang.Double.isFinite(value) && value >= 0.0 && value <= 1.0),
    "RAG 质量阈值必须是 0..1 的有限数"
  )
  require(maxLatencyMillis > 0L, "RAG 最大延迟必须为正数")

/** 一条版本化 RAG 数据集用例。
  *
  * @param id
  *   数据集内稳定 ID，报告只记录该 ID，不记录 query 和知识正文
  * @param datasetVersion
  *   数据集或标注版本
  * @param query
  *   已脱敏的检索问题
  * @param scope
  *   可信租户与权限范围；评测会再次检查每个命中是否越界
  * @param expectedRelevantChunkIds
  *   人工或业务规则确认相关的片段 ID，不能为空
  * @param forbiddenChunkIds
  *   绝不能返回的片段 ID，例如其他租户、已撤回或注入诱饵资料
  * @param requiredCitationSourceUris
  *   最终 citations 至少覆盖的来源 URI
  * @param limit
  *   Retriever 前 K 数量
  * @param thresholds
  *   本用例质量与延迟门禁
  */
final case class RagEvalCase(
    id: String,
    datasetVersion: String,
    query: String,
    scope: RetrievalScope,
    expectedRelevantChunkIds: Set[String],
    forbiddenChunkIds: Set[String] = Set.empty,
    requiredCitationSourceUris: Set[String] = Set.empty,
    limit: Int = 5,
    thresholds: RagEvalThresholds = RagEvalThresholds()
):
  require(id.trim.nonEmpty && datasetVersion.trim.nonEmpty, "RAG 评测 id 和 datasetVersion 不能为空")
  require(query.trim.nonEmpty, "RAG 评测 query 不能为空")
  require(expectedRelevantChunkIds.nonEmpty, "RAG 评测必须至少标注一个相关片段")
  require(expectedRelevantChunkIds.intersect(forbiddenChunkIds).isEmpty, "相关片段不能同时被标为禁止")
  require(limit > 0 && limit <= 1000, "RAG 评测 limit 必须位于 1..1000")

/** 一次检索的标准化观测值。
  *
  * @param result
  *   Retriever 的结构化结果；禁止从最终自然语言答案中用正则猜引用
  * @param latencyMillis
  *   从调用 Retriever 到完整结果返回的墙钟延迟
  */
final case class RagEvalObservation(result: RetrievalResult, latencyMillis: Long):
  require(latencyMillis >= 0L, "RAG 观测延迟不能为负数")

/** 单条 RAG 用例报告；grades 沿用通用 EvalGrade，便于 CI 和 dashboard 使用统一门禁格式。 */
final case class RagEvalReport(caseId: String, datasetVersion: String, grades: Chunk[EvalGrade]):
  /** 排名、引用、安全和延迟全部通过才允许发布。 */
  def passed: Boolean = grades.forall(_.passed)

/** 一次数据集回归报告。 */
final case class RagEvalSuiteReport(reports: Chunk[RagEvalReport]):
  /** 空数据集不视为通过，避免 CI 因测试数据未加载而假绿。 */
  def passed: Boolean = reports.nonEmpty && reports.forall(_.passed)

  /** 通过率用于趋势图；真正发布判断仍使用 passed。 */
  def passRate: Double =
    if reports.isEmpty then 0.0 else reports.count(_.passed).toDouble / reports.length.toDouble

/** RAG 确定性评分器。
  *
  * 这里不使用 LLM-as-a-judge：排名、租户授权、引用是否能由命中片段支持和延迟都是可机械验证事实。 回答语义质量可在更高层 Agent eval 中增加 Judge，但不能替代这些硬门禁。
  */
object RagEvalGrader:
  /** 计算排名质量、引用支持、授权/泄漏和延迟四组独立门禁。 */
  def grade(evalCase: RagEvalCase, observation: RagEvalObservation): RagEvalReport =
    RagEvalReport(
      evalCase.id,
      evalCase.datasetVersion,
      Chunk(
        rankingGrade(evalCase, observation),
        citationGrade(evalCase, observation),
        securityGrade(evalCase, observation),
        latencyGrade(evalCase, observation)
      )
    )

  /** 以 chunk ID 计算 Recall@K、Precision@K、MRR 和二元 NDCG@K。 */
  private def rankingGrade(evalCase: RagEvalCase, observation: RagEvalObservation): EvalGrade =
    val ids       = observation.result.hits.take(evalCase.limit).map(_.chunk.id)
    val relevance = ids.map(id => if evalCase.expectedRelevantChunkIds.contains(id) then 1.0 else 0.0)
    val found     = ids.toSet.intersect(evalCase.expectedRelevantChunkIds).size
    val recall    = found.toDouble / evalCase.expectedRelevantChunkIds.size.toDouble
    val precision = if ids.isEmpty then 0.0 else found.toDouble / ids.length.toDouble
    val mrr       = relevance.indexWhere(_ > 0.0) match
      case -1    => 0.0
      case index => 1.0 / (index + 1).toDouble
    val dcg        = relevance.zipWithIndex.map((rel, index) => rel / log2(index.toDouble + 2.0)).sum
    val idealCount = math.min(evalCase.expectedRelevantChunkIds.size, evalCase.limit)
    val idealDcg   = (0 until idealCount).map(index => 1.0 / log2(index.toDouble + 2.0)).sum
    val ndcg       = if idealDcg == 0.0 then 0.0 else dcg / idealDcg
    val threshold  = evalCase.thresholds
    val passed     = recall >= threshold.minRecallAtK && precision >= threshold.minPrecisionAtK &&
      mrr >= threshold.minMrr && ndcg >= threshold.minNdcg
    EvalGrade(
      "rag-ranking",
      passed,
      List(recall, precision, mrr, ndcg).sum / 4.0,
      f"recall=$recall%.4f;precision=$precision%.4f;mrr=$mrr%.4f;ndcg=$ndcg%.4f"
    )

  /** 引用必须指向本次授权命中，并且非空 excerpt 必须确实出现在同来源片段正文中。 这是“引用存在且有机械依据”，不等同于自然语言结论完全被来源蕴含；后者属于答案级 eval。
    */
  private def citationGrade(evalCase: RagEvalCase, observation: RagEvalObservation): EvalGrade =
    val hits      = observation.result.hits
    val citations = observation.result.citations
    val supported = citations.count { citation =>
      citation.excerpt.nonEmpty && java.lang.Double.isFinite(citation.score) &&
      hits.exists(hit =>
        hit.chunk.sourceUri == citation.sourceUri && hit.chunk.text.contains(citation.excerpt)
      )
    }
    val support =
      if citations.isEmpty then if evalCase.requiredCitationSourceUris.isEmpty then 1.0 else 0.0
      else supported.toDouble / citations.length.toDouble
    val citationSources = citations.map(_.sourceUri).toSet
    val requiredPresent = evalCase.requiredCitationSourceUris.subsetOf(citationSources)
    val uniqueIds       = citations.map(_.id).distinct.length == citations.length
    val passed          = support >= evalCase.thresholds.minCitationSupport && requiredPresent && uniqueIds
    EvalGrade(
      "rag-citation-support",
      passed,
      support,
      s"supported=$supported/${citations.length};missingRequired=${evalCase.requiredCitationSourceUris.diff(citationSources).size};duplicateIds=${citations.length - citations.map(_.id).distinct.length}"
    )

  /** 重新验证租户、权限、禁止 ID、重复 ID 和所有排名数值，防止好看的平均分掩盖越权。 */
  private def securityGrade(evalCase: RagEvalCase, observation: RagEvalObservation): EvalGrade =
    val hits         = observation.result.hits
    val unauthorized = hits.count(hit =>
      hit.chunk.tenantId != evalCase.scope.tenantId || !hit.chunk.permissions.subsetOf(
        evalCase.scope.permissions
      )
    )
    val forbidden     = hits.count(hit => evalCase.forbiddenChunkIds.contains(hit.chunk.id))
    val duplicates    = hits.length - hits.map(_.chunk.id).distinct.length
    val invalidScores = hits.count(hit =>
      !java.lang.Double.isFinite(hit.score) || hit.signals.values.exists(value =>
        !java.lang.Double.isFinite(value)
      )
    )
    val passed = unauthorized == 0 && forbidden == 0 && duplicates == 0 && invalidScores == 0
    EvalGrade(
      "rag-authorization-and-integrity",
      passed,
      if passed then 1.0 else 0.0,
      s"unauthorized=$unauthorized;forbidden=$forbidden;duplicates=$duplicates;invalidScores=$invalidScores"
    )

  /** 延迟使用独立硬门禁，避免通过降低检索质量来隐藏尾延迟问题。 */
  private def latencyGrade(evalCase: RagEvalCase, observation: RagEvalObservation): EvalGrade =
    val passed = observation.latencyMillis <= evalCase.thresholds.maxLatencyMillis
    EvalGrade(
      "rag-latency",
      passed,
      if passed then 1.0 else 0.0,
      s"latency=${observation.latencyMillis}/${evalCase.thresholds.maxLatencyMillis}"
    )

  /** JVM 没有稳定跨版本的 `math.log2` API，用自然对数比值计算。 */
  private def log2(value: Double): Double = math.log(value) / math.log(2.0)

/** 并发运行版本化 RAG 数据集的 ZIO Harness。
  *
  * @param maxParallelism
  *   最大并发用例数；应受 Embedding Provider、reranker 和数据库连接池共同约束
  */
final class RagEvalRunner(maxParallelism: Int):
  require(maxParallelism > 0 && maxParallelism <= 256, "RAG eval maxParallelism 必须位于 1..256")

  /** 使用自定义执行函数运行数据集，便于故障注入、固定延迟和离线 cassette 测试。
    * @param cases
    *   版本化、脱敏的数据集
    * @param execute
    *   把单条用例转换为标准观测值
    */
  def run(
      cases: Chunk[RagEvalCase]
  )(execute: RagEvalCase => IO[AgentError, RagEvalObservation]): IO[AgentError, RagEvalSuiteReport] =
    ZIO
      .foreachPar(cases)(evalCase => execute(evalCase).map(RagEvalGrader.grade(evalCase, _)))
      .withParallelism(maxParallelism)
      .map(RagEvalSuiteReport(_))

  /** 直接测量一个真实 Retriever；使用 monotonic nanoTime，避免系统时钟回拨产生负延迟。
    * @param retriever
    *   被测检索流水线，通常包含 Embedding、hybrid search 与 reranker
    * @param cases
    *   评测数据集
    */
  def runRetriever(retriever: Retriever, cases: Chunk[RagEvalCase]): IO[AgentError, RagEvalSuiteReport] =
    run(cases) { evalCase =>
      for
        started <- Clock.nanoTime
        result  <- retriever.retrieve(evalCase.query, evalCase.scope, evalCase.limit)
        ended   <- Clock.nanoTime
      yield RagEvalObservation(result, math.max(0L, (ended - started) / 1_000_000L))
    }
