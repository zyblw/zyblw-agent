package com.zyblw.agent.model

// 验证 Provider 路由、默认模型选择和未知 Provider 的显式失败行为。

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object RoutedChatModelSpec extends ZIOSpecDefault:
  final private class StubModel(val provider: String) extends ChatModel:
    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      ZIO.succeed(ChatResponse(AgentMessage.assistant(provider), FinishReason.Stop))

  def spec = suite("RoutedChatModel")(
    test("selects the requested provider and otherwise uses the default") {
      for
        router   <- RoutedChatModel.make("deepseek", List(StubModel("deepseek"), StubModel("glm")))
        default  <- router.complete(ChatRequest(Chunk(AgentMessage.user("hello"))))
        selected <- router.complete(
          ChatRequest(
            Chunk(AgentMessage.user("hello")),
            settings = ModelSettings(provider = Some("glm"))
          )
        )
      yield assertTrue(default.message.text == "deepseek", selected.message.text == "glm")
    },
    test("fails explicitly for an unknown provider") {
      for
        router <- RoutedChatModel.make("deepseek", List(StubModel("deepseek")))
        exit   <- router
          .complete(
            ChatRequest(
              Chunk(AgentMessage.user("hello")),
              settings = ModelSettings(provider = Some("unknown"))
            )
          )
          .exit
      yield exit match
        case Exit.Failure(cause) =>
          assertTrue(cause.failureOption.contains(AgentError.ProviderNotFound("unknown")))
        case Exit.Success(_) => assertTrue(false)
    },
    test("rejects image parts when the selected model does not declare vision") {
      for
        router <- RoutedChatModel.make("deepseek", List(StubModel("deepseek")))
        exit   <- router
          .complete(
            ChatRequest(
              Chunk(
                AgentMessage(
                  MessageRole.User,
                  Chunk(ContentPart.ImageUrl("https://example.invalid/page.jpg"))
                )
              )
            )
          )
          .exit
      yield exit match
        case Exit.Failure(cause) =>
          assertTrue(
            cause.failureOption.exists {
              case AgentError.UnsupportedModelCapability(_, capability, _) => capability == "vision"
              case _                                                       => false
            }
          )
        case Exit.Success(_) => assertTrue(false)
    }
  )
