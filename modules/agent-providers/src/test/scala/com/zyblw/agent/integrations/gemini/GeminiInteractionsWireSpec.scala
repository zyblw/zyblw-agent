package com.zyblw.agent.integrations.gemini

import com.zyblw.agent.core.*
import com.zyblw.agent.model.ModelStreamEvent
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** Gemini steps、签名重放、function result、usage 与任意 byte 分块的纯协议测试。 */
object GeminiInteractionsWireSpec extends ZIOSpecDefault:
  private val config = GeminiInteractionsConfig(
    "https://generativelanguage.test/v1",
    "test-secret",
    "gemini-test"
  )

  private val toolResponse =
    """{
      | "id":"int-tool-1",
      | "model":"gemini-test",
      | "steps":[
      |   {"type":"thought","signature":"signed-thought"},
      |   {"type":"model_output","content":[{"type":"text","text":"需要查询"}]},
      |   {"type":"function_call","id":"call-1","name":"lookup","arguments":{"query":"zio"},"signature":"signed-call"}
      | ],
      | "status":"requires_action",
      | "usage":{"total_input_tokens":12,"total_output_tokens":6,"total_thought_tokens":3,"total_tokens":21}
      |} """.stripMargin

  private val streamPayload =
    """event: interaction.created
      |data: {"interaction":{"id":"int-stream","status":"in_progress"},"event_type":"interaction.created"}
      |
      |event: step.start
      |data: {"index":0,"step":{"type":"thought","signature":"thought-signature"},"event_type":"step.start"}
      |
      |event: step.stop
      |data: {"index":0,"event_type":"step.stop"}
      |
      |event: step.start
      |data: {"index":1,"step":{"type":"model_output","content":[{"type":"text","text":"正在"}]},"event_type":"step.start"}
      |
      |event: step.delta
      |data: {"index":1,"delta":{"type":"text","text":"查询"},"event_type":"step.delta"}
      |
      |event: step.stop
      |data: {"index":1,"event_type":"step.stop"}
      |
      |event: step.start
      |data: {"index":2,"step":{"type":"function_call","id":"call-stream","name":"lookup","signature":"call-signature"},"event_type":"step.start"}
      |
      |event: step.delta
      |data: {"index":2,"delta":{"type":"arguments_delta","arguments":"{\"query\":"},"event_type":"step.delta"}
      |
      |event: step.delta
      |data: {"index":2,"delta":{"type":"arguments_delta","arguments":"\"zio\"}"},"event_type":"step.delta"}
      |
      |event: step.stop
      |data: {"index":2,"event_type":"step.stop"}
      |
      |event: interaction.requires_action
      |data: {"interaction":{"id":"int-stream","status":"requires_action","usage":{"total_input_tokens":8,"total_output_tokens":4}},"event_type":"interaction.requires_action"}
      |
      |""".stripMargin

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Gemini Interactions wire")(
    test("原生 tool response 保存完整 steps，并以相同 call_id 回填 function_result") {
      for
        decoded <- GeminiInteractionsWire.decodeResponse(toolResponse)
        request = ChatRequest(
          messages = Chunk(
            AgentMessage.system("系统规则"),
            AgentMessage.developer("开发规则"),
            AgentMessage.user("请查询"),
            decoded.message,
            AgentMessage.tool("call-1", "lookup", ToolResult(Json.Obj("answer" -> Json.Str("ok"))))
          ),
          tools = Chunk(ToolDefinition("lookup", "查询", Json.Obj("type" -> Json.Str("object")))),
          settings = ModelSettings(toolChoice = ToolChoice.Required)
        )
        encoded <- ZIO.fromEither(GeminiInteractionsWire.encodeRequest(request, config, streaming = false))
        body = encoded.toJson
      yield assertTrue(
        decoded.message.text == "需要查询",
        decoded.message.toolCalls.map(_.id) == Chunk("call-1"),
        decoded.usage == TokenUsage(12, 6),
        decoded.finishReason == FinishReason.ToolCalls,
        decoded.message.metadata(GeminiInteractionsWire.RawStepsMetadata).contains("signed-thought"),
        body.contains("\"store\":false"),
        body.contains("\"system_instruction\":\"系统规则\\n\\n[developer]\\n开发规则\""),
        body.contains("\"type\":\"function_result\""),
        body.contains("\"call_id\":\"call-1\""),
        body.contains("signed-call"),
        !body.contains("previous_interaction_id")
      )
    },
    test("任意 UTF-8 byte 分块都产生确定性工具顺序、usage 和唯一 Completed") {
      val bytes = streamPayload.getBytes(StandardCharsets.UTF_8)
      ZIO
        .foreach(1 to 13) { size =>
          GeminiInteractionsSse
            .events(
              ZStream.fromChunks(bytes.grouped(size).map(Chunk.fromArray).toSeq*)
            )
            .runCollect
        }
        .map { all =>
          val completed = all.flatMap(_.collect { case ModelStreamEvent.Completed(response) => response })
          assertTrue(
            completed.size == 13,
            completed.forall(_.message.text == "正在查询"),
            completed.forall(_.message.toolCalls.map(_.id) == Chunk("call-stream")),
            completed.forall(_.message.toolCalls.head.arguments.toJson.contains("zio")),
            completed.forall(_.usage == TokenUsage(8, 4)),
            completed.forall(
              _.message.metadata(GeminiInteractionsWire.RawStepsMetadata).contains("thought-signature")
            ),
            all.forall(_.count(_.isInstanceOf[ModelStreamEvent.Completed]) == 1),
            all.forall(_.lastOption.exists(_.isInstanceOf[ModelStreamEvent.Completed]))
          )
        }
    },
    test("终止前断流、负 usage、未知 providerOptions 均 fail-closed") {
      val truncated = GeminiInteractionsSse
        .events(
          ZStream.fromIterable(
            "data: {\"interaction\":{\"id\":\"i\"},\"event_type\":\"interaction.created\"}\n\n".getBytes
          )
        )
        .runDrain
        .exit
      val invalidUsage = GeminiInteractionsWire
        .decodeResponse(
          """{"id":"i","status":"completed","steps":[],"usage":{"total_input_tokens":-1,"total_output_tokens":2}}"""
        )
        .exit
      val invalidOption = ZIO
        .fromEither(
          GeminiInteractionsWire.encodeRequest(
            ChatRequest(
              Chunk(AgentMessage.user("hi")),
              settings = ModelSettings(providerOptions = Map("hosted_tool" -> Json.Bool(true)))
            ),
            config,
            streaming = false
          )
        )
        .exit
      truncated.zipWith(invalidUsage)((a, b) => (a, b)).zipWith(invalidOption) { case ((a, b), c) =>
        assertTrue(a.isFailure, b.isFailure, c.isFailure)
      }
    }
  )
