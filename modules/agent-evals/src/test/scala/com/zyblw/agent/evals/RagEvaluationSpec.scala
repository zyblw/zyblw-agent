package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.test.*

/** 验证 RAG 排名、引用、授权与并发评测门禁不会相互掩盖。 */
object RagEvaluationSpec extends ZIOSpecDefault:
  private val tenant = TenantId("tenant-a")
  private val scope  = RetrievalScope(tenant, Set("knowledge:read"), Some("eval-request"))

  /** 创建一条带稳定正文和来源的检索命中。 */
  private def hit(
      id: String,
      score: Double,
      tenantId: TenantId = tenant,
      permissions: Set[String] = Set("knowledge:read")
  ): RetrievalHit = RetrievalHit(
    DocumentChunk(id, s"doc-$id", s"evidence for $id", s"knowledge://$id", tenantId, permissions),
    score,
    Map("rrf" -> score)
  )

  /** 从命中生成与 DefaultRetriever 同样可机械验证的引用。 */
  private def citation(id: String, sourceId: String, score: Double): Citation =
    Citation(id, s"knowledge://$sourceId", s"evidence for $sourceId", score)

  private val baseCase = RagEvalCase(
    id = "rag-1",
    datasetVersion = "2026-07",
    query = "查询有出处的知识",
    scope = scope,
    expectedRelevantChunkIds = Set("relevant-1", "relevant-2"),
    forbiddenChunkIds = Set("forbidden"),
    requiredCitationSourceUris = Set("knowledge://relevant-1"),
    limit = 3,
    thresholds = RagEvalThresholds(
      minRecallAtK = 1.0,
      minPrecisionAtK = 0.6,
      minMrr = 1.0,
      minNdcg = 0.9,
      minCitationSupport = 1.0,
      maxLatencyMillis = 100L
    )
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("RagEvaluation")(
    test("Recall、Precision、MRR、NDCG、引用和授权全部通过才放行") {
      val result = RetrievalResult(
        Chunk(hit("relevant-1", 0.9), hit("relevant-2", 0.8), hit("noise", 0.7)),
        Chunk(citation("cite-1", "relevant-1", 0.9))
      )
      val report = RagEvalGrader.grade(baseCase, RagEvalObservation(result, 50L))
      assertTrue(report.passed, report.grades.length == 4, report.grades.forall(_.score >= 0.0))
    },
    test("相关结果排名过低时 MRR/NDCG 门禁失败") {
      val result = RetrievalResult(
        Chunk(hit("noise", 0.9), hit("relevant-1", 0.8), hit("relevant-2", 0.7)),
        Chunk(citation("cite-1", "relevant-1", 0.8))
      )
      val report  = RagEvalGrader.grade(baseCase, RagEvalObservation(result, 10L))
      val ranking = report.grades.find(_.dimension == "rag-ranking")
      assertTrue(!report.passed, ranking.exists(!_.passed))
    },
    test("越权、禁止片段、重复结果和非有限分数是不可平均掉的安全失败") {
      val otherTenant = TenantId("tenant-b")
      val result      = RetrievalResult(
        Chunk(
          hit("relevant-1", 0.9),
          hit("forbidden", 0.8),
          hit("relevant-1", Double.NaN, otherTenant)
        ),
        Chunk(citation("cite-1", "relevant-1", 0.9))
      )
      val report   = RagEvalGrader.grade(baseCase, RagEvalObservation(result, 10L))
      val security = report.grades.find(_.dimension == "rag-authorization-and-integrity")
      assertTrue(!report.passed, security.exists(grade => !grade.passed && grade.score == 0.0))
    },
    test("引用只有 URL 不够，excerpt 必须能由同来源命中正文支持") {
      val result = RetrievalResult(
        Chunk(hit("relevant-1", 0.9), hit("relevant-2", 0.8), hit("noise", 0.7)),
        Chunk(Citation("cite-1", "knowledge://relevant-1", "不存在的证据", 0.9))
      )
      val report = RagEvalGrader.grade(baseCase, RagEvalObservation(result, 10L))
      assertTrue(!report.passed, report.grades.find(_.dimension == "rag-citation-support").exists(!_.passed))
    },
    test("Runner 保持数据集顺序，空数据集不能制造假绿") {
      val runner      = RagEvalRunner(maxParallelism = 2)
      val second      = baseCase.copy(id = "rag-2")
      val validResult = RetrievalResult(
        Chunk(hit("relevant-1", 0.9), hit("relevant-2", 0.8), hit("noise", 0.7)),
        Chunk(citation("cite-1", "relevant-1", 0.9))
      )
      for
        report <- runner.run(Chunk(baseCase, second))(evalCase =>
          ZIO.succeed(RagEvalObservation(validResult, if evalCase.id == "rag-1" then 10L else 20L))
        )
        empty <- runner.run(Chunk.empty)(_ => ZIO.dieMessage("空数据集不应执行"))
      yield assertTrue(
        report.reports.map(_.caseId) == Chunk("rag-1", "rag-2"),
        report.passed,
        !empty.passed,
        empty.passRate == 0.0
      )
    }
  )
