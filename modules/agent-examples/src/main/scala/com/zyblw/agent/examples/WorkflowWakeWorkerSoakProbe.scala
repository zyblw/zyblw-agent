package com.zyblw.agent.examples

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.persistence.postgres.*
import com.zyblw.agent.workflow.*
import org.postgresql.ds.PGSimpleDataSource
import zio.*
import zio.json.*

import java.time.Instant
import javax.sql.DataSource

final case class WorkflowWakeWorkerSoakConfig(
    minimumRounds: Int,
    runsPerRound: Int,
    workerCount: Int,
    minimumDuration: Duration,
    roundPause: Duration,
    nodeLatency: Duration,
    sampleEvery: Duration,
    maxClaimP95Millis: Long,
    maxTerminalP95Millis: Long,
    timeout: Duration
):
  require(minimumRounds >= 1 && minimumRounds <= 100000, "minimumRounds 必须位于 1..100000")
  require(runsPerRound >= 1 && runsPerRound <= 1000, "runsPerRound 必须位于 1..1000")
  require(workerCount >= 1 && workerCount <= 32, "workerCount 必须位于 1..32")
  require(minimumDuration >= Duration.Zero, "minimumDuration 不能为负数")
  require(roundPause >= Duration.Zero, "roundPause 不能为负数")
  require(nodeLatency >= Duration.Zero, "nodeLatency 不能为负数")
  require(sampleEvery > Duration.Zero, "sampleEvery 必须大于零")
  require(maxClaimP95Millis > 0L && maxTerminalP95Millis > 0L, "延迟阈值必须大于零")
  require(timeout > minimumDuration, "timeout 必须大于 minimumDuration")

  def shouldContinue(completedRounds: Int, elapsed: Duration): Boolean =
    completedRounds < minimumRounds || elapsed < minimumDuration

object WorkflowWakeWorkerSoakConfig:
  val smoke: WorkflowWakeWorkerSoakConfig = WorkflowWakeWorkerSoakConfig(
    minimumRounds = 4,
    runsPerRound = 18,
    workerCount = 3,
    minimumDuration = 5.seconds,
    roundPause = 100.millis,
    nodeLatency = 25.millis,
    sampleEvery = 20.millis,
    maxClaimP95Millis = 5000L,
    maxTerminalP95Millis = 10000L,
    timeout = 120.seconds
  )

  def load: Task[WorkflowWakeWorkerSoakConfig] = ZIO.attempt {
    def int(name: String, default: Int): Int =
      sys.env.get(name).fold(default)(value => value.trim.toInt)

    def long(name: String, default: Long): Long =
      sys.env.get(name).fold(default)(value => value.trim.toLong)

    val baseline = smoke
    WorkflowWakeWorkerSoakConfig(
      minimumRounds = int("ZYBLW_AGENT_WORKFLOW_SOAK_MIN_ROUNDS", baseline.minimumRounds),
      runsPerRound = int("ZYBLW_AGENT_WORKFLOW_SOAK_RUNS_PER_ROUND", baseline.runsPerRound),
      workerCount = int("ZYBLW_AGENT_WORKFLOW_SOAK_WORKERS", baseline.workerCount),
      minimumDuration = long(
        "ZYBLW_AGENT_WORKFLOW_SOAK_MIN_DURATION_SECONDS",
        baseline.minimumDuration.toSeconds
      ).seconds,
      roundPause = long(
        "ZYBLW_AGENT_WORKFLOW_SOAK_ROUND_PAUSE_MILLIS",
        baseline.roundPause.toMillis
      ).millis,
      nodeLatency = long(
        "ZYBLW_AGENT_WORKFLOW_SOAK_NODE_LATENCY_MILLIS",
        baseline.nodeLatency.toMillis
      ).millis,
      sampleEvery = long(
        "ZYBLW_AGENT_WORKFLOW_SOAK_SAMPLE_MILLIS",
        baseline.sampleEvery.toMillis
      ).millis,
      maxClaimP95Millis = long(
        "ZYBLW_AGENT_WORKFLOW_SOAK_MAX_CLAIM_P95_MILLIS",
        baseline.maxClaimP95Millis
      ),
      maxTerminalP95Millis = long(
        "ZYBLW_AGENT_WORKFLOW_SOAK_MAX_TERMINAL_P95_MILLIS",
        baseline.maxTerminalP95Millis
      ),
      timeout = long("ZYBLW_AGENT_WORKFLOW_SOAK_TIMEOUT_SECONDS", baseline.timeout.toSeconds).seconds
    )
  }

