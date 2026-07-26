package com.zyblw.agent.observability.otlp

import com.zyblw.agent.core.*
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import zio.*

/** 通用 OTLP/HTTP trace exporter 配置。
  *
  * @param tracesEndpoint
  *   完整 traces endpoint，必须包含 `/v1/traces`；例如 Tempo 的 `http://tempo:4318/v1/traces` 或 Langfuse 的
  *   `https://cloud.langfuse.com/api/public/otel/v1/traces`
  * @param metricsEndpoint
  *   可选的 OTLP/HTTP metrics endpoint，通常以 `/v1/metrics` 结尾。Langfuse 的 LLM 分析来自 traces，因此 Langfuse 便捷配置保持
  *   None；生产平台一般发送到 OTel Collector
  * @param traceHeaders
  *   只发送到 tracesEndpoint 的认证/租户头；与 Metrics 分开，避免 Langfuse 密钥泄漏给 Collector
  * @param metricHeaders
  *   只发送到 metricsEndpoint 的认证/租户头；`toString` 永远不会输出其值
  * @param serviceName
  *   OpenTelemetry Resource 中的服务名
  * @param serviceVersion
  *   应用发布版本，便于比较不同版本的延迟和错误率
  * @param deploymentEnvironment
  *   环境名，例如 `dev`、`staging`、`prod`
  * @param timeout
  *   单次 exporter HTTP 请求超时
  * @param scheduleDelay
  *   BatchSpanProcessor 两次批量发送之间的最大等待
  * @param metricExportInterval
  *   PeriodicMetricReader 的导出周期；过短会增加 collector 压力
  * @param sampleRatio
  *   0 到 1 的采样比例；关键审计仍写 RunStore，不能依赖采样后的 trace
  */
final case class OtlpTelemetryConfig(
    tracesEndpoint: String,
    metricsEndpoint: Option[String] = None,
    traceHeaders: Map[String, String] = Map.empty,
    metricHeaders: Map[String, String] = Map.empty,
    serviceName: String = "zyblw-agent",
    serviceVersion: String = "dev",
    deploymentEnvironment: String = "development",
    timeout: Duration = 10.seconds,
    scheduleDelay: Duration = 1.second,
    metricExportInterval: Duration = 30.seconds,
    sampleRatio: Double = 1.0
):
  /** 防止日志或配置错误报告通过 case class 默认 toString 泄漏 Authorization。 */
  override def toString: String =
    s"OtlpTelemetryConfig(tracesEndpoint=$tracesEndpoint, metricsEndpoint=${metricsEndpoint.fold("disabled")(
        _ => "configured"
      )}, traceHeaders=<redacted:${traceHeaders.size}>, metricHeaders=<redacted:${metricHeaders.size}>, service=$serviceName, version=$serviceVersion, environment=$deploymentEnvironment, sampleRatio=$sampleRatio)"

  /** 在创建 SDK 后台 worker 之前执行配置校验。
    *
    * @return
    *   合法时返回自身，非法时使用 typed `InvalidConfiguration`
    */
  def validated: IO[AgentError, OtlpTelemetryConfig] =
    ZIO.fromEither {
      val endpointValid = scala.util
        .Try(URI.create(tracesEndpoint))
        .toOption
        .exists(uri =>
          Set("http", "https").contains(Option(uri.getScheme).getOrElse("")) &&
            Option(uri.getHost).exists(_.nonEmpty) && uri.getPath.endsWith("/v1/traces")
        )
      val metricsValid = metricsEndpoint.forall(endpoint =>
        scala.util
          .Try(URI.create(endpoint))
          .toOption
          .exists(uri =>
            Set("http", "https").contains(Option(uri.getScheme).getOrElse("")) &&
              Option(uri.getHost).exists(_.nonEmpty) && uri.getPath.endsWith("/v1/metrics")
          )
      )
      if !endpointValid then
        Left(AgentError.InvalidConfiguration("OTLP tracesEndpoint 必须是以 /v1/traces 结尾的 HTTP(S) URL"))
      else if !metricsValid then
        Left(AgentError.InvalidConfiguration("OTLP metricsEndpoint 必须是以 /v1/metrics 结尾的 HTTP(S) URL"))
      else if serviceName.trim.isEmpty then Left(AgentError.InvalidConfiguration("OTLP serviceName 不能为空"))
      else if sampleRatio < 0.0 || sampleRatio > 1.0 then
        Left(AgentError.InvalidConfiguration("OTLP sampleRatio 必须位于 0 到 1"))
      else if timeout <= Duration.Zero || scheduleDelay <= Duration.Zero || metricExportInterval <= Duration.Zero
      then Left(AgentError.InvalidConfiguration("OTLP timeout、scheduleDelay 与 metricExportInterval 必须大于 0"))
      else Right(this)
    }

/** Langfuse 的 OTLP 原生接入配置。
  *
  * Langfuse 官方 OTLP endpoint 使用 HTTP Basic：public key 为用户名、secret key 为密码。密钥只在 `toOtlp` 时进入 Authorization
  * header，两个配置类型都覆盖了 `toString`，不会被普通日志输出。
  *
  * @param host
  *   Langfuse 根地址；云版通常为 `https://cloud.langfuse.com`，自托管填写自己的域名
  * @param publicKey
  *   Langfuse Project public key
  * @param secretKey
  *   Langfuse Project secret key
  * @param serviceName
  *   在 Langfuse/OTel 中显示的服务名
  * @param serviceVersion
  *   业务发布版本或 Git SHA
  * @param deploymentEnvironment
  *   环境名
  * @param sampleRatio
  *   trace 采样率
  */
final case class LangfuseOtlpConfig(
    host: String,
    publicKey: String,
    secretKey: String,
    serviceName: String = "zyblw-agent",
    serviceVersion: String = "dev",
    deploymentEnvironment: String = "development",
    sampleRatio: Double = 1.0
):
  /** 不把 public/secret key 输出到日志。 */
  override def toString: String =
    s"LangfuseOtlpConfig(host=$host, credentials=<redacted>, service=$serviceName, version=$serviceVersion, environment=$deploymentEnvironment, sampleRatio=$sampleRatio)"

  /** 转为通用 OTLP 配置。
    *
    * @return
    *   带官方 Basic Authorization 与 ingestion version header 的配置
    */
  def toOtlp: IO[AgentError, OtlpTelemetryConfig] =
    if publicKey.trim.isEmpty || secretKey.trim.isEmpty then
      ZIO.fail(AgentError.InvalidConfiguration("Langfuse publicKey 与 secretKey 不能为空"))
    else
      val base        = host.stripSuffix("/")
      val credentials = Base64.getEncoder.encodeToString(
        s"${publicKey.trim}:${secretKey.trim}".getBytes(StandardCharsets.UTF_8)
      )
      OtlpTelemetryConfig(
        tracesEndpoint = s"$base/api/public/otel/v1/traces",
        traceHeaders = Map(
          "Authorization"                -> s"Basic $credentials",
          "x-langfuse-ingestion-version" -> "4"
        ),
        serviceName = serviceName,
        serviceVersion = serviceVersion,
        deploymentEnvironment = deploymentEnvironment,
        sampleRatio = sampleRatio
      ).validated
