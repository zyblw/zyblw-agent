package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 真实 PostgreSQL 管理面 Adapter 契约测试。
  *
  * 这些语义无法用内存实现替代：`V002` 的生成列由数据库在写入时计算，keyset 翻页依赖行值比较与复合索引上的 UUID 排序，而配置覆盖的 CAS 在并发下最终由主键唯一约束拦截。CI 通过
  * `RUN_POSTGRES_INTEGRATION=1` 显式启用。
  */
object PostgresAdminStoresIntegrationSpec extends ZIOSpecDefault:
  final private case class Stores(
      runStore: RunStore,
      directory: RunDirectory,
      overrides: RuntimeOverrideStore,
      jobs: IngestionJobStore
  )

  private val storesLayer: ZLayer[Any, Throwable, Stores] = ZLayer.scoped {
    for
      container <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val value =
            PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"))
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
      _ <- ZIO.attemptBlocking(
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations(AgentPostgresMigrations.DefaultLocation)
          .load()
          .migrate()
      )
    yield Stores(
      PostgresRunStore(dataSource),
      PostgresRunDirectory(dataSource),
      PostgresRuntimeOverrideStore(dataSource),
      PostgresIngestionJobStore(dataSource)
    )
  }

  private def approval(runId: RunId): ApprovalRequest = ApprovalRequest(
    id = "approval-1",
    runId = runId,
    toolCall = ToolCall("call-1", "delete_account", zio.json.ast.Json.Obj()),
    risk = ToolRisk.ApprovalWrite,
    reason = "需要人工确认",
    requestedAtEpochMilli = 0L
  )

  /** 写入一个满足事件不变量的 Run，供目录查询读取。 */
  private def createRun(
      store: RunStore,
      updatedAt: Instant,
      status: RunStatus = RunStatus.Running,
      tenantId: Option[String] = Some("acme"),
      agentId: String = "support",
      awaitingApproval: Boolean = false
  ): IO[StoreError, RunId] =
    for
      runId   <- RunId.random
      session <- SessionId.random
      eventId <- EventId.random
      state = AgentState(
        runId,
        session,
        AgentId(agentId),
        status,
        Chunk.empty,
        Chunk.empty,
        UsageSummary(),
        BudgetState(RunLimits(), UsageSummary(), 0),
        Option.when(awaitingApproval)(approval(runId)),
        Instant.EPOCH,
        updatedAt,
        Version.initial,
        runContext = RunContext(tenantId = tenantId, userId = Some("user-1")),
        lastEventSequence = 0L
      )
      event = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, session, updatedAt.toEpochMilli),
        updatedAt.toEpochMilli
      )
      _ <- store.createWithEvents(state, NonEmptyChunk(event))
    yield runId

  private def job(jobId: String, tenantId: String = "acme", createdAt: Long = 0L): IngestionJobView =
    IngestionJobView(
      jobId = jobId,
      tenantId = tenantId,
      sourceUri = s"admin-upload://$jobId",
      fileName = "guide.md",
      mediaType = "text/markdown",
      status = IngestionJobStatus.Queued,
      progressPercent = 0,
      documentId = None,
      indexVersion = None,
      chunkCount = None,
      failureCode = None,
      submittedBy = "acme/operator-1",
      createdAtEpochMilli = createdAt,
      updatedAtEpochMilli = createdAt
    )

  def spec = suite("PostgreSQL 管理面 Adapter")(
    test("V002 生成列从 state_json 提取租户、用户与审批等待，无需改动任何写路径") {
      (for
        stores <- ZIO.service[Stores]
        _      <- createRun(stores.runStore, Instant.ofEpochMilli(4000L), tenantId = Some("acme"))
        _      <- createRun(stores.runStore, Instant.ofEpochMilli(3000L), tenantId = None)
        _      <- createRun(stores.runStore, Instant.ofEpochMilli(2000L), awaitingApproval = true)
        scoped <- stores.directory.list(RunDirectoryQuery(tenantId = Some("acme")))
        // 租户为 None 的 Run 不能被租户过滤命中，也不能因为生成列为 NULL 而被误判为待审批。
        approving <- stores.directory.list(RunDirectoryQuery(awaitingApprovalOnly = true))
        overview  <- stores.directory.overview(None)
      yield assertTrue(
        scoped.items.length == 2,
        scoped.items.forall(_.tenantId.contains("acme")),
        approving.items.length == 1,
        approving.items.head.pendingApprovalToolName.contains("delete_account"),
        overview.totalRuns == 3L,
        overview.awaitingApproval == 1L
      )).provideLayer(storesLayer)
    },
    test("keyset 翻页在真实 SQL 上与内存实现返回同一顺序，且不重复不跳过") {
      (for
        stores <- ZIO.service[Stores]
        _      <- ZIO.foreachDiscard(1 to 5)(index =>
          createRun(stores.runStore, Instant.ofEpochMilli(index * 1000L))
        )
        first <- stores.directory.list(RunDirectoryQuery(limit = 2))
        c1    <- ZIO
          .fromEither(RunDirectoryCursor.decode(first.nextCursor.get))
          .mapError(AgentError.Unexpected(_))
        second <- stores.directory.list(RunDirectoryQuery(limit = 2, cursor = Some(c1)))
        c2     <- ZIO
          .fromEither(RunDirectoryCursor.decode(second.nextCursor.get))
          .mapError(AgentError.Unexpected(_))
        third <- stores.directory.list(RunDirectoryQuery(limit = 2, cursor = Some(c2)))
        seen = first.items ++ second.items ++ third.items
      yield assertTrue(
        seen.map(_.runId).distinct.length == 5,
        seen.map(_.updatedAtEpochMilli) == Chunk(5000L, 4000L, 3000L, 2000L, 1000L),
        !third.hasMore,
        third.nextCursor.isEmpty
      )).provideLayer(storesLayer)
    },
    test("同一毫秒内的亚毫秒时间戳仍然逐行翻页，不整段丢行") {
      // 排序列是微秒精度的 TIMESTAMPTZ。只要游标精度低于排序列，游标就会落在这一毫秒内所有行之前，
      // 行值比较会把整个毫秒区间排除，下一页整段消失。用 ofEpochMilli 构造的夹具无法触发这一点。
      val base = Instant.ofEpochSecond(1_700_000_000L)
      (for
        stores <- ZIO.service[Stores]
        _      <- ZIO.foreachDiscard(Chunk(100, 300, 500, 700))(micros =>
          createRun(stores.runStore, base.plusNanos(micros * 1_000L))
        )
        first <- stores.directory.list(RunDirectoryQuery(limit = 2))
        c1    <- ZIO
          .fromEither(RunDirectoryCursor.decode(first.nextCursor.get))
          .mapError(AgentError.Unexpected(_))
        second <- stores.directory.list(RunDirectoryQuery(limit = 2, cursor = Some(c1)))
        seen = first.items ++ second.items
      yield assertTrue(
        // 四条 Run 落在同一毫秒里，因此展示用毫秒字段全部相同。
        seen.map(_.updatedAtEpochMilli).toSet.size == 1,
        first.items.length == 2,
        second.items.length == 2,
        seen.map(_.runId).distinct.length == 4,
        !second.hasMore
      )).provideLayer(storesLayer)
    },
    test("同毫秒并列时游标按 UUID 排序严格前进，SQL 与内存实现的游标含义一致") {
      (for
        stores <- ZIO.service[Stores]
        moment = Instant.ofEpochMilli(7000L)
        _      <- ZIO.foreachDiscard(1 to 4)(_ => createRun(stores.runStore, moment))
        first  <- stores.directory.list(RunDirectoryQuery(limit = 2))
        cursor <- ZIO
          .fromEither(RunDirectoryCursor.decode(first.nextCursor.get))
          .mapError(AgentError.Unexpected(_))
        second <- stores.directory.list(RunDirectoryQuery(limit = 2, cursor = Some(cursor)))
        ordered = (first.items ++ second.items).map(_.runId)
      yield assertTrue(
        ordered.length == 4,
        ordered.distinct.length == 4,
        ordered == ordered.sorted.reverse
      )).provideLayer(storesLayer)
    },
    test("状态与 Agent 过滤下推到 SQL 并保持与内存实现相同的语义") {
      (for
        stores <- ZIO.service[Stores]
        _      <- createRun(stores.runStore, Instant.ofEpochMilli(4000L), status = RunStatus.Running)
        _      <- createRun(stores.runStore, Instant.ofEpochMilli(3000L), status = RunStatus.Failed)
        _      <- createRun(stores.runStore, Instant.ofEpochMilli(2000L), agentId = "billing")
        failed <- stores.directory.list(RunDirectoryQuery(statuses = Set(RunStatus.Failed)))
        either <- stores.directory.list(
          RunDirectoryQuery(statuses = Set(RunStatus.Failed, RunStatus.Running))
        )
        billing  <- stores.directory.list(RunDirectoryQuery(agentId = Some("billing")))
        overview <- stores.directory.overview(Some("acme"))
      yield assertTrue(
        failed.items.length == 1,
        either.items.length == 3,
        billing.items.length == 1,
        overview.countsByStatus == Map("Running" -> 2L, "Failed" -> 1L)
      )).provideLayer(storesLayer)
    },
    test("配置覆盖 append-only 写入使存储同时成为审计日志") {
      (for
        stores  <- ZIO.service[Stores]
        initial <- stores.overrides.current
        first <- stores.overrides.put(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(2)), "acme/alice", "收紧")
        second <- stores.overrides.put(
          first.version,
          RuntimeOverrides(toolMaxCallsPerStep = Some(1), retrievalTopK = Some(9)),
          "acme/bob",
          "再收紧"
        )
        current <- stores.overrides.current
        history <- stores.overrides.history(10)
      yield assertTrue(
        initial.version == 0L,
        first.version == 1L,
        second.version == 2L,
        current.overrides.retrievalTopK.contains(9),
        history.map(_.version) == Chunk(2L, 1L),
        history.map(_.updatedBy) == Chunk("acme/bob", "acme/alice"),
        // 历史行永不被改写：第一次写入的补丁在第二次写入之后仍然可读。
        history.last.overrides.retrievalTopK.isEmpty
      )).provideLayer(storesLayer)
    },
    test("陈旧 expectedVersion 返回乐观锁冲突并报告真实的当前版本") {
      (for
        stores <- ZIO.service[Stores]
        _      <- stores.overrides.put(0L, RuntimeOverrides(rerankEnabled = Some(true)), "acme/alice", "启用重排")
        conflict <- stores.overrides
          .put(0L, RuntimeOverrides(rerankEnabled = Some(false)), "acme/bob", "关闭")
          .flip
        current <- stores.overrides.current
      yield assertTrue(
        conflict match
          case AgentError.OptimisticLock(expected, actual) => expected.value == 0L && actual.value == 1L
          case _                                           => false,
        current.overrides.rerankEnabled.contains(true)
      )).provideLayer(storesLayer)
    },
    test("并发写入只有一个成功，失败方得到乐观锁冲突而不是数据库故障") {
      (for
        stores  <- ZIO.service[Stores]
        results <- ZIO.foreachPar(1 to 8)(index =>
          stores.overrides
            .put(0L, RuntimeOverrides(toolMaxCallsPerRun = Some(index)), s"acme/w$index", "并发")
            .exit
        )
        history <- stores.overrides.history(20)
        failures = results
          .filter(_.isFailure)
          .flatMap(_.foldExit(cause => Chunk.fromIterable(cause.failures), _ => Chunk.empty))
      yield assertTrue(
        results.count(_.isSuccess) == 1,
        history.length == 1,
        history.head.version == 1L,
        // 竞争失败必须映射为乐观锁而不是 DatabaseFailure：管理台只在 409 时提示重新加载。
        failures.forall {
          case _: AgentError.OptimisticLock => true
          case _                            => false
        }
      )).provideLayer(storesLayer)
    },
    test("摄入任务状态机推进保留上一阶段已确定的字段") {
      (for
        stores <- ZIO.service[Stores]
        id = UUID.randomUUID().toString
        _         <- stores.jobs.create(job(id))
        chunking  <- stores.jobs.transition(id, IngestionJobStatus.Chunking, documentId = Some("doc-1"))
        embedding <- stores.jobs.transition(id, IngestionJobStatus.Embedding, chunkCount = Some(42))
        completed <- stores.jobs.transition(id, IngestionJobStatus.Completed, indexVersion = Some(3L))
        fetched   <- stores.jobs.get(id)
      yield assertTrue(
        chunking.documentId.contains("doc-1"),
        chunking.progressPercent == IngestionJobStatus.Chunking.progressPercent,
        // Embedding 阶段没有再传 documentId，COALESCE 必须保留它而不是抹成 NULL。
        embedding.documentId.contains("doc-1"),
        embedding.chunkCount.contains(42),
        completed.documentId.contains("doc-1"),
        completed.chunkCount.contains(42),
        completed.indexVersion.contains(3L),
        completed.progressPercent == 100,
        fetched.exists(_.status == IngestionJobStatus.Completed)
      )).provideLayer(storesLayer)
    },
    test("失败任务必须携带稳定失败码，且不落任何 Provider 原始响应") {
      (for
        stores <- ZIO.service[Stores]
        id = UUID.randomUUID().toString
        _      <- stores.jobs.create(job(id))
        failed <- stores.jobs
          .transition(id, IngestionJobStatus.Failed, failureCode = Some("ingestion:load-failed"))
        // 数据库 CHECK 要求 Failed 与 failure_code 同时出现；缺少失败码的转换必须被拒绝而不是写入半状态。
        uncoded <- stores.jobs
          .create(job(UUID.randomUUID().toString))
          .flatMap(created => stores.jobs.transition(created.jobId, IngestionJobStatus.Failed).exit)
      yield assertTrue(
        failed.status == IngestionJobStatus.Failed,
        failed.failureCode.contains("ingestion:load-failed"),
        uncoded.isFailure
      )).provideLayer(storesLayer)
    },
    test("任务列表按创建时间倒序并支持租户过滤，非法任务 ID 视作不存在") {
      (for
        stores <- ZIO.service[Stores]
        older = job(UUID.randomUUID().toString, createdAt = 1000L)
        newer = job(UUID.randomUUID().toString, createdAt = 2000L)
        other = job(UUID.randomUUID().toString, tenantId = "other", createdAt = 3000L)
        _       <- ZIO.foreachDiscard(Chunk(older, newer, other))(stores.jobs.create)
        all     <- stores.jobs.list(None, 10)
        scoped  <- stores.jobs.list(Some("acme"), 10)
        missing <- stores.jobs.get("not-a-uuid")
      yield assertTrue(
        all.map(_.jobId) == Chunk(other.jobId, newer.jobId, older.jobId),
        scoped.map(_.jobId) == Chunk(newer.jobId, older.jobId),
        missing.isEmpty
      )).provideLayer(storesLayer)
    },
    test("推进不存在的任务返回持久化错误而不是静默成功") {
      (for
        stores <- ZIO.service[Stores]
        result <- stores.jobs.transition(UUID.randomUUID().toString, IngestionJobStatus.Loading).exit
      yield assertTrue(result.isFailure)).provideLayer(storesLayer)
    }
  ) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.withLiveClock @@
    TestAspect.timeout(5.minutes) @@ TestAspect.sequential
