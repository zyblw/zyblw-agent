package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.admin.CursorTime
import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 用真实知识 schema 验证管理面清单目录。
  *
  * 这些语义无法用内存实现替代：SQL 的 keyset 行值比较、`updated_at` 由数据库时钟写入，以及发布新版本时旧版本转 `Superseded`、新版本转 `Ready`
  * 共享同一事务时间点——正是这个场景决定了游标必须带上 `indexVersion`。
  */
object PostgresKnowledgeIndexDirectoryIntegrationSpec extends ZIOSpecDefault:
  final private case class Harness(index: KnowledgeIndexStore, directory: KnowledgeIndexDirectory)

  private val harnessLayer: ZLayer[Any, Throwable, Harness] = ZLayer.scoped {
    for
      container <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val image = DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
          val value = PostgreSQLContainer(dockerImageNameOverride = image)
          value.start()
          value
        }
      )(value => ZIO.attemptBlocking(value.stop()).orDie)
      dataSource <- ZIO.attempt {
        val value = PGSimpleDataSource()
        value.setURL(container.jdbcUrl)
        value.setUser(container.username)
        value.setPassword(container.password)
        value: DataSource
      }
      _         <- AgentPostgresMigrations.migrate(dataSource)
      _         <- AgentPostgresMigrations.migrateKnowledge1536(dataSource)
      knowledge <- PostgresAgentPersistence
        .knowledge(1536, PostgresHybridSearchConfig(enableHnswIterativeScan = false))
        .build
        .provideSome[Scope](ZLayer.succeed[DataSource](dataSource))
    yield Harness(knowledge.get[KnowledgeIndexStore], PostgresKnowledgeIndexDirectory(dataSource))
  }

  private val descriptor =
    EmbeddingProviderDescriptor("integration-embedding", "v1", 1536, 100, supportsDimensions = false)

  private def unitVector(slot: Int): Embedding =
    val values = Array.fill[Float](1536)(0.0f)
    values(slot % 1536) = 1.0f
    Embedding(Chunk.fromArray(values))

  private def request(
      tenant: TenantId,
      documentId: String,
      ingestionId: String,
      expectation: ActiveVersionExpectation
  ): BeginKnowledgeIndex = BeginKnowledgeIndex(
    KnowledgeDocumentKey(tenant, documentId),
    ingestionId,
    s"doc://$documentId",
    KnowledgeIndexer.sha256(s"$documentId-$ingestionId"),
    Set("read"),
    Map("title" -> documentId),
    descriptor,
    "integration-split-v1",
    expectation
  )

  private def chunk(build: KnowledgeIndexBuild, slot: Int): IndexedChunk = IndexedChunk(
    DocumentChunk(
      s"${build.key.documentId}-v${build.version}-c0",
      build.key.documentId,
      "正文",
      s"doc://${build.key.documentId}",
      build.key.tenantId,
      Set("read"),
      Map.empty,
      Some("正文"),
      build.version,
      None
    ),
    unitVector(slot)
  )

  /** 发布一个 Ready 且 active 的版本。 */
  private def publish(
      harness: Harness,
      tenant: TenantId,
      documentId: String,
      ingestionId: String,
      expectation: ActiveVersionExpectation,
      slot: Int
  ): IO[RetrievalError, KnowledgeIndexBuild] =
    for
      build <- harness.index.begin(request(tenant, documentId, ingestionId, expectation))
      _     <- harness.index.stage(build, Chunk(chunk(build, slot)))
      _     <- harness.index.activate(build, 1)
    yield build

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PostgreSQL 知识清单目录")(
    test("跨租户列出全部清单，并按 updated_at 倒序返回") {
      (for
        harness <- ZIO.service[Harness]
        tenantA = TenantId("tenant-a")
        tenantB = TenantId("tenant-b")
        _      <- publish(harness, tenantA, "doc-a", "ing-a", ActiveVersionExpectation.NoActiveVersion, 1)
        _      <- publish(harness, tenantB, "doc-b", "ing-b", ActiveVersionExpectation.NoActiveVersion, 2)
        all    <- harness.directory.list(None, 10, None)
        scoped <- harness.directory.list(Some(tenantA), 10, None)
      yield assertTrue(
        all.items.length == 2,
        all.items.map(_.build.key.documentId).toSet == Set("doc-a", "doc-b"),
        all.items.forall(_.status == KnowledgeIndexStatus.Ready),
        all.items.forall(_.active),
        // 目录只返回 manifest；chunkCount 是计数而不是正文。
        all.items.forall(_.chunkCount == 1),
        !all.hasMore,
        all.nextCursor.isEmpty,
        scoped.items.length == 1,
        scoped.items.head.build.key.tenantId == tenantA
      )).provideLayer(harnessLayer)
    },
    test("同一文档的 Superseded 与 Ready 版本共享 updated_at 时，游标仍逐行前进不丢版本") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("tenant-a")
        first <- publish(harness, tenant, "doc-a", "ing-1", ActiveVersionExpectation.NoActiveVersion, 1)
        // 发布第二版：第一版转 Superseded、第二版转 Ready，两行在同一事务里被写入同一时间点。
        second <- publish(harness, tenant, "doc-a", "ing-2", ActiveVersionExpectation.Exact(first.version), 2)
        page1  <- harness.directory.list(None, 1, None)
        page2  <- harness.directory.list(None, 1, page1.nextCursor)
        // 从最后一行显式构造游标，验证走完全部清单后继续翻页返回空页而不是绕回第一页。
        beyond <- harness.directory.list(
          None,
          1,
          Some(
            KnowledgeIndexCursor(
              CursorTime.epochMicro(page2.items.head.updatedAt),
              page2.items.head.build.key.documentId,
              page2.items.head.build.version
            )
          )
        )
        seen = page1.items ++ page2.items
      yield assertTrue(
        second.version == first.version + 1L,
        page1.items.length == 1,
        page2.items.length == 1,
        // 两个版本都必须出现；游标精度低于排序列时这一行会被整段跳过。
        seen.map(_.build.version).toSet == Set(first.version, second.version),
        seen.map(_.status).toSet == Set(KnowledgeIndexStatus.Ready, KnowledgeIndexStatus.Superseded),
        // 降序排列下更高的版本先出现。
        page1.items.head.build.version == second.version,
        page1.hasMore,
        // 最后一页必须自行终止，否则管理台会无限翻页。
        !page2.hasMore,
        page2.nextCursor.isEmpty,
        beyond.items.isEmpty,
        !beyond.hasMore
      )).provideLayer(harnessLayer)
    },
    test("keyset 翻页覆盖全部清单且不重复") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("tenant-a")
        _ <- ZIO.foreachDiscard(1 to 5)(index =>
          publish(
            harness,
            tenant,
            s"doc-$index",
            s"ing-$index",
            ActiveVersionExpectation.NoActiveVersion,
            index
          )
        )
        first  <- harness.directory.list(None, 2, None)
        second <- harness.directory.list(None, 2, first.nextCursor)
        third  <- harness.directory.list(None, 2, second.nextCursor)
        seen = first.items ++ second.items ++ third.items
      yield assertTrue(
        seen.length == 5,
        seen.map(_.build.key.documentId).distinct.length == 5,
        !third.hasMore,
        third.nextCursor.isEmpty
      )).provideLayer(harnessLayer)
    },
    test("单页条数收敛到硬上限，管理台无法一次拉取整个知识库") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("tenant-a")
        _    <- publish(harness, tenant, "doc-a", "ing-a", ActiveVersionExpectation.NoActiveVersion, 1)
        huge <- harness.directory.list(None, Int.MaxValue, None)
        zero <- harness.directory.list(None, 0, None)
      yield assertTrue(huge.items.length == 1, zero.items.length == 1)).provideLayer(harnessLayer)
    },
    test("未知租户返回空页而不是报错") {
      (for
        harness <- ZIO.service[Harness]
        _       <- publish(
          harness,
          TenantId("tenant-a"),
          "doc-a",
          "ing-a",
          ActiveVersionExpectation.NoActiveVersion,
          1
        )
        page <- harness.directory.list(Some(TenantId("tenant-missing")), 10, None)
      yield assertTrue(page.items.isEmpty, !page.hasMore, page.nextCursor.isEmpty)).provideLayer(harnessLayer)
    }
  ) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.withLiveClock @@
    TestAspect.timeout(5.minutes) @@ TestAspect.sequential
