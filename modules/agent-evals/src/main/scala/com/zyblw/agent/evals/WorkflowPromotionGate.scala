package com.zyblw.agent.evals

/** 多智能体/Workflow 方案必须在固定 eval 上优于已通过的单 Agent 基线，且不能让安全或资源回退。 */
object WorkflowPromotionGate:
  def decide(baseline: AgentEvalSuiteReport, candidate: AgentEvalSuiteReport): Either[String, Unit] =
    if !baseline.passed then Left("baseline-single-agent-failed")
    else if !candidate.passed then Left("candidate-failed")
    else if average(candidate, EvalAxis.Outcome) <= average(baseline, EvalAxis.Outcome) then
      Left("no-outcome-advantage")
    else if average(candidate, EvalAxis.Safety) + 1e-9 < average(baseline, EvalAxis.Safety) then
      Left("safety-regression")
    else if average(candidate, EvalAxis.Resource) + 1e-9 < average(baseline, EvalAxis.Resource) then
      Left("resource-regression")
    else Right(())

  private def average(suite: AgentEvalSuiteReport, axis: EvalAxis): Double =
    val scores = suite.reports.flatMap(_.axisSummaries).collect {
      case summary if summary.axis == axis => summary.averageScore
    }
    if scores.isEmpty then 0.0 else scores.sum / scores.length.toDouble
