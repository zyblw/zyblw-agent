package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.workflow.*
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.test.*

/** 真实 PostgreSQL 16 下的 Workflow checkpoint 契约。
  *
  * 覆盖 V008 migration、跨 Store 幂等/单调仲裁、identity 隔离、checksum fail-closed，以及暂停后由另一 Adapter 实例恢复。
  */
object PostgresWorkflowCheckpointStoreIntegrationSpec extends ZIOSpecDefault:
  final private case class WorkflowState(value: Int, notes: Chunk[String]) derives JsonCodec

  final private case class Harness(
      storeA: PostgresWorkflowCheckpointStore[WorkflowState],
      storeB: PostgresWorkflowCheckpointStore[WorkflowState],
      dataSource: DataSource
  )

  private val workflowId      = WorkflowId("postgres-workflow-spec")
  private val workflowVersion = WorkflowVersion(1)
  private val entry           = NodeId("entry")

  private val harnessLayer: ZLayer[Any, Throwable, Harness] = ZLayer.scoped {
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
      _ <- ZIO.attemptBlocking {
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations(AgentPostgresMigrations.DefaultLocation)
          .load()
          .migrate()
      }
    yield Harness(
      PostgresWorkflowCheckpointStore[WorkflowState](dataSource),
      PostgresWorkflowCheckpointStore[WorkflowState](dataSource),
      dataSource
    )
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PostgreSQL WorkflowCheckpointStore")(
    test("跨 Store 相同快照幂等，step 只允许单调推进且陈旧写不能覆盖") {
      (for
        harness <- ZIO.service[Harness]
        runId   <- RunId.random
        session <- SessionId.random
        first = checkpoint(
          session,
          WorkflowCursor.At(entry),
          WorkflowState(1, Chunk("first")),
          step = 1,
          visits = Map(entry -> 1)
        )
        advanced = checkpoint(
          session,
          WorkflowCursor.Completed,
          WorkflowState(2, Chunk("first", "completed")),
          step = 2,
          visits = Map(entry -> 2)
        )
        initialWrites <- ZIO.collectAllPar(
          Chunk(harness.storeA.save(runId, first).either, harness.storeB.save(runId, first).either)
        )
        _        <- harness.storeB.save(runId, advanced)
        conflict <- harness.storeA.save(runId, first).either
        reopened = checkpoint(
          session,
          WorkflowCursor.At(entry),
          WorkflowState(3, Chunk("must-not-reopen")),
          step = 3,
          visits = Map(entry -> 3)
        )
        terminalConflict <- harness.storeA.save(runId, reopened).either
        loaded           <- harness.storeA.load(runId)
        count            <- rowCount(harness.dataSource)
      yield assertTrue(
        initialWrites.forall(_.isRight),
        conflict.left.exists(_.category == ErrorCategory.Conflict),
        terminalConflict.left.exists(_.category == ErrorCategory.Conflict),
        loaded.contains(advanced),
        count == 1
      )).provideLayer(harnessLayer)
    },
    test("相同 runId 不能漂移到另一 Workflow、版本或 Session") {
      (for
        harness      <- ZIO.service[Harness]
        runId        <- RunId.random
        session      <- SessionId.random
        otherSession <- SessionId.random
        original = checkpoint(
          session,
          WorkflowCursor.At(entry),
          WorkflowState(1, Chunk.empty),
          step = 1,
          visits = Map(entry -> 1)
        )
        drifted = WorkflowCheckpoint(
          WorkflowId("other-workflow"),
          WorkflowVersion(2),
          otherSession,
          WorkflowCursor.Completed,
          WorkflowState(999, Chunk("must-not-win")),
          step = 2,
          visits = Map(entry -> 2)
        )
        _        <- harness.storeA.save(runId, original)
        conflict <- harness.storeB.save(runId, drifted).either
        loaded   <- harness.storeA.load(runId)
      yield assertTrue(
        conflict.left.exists(_.category == ErrorCategory.Conflict),
        loaded.contains(original)
      )).provideLayer(harnessLayer)
    },
    test("checksum 被篡改时读取 fail-closed 且错误不包含状态正文") {
      (for
        harness <- ZIO.service[Harness]
        runId   <- RunId.random
        session <- SessionId.random
        secret = "secret-checkpoint-body"
        value  = checkpoint(
          session,
          WorkflowCursor.At(entry),
          WorkflowState(1, Chunk(secret)),
          step = 1,
          visits = Map(entry -> 1)
        )
        _      <- harness.storeA.save(runId, value)
        _      <- tamperChecksum(harness.dataSource, runId)
        result <- harness.storeB.load(runId).either
      yield assertTrue(
        result.left.exists(_.category == ErrorCategory.Persistence),
        result.left.exists(!_.retryable),
        result.left.exists(_.message.contains("checksum-mismatch")),
        result.left.forall(!_.message.contains(secret))
      )).provideLayer(harnessLayer)
    },
    test("暂停 checkpoint 可由另一 Store 实例恢复并完成") {
      (for
        harness <- ZIO.service[Harness]
        runId   <- RunId.random
        session <- SessionId.random
        node = new WorkflowNode[Any, WorkflowState]:
          val id = entry
          def execute(
              state: WorkflowState,
              context: WorkflowContext
          ): IO[WorkflowError, NodeOutcome[WorkflowState]] =
            if state.value == 0 then
              ZIO.succeed(NodeOutcome.Suspended(state.copy(value = 1), "external-signal"))
            else ZIO.succeed(NodeOutcome.Succeeded(state.copy(value = state.value + 1)))
        definition = WorkflowDefinition
          .make(
            workflowId,
            workflowVersion,
            entry,
            Map(entry -> node),
            Map(entry -> WorkflowTransition.Complete())
          )
          .fold(
            issues => throw new IllegalArgumentException(issues.map(_.message).mkString("; ")),
            identity
          )
        reducer = new StateReducer[WorkflowState]:
          def merge(
              base: WorkflowState,
              branches: Chunk[WorkflowState]
          ): IO[WorkflowError, WorkflowState] = ZIO.succeed(base)
        first <- WorkflowEngine
          .make(definition, harness.storeA, reducer)
          .run(WorkflowState(0, Chunk.empty), WorkflowContext(runId, session))
          .runCollect
        resumed <- WorkflowEngine
          .make(definition, harness.storeB, reducer)
          .resume(WorkflowContext(runId, session))
          .runCollect
      yield assertTrue(
        first.lastOption.exists {
          case WorkflowEvent.Suspended(_, "external-signal", state) => state.value == 1
          case _                                                    => false
        },
        resumed.lastOption.contains(WorkflowEvent.Completed(WorkflowState(2, Chunk.empty)))
      )).provideLayer(harnessLayer)
    }
  ) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.timeout(
    3.minutes
  ) @@ TestAspect.sequential

  private def checkpoint(
      sessionId: SessionId,
      cursor: WorkflowCursor,
      state: WorkflowState,
      step: Int,
      visits: Map[NodeId, Int]
  ): WorkflowCheckpoint[WorkflowState] =
    WorkflowCheckpoint(
      workflowId,
      workflowVersion,
      sessionId,
      cursor,
      state,
      step,
      visits
    )

  private def rowCount(dataSource: DataSource): Task[Int] = ZIO.attemptBlocking {
    val connection = dataSource.getConnection
    try
      val statement = connection.prepareStatement("SELECT count(*) FROM agent_workflow_checkpoints")
      try
        val result = statement.executeQuery()
        result.next()
        result.getInt(1)
      finally statement.close()
    finally connection.close()
  }

  private def tamperChecksum(dataSource: DataSource, runId: RunId): Task[Unit] = ZIO.attemptBlocking {
    val connection = dataSource.getConnection
    try
      val statement = connection.prepareStatement(
        "UPDATE agent_workflow_checkpoints SET checkpoint_sha256 = ? WHERE run_id = ?::uuid"
      )
      try
        statement.setString(1, "0" * 64)
        statement.setString(2, runId.asString)
        statement.executeUpdate()
        ()
      finally statement.close()
    finally connection.close()
  }
