package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 默认 `Retriever.fetch` 必须按块身份配对引用，不能在过滤后按长度截取。 */
object RetrieverFetchFilterSpec extends ZIOSpecDefault:
  private val tenant = TenantId("fetch-filter")
  private val scope  = RetrievalScope(tenant, Set("read"))

  def spec = suite("Retriever.fetch filter")(
    test("过滤掉第一条命中后，剩余引用属于剩余命中") {
      val first = RetrievalHit(DocumentChunk("first", "doc", "第一条", "book://first", tenant, Set("read")), 0.9)
      val second =
        RetrievalHit(DocumentChunk("second", "doc", "第二条", "book://second", tenant, Set("read")), 0.4)
      val result = RetrievalResult(
        Chunk(first, second),
        Chunk(
          Citation("cite-first", first.chunk.sourceUri, "第一条", 0.9, chunkId = Some("first")),
          Citation("cite-second", second.chunk.sourceUri, "第二条", 0.4, chunkId = Some("second"))
        ),
        RetrievalEvidence(
          RetrievalEvidenceStatus.Supported,
          candidateCount = 4,
          acceptedCount = 2,
          topAcceptedScore = Some(0.9)
        )
      )
      val retriever = new Retriever:
        def retrieve(request: RetrievalRequest): IO[RetrievalError, RetrievalResult] =
          val _ = request
          ZIO.fail(AgentError.RetrievalFailed("unused"))
        override def fetch(
            chunkIds: Set[String],
            scope: RetrievalScope
        ): IO[RetrievalError, RetrievalResult] =
          val _ = (chunkIds, scope)
          ZIO.succeed(result)
      for filtered <- retriever
          .fetch(Set("first", "second"), scope, RetrievalFilter(chunkIds = Set("second")))
      yield assertTrue(
        filtered.hits.map(_.chunk.id) == Chunk("second"),
        filtered.citations.map(_.id) == Chunk("cite-second"),
        filtered.citations.flatMap(_.chunkId) == Chunk("second"),
        filtered.evidence.acceptedCount == 1,
        filtered.evidence.candidateCount == 4,
        filtered.evidence.topAcceptedScore.contains(0.4),
        filtered.evidence.status == RetrievalEvidenceStatus.Supported
      )
    },
    test("DefaultRetriever 的覆盖路径按过滤后的块生成引用") {
      val vector = Embedding(Chunk(1.0f, 0.0f))
      for
        store <- InMemoryKnowledgeIndexStore.make
        _     <- store.upsert(
          Chunk(
            IndexedChunk(DocumentChunk("first", "doc", "甲", "book://a", tenant, Set("read")), vector),
            IndexedChunk(DocumentChunk("second", "doc", "乙", "book://b", tenant, Set("read")), vector)
          )
        )
        retriever = DefaultRetriever(
          HashEmbedding(2),
          store,
          new Reranker:
            def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): UIO[Chunk[RetrievalHit]] =
              val _ = query
              ZIO.succeed(hits.take(limit))
        )
        filtered <- retriever.fetch(Set("first", "second"), scope, RetrievalFilter(chunkIds = Set("second")))
      yield assertTrue(
        filtered.hits.map(_.chunk.id) == Chunk("second"),
        filtered.citations.flatMap(_.chunkId) == Chunk("second"),
        filtered.citations.map(_.excerpt) == Chunk("乙"),
        filtered.evidence.acceptedCount == filtered.hits.length
      )
    }
  )
