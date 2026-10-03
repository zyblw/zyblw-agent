package com.zyblw.agent.integrations

import zio.*
import zio.http.*
import zio.test.*

object ModelDiscoverySpec extends ZIOSpecDefault:
  private val openRouterBody =
    """{"data":[
      |{"id":"deepseek/deepseek-chat","name":"DeepSeek V3","context_length":163840,
      | "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
      | "pricing":{"prompt":"0.00000027","completion":"0.0000011","input_cache_read":"0.00000007"},
      | "top_provider":{"max_completion_tokens":8192},
      | "supported_parameters":["tools","tool_choice","reasoning"]},
      |{"id":"openai/gpt-4o","name":"GPT-4o","context_length":128000,
      | "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
      | "pricing":{"prompt":"0.0000025","completion":"0.00001"},"supported_parameters":["tools"]},
      |{"id":"openrouter/auto","name":"Auto","pricing":{"prompt":"-1","completion":"-1"}},
      |{"id":"qwen/qwen3-embedding-8b","architecture":{"output_modalities":["embeddings"]}},
      |{"name":"missing id is skipped"}
      |]}""".stripMargin

  def spec = suite("ModelDiscovery")(
    test("OpenRouter 目录归一化为每百万 token USD、能力与用途") {
      val models = ModelDiscovery.parseOpenRouter(openRouterBody).toOption.get
      val byId   = models.map(model => model.id -> model).toMap
      val chat   = byId("deepseek/deepseek-chat")
      assertTrue(
        models.length == 4,
        chat.inputPerMillionUsd.contains(BigDecimal("0.27")),
        chat.outputPerMillionUsd.contains(BigDecimal("1.1")),
        chat.cachedInputPerMillionUsd.contains(BigDecimal("0.07")),
        chat.toolCalls,
        chat.reasoning,
        chat.capabilities.maxInputTokens.contains(163840L),
        chat.capabilities.maxOutputTokens.contains(8192L),
        byId("openai/gpt-4o").vision,
        !chat.vision,
        byId("openrouter/auto").inputPerMillionUsd.isEmpty,
        byId("qwen/qwen3-embedding-8b").kind == DiscoveredModelKind.Embedding,
        chat.kind == DiscoveredModelKind.Chat
      )
    },
    test("OpenAI-compatible /models 只信任 id,用途按名称推断,结构错误给出稳定消息") {
      val parsed = ModelDiscovery.parseOpenAIModels(
        """{"object":"list","data":[{"id":"gte-rerank-v2"},{"id":"text-embedding-v4"},{"id":"qwen-plus"},{"id":"qwen-plus"}]}"""
      )
      val invalid = ModelDiscovery.parseOpenAIModels("""{"models":[]}""")
      assertTrue(
        parsed.toOption.get.map(m => m.id -> m.kind) == Chunk(
          "gte-rerank-v2"     -> DiscoveredModelKind.Rerank,
          "qwen-plus"         -> DiscoveredModelKind.Chat,
          "text-embedding-v4" -> DiscoveredModelKind.Embedding
        ),
        invalid == Left("模型目录缺少 data 数组")
      )
    },
    test("通过 HTTP 读取网关目录并携带 Bearer 与额外请求头;401 归类为凭据错误") {
      val routes = Routes(
        Method.GET / "v1" / "models" -> handler { (request: Request) =>
          val ok = request.headers.get("Authorization").contains("Bearer good") &&
            request.headers.get("X-Title").contains("zyblw")
          if ok then Response.json("""{"data":[{"id":"m1"}]}""")
          else Response.status(Status.Unauthorized)
        }
      )
      for
        _      <- TestServer.addRoutes(routes)
        port   <- ZIO.serviceWithZIO[Server](_.port)
        client <- ZIO.service[Client]
        base = s"http://127.0.0.1:$port/v1"
        ok     <- ModelDiscovery.openAICompatible(client, base, "good", Map("X-Title" -> "zyblw"))
        denied <- ModelDiscovery.openAICompatible(client, base, "bad").either
      yield assertTrue(
        ok.map(_.id) == Chunk("m1"),
        denied.left.exists(_.message.contains("凭据"))
      )
    }.provide(Client.default, TestServer.default)
  )
