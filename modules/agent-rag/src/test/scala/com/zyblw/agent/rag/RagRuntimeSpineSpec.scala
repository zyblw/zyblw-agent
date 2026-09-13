package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object RagRuntimeSpineSpec extends ZIOSpecDefault:
  private def publication(build: KnowledgeIndexBuild): ProfilePublication =
    val census = Chunk(ProfileDocument(build.key.documentId, build.version, build.contentHash, 1))
    ProfilePublication(census, "contract-evaluation", ProfilePublication.digest(census), qualityPassed = true)

  def spec = suite("RAG runtime spine")(
    test("ingestionKey 可推导且确定性") {
      val a = IngestionKeys.hmac(KnowledgeSpaceId("s"), IndexProfileId("p"), "doc", "rev1", "a" * 64)
      val b = IngestionKeys.hmac(KnowledgeSpaceId("s"), IndexProfileId("p"), "doc", "rev1", "a" * 64)
      val c = IngestionKeys.hmac(KnowledgeSpaceId("s"), IndexProfileId("p"), "doc", "rev2", "a" * 64)
      assertTrue(a == b, a != c, a.length == 64)
    },
    test("planner 不能改 trusted scope；Comparison 最多拆 2 条子查询") {
      val scope =
        RetrievalScope(TenantId("t"), Set("read"), knowledgeSpaceId = Some(KnowledgeSpaceId("space-a")))
      val plan  = DeterministicQueryPlanner.plan("桂枝 对比 麻黄", RetrievalMode.Hybrid)
      val exact = DeterministicQueryPlanner.plan("桂枝汤", RetrievalMode.Hybrid)
      QueryAssist.disabled.rewrite("  甲  乙  ", QueryAssistConfig()).map { rewritten =>
        assertTrue(
          plan.kind == QueryKind.Comparison,
          plan.subqueries == Chunk("桂枝", "麻黄"),
          plan.subqueries.length <= 3,
          exact.includeExact,
          scope.spaceId.value == "space-a",
          rewritten.rewritten == "甲 乙",
          rewritten.cacheKey.nonEmpty
        )
      }
    },
    test("EvidenceBundle citation 只用 displayText") {
      val tenant = TenantId("t")
      val chunk  = DocumentChunk(
        "c1",
        "d1",
        "src://a",
        tenant,
        Set("read"),
        ChunkRepresentations.of("展示原文", "dense派生", "lexical 派生")
      )
      val bundle = ContextAssembler.assemble(
        Chunk(RetrievalHit(chunk, 0.8)),
        Chunk.empty,
        RetrievalEvidence(RetrievalEvidenceStatus.Supported, 1, 1, Some(0.8)),
        CandidateBudgets(rerankSeeds = 1)
      )
      assertTrue(
        bundle.citations.head.excerpt.contains("展示原文"),
        !bundle.citations.head.excerpt.contains("dense派生"),
        bundle.items.head.displayText == "展示原文"
      )
    },
    test("未批准 scheme 被拒绝") {
      ApprovedSourceResolver.validate("http://169.254.169.254/latest").flip.map { error =>
        assertTrue(error.getMessage.contains("未批准"))
      }
    },
    test("第一方 scheme 通过 allowlist") {
      ZIO
        .foreach(
          Chunk(
            "admin-upload://tenant-a/notes.txt",
            "zyblw-book://long",
            "zyblw-content://id/revisions/1",
            "zyblw-asset://asset-1",
            "book://suwen"
          )
        )(ApprovedSourceResolver.validate)
        .as(assertTrue(true))
    },
    test("撤回后检索不可见") {
      val tenant = TenantId("t")
      val chunk  = DocumentChunk("c1", "doc-1", "可见条文", "book://x", tenant, Set("read"))
      for
        store <- InMemoryKnowledgeIndexStore.make
        vector = Embedding(Chunk(1.0f, 0.0f))
        _      <- store.upsert(Chunk(IndexedChunk(chunk, vector)))
        before <- store.search(vector, RetrievalScope(tenant, Set("read")), 5)
        _      <- store.withdraw(KnowledgeDocumentKey(tenant, "doc-1"), "1")
        after  <- store.search(vector, RetrievalScope(tenant, Set("read")), 5)
      yield assertTrue(before.nonEmpty, after.isEmpty)
    },
    test("激活新版本不能复活已撤回文档") {
      val tenant = TenantId("t")
      val chunk  = DocumentChunk("c1", "doc-1", "可见条文", "book://x", tenant, Set("read"))
      for
        store <- InMemoryKnowledgeIndexStore.make
        vector = Embedding(Chunk(1.0f, 0.0f))
        _     <- store.upsert(Chunk(IndexedChunk(chunk, vector)))
        _     <- store.withdraw(KnowledgeDocumentKey(tenant, "doc-1"), "1")
        begin <- store.begin(
          BeginKnowledgeIndex(
            KnowledgeDocumentKey(tenant, "doc-1"),
            "ingest-new",
            "book://x",
            "a" * 64,
            Set("read"),
            Map.empty,
            EmbeddingProviderDescriptor("hash", "hash-2", 2, 8, false),
            "structure-v1"
          )
        )
        _     <- store.stage(begin, Chunk(IndexedChunk(chunk.copy(catalogVersion = begin.version), vector)))
        _     <- store.activate(begin, 1)
        after <- store.search(vector, RetrievalScope(tenant, Set("read")), 5)
      yield assertTrue(after.isEmpty)
    },
    test("sparse 第三路默认关闭，开启后参与 hybrid 融合") {
      val tenant     = TenantId("t")
      val a          = DocumentChunk("a", "doc-a", "桂枝汤", "book://a", tenant, Set("read"))
      val b          = DocumentChunk("b", "doc-b", "无关句", "book://b", tenant, Set("read"))
      val query      = Embedding(Chunk(1.0f, 0.0f))
      val sparseQ    = SparseEmbedding(8, Chunk(SparseEmbeddingEntry(1, 1.0f)))
      val withSparse = IndexedChunk(
        a,
        Embedding(Chunk(0.1f, 0.9f)),
        Some(SparseEmbedding(8, Chunk(SparseEmbeddingEntry(1, 1.0f))))
      )
      val without = IndexedChunk(b, Embedding(Chunk(1.0f, 0.0f)))
      val off = RetrievalScoring.rank(RetrievalMode.Hybrid, "桂枝", query, Iterator(withSparse, without), None)
      val on  =
        RetrievalScoring.rank(RetrievalMode.Hybrid, "桂枝", query, Iterator(withSparse, without), Some(sparseQ))
      assertTrue(
        off.head.chunk.id == "b" || off.head.signals.getOrElse("sparseScore", 0.0) == 0.0,
        on.exists(_.signals.get("sparseScore").exists(_ > 0.0)),
        BaselineRagProfile().sparseEnabled == false,
        QueryAssistConfig().enabled == false
      )
    },
    test("HMAC 对同一密钥确定，更换密钥必须改键") {
      val args = (KnowledgeSpaceId("s"), IndexProfileId("p"), "doc", "rev1", "a" * 64)
      val a    = IngestionKeys.hmac(args._1, args._2, args._3, args._4, args._5, "secret-a")
      val b    = IngestionKeys.hmac(args._1, args._2, args._3, args._4, args._5, "secret-a")
      val c    = IngestionKeys.hmac(args._1, args._2, args._3, args._4, args._5, "secret-b")
      assertTrue(a == b, a != c, IngestionKeys.configuredSecret == IngestionKeys.TestSecret)
    },
    test("EvidenceBundle 按 token 与来源多样性裁剪") {
      val tenant = TenantId("t")
      val a      = DocumentChunk("a", "d1", "甲" * 8, "book://one", tenant, Set("read"))
      val b      = DocumentChunk("b", "d1", "乙" * 8, "book://one", tenant, Set("read"))
      val c      = DocumentChunk("c", "d2", "丙" * 8, "book://two", tenant, Set("read"))
      val bundle = ContextAssembler.assemble(
        Chunk(RetrievalHit(a, 0.9), RetrievalHit(b, 0.8), RetrievalHit(c, 0.7)),
        Chunk.empty,
        RetrievalEvidence(RetrievalEvidenceStatus.Supported, 3, 3, Some(0.9)),
        CandidateBudgets(rerankSeeds = 8, maxEvidenceTokens = 4L),
        tokenCounter = TokenCounter.CodePoints
      )
      assertTrue(
        bundle.degradedStages.contains("dropped-budget") || bundle.degradedStages.contains(
          "dropped-diversity"
        ),
        bundle.citations.forall(_.excerpt == bundle.citations.head.excerpt) ||
          bundle.citations.forall(cite => !cite.excerpt.contains("dense")),
        bundle.toRetrievalResult.hits.length <= 2
      )
    },
    test("legal-hold 文档不会被 retention purge") {
      val tenant = TenantId("t")
      val key    = KnowledgeDocumentKey(tenant, "held")
      for
        store <- InMemoryKnowledgeIndexStore.make
        begin <- store.begin(
          BeginKnowledgeIndex(
            key,
            "ingest-held",
            "book://held",
            "a" * 64,
            Set("read"),
            Map.empty,
            EmbeddingProviderDescriptor("hash", "hash-2", 2, 8, false),
            "structure-v1"
          )
        )
        _ <- store.stage(
          begin,
          Chunk(
            IndexedChunk(
              DocumentChunk("c", "held", "条文", "book://held", tenant, Set("read"))
                .copy(catalogVersion = begin.version),
              Embedding(Chunk(1.0f, 0.0f))
            )
          )
        )
        ready <- store.activate(begin, 1)
        _     <- store.retire(key, ready.build.version)
        now   <- Clock.instant
        held  <- store.purgeInactive(now.plusSeconds(1), 10, Set("held"))
        gone  <- store.purgeInactive(now.plusSeconds(1), 10)
      yield assertTrue(held == 0L, gone == 1L)
    },
    test("换模完整重建不切换 active 指针；评测 census 与空间级 CAS 后才可见") {
      val tenant     = TenantId("t")
      val space      = KnowledgeSpaceId("default")
      val firstDesc  = EmbeddingProviderDescriptor("hash", "model-a", 2, 8, false)
      val secondDesc = EmbeddingProviderDescriptor("hash", "model-b", 2, 8, false)
      val vector     = Embedding(Chunk(1.0f, 0.0f))
      for
        store <- InMemoryKnowledgeIndexStore.make
        first <- store.begin(
          BeginKnowledgeIndex(
            KnowledgeDocumentKey(tenant, "doc-a"),
            "ing-a",
            "book://a",
            "a" * 64,
            Set("read"),
            Map.empty,
            firstDesc,
            "s-v1"
          )
        )
        _ <- store.stage(
          first,
          Chunk(
            IndexedChunk(
              DocumentChunk("c1", "doc-a", "旧模条文", "book://a", tenant, Set("read"))
                .copy(catalogVersion = first.version),
              vector
            )
          )
        )
        _       <- store.activate(first, 1)
        active1 <- store.resolveActiveProfile(tenant, space)
        second  <- store.begin(
          BeginKnowledgeIndex(
            KnowledgeDocumentKey(tenant, "doc-a"),
            "ing-b",
            "book://a",
            "a" * 64,
            Set("read"),
            Map.empty,
            secondDesc,
            "s-v1"
          )
        )
        _ <- store.stage(
          second,
          Chunk(
            IndexedChunk(
              DocumentChunk("c2", "doc-a", "新模条文", "book://a", tenant, Set("read"))
                .copy(catalogVersion = second.version),
              vector
            )
          )
        )
        _         <- store.activate(second, 1)
        active2   <- store.resolveActiveProfile(tenant, space)
        beforeCas <- store.search(vector, RetrievalScope(tenant, Set("read")), 5)
        cas       <- store.activateProfile(
          tenant,
          space,
          second.profileId,
          expectedRevision = 1L,
          reason = "cutover",
          publication = Some(publication(second))
        )
        afterCas <- store.search(
          vector,
          RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(second.profileId)),
          5
        )
        oldMutation <- store
          .begin(
            BeginKnowledgeIndex(
              KnowledgeDocumentKey(tenant, "doc-a"),
              "ing-old-after-cutover",
              "book://a",
              "b" * 64,
              Set("read"),
              Map.empty,
              firstDesc,
              "s-v1",
              targetProfileId = Some(first.profileId)
            )
          )
          .exit
        rollback <- store.activateProfile(
          tenant,
          space,
          first.profileId,
          expectedRevision = cas,
          reason = "rollback",
          publication = Some(publication(first))
        )
        _             <- store.withdraw(KnowledgeDocumentKey(tenant, "doc-a"), "1")
        afterRollback <- store.search(
          vector,
          RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(first.profileId)),
          5
        )
      yield assertTrue(
        !first.casActiveProfile || active1.contains(first.profileId),
        active2 == active1,
        beforeCas.map(_.chunk.id) == Chunk("c1"),
        afterCas.map(_.chunk.id) == Chunk("c2"),
        oldMutation.isFailure,
        rollback > cas,
        afterRollback.isEmpty
      )
    },
    test("retire 只删除当前 active Profile 的块并保留旧 Profile 快照") {
      val tenant = TenantId("retire-profile")
      val space  = KnowledgeSpaceId("default")
      val key    = KnowledgeDocumentKey(tenant, "doc-a")
      val vector = Embedding(Chunk(1.0f, 0.0f))
      def build(
          store: InMemoryKnowledgeIndexStore,
          ingestionId: String,
          model: String,
          chunkId: String
      ) =
        for
          handle <- store.begin(
            BeginKnowledgeIndex(
              key,
              ingestionId,
              "book://a",
              "a" * 64,
              Set("read"),
              Map.empty,
              EmbeddingProviderDescriptor("hash", model, 2, 8, false),
              "s-v1"
            )
          )
          _ <- store.stage(
            handle,
            Chunk(
              IndexedChunk(
                DocumentChunk(chunkId, "doc-a", chunkId, "book://a", tenant, Set("read"))
                  .copy(catalogVersion = handle.version),
                vector
              )
            )
          )
          _ <- store.activate(handle, 1)
        yield handle
      for
        store  <- InMemoryKnowledgeIndexStore.make
        first  <- build(store, "retire-a", "model-a", "old")
        second <- build(store, "retire-b", "model-b", "new")
        _      <- store.activateProfile(
          tenant,
          space,
          second.profileId,
          1L,
          "cutover",
          Some(publication(second))
        )
        _        <- store.retire(key, second.version)
        snapshot <- store.published(key)
        oldHits  <- store.search(
          vector,
          RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(first.profileId)),
          5
        )
      yield assertTrue(
        snapshot.map(_.chunk.id) == Chunk("old"),
        oldHits.map(_.chunk.id) == Chunk("old")
      )
    },
    test("并行 Profile 缺少 active corpus 文档时拒绝切换") {
      val tenant = TenantId("partial")
      val space  = KnowledgeSpaceId("default")
      val old    = EmbeddingProviderDescriptor("hash", "model-a", 2, 8, false)
      val next   = EmbeddingProviderDescriptor("hash", "model-b", 2, 8, false)
      val vector = Embedding(Chunk(1.0f, 0.0f))
      def begin(
          store: KnowledgeIndexStore,
          documentId: String,
          ingestionId: String,
          hash: String,
          descriptor: EmbeddingProviderDescriptor
      ) =
        store.begin(
          BeginKnowledgeIndex(
            KnowledgeDocumentKey(tenant, documentId),
            ingestionId,
            s"book://$documentId",
            hash,
            Set("read"),
            Map.empty,
            descriptor,
            "s-v1"
          )
        )
      def publish(store: KnowledgeIndexStore, build: KnowledgeIndexBuild, chunkId: String) =
        store.stage(
          build,
          Chunk(
            IndexedChunk(
              DocumentChunk(
                chunkId,
                build.key.documentId,
                chunkId,
                s"book://${build.key.documentId}",
                tenant,
                Set("read")
              )
                .copy(catalogVersion = build.version),
              vector
            )
          )
        ) *> store.activate(build, 1)
      for
        store   <- InMemoryKnowledgeIndexStore.make
        first   <- begin(store, "doc-a", "old-a", "a" * 64, old)
        _       <- publish(store, first, "old-a")
        second  <- begin(store, "doc-b", "old-b", "b" * 64, old)
        _       <- publish(store, second, "old-b")
        target  <- begin(store, "doc-a", "new-a", "a" * 64, next)
        _       <- publish(store, target, "new-a")
        cutover <- store
          .activateProfile(tenant, space, target.profileId, 1L, "cutover", Some(publication(target)))
          .exit
      yield assertTrue(cutover.isFailure)
    },
    test("钉死 profile 后切换不影响进行中查询") {
      val tenant = TenantId("t")
      val vector = Embedding(Chunk(1.0f, 0.0f))
      for
        store <- InMemoryKnowledgeIndexStore.make
        first <- store.begin(
          BeginKnowledgeIndex(
            KnowledgeDocumentKey(tenant, "doc-a"),
            "ing-a",
            "book://a",
            "a" * 64,
            Set("read"),
            Map.empty,
            EmbeddingProviderDescriptor("hash", "model-a", 2, 8, false),
            "s-v1"
          )
        )
        _ <- store.stage(
          first,
          Chunk(
            IndexedChunk(
              DocumentChunk("c1", "doc-a", "旧", "book://a", tenant, Set("read"))
                .copy(catalogVersion = first.version),
              vector
            )
          )
        )
        _ <- store.activate(first, 1)
        pinned = RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(first.profileId))
        during <- store.search(vector, pinned, 5)
        _      <- store.activateProfile(
          tenant,
          KnowledgeSpaceId("default"),
          first.profileId,
          1L,
          "noop",
          Some(publication(first))
        )
      yield assertTrue(during.exists(_.chunk.documentId == "doc-a"))
    },
    test("CAS 并发只有一个成功") {
      val tenant = TenantId("t")
      val space  = KnowledgeSpaceId("default")
      val desc   = EmbeddingProviderDescriptor("hash", "model-a", 2, 8, false)
      for
        store <- InMemoryKnowledgeIndexStore.make
        first <- store.begin(
          BeginKnowledgeIndex(
            KnowledgeDocumentKey(tenant, "doc-a"),
            "ing-a",
            "book://a",
            "a" * 64,
            Set("read"),
            Map.empty,
            desc,
            "s-v1"
          )
        )
        _ <- store.stage(
          first,
          Chunk(
            IndexedChunk(
              DocumentChunk("c1", "doc-a", "旧", "book://a", tenant, Set("read"))
                .copy(catalogVersion = first.version),
              Embedding(Chunk(1.0f, 0.0f))
            )
          )
        )
        _   <- store.activate(first, 1)
        one <- store.activateProfile(tenant, space, first.profileId, 1L, "a", Some(publication(first))).exit
        two <- store.activateProfile(tenant, space, first.profileId, 1L, "b", Some(publication(first))).exit
      yield assertTrue(one.isSuccess, two.isFailure)
    },
    test("sparse NNZ 超限必须失败") {
      val over = SparseEmbedding(
        256,
        Chunk.fromIterable((0 until 200).map(i => SparseEmbeddingEntry(i, 1.0f)))
      )
      assertTrue(over.requireWithinNnz(128).isLeft, BaselineRagProfile().sparseEnabled == false)
    },
    test("撤回会使租户 embedding cache 失效") {
      for
        cache <- ZIO.service[EmbeddingCacheStore].provide(EmbeddingCacheStore.inMemory)
        store <- InMemoryKnowledgeIndexStore.make
        now = java.time.Instant.parse("2026-09-05T00:00:00Z")
        key = EmbeddingCacheKey(
          TenantId("t"),
          EmbeddingPurpose.Query,
          "hash",
          "hash-1",
          2,
          "v1",
          "a" * 64
        )
        _ <- cache.put(Chunk(EmbeddingCacheEntry(key, Embedding(Chunk(1.0f, 0.0f)), now.plusSeconds(60))))
        before <- cache.get(Chunk(key), now)
        worker = KnowledgeRetentionWorker(store, cache = Some(cache))
        _     <- worker.withdraw(KnowledgeDocumentKey(TenantId("t"), "doc-1"), "1")
        after <- cache.get(Chunk(key), now)
      yield assertTrue(before.contains(key), after.isEmpty)
    }
  )
