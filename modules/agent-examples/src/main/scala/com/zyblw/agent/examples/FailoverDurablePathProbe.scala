package com.zyblw.agent.examples

import com.zyblw.agent.artifacts.{ArtifactInput, ArtifactStore}
import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.persistence.postgres.{AgentPostgresMigrations, PostgresAgentPersistence}
import com.zyblw.agent.runtime.*
import org.postgresql.ds.PGSimpleDataSource
import zio.*
import zio.json.*

import java.nio.charset.StandardCharsets
import javax.sql.DataSource

/** 主备切换后验证 Suspension 到期路径与 Run 域 artifact 读回。输出 `FAILOVER_PATH_REPORT` JSON。 */
object FailoverDurablePathProbe extends ZIOAppDefault:
  final case class PathReport(
      artifactWritten: Boolean,
      artifactReadBack: Boolean,
      suspensionExpired: Boolean,
      runTimedOut: Boolean
  ) derives JsonCodec:
    def passed: Boolean = artifactWritten && artifactReadBack && suspensionExpired && runTimedOut

  private case class DatabaseConfig(jdbcUrl: String, user: String, password: String)

  private val agent = AgentDefinition(AgentId("failover-path"), "Failover Path", "主备路径探针")

  def run: ZIO[Any, Any, Unit] =
    for
      database   <- loadDatabaseConfig
      dataSource <- makeDataSource(database)
      _          <- AgentPostgresMigrations.migrate(dataSource)
      report     <- execute(dataSource)
      _          <- Console.printLine("FAILOVER_PATH_REPORT " + report.toJson)
      _          <- ZIO.fail(AgentError.Unexpected("failover durable path probe failed")).when(!report.passed)
    yield ()

  private def execute(dataSource: DataSource): IO[AgentError, PathReport] =
    ZIO.scoped {
      val persistence = PostgresAgentPersistence.layer
      (for
        store       <- ZIO.service[RunStore]
        suspensions <- ZIO.service[SuspensionStore]
        artifacts   <- ZIO.service[ArtifactStore]
        runId       <- RunId.random
        sessionId   <- SessionId.random
        threadId = ThreadId("failover-path")
        payload  = Chunk.fromArray("failover-artifact-body".getBytes(StandardCharsets.UTF_8))
        descriptor <- artifacts.save(
          ArtifactScope.Run(runId, threadId),
          ArtifactName("tool-results/failover.json"),
          ArtifactInput(payload, "application/json")
        )
        loaded <- artifacts.read(descriptor.reference)
        now    <- Clock.instant
        createdAt = now.minusSeconds(2)
        deadline  = now.minusSeconds(1)
        createdId <- EventId.random
        created = PersistedAgentEvent(
          createdId,
          runId,
          0L,
          AgentEvent.RunCreated(runId, sessionId, createdAt.toEpochMilli),
          createdAt.toEpochMilli
        )
        initial = AgentState(
          runId,
          sessionId,
          agent.id,
          RunStatus.Running,
          Chunk.empty,
          Chunk.empty,
          UsageSummary(),
          BudgetState(RunLimits(), UsageSummary(), 0),
          None,
          createdAt,
          createdAt,
          Version.initial,
          agent,
          RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings),
          threadId
        ).copy(lastEventSequence = 0L)
        _            <- store.createWithEvents(initial, NonEmptyChunk(created))
        loadedRun    <- store.load(runId)
        (_, waiting) <- ZIO.fromEither(
          AgentKernel.suspend(
            loadedRun,
            ToolCall("call-failover", "write", zio.json.ast.Json.Obj()),
            ToolRisk.ApprovalWrite,
            "confirm",
            None,
            createdAt,
            Some(deadline)
          )
        )
        committer <- RunCommitter.make(store, _ => ZIO.unit)
        _         <- committer.commitTransition(loadedRun, waiting)
        worker = SuspensionExpiryWorker(
          WorkerId("failover-path-worker"),
          store,
          suspensions,
          committer,
          new SuspensionExpiryObserver:
            def cycle(result: SuspensionExpiryCycle): UIO[Unit]                = ZIO.unit
            def leaseLost(): UIO[Unit]                                         = ZIO.unit
            def abandoned(category: ErrorCategory): UIO[Unit]                  = ZIO.unit
            def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] = ZIO.unit
          ,
          SuspensionExpiryWorkerConfig()
        )
        cycle <- worker.runOnce
        after <- store.load(runId)
      yield PathReport(
        artifactWritten = true,
        artifactReadBack = loaded.exists(_.bytes == payload),
        suspensionExpired = cycle.expired >= 1 && cycle.completed,
        runTimedOut = after.status == RunStatus.TimedOut
      )).provideSome[Scope](ZLayer.succeed(dataSource) >>> persistence)
    }

  private def loadDatabaseConfig: Task[DatabaseConfig] = ZIO.attempt {
    def required(name: String): String =
      sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse {
        throw IllegalArgumentException(s"missing required environment variable: $name")
      }
    if !sys.env.get("ZYBLW_AGENT_SOAK_CONFIRM_DISPOSABLE").contains("true") then
      throw IllegalArgumentException(
        "ZYBLW_AGENT_SOAK_CONFIRM_DISPOSABLE=true is required; never run the path probe against a shared database"
      )
    DatabaseConfig(
      required("ZYBLW_AGENT_JDBC_URL"),
      required("ZYBLW_AGENT_DB_USER"),
      required("ZYBLW_AGENT_DB_PASSWORD")
    )
  }

  private def makeDataSource(config: DatabaseConfig): Task[DataSource] = ZIO.attempt {
    val dataSource = PGSimpleDataSource()
    dataSource.setURL(config.jdbcUrl)
    dataSource.setUser(config.user)
    dataSource.setPassword(config.password)
    dataSource.setConnectTimeout(10)
    dataSource: DataSource
  }
