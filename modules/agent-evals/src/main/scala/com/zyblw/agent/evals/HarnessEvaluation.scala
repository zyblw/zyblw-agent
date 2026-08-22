package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 同一 case/attempt 的有无 Harness 观测；执行顺序、随机种子和 Provider 固定方式由业务评测适配器负责。 */
final case class HarnessPairedObservation(
    baseline: AgentEvalObservation,
    harness: AgentEvalObservation,
    baselineHumanInterventions: Int = 0,
    harnessHumanInterventions: Int = 0
):
  require(
    baselineHumanInterventions >= 0 && harnessHumanInterventions >= 0,
    "人工介入次数不能为负数"
  )

/** 单个变体的低敏测量。问题、回答、轨迹正文和 Tool 参数不进入该结构。 */
final case class HarnessEvalMeasurement(
    report: AgentEvalReport,
    latencyMillis: Long,
    totalTokens: Long,
    estimatedCost: BigDecimal,
    humanInterventions: Int
) derives JsonCodec:
  require(
    latencyMillis >= 0L && totalTokens >= 0L && estimatedCost >= 0 && humanInterventions >= 0,
    "Harness 评测测量不能为负数"
  )
  require(
    Set(EvalAxis.Outcome, EvalAxis.Trajectory, EvalAxis.Safety, EvalAxis.Resource)
      .subsetOf(report.axisSummaries.map(_.axis).toSet),
    "Harness 成对评测必须包含 outcome/trajectory/safety/resource 四轴"
  )

  def axisScore(axis: EvalAxis): Double =
    report.axisSummaries.find(_.axis == axis).fold(0.0)(_.averageScore)

  def axisPassed(axis: EvalAxis): Boolean =
    report.axisSummaries.find(_.axis == axis).exists(_.passed)

object HarnessEvalMeasurement:
  def from(
      evalCase: AgentEvalCase,
      observation: AgentEvalObservation,
      humanInterventions: Int
  ): HarnessEvalMeasurement =
    HarnessEvalMeasurement(
      AgentEvalGrader.grade(evalCase, observation),
      observation.latencyMillis,
      observation.usage.totalTokens,
      observation.estimatedCost,
      humanInterventions
    )

/** 同一用例的一次成对试验。 */
final case class HarnessEvalTrialComparison(
    attempt: Int,
    baseline: HarnessEvalMeasurement,
    harness: HarnessEvalMeasurement
) derives JsonCodec:
  require(attempt > 0, "Harness 成对试验 attempt 必须大于零")
  require(
    baseline.report.caseId == harness.report.caseId &&
      baseline.report.datasetVersion == harness.report.datasetVersion,
    "Harness 成对试验的 case 与 datasetVersion 必须一致"
  )

/** Harness 成对发布策略。阈值必须由业务数据集所有者显式审查；默认值只阻止一次跑绿、安全退化和无界资源放大。 */
final case class HarnessEvalPolicy(
    minimumTrialsPerCase: Int = 5,
    minimumHarnessSuccessRate: Double = 1.0,
    minimumHarnessWilsonLowerBound95: Double = 0.5,
    minimumOutcomeDelta: Double = 0.0,
    minimumTrajectoryDelta: Double = 0.0,
    maximumLatencyRatio: Double = 1.25,
    maximumTokenRatio: Double = 1.25,
    maximumCostRatio: Double = 1.25,
    maximumHumanInterventionDelta: Double = 0.0
):
  require(minimumTrialsPerCase > 0, "minimumTrialsPerCase 必须大于零")
  require(probability(minimumHarnessSuccessRate), "minimumHarnessSuccessRate 必须是 0..1 的有限数")
  require(
    probability(minimumHarnessWilsonLowerBound95),
    "minimumHarnessWilsonLowerBound95 必须是 0..1 的有限数"
  )
  require(delta(minimumOutcomeDelta), "minimumOutcomeDelta 必须是 -1..1 的有限数")
  require(delta(minimumTrajectoryDelta), "minimumTrajectoryDelta 必须是 -1..1 的有限数")
  require(
    List(maximumLatencyRatio, maximumTokenRatio, maximumCostRatio).forall(positiveFinite),
    "Harness 资源倍率必须是正有限数"
  )
  require(
    java.lang.Double.isFinite(maximumHumanInterventionDelta),
    "maximumHumanInterventionDelta 必须是有限数"
  )

  private def probability(value: Double): Boolean =
    java.lang.Double.isFinite(value) && value >= 0.0 && value <= 1.0

  private def delta(value: Double): Boolean =
    java.lang.Double.isFinite(value) && value >= -1.0 && value <= 1.0

  private def positiveFinite(value: Double): Boolean =
    java.lang.Double.isFinite(value) && value > 0.0

