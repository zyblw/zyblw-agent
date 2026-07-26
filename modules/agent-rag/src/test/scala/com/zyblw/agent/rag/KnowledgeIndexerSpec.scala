package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 验证索引构建、幂等重放、乐观版本条件与失败终态，不依赖 Docker 或真实 Embedding API。 */
object KnowledgeIndexerSpec extends ZIOSpecDefault:

  /** 创建可统计调用次数的两维测试 Embedding。 */
  private def countingEmbedding(calls: Ref[Int]): EmbeddingService = new EmbeddingService:
    val dimension: Int                                   = 2
    override val descriptor: EmbeddingProviderDescriptor =
      EmbeddingProviderDescriptor("test-embedding", "v1", 2, 100, supportsDimensions = false)

    /** 每次逻辑调用只增加一次计数，输出数量严格等于输入。 */
    def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
      calls.update(_ + 1).as(texts.map(text => Embedding(Chunk(text.length.toFloat, 1.0f))))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeIndexer")(
    test("构建发布为 active，完成后的相同 ingestionId 不重复调用 Provider") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        calls <- Ref.make(0)
        indexer = KnowledgeIndexer(
          SlidingWindowChunker(maxCharacters = 4, overlap = 0),
          countingEmbedding(calls),
          store,
          1
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
        second.manifest == first.manifest,
        second.embeddingUsage.isEmpty,
        active.contains(first.manifest),
        published.map(_.chunk.indexVersion) == Chunk(1L, 1L),
        count == 1
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
        broken = new EmbeddingService:
          val dimension: Int                                   = 2
          override val descriptor: EmbeddingProviderDescriptor =
            EmbeddingProviderDescriptor("broken", "v1", 2, 10, supportsDimensions = false)

          /** 故意少返回一个向量，用于验证发布前的数量防线。 */
          def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] = ZIO.succeed(Chunk.empty)
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
    }
  )
