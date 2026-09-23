package com.zyblw.agent.rag

import zio.test.*

/** 未声明 tokenizer 不能静默对齐；测试哈希向量只能走显式 `test-hash`。 */
object ChunkEmbeddingAlignmentSpec extends ZIOSpecDefault:
  private val cl100k = "document-structure-v3:counter=cl100k-base"

  def spec = suite("ChunkEmbeddingAlignment")(
    test("未声明 tokenizer 不对齐") {
      val capabilities = EmbeddingDefaults.denseCapabilities(8)
      assertTrue(ChunkEmbeddingAlignment.require(cl100k, capabilities).isLeft)
    },
    test("声明了但切分策略没有该计数器时失败") {
      val capabilities = EmbeddingDefaults.denseCapabilities(8).copy(tokenizerId = Some("o200k-base"))
      assertTrue(ChunkEmbeddingAlignment.require(cl100k, capabilities).isLeft)
    },
    test("声明与切分计数器一致时通过对齐") {
      val capabilities = EmbeddingDefaults.denseCapabilities(8).copy(tokenizerId = Some("cl100k-base"))
      assertTrue(ChunkEmbeddingAlignment.require(cl100k, capabilities).isRight)
    },
    test("test-hash 是显式测试豁免，不要求策略里出现 counter") {
      val capabilities =
        EmbeddingDefaults
          .denseCapabilities(8)
          .copy(tokenizerId = Some(ChunkEmbeddingAlignment.TestTokenizerId))
      assertTrue(
        ChunkEmbeddingAlignment.TestTokenizerId == "test-hash",
        ChunkEmbeddingAlignment.require("sliding-window-v1", capabilities).isRight,
        HashEmbedding(8).capabilities.tokenizerId.contains(ChunkEmbeddingAlignment.TestTokenizerId)
      )
    }
  )
