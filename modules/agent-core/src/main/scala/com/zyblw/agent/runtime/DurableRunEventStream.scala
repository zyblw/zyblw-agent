package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

/** 跨 Worker 耐久事件订阅的轮询参数。
  *
  * @param pollInterval
  *   当前没有新事件时的数据库轮询间隔；真实部署应结合连接池与期望延迟调整
  * @param batchSize
  *   单次按 sequence 读取的最大事件数，形成数据库和网络的背压边界
  */
final case class DurableRunEventStreamConfig(
    pollInterval: Duration = 500.millis,
    batchSize: Int = 256
):
  require(pollInterval > Duration.Zero, "耐久事件 pollInterval 必须为正数")
  require(batchSize > 0 && batchSize <= 4096, "耐久事件 batchSize 必须位于 1..4096")

/** 从唯一 `AgentState/RunStore` 事实源构造可断点续传的事件流。
  *
  * 与进程内 `RunEventStream` 不同，本服务不依赖创建 Run 的 Worker，也不丢失部署前已经提交的事件。客户端可以把最后 收到的 `sequence` 放入 `Last-Event-ID`
  * 后连接任意 HTTP 实例。流只输出已随状态事务提交的精选事件；逐 token delta 属于瞬时高吞吐数据，若业务需要跨节点传输，应接入有界消息总线，而不能伪装成 PostgreSQL 耐久事件。
  */
trait DurableRunEventStream:
  /** 从指定游标之后开始订阅。
    *
    * @param runId
    *   目标运行
    * @param afterSequence
    *   客户端已确认的最后序号；`-1` 表示从头开始
    * @return
    *   sequence 严格连续、按批读取且可被 Fiber 中断的 ZStream
    */
  def events(runId: RunId, afterSequence: Long = -1L): ZStream[Any, AgentError, PersistedAgentEvent]

