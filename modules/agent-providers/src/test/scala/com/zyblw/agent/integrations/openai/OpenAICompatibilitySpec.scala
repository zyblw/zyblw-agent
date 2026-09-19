package com.zyblw.agent.integrations.openai

// 以纯编解码方式验证 OpenAI、DeepSeek、GLM、Qwen、Kimi 的字段兼容矩阵，不依赖真实 API Key 和网络。

import com.zyblw.agent.core.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object OpenAICompatibilitySpec extends ZIOSpecDefault:
  private val tool = ToolDefinition(
    "lookup",
    "Lookup data",
    Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj("query" -> Json.Obj("type" -> Json.Str("string"))),
      "required"   -> Json.Arr(Chunk(Json.Str("query")))
    )
  )

  def spec = suite("OpenAI-compatible provider profiles")(
    test("DeepSeek maps developer role, omits incompatible fields and keeps thinking options") {
      val request = ChatRequest(
        Chunk(AgentMessage.developer("rule"), AgentMessage.user("question")),
        Chunk(tool)
      )
      val config  = ProviderPresets.deepSeek("test-key")
      val encoded = OpenAIWire
        .encodeRequest(request, config.defaultModel, config.compatibility, config.defaultOptions)
        .map(_.toString)
      assertTrue(
        encoded.exists(_.contains("deepseek-v4-flash")),
        encoded.exists(_.contains("system")),
        encoded.exists(_.contains("thinking")),
        encoded.forall(!_.contains("tool_choice")),
        encoded.forall(!_.contains("strict"))
      )
    },
    test("DeepSeek preserves reasoning_content across a tool-call turn") {
      val response =
        """{"id":"req-1","choices":[{"message":{"content":"","reasoning_content":"private-state","tool_calls":[{"id":"c1","function":{"name":"lookup","arguments":"{\"query\":\"x\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}"""
      for
        decoded <- OpenAIWire.decodeResponse(response, OpenAICompatibility.deepSeek)
        replay = OpenAIWire.encodeRequest(
          ChatRequest(Chunk(decoded.message, AgentMessage.tool("c1", "lookup", ToolResult(Json.Str("ok"))))),
          ProviderPresets.DeepSeekDefaultModel,
          OpenAICompatibility.deepSeek
        )
      yield assertTrue(
        decoded.message.metadata.get("reasoning_content").contains("private-state"),
        replay.exists(_.toString.contains("reasoning_content"))
      )
    },
    test("DeepSeek typed reasoning overrides preset thinking and maps verified effort values") {
      val enabled = OpenAIWire
        .encodeRequest(
          ChatRequest(
            Chunk(AgentMessage.user("question")),
            settings = ModelSettings(reasoningEffort = Some(ReasoningEffort.High))
          ),
          ProviderPresets.DeepSeekDefaultModel,
          OpenAICompatibility.deepSeek,
          ProviderPresets.deepSeek("test-key").defaultOptions
        )
        .map(_.toString)
      val disabled = OpenAIWire
        .encodeRequest(
          ChatRequest(
            Chunk(AgentMessage.user("question")),
            settings = ModelSettings(reasoningEffort = Some(ReasoningEffort.None))
          ),
          ProviderPresets.DeepSeekDefaultModel,
          OpenAICompatibility.deepSeek,
          ProviderPresets.deepSeek("test-key").defaultOptions
        )
        .map(_.toString)
      assertTrue(
        enabled.exists(_.contains("\"thinking\":{\"type\":\"enabled\"}")),
        enabled.exists(_.contains("\"reasoning_effort\":\"high\"")),
        disabled.exists(_.contains("\"thinking\":{\"type\":\"disabled\"}")),
        disabled.forall(!_.contains("reasoning_effort"))
      )
    },
    test("GLM rejects non-auto tool choice before the HTTP request") {
      val request = ChatRequest(
        Chunk(AgentMessage.user("question")),
        Chunk(tool),
        ModelSettings(toolChoice = ToolChoice.Required)
      )
      val encoded =
        OpenAIWire.encodeRequest(request, ProviderPresets.GlmDefaultModel, OpenAICompatibility.glm)
      assertTrue(encoded.left.exists(_.isInstanceOf[AgentError.UnsupportedModelCapability]))
    },
    test("Qwen maps developer role, omits strict schema and supports explicit tool choice") {
      val autoRequest = ChatRequest(
        Chunk(AgentMessage.developer("rule"), AgentMessage.user("question")),
        Chunk(tool)
      )
      val specificRequest =
        autoRequest.copy(settings = ModelSettings(toolChoice = ToolChoice.Specific("lookup")))
      val encoded = OpenAIWire
        .encodeRequest(autoRequest, "qwen-test", OpenAICompatibility.qwen)
        .map(_.toString)
      val specific = OpenAIWire
        .encodeRequest(specificRequest, "qwen-test", OpenAICompatibility.qwen)
        .map(_.toString)
      assertTrue(
        encoded.exists(_.contains("\"role\":\"system\"")),
        encoded.exists(_.contains("\"tool_choice\":\"auto\"")),
        encoded.forall(!_.contains("\"strict\"")),
        specific.exists(_.contains("\"name\":\"lookup\""))
      )
    },
    test("OpenAI keeps strict schema and uses max_completion_tokens") {
      val request = ChatRequest(
        Chunk(AgentMessage.user("question")),
        Chunk(tool),
        ModelSettings(maxOutputTokens = Some(128), reasoningEffort = Some(ReasoningEffort.High))
      )
      val encoded =
        OpenAIWire.encodeRequest(request, "gpt-5.4-mini", OpenAICompatibility.openAI).map(_.toString)
      assertTrue(
        encoded.exists(_.contains("strict")),
        encoded.exists(_.contains("max_completion_tokens")),
        encoded.exists(_.contains("\"reasoning_effort\":\"high\""))
      )
    },
    test("models without a declared effort set reject typed reasoning before HTTP") {
      val request = ChatRequest(
        Chunk(AgentMessage.user("question")),
        settings = ModelSettings(reasoningEffort = Some(ReasoningEffort.High))
      )
      val encoded = OpenAIWire.encodeRequest(
        request,
        ProviderPresets.GlmDefaultModel,
        OpenAICompatibility.glm
      )
      assertTrue(encoded.left.exists(_.isInstanceOf[AgentError.UnsupportedModelCapability]))
    },
    test("per-model Qwen capabilities drive enable_thinking on the wire") {
      val base          = OpenAICompatibility.qwen.descriptor.capabilities
      val compatibility = OpenAICompatibility.qwen.copy(
        descriptor = OpenAICompatibility.qwen.descriptor.copy(
          models = Map(
            "qwen-reasoning" -> base.copy(
              thinking = true,
              reasoningEfforts = Set(ReasoningEffort.None, ReasoningEffort.High)
            )
          )
        )
      )
      val enabled = OpenAIWire
        .encodeRequest(
          ChatRequest(
            Chunk(AgentMessage.user("question")),
            settings = ModelSettings(
              model = Some("qwen-reasoning"),
              reasoningEffort = Some(ReasoningEffort.High)
            )
          ),
          "qwen-default",
          compatibility
        )
        .map(_.toString)
      assertTrue(enabled.exists(_.contains("\"enable_thinking\":true")))
    },
    test("Kimi uses conservative tools and replays reasoning_content for tool continuation") {
      val base          = OpenAICompatibility.kimi.descriptor.capabilities
      val compatibility = OpenAICompatibility.kimi.copy(
        descriptor = OpenAICompatibility.kimi.descriptor.copy(
          models = Map(
            "kimi-test" -> base.copy(
              reasoningEfforts = Set(ReasoningEffort.None, ReasoningEffort.High)
            )
          )
        )
      )
      val response =
        """{"id":"req-k","choices":[{"message":{"content":"","reasoning_content":"continuation-state","tool_calls":[{"id":"c1","function":{"name":"lookup","arguments":"{\"query\":\"x\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}"""
      for
        decoded <- OpenAIWire.decodeResponse(response, compatibility)
        replay = OpenAIWire.encodeRequest(
          ChatRequest(
            Chunk(decoded.message, AgentMessage.tool("c1", "lookup", ToolResult(Json.Str("ok")))),
            Chunk(tool),
            ModelSettings(model = Some("kimi-test"), reasoningEffort = Some(ReasoningEffort.High))
          ),
          "kimi-test",
          compatibility
        )
        required = OpenAIWire.encodeRequest(
          ChatRequest(
            Chunk(AgentMessage.user("question")),
            Chunk(tool),
            ModelSettings(model = Some("kimi-test"), toolChoice = ToolChoice.Required)
          ),
          "kimi-test",
          compatibility
        )
      yield assertTrue(
        decoded.message.metadata.get("reasoning_content").contains("continuation-state"),
        replay.exists(_.toString.contains("reasoning_content")),
        replay.exists(_.toString.contains("\"thinking\":{\"type\":\"enabled\"}")),
        replay.exists(_.toString.contains("\"strict\":false")),
        required.left.exists(_.isInstanceOf[AgentError.UnsupportedModelCapability])
      )
    },
    test("OpenAI encodes vision content parts instead of stringifying image URLs") {
      val request = ChatRequest(
        Chunk(
          AgentMessage(
            MessageRole.User,
            Chunk(
              ContentPart.Text("transcribe"),
              ContentPart.ImageUrl("data:image/jpeg;base64,abc", Some("low"))
            )
          )
        )
      )
      val encoded =
        OpenAIWire.encodeRequest(request, "gpt-5.4-mini", OpenAICompatibility.openAI).map(_.toString)
      assertTrue(
        encoded.exists(_.contains("\"type\":\"image_url\"")),
        encoded.exists(_.contains("data:image/jpeg;base64,abc")),
        encoded.forall(!_.contains("[image:"))
      )
    },
    test("remote ImageUrl and unbound ImageArtifact are rejected before the wire") {
      val remote = ChatRequest(
        Chunk(AgentMessage(MessageRole.User, Chunk(ContentPart.ImageUrl("https://evil.example/x.png"))))
      )
      val unbound = ChatRequest(
        Chunk(
          AgentMessage(
            MessageRole.User,
            Chunk(ContentPart.ImageArtifact("0" * 64, "image/png", 4L))
          )
        )
      )
      val remoteEncoded =
        OpenAIWire.encodeRequest(remote, "gpt-5.4-mini", OpenAICompatibility.openAI)
      val unboundEncoded =
        OpenAIWire.encodeRequest(unbound, "gpt-5.4-mini", OpenAICompatibility.openAI)
      assertTrue(
        remoteEncoded.left.exists {
          case AgentError.PermissionDenied("model.image", _) => true
          case _                                             => false
        },
        unboundEncoded.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
      )
    },
    test("DeepSeek rejects image parts because the profile has no vision") {
      val request = ChatRequest(
        Chunk(
          AgentMessage(
            MessageRole.User,
            Chunk(ContentPart.ImageUrl("data:image/jpeg;base64,abc"))
          )
        )
      )
      val encoded =
        OpenAIWire.encodeRequest(request, ProviderPresets.DeepSeekDefaultModel, OpenAICompatibility.deepSeek)
      assertTrue(encoded.left.exists(_.isInstanceOf[AgentError.UnsupportedModelCapability]))
    }
  )
