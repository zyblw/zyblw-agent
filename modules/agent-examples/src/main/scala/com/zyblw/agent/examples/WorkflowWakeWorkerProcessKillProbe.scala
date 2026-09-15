package com.zyblw.agent.examples

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.persistence.postgres.*
import com.zyblw.agent.workflow.*
import org.postgresql.ds.PGSimpleDataSource
import zio.*
import zio.json.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import javax.sql.DataSource

/** Test-only process probe for the durable Workflow wake path.
  *
  * The companion shell exercise kills the old forked JVM only after this probe has observed both its wake
  * lease and its node-execution lease. Recovery must then reclaim both generations, consume the wait and
  * commit the terminal checkpoint through the production [[WorkflowWakeWorker]], engine and PostgreSQL store.
  */
object WorkflowWakeWorkerProcessKillProbe extends ZIOAppDefault:
  final private case class ProbeArgs(
      mode: String,
      stateFile: Path,
      oldReport: Path,
      recoveryReport: Path
  )

  final private case class DatabaseConfig(jdbcUrl: String, user: String, password: String)

  final private case class Seeded(runId: RunId, sessionId: SessionId)

  final private case class ProbeState(value: Int) derives JsonCodec

  private val workflowId      = WorkflowId("workflow-wake-process-kill-probe")
  private val workflowVersion = WorkflowVersion(1)
  private val entry           = NodeId("wait")
  private val signalName      = WorkflowSignalName("probe.ready")

  private val wakeConfig = WorkflowWakeWorkerConfig(
    leaseDuration = 4.seconds,
    heartbeatEvery = 1.second,
    pollEvery = 100.millis,
    retryDelay = 1.second,
    expireBatchSize = 10
  )

  private def executionPolicy(owner: WorkerId) = WorkflowExecutionPolicy(
    owner,
    leaseDuration = 2.seconds,
    heartbeatInterval = 500.millis
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
      sessionIdText <- required(values, "sessionId")
      runId         <- ZIO.fromEither(RunId.fromString(runIdText).left.map(IllegalStateException(_)))
      sessionId     <- ZIO.fromEither(SessionId.fromString(sessionIdText).left.map(IllegalStateException(_)))
    yield Seeded(runId, sessionId)

  private def ensure(condition: Boolean, message: => String): Task[Unit] =
    ZIO.fail(IllegalStateException(message)).unless(condition).unit

  private def definition(holdAfterWake: Boolean): WorkflowDefinition[Any, ProbeState] =
    val node = new WorkflowNode[Any, ProbeState]:
      val id = entry

      def execute(
          state: ProbeState,
          context: WorkflowContext
      ): IO[WorkflowError, NodeOutcome[ProbeState]] =
        context.wakeup match
          case None =>
            Clock.instant.map(now =>
              NodeOutcome.Awaiting(
                state.copy(value = 1),
                WorkflowWaitRequest(
                  WorkflowWaitCondition.Signal(signalName),
                  now.plusSeconds(300)
                )
              )
            )
          case Some(_) if holdAfterWake => ZIO.never
          case Some(_)                  => ZIO.succeed(NodeOutcome.Succeeded(state.copy(value = 2)))

    WorkflowDefinition
      .make(
        workflowId,
        workflowVersion,
        entry,
        Map(entry -> node),
        Map(entry -> WorkflowTransition.Complete())
      )
      .fold(
        issues => throw IllegalArgumentException(issues.map(_.message).mkString("; ")),
        identity
      )

  private val reducer = new StateReducer[ProbeState]:
    def merge(base: ProbeState, branches: Chunk[ProbeState]): IO[WorkflowError, ProbeState] =
      ZIO.succeed(base)

  private def engine(
      store: WorkflowExecutionStore[ProbeState],
      nodeOwner: WorkerId,
      holdAfterWake: Boolean
  ): WorkflowEngine[Any, ProbeState] =
    WorkflowEngine.makeDurable(
      definition(holdAfterWake),
      store,
      reducer,
      executionPolicy(nodeOwner)
    )

  private def seed(dataSource: DataSource, args: ProbeArgs): Task[Unit] =
    for
      _         <- AgentPostgresMigrations.migrate(dataSource)
      runId     <- RunId.random
      sessionId <- SessionId.random
      store = PostgresWorkflowCheckpointStore[ProbeState](dataSource)
      _ <- engine(store, WorkerId("workflow-seed-node"), holdAfterWake = false)
        .run(ProbeState(0), WorkflowContext(runId, sessionId))
        .runDrain
      wait <- store
        .currentWait(runId)
        .someOrFail(AgentError.PersistenceFailure("workflow process probe did not register a wait"))
      receipt <- store.signal(
        wait.key,
        WorkflowSignalId("workflow-process-kill-signal"),
        signalName,
        "ready",
        com.zyblw.agent.composition.AuthorizationFingerprint.of(RunContext())
      )
      _ <- ensure(
        receipt.disposition == WorkflowSignalDisposition.Accepted,
        s"unexpected workflow signal disposition: ${receipt.disposition}"
      )
      _ <- writeValues(args.stateFile, "runId" -> runId.asString, "sessionId" -> sessionId.asString)
      _ <- Console.printLine(s"seeded workflow run=${runId.asString} waitStep=${wait.key.step}")
    yield ()

  /** Delegates the full production Store contract and reports only after wake and node leases are both
    * acquired.
    */
  final private class ReportingStore(
      delegate: WorkflowExecutionStore[ProbeState],
      report: Path,
      pendingWake: Ref[Option[WorkflowWakeupLease]]
  ) extends WorkflowExecutionStore[ProbeState]:
    export delegate.{
      abandonWakeup,
      commit,
      currentWait,
      expireDue,
      get,
      heartbeat,
      heartbeatWakeup,
      load,
      prepare,
      save,
      signal
    }

    override def wakeQueueSnapshot(
        workflowId: WorkflowId,
        definitionVersion: WorkflowVersion
    ): IO[StoreError, WorkflowWakeQueueSnapshot] =
      delegate.wakeQueueSnapshot(workflowId, definitionVersion)

    def claim(
        key: WorkflowExecutionKey,
        owner: WorkerId,
        leaseDuration: Duration
    ): IO[StoreError, WorkflowExecutionClaim[ProbeState]] =
      delegate.claim(key, owner, leaseDuration).tap {
        case WorkflowExecutionClaim.Acquired(executionLease, _) =>
          pendingWake.get.flatMap {
            case Some(wakeLease) => writeReport(wakeLease, executionLease)
            case None            =>
              ZIO.fail(
                AgentError.PersistenceFailure("workflow process probe acquired execution without wake lease")
              )
          }
        case _ => ZIO.unit
      }

    def claimWakeups(
        requestedWorkflowId: WorkflowId,
        definitionVersion: WorkflowVersion,
        owner: WorkerId,
        leaseDuration: Duration,
        limit: Int
    ): IO[StoreError, Chunk[WorkflowWakeupLease]] =
      delegate
        .claimWakeups(requestedWorkflowId, definitionVersion, owner, leaseDuration, limit)
        .tap(leases => pendingWake.set(leases.headOption))

    private def writeReport(
        wakeLease: WorkflowWakeupLease,
        executionLease: WorkflowExecutionLease
    ): IO[StoreError, Unit] =
      writeValues(
        report,
        "pid"                 -> ProcessHandle.current().pid().toString,
        "runId"               -> wakeLease.key.runId.asString,
        "nodeId"              -> wakeLease.key.nodeId.value,
        "wakeOwner"           -> wakeLease.owner.value,
        "wakeGeneration"      -> wakeLease.generation.toString,
        "executionOwner"      -> executionLease.owner.value,
        "executionGeneration" -> executionLease.generation.toString
      ).mapError(error =>
        AgentError.PersistenceFailure(
          s"workflow process probe could not write lease report: ${error.getClass.getSimpleName}"
        )
      )

  private object ReportingStore:
    def make(
        delegate: WorkflowExecutionStore[ProbeState],
        report: Path
    ): UIO[ReportingStore] =
      Ref.make(Option.empty[WorkflowWakeupLease]).map(ReportingStore(delegate, report, _))

  private def makeWorker(
      dataSource: DataSource,
      wakeOwner: WorkerId,
      nodeOwner: WorkerId,
      report: Path,
      holdAfterWake: Boolean
  ): UIO[WorkflowWakeWorker[Any, ProbeState]] =
    val delegate = PostgresWorkflowCheckpointStore[ProbeState](dataSource)
    ReportingStore.make(delegate, report).map { store =>
      val observer = new WorkflowWakeObserver:
        def cycle(result: WorkflowWakeCycle): UIO[Unit]                    = ZIO.unit
        def leaseLost(): UIO[Unit]                                         = ZIO.unit
        def abandoned(category: ErrorCategory): UIO[Unit]                  = ZIO.unit
        def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] = ZIO.unit
      WorkflowWakeWorker(
        wakeOwner,
        store,
        engine(store, nodeOwner, holdAfterWake),
        observer,
        wakeConfig
      )
    }

  private def runOld(dataSource: DataSource, args: ProbeArgs): Task[Unit] =
    for
      _      <- readSeeded(args.stateFile)
      worker <- makeWorker(
        dataSource,
        WorkerId("workflow-wake-old"),
        WorkerId("workflow-node-old"),
        args.oldReport,
        holdAfterWake = true
      )
      _ <- worker.runOnce
      _ <- ZIO.fail(IllegalStateException("old workflow Worker returned before SIGKILL"))
    yield ()

  private def recoverUntilCompleted(
      worker: WorkflowWakeWorker[Any, ProbeState]
  ): IO[AgentError, Unit] =
    worker.runOnce.flatMap { cycle =>
      if cycle.completed then ZIO.unit
      else ZIO.sleep(100.millis) *> recoverUntilCompleted(worker)
    }

  private def recover(dataSource: DataSource, args: ProbeArgs): Task[Unit] =
    for
      _      <- readSeeded(args.stateFile)
      worker <- makeWorker(
        dataSource,
        WorkerId("workflow-wake-recovery"),
        WorkerId("workflow-node-recovery"),
        args.recoveryReport,
        holdAfterWake = false
      )
      _ <- recoverUntilCompleted(worker)
        .timeoutFail(IllegalStateException("workflow wake lease was not recovered within 30 seconds"))(
          30.seconds
        )
      _ <- Console.printLine("recovery Workflow worker committed the reclaimed wait")
    yield ()

  private def verifyReport(
      report: Map[String, String],
      seeded: Seeded,
      wakeOwner: String,
      nodeOwner: String,
      generation: String
  ): Task[Unit] =
    for
      pidText            <- required(report, "pid")
      runIdText          <- required(report, "runId")
      nodeIdText         <- required(report, "nodeId")
      wakeOwnerText      <- required(report, "wakeOwner")
      wakeGenerationText <- required(report, "wakeGeneration")
      nodeOwnerText      <- required(report, "executionOwner")
      nodeGenerationText <- required(report, "executionGeneration")
      _                  <- ensure(pidText.matches("[1-9][0-9]*"), s"invalid workflow report pid: $pidText")
      _ <- ensure(runIdText == seeded.runId.asString, s"unexpected workflow report runId: $runIdText")
      _ <- ensure(nodeIdText == entry.value, s"unexpected workflow report nodeId: $nodeIdText")
      _ <- ensure(wakeOwnerText == wakeOwner, s"unexpected workflow wake owner: $wakeOwnerText")
      _ <- ensure(nodeOwnerText == nodeOwner, s"unexpected workflow node owner: $nodeOwnerText")
      _ <- ensure(wakeGenerationText == generation, s"unexpected wake generation: $wakeGenerationText")
      _ <- ensure(nodeGenerationText == generation, s"unexpected execution generation: $nodeGenerationText")
    yield ()

  private def verify(dataSource: DataSource, args: ProbeArgs): Task[Unit] =
    val store = PostgresWorkflowCheckpointStore[ProbeState](dataSource)
    for
      seeded         <- readSeeded(args.stateFile)
      oldReport      <- readValues(args.oldReport)
      recoveryReport <- readValues(args.recoveryReport)
      _              <- verifyReport(
        oldReport,
        seeded,
        "workflow-wake-old",
        "workflow-node-old",
        "1"
      )
      _ <- verifyReport(
        recoveryReport,
        seeded,
        "workflow-wake-recovery",
        "workflow-node-recovery",
        "2"
      )
      oldPid      <- required(oldReport, "pid")
      recoveryPid <- required(recoveryReport, "pid")
      _           <- ensure(oldPid != recoveryPid, "old and recovery Workflow workers must be different JVMs")
      wait        <- store.currentWait(seeded.runId)
      checkpoint  <- store.load(seeded.runId)
      execution   <- store.get(
        WorkflowExecutionKey(
          seeded.runId,
          workflowId,
          workflowVersion,
          seeded.sessionId,
          entry,
          step = 1,
          visit = 2
        )
      )
      _ <- ensure(wait.isEmpty, "workflow wait was not consumed")
      _ <- ensure(
        checkpoint.exists(value =>
          value.cursor == WorkflowCursor.Completed && value.state == ProbeState(2) && value.step == 2
        ),
        s"workflow checkpoint did not converge: $checkpoint"
      )
      _ <- ensure(
        execution.exists(record =>
          record.status == WorkflowExecutionStatus.Committed && record.generation == 2L &&
            record.owner == WorkerId("workflow-node-recovery")
        ),
        "workflow execution ledger did not commit at generation 2"
      )
      _ <- Console.printLine(
        s"verified Workflow SIGKILL recovery run=${seeded.runId.asString} wakeGeneration=1->2 executionGeneration=1->2"
      )
    yield ()

  def run =
    for
      values     <- getArgs
      args       <- parseArgs(values)
      config     <- loadDatabaseConfig
      dataSource <- makeDataSource(config)
      _          <- args.mode match
        case "seed"    => seed(dataSource, args)
        case "old"     => runOld(dataSource, args)
        case "recover" => recover(dataSource, args)
        case "verify"  => verify(dataSource, args)
    yield ()
