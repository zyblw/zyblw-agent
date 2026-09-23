package com.zyblw.agent.rag

import com.zyblw.agent.core.*

/** 切分计数器与 Embedding 声明的 tokenizer 必须是同一个身份。未声明 tokenizer 不能通过。 */
object ChunkEmbeddingAlignment:
  /** 仅 `HashEmbedding` 与 `EmbeddingModel.stub` 使用。生产配置不得把这个 id 当成已对齐的 tokenizer。 */
  val TestTokenizerId: String = "test-hash"

  def require(strategyId: String, capabilities: EmbeddingCapabilities): Either[RetrievalError, Unit] =
    capabilities.tokenizerId match
      case Some(TestTokenizerId) =>
        Right(())
      case Some(id) if strategyId.contains(s"counter=$id") =>
        Right(())
      case Some(id) =>
        Left(
          AgentError.RetrievalFailed(
            s"切分策略未绑定 Embedding tokenizer $id。索引 Profile 必须同时记录 tokenizer、维度和模型，不能默认使用 cl100k。"
          )
        )
      case None =>
        Left(
          AgentError.RetrievalFailed(
            "Embedding 未声明 tokenizerId，不能视为已与切分策略对齐。测试哈希向量必须显式声明 test-hash。"
          )
        )
