package com.zyblw.agent.integrations.openai

// 以纯编解码方式验证 OpenAI、DeepSeek、GLM、Qwen、Kimi 的字段兼容矩阵，不依赖真实 API Key 和网络。

import com.zyblw.agent.core.*
import com.zyblw.agent.model.ModelRequirement
import zio.*
import zio.json.*
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
    test("generic relay keeps reasoning and per-call Gemini opaque metadata across ordinary tool turns") {
      val relay   = OpenAICompatibility.relay("relay")
      val payload =
        """{"choices":[{"message":{"content":"","reasoning_content":"opaque-reasoning","tool_calls":[{"id":"call-google","function":{"name":"lookup","arguments":"{}"},"extra_content":{"google":{"thought_signature":"opaque-signature"}}}]},"finish_reason":"tool_calls"}]}"""
      for
        decoded <- OpenAIWire.decodeResponse(payload, relay)
        encoded = OpenAIWire.encodeRequest(
          ChatRequest(
            Chunk(decoded.message, AgentMessage.tool("call-google", "lookup", ToolResult(Json.Obj())))
          ),
          "gemini",
          relay
        )
      yield assertTrue(
        encoded.exists(_.toJson.contains("\"reasoning_content\":\"opaque-reasoning\"")),
        encoded.exists(_.toJson.contains("\"thought_signature\":\"opaque-signature\""))
      )
    },
    test("missing array items fail locally and strict is enabled only for closed complete schemas") {
      val malformed = ToolDefinition("lookup", "lookup", Json.Obj("type" -> Json.Str("array")))
      val optional  = ToolDefinition(
        "lookup",
        "lookup",
        Json.Obj(
          "type"       -> Json.Str("object"),
          "properties" -> Json.Obj("q" -> Json.Obj("type" -> Json.Str("string")))
        )
      )
      val closed = optional.copy(inputSchema =
        Json.Obj(
          optional.inputSchema.fields ++ Chunk(
            "required"             -> Json.Arr(Json.Str("q")),
            "additionalProperties" -> Json.Bool(false)
          )
        )
      )
      def encode(value: ToolDefinition) = OpenAIWire
        .encodeRequest(
          ChatRequest(Chunk(AgentMessage.user("query")), Chunk(value)),
          "model",
          OpenAICompatibility.openAI
        )
        .map(_.toJson)
      assertTrue(
        encode(malformed).isLeft,
        encode(optional).exists(_.contains("\"strict\":false")),
        encode(closed).exists(_.contains("\"strict\":true"))
      )
    },
    test("hard strict is rejected when the selected protocol cannot send that guarantee") {
      val closed = ToolDefinition(
        "lookup",
        "lookup",
        Json.Obj(
          "type"                 -> Json.Str("object"),
          "properties"           -> Json.Obj(),
          "required"             -> Json.Arr(),
          "additionalProperties" -> Json.Bool(false)
        )
      )
      val request = ChatRequest(
        Chunk(AgentMessage.user("query")),
        Chunk(closed),
        ModelSettings(requirement = Some(ModelRequirement(strictToolSchema = true)))
      )
      val unsupported =
        Chunk(OpenAICompatibility.relay("relay"), OpenAICompatibility.qwen, OpenAICompatibility.deepSeek)
          .map(profile => OpenAIWire.encodeRequest(request, "model", profile))
      assertTrue(
        OpenAIWire.encodeRequest(request, "model", OpenAICompatibility.openAI).isRight,
        OpenAIResponsesWire
          .encodeRequest(request, OpenAIResponsesConfig("https://example.test/v1", "secret", "model"), false)
          .isRight,
        unsupported.forall(_.left.toOption.exists(_.isInstanceOf[AgentError.UnsupportedModelCapability]))
      )
    },
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
    test("generic relay maps developer messages to the portable system role") {
      val request = ChatRequest(
        Chunk(AgentMessage.developer("must cite sources"), AgentMessage.user("question"))
      )
      val encoded = OpenAIWire
        .encodeRequest(request, "relay-model", OpenAICompatibility.relay("relay"))
        .map(_.toString)
      assertTrue(
        encoded.exists(_.contains("\"role\":\"system\"")),
        encoded.forall(!_.contains("\"role\":\"developer\"")),
        encoded.forall(!_.contains("reasoning_effort"))
      )
    },
    test(
      "AIHubMix maps developer to system, uses max_tokens, omits strict schema and projects standard reasoning_effort"
    ) {
      val request = ChatRequest(
        Chunk(AgentMessage.developer("system prompt"), AgentMessage.user("question")),
        Chunk(tool),
        ModelSettings(
          maxOutputTokens = Some(2048),
          reasoningEffort = Some(ReasoningEffort.Medium),
          toolChoice = ToolChoice.Specific("lookup")
        )
      )
      val encoded = OpenAIWire
        .encodeRequest(request, "gemini-2.5-flash", OpenAICompatibility.aihubmix())
        .map(_.toString)
      val qwenNone = OpenAIWire
        .encodeRequest(
          ChatRequest(
            Chunk(AgentMessage.user("q")),
            settings = ModelSettings(reasoningEffort = Some(ReasoningEffort.None))
          ),
          "qwen3.8-flash",
          OpenAICompatibility.aihubmix()
        )
        .map(_.toString)
      val deepSeekNone = OpenAIWire
        .encodeRequest(
          ChatRequest(
            Chunk(AgentMessage.user("q")),
            settings = ModelSettings(reasoningEffort = Some(ReasoningEffort.None))
          ),
          "deepseek-v4.1-flash",
          OpenAICompatibility.aihubmix()
        )
        .map(_.toString)
      val gemini3None = OpenAIWire
        .encodeRequest(
          ChatRequest(
            Chunk(AgentMessage.user("q")),
            settings = ModelSettings(reasoningEffort = Some(ReasoningEffort.None))
          ),
          "gemini-3.8-flash",
          OpenAICompatibility.aihubmix()
        )
        .map(_.toString)
      val claudeNone = OpenAIWire
        .encodeRequest(
          ChatRequest(
            Chunk(AgentMessage.user("q")),
            settings = ModelSettings(reasoningEffort = Some(ReasoningEffort.None))
          ),
          "claude-sonnet-5-5",
          OpenAICompatibility.aihubmix()
        )
        .map(_.toString)
      val defaultConfig = OpenAICompatibleConfig(
        baseUrl = "https://aihubmix.com/v1",
        apiKey = "sk-test",
        defaultModel = "gemini-2.5-flash",
        compatibility = OpenAICompatibility.aihubmix()
      )
      val customConfig = defaultConfig.copy(extraHeaders = Map("APP-Code" -> "CUSTOM99"))
      assertTrue(
        encoded.exists(_.contains("\"role\":\"system\"")),
        encoded.forall(!_.contains("\"role\":\"developer\"")),
        encoded.exists(_.contains("\"max_tokens\":2048")),
        encoded.forall(!_.contains("max_completion_tokens")),
        encoded.forall(!_.contains("\"strict\"")),
        encoded.exists(_.contains("\"reasoning_effort\":\"medium\"")),
        encoded.exists(_.contains("\"name\":\"lookup\"")),
        qwenNone.exists(_.contains("\"enable_thinking\":false")),
        deepSeekNone.exists(_.contains("\"thinking\":{\"type\":\"disabled\"}")),
        gemini3None.exists(_.contains("\"reasoning_effort\":\"low\"")),
        claudeNone.forall(!_.contains("reasoning_effort")),
        defaultConfig.effectiveExtraHeaders.get("APP-Code").contains("PXLD0553"),
        customConfig.effectiveExtraHeaders.get("APP-Code").contains("CUSTOM99")
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
