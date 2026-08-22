package com.zyblw.agent.integrations.openai

// 集中描述 OpenAI-compatible 厂商的协议差异，避免在 HTTP 编解码分支中散落模型名称判断。

import com.zyblw.agent.model.*

enum DeveloperRoleMode:
  case Native, MapToSystem

enum StrictToolSchemaMode:
  case Include, Omit

enum ToolChoiceMode:
  case Full, AutoOnly, Omit

final case class OpenAICompatibility(
    descriptor: ProviderDescriptor,
    developerRoleMode: DeveloperRoleMode,
    strictToolSchemaMode: StrictToolSchemaMode,
    toolChoiceMode: ToolChoiceMode,
    outputTokenField: String = "max_tokens",
    preserveReasoningContent: Boolean = false
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
        streaming = true
      )
    ),
    DeveloperRoleMode.Native,
    StrictToolSchemaMode.Include,
    ToolChoiceMode.Full,
    outputTokenField = "max_completion_tokens"
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
        streaming = true
      )
    ),
    DeveloperRoleMode.MapToSystem,
    StrictToolSchemaMode.Omit,
    ToolChoiceMode.Omit,
    preserveReasoningContent = true
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
    ToolChoiceMode.AutoOnly
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
        ModelCapabilities(toolCalls = true, streaming = true, usageReporting = true)
      ),
      DeveloperRoleMode.Native,
      StrictToolSchemaMode.Omit,
      ToolChoiceMode.Full
    )
