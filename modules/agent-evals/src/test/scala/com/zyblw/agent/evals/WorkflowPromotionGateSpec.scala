package com.zyblw.agent.evals

import zio.Chunk
import zio.test.*

object WorkflowPromotionGateSpec extends ZIOSpecDefault:
  private def report(outcome: Double, safety: Double, resource: Double, passed: Boolean = true) =
    AgentEvalReport(
      "wf-1",
      "v1",
      Chunk(
        EvalGrade("outcome-labels", passed, outcome, "outcome"),
        EvalGrade("forbidden-tool-safety", passed, safety, "safety"),
        EvalGrade("resource-budget", passed, resource, "resource")
      )
    )

  private def suiteOf(value: AgentEvalReport) = AgentEvalSuiteReport(Chunk(value))

  def spec = suite("WorkflowPromotionGate")(
    test("候选必须通过、outcome 严格更好，且安全/资源不能回退") {
      val baseline = suiteOf(report(0.85, 1.0, 0.95))
      val better   = suiteOf(report(0.95, 1.0, 0.95))
      val same     = suiteOf(report(0.85, 1.0, 0.95))
      val unsafe   = suiteOf(report(0.95, 0.7, 0.95))
      val costly   = suiteOf(report(0.95, 1.0, 0.7))
      val failed   = suiteOf(report(0.95, 1.0, 0.95, passed = false))
      assertTrue(
        WorkflowPromotionGate.decide(baseline, better).isRight,
        WorkflowPromotionGate.decide(baseline, same) == Left("no-outcome-advantage"),
        WorkflowPromotionGate.decide(baseline, unsafe) == Left("safety-regression"),
        WorkflowPromotionGate.decide(baseline, costly) == Left("resource-regression"),
        WorkflowPromotionGate.decide(baseline, failed) == Left("candidate-failed"),
        WorkflowPromotionGate.decide(failed, better) == Left("baseline-single-agent-failed")
      )
    }
  )
