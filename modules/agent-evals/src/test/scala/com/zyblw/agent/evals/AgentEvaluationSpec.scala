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
    }
  )
