package com.zyblw.agent.core

import zio.*
import zio.json.*

/** 一次 Agent Run 的所有硬预算；runtime 必须在每个外部动作之前检查。 */
final case class RunLimits(
    maxSteps: Int = 24,
    maxModelCalls: Int = 16,
    maxToolCalls: Int = 32,
    maxRepeatedActions: Int = 3,
    maxInputTokens: Long = 200_000L,
    maxOutputTokens: Long = 32_000L,
    maxTotalTokens: Long = 220_000L,
    maxEstimatedCost: Option[BigDecimal] = None,
    maxDuration: Duration = 10.minutes
):
  require(maxSteps > 0 && maxModelCalls > 0 && maxToolCalls > 0, "运行次数预算必须为正数")
  require(maxRepeatedActions > 0, "重复动作上限必须为正数")
  require(maxInputTokens > 0 && maxOutputTokens > 0 && maxTotalTokens > 0, "Token 预算必须为正数")

object RunLimits:
  /** BigDecimal 以字符串编码，避免经过 JSON 浮点数造成费用精度损失。 解码失败会保留解析错误信息，便于启动阶段定位配置问题。
    */
  given JsonCodec[BigDecimal] = JsonCodec.string.transformOrFail(
    value => scala.util.Try(BigDecimal(value)).toEither.left.map(_.getMessage),
    _.toString
  )
  given JsonCodec[RunLimits] = DeriveJsonCodec.gen[RunLimits]

enum ToolFailureMode derives JsonCodec:
  case ContinueWithErrorResult, FailStep, FailRun

/** 控制运行循环行为，而不是描述某个具体 Provider。 */
final case class RunPolicy(
    limits: RunLimits = RunLimits(),
    toolFailureMode: ToolFailureMode = ToolFailureMode.ContinueWithErrorResult,
    parallelToolCalls: Boolean = true,
    cancelSiblingsOnToolFailure: Boolean = false,
    eventBufferCapacity: Int = 256
):
  require(eventBufferCapacity > 0, "事件缓冲区必须为正数")

object RunPolicy:
  given JsonCodec[RunPolicy] = DeriveJsonCodec.gen[RunPolicy]

/** 上下文窗口各部分的 token 预算，所有字段之和不能超过 total。 */
final case class ContextBudget(
    total: Long,
    system: Long,
    tools: Long,
    recentMessages: Long,
    memory: Long,
    retrieval: Long,
    outputReserve: Long,
    safetyMargin: Long
):
  private val allocated = system + tools + recentMessages + memory + retrieval + outputReserve + safetyMargin
  require(
    total > 0 && List(system, tools, recentMessages, memory, retrieval, outputReserve, safetyMargin).forall(
      _ >= 0
    )
  )
  require(allocated <= total, s"上下文分区预算 $allocated 超过总预算 $total")

object ContextBudget:
  val default: ContextBudget     = ContextBudget(64_000, 4_000, 8_000, 28_000, 6_000, 10_000, 6_000, 2_000)
  given JsonCodec[ContextBudget] = DeriveJsonCodec.gen[ContextBudget]

enum CompressionMode derives JsonCodec:
  case Deterministic, ModelAssisted, Disabled

/** 控制历史、工具输出和摘要的压缩方式。 */
final case class ContextPolicy(
    budget: ContextBudget = ContextBudget.default,
    preserveImportantMessages: Boolean = true,
    maxToolResultCharacters: Int = 16_000,
    historyCompression: CompressionMode = CompressionMode.Deterministic,
    toolOutputCompression: CompressionMode = CompressionMode.Deterministic
):
  require(maxToolResultCharacters > 0, "工具结果上限必须为正数")

object ContextPolicy:
  given JsonCodec[ContextPolicy] = DeriveJsonCodec.gen[ContextPolicy]

/** 指数退避参数；Provider 或工具可以根据错误分类决定是否应用。 */
final case class RetryPolicy(
    maxAttempts: Int = 3,
    initialDelay: Duration = 200.millis,
    maxDelay: Duration = 5.seconds,
    jitter: Double = 0.2,
    maxElapsed: Duration = 20.seconds
):
  require(maxAttempts >= 1 && jitter >= 0.0 && jitter <= 1.0)

object RetryPolicy:
  given JsonCodec[RetryPolicy] = DeriveJsonCodec.gen[RetryPolicy]

final case class CircuitBreakerPolicy(maxFailures: Int = 5, resetAfter: Duration = 30.seconds):
  require(maxFailures > 0)

final case class RateLimitPolicy(maxConcurrent: Int = 16, permitsPerSecond: Int = 50):
  require(maxConcurrent > 0 && permitsPerSecond > 0)

/** Provider、工具和 Retriever 共用的可靠性治理参数。 */
final case class ReliabilityPolicy(
    timeout: Duration = 90.seconds,
    retry: RetryPolicy = RetryPolicy(),
    concurrencyLimit: Int = 16,
    circuitBreaker: CircuitBreakerPolicy = CircuitBreakerPolicy(),
    rateLimit: RateLimitPolicy = RateLimitPolicy()
):
  require(concurrencyLimit > 0)
