package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object RetrievalContractSpec extends ZIOSpecDefault:
  private val tenant   = TenantId("retrieval-contract")
  private val scope    = RetrievalScope(tenant, Set("read"))
  private val chunk    = DocumentChunk("seed", "doc", "原始证据", "book://one", tenant, Set("read"))
  private val hit      = RetrievalHit(chunk, 0.9)
  private val identity = new Reranker:
    def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): UIO[Chunk[RetrievalHit]] =
      ZIO.succeed(hits.take(limit))

  def spec = suite("Retrieval production contracts")(
    test("citation excerpts never split a Unicode supplementary character") {
      val body   = "a".repeat(499) + "𠀀" + "尾文"
      val chunk  = DocumentChunk("unicode", "book", body, "book://unicode", TenantId("unicode"), Set("read"))
      val bundle = ContextAssembler.assemble(
        Chunk(RetrievalHit(chunk, 1.0)),
        Chunk.empty,
        RetrievalEvidence(RetrievalEvidenceStatus.Supported, 1, 1),
        CandidateBudgets()
      )
      assertTrue(
        bundle.citations.head.excerpt == "a".repeat(499) + "𠀀",
        bundle.citations.head.excerpt.codePointCount(0, bundle.citations.head.excerpt.length) == 500
      )
    },
    test("explicit modes survive short query planning; phrase receives original text") {
      for
        calls <- Ref.make(Chunk.empty[(RetrievalMode, String)])
        store = new VectorStore:
          def upsert(chunks: Chunk[IndexedChunk]): UIO[Unit]                                        = ZIO.unit
          def deleteByDocument(id: String, tenant: TenantId): UIO[Unit]                             = ZIO.unit
          def search(query: Embedding, scope: RetrievalScope, limit: Int): UIO[Chunk[RetrievalHit]] =
            ZIO.succeed(Chunk(hit))
          override def searchFiltered(
              mode: RetrievalMode,
              text: String,
              query: Embedding,
              scope: RetrievalScope,
              filter: RetrievalFilter,
              limit: Int,
              sparseQuery: Option[SparseEmbedding]
          ): UIO[Chunk[RetrievalHit]] =
            calls.update(_ :+ (mode -> text)).as(Chunk(hit))
        retriever = DefaultRetriever(EmbeddingModel.stub(), store, identity)
        modes     = Chunk(
          RetrievalMode.Hybrid,
          RetrievalMode.VectorOnly,
          RetrievalMode.LexicalOnly,
          RetrievalMode.Phrase
        )
        _      <- ZIO.foreach(modes)(mode => retriever.retrieve(RetrievalRequest("桂枝汤", scope, 3, mode)))
        actual <- calls.get
      yield assertTrue(
        actual.map(_._1) == Chunk(
          RetrievalMode.Hybrid,
          RetrievalMode.Phrase,
          RetrievalMode.VectorOnly,
          RetrievalMode.LexicalOnly,
          RetrievalMode.Phrase
        ),
        actual.count(_._1 == RetrievalMode.Phrase) == 2,
        actual.last._2 == "桂枝汤"
      )
    },
    test("all evidence dropped by budget produces insufficient evidence and preserves diagnostic reason") {
      val result = ContextAssembler
        .assemble(
          Chunk(hit),
          Chunk.empty,
          RetrievalEvidence(RetrievalEvidenceStatus.Supported, 1, 1, Some(0.9)),
          CandidateBudgets(maxEvidenceTokens = 1),
          Some(IndexProfileId("profile")),
          Some(KnowledgeSpaceId("space")),
          tokenCounter = TokenCounter.CodePoints
        )
        .toRetrievalResult
      assertTrue(
        result.hits.isEmpty,
        result.citations.isEmpty,
        !result.evidence.supportsGroundedAnswer,
        result.evidence.acceptedCount == 0,
        result.evidence.topAcceptedScore.isEmpty,
        result.diagnostics.profileId.contains("profile"),
        result.diagnostics.degradedStages.contains("dropped-budget")
      )
    },
    test("seed and neighbor lineage survives assembly; duplicate seeds are not counted twice") {
      val neighbor = RetrievalHit(
        chunk.copy(id = "neighbor", lineage = Some(ChunkLineage(None, 1, seedChunkId = Some("seed")))),
        0.8
      )
      val bundle = ContextAssembler.assemble(
        Chunk(hit, hit),
        Chunk(neighbor),
        RetrievalEvidence(RetrievalEvidenceStatus.Supported, 2, 2, Some(0.9)),
        CandidateBudgets()
      )
      assertTrue(
        bundle.toRetrievalResult.hits.map(_.chunk.id) == Chunk("seed", "neighbor"),
        bundle.evidence.acceptedCount == 1,
        bundle.items.exists(_.decision == EvidenceDecision.DroppedDuplicate),
        bundle.items.exists(_.decision == EvidenceDecision.KeptExpanded)
      )
    },
    test("partial embedding batch fails instead of silently dropping comparison subqueries") {
      val model = EmbeddingModel.stub(onEmbed = _ => ZIO.succeed(Chunk(Embedding(Chunk(1.0f, 0.0f)))))
      (for
        store  <- ZIO.service[VectorStore]
        result <- DefaultRetriever(model, store, identity).retrieve("桂枝 对比 麻黄", scope, 3).exit
      yield assertTrue(result.isFailure)).provide(InMemoryVectorStore.layer)
    },
    test("query assist failure falls back visibly without losing original plan") {
      val assist = new QueryAssist:
        def rewrite(query: String, config: QueryAssistConfig): IO[RetrievalError, QueryRewrite] =
          ZIO.fail(AgentError.RetrievalFailed("unavailable"))
      (for
        store  <- ZIO.service[VectorStore]
        _      <- store.upsert(Chunk(IndexedChunk(chunk, Embedding(Chunk(1.0f, 0.0f)))))
        result <- DefaultRetriever(
          EmbeddingModel.stub(),
          store,
          identity,
          assist = assist,
          assistConfig = QueryAssistConfig(enabled = true)
        ).retrieve("桂枝", scope, 3)
      yield assertTrue(result.diagnostics.degradedStages.contains("rewrite-fallback")))
        .provide(InMemoryVectorStore.layer)
    },
    test("query assist rewrites only the primary route and preserves comparison routes") {
      (for
        embeddingCalls <- Ref.make(Chunk.empty[String])
        model = EmbeddingModel.stub(onEmbed =
          texts =>
            embeddingCalls.set(texts) *> ZIO.succeed(
              texts.map(text => Embedding(Chunk(text.length.toFloat, 1.0f)))
            )
        )
        assist = new QueryAssist:
          def rewrite(query: String, config: QueryAssistConfig): IO[RetrievalError, QueryRewrite] =
            ZIO.succeed(QueryRewrite(query, "桂枝别名", "assist:test"))
        store <- ZIO.service[VectorStore]
        _     <- store.upsert(Chunk(IndexedChunk(chunk, Embedding(Chunk(1.0f, 0.0f)))))
        _     <- DefaultRetriever(
          model,
          store,
          identity,
          assist = assist,
          assistConfig = QueryAssistConfig(enabled = true)
        ).retrieve("桂枝 对比 麻黄", scope, 3)
        calls <- embeddingCalls.get
      yield assertTrue(calls == Chunk("桂枝别名", "麻黄"))).provide(InMemoryVectorStore.layer)
    },
    test("comparison query triggers hybrid and phrase routes for both subqueries") {
      for
        calls <- Ref.make(Chunk.empty[(RetrievalMode, String)])
        store = new VectorStore:
          def upsert(chunks: Chunk[IndexedChunk]): UIO[Unit]                                        = ZIO.unit
          def deleteByDocument(id: String, tenant: TenantId): UIO[Unit]                             = ZIO.unit
          def search(query: Embedding, scope: RetrievalScope, limit: Int): UIO[Chunk[RetrievalHit]] =
            ZIO.succeed(Chunk(hit))
          override def searchFiltered(
              mode: RetrievalMode,
              text: String,
              query: Embedding,
              scope: RetrievalScope,
              filter: RetrievalFilter,
              limit: Int,
              sparseQuery: Option[SparseEmbedding]
          ): UIO[Chunk[RetrievalHit]] =
            calls.update(_ :+ (mode -> text)).as(Chunk(hit))
        retriever = DefaultRetriever(EmbeddingModel.stub(), store, identity)
        _      <- retriever.retrieve(RetrievalRequest("桂枝汤 对比 麻黄汤", scope, 3, RetrievalMode.Hybrid))
        actual <- calls.get
        phrases = actual.filter(_._1 == RetrievalMode.Phrase).map(_._2)
      yield assertTrue(
        actual.count(_._1 == RetrievalMode.Hybrid) == 2,
        phrases == Chunk("桂枝汤", "麻黄汤")
      )
    },
    test("扩展块不占用来源名额，挂上种子后仍进入上下文") {
      val neighbor = RetrievalHit(
        chunk.copy(
          id = "neighbor",
          representations = ChunkRepresentations.uniform("相邻条文"),
          lineage = Some(ChunkLineage(Some("section"), 1, seedChunkId = Some("seed")))
        ),
        0.7
      )
      val second = RetrievalHit(
        chunk.copy(id = "second", representations = ChunkRepresentations.uniform("同书另一段")),
        0.8
      )
      val bundle = ContextAssembler.assemble(
        Chunk(hit, second),
        Chunk(neighbor),
        RetrievalEvidence(RetrievalEvidenceStatus.Supported, 2, 2, Some(0.9)),
        CandidateBudgets(maxChunksPerSource = 2)
      )
      assertTrue(
        bundle.items.exists(item =>
          item.chunk.id == "neighbor" && item.decision == EvidenceDecision.KeptExpanded
        ),
        bundle.toRetrievalResult.hits.map(_.chunk.id).toSet == Set("seed", "second", "neighbor")
      )
    },
    test("同一来源先按标题路径分散，再留下一块") {
      def headed(id: String, heading: String, score: Double) =
        RetrievalHit(
          chunk.copy(
            id = id,
            lineage = Some(ChunkLineage(Some("parent"), 0, headingPath = Chunk(heading)))
          ),
          score
        )
      val bundle = ContextAssembler.assemble(
        Chunk(headed("same-a", "病因", 0.9), headed("same-b", "病因", 0.85), headed("other", "表现", 0.8)),
        Chunk.empty,
        RetrievalEvidence(RetrievalEvidenceStatus.Supported, 3, 3, Some(0.9)),
        CandidateBudgets(maxChunksPerSource = 2)
      )
      assertTrue(
        bundle.toRetrievalResult.hits.map(_.chunk.id).toSet == Set("same-a", "other"),
        bundle.items.exists(item =>
          item.chunk.id == "same-b" && item.decision == EvidenceDecision.DroppedDiversity
        )
      )
    },
    test("单书检索放宽来源上限，邻居经完整检索进入结果") {
      val query   = "桂枝"
      val matched = Embedding(Chunk(query.length.toFloat, 1.0f))
      val seed    = chunk.copy(
        lineage = Some(ChunkLineage(Some("section"), 0, nextChunkId = Some("neighbor")))
      )
      val neighbor = chunk.copy(
        id = "neighbor",
        representations = ChunkRepresentations.uniform("相邻条文"),
        lineage = Some(ChunkLineage(Some("section"), 1, previousChunkId = Some("seed")))
      )
      (for
        store <- ZIO.service[VectorStore]
        _     <- store.upsert(
          Chunk(
            IndexedChunk(seed, matched),
            IndexedChunk(neighbor, Embedding(Chunk(0.01f, 0.0f)))
          )
        )
        result <- DefaultRetriever(EmbeddingModel.stub(), store, identity).retrieve(
          RetrievalRequest(query, scope, 1, filter = RetrievalFilter(documentIds = Set("doc")))
        )
      yield assertTrue(result.hits.map(_.chunk.id).toSet == Set("seed", "neighbor")))
        .provide(InMemoryVectorStore.layer)
    },
    test("mergeHits 跨分支合并保留完整 signals，且非精确 3-gram phrase 不会压过 Hybrid RRF") {
      val hybridHit = RetrievalHit(
        chunk.copy(id = "c-hybrid"),
        0.032,
        Map("vectorScore" -> 0.88, "textScore" -> 0.5, "textRank" -> 1.0)
      )
      val exactSameChunk = RetrievalHit(
        chunk.copy(id = "c-hybrid"),
        1.0,
        Map("phraseScore" -> 1.0)
      )
      val fuzzyPhraseHit = RetrievalHit(
        chunk.copy(id = "c-fuzzy"),
        0.25,
        Map("phraseScore" -> 0.25)
      )
      val merged = DefaultRetriever.mergeHits(
        Chunk(Chunk(hybridHit), Chunk(fuzzyPhraseHit, exactSameChunk)),
        limit = 5
      )
      val top = merged.head
      assertTrue(
        top.chunk.id == "c-hybrid",
        top.signals.get("vectorScore").contains(0.88),
        top.signals.get("textScore").contains(0.5),
        top.signals.get("phraseScore").contains(1.0)
      )
    },
    test("acceptsSeed 在具备权威 rerankScore 时优先按精排分裁决，回退重排仍沿用词法/向量规则") {
      val rejectedByRerank = RetrievalHit(
        chunk.copy(id = "rerank-low"),
        0.10,
        Map("textScore" -> 0.8, "vectorScore" -> 0.9, "rerankScore" -> 0.10)
      )
      val acceptedByFallback = RetrievalHit(
        chunk.copy(id = "rerank-fallback"),
        0.10,
        Map("textScore" -> 0.8, "rerankScore" -> 0.10, "rerankFallback" -> 1.0)
      )
      assertTrue(
        !DefaultRetriever.acceptsSeed(rejectedByRerank, 0.35),
        DefaultRetriever.acceptsSeed(acceptedByFallback, 0.35)
      )
    },
    test("R7：HybridExact 路径与多次 mergeHits 对模糊 Phrase 仅归一化一次，不发生重复缩放") {
      val fuzzyPhraseHit = RetrievalHit(
        chunk.copy(id = "c-fuzzy-once"),
        0.25,
        Map("phraseScore" -> 0.25)
      )
      val once  = DefaultRetriever.mergeHits(Chunk(Chunk(fuzzyPhraseHit)), limit = 5)
      val twice = DefaultRetriever.mergeHits(Chunk(once), limit = 5)
      assertTrue(
        once.head.score == (0.25 / 61.0),
        twice.head.score == once.head.score,
        twice.head.signals.get("retrievalScore").contains(0.25)
      )
    },
    test("LexicalOnly 与 Phrase 模式不依赖前置 Embedding 调用，Embedding 故障时仍可完成词法检索") {
      val baseModel    = HashEmbedding(2)
      val failingModel = new EmbeddingModel:
        override val capabilities                                                   = baseModel.capabilities
        override val descriptor                                                     = baseModel.descriptor
        def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse] =
          val _ = request
          ZIO.fail(AgentError.RetrievalFailed("Embedding 服务故障"))
      val lexicalChunk = DocumentChunk(
        "c-lex",
        "doc-lex",
        "桂枝汤主治太阳中风",
        "book://lex",
        tenant,
        Set("read")
      )
      (for
        store <- ZIO.service[VectorStore]
        _     <- store.upsert(Chunk(IndexedChunk(lexicalChunk, Embedding(Chunk(1.0f, 0.0f)))))
        retriever = DefaultRetriever(failingModel, store, identity)
        lexicalRes <- retriever.retrieve(
          RetrievalRequest("桂枝汤", scope, 3, RetrievalMode.LexicalOnly)
        )
        phraseRes <- retriever.retrieve(
          RetrievalRequest("桂枝汤", scope, 3, RetrievalMode.Phrase)
        )
      yield assertTrue(
        lexicalRes.hits.map(_.chunk.id) == Chunk("c-lex"),
        phraseRes.hits.map(_.chunk.id) == Chunk("c-lex")
      )).provide(InMemoryVectorStore.layer)
    }
  )
