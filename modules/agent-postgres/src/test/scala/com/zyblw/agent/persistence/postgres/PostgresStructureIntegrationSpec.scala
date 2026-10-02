package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

object PostgresStructureIntegrationSpec extends ZIOSpecDefault:
  private val database = ZLayer.scoped {
    for
      container <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val image =
          DockerImageName.parse("pgvector/pgvector:0.8.6-pg18-bookworm").asCompatibleSubstituteFor("postgres")
        val value = PostgreSQLContainer(dockerImageNameOverride = image)
        value.start()
        value
      })(value => ZIO.attemptBlocking(value.stop()).orDie)
      ds <- ZIO.attempt {
        val value = PGSimpleDataSource()
        value.setURL(container.jdbcUrl)
        value.setUser(container.username)
        value.setPassword(container.password)
        value: DataSource
      }
      _ <- AgentPostgresMigrations.migrateKnowledge1024(ds)
    yield ds
  }

  def spec = suite("Postgres structure")(
    test(
      "Paddle pages/tables and summaries survive database publication, interrupted staging, replay and reasoned citation retrieval"
    ) {
      (for
        ds <- ZIO.service[DataSource]
        index = PostgresKnowledgeIndexStore(ds, 1024)
        trees = PostgresStructureStore(ds)
        parsed <- ZIO
          .fromEither(
            PaddleOcrVlDocument.decode(
              """[{"page_index":0,"page_count":2,"prunedResult":{"parsing_res_list":[{"block_id":1,"block_label":"doc_title","block_content":"医书"},{"block_id":2,"block_label":"text","block_content":"太阳病的原文证据。"}]}},{"page_index":1,"page_count":2,"prunedResult":{"parsing_res_list":[{"block_id":3,"block_label":"table","block_content":"<table><tr><th>名称</th><th>证候</th></tr><tr><td>原文</td><td>太阳病</td></tr></table>"}]}}]""",
              expectedPageCount = Some(2)
            )
          )
          .mapError(AgentError.RetrievalFailed(_))
        document = parsed.toSourceDocument("paddle-book", "book://paddle.pdf")
        tenant   = TenantId("paddle-db")
        calls <- Ref.make(0)
        summary = new NodeSummarizer:
          def summarize(
              section: DocumentSection,
              blocks: Chunk[DocumentBlock],
              isLeaf: Boolean,
              spec: NodeSummarySpec
          ) =
            calls.updateAndGet(_ + 1).map(i => Some(s"navigation $i"))
        spec = StructureBuildSpec(summary =
          Some(NodeSummarySpec("contract", "summary", mode = SummaryMode.Full))
        )
        vector = Embedding(Chunk.fromArray(Array.fill[Float](1024)(1.0f)))
        model  = EmbeddingModel.stub(dimension = 1024, onEmbed = texts => ZIO.succeed(texts.map(_ => vector)))
        staging = StructureIndexing(trees, StructurePublicationPolicy.Required, spec, Some(summary))
        indexer = KnowledgeIndexer(
          DocumentStructureChunker(),
          model,
          index,
          structureIndexing = Some(staging)
        )
        first         <- indexer.index(document, tenant, Set("read"), "first")
        _             <- indexer.index(document, tenant, Set("read"), "first")
        originalCalls <- calls.get
        scope = RetrievalScope(tenant, Set("read")).withPinnedProfile(first.manifest.build.profileId)
        active    <- trees.getActive(document.id, spec, scope)
        published <- PostgresPgVectorStore(ds, 1024)
          .fetchChunks(active.get.nodes.flatMap(_.chunkIds).toSet, scope)
          .map(_.map(c => IndexedChunk(c, vector)))
        original = first.manifest.build
        resumed <- index.begin(
          BeginKnowledgeIndex(
            original.key,
            "resume",
            document.sourceUri,
            original.lineage,
            Set("read"),
            first.manifest.metadata,
            original.buildSpec
          )
        )
        chunks = published.map(_.chunk.copy(catalogVersion = resumed.version))
        _            <- staging.stage(document, resumed, chunks)
        hidden       <- trees.get(document.id, resumed.version, spec, scope)
        _            <- staging.stage(document, resumed, chunks)
        resumedCalls <- calls.get
        _ <- index.stage(resumed, published.zip(chunks).map((item, chunk) => item.copy(chunk = chunk)))
        _ <- index.activate(resumed, ChunkSetDigest.of(chunks))
        _ <- staging.stage(document, resumed, chunks)
        totalCalls <- calls.get
        reranker = new Reranker:
          def rerank(q: String, hits: Chunk[RetrievalHit], n: Int) = ZIO.succeed(hits.take(n))
        retriever = DefaultRetriever(
          model,
          PostgresPgVectorStore(ds, 1024),
          reranker,
          structural = Some(StructuralRetrieval(trees, spec))
        )
        result <- retriever.retrieve(
          RetrievalRequest(
            "太阳病",
            scope,
            4,
            filter = RetrievalFilter(documentIds = Set(document.id)),
            recipe = Some(RetrievalRecipe.BookDeep)
          )
        )
        denied <- trees.getActive(document.id, spec, scope.copy(permissions = Set("other")))
      yield assertTrue(
        active.nonEmpty,
        hidden.isEmpty,
        denied.isEmpty,
        originalCalls == active.get.nodes.length,
        resumedCalls == originalCalls * 2,
        totalCalls == resumedCalls,
        result.hits.nonEmpty,
        result.citations.exists(_.pageNumbers.contains(2)),
        result.diagnostics.structureSelections.nonEmpty,
        result.hits.forall(_.chunk.catalogVersion == resumed.version),
        result.citations.forall(c =>
          c.chunkId.exists(id =>
            result.hits.exists(h => h.chunk.id == id && h.chunk.displayText.contains(c.excerpt))
          )
        )
      )).provideLayer(database)
    },
    test("atomic idempotence, ACL, immutable generation, publication replacement and retention cascade") {
      (for
        ds <- ZIO.service[DataSource]
        index      = PostgresKnowledgeIndexStore(ds, 1024)
        structures = PostgresStructureStore(ds)
        tenant     = TenantId("structure-db")
        block      = DocumentBlock(
          "p",
          Some("chapter"),
          0,
          DocumentBlockKind.Paragraph,
          "原文证据",
          Chunk("章节"),
          Chunk(DocumentOrigin(1))
        )
        section  = DocumentSection("chapter", None, 0, 1, "章节", Some(1), Some(1))
        document = SourceDocument(
          "book",
          block.text,
          "book://public",
          structure = Some(DocumentStructure("contract", Some("1"), Chunk(block), Chunk(section)))
        )
        chunker = DocumentStructureChunker(DocumentStructureChunkerConfig(maxTokens = None))
        vector  = Embedding(Chunk.fromArray(Array.fill[Float](1024)(1.0f)))
        model = EmbeddingModel.stub(dimension = 1024, onEmbed = texts => ZIO.succeed(texts.map(_ => vector)))
        indexer = KnowledgeIndexer(
          chunker,
          model,
          index,
          structureIndexing = Some(StructureIndexing(structures))
        )
        first <- indexer.index(document, tenant, Set("read"), "first")
        scope = RetrievalScope(tenant, Set("read")).withPinnedProfile(first.manifest.build.profileId)
        tree <- structures
          .get("book", first.manifest.build.version, StructureBuildSpec(), scope)
          .someOrFail(AgentError.RetrievalFailed("tree missing"))
        _       <- structures.put(tree)
        denied  <- structures.get("book", 1, tree.spec, scope.copy(permissions = Set.empty))
        foreign <- structures
          .get("book", 1, tree.spec, scope.copy(knowledgeSpaceId = Some(KnowledgeSpaceId("foreign"))))
        conflict <- structures
          .put(tree.copy(nodes = tree.nodes.map(n => n.copy(section = n.section.copy(title = "changed")))))
          .either
        // Independent structure rebuild reuses the published chunk set.
        rebuilt = tree.copy(spec = StructureBuildSpec("canonical-sections-v2"))
        _         <- structures.put(rebuilt)
        v2        <- structures.get("book", 1, rebuilt.spec, scope)
        second    <- indexer.index(document, tenant, Set("read"), "second")
        old       <- structures.get("book", 1, tree.spec, scope)
        current   <- structures.get("book", second.manifest.build.version, tree.spec, scope)
        _         <- index.withdraw(second.manifest.build.key, second.manifest.build.lineage.sourceRevisionId)
        withdrawn <- structures.get("book", second.manifest.build.version, tree.spec, scope)
        _         <- index.purgeInactive(java.time.Instant.now().plusSeconds(60), 100)
        rows      <- ZIO.attemptBlocking {
          val c = ds.getConnection
          try
            val statement = c.createStatement()
            try
              val result = statement.executeQuery(
                "SELECT count(*) FROM zyblw_agent_knowledge.agent_knowledge_structures"
              )
              try
                result.next()
                result.getLong(1)
              finally result.close()
            finally statement.close()
          finally c.close()
        }
      yield assertTrue(
        denied.isEmpty,
        foreign.isEmpty,
        conflict.isLeft,
        v2.contains(rebuilt),
        old.isEmpty,
        current.nonEmpty,
        withdrawn.isEmpty,
        rows == 0L
      )).provideLayer(database)
    }
  ) @@ PostgresIntegrationAspect.enabled @@ TestAspect.withLiveClock @@ TestAspect.timeout(3.minutes)
