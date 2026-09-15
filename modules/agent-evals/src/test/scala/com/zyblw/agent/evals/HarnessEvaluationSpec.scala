package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.json.*
import zio.test.*

object HarnessEvaluationSpec extends ZIOSpecDefault:
  private val evalCase = AgentEvalCase(
    "long-task",
    "v1",
    "已脱敏长任务",
    expectedTools = Set("lookup"),
    forbiddenTools = Set("delete"),
    expectedCitationIds = Set("doc-1"),
    requireRecovery = true,
    budget = EvalBudget(1000L, 1000L, BigDecimal("1"))
  )

  private val cases = Chunk(evalCase)

  private val dataset = AgentEvalDataset(
    AgentEvalDatasetProvenance(
      AgentEvalDatasetProvenance.CurrentSchemaVersion,
      "harness-ab",
      "v1",
      Set(EvalDatasetSource.Curated),
      "change-1",
      "eval-team",
      EvalDatasetReviewStatus.Approved,
      Some(Instant.parse("2026-08-21T00:00:00Z")),
      AgentEvalDataset.contentSha256(cases),
      reviewerIds = Set("reviewer-1", "reviewer-2")
    ),
    cases
  )

  private def observation(
      tools: Chunk[String] = Chunk("lookup"),
      citations: Set[String] = Set("doc-1"),
      latency: Long = 100L,
      tokens: Long = 100L,
      cost: BigDecimal = BigDecimal("0.10")
  ): AgentEvalObservation =
    AgentEvalObservation(
      tools,
      citations,
      recovered = true,
      duplicateSideEffects = 0,
      RunStatus.Completed,
      latency,
      TokenUsage(tokens / 2L, tokens - tokens / 2L),
      cost
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("HarnessEvaluation")(
    test("同一 case/attempt 成对比较四轴、人工介入与资源，固定并发不放大") {
      val runner = HarnessEvalRunner(maxParallelism = 2)
      for
        active    <- Ref.make(0)
        maxActive <- Ref.make(0)
        report    <- runner.runRepeated(dataset, trialsPerCase = 5) { (_, _) =>
          ZIO.acquireReleaseWith(
            active.updateAndGet(_ + 1).tap(value => maxActive.update(_.max(value)))
          )(_ => active.update(_ - 1))(_ =>
            ZIO.succeed(
              HarnessPairedObservation(
                baseline = observation(latency = 100L, tokens = 100L, cost = BigDecimal("0.10")),
                harness = observation(latency = 110L, tokens = 110L, cost = BigDecimal("0.11")),
                baselineHumanInterventions = 1,
                harnessHumanInterventions = 0
              )
            )
          )
        }
        observedMax <- maxActive.get
        snapshot    <- EvalSuiteSnapshot.fromHarnessComparison(
          EvalSnapshotMetadata(
            "harness-comparison-1",
            "harness-ab-suite",
            "harness-ab",
            "v1",
            "harness-v2",
            Some("stub"),
            Some("stub-model"),
            Some("price-v1"),
            Some("abcdef"),
            Instant.parse("2026-08-21T00:00:00Z"),
            Instant.parse("2026-08-21T00:01:00Z")
          ),
          report
        )
        comparison = report.cases.head
      yield assertTrue(
        report.passed(HarnessEvalPolicy()),
        snapshot.kind == EvalSuiteKind.HarnessComparison,
        snapshot.passed,
        snapshot.cases.head.dimensions.length == 10,
        !snapshot.toJson.contains(evalCase.input),
        !snapshot.toJson.contains("\"trials\":"),
        comparison.harnessSuccessRate == 1.0,
        comparison.harnessConfidenceInterval95.lower > 0.5,
        comparison.harnessSafetyFailures == 0,
        comparison.outcomeDelta == 0.0,
        comparison.trajectoryDelta == 0.0,
        math.abs(comparison.latencyRatio - 1.1) < 1e-12,
        math.abs(comparison.tokenRatio - 1.1) < 1e-12,
        math.abs(comparison.costRatio - 1.1) < 1e-12,
        comparison.humanInterventionDelta == -1.0,
        observedMax <= 2
      )
    },
    test("Harness 的安全失败不能被结果或资源收益抵消") {
      val runner = HarnessEvalRunner(maxParallelism = 1)
      for
        report <- runner.runRepeated(dataset, trialsPerCase = 5) { (_, _) =>
          ZIO.succeed(
            HarnessPairedObservation(
              baseline = observation(),
              harness = observation(
                tools = Chunk("lookup", "delete"),
                latency = 50L,
                tokens = 50L,
                cost = BigDecimal("0.05")
              )
            )
          )
        }
        comparison = report.cases.head
      yield assertTrue(
        comparison.harnessSafetyFailures == 5,
        !comparison.passed(HarnessEvalPolicy()),
        !report.passed(HarnessEvalPolicy())
      )
    },
    test("未审查或摘要漂移的数据集在执行 pair 前 fail-closed") {
      val tampered = dataset.copy(cases = Chunk(evalCase.copy(input = "被篡改")))
      for
        calls  <- Ref.make(0)
        result <- HarnessEvalRunner(1)
          .runRepeated(tampered, 5)((_, _) =>
            calls.update(_ + 1).as(HarnessPairedObservation(observation(), observation()))
          )
          .either
        count <- calls.get
      yield assertTrue(
        result.left.exists(_.message == "agent-eval-dataset:content-digest-mismatch"),
        count == 0
      )
    }
  )
