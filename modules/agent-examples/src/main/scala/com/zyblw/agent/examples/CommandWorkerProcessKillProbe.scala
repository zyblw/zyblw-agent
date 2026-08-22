package com.zyblw.agent.examples

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.persistence.postgres.*
import com.zyblw.agent.runtime.LeaseAwareAgentRuntime
import com.zyblw.agent.scheduler.*
import org.postgresql.ds.PGSimpleDataSource
import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import javax.sql.DataSource

/** Test-only process probe for the real PostgreSQL command lease protocol.
  *
  * The companion `integration-tests/command-worker-kill-recovery.sh` runs each mode in a separate forked JVM,
  * kills the old owner with SIGKILL, and then proves that a new owner can reclaim the expired lease. It
  * deliberately reuses the production migrations, stores and [[WorkerHost]] instead of maintaining a second
  * test scheduler.
  */
object CommandWorkerProcessKillProbe extends ZIOAppDefault:
  final private case class ProbeArgs(
      mode: String,
      stateFile: Path,
      oldReport: Path,
      recoveryReport: Path
  )

  final private case class DatabaseConfig(jdbcUrl: String, user: String, password: String)

  final private case class Seeded(runId: RunId, commandId: CommandId)

  private val workerConfig = WorkerHostConfig(
    leaseDuration = 3.seconds,
    heartbeatEvery = 1.second,
    pollEvery = 100.millis,
    retryDelay = Duration.Zero,
    maxAttempts = 3,
    parallelism = 1
  )

  private def parseArgs(values: Chunk[String]): Task[ProbeArgs] =
    ZIO.attempt {
      if values.length != 4 then
        throw IllegalArgumentException(
          "expected: <seed|old|recover|verify> <state-file> <old-report> <recovery-report>"
        )
      val mode = values(0)
      if !Set("seed", "old", "recover", "verify").contains(mode) then
        throw IllegalArgumentException(s"unsupported probe mode: $mode")
      ProbeArgs(mode, Path.of(values(1)), Path.of(values(2)), Path.of(values(3)))
    }

  private def loadDatabaseConfig: Task[DatabaseConfig] =
    ZIO.attempt {
      def required(name: String): String =
        sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse {
          throw IllegalArgumentException(s"missing required environment variable: $name")
        }

      DatabaseConfig(
        required("ZYBLW_AGENT_JDBC_URL"),
        required("ZYBLW_AGENT_DB_USER"),
        required("ZYBLW_AGENT_DB_PASSWORD")
      )
    }

  private def makeDataSource(config: DatabaseConfig): Task[DataSource] =
    ZIO.attempt {
      val dataSource = PGSimpleDataSource()
      dataSource.setURL(config.jdbcUrl)
      dataSource.setUser(config.user)
      dataSource.setPassword(config.password)
      dataSource.setConnectTimeout(10)
      dataSource: DataSource
    }

  private def writeValues(path: Path, values: (String, String)*): Task[Unit] =
    ZIO.attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      val body = values.map { case (key, value) => s"$key=$value" }.mkString("", "\n", "\n")
      Files.writeString(path, body, StandardCharsets.UTF_8)
      ()
    }

  private def readValues(path: Path): Task[Map[String, String]] =
    ZIO.attemptBlocking {
      val entries = Files.readAllLines(path, StandardCharsets.UTF_8)
      val parsed  = entries.toArray(new Array[String](entries.size())).toList.filter(_.nonEmpty).map { line =>
        val separator = line.indexOf('=')
        if separator <= 0 then throw IllegalStateException(s"malformed probe record in $path")
        line.substring(0, separator) -> line.substring(separator + 1)
      }
      val result = parsed.toMap
      if result.size != parsed.size then throw IllegalStateException(s"duplicate probe record key in $path")
      result
    }

  private def required(values: Map[String, String], key: String): Task[String] =
    ZIO.fromOption(values.get(key)).orElseFail(IllegalStateException(s"missing probe record key: $key"))

  private def readSeeded(path: Path): Task[Seeded] =
    for
      values        <- readValues(path)
      runIdText     <- required(values, "runId")
      commandIdText <- required(values, "commandId")
      runId         <- ZIO.fromEither(RunId.fromString(runIdText).left.map(IllegalStateException(_)))
      commandId     <- ZIO.fromEither(CommandId.fromString(commandIdText).left.map(IllegalStateException(_)))
    yield Seeded(runId, commandId)

  private def ensure(condition: Boolean, message: => String): Task[Unit] =
    ZIO.fail(IllegalStateException(message)).unless(condition).unit

  private def seed(dataSource: DataSource, args: ProbeArgs): Task[Unit] =
    for
      _         <- AgentPostgresMigrations.migrate(dataSource)
      runId     <- RunId.random
      sessionId <- SessionId.random
      eventId   <- EventId.random
      now       <- Clock.instant
      state = AgentState(
        runId,
        sessionId,
        AgentId("command-worker-process-kill-probe"),
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
        AgentEvent.RunCreated(runId, sessionId, now.toEpochMilli),
        now.toEpochMilli
      )
      runStore     = PostgresRunStore(dataSource)
      commandStore = PostgresRunCommandStore(dataSource)
      _       <- runStore.createWithEvents(state, NonEmptyChunk(event))
      command <- commandStore.submit(runId, RunCommandPayload.Recover, "process-kill-recover")
      _ <- writeValues(args.stateFile, "runId" -> runId.asString, "commandId" -> command.commandId.asString)
      _ <- Console.printLine(s"seeded run=${runId.asString} command=${command.commandId.asString}")
    yield ()

  private def assertExpectedLease(lease: RunCommandLease, seeded: Seeded): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.Unexpected("process probe claimed an unexpected run or command"))
      .unless(lease.runId == seeded.runId && lease.commandId == seeded.commandId)
      .unit

  private def reportLease(path: Path, lease: RunCommandLease): IO[AgentError, Unit] =
    writeValues(
      path,
      "pid"        -> ProcessHandle.current().pid().toString,
      "runId"      -> lease.runId.asString,
      "commandId"  -> lease.commandId.asString,
      "owner"      -> lease.owner.value,
      "generation" -> lease.generation.toString,
      "attempt"    -> lease.command.attempt.toString
    ).mapError(error =>
      AgentError.Unexpected(s"process probe could not write lease report: ${error.getClass.getSimpleName}")
    )

  private def runWorker(
      dataSource: DataSource,
      seeded: Seeded,
      owner: WorkerId,
      report: Path,
      holdForever: Boolean
  ): Task[Unit] =
    val store   = PostgresRunCommandStore(dataSource)
    val runtime = new LeaseAwareAgentRuntime:
      def executeLeased(lease: RunCommandLease): IO[AgentError, Unit] =
        assertExpectedLease(lease, seeded) *> reportLease(report, lease) *>
          ZIO.when(holdForever)(ZIO.never).unit

    for
      host <- WorkerHost
        .make(owner, workerConfig)
        .provide(ZLayer.succeed[RunCommandStore](store), ZLayer.succeed[LeaseAwareAgentRuntime](runtime))
      claimed <- host.claimOnce
      _       <- ensure(claimed, s"${owner.value} did not claim the seeded command")
    yield ()

  private def awaitExpiredLease(store: RunCommandStore): Task[Unit] =
    def loop: IO[StoreError, Unit] =
      store.queueSnapshot.foldZIO(
        error => if error.retryable then ZIO.sleep(100.millis) *> loop else ZIO.fail(error),
        snapshot =>
          if snapshot.expiredLeases == 1L then ZIO.unit
          else ZIO.sleep(100.millis) *> loop
      )

    loop.timeoutFail(IllegalStateException("lease did not expire within 30 seconds"))(30.seconds)

  private def recover(dataSource: DataSource, args: ProbeArgs): Task[Unit] =
    for
      seeded <- readSeeded(args.stateFile)
      store = PostgresRunCommandStore(dataSource)
      _ <- awaitExpiredLease(store)
      _ <- runWorker(
        dataSource,
        seeded,
        WorkerId("process-kill-recovery"),
        args.recoveryReport,
        holdForever = false
      )
      _ <- Console.printLine("recovery worker completed the reclaimed command")
    yield ()

  private def verifyReport(
      report: Map[String, String],
      seeded: Seeded,
      owner: String,
      generation: String,
      attempt: String
  ): Task[Unit] =
    for
      runIdText      <- required(report, "runId")
      commandIdText  <- required(report, "commandId")
      ownerText      <- required(report, "owner")
      generationText <- required(report, "generation")
      attemptText    <- required(report, "attempt")
      pidText        <- required(report, "pid")
      _              <- ensure(runIdText == seeded.runId.asString, s"unexpected report runId: $runIdText")
      _ <- ensure(commandIdText == seeded.commandId.asString, s"unexpected report commandId: $commandIdText")
      _ <- ensure(ownerText == owner, s"unexpected report owner: $ownerText")
      _ <- ensure(generationText == generation, s"unexpected report generation: $generationText")
      _ <- ensure(attemptText == attempt, s"unexpected report attempt: $attemptText")
      _ <- ensure(pidText.matches("[1-9][0-9]*"), s"invalid report pid: $pidText")
    yield ()

  private def verify(dataSource: DataSource, args: ProbeArgs): Task[Unit] =
    val store = PostgresRunCommandStore(dataSource)
    for
      seeded         <- readSeeded(args.stateFile)
      oldReport      <- readValues(args.oldReport)
      recoveryReport <- readValues(args.recoveryReport)
      _              <- verifyReport(oldReport, seeded, "process-kill-old", "1", "1")
      _              <- verifyReport(recoveryReport, seeded, "process-kill-recovery", "2", "2")
      oldPid         <- required(oldReport, "pid")
      recoveryPid    <- required(recoveryReport, "pid")
      _        <- ensure(oldPid != recoveryPid, "old and recovery workers must be different JVM processes")
      command  <- store.get(seeded.commandId)
      snapshot <- store.queueSnapshot
      _ <- ensure(command.status == RunCommandStatus.Completed, s"unexpected status: ${command.status}")
      _ <- ensure(command.attempt == 2, s"unexpected command attempt: ${command.attempt}")
      _ <- ensure(
        snapshot.queuedCommands == 0L && snapshot.dispatchableRuns == 0L && snapshot.leasedRuns == 0L &&
          snapshot.expiredLeases == 0L && snapshot.deadLetterCommands == 0L,
        s"queue did not converge after recovery: $snapshot"
      )
      _ <- Console.printLine(
        s"verified SIGKILL recovery run=${seeded.runId.asString} command=${seeded.commandId.asString} generation=1->2 attempt=1->2"
      )
    yield ()

  def run =
    for
      values     <- getArgs
      args       <- parseArgs(values)
      config     <- loadDatabaseConfig
      dataSource <- makeDataSource(config)
      _          <- args.mode match
        case "seed" => seed(dataSource, args)
        case "old"  =>
          readSeeded(args.stateFile).flatMap(seeded =>
            runWorker(dataSource, seeded, WorkerId("process-kill-old"), args.oldReport, holdForever = true)
          )
        case "recover" => recover(dataSource, args)
        case "verify"  => verify(dataSource, args)
    yield ()