/** 一个用例的成对多试验聚合。所有指标都按相同 attempt 配对，不能拿不同运行批次的均值冒充 A/B 证据。 */
final case class HarnessEvalCaseComparison(
    caseId: String,
    datasetVersion: String,
    trials: Chunk[HarnessEvalTrialComparison]
) derives JsonCodec:
  require(caseId.trim.nonEmpty && datasetVersion.trim.nonEmpty, "Harness 成对报告身份不能为空")
  require(trials.nonEmpty, "Harness 成对报告至少需要一次试验")
  require(
    trials.map(_.attempt) == Chunk.fromIterable(1 to trials.length),
    "Harness 成对报告 attempt 必须从 1 开始连续递增"
  )
  require(
    trials.forall(trial =>
      trial.baseline.report.caseId == caseId &&
        trial.harness.report.caseId == caseId &&
        trial.baseline.report.datasetVersion == datasetVersion &&
        trial.harness.report.datasetVersion == datasetVersion
    ),
    "Harness 成对报告中的用例身份必须一致"
  )

  def harnessSuccesses: Int = trials.count(_.harness.report.passed)

  def harnessSuccessRate: Double = harnessSuccesses.toDouble / trials.length.toDouble

  def harnessConfidenceInterval95: BinomialConfidenceInterval =
    BinomialConfidenceInterval.wilson95(harnessSuccesses, trials.length)

  def harnessSafetyFailures: Int = trials.count(!_.harness.axisPassed(EvalAxis.Safety))

  def outcomeDelta: Double = meanAxis(EvalAxis.Outcome, _.harness) - meanAxis(EvalAxis.Outcome, _.baseline)

  def trajectoryDelta: Double =
    meanAxis(EvalAxis.Trajectory, _.harness) - meanAxis(EvalAxis.Trajectory, _.baseline)

  def latencyRatio: Double =
    ratio(mean(_.harness.latencyMillis.toDouble), mean(_.baseline.latencyMillis.toDouble))

  def tokenRatio: Double = ratio(mean(_.harness.totalTokens.toDouble), mean(_.baseline.totalTokens.toDouble))

  def costRatio: Double =
    ratio(mean(_.harness.estimatedCost.toDouble), mean(_.baseline.estimatedCost.toDouble))

  def humanInterventionDelta: Double =
    mean(_.harness.humanInterventions.toDouble) - mean(_.baseline.humanInterventions.toDouble)

  def passed(policy: HarnessEvalPolicy): Boolean =
    trials.length >= policy.minimumTrialsPerCase &&
      harnessSuccessRate >= policy.minimumHarnessSuccessRate &&
      harnessConfidenceInterval95.lower >= policy.minimumHarnessWilsonLowerBound95 &&
      harnessSafetyFailures == 0 &&
      outcomeDelta >= policy.minimumOutcomeDelta &&
      trajectoryDelta >= policy.minimumTrajectoryDelta &&
      latencyRatio <= policy.maximumLatencyRatio &&
      tokenRatio <= policy.maximumTokenRatio &&
      costRatio <= policy.maximumCostRatio &&
      humanInterventionDelta <= policy.maximumHumanInterventionDelta

  private def meanAxis(axis: EvalAxis, select: HarnessEvalTrialComparison => HarnessEvalMeasurement): Double =
    mean(trial => select(trial).axisScore(axis))

  private def mean(select: HarnessEvalTrialComparison => Double): Double =
    trials.map(select).sum / trials.length.toDouble

  private def ratio(candidate: Double, baseline: Double): Double =
    if baseline == 0.0 then if candidate == 0.0 then 1.0 else Double.PositiveInfinity
    else candidate / baseline

/** 全数据集 Harness 成对报告。 */
final case class HarnessEvalSuiteComparison(cases: Chunk[HarnessEvalCaseComparison]) derives JsonCodec:
  def passed(policy: HarnessEvalPolicy): Boolean = cases.nonEmpty && cases.forall(_.passed(policy))

/** 有界并发的 Harness A/B runner。执行函数一次返回完整 pair，随机化与 Provider 固定不会被框架暗中拆开。 */
final class HarnessEvalRunner(maxParallelism: Int):
  require(maxParallelism > 0, "maxParallelism 必须大于零")

  def runRepeated(
      dataset: AgentEvalDataset,
      trialsPerCase: Int
  )(
      executePair: (AgentEvalCase, Int) => IO[AgentError, HarnessPairedObservation]
  ): IO[AgentError, HarnessEvalSuiteComparison] =
    require(trialsPerCase > 0, "trialsPerCase 必须大于零")
    val jobs = dataset.cases.zipWithIndex.flatMap { case (evalCase, caseIndex) =>
      Chunk.fromIterable(1 to trialsPerCase).map(attempt => (caseIndex, evalCase, attempt))
    }
    dataset.validateForRelease *>
      ZIO
        .foreachPar(jobs) { case (caseIndex, evalCase, attempt) =>
          executePair(evalCase, attempt).map { pair =>
            val comparison = HarnessEvalTrialComparison(
              attempt,
              HarnessEvalMeasurement.from(evalCase, pair.baseline, pair.baselineHumanInterventions),
              HarnessEvalMeasurement.from(evalCase, pair.harness, pair.harnessHumanInterventions)
            )
            caseIndex -> comparison
          }
        }
        .withParallelism(maxParallelism)
        .map { completed =>
          HarnessEvalSuiteComparison(
            dataset.cases.zipWithIndex.map { case (evalCase, caseIndex) =>
              HarnessEvalCaseComparison(
                evalCase.id,
                evalCase.datasetVersion,
                completed.collect { case (`caseIndex`, trial) => trial }
              )
            }
          )
        }
