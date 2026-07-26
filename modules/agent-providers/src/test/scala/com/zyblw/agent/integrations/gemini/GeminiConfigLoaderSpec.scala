package com.zyblw.agent.integrations.gemini

import zio.*
import zio.test.*

/** 验证 Gemini 原生 Provider 配置的可替换来源、启动校验和 Secret 脱敏。 */
object GeminiConfigLoaderSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("Gemini config loader")(
    test("加载 endpoint、模型和超时且摘要不包含 API Key") {
      val values = Map(
        "GEMINI_API_KEY"         -> "gemini-secret",
        "GEMINI_MODEL"           -> "gemini-test",
        "GEMINI_BASE_URL"        -> "https://gemini.example/v1",
        "GEMINI_REQUEST_TIMEOUT" -> "33s"
      )
      GeminiInteractionsConfig.fromEnvironment.provide(provider(values)).map { config =>
        assertTrue(
          config.defaultModel == "gemini-test",
          config.requestTimeout == 33.seconds,
          config.interactionsUrl == "https://gemini.example/v1/interactions",
          !config.toString.contains("gemini-secret")
        )
      }
    },
    test("空模型在启动阶段失败且错误不包含 API Key") {
      val secret = "gemini-must-stay-secret"
      val values = Map("GEMINI_API_KEY" -> secret, "GEMINI_MODEL" -> "")
      GeminiInteractionsConfig.fromEnvironment
        .provide(provider(values))
        .exit
        .map(exit => assertTrue(exit.isFailure, !exit.toString.contains(secret)))
    }
  )

  /** 创建测试专用的 ConfigProvider，不依赖机器上的真实 GEMINI_* 变量。 */
  private def provider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))
