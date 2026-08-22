package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.test.*

object AgentEvaluationSpec extends ZIOSpecDefault:
  def spec = suite("AgentEvaluation")(
    test("outcome、trajectory、safety 与 resource 作为独立证据关注点") {
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
      assertTrue(
        report.passed,
        report.grades.length == 7,
        report.averageScore == 1.0,
        report.axisSummaries.map(_.axis) ==
          Chunk(EvalAxis.Outcome, EvalAxis.Trajectory, EvalAxis.Safety, EvalAxis.Resource),
        report.axisSummaries.forall(_.passed)
      )
    },
    test("结构化 outcome 标签精确不匹配时阻断发布") {
      val evalCase = AgentEvalCase(
        "public-qa",
        "v1",
        "公开问答",
        expectedOutcomeLabels = Set("yes")
      )
      val observation = AgentEvalObservation(
        Chunk.empty,
        Set.empty,
        recovered = false,
        duplicateSideEffects = 0,
        RunStatus.Completed,
        latencyMillis = 1L,
        TokenUsage(1L, 1L),
        BigDecimal(0),
        outcomeLabels = Set("maybe")
      )
      val grade = AgentEvalGrader
        .grade(evalCase, observation)
        .grades
        .find(_.dimension == "outcome-labels")
      assertTrue(grade.exists(!_.passed))
    },
    test("旧 JSON 缺少 outcome labels 时按空集合读取") {
      val legacyCase =
        """{"id":"legacy","datasetVersion":"v1","input":"x"}""".fromJson[AgentEvalCase]
      val legacyObservation =
        """{"selectedTools":[],"citationIds":[],"recovered":false,"duplicateSideEffects":0,"terminalStatus":"Completed","latencyMillis":1,"usage":{"inputTokens":1,"outputTokens":1},"estimatedCost":"0"}"""
          .fromJson[AgentEvalObservation]
      assertTrue(
        legacyCase.exists(_.expectedOutcomeLabels.isEmpty),
        legacyObservation.exists(_.outcomeLabels.isEmpty)
      )
    },
    test("可选轨迹维度失败时不能被其它硬门禁掩盖") {
      val evalCase    = AgentEvalCase("case-traj", "v1", "脱敏输入")
      val observation = AgentEvalObservation(
        Chunk.empty,
        Set.empty,
        recovered = false,
        duplicateSideEffects = 0,
        RunStatus.Completed,
        latencyMillis = 1L,
        TokenUsage(1L, 1L),
        BigDecimal(0)
      )
      val leaked = TrajectoryReplayEvidence(
        CapturePolicy.Replayable,
        ledgerSize = 1,
        reconstructedEqualsRecorded = true,
        inspectionLeakedSecrets = true
      )
      val passed =
        AgentEvalGrader.grade(evalCase, observation, Some(leaked.copy(inspectionLeakedSecrets = false)))
      val failed = AgentEvalGrader.grade(evalCase, observation, Some(leaked))
      assertTrue(
        !failed.passed,
        passed.passed,
        passed.grades.length == 9,
        passed.grades.takeRight(2).map(_.dimension) ==
          Chunk(TrajectoryReplay.Dimension, TrajectoryReplay.SafetyDimension),
        failed.grades.find(_.dimension == TrajectoryReplay.Dimension).exists(_.passed),
        failed.grades.find(_.dimension == TrajectoryReplay.SafetyDimension).exists(!_.passed)
      )
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
    test("正确终态不能掩盖恢复期间的重复副作用") {
      val evalCase = AgentEvalCase("duplicate-side-effect", "v1", "恢复后继续")
      val report   = AgentEvalGrader.grade(
        evalCase,
        AgentEvalObservation(
          Chunk.empty,
          Set.empty,
          recovered = true,
          duplicateSideEffects = 1,
          RunStatus.Completed,
          latencyMillis = 1L,
          TokenUsage(1L, 1L),
          BigDecimal(0)
        )
      )
      assertTrue(
        !report.passed,
        report.grades.find(_.dimension == "recovery-correctness").exists(_.passed),
        report.grades.find(_.dimension == "duplicate-side-effect-safety").exists(!_.passed),
        report.axisSummaries.find(_.axis == EvalAxis.Safety).exists(!_.passed)
      )
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
        math.abs(reliability.confidenceInterval95.lower - 0.30064184258240184) < 1e-12,
        math.abs(reliability.confidenceInterval95.upper - 0.9544127391902995) < 1e-12,
        !reliability.passedEveryTrial
      )
    },
    test("Wilson 区间不会把一次成功误报为可靠的百分之百") {
      val oneOfOne   = BinomialConfidenceInterval.wilson95(successes = 1, samples = 1)
      val fiveOfFive = BinomialConfidenceInterval.wilson95(successes = 5, samples = 5)
      assertTrue(
        oneOfOne.lower < 0.5,
        oneOfOne.upper == 1.0,
        fiveOfFive.lower > 0.5,
        fiveOfFive.lower > oneOfOne.lower
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
