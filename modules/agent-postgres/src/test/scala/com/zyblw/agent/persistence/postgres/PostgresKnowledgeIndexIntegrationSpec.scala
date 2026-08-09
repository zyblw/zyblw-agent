package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 使用带 pgvector 扩展的真实 PostgreSQL 验证知识版本暂存、原子发布与 hybrid retrieval。
  *
  * 默认单元测试不会拉取 Docker 镜像；CI 或本机显式设置 `RUN_POSTGRES_INTEGRATION=1` 后才执行。该门禁只 控制环境成本，不代表这些契约可永久忽略。
  */
object PostgresKnowledgeIndexIntegrationSpec extends ZIOSpecDefault:

  /** 同时暴露版本 Store 与检索 Store，确保二者操作同一份真实 schema。 */
  final private case class Harness(
      index: KnowledgeIndexStore,
      vectors: VectorStore,
      coreReplayMigrations: Int,
      firstMigrations: Int,
      replayMigrations: Int,
      vectorExtensionVersion: Option[String]
  )

  /** 启动 `pgvector/pgvector:pg16`，依次执行 public 核心基线和专属 schema 中的知识库基线。 */
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
      // 模拟平台已经在 public schema 中创建业务对象；agent 只能以受限 version 0 baseline 接入，
      // 不能因此跳过 V001+ 或接管已有 agent core 表。
      _ <- ZIO.attemptBlocking {
        val connection = dataSource.getConnection
        try
          val statement = connection.createStatement()
          try statement.execute("CREATE TABLE platform_shared_schema_marker (id bigint PRIMARY KEY)")
          finally statement.close()
        finally connection.close()
      }
      _ <- AgentPostgresMigrations.migrate(dataSource, AgentPostgresMigrationConfig.sharedPublicSchema)
      coreReplay <- AgentPostgresMigrations.migrate(
        dataSource,
        AgentPostgresMigrationConfig.sharedPublicSchema
      )
      firstMigration  <- AgentPostgresMigrations.migrateKnowledge1024(dataSource)
      replayMigration <- AgentPostgresMigrations.migrateKnowledge1024(dataSource)
      verification    <- AgentPostgresMigrations.verifyKnowledge1024(dataSource)
      knowledge       <- PostgresAgentPersistence
        .knowledge(
          1024,
          PostgresHybridSearchConfig(enableHnswIterativeScan = false)
        )
        .build
        .provideSome[Scope](ZLayer.succeed[DataSource](dataSource))
    yield Harness(
      knowledge.get[KnowledgeIndexStore],
      knowledge.get[VectorStore],
      coreReplay.migrationsExecuted,
      firstMigration.migrationsExecuted,
      replayMigration.migrationsExecuted,
      verification.extensionVersion
    )
  }

  /** 创建 1024 维单位向量；slot 用于制造可预测 cosine 排名。 */
  private def unitVector(slot: Int): Embedding =
    val values = Array.fill[Float](1024)(0.0f)
    values(slot) = 1.0f
    Embedding(Chunk.fromArray(values))

  /** 创建与 optional baseline 一致的 Embedding 描述。 */
  private val descriptor = EmbeddingProviderDescriptor(
    "integration-embedding",
    "v1",
    1024,
    100,
    supportsDimensions = false
  )

  /** 构造一个版本请求；正文 hash 由正式工具计算，测试不手写伪长度。 */
  private def request(
      tenant: TenantId,
      ingestionId: String,
      text: String,
      expectation: ActiveVersionExpectation
  ): BeginKnowledgeIndex = BeginKnowledgeIndex(
    KnowledgeDocumentKey(tenant, "doc-1"),
    ingestionId,
    "doc://1",
    KnowledgeIndexer.sha256(text),
    Set("read"),
    Map("title" -> "伤寒论测试资料"),
    descriptor,
    "integration-split-v1",
    expectation
  )

  /** 为给定 build 创建带显式中文分词文本的暂存块。 */
  private def indexed(
      build: KnowledgeIndexBuild,
      id: String,
      text: String,
      searchText: String,
      vector: Embedding,
      permissions: Set[String] = Set("read"),
      ordinal: Int = 0,
      previous: Option[String] = None,
      next: Option[String] = None
  ): IndexedChunk = IndexedChunk(
    DocumentChunk(
      id,
      build.key.documentId,
      text,
      "doc://1",
      build.key.tenantId,
      permissions,
      Map("section" -> id),
      Some(searchText),
      build.version,
      Some(
        ChunkLineage(
          Some("section-a"),
          ordinal,
          previous,
          next,
          headingPath = Chunk("测试篇", "section-a"),
          origins = Chunk(
            DocumentOrigin(
              ordinal + 1,
              Some(DocumentBoundingBox(10, 20, 100, 40, Some(612), Some(792))),
              Some(s"block-$ordinal")
            )
          ),
          blockIds = Chunk(s"block-$ordinal")
        )
      )
    ),
    vector
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PostgreSQL knowledge index")(
    test("暂存不可见，发布原子切换，块数失败回滚且 hybrid 保持权限边界") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("tenant-a")
        scope  = RetrievalScope(tenant, Set("read"))
        first <- harness.index.begin(
          request(tenant, "ingestion-1", "first", ActiveVersionExpectation.NoActiveVersion)
        )
        firstChunks = Chunk(
          indexed(first, "doc-1-0", "桂枝汤用于测试", "桂枝 汤 测试", unitVector(0), next = Some("doc-1-1")),
          indexed(
            first,
            "doc-1-1",
            "租户内受限资料",
            "受限 资料",
            unitVector(1),
            Set("private"),
            ordinal = 1,
            previous = Some("doc-1-0")
          )
        )
        _          <- harness.index.stage(first, firstChunks)
        before     <- harness.vectors.searchHybrid("桂枝", unitVector(0), scope, 10)
        firstReady <- harness.index.activate(first, 2)
        after      <- harness.vectors.searchHybrid("桂枝", unitVector(0), scope, 10)
        expansion  <- harness.vectors.expandContext(
          after,
          scope,
          RetrievalExpansionConfig(parentHitThreshold = 1)
        )
        firstReplay <- harness.index.begin(
          request(tenant, "ingestion-1", "first", ActiveVersionExpectation.NoActiveVersion)
        )
        second <- harness.index.begin(
          request(tenant, "ingestion-2", "second", ActiveVersionExpectation.Exact(1L))
        )
        _ <- harness.index.stage(second, Chunk(indexed(second, "doc-1-new", "麻黄汤新版本", "麻黄 汤", unitVector(2))))
        wrongCount    <- harness.index.activate(second, 2).exit
        afterRollback <- harness.vectors.search(unitVector(0), scope, 10)
        secondReady   <- harness.index.activate(second, 1)
        finalHits     <- harness.vectors.search(unitVector(2), scope, 10)
        active        <- harness.index.active(KnowledgeDocumentKey(tenant, "doc-1"))
        staleRetire   <- harness.index.retire(KnowledgeDocumentKey(tenant, "doc-1"), 1L).exit
        retired       <- harness.index.retire(KnowledgeDocumentKey(tenant, "doc-1"), 2L)
        retireReplay  <- harness.index.retire(KnowledgeDocumentKey(tenant, "doc-1"), 2L)
        afterRetire   <- harness.vectors.search(unitVector(2), scope, 10)
        purged        <- harness.index.purgeInactive(retired.updatedAt.plusSeconds(1), 10)
        goneFirst     <- harness.index.find(KnowledgeDocumentKey(tenant, "doc-1"), "ingestion-1")
        goneSecond    <- harness.index.find(KnowledgeDocumentKey(tenant, "doc-1"), "ingestion-2")
        sharedChunkA = DocumentChunk(
          "shared-chunk",
          "doc-a",
          "共享标识的第一份文档",
          "doc://a",
          tenant,
          Set("read"),
          searchText = Some("共享 标识 第一份")
        )
        sharedChunkB = DocumentChunk(
          "shared-chunk",
          "doc-b",
          "共享标识的第二份文档",
          "doc://b",
          tenant,
          Set("read"),
          searchText = Some("共享 标识 第二份")
        )
        _ <- harness.vectors.upsert(
          Chunk(IndexedChunk(sharedChunkA, unitVector(3)), IndexedChunk(sharedChunkB, unitVector(3)))
        )
        sharedHits <- harness.vectors.searchHybrid("共享标识", unitVector(3), scope, 10)
      yield assertTrue(
        before.isEmpty,
        harness.coreReplayMigrations == 0,
        harness.firstMigrations == 1,
        harness.replayMigrations == 0,
        harness.vectorExtensionVersion.exists(_.startsWith("0.8.")),
        firstReady.active,
        firstReady.chunkCount == 2,
        after.map(_.chunk.id) == Chunk("doc-1-0"),
        after.head.signals.contains("textRank"),
        after.head.chunk.lineage.exists(_.pageNumbers == Chunk(1)),
        after.head.chunk.lineage.exists(_.headingPath == Chunk("测试篇", "section-a")),
        after.head.chunk.lineage.exists(_.origins.head.boundingBox.nonEmpty),
        after.head.chunk.lineage.exists(_.blockIds == Chunk("block-0")),
        expansion.isEmpty,
        firstReplay == first,
        wrongCount.isFailure,
        afterRollback.map(_.chunk.id) == Chunk("doc-1-0"),
        secondReady.build.version == 2L,
        finalHits.map(_.chunk.id) == Chunk("doc-1-new"),
        active.contains(secondReady),
        staleRetire.isFailure,
        retired.status == KnowledgeIndexStatus.Retired,
        retireReplay == retired,
        afterRetire.isEmpty,
        purged == 2L,
        goneFirst.isEmpty,
        goneSecond.isEmpty,
        sharedHits.filter(_.chunk.id == "shared-chunk").map(_.chunk.documentId).toSet == Set("doc-a", "doc-b")
      )).provideLayer(harnessLayer)
    } @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential
  )
