package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object AgentEvaluationSpec extends ZIOSpecDefault:
  def spec = suite("AgentEvaluation")(
    test("工具、引用、恢复与资源预算全部作为独立硬门禁") {
      val evalCase = AgentEvalCase(
        "case-1",
        "v1",
        "查询有出处的知识",
        expectedTools = Set("knowledge_lookup"),
        forbiddenTools = Set("delete_content"),
        expectedCitationIds = Set("doc-1"),
        requireRecovery = true,
        budget = EvalBudget(1000L, 100L, BigDecimal("0.01"))
      )
      val observation = AgentEvalObservation(
        Chunk("knowledge_lookup"),
        Set("doc-1"),
        recovered = true,
        duplicateSideEffects = 0,
        RunStatus.Completed,
        latencyMillis = 800L,
        TokenUsage(40L, 20L),
        BigDecimal("0.005")
      )
      val report = AgentEvalGrader.grade(evalCase, observation)
      assertTrue(report.passed, report.grades.length == 4, report.averageScore == 1.0)
    },
    test("平均质量不能掩盖禁止工具和成本超限") {
      val evalCase = AgentEvalCase(
        "case-2",
        "v1",
        "危险输入",
        forbiddenTools = Set("delete_content"),
        budget = EvalBudget(maxEstimatedCost = BigDecimal("0.01"))
      )
      val observation = AgentEvalObservation(
        Chunk("delete_content"),
        Set.empty,
        recovered = false,
        duplicateSideEffects = 0,
        RunStatus.Completed,
        latencyMillis = 1L,
        TokenUsage(1L, 1L),
        BigDecimal("0.02")
      )
      val report = AgentEvalGrader.grade(evalCase, observation)
      assertTrue(!report.passed, report.grades.count(!_.passed) == 2)
    },
    test("多次试验同时报告逐次成功率、pass@k 与 pass^k") {
      val evalCase = AgentEvalCase("reliability-case", "v1", "重复执行")
      val passed   = AgentEvalGrader.grade(
        evalCase,
        AgentEvalObservation(
          Chunk.empty,
          Set.empty,
          recovered = false,
          duplicateSideEffects = 0,
          RunStatus.Completed,
          latencyMillis = 1L,
          TokenUsage(1L, 1L),
          BigDecimal(0)
        )
      )
      val failed = passed.copy(
        grades = passed.grades.updated(
          0,
          EvalGrade("tool-selection", passed = false, score = 0.0, details = "deterministic-test")
        )
      )
      val reliability = AgentEvalCaseReliability(
        evalCase.id,
        evalCase.datasetVersion,
        Chunk(
          AgentEvalTrialReport(1, passed),
          AgentEvalTrialReport(2, passed),
          AgentEvalTrialReport(3, passed),
          AgentEvalTrialReport(4, failed)
        )
      )
      assertTrue(
        reliability.successes == 3,
        reliability.successRate == 0.75,
        reliability.estimatedPassAtK(3) == 0.984375,
        reliability.estimatedPassPowerK(3) == 0.421875,
        !reliability.passedEveryTrial
      )
    },
    test("重复运行使用同一个有界 job 集合并保持用例与 attempt 顺序") {
      val cases = Chunk(
        AgentEvalCase("case-a", "v1", "a"),
        AgentEvalCase("case-b", "v1", "b")
      )
      val runner = AgentEvalRunner(maxParallelism = 2)
      for
        active    <- Ref.make(0)
        maxActive <- Ref.make(0)
        report    <- runner.runRepeated(cases, trialsPerCase = 3) { (_, attempt) =>
          ZIO.acquireReleaseWith(
            active.updateAndGet(_ + 1).tap(current => maxActive.update(_.max(current)))
          )(_ => active.update(_ - 1))(_ =>
            ZIO.foreachDiscard(0 until (4 - attempt))(_ => ZIO.yieldNow) *>
              ZIO.succeed(
                AgentEvalObservation(
                  Chunk.empty,
                  Set.empty,
                  recovered = false,
                  duplicateSideEffects = 0,
                  RunStatus.Completed,
                  latencyMillis = 1L,
                  TokenUsage(1L, 1L),
                  BigDecimal(0)
                )
              )
          )
        }
        observedMax <- maxActive.get
      yield assertTrue(
        report.cases.map(_.caseId) == Chunk("case-a", "case-b"),
        report.cases.forall(_.trials.map(_.attempt) == Chunk(1, 2, 3)),
        report.passedEveryTrial,
        report.meanSuccessRate == 1.0,
        observedMax <= 2
      )
    }
  )
