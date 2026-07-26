package com.zyblw.agent.integrations.anthropic

import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** Anthropic 原生 content blocks、工具回填、usage 与 SSE 状态机的纯协议测试。 */
object AnthropicMessagesWireSpec extends ZIOSpecDefault:
  private val config = AnthropicMessagesConfig(
    "https://api.anthropic.test/v1",
    "test-secret",
    "claude-test"
  )

  private val responsePayload =
    """{
      | "id":"msg-1",
      | "type":"message",
      | "role":"assistant",
      | "content":[
      |   {"type":"thinking","thinking":"internal","signature":"signed"},
      |   {"type":"text","text":"需要查询"},
      |   {"type":"tool_use","id":"toolu-1","name":"lookup","input":{"query":"zio"}}
      | ],
      | "stop_reason":"tool_use",
      | "usage":{"input_tokens":11,"output_tokens":7}
      |} """.stripMargin

  private val streamPayload =
    """event: message_start
      |data: {"type":"message_start","message":{"id":"msg-stream","type":"message","role":"assistant","content":[],"usage":{"input_tokens":9,"output_tokens":0}}}
      |
      |event: content_block_start
      |data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
      |
      |event: content_block_delta
      |data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"查询中"}}
      |
      |event: content_block_stop
      |data: {"type":"content_block_stop","index":0}
      |
      |event: content_block_start
      |data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu-stream","name":"lookup","input":{}}}
      |
      |event: content_block_delta
      |data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"zio\"}"}}
      |
      |event: content_block_stop
      |data: {"type":"content_block_stop","index":1}
      |
      |event: message_delta
      |data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":5}}
      |
      |event: message_stop
      |data: {"type":"message_stop"}
      |
      |""".stripMargin

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Anthropic Messages wire")(
    test("原生响应保留 thinking blocks，工具结果按 tool_result 回填") {
      for
        decoded <- AnthropicMessagesWire.decodeResponse(responsePayload)
        request = ChatRequest(
          Chunk(
            AgentMessage.system("系统规则"),
            AgentMessage.developer("开发规则"),
            AgentMessage.user("请查询"),
            decoded.message,
            AgentMessage.tool("toolu-1", "lookup", ToolResult(Json.Obj("answer" -> Json.Str("ok"))))
          ),
          Chunk(ToolDefinition("lookup", "查询", Json.Obj("type" -> Json.Str("object")))),
          ModelSettings(toolChoice = ToolChoice.Specific("lookup"))
        )
        encoded <- ZIO.fromEither(AnthropicMessagesWire.encodeRequest(request, config, streaming = false))
        json = encoded.toJson
      yield assertTrue(
        decoded.message.text == "需要查询",
        decoded.message.toolCalls.map(_.id) == Chunk("toolu-1"),
        decoded.usage == TokenUsage(11, 7),
        decoded.finishReason == FinishReason.ToolCalls,
        decoded.message.metadata(AnthropicMessagesWire.RawContentBlocksMetadata).contains("signature"),
        json.contains("\"system\":\"系统规则\\n\\n[developer]\\n开发规则\""),
        json.contains("\"type\":\"tool_result\""),
        json.contains("\"tool_use_id\":\"toolu-1\""),
        json.contains("\"input_schema\""),
        !json.contains("\"function\"")
      )
    },
    test("任意 byte 分块均生成工具增量、usage 和唯一 Completed") {
      val bytes = streamPayload.getBytes(StandardCharsets.UTF_8)
      ZIO
        .foreach(1 to 11) { size =>
          val chunks = bytes.grouped(size).map(Chunk.fromArray).toSeq
          AnthropicMessagesSse.events(ZStream.fromChunks(chunks*)).runCollect
        }
        .map { all =>
          val completed = all.flatMap(_.collect {
            case com.zyblw.agent.model.ModelStreamEvent.Completed(response) => response
          })
          val toolCompletedCounts = all.map(_.count {
            case com.zyblw.agent.model.ModelStreamEvent.ToolCallCompleted(call)
                if call.id == "toolu-stream" =>
              true
            case _ => false
          })
          assertTrue(
            completed.size == 11,
            completed.forall(_.message.text == "查询中"),
            completed.forall(_.message.toolCalls.headOption.exists(_.arguments.toJson.contains("zio"))),
            completed.forall(_.usage == TokenUsage(9, 5)),
            toolCompletedCounts.forall(_ == 1)
          )
        }
    },
    test("message_stop 前断流与负 usage 都显式失败") {
      val truncated = ZStream.fromIterable(
        "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n".getBytes
      )
      val invalid = ZStream.fromIterable(
        ("data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":-1,\"output_tokens\":0}}}\n\n").getBytes
      )
      AnthropicMessagesSse
        .events(truncated)
        .runDrain
        .exit
        .zipWith(
          AnthropicMessagesSse.events(invalid).runDrain.exit
        )((left, right) => assertTrue(left.isFailure, right.isFailure))
    }
  )
