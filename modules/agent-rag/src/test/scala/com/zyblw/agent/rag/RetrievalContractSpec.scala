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
      yield assertTrue(actual.map(_._1) == modes, actual.last._2 == "桂枝汤")
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
        bundle.items.exists(item => item.chunk.id == "neighbor" && item.decision == EvidenceDecision.KeptExpanded),
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
        bundle.items.exists(item => item.chunk.id == "same-b" && item.decision == EvidenceDecision.DroppedDiversity)
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
        _ <- store.upsert(
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
    }
  )
