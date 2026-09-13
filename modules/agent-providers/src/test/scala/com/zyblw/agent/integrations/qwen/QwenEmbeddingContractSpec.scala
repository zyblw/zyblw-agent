package com.zyblw.agent.integrations.qwen

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.http.*
import zio.test.*

object QwenEmbeddingContractSpec extends ZIOSpecDefault:
  def spec = suite("Qwen embedding contract")(
    test("未启用 sparse 时拒绝 Sparse / DenseAndSparse 输出") {
      val context = EmbeddingRequestContext(TenantId("t"), EmbeddingPurpose.Query, "qwen-1")
      ZIO
        .serviceWithZIO[Client] { client =>
          val model =
            QwenEmbeddingModel(client, QwenEmbeddingConfig(apiKey = "test-key", model = "text-embedding-v4"))
          model
            .embed(
              EmbeddingRequest(
                Chunk("桂枝汤"),
                EmbeddingInputRole.Query,
                EmbeddingOutputKind.Sparse,
                context = context
              )
            )
            .flip
            .map(error => assertTrue(error.getMessage.contains("未启用 Qwen sparse")))
        }
        .provide(Client.default)
    }
  )
