package com.zyblw.agent.tools

import com.zyblw.agent.artifacts.{ArtifactInput, ArtifactStore}
import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.EncoderOps

enum ApprovalPolicy:
  case Never, RiskBased, Always

/** 同一次在线工具调用的有界热重试。不决定进程死后是否重放；崩溃恢复看 [[ToolRecoveryPolicy]]。 */
enum ToolRetryPolicy:
  case Never
  case IdempotentOnly(policy: RetryPolicy)

/** Agent 级工具执行硬限制。空白名单表示默认拒绝，而不是允许全部工具。 */
final case class ToolPolicyConfig(
    allowedTools: Set[ToolName] = Set.empty,
    deniedTools: Set[ToolName] = Set.empty,
    maxCallsPerRun: Int = 32,
    maxCallsPerStep: Int = 8,
    maxParallelism: Int = 4,
    defaultTimeout: Duration = 30.seconds,
    maxResultBytes: Long = 256 * 1024,
    externalizeAboveBytes: Long = 32 * 1024,
    retryPolicy: ToolRetryPolicy = ToolRetryPolicy.Never,
    approvalPolicy: ApprovalPolicy = ApprovalPolicy.RiskBased
):
  require(maxCallsPerRun > 0 && maxCallsPerStep > 0 && maxParallelism > 0)
  require(maxResultBytes > 0)
  require(
    externalizeAboveBytes > 0L && externalizeAboveBytes <= maxResultBytes,
    "externalizeAboveBytes 必须位于 (0, maxResultBytes]"
  )

object ToolPolicyConfig:
  val secureDefault: ToolPolicyConfig = ToolPolicyConfig()

/** 同步返回当前生效工具治理配置的解析器。
  *
  * 它刻意返回裸值而不是 `UIO[ToolPolicyConfig]`。Runtime 在规划批次、判断审批和检查预算时会在纯表达式里读取策略， 把这些读取全部改成效果会迫使 `approvalReason`
  * 之类的纯函数变成 `ZIO`，进而把工具规划逻辑重写成 for 推导——这既放大了改动 面，也没有换来任何额外保证：读取一个不可变 `ToolPolicyConfig` 引用本来就是无副作用的。
  *
  * 实现必须保证 [[current]] 是无阻塞、无异常的引用读取。管理面覆盖通过替换被引用的不可变值生效，而不是原地修改配置对象。
  *
  * 一次 Run 进行到一半时策略被替换是允许的，且在两个方向上都是安全的：收紧策略会让后续工具调用被拒绝或要求审批， 放宽策略只影响尚未规划的批次。已经冻结进 `AgentState` 的预算（例如
  * `maxCallsPerRun`）不受影响。
  */
trait ToolPolicySource:
  /** 读取当前生效配置。 */
  def current(): ToolPolicyConfig

object ToolPolicySource:
  /** 永远返回同一份部署基线；未接入管理面覆盖时使用。 */
  def static(config: ToolPolicyConfig): ToolPolicySource = new ToolPolicySource:
    def current(): ToolPolicyConfig = config

  /** 从已装配的 `ToolPolicyConfig` 构造静态解析器。
    *
    * 该层让既有装配图在不引入管理面依赖的前提下满足 Runtime 的新环境要求。接入运行时覆盖的部署应改为提供 由 `RuntimeSettingsService` 支撑的解析器。
    */
  val staticLayer: URLayer[ToolPolicyConfig, ToolPolicySource] =
    ZLayer.fromFunction((config: ToolPolicyConfig) => static(config))

