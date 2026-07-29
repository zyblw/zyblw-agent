package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import javax.sql.DataSource
import org.flywaydb.core.Flyway
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
  final private case class Harness(index: KnowledgeIndexStore, vectors: VectorStore)

  /** 启动 `pgvector/pgvector:pg16` 并只执行 optional pgvector baseline。 */
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
      _ <- ZIO.attemptBlocking {
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations(AgentPostgresMigrations.OptionalPgVectorLocation)
          .load()
          .migrate()
      }
      knowledge <- PostgresAgentPersistence
        .knowledge(
          1536,
          PostgresHybridSearchConfig(enableHnswIterativeScan = false)
        )
        .build
        .provideSome[Scope](ZLayer.succeed[DataSource](dataSource))
    yield Harness(knowledge.get[KnowledgeIndexStore], knowledge.get[VectorStore])
  }

  /** 创建 1536 维单位向量；slot 用于制造可预测 cosine 排名。 */
  private def unitVector(slot: Int): Embedding =
    val values = Array.fill[Float](1536)(0.0f)
    values(slot) = 1.0f
    Embedding(Chunk.fromArray(values))

  /** 创建与 optional baseline 一致的 Embedding 描述。 */
  private val descriptor = EmbeddingProviderDescriptor(
    "integration-embedding",
    "v1",
    1536,
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
      permissions: Set[String] = Set("read")
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
      build.version
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
          indexed(first, "doc-1-0", "桂枝汤用于测试", "桂枝 汤 测试", unitVector(0)),
          indexed(first, "doc-1-1", "租户内受限资料", "受限 资料", unitVector(1), Set("private"))
        )
        _           <- harness.index.stage(first, firstChunks)
        before      <- harness.vectors.searchHybrid("桂枝", unitVector(0), scope, 10)
        firstReady  <- harness.index.activate(first, 2)
        after       <- harness.vectors.searchHybrid("桂枝", unitVector(0), scope, 10)
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
      yield assertTrue(
        before.isEmpty,
        firstReady.active,
        firstReady.chunkCount == 2,
        after.map(_.chunk.id) == Chunk("doc-1-0"),
        after.head.signals.contains("textRank"),
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
        goneSecond.isEmpty
      )).provideLayer(harnessLayer)
    } @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential
  )
