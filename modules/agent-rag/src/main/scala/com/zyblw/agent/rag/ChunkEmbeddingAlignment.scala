package com.zyblw.agent.rag

import com.zyblw.agent.core.*

/** 切分计数器与 Embedding 声明的 tokenizer 必须是同一个身份。未声明 tokenizer 时不假装已经对齐。 */
object ChunkEmbeddingAlignment:
  def require(strategyId: String, capabilities: EmbeddingCapabilities): Either[RetrievalError, Unit] =
    capabilities.tokenizerId match
      case None =>
        Right(())
      case Some(id) if strategyId.contains(s"counter=$id") =>
        Right(())
      case Some(id) =>
        Left(
          AgentError.RetrievalFailed(
            s"切分策略未绑定 Embedding tokenizer $id。索引 Profile 必须同时记录 tokenizer、维度和模型，不能默认使用 cl100k。"
          )
        )
