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

  private def specOf(model: String = "v1"): IndexBuildSpec =
    IndexBuildSpec(
      "integration-embedding",
      model,
      1024,
      IndexBuildSpec.UndeclaredTokenizer,
      None,
      "integration-split-v1",
      "identity"
    )

  /** 测试把正文摘要的前缀当作来源修订：不同正文即不同修订。 */
  private def revisionOf(text: String): String = s"rev-${KnowledgeDigest.sha256(text).take(16)}"

  private def lineageOf(documentId: String, text: String): DocumentLineage =
    val sha = KnowledgeDigest.sha256(text)
    DocumentLineage(
      documentId,
      revisionOf(text),
      sha,
      "text/plain",
      DocumentLineage.DirectParserId,
      sha,
      sha,
      sha
    )

  /** 构造一个版本请求；正文 hash 由正式工具计算，测试不手写伪长度。 */
  private def request(
      tenant: TenantId,
      ingestionId: String,
      text: String,
      expectation: ActiveVersionExpectation,
      key: Option[KnowledgeDocumentKey] = None,
      spec: IndexBuildSpec = specOf(),
      targetProfileId: Option[IndexProfileId] = None
  ): BeginKnowledgeIndex =
    val documentKey = key.getOrElse(KnowledgeDocumentKey(tenant, "doc-1"))
    BeginKnowledgeIndex(
      documentKey,
      ingestionId,
      s"doc://${documentKey.documentId}",
      lineageOf(documentKey.documentId, text),
      Set("read"),
      Map("title" -> "伤寒论测试资料"),
      spec,
      expectation,
      targetProfileId
    )

  /** 暂存并以真实块集合摘要发布。 */
  private def publish(
      harness: Harness,
      build: KnowledgeIndexBuild,
      chunks: Chunk[IndexedChunk]
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    harness.index
      .stage(build, chunks) *> harness.index.activate(build, ChunkSetDigest.of(chunks.map(_.chunk)))

  /** 由 manifest 生成评测 census。 */
  private def gateOf(evaluationId: String, manifests: KnowledgeIndexManifest*): ProfilePublication =
    val census = Chunk.fromIterable(manifests).map(ProfileDocument.fromManifest)
    ProfilePublication(census, evaluationId, ProfilePublication.digest(census), true)

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
    test(
      "profile publication rejects missing and incomplete evidence, keeps active profile writable, and records CAS"
    ) {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("profile-publication")
        req    = request(tenant, "initial", "完整原文", ActiveVersionExpectation.AnyVersion)
        build   <- harness.index.begin(req)
        missing <- harness.index
          .activateProfile(tenant, build.knowledgeSpaceId, build.profileId, 0L, "cutover")
          .exit
        building <- harness.index
          .activateProfile(
            tenant,
            build.knowledgeSpaceId,
            build.profileId,
            0L,
            "cutover",
            Some(
              ProfilePublication(
                Chunk.empty,
                "contract-evaluation",
                ProfilePublication.digest(Chunk.empty),
                true
              )
            )
          )
          .exit
        firstReady <- publish(harness, build, Chunk(indexed(build, "one", "完整原文", "完整 原文", unitVector(0))))
        second     <- harness.index.begin(
          request(
            tenant,
            "initial-2",
            "第二份原文",
            ActiveVersionExpectation.AnyVersion,
            key = Some(KnowledgeDocumentKey(tenant, "doc-2"))
          )
        )
        secondReady <- publish(
          harness,
          second,
          Chunk(indexed(second, "two", "第二份原文", "第二份 原文", unitVector(1)))
        )
        gate = gateOf("contract-evaluation", firstReady, secondReady)
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
        mutation <- harness.index.begin(
          req.copy(ingestionId = "overwrite", lineage = lineageOf("doc-1", "修订后原文"))
        )
        partial <- harness.index.begin(
          req.copy(
            ingestionId = "partial-new-profile",
            buildSpec = specOf("v2"),
            targetProfileId = Some(IndexProfileId("parallel-v2"))
          )
        )
        partialReady <- publish(
          harness,
          partial,
          Chunk(indexed(partial, "partial", "完整原文", "完整 原文", unitVector(2)))
        )
        incomplete <- harness.index
          .activateProfile(
            tenant,
            partial.knowledgeSpaceId,
            partial.profileId,
            revision,
            "cutover",
            Some(gateOf("partial-evaluation", partialReady))
          )
          .exit
        diff <- harness.index.profileCorpusDiff(tenant, partial.knowledgeSpaceId, partial.profileId)
      yield assertTrue(
        missing.isFailure,
        building.isFailure,
        rejected.isFailure,
        revision == 2L,
        audit == ((Some(build.profileId.value), gate.evaluationId, gate.evaluatedCensusSha256, 2, "cutover")),
        stale.isFailure,
        mutation.profileId == build.profileId,
        mutation.version == 2L,
        incomplete.isFailure,
        diff.missing.map(_.documentId) == Chunk("doc-2")
      ))
        .provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential,
    test("切换后 active Profile 可继续写入，回滚目标显式补齐后可回滚；retire 下线全部 Profile 副本") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("profile-rollback")
        space  = KnowledgeSpaceId.Default
        keyA   = KnowledgeDocumentKey(tenant, "doc-a")
        keyB   = KnowledgeDocumentKey(tenant, "doc-b")
        pinned = (profile: IndexProfileId) =>
          RetrievalScope(tenant, Set("read"), pinnedProfileId = Some(profile))
        oldA <- harness.index.begin(
          request(tenant, "a-old", "甲文", ActiveVersionExpectation.AnyVersion, Some(keyA))
        )
        oldAR <- publish(harness, oldA, Chunk(indexed(oldA, "a-old", "甲文旧模", "甲 文", unitVector(0))))
        newA  <- harness.index.begin(
          request(tenant, "a-new", "甲文", ActiveVersionExpectation.AnyVersion, Some(keyA), specOf("v2"))
        )
        newAR <- publish(harness, newA, Chunk(indexed(newA, "a-new", "甲文新模", "甲 文", unitVector(0))))
        cas   <- harness.index
          .activateProfile(tenant, space, newA.profileId, 1L, "cutover", Some(gateOf("cut", newAR)))
        newB <- harness.index.begin(
          request(tenant, "b-new", "乙文", ActiveVersionExpectation.AnyVersion, Some(keyB), specOf("v2"))
        )
        newBR       <- publish(harness, newB, Chunk(indexed(newB, "b-new", "乙文新模", "乙 文", unitVector(1))))
        implicitOld <- harness.index
          .begin(request(tenant, "b-old-implicit", "乙文", ActiveVersionExpectation.AnyVersion, Some(keyB)))
          .exit
        blocked <- harness.index
          .activateProfile(tenant, space, oldA.profileId, cas, "rollback", Some(gateOf("rollback", oldAR)))
          .exit
        oldB <- harness.index.begin(
          request(
            tenant,
            "b-old",
            "乙文",
            ActiveVersionExpectation.AnyVersion,
            Some(keyB),
            targetProfileId = Some(oldA.profileId)
          )
        )
        oldBR    <- publish(harness, oldB, Chunk(indexed(oldB, "b-old", "乙文旧模", "乙 文", unitVector(1))))
        rollback <- harness.index
          .activateProfile(
            tenant,
            space,
            oldA.profileId,
            cas,
            "rollback",
            Some(gateOf("rollback", oldAR, oldBR))
          )
        oldHits        <- harness.vectors.search(unitVector(0), pinned(oldA.profileId), 10)
        _              <- harness.index.retire(keyA, oldAR.build.version)
        afterRetireOld <- harness.vectors.search(unitVector(0), pinned(oldA.profileId), 10)
        afterRetireNew <- harness.vectors.search(unitVector(0), pinned(newA.profileId), 10)
      yield assertTrue(
        newBR.build.profileId == newA.profileId,
        implicitOld.isFailure,
        blocked.isFailure,
        oldB.profileId == oldA.profileId,
        rollback > cas,
        oldHits.map(_.chunk.id).toSet == Set("a-old", "b-old"),
        !afterRetireOld.exists(_.chunk.documentId == "doc-a"),
        !afterRetireNew.exists(_.chunk.documentId == "doc-a")
      )).provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential,
    test("并发首次导入只引导一次空间 Profile，全部文档发布成功") {
      (for
        harness <- ZIO.service[Harness]
        tenant = TenantId("concurrent-bootstrap")
        builds <- ZIO.foreachPar(Chunk.range(0, 8)) { i =>
          val key = KnowledgeDocumentKey(tenant, s"doc-$i")
          harness.index
            .begin(request(tenant, s"boot-$i", s"并发正文$i", ActiveVersionExpectation.AnyVersion, Some(key)))
            .flatMap(build =>
              publish(harness, build, Chunk(indexed(build, s"c-$i", s"并发正文$i", "并发 正文", unitVector(i))))
            )
        }
        active <- harness.index.resolveActiveProfile(tenant, KnowledgeSpaceId.Default)
        hits   <- harness.vectors.search(unitVector(0), RetrievalScope(tenant, Set("read")), 20)
      yield assertTrue(
        builds.forall(_.active),
        builds.map(_.build.profileId).toSet.size == 1,
        active.contains(builds.head.build.profileId),
        hits.length == 8
      )).provideLayer(harnessLayer)
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
        firstReady <- harness.index.activate(first, ChunkSetDigest.of(firstChunks.map(_.chunk)))
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
        secondChunks = Chunk(indexed(second, "doc-1-new", "麻黄汤新版本", "麻黄 汤", unitVector(2)))
        _             <- harness.index.stage(second, secondChunks)
        wrongCount    <- harness.index.activate(second, ChunkSetDigest(2, "0" * 64)).exit
        wrongDigest   <- harness.index.activate(second, ChunkSetDigest(1, "0" * 64)).exit
        afterRollback <- harness.vectors.search(unitVector(0), scope, 10)
        secondReady   <- harness.index.activate(second, ChunkSetDigest.of(secondChunks.map(_.chunk)))
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
          request(
            tenant,
            "shared-a",
            "共享标识的第一份文档",
            ActiveVersionExpectation.AnyVersion,
            Some(KnowledgeDocumentKey(tenant, "doc-a"))
          )
        )
        _ <- publish(
          harness,
          sharedA,
          Chunk(indexed(sharedA, "shared-chunk", "共享标识的第一份文档", "共享 标识 第一份", unitVector(3)))
        )
        sharedB <- harness.index.begin(
          request(
            tenant,
            "shared-b",
            "共享标识的第二份文档",
            ActiveVersionExpectation.AnyVersion,
            Some(KnowledgeDocumentKey(tenant, "doc-b"))
          )
        )
        _ <- publish(
          harness,
          sharedB,
          Chunk(indexed(sharedB, "shared-chunk", "共享标识的第二份文档", "共享 标识 第二份", unitVector(3)))
        )
        sharedHits <- harness.vectors.searchHybrid("共享标识", unitVector(3), scope, 10)
        orphan     <- harness.vectors
          .upsert(
            Chunk(
              IndexedChunk(
                DocumentChunk
                  .fromText("orphan-chunk", "missing-doc", "孤儿块", "doc://orphan", tenant, Set("read"))
                  .copy(
                    knowledgeSpaceId = Some(KnowledgeSpaceId.Default),
                    profileId = Some(IndexProfileId("missing-profile")),
                    sourceRevisionId = Some("rev-orphan")
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
        wrongDigest.isFailure,
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
          request(
            tenant,
            "ingestion-id-1",
            "identity-first",
            ActiveVersionExpectation.NoActiveVersion,
            Some(KnowledgeDocumentKey(tenant, "doc-identity"))
          )
        )
        _ <- publish(
          harness,
          first,
          Chunk(indexed(first, "doc-identity-0", "桂枝汤身份校验", "桂枝 汤", unitVector(0)))
        )
        matchOk  <- harness.vectors.assertEmbeddingIdentity(tenant, descriptor).exit
        mismatch <- harness.vectors
          .assertEmbeddingIdentity(
            tenant,
            descriptor.copy(model = "other-model")
          )
          .exit
        hits  <- harness.vectors.search(unitVector(0), scope, 10)
        other <- harness.index.begin(
          request(
            tenant,
            "ingestion-id-2",
            "identity-second",
            ActiveVersionExpectation.AnyVersion,
            Some(KnowledgeDocumentKey(tenant, "doc-identity-2")),
            specOf("other-model")
          )
        )
        _ <- publish(
          harness,
          other,
          Chunk(indexed(other, "doc-identity-2-0", "麻黄汤换模", "麻黄 汤", unitVector(3)))
        )
        afterSwitch <- harness.vectors.search(unitVector(0), scope, 10)
        mixed       <- harness.vectors.search(unitVector(3), scope, 10)
        unknown <- harness.index.withdraw(KnowledgeDocumentKey(tenant, "doc-identity"), "rev-unknown").exit
        _       <- harness.index.withdraw(
          KnowledgeDocumentKey(tenant, "doc-identity"),
          revisionOf("identity-first")
        )
        withdrawn <- harness.vectors.search(unitVector(0), scope, 10)
        remaining <- ZIO.attemptBlocking {
          val connection = harness.dataSource.getConnection
          try
            val statement = connection.prepareStatement(
              """SELECT count(*) FROM zyblw_agent_knowledge.agent_knowledge_profile_chunks
                |WHERE tenant_id = ? AND document_id = 'doc-identity'""".stripMargin
            )
            try
              statement.setString(1, tenant.value)
              val result = statement.executeQuery()
              result.next()
              result.getLong(1)
            finally statement.close()
          finally connection.close()
        }
        replay <- harness.index
          .begin(
            request(
              tenant,
              "ingestion-id-1-replay",
              "identity-first",
              ActiveVersionExpectation.AnyVersion,
              Some(KnowledgeDocumentKey(tenant, "doc-identity"))
            )
          )
          .exit
      yield assertTrue(
        emptyOk.isSuccess,
        matchOk.isSuccess,
        mismatch.isFailure,
        hits.map(_.chunk.id) == Chunk("doc-identity-0"),
        hits.head.chunk.displayText.contains("桂枝"),
        afterSwitch.map(_.chunk.id) == Chunk("doc-identity-0"),
        !mixed.exists(_.chunk.documentId == "doc-identity-2"),
        unknown.isFailure,
        withdrawn.isEmpty,
        remaining == 0L,
        replay.isFailure
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
          request(
            tenant,
            "wd-a1",
            textA,
            ActiveVersionExpectation.AnyVersion,
            Some(KnowledgeDocumentKey(tenant, "doc-wd", spaceA))
          )
        )
        _      <- publish(harness, first, Chunk(indexed(first, "wd-a1", textA, "桂枝 汤 甲", unitVector(0))))
        second <- harness.index.begin(
          request(
            tenant,
            "wd-b1",
            textA,
            ActiveVersionExpectation.AnyVersion,
            Some(KnowledgeDocumentKey(tenant, "doc-wd", spaceB))
          )
        )
        _       <- publish(harness, second, Chunk(indexed(second, "wd-b1", textB, "桂枝 汤 乙", unitVector(1))))
        _       <- harness.index.withdraw(KnowledgeDocumentKey(tenant, "doc-wd", spaceA), revisionOf(textA))
        hiddenA <- harness.vectors.search(unitVector(0), scopeOf(spaceA), 10)
        keptB   <- harness.vectors.search(unitVector(1), scopeOf(spaceB), 10)
        newer   <- harness.index.begin(
          request(
            tenant,
            "wd-a2",
            textA2,
            ActiveVersionExpectation.AnyVersion,
            Some(KnowledgeDocumentKey(tenant, "doc-wd", spaceA))
          )
        )
        _         <- publish(harness, newer, Chunk(indexed(newer, "wd-a2", textA2, "桂枝 汤 新", unitVector(2))))
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
