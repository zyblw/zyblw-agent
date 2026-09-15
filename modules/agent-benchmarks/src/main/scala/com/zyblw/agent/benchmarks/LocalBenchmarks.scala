package com.zyblw.agent.benchmarks

import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.time.Instant
import zio.*

/** 无第三方基准插件的本地 smoke benchmark。结果只用于同机版本回归，不能作为公开性能结论。
  */
object LocalBenchmarks extends ZIOAppDefault:
  private val iterations = 10_000

  /** 基准只测量存储路径，Agent 定义保持最小；它必须存在是因为状态必须自带定义与组合快照。 */
  private val agent = AgentDefinition(AgentId("benchmark"), "benchmark", "benchmark agent")

  def run =
    (for
      store   <- ZIO.service[RunStore]
      started <- Clock.nanoTime
      _       <- ZIO.foreachDiscard(0 until iterations) { _ =>
        for
          runId     <- RunId.random
          sessionId <- SessionId.random
          eventId   <- EventId.random
          limits = RunLimits()
          state  = AgentState(
            runId,
            sessionId,
            AgentId("benchmark"),
            RunStatus.Created,
            Chunk.empty,
            Chunk.empty,
            UsageSummary(),
            BudgetState(limits, UsageSummary(), 0),
            None,
            Instant.EPOCH,
            Instant.EPOCH,
            Version.initial,
            LocalBenchmarks.agent,
            RuntimeComposition.fingerprint(
              RuntimeProfile.default,
              LocalBenchmarks.agent,
              LocalBenchmarks.agent.modelSettings
            ),
            ThreadId("benchmark-thread"),
            lastEventSequence = 0L
          )
          event = PersistedAgentEvent(
            eventId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, sessionId, 0L),
            0L
          )
          _ <- store.createWithEvents(state, NonEmptyChunk(event))
          _ <- store.load(runId)
        yield ()
      }
      ended <- Clock.nanoTime
      _     <- Console.printLine(
        s"InMemoryRunStore createWithEvents+load: $iterations 次，耗时 ${(ended - started) / 1_000_000} ms"
      )
    yield ()).provide(RunStore.inMemory)
