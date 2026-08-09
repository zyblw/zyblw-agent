package com.zyblw.agent.integrations.openai

import zio.*
import zio.test.*

/** 验证 Embedding 配置由 ZIO Config 驱动,且必填项缺失时在装配期失败而不是静默取默认值。 */
object EmbeddingConfigLoaderSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("Embedding config loader")(
    test("只提供必填项即可加载,其余取安全默认值") {
      val values = Map(
        "EMBEDDING_API_KEY"   -> "embedding-secret",
        "EMBEDDING_MODEL"     -> "embed-test",
        "EMBEDDING_DIMENSION" -> "1024"
      )
      OpenAICompatibleEmbeddingConfig.fromEnvironment.provide(provider(values)).map { config =>
        assertTrue(
          config.providerId == OpenAICompatibleEmbeddingConfig.DefaultProviderId,
          config.model == "embed-test",
          config.dimension == 1024,
          config.sendDimensions,
          config.maxBatchSize == 128,
          config.requestTimeout == 60.seconds,
          config.embeddingsUrl == "https://api.openai.com/v1/embeddings",
          !config.toString.contains("embedding-secret")
        )
      }
    },
    test("全部键均可被部署覆盖") {
      val values = Map(
        "EMBEDDING_PROVIDER_ID"     -> "glm-embeddings",
        "EMBEDDING_BASE_URL"        -> "https://embeddings.example/v4",
        "EMBEDDING_API_KEY"         -> "embedding-secret",
        "EMBEDDING_MODEL"           -> "embedding-3",
        "EMBEDDING_DIMENSION"       -> "1024",
        "EMBEDDING_SEND_DIMENSIONS" -> "false",
        "EMBEDDING_MAX_BATCH_SIZE"  -> "16",
        "EMBEDDING_REQUEST_TIMEOUT" -> "5s"
      )
      OpenAICompatibleEmbeddingConfig.fromEnvironment.provide(provider(values)).map { config =>
        assertTrue(
          config.providerId == "glm-embeddings",
          config.dimension == 1024,
          !config.sendDimensions,
          config.maxBatchSize == 16,
          config.requestTimeout == 5.seconds,
          config.embeddingsUrl == "https://embeddings.example/v4/embeddings"
        )
      }
    },
    test("缺少维度或维度非法时失败,错误文本不含 API Key") {
      val secret  = "embedding-must-stay-secret"
      val missing = Map("EMBEDDING_API_KEY" -> secret, "EMBEDDING_MODEL" -> "embed-test")
      val invalid = missing + ("EMBEDDING_DIMENSION" -> "0")
      for
        missingExit <- OpenAICompatibleEmbeddingConfig.fromEnvironment.provide(provider(missing)).exit
        invalidExit <- OpenAICompatibleEmbeddingConfig.fromEnvironment.provide(provider(invalid)).exit
      yield assertTrue(
        missingExit.isFailure,
        invalidExit.isFailure,
        !missingExit.toString.contains(secret),
        !invalidExit.toString.contains(secret)
      )
    }
  )

  /** 测试级 ConfigProvider 通过 FiberRef 隔离,不修改进程环境变量。 */
  private def provider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))
