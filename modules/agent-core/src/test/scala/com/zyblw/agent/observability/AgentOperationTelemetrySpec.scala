package com.zyblw.agent.observability

import com.zyblw.agent.core.*
import java.util.UUID
import zio.*
import zio.test.*

/** 验证非 Runtime 主事件操作也遵守相同持续时间、错误保持和正文安全边界。 */
object AgentOperationTelemetrySpec extends ZIOSpecDefault:
  private val runId = RunId(UUID.fromString("00000000-0000-4000-8000-000000000201"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentOperationTelemetry")(
    test("检索成功同时写 retriever trace 与 duration/hit metrics，未知操作不泄漏原文") {
      for
        telemetry <- ZIO.service[InMemoryTelemetry]
        metrics   <- ZIO.service[InMemoryAgentMetrics]
        observer = AgentOperationTelemetry(telemetry, metrics)
        result <- observer.retrieval(runId, "患者私密检索操作")(
          TestClock.adjust(2.seconds) *> ZIO.succeed(Chunk("正文一", "正文二"))
        )(_.length.toLong)
        traces <- telemetry.events
        points <- metrics.recorded
        trace = traces.find(_.name == "agent.retrieval")
      yield assertTrue(
        result.length == 2,
        trace.flatMap(_.startedAtEpochMilli).contains(0L),
        trace.exists(_.atEpochMilli == 2000L),
        trace.exists(_.attributes.get("langfuse.observation.type").contains("retriever")),
        trace.exists(_.attributes.get("agent.retrieval.operation").contains("other")),
        points.contains(AgentMetric.RetrievalFinished("other", MetricOutcome.Succeeded, Some(2.0), 2L)),
        !traces.toString.contains("患者私密检索操作"),
        !traces.toString.contains("正文一")
      )
    }.provide(InMemoryTelemetry.layer, InMemoryAgentMetrics.layer),
    test("记忆失败保持原 typed error 并记录 failed，不吞错也不改写为监控错误") {
      val expected = AgentError.MemoryExtractionFailed("safe-code")
      for
        telemetry <- ZIO.service[InMemoryTelemetry]
        metrics   <- ZIO.service[InMemoryAgentMetrics]
        observer = AgentOperationTelemetry(telemetry, metrics)
        exit   <- observer.memory(runId, "extract")(ZIO.fail(expected)).exit
        points <- metrics.recorded
      yield assertTrue(
        exit == Exit.fail(expected),
        points.contains(AgentMetric.MemoryOperationFinished("extract", MetricOutcome.Failed, Some(0.0)))
      )
    }.provide(InMemoryTelemetry.layer, InMemoryAgentMetrics.layer),
    test("评测只输出规范化名称和数值，不接受非有限分数") {
      for
        telemetry <- ZIO.service[InMemoryTelemetry]
        metrics   <- ZIO.service[InMemoryAgentMetrics]
        observer = AgentOperationTelemetry(telemetry, metrics)
        _      <- observer.evaluation(runId, "引用正确率 v1 患者", 0.95, passed = true)
        _      <- observer.evaluation(runId, "bad", Double.NaN, passed = false)
        traces <- telemetry.events
        points <- metrics.recorded
        evalName = traces.headOption.flatMap(_.attributes.get("agent.evaluation.name"))
      yield assertTrue(
        traces.length == 1,
        evalName.exists(_.matches("[a-z0-9._-]{1,80}")),
        !traces.toString.contains("引用正确率"),
        points.length == 1,
        points.headOption.exists {
          case AgentMetric.EvaluationRecorded(name, score, passed) => name.nonEmpty && score == 0.95 && passed
          case _                                                   => false
        }
      )
    }.provide(InMemoryTelemetry.layer, InMemoryAgentMetrics.layer)
  )
