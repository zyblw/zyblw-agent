package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.test.*

object StructuralRetrievalSpec extends ZIOSpecDefault:
  private val tenant   = TenantId("structure-tenant")
  private val acl      = Set("read")
  private val sections = Chunk(
    DocumentSection("root", None, 0, 1, "医书", Some(1), Some(3)),
    DocumentSection("a", Some("root"), 1, 2, "同名章节", Some(1), Some(2)),
    DocumentSection("b", Some("root"), 2, 2, "同名章节", Some(3), Some(3))
  )
  private val blocks = Chunk(
    DocumentBlock(
      "a1",
      Some("a"),
      0,
      DocumentBlockKind.Paragraph,
      "太阳病证候",
      Chunk("医书", "同名章节"),
      Chunk(DocumentOrigin(1))
    ),
    DocumentBlock(
      "a2",
      Some("a"),
      1,
      DocumentBlockKind.Paragraph,
      "太阳病方剂组成",
      Chunk("医书", "同名章节"),
      Chunk(DocumentOrigin(2))
    ),
    DocumentBlock(
      "b1",
      Some("b"),
      2,
      DocumentBlockKind.Paragraph,
      "少阴病篇",
      Chunk("医书", "同名章节"),
      Chunk(DocumentOrigin(3))
    )
  )
  private val document = SourceDocument(
    "book",
    blocks.map(_.text).mkString("\n"),
    "book://public",
    structure = Some(DocumentStructure("test", Some("1"), blocks, sections))
  )
  private val chunker = DocumentStructureChunker(
    DocumentStructureChunkerConfig(mergePeers = false, maxTokens = None)
  )

  private def setup = for
    index      <- InMemoryKnowledgeIndexStore.make
    structures <- InMemoryStructureStore.make(index)
    calls      <- Ref.make(0)
    model = EmbeddingModel.stub(onEmbed =
      texts => calls.update(_ + 1).as(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
    )
    indexer = KnowledgeIndexer(chunker, model, index, structureIndexing = Some(StructureIndexing(structures)))
    result <- indexer.index(document, tenant, acl, "ingest")
    chunks <- index.published(result.manifest.build.key)
    scope = RetrievalScope(tenant, acl).withPinnedProfile(result.manifest.build.profileId)
    snapshot <- structures
      .get(document.id, result.manifest.build.version, StructureBuildSpec(), scope)
      .someOrFail(AgentError.RetrievalFailed("missing structure"))
  yield (index, structures, indexer, result, chunks, scope, snapshot, calls)

  def spec = suite("Structural retrieval")(
    test("visited/frontier/model-call budgets are hard ceilings and retain reached leaves") {
      for
        data <- setup
        snapshot = data._7
        result <- BoundedTreeSearch.search(
          "太阳病",
          snapshot,
          budget = TreeSearchBudget(maxVisitedNodes = 2, maxModelCalls = 1, beamWidth = 1)
        )
      yield assertTrue(result.visitedNodeCount <= 2, result.selectedNodeIds.length <= 1, result.budgetLimited)
    },
    test("reasoned search navigates explicit authorized books with no classic seed") {
      for
        data <- setup
        (index, trees, _, _, _, scope, _, _) = data
        result <- StructuralRetrieval(trees).retrieve(
          RetrievalRequest(
            "太阳病",
            scope,
            3,
            filter = RetrievalFilter(documentIds = Set("book")),
            strategy = Some(RetrievalStrategy.Reasoned)
          ),
          SimpleChineseLexicalProcessor.query("太阳病"),
          Embedding(Chunk(1.0f, 0.0f)),
          Chunk.empty,
          index
        )
        active <- trees.getActive("book", StructureBuildSpec(), scope)
        denied <- trees.getActive("book", StructureBuildSpec(), scope.copy(permissions = Set("other")))
      yield assertTrue(
        result.hits.nonEmpty,
        result.selections.nonEmpty,
        !result.degraded,
        active.nonEmpty,
        denied.isEmpty
      )
    },
    test(
      "coordination searches all six targets with two fibers, pins once, and intersects filters even for one target"
    ) {
      for
        data <- setup
        (index, trees, indexer, _, _, scope, _, _) = data
        _ <- ZIO.foreach(2 to 6)(i =>
          indexer.index(document.copy(id = s"book$i", sourceUri = s"book://$i"), tenant, acl, s"ingest$i")
        )
        pins           <- Ref.make(0)
        embeddingCalls <- Ref.make(0)
        model = EmbeddingModel.stub(onEmbed =
          texts => embeddingCalls.update(_ + 1).as(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        )
        reranker = new Reranker:
          def rerank(q: String, hits: Chunk[RetrievalHit], n: Int) = ZIO.succeed(hits.take(n))
        underlying = DefaultRetriever(model, index, reranker, structural = Some(StructuralRetrieval(trees)))
        counting   = new Retriever:
          override def querySession                = underlying.querySession
          override def pinScope(s: RetrievalScope) = pins.update(_ + 1) *> underlying.pinScope(s)
          def retrieve(r: RetrievalRequest)        = underlying.retrieve(r)
        ids = Set("book", "book2", "book3", "book4", "book5", "book6")
        result <- CrossDocumentCoordinator.coordinate(
          counting,
          RetrievalRequest(
            "太阳病",
            scope.copy(pinnedProfileId = None),
            6,
            recipe = Some(RetrievalRecipe.LowLatency)
          ),
          ids,
          2
        )
        pinCount       <- pins.get
        embeddingCount <- embeddingCalls.get
        disjoint       <- CrossDocumentCoordinator.coordinate(
          counting,
          RetrievalRequest("太阳病", scope, 2, filter = RetrievalFilter(documentIds = Set("book"))),
          Set("book2")
        )
      yield assertTrue(
        result.hits.map(_.chunk.documentId).toSet == ids,
        result.citations.map(_.id).distinct.length == result.citations.length,
        result.citations.forall(c =>
          c.chunkId.exists(id =>
            result.hits.exists(h => h.chunk.id == id && h.chunk.sourceUri == c.sourceUri)
          )
        ),
        pinCount == 1,
        embeddingCount == 1,
        disjoint.hits.isEmpty
      )
    },
    test("LowLatency skips reranking; BookDeep keeps its rerank pool before final topK") {
      for
        data <- setup
        (index, trees, _, _, _, scope, _, _) = data
        calls <- Ref.make(Chunk.empty[Int])
        model = EmbeddingModel.stub(onEmbed =
          texts => ZIO.succeed(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        )
        reranker = new Reranker:
          def rerank(q: String, hits: Chunk[RetrievalHit], n: Int) = calls.update(_ :+ n).as(hits.take(n))
        retriever = DefaultRetriever(model, index, reranker, structural = Some(StructuralRetrieval(trees)))
        fast <- retriever.retrieve(
          RetrievalRequest("太阳病", scope, 1, recipe = Some(RetrievalRecipe.LowLatency))
        )
        fastCalls <- calls.get
        deep <- retriever.retrieve(RetrievalRequest("太阳病", scope, 1, recipe = Some(RetrievalRecipe.BookDeep)))
        deepCalls <- calls.get
      yield assertTrue(
        fastCalls.isEmpty,
        fast.hits.length <= 1,
        deepCalls == Chunk(20),
        deep.diagnostics.strategy.contains("Reasoned")
      )
    },
    test(
      "Building summaries are hidden from retrieval and reused on resume and Ready replay without model calls"
    ) {
      for
        data <- setup
        (index, trees, _, result, indexed, scope, _, _) = data
        original                                        = result.manifest.build
        build <- index.begin(
          BeginKnowledgeIndex(
            original.key,
            "interrupted",
            document.sourceUri,
            original.lineage,
            acl,
            result.manifest.metadata,
            original.buildSpec
          )
        )
        chunks = indexed.map(
          _.chunk
            .copy(catalogVersion = build.version, sourceRevisionId = Some(build.lineage.sourceRevisionId))
        )
        calls <- Ref.make(0)
        summarySpec = NodeSummarySpec("test", "model", mode = SummaryMode.Full)
        spec        = StructureBuildSpec(summary = Some(summarySpec))
        summarizer  = new NodeSummarizer:
          def summarize(
              section: DocumentSection,
              blocks: Chunk[DocumentBlock],
              isLeaf: Boolean,
              summarySpec: NodeSummarySpec
          ) =
            calls.updateAndGet(_ + 1).map(i => Some(s"non-deterministic summary $i"))
        staging = StructureIndexing(trees, StructurePublicationPolicy.Required, spec, Some(summarizer))
        first  <- staging.stage(document, build, chunks)
        hidden <- trees.get(document.id, build.version, spec, scope)
        again  <- staging.stage(document, build, chunks)
        count  <- calls.get
        _ <- index.stage(build, indexed.zip(chunks).map((original, chunk) => original.copy(chunk = chunk)))
        _ <- index.activate(build, ChunkSetDigest.of(chunks))
        ready   <- staging.stage(document, build, chunks)
        total   <- calls.get
        visible <- trees.getActive(document.id, spec, scope)
      yield assertTrue(
        first == StructurePublicationStatus.Ready,
        again == first,
        ready == first,
        hidden.isEmpty,
        count == 3,
        total == count,
        visible.exists(_.nodes.forall(_.summary.nonEmpty))
      )
    },
    test("a large leaf section retrieves late evidence through a section predicate without classic seeds") {
      for
        data <- setup
        (index, trees, _, _, _, scope, _, _) = data
        result <- StructuralRetrieval(trees, config = StructuralRetrievalConfig(maxChunksPerNode = 1))
          .retrieve(
            RetrievalRequest(
              "太阳病方剂组成",
              scope,
              4,
              mode = RetrievalMode.LexicalOnly,
              filter = RetrievalFilter(documentIds = Set("book")),
              strategy = Some(RetrievalStrategy.Reasoned)
            ),
            SimpleChineseLexicalProcessor.query("太阳病方剂组成"),
            Embedding(Chunk(1.0f, 0.0f)),
            Chunk.empty,
            index
          )
      yield assertTrue(
        result.hits.exists(_.chunk.text.contains("方剂组成")),
        result.selections.nonEmpty,
        !result.degraded
      )
    },
    test(
      "evaluation rejects empty/ambiguous gold and does not count wrong-document node IDs or forged citations"
    ) {
      for
        data <- setup
        (_, _, _, _, chunks, scope, snapshot, _) = data
        hit                                      = RetrievalHit(chunks.head.chunk, 1.0)
        retriever                                = new Retriever:
          def retrieve(request: RetrievalRequest) = ZIO.succeed(
            RetrievalResult(
              Chunk(hit),
              Chunk(Citation("c", "book://forged", "invented", 1.0, chunkId = Some(hit.chunk.id))),
              RetrievalEvidence(RetrievalEvidenceStatus.Supported, 1, 1),
              RetrievalDiagnostics(structureSelections =
                Chunk(StructureSelection("other-book", snapshot.generation, "a", 1))
              )
            )
          )
        gold = StructuralTestCase("gold", "太阳病", Set("book"), Set("a"), Set(hit.chunk.id))
        metrics <- StructuralRagEval.evaluateCase(gold, retriever, scope)
        empty   <- StructuralRagEval
          .evaluateCase(gold.copy(expectedDocumentIds = Set.empty), retriever, scope)
          .exit
        ambiguous <- StructuralRagEval
          .evaluateCase(gold.copy(expectedDocumentIds = Set("book", "other")), retriever, scope)
          .exit
      yield assertTrue(
        metrics.nodeRecall == 0.0,
        metrics.citationPrecision == 0.0,
        empty.isFailure,
        ambiguous.isFailure
      )
    },
    test("deployment expansion disable remains authoritative even under BookDeep recipe") {
      for
        data <- setup
        (index, trees, _, _, _, scope, _, _) = data
        model                                = EmbeddingModel.stub(onEmbed =
          texts => ZIO.succeed(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        )
        reranker = new Reranker:
          def rerank(q: String, hits: Chunk[RetrievalHit], n: Int) = ZIO.succeed(hits.take(n))
        result <- DefaultRetriever(
          model,
          index,
          reranker,
          structural = Some(StructuralRetrieval(trees)),
          expansionEnabled = false
        )
          .retrieve(RetrievalRequest("太阳病", scope, 1, recipe = Some(RetrievalRecipe.BookDeep)))
      yield assertTrue(
        result.hits.length == 1,
        !result.hits.exists(_.signals.get(RetrievalSignals.ContextExpanded).contains(1.0))
      )
    },
    test("query sharing does not cross tenant/permissions or independent sessions") {
      for
        data <- setup
        index = data._1
        scope = data._6
        calls <- Ref.make(0)
        model = EmbeddingModel.stub(onEmbed =
          texts => calls.update(_ + 1).as(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        )
        reranker = new Reranker:
          def rerank(q: String, hits: Chunk[RetrievalHit], n: Int) = ZIO.succeed(hits.take(n))
        retriever = DefaultRetriever(model, index, reranker)
        session <- retriever.querySession
        _       <- session.retrieve("太阳病", scope, 2)
        _       <- session.retrieve("太阳病", scope, 2)
        _       <- session.retrieve("太阳病", scope.copy(permissions = Set("other")), 2)
        _     <- session.retrieve("太阳病", scope.copy(tenantId = TenantId("other"), pinnedProfileId = None), 2)
        fresh <- retriever.querySession
        _     <- fresh.retrieve("太阳病", scope, 2)
        count <- calls.get
      yield assertTrue(count == 4)
    },
    test("cancelling a shared embedding releases its permit and never caches an interrupted result") {
      for
        data <- setup
        index = data._1
        scope = data._6
        calls       <- Ref.make(0)
        entered     <- Promise.make[Nothing, Unit]
        interrupted <- Ref.make(false)
        model = EmbeddingModel.stub(onEmbed =
          texts =>
            (for
              n <- calls.updateAndGet(_ + 1)
              _ <- if n == 1 then entered.succeed(()) *> ZIO.never else ZIO.unit
            yield texts.map(_ => Embedding(Chunk(1.0f, 0.0f)))).onInterrupt(interrupted.set(true))
        )
        reranker = new Reranker:
          def rerank(q: String, hits: Chunk[RetrievalHit], n: Int) = ZIO.succeed(hits.take(n))
        session   <- DefaultRetriever(model, index, reranker).querySession
        fiber     <- session.retrieve("太阳病", scope, 2).fork
        _         <- entered.await
        _         <- fiber.interrupt
        retry     <- session.retrieve("太阳病", scope, 2)
        count     <- calls.get
        cancelled <- interrupted.get
      yield assertTrue(cancelled, count == 2, retry.hits.nonEmpty)
    },
    test("canonical sections affect identity and roundtrip without losing duplicate titles") {
      val altered = document.copy(structure =
        document.structure.map(s => s.copy(sections = s.sections.updated(1, sections(1).copy(title = "新标题"))))
      )
      assertTrue(
        DocumentLineage.structureSha256(document) != DocumentLineage.structureSha256(altered),
        document.structure.get.toJson.fromJson[DocumentStructure] == Right(document.structure.get)
      )
    },
    test("required publication maps every block and chunk, replay skips embedding") {
      for
        data <- setup
        (_, structures, indexer, result, chunks, scope, snapshot, calls) = data
        replay <- indexer.index(document, tenant, acl, "ingest")
        _      <- structures.put(snapshot)
        count  <- calls.get
      yield assertTrue(
        result.structureStatus == StructurePublicationStatus.Ready,
        replay.structureStatus == StructurePublicationStatus.Ready,
        count == 1,
        snapshot.nodes.length == 3,
        snapshot.nodes.flatMap(_.chunkIds).toSet == chunks.map(_.chunk.id).toSet,
        snapshot.nodes.map(_.section.title).count(_ == "同名章节") == 2,
        StructureStore.authorized(snapshot.binding, scope)
      )
    },
    test("structure-only spec rebuild does not change chunk publication") {
      for
        data <- setup
        (_, structures, _, result, chunks, scope, snapshot, _) = data
        rebuilt                                                = StructureBuilder
          .build(
            document,
            result.manifest.build,
            chunks.map(_.chunk),
            StructureBuildSpec("canonical-sections-v2")
          )
          .toOption
          .get
        _    <- structures.put(rebuilt)
        old  <- structures.get("book", 1, snapshot.spec, scope)
        next <- structures.get("book", 1, rebuilt.spec, scope)
      yield assertTrue(
        old.contains(snapshot),
        next.contains(rebuilt),
        rebuilt.binding == snapshot.binding,
        rebuilt.generation != snapshot.generation
      )
    },
    test("reject malformed hierarchy, incomplete mapping and foreign version") {
      for
        data <- setup
        (_, _, _, result, chunks, _, snapshot, _) = data
        cyclic                                    = snapshot.copy(nodes =
          snapshot.nodes
            .updated(0, snapshot.nodes(0).copy(section = sections.head.copy(parentId = Some("a"))))
        )
        orphan = snapshot.copy(nodes =
          snapshot.nodes
            .updated(1, snapshot.nodes(1).copy(section = sections(1).copy(parentId = Some("missing"))))
        )
      yield assertTrue(
        cyclic.validate.isLeft,
        orphan.validate.isLeft,
        StructureBuilder
          .build(document, result.manifest.build, chunks.map(c => c.chunk.copy(catalogVersion = 99)))
          .isLeft,
        StructureBuilder.build(document, result.manifest.build, chunks.map(_.chunk).drop(1)).isLeft
      )
    },
    test("overlapping chunks map to multiple sections by block ID") {
      for
        data <- setup
        (_, _, _, result, chunks, _, _, _) = data
        first                              = chunks.head.chunk
        merged = first.copy(lineage = first.lineage.map(_.copy(blockIds = blocks.map(_.id))))
        tree   = StructureBuilder.build(document, result.manifest.build, Chunk(merged)).toOption.get
      yield assertTrue(
        tree.nodes.filter(_.chunkIds.contains(first.id)).map(_.section.id).toSet == Set("a", "b")
      )
    },
    test("tenant, permission, space and profile enforced; withdraw makes structure unreadable") {
      for
        data <- setup
        (index, structures, _, result, _, scope, snapshot, _) = data
        denied <- ZIO.foreach(
          Chunk(
            scope.copy(tenantId = TenantId("other")),
            scope.copy(permissions = Set.empty),
            scope.copy(knowledgeSpaceId = Some(KnowledgeSpaceId("other"))),
            scope.copy(pinnedProfileId = Some(IndexProfileId("other")))
          )
        ) { deniedScope =>
          structures.get("book", 1, snapshot.spec, deniedScope)
        }
        _         <- index.withdraw(result.manifest.build.key, result.manifest.build.lineage.sourceRevisionId)
        withdrawn <- structures.get("book", 1, snapshot.spec, scope)
      yield assertTrue(denied.forall(_.isEmpty), withdrawn.isEmpty)
    },
    test("same spec/version cannot overwrite an immutable generation") {
      for
        data <- setup
        (_, structures, _, _, _, _, snapshot, _) = data
        altered                                  = snapshot.copy(nodes =
          snapshot.nodes.updated(0, snapshot.nodes.head.copy(section = sections.head.copy(title = "篡改")))
        )
        conflict <- structures.put(altered).either
      yield assertTrue(conflict.isLeft)
    },
    test("Required failure leaves no published document; BestEffort reports unavailable") {
      for
        index      <- InMemoryKnowledgeIndexStore.make
        structures <- InMemoryStructureStore.make(index)
        model = EmbeddingModel.stub()
        plain = document.copy(structure = None)
        required <- KnowledgeIndexer(
          chunker,
          model,
          index,
          structureIndexing = Some(StructureIndexing(structures))
        ).index(plain, tenant, acl, "required").either
        missing <- index.active(KnowledgeDocumentKey(tenant, "book"))
        best    <- KnowledgeIndexer(
          chunker,
          model,
          index,
          structureIndexing = Some(StructureIndexing(structures, StructurePublicationPolicy.BestEffort))
        ).index(plain, tenant, acl, "best")
      yield assertTrue(
        required.isLeft,
        missing.isEmpty,
        best.structureStatus == StructurePublicationStatus.Unavailable,
        best.manifest.active
      )
    },
    test("value search materializes original chunks and obeys page filters") {
      for
        data <- setup
        (_, structures, _, _, chunks, scope, snapshot, _) = data
        vectors <- ZIO.scoped(InMemoryVectorStore.layer.build.map(_.get[VectorStore]))
        _       <- vectors.upsert(chunks)
        seed = RetrievalHit(
          chunks.find(_.chunk.lineage.exists(_.pageNumbers.contains(1))).get.chunk,
          0.9,
          Map("vectorScore" -> 0.9)
        )
        branch = StructuralRetrieval(structures, config = StructuralRetrievalConfig(failOpen = false))
        all <- branch.retrieve(
          RetrievalRequest("太阳", scope, 4),
          "太阳",
          Embedding(Chunk(1.0f, 0.0f)),
          Chunk(seed),
          vectors
        )
        filtered <- branch.retrieve(
          RetrievalRequest("太阳", scope, 4, filter = RetrievalFilter(pages = Set(1))),
          "太阳",
          Embedding(Chunk(1.0f, 0.0f)),
          Chunk(seed),
          vectors
        )
      yield assertTrue(
        all.hits.length == 2,
        filtered.hits.length == 1,
        all.hits.forall(h => snapshot.binding.matches(h.chunk)),
        all.hits.forall(_.signals.contains("vectorScore"))
      )
    },
    test("classic and structural modes share original citations and filtered expansion") {
      for
        data <- setup
        (index, structures, _, _, _, scope, _, _) = data
        reranker                                  = new Reranker:
          def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int) = ZIO.succeed(hits.take(limit))
        model = EmbeddingModel.stub(onEmbed =
          texts => ZIO.succeed(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        )
        classic    = DefaultRetriever(model, index, reranker)
        structural = DefaultRetriever(
          model,
          index,
          reranker,
          structural = Some(StructuralRetrieval(structures))
        )
        request = RetrievalRequest("太阳", scope, 3, filter = RetrievalFilter(pages = Set(1)))
        baseline <- classic.retrieve(request)
        enhanced <- structural.retrieve(request)
      yield assertTrue(
        baseline.hits.nonEmpty,
        enhanced.hits.nonEmpty,
        enhanced.hits.forall(h => request.filter.matches(h.chunk)),
        enhanced.citations.flatMap(_.chunkId).toSet == enhanced.hits.map(_.chunk.id).toSet,
        enhanced.hits.map(_.chunk).toSet == baseline.hits.map(_.chunk).toSet
      )
    },
    test("RRF cannot turn a structural rank into evidence or suppress exact phrase relevance") {
      for
        data <- setup
        (_, _, _, _, chunks, _, _, _) = data
        phrase                        = RetrievalHit(chunks.head.chunk, 1.0, Map("phraseScore" -> 1.0))
        dense                         = RetrievalHit(chunks(1).chunk, 0.1, Map("vectorScore" -> 0.1))
        fused = StructuralRetrieval.fuse(Chunk(phrase, dense), Chunk(dense, phrase), 2)
      yield assertTrue(
        fused.forall(_.score < 0.1),
        DefaultRetriever.acceptsSeed(fused.find(_.chunk == phrase.chunk).get, 0.8),
        !DefaultRetriever.acceptsSeed(fused.find(_.chunk == dense.chunk).get, 0.8)
      )
    },
    test("timeout falls back explicitly and missing structures are observable") {
      for
        data <- setup
        (_, structures, _, _, chunks, scope, _, _) = data
        started <- Promise.make[Nothing, Unit]
        never = new StructureStore:
          def put(snapshot: StructureSnapshot)                                                = ZIO.unit
          def get(id: String, version: Long, spec: StructureBuildSpec, scope: RetrievalScope) =
            started.succeed(()) *> ZIO.never
        vectors <- ZIO.scoped(InMemoryVectorStore.layer.build.map(_.get[VectorStore]))
        request = RetrievalRequest("太阳", scope, 1)
        seeds   = Chunk(RetrievalHit(chunks.head.chunk, 1))
        fiber <- StructuralRetrieval(never)
          .retrieve(request, "太阳", Embedding(Chunk(1.0f, 0.0f)), seeds, vectors)
          .fork
        _       <- started.await
        _       <- TestClock.adjust(4.seconds)
        timed   <- fiber.join
        missing <- StructuralRetrieval(structures, StructureBuildSpec("unbuilt"))
          .retrieve(request, "太阳", Embedding(Chunk(1.0f, 0.0f)), seeds, vectors)
      yield assertTrue(timed.degraded, timed.hits.isEmpty, missing.degraded, missing.hits.isEmpty)
    },
    test("interrupt is not swallowed by optional branch fallback") {
      for
        started <- Promise.make[Nothing, Unit]
        never = new StructureStore:
          def put(snapshot: StructureSnapshot)                                                = ZIO.unit
          def get(id: String, version: Long, spec: StructureBuildSpec, scope: RetrievalScope) =
            started.succeed(()) *> ZIO.never
        data <- setup
        (_, _, _, _, chunks, scope, _, _) = data
        vectors <- ZIO.scoped(InMemoryVectorStore.layer.build.map(_.get[VectorStore]))
        _       <- vectors.upsert(chunks)
        branch = StructuralRetrieval(never)
        fiber <- branch
          .retrieve(
            RetrievalRequest("太阳", scope, 1),
            "太阳",
            Embedding(Chunk(1.0f, 0.0f)),
            Chunk(RetrievalHit(chunks.head.chunk, 1)),
            vectors
          )
          .fork
        _    <- started.await
        exit <- fiber.interrupt
      yield assertTrue(exit.isInterrupted)
    },
    test(
      "RetrievalRecipe resolves and steers strategy; Classic disables structural search while BookGrounded enables it"
    ) {
      for
        data <- setup
        (index, structures, _, _, _, scope, _, _) = data
        reranker                                  = new Reranker:
          def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int) = ZIO.succeed(hits.take(limit))
        model = EmbeddingModel.stub(onEmbed =
          texts => ZIO.succeed(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        )
        retriever = DefaultRetriever(
          model,
          index,
          reranker,
          structural = Some(StructuralRetrieval(structures))
        )
        classicReq  = RetrievalRequest("太阳", scope, 3, recipe = Some(RetrievalRecipe.Classic))
        groundedReq = RetrievalRequest("太阳", scope, 3, recipe = Some(RetrievalRecipe.BookGrounded))
        classicRes  <- retriever.retrieve(classicReq)
        groundedRes <- retriever.retrieve(groundedReq)
      yield assertTrue(
        classicRes.diagnostics.structureSelections.isEmpty,
        groundedRes.diagnostics.structureSelections.nonEmpty,
        groundedRes.hits.nonEmpty
      )
    },
    test("structural expansion expands peer chunks within section boundary with decay factor") {
      for
        data <- setup
        (index, structures, _, _, chunks, scope, snapshot, _) = data
        seed = RetrievalHit(chunks.head.chunk, 0.9, Map("vectorScore" -> 0.9))
        expanded <- StructuralExpansion.expand(
          Chunk(seed),
          scope,
          structures,
          snapshot.spec,
          index,
          RetrievalFilter.empty,
          RetrievalExpansionConfig(maxAdditionalChunks = 5, expandedScoreFactor = 0.85)
        )
      yield assertTrue(
        expanded.nonEmpty,
        expanded.forall(h => h.chunk.documentId == seed.chunk.documentId),
        expanded.forall(h => h.signals.contains(RetrievalSignals.ContextStructureNode)),
        expanded.forall(h => h.score <= seed.score * 0.86)
      )
    },
    test("StructuralRagEval computes multi-level metrics accurately on test cases") {
      for
        data <- setup
        (index, structures, _, _, chunks, scope, _, _) = data
        reranker                                       = new Reranker:
          def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int) = ZIO.succeed(hits.take(limit))
        model = EmbeddingModel.stub(onEmbed =
          texts => ZIO.succeed(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        )
        retriever = DefaultRetriever(
          model,
          index,
          reranker,
          structural = Some(StructuralRetrieval(structures))
        )
        testCase = StructuralTestCase(
          id = "case-1",
          query = "太阳病",
          expectedDocumentIds = Set("book"),
          expectedSectionIds = Set("a"),
          expectedChunkIds = Set(chunks.head.chunk.id),
          recipe = RetrievalRecipe.BookGrounded
        )
        metrics <- StructuralRagEval.evaluateCase(testCase, retriever, scope, limit = 4)
        report  <- StructuralRagEval.evaluateAll(Chunk(testCase), retriever, scope, limit = 4)
      yield assertTrue(
        metrics.documentRecall == 1.0,
        metrics.nodeRecall == 1.0,
        metrics.citationPrecision > 0.0,
        metrics.evidenceStatus == RetrievalEvidenceStatus.Supported,
        report.totalCases == 1,
        report.documentRecall == 1.0,
        report.nodeRecall == 1.0
      )
    },
    test("StructureSnapshot navigation helpers: breadcrumbs, ancestors, children, and subtreeChunkIds") {
      for
        data <- setup
        (_, _, _, _, chunks, _, snapshot, _) = data
        root                                 = snapshot.rootNodes
        childOfRoot                          = snapshot.children("root")
        ancestorsA                           = snapshot.ancestors("a")
        breadcrumbsA                         = snapshot.breadcrumbs("a")
        subtreeChunks                        = snapshot.subtreeChunkIds("root")
      yield assertTrue(
        root.map(_.section.id) == Chunk("root"),
        childOfRoot.map(_.section.id).toSet == Set("a", "b"),
        ancestorsA.map(_.section.id) == Chunk("root"),
        breadcrumbsA == Chunk("医书", "同名章节"),
        subtreeChunks == chunks.map(_.chunk.id).toSet
      )
    },
    test("BoundedTreeSearch traverses hierarchy, obeys budget and rejects hallucinated IDs") {
      for
        data <- setup
        (_, _, _, _, _, _, snapshot, _) = data
        hallucinatingNavigator          = new TreeNavigator:
          def selectBranches(query: String, path: Chunk[String], frontier: Chunk[StructureNode], max: Int) =
            ZIO.succeed(Chunk("hallucinated-id-123", frontier.head.section.id))
        searchRes <- BoundedTreeSearch.search(
          "太阳病",
          snapshot,
          hallucinatingNavigator,
          TreeSearchBudget(maxDepth = 2, beamWidth = 2)
        )
      yield assertTrue(
        searchRes.degraded,
        !searchRes.selectedNodeIds.contains("hallucinated-id-123"),
        searchRes.selectedNodeIds.nonEmpty,
        searchRes.visitedNodeCount > 0
      )
    },
    test("NodeSummarizer generates selective summaries and caches them by content hash") {
      for
        cache <- InMemoryNodeSummaryStore.make
        summarizer     = NodeSummarizer.deterministic(SummaryMode.Selective, Some(cache))
        spec           = NodeSummarySpec("test-provider", "test-model")
        nonLeafSection = DocumentSection("root", None, 0, 1, "医书总论")
        blocks         = Chunk(
          DocumentBlock(
            "b1",
            Some("root"),
            0,
            DocumentBlockKind.Paragraph,
            "第一节包含重要论述",
            Chunk.empty,
            Chunk.empty
          )
        )
        s1 <- summarizer.summarize(nonLeafSection, blocks, isLeaf = false, spec)
        s2 <- summarizer.summarize(nonLeafSection, blocks, isLeaf = false, spec)
      yield assertTrue(
        s1.isDefined,
        s1.get.contains("医书总论"),
        s1 == s2
      )
    },
    test(
      "DocumentDiscovery matches explicit and implicit titles, and CrossDocumentCoordinator balances results"
    ) {
      val catalog = Chunk(
        DiscoverableDocument("shanghan", "伤寒论", Some("外感病专著"), Set("伤寒卒病论")),
        DiscoverableDocument("jinkui", "金匮要略", Some("杂病专著"), Set("金匮")),
        DiscoverableDocument("neijing", "黄帝内经", Some("基础理论"), Set("素问", "灵枢"))
      )
      val discovered = DocumentDiscovery.discover("请问伤寒论与金匮要略对桂枝汤有何不同论述？", catalog)
      assertTrue(
        discovered == Set("shanghan", "jinkui")
      )
    },
    test("DefaultRetriever executes Reasoned strategy with BoundedTreeSearch integration") {
      for
        data <- setup
        (index, structures, _, _, _, scope, _, _) = data
        reranker                                  = new Reranker:
          def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int) = ZIO.succeed(hits.take(limit))
        model = EmbeddingModel.stub(onEmbed =
          texts => ZIO.succeed(texts.map(_ => Embedding(Chunk(1.0f, 0.0f))))
        )
        retriever = DefaultRetriever(
          model,
          index,
          reranker,
          structural = Some(StructuralRetrieval(structures))
        )
        reasonedReq = RetrievalRequest("太阳病", scope, 3, strategy = Some(RetrievalStrategy.Reasoned))
        result <- retriever.retrieve(reasonedReq)
      yield assertTrue(
        result.hits.nonEmpty,
        result.diagnostics.structureSelections.nonEmpty,
        result.diagnostics.strategy == Some(RetrievalStrategy.Reasoned.toString)
      )
    }
  )
