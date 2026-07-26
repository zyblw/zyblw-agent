package com.zyblw.agent.app

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.runtime.*
import com.zyblw.agent.scheduler.*
import com.zyblw.agent.tools.*
import zio.*

/** 业务宿主装配 Agent Runtime 时使用的集中配置。
  *
  * 配置只包含框架级硬策略，不保存 API Key、数据库密码、用户身份或业务正文。Provider、持久化、工具和认证仍通过 独立 ZLayer 注入，避免 Builder 演变成不可测试的 Service
  * Locator。
  *
  * @param toolPolicy
  *   工具白名单、调用次数、并行度、超时、结果大小、重试和审批策略；Runtime 与异步创建控制面共享同一实例
  * @param worker
  *   跨 Worker claim、lease、heartbeat、轮询和自动尝试参数
  */
final case class AgentApplicationConfig(
    toolPolicy: ToolPolicyConfig = ToolPolicyConfig.secureDefault,
    worker: WorkerHostConfig = WorkerHostConfig()
)

/** 业务代码使用耐久 Agent 的小型门面。
  *
  * `AgentApplication` 不另建状态机：创建/审批/取消等意图委托给 `AgentCommandService`，状态查询委托给唯一 `AgentRuntime/RunStore`，执行则委托给
  * `WorkerHost`。它的价值是减少业务层样板代码，同时保留底层服务作为独立 ZLayer 输出，便于 ZIO HTTP、运维任务和高级接入直接使用。
  */
trait AgentApplication:
  /** 耐久提交新 Run，并立即返回 Start 命令回执，不在调用 Fiber 中等待模型执行。
    *
    * @param agent
    *   从可信业务注册表取得并会冻结到 AgentState 的定义
    * @param request
    *   首条用户消息、线程、可信租户/用户 scope 和运行预算
    * @param idempotencyKey
    *   客户端稳定幂等键；网络重试必须复用，同键不同请求会冲突
    */
  def submit(
      agent: AgentDefinition,
      request: RunRequest,
      idempotencyKey: String
  ): IO[AgentError, RunCommandRecord]

  /** 提交当前待审批动作的决定。
    * @param runId
    *   等待审批的 Run
    * @param decision
    *   批准或带安全原因的拒绝
    * @param actor
    *   由认证层构造的操作者身份；不能取自请求 JSON
    */
  def decide(runId: RunId, decision: ApprovalDecision, actor: RunContext): IO[AgentError, RunCommandRecord]

  /** 提交高优先级取消命令；命令队列会撤销当前 dispatcher lease，Heartbeat 与状态提交 fencing 共同阻止旧 Worker 继续写入。
    */
  def cancel(runId: RunId, reason: Option[String], actor: RunContext): IO[AgentError, RunCommandRecord]

  /** 请求从最近耐久状态和工具执行账本恢复，而不是从头重放整个会话。 */
  def recover(runId: RunId, actor: RunContext): IO[AgentError, RunCommandRecord]

  /** 对永久失败或人工判断可重试的 Run 提交显式 Retry。
    * @param requestId
    *   外部操作的稳定幂等 ID
    * @param reason
    *   低敏运维原因；持久化前会截断
    */
  def retry(
      runId: RunId,
      requestId: String,
      reason: String,
      actor: RunContext
  ): IO[AgentError, RunCommandRecord]

  /** 查询唯一耐久 AgentState；不会从本地 Worker Fiber 推断状态。 */
  def inspect(runId: RunId): IO[AgentError, AgentState]

  /** 查询一条控制命令的状态并执行租户/用户归属校验。 */
  def inspectCommand(commandId: CommandId, actor: RunContext): IO[AgentError, RunCommandRecord]

  /** 处理一个 claim 周期。适合 readiness probe、受控批处理和确定性集成测试；常驻生产进程应使用 `runWorker`。
    */
  def claimOnce: IO[AgentError, Boolean]

  /** 持续 claim/heartbeat/execute，直到宿主应用被中断。 */
  def runWorker: IO[AgentError, Nothing]

  /** 在当前 `Scope` 中启动 Worker Fiber。
    *
    * Scope 关闭会中断 claim、heartbeat、模型流和工具子 Fiber，不使用可能泄漏到测试或热重载之后的 daemon Fiber。 返回 Fiber 便于宿主读取失败原因或在健康管理器中
    * join。
    */
  def startWorkerScoped: ZIO[Scope, Nothing, Fiber.Runtime[AgentError, Nothing]]

