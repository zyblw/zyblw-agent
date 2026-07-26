package com.zyblw.agent.integrations.openai

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** OpenAI Responses 原生协议的纯编解码与 SSE 状态机契约测试。
  *
  * 这些测试故意不依赖真实 OpenAI：协议适配的确定性边界应该在本地快速验证，只有少量凭据保护的 smoke test 才需要访问真实 Provider。
  */
object OpenAIResponsesWireSpec extends ZIOSpecDefault:
  private val config = OpenAIResponsesConfig(
    baseUrl = "https://api.openai.test/v1",
    apiKey = "test-secret",
    defaultModel = "test-model"
  )

  /** 包含 reasoning、可见文本与工具调用的完整 Responses 返回样例。 */
  private val responsePayload =
    """{
      |  "id":"resp-1",
      |  "status":"completed",
      |  "output":[
      |    {"id":"rs-1","type":"reasoning","summary":[]},
      |    {"id":"msg-1","type":"message","role":"assistant","content":[{"type":"output_text","text":"需要查询","annotations":[]}]},
      |    {"id":"fc-1","type":"function_call","call_id":"call-1","name":"lookup","arguments":"{\"query\":\"zio\"}","status":"completed"}
      |  ],
      |  "usage":{"input_tokens":12,"output_tokens":7,"input_tokens_details":{"cached_tokens":5},"output_tokens_details":{"reasoning_tokens":3}}
      |} """.stripMargin

  /** typed SSE 样例同时覆盖工具增量、文本增量、usage 与最终完整 Response。 */
  private val streamPayload =
    """data: {"type":"response.created","response":{"id":"resp-stream","status":"in_progress","output":[]}}
      |
      |data: {"type":"response.output_item.added","response_id":"resp-stream","output_index":0,"item":{"id":"fc-stream","type":"function_call","call_id":"call-stream","name":"lookup","arguments":""}}
      |
      |data: {"type":"response.function_call_arguments.delta","response_id":"resp-stream","item_id":"fc-stream","output_index":0,"delta":"{\"query\":"}
      |
      |data: {"type":"response.function_call_arguments.delta","response_id":"resp-stream","item_id":"fc-stream","output_index":0,"delta":"\"zio\"}"}
      |
      |data: {"type":"response.function_call_arguments.done","response_id":"resp-stream","output_index":0,"item":{"id":"fc-stream","type":"function_call","call_id":"call-stream","name":"lookup","arguments":"{\"query\":\"zio\"}"}}
      |
      |data: {"type":"response.output_text.delta","response_id":"resp-stream","output_index":1,"delta":"查询中"}
      |
      |data: {"type":"response.completed","response":{"id":"resp-stream","status":"completed","output":[{"id":"fc-stream","type":"function_call","call_id":"call-stream","name":"lookup","arguments":"{\"query\":\"zio\"}","status":"completed"},{"id":"msg-stream","type":"message","role":"assistant","content":[{"type":"output_text","text":"查询中","annotations":[]}]}],"usage":{"input_tokens":9,"output_tokens":4,"input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":2}}}}
      |
      |data: [DONE]
      |
      |""".stripMargin

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OpenAI Responses wire")(
    test("请求使用原生扁平工具结构、store=false，并能回填 reasoning item 与工具结果") {
      for
        decoded <- OpenAIResponsesWire.decodeResponse(responsePayload)
        toolResult = AgentMessage.tool(
          callId = "call-1",
          name = "lookup",
          result = ToolResult(Json.Obj("result" -> Json.Str("ok")))
        )
        request = ChatRequest(
          messages = Chunk(AgentMessage.user("请查询"), decoded.message, toolResult),
          tools = Chunk(
            ToolDefinition(
              name = "lookup",
              description = "查询资料",
              inputSchema = Json.Obj(
                "type"                 -> Json.Str("object"),
                "properties"           -> Json.Obj("query" -> Json.Obj("type" -> Json.Str("string"))),
                "required"             -> Json.Arr(Json.Str("query")),
                "additionalProperties" -> Json.Bool(false)
              )
            )
          ),
          settings = ModelSettings(toolChoice = ToolChoice.Specific("lookup"))
        )
        encoded <- ZIO.fromEither(OpenAIResponsesWire.encodeRequest(request, config, streaming = false))
        json = encoded.toJson
      yield assertTrue(
        decoded.message.text == "需要查询",
        decoded.message.toolCalls.map(_.id) == Chunk("call-1"),
        decoded.usage == TokenUsage(12, 7, cachedInputTokens = 5, reasoningOutputTokens = 3),
        decoded.finishReason == FinishReason.ToolCalls,
        json.contains("\"store\":false"),
        json.contains("\"type\":\"reasoning\""),
        json.contains("\"type\":\"function_call_output\""),
        json.contains("\"call_id\":\"call-1\""),
        json.contains("\"name\":\"lookup\""),
        !json.contains("\"function\":{\"name\":\"lookup\"")
      )
    },
    test("任意网络分块都能组装 typed SSE，且每个工具完成事件只出现一次") {
      val bytes = streamPayload.getBytes(StandardCharsets.UTF_8)
      ZIO
        .foreach(1 to 13) { size =>
          val chunks = bytes.grouped(size).map(array => Chunk.fromArray(array)).toSeq
          OpenAIResponsesSse.events(ZStream.fromChunks(chunks*)).runCollect
        }
        .map { all =>
          val completed = all.flatMap(_.collect { case ModelStreamEvent.Completed(response) => response })
          val toolDoneCounts = all.map(_.count {
            case ModelStreamEvent.ToolCallCompleted(call) if call.id == "call-stream" => true
            case _                                                                    => false
          })
          assertTrue(
            completed.length == 13,
            completed.forall(_.message.text == "查询中"),
            completed.forall(_.message.toolCalls.headOption.exists(_.name == "lookup")),
            completed.forall(_.usage == TokenUsage(9, 4, cachedInputTokens = 4, reasoningOutputTokens = 2)),
            toolDoneCounts.forall(_ == 1)
          )
        }
    },
    test("终止事件前断流、负 usage 与保留字段覆盖都会显式失败") {
      val truncated = ZStream.fromIterable(
        "data: {\"type\":\"response.output_text.delta\",\"response_id\":\"r\",\"delta\":\"partial\"}\n\n"
          .getBytes(StandardCharsets.UTF_8)
      )
      val negativeUsage =
        """{"id":"bad","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"bad"}]}],"usage":{"input_tokens":-1,"output_tokens":2}}"""
      val inconsistentUsage =
        """{"id":"bad-details","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"bad"}]}],"usage":{"input_tokens":3,"output_tokens":2,"input_tokens_details":{"cached_tokens":4}}}"""
      val conflicting = config.copy(defaultOptions = Map("store" -> Json.Bool(true)))
      for
        truncatedExit <- OpenAIResponsesSse.events(truncated).runDrain.exit
        usageExit     <- OpenAIResponsesWire.decodeResponse(negativeUsage).exit
        detailsExit   <- OpenAIResponsesWire.decodeResponse(inconsistentUsage).exit
        encoded = OpenAIResponsesWire.encodeRequest(
          ChatRequest(Chunk(AgentMessage.user("hello"))),
          conflicting,
          streaming = false
        )
      yield assertTrue(truncatedExit.isFailure, usageExit.isFailure, detailsExit.isFailure, encoded.isLeft)
    }
  )
