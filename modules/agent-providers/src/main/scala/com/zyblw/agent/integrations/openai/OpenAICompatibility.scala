package com.zyblw.agent.integrations.openai

// 集中描述 OpenAI-compatible 厂商的协议差异，避免在 HTTP 编解码分支中散落模型名称判断。

import com.zyblw.agent.model.*

enum DeveloperRoleMode:
  case Native, MapToSystem

enum StrictToolSchemaMode:
  case Include, Omit, Disable

enum ToolChoiceMode:
  case Full, AutoOnly, Omit

/** OpenAI-compatible Chat Completions 中已通过契约测试的推理控制方言。 */
enum ReasoningWireMode:
  /** 不发送通用推理字段；厂商私有选项仍可作为受控迁移出口。 */
  case Unsupported

  /** OpenAI Chat Completions 的 `reasoning_effort`，`Max` 映射为 `xhigh`。 */
  case OpenAIEffort

  /** 直接使用 `reasoning_effort`，`Max` 保持为 `max`。 */
  case StandardEffort

  /** DeepSeek：`thinking.type` 控制开关，非 None 档位另发 `reasoning_effort`。 */
  case ThinkingObjectEffort

  /** Qwen Chat Completions：typed 档位只投影为 `enable_thinking` 开关。 */
  case EnableThinking

  /** Kimi Chat Completions：typed 档位只投影为 `thinking.type` 开关。 */
  case ThinkingObjectToggle

final case class OpenAICompatibility(
    descriptor: ProviderDescriptor,
    developerRoleMode: DeveloperRoleMode,
    strictToolSchemaMode: StrictToolSchemaMode,
    toolChoiceMode: ToolChoiceMode,
    outputTokenField: String = "max_tokens",
    preserveReasoningContent: Boolean = false,
    reasoningWireMode: ReasoningWireMode = ReasoningWireMode.Unsupported
)

