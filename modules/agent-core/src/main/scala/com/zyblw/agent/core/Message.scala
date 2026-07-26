package com.zyblw.agent.core

import zio.*
import zio.json.*
import zio.json.ast.Json

enum MessageRole derives JsonCodec:
  case System, Developer, User, Assistant, Tool

enum ContentPart derives JsonCodec:
  case Text(value: String)
  case JsonValue(value: Json)
  case ImageUrl(url: String, detail: Option[String] = None)

/** Provider-neutral 工具调用；稳定 callId 是恢复和幂等的基础。 */
final case class ToolCall(id: String, name: String, arguments: Json) derives JsonCodec

final case class AgentMessage(
    role: MessageRole,
    content: Chunk[ContentPart],
    toolCalls: Chunk[ToolCall] = Chunk.empty,
    toolCallId: Option[String] = None,
    name: Option[String] = None,
    metadata: Map[String, String] = Map.empty
) derives JsonCodec:
  /** 提取消息中所有文本片段并按原顺序连接。 JSON、图片等非文本内容不会被强制转成字符串，避免把结构化数据意外塞进提示词。
    *
    * @return
    *   仅由 `ContentPart.Text` 组成的文本；没有文本片段时返回空字符串
    */
  def text: String = content.collect { case ContentPart.Text(value) => value }.mkString

object AgentMessage:
  /** 创建系统消息。`text` 通常放稳定、最高优先级的 Agent 身份和行为边界。 */
  def system(text: String): AgentMessage = textMessage(MessageRole.System, text)

  /** 创建开发者消息。Provider 不支持该角色时，由适配器按能力矩阵显式转换或拒绝。 */
  def developer(text: String): AgentMessage = textMessage(MessageRole.Developer, text)

  /** 创建用户消息。参数 `text` 必须已经经过业务输入边界所需的长度和安全检查。 */
  def user(text: String): AgentMessage = textMessage(MessageRole.User, text)

  /** 创建普通助手文本消息，不携带工具调用。 */
  def assistant(text: String): AgentMessage = textMessage(MessageRole.Assistant, text)

  /** 创建包含工具调用提议的助手消息。
    *
    * @param calls
    *   模型提出的工具调用；稳定 call id 用于结果关联、幂等和恢复
    * @param text
    *   模型同时返回的可选说明文本
    * @return
    *   尚未执行任何工具的助手消息；真正执行必须由 Runtime 完成
    */
  def assistantToolCalls(calls: Chunk[ToolCall], text: String = ""): AgentMessage =
    AgentMessage(MessageRole.Assistant, Chunk(ContentPart.Text(text)), toolCalls = calls)

  /** 把工具执行结果转换成回填模型的 Tool 消息。
    *
    * @param callId
    *   对应原始工具调用 ID，模型依靠它匹配请求与结果
    * @param name
    *   工具注册名称，用于诊断和 Provider 编码
    * @param result
    *   结构化成功或错误结果；即使拒绝/超时也必须形成结果消息
    */
  def tool(callId: String, name: String, result: ToolResult): AgentMessage =
    AgentMessage(
      role = MessageRole.Tool,
      content = Chunk(ContentPart.JsonValue(result.value)),
      toolCallId = Some(callId),
      name = Some(name),
      metadata = Map("isError" -> result.isError.toString)
    )

  /** 内部统一构造纯文本消息，避免各角色工厂重复初始化默认字段。 */
  private def textMessage(role: MessageRole, text: String): AgentMessage =
    AgentMessage(role, Chunk(ContentPart.Text(text)))

final case class ToolResult(value: Json, isError: Boolean = false, metadata: Map[String, String] = Map.empty)
    derives JsonCodec