final case class WorkflowSoakQueueHighWatermarks(
    pendingWaits: Long = 0L,
    dueWaits: Long = 0L,
    dispatchableWakeups: Long = 0L,
    leasedWakeups: Long = 0L,
    expiredWakeLeases: Long = 0L,
    oldestDispatchableAgeMillis: Long = 0L
) derives JsonCodec:
  def observe(snapshot: WorkflowWakeQueueSnapshot): WorkflowSoakQueueHighWatermarks =
    WorkflowSoakQueueHighWatermarks(
      pendingWaits.max(snapshot.pendingWaits),
      dueWaits.max(snapshot.dueWaits),
      dispatchableWakeups.max(snapshot.dispatchableWakeups),
      leasedWakeups.max(snapshot.leasedWakeups),
      expiredWakeLeases.max(snapshot.expiredWakeLeases),
      oldestDispatchableAgeMillis.max(snapshot.oldestDispatchableAgeMillis.getOrElse(0L))
    )

final case class WorkflowWakeWorkerSoakReport(
    schemaVersion: Int,
    completedAtEpochMillis: Long,
    minimumRounds: Int,
    completedRounds: Int,
    runsPerRound: Int,
    workerCount: Int,
    elapsedMillis: Long,
    submittedRuns: Long,
    completedRuns: Long,
    throughputPerMinute: Long,
    wakeClaims: Long,
    executionClaims: Long,
    completedCycles: Long,
    wakeOwnersObserved: Int,
    nodeOwnersObserved: Int,
    maximumConcurrentExecutions: Int,
    wakeGenerationReclaims: Long,
    executionGenerationReclaims: Long,
    abandonedCycles: Long,
    leaseLostCycles: Long,
    failedCycles: Long,
    maximumOutstandingRuns: Long,
    finalOutstandingRuns: Long,
    queueSamples: Long,
    queueHighWatermarks: WorkflowSoakQueueHighWatermarks,
    claimLatency: SoakLatencySummary,
    terminalLatency: SoakLatencySummary,
    checks: Chunk[SoakCheck],
    passed: Boolean
) derives JsonCodec

/** Bounded PostgreSQL soak for the durable signal → WorkflowWakeWorker → checkpoint path.
  *
  * Every Worker owns an independent PostgreSQL Store adapter and Workflow engine. The report contains
  * aggregate counters and latency histograms only: no Run/Session/signal identity, payload, state, owner
  * value or lease token.
  */
