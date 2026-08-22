package com.zyblw.agent.examples

import com.zyblw.agent.app.AgentDefinitionBuilder
import com.zyblw.agent.composition.RuntimeProfile
import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.extension.RuntimeExtensions
import com.zyblw.agent.guardrails.GuardrailEngine
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.persistence.postgres.*
import com.zyblw.agent.runtime.*
import com.zyblw.agent.scheduler.*
import com.zyblw.agent.tools.*
import org.postgresql.ds.PGSimpleDataSource
import zio.*
import zio.json.*

import javax.sql.DataSource

final case class DurableWorkerSoakConfig(
    minimumRounds: Int,
    runsPerRound: Int,
    workerCount: Int,
    parallelismPerWorker: Int,
    minimumDuration: Duration,
    roundPause: Duration,
    modelLatency: Duration,
    sampleEvery: Duration,
    maxClaimP95Millis: Long,
    maxTerminalP95Millis: Long,
    timeout: Duration
):
  require(minimumRounds >= 1 && minimumRounds <= 100000, "minimumRounds 必须位于 1..100000")
  require(runsPerRound >= 1 && runsPerRound <= 1000, "runsPerRound 必须位于 1..1000")
  require(workerCount >= 1 && workerCount <= 32, "workerCount 必须位于 1..32")
  require(parallelismPerWorker >= 1 && parallelismPerWorker <= 64, "parallelismPerWorker 必须位于 1..64")
  require(workerCount * parallelismPerWorker <= 256, "Worker 总 lane 数不能超过 256")
  require(minimumDuration >= Duration.Zero, "minimumDuration 不能为负数")
  require(roundPause >= Duration.Zero, "roundPause 不能为负数")
  require(modelLatency >= Duration.Zero, "modelLatency 不能为负数")
  require(sampleEvery > Duration.Zero, "sampleEvery 必须大于零")
  require(maxClaimP95Millis > 0L && maxTerminalP95Millis > 0L, "延迟阈值必须大于零")
  require(timeout > minimumDuration, "timeout 必须大于 minimumDuration")

  val totalParallelism: Int = workerCount * parallelismPerWorker

  def shouldContinue(completedRounds: Int, elapsed: Duration): Boolean =
    completedRounds < minimumRounds || elapsed < minimumDuration

object DurableWorkerSoakConfig:
  val smoke: DurableWorkerSoakConfig = DurableWorkerSoakConfig(
    minimumRounds = 4,
    runsPerRound = 24,
    workerCount = 3,
    parallelismPerWorker = 2,
    minimumDuration = 5.seconds,
    roundPause = 100.millis,
    modelLatency = 25.millis,
    sampleEvery = 20.millis,
    maxClaimP95Millis = 5000L,
    maxTerminalP95Millis = 10000L,
    timeout = 120.seconds
  )

  def load: Task[DurableWorkerSoakConfig] = ZIO.attempt {
    def int(name: String, default: Int): Int =
      sys.env.get(name).fold(default)(value => value.trim.toInt)

    def long(name: String, default: Long): Long =
      sys.env.get(name).fold(default)(value => value.trim.toLong)

    val baseline = smoke
    DurableWorkerSoakConfig(
      minimumRounds = int("ZYBLW_AGENT_SOAK_MIN_ROUNDS", baseline.minimumRounds),
      runsPerRound = int("ZYBLW_AGENT_SOAK_RUNS_PER_ROUND", baseline.runsPerRound),
      workerCount = int("ZYBLW_AGENT_SOAK_WORKERS", baseline.workerCount),
      parallelismPerWorker = int(
        "ZYBLW_AGENT_SOAK_PARALLELISM_PER_WORKER",
        baseline.parallelismPerWorker
      ),
      minimumDuration = long(
        "ZYBLW_AGENT_SOAK_MIN_DURATION_SECONDS",
        baseline.minimumDuration.toSeconds
      ).seconds,
      roundPause = long("ZYBLW_AGENT_SOAK_ROUND_PAUSE_MILLIS", baseline.roundPause.toMillis).millis,
      modelLatency = long("ZYBLW_AGENT_SOAK_MODEL_LATENCY_MILLIS", baseline.modelLatency.toMillis).millis,
      sampleEvery = long("ZYBLW_AGENT_SOAK_SAMPLE_MILLIS", baseline.sampleEvery.toMillis).millis,
      maxClaimP95Millis = long("ZYBLW_AGENT_SOAK_MAX_CLAIM_P95_MILLIS", baseline.maxClaimP95Millis),
      maxTerminalP95Millis = long(
        "ZYBLW_AGENT_SOAK_MAX_TERMINAL_P95_MILLIS",
        baseline.maxTerminalP95Millis
      ),
      timeout = long("ZYBLW_AGENT_SOAK_TIMEOUT_SECONDS", baseline.timeout.toSeconds).seconds
    )
  }

