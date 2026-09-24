package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object RagRuntimeSpineSpec extends ZIOSpecDefault:
  import KnowledgeFixtures.*

  def spec = suite("RAG runtime spine")(
    test("ingestionKey 可推导且确定性") {
      val keySpec               = KnowledgeFixtures.buildSpec().sha256
      def key(revision: String) =
        IngestionKeys
          .hmac(KnowledgeSpaceId("s"), IndexProfileId("p"), "doc", lineage("doc", revision), keySpec)
      assertTrue(key("rev1") == key("rev1"), key("rev1") != key("rev2"), key("rev1").length == 64)
    },
    test("ingestionKey 覆盖结构摘要：只改版面结构也必须产生新摄取") {
      val base      = lineage("doc")
      val relaidOut = base.copy(structureSha256 = "c" * 64)
      val specSha   = KnowledgeFixtures.buildSpec().sha256
      val space     = KnowledgeSpaceId.Default
      assertTrue(
        IngestionKeys.hmac(space, IndexProfileId("p"), "doc", base, specSha) !=
          IngestionKeys.hmac(space, IndexProfileId("p"), "doc", relaidOut, specSha)
      )
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
    test("撤回后检索不可见；未知来源修订的撤回必须失败") {
      val tenant = TenantId("t")
      val key    = KnowledgeDocumentKey(tenant, "doc-1")
      val vector = Embedding(Chunk(1.0f, 0.0f))
      for
        store   <- InMemoryKnowledgeIndexStore.make
        _       <- beginAndPublish(store, begin(key, "ing-1"), "c1")
        before  <- store.search(vector, RetrievalScope(tenant, Set("read")), 5)
        unknown <- store.withdraw(key, "rev-unknown").exit
        _       <- store.withdraw(key, "rev-1")
        after   <- store.search(vector, RetrievalScope(tenant, Set("read")), 5)
      yield assertTrue(before.nonEmpty, unknown.isFailure, after.isEmpty)
    },
    test("撤回只作用于同一空间的同一修订；墓碑阻止同修订重新摄取") {
      val tenant = TenantId("t")
      val vector = Embedding(Chunk(1.0f, 0.0f))
      val spaceA = KnowledgeSpaceId("space-a")
      val spaceB = KnowledgeSpaceId("space-b")
      val keyA   = KnowledgeDocumentKey(tenant, "doc-1", spaceA)
      val keyB   = KnowledgeDocumentKey(tenant, "doc-1", spaceB)
      def search(space: KnowledgeSpaceId, store: InMemoryKnowledgeIndexStore) =
        store.search(vector, RetrievalScope(tenant, Set("read"), knowledgeSpaceId = Some(space)), 5)
      for
        store  <- InMemoryKnowledgeIndexStore.make
        _      <- beginAndPublish(store, begin(keyA, "a-rev-1"), "old")
        _      <- beginAndPublish(store, begin(keyB, "b-rev-1"), "other-space")
        _      <- store.withdraw(keyA, "rev-1")
        hidden <- search(spaceA, store)
        other  <- search(spaceB, store)
        replay <- store.begin(begin(keyA, "a-rev-1-again", text = "b" * 64)).exit
        _      <- beginAndPublish(
          store,
          begin(keyA, "a-rev-2", revision = "rev-2", text = "c" * 64),
          "visible-new"
        )
        after <- search(spaceA, store)
      yield assertTrue(
        hidden.isEmpty,
        other.map(_.chunk.id) == Chunk("other-space"),
        replay.isFailure,
        after.map(_.chunk.id) == Chunk("visible-new")
      )
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
      val specSha             = KnowledgeFixtures.buildSpec().sha256
      def key(secret: String) =
        IngestionKeys.hmac(KnowledgeSpaceId("s"), IndexProfileId("p"), "doc", lineage("doc"), specSha, secret)
      assertTrue(
        key("secret-a") == key("secret-a"),
        key("secret-a") != key("secret-b"),
        IngestionKeys.configuredSecret == IngestionKeys.TestSecret
      )
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
        build <- beginAndPublish(store, begin(key, "ingest-held"), "c")
        _     <- store.retire(key, build.version)
        now   <- Clock.instant
        held  <- store.purgeInactive(now.plusSeconds(1), 10, Set(key))
        gone  <- store.purgeInactive(now.plusSeconds(1), 10)
      yield assertTrue(held == 0L, gone == 1L)
    },
    test("换模完整重建不切换 active 指针；评测 census 与空间级 CAS 后才可见") {
      val tenant = TenantId("t")
      val space  = KnowledgeSpaceId.Default
      val first  = buildSpec(model = "model-a")
      val second = buildSpec(model = "model-b")
      val keyA   = KnowledgeDocumentKey(tenant, "doc-a")
      val vector = Embedding(Chunk(1.0f, 0.0f))
      for
        store     <- InMemoryKnowledgeIndexStore.make
        old       <- beginAndPublish(store, begin(keyA, "ing-a", first), "c1")
        active1   <- store.resolveActiveProfile(tenant, space)
        next      <- beginAndPublish(store, begin(keyA, "ing-b", second), "c2")
        active2   <- store.resolveActiveProfile(tenant, space)
        beforeCas <- store.search(vector, RetrievalScope(tenant, Set("read")), 5)
        evidence  <- publicationFor(store, next.profileId)
        cas       <- store.activateProfile(tenant, space, next.profileId, 1L, "cutover", Some(evidence))
        afterCas  <- store.search(vector, RetrievalScope(tenant, Set("read")), 5)
      yield assertTrue(
        old.casActiveProfile,
        active1.contains(old.profileId),
        !next.casActiveProfile,
        active2 == active1,
        beforeCas.map(_.chunk.id) == Chunk("c1"),
        cas == 2L,
        afterCas.map(_.chunk.id) == Chunk("c2")
      )
    },
    test("切换后新文档继续写入 active Profile，回滚目标可显式补齐后回滚（无 Profile 死锁）") {
      val tenant = TenantId("t")
      val space  = KnowledgeSpaceId.Default
      val first  = buildSpec(model = "model-a")
      val second = buildSpec(model = "model-b")
      val keyA   = KnowledgeDocumentKey(tenant, "doc-a")
      val keyB   = KnowledgeDocumentKey(tenant, "doc-b")
      val pinned =
        (profile: IndexProfileId) => RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(profile))
      val vector = Embedding(Chunk(1.0f, 0.0f))
      for
        store       <- InMemoryKnowledgeIndexStore.make
        old         <- beginAndPublish(store, begin(keyA, "a-old", first), "a-old")
        next        <- beginAndPublish(store, begin(keyA, "a-new", second), "a-new")
        evidence    <- publicationFor(store, next.profileId)
        cas         <- store.activateProfile(tenant, space, next.profileId, 1L, "cutover", Some(evidence))
        afterWrite  <- beginAndPublish(store, begin(keyB, "b-new", second, text = "b" * 64), "b-new")
        implicitOld <- store.begin(begin(keyB, "b-old-implicit", first, text = "b" * 64)).exit
        early       <- publicationFor(store, old.profileId)
        blocked     <- store.activateProfile(tenant, space, old.profileId, cas, "rollback", Some(early)).exit
        diff        <- store.profileCorpusDiff(tenant, space, old.profileId)
        catchUp     <- beginAndPublish(
          store,
          begin(keyB, "b-old", first, text = "b" * 64, targetProfileId = Some(old.profileId)),
          "b-old"
        )
        ready    <- publicationFor(store, old.profileId)
        rollback <- store.activateProfile(tenant, space, old.profileId, cas, "rollback", Some(ready))
        hits     <- store.search(vector, pinned(old.profileId), 5)
      yield assertTrue(
        afterWrite.profileId == next.profileId,
        implicitOld.isFailure,
        blocked.isFailure,
        diff.missing.map(_.documentId) == Chunk("doc-b"),
        catchUp.profileId == old.profileId,
        rollback > cas,
        hits.map(_.chunk.id).toSet == Set("a-old", "b-old")
      )
    },
    test("较早的失败尝试不阻塞 Profile 发布：只看每份文档的最新版本") {
      val tenant = TenantId("t")
      val space  = KnowledgeSpaceId.Default
      val key    = KnowledgeDocumentKey(tenant, "doc-a")
      for
        store  <- InMemoryKnowledgeIndexStore.make
        _      <- beginAndPublish(store, begin(key, "a-old", buildSpec(model = "model-a")), "old")
        failed <- store.begin(begin(key, "a-fail", buildSpec(model = "model-b")))
        _      <- store.markFailed(failed, "embedding_timeout")
        retry  <- beginAndPublish(
          store,
          begin(key, "a-retry", buildSpec(model = "model-b"), text = "a" * 64),
          "new"
        )
        evidence <- publicationFor(store, retry.profileId)
        revision <- store.activateProfile(tenant, space, retry.profileId, 1L, "cutover", Some(evidence))
      yield assertTrue(failed.profileId == retry.profileId, revision == 2L)
    },
    test("retire 下线文档在空间内全部 Profile 的副本") {
      val tenant = TenantId("retire-profile")
      val space  = KnowledgeSpaceId.Default
      val key    = KnowledgeDocumentKey(tenant, "doc-a")
      val vector = Embedding(Chunk(1.0f, 0.0f))
      for
        store    <- InMemoryKnowledgeIndexStore.make
        first    <- beginAndPublish(store, begin(key, "retire-a", buildSpec(model = "model-a")), "old")
        second   <- beginAndPublish(store, begin(key, "retire-b", buildSpec(model = "model-b")), "new")
        evidence <- publicationFor(store, second.profileId)
        _        <- store.activateProfile(tenant, space, second.profileId, 1L, "cutover", Some(evidence))
        _        <- store.retire(key, second.version)
        snapshot <- store.published(key)
        oldHits  <- store.search(
          vector,
          RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(first.profileId)),
          5
        )
        firstNow <- store.find(key, "retire-a")
      yield assertTrue(
        snapshot.isEmpty,
        oldHits.isEmpty,
        firstNow.exists(_.status == KnowledgeIndexStatus.Retired)
      )
    },
    test("并行 Profile 缺少 active corpus 文档时拒绝切换") {
      val tenant = TenantId("partial")
      val space  = KnowledgeSpaceId.Default
      val old    = buildSpec(model = "model-a")
      val next   = buildSpec(model = "model-b")
      for
        store <- InMemoryKnowledgeIndexStore.make
        _     <- beginAndPublish(store, begin(KnowledgeDocumentKey(tenant, "doc-a"), "old-a", old), "old-a")
        _     <- beginAndPublish(
          store,
          begin(KnowledgeDocumentKey(tenant, "doc-b"), "old-b", old, text = "b" * 64),
          "old-b"
        )
        target <- beginAndPublish(store, begin(KnowledgeDocumentKey(tenant, "doc-a"), "new-a", next), "new-a")
        evidence <- publicationFor(store, target.profileId)
        cutover  <- store.activateProfile(tenant, space, target.profileId, 1L, "cutover", Some(evidence)).exit
        diff     <- store.profileCorpusDiff(tenant, space, target.profileId)
      yield assertTrue(cutover.isFailure, diff.missing.map(_.documentId) == Chunk("doc-b"))
    },
    test("块集合摘要不一致时拒绝发布") {
      val tenant = TenantId("t")
      val key    = KnowledgeDocumentKey(tenant, "doc-a")
      for
        store <- InMemoryKnowledgeIndexStore.make
        build <- store.begin(begin(key, "ing-a"))
        staged = Chunk(chunk(build, "c1"), chunk(build, "c2"))
        _     <- store.stage(build, staged.map(IndexedChunk(_, Embedding(Chunk(1.0f, 0.0f)))))
        wrong <- store.activate(build, ChunkSetDigest.of(staged.take(1))).exit
        right <- store.activate(build, ChunkSetDigest.of(staged.reverse))
      yield assertTrue(
        wrong.isFailure,
        right.chunkCount == 2,
        right.chunkSetSha256.contains(ChunkSetDigest.of(staged).sha256)
      )
    },
    test("同一文档 ID 在不同空间互不影响版本与 active 状态") {
      val tenant = TenantId("t")
      val keyA   = KnowledgeDocumentKey(tenant, "doc", KnowledgeSpaceId("space-a"))
      val keyB   = KnowledgeDocumentKey(tenant, "doc", KnowledgeSpaceId("space-b"))
      for
        store   <- InMemoryKnowledgeIndexStore.make
        a1      <- beginAndPublish(store, begin(keyA, "same-ingestion"), "a1")
        b1      <- beginAndPublish(store, begin(keyB, "same-ingestion"), "b1")
        a2      <- beginAndPublish(store, begin(keyA, "a-2", revision = "rev-2", text = "b" * 64), "a2")
        activeB <- store.active(keyB)
      yield assertTrue(
        a1.version == 1L,
        b1.version == 1L,
        a2.version == 2L,
        activeB.map(_.build.version).contains(1L)
      )
    },
    test("钉死 profile 后切换不影响进行中查询") {
      val tenant = TenantId("t")
      val vector = Embedding(Chunk(1.0f, 0.0f))
      for
        store <- InMemoryKnowledgeIndexStore.make
        first <- beginAndPublish(store, begin(KnowledgeDocumentKey(tenant, "doc-a"), "ing-a"), "c1")
        pinned = RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(first.profileId))
        during   <- store.search(vector, pinned, 5)
        evidence <- publicationFor(store, first.profileId)
        _        <- store.activateProfile(
          tenant,
          KnowledgeSpaceId.Default,
          first.profileId,
          1L,
          "noop",
          Some(evidence)
        )
      yield assertTrue(during.exists(_.chunk.documentId == "doc-a"))
    },
    test("CAS 并发只有一个成功") {
      val tenant = TenantId("t")
      val space  = KnowledgeSpaceId.Default
      for
        store    <- InMemoryKnowledgeIndexStore.make
        first    <- beginAndPublish(store, begin(KnowledgeDocumentKey(tenant, "doc-a"), "ing-a"), "c1")
        evidence <- publicationFor(store, first.profileId)
        one      <- store.activateProfile(tenant, space, first.profileId, 1L, "a", Some(evidence)).exit
        two      <- store.activateProfile(tenant, space, first.profileId, 1L, "b", Some(evidence)).exit
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
        _     <- beginAndPublish(store, begin(KnowledgeDocumentKey(TenantId("t"), "doc-1"), "ing-1"), "c1")
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
        _     <- worker.withdraw(KnowledgeDocumentKey(TenantId("t"), "doc-1"), "rev-1")
        after <- cache.get(Chunk(key), now)
      yield assertTrue(before.contains(key), after.isEmpty)
    }
  )
