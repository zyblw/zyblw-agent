package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 使用真实 PostgreSQL 容器验证 JDBC 适配器，而不是用 H2 模拟 PostgreSQL 的 JSONB、UUID 和事务行为。
  *
  * 默认测试任务不会启动 Docker；显式设置 `RUN_POSTGRES_INTEGRATION=1` 后执行本规格。这样普通开发者无需 Docker 也可运行单元测试，而 CI 的 integration
  * job 必须开启该变量，不能把这里当作可永久跳过的测试。
  */
object PostgresRunStoreIntegrationSpec extends ZIOSpecDefault:

  /** 启动临时 PostgreSQL、执行正式 Flyway migration，并暴露与生产相同的 `RunStore`。 Scope 结束时始终停止容器，即使断言失败或 Fiber 被中断也不会泄漏
    * Docker 资源。
    */
  private val storeLayer: ZLayer[Any, Throwable, RunStore] =
    ZLayer.scoped {
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
        persistence <- PostgresAgentPersistence.migratedLayer.build.provideSome[Scope](
          ZLayer.succeed[DataSource](dataSource)
        )
      yield persistence.get[RunStore]
    }

  /** 创建最小但完整的 AgentState。
    * @param runId
    *   测试隔离使用的随机 Run 标识
    * @param now
    *   数据库时间列与 JSON 状态共用的稳定时间
    */
  private def state(runId: RunId, now: Instant): AgentState =
    AgentState(
      runId,
      SessionId(UUID.randomUUID()),
      AgentId("postgres-test"),
      RunStatus.Running,
      Chunk(AgentMessage.user("test")),
      Chunk.empty,
      UsageSummary(),
      BudgetState(RunLimits(), UsageSummary(), 0),
      None,
      now,
      now,
      Version.initial,
      threadId = Some(ThreadId("postgres-thread")),
      definition = Some(AgentDefinition(AgentId("postgres-test"), "Postgres", "test"))
    )

  /** PostgreSQL 集成契约：事务提交、乐观锁、审批状态恢复、工具账本和并发取消。 */
  def spec: Spec[TestEnvironment & Scope, Any] =
    test("真实 PostgreSQL 保证状态/事件原子提交并正确处理并发控制") {
      (for
        store <- ZIO.service[RunStore]
        runId <- RunId.random
        now   <- Clock.instant
        base = state(runId, now)
        createdEventId <- EventId.random
        createdEvent = PersistedAgentEvent(
          createdEventId,
          runId,
          0L,
          AgentEvent.RunCreated(runId, base.sessionId, now.toEpochMilli),
          now.toEpochMilli
        )
        initial = base.copy(lastEventSequence = 0L)
        _       <- store.createWithEvents(initial, NonEmptyChunk(createdEvent))
        eventId <- EventId.random
        event = PersistedAgentEvent(
          eventId,
          runId,
          1L,
          AgentEvent.RunSuspended(runId, "approval", now.toEpochMilli),
          now.toEpochMilli
        )
        waiting = initial.copy(
          status = RunStatus.WaitingForApproval,
          pendingApproval = Some(
            ApprovalRequest(
              "approval-pg",
              runId,
              ToolCall("call-pg", "write", zio.json.ast.Json.Obj()),
              ToolRisk.ApprovalWrite,
              "test",
              now.toEpochMilli
            )
          ),
          lastEventSequence = 1L
        )
        version  <- store.commit(Version.initial, waiting, NonEmptyChunk(event))
        loaded   <- store.load(runId)
        events   <- store.events(runId)
        conflict <- store.save(Version.initial, loaded.copy(status = RunStatus.Completed)).exit
        record = ToolExecutionRecord(
          runId,
          "postgres-plan:0",
          0,
          "call-pg",
          "write",
          Some("call-pg"),
          ToolExecutionStatus.Prepared,
          None,
          0,
          now.toEpochMilli
        )
        secondRecord = record.copy(ordinal = 1, callId = "call-pg-2", idempotencyKey = Some("call-pg-2"))
        _       <- store.prepareToolExecutions(NonEmptyChunk(record, secondRecord))
        running <- store.transitionToolExecution(
          ToolExecutionStatus.Prepared,
          0,
          record.copy(status = ToolExecutionStatus.Running, attempt = 1)
        )
        toolResult = ToolResult(zio.json.ast.Json.Obj("ok" -> zio.json.ast.Json.Bool(true)))
        _ <- store.transitionToolExecution(
          ToolExecutionStatus.Running,
          1,
          running.copy(status = ToolExecutionStatus.Succeeded, result = Some(toolResult))
        )
        ledger           <- store.getToolExecution(runId, "call-pg")
        batchLedger      <- store.getToolExecutions(runId, "postgres-plan:0")
        identityConflict <- store
          .prepareToolExecutions(
            NonEmptyChunk(record.copy(batchId = "other-plan:0", ordinal = 3))
          )
          .exit
        _               <- ZIO.foreachParDiscard(1 to 8)(_ => store.requestCancellation(runId))
        cancelled       <- store.cancellationRequested(runId)
        rolledBackRunId <- RunId.random
        rolledBackState = state(rolledBackRunId, now).copy(lastEventSequence = 0L)
        duplicateEvent  = createdEvent.copy(
          runId = rolledBackRunId,
          event = AgentEvent.RunCreated(rolledBackRunId, rolledBackState.sessionId, now.toEpochMilli)
        )
        createFailure       <- store.createWithEvents(rolledBackState, NonEmptyChunk(duplicateEvent)).exit
        absentAfterRollback <- store.load(rolledBackRunId).exit
      yield assertTrue(
        version == Version(1L),
        loaded.status == RunStatus.WaitingForApproval,
        loaded.pendingApproval.exists(_.id == "approval-pg"),
        events.map(_.eventId) == Chunk(createdEventId, eventId),
        conflict.isFailure,
        ledger.exists(value => value.status == ToolExecutionStatus.Succeeded && value.attempt == 1),
        batchLedger.map(_.callId) == Chunk("call-pg", "call-pg-2"),
        identityConflict.isFailure,
        cancelled,
        createFailure.isFailure,
        absentAfterRollback.isFailure
      )).provideLayer(storeLayer)
    } @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.timeout(2.minutes)