final case class SoakLatencySummary(
    samples: Long,
    minimumMillis: Long,
    p50Millis: Long,
    p95Millis: Long,
    p99Millis: Long,
    maximumMillis: Long,
    meanMillis: Long,
    bucketWidthMillis: Int
) derives JsonCodec

final case class SoakLatencyHistogram private (
    samples: Long,
    minimumMillis: Long,
    maximumMillis: Long,
    totalMillis: Long,
    buckets: Map[Long, Long]
):
  def observe(value: Long): SoakLatencyHistogram =
    val millis = value.max(0L)
    val bucket = if millis == 0L then 0L else ((millis + 9L) / 10L) * 10L
    copy(
      samples = samples + 1L,
      minimumMillis = if samples == 0L then millis else minimumMillis.min(millis),
      maximumMillis = maximumMillis.max(millis),
      totalMillis = totalMillis + millis,
      buckets = buckets.updated(bucket, buckets.getOrElse(bucket, 0L) + 1L)
    )

  def summary: SoakLatencySummary =
    if samples == 0L then SoakLatencySummary(0L, 0L, 0L, 0L, 0L, 0L, 0L, 10)
    else
      SoakLatencySummary(
        samples,
        minimumMillis,
        percentile(50),
        percentile(95),
        percentile(99),
        maximumMillis,
        totalMillis / samples,
        10
      )

  private def percentile(value: Int): Long =
    val rank = ((samples * value.toLong) + 99L) / 100L
    buckets.toList
      .sortBy(_._1)
      .foldLeft(0L -> 0L) { case ((selected, cumulative), (bucket, count)) =>
        if cumulative >= rank then selected -> cumulative
        else if cumulative + count >= rank then bucket -> (cumulative + count)
        else selected                                  -> (cumulative + count)
      }
      ._1

object SoakLatencyHistogram:
  val empty: SoakLatencyHistogram = SoakLatencyHistogram(0L, 0L, 0L, 0L, Map.empty)

final case class SoakQueueHighWatermarks(
    queuedCommands: Long = 0L,
    dispatchableRuns: Long = 0L,
    leasedRuns: Long = 0L,
    expiredLeases: Long = 0L,
    deadLetterCommands: Long = 0L,
    oldestDispatchableAgeMillis: Long = 0L
) derives JsonCodec:
  def observe(snapshot: RunCommandQueueSnapshot): SoakQueueHighWatermarks =
    SoakQueueHighWatermarks(
      queuedCommands.max(snapshot.queuedCommands),
      dispatchableRuns.max(snapshot.dispatchableRuns),
      leasedRuns.max(snapshot.leasedRuns),
      expiredLeases.max(snapshot.expiredLeases),
      deadLetterCommands.max(snapshot.deadLetterCommands),
      oldestDispatchableAgeMillis.max(snapshot.oldestDispatchableAgeMillis.getOrElse(0L))
    )

final case class SoakCheck(name: String, passed: Boolean, observed: Long, expected: Long) derives JsonCodec

final case class DurableWorkerSoakReport(
    schemaVersion: Int,
    completedAtEpochMillis: Long,
    minimumRounds: Int,
    completedRounds: Int,
    runsPerRound: Int,
    workerCount: Int,
    parallelismPerWorker: Int,
    elapsedMillis: Long,
    submittedRuns: Long,
    completedRuns: Long,
    completedCommands: Long,
    throughputPerMinute: Long,
    workerOwnersObserved: Int,
    maximumConcurrentExecutions: Int,
    generationReclaims: Long,
    automaticRetries: Long,
    queueSamples: Long,
    queueHighWatermarks: SoakQueueHighWatermarks,
    claimLatency: SoakLatencySummary,
    terminalLatency: SoakLatencySummary,
    checks: Chunk[SoakCheck],
    passed: Boolean
) derives JsonCodec

