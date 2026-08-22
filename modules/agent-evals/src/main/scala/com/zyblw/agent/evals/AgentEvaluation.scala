package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 一条可版本化业务评测用例。
  *
  * @param id
  *   在数据集内稳定唯一的 ID，失败报告和回归趋势以它关联
  * @param datasetVersion
  *   数据集版本；修改期望或输入时必须推进版本
  * @param input
  *   传给被测 Agent 的业务输入，生产数据必须脱敏
  * @param expectedTools
  *   必须选择的工具名集合
  * @param forbiddenTools
  *   明确禁止选择的工具名集合
  * @param expectedCitationIds
  *   答案至少应引用的知识片段/文档 ID
  * @param requireRecovery
  *   是否要求本用例经过故障恢复后仍正确终结
  * @param budget
  *   本用例的延迟、token 和成本硬预算
  */
final case class AgentEvalCase(
    id: String,
    datasetVersion: String,
    input: String,
    expectedTools: Set[String] = Set.empty,
    forbiddenTools: Set[String] = Set.empty,
    expectedCitationIds: Set[String] = Set.empty,
    requireRecovery: Boolean = false,
    budget: EvalBudget = EvalBudget(),
    /** 由宿主确定性 normalizer 从最终答案提取的标签，例如 yes/no/maybe；不在框架内做自由文本猜测。 */
    expectedOutcomeLabels: Set[String] = Set.empty
) derives JsonCodec:
  require(id.trim.nonEmpty && datasetVersion.trim.nonEmpty, "评测 id 和 datasetVersion 不能为空")
  require(expectedTools.intersect(forbiddenTools).isEmpty, "同一工具不能同时为必需和禁止")

/** 单用例资源预算。
  *
  * @param maxLatencyMillis
  *   端到端最大延迟；默认 30 秒
  * @param maxTotalTokens
  *   输入与输出总 token 上限
  * @param maxEstimatedCost
  *   估算成本上限；币种和价格版本由运行环境统一约定
  */
final case class EvalBudget(
    maxLatencyMillis: Long = 30000L,
    maxTotalTokens: Long = 16000L,
    maxEstimatedCost: BigDecimal = BigDecimal(1)
):
  require(maxLatencyMillis > 0L && maxTotalTokens > 0L && maxEstimatedCost >= 0, "评测预算必须为正数")

object EvalBudget:
  /** 以十进制字符串编码成本，避免 JSON Double 引入货币精度误差。 */
  given JsonCodec[BigDecimal] = JsonCodec.string.transformOrFail(
    value => scala.util.Try(BigDecimal(value)).toEither.left.map(_.getMessage),
    _.bigDecimal.toPlainString
  )
  given JsonCodec[EvalBudget] = DeriveJsonCodec.gen[EvalBudget]

/** 被测 Agent 的标准化观测值。
  *
  * @param selectedTools
  *   实际工具调用顺序；重复调用保留重复项
  * @param citationIds
  *   最终回答携带的规范化引用 ID；正文中的任意 URL 不自动视为可信引用
  * @param recovered
  *   是否确实从持久化状态恢复而不是从头重跑
  * @param duplicateSideEffects
  *   恢复期间检测到的重复副作用次数，正确结果必须为零
  * @param terminalStatus
  *   最终 Run 状态
  * @param latencyMillis
  *   端到端实测耗时
  * @param usage
  *   实际或可信估算 token
  * @param estimatedCost
  *   使用带版本的价格表计算的估算成本
  */
final case class AgentEvalObservation(
    selectedTools: Chunk[String],
    citationIds: Set[String],
    recovered: Boolean,
    duplicateSideEffects: Int,
    terminalStatus: RunStatus,
    latencyMillis: Long,
    usage: TokenUsage,
    estimatedCost: BigDecimal,
    /** 宿主确定性提取或人工标注的结果标签；正文不进入趋势快照。 */
    outcomeLabels: Set[String] = Set.empty
):
  require(duplicateSideEffects >= 0 && latencyMillis >= 0L && estimatedCost >= 0, "观测指标不能为负数")