/** 使用 AgentRuntime 的只读状态/事件 API 实现耐久订阅。 */
final class DurableRunEventStreamLive(
    runtime: AgentRuntime,
    config: DurableRunEventStreamConfig
) extends DurableRunEventStream:
  /** `paginateChunkZIO` 让每个数据库页面直接成为 ZStream chunk；下游背压时不会预取无界页面。 暂停/待审批也是本次连接的静止边界，客户端在提交审批后可携带原游标重新连接。
    */
  def events(runId: RunId, afterSequence: Long): ZStream[Any, AgentError, PersistedAgentEvent] =
    if afterSequence < -1L then ZStream.fail(AgentError.InvalidConfiguration("事件游标不能小于 -1"))
    else
      ZStream.paginateChunkZIO(afterSequence) { cursor =>
        runtime.persistedEvents(runId, cursor, config.batchSize).flatMap { page =>
          if page.nonEmpty then emitPage(runId, cursor, page)
          else inspectAfterEmptyPage(runId, cursor)
        }
      }

  /** 空事件页之后再读取权威状态，并区分“仍在等待”“客户端游标非法”和“状态已经前进”三种情况。
    *
    * 事件页与状态是两次只读查询。即使生产提交把 AgentState 与事件放在同一事务，提交仍可能恰好发生在这两次查询之间： 第一次事件查询返回空，随后状态查询已经看到更大的
    * `lastEventSequence`。此时不能立刻报告数据损坏；必须在观察到状态 前进之后再读一次事件。因为第二次事件读取发生在状态观察之后，若仍为空，才说明同一权威 Store 的状态/事件契约被
    * 破坏，而不是普通 TOCTOU 竞态。
    */
  private def inspectAfterEmptyPage(
      runId: RunId,
      cursor: Long
  ): IO[AgentError, (Chunk[PersistedAgentEvent], Option[Long])] =
    runtime.inspect(runId).flatMap { state =>
      if cursor > state.lastEventSequence then
        ZIO.fail(
          AgentError.InvalidConfiguration(
            s"事件游标 $cursor 超过 Run 当前最后序号 ${state.lastEventSequence}"
          )
        )
      else if cursor < state.lastEventSequence then
        rereadAfterObservedAdvance(runId, cursor, state.lastEventSequence)
      else if quiescent(state.status) then ZIO.succeed(Chunk.empty -> None)
      else ZIO.sleep(config.pollInterval).as(Chunk.empty           -> Some(cursor))
    }

  /** 在已经观察到状态序号前进后重新读取事件，消除“事件查询与状态查询之间发生原子提交”的正常竞态。
    *
    * @param runId
    *   目标 Run
    * @param cursor
    *   调用方已经确认的最后事件序号
    * @param observedLastSequence
    *   状态查询观察到的权威最后序号，仅用于生成低敏一致性错误
    */
  private def rereadAfterObservedAdvance(
      runId: RunId,
      cursor: Long,
      observedLastSequence: Long
  ): IO[AgentError, (Chunk[PersistedAgentEvent], Option[Long])] =
    runtime.persistedEvents(runId, cursor, config.batchSize).flatMap { confirmedPage =>
      if confirmedPage.nonEmpty then emitPage(runId, cursor, confirmedPage)
      else
        ZIO.fail(
          AgentError.PersistenceFailure(
            s"Run ${runId.asString} 声明最后序号 $observedLastSequence，但游标 $cursor 后没有可读事件"
          )
        )
    }

  /** 校验非空页并把最后序号作为下一轮游标，所有首次读取和竞态重读共用同一连续性门禁。 */
  private def emitPage(
      runId: RunId,
      cursor: Long,
      page: Chunk[PersistedAgentEvent]
  ): IO[AgentError, (Chunk[PersistedAgentEvent], Option[Long])] =
    validatePage(runId, cursor, page).as(page -> Some(page.last.sequence))

  /** 检测存储损坏、错误排序或分页实现遗漏，避免客户端悄悄跳过审批/终态事件。 */
  private def validatePage(
      runId: RunId,
      cursor: Long,
      page: Chunk[PersistedAgentEvent]
  ): IO[AgentError, Unit] =
    val expected = Chunk.fromIterable(0.until(page.length).map(index => cursor + index.toLong + 1L))
    val actual   = page.map(_.sequence)
    val sameRun  = page.forall(_.runId == runId)
    if sameRun && actual == expected then ZIO.unit
    else
      ZIO.fail(
        AgentError.PersistenceFailure(
          s"Run ${runId.asString} 的事件页不连续: cursor=$cursor, sequences=${actual.mkString(",")}"
        )
      )

  /** 终态和人工暂停态都不会自行产生下一事件，因此当前 SSE 连接可以安全结束。 */
  private def quiescent(status: RunStatus): Boolean = status match
    case RunStatus.WaitingForApproval | RunStatus.Suspended | RunStatus.Completed | RunStatus.Failed |
        RunStatus.Cancelled | RunStatus.TimedOut | RunStatus.BudgetExceeded =>
      true
    case RunStatus.Created | RunStatus.Running => false

object DurableRunEventStream:
  /** 使用默认轮询参数直接构造，适合 HTTP Adapter 默认装配与测试。 */
  def make(
      runtime: AgentRuntime,
      config: DurableRunEventStreamConfig = DurableRunEventStreamConfig()
  ): DurableRunEventStream =
    DurableRunEventStreamLive(runtime, config)

  /** 从环境装配可替换配置的生产 Layer。 */
  val layer: URLayer[AgentRuntime & DurableRunEventStreamConfig, DurableRunEventStream] =
    ZLayer.fromFunction(DurableRunEventStreamLive.apply)

  /** 使用框架默认轮询参数的便捷 Layer。 */
  val default: URLayer[AgentRuntime, DurableRunEventStream] =
    ZLayer.fromFunction((runtime: AgentRuntime) =>
      DurableRunEventStreamLive(runtime, DurableRunEventStreamConfig())
    )
