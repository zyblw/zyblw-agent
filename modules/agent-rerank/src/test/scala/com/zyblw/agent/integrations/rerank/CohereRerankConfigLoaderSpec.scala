package com.zyblw.agent.integrations.rerank

import zio.*
import zio.test.*

/** 验证 Cohere Rerank 配置由 ZIO Config 驱动,且失败文本不泄漏 API Key。 */
object CohereRerankConfigLoaderSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("Cohere rerank config loader")(
    test("只提供 API Key 即可加载官方 SaaS 默认值") {
      CohereRerankConfig.fromEnvironment
        .provide(provider(Map("COHERE_API_KEY" -> "cohere-secret")))
        .map { config =>
          assertTrue(
            config.model == CohereRerankConfig.DefaultModel,
            config.maxCandidates == 100,
            config.requestTimeout == 10.seconds,
            config.maxAttempts == 3,
            config.rerankUrl == "https://api.cohere.com/v2/rerank",
            // 明文 HTTP 不可由配置打开;凭据只能走 TLS 外发。
            !config.allowInsecureHttp,
            !config.toString.contains("cohere-secret")
          )
        }
    },
    test("预算项可被部署覆盖") {
      val values = Map(
        "COHERE_API_KEY"                -> "cohere-secret",
        "COHERE_BASE_URL"               -> "https://rerank.example",
        "COHERE_RERANK_MODEL"           -> "rerank-test",
        "COHERE_RERANK_MAX_CANDIDATES"  -> "25",
        "COHERE_RERANK_REQUEST_TIMEOUT" -> "3s",
        "COHERE_RERANK_MAX_ATTEMPTS"    -> "1"
      )
      CohereRerankConfig.fromEnvironment.provide(provider(values)).map { config =>
        assertTrue(
          config.model == "rerank-test",
          config.maxCandidates == 25,
          config.requestTimeout == 3.seconds,
          config.maxAttempts == 1,
          config.rerankUrl == "https://rerank.example/v2/rerank"
        )
      }
    },
    test("缺少 API Key 或候选上限越界时失败,错误文本不含 API Key") {
      val secret     = "cohere-must-stay-secret"
      val missingKey = Map("COHERE_RERANK_MODEL" -> "rerank-test")
      val outOfRange = Map("COHERE_API_KEY" -> secret, "COHERE_RERANK_MAX_CANDIDATES" -> "5000")
      for
        missingExit <- CohereRerankConfig.fromEnvironment.provide(provider(missingKey)).exit
        rangeExit   <- CohereRerankConfig.fromEnvironment.provide(provider(outOfRange)).exit
      yield assertTrue(
        missingExit.isFailure,
        rangeExit.isFailure,
        !rangeExit.toString.contains(secret)
      )
    }
  )

  /** 测试级 ConfigProvider 通过 FiberRef 隔离,不修改进程环境变量。 */
  private def provider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))
