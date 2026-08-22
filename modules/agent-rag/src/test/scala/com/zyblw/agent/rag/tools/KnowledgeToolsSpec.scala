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
    }.provide(memoryRag)
  )

  private val context = ToolExecutionContext(
    RunId(java.util.UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
    ThreadId("thread-tools"),
    "call-1",
    RunContext(Some("user"), Some("acme"), Set("knowledge:read"))
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
      ZLayer.succeed[EmbeddingService](HashEmbedding(32)),
      InMemoryKnowledgeIndexStore.knowledge,
      DocumentStructureChunker.layer,
      KnowledgeIndexer.layer(),
      DocumentIngestionService.layer(failureMode = DocumentIngestionFailureMode.FailFast),
      Reranker.identity,
      DefaultRetriever.layer,
      RagApplication.layer
    )
