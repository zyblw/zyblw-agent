package com.zyblw.agent.observability.otlp

import com.zyblw.agent.core.*
import com.zyblw.agent.observability.*
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
// `export` 在 Scala 3 中是关键字；反引号表示这里是 Java 包名的一部分。
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.time.Duration as JavaDuration
import zio.*

/** Trace 与 Metrics 共用的 OpenTelemetry SDK/OTLP 资源层。
  *
  * 一个 SDK 同时持有 BatchSpanProcessor 与 PeriodicMetricReader，避免业务错误地创建两套 Resource、线程和连接。 SDK 生命周期由
  * `ZLayer.scoped` 管理：应用优雅关闭会 flush/close，测试 Scope 退出后也不会遗留 exporter worker。
  */
object OtlpAgentObservability:
  private val InstrumentationScope = "com.zyblw.agent"

  /** 同时创建安全 Trace 和类型化 Metrics 出口。
    *
    * 使用示例：
    * {{{
    * val observability =
    *   Redactor.default >>>
    *     OtlpAgentObservability.layer(
    *       config.copy(metricsEndpoint = Some("http://otel-collector:4318/v1/metrics")),
    *       MetricAttributePolicy(allowedModels = Set("deepseek-chat"))
    *     )
    *
    * val runtimeObserver = observability >>>
    *   (TelemetryRunObserver.layer ++ MetricsRunObserver.layer)
    * }}}
    *
    * @param config
    *   traces/metrics endpoint、认证、Resource、采样与批处理配置
    * @param metricPolicy
    *   模型名与工具名的低基数 allow-list
    * @param contentPolicy
    *   Trace 正文策略；中医和多租户生产环境保持 MetadataOnly
    * @return
    *   同一个 scoped SDK 支撑的 AgentTelemetry 与 AgentMetrics
    */
  def layer(
      config: OtlpTelemetryConfig,
      metricPolicy: MetricAttributePolicy = MetricAttributePolicy(),
      contentPolicy: ContentRecordingPolicy = ContentRecordingPolicy.MetadataOnly
  ): ZLayer[Redactor, AgentError, AgentTelemetry & AgentMetrics] =
    ZLayer.scopedEnvironment {
      create(config, metricPolicy, contentPolicy).map { case (telemetry, metrics) =>
        ZEnvironment[AgentTelemetry](telemetry) ++ ZEnvironment[AgentMetrics](metrics)
      }
    }

  /** 创建并注册 SDK；该 effect 必须运行在 Scope 内。 */
  private[otlp] def create(
      config: OtlpTelemetryConfig,
      metricPolicy: MetricAttributePolicy,
      contentPolicy: ContentRecordingPolicy
  ): ZIO[Redactor & Scope, AgentError, (AgentTelemetry, AgentMetrics)] =
    for
      validated <- config.validated
      redactor  <- ZIO.service[Redactor]
      sdk       <- ZIO.acquireRelease(buildSdk(validated))(closeSdk)
      tracer = sdk.getTracer(InstrumentationScope, validated.serviceVersion)
      meter  = sdk
        .meterBuilder(InstrumentationScope)
        .setInstrumentationVersion(validated.serviceVersion)
        .build()
      rawTrace  = OpenTelemetryAgentTelemetry(tracer)
      safeTrace = SanitizingTelemetry.make(rawTrace, contentPolicy, redactor)
      metrics   = OpenTelemetryAgentMetrics.make(meter, metricPolicy)
    yield safeTrace -> metrics

  /** 创建一个同时拥有 TracerProvider 与 MeterProvider 的 SDK。
    *
    * metricsEndpoint 为 None 时仍提供 API recorder，但不启动 PeriodicMetricReader；这适合只把 traces 发给 Langfuse。
    */
  private def buildSdk(config: OtlpTelemetryConfig): IO[AgentError, OpenTelemetrySdk] =
    ZIO
      .attempt {
        val resource       = buildResource(config)
        val tracerProvider = buildTracerProvider(config, resource)
        val meterProvider  = buildMeterProvider(config, resource)
        OpenTelemetrySdk
          .builder()
          .setTracerProvider(tracerProvider)
          .setMeterProvider(meterProvider)
          .build()
      }
      .mapError(error => AgentError.InvalidConfiguration(s"无法初始化 OTLP SDK: ${error.getClass.getSimpleName}"))

  /** 创建 OTLP trace exporter、批处理器和采样器。 */
  private def buildTracerProvider(config: OtlpTelemetryConfig, resource: Resource): SdkTracerProvider =
    val exporterBuilder = OtlpHttpSpanExporter
      .builder()
      .setEndpoint(config.tracesEndpoint)
      .setTimeout(JavaDuration.ofMillis(config.timeout.toMillis))
    config.traceHeaders.foreach((name, value) => exporterBuilder.addHeader(name, value))
    val processor = BatchSpanProcessor
      .builder(exporterBuilder.build())
      .setScheduleDelay(JavaDuration.ofMillis(config.scheduleDelay.toMillis))
      .build()
    SdkTracerProvider
      .builder()
      .setResource(resource)
      .setSampler(Sampler.traceIdRatioBased(config.sampleRatio))
      .addSpanProcessor(processor)
      .build()

  /** 创建 MeterProvider。配置 metricsEndpoint 后注册 PeriodicMetricReader；否则保持无 exporter 的轻量 provider。
    */
  private def buildMeterProvider(config: OtlpTelemetryConfig, resource: Resource): SdkMeterProvider =
    val builder = SdkMeterProvider.builder().setResource(resource)
    config.metricsEndpoint.foreach { endpoint =>
      val exporterBuilder = OtlpHttpMetricExporter
        .builder()
        .setEndpoint(endpoint)
        .setTimeout(JavaDuration.ofMillis(config.timeout.toMillis))
      config.metricHeaders.foreach((name, value) => exporterBuilder.addHeader(name, value))
      val reader = PeriodicMetricReader
        .builder(exporterBuilder.build())
        .setInterval(JavaDuration.ofMillis(config.metricExportInterval.toMillis))
        .build()
      builder.registerMetricReader(reader)
    }
    builder.build()

  /** Resource 字段用于按服务、版本和环境聚合；高基数 instanceId 应由部署侧标准配置追加。 */
  private def buildResource(config: OtlpTelemetryConfig): Resource =
    Resource.getDefault.merge(
      Resource.create(
        Attributes
          .builder()
          .put("service.name", config.serviceName)
          .put("service.version", config.serviceVersion)
          .put("deployment.environment.name", config.deploymentEnvironment)
          .build()
      )
    )

  /** 在 blocking pool 中 flush 并关闭 SDK；关闭失败作为 defect 暴露，不伪装成 Agent 业务错误。 */
  private def closeSdk(sdk: OpenTelemetrySdk): UIO[Unit] =
    ZIO.attemptBlocking(sdk.close()).orDie

