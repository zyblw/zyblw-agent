package com.zyblw.agent.evals

import com.zyblw.agent.rag.*
import zio.*
import zio.test.*

/** 书籍语料在每种再识别模式下都必须通过排名、引用与越权门禁。 */
object BookCorpusRagEvalSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("BookCorpusRagEval")(
    test("公开语料覆盖全部查询模式，且越权 decoy 永不进入 gold") {
      val modes = BookCorpusRagEval.cases.map(_.mode).toSet
      assertTrue(
        modes == Set(
          RetrievalMode.Hybrid,
          RetrievalMode.VectorOnly,
          RetrievalMode.LexicalOnly,
          RetrievalMode.Phrase
        ),
        BookCorpusRagEval.cases.forall(!_.expectedRelevantChunkIds.contains(BookCorpusRagEval.decoy.id)),
        BookCorpusRagEval.cases.forall(_.forbiddenChunkIds.contains(BookCorpusRagEval.decoy.id))
      )
    },
    test("内存 Retriever 对每种模式召回正确片段并拒绝跨租户 decoy") {
      (for
        store      <- ZIO.service[VectorStore]
        embeddings <- ZIO.service[EmbeddingService]
        retriever  <- ZIO.service[Retriever]
        vectors    <- embeddings.embed(BookCorpusRagEval.fixtures.map(_.text))
        _          <- store.upsert(BookCorpusRagEval.fixtures.zip(vectors).map(IndexedChunk.apply))
        report     <- RagEvalRunner(4).runRetriever(retriever, BookCorpusRagEval.cases)
      yield assertTrue(report.passed, report.reports.length == BookCorpusRagEval.cases.length))
        .provide(
          InMemoryVectorStore.layer,
          ZLayer.succeed[EmbeddingService](HashEmbedding(32)),
          Reranker.identity,
          DefaultRetriever.layer
        )
    }
  )
