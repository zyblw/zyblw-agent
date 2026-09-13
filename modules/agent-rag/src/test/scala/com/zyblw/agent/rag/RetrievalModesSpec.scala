package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object RetrievalModesSpec extends ZIOSpecDefault:
  private val tenant = TenantId("books")
  private val read   = Set("knowledge:read")
  private val other  = Set("knowledge:other")

  private def chunk(
      id: String,
      documentId: String,
      text: String,
      permissions: Set[String] = read,
      pages: Chunk[Int] = Chunk(1),
      heading: Chunk[String] = Chunk("太阳病"),
      metadata: Map[String, String] = Map("edition" -> "song")
  ): DocumentChunk =
    DocumentChunk.fromText(
      id = id,
      documentId = documentId,
      text = text,
      sourceUri = s"memory://$documentId",
      tenantId = tenant,
      permissions = permissions,
      metadata = metadata,
      searchText = Some(SimpleChineseLexicalProcessor.document(text)),
      lineage = Some(
        ChunkLineage(
          parentId = Some("root"),
          ordinal = 0,
          headingPath = heading,
          origins = pages.map(DocumentOrigin(_))
        )
      )
    )

  private def indexed(value: DocumentChunk, dim: Int, hotspot: Int): IndexedChunk =
    val values = Chunk.fromIterable(List.tabulate(dim)(index => if index == hotspot then 1.0f else 0.0f))
    IndexedChunk(value, Embedding(values))

  def spec = suite("RetrievalModes")(
    test("过滤发生在授权之后，权限不足的块即使用 documentId 也不能命中") {
      for
        store <- ZIO.service[VectorStore]
        _     <- store.upsert(
          Chunk(
            indexed(chunk("c1", "doc-a", "桂枝汤主之", permissions = other), 4, 0),
            indexed(chunk("c2", "doc-a", "桂枝汤主之"), 4, 0)
          )
        )
        query = Embedding(Chunk(1.0f, 0.0f, 0.0f, 0.0f))
        scope = RetrievalScope(tenant, read)
        hits <- store.searchFiltered(
          RetrievalMode.Hybrid,
          SimpleChineseLexicalProcessor.query("桂枝汤"),
          query,
          scope,
          RetrievalFilter(documentIds = Set("doc-a")),
          10
        )
        stolen <- store.fetchChunks(Set("c1", "c2"), scope)
      yield assertTrue(hits.map(_.chunk.id) == Chunk("c2"), stolen.map(_.id) == Chunk("c2"))
    },
    test("page / heading / metadata / chunkId 过滤只保留同时满足的已授权块") {
      for
        store <- ZIO.service[VectorStore]
        _     <- store.upsert(
          Chunk(
            indexed(chunk("p1", "book-1", "太阳之为病", pages = Chunk(2), heading = Chunk("太阳病")), 4, 1),
            indexed(
              chunk(
                "p2",
                "book-1",
                "阳明之为病",
                pages = Chunk(8),
                heading = Chunk("阳明病"),
                metadata = Map("edition" -> "ming")
              ),
              4,
              1
            ),
            indexed(chunk("p3", "book-2", "太阳之为病", pages = Chunk(2), metadata = Map.empty), 4, 1)
          )
        )
        query = Embedding(Chunk(0.0f, 1.0f, 0.0f, 0.0f))
        scope = RetrievalScope(tenant, read)
        pages <- store.searchFiltered(
          RetrievalMode.VectorOnly,
          "太阳",
          query,
          scope,
          RetrievalFilter(pages = Set(2), documentIds = Set("book-1")),
          10
        )
        heading <- store.searchFiltered(
          RetrievalMode.LexicalOnly,
          SimpleChineseLexicalProcessor.query("阳明"),
          query,
          scope,
          RetrievalFilter(headingPrefix = Chunk("阳明病")),
          10
        )
        edition <- store.searchFiltered(
          RetrievalMode.Phrase,
          "太阳之为病",
          query,
          scope,
          RetrievalFilter(metadataEquals = Map("edition" -> "song")),
          10
        )
        fetched <- store.fetchChunks(Set("p2"), scope)
      yield assertTrue(
        pages.map(_.chunk.id) == Chunk("p1"),
        heading.map(_.chunk.id) == Chunk("p2"),
        edition.map(_.chunk.id) == Chunk("p1"),
        fetched.map(_.id) == Chunk("p2")
      )
    },
    test("跨租户 chunkId 直取必须落空") {
      for
        store  <- ZIO.service[VectorStore]
        _      <- store.upsert(Chunk(indexed(chunk("shared", "book-1", "不可越权"), 4, 2)))
        stolen <- store.fetchChunks(Set("shared"), RetrievalScope(TenantId("other"), read))
      yield assertTrue(stolen.isEmpty)
    },
    test("Phrase 对原文短语给出高于无关句的分数") {
      val needle = "太阳中风发热汗出"
      val hit    = RetrievalScoring.phraseScore(needle, s"伤寒论曰${needle}恶风")
      val miss   = RetrievalScoring.phraseScore(needle, "完全无关的现代说明文字")
      assertTrue(hit > 0.5, hit > miss)
    }
  ).provideLayer(InMemoryVectorStore.layer)
