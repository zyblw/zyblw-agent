package com.zyblw.agent.integrations.openai

// 以纯编解码方式验证 OpenAI、DeepSeek、GLM 的字段兼容矩阵，不依赖真实 API Key 和网络。

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
    test("OpenAI keeps strict schema and uses max_completion_tokens") {
      val request = ChatRequest(
        Chunk(AgentMessage.user("question")),
        Chunk(tool),
        ModelSettings(maxOutputTokens = Some(128))
      )
      val encoded =
        OpenAIWire.encodeRequest(request, "gpt-5.4-mini", OpenAICompatibility.openAI).map(_.toString)
      assertTrue(
        encoded.exists(_.contains("strict")),
        encoded.exists(_.contains("max_completion_tokens"))
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
