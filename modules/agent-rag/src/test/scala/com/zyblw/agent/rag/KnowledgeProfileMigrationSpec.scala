package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.test.*

/** 换切分器后的蓝绿迁移：完整重建并评测通过才切指针，否则旧 Profile 继续服务。 */
object KnowledgeProfileMigrationSpec extends ZIOSpecDefault:
  private val tenant      = TenantId("migration-tenant")
  private val permissions = Set("knowledge:read")
  private val texts       = Map(
    "doc-a" -> "阴阳者天地之道也，万物之纲纪，变化之父母。",
    "doc-b" -> "五行相生相克，木火土金水循环往复。"
  )

  private def markdown(id: String, text: String): DocumentInput =
    DocumentInput.fromBytes(
      id,
      s"memory://$id",
      s"$id.md",
      "text/markdown",
      Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8))
    )

  private val loader = new DocumentLoader:
    override val id: String                       = "migration-md"
    override val supportedMediaTypes: Set[String] = Set("text/markdown")
    override def load(input: DocumentInput)       =
      input.content.runCollect.map(bytes =>
        SourceDocument(input.id, String(bytes.toArray, StandardCharsets.UTF_8), input.sourceUri)
      )

  private val sources = new KnowledgeSourceResolver:
    def load(tenantId: TenantId, documentId: String): UIO[Option[DocumentInput]] =
      val _ = tenantId
      ZIO.succeed(texts.get(documentId).map(markdown(documentId, _)))

  final private case class Fixture(
      store: InMemoryKnowledgeIndexStore,
      migration: KnowledgeProfileMigration,
      source: IndexProfileId,
      target: IndexProfileId
  )

  private val fixture: IO[RetrievalError, Fixture] =
    for
      store    <- InMemoryKnowledgeIndexStore.make
      registry <- DocumentLoaderRegistry.make(Chunk(loader))
      legacy = KnowledgeIndexer(SlidingWindowChunker(32, 0), HashEmbedding(4), store)
      _ <- ZIO.foreachDiscard(texts.toList.sorted) { (id, text) =>
        DocumentIngestionService(registry, legacy, failureMode = DocumentIngestionFailureMode.FailFast)
          .ingestOne(DocumentIngestionRequest(markdown(id, text), tenant, permissions, s"seed-$id"))
      }
      upgraded  = KnowledgeIndexer(SlidingWindowChunker(12, 0), HashEmbedding(4), store)
      ingestion = DocumentIngestionService(
        registry,
        upgraded,
        failureMode = DocumentIngestionFailureMode.FailFast
      )
      reindex = KnowledgeReindexService(KnowledgeIndexDirectory.inMemory(store), store, ingestion, sources)
    yield Fixture(
      store,
      KnowledgeProfileMigration(store, reindex),
      IndexProfileIds.fromSpec(legacy.buildSpec),
      IndexProfileIds.fromSpec(upgraded.buildSpec)
    )

  private def request(f: Fixture) =
    KnowledgeProfileMigrationRequest(tenant, f.target, reason = "chunk-upgrade")

  private val pass = (_: Chunk[KnowledgeIndexManifest]) =>
    ZIO.succeed(ProfileEvaluation("eval:pass", passed = true))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeProfileMigration")(
    test("完整重建并评测通过后切换，重复执行为幂等的 AlreadyActive") {
      for
        f     <- fixture
        seen  <- Ref.make(Chunk.empty[String])
        first <- f.migration.migrate(
          request(f),
          docs => seen.set(docs.map(_.build.key.documentId)) *> pass(docs)
        )
        active  <- f.store.resolveActiveProfile(tenant, KnowledgeSpaceId.Default)
        second  <- f.migration.migrate(request(f), pass)
        checked <- seen.get
      yield assertTrue(
        f.source != f.target,
        first == KnowledgeProfileMigrationOutcome.Activated(f.source, f.target, 2L, 2),
        active.contains(f.target),
        second == KnowledgeProfileMigrationOutcome.AlreadyActive(f.target),
        checked.sorted == Chunk("doc-a", "doc-b")
      )
    },
    test("评测不通过时不切换，旧 Profile 继续服务") {
      for
        f       <- fixture
        outcome <- f.migration
          .migrate(request(f), _ => ZIO.succeed(ProfileEvaluation("eval:fail", false, "doc-b")))
        active <- f.store.resolveActiveProfile(tenant, KnowledgeSpaceId.Default)
      yield assertTrue(
        outcome == KnowledgeProfileMigrationOutcome.Blocked("evaluation-failed: doc-b"),
        active.contains(f.source)
      )
    },
    test("来源缺失导致重建不完整时阻断切换") {
      for
        f        <- fixture
        registry <- DocumentLoaderRegistry.make(Chunk(loader))
        partial = new KnowledgeSourceResolver:
          def load(tenantId: TenantId, documentId: String): UIO[Option[DocumentInput]] =
            sources.load(tenantId, documentId).map(_.filter(_.id == "doc-a"))
        upgraded = KnowledgeIndexer(SlidingWindowChunker(12, 0), HashEmbedding(4), f.store)
        reindex  = KnowledgeReindexService(
          KnowledgeIndexDirectory.inMemory(f.store),
          f.store,
          DocumentIngestionService(registry, upgraded, failureMode = DocumentIngestionFailureMode.FailFast),
          partial
        )
        outcome <- KnowledgeProfileMigration(f.store, reindex).migrate(request(f), pass)
        active  <- f.store.resolveActiveProfile(tenant, KnowledgeSpaceId.Default)
      yield assertTrue(
        outcome match
          case KnowledgeProfileMigrationOutcome.Blocked("rebuild-incomplete", failures, _) =>
            failures.map(_.documentId) == Chunk("doc-b")
          case _ => false
        ,
        active.contains(f.source)
      )
    }
  )