/** A bounded, repeatable PostgreSQL soak probe for the production Start → WorkerHost → AgentRuntime path.
  *
  * It emits aggregate counts and latency histograms only. Run IDs, tenant/user data, prompts, model output,
  * command payloads and lease tokens never enter the report. This is a repository verification tool, not
  * another Runtime or a public SLO promise.
  */
object DurableWorkerSoakProbe extends ZIOAppDefault:
  final private case class DatabaseConfig(jdbcUrl: String, user: String, password: String)

  final private case class ExecutionMetrics(
      invocations: Long,
      active: Int,
      maximumActive: Int,
      owners: Set[String],
      generationReclaims: Long,
      automaticRetries: Long,
      claimLatency: SoakLatencyHistogram
  )

  final private case class QueueMetrics(samples: Long, highWatermarks: SoakQueueHighWatermarks)

  final private case class WorkloadMetrics(
      completedRounds: Int,
      submittedRuns: Long,
      completedRuns: Long,
      completedCommands: Long,
      terminalLatency: SoakLatencyHistogram
  )

  private val emptyExecutionMetrics =
    ExecutionMetrics(0L, 0, 0, Set.empty, 0L, 0L, SoakLatencyHistogram.empty)
  private val emptyQueueMetrics    = QueueMetrics(0L, SoakQueueHighWatermarks())
  private val emptyWorkloadMetrics =
    WorkloadMetrics(0, 0L, 0L, 0L, SoakLatencyHistogram.empty)

  final private class RepeatingChatModel(latency: Duration) extends ChatModel:
    val provider: String                        = "durable-worker-soak"
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
      provider,
      "Durable worker soak model",
      "deterministic",
      ModelCapabilities(toolCalls = false, usageReporting = true)
    )

    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      ZIO
        .sleep(latency)
        .as(
          ChatResponse(
            AgentMessage.assistant("durable worker soak completed"),
            FinishReason.Stop,
            TokenUsage(inputTokens = 4L, outputTokens = 4L)
          )
        )

  final private class MeasuringRuntime(
      delegate: LeaseAwareAgentRuntime,
      metrics: Ref[ExecutionMetrics]
  ) extends LeaseAwareAgentRuntime:
    def executeLeased(lease: RunCommandLease): IO[AgentError, Unit] =
      val claimLatency = java.time.Duration
        .between(lease.command.createdAt, lease.claimedAt)
        .toMillis
        .max(0L)
      val acquire = metrics.update { current =>
        val active = current.active + 1
        current.copy(
          invocations = current.invocations + 1L,
          active = active,
          maximumActive = current.maximumActive.max(active),
          owners = current.owners + lease.owner.value,
          generationReclaims =
            current.generationReclaims + Option.when(lease.generation > 1L)(1L).getOrElse(0L),
          automaticRetries =
            current.automaticRetries + Option.when(lease.command.attempt > 1)(1L).getOrElse(0L),
          claimLatency = current.claimLatency.observe(claimLatency)
        )
      }
      (acquire *> delegate.executeLeased(lease))
        .ensuring(metrics.update(current => current.copy(active = (current.active - 1).max(0))))

  private def loadDatabaseConfig: Task[DatabaseConfig] = ZIO.attempt {
    def required(name: String): String =
      sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse {
        throw IllegalArgumentException(s"missing required environment variable: $name")
      }

    if !sys.env.get("ZYBLW_AGENT_SOAK_CONFIRM_DISPOSABLE").contains("true") then
      throw IllegalArgumentException(
        "ZYBLW_AGENT_SOAK_CONFIRM_DISPOSABLE=true is required; never run the soak against a shared database"
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

  private def services(
      dataSource: DataSource,
      model: ChatModel
  ): Layer[
    AgentError.InvalidConfiguration,
    AgentCommandService & RunStore & RunCommandStore & LeaseAwareAgentRuntime
  ] =
    ZLayer.make[AgentCommandService & RunStore & RunCommandStore & LeaseAwareAgentRuntime](
      ZLayer.succeed[DataSource](dataSource),
      PostgresAgentPersistence.layer,
      ZLayer.succeed[ChatModel](model),
      RegisteredToolRegistry.fromTools(Nil),
      ContextSourceResolver.empty,
      GuardrailEngine.empty,
      RunObserver.noop,
      ZLayer.succeed(ToolPolicyConfig.secureDefault),
      ToolPolicySource.staticLayer,
      ModelPolicySource.defaultLayer,
      TokenCounter.approximate,
      ContextCompressor.deterministic,
      DefaultContextManager.layer,
      RuntimeExtensions.emptyLayer,
      AgentRuntimeLive.layerWithProfile(RuntimeProfile.default),
      AgentCommandServiceLive.configured(RuntimeProfile.default)
    )

  private def makeAgent: IO[AgentError.InvalidConfiguration, AgentDefinition] =
    AgentDefinitionBuilder(AgentId("durable-worker-soak"), "Durable Worker Soak")
      .withInstructions("Return one deterministic completion without tools.")
      .build

  private def makeHosts(
      config: DurableWorkerSoakConfig,
      store: RunCommandStore,
      runtime: LeaseAwareAgentRuntime
  ): UIO[Chunk[WorkerHost]] =
    ZIO
      .foreach(1 to config.workerCount) { index =>
        WorkerHost
          .make(
            WorkerId(s"soak-worker-$index"),
            WorkerHostConfig(
              leaseDuration = 10.seconds,
              heartbeatEvery = 2.seconds,
              pollEvery = 10.millis,
              retryDelay = 100.millis,
              maxAttempts = 3,
              parallelism = config.parallelismPerWorker
            )
          )
          .provide(ZLayer.succeed(store), ZLayer.succeed(runtime))
      }
      .map(Chunk.fromIterable)

  private def sampler(
      store: RunCommandStore,
      metrics: Ref[QueueMetrics],
      every: Duration
  ): IO[AgentError, Nothing] =
    (store.queueSnapshot.flatMap(snapshot =>
      metrics.update(current => QueueMetrics(current.samples + 1L, current.highWatermarks.observe(snapshot)))
    ) *> ZIO.sleep(every)).forever

  private def submitRound(
      probeId: String,
      round: Int,
      config: DurableWorkerSoakConfig,
      commands: AgentCommandService,
      agent: AgentDefinition
  ): IO[AgentError, Chunk[RunCommandRecord]] =
    ZIO
      .foreachPar(1 to config.runsPerRound) { index =>
        commands.submitStart(
          agent,
          RunRequest(
            ThreadId(s"soak-$probeId-$round-$index"),
            AgentMessage.user("complete the deterministic soak turn")
          ),
          idempotencyKey = s"soak-$probeId-$round-$index"
        )
      }
      .withParallelism(config.totalParallelism)
      .map(Chunk.fromIterable)

  private def awaitCommands(
      submitted: Chunk[RunCommandRecord],
      store: RunCommandStore,
      parallelism: Int
  ): IO[AgentError, Chunk[RunCommandRecord]] =
    ZIO
      .foreachPar(submitted)(record => store.get(record.commandId))
      .withParallelism(parallelism)
      .flatMap { current =>
        current.find(_.status == RunCommandStatus.DeadLetter) match
          case Some(_) => ZIO.fail(AgentError.Unexpected("soak command entered DeadLetter"))
          case None if current.forall(_.status == RunCommandStatus.Completed) => ZIO.succeed(current)
          case None => ZIO.sleep(20.millis) *> awaitCommands(submitted, store, parallelism)
      }

  private def executeRound(
      probeId: String,
      round: Int,
      config: DurableWorkerSoakConfig,
      commands: AgentCommandService,
      runStore: RunStore,
      commandStore: RunCommandStore,
      agent: AgentDefinition,
      metrics: Ref[WorkloadMetrics]
  ): IO[AgentError, Unit] =
    for
      submitted <- submitRound(probeId, round, config, commands, agent)
      completed <- awaitCommands(submitted, commandStore, config.totalParallelism)
      states    <- ZIO
        .foreachPar(completed)(record => runStore.load(record.runId))
        .withParallelism(config.totalParallelism)
      _ <- ZIO
        .fail(AgentError.Unexpected("soak command completed without a Completed AgentState"))
        .unless(states.forall(_.status == RunStatus.Completed))
      _ <- metrics.update(current =>
        current.copy(
          completedRounds = current.completedRounds + 1,
          submittedRuns = current.submittedRuns + submitted.length.toLong,
          completedRuns = current.completedRuns + states.count(_.status == RunStatus.Completed).toLong,
          completedCommands = current.completedCommands + completed
            .count(_.status == RunCommandStatus.Completed)
            .toLong,
          terminalLatency = completed.foldLeft(current.terminalLatency) { (histogram, record) =>
            histogram.observe(java.time.Duration.between(record.createdAt, record.updatedAt).toMillis)
          }
        )
      )
      _ <- ZIO.sleep(config.roundPause)
    yield ()

  private def workload(
      probeId: String,
      config: DurableWorkerSoakConfig,
      commands: AgentCommandService,
      runStore: RunStore,
      commandStore: RunCommandStore,
      agent: AgentDefinition,
      metrics: Ref[WorkloadMetrics],
      startedNanos: Long
  ): IO[AgentError, Unit] =
    def loop(round: Int): IO[AgentError, Unit] =
      for
        now <- Clock.nanoTime
        elapsed = Duration.fromNanos((now - startedNanos).max(0L))
        _ <-
          if config.shouldContinue(round, elapsed) then
            executeRound(probeId, round, config, commands, runStore, commandStore, agent, metrics) *> loop(
              round + 1
            )
          else ZIO.unit
      yield ()
    loop(0)

  private def buildReport(
      config: DurableWorkerSoakConfig,
      startedNanos: Long,
      execution: ExecutionMetrics,
      queue: QueueMetrics,
      workload: WorkloadMetrics,
      finalSnapshot: RunCommandQueueSnapshot
  ): UIO[DurableWorkerSoakReport] =
    for
      endedNanos  <- Clock.nanoTime
      completedAt <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
    yield
      val elapsedMillis   = ((endedNanos - startedNanos) / 1000000L).max(1L)
      val claim           = execution.claimLatency.summary
      val terminal        = workload.terminalLatency.summary
      val minimumOwners   = config.workerCount.min(2).min(workload.submittedRuns.toInt)
      val minimumActive   = config.totalParallelism.min(2).min(workload.submittedRuns.toInt)
      val finalQueueDepth =
        finalSnapshot.queuedCommands + finalSnapshot.leasedRuns + finalSnapshot.deadLetterCommands
      val checks = Chunk(
        SoakCheck(
          "runs.completed",
          workload.completedRuns == workload.submittedRuns,
          workload.completedRuns,
          workload.submittedRuns
        ),
        SoakCheck(
          "commands.completed",
          workload.completedCommands == workload.submittedRuns,
          workload.completedCommands,
          workload.submittedRuns
        ),
        SoakCheck(
          "runtime.invocations",
          execution.invocations == workload.submittedRuns,
          execution.invocations,
          workload.submittedRuns
        ),
        SoakCheck(
          "workers.observed.min",
          execution.owners.size >= minimumOwners,
          execution.owners.size,
          minimumOwners
        ),
        SoakCheck(
          "concurrency.observed.min",
          execution.maximumActive >= minimumActive,
          execution.maximumActive,
          minimumActive
        ),
        SoakCheck(
          "concurrency.capacity.max",
          execution.maximumActive <= config.totalParallelism,
          execution.maximumActive,
          config.totalParallelism
        ),
        SoakCheck(
          "generation.reclaims.max",
          execution.generationReclaims == 0L,
          execution.generationReclaims,
          0L
        ),
        SoakCheck("automatic.retries.max", execution.automaticRetries == 0L, execution.automaticRetries, 0L),
        SoakCheck(
          "queue.expired_leases.max",
          queue.highWatermarks.expiredLeases == 0L,
          queue.highWatermarks.expiredLeases,
          0L
        ),
        SoakCheck(
          "queue.dead_letters.max",
          queue.highWatermarks.deadLetterCommands == 0L,
          queue.highWatermarks.deadLetterCommands,
          0L
        ),
        SoakCheck("queue.final_depth.max", finalQueueDepth == 0L, finalQueueDepth, 0L),
        SoakCheck(
          "claim.p95_millis.max",
          claim.p95Millis <= config.maxClaimP95Millis,
          claim.p95Millis,
          config.maxClaimP95Millis
        ),
        SoakCheck(
          "terminal.p95_millis.max",
          terminal.p95Millis <= config.maxTerminalP95Millis,
          terminal.p95Millis,
          config.maxTerminalP95Millis
        )
      )
      DurableWorkerSoakReport(
        schemaVersion = 1,
        completedAtEpochMillis = completedAt,
        minimumRounds = config.minimumRounds,
        completedRounds = workload.completedRounds,
        runsPerRound = config.runsPerRound,
        workerCount = config.workerCount,
        parallelismPerWorker = config.parallelismPerWorker,
        elapsedMillis = elapsedMillis,
        submittedRuns = workload.submittedRuns,
        completedRuns = workload.completedRuns,
        completedCommands = workload.completedCommands,
        throughputPerMinute = (workload.completedRuns * 60000L) / elapsedMillis,
        workerOwnersObserved = execution.owners.size,
        maximumConcurrentExecutions = execution.maximumActive,
        generationReclaims = execution.generationReclaims,
        automaticRetries = execution.automaticRetries,
        queueSamples = queue.samples,
        queueHighWatermarks = queue.highWatermarks.observe(finalSnapshot),
        claimLatency = claim,
        terminalLatency = terminal,
        checks = checks,
        passed = checks.forall(_.passed)
      )

  private def execute(
      config: DurableWorkerSoakConfig,
      dataSource: DataSource,
      model: ChatModel
  ): IO[AgentError, DurableWorkerSoakReport] =
    (for
      commands        <- ZIO.service[AgentCommandService]
      runStore        <- ZIO.service[RunStore]
      commandStore    <- ZIO.service[RunCommandStore]
      delegateRuntime <- ZIO.service[LeaseAwareAgentRuntime]
      agent           <- makeAgent
      execution       <- Ref.make(emptyExecutionMetrics)
      queue           <- Ref.make(emptyQueueMetrics)
      completed       <- Ref.make(emptyWorkloadMetrics)
      runtime = MeasuringRuntime(delegateRuntime, execution)
      hosts   <- makeHosts(config, commandStore, runtime)
      probeId <- Random.nextUUID.map(_.toString)
      started <- Clock.nanoTime
      runWorkload = workload(probeId, config, commands, runStore, commandStore, agent, completed, started)
      runWorkers  = ZIO.foreachParDiscard(hosts)(_.run)
      runSampler  = sampler(commandStore, queue, config.sampleEvery)
      _ <- runWorkload
        .raceFirst(runWorkers)
        .raceFirst(runSampler)
        .timeoutFail(AgentError.Unexpected("durable worker soak timed out"))(config.timeout)
      finalSnapshot   <- commandStore.queueSnapshot
      executionResult <- execution.get
      queueResult     <- queue.get
      workloadResult  <- completed.get
      report <- buildReport(config, started, executionResult, queueResult, workloadResult, finalSnapshot)
    yield report).provide(services(dataSource, model))

  override def gracefulShutdownTimeout: Duration = 15.seconds

  def run: ZIO[Any, Any, Unit] =
    for
      config         <- DurableWorkerSoakConfig.load
      databaseConfig <- loadDatabaseConfig
      dataSource     <- makeDataSource(databaseConfig)
      _              <- AgentPostgresMigrations.migrate(dataSource)
      report         <- execute(config, dataSource, RepeatingChatModel(config.modelLatency))
      _              <- Console.printLine(s"SOAK_REPORT ${report.toJson}")
      failed = report.checks.filterNot(_.passed).map(_.name)
      _ <- ZIO
        .fail(IllegalStateException(s"durable worker soak failed checks: ${failed.mkString(",")}"))
        .unless(report.passed)
    yield ()