object WorkflowWakeWorkerSoakProbe extends ZIOAppDefault:
  final private case class DatabaseConfig(jdbcUrl: String, user: String, password: String)

  final private case class ProbeState(completed: Boolean) derives JsonCodec

  final private case class SubmittedWorkflow(runId: RunId, signalReceivedAt: Instant)

  final private case class ControlMetrics(
      wakeClaims: Long,
      executionClaims: Long,
      completedCycles: Long,
      activeExecutions: Int,
      maximumActiveExecutions: Int,
      wakeOwners: Set[String],
      nodeOwners: Set[String],
      wakeGenerationReclaims: Long,
      executionGenerationReclaims: Long,
      abandonedCycles: Long,
      leaseLostCycles: Long,
      failedCycles: Long,
      claimLatency: SoakLatencyHistogram
  )

  final private case class WorkloadMetrics(
      completedRounds: Int,
      submittedRuns: Long,
      completedRuns: Long,
      outstandingRuns: Long,
      maximumOutstandingRuns: Long,
      terminalLatency: SoakLatencyHistogram
  )

  final private case class QueueMetrics(samples: Long, highWatermarks: WorkflowSoakQueueHighWatermarks)

  private val emptyControlMetrics = ControlMetrics(
    0L,
    0L,
    0L,
    0,
    0,
    Set.empty,
    Set.empty,
    0L,
    0L,
    0L,
    0L,
    0L,
    SoakLatencyHistogram.empty
  )

  private val emptyWorkloadMetrics =
    WorkloadMetrics(0, 0L, 0L, 0L, 0L, SoakLatencyHistogram.empty)
  private val emptyQueueMetrics = QueueMetrics(0L, WorkflowSoakQueueHighWatermarks())

  private val workflowId      = WorkflowId("workflow-wake-worker-soak")
  private val workflowVersion = WorkflowVersion(1)
  private val entry           = NodeId("wait")
  private val signalName      = WorkflowSignalName("soak.ready")

  private val wakeConfig = WorkflowWakeWorkerConfig(
    leaseDuration = 8.seconds,
    heartbeatEvery = 2.seconds,
    pollEvery = 10.millis,
    retryDelay = 500.millis,
    expireBatchSize = 100
  )

  private def executionPolicy(owner: WorkerId): WorkflowExecutionPolicy = WorkflowExecutionPolicy(
    owner,
    leaseDuration = 5.seconds,
    heartbeatInterval = 1.second
  )

  final private class MeasuringStore(
      delegate: WorkflowExecutionStore[ProbeState],
      metrics: Ref[ControlMetrics]
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
        case WorkflowExecutionClaim.Acquired(lease, _) =>
          metrics.update(current =>
            current.copy(
              executionClaims = current.executionClaims + 1L,
              nodeOwners = current.nodeOwners + lease.owner.value,
              executionGenerationReclaims = current.executionGenerationReclaims +
                Option.when(lease.generation > 1L)(1L).getOrElse(0L)
            )
          )
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
        .flatMap { leases =>
          Clock.instant.flatMap { observedAt =>
            metrics
              .update { current =>
                leases.foldLeft(current) { (result, lease) =>
                  val latency = java.time.Duration
                    .between(lease.record.resolvedAt.get, observedAt)
                    .toMillis
                    .max(0L)
                  result.copy(
                    wakeClaims = result.wakeClaims + 1L,
                    wakeOwners = result.wakeOwners + lease.owner.value,
                    wakeGenerationReclaims = result.wakeGenerationReclaims +
                      Option.when(lease.generation > 1L)(1L).getOrElse(0L),
                    claimLatency = result.claimLatency.observe(latency)
                  )
                }
              }
              .as(leases)
          }
        }

  final private class MeasuringObserver(metrics: Ref[ControlMetrics]) extends WorkflowWakeObserver:
    def cycle(result: WorkflowWakeCycle): UIO[Unit] =
      metrics.update(current =>
        current.copy(
          completedCycles = current.completedCycles + Option.when(result.completed)(1L).getOrElse(0L)
        )
      )

    def leaseLost(): UIO[Unit] =
      metrics.update(current => current.copy(leaseLostCycles = current.leaseLostCycles + 1L))

    def abandoned(category: ErrorCategory): UIO[Unit] =
      metrics.update(current => current.copy(abandonedCycles = current.abandonedCycles + 1L))

    def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] =
      metrics.update(current => current.copy(failedCycles = current.failedCycles + 1L))

  private def definition(
      nodeOwner: WorkerId,
      metrics: Ref[ControlMetrics],
      latency: Duration
  ): WorkflowDefinition[Any, ProbeState] =
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
                state,
                WorkflowWaitRequest(WorkflowWaitCondition.Signal(signalName), now.plusSeconds(300))
              )
            )
          case Some(_) =>
            val acquire = metrics.update { current =>
              val active = current.activeExecutions + 1
              current.copy(
                activeExecutions = active,
                maximumActiveExecutions = current.maximumActiveExecutions.max(active),
                nodeOwners = current.nodeOwners + nodeOwner.value
              )
            }
            (acquire *> ZIO.sleep(latency).as(NodeOutcome.Succeeded(state.copy(completed = true))))
              .ensuring(
                metrics.update(current =>
                  current.copy(activeExecutions = (current.activeExecutions - 1).max(0))
                )
              )

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
      owner: WorkerId,
      metrics: Ref[ControlMetrics],
      latency: Duration
  ): WorkflowEngine[Any, ProbeState] =
    WorkflowEngine.makeDurable(
      definition(owner, metrics, latency),
      store,
      reducer,
      executionPolicy(owner)
    )

  private def loadDatabaseConfig: Task[DatabaseConfig] = ZIO.attempt {
    def required(name: String): String =
      sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse {
        throw IllegalArgumentException(s"missing required environment variable: $name")
      }

    if !sys.env.get("ZYBLW_AGENT_WORKFLOW_SOAK_CONFIRM_DISPOSABLE").contains("true") then
      throw IllegalArgumentException(
        "ZYBLW_AGENT_WORKFLOW_SOAK_CONFIRM_DISPOSABLE=true is required; never run the soak against a shared database"
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

  private def makeWorkers(
      config: WorkflowWakeWorkerSoakConfig,
      dataSource: DataSource,
      metrics: Ref[ControlMetrics]
  ): Chunk[WorkflowWakeWorker[Any, ProbeState]] =
    val observer = MeasuringObserver(metrics)
    Chunk.fromIterable((1 to config.workerCount).map { index =>
      val wakeOwner = WorkerId(s"workflow-soak-wake-$index")
      val nodeOwner = WorkerId(s"workflow-soak-node-$index")
      val store     = MeasuringStore(PostgresWorkflowCheckpointStore[ProbeState](dataSource), metrics)
      WorkflowWakeWorker(
        wakeOwner,
        store,
        engine(store, nodeOwner, metrics, config.nodeLatency),
        observer,
        wakeConfig
      )
    })

  private def seedAndSignal(
      probeId: String,
      round: Int,
      index: Int,
      seedEngine: WorkflowEngine[Any, ProbeState],
      store: WorkflowExecutionStore[ProbeState]
  ): IO[AgentError, SubmittedWorkflow] =
    for
      runId     <- RunId.random
      sessionId <- SessionId.random
      _         <- seedEngine.run(ProbeState(completed = false), WorkflowContext(runId, sessionId)).runDrain
      wait      <- store
        .currentWait(runId)
        .someOrFail(AgentError.PersistenceFailure("workflow soak did not register a durable wait"))
      receipt <- store.signal(
        wait.key,
        WorkflowSignalId(s"workflow-soak-$probeId-$round-$index"),
        signalName,
        "ready",
        com.zyblw.agent.composition.AuthorizationFingerprint.of(RunContext())
      )
      _ <- ZIO
        .fail(AgentError.Unexpected("workflow soak signal was not accepted"))
        .unless(receipt.disposition == WorkflowSignalDisposition.Accepted)
    yield SubmittedWorkflow(runId, receipt.receivedAt)

  private def awaitCompleted(
      submitted: Chunk[SubmittedWorkflow],
      store: WorkflowExecutionStore[ProbeState],
      parallelism: Int
  ): IO[AgentError, Chunk[WorkflowCheckpoint[ProbeState]]] =
    ZIO
      .foreachPar(submitted)(item => store.load(item.runId))
      .withParallelism(parallelism)
      .flatMap { loaded =>
        if loaded.exists(_.isEmpty) then
          ZIO.fail(AgentError.PersistenceFailure("workflow soak checkpoint disappeared"))
        else
          val checkpoints = loaded.flatten
          if checkpoints.forall(value =>
              value.cursor == WorkflowCursor.Completed && value.state.completed && value.step == 2
            )
          then ZIO.succeed(checkpoints)
          else ZIO.sleep(20.millis) *> awaitCompleted(submitted, store, parallelism)
      }

  private def sampler(
      store: WorkflowExecutionStore[ProbeState],
      metrics: Ref[QueueMetrics],
      every: Duration
  ): IO[AgentError, Nothing] =
    (store
      .wakeQueueSnapshot(workflowId, workflowVersion)
      .flatMap(snapshot =>
        metrics
          .update(current => QueueMetrics(current.samples + 1L, current.highWatermarks.observe(snapshot)))
      ) *> ZIO.sleep(every)).forever

  private def executeRound(
      probeId: String,
      round: Int,
      config: WorkflowWakeWorkerSoakConfig,
      seedEngine: WorkflowEngine[Any, ProbeState],
      store: WorkflowExecutionStore[ProbeState],
      metrics: Ref[WorkloadMetrics]
  ): IO[AgentError, Unit] =
    for
      submitted <- ZIO
        .foreachPar(1 to config.runsPerRound)(index =>
          seedAndSignal(probeId, round, index, seedEngine, store)
        )
        .withParallelism(config.workerCount)
        .map(Chunk.fromIterable)
      _ <- metrics.update { current =>
        val outstanding = current.outstandingRuns + submitted.length.toLong
        current.copy(
          submittedRuns = current.submittedRuns + submitted.length.toLong,
          outstandingRuns = outstanding,
          maximumOutstandingRuns = current.maximumOutstandingRuns.max(outstanding)
        )
      }
      completed <- awaitCompleted(submitted, store, config.workerCount)
      waits     <- ZIO
        .foreachPar(submitted)(item => store.currentWait(item.runId))
        .withParallelism(config.workerCount)
      _ <- ZIO
        .fail(AgentError.Unexpected("workflow soak terminal checkpoint retained an active wait"))
        .unless(waits.forall(_.isEmpty))
      observedAt <- Clock.instant
      _          <- metrics.update { current =>
        val histogram = submitted.foldLeft(current.terminalLatency) { (result, item) =>
          result.observe(
            java.time.Duration.between(item.signalReceivedAt, observedAt).toMillis.max(0L)
          )
        }
        current.copy(
          completedRounds = current.completedRounds + 1,
          completedRuns = current.completedRuns + completed.length.toLong,
          outstandingRuns = (current.outstandingRuns - completed.length.toLong).max(0L),
          terminalLatency = histogram
        )
      }
      _ <- ZIO.sleep(config.roundPause)
    yield ()

  private def workload(
      probeId: String,
      config: WorkflowWakeWorkerSoakConfig,
      seedEngine: WorkflowEngine[Any, ProbeState],
      store: WorkflowExecutionStore[ProbeState],
      metrics: Ref[WorkloadMetrics],
      startedNanos: Long
  ): IO[AgentError, Unit] =
    def loop(round: Int): IO[AgentError, Unit] =
      for
        now <- Clock.nanoTime
        elapsed = Duration.fromNanos((now - startedNanos).max(0L))
        _ <-
          if config.shouldContinue(round, elapsed) then
            executeRound(probeId, round, config, seedEngine, store, metrics) *> loop(round + 1)
          else ZIO.unit
      yield ()
    loop(0)

  private def buildReport(
      config: WorkflowWakeWorkerSoakConfig,
      startedNanos: Long,
      control: ControlMetrics,
      workload: WorkloadMetrics,
      queue: QueueMetrics,
      finalSnapshot: WorkflowWakeQueueSnapshot
  ): UIO[WorkflowWakeWorkerSoakReport] =
    for
      endedNanos  <- Clock.nanoTime
      completedAt <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
    yield
      val elapsedMillis   = ((endedNanos - startedNanos) / 1000000L).max(1L)
      val claim           = control.claimLatency.summary
      val terminal        = workload.terminalLatency.summary
      val minimumOwners   = config.workerCount.min(2).min(workload.submittedRuns.toInt)
      val minimumActive   = config.workerCount.min(2).min(workload.submittedRuns.toInt)
      val finalQueueDepth =
        finalSnapshot.pendingWaits + finalSnapshot.dispatchableWakeups + finalSnapshot.leasedWakeups
      val checks = Chunk(
        SoakCheck(
          "workflow.runs.completed",
          workload.completedRuns == workload.submittedRuns,
          workload.completedRuns,
          workload.submittedRuns
        ),
        SoakCheck(
          "workflow.wake.claims",
          control.wakeClaims == workload.submittedRuns,
          control.wakeClaims,
          workload.submittedRuns
        ),
        SoakCheck(
          "workflow.execution.claims",
          control.executionClaims == workload.submittedRuns,
          control.executionClaims,
          workload.submittedRuns
        ),
        SoakCheck(
          "workflow.cycles.completed",
          control.completedCycles == workload.submittedRuns,
          control.completedCycles,
          workload.submittedRuns
        ),
        SoakCheck(
          "workflow.wake_owners.observed.min",
          control.wakeOwners.size >= minimumOwners,
          control.wakeOwners.size,
          minimumOwners
        ),
        SoakCheck(
          "workflow.node_owners.observed.min",
          control.nodeOwners.size >= minimumOwners,
          control.nodeOwners.size,
          minimumOwners
        ),
        SoakCheck(
          "workflow.concurrency.observed.min",
          control.maximumActiveExecutions >= minimumActive,
          control.maximumActiveExecutions,
          minimumActive
        ),
        SoakCheck(
          "workflow.concurrency.capacity.max",
          control.maximumActiveExecutions <= config.workerCount,
          control.maximumActiveExecutions,
          config.workerCount
        ),
        SoakCheck(
          "workflow.wake_generation.reclaims.max",
          control.wakeGenerationReclaims == 0L,
          control.wakeGenerationReclaims,
          0L
        ),
        SoakCheck(
          "workflow.execution_generation.reclaims.max",
          control.executionGenerationReclaims == 0L,
          control.executionGenerationReclaims,
          0L
        ),
        SoakCheck(
          "workflow.cycles.abandoned.max",
          control.abandonedCycles == 0L,
          control.abandonedCycles,
          0L
        ),
        SoakCheck(
          "workflow.cycles.lease_lost.max",
          control.leaseLostCycles == 0L,
          control.leaseLostCycles,
          0L
        ),
        SoakCheck(
          "workflow.cycles.failed.max",
          control.failedCycles == 0L,
          control.failedCycles,
          0L
        ),
        SoakCheck(
          "workflow.outstanding.final.max",
          workload.outstandingRuns == 0L,
          workload.outstandingRuns,
          0L
        ),
        SoakCheck(
          "workflow.queue.due_waits.max",
          queue.highWatermarks.dueWaits == 0L,
          queue.highWatermarks.dueWaits,
          0L
        ),
        SoakCheck(
          "workflow.queue.expired_wake_leases.max",
          queue.highWatermarks.expiredWakeLeases == 0L,
          queue.highWatermarks.expiredWakeLeases,
          0L
        ),
        SoakCheck(
          "workflow.queue.final_depth.max",
          finalQueueDepth == 0L,
          finalQueueDepth,
          0L
        ),
        SoakCheck(
          "workflow.claim.p95_millis.max",
          claim.p95Millis <= config.maxClaimP95Millis,
          claim.p95Millis,
          config.maxClaimP95Millis
        ),
        SoakCheck(
          "workflow.terminal.p95_millis.max",
          terminal.p95Millis <= config.maxTerminalP95Millis,
          terminal.p95Millis,
          config.maxTerminalP95Millis
        )
      )
      WorkflowWakeWorkerSoakReport(
        schemaVersion = 1,
        completedAtEpochMillis = completedAt,
        minimumRounds = config.minimumRounds,
        completedRounds = workload.completedRounds,
        runsPerRound = config.runsPerRound,
        workerCount = config.workerCount,
        elapsedMillis = elapsedMillis,
        submittedRuns = workload.submittedRuns,
        completedRuns = workload.completedRuns,
        throughputPerMinute = (workload.completedRuns * 60000L) / elapsedMillis,
        wakeClaims = control.wakeClaims,
        executionClaims = control.executionClaims,
        completedCycles = control.completedCycles,
        wakeOwnersObserved = control.wakeOwners.size,
        nodeOwnersObserved = control.nodeOwners.size,
        maximumConcurrentExecutions = control.maximumActiveExecutions,
        wakeGenerationReclaims = control.wakeGenerationReclaims,
        executionGenerationReclaims = control.executionGenerationReclaims,
        abandonedCycles = control.abandonedCycles,
        leaseLostCycles = control.leaseLostCycles,
        failedCycles = control.failedCycles,
        maximumOutstandingRuns = workload.maximumOutstandingRuns,
        finalOutstandingRuns = workload.outstandingRuns,
        queueSamples = queue.samples,
        queueHighWatermarks = queue.highWatermarks.observe(finalSnapshot),
        claimLatency = claim,
        terminalLatency = terminal,
        checks = checks,
        passed = checks.forall(_.passed)
      )

  private def execute(
      config: WorkflowWakeWorkerSoakConfig,
      dataSource: DataSource
  ): IO[AgentError, WorkflowWakeWorkerSoakReport] =
    for
      controlMetrics  <- Ref.make(emptyControlMetrics)
      workloadMetrics <- Ref.make(emptyWorkloadMetrics)
      queueMetrics    <- Ref.make(emptyQueueMetrics)
      seedStore  = PostgresWorkflowCheckpointStore[ProbeState](dataSource)
      seedEngine = engine(
        seedStore,
        WorkerId("workflow-soak-seed"),
        controlMetrics,
        Duration.Zero
      )
      workers = makeWorkers(config, dataSource, controlMetrics)
      probeId <- Random.nextUUID.map(_.toString)
      started <- Clock.nanoTime
      runWorkload = workload(
        probeId,
        config,
        seedEngine,
        seedStore,
        workloadMetrics,
        started
      )
      runWorkers = ZIO.foreachParDiscard(workers)(_.run)
      runSampler = sampler(seedStore, queueMetrics, config.sampleEvery)
      _ <- runWorkload
        .raceFirst(runWorkers)
        .raceFirst(runSampler)
        .timeoutFail(AgentError.Unexpected("workflow wake worker soak timed out"))(config.timeout)
      finalSnapshot <- seedStore.wakeQueueSnapshot(workflowId, workflowVersion)
      control       <- controlMetrics.get
      completed     <- workloadMetrics.get
      queue         <- queueMetrics.get
      report        <- buildReport(config, started, control, completed, queue, finalSnapshot)
    yield report

  override def gracefulShutdownTimeout: Duration = 15.seconds

  def run: ZIO[Any, Any, Unit] =
    for
      config         <- WorkflowWakeWorkerSoakConfig.load
      databaseConfig <- loadDatabaseConfig
      dataSource     <- makeDataSource(databaseConfig)
      _              <- AgentPostgresMigrations.migrate(dataSource)
      report         <- execute(config, dataSource)
      _              <- Console.printLine(s"WORKFLOW_SOAK_REPORT ${report.toJson}")
      failed = report.checks.filterNot(_.passed).map(_.name)
      _ <- ZIO
        .fail(IllegalStateException(s"workflow wake worker soak failed checks: ${failed.mkString(",")}"))
        .unless(report.passed)
    yield ()