/** `AgentApplication` 的零额外状态实现；所有事实仍归底层 durable service 所有。 */
final private class AgentApplicationLive(
    runtime: AgentRuntime,
    commands: AgentCommandService,
    worker: WorkerHost
) extends AgentApplication:
  def submit(
      agent: AgentDefinition,
      request: RunRequest,
      idempotencyKey: String
  ): IO[AgentError, RunCommandRecord] =
    commands.submitStart(agent, request, idempotencyKey)

  def decide(runId: RunId, decision: ApprovalDecision, actor: RunContext): IO[AgentError, RunCommandRecord] =
    commands.submitApproval(runId, decision, actor)

  def cancel(runId: RunId, reason: Option[String], actor: RunContext): IO[AgentError, RunCommandRecord] =
    commands.submitCancel(runId, reason, actor)

  def recover(runId: RunId, actor: RunContext): IO[AgentError, RunCommandRecord] =
    commands.submitRecover(runId, actor)

  def retry(
      runId: RunId,
      requestId: String,
      reason: String,
      actor: RunContext
  ): IO[AgentError, RunCommandRecord] =
    commands.submitRetry(runId, requestId, reason, actor)

  def inspect(runId: RunId): IO[AgentError, AgentState] = runtime.inspect(runId)

  def inspectCommand(commandId: CommandId, actor: RunContext): IO[AgentError, RunCommandRecord] =
    commands.inspect(commandId, actor)

  def claimOnce: IO[AgentError, Boolean] = worker.claimOnce

  def runWorker: IO[AgentError, Nothing] = worker.run

  def startWorkerScoped: ZIO[Scope, Nothing, Fiber.Runtime[AgentError, Nothing]] = worker.run.forkScoped