/** 向后保持“只取 Trace SPI”的窄入口。
  *
  * 新业务若还需要 Metrics，应使用 `OtlpAgentObservability.layer`，从同一个 SDK 同时取得两个服务。
  */
object OtlpAgentTelemetry:
  /** 创建经过内容安全策略包装的 OTLP telemetry。
    *
    * @param config
    *   OTLP endpoint、认证、Resource 和采样配置
    * @param contentPolicy
    *   正文策略；生产与中医业务必须使用 MetadataOnly 或 Disabled
    */
  def layer(
      config: OtlpTelemetryConfig,
      contentPolicy: ContentRecordingPolicy = ContentRecordingPolicy.MetadataOnly
  ): ZLayer[Redactor, AgentError, AgentTelemetry] =
    ZLayer.scoped {
      OtlpAgentObservability
        .create(config, MetricAttributePolicy(), contentPolicy)
        .map(_._1)
    }

/** Langfuse 专用便捷入口：官方 OTLP traces 承载 Generation/Tool/Session，独立 Metrics 发往 Collector。 */
object LangfuseTelemetry:
  /** 创建 Langfuse trace exporter。
    *
    * @param config
    *   Langfuse host 与 project credentials
    * @param contentPolicy
    *   生产默认仅发送元数据
    */
  def layer(
      config: LangfuseOtlpConfig,
      contentPolicy: ContentRecordingPolicy = ContentRecordingPolicy.MetadataOnly
  ): ZLayer[Redactor, AgentError, AgentTelemetry] =
    ZLayer
      .fromZIO(config.toOtlp)
      .flatMap(environment => OtlpAgentTelemetry.layer(environment.get[OtlpTelemetryConfig], contentPolicy))
