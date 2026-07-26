package com.zyblw.agent.core

import zio.*
import zio.json.*
import zio.json.ast.Json

/** 提供给模型的工具协议定义；Schema 是跨 Provider 边界的稳定 JSON 表示。 */
final case class ToolDefinition(
    name: String,
    description: String,
    inputSchema: Json.Obj,
    outputSchema: Option[Json.Obj] = None,
    strict: Boolean = true
) derives JsonCodec

/** 厂商无关的生成参数；Provider 不支持的字段必须显式拒绝或按配置降级。 */
final case class ModelSettings(
    provider: Option[String] = None,
    model: Option[String] = None,
    temperature: Option[Double] = None,
    maxOutputTokens: Option[Int] = None,
    toolChoice: ToolChoice = ToolChoice.Auto,
    providerOptions: Map[String, Json] = Map.empty,
    metadata: Map[String, String] = Map.empty
) derives JsonCodec

enum ToolChoice derives JsonCodec:
  case Auto, None, Required
  case Specific(name: String)

/** 一次完整模型请求；消息和工具均使用框架内部模型，不泄漏厂商 SDK 类型。 */
final case class ChatRequest(
    messages: Chunk[AgentMessage],
    tools: Chunk[ToolDefinition] = Chunk.empty,
    settings: ModelSettings = ModelSettings()
) derives JsonCodec

enum FinishReason derives JsonCodec:
  case Stop, ToolCalls, Length, ContentFilter
  case Other(value: String)

/** 一次模型调用的 Provider-neutral Token 用量。
  *
  * `cachedInputTokens` 是 `inputTokens` 的子集，`reasoningOutputTokens` 是 `outputTokens` 的子集，因此 `totalTokens`
  * 只计算输入与输出总量，不能再次累加两个明细字段。Provider 未返回明细时保持零，框架不会把估算值 冒充供应商账单事实。
  *
  * @param inputTokens
  *   Provider 报告的全部输入 token
  * @param outputTokens
  *   Provider 报告的全部输出 token
  * @param cachedInputTokens
  *   输入 token 中由 Prompt Cache 命中的数量
  * @param reasoningOutputTokens
  *   输出 token 中用于内部推理的数量；不得记录推理正文
  */
final case class TokenUsage(
    inputTokens: Long = 0L,
    outputTokens: Long = 0L,
    cachedInputTokens: Long = 0L,
    reasoningOutputTokens: Long = 0L
) derives JsonCodec:
  /** 返回输入与输出 token 总和，供总预算和成本估算使用。 */
  def totalTokens: Long = inputTokens + outputTokens

  /** 合并两次模型调用的用量。
    * @param that
    *   另一段用量，通常来自下一次模型响应
    * @return
    *   新的不可变累计值，不修改任一操作数
    */
  def +(that: TokenUsage): TokenUsage =
    TokenUsage(
      inputTokens + that.inputTokens,
      outputTokens + that.outputTokens,
      cachedInputTokens + that.cachedInputTokens,
      reasoningOutputTokens + that.reasoningOutputTokens
    )

/** 模型完成结果，保留 usage、finish reason 和可选厂商 request ID 以便审计。 */
final case class ChatResponse(
    message: AgentMessage,
    finishReason: FinishReason,
    usage: TokenUsage = TokenUsage(),
    providerRequestId: Option[String] = None,
    metadata: Map[String, String] = Map.empty
) derives JsonCodec