object AgentEvalObservation:
  given JsonCodec[AgentEvalObservation] = DeriveJsonCodec.gen[AgentEvalObservation]

/** 评测证据关注点。
  *
  * `Outcome` 回答最终结果是否正确，`Trajectory` 回答执行路径是否符合预期，`Safety` 回答是否越权、泄漏或重复产生副作用， `Resource` 回答延迟、Token
  * 和成本是否受控。`Other` 为第三方自定义维度保留兼容出口；框架内置维度必须归入前四类。
  */
enum EvalAxis derives JsonCodec:
  case Outcome
  case Trajectory
  case Safety
  case Resource
  case Other

object EvalAxis:
  private val known: Map[String, EvalAxis] = Map(
    "citation-correctness"                    -> EvalAxis.Outcome,
    "outcome-labels"                          -> EvalAxis.Outcome,
    "rag-ranking"                             -> EvalAxis.Outcome,
    "rag-citation-support"                    -> EvalAxis.Outcome,
    "context-compression-completion"          -> EvalAxis.Outcome,
    "context-compression-evidence-retention"  -> EvalAxis.Outcome,
    "context-compression-reference-retention" -> EvalAxis.Outcome,
    "context-compression-stability"           -> EvalAxis.Outcome,
    "tool-selection"                          -> EvalAxis.Trajectory,
    "recovery-correctness"                    -> EvalAxis.Trajectory,
    TrajectoryReplay.Dimension                -> EvalAxis.Trajectory,
    "forbidden-tool-safety"                   -> EvalAxis.Safety,
    "duplicate-side-effect-safety"            -> EvalAxis.Safety,
    TrajectoryReplay.SafetyDimension          -> EvalAxis.Safety,
    "rag-authorization-and-integrity"         -> EvalAxis.Safety,
    "context-compression-forbidden-content"   -> EvalAxis.Safety,
    "harness-safety-no-failures"              -> EvalAxis.Safety,
    "resource-budget"                         -> EvalAxis.Resource,
    "rag-latency"                             -> EvalAxis.Resource,
    "context-compression-resource-budget"     -> EvalAxis.Resource,
    "harness-outcome-delta"                   -> EvalAxis.Outcome,
    "harness-trajectory-delta"                -> EvalAxis.Trajectory,
    "harness-latency-ratio"                   -> EvalAxis.Resource,
    "harness-token-ratio"                     -> EvalAxis.Resource,
    "harness-cost-ratio"                      -> EvalAxis.Resource,
    "harness-human-intervention-delta"        -> EvalAxis.Resource
  )

  /** 稳定维度名到关注点的纯映射；未知第三方维度不会被误归类为框架安全证据。 */
  def fromDimension(dimension: String): EvalAxis = known.getOrElse(dimension, EvalAxis.Other)

/** 一个关注点的聚合视图。平均分只用于解释，任一子维度失败仍使整个关注点失败。 */
final case class EvalAxisSummary(
    axis: EvalAxis,
    passed: Boolean,
    averageScore: Double,
    dimensions: Chunk[String]
) derives JsonCodec

object EvalAxisSummary:
  /** 保持 enum 顺序，且只输出实际存在的关注点。 */
  def from(grades: Chunk[EvalGrade]): Chunk[EvalAxisSummary] =
    Chunk.fromIterable(EvalAxis.values).flatMap { axis =>
      val selected = grades.filter(_.axis == axis)
      Chunk.fromIterable(
        Option.when(selected.nonEmpty)(
          EvalAxisSummary(
            axis,
            selected.forall(_.passed),
            selected.map(_.score).sum / selected.length.toDouble,
            selected.map(_.dimension)
          )
        )
      )
    }

/** 一个维度的评分结果；details 只保存可安全进入 CI 报告的摘要。 */
final case class EvalGrade(dimension: String, passed: Boolean, score: Double, details: String)
    derives JsonCodec:
  def axis: EvalAxis = EvalAxis.fromDimension(dimension)

/** 单用例完整报告。
  * @param caseId
  *   用例 ID
  * @param datasetVersion
  *   数据集版本
  * @param grades
  *   工具、引用、恢复和预算四类评分
  */
