package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.test.*

/** 重建必须按文档原子完成：源缺失显式失败，中断后重跑不留下两个 active，也不静默跳过。 */
object KnowledgeReindexServiceSpec extends ZIOSpecDefault:
  private val tenant      = TenantId("reindex-tenant")
  private val permissions = Set("knowledge:read")

  private def countingEmbedding(
      calls: Ref[Int],
      gate: Option[Promise[Nothing, Unit]] = None
  ): EmbeddingService =
    new EmbeddingService:
      val dimension: Int                                   = 2
      override val descriptor: EmbeddingProviderDescriptor =
        EmbeddingProviderDescriptor("reindex-embed", "v1", 2, 100, supportsDimensions = false)

      def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
        calls.update(_ + 1) *>
          gate.fold(ZIO.unit)(_.await) *>
          ZIO.succeed(texts.map(text => Embedding(Chunk(text.length.toFloat, 1.0f))))

  private def markdown(id: String, text: String): DocumentInput =
    DocumentInput.fromBytes(
      id,
      s"memory://$id",
      s"$id.md",
      "text/markdown",
      Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8))
    )

  private val loader = new DocumentLoader:
    override val id: String                       = "reindex-md"
    override val supportedMediaTypes: Set[String] = Set("text/markdown")
    override def load(input: DocumentInput)       =
      input.content.runCollect.map(bytes =>
        SourceDocument(input.id, String(bytes.toArray, StandardCharsets.UTF_8), input.sourceUri)
      )

  private val unavailable: KnowledgeSourceResolver =
    new KnowledgeSourceResolver:
      def load(tenantId: TenantId, documentId: String): UIO[Option[DocumentInput]] =
        val _ = (tenantId, documentId)
        ZIO.succeed(None)

  private def sources(texts: Map[String, String]): KnowledgeSourceResolver =
    new KnowledgeSourceResolver:
      def load(tenantId: TenantId, documentId: String): IO[RetrievalError, Option[DocumentInput]] =
        val _ = tenantId
        ZIO.succeed(texts.get(documentId).map(text => markdown(documentId, text)))

  private def request: KnowledgeReindexRequest =
    KnowledgeReindexRequest(tenant, permissions, limit = 16)

  private def stack(
      store: InMemoryKnowledgeIndexStore,
      embedding: EmbeddingService,
      resolver: KnowledgeSourceResolver
  ): IO[RetrievalError, (DocumentIngestionService, KnowledgeReindexService)] =
    DocumentLoaderRegistry.make(Chunk(loader)).map { registry =>
      val ingestion = DocumentIngestionService(
        registry,
        KnowledgeIndexer(SlidingWindowChunker(32, 0), embedding, store),
        failureMode = DocumentIngestionFailureMode.FailFast
      )
      val service = KnowledgeReindexService(KnowledgeIndexDirectory.inMemory(store), ingestion, resolver)
      (ingestion, service)
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeReindexService")(
    test("源缺失时报告 SourceUnavailable，已发布 active 不变") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        pair  <- stack(store, countingEmbedding(calls), unavailable)
        (ingestion, service) = pair
        seeded <- ingestion.ingestOne(
          DocumentIngestionRequest(markdown("doc-a", "阴阳者天地之道"), tenant, permissions, "seed-a")
        )
        before <- store.active(KnowledgeDocumentKey(tenant, "doc-a"))
        report <- service.reindex(request)
        after  <- store.active(KnowledgeDocumentKey(tenant, "doc-a"))
        billed <- calls.get
      yield assertTrue(
        seeded.isInstanceOf[DocumentIngestionOutcome.Indexed],
        report.items == Chunk(KnowledgeReindexItem("doc-a", KnowledgeReindexStatus.SourceUnavailable)),
        after.map(_.build.version) == before.map(_.build.version),
        after.exists(_.active),
        billed == 1
      )
    },
    test("源可用时发布新版本，同一文档只保留一个 active") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        pair  <- stack(store, countingEmbedding(calls), sources(Map("doc-a" -> "阴阳者天地之道也")))
        (ingestion, service) = pair
        _ <- ingestion.ingestOne(
          DocumentIngestionRequest(markdown("doc-a", "阴阳者天地之道"), tenant, permissions, "seed-a")
        )
        first     <- service.reindex(request)
        second    <- service.reindex(request)
        active    <- store.active(KnowledgeDocumentKey(tenant, "doc-a"))
        manifests <- store.manifests
        actives = manifests.filter(item => item.active && item.build.key.documentId == "doc-a")
      yield assertTrue(
        first.items.map(_.documentId) == Chunk("doc-a"),
        first.items.forall(_.status == KnowledgeReindexStatus.Reindexed),
        second.items.map(_.documentId) == Chunk("doc-a"),
        second.items.forall(_.status == KnowledgeReindexStatus.Reindexed),
        active.exists(_.build.version >= 2L),
        actives.length == 1
      )
    },
    test("中断进行中的重建后重跑，不会留下两个 active") {
      for
        store    <- InMemoryKnowledgeIndexStore.make
        calls    <- Ref.make(0)
        started  <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        registry <- DocumentLoaderRegistry.make(Chunk(loader))
        blocked = new EmbeddingService:
          val dimension: Int                                   = 2
          override val descriptor: EmbeddingProviderDescriptor =
            EmbeddingProviderDescriptor("reindex-blocked", "v1", 2, 100, supportsDimensions = false)
          def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
            calls.update(_ + 1) *>
              started.succeed(()) *>
              release.await *>
              ZIO.succeed(texts.map(text => Embedding(Chunk(text.length.toFloat, 1.0f))))
        seedIngestion = DocumentIngestionService(
          registry,
          KnowledgeIndexer(SlidingWindowChunker(32, 0), HashEmbedding(2), store),
          failureMode = DocumentIngestionFailureMode.FailFast
        )
        _ <- seedIngestion.ingestOne(
          DocumentIngestionRequest(markdown("doc-a", "阴阳者天地之道"), tenant, permissions, "seed-a")
        )
        blockedIngestion = DocumentIngestionService(
          registry,
          KnowledgeIndexer(SlidingWindowChunker(32, 0), blocked, store),
          failureMode = DocumentIngestionFailureMode.FailFast
        )
        blockedService = KnowledgeReindexService(
          KnowledgeIndexDirectory.inMemory(store),
          blockedIngestion,
          sources(Map("doc-a" -> "阴阳者天地之道也"))
        )
        fiber <- blockedService.reindex(request).fork
        _     <- started.await
        _     <- fiber.interrupt
        _     <- release.succeed(())
        pair  <- stack(store, countingEmbedding(calls), sources(Map("doc-a" -> "阴阳者天地之道也")))
        replay = pair._2
        report    <- replay.reindex(request)
        manifests <- store.manifests
        actives = manifests.filter(item => item.active && item.build.key.documentId == "doc-a")
      yield assertTrue(
        report.items.map(_.documentId).distinct == Chunk("doc-a"),
        report.items.forall(_.status == KnowledgeReindexStatus.Reindexed),
        actives.length == 1
      )
    },
    test("并行重建同一文档不会留下两个 active") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        pair  <- stack(store, countingEmbedding(calls), sources(Map("doc-a" -> "阴阳者天地之道也")))
        (ingestion, service) = pair
        _ <- ingestion.ingestOne(
          DocumentIngestionRequest(markdown("doc-a", "阴阳者天地之道"), tenant, permissions, "seed-a")
        )
        _         <- service.reindex(request).zipPar(service.reindex(request))
        manifests <- store.manifests
        actives = manifests.filter(item => item.active && item.build.key.documentId == "doc-a")
      yield assertTrue(actives.length == 1)
    }
  )
