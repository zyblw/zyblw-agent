package com.zyblw.agent.testkit

import com.zyblw.agent.composition.RuntimeProfile
import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.extension.RuntimeExtensions
import com.zyblw.agent.guardrails.*
import com.zyblw.agent.memory.RunStore
import com.zyblw.agent.model.ChatModel
import com.zyblw.agent.runtime.{AgentRuntime, AgentRuntimeDriver, RunObserver}
import com.zyblw.agent.tools.*
import zio.*

/** 内存 Runtime 装配，避免每个 spec 复制十层 ZLayer。
  *
  * 始终走 `AgentRuntimeDriver.layerWithProfile`，与生产冻结 `sourceIds` 的路径一致。不创建 WorkerHost 或 HTTP。
  */
object TestAgentRuntime:
  /** 组装 `AgentRuntime` 与共享内存 `RunStore`。 */
  def inMemory(
      model: ChatModel,
      tools: Iterable[RegisteredTool] = Nil,
      profile: RuntimeProfile = RuntimeProfile.default,
      contextSources: ContextSourceResolver = ContextSourceResolver.emptyValue,
      guardrailEngine: GuardrailEngine = GuardrailEngine(
        ConfiguredGuardrails(Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty)
      ),
      toolPolicy: ToolPolicyConfig = ToolPolicyConfig.secureDefault,
      modelPolicies: ModelPolicySource = ModelPolicySource.default,
      store: ULayer[RunStore] = RunStore.inMemory,
      observer: ULayer[RunObserver] = RunObserver.noop,
      contextManager: Option[ContextManager] = None,
      extensions: RuntimeExtensions = RuntimeExtensions.empty
  ): Layer[AgentError.InvalidConfiguration, AgentRuntime & RunStore] =
    inMemoryWithToolPolicySource(
      model,
      tools,
      ToolPolicySource.static(toolPolicy),
      profile,
      contextSources,
      guardrailEngine,
      modelPolicies,
      store,
      observer,
      contextManager,
      extensions
    )

  /** 与 [[inMemory]] 相同的装配，但由调用方提供工具治理解析器。
    *
    * 需要验证「运行中途替换生效配置」的行为时使用：审批要求的单调性、审批主体漂移和预算门禁都依赖 Runtime 在每次判定时 重新读取解析器，用固定的 [[ToolPolicyConfig]]
    * 无法覆盖这些路径。
    */
  def inMemoryWithToolPolicySource(
      model: ChatModel,
      tools: Iterable[RegisteredTool],
      toolPolicies: ToolPolicySource,
      profile: RuntimeProfile = RuntimeProfile.default,
      contextSources: ContextSourceResolver = ContextSourceResolver.emptyValue,
      guardrailEngine: GuardrailEngine = GuardrailEngine(
        ConfiguredGuardrails(Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty)
      ),
      modelPolicies: ModelPolicySource = ModelPolicySource.default,
      store: ULayer[RunStore] = RunStore.inMemory,
      observer: ULayer[RunObserver] = RunObserver.noop,
      contextManager: Option[ContextManager] = None,
      extensions: RuntimeExtensions = RuntimeExtensions.empty
  ): Layer[AgentError.InvalidConfiguration, AgentRuntime & RunStore] =
    contextManager match
      case Some(manager) =>
        ZLayer.make[AgentRuntime & RunStore](
          ZLayer.succeed[ChatModel](model),
          RegisteredToolRegistry.fromTools(tools),
          store,
          ZLayer.succeed[ContextManager](manager),
          ZLayer.succeed[ContextSourceResolver](contextSources),
          ZLayer.succeed(guardrailEngine),
          ZLayer.succeed(toolPolicies),
          ZLayer.succeed(modelPolicies),
          observer,
          RuntimeExtensions.layer(extensions),
          com.zyblw.agent.artifacts.ArtifactStore.inMemory(),
          AgentRuntimeDriver.layerWithProfile(profile)
        )
      case None =>
        ZLayer.make[AgentRuntime & RunStore](
          ZLayer.succeed[ChatModel](model),
          RegisteredToolRegistry.fromTools(tools),
          store,
          TokenCounter.approximate,
          ContextCompressor.deterministic,
          DefaultContextManager.layer,
          ZLayer.succeed[ContextSourceResolver](contextSources),
          ZLayer.succeed(guardrailEngine),
          ZLayer.succeed(toolPolicies),
          ZLayer.succeed(modelPolicies),
          observer,
          RuntimeExtensions.layer(extensions),
          com.zyblw.agent.artifacts.ArtifactStore.inMemory(),
          AgentRuntimeDriver.layerWithProfile(profile)
        )
