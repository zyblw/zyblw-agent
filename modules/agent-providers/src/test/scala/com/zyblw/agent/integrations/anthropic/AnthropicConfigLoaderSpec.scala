package com.zyblw.agent.integrations.anthropic

import zio.*
import zio.test.*

/** 验证 Anthropic 原生 Provider 的 Secret、超时和协议版本均由 ZIO Config 驱动。 */
object AnthropicConfigLoaderSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("Anthropic config loader")(
    test("加载完整配置且日志摘要不包含 API Key") {
      val values = Map(
        "ANTHROPIC_API_KEY"         -> "anthropic-secret",
        "ANTHROPIC_MODEL"           -> "claude-test",
        "ANTHROPIC_BASE_URL"        -> "https://anthropic.example/v1",
        "ANTHROPIC_VERSION"         -> "2026-01-01",
        "ANTHROPIC_MAX_TOKENS"      -> "2048",
        "ANTHROPIC_REQUEST_TIMEOUT" -> "45s"
      )
      AnthropicMessagesConfig.fromEnvironment.provide(provider(values)).map { config =>
        assertTrue(
          config.defaultModel == "claude-test",
          config.anthropicVersion == "2026-01-01",
          config.defaultMaxTokens == 2048,
          config.requestTimeout == 45.seconds,
          config.messagesUrl == "https://anthropic.example/v1/messages",
          !config.toString.contains("anthropic-secret")
        )
      }
    },
    test("非法 max tokens 在启动阶段以 typed failure 返回且不泄漏密钥") {
      val secret = "anthropic-must-stay-secret"
      val values = Map(
        "ANTHROPIC_API_KEY"    -> secret,
        "ANTHROPIC_MODEL"      -> "claude-test",
        "ANTHROPIC_MAX_TOKENS" -> "0"
      )
      AnthropicMessagesConfig.fromEnvironment
        .provide(provider(values))
        .exit
        .map(exit => assertTrue(exit.isFailure, !exit.toString.contains(secret)))
    }
  )

  /** 创建不触碰真实环境变量的测试配置源。 */
  private def provider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))
