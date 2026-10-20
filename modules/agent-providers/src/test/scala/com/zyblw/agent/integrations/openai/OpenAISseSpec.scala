package com.zyblw.agent.integrations.openai

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

object OpenAISseSpec extends ZIOSpecDefault:
  private val payload =
    """data: {"id":"resp-1","choices":[{"delta":{"content":"你"},"finish_reason":null}],"usage":null}
      |
      |data: {"id":"resp-1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"echo","arguments":"{\"value\":"}}]},"finish_reason":null}],"usage":null}
      |
      |data: {"id":"resp-1","choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"\"好\"}"}}]},"finish_reason":"tool_calls"}],"usage":null}
      |
      |data: {"id":"resp-1","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":5},"completion_tokens_details":{"reasoning_tokens":3}}}
      |
      |data: [DONE]
      |
      |""".stripMargin

  def spec = suite("OpenAI SSE")(
    test("actual service tier in an early frame survives a later usage-only frame") {
      val stream =
        """data: {"service_tier":"priority","choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

 data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1}}

 data: [DONE]

""".replace(" data:", "data:")
      for
        events <- OpenAISse
          .events(ZStream.fromIterable(stream.getBytes(StandardCharsets.UTF_8)), OpenAICompatibility.openAI)
          .runCollect
        completed = events.collect { case ModelStreamEvent.Completed(r) => r }
      yield assertTrue(
        completed.head.usage.billing.exists(_.dimensions.get("service_tier").contains("priority"))
      )
    },
    test("OpenRouter cache writes and DeepSeek cache hits agree across ordinary and streaming responses") {
      val usage =
        """{"prompt_tokens":1000000,"completion_tokens":1000,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":100000},"prompt_cache_hit_tokens":0}"""
      val response = s"""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":$usage}"""
      val stream   = s"""data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":$usage}

 data: [DONE]

""".replace(" data:", "data:")
      for
        ordinary <- OpenAIWire.decodeResponse(response, OpenAICompatibility.openAI)
        events   <- OpenAISse
          .events(ZStream.fromIterable(stream.getBytes(StandardCharsets.UTF_8)), OpenAICompatibility.openAI)
          .runCollect
        alias <- OpenAIWire.decodeResponse(
          response
            .replace("\"cached_tokens\":0,", "")
            .replace("\"prompt_cache_hit_tokens\":0", "\"prompt_cache_hit_tokens\":200000"),
          OpenAICompatibility.deepSeek
        )
        conflict <- OpenAIWire
          .decodeResponse(
            response.replace("\"prompt_cache_hit_tokens\":0", "\"prompt_cache_hit_tokens\":1"),
            OpenAICompatibility.openAI
          )
          .either
        invalid <- OpenAIWire
          .decodeResponse(
            response.replace("\"cache_write_tokens\":100000", "\"cache_write_tokens\":1000001"),
            OpenAICompatibility.openAI
          )
          .either
        completed = events.collect { case ModelStreamEvent.Completed(r) => r }
        price     = ModelPrice(2, 4, cacheWriteInputPerMillionTokens = Some(4))
      yield assertTrue(
        ordinary.usage.cacheWriteInputTokens == 100000,
        completed.head.usage == ordinary.usage,
        price.rated(ordinary.usage) == Right(BigDecimal("2.204")),
        alias.usage.cachedInputTokens == 200000,
        conflict.isLeft,
        invalid.isLeft
      )
    },
    test("缺失 usage 与明确报告零用量保持不同，流式和非流式一致") {
      val absent =
        """{"id":"r","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
      val zero   = absent.dropRight(1) + """, "usage":{"prompt_tokens":0,"completion_tokens":0}}"""
      val stream = """data: {"id":"r","choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

 data: [DONE]

""".replace(" data:", "data:")
      for
        missing  <- OpenAIWire.decodeResponse(absent, OpenAICompatibility.openAI)
        reported <- OpenAIWire.decodeResponse(zero, OpenAICompatibility.openAI)
        events   <- OpenAISse
          .events(ZStream.fromIterable(stream.getBytes(StandardCharsets.UTF_8)), OpenAICompatibility.openAI)
          .runCollect
        completed = events.collect { case ModelStreamEvent.Completed(value) => value }
      yield assertTrue(
        !missing.usageReported,
        reported.usageReported,
        reported.usage.totalTokens == 0,
        completed.size == 1,
        !completed.head.usageReported
      )
    },
    test("任意网络分块和跨 chunk UTF-8 都能组装文本、工具参数与 usage") {
      val bytes = payload.getBytes(StandardCharsets.UTF_8)
      ZIO
        .foreach(1 to 11) { size =>
          val chunks = bytes.grouped(size).map(array => Chunk.fromArray(array)).toSeq
          OpenAISse.events(ZStream.fromChunks(chunks*), OpenAICompatibility.deepSeek).runCollect
        }
        .map { all =>
          val completed = all.flatMap(_.collect { case ModelStreamEvent.Completed(response) => response })
          assertTrue(
            completed.length == 11,
            completed.forall(_.message.text == "你"),
            completed.forall(_.message.toolCalls.headOption.exists(_.arguments.toJson.contains("好"))),
            completed.forall(
              _.usage
                .copy(billing = None) == TokenUsage(12, 7, cachedInputTokens = 5, reasoningOutputTokens = 3)
            )
          )
        }
    },
    test("空流和 Provider 错误事件显式失败") {
      val empty  = ZStream.fromIterable("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8))
      val failed = ZStream.fromIterable(
        "data: {\"error\":{\"message\":\"rate limited\"}}\n\n".getBytes(StandardCharsets.UTF_8)
      )
      for
        emptyExit <- OpenAISse.events(empty, OpenAICompatibility.openAI).runDrain.exit
        errorExit <- OpenAISse.events(failed, OpenAICompatibility.openAI).runDrain.exit
      yield assertTrue(emptyExit.isFailure, errorExit.isFailure)
    },
    test("网络在 DONE 前断流以及负 usage 都显式失败") {
      val truncated = ZStream.fromIterable(
        "data: {\"id\":\"r\",\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}],\"usage\":null}\n\n"
          .getBytes(StandardCharsets.UTF_8)
      )
      val invalidUsage = ZStream.fromIterable(
        "data: {\"id\":\"r\",\"choices\":[],\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n"
          .getBytes(StandardCharsets.UTF_8)
      )
      for
        truncatedExit <- OpenAISse.events(truncated, OpenAICompatibility.openAI).runDrain.exit
        usageExit     <- OpenAISse.events(invalidUsage, OpenAICompatibility.openAI).runDrain.exit
      yield assertTrue(truncatedExit.isFailure, usageExit.isFailure)
    }
  )
