package com.zyblw.agent.observability.otlp

import com.zyblw.agent.observability.*
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

/** 使用 OpenTelemetry 官方 InMemoryMetricReader 验证 instruments、usage 和低基数策略。 */
object OpenTelemetryAgentMetricsSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("OpenTelemetryAgentMetrics")(
    test("输出 GenAI 标准指标并把未知模型和动态工具名折叠为低基数值") {
      ZIO
        .acquireRelease(
          ZIO.attempt {
            val reader   = InMemoryMetricReader.create()
            val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
            reader -> provider
          }.orDie
        ) { case (_, provider) => ZIO.attempt(provider.close()).orDie }
        .flatMap { case (reader, provider) =>
          val meter   = provider.meterBuilder("com.zyblw.agent.test").build()
          val metrics = OpenTelemetryAgentMetrics.make(
            meter,
            MetricAttributePolicy(
              allowedModels = Set("deepseek-chat"),
              allowedToolNames = Set("safe_lookup")
            )
          )
          for
            _ <- metrics.record(
              AgentMetric.ModelCallFinished(
                provider = "gemini-native",
                model = "attacker-model-patient-secret",
                outcome = MetricOutcome.Succeeded,
                durationSeconds = Some(1.25),
                inputTokens = 11L,
                outputTokens = 4L,
                cachedInputTokens = 6L,
                reasoningOutputTokens = 2L
              )
            )
            _ <- metrics.record(AgentMetric.ContextPrepared(1200L, 3L, 1L, 2L, 4L, 2L))
            _ <- metrics.record(AgentMetric.ContextCompacted(12L, 1L, 6L, 2L))
            _ <- metrics.record(
              AgentMetric.ToolCallFinished(
                toolName = "tenant-dynamic-secret-tool",
                risk = "ApprovalWrite",
                outcome = MetricOutcome.Failed,
                durationSeconds = Some(0.5)
              )
            )
            all         = reader.collectAllMetrics().asScala.toVector
            names       = all.map(_.getName).toSet
            tokenMetric = all.find(_.getName == "gen_ai.client.token.usage")
            tokenPoints = tokenMetric.toVector.flatMap(_.getHistogramData.getPoints.asScala)
            tokenSum    = tokenPoints.map(_.getSum).sum
            serialized  = all.toString
          yield assertTrue(
            names.contains("gen_ai.client.operation.duration"),
            names.contains("gen_ai.client.token.usage"),
            names.contains("zyblw.agent.model.cached.input.token.count"),
            names.contains("zyblw.agent.model.reasoning.output.token.count"),
            names.contains("zyblw.agent.model.call.count"),
            names.contains("zyblw.agent.tool.call.count"),
            names.contains("zyblw.agent.context.build.count"),
            names.contains("zyblw.agent.context.estimated.token.count"),
            names.contains("zyblw.agent.context.dropped.item.count"),
            names.contains("zyblw.agent.context.rot.signal.count"),
            names.contains("zyblw.agent.context.compression.count"),
            names.contains("zyblw.agent.context.compression.model.call.count"),
            names.contains("zyblw.agent.context.compression.covered.message.count"),
            tokenPoints.map(_.getCount).sum == 4L,
            tokenSum == 23.0,
            serialized.contains("gcp.gen_ai"),
            serialized.contains("other"),
            !serialized.contains("attacker-model-patient-secret"),
            !serialized.contains("tenant-dynamic-secret-tool")
          )
        }
    }
  )
