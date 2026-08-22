package com.zyblw.agent.inspection

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunStore
import zio.*

/** 从 RunStore 组装可安全导出的事故包；分页读取时间线，不含 prompt 或工具参数。 */
object IncidentPackExporter:
  def fromStore(
      store: RunStore,
      runId: RunId,
      generatedAtEpochMilli: Long
  ): IO[StoreError, IncidentPack] =
    for
      state  <- store.load(runId)
      events <- loadTimeline(store, runId, state.lastEventSequence)
      calls  <- store.getModelCalls(runId)
    yield IncidentPack.build(
      RunInspection.build(state, events),
      calls,
      state.composition.map(_.value),
      generatedAtEpochMilli
    )

  def encodeFromStore(
      store: RunStore,
      runId: RunId,
      generatedAtEpochMilli: Long,
      forbidden: Chunk[String] = Chunk.empty
  ): IO[StoreError, Either[String, String]] =
    fromStore(store, runId, generatedAtEpochMilli).map(pack => IncidentPack.encode(pack, forbidden))

  private def loadTimeline(
      store: RunStore,
      runId: RunId,
      lastSequence: Long
  ): IO[StoreError, Chunk[PersistedAgentEvent]] =
    def page(after: Long, acc: Chunk[PersistedAgentEvent]): IO[StoreError, Chunk[PersistedAgentEvent]] =
      store.events(runId, after, 512).flatMap { batch =>
        val next = acc ++ batch
        if batch.isEmpty || batch.last.sequence >= lastSequence || batch.length < 512 then ZIO.succeed(next)
        else page(batch.last.sequence, next)
      }
    page(-1L, Chunk.empty)
