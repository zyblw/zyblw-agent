package com.zyblw.agent.rag.tools

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.test.*

/** knowledge_search / knowledge_fetch 拒绝模型覆盖 tenant，并在低证据时显式拒答。 */
object KnowledgeToolsSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeTools")(
    test("search Schema declares portable array element types") {
      for
        rag <- ZIO.service[RagApplication]
        schema = KnowledgeTools.searchTool(rag).inputSchema
      yield assertTrue(
        ToolSchema.validate(Chunk(ToolDefinition("knowledge_search", "search", schema))).isRight
      )
    }.provide(memoryRag),
    test("模型参数携带 tenantId 时拒绝执行") {
      for
        rag <- ZIO.service[RagApplication]
        tool = KnowledgeTools.searchTool(rag)
        exit <- tool
          .execute(
            KnowledgeTools.SearchInput("阴阳", tenantId = Some("attacker")),
            openLibrary
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
        ok <- search.execute(KnowledgeTools.SearchInput("阴阳者"), openLibrary)
        fetch = KnowledgeTools.fetchTool(rag)
        miss <- fetch.execute(KnowledgeTools.FetchInput("missing-chunk"), openLibrary)
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
        found <- search.execute(KnowledgeTools.SearchInput("后半段鉴别要点"), openLibrary)
        chunkId = found.citations.head.chunkId
        fetched <- KnowledgeTools.fetchTool(rag).execute(KnowledgeTools.FetchInput(chunkId), openLibrary)
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
          .constrainDocumentIds("knowledge_search", Set("book-b"), DocumentScope.Restricted(Set("book-a")))
          .exit
        narrowed <- KnowledgeTools.constrainDocumentIds(
          "knowledge_search",
          Set("book-a"),
          DocumentScope.Restricted(Set("book-a"))
        )
        omitted <- KnowledgeTools.constrainDocumentIds(
          "knowledge_search",
          Set.empty,
          DocumentScope.Restricted(Set("book-a"))
        )
        rag    <- ZIO.service[RagApplication]
        _      <- ingest(rag, "book-a", "甲书只讲营卫")
        _      <- ingest(rag, "book-b", "乙书只讲营卫")
        denied <- KnowledgeTools
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
          .execute(KnowledgeTools.SearchInput("乙书独有原文"), openLibrary)
        chunkId = found.citations.head.chunkId
        denied <- KnowledgeTools.fetchTool(rag).execute(KnowledgeTools.FetchInput(chunkId), host).exit
      yield assertTrue(found.citations.nonEmpty, denied.isFailure)
    }.provide(memoryRag),
    test("批量 fetch 去重并限制为六个不同块") {
      val accepted = KnowledgeTools.fetchChunkIds(
        KnowledgeTools.FetchInput(" a ", chunkIds = Some(List("b", "a", " ")))
      )
      val empty   = KnowledgeTools.fetchChunkIds(KnowledgeTools.FetchInput(" ", chunkIds = Some(Nil)))
      val tooMany = KnowledgeTools.fetchChunkIds(
        KnowledgeTools.FetchInput("a", chunkIds = Some(List("b", "c", "d", "e", "f", "g")))
      )
      assertTrue(accepted == Right(Set("a", "b")), empty.isLeft, tooMany.isLeft)
    },
    test("空白或缺失的资料范围不能放宽成全库") {
      val blank = context.copy(runContext =
        context.runContext.copy(attributes = Map(KnowledgeTools.ScopeDocumentAttribute -> "  "))
      )
      val missing = DocumentScope.parse(Map.empty)
      val empty   = DocumentScope.parse(Map(KnowledgeTools.ScopeDocumentAttribute -> ""))
      for
        rag    <- ZIO.service[RagApplication]
        _      <- ingest(rag, "book-a", "甲书只讲营卫")
        denied <- KnowledgeTools.searchTool(rag).execute(KnowledgeTools.SearchInput("营卫"), blank).exit
        absent <- KnowledgeTools.searchTool(rag).execute(KnowledgeTools.SearchInput("营卫"), context).exit
      yield assertTrue(missing.isLeft, empty.isLeft, denied.isFailure, absent.isFailure)
    }.provide(memoryRag),
    test("低证据或未命中时输出指导性 guidance 建议") {
      for
        rag <- ZIO.service[RagApplication]
        search = KnowledgeTools.searchTool(rag)
        miss <- search.execute(KnowledgeTools.SearchInput("未曾收录的生僻词"), openLibrary)
      yield assertTrue(
        miss.guidance.nonEmpty,
        miss.guidance.get.contains("建议")
      )
    }.provide(memoryRag),
    test("fetch 支持 expandNeighbors 扩展相邻块") {
      for
        rag <- ZIO.service[RagApplication]
        tenant = TenantId("acme")
        _ <- rag.ingestOne(
          DocumentIngestionRequest(
            DocumentInput.fromBytes(
              "multi-chunk.md",
              "book://multi",
              "multi-chunk.md",
              "text/markdown",
              Chunk.fromArray(("# 卷一\n\n第一段论述。\n\n" * 20 + "# 卷二\n\n第二段论述。\n\n" * 20).getBytes("UTF-8"))
            ),
            tenant,
            Set("knowledge:read"),
            "tools-ingest-multi"
          )
        )
        search = KnowledgeTools.searchTool(rag)
        found <- search.execute(KnowledgeTools.SearchInput("第一段论述"), openLibrary)
        chunkId = found.citations.head.chunkId
        fetch   = KnowledgeTools.fetchTool(rag)
        fetched <- fetch.execute(
          KnowledgeTools.FetchInput(chunkId, expandNeighbors = Some(true)),
          openLibrary
        )
      yield assertTrue(
        fetched.status == "ok",
        fetched.excerpts.nonEmpty
      )
    }.provide(memoryRag),
    test("outlineTool 固定当前 Profile 后读取目录，并拒绝越出宿主范围") {
      for
        rag      <- ZIO.service[RagApplication]
        _        <- ingest(rag, "outline-book", "太阳病原文")
        observed <- Ref.make(Option.empty[RetrievalScope])
        store = new StructureStore:
          def put(snapshot: StructureSnapshot) = ZIO.unit
          def get(documentId: String, indexVersion: Long, spec: StructureBuildSpec, scope: RetrievalScope) =
            ZIO.succeed(None)
          override def getActive(documentId: String, spec: StructureBuildSpec, scope: RetrievalScope) =
            observed.set(Some(scope)).as(None)
        tool = KnowledgeTools.outlineTool(store, rag = Some(rag))
        _      <- tool.execute(KnowledgeTools.OutlineInput("outline-book"), openLibrary)
        scope  <- observed.get
        denied <- tool
          .execute(
            KnowledgeTools.OutlineInput("another-book"),
            openLibrary.copy(runContext =
              openLibrary.runContext.copy(
                attributes = Map(DocumentScope.SingleAttribute -> "outline-book")
              )
            )
          )
          .exit
        after <- observed.get
      yield assertTrue(
        scope.flatMap(_.pinnedProfileId).nonEmpty,
        scope.exists(_.tenantId == TenantId("acme")),
        scope.exists(_.permissions == Set("knowledge:read")),
        denied.isFailure,
        after == scope
      )
    }.provide(memoryRag),
    test("outlineTool 返回书籍结构大纲，未找到返回 not_found") {
      val mockStore = new StructureStore:
        def put(snapshot: StructureSnapshot) = ZIO.unit
        def get(documentId: String, indexVersion: Long, spec: StructureBuildSpec, scope: RetrievalScope) =
          ZIO.succeed(None)
        override def getActive(documentId: String, spec: StructureBuildSpec, scope: RetrievalScope) =
          if documentId == "doc-mock" then
            val binding =
              StructureBinding("acme", "default", "doc-mock", "profile", 1, "ing", "rev", "a" * 64, "b" * 64)
            val node = StructureNode(
              DocumentSection("s1", None, 0, 1, "总论", Some(1), Some(5)),
              Chunk("b-s1"),
              Chunk("c-s1"),
              Some("总论概要")
            )
            ZIO.succeed(Some(StructureSnapshot(binding, StructureBuildSpec(), Chunk(node))))
          else ZIO.succeed(None)
      val tool = KnowledgeTools.outlineTool(mockStore)
      for
        found <- tool.execute(KnowledgeTools.OutlineInput("doc-mock"), openLibrary)
        miss  <- tool.execute(KnowledgeTools.OutlineInput("missing-doc"), openLibrary)
      yield assertTrue(
        found.status == "ok",
        found.outlineText.contains("总论"),
        found.totalSections == 1,
        miss.status == "not_found",
        miss.guidance.nonEmpty,
        miss.guidance.get.contains("knowledge_search")
      )
    },
    test("fetchTool 未找到 chunk 时返回专有的 fetch guidance 建议") {
      for
        rag <- ZIO.service[RagApplication]
        fetch = KnowledgeTools.fetchTool(rag)
        miss <- fetch.execute(KnowledgeTools.FetchInput("non-existent-chunk-id"), openLibrary)
      yield assertTrue(
        miss.guidance.nonEmpty,
        miss.guidance.get.contains("chunkId")
      )
    }.provide(memoryRag)
  )

  private val openLibrary = ToolExecutionContext(
    RunId(java.util.UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
    ThreadId("thread-tools"),
    "call-1",
    RunContext(
      Some("user"),
      Some("acme"),
      Set("knowledge:read"),
      DocumentScope.unrestrictedAttributes
    )
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
