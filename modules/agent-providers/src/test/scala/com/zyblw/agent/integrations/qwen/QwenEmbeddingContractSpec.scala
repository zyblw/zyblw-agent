package com.zyblw.agent.integrations.qwen

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.http.*
import zio.test.*

object QwenEmbeddingContractSpec extends ZIOSpecDefault:
  def spec = suite("Qwen embedding contract")(
    test("未启用 sparse 时能力矩阵只声明 Dense，且请求 Sparse 会失败") {
      val context = EmbeddingRequestContext(TenantId("t"), EmbeddingPurpose.Query, "qwen-1")
      ZIO
        .serviceWithZIO[Client] { client =>
          val model =
            QwenEmbeddingModel(client, QwenEmbeddingConfig(apiKey = "test-key", model = "text-embedding-v4"))
          val declared =
            assertTrue(
              model.capabilities.outputs == Set(EmbeddingOutputKind.Dense),
              !model.capabilities.reportsUsage
            )
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
            .map(error => declared && assertTrue(error.getMessage.contains("不支持 output")))
        }
        .provide(Client.default)
    }
  )
