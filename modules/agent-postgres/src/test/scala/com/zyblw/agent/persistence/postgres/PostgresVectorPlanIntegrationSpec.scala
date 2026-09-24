package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import scala.util.Random
import zio.*
import zio.test.*

/** 向量检索物理设计的回归门禁：执行计划与召回/填充率。
  *
  * 固定种子生成 5000 条聚类的 1024 维向量（50 份文档 × 100 块），经正式 Building→stage→activate 协议发布后：
  *   - 计划：可见性谓词是绑定参数，不出现相关子查询；宽过滤走 HNSW。
  *   - 召回：与精确暴力排序比较 recall@10；10% 权限选择性与 25% metadata 过滤下，迭代扫描仍填满 limit。
  */
object PostgresVectorPlanIntegrationSpec extends ZIOSpecDefault:
  private val Dimension     = 1024
  private val Documents     = 50
  private val ChunksPerDoc  = 100
  private val Clusters      = 20
  private val Limit         = 10
  private val tenant        = TenantId("plan-recall")
  private val HnswIndexName = "agent_knowledge_profile_chunks_embedding_hnsw_idx"

  final private case class Row(chunk: DocumentChunk, vector: Array[Float])

  /** `forced*` 用禁用 seqscan/sort 的连接强制走 HNSW，专门度量迭代扫描在选择性过滤下的填充率。 */
  final private case class Harness(
      dataSource: DataSource,
      store: PostgresPgVectorStore,
      forcedIterative: PostgresPgVectorStore,
      forcedSingleScan: PostgresPgVectorStore,
      rows: Chunk[Row]
  )

  private def normalize(values: Array[Float]): Array[Float] =
    val norm = math.sqrt(values.map(v => v.toDouble * v).sum).toFloat
    values.map(_ / norm)

  private def cosine(a: Array[Float], b: Array[Float]): Double =
    var dot = 0.0
    var i   = 0
    while i < a.length do
      dot += a(i).toDouble * b(i)
      i += 1
    dot

  private val random    = Random(20260924L)
  private val centroids =
    Array.fill(Clusters)(normalize(Array.fill(Dimension)(random.nextGaussian().toFloat)))

  private def near(cluster: Int, spread: Double): Array[Float] =
    normalize(
      centroids(cluster).map(v => (v + random.nextGaussian() * spread / math.sqrt(Dimension)).toFloat)
    )

  private val queries: Chunk[Array[Float]] =
    Chunk.fromIterable((0 until 20).map(i => near(i % Clusters, 0.8)))

  private def restricted(documentIndex: Int): Boolean = documentIndex % 10 == 0
  private def tierA(documentIndex: Int): Boolean      = documentIndex % 4 == 0

  private val buildSpec = IndexBuildSpec(
    "plan-embedding",
    "v1",
    Dimension,
    IndexBuildSpec.UndeclaredTokenizer,
    None,
    "plan-split-v1",
    "identity"
  )

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
      connect = (options: Option[String]) =>
        val value = PGSimpleDataSource()
        value.setURL(container.jdbcUrl)
        value.setUser(container.username)
        value.setPassword(container.password)
        options.foreach(value.setOptions)
        value: DataSource
      dataSource <- ZIO.attempt(connect(None))
      forced     <- ZIO.attempt(connect(Some("-c enable_seqscan=off -c enable_sort=off")))
      _ <- AgentPostgresMigrations.migrate(dataSource, AgentPostgresMigrationConfig.sharedPublicSchema)
      _ <- AgentPostgresMigrations.migrateKnowledge1024(dataSource)
      index = PostgresKnowledgeIndexStore(dataSource, Dimension)
      rows <- ZIO.foreach(Chunk.range(0, Documents)) { d =>
        val documentId = s"doc-$d"
        val sha        = KnowledgeDigest.sha256(documentId)
        val request    = BeginKnowledgeIndex(
          KnowledgeDocumentKey(tenant, documentId),
          s"ingest-$d",
          s"doc://$documentId",
          DocumentLineage(
            documentId,
            "rev-1",
            sha,
            "text/plain",
            DocumentLineage.DirectParserId,
            sha,
            sha,
            sha
          ),
          if restricted(d) then Set("restricted") else Set("read"),
          Map.empty,
          buildSpec
        )
        for
          build <- index.begin(request)
          rows = Chunk.fromIterable((0 until ChunksPerDoc).map { c =>
            val chunk = DocumentChunk(
              s"$documentId-c$c",
              documentId,
              s"正文 $d $c",
              s"doc://$documentId",
              tenant,
              request.permissions
            ).copy(
              catalogVersion = build.version,
              metadata = Map("tier" -> (if tierA(d) then "a" else "b"))
            )
            Row(chunk, near((d * 7 + c) % Clusters, 1.2))
          })
          _ <- index.stage(
            build,
            rows.map(row => IndexedChunk(row.chunk, Embedding(Chunk.fromArray(row.vector))))
          )
          _ <- index.activate(build, ChunkSetDigest.of(rows.map(_.chunk)))
        yield rows
      }
      _ <- ZIO.attemptBlocking {
        val connection = dataSource.getConnection
        try
          val statement = connection.createStatement()
          try statement.execute("ANALYZE zyblw_agent_knowledge.agent_knowledge_profile_chunks")
          finally statement.close()
        finally connection.close()
      }
    yield Harness(
      dataSource,
      PostgresPgVectorStore(dataSource, Dimension, PostgresHybridSearchConfig()),
      PostgresPgVectorStore(forced, Dimension, PostgresHybridSearchConfig()),
      PostgresPgVectorStore(forced, Dimension, PostgresHybridSearchConfig(enableHnswIterativeScan = false)),
      rows.flatten
    )
  }

  private def exact(
      rows: Chunk[Row],
      query: Array[Float],
      scope: RetrievalScope,
      filter: RetrievalFilter
  ): Chunk[String] =
    rows
      .filter(row => row.chunk.permissions.subsetOf(scope.permissions) && filter.matches(row.chunk))
      .sortBy(row => -cosine(row.vector, query))
      .take(Limit)
      .map(_.chunk.id)

  /** 平均 recall@Limit 与“每个查询都填满 limit”。 */
  private def measure(
      harness: Harness,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      store: Harness => PostgresPgVectorStore = _.store
  ): IO[RetrievalError, (Double, Boolean)] =
    ZIO
      .foreach(queries) { query =>
        store(harness)
          .searchFiltered(
            RetrievalMode.VectorOnly,
            "",
            Embedding(Chunk.fromArray(query)),
            scope,
            filter,
            Limit
          )
          .map { hits =>
            val truth = exact(harness.rows, query, scope, filter).toSet
            (hits.count(hit => truth.contains(hit.chunk.id)).toDouble / truth.size, hits.length == Limit)
          }
      }
      .map(results => (results.map(_._1).sum / results.length, results.forall(_._2)))

  private val broad          = RetrievalScope(tenant, Set("read", "restricted"))
  private val onlyRestricted = RetrievalScope(tenant, Set("restricted"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PostgreSQL 向量物理设计")(
    test("执行计划：Profile 为绑定参数、无相关子查询，宽过滤走 HNSW") {
      (for
        harness <- ZIO.service[Harness]
        query = Embedding(Chunk.fromArray(queries.head))
        plain    <- harness.store.explainVectorSearch(query, broad, RetrievalFilter.empty, Limit)
        filtered <- harness.store.explainVectorSearch(
          query,
          broad,
          RetrievalFilter(metadataEquals = Map("tier" -> "a")),
          Limit
        )
        selective <- harness.store.explainVectorSearch(query, onlyRestricted, RetrievalFilter.empty, Limit)
      yield assertTrue(
        plain.contains(HnswIndexName),
        !plain.contains("SubPlan"),
        !plain.contains("agent_knowledge_spaces"),
        !filtered.contains("SubPlan"),
        !selective.contains("SubPlan"),
        !selective.contains("agent_knowledge_spaces")
      )).provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(5.minutes),
    test("召回与填充：宽范围、10% 权限选择性、25% metadata 过滤") {
      (for
        harness                            <- ZIO.service[Harness]
        (broadRecall, broadFull)           <- measure(harness, broad, RetrievalFilter.empty)
        (restrictedRecall, restrictedFull) <- measure(harness, onlyRestricted, RetrievalFilter.empty)
        (tierRecall, tierFull)             <- measure(
          harness,
          broad,
          RetrievalFilter(metadataEquals = Map("tier" -> "a"))
        )
        _ <- ZIO.logInfo(
          f"recall@$Limit broad=$broadRecall%.3f restricted=$restrictedRecall%.3f tier=$tierRecall%.3f"
        )
      yield assertTrue(
        broadFull,
        restrictedFull,
        tierFull,
        broadRecall >= 0.9,
        restrictedRecall >= 0.8,
        tierRecall >= 0.8
      )).provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(5.minutes),
    test("强制 HNSW：迭代扫描在 10% 权限与 25% metadata 过滤下填满 limit，单轮扫描作为对照") {
      val tier = RetrievalFilter(metadataEquals = Map("tier" -> "a"))
      (for
        harness <- ZIO.service[Harness]
        plan    <- harness.forcedIterative.explainVectorSearch(
          Embedding(Chunk.fromArray(queries.head)),
          onlyRestricted,
          RetrievalFilter.empty,
          Limit
        )
        (restrictedRecall, restrictedFull) <- measure(
          harness,
          onlyRestricted,
          RetrievalFilter.empty,
          _.forcedIterative
        )
        (tierRecall, tierFull)     <- measure(harness, broad, tier, _.forcedIterative)
        (singleRecall, singleFull) <- measure(
          harness,
          onlyRestricted,
          RetrievalFilter.empty,
          _.forcedSingleScan
        )
        _ <- ZIO.logInfo(
          f"forced HNSW recall@$Limit restricted=$restrictedRecall%.3f tier=$tierRecall%.3f " +
            f"single-scan restricted=$singleRecall%.3f full=$singleFull"
        )
      yield assertTrue(
        plan.contains(HnswIndexName),
        restrictedFull,
        tierFull,
        restrictedRecall >= 0.8,
        tierRecall >= 0.8
      )).provideLayer(harnessLayer)
    } @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(5.minutes)
  ) @@ TestAspect.sequential
