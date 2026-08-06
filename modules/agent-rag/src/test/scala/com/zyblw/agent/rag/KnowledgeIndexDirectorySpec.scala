package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.test.*

/** 验证知识索引目录的排序、keyset 翻页与租户隔离。
  *
  * 重点是"同一份文档的两个版本共享同一个 `updatedAt`"这种正常情况：发布新版本时旧版本被置为 Superseded、新版本被 置为 Ready，两者时间戳来自同一事务时间点。如果游标只包含
  * `(updatedAt, documentId)`，翻页会在这两行之间丢行。
  */
object KnowledgeIndexDirectorySpec extends ZIOSpecDefault:

  private val embedding =
    EmbeddingProviderDescriptor("directory-test", "v1", 2, 10, supportsDimensions = false)

  /** 构造一份只用于目录投影的 manifest；正文与向量不参与列表。 */
  private def manifest(
      documentId: String,
      version: Long,
      updatedAtEpochMilli: Long,
      tenant: String = "tenant-a"
  ): KnowledgeIndexManifest = KnowledgeIndexManifest(
    build = KnowledgeIndexBuild(
      key = KnowledgeDocumentKey(TenantId(tenant), documentId),
      version = version,
      ingestionId = s"$documentId-v$version",
      contentHash = "0" * 64,
      embedding = embedding,
      indexingStrategy = "directory-test-v1"
    ),
    sourceUri = s"knowledge://$documentId",
    permissions = Set("knowledge:read"),
    metadata = Map.empty,
    status = KnowledgeIndexStatus.Superseded,
    active = false,
    chunkCount = 1,
    failureCode = None,
    createdAt = Instant.ofEpochMilli(updatedAtEpochMilli),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMilli)
  )

  /** 同毫秒的同名文档双版本、同毫秒的不同文档，以及不同毫秒各一条。 */
  private val manifests = Chunk(
    manifest("doc-a", 1L, 100L),
    manifest("doc-a", 2L, 100L),
    manifest("doc-b", 1L, 200L),
    manifest("doc-c", 1L, 100L),
    manifest("doc-d", 1L, 50L),
    manifest("doc-e", 1L, 300L, tenant = "tenant-b")
  )

  /** 逐页读取直到目录不再返回游标，返回拼接后的完整序列与页数。 */
  private def drain(
      directory: KnowledgeIndexDirectory,
      tenantId: Option[TenantId],
      pageSize: Int
  ): IO[RetrievalError, (Chunk[KnowledgeIndexManifest], Int)] =
    def loop(
        cursor: Option[KnowledgeIndexCursor],
        acc: Chunk[KnowledgeIndexManifest],
        pages: Int
    ): IO[RetrievalError, (Chunk[KnowledgeIndexManifest], Int)] =
      directory.list(tenantId, pageSize, cursor).flatMap { page =>
        val collected = acc ++ page.items
        page.nextCursor match
          case Some(next) if page.hasMore => loop(Some(next), collected, pages + 1)
          case _                          => ZIO.succeed(collected -> (pages + 1))
      }
    loop(None, Chunk.empty, 0)

  private def keys(items: Chunk[KnowledgeIndexManifest]): Chunk[(String, Long)] =
    items.map(item => item.build.key.documentId -> item.build.version)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeIndexDirectory")(
    test("按 (updatedAt DESC, documentId DESC, indexVersion DESC) 稳定排序") {
      val directory = KnowledgeIndexDirectory.fromSnapshots(ZIO.succeed(manifests))
      directory.list(Some(TenantId("tenant-a")), KnowledgeIndexDirectory.MaxLimit, None).map { page =>
        assertTrue(
          keys(page.items) == Chunk(
            "doc-b" -> 1L,
            "doc-c" -> 1L,
            "doc-a" -> 2L,
            "doc-a" -> 1L,
            "doc-d" -> 1L
          ),
          page.nextCursor.isEmpty,
          !page.hasMore
        )
      }
    },
    test("keyset 翻页跨页不重复、不丢行，且与单页结果完全一致") {
      val directory = KnowledgeIndexDirectory.fromSnapshots(ZIO.succeed(manifests))
      for
        single <- directory.list(Some(TenantId("tenant-a")), KnowledgeIndexDirectory.MaxLimit, None)
        paged  <- drain(directory, Some(TenantId("tenant-a")), 2)
        (items, pages) = paged
      yield assertTrue(
        keys(items) == keys(single.items),
        keys(items).distinct.length == keys(items).length,
        items.length == 5,
        pages == 3
      )
    },
    test("游标只包含时间、文档与版本，可安全往返编解码") {
      val cursor = KnowledgeIndexCursor(100L, "doc:with:colon", 3L)
      assertTrue(
        KnowledgeIndexCursor.decode(cursor.encoded) == Right(cursor),
        KnowledgeIndexCursor.decode("not-a-cursor").isLeft,
        KnowledgeIndexCursor.decode("100:doc-a:0").isLeft,
        KnowledgeIndexCursor.decode("100::1").isLeft
      )
    },
    test("租户过滤生效，跨租户查询返回全部清单") {
      val directory = KnowledgeIndexDirectory.fromSnapshots(ZIO.succeed(manifests))
      for
        tenantB <- directory.list(Some(TenantId("tenant-b")), 50, None)
        all     <- directory.list(None, 50, None)
      yield assertTrue(
        keys(tenantB.items) == Chunk("doc-e" -> 1L),
        all.items.length == 6,
        keys(all.items).head == ("doc-e" -> 1L)
      )
    },
    test("内存索引实现发布的每个版本都出现在目录中") {
      for
        store <- InMemoryKnowledgeIndexStore.make
        directory = KnowledgeIndexDirectory.inMemory(store)
        indexer   = KnowledgeIndexer(SlidingWindowChunker(16, 0), HashEmbedding(8), store)
        tenant    = TenantId("tenant-a")
        first  <- indexer.index(SourceDocument("doc-1", "第一版正文", "doc://1"), tenant, Set("read"), "ing-1")
        second <- indexer.index(
          SourceDocument("doc-1", "第二版正文", "doc://1"),
          tenant,
          Set("read"),
          "ing-2",
          ActiveVersionExpectation.Exact(first.manifest.build.version)
        )
        page <- directory.list(Some(tenant), 50, None)
      yield assertTrue(
        keys(page.items) == Chunk("doc-1" -> 2L, "doc-1" -> 1L),
        page.items.count(_.active) == 1,
        page.items.find(_.active).map(_.build.version).contains(second.manifest.build.version)
      )
    },
    test("未接入耐久目录的部署显式返回空页") {
      ZIO.serviceWithZIO[KnowledgeIndexDirectory](_.list(None, 50, None)).map { page =>
        assertTrue(page.items.isEmpty, page.nextCursor.isEmpty, !page.hasMore)
      }
    }.provide(KnowledgeIndexDirectory.empty)
  )
