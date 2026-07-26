package com.zyblw.agent.testkit

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import java.time.Instant
import zio.*
import zio.json.*

/** 真实 Provider 小流量门禁的执行参数。
  *
  * Smoke 不替代 stub ProviderContract，也不测试复杂业务知识；它只证明当前密钥、endpoint、模型和协议在真实网络上仍能 完成非流式与流式最小交互，并满足延迟、usage
  * 和输出预算。默认每种调用只执行一次，避免 CI 意外产生高额费用。
  *
  * @param model
  *   请求使用的真实模型 ID；报告会记录该 ID，但不记录 endpoint 或 API Key
  * @param repetitions
  *   非流式和流式各执行多少次
  * @param callTimeout
  *   每次完整调用的外层硬超时；应略大于 Adapter 自身 timeout
  * @param maxLatency
  *   单次调用允许的发布门禁延迟
  * @param maxTotalTokens
  *   单次调用允许的输入+输出 token 上限
  * @param maxOutputTokens
  *   发送给 Provider 的输出 token 硬限制
  * @param requireUsage
  *   声明 usageReporting 的 Provider 是否必须返回正 token 数
  * @param marker
  *   固定低敏回声标记；只记录是否命中，不把响应正文写入报告
  */
final case class LiveProviderSmokeConfig(
    model: String,
    repetitions: Int = 1,
    callTimeout: Duration = 120.seconds,
    maxLatency: Duration = 60.seconds,
    maxTotalTokens: Long = 2_000L,
    maxOutputTokens: Int = 64,
    requireUsage: Boolean = true,
    marker: String = "ZYBLW_SMOKE_OK"
):
  require(model.trim.nonEmpty && model.length <= 200, "Provider smoke model 长度必须位于 1..200")
  require(repetitions >= 1 && repetitions <= 5, "Provider smoke repetitions 必须位于 1..5")
  require(callTimeout > Duration.Zero && maxLatency > Duration.Zero, "Provider smoke 超时必须大于零")
  require(maxTotalTokens > 0L && maxOutputTokens > 0, "Provider smoke token 预算必须大于零")
  require(marker.matches("[A-Z0-9_]{4,64}"), "Provider smoke marker 只允许 4..64 位大写字母、数字和下划线")

/** 一次 complete 或 stream 调用的低敏观测结果。 */
final case class LiveProviderSmokeCall(
    attempt: Int,
    kind: String,
    succeeded: Boolean,
    latencyMillis: Long,
    markerObserved: Boolean,
    inputTokens: Long,
    outputTokens: Long,
    completedEvents: Int,
    errorCategory: Option[String],
    retryable: Option[Boolean]
) derives JsonCodec:
  require(attempt >= 0 && latencyMillis >= 0L, "Provider smoke attempt/latency 不能为负数")
  require(inputTokens >= 0L && outputTokens >= 0L && completedEvents >= 0, "Provider smoke usage/event 不能为负数")

/** 一项可直接用于 CI 判断的确定性门禁。 */
final case class LiveProviderSmokeCheck(name: String, passed: Boolean, details: String) derives JsonCodec

/** 真实 Provider Smoke 的低敏报告。
  *
  * `calls/checks` 只包含数字、布尔值、稳定错误类别和固定诊断，不包含 prompt、模型输出、HTTP body、endpoint、请求 ID 或 凭据。报告因此可以作为 CI artifact
  * 保存，但模型 ID 仍可能体现部署信息，公开发布前应按组织政策处理。
  */
final case class LiveProviderSmokeReport(
    provider: String,
    protocol: String,
    model: String,
    startedAt: Instant,
    finishedAt: Instant,
    calls: Chunk[LiveProviderSmokeCall],
    checks: Chunk[LiveProviderSmokeCheck]
) derives JsonCodec:
  /** 所有门禁都通过才允许把当前 Provider 配置提升到生产。 */
  def passed: Boolean = checks.nonEmpty && checks.forall(_.passed)

/** 厂商无关的真实 Provider 小流量验证器。
  *
  * Runner 使用同一个固定请求分别调用 `complete` 和 `stream`。两条路径是独立网络请求，因为真实部署中同步 API、SSE 网关、代理缓冲和 usage 聚合可能单独失效。Runner
  * 不做自动重试，以免掩盖首请求失败率或在凭据错误时放大费用；若 业务需要多次稳定性证据，应显式增加 repetitions，最多 5 次。
  */