final case class AgentEvalReport(caseId: String, datasetVersion: String, grades: Chunk[EvalGrade])
    derives JsonCodec:
  /** 所有硬门禁都通过才算用例通过。 */
  def passed: Boolean = grades.forall(_.passed)

  /** 维度分数等权平均；发布门禁仍应使用 passed，不能用平均分掩盖安全失败。 */
  def averageScore: Double = if grades.isEmpty then 0.0 else grades.map(_.score).sum / grades.length.toDouble

  /** outcome、trajectory、safety 与 resource 的独立解释视图。 */
  def axisSummaries: Chunk[EvalAxisSummary] = EvalAxisSummary.from(grades)

/** 评测套件聚合报告。 */
final case class AgentEvalSuiteReport(reports: Chunk[AgentEvalReport]) derives JsonCodec:
  /** 通过率，空套件返回零而不是虚假的 100%。 */
  def passRate: Double =
    if reports.isEmpty then 0.0 else reports.count(_.passed).toDouble / reports.length.toDouble

  /** 是否全部满足发布门禁。 */
  def passed: Boolean = reports.nonEmpty && reports.forall(_.passed)

/** 同一用例的一次独立试验。
  *
  * `attempt` 从 1 开始，只表示本次多试验运行内的稳定顺序，不是可跨构建复用的持久化身份。
  */
final case class AgentEvalTrialReport(attempt: Int, report: AgentEvalReport) derives JsonCodec:
  require(attempt > 0, "评测试验 attempt 必须大于零")

/** 同一用例的多次独立试验可靠性。
  *
  * 单次成功只能说明“这次成功”。这里同时暴露：
  *
  *   - `successRate`：观察到的逐次成功率；
  *   - `estimatedPassAtK`：以观察成功率估算 k 次中至少一次成功的概率；
  *   - `estimatedPassPowerK`：以观察成功率估算连续 k 次全部成功的概率。
  *
  * 两个估算都假设各次试验近似独立同分布。它们是发布决策的可靠性信号，不是置信区间，也不能替代对失败轨迹和最终结果的 人工校准。
  */
final case class AgentEvalCaseReliability(
    caseId: String,
    datasetVersion: String,
    trials: Chunk[AgentEvalTrialReport]
) derives JsonCodec:
  require(caseId.trim.nonEmpty && datasetVersion.trim.nonEmpty, "可靠性报告 id 和 datasetVersion 不能为空")
  require(trials.nonEmpty, "可靠性报告至少需要一次试验")
  require(
    trials.map(_.attempt) == Chunk.fromIterable(1 to trials.length),
    "可靠性报告 attempt 必须从 1 开始连续递增"
  )
  require(
    trials.forall(trial => trial.report.caseId == caseId && trial.report.datasetVersion == datasetVersion),
    "可靠性报告中的用例身份必须一致"
  )

  def successes: Int = trials.count(_.report.passed)

  def successRate: Double = successes.toDouble / trials.length.toDouble

  /** 观察成功率的 95% Wilson score interval；小样本下不会把 1/1 误报为确定的 100%。 */
  def confidenceInterval95: BinomialConfidenceInterval =
    BinomialConfidenceInterval.wilson95(successes, trials.length)

  /** k 次独立尝试中至少一次成功的估算概率，即常称的 pass@k。 */
  def estimatedPassAtK(k: Int): Double =
    require(k > 0, "pass@k 的 k 必须大于零")
    1.0 - math.pow(1.0 - successRate, k.toDouble)

  /** 连续 k 次独立尝试全部成功的估算概率，即常称的 pass^k。 */
  def estimatedPassPowerK(k: Int): Double =
    require(k > 0, "pass^k 的 k 必须大于零")
    math.pow(successRate, k.toDouble)

  /** 对面向用户、必须稳定成功的路径，优先使用这个最严格的离线门禁。 */
  def passedEveryTrial: Boolean = successes == trials.length

