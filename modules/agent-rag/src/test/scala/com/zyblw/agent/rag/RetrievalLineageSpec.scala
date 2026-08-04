package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object RetrievalLineageSpec extends ZIOSpecDefault:

  private val tenant = TenantId("tenant-a")
  private val scope  = RetrievalScope(tenant, Set("knowledge:read"))

  private def chunk(
      id: String,
      ordinal: Int,
      previous: Option[String],
      next: Option[String],
      permissions: Set[String] = Set("knowledge:read"),
      documentId: String = "doc-1"
  ): DocumentChunk =
    DocumentChunk(
      id = id,
      documentId = documentId,
      text = s"原文 $id",
      sourceUri = s"knowledge://$documentId",
      tenantId = tenant,
      permissions = permissions,
      lineage = Some(
        ChunkLineage(
          parentId = Some("section-a"),
          ordinal = ordinal,
          previousChunkId = previous,
          nextChunkId = next,
          origins = Chunk(DocumentOrigin(ordinal + 1))
        )
      )
    )

  def spec = suite("Retrieval lineage")(
    test("先应用 ACL，再有界扩展相邻块和同父级块") {
      val chunks = Chunk(
        chunk("c-0", 0, None, Some("c-1")),
        chunk("c-1", 1, Some("c-0"), Some("c-2")),
        chunk("c-2", 2, Some("c-1"), Some("c-3")),
        chunk("c-3", 3, Some("c-2"), None),
        chunk("secret", 4, None, None, Set("admin")),
        // 另一文档故意复用相同局部 chunkId/parentId，验证复合身份不会覆盖或串联。
        chunk("c-0", 0, None, None, documentId = "doc-2")
      )
      for
        store <- ZIO.service[VectorStore]
        _     <- store.upsert(chunks.map(value => IndexedChunk(value, Embedding(Chunk(1.0f, 0.0f)))))
        seeds = Chunk(RetrievalHit(chunks(1), 0.9), RetrievalHit(chunks(2), 0.8))
        expanded <- store.expandContext(
          seeds,
          scope,
          RetrievalExpansionConfig(
            neighborRadius = 1,
            parentHitThreshold = 2,
            maxSiblingsPerParent = 4,
            maxAdditionalChunks = 3
          )
        )
      yield assertTrue(
        expanded.map(_.chunk.id).toSet == Set("c-0", "c-3"),
        expanded.forall(_.chunk.documentId == "doc-1"),
        expanded.forall(_.signals.get("contextExpanded").contains(1.0)),
        !expanded.exists(_.chunk.id == "secret"),
        !expanded.exists(_.chunk.documentId == "doc-2")
      )
    }.provide(InMemoryVectorStore.layer),
    test("引用保留页码和几何来源") {
      val origin = DocumentOrigin(
        7,
        Some(DocumentBoundingBox(10, 20, 100, 80, Some(612), Some(792)))
      )
      val value = chunk("c-7", 6, None, None).copy(
        lineage = Some(ChunkLineage(Some("section-7"), 6, origins = Chunk(origin)))
      )
      val store = new VectorStore:
        def upsert(chunks: Chunk[IndexedChunk])                         = ZIO.unit
        def search(query: Embedding, scope: RetrievalScope, limit: Int) =
          ZIO.succeed(Chunk(RetrievalHit(value, 0.95)))
        def deleteByDocument(documentId: String, tenantId: TenantId) = ZIO.unit
      val reranker = new Reranker:
        def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int) = ZIO.succeed(hits.take(limit))
      for result <- DefaultRetriever(HashEmbedding(2), store, reranker).retrieve("查询", scope, 1)
      yield assertTrue(
        result.citations.head.pageNumbers == Chunk(7),
        result.citations.head.origins == Chunk(origin)
      )
    }
  )
