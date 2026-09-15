package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{ModelCallWrite, RunCommandLease, RunStore}
import zio.*

/** `AgentState` 的唯一持久化出口。
  *
  * 把此前分散在 Runtime 里的创建写入、乐观锁提交、fenced 提交、事件序号推进与 `CheckpointSaved` 发射收敛到一处，因此
  * "所有耐久状态写入都经过同一边界"成为可强制的不变量，而不是需要逐个调用点复核的约定。
  *
  * `commit` 与 `commitFenced` 的选择完全由内部持有的 `executionLease` 决定，调用方不感知 fencing：Worker 通过
  * [[RunCommitter.withLease]] 把租约绑定到当前 Fiber 及其子 Fiber，之后同一段代码在单进程嵌入与分布式 Worker 下 走不同的存储原语，但业务逻辑不需要分叉。
  */
private[agent] trait RunCommitter:
  /** 创建 Run 的首次写入：初始状态与首批不可缺失事件在同一存储事务内落库。
    *
    * @param initial
    *   `lastEventSequence` 尚未推进的初始状态
    * @param events
    *   `RunCreated` 等首批领域事件
    * @return
    *   已回填 version 与 `lastEventSequence` 的权威状态
    */
  def create(initial: AgentState, events: NonEmptyChunk[AgentEvent]): IO[AgentError, AgentState]

  /** 提交状态转换与单个领域事件。 */
  def commit(current: AgentState, next: AgentState, event: AgentEvent): IO[AgentError, AgentState] =
    commitAll(current, next, NonEmptyChunk(event), None)

  /** 提交状态转换、连续事件批次与可选主模型账本。
    *
    * 内部四项职责均不可绕过：补全事件时间戳与 `EventId`、从 `current.lastEventSequence` 起分配连续序号、按租约选择 `commitFenced` 或 `commit`、回填
    * version 并发射事件与 `CheckpointSaved`。
    *
    * @param current
    *   提供 expectedVersion 与上一事件序号的权威状态
    * @param next
    *   尚未推进 version / `lastEventSequence` 的下一状态
    */
  def commitAll(
      current: AgentState,
      next: AgentState,
      events: NonEmptyChunk[AgentEvent],
      modelCall: Option[ModelCallWrite] = None
  ): IO[AgentError, AgentState]

  /** 直接提交内核产出的 `Transition`，复用完成、暂停、失败与取消的共同形状。 */
  def commitTransition(
      current: AgentState,
      transition: AgentKernel.Transition,
      modelCall: Option[ModelCallWrite] = None
  ): IO[AgentError, AgentState] =
    commitAll(current, transition.state, transition.events, modelCall)

  /** 尽力提交终态：并发取消可能与完成提交竞争，乐观锁失败必须被忽略。
    *
    * 最终状态始终由数据库中先成功的终态决定；记录失败不能覆盖调用方已经收到的原始错误。
    */
  def commitBestEffort(current: AgentState, transition: AgentKernel.Transition): UIO[Unit] =
    commitTransition(current, transition).unit.ignore

  /** 在当前 Fiber 及其子 Fiber 范围内绑定 fencing 租约。
    *
    * 使用 `FiberRef.locally` 语义：成功、失败或中断都会恢复旧值，因此同一个 Runtime 实例处理下一条命令时不会继承 上一条命令的权限。
    */
  def withLease[R, E, A](lease: RunCommandLease)(effect: ZIO[R, E, A]): ZIO[R, E, A]

object RunCommitter:
  /** 以 `RunStore` 与事件发布器构造提交边界。
    *
    * @param publish
    *   已提交事件的进程内投影；它只能观察，不能改变提交结果
    */
  def make(store: RunStore, publish: AgentEvent => UIO[Unit]): URIO[Scope, RunCommitter] =
    FiberRef.make(Option.empty[RunCommandLease]).map(new Live(store, publish, _))

  final private class Live(
      store: RunStore,
      publish: AgentEvent => UIO[Unit],
      executionLease: FiberRef[Option[RunCommandLease]]
  ) extends RunCommitter:
    def create(initial: AgentState, events: NonEmptyChunk[AgentEvent]): IO[AgentError, AgentState] =
      for
        stamped <- persistable(initial.runId, initial.lastEventSequence, events)
        (persisted, lastSequence, at) = stamped
        durable                       = initial.copy(lastEventSequence = lastSequence)
        _ <- store.createWithEvents(durable, persisted)
        _ <- ZIO.foreachDiscard(persisted)(entry => publish(entry.event))
        _ <- publish(AgentEvent.CheckpointSaved(initial.runId, durable.version, at))
      yield durable

    def commitAll(
        current: AgentState,
        next: AgentState,
        events: NonEmptyChunk[AgentEvent],
        modelCall: Option[ModelCallWrite]
    ): IO[AgentError, AgentState] =
      for
        stamped <- persistable(current.runId, current.lastEventSequence, events)
        (persisted, lastSequence, at) = stamped
        durable                       = next.copy(lastEventSequence = lastSequence)
        lease   <- executionLease.get
        version <- lease match
          case Some(value) => store.commitFenced(value, current.version, durable, persisted, modelCall)
          case None        => store.commit(current.version, durable, persisted, modelCall)
        saved = durable.copy(version = version)
        _ <- ZIO.foreachDiscard(persisted)(entry => publish(entry.event))
        _ <- publish(AgentEvent.CheckpointSaved(current.runId, version, at))
      yield saved

    def withLease[R, E, A](lease: RunCommandLease)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
      executionLease.locally(Some(lease))(effect)

    /** 补全时间戳与 `EventId`，并分配从给定游标起的连续序号。 */
    private def persistable(
        runId: RunId,
        afterSequence: Long,
        events: NonEmptyChunk[AgentEvent]
    ): UIO[(NonEmptyChunk[PersistedAgentEvent], Long, Long)] =
      for
        eventIds <- ZIO.foreach(events)(_ => EventId.random)
        now      <- RunClock.millis
        persisted = NonEmptyChunk
          .fromChunk(events.toChunk.zip(eventIds.toChunk).zipWithIndex.map { case ((event, eventId), index) =>
            val sequence = afterSequence + index.toLong + 1L
            PersistedAgentEvent(eventId, runId, sequence, stamp(event, now), now)
          })
          .get
      yield (persisted, persisted.last.sequence, now)

    /** 为构造时尚未取得当前时间的事件补入真实时间戳。 */
    private def stamp(event: AgentEvent, at: Long): AgentEvent =
      event match
        case AgentEvent.RunCancelled(runId, _) => AgentEvent.RunCancelled(runId, at)
        case other                             => other