/** 二项成功率的低敏置信区间，只保留计数与数值，不保存任何输入、回答或轨迹正文。 */
final case class BinomialConfidenceInterval(
    successes: Int,
    samples: Int,
    confidenceLevel: Double,
    lower: Double,
    upper: Double
) derives JsonCodec:
  require(samples > 0 && successes >= 0 && successes <= samples, "二项区间计数不合法")
  require(
    confidenceLevel > 0.0 && confidenceLevel < 1.0 &&
      java.lang.Double.isFinite(lower) && java.lang.Double.isFinite(upper) &&
      lower >= 0.0 && lower <= upper && upper <= 1.0,
    "二项区间边界不合法"
  )

object BinomialConfidenceInterval:
  private val Z95        = 1.959963984540054
  private val Confidence = 0.95

  /** 95% Wilson score interval；只需标准库，避免为一个稳定公式引入统计依赖。 */
  def wilson95(successes: Int, samples: Int): BinomialConfidenceInterval =
    require(samples > 0 && successes >= 0 && successes <= samples, "Wilson 区间计数不合法")
    val n           = samples.toDouble
    val observed    = successes.toDouble / n
    val zSquared    = Z95 * Z95
    val denominator = 1.0 + zSquared / n
    val center      = observed + zSquared / (2.0 * n)
    val margin      = Z95 * math.sqrt(observed * (1.0 - observed) / n + zSquared / (4.0 * n * n))
    BinomialConfidenceInterval(
      successes,
      samples,
      Confidence,
      math.max(0.0, (center - margin) / denominator),
      math.min(1.0, (center + margin) / denominator)
    )

/** 多试验发布策略。
  *
  * 默认要求每个用例至少五次、观察结果全部成功，并让 95% Wilson 下界高于 0.5。关键生产路径通常应提高样本数和下界；该默认值 主要阻止“一次跑绿”被当成可靠性证据。
  */
final case class AgentEvalReliabilityPolicy(
    minimumTrialsPerCase: Int = 5,
    minimumObservedSuccessRate: Double = 1.0,
    minimumWilsonLowerBound95: Double = 0.5
):
  require(minimumTrialsPerCase > 0, "minimumTrialsPerCase 必须大于零")
  require(
    java.lang.Double.isFinite(minimumObservedSuccessRate) &&
      minimumObservedSuccessRate >= 0.0 && minimumObservedSuccessRate <= 1.0,
    "minimumObservedSuccessRate 必须是 0..1 的有限数"
  )
  require(
    java.lang.Double.isFinite(minimumWilsonLowerBound95) &&
      minimumWilsonLowerBound95 >= 0.0 && minimumWilsonLowerBound95 <= 1.0,
    "minimumWilsonLowerBound95 必须是 0..1 的有限数"
  )

/** 多试验套件报告；用例顺序与输入数据集一致。 */
final case class AgentEvalReliabilityReport(cases: Chunk[AgentEvalCaseReliability]) derives JsonCodec:
  def passedEveryTrial: Boolean = cases.nonEmpty && cases.forall(_.passedEveryTrial)

  def meanSuccessRate: Double =
    if cases.isEmpty then 0.0 else cases.map(_.successRate).sum / cases.length.toDouble

/** 确定性规则评分器。
  *
  * LLM-as-judge 可作为额外维度，但工具选择、引用 ID、恢复副作用和预算都能用确定性规则判断， 不应把这些硬事实交给另一个模型猜测。
  */
