package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 真实 PostgreSQL command dispatcher 契约测试。
  *
  * 覆盖 `SKIP LOCKED` 并发、租约 generation 抢占、AgentState 提交 fencing、Cancel 原子抢占以及 pg_dump/restore。 CI 通过
  * `RUN_POSTGRES_INTEGRATION=1` 显式启用，不能用 H2 或 mock SQL 代替这些并发语义。
  */
object PostgresRunCommandStoreIntegrationSpec extends ZIOSpecDefault:
  final private case class Stores(
      container: PostgreSQLContainer,
      runStore: RunStore,
      commandStore: RunCommandStore,
      submissionStore: RunSubmissionStore
  )

  /** 启动 PostgreSQL 16 并执行正式 Flyway 迁移。 */
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
      container,
      PostgresRunStore(dataSource),
      PostgresRunCommandStore(dataSource),
      PostgresRunSubmissionStore(dataSource)
    )
  }

  /** 创建满足外键和事件不变量的最小 Run。 */
  private def createRun(store: RunStore): UIO[RunId] =
    (for
      runId   <- RunId.random
      session <- SessionId.random
      eventId <- EventId.random
      now     <- Clock.instant
      state = AgentState(
        runId,
        session,
        AgentId("command-pg-test"),
        RunStatus.Created,
        Chunk.empty,
        Chunk.empty,
        UsageSummary(),
        BudgetState(RunLimits(), UsageSummary(), 0),
        None,
        now,
        now,
        Version.initial,
        lastEventSequence = 0L
      )
      event = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, session, now.toEpochMilli),
        now.toEpochMilli
      )
      _ <- store.createWithEvents(state, NonEmptyChunk(event))
    yield runId).orDie

  /** 构造 PostgreSQL 原子 Start 提交，不依赖 Runtime 模块，专门测试持久化事务边界。 */
  private def startSubmission(key: String, requestHash: String): UIO[RunStartSubmission] =
    for
      runId   <- RunId.random
      session <- SessionId.random
      eventId <- EventId.random
      now     <- Clock.instant
      agent   = AgentDefinition(AgentId("pg-start-test"), "PG Start", "test")
      context = RunContext(Some("user-a"), Some("tenant-a"))
      state   = AgentState(
        runId,
        session,
        agent.id,
        RunStatus.Created,
        Chunk(AgentMessage.user("hello")),
        Chunk.empty,
        UsageSummary(),
        BudgetState(RunLimits(), UsageSummary(), 0),
        None,
        now,
        now,
        Version.initial,
        threadId = Some(ThreadId("pg-start-thread")),
        definition = Some(agent),
        runContext = context,
        lastEventSequence = 0L
      )
      event = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, session, now.toEpochMilli),
        now.toEpochMilli
      )
    yield RunStartSubmission(state, event, "a" * 64, key, requestHash)

  def spec = suite("PostgreSQL command queue and resilience")(
    test("初始状态、RunCreated、Start 命令和 dispatcher 原子提交，并发 HTTP 重试复用同一回执") {
      (for
        stores  <- ZIO.service[Stores]
        records <- ZIO.foreachPar(1 to 16)(_ =>
          startSubmission("pg-client-key", "b" * 64).flatMap(stores.submissionStore.submitStart)
        )
        state       <- stores.runStore.load(records.head.runId)
        events      <- stores.runStore.events(records.head.runId)
        commands    <- stores.commandStore.list(records.head.runId)
        conflicting <- startSubmission("pg-client-key", "c" * 64)
        conflict    <- stores.submissionStore.submitStart(conflicting).exit
        orphan      <- stores.runStore.load(conflicting.state.runId).exit
      yield assertTrue(
        records.map(_.runId).distinct.length == 1,
        records.map(_.commandId).distinct.length == 1,
        records.forall(_.payload == RunCommandPayload.Start),
        state.status == RunStatus.Created,
        events.map(_.sequence) == Chunk(0L),
        commands.length == 1,
        conflict.isFailure,
        orphan.isFailure
      )).provideLayer(storesLayer)
    },
    test("并发 claim 唯一，租约过期后同一命令可由新 generation 抢占") {
      (for
        stores  <- ZIO.service[Stores]
        runIds  <- ZIO.foreach(1 to 24)(_ => createRun(stores.runStore))
        records <- ZIO.foreach(runIds)(runId =>
          stores.commandStore.submit(runId, RunCommandPayload.Recover, "recover:0")
        )
        claimed <- ZIO.foreachPar(1 to 24)(index =>
          stores.commandStore.claim(WorkerId(s"pg-worker-$index"), 30.seconds, 3)
        )
        leases = Chunk.fromIterable(claimed.flatten)
        expiringRun <- createRun(stores.runStore)
        expiring    <- stores.commandStore
          .submit(expiringRun, RunCommandPayload.Recover, "recover:expiring", priority = 100)
        first <- stores.commandStore
          .claim(WorkerId("old-worker"), 200.millis, 3)
          .someOrFail(AgentError.Unexpected("claim missing"))
        _      <- ZIO.sleep(300.millis)
        second <- stores.commandStore
          .claim(WorkerId("new-worker"), 5.seconds, 3)
          .someOrFail(AgentError.Unexpected("reclaim missing"))
        stale <- stores.commandStore.complete(first).exit
        _     <- stores.commandStore.complete(second)
      yield assertTrue(
        records.length == 24,
        leases.length == 24,
        leases.map(_.runId).distinct.length == 24,
        second.commandId == expiring.commandId,
        second.generation == first.generation + 1L,
        stale.isFailure
      )).provideLayer(storesLayer)
    },
    test("AgentState 提交校验 commandId/token/generation，旧 worker 不能写状态或事件") {
      (for
        stores <- ZIO.service[Stores]
        runId  <- createRun(stores.runStore)
        _      <- stores.commandStore.submit(runId, RunCommandPayload.Recover, "recover:state")
        first  <- stores.commandStore
          .claim(WorkerId("state-old-worker"), 250.millis, 3)
          .someOrFail(AgentError.Unexpected("first claim missing"))
        initial   <- stores.runStore.load(runId)
        now       <- Clock.instant
        startedId <- EventId.random
        startedEvent = PersistedAgentEvent(
          startedId,
          runId,
          1L,
          AgentEvent.RunStarted(runId, now.toEpochMilli),
          now.toEpochMilli
        )
        running = initial.copy(status = RunStatus.Running, lastEventSequence = 1L)
        version1 <- stores.runStore.commitFenced(first, Version.initial, running, NonEmptyChunk(startedEvent))
        _        <- ZIO.sleep(350.millis)
        second   <- stores.commandStore
          .claim(WorkerId("state-new-worker"), 5.seconds, 3)
          .someOrFail(AgentError.Unexpected("second claim missing"))
        loaded1 <- stores.runStore.load(runId)
        staleId <- EventId.random
        staleEvent = PersistedAgentEvent(
          staleId,
          runId,
          2L,
          AgentEvent.RunFailed(runId, "stale", "旧 worker 不应写入", now.toEpochMilli),
          now.toEpochMilli
        )
        stale <- stores.runStore
          .commitFenced(
            first,
            version1,
            loaded1.copy(status = RunStatus.Failed, lastEventSequence = 2L),
            NonEmptyChunk(staleEvent)
          )
          .exit
        completedId <- EventId.random
        answer         = AgentMessage.assistant("new-worker-completed")
        completedEvent = PersistedAgentEvent(
          completedId,
          runId,
          2L,
          AgentEvent.RunCompleted(runId, answer, loaded1.usage, now.toEpochMilli),
          now.toEpochMilli
        )
        version2 <- stores.runStore.commitFenced(
          second,
          version1,
          loaded1.copy(
            status = RunStatus.Completed,
            messages = loaded1.messages :+ answer,
            lastEventSequence = 2L
          ),
          NonEmptyChunk(completedEvent)
        )
        _          <- stores.commandStore.complete(second)
        finalState <- stores.runStore.load(runId)
        events     <- stores.runStore.events(runId)
        staleLeaseLost = stale match
          case Exit.Failure(cause) => cause.failureOption.exists(_.isInstanceOf[AgentError.LeaseLost])
          case Exit.Success(_)     => false
      yield assertTrue(
        version1 == Version(1L),
        second.generation == first.generation + 1L,
        staleLeaseLost,
        version2 == Version(2L),
        finalState.status == RunStatus.Completed,
        events.map(_.sequence) == Chunk(0L, 1L, 2L),
        events.drop(1).map(_.eventId) == Chunk(startedId, completedId),
        !events.exists(_.eventId == staleId)
      )).provideLayer(storesLayer)
    },
    test("Cancel 提交原子撤销活动租约，完成后 supersede 被抢占命令") {
      (for
        stores  <- ZIO.service[Stores]
        runId   <- createRun(stores.runStore)
        recover <- stores.commandStore.submit(runId, RunCommandPayload.Recover, "recover:cancel")
        old     <- stores.commandStore
          .claim(WorkerId("active-worker"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("active claim missing"))
        cancel <- stores.commandStore
          .submit(runId, RunCommandPayload.Cancel(Some("operator")), "cancel", priority = Int.MaxValue)
        stale   <- stores.commandStore.heartbeat(old, 30.seconds).exit
        current <- stores.commandStore
          .claim(WorkerId("cancel-worker"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("cancel claim missing"))
        _       <- stores.commandStore.complete(current)
        records <- stores.commandStore.list(runId)
      yield assertTrue(
        stale.isFailure,
        current.commandId == cancel.commandId,
        records.find(_.commandId == cancel.commandId).exists(_.status == RunCommandStatus.Completed),
        records.find(_.commandId == recover.commandId).exists(_.status == RunCommandStatus.Superseded)
      )).provideLayer(storesLayer)
    },
    test("pg_dump/pg_restore 后 Run、命令正文和 dispatcher generation 均可恢复") {
      (for
        stores  <- ZIO.service[Stores]
        runId   <- createRun(stores.runStore)
        command <- stores.commandStore
          .submit(runId, RunCommandPayload.Retry("backup-test"), "retry:backup", priority = 7)
        lease <- stores.commandStore
          .claim(WorkerId("backup-worker"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("claim missing"))
        dump <- ZIO.attemptBlocking(
          stores.container.container.execInContainer(
            "pg_dump",
            "-U",
            stores.container.username,
            "-d",
            stores.container.databaseName,
            "-Fc",
            "-f",
            "/tmp/zyblw-agent.dump"
          )
        )
        _ <- ZIO
          .fail(AgentError.Unexpected(s"pg_dump failed: ${dump.getStderr}"))
          .unless(dump.getExitCode == 0)
          .unit
        _       <- stores.runStore.delete(runId)
        missing <- stores.runStore.load(runId).exit
        restore <- ZIO.attemptBlocking(
          stores.container.container.execInContainer(
            "pg_restore",
            "-U",
            stores.container.username,
            "-d",
            stores.container.databaseName,
            "--clean",
            "--if-exists",
            "/tmp/zyblw-agent.dump"
          )
        )
        _ <- ZIO
          .fail(AgentError.Unexpected(s"pg_restore failed: ${restore.getStderr}"))
          .unless(restore.getExitCode == 0)
          .unit
        restoredRun     <- stores.runStore.load(runId)
        restoredCommand <- stores.commandStore.get(command.commandId)
        noSecondClaim   <- stores.commandStore.claim(WorkerId("other-worker"), 30.seconds, 3)
      yield assertTrue(
        missing.isFailure,
        restoredRun.runId == runId,
        restoredCommand.status == RunCommandStatus.Leased,
        restoredCommand.payload == RunCommandPayload.Retry("backup-test"),
        lease.generation == 1L,
        noSecondClaim.isEmpty
      )).provideLayer(storesLayer)
    }
  ) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.withLiveClock @@
    TestAspect.timeout(3.minutes) @@ TestAspect.sequential