object LiveProviderSmokeRunner:

  /** 执行完整 smoke 并始终返回报告。
    *
    * Provider typed error、defect 和超时都会转为失败 call，不让 CI 因第一项故障丢失其余证据。Fiber interruption 仍会 传播，不会被伪装成 Provider
    * 失败，保证取消可以停止真实计费请求。
    */
  def run(model: ChatModel, config: LiveProviderSmokeConfig): UIO[LiveProviderSmokeReport] =
    ZIO.uninterruptibleMask { restore =>
      for
        started          <- Clock.instant
        capabilitiesExit <- restore(model.capabilities(Some(config.model))).exit
        // `.exit` 会同时捕获 typed error、defect 与 Fiber interruption。前两者应进入低敏报告，
        // interruption 却代表调用方明确要求停止计费网络请求，因此必须恢复为真正的 ZIO 取消。
        capabilities <- capabilitiesExit match
          case Exit.Failure(cause) if cause.isInterrupted => ZIO.interrupt
          case other                                      => ZIO.succeed(other)
        calls <- capabilities match
          case Exit.Success(_) =>
            ZIO
              .foreach(0 until config.repetitions) { attempt =>
                restore(runAttempt(model, config, attempt)).onInterrupt(ZIO.logInfo("Provider smoke 已被取消"))
              }
              .map(values => Chunk.fromIterable(values).flatten)
          case Exit.Failure(cause) =>
            ZIO.succeed(Chunk(capabilityFailure(cause)))
        finished <- Clock.instant
        checks = buildChecks(config, capabilities, calls)
      yield LiveProviderSmokeReport(
        provider = bounded(model.provider),
        protocol = bounded(model.descriptor.protocol),
        model = config.model.take(200),
        startedAt = started,
        finishedAt = finished,
        calls = calls,
        checks = checks
      )
    }

  /** 每轮先 complete 再 stream，避免在小额度账号上制造并发突发。 */
  private def runAttempt(
      model: ChatModel,
      config: LiveProviderSmokeConfig,
      attempt: Int
  ): UIO[Chunk[LiveProviderSmokeCall]] =
    val request = smokeRequest(model.provider, config)
    for
      complete <- observeCall(attempt, "complete", config)(model.complete(request)) { response =>
        val text = response.message.text
        (text.contains(config.marker), response.usage, 0)
      }
      stream <- observeCall(attempt, "stream", config)(model.stream(request).runCollect) { events =>
        val completed = events.collect { case ModelStreamEvent.Completed(response) => response }
        val deltaText = events.collect { case ModelStreamEvent.TextDelta(value) => value }.mkString
        val finalText = completed.lastOption.map(_.message.text).getOrElse("")
        val usage     = completed.lastOption.map(_.usage).getOrElse(TokenUsage())
        (deltaText.contains(config.marker) || finalText.contains(config.marker), usage, completed.length)
      }
    yield Chunk(complete, stream)

  /** 计时并收敛调用结果。Cause 不进入报告：typed error 只保留 category/retryable，defect 使用 `Unexpected` 稳定分类。
    */
  private def observeCall[A](
      attempt: Int,
      kind: String,
      config: LiveProviderSmokeConfig
  )(
      effect: IO[AgentError, A]
  )(
      summarize: A => (Boolean, TokenUsage, Int)
  ): UIO[LiveProviderSmokeCall] =
    for
      started <- Clock.nanoTime
      exit    <- effect
        .timeoutFail(AgentError.ModelFailure("smoke", "outer timeout", retryable = true))(config.callTimeout)
        .exit
      ended <- Clock.nanoTime
      latency = ((ended - started) / 1_000_000L).max(0L)
      result <- exit match
        case Exit.Failure(cause) if cause.isInterrupted =>
          // 不把取消伪装成 Unexpected，否则 Worker 停机或 HTTP 客户端断开后，
          // Runner 还可能继续下一次真实请求并产生额外费用。
          ZIO.interrupt
        case Exit.Success(value) =>
          val (marker, usage, completed) = summarize(value)
          ZIO.succeed(
            LiveProviderSmokeCall(
              attempt,
              kind,
              succeeded = true,
              latency,
              marker,
              usage.inputTokens,
              usage.outputTokens,
              completed,
              None,
              None
            )
          )
        case Exit.Failure(cause) =>
          cause.failureOption match
            case Some(error) =>
              ZIO.succeed(
                LiveProviderSmokeCall(
                  attempt,
                  kind,
                  succeeded = false,
                  latency,
                  markerObserved = false,
                  0L,
                  0L,
                  0,
                  Some(error.category.toString),
                  Some(error.retryable)
                )
              )
            case None =>
              ZIO.succeed(
                LiveProviderSmokeCall(
                  attempt,
                  kind,
                  succeeded = false,
                  latency,
                  markerObserved = false,
                  0L,
                  0L,
                  0,
                  Some(ErrorCategory.Unexpected.toString),
                  None
                )
              )
    yield result

  /** 能力查询失败也形成一条报告 call，避免把 cause 原文输出到 CI。 */
  private def capabilityFailure(cause: Cause[AgentError]): LiveProviderSmokeCall =
    val failure = cause.failureOption
    LiveProviderSmokeCall(
      attempt = 0,
      kind = "capabilities",
      succeeded = false,
      latencyMillis = 0L,
      markerObserved = false,
      inputTokens = 0L,
      outputTokens = 0L,
      completedEvents = 0,
      errorCategory = Some(failure.fold(ErrorCategory.Unexpected.toString)(_.category.toString)),
      retryable = failure.map(_.retryable)
    )

  /** 构建发布门禁。details 只使用计数和限制，不拼接模型正文或 AgentError.message。
    */
  private def buildChecks(
      config: LiveProviderSmokeConfig,
      capabilities: Exit[AgentError, ModelCapabilities],
      calls: Chunk[LiveProviderSmokeCall]
  ): Chunk[LiveProviderSmokeCheck] =
    val expectedCalls   = config.repetitions * 2
    val capabilityValue = capabilities match
      case Exit.Success(value) => Some(value)
      case Exit.Failure(_)     => None
    val usageRequired = config.requireUsage && capabilityValue.exists(_.usageReporting)
    val successful    = calls.count(_.succeeded)
    val markerCount   = calls.count(call => call.succeeded && call.markerObserved)
    val usageOk       = calls.filter(_.succeeded).forall { call =>
      !usageRequired || call.inputTokens + call.outputTokens > 0L
    }
    val budgetOk =
      calls.filter(_.succeeded).forall(call => call.inputTokens + call.outputTokens <= config.maxTotalTokens)
    val latencyOk   = calls.filter(_.succeeded).forall(_.latencyMillis <= config.maxLatency.toMillis)
    val streamCalls = calls.filter(_.kind == "stream")
    val completedOk = streamCalls.length == config.repetitions && streamCalls.forall(_.completedEvents == 1)
    Chunk(
      LiveProviderSmokeCheck(
        "capabilities.loaded",
        capabilityValue.nonEmpty,
        s"loaded=${capabilityValue.nonEmpty}"
      ),
      LiveProviderSmokeCheck(
        "protocol.streaming-declared",
        capabilityValue.exists(_.streaming),
        s"declared=${capabilityValue.exists(_.streaming)}"
      ),
      LiveProviderSmokeCheck(
        "calls.all-succeeded",
        calls.length == expectedCalls && successful == expectedCalls,
        s"expected=$expectedCalls, actual=${calls.length}, succeeded=$successful"
      ),
      LiveProviderSmokeCheck(
        "output.marker-observed",
        markerCount == expectedCalls,
        s"expected=$expectedCalls, observed=$markerCount"
      ),
      LiveProviderSmokeCheck(
        "stream.completed-exactly-once",
        completedOk,
        s"streamCalls=${streamCalls.length}, completedCounts=${streamCalls.map(_.completedEvents).mkString(",")}"
      ),
      LiveProviderSmokeCheck(
        "usage.reported",
        usageOk,
        s"required=$usageRequired"
      ),
      LiveProviderSmokeCheck(
        "budget.tokens",
        budgetOk,
        s"limit=${config.maxTotalTokens}"
      ),
      LiveProviderSmokeCheck(
        "slo.latency",
        latencyOk,
        s"limitMillis=${config.maxLatency.toMillis}"
      )
    )

  /** 固定 prompt 不包含业务数据；温度为零且输出很短，控制成本并减少随机性。 */
  private def smokeRequest(provider: String, config: LiveProviderSmokeConfig): ChatRequest = ChatRequest(
    messages = Chunk(
      AgentMessage.system("这是基础设施连通性测试。只输出用户指定的 ASCII 标记，不调用工具，不添加解释。"),
      AgentMessage.user(s"只输出：${config.marker}")
    ),
    settings = ModelSettings(
      provider = Some(provider),
      model = Some(config.model),
      temperature = Some(0.0),
      maxOutputTokens = Some(config.maxOutputTokens),
      toolChoice = ToolChoice.None,
      metadata = Map("purpose" -> "provider-smoke-v1")
    )
  )

  /** Provider/protocol 是报告标签，只允许低基数 ASCII；异常值折叠而不回显。 */
  private def bounded(value: String): String =
    val normalized = value.trim.toLowerCase
    if normalized.matches("[a-z0-9._-]{1,80}") then normalized else "other"