object AgentApplication:
  /** `durable` 与 `inMemory` 共同输出的业务服务集合。
    *
    * `LeaseAwareAgentRuntime` 只在装配图内部供 WorkerHost 使用，刻意不放入公开集合，避免业务代码伪造租约旁路命令队列。
    */
  type Services = AgentApplication & AgentRuntime & AgentCommandService & WorkerHost

  /** 生产装配必须显式提供的依赖；任何一项缺失都会在编译期表现为 ZIO 环境未满足。 */
  type DurableDependencies =
    ChatModel & RegisteredToolRegistry & RunStore & RunCommandStore & RunSubmissionStore &
      ContextSourceResolver & GuardrailEngine & RunObserver

  /** 启用 `CompressionMode.ModelAssisted` 的生产装配依赖。
    *
    * `ContextCompressor` 被单独列入环境，是为了让业务明确决定压缩数据发送给哪个 Provider，而不是由 app 模块偷偷复用主 模型或读取全局配置。若 AgentDefinition
    * 只使用确定性压缩，该依赖不会产生模型调用。
    */
  type DurableContextCompressionDependencies = DurableDependencies & ContextCompressor

  /** 允许业务替换 Context、Guardrail 和 Observer 的进程内装配依赖。 */
  type InMemoryDependencies =
    ChatModel & RegisteredToolRegistry & ContextSourceResolver & GuardrailEngine & RunObserver

  /** 带真实模型辅助压缩器的进程内集成测试依赖。 */
  type InMemoryContextCompressionDependencies = InMemoryDependencies & ContextCompressor

  /** 从环境取得门面并耐久提交新 Run。 */
  def submit(
      agent: AgentDefinition,
      request: RunRequest,
      idempotencyKey: String
  ): ZIO[AgentApplication, AgentError, RunCommandRecord] =
    ZIO.serviceWithZIO[AgentApplication](_.submit(agent, request, idempotencyKey))

  /** 从环境查询权威 AgentState。 */
  def inspect(runId: RunId): ZIO[AgentApplication, AgentError, AgentState] =
    ZIO.serviceWithZIO[AgentApplication](_.inspect(runId))

  /** 从环境执行一个 Worker claim 周期。 */
  def claimOnce: ZIO[AgentApplication, AgentError, Boolean] =
    ZIO.serviceWithZIO[AgentApplication](_.claimOnce)

  /** 从环境在当前 Scope 中启动结构化 Worker。 */
  def startWorkerScoped: ZIO[AgentApplication & Scope, Nothing, Fiber.Runtime[AgentError, Nothing]] =
    ZIO.serviceWithZIO[AgentApplication](_.startWorkerScoped)

  /** 从 Runtime、控制面和 WorkerHost 构造无额外状态的业务门面。 */
  private val live: URLayer[AgentRuntime & AgentCommandService & WorkerHost, AgentApplication] =
    ZLayer.fromFunction(AgentApplicationLive.apply)

  /** 生产耐久装配。
    *
    * 业务必须显式提供 PostgreSQL 等 `RunStore/RunCommandStore/RunSubmissionStore` 组合，且三个服务必须来自同一个
    * Adapter/连接池事务边界。该层不会在生产依赖缺失时偷偷回退到内存，也不会提供空 Guardrail、空 Context 或 noop Observer，从而防止配置存在但未接入主循环的静默失效。
    *
    * @param owner
    *   当前部署实例唯一 Worker ID，建议包含 pod 名和启动 UUID
    * @param config
    *   工具与 Worker 的硬治理配置
    */
  def durable(owner: WorkerId, config: AgentApplicationConfig): URLayer[DurableDependencies, Services] =
    ZLayer.makeSome[DurableDependencies, Services](
      ZLayer.succeed(config.toolPolicy),
      TokenCounter.approximate,
      ContextCompressor.deterministic,
      DefaultContextManager.layer,
      AgentRuntimeLive.layerWithContextSources,
      AgentCommandServiceLive.layer,
      WorkerHost.layer(owner, config.worker),
      live
    )

  /** 可启用真实模型辅助 Context 压缩的生产耐久装配。
    *
    * 与 [[durable]] 唯一的装配差异是：压缩器必须由业务 ZLayer 显式提供。推荐使用 `LlmContextCompressor.configured`，并让其复用已经通过
    * ProviderContract 的 `ChatModel` 路由。Agent 的 `ContextPolicy.historyCompression` 仍决定某个 Run 是否实际调用压缩模型；框架不会仅因
    * Layer 存在就自动产生费用。
    *
    * @param owner
    *   当前部署实例唯一 Worker ID
    * @param config
    *   工具和 Worker 硬治理配置
    * @return
    *   要求业务额外提供 ContextCompressor 的完整生产服务图
    */
  def durableWithContextCompressor(
      owner: WorkerId,
      config: AgentApplicationConfig
  ): URLayer[DurableContextCompressionDependencies, Services] =
    ZLayer.makeSome[DurableContextCompressionDependencies, Services](
      ZLayer.succeed(config.toolPolicy),
      TokenCounter.approximate,
      DefaultContextManager.layer,
      AgentRuntimeLive.layerWithContextSources,
      AgentCommandServiceLive.layer,
      WorkerHost.layer(owner, config.worker),
      live
    )

  /** 可替换 Context/Guardrail/Observer 的进程内装配。
    *
    * 它使用一套共享 `AgentPersistence.inMemory`，可验证完整异步命令路径，但进程退出即丢失状态，绝不能用于多副本或生产。
    */
  def inMemory(owner: WorkerId, config: AgentApplicationConfig): URLayer[InMemoryDependencies, Services] =
    ZLayer.makeSome[InMemoryDependencies, Services](
      AgentPersistence.inMemory,
      ZLayer.succeed(config.toolPolicy),
      TokenCounter.approximate,
      ContextCompressor.deterministic,
      DefaultContextManager.layer,
      AgentRuntimeLive.layerWithContextSources,
      AgentCommandServiceLive.layer,
      WorkerHost.layer(owner, config.worker),
      live
    )

  /** 使用内存耐久组件、但显式接入真实 ContextCompressor 的业务集成测试入口。
    *
    * 这个入口适合在接入 PostgreSQL 前验证模型摘要的调用预算、checkpoint、Telemetry 和崩溃恢复语义。由于 Store 仍在 进程内，不能用于正式部署。
    */
  def inMemoryWithContextCompressor(
      owner: WorkerId,
      config: AgentApplicationConfig
  ): URLayer[InMemoryContextCompressionDependencies, Services] =
    ZLayer.makeSome[InMemoryContextCompressionDependencies, Services](
      AgentPersistence.inMemory,
      ZLayer.succeed(config.toolPolicy),
      TokenCounter.approximate,
      DefaultContextManager.layer,
      AgentRuntimeLive.layerWithContextSources,
      AgentCommandServiceLive.layer,
      WorkerHost.layer(owner, config.worker),
      live
    )

  /** 最小本地 Starter：调用方只提供 ChatModel 与显式工具注册表。
    *
    * 该入口明确使用空 ContextSource、空 Guardrail 和 noop Observer，适合教程与单元测试。知识 Agent、含敏感数据的业务或 任何生产部署都应改用
    * `inMemory`（自定义治理验证）或 `durable`（真实持久化）。
    */
  def inMemoryDefaults(
      owner: WorkerId,
      config: AgentApplicationConfig = AgentApplicationConfig()
  ): URLayer[ChatModel & RegisteredToolRegistry, Services] =
    ZLayer.makeSome[ChatModel & RegisteredToolRegistry, Services](
      AgentPersistence.inMemory,
      ContextSourceResolver.empty,
      GuardrailEngine.empty,
      RunObserver.noop,
      ZLayer.succeed(config.toolPolicy),
      TokenCounter.approximate,
      ContextCompressor.deterministic,
      DefaultContextManager.layer,
      AgentRuntimeLive.layerWithContextSources,
      AgentCommandServiceLive.layer,
      WorkerHost.layer(owner, config.worker),
      live
    )

  /** 最小治理默认值加显式模型辅助压缩器的本地入口。
    *
    * 它仍会注入空 ContextSource、空 Guardrail 和 noop Observer，因此只适合教程与压缩器契约测试。真实知识问答应使用
    * [[inMemoryWithContextCompressor]] 或 [[durableWithContextCompressor]]，以免把“能压缩历史”误当成已经接入了
    * Memory/RAG、安全策略和可观测性。
    */
  def inMemoryDefaultsWithContextCompressor(
      owner: WorkerId,
      config: AgentApplicationConfig = AgentApplicationConfig()
  ): URLayer[ChatModel & RegisteredToolRegistry & ContextCompressor, Services] =
    ZLayer.makeSome[ChatModel & RegisteredToolRegistry & ContextCompressor, Services](
      AgentPersistence.inMemory,
      ContextSourceResolver.empty,
      GuardrailEngine.empty,
      RunObserver.noop,
      ZLayer.succeed(config.toolPolicy),
      TokenCounter.approximate,
      DefaultContextManager.layer,
      AgentRuntimeLive.layerWithContextSources,
      AgentCommandServiceLive.layer,
      WorkerHost.layer(owner, config.worker),
      live
    )