object AgentEvalGrader:
  /** 对单用例生成工具、引用、恢复和预算四个稳定评分维度。
    *
    * @param trajectory
    *   Replayable 账本证据。缺省时不加轨迹维度，保持既有四门禁；授权评测应传入以便重建失败或 Inspector 泄漏直接阻断发布。
    */
  def grade(
      evalCase: AgentEvalCase,
      observation: AgentEvalObservation,
      trajectory: Option[TrajectoryReplayEvidence] = None
  ): AgentEvalReport =
    val coreGrades = Chunk(
      gradeOutcomeLabels(evalCase, observation),
      gradeExpectedTools(evalCase, observation),
      gradeCitations(evalCase, observation),
      gradeRecovery(evalCase, observation),
      gradeBudget(evalCase, observation),
      gradeForbiddenTools(evalCase, observation),
      gradeDuplicateSideEffects(observation)
    )
    AgentEvalReport(
      evalCase.id,
      evalCase.datasetVersion,
      trajectory.fold(coreGrades)(evidence => coreGrades ++ TrajectoryReplay.grades(evidence))
    )

  /** 公开 QA 等结构化标签使用精确集合比较；没有声明标签时保持兼容通过。 */
  private def gradeOutcomeLabels(
      evalCase: AgentEvalCase,
      observation: AgentEvalObservation
  ): EvalGrade =
    val passed = evalCase.expectedOutcomeLabels.isEmpty ||
      evalCase.expectedOutcomeLabels == observation.outcomeLabels
    EvalGrade(
      "outcome-labels",
      passed,
      if passed then 1.0 else 0.0,
      s"expected=${evalCase.expectedOutcomeLabels.toList.sorted.mkString(",")};" +
        s"actual=${observation.outcomeLabels.toList.sorted.mkString(",")}"
    )

  /** 工具轨迹只检查必需集合；禁止工具由独立 Safety 门禁负责。 */
  private def gradeExpectedTools(evalCase: AgentEvalCase, observation: AgentEvalObservation): EvalGrade =
    val actual        = observation.selectedTools.toSet
    val missing       = evalCase.expectedTools.diff(actual)
    val expectedCount = evalCase.expectedTools.size.max(1)
    val recall        = (evalCase.expectedTools.size - missing.size).toDouble / expectedCount.toDouble
    EvalGrade(
      "tool-selection",
      missing.isEmpty,
      recall,
      s"missing=${missing.toList.sorted.mkString(",")}"
    )

  /** 引用以 expectedCitationIds 的召回率评分；没有引用要求时视为通过且得满分。 */
  private def gradeCitations(evalCase: AgentEvalCase, observation: AgentEvalObservation): EvalGrade =
    val missing = evalCase.expectedCitationIds.diff(observation.citationIds)
    val score   = if evalCase.expectedCitationIds.isEmpty then 1.0
    else
      (evalCase.expectedCitationIds.size - missing.size).toDouble / evalCase.expectedCitationIds.size.toDouble
    EvalGrade(
      "citation-correctness",
      missing.isEmpty,
      score,
      s"missing=${missing.toList.sorted.mkString(",")}"
    )

  /** 轨迹恢复门禁检查是否按要求恢复并到达 Completed；重复副作用由独立 Safety 门禁负责。 */
  private def gradeRecovery(evalCase: AgentEvalCase, observation: AgentEvalObservation): EvalGrade =
    val recoveredAsRequired = !evalCase.requireRecovery || observation.recovered
    val passed              = recoveredAsRequired && observation.terminalStatus == RunStatus.Completed
    EvalGrade(
      "recovery-correctness",
      passed,
      if passed then 1.0 else 0.0,
      s"required=${evalCase.requireRecovery};recovered=${observation.recovered};status=${observation.terminalStatus}"
    )

  /** 禁止工具零命中是独立 Safety 硬门禁，不能被正确结果或工具召回率抵消。 */
  private def gradeForbiddenTools(evalCase: AgentEvalCase, observation: AgentEvalObservation): EvalGrade =
    val forbidden = evalCase.forbiddenTools.intersect(observation.selectedTools.toSet)
    EvalGrade(
      "forbidden-tool-safety",
      forbidden.isEmpty,
      if forbidden.isEmpty then 1.0 else 0.0,
      s"forbidden=${forbidden.toList.sorted.mkString(",")}"
    )

  /** 恢复后重复副作用独立阻断发布，即使最终状态和答案看起来正确。 */
  private def gradeDuplicateSideEffects(observation: AgentEvalObservation): EvalGrade =
    val passed = observation.duplicateSideEffects == 0
    EvalGrade(
      "duplicate-side-effect-safety",
      passed,
      if passed then 1.0 else 0.0,
      s"duplicates=${observation.duplicateSideEffects}"
    )

  /** 延迟、token、成本任一超限都令预算门禁失败。 */
  private def gradeBudget(evalCase: AgentEvalCase, observation: AgentEvalObservation): EvalGrade =
    val limits = evalCase.budget
    val passed = observation.latencyMillis <= limits.maxLatencyMillis &&
      observation.usage.totalTokens <= limits.maxTotalTokens &&
      observation.estimatedCost <= limits.maxEstimatedCost
    EvalGrade(
      "resource-budget",
      passed,
      if passed then 1.0 else 0.0,
      s"latency=${observation.latencyMillis}/${limits.maxLatencyMillis};tokens=${observation.usage.totalTokens}/${limits.maxTotalTokens};cost=${observation.estimatedCost}/${limits.maxEstimatedCost}"
    )

