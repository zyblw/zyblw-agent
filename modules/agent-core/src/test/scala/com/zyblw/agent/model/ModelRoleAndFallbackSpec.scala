package com.zyblw.agent.model

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object ModelRoleAndFallbackSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelRoleAndFallback")(
    test("未声明角色 fail-closed，已写 provider 的 Agent 不被目录覆盖") {
      val catalog = ModelRoleCatalog(
        Map("planner" -> ModelRoleBinding(ModelRole.Planner, "relay", "deepseek-v4"))
      )
      val blank    = ModelSettings(role = Some(ModelRole.Planner))
      val explicit =
        ModelSettings(provider = Some("openai"), model = Some("gpt-4.1"), role = Some(ModelRole.Planner))
      for
        missing <- catalog.applyTo(ModelSettings(role = Some(ModelRole("extraction")))).either
        planned <- catalog.applyTo(blank)
        kept    <- catalog.applyTo(explicit)
      yield assertTrue(
        missing.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration]),
        planned.provider.contains("relay"),
        planned.model.contains("deepseek-v4"),
        planned.metadata.get("model-role").contains("planner"),
        kept.provider.contains("openai"),
        kept.model.contains("gpt-4.1")
      )
    },
    test("降级链只对可重试错误切换，能力不匹配不换候选") {
      for
        primary  <- ScriptedFailingModel.make("primary", retryable = true)
        backup   <- SucceedingModel.make("backup", "ok")
        fallback <- FallbackChatModel.make("primary", List(primary, backup))
        ok       <- fallback.complete(ChatRequest(Chunk(AgentMessage.user("hi"))))
        vision   <- FallbackChatModel
          .make("primary", List(primary, backup))
          .flatMap(
            _.complete(
              ChatRequest(
                Chunk(
                  AgentMessage(
                    MessageRole.User,
                    Chunk(ContentPart.Text("see"), ContentPart.ImageUrl("https://example/x.png"))
                  )
                ),
                settings = ModelSettings(provider = Some("primary"))
              )
            ).either
          )
      yield assertTrue(
        ok.message.text == "ok",
        vision.left.exists(_.isInstanceOf[AgentError.UnsupportedModelCapability])
      )
    }
  )

/** 只用于降级链测试的失败模型。 */
final class ScriptedFailingModel private (val provider: String, retryable: Boolean) extends ChatModel:
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    ZIO.fail(AgentError.ModelFailure(provider, "synthetic outage", retryable))

object ScriptedFailingModel:
  def make(provider: String, retryable: Boolean): UIO[ScriptedFailingModel] =
    ZIO.succeed(ScriptedFailingModel(provider, retryable))

final class SucceedingModel private (val provider: String, text: String) extends ChatModel:
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    ZIO.succeed(ChatResponse(AgentMessage.assistant(text), FinishReason.Stop, TokenUsage(1, 1, 1)))

object SucceedingModel:
  def make(provider: String, text: String): UIO[SucceedingModel] =
    ZIO.succeed(SucceedingModel(provider, text))
