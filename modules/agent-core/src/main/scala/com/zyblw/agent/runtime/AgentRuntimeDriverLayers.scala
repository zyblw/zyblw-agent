package com.zyblw.agent.runtime

import com.zyblw.agent.artifacts.ArtifactStore
import com.zyblw.agent.composition.{LiveComposition, RuntimeProfile}
import com.zyblw.agent.context.{ContextManager, ContextSourceResolver}
import com.zyblw.agent.core.*
import com.zyblw.agent.extension.RuntimeExtensions
import com.zyblw.agent.guardrails.GuardrailEngine
import com.zyblw.agent.memory.RunStore
import com.zyblw.agent.model.*
import com.zyblw.agent.tools.{RegisteredToolRegistry, ToolExecutor, ToolPolicySource}
import zio.*

/** [[AgentRuntimeDriver]] 的装配入口。
  *
  * 依赖图用 `ZLayer` 表达，而不是自制服务定位器：缺依赖在编译期暴露，不会在运行时静默 fallback 到内存实现。协作者在这里 一次性构造，Run 级配置保持为不可变值，不在每个 Run 或每个
  * Step 重建依赖图。
  */
object AgentRuntimeDriverLayers:
  /** 纯工具 Agent 的默认装配；不读取 Memory/RAG 来源。 */
  val layer: URLayer[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & GuardrailEngine & ToolPolicySource &
      ModelPolicySource & RunObserver & ArtifactStore,
    AgentRuntime & LeaseAwareAgentRuntime
  ] = RuntimeExtensions.emptyLayer >>> withResolver(ContextSourceResolver.emptyValue, RuntimeProfile.default)

  /** 接入长期记忆或知识库的装配入口：每个模型回合都会从显式 resolver 读取来源。
    *
    * 默认 `layer` 仍适合纯工具 Agent；配置了 Retriever 却使用默认层会导致"配置了但 Runtime 从未读取"的静默失效。
    */
  val layerWithContextSources: URLayer[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & ContextSourceResolver & GuardrailEngine &
      ToolPolicySource & ModelPolicySource & RunObserver & RuntimeExtensions & ArtifactStore,
    AgentRuntime & LeaseAwareAgentRuntime
  ] = layerWithProfile(RuntimeProfile.default)

  /** 以显式 RuntimeProfile 与 ModelRole 目录装配。 */
  def layerWithProfile(
      profile: RuntimeProfile,
      roleCatalog: ModelRoleCatalog = ModelRoleCatalog.empty
  ): URLayer[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & ContextSourceResolver & GuardrailEngine &
      ToolPolicySource & ModelPolicySource & RunObserver & RuntimeExtensions & ArtifactStore,
    AgentRuntime & LeaseAwareAgentRuntime
  ] = ZLayer.scoped {
    for
      resolver <- ZIO.service[ContextSourceResolver]
      runtime  <- build(resolver, profile, roleCatalog)
    yield runtime
  }

  /** 测试与评测使用 Replayable，以便从账本重建 ChatRequest。 */
  def layerWithCapture(capturePolicy: CapturePolicy): URLayer[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & GuardrailEngine & ToolPolicySource &
      ModelPolicySource & RunObserver & ArtifactStore,
    AgentRuntime & LeaseAwareAgentRuntime
  ] = RuntimeExtensions.emptyLayer >>> withResolver(
    ContextSourceResolver.emptyValue,
    RuntimeProfile(capturePolicy = capturePolicy)
  )

  private def withResolver(
      resolver: ContextSourceResolver,
      profile: RuntimeProfile,
      roleCatalog: ModelRoleCatalog = ModelRoleCatalog.empty
  ): URLayer[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & GuardrailEngine & ToolPolicySource &
      ModelPolicySource & RunObserver & ArtifactStore & RuntimeExtensions,
    AgentRuntime & LeaseAwareAgentRuntime
  ] = ZLayer.scoped(build(resolver, profile, roleCatalog))

  /** 构造全部协作者。
    *
    * 顺序反映依赖方向：事件发布器与提交边界最先建立，其余协作者都通过它们写入耐久事实，因此不存在第二条持久化路径。
    */
  private def build(
      resolver: ContextSourceResolver,
      profile: RuntimeProfile,
      roleCatalog: ModelRoleCatalog
  ): ZIO[
    ChatModel & RegisteredToolRegistry & RunStore & ContextManager & GuardrailEngine & ToolPolicySource &
      ModelPolicySource & RunObserver & ArtifactStore & RuntimeExtensions & Scope,
    Nothing,
    AgentRuntimeDriver
  ] =
    for
      model          <- ZIO.service[ChatModel]
      registry       <- ZIO.service[RegisteredToolRegistry]
      store          <- ZIO.service[RunStore]
      contextManager <- ZIO.service[ContextManager]
      guardrails     <- ZIO.service[GuardrailEngine]
      toolPolicies   <- ZIO.service[ToolPolicySource]
      modelPolicies  <- ZIO.service[ModelPolicySource]
      observer       <- ZIO.service[RunObserver]
      extensions     <- ZIO.service[RuntimeExtensions]
      artifacts      <- ZIO.service[ArtifactStore]
      executor       <- ToolExecutor.make(toolPolicies.current(), artifacts)
      activeRuns     <- Ref.make(Map.empty[RunId, Fiber.Runtime[AgentError, RunOutcome]])
      publisher      <- RunEventPublisher.make(observer, activeRuns)
      committer      <- RunCommitter.make(store, publisher.emit)
      toolLedger = ToolLedger.make(store, extensions)
      // 现场组合是**读**装配事实，因此按名求值：部署热改扩展或权限时下一次比较就能看到，不会缓存成陈旧快照。
      live = LiveComposition.make(
        profile,
        modelPolicies,
        resolver.sourceIds,
        extensions.sourceIds,
        extensions.environment.id.value,
        extensions.environment.permissions.fingerprint
      )
      guard = CompositionGuard(registry, live, roleCatalog, publisher)
    yield new AgentRuntimeDriver(
      model,
      registry,
      store,
      guardrails,
      toolPolicies,
      modelPolicies,
      resolver,
      committer,
      toolLedger,
      guard,
      ContextAssembler(contextManager, committer),
      ModelRouterGateway(model, profile),
      ModelCallCoordinator(store, committer, publisher, profile.capturePolicy),
      ToolAuthorizationGate(registry, toolPolicies, extensions),
      RunTerminator(store, committer, guardrails, publisher, activeRuns),
      publisher,
      executor
    )
