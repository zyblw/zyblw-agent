package com.zyblw.agent.observability.otlp

import com.zyblw.agent.core.TelemetryEvent
import com.zyblw.agent.observability.*
import zio.*
import zio.test.*

/** 只测试纯配置与密钥边界；exporter 网络行为应由独立 OTLP collector 集成测试覆盖。 */
object OtlpTelemetryConfigSpec extends ZIOSpecDefault:
  def spec = suite("OTLP and Langfuse config")(
    test("Langfuse 生成官方 OTLP traces endpoint 且 toString 不泄漏密钥") {
      val config = LangfuseOtlpConfig(
        host = "https://langfuse.example.com/",
        publicKey = "pk-test-secret",
        secretKey = "sk-test-secret",
        serviceVersion = "1.2.3"
      )
      config.toOtlp.map { otlp =>
        assertTrue(
          otlp.tracesEndpoint == "https://langfuse.example.com/api/public/otel/v1/traces",
          otlp.metricsEndpoint.isEmpty,
          otlp.traceHeaders.contains("Authorization"),
          otlp.traceHeaders.get("x-langfuse-ingestion-version").contains("4"),
          otlp.metricHeaders.isEmpty,
          !config.toString.contains("pk-test-secret"),
          !config.toString.contains("sk-test-secret"),
          !otlp.toString.contains("Basic")
        )
      }
    },
    test("拒绝缺少 /v1/traces 的 endpoint 与非法采样率") {
      val endpoint = OtlpTelemetryConfig("https://collector.example.com/api").validated.exit
      val sampling =
        OtlpTelemetryConfig("https://collector.example.com/v1/traces", sampleRatio = 1.1).validated.exit
      val metrics = OtlpTelemetryConfig(
        "https://collector.example.com/v1/traces",
        metricsEndpoint = Some("https://collector.example.com/not-metrics")
      ).validated.exit
      endpoint.zipWith(sampling.zip(metrics))((left, right) =>
        assertTrue(left.isFailure, right._1.isFailure, right._2.isFailure)
      )
    },
    test("collector 不可达时 record 与 emit 仍成功，exporter 故障不污染业务错误通道") {
      val config = OtlpTelemetryConfig(
        tracesEndpoint = "http://127.0.0.1:1/v1/traces",
        metricsEndpoint = Some("http://127.0.0.1:1/v1/metrics"),
        timeout = 50.millis,
        scheduleDelay = 10.millis,
        metricExportInterval = 10.millis
      )
      val program = for
        telemetry <- ZIO.service[AgentTelemetry]
        metrics   <- ZIO.service[AgentMetrics]
        _ <- telemetry.emit(TelemetryEvent("exporter.failure.test", None, None, Map.empty, atEpochMilli = 1L))
        _ <- metrics.record(AgentMetric.RunFinished(MetricOutcome.Succeeded, Some(0.1), 0.0))
        // OTel exporter 使用自己的 Java scheduler；短暂等待让真实连接失败发生，然后验证 Scope 仍可正常关闭。
        _ <- ZIO.attemptBlocking(Thread.sleep(80L)).orDie
      yield assertCompletes

      program.provide(
        Redactor.default,
        OtlpAgentObservability.layer(config)
      )
    } @@ TestAspect.timeout(5.seconds)
  )
