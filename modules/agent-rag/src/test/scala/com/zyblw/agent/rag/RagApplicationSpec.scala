package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.test.*

object RagApplicationSpec extends ZIOSpecDefault:

  private val markdownLoader = new DocumentLoader:
    override val id: String                       = "test-markdown"
    override val supportedMediaTypes: Set[String] = Set("text/markdown")

    override def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
      input.content.runCollect.map(bytes =>
        SourceDocument(
          input.id,
          String(bytes.toArray, StandardCharsets.UTF_8),
          input.sourceUri,
          representation = DocumentRepresentation.Markdown
        )
      )

  private def input(id: String, text: String): DocumentInput =
    DocumentInput.fromBytes(
      id,
      s"knowledge://$id",
      s"$id.md",
      "text/markdown",
      Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8))
    )

  private def makeApplication(
      store: InMemoryKnowledgeIndexStore,
      config: RagApplicationConfig = RagApplicationConfig()
  ): IO[RetrievalError, RagApplication] =
    for registry <- DocumentLoaderRegistry.make(Chunk(markdownLoader))
    yield
      val embeddings = HashEmbedding(64)
      val ingestion  = DocumentIngestionService(
        registry,
        KnowledgeIndexer(MarkdownStructureChunker(), embeddings, store),
        failureMode = DocumentIngestionFailureMode.FailFast
      )
      val retriever = DefaultRetriever(
        embeddings,
        store,
        new Reranker:
          override def rerank(
              query: String,
              hits: Chunk[RetrievalHit],
              limit: Int
          ): UIO[Chunk[RetrievalHit]] = ZIO.succeed(hits.take(limit))
      )
      RagApplication(ingestion, retriever, config)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("RagApplication")(
    test("正式 Loader→Indexer→active snapshot→Retriever 主路径无需手工复制向量") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        app   <- makeApplication(store)
        tenant = TenantId("tenant-a")
        outcome <- app.ingestOne(
          DocumentIngestionRequest(
            input("doc-1", "# 方剂\n\n桂枝汤由桂枝、芍药等组成。"),
            tenant,
            Set("knowledge:read"),
            "upload-1"
          )
        )
        result <- app.retrieve(
          RagQuery(
            "桂枝汤",
            RetrievalScope(tenant, Set("knowledge:read"), Some("query-1")),
            Some(3)
          )
        )
      yield assertTrue(
        outcome.isInstanceOf[DocumentIngestionOutcome.Indexed],
        result.hits.nonEmpty,
        result.hits.head.chunk.documentId == "doc-1",
        result.citations.head.sourceUri == "knowledge://doc-1"
      )
    },
    test("tenant 与 permission 仍在相似度计算前过滤") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        app   <- makeApplication(store)
        tenant = TenantId("tenant-a")
        _ <- app.ingestOne(
          DocumentIngestionRequest(
            input("private", "仅授权资料"),
            tenant,
            Set("private:read"),
            "upload-private"
          )
        )
        wrongTenant <- app.retrieve(
          RagQuery("授权资料", RetrievalScope(TenantId("tenant-b"), Set("private:read")))
        )
        missingScope <- app.retrieve(
          RagQuery("授权资料", RetrievalScope(tenant, Set("knowledge:read")))
        )
      yield assertTrue(wrongTenant.hits.isEmpty, missingScope.hits.isEmpty)
    },
    test("query 和 topK 在进入 Retriever 前执行硬上限") {
      for
        calls    <- Ref.make(0)
        store    <- InMemoryKnowledgeIndexStore.make
        registry <- DocumentLoaderRegistry.make(Chunk(markdownLoader))
        retriever = new Retriever:
          override def retrieve(
              query: String,
              scope: RetrievalScope,
              limit: Int
          ): IO[RetrievalError, RetrievalResult] =
            calls.update(_ + 1).as(RetrievalResult(Chunk.empty, Chunk.empty))
        ingestion = DocumentIngestionService(
          registry,
          KnowledgeIndexer(SlidingWindowChunker(), HashEmbedding(), store)
        )
        app = RagApplication(
          ingestion,
          retriever,
          RagApplicationConfig(defaultTopK = 3, maxTopK = 5, maxQueryCodePoints = 4)
        )
        blank     <- app.retrieve(RagQuery("  ", RetrievalScope(TenantId("t"), Set.empty))).exit
        tooLong   <- app.retrieve(RagQuery("超过四个字符", RetrievalScope(TenantId("t"), Set.empty))).exit
        tooMany   <- app.retrieve(RagQuery("合法", RetrievalScope(TenantId("t"), Set.empty), Some(6))).exit
        succeeded <- app.retrieve(RagQuery("合法", RetrievalScope(TenantId("t"), Set.empty)))
        count     <- calls.get
      yield assertTrue(
        blank.isFailure,
        tooLong.isFailure,
        tooMany.isFailure,
        succeeded.hits.isEmpty,
        count == 1
      )
    }
  )
