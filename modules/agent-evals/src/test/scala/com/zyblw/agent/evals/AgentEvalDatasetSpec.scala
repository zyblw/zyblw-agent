package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.test.*

object AgentEvalDatasetSpec extends ZIOSpecDefault:
  private val reviewedAt = Instant.parse("2026-08-21T00:00:00Z")

  private val first = AgentEvalCase(
    id = "case-a",
    datasetVersion = "dataset-v1",
    input = "已脱敏输入 A",
    expectedTools = Set("lookup", "calculator"),
    forbiddenTools = Set("delete"),
    expectedCitationIds = Set("doc-1")
  )

  private val second = AgentEvalCase(
    id = "case-b",
    datasetVersion = "dataset-v1",
    input = "已脱敏输入 B"
  )

  private val cases = Chunk(first, second)

  private def dataset(
      values: Chunk[AgentEvalCase] = cases,
      digestCases: Chunk[AgentEvalCase] = cases,
      status: EvalDatasetReviewStatus = EvalDatasetReviewStatus.Approved,
      reviewerId: Option[String] = Some("reviewer-1"),
      approvedAt: Option[Instant] = Some(reviewedAt),
      reviewerIds: Set[String] = Set("reviewer-1", "reviewer-2"),
      sources: Set[EvalDatasetSource] = Set(EvalDatasetSource.Curated, EvalDatasetSource.IncidentRedacted),
      adjudicatorId: Option[String] = None,
      upstreams: Chunk[EvalDatasetUpstream] = Chunk.empty
  ): AgentEvalDataset =
    AgentEvalDataset(
      AgentEvalDatasetProvenance(
        schemaVersion = AgentEvalDatasetProvenance.CurrentSchemaVersion,
        datasetId = "tcm-learning-golden",
        datasetVersion = "dataset-v1",
        sources = sources,
        changeId = "change-42",
        ownerId = "eval-team",
        reviewStatus = status,
        reviewerId = reviewerId,
        reviewedAt = approvedAt,
        contentSha256 = AgentEvalDataset.contentSha256(digestCases),
        reviewerIds = reviewerIds,
        adjudicatorId = adjudicatorId,
        upstreams = upstreams
      ),
      values
    )

  private val passingObservation = AgentEvalObservation(
    selectedTools = Chunk("lookup", "calculator"),
    citationIds = Set("doc-1"),
    recovered = false,
    duplicateSideEffects = 0,
    terminalStatus = RunStatus.Completed,
    latencyMillis = 1L,
    usage = TokenUsage(1L, 1L),
    estimatedCost = BigDecimal(0)
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentEvalDataset")(
    test("内容摘要对 Set 构造顺序稳定，并绑定用例顺序与完整内容") {
      val reorderedSets = first.copy(
        expectedTools = List("calculator", "lookup").toSet,
        expectedCitationIds = List("doc-1").toSet
      )
      assertTrue(
        AgentEvalDataset.contentSha256(cases) ==
          AgentEvalDataset.contentSha256(Chunk(reorderedSets, second)),
        AgentEvalDataset.contentSha256(cases) != AgentEvalDataset.contentSha256(cases.reverse),
        AgentEvalDataset.contentSha256(cases) !=
          AgentEvalDataset.contentSha256(Chunk(first.copy(input = "被篡改"), second))
      )
    },
    test("已批准、版本一致且摘要匹配的数据集可以运行") {
      val runner = AgentEvalRunner(maxParallelism = 2)
      for
        validated <- dataset().validateForRelease.either
        report    <- runner.run(dataset())(evalCase =>
          ZIO.succeed(
            if evalCase.id == first.id then passingObservation
            else passingObservation.copy(selectedTools = Chunk.empty, citationIds = Set.empty)
          )
        )
      yield assertTrue(
        validated.isRight,
        report.passed,
        report.reports.map(_.caseId) == Chunk("case-a", "case-b")
      )
    },
    test("草稿、缺失审查身份和版本漂移都 fail-closed") {
      val draft = dataset(
        status = EvalDatasetReviewStatus.Draft,
        reviewerId = None,
        approvedAt = None
      )
      val versionDrift = dataset(values = cases.updated(1, second.copy(datasetVersion = "dataset-v2")))
      for
        draftResult   <- draft.validateForRelease.either
        versionResult <- versionDrift.validateForRelease.either
      yield assertTrue(
        draftResult.left.exists(_.message == "agent-eval-dataset:review-not-approved"),
        versionResult.left.exists(_.message == "agent-eval-dataset:dataset-version-mismatch")
      )
    },
    test("发布门禁要求双人审查，人工分歧样本还要求独立仲裁") {
      val singleReviewer = dataset(reviewerIds = Set("reviewer-1"))
      val disagreement   = dataset(
        sources = Set(EvalDatasetSource.Curated, EvalDatasetSource.HumanDisagreementRedacted)
      )
      val adjudicated = dataset(
        sources = Set(EvalDatasetSource.Curated, EvalDatasetSource.HumanDisagreementRedacted),
        adjudicatorId = Some("adjudicator-1")
      )
      for
        singleResult       <- singleReviewer.validateForRelease.either
        disagreementResult <- disagreement.validateForRelease.either
        adjudicatedResult  <- adjudicated.validateForRelease.either
      yield assertTrue(
        singleResult.left.exists(_.message == "agent-eval-dataset:review-not-approved"),
        disagreementResult.left.exists(
          _.message == "agent-eval-dataset:missing-independent-adjudicator"
        ),
        adjudicatedResult.isRight
      )
    },
    test("公开数据集必须固定不可变 revision、许可证、源摘要和选择协议") {
      val validUpstream = EvalDatasetUpstream(
        id = "agent-injection-bench",
        revision = "v0.1",
        license = "Apache-2.0",
        url = "https://huggingface.co/datasets/sincpp/AgentInjectionBench",
        sourceSha256 = "1" * 64,
        selectionProtocol = "stratified-v1"
      )
      val valid = dataset(
        sources = Set(EvalDatasetSource.PublicOpenDataset),
        upstreams = Chunk(validUpstream)
      )
      val mutableRevision = dataset(
        sources = Set(EvalDatasetSource.PublicOpenDataset),
        upstreams = Chunk(validUpstream.copy(revision = "main"))
      )
      assertTrue(
        AgentEvalDataset.validateForRelease(valid).isRight,
        AgentEvalDataset
          .validateForRelease(mutableRevision)
          .left
          .exists(
            _.message == "agent-eval-dataset:invalid-public-upstream"
          )
      )
    },
    test("摘要漂移在任何 Provider 或工具执行前失败") {
      val tampered = dataset(values = cases.updated(0, first.copy(input = "篡改后的输入")))
      val runner   = AgentEvalRunner(maxParallelism = 2)
      for
        executions <- Ref.make(0)
        result     <- runner
          .runRepeated(tampered, trialsPerCase = 3)((_, _) => executions.update(_ + 1).as(passingObservation))
          .either
        count <- executions.get
      yield assertTrue(
        result.left.exists(_.message == "agent-eval-dataset:content-digest-mismatch"),
        count == 0
      )
    }
  )
