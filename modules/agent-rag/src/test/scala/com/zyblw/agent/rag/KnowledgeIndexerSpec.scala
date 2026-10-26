package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 验证索引构建、幂等重放、乐观版本条件与失败终态，不依赖 Docker 或真实 Embedding API。 */
object KnowledgeIndexerSpec extends ZIOSpecDefault:

  /** 创建可统计调用次数的两维测试 Embedding。 */
  private def countingEmbedding(calls: Ref[Int]): EmbeddingModel =
    EmbeddingModel.stub(
      provider = "test-embedding",
      onEmbed =
        texts => calls.update(_ + 1).as(texts.map(text => Embedding(Chunk(text.length.toFloat, 1.0f))))
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeIndexer")(
    test("构建发布为 active，完成后的相同 ingestionId 不重复调用 Provider") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        indexer = KnowledgeIndexer(
          SlidingWindowChunker(maxCharacters = 4, overlap = 0),
          countingEmbedding(calls),
          store,
          8
        )
        document = SourceDocument("doc-1", "abcdefgh", "doc://1", Map("title" -> "测试文档"))
        first <- indexer.index(
          document,
          TenantId("tenant-a"),
          Set("knowledge:read"),
          "ingestion-1",
          ActiveVersionExpectation.NoActiveVersion
        )
        second <- indexer.index(
          document,
          TenantId("tenant-a"),
          Set("knowledge:read"),
          "ingestion-1",
          ActiveVersionExpectation.NoActiveVersion
        )
        active    <- store.active(KnowledgeDocumentKey(TenantId("tenant-a"), "doc-1"))
        published <- store.published(KnowledgeDocumentKey(TenantId("tenant-a"), "doc-1"))
        count     <- calls.get
      yield assertTrue(
        first.manifest.status == KnowledgeIndexStatus.Ready,
        first.manifest.active,
        first.manifest.chunkCount == 2,
        first.manifest.build.indexingStrategy == "sliding-window-v1:max=4:overlap=0:lexical=simple-cjk-bigram-v2",
        second.manifest == first.manifest,
        second.embeddingUsage.isEmpty,
        active.contains(first.manifest),
        published.map(_.chunk.indexVersion) == Chunk(1L, 1L),
        count == 1
      )
    },
    test("空 ingestionId 由谱系派生：重放幂等，新来源修订产生新版本，并按批次调用 Provider") {
      val tenant = TenantId("tenant-a")
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        indexer = KnowledgeIndexer(
          SlidingWindowChunker(maxCharacters = 4, overlap = 0),
          countingEmbedding(calls),
          store,
          1
        )
        document = SourceDocument("doc-1", "abcdefgh", "doc://1")
        rev1     = IngestionProvenance(Some(SourceRevision("book-1", "rev-1")))
        rev2     = IngestionProvenance(Some(SourceRevision("book-1", "rev-2")))
        first   <- indexer.index(document, tenant, Set("read"), "", provenance = rev1)
        replay  <- indexer.index(document, tenant, Set("read"), "", provenance = rev1)
        batched <- calls.get
        second  <- indexer.index(document, tenant, Set("read"), "", provenance = rev2)
      yield assertTrue(
        first.manifest.build.ingestionId.length == 64,
        replay.manifest == first.manifest,
        batched == 2,
        first.manifest.build.lineage.sourceId == "book-1",
        second.manifest.build.version == 2L,
        second.manifest.build.lineage.sourceRevisionId == "rev-2",
        first.manifest.chunkSetSha256.exists(_.length == 64)
      )
    },
    test("同 ingestionId 的内容漂移与错误 active 前置条件均被拒绝") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        indexer = KnowledgeIndexer(SlidingWindowChunker(10, 0), countingEmbedding(calls), store)
        base    = SourceDocument("doc-1", "original", "doc://1")
        _       <- indexer.index(base, TenantId("tenant-a"), Set("read"), "same-id")
        changed <- indexer
          .index(base.copy(text = "changed"), TenantId("tenant-a"), Set("read"), "same-id")
          .exit
        conflict <- indexer
          .index(
            base.copy(text = "new version"),
            TenantId("tenant-a"),
            Set("read"),
            "new-id",
            ActiveVersionExpectation.Exact(99L)
          )
          .exit
      yield assertTrue(changed.isFailure, conflict.isFailure)
    },
    test("Embedding 协议失败会把 Building manifest 标记为 Failed") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        broken  = EmbeddingModel.stub(provider = "broken", onEmbed = _ => ZIO.succeed(Chunk.empty))
        indexer = KnowledgeIndexer(SlidingWindowChunker(10, 0), broken, store)
        result <- indexer
          .index(
            SourceDocument("doc-bad", "non-empty", "doc://bad"),
            TenantId("tenant-a"),
            Set("read"),
            "bad-ingestion"
          )
          .exit
        manifest <- store.find(KnowledgeDocumentKey(TenantId("tenant-a"), "doc-bad"), "bad-ingestion")
      yield assertTrue(
        result.isFailure,
        manifest.exists(_.status == KnowledgeIndexStatus.Failed),
        manifest.flatMap(_.failureCode).contains(ErrorCategory.Validation.toString)
      )
    },
    test("下线使用 active version 乐观条件且可幂等恢复，retention 只清理非活动终态") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        tenant  = TenantId("tenant-a")
        key     = KnowledgeDocumentKey(tenant, "doc-retire")
        indexer = KnowledgeIndexer(SlidingWindowChunker(10, 0), countingEmbedding(calls), store)
        first <- indexer.index(SourceDocument("doc-retire", "第一版", "doc://retire"), tenant, Set("read"), "v1")
        second <- indexer.index(
          SourceDocument("doc-retire", "第二版", "doc://retire"),
          tenant,
          Set("read"),
          "v2",
          ActiveVersionExpectation.Exact(first.manifest.build.version)
        )
        staleDelete <- store.retire(key, first.manifest.build.version).exit
        retired     <- store.retire(key, second.manifest.build.version)
        replay      <- store.retire(key, second.manifest.build.version)
        active      <- store.active(key)
        published   <- store.published(key)
        now         <- Clock.instant
        purged      <- store.purgeInactive(now.plusSeconds(1), 10)
        goneV1      <- store.find(key, "v1")
        goneV2      <- store.find(key, "v2")
      yield assertTrue(
        staleDelete.isFailure,
        retired.status == KnowledgeIndexStatus.Retired,
        !retired.active,
        replay == retired,
        active.isEmpty,
        published.isEmpty,
        purged == 2L,
        goneV1.isEmpty,
        goneV2.isEmpty
      )
    },
    test("质量不足写入隔离且不得激活") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        indexer = KnowledgeIndexer(
          SlidingWindowChunker(10, 0),
          countingEmbedding(calls),
          store,
          qualityPolicy = ExtractionQualityPolicy(minScriptCodePoints = 48)
        )
        result <- indexer
          .index(
            SourceDocument("doc-q", "(cid:12)(cid:13)", "doc://q"),
            TenantId("tenant-a"),
            Set("read"),
            "q-1"
          )
          .exit
        manifest <- store.find(KnowledgeDocumentKey(TenantId("tenant-a"), "doc-q"), "q-1")
        active   <- store.active(KnowledgeDocumentKey(TenantId("tenant-a"), "doc-q"))
        callsN   <- calls.get
      yield assertTrue(
        result.isFailure,
        manifest.exists(_.isQuarantined),
        manifest.exists(_.status == KnowledgeIndexStatus.Failed),
        active.isEmpty,
        callsN == 0
      )
    },
    test("空 ingestionId 时 HMAC 可推导且确定") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        indexer  = KnowledgeIndexer(SlidingWindowChunker(10, 0), countingEmbedding(calls), store)
        document = SourceDocument("doc-hmac", "abcdefgh", "doc://hmac")
        first  <- indexer.index(document, TenantId("tenant-a"), Set("read"), "")
        second <- indexer.index(document, TenantId("tenant-a"), Set("read"), "")
        count  <- calls.get
      yield assertTrue(first.manifest.build.ingestionId == second.manifest.build.ingestionId, count == 1)
    },
    test("DocumentEnricher 不能改写 tenant/ACL/URI") {
      val hostile = new DocumentEnricher:
        def descriptor: EnricherDescriptor = EnricherDescriptor.Host("hostile", "1")
        def enrich(document: SourceDocument, chunks: Chunk[DocumentChunk]) =
          ZIO.succeed(EnrichmentResult(Map("tenantId" -> "other", "sourceUri" -> "http://evil")))
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        indexer = KnowledgeIndexer(
          SlidingWindowChunker(10, 0),
          countingEmbedding(calls),
          store,
          enricher = hostile
        )
        result <- indexer
          .index(SourceDocument("doc-e", "abcdefgh", "doc://e"), TenantId("tenant-a"), Set("read"), "e-1")
          .exit
        manifest <- store.find(KnowledgeDocumentKey(TenantId("tenant-a"), "doc-e"), "e-1")
      yield assertTrue(result.isFailure, manifest.exists(_.status == KnowledgeIndexStatus.Failed))
    },
    test("局部编辑、追加章节与权限变更按 denseSha256 增量复用已有块向量，不重算未改块") {
      val tenant = TenantId("tenant-a")
      val key    = KnowledgeDocumentKey(tenant, "doc-inc")
      for
        store         <- InMemoryKnowledgeIndexStore.make
        embeddedTexts <- Ref.make(Chunk.empty[String])
        embedding = EmbeddingModel.stub(
          provider = "inc-embedding",
          model = "text-embedding-v4",
          onEmbed = texts =>
            embeddedTexts.update(_ ++ texts) *>
              ZIO.succeed(texts.map(text => Embedding(Chunk(text.hashCode.toFloat, text.length.toFloat))))
        )
        indexer = KnowledgeIndexer(MarkdownStructureChunker(), embedding, store)
        v1Text  =
          """# 浮脉
            |
            |浮脉主表，轻取即得，按之稍减而不空。
            |
            |# 沉脉
            |
            |沉脉主里，轻取不应，重按始得。
            |
            |# 迟脉
            |
            |迟脉主寒，一息三至，去来极慢。""".stripMargin
        first <- indexer.index(
          SourceDocument("doc-inc", v1Text, "doc://inc", Map("title" -> "脉学要义")),
          tenant,
          Set("public"),
          "inc-v1",
          provenance = IngestionProvenance(Some(SourceRevision("src-inc", "rev-1")))
        )
        afterV1 <- embeddedTexts.get
        _       <- embeddedTexts.set(Chunk.empty)
        // 仅修改第二章的一个标点符号：只有「沉脉」这一块重算 Embedding，其余两块直接复用
        v2Text = v1Text.replace("重按始得。", "重按始得！")
        second <- indexer.index(
          SourceDocument("doc-inc", v2Text, "doc://inc", Map("title" -> "脉学要义")),
          tenant,
          Set("public"),
          "inc-v2",
          provenance = IngestionProvenance(Some(SourceRevision("src-inc", "rev-2")))
        )
        afterV2 <- embeddedTexts.get
        _       <- embeddedTexts.set(Chunk.empty)
        // 在末尾追加第四章「数脉」：前三个章节块全部复用，仅新增块调用 Embedding
        v3Text = v2Text + "\n\n# 数脉\n\n数脉主热，一息六至，脉流薄疾。"
        third <- indexer.index(
          SourceDocument("doc-inc", v3Text, "doc://inc", Map("title" -> "脉学要义")),
          tenant,
          Set("public"),
          "inc-v3",
          provenance = IngestionProvenance(Some(SourceRevision("src-inc", "rev-3")))
        )
        afterV3 <- embeddedTexts.get
        _       <- embeddedTexts.set(Chunk.empty)
        // 仅变更权限与摘要元数据，或删除最后一个章节：100% 复用已有向量，0 次远程 Embedding 调用
        fourth <- indexer.index(
          SourceDocument("doc-inc", v2Text, "doc://inc", Map("title" -> "脉学要义", "summary" -> "新增摘要")),
          tenant,
          Set("members"),
          "inc-v4",
          provenance = IngestionProvenance(Some(SourceRevision("src-inc", "rev-4")))
        )
        afterV4   <- embeddedTexts.get
        published <- store.published(key)
        ordered = published.map(_.chunk).sortBy(_.lineage.fold(0)(_.ordinal))
      yield assertTrue(
        first.manifest.chunkCount == 3,
        first.reusedChunkCount == 0,
        first.embeddedChunkCount == 3,
        afterV1.length == 3,
        second.manifest.chunkCount == 3,
        second.reusedChunkCount == 2,
        second.embeddedChunkCount == 1,
        afterV2.length == 1,
        afterV2.head.contains("重按始得！"),
        third.manifest.chunkCount == 4,
        third.reusedChunkCount == 3,
        third.embeddedChunkCount == 1,
        afterV3.length == 1,
        afterV3.head.contains("数脉主热"),
        fourth.manifest.chunkCount == 3,
        fourth.reusedChunkCount == 3,
        fourth.embeddedChunkCount == 0,
        afterV4.isEmpty,
        ordered.length == 3,
        ordered.forall(_.permissions == Set("members")),
        ordered.map(_.lineage.map(_.ordinal)) == Chunk(Some(0), Some(1), Some(2)),
        ordered(0).lineage.flatMap(_.nextChunkId).contains(ordered(1).id),
        ordered(1).lineage.flatMap(_.previousChunkId).contains(ordered(0).id),
        ordered(1).lineage.flatMap(_.nextChunkId).contains(ordered(2).id)
      )
    }
  )
