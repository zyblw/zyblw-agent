package com.zyblw.agent.integrations.openai

import zio.*
import zio.test.*

/** 验证 OpenAI、DeepSeek、GLM 配置都经过可替换 ConfigProvider，并确保失败文本不会泄漏 Secret。 */
object ProviderConfigLoaderSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("OpenAI provider config loader")(
    test("OpenAI-compatible 与 Responses 共享部署键但保持独立协议配置") {
      val values = Map(
        "OPENAI_API_KEY"      -> "test-secret",
        "OPENAI_MODEL"        -> "gpt-test",
        "OPENAI_BASE_URL"     -> "https://gateway.example/v1",
        "OPENAI_ORGANIZATION" -> "org-test",
        "OPENAI_PROJECT"      -> "project-test"
      )
      (for
        compatible <- OpenAICompatibleConfig.fromEnvironment
        responses  <- OpenAIResponsesConfig.fromEnvironment
      yield assertTrue(
        compatible.defaultModel == "gpt-test",
        compatible.organization.contains("org-test"),
        compatible.chatCompletionsUrl == "https://gateway.example/v1/chat/completions",
        responses.project.contains("project-test"),
        responses.responsesUrl == "https://gateway.example/v1/responses",
        !compatible.toString.contains("test-secret"),
        !responses.toString.contains("test-secret")
      )).provide(provider(values))
    },
    test("DeepSeek 和 GLM 使用各自密钥并保留安全默认模型") {
      val values = Map(
        "DEEPSEEK_API_KEY" -> "deepseek-secret",
        "GLM_API_KEY"      -> "glm-secret"
      )
      (for
        deepSeek <- ProviderPresets.deepSeekFromEnvironment
        glm      <- ProviderPresets.glmFromEnvironment
      yield assertTrue(
        deepSeek.defaultModel == ProviderPresets.DeepSeekDefaultModel,
        deepSeek.compatibility.descriptor.id == "deepseek",
        glm.defaultModel == ProviderPresets.GlmDefaultModel,
        glm.compatibility.descriptor.id == "glm",
        !deepSeek.toString.contains("deepseek-secret"),
        !glm.toString.contains("glm-secret")
      )).provide(provider(values))
    },
    test("构造失败信息不包含 API Key") {
      val secret = "must-not-appear-in-error"
      val values = Map("OPENAI_API_KEY" -> secret, "OPENAI_MODEL" -> "")
      OpenAIResponsesConfig.fromEnvironment.provide(provider(values)).exit.map { exit =>
        val rendered = exit.toString
        assertTrue(exit.isFailure, !rendered.contains(secret))
      }
    }
  )

  /** 测试级 ConfigProvider 通过 FiberRef 隔离，不修改进程环境变量。 */
  private def provider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))