object OpenAICompatibility:
  val openAI: OpenAICompatibility = OpenAICompatibility(
    ProviderDescriptor(
      "openai",
      "OpenAI",
      "openai-chat-completions",
      ModelCapabilities(
        toolCalls = true,
        strictToolSchema = true,
        specificToolChoice = true,
        developerRole = true,
        thinking = true,
        vision = true,
        streaming = true,
        reasoningEfforts = Set(
          com.zyblw.agent.core.ReasoningEffort.None,
          com.zyblw.agent.core.ReasoningEffort.Low,
          com.zyblw.agent.core.ReasoningEffort.Medium,
          com.zyblw.agent.core.ReasoningEffort.High,
          com.zyblw.agent.core.ReasoningEffort.Max
        )
      )
    ),
    DeveloperRoleMode.Native,
    StrictToolSchemaMode.Include,
    ToolChoiceMode.Full,
    outputTokenField = "max_completion_tokens",
    reasoningWireMode = ReasoningWireMode.OpenAIEffort
  )

  val deepSeek: OpenAICompatibility = OpenAICompatibility(
    ProviderDescriptor(
      "deepseek",
      "DeepSeek",
      "openai-chat-completions",
      ModelCapabilities(
        toolCalls = true,
        strictToolSchema = false,
        specificToolChoice = false,
        developerRole = false,
        thinking = true,
        streaming = true,
        reasoningEfforts = Set(
          com.zyblw.agent.core.ReasoningEffort.None,
          com.zyblw.agent.core.ReasoningEffort.Low,
          com.zyblw.agent.core.ReasoningEffort.Medium,
          com.zyblw.agent.core.ReasoningEffort.High,
          com.zyblw.agent.core.ReasoningEffort.Max
        )
      )
    ),
    DeveloperRoleMode.MapToSystem,
    StrictToolSchemaMode.Omit,
    ToolChoiceMode.Omit,
    preserveReasoningContent = true,
    reasoningWireMode = ReasoningWireMode.ThinkingObjectEffort
  )

  val glm: OpenAICompatibility = OpenAICompatibility(
    ProviderDescriptor(
      "glm",
      "Zhipu GLM",
      "openai-chat-completions",
      ModelCapabilities(
        toolCalls = true,
        strictToolSchema = false,
        specificToolChoice = false,
        developerRole = false,
        thinking = true,
        streaming = true
      )
    ),
    DeveloperRoleMode.MapToSystem,
    StrictToolSchemaMode.Omit,
    ToolChoiceMode.AutoOnly,
    preserveReasoningContent = true,
    reasoningWireMode = ReasoningWireMode.StandardEffort
  )

  /** Alibaba Cloud Model Studio 的 Qwen OpenAI Chat Completions 兼容档案。
    *
    * Qwen 的具体能力随模型变化。这里声明兼容端点共同具备的能力及 `enable_thinking` 推理开关；视觉等能力可在 `ProviderEndpointDeclaration.models`
    * 中按部署模型显式覆盖。
    */
  val qwen: OpenAICompatibility = OpenAICompatibility(
    ProviderDescriptor(
      "qwen",
      "Alibaba Cloud Qwen",
      "openai-chat-completions",
      ModelCapabilities(
        toolCalls = true,
        strictToolSchema = false,
        specificToolChoice = true,
        developerRole = false,
        streaming = true,
        usageReporting = true,
        reasoningEfforts = Set(
          com.zyblw.agent.core.ReasoningEffort.None,
          com.zyblw.agent.core.ReasoningEffort.Low,
          com.zyblw.agent.core.ReasoningEffort.Medium,
          com.zyblw.agent.core.ReasoningEffort.High
        )
      )
    ),
    DeveloperRoleMode.MapToSystem,
    StrictToolSchemaMode.Omit,
    ToolChoiceMode.Full,
    preserveReasoningContent = true,
    reasoningWireMode = ReasoningWireMode.EnableThinking
  )

  /** Moonshot Kimi Open Platform 的 OpenAI Chat Completions 兼容档案。
    *
    * 思考模式下工具选择只声明 `auto`，并保留 `reasoning_content` 用于工具回填。视觉、strict schema 与可切换推理档位均随 模型变化，不在 Provider
    * 默认能力中猜测；宿主可通过 `ProviderEndpointDeclaration.models` 逐模型声明。
    */
  val kimi: OpenAICompatibility = OpenAICompatibility(
    ProviderDescriptor(
      "kimi",
      "Moonshot Kimi",
      "openai-chat-completions",
      ModelCapabilities(
        toolCalls = true,
        strictToolSchema = false,
        specificToolChoice = false,
        developerRole = false,
        thinking = true,
        streaming = true,
        usageReporting = true
      )
    ),
    DeveloperRoleMode.MapToSystem,
    StrictToolSchemaMode.Disable,
    ToolChoiceMode.AutoOnly,
    preserveReasoningContent = true,
    reasoningWireMode = ReasoningWireMode.ThinkingObjectToggle
  )

  /** AIHubMix 聚合中转平台兼容档案。
    *
    * 汇聚 Claude、Gemini、OpenAI、DeepSeek、Qwen 等主流模型。
    *   1. 角色映射：使用 `DeveloperRoleMode.MapToSystem`，因为非 OpenAI 模型（Claude、Gemini、DeepSeek 等）不接受 `developer` 角色。
    *   2. 工具 Schema：使用 `StrictToolSchemaMode.Omit`，避免多厂商上游在 `strict: true` 下报错。
    *   3. 工具选择：`ToolChoiceMode.Full`，支持 auto、none 及命名函数选择。
    *   4. 字段兼容：使用 `outputTokenField = "max_tokens"`，广泛兼容多厂商模型。
    *   5. 推理控制：使用 `ReasoningWireMode.StandardEffort`，直接向顶层透传
    *      `"reasoning_effort": "none" | "low" | "medium" | "high"`。 AIHubMix 在 Gemini 模型上会将此字段映射为 1K/8K/24K
    *      token 思考预算，并在 "none" 时彻底关闭思考； 在 Claude / OpenAI / DeepSeek 模型上也会按需转换。
    *   6. 多轮工具链：`preserveReasoningContent = true`，保留 Gemini / DeepSeek / Claude 在多轮工具调用中的思考签名与推理上下文。
    */
  def aihubmix(id: String = "aihubmix", displayName: String = "AIHubMix"): OpenAICompatibility =
    require(id.matches("[a-z][a-z0-9_-]{0,31}"), "AIHubMix Provider id 必须是 1..32 的小写标识")
    OpenAICompatibility(
      ProviderDescriptor(
        id,
        displayName,
        "openai-chat-completions",
        ModelCapabilities(
          toolCalls = true,
          strictToolSchema = false,
          specificToolChoice = true,
          developerRole = false,
          thinking = true,
          vision = true,
          streaming = true,
          usageReporting = true,
          reasoningEfforts = Set(
            com.zyblw.agent.core.ReasoningEffort.None,
            com.zyblw.agent.core.ReasoningEffort.Low,
            com.zyblw.agent.core.ReasoningEffort.Medium,
            com.zyblw.agent.core.ReasoningEffort.High
          )
        )
      ),
      DeveloperRoleMode.MapToSystem,
      StrictToolSchemaMode.Omit,
      ToolChoiceMode.Full,
      outputTokenField = "max_tokens",
      preserveReasoningContent = true,
      reasoningWireMode = ReasoningWireMode.StandardEffort
    )

  /** 通用中转站档案：任意本地 Provider id，协议仍走 OpenAI Chat Completions。
    *
    * 模型名原样发送到网关；能力默认保守（工具 + 流式），宿主应通过 `ProviderEndpointsConfig` 为每个模型填写更精确的 `ModelCapabilities`。
    */
  def relay(id: String, displayName: String = "OpenAI-compatible relay"): OpenAICompatibility =
    require(id.matches("[a-z][a-z0-9_-]{0,31}"), "中转站 Provider id 必须是 1..32 的小写标识")
    OpenAICompatibility(
      ProviderDescriptor(
        id,
        displayName,
        "openai-chat-completions",
        ModelCapabilities(
          toolCalls = true,
          streaming = true,
          usageReporting = true,
          thinking = true,
          reasoningEfforts = Set(
            com.zyblw.agent.core.ReasoningEffort.None,
            com.zyblw.agent.core.ReasoningEffort.Low,
            com.zyblw.agent.core.ReasoningEffort.Medium,
            com.zyblw.agent.core.ReasoningEffort.High
          )
        )
      ),
      // Generic relays commonly expose the Chat Completions system/user role set.
      // Mapping developer to system keeps the request valid for gateways that do
      // not implement the newer developer role while preserving its instruction
      // semantics.
      DeveloperRoleMode.MapToSystem,
      StrictToolSchemaMode.Omit,
      ToolChoiceMode.Full,
      preserveReasoningContent = true,
      reasoningWireMode = ReasoningWireMode.StandardEffort
    )
