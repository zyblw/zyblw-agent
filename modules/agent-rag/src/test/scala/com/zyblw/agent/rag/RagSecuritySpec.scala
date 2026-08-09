package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object RagSecuritySpec extends ZIOSpecDefault:
  def spec = suite("RAG tenant security")(
    test("在相似度计算候选集之前过滤 tenant 和权限") {
      (for
        store <- ZIO.service[VectorStore]
        embeddingService = HashEmbedding(16)
        vectors <- embeddingService.embed(Chunk("公开资料", "另一个租户的秘密"))
        tenantA = TenantId("tenant-a")
        tenantB = TenantId("tenant-b")
        chunks  = Chunk(
          DocumentChunk("a", "doc-a", "公开资料", "a.md", tenantA, Set("read")),
          DocumentChunk("b", "doc-b", "另一个租户的秘密", "b.md", tenantB, Set("read"))
        )
        _     <- store.upsert(chunks.zip(vectors).map(IndexedChunk.apply))
        query <- embeddingService.embed(Chunk("另一个租户的秘密")).map(_.head)
        hits  <- store.search(query, RetrievalScope(tenantA, Set("read")), 10)
      yield assertTrue(hits.map(_.chunk.id) == Chunk("a"))).provide(InMemoryVectorStore.layer)
    },
    test("DefaultRetriever 把受控 lexical query 交给 hybrid store，并保持统一候选预算") {
      for
        observed <- Ref.make(Option.empty[(String, Int)])
        tenant = TenantId("tenant-a")
        scope  = RetrievalScope(tenant, Set("read"))
        hit    = RetrievalHit(
          DocumentChunk("chunk-1", "doc-1", "桂枝相关资料", "doc://1", tenant, Set("read")),
          score = 0.031,
          signals = Map("vectorRank" -> 1.0, "textRank" -> 2.0)
        )
        embedding = new EmbeddingService:
          val dimension: Int = 2

          /** 测试固定向量，确保本用例只验证 Retriever 编排而非语义质量。 */
          def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
            ZIO.succeed(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        store = new VectorStore:
          def upsert(chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit] = ZIO.unit
          def search(
              query: Embedding,
              scope: RetrievalScope,
              limit: Int
          ): IO[RetrievalError, Chunk[RetrievalHit]] = ZIO.dieMessage("不应回退到纯向量 search")

          /** 记录 Retriever 传来的受控 lexical query 与放大后的候选数。 */
          override def searchHybrid(
              queryText: String,
              query: Embedding,
              scope: RetrievalScope,
              limit: Int
          ): IO[RetrievalError, Chunk[RetrievalHit]] = observed.set(Some(queryText -> limit)).as(Chunk(hit))
          def deleteByDocument(documentId: String, tenantId: TenantId): IO[RetrievalError, Unit] = ZIO.unit
        reranker = new Reranker:
          def rerank(
              query: String,
              hits: Chunk[RetrievalHit],
              limit: Int
          ): IO[RetrievalError, Chunk[RetrievalHit]] = ZIO.succeed(hits.take(limit))
        result <- DefaultRetriever(embedding, store, reranker).retrieve("桂枝", scope, 2)
        call   <- observed.get
      yield assertTrue(
        call.contains("桂 枝 桂枝" -> 6),
        result.hits == Chunk(hit),
        result.hits.head.signals("textRank") == 2.0,
        result.citations.head.sourceUri == "doc://1"
      )
    },
    test("limit 非正数时不调用 Provider 或存储") {
      val explodingEmbedding = new EmbeddingService:
        val dimension: Int                                                    = 2
        def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
          ZIO.dieMessage("不应调用 embedding")
      val explodingStore = new VectorStore:
        def upsert(chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit] = ZIO.dieMessage("不应调用 store")
        def search(
            query: Embedding,
            scope: RetrievalScope,
            limit: Int
        ): IO[RetrievalError, Chunk[RetrievalHit]] =
          ZIO.dieMessage("不应调用 search")
        def deleteByDocument(documentId: String, tenantId: TenantId): IO[RetrievalError, Unit] =
          ZIO.dieMessage("不应调用 delete")
      val reranker = new Reranker:
        def rerank(
            query: String,
            hits: Chunk[RetrievalHit],
            limit: Int
        ): IO[RetrievalError, Chunk[RetrievalHit]] =
          ZIO.dieMessage("不应调用 reranker")
      DefaultRetriever(explodingEmbedding, explodingStore, reranker)
        .retrieve("任意查询", RetrievalScope(TenantId("tenant-a"), Set("read")), 0)
        .map(result => assertTrue(result.hits.isEmpty, result.citations.isEmpty))
    },
    test("候选未通过最低分时返回可展示的证据不足状态，且不生成 citation") {
      val tenant    = TenantId("tenant-a")
      val hit       = RetrievalHit(DocumentChunk("weak", "doc-a", "弱相关", "a", tenant, Set("read")), 0.1)
      val embedding = new EmbeddingService:
        val dimension                                                         = 2
        def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
          ZIO.succeed(Chunk(Embedding(Chunk(1.0f, 0.0f))))
      val store = new VectorStore:
        def upsert(chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit] = ZIO.unit
        def search(
            query: Embedding,
            scope: RetrievalScope,
            limit: Int
        ): IO[RetrievalError, Chunk[RetrievalHit]] =
          ZIO.succeed(Chunk(hit))
        def deleteByDocument(documentId: String, tenantId: TenantId): IO[RetrievalError, Unit] = ZIO.unit
      val reranker = new Reranker:
        def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): UIO[Chunk[RetrievalHit]] =
          ZIO.succeed(hits.take(limit))
      val result = DefaultRetriever(
        embedding,
        store,
        reranker,
        policies = RetrievalPolicySource.static(RetrievalPolicy(minimumScore = 0.2))
      )
      result
        .retrieve("查询", RetrievalScope(tenant, Set("read")), 1)
        .map(value =>
          assertTrue(
            value.hits.isEmpty,
            value.citations.isEmpty,
            value.evidence.status == RetrievalEvidenceStatus.BelowMinimumScore,
            !value.evidence.supportsGroundedAnswer,
            value.evidence.candidateCount == 1,
            value.evidence.topAcceptedScore.isEmpty
          )
        )
    },
    test("失陷 Reranker 不能向候选集注入另一个租户的文档") {
      val tenantA   = TenantId("tenant-a")
      val tenantB   = TenantId("tenant-b")
      val allowed   = RetrievalHit(DocumentChunk("allowed", "doc-a", "可见", "a", tenantA, Set("read")), 0.5)
      val injected  = RetrievalHit(DocumentChunk("secret", "doc-b", "秘密", "b", tenantB, Set("read")), 1.0)
      val embedding = new EmbeddingService:
        val dimension                                                         = 2
        def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
          ZIO.succeed(Chunk(Embedding(Chunk(1.0f, 0.0f))))
      val store = new VectorStore:
        def upsert(chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit] = ZIO.unit
        def search(
            query: Embedding,
            scope: RetrievalScope,
            limit: Int
        ): IO[RetrievalError, Chunk[RetrievalHit]] =
          ZIO.succeed(Chunk(allowed))
        def deleteByDocument(documentId: String, tenantId: TenantId): IO[RetrievalError, Unit] = ZIO.unit
      val malicious = new Reranker:
        def rerank(
            query: String,
            hits: Chunk[RetrievalHit],
            limit: Int
        ): IO[RetrievalError, Chunk[RetrievalHit]] =
          ZIO.succeed(Chunk(injected))
      DefaultRetriever(embedding, store, malicious)
        .retrieve("查询", RetrievalScope(tenantA, Set("read")), 1)
        .exit
        .map(exit => assertTrue(exit.isFailure))
    }
  )
