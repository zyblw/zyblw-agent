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
      dataSource: DataSource,
      coreReplayMigrations: Int,
      firstMigrations: Int,
      replayMigrations: Int,
      vectorExtensionVersion: Option[String],
      knowledgeVersion: Option[String]
  )

  /** 启动 `pgvector/pgvector:0.8.6-pg18-bookworm`，依次执行 public 核心基线和专属 schema 中的知识库基线。 */
  private val harnessLayer: ZLayer[Any, Throwable, Harness] = ZLayer.scoped {
    for
      container <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val image = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg18-bookworm")
            .asCompatibleSubstituteFor("postgres")
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
      // 模拟独立宿主已经在 public schema 中创建业务对象；agent 只能以受限 version 0 baseline 接入，
      // 不能因此跳过 V001+ 或接管已有 agent core 表。
      _ <- ZIO.attemptBlocking {
        val connection = dataSource.getConnection
        try
          val statement = connection.createStatement()
          try statement.execute("CREATE TABLE host_shared_schema_marker (id bigint PRIMARY KEY)")
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
      knowledgeStatus <- AgentPostgresMigrations.inspectKnowledge1024(dataSource)
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
      dataSource,
      coreReplay.migrationsExecuted,
      firstMigration.migrationsExecuted,
      replayMigration.migrationsExecuted,
      verification.extensionVersion,
      knowledgeStatus.currentVersion
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
    DocumentChunk.fromText(
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
    test("profile publication rejects missing and incomplete evidence, seals writes, and records CAS") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("profile-publication")
        req    = request(tenant, "initial", "完整原文", ActiveVersionExpectation.AnyVersion)
        build <- harness.index.begin(req)
        firstCensus = Chunk(ProfileDocument(build.key.documentId, build.version, build.contentHash, 1))
        firstGate   = ProfilePublication(
          firstCensus,
          "contract-evaluation",
          ProfilePublication.digest(firstCensus),
          true
        )
        missing <- harness.index
          .activateProfile(tenant, build.knowledgeSpaceId, build.profileId, 0L, "cutover")
          .exit
        building <- harness.index
          .activateProfile(tenant, build.knowledgeSpaceId, build.profileId, 0L, "cutover", Some(firstGate))
          .exit
        _      <- harness.index.stage(build, Chunk(indexed(build, "one", "完整原文", "完整 原文", unitVector(0))))
        _      <- harness.index.activate(build, 1)
        second <- harness.index.begin(
          req.copy(
            key = KnowledgeDocumentKey(tenant, "doc-2"),
            ingestionId = "initial-2",
            sourceUri = "doc://2",
            contentHash = KnowledgeIndexer.sha256("第二份原文")
          )
        )
        _ <- harness.index.stage(second, Chunk(indexed(second, "two", "第二份原文", "第二份 原文", unitVector(1))))
        _ <- harness.index.activate(second, 1)
        census = Chunk(
          ProfileDocument(build.key.documentId, build.version, build.contentHash, 1),
          ProfileDocument(second.key.documentId, second.version, second.contentHash, 1)
        )
        gate = ProfilePublication(census, "contract-evaluation", ProfilePublication.digest(census), true)
        rejected <- harness.index
          .activateProfile(
            tenant,
            build.knowledgeSpaceId,
            build.profileId,
            1L,
            "cutover",
            Some(gate.copy(qualityPassed = false))
          )
          .exit
        revision <- harness.index
          .activateProfile(tenant, build.knowledgeSpaceId, build.profileId, 1L, "cutover", Some(gate))
        audit <- ZIO.attemptBlocking {
          val connection = harness.dataSource.getConnection
          try
            val statement = connection.prepareStatement(
              """SELECT old_profile_id, evaluation_id, evaluated_census_sha256, document_count, reason
                |FROM zyblw_agent_knowledge.agent_knowledge_profile_activation_audit
                |WHERE tenant_id = ? AND knowledge_space_id = ? AND evaluation_id IS NOT NULL
                |ORDER BY activated_at DESC LIMIT 1""".stripMargin
            )
            try
              statement.setString(1, tenant.value)
              statement.setString(2, build.knowledgeSpaceId.value)
              val result = statement.executeQuery()
              if !result.next() then throw IllegalStateException("evaluated activation audit missing")
              (
                Option(result.getString(1)),
                result.getString(2),
                result.getString(3),
                result.getInt(4),
                result.getString(5)
              )
            finally statement.close()
          finally connection.close()
        }
        stale <- harness.index
          .activateProfile(tenant, build.knowledgeSpaceId, build.profileId, 1L, "cutover", Some(gate))
          .exit
        mutation <- harness.index.begin(req.copy(ingestionId = "overwrite", contentHash = "b" * 64)).exit
        partial  <- harness.index.begin(
          req.copy(
            ingestionId = "partial-new-profile",
            embedding = descriptor.copy(model = "v2"),
            targetProfileId = Some(IndexProfileId("parallel-v2"))
          )
        )
        _ <- harness.index.stage(partial, Chunk(indexed(partial, "partial", "完整原文", "完整 原文", unitVector(2))))
        _ <- harness.index.activate(partial, 1)
        partialCensus = Chunk(
          ProfileDocument(partial.key.documentId, partial.version, partial.contentHash, 1)
        )
        partialGate = ProfilePublication(
          partialCensus,
          "partial-evaluation",
          ProfilePublication.digest(partialCensus),
          true
        )
        incomplete <- harness.index
          .activateProfile(
            tenant,
            partial.knowledgeSpaceId,
            partial.profileId,
            revision,
            "cutover",
            Some(partialGate)
          )
          .exit
      yield assertTrue(
        missing.isFailure,
        building.isFailure,
        rejected.isFailure,
        revision == 2L,
        audit == ((Some(build.profileId.value), gate.evaluationId, gate.evaluatedCensusSha256, 2, "cutover")),
        stale.isFailure,
        mutation.isFailure,
        incomplete.isFailure
      ))
        .provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential,
    test("retire 仅删除当前 Profile chunks，旧 Profile 快照保持完整") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("profile-retire-isolation")
        first <- harness.index.begin(
          request(tenant, "profile-a", "相同正文", ActiveVersionExpectation.AnyVersion)
        )
        _ <- harness.index.stage(first, Chunk(indexed(first, "old", "旧 Profile", "旧 Profile", unitVector(0))))
        _ <- harness.index.activate(first, 1)
        second <- harness.index.begin(
          request(tenant, "profile-b", "相同正文", ActiveVersionExpectation.AnyVersion)
            .copy(embedding = descriptor.copy(model = "v2"))
        )
        _ <- harness.index.stage(
          second,
          Chunk(indexed(second, "new", "新 Profile", "新 Profile", unitVector(0)))
        )
        _ <- harness.index.activate(second, 1)
        census = Chunk(ProfileDocument(second.key.documentId, second.version, second.contentHash, 1))
        _ <- harness.index.activateProfile(
          tenant,
          second.knowledgeSpaceId,
          second.profileId,
          1L,
          "cutover",
          Some(ProfilePublication(census, "retire-isolation", ProfilePublication.digest(census), true))
        )
        _       <- harness.index.retire(second.key, second.version)
        oldHits <- harness.vectors.search(
          unitVector(0),
          RetrievalScope(
            tenant,
            Set("read"),
            pinnedProfileId = Some(first.profileId)
          ),
          10
        )
      yield assertTrue(oldHits.map(_.chunk.id) == Chunk("old"))).provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential,
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
        // 短查询会走 Phrase；heading_path 过滤有 3 个占位符，绑定数必须对齐否则 JDBC 抛「未设定参数」。
        _ <- harness.vectors.searchFiltered(
          RetrievalMode.Phrase,
          "桂枝汤用于测试",
          unitVector(0),
          scope,
          RetrievalFilter(),
          10
        )
        expansion <- harness.vectors.expandContext(
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
        sharedA       <- harness.index.begin(
          request(tenant, "shared-a", "共享标识的第一份文档", ActiveVersionExpectation.AnyVersion)
            .copy(key = KnowledgeDocumentKey(tenant, "doc-a"))
        )
        _ <- harness.index.stage(
          sharedA,
          Chunk(indexed(sharedA, "shared-chunk", "共享标识的第一份文档", "共享 标识 第一份", unitVector(3)))
        )
        _       <- harness.index.activate(sharedA, 1)
        sharedB <- harness.index.begin(
          request(tenant, "shared-b", "共享标识的第二份文档", ActiveVersionExpectation.AnyVersion)
            .copy(key = KnowledgeDocumentKey(tenant, "doc-b"))
        )
        _ <- harness.index.stage(
          sharedB,
          Chunk(indexed(sharedB, "shared-chunk", "共享标识的第二份文档", "共享 标识 第二份", unitVector(3)))
        )
        _          <- harness.index.activate(sharedB, 1)
        sharedHits <- harness.vectors.searchHybrid("共享标识", unitVector(3), scope, 10)
        orphan     <- harness.vectors
          .upsert(
            Chunk(
              IndexedChunk(
                DocumentChunk
                  .fromText("orphan-chunk", "missing-doc", "孤儿块", "doc://orphan", tenant, Set("read"))
                  .copy(
                    knowledgeSpaceId = Some(KnowledgeSpaceId("default")),
                    profileId = Some(IndexProfileId("default"))
                  ),
                unitVector(4)
              )
            )
          )
          .exit
      yield assertTrue(
        before.isEmpty,
        harness.coreReplayMigrations == 0,
        // V001 结构基线 + R__ 中文数据字典；二次启动仍为零次执行。
        harness.firstMigrations == 2,
        harness.replayMigrations == 0,
        harness.vectorExtensionVersion.exists(_.startsWith("0.8.")),
        harness.knowledgeVersion.contains("001"),
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
        sharedHits.filter(_.chunk.id == "shared-chunk").map(_.chunk.documentId).toSet == Set(
          "doc-a",
          "doc-b"
        ),
        orphan.isFailure,
        orphan.causeOption.exists(_.prettyPrint.toLowerCase.contains("foreign key"))
      )).provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential,
    test("查询 embedding 身份与 active 索引不一致时 fail-closed") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("tenant-identity")
        scope  = RetrievalScope(tenant, Set("read"))
        emptyOk <- harness.vectors.assertEmbeddingIdentity(tenant, descriptor).exit
        first   <- harness.index.begin(
          request(tenant, "ingestion-id-1", "identity-first", ActiveVersionExpectation.NoActiveVersion)
            .copy(key = KnowledgeDocumentKey(tenant, "doc-identity"))
        )
        _ <- harness.index.stage(
          first,
          Chunk(indexed(first, "doc-identity-0", "桂枝汤身份校验", "桂枝 汤", unitVector(0)))
        )
        _        <- harness.index.activate(first, 1)
        matchOk  <- harness.vectors.assertEmbeddingIdentity(tenant, descriptor).exit
        mismatch <- harness.vectors
          .assertEmbeddingIdentity(
            tenant,
            descriptor.copy(model = "other-model")
          )
          .exit
        hits  <- harness.vectors.search(unitVector(0), scope, 10)
        other <- harness.index.begin(
          request(tenant, "ingestion-id-2", "identity-second", ActiveVersionExpectation.AnyVersion)
            .copy(
              key = KnowledgeDocumentKey(tenant, "doc-identity-2"),
              embedding = descriptor.copy(model = "other-model")
            )
        )
        _ <- harness.index.stage(
          other,
          Chunk(indexed(other, "doc-identity-2-0", "麻黄汤换模", "麻黄 汤", unitVector(3)))
        )
        _           <- harness.index.activate(other, 1)
        afterSwitch <- harness.vectors.search(unitVector(0), scope, 10)
        mixed       <- harness.vectors.search(unitVector(3), scope, 10)
        _           <- harness.index.withdraw(
          KnowledgeDocumentKey(tenant, "doc-identity"),
          KnowledgeIndexer.sha256("identity-first")
        )
        withdrawn <- harness.vectors.search(unitVector(0), scope, 10)
      yield assertTrue(
        emptyOk.isSuccess,
        matchOk.isSuccess,
        mismatch.isFailure,
        hits.map(_.chunk.id) == Chunk("doc-identity-0"),
        hits.head.chunk.displayText.contains("桂枝"),
        afterSwitch.map(_.chunk.id) == Chunk("doc-identity-0"),
        !mixed.exists(_.chunk.documentId == "doc-identity-2"),
        withdrawn.isEmpty
      )).provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential,
    test("撤回一个空间的一个修订不会隐藏其他空间或更新修订") {
      val tenant                           = TenantId("tenant-withdraw-scope")
      val spaceA                           = KnowledgeSpaceId("space-a")
      val spaceB                           = KnowledgeSpaceId("space-b")
      val textA                            = "桂枝汤空间甲修订一"
      val textB                            = "桂枝汤空间乙修订一"
      val textA2                           = "桂枝汤空间甲修订二"
      def scopeOf(space: KnowledgeSpaceId) =
        RetrievalScope(tenant, Set("read"), knowledgeSpaceId = Some(space))
      (for
        harness <- ZIO.service[Harness]
        first   <- harness.index.begin(
          request(tenant, "wd-a1", textA, ActiveVersionExpectation.AnyVersion).copy(
            key = KnowledgeDocumentKey(tenant, "doc-wd"),
            knowledgeSpaceId = spaceA
          )
        )
        _ <- harness.index.stage(
          first,
          Chunk(indexed(first, "wd-a1", textA, "桂枝 汤 甲", unitVector(0)))
        )
        _      <- harness.index.activate(first, 1)
        second <- harness.index.begin(
          request(tenant, "wd-b1", textB, ActiveVersionExpectation.AnyVersion).copy(
            key = KnowledgeDocumentKey(tenant, "doc-wd"),
            knowledgeSpaceId = spaceB
          )
        )
        _ <- harness.index.stage(
          second,
          Chunk(indexed(second, "wd-b1", textB, "桂枝 汤 乙", unitVector(1)))
        )
        _ <- harness.index.activate(second, 1)
        _ <- harness.index.withdraw(
          KnowledgeDocumentKey(tenant, "doc-wd"),
          KnowledgeIndexer.sha256(textA),
          spaceA
        )
        hiddenA <- harness.vectors.search(unitVector(0), scopeOf(spaceA), 10)
        keptB   <- harness.vectors.search(unitVector(1), scopeOf(spaceB), 10)
        newer   <- harness.index.begin(
          request(tenant, "wd-a2", textA2, ActiveVersionExpectation.AnyVersion).copy(
            key = KnowledgeDocumentKey(tenant, "doc-wd"),
            knowledgeSpaceId = spaceA
          )
        )
        _ <- harness.index.stage(
          newer,
          Chunk(indexed(newer, "wd-a2", textA2, "桂枝 汤 新", unitVector(2)))
        )
        _         <- harness.index.activate(newer, 1)
        visibleA  <- harness.vectors.search(unitVector(2), scopeOf(spaceA), 10)
        stillKept <- harness.vectors.search(unitVector(1), scopeOf(spaceB), 10)
      yield assertTrue(
        hiddenA.isEmpty,
        keptB.map(_.chunk.id) == Chunk("wd-b1"),
        visibleA.map(_.chunk.id) == Chunk("wd-a2"),
        stillKept.map(_.chunk.id) == Chunk("wd-b1")
      )).provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential
  )
