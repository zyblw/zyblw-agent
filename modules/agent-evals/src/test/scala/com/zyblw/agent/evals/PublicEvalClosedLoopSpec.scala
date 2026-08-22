package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 维护者审查后的公开数据集闭环：72 条结构化标签 + 禁止工具，不声称领域专家校准。 */
object PublicEvalClosedLoopSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PublicEvalClosedLoop")(
    test("维护者双审后的 72 条公开 fixture 可通过发布完整性门禁") {
      val ready = AgentEvalDataset.validateForRelease(PublicEvalClosedLoop.dataset)
      assertTrue(
        ready.isRight,
        PublicEvalClosedLoop.dataset.cases.length == PublicEvalClosedLoop.CaseCount,
        PublicEvalClosedLoop.dataset.cases.count(_.id.startsWith("pubmedqa-")) ==
          PublicEvalClosedLoop.PubmedCount,
        PublicEvalClosedLoop.dataset.cases.count(_.id.startsWith("injecagent-")) ==
          PublicEvalClosedLoop.InjecCount
      )
    },
    test("脚本化观察对 PubMedQA 标签和 InjecAgent 禁止工具分别计分") {
      val pubmedCase = PublicEvalClosedLoop.dataset.cases
        .find(_.id == "pubmedqa-yes-01")
        .get
      val pubmed = AgentEvalGrader.grade(
        pubmedCase,
        AgentEvalObservation(
          Chunk.empty,
          Set("pmid-yes-01"),
          recovered = false,
          duplicateSideEffects = 0,
          RunStatus.Completed,
          10L,
          TokenUsage(4, 2),
          BigDecimal(0),
          outcomeLabels = Set("yes")
        )
      )
      val attack = AgentEvalGrader.grade(
        PublicEvalClosedLoop.dataset.cases.find(_.id.startsWith("injecagent-direct-harm")).get,
        AgentEvalObservation(
          Chunk(PublicEvalClosedLoop.ForbiddenDirectHarm),
          Set.empty,
          recovered = false,
          duplicateSideEffects = 0,
          RunStatus.Completed,
          10L,
          TokenUsage(4, 2),
          BigDecimal(0)
        )
      )
      val refused = AgentEvalGrader.grade(
        PublicEvalClosedLoop.dataset.cases.find(_.id.startsWith("injecagent-data-stealing")).get,
        AgentEvalObservation(
          Chunk.empty,
          Set.empty,
          recovered = false,
          duplicateSideEffects = 0,
          RunStatus.Completed,
          10L,
          TokenUsage(4, 2),
          BigDecimal(0)
        )
      )
      assertTrue(
        pubmed.passed,
        pubmed.grades.exists(grade => grade.dimension == "outcome-labels" && grade.passed),
        !attack.passed,
        attack.grades.exists(grade => grade.dimension == "forbidden-tool-safety" && !grade.passed),
        refused.passed
      )
    },
    test("脚本化回答提取器只认空格分隔标签和 pmid 引用") {
      val answer = PublicEvalClosedLoop.scriptedAnswer(
        "PubMedQA fixture 3 expecting maybe. Cite pmid-maybe-03."
      )
      assertTrue(
        PublicEvalClosedLoop.labelsFromAnswer(answer) == Set("maybe"),
        PublicEvalClosedLoop.citationsFromAnswer(answer) == Set("pmid-maybe-03"),
        PublicEvalClosedLoop.scriptedAnswer("InjecAgent direct-harm fixture 1").contains("refused")
      )
    }
  )
