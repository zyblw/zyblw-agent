package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.composition.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.tools.*
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** v6 审批主体必须经真实 PostgreSQL JSONB 往返后仍可判定，不能只在内存 codec 上成立。 */
object PostgresApprovalSubjectIntegrationSpec extends ZIOSpecDefault:

  final private case class Fixture(store: RunStore, dataSource: DataSource)

  private val fixtureLayer: ZLayer[Any, Throwable, Fixture] =
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
      yield Fixture(persistence.get[RunStore], dataSource)
    }

  private def executeSql(dataSource: DataSource, sql: String, runId: RunId): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(sql)
        try
          statement.setString(1, runId.asString)
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }

  private def readSchemaVersion(dataSource: DataSource, runId: RunId): Task[Int] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement =
          connection.prepareStatement("SELECT schema_version FROM agent_runs WHERE run_id = ?::uuid")
        try
          statement.setString(1, runId.asString)
          val result = statement.executeQuery()
          if result.next() then result.getInt(1)
          else throw IllegalStateException(s"missing run ${runId.asString}")
        finally statement.close()
      finally connection.close()
    }

  private val writeTool: RegisteredTool = new RegisteredTool:
    val definition = ToolDefinition("write", "写入草稿", Json.Obj("type" -> Json.Str("object")), None)
    val metadata   = ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
    def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
      ZIO.succeed(ToolResult(arguments))

  private def subject(
      call: ToolCall,
      context: RunContext = RunContext(tenantId = Some("tenant-a"))
  ): ApprovalSubject =
    val policy = ToolPolicyConfig(allowedTools = Set(ToolName("write")))
    ApprovalSubject.of(
      call,
      writeTool.metadata,
      ToolContractFingerprint.registered(writeTool),
      ApprovalPolicyFingerprint.of(policy, ToolName(call.name)),
      AuthorizationFingerprint.of(context)
    )

  private def v6State(
      runId: RunId,
      now: Instant,
      call: ToolCall,
      frozen: ApprovalSubject
  ): AgentState =
    val plan = DurableToolPlan(
      "plan-pg-v6",
      Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, call)))),
      toolContractFingerprints = Map("write" -> frozen.toolContract),
      approvalSubjects = Some(Map(call.id -> frozen))
    )
    AgentState(
      runId,
      SessionId(UUID.randomUUID()),
      AgentId("postgres-approval-subject"),
      RunStatus.WaitingForApproval,
      Chunk(AgentMessage.user("写入")),
      Chunk.empty,
      UsageSummary(),
      BudgetState(RunLimits(), UsageSummary(), 0),
      Some(
        ApprovalRequest(
          s"approval-${runId.asString}-${call.id}-${frozen.value.take(16)}",
          runId,
          call,
          ToolRisk.ApprovalWrite,
          "需要人工授权",
          now.toEpochMilli,
          Some(frozen)
        )
      ),
      now,
      now,
      Version.initial,
      schemaVersion = AgentState.CurrentSchemaVersion,
      threadId = Some(ThreadId("postgres-approval-thread")),
      definition = Some(AgentDefinition(AgentId("postgres-approval-subject"), "Postgres", "test")),
      runContext = RunContext(tenantId = Some("tenant-a")),
      pendingToolPlan = Some(plan),
      lastEventSequence = 0L
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Postgres ApprovalSubject")(
      test("v6 审批主体经 JSONB 往返后结构相等，且参数正文不进入主体字段") {
        val secret = "hunter2-should-not-hash-as-plaintext"
        val call   =
          ToolCall("call-pg-v6", "write", Json.Obj("path" -> Json.Str("/tmp/a"), "token" -> Json.Str(secret)))
        val frozen = subject(call)
        (for
          fixture <- ZIO.service[Fixture]
          runId   <- RunId.random
          now     <- Clock.instant
          eventId <- EventId.random
          initial = v6State(runId, now, call, frozen).copy(lastEventSequence = 0L)
          created = PersistedAgentEvent(
            eventId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, initial.sessionId, now.toEpochMilli),
            now.toEpochMilli
          )
          _            <- fixture.store.createWithEvents(initial, NonEmptyChunk(created))
          loaded       <- fixture.store.load(runId)
          schemaColumn <- readSchemaVersion(fixture.dataSource, runId)
          pending = loaded.pendingApproval.flatMap(_.subject)
          planned = loaded.pendingToolPlan.flatMap(_.approvalSubjects).flatMap(_.get(call.id))
        yield assertTrue(
          loaded.schemaVersion == AgentState.CurrentSchemaVersion,
          schemaColumn == AgentState.CurrentSchemaVersion,
          pending.contains(frozen),
          planned.contains(frozen),
          pending.exists(_.value == frozen.value),
          planned.exists(_.driftFrom(frozen).isEmpty),
          pending.exists(value => !value.toJson.contains(secret)),
          planned.exists(value => !value.toJson.contains(secret)),
          loaded.pendingToolPlan.flatMap(_.frozenApprovalCallIds).contains(Set(call.id))
        )).provideLayer(fixtureLayer)
      },
      test("关系列 schema_version 与 JSON 信封不一致时 fail-closed") {
        val call   = ToolCall("call-pg-envelope", "write", Json.Obj())
        val frozen = subject(call)
        (for
          fixture <- ZIO.service[Fixture]
          runId   <- RunId.random
          now     <- Clock.instant
          eventId <- EventId.random
          initial = v6State(runId, now, call, frozen).copy(lastEventSequence = 0L)
          created = PersistedAgentEvent(
            eventId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, initial.sessionId, now.toEpochMilli),
            now.toEpochMilli
          )
          _ <- fixture.store.createWithEvents(initial, NonEmptyChunk(created))
          _ <- executeSql(
            fixture.dataSource,
            "UPDATE agent_runs SET schema_version = 5 WHERE run_id = ?::uuid",
            runId
          )
          result <- fixture.store.load(runId).either
          rejected = result match
            case Left(AgentError.PersistenceFailure(message, _)) => message.contains("schemaVersion")
            case _                                               => false
        yield assertTrue(rejected)).provideLayer(fixtureLayer)
      }
    ) @@ TestAspect.withLiveEnvironment @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@
      TestAspect.timeout(2.minutes)
