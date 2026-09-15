package com.zyblw.agent.inspection

import com.zyblw.agent.composition.LiveComposition
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{RunCommandStore, RunStore}
import zio.*

/** 从权威存储组装可安全导出的事故包；分页读取时间线，不含 prompt 或工具参数。 */
object IncidentPackExporter:
  /** @param live
    *   现场组合读出口。在生产进程内导出时应当传入，这样事故包直接带上 drift diff；纯离线导出（只有数据库、没有装配）传 `None`， 此时事故包只含冻结侧而不臆造对照结论。
    */
  def fromStore(
      store: RunStore,
      runId: RunId,
      generatedAtEpochMilli: Long,
      live: Option[LiveComposition] = None,
      commands: Option[RunCommandStore] = None
  ): IO[StoreError, IncidentPack] =
    RunTrajectoryExporter
      .fromStore(store, runId, live, commands)
      .map(IncidentPack.build(_, generatedAtEpochMilli))

  def encodeFromStore(
      store: RunStore,
      runId: RunId,
      generatedAtEpochMilli: Long,
      forbidden: Chunk[String] = Chunk.empty,
      live: Option[LiveComposition] = None,
      commands: Option[RunCommandStore] = None
  ): IO[StoreError, Either[String, String]] =
    fromStore(store, runId, generatedAtEpochMilli, live, commands).map(pack =>
      IncidentPack.encode(pack, forbidden)
    )
