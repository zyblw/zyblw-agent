package com.zyblw.agent.rag.tools

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.test.*

/** knowledge_search / knowledge_fetch 拒绝模型覆盖 tenant，并在低证据时显式拒答。 */
object KnowledgeToolsSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeTools")(
    test("模型参数携带 tenantId 时拒绝执行") {
      for
        rag <- ZIO.service[RagApplication]
        tool = KnowledgeTools.searchTool(rag)
        exit <- tool
          .execute(
            KnowledgeTools.SearchInput("阴阳", tenantId = Some("attacker")),
            context
          )
          .exit
      yield assertTrue(exit.isFailure)
    }.provide(memoryRag),
    test("可信检索返回 citations，低证据返回 insufficient_evidence") {
      for
        rag <- ZIO.service[RagApplication]
        tenant = TenantId("acme")
        _ <- rag.ingestOne(
          DocumentIngestionRequest(
            DocumentInput.fromBytes(
              "suwen.md",
              "book://suwen",
              "suwen.md",
              "text/markdown",
              Chunk.fromArray("# 阴阳\n\n阴阳者，天地之道也。".getBytes("UTF-8"))
            ),
            tenant,
            Set("knowledge:read"),
            "tools-ingest-1"
          )
        )
        search = KnowledgeTools.searchTool(rag)
        ok <- search.execute(KnowledgeTools.SearchInput("阴阳者"), context)
        fetch = KnowledgeTools.fetchTool(rag)
        miss <- fetch.execute(KnowledgeTools.FetchInput("missing-chunk"), context)
      yield assertTrue(
        ok.citations.nonEmpty,
        ok.status == "ok",
        miss.status == "insufficient_evidence" || miss.citations.isEmpty
      )
    }.provide(memoryRag),
    test("search 摘录截断，fetch 返回授权块全文，citation excerpt 仍不超过 500") {
      val tail = "后半段鉴别要点" * 80
      val body = ("阴阳者，天地之道也。" * 40) + tail
      for
        rag <- ZIO.service[RagApplication]
        tenant = TenantId("acme")
        _ <- rag.ingestOne(
          DocumentIngestionRequest(
            DocumentInput.fromBytes(
              "long.md",
              "zyblw-book://long",
              "long.md",
              "text/markdown",
              Chunk.fromArray(s"# 长文\n\n$body".getBytes("UTF-8")),
              Map("sourceType" -> "book")
            ),
            tenant,
            Set("knowledge:read"),
            "tools-ingest-long"
          )
        )
        search = KnowledgeTools.searchTool(rag)
        found <- search.execute(KnowledgeTools.SearchInput("后半段鉴别要点"), context)
        chunkId = found.citations.head.chunkId
        fetched <- KnowledgeTools.fetchTool(rag).execute(KnowledgeTools.FetchInput(chunkId), context)
      yield assertTrue(
        found.excerpts.forall(_.length <= 500),
        found.citations.forall(_.excerpt.length <= 500),
        found.citations.exists(_.sourceKind.contains("book")),
        fetched.excerpts.exists(_.contains("后半段鉴别要点")),
        fetched.excerpts.exists(text => text.length >= found.excerpts.map(_.length).maxOption.getOrElse(0)),
        fetched.citations.forall(_.excerpt.length <= 500),
        KnowledgeTools.searchTool(rag).metadata.conflictAwareParallel,
        KnowledgeTools.fetchTool(rag).metadata.conflictAwareParallel
      )
    }.provide(memoryRag),
    test("toSearchOutput 只把 seed 写成 citation，并带上标题与章节") {
      val tenant = TenantId("acme")
      val seed   = RetrievalHit(
        DocumentChunk.fromText(
          "seed",
          "doc-1",
          "伤寒论辨证论治",
          "book://shanghan",
          tenant,
          Set("knowledge:read"),
          Map("title" -> "伤寒论", "headingPath" -> "辨太阳病 > 提纲"),
          lineage = Some(
            ChunkLineage(
              parentId = Some("section-1"),
              ordinal = 0,
              headingPath = Chunk("辨太阳病", "提纲")
            )
          )
        ),
        score = 0.016,
        signals = Map("vectorScore" -> 0.52, "textScore" -> 0.4)
      )
      val expanded = RetrievalHit(
        DocumentChunk.fromText(
          "expanded",
          "doc-1",
          "邻块扩展正文",
          "book://shanghan",
          tenant,
          Set("knowledge:read"),
          Map("title" -> "伤寒论")
        ),
        score = 0.01
      )
      val result = RetrievalResult(
        Chunk(seed, expanded),
        Chunk(
          Citation("cite-1", "book://shanghan", "伤寒论辨证论治", 0.016),
          Citation("cite-2", "book://shanghan", "邻块扩展正文", 0.01)
        ),
        RetrievalEvidence(
          RetrievalEvidenceStatus.Supported,
          candidateCount = 2,
          acceptedCount = 1,
          topAcceptedScore = Some(0.52),
          minimumScore = 0.18
        )
      )
      val output = KnowledgeTools.toSearchOutput(result, includeFullChunkText = false)
      assertTrue(
        output.status == "ok",
        output.citations.length == 1,
        output.citations.head.chunkId == "seed",
        output.citations.head.title.contains("伤寒论"),
        output.citations.head.headingPath == List("辨太阳病", "提纲"),
        output.citations.head.vectorScore.contains(0.52),
        output.excerpts.length == 2
      )
    },
    test("宿主限定资料时，模型不能改写 documentIds，未填写则锁在范围内") {
      val host = context.copy(runContext =
        context.runContext.copy(attributes = Map(KnowledgeTools.ScopeDocumentAttribute -> "book-a"))
      )
      for
        widened <- KnowledgeTools
          .constrainDocumentIds("knowledge_search", Set("book-b"), Set("book-a"))
          .exit
        narrowed <- KnowledgeTools.constrainDocumentIds("knowledge_search", Set("book-a"), Set("book-a"))
        omitted  <- KnowledgeTools.constrainDocumentIds("knowledge_search", Set.empty, Set("book-a"))
        rag      <- ZIO.service[RagApplication]
        _        <- ingest(rag, "book-a", "甲书只讲营卫")
        _        <- ingest(rag, "book-b", "乙书只讲营卫")
        denied   <- KnowledgeTools
          .searchTool(rag)
          .execute(KnowledgeTools.SearchInput("营卫", documentIds = Some(List("book-b"))), host)
          .exit
        locked <- KnowledgeTools.searchTool(rag).execute(KnowledgeTools.SearchInput("营卫"), host)
      yield assertTrue(
        widened.isFailure,
        narrowed == Set("book-a"),
        omitted == Set("book-a"),
        denied.isFailure,
        locked.citations.nonEmpty,
        locked.citations.map(_.documentId).toSet == Set("book-a")
      )
    }.provide(memoryRag),
    test("宿主限定资料时，fetch 不能取回范围外的块") {
      val host = context.copy(runContext =
        context.runContext.copy(attributes = Map(KnowledgeTools.ScopeDocumentAttribute -> "book-a"))
      )
      for
        rag   <- ZIO.service[RagApplication]
        _     <- ingest(rag, "book-b", "乙书独有原文甲乙丙")
        found <- KnowledgeTools
          .searchTool(rag)
          .execute(KnowledgeTools.SearchInput("乙书独有原文"), context)
        chunkId = found.citations.head.chunkId
        denied <- KnowledgeTools.fetchTool(rag).execute(KnowledgeTools.FetchInput(chunkId), host).exit
      yield assertTrue(found.citations.nonEmpty, denied.isFailure)
    }.provide(memoryRag)
  )

  private val context = ToolExecutionContext(
    RunId(java.util.UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
    ThreadId("thread-tools"),
    "call-1",
    RunContext(Some("user"), Some("acme"), Set("knowledge:read"))
  )

  private def ingest(rag: RagApplication, id: String, body: String) =
    rag.ingestOne(
      DocumentIngestionRequest(
        DocumentInput.fromBytes(
          id,
          s"book://$id",
          s"$id.md",
          "text/markdown",
          Chunk.fromArray(s"# $id\n\n$body".getBytes("UTF-8"))
        ),
        TenantId("acme"),
        Set("knowledge:read"),
        s"tools-ingest-$id"
      )
    )

  private val markdownLoader = new DocumentLoader:
    override val id: String                       = "tools-md"
    override val supportedMediaTypes: Set[String] = Set("text/markdown")
    override def load(input: DocumentInput)       =
      input.content.runCollect.map(bytes =>
        SourceDocument(
          input.id,
          String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8),
          input.sourceUri
        )
      )

  private val memoryRag: ZLayer[Any, RetrievalError, RagApplication] =
    ZLayer.make[RagApplication](
      DocumentLoaderRegistry.layer(Chunk(markdownLoader)),
      ZLayer.succeed[EmbeddingModel](HashEmbedding(32)),
      InMemoryKnowledgeIndexStore.knowledge,
      DocumentStructureChunker.layer,
      KnowledgeIndexer.layer(),
      DocumentIngestionService.layer(failureMode = DocumentIngestionFailureMode.FailFast),
      Reranker.identity,
      DefaultRetriever.layer,
      RagApplication.layer
    )
