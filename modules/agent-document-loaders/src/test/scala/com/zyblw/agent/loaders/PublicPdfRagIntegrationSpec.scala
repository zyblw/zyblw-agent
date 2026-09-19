package com.zyblw.agent.loaders

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import zio.*
import zio.test.*

/** Opt-in evidence that a public, real-world PDF crosses the complete Loader -> structured chunker -> index
  * publication -> hybrid retrieval path. The downloader pins the bytes so upstream content drift fails
  * closed.
  */
object PublicPdfRagIntegrationSpec extends ZIOSpecDefault:
  private val ExpectedSha256        = "82dd470712ce8389f19f20eb9330475e2166a281f8c7990a9f1d0763d73b4d22"
  private val OpenRagPositiveSha256 = "82252bbfb0f00b47d1c9110f2004489b3ca8687031394b6f98c5278c14fe5888"
  private val OpenRagNegativeSha256 = "68eca45ee66f6c88de378e13ddae531e241f362a4db24ceecf5d34297928fd33"

  private def setting(name: String): Option[String] =
    sys.env
      .get(name)
      .orElse(Option(java.lang.System.getProperty(name)))
      .map(_.trim)
      .filter(_.nonEmpty)

  private val enabled = setting("RUN_PUBLIC_PDF_INTEGRATION").contains("1")

  private def fixturePath: Task[Path] =
    ZIO
      .fromOption(setting("PUBLIC_PDF_FIXTURE"))
      .orElseFail(IllegalArgumentException("PUBLIC_PDF_FIXTURE is required"))
      .map(Path.of(_))

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x".format(_))
      .mkString

  private def input(bytes: Chunk[Byte]): DocumentInput =
    pdfInput(
      "open-rag-docling-2408.09869",
      "book://public-fixture/docling-technical-report",
      "docling-technical-report.pdf",
      bytes
    )

  private def pdfInput(
      id: String,
      sourceUri: String,
      fileName: String,
      bytes: Chunk[Byte]
  ): DocumentInput =
    DocumentInput.fromBytes(
      id = id,
      sourceUri = sourceUri,
      fileName = fileName,
      declaredMediaType = "application/pdf",
      bytes = bytes
    )

  private def readFixture(envName: String, expectedSha: String): Task[Chunk[Byte]] =
    for
      path <- ZIO
        .fromOption(setting(envName))
        .orElseFail(IllegalArgumentException(s"$envName is required"))
        .map(Path.of(_))
      raw <- ZIO.attemptBlocking(Files.readAllBytes(path))
      _   <- ZIO
        .fail(IllegalArgumentException(s"$envName checksum mismatch"))
        .unless(
          sha256(raw) == expectedSha
        )
    yield Chunk.fromArray(raw)

  private def application(
      store: InMemoryKnowledgeIndexStore,
      loader: DocumentLoader
  ): IO[RetrievalError, RagApplication] =
    for registry <- DocumentLoaderRegistry.make(Chunk(loader))
    yield
      val embeddings = HashEmbedding(256)
      val ingestion  = DocumentIngestionService(
        registry,
        KnowledgeIndexer(DocumentStructureChunker(), embeddings, store),
        failureMode = DocumentIngestionFailureMode.FailFast
      )
      val retriever = DefaultRetriever(
        embeddings,
        store,
        new Reranker:
          def rerank(
              query: String,
              hits: Chunk[RetrievalHit],
              limit: Int
          ): UIO[Chunk[RetrievalHit]] = ZIO.succeed(hits.take(limit))
      )
      RagApplication(ingestion, retriever, RagApplicationConfig(defaultTopK = 5, maxTopK = 10))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("public PDF RAG integration")(
    test("Docling technical report preserves pages and is retrievable after publication") {
      for
        path <- fixturePath
        raw  <- ZIO.attemptBlocking(Files.readAllBytes(path))
        _    <- ZIO
          .fail(IllegalArgumentException("public PDF checksum mismatch"))
          .unless(
            sha256(raw) == ExpectedSha256
          )
        bytes  = Chunk.fromArray(raw)
        loader = TikaDocumentLoader()
        document <- loader.load(input(bytes))
        store    <- InMemoryKnowledgeIndexStore.make
        app      <- application(store, loader)
        outcome  <- app.ingestOne(
          DocumentIngestionRequest(
            input(bytes),
            TenantId("public-pdf-eval"),
            Set("knowledge:read"),
            "open-rag-docling-2408.09869-v1"
          )
        )
        result <- app.retrieve(
          RagQuery(
            "geometric coordinates visual representation PDF backend",
            RetrievalScope(TenantId("public-pdf-eval"), Set("knowledge:read"), Some("public-pdf-query")),
            Some(5),
            RetrievalMode.Hybrid
          )
        )
      yield assertTrue(
        document.text.contains("Docling Technical Report"),
        document.text.contains("geometric coordinates"),
        document.metadata.get("pageCount").exists(_.toInt >= 8),
        document.structure.exists(_.blocks.exists(_.origins.exists(_.pageNumber > 1))),
        outcome.isInstanceOf[DocumentIngestionOutcome.Indexed],
        result.hits.nonEmpty,
        result.hits.head.chunk.documentId == "open-rag-docling-2408.09869",
        result.citations.exists(_.sourceUri == "book://public-fixture/docling-technical-report")
      )
    },
    test("Open RAG Benchmark qrel ranks the gold PDF above a fixed negative") {
      for
        positive <- readFixture("OPEN_RAG_BENCH_POSITIVE_PDF", OpenRagPositiveSha256)
        negative <- readFixture("OPEN_RAG_BENCH_NEGATIVE_PDF", OpenRagNegativeSha256)
        loader = TikaDocumentLoader()
        store <- InMemoryKnowledgeIndexStore.make
        app   <- application(store, loader)
        tenant     = TenantId("open-rag-bench-eval")
        scope      = Set("knowledge:read")
        positiveId = "2404.08757v2"
        _ <- app.ingestOne(
          DocumentIngestionRequest(
            pdfInput(
              positiveId,
              s"book://open-rag-bench/$positiveId",
              s"$positiveId.pdf",
              positive
            ),
            tenant,
            scope,
            s"open-rag-bench-$positiveId-v1"
          )
        )
        negativeId = "2407.01528v3"
        _ <- app.ingestOne(
          DocumentIngestionRequest(
            pdfInput(
              negativeId,
              s"book://open-rag-bench/$negativeId",
              s"$negativeId.pdf",
              negative
            ),
            tenant,
            scope,
            s"open-rag-bench-$negativeId-v1"
          )
        )
        result <- app.retrieve(
          RagQuery(
            "In what ways do large financial institutions influence market equilibrium prices?",
            RetrievalScope(tenant, scope, Some("open-rag-bench-qrel")),
            Some(5),
            RetrievalMode.Hybrid
          )
        )
      yield assertTrue(
        result.hits.nonEmpty,
        result.hits.head.chunk.documentId == positiveId,
        result.citations.head.sourceUri == s"book://open-rag-bench/$positiveId"
      )
    }
  ).when(enabled) @@ TestAspect.timeout(2.minutes)