/** 并发运行评测数据集的 ZIO harness。
  *
  * @param maxParallelism
  *   最大并发用例数；应小于 Provider 和数据库连接池的安全上限
  */
final class AgentEvalRunner(maxParallelism: Int):
  require(maxParallelism > 0, "maxParallelism 必须大于零")

  /** 执行所有用例并保持输入顺序生成报告。
    * @param cases
    *   版本化评测数据集
    * @param execute
    *   将用例运行成标准观测值的业务适配函数
    */
  def run(
      cases: Chunk[AgentEvalCase]
  )(execute: AgentEvalCase => IO[AgentError, AgentEvalObservation]): IO[AgentError, AgentEvalSuiteReport] =
    ZIO
      .foreachPar(cases)(evalCase => execute(evalCase).map(AgentEvalGrader.grade(evalCase, _)))
      .withParallelism(maxParallelism)
      .map(AgentEvalSuiteReport(_))

  /** 运行带 provenance 的发布数据集；任何审查、版本或摘要漂移都会在执行首个用例前失败。 */
  def run(
      dataset: AgentEvalDataset
  )(execute: AgentEvalCase => IO[AgentError, AgentEvalObservation]): IO[AgentError, AgentEvalSuiteReport] =
    dataset.validateForRelease *> run(dataset.cases)(execute)

  /** 对每个用例执行固定次数的独立试验，并保留确定性的用例/attempt 顺序。
    *
    * 整个 job 集合共享 `maxParallelism`，不会把“用例并发 × 重复次数”意外放大成无界 Provider 或数据库压力。任一执行返回 typed failure 时，ZIO
    * 会中断仍在运行的兄弟 Fiber，本次报告不会以部分数据假装完成。
    *
    * @param cases
    *   版本化评测数据集
    * @param trialsPerCase
    *   每个用例的独立试验次数
    * @param execute
    *   将用例与从 1 开始的 attempt 运行成标准观测值
    */
  def runRepeated(
      cases: Chunk[AgentEvalCase],
      trialsPerCase: Int
  )(
      execute: (AgentEvalCase, Int) => IO[AgentError, AgentEvalObservation]
  ): IO[AgentError, AgentEvalReliabilityReport] =
    require(trialsPerCase > 0, "trialsPerCase 必须大于零")
    val jobs = cases.zipWithIndex.flatMap { case (evalCase, caseIndex) =>
      Chunk.fromIterable(1 to trialsPerCase).map(attempt => (caseIndex, evalCase, attempt))
    }
    ZIO
      .foreachPar(jobs) { case (caseIndex, evalCase, attempt) =>
        execute(evalCase, attempt)
          .map(AgentEvalGrader.grade(evalCase, _))
          .map(report => (caseIndex, AgentEvalTrialReport(attempt, report)))
      }
      .withParallelism(maxParallelism)
      .map { completed =>
        AgentEvalReliabilityReport(
          cases.zipWithIndex.map { case (evalCase, caseIndex) =>
            AgentEvalCaseReliability(
              evalCase.id,
              evalCase.datasetVersion,
              completed.collect { case (`caseIndex`, trial) => trial }
            )
          }
        )
      }

  /** 对带 provenance 的发布数据集运行多试验；校验失败时不会调用 Provider 或工具。 */
  def runRepeated(
      dataset: AgentEvalDataset,
      trialsPerCase: Int
  )(
      execute: (AgentEvalCase, Int) => IO[AgentError, AgentEvalObservation]
  ): IO[AgentError, AgentEvalReliabilityReport] =
    dataset.validateForRelease *> runRepeated(dataset.cases, trialsPerCase)(execute)
