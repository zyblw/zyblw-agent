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
            completed.forall(_.usage == TokenUsage(12, 7, cachedInputTokens = 5, reasoningOutputTokens = 3))
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