/** 集中处理超时、并发度和结果大小，不让每个工具重复实现。 */
final class ToolExecutor private (
    semaphore: Semaphore,
    policy: ToolPolicyConfig,
    artifacts: ArtifactStore
):
  /** 在统一治理边界内执行一个已注册工具。返回值在硬上限以内仍是全文 [[ToolResult.Inline]]，外置由 [[externalize]] 在 Guardrail 之后完成。 */
  def execute(
      tool: RegisteredTool,
      call: ToolCall,
      context: ToolExecutionContext
  ): IO[AgentError, ToolResult] =
    val name   = ToolName(call.name)
    val denied = policy.deniedTools.contains(name) || !policy.allowedTools.contains(name)
    if denied then ZIO.fail(AgentError.PermissionDenied(call.name, "工具未进入显式白名单"))
    else
      semaphore.withPermit {
        val invocation = tool.invoke(call.arguments, context)
        val resilient  = policy.retryPolicy match
          case ToolRetryPolicy.Never                                                     => invocation
          case ToolRetryPolicy.IdempotentOnly(settings) if tool.metadata.onlineRetryable =>
            val backoff = Schedule
              .exponential(settings.initialDelay)
              .modifyDelay((_, delay) => delay.min(settings.maxDelay))
              .jittered(1.0 - settings.jitter, 1.0 + settings.jitter)
            val bounded = backoff &&
              Schedule.recurs((settings.maxAttempts - 1).toLong) &&
              Schedule.elapsed.whileOutput(_ <= settings.maxElapsed)
            invocation.retry(bounded.whileInput[AgentError](_.retryable))
          case ToolRetryPolicy.IdempotentOnly(_) => invocation
        resilient
          .timeoutFail(AgentError.ToolExecutionFailed(call.name, "工具执行超时", retryable = false))(
            policy.defaultTimeout
          )
          .flatMap(enforceHardLimit(call.name, _))
      }

  /** 同一并行许可池上收窄白名单，避免每个工具调用重建执行器。 */
  def narrowed(allowed: Set[ToolName]): ToolExecutor =
    ToolExecutor(semaphore, policy.copy(allowedTools = allowed), artifacts)

  /** 阈值以上把 Inline 全文外置为 Run 域 Artifact；已外置或未超阈值的结果原样返回。 */
  def externalize(
      call: ToolCall,
      context: ToolExecutionContext,
      result: ToolResult
  ): IO[AgentError, ToolResult] = externalize(call, context, result, Int.MaxValue)

  /** 同时应用部署字节阈值与当前 Agent 的模型可见字符阈值，保证 Context 不需要二次改写结果。 */
  def externalize(
      call: ToolCall,
      context: ToolExecutionContext,
      result: ToolResult,
      maxInlineCharacters: Int
  ): IO[AgentError, ToolResult] =
    result match
      case _: ToolResult.Externalized => ZIO.succeed(result)
      case inline: ToolResult.Inline  =>
        val bytes      = inline.utf8ByteSize
        val characters = inline.value.toJson.length
        if bytes <= policy.externalizeAboveBytes && characters <= maxInlineCharacters then ZIO.succeed(inline)
        else
          val name       = artifactName(call)
          val bytesChunk = Chunk.fromArray(
            inline.value.toJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          )
          artifacts
            .save(
              ArtifactScope.of(context),
              name,
              ArtifactInput(bytesChunk, "application/json", Map("tool" -> call.name, "callId" -> call.id))
            )
            .mapBoth(
              error =>
                AgentError.ToolExecutionFailed(
                  call.name,
                  s"工具结果外置失败: ${if error.safeToExpose then error.message else "artifact-store"}",
                  retryable = false
                ),
              descriptor =>
                ToolResult.Externalized(
                  descriptor.reference,
                  ToolResult.previewOf(inline.value),
                  inline.isError,
                  inline.metadata
                )
            )

  private def enforceHardLimit(name: String, result: ToolResult): IO[AgentError, ToolResult] =
    val bytes = result.utf8ByteSize
    if bytes <= policy.maxResultBytes then ZIO.succeed(result)
    else
      ZIO.fail(
        AgentError.ToolExecutionFailed(
          name,
          s"工具结果 $bytes bytes 超过限制 ${policy.maxResultBytes}",
          retryable = false
        )
      )

  private def artifactName(call: ToolCall): ArtifactName =
    ArtifactName
      .fromString(s"tool-results/${call.id}.json")
      .getOrElse(ArtifactName("tool-results/result.json"))

object ToolExecutor:
  /** 根据策略与 ArtifactStore 创建带固定并行许可数的执行器。 */
  def make(policy: ToolPolicyConfig, artifacts: ArtifactStore): UIO[ToolExecutor] =
    Semaphore.make(policy.maxParallelism.toLong).map(ToolExecutor(_, policy, artifacts))

  /** 将执行器构造成依赖 ArtifactStore 的 ZLayer，由 Runtime Scope 持有一份，而不是每个工具调用重建。 */
  val layer: URLayer[ToolPolicyConfig & ArtifactStore, ToolExecutor] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[ToolPolicyConfig](policy => ZIO.serviceWithZIO[ArtifactStore](make(policy, _)))
    )
