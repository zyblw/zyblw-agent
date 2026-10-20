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
      | "usage":{"total_input_tokens":12,"total_cached_tokens":5,"total_output_tokens":6,"total_thought_tokens":3,"total_tokens":21}
      |} """.stripMargin

  private val streamPayload =
    """event: interaction.created
      |data: {"interaction":{"id":"int-stream","status":"in_progress","service_tier":"priority"},"event_type":"interaction.created"}
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
      |data: {"interaction":{"id":"int-stream","status":"requires_action","usage":{"total_input_tokens":8,"total_cached_tokens":3,"total_output_tokens":4}},"event_type":"interaction.requires_action"}
      |
      |""".stripMargin

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Gemini Interactions wire")(
    test("thought output is billed once and terminal streaming facts match ordinary responses") {
      val usage =
        """{"total_input_tokens":100,"total_output_tokens":1000,"total_thought_tokens":9000,"total_tokens":10100,"total_tool_use_tokens":0}"""
      val response = s"""{"id":"r","status":"completed","service_tier":"flex","steps":[],"usage":$usage}"""
      val stream   = s"""event: interaction.completed
 data: {"event_type":"interaction.completed","interaction":$response}

""".replace(" data:", "data:")
      for
        ordinary     <- GeminiInteractionsWire.decodeResponse(response)
        inconsistent <- GeminiInteractionsWire.decodeResponse(
          response.replace("\"total_tokens\":10100", "\"total_tokens\":10101")
        )
        events <- GeminiInteractionsSse
          .events(ZStream.fromIterable(stream.getBytes(StandardCharsets.UTF_8)))
          .runCollect
        invalid <- ZIO.foreach(List("-1", "1.5", "9223372036854775807", "\"unknown\""))(bad =>
          GeminiInteractionsWire
            .decodeResponse(
              response.replace("\"total_thought_tokens\":9000", s"\"total_thought_tokens\":$bad")
            )
            .either
        )
        completed = events.collect { case ModelStreamEvent.Completed(r) => r }
      yield assertTrue(
        ordinary.usage.outputTokens == 10000,
        ordinary.usage.reasoningOutputTokens == 9000,
        ModelPrice(1, 10).rated(
          ordinary.usage.copy(billing = ordinary.usage.billing.map(_.copy(dimensions = Map.empty)))
        ) == Right(BigDecimal("0.1001")),
        completed.head.usage == ordinary.usage,
        completed.head.usage.billing.exists(_.dimensions.get("service_tier").contains("flex")),
        invalid.forall(_.isLeft),
        inconsistent.usage.billing.exists(_.issues.nonEmpty)
      )
    },
    test("modality facts distinguish fresh audio, cached audio and unsupported tool prompts") {
      val raw = """{"total_input_tokens":100,"total_output_tokens":10,"total_cached_tokens":20,
        "input_tokens_by_modality":[{"modality":"audio","tokens":40},{"modality":"text","tokens":60}],
        "cached_tokens_by_modality":[{"modality":"audio","tokens":10},{"modality":"text","tokens":10}],
        "output_tokens_by_modality":[{"modality":"text","tokens":10}],"total_tool_use_tokens":5}"""
      for
        usage   <- GeminiInteractionsWire.decodeUsage(Some(raw.fromJson[Json].toOption.get), "usage")
        unknown <- GeminiInteractionsWire.decodeUsage(
          Some(raw.replace("\"cached_tokens_by_modality\"", "\"omitted\"").fromJson[Json].toOption.get),
          "usage"
        )
      yield assertTrue(
        usage.billing.exists(_.meters.get("input_audio_tokens").contains("30")),
        usage.billing.exists(_.meters.get("tool_use_tokens").contains("5")),
        unknown.billing.exists(_.issues.nonEmpty),
        ModelPrice(1, 2).rated(unknown).isLeft
      )
    },
    test("缺失或不完整 usage 不成为准确零，流式同样保留未知") {
      val body =
        """{"id":"r","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"ok"}]}]}"""
      val stream = streamPayload.replaceAll(",\"usage\":\\{[^}]+\\}", "")
      for
        response <- GeminiInteractionsWire.decodeResponse(body)
        partial  <- GeminiInteractionsWire.decodeResponse(
          body.dropRight(1) + """, "usage":{"total_input_tokens":4}}"""
        )
        events <- GeminiInteractionsSse
          .events(ZStream.fromIterable(stream.getBytes(StandardCharsets.UTF_8)))
          .runCollect
        completed = events.collect { case ModelStreamEvent.Completed(value) => value }
      yield assertTrue(
        !response.usageReported,
        !partial.usageReported,
        completed.size == 1,
        !completed.head.usageReported
      )
    },
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
          settings = ModelSettings(
            toolChoice = ToolChoice.Required,
            reasoningEffort = Some(ReasoningEffort.Medium)
          )
        )
        encoded <- ZIO.fromEither(GeminiInteractionsWire.encodeRequest(request, config, streaming = false))
        body = encoded.toJson
      yield assertTrue(
        decoded.message.text == "需要查询",
        decoded.message.toolCalls.map(_.id) == Chunk("call-1"),
        decoded.usage
          .copy(billing = None) == TokenUsage(12, 9, cachedInputTokens = 5, reasoningOutputTokens = 3),
        decoded.finishReason == FinishReason.ToolCalls,
        decoded.message.metadata(GeminiInteractionsWire.RawStepsMetadata).contains("signed-thought"),
        body.contains("\"store\":false"),
        body.contains("\"system_instruction\":\"系统规则\\n\\n[developer]\\n开发规则\""),
        body.contains("\"type\":\"function_result\""),
        body.contains("\"call_id\":\"call-1\""),
        body.contains("\"thinking_level\":\"medium\""),
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
            completed.forall(_.usage.copy(billing = None) == TokenUsage(8, 4, cachedInputTokens = 3)),
            completed.forall(_.usage.billing.exists(_.dimensions.get("service_tier").contains("priority"))),
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
      val invalidEffort = ZIO
        .fromEither(
          GeminiInteractionsWire.encodeRequest(
            ChatRequest(
              Chunk(AgentMessage.user("hi")),
              settings = ModelSettings(reasoningEffort = Some(ReasoningEffort.Max))
            ),
            config,
            streaming = false
          )
        )
        .exit
      truncated
        .zipWith(invalidUsage)((a, b) => (a, b))
        .zipWith(invalidOption)((ab, c) => (ab, c))
        .zipWith(invalidEffort) { case (((a, b), c), d) =>
          assertTrue(a.isFailure, b.isFailure, c.isFailure, d.isFailure)
        }
    }
  )
