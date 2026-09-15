package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.extension.{RuntimeExtensions, ToolLifecycleObserver}
import com.zyblw.agent.memory.RunStore
import zio.*

/** 工具执行账本的唯一写入出口。
  *
  * 把此前分散的 pending write 插入与五处状态 CAS 收敛到一处，同时让"写账本"与"通知生命周期观察者"在同一个方法里发生， 因此不可能出现推进了账本却漏掉观察者的路径。CAS
  * 三元组（期望状态、期望 attempt、下一记录）封在实现内部，对外只暴露 语义化迁移。
  *
  * 观察者全部是 `UIO`，且缺陷被吞掉：它们不能取消已经发生或即将发生的副作用。
  */
private[agent] trait ToolLedger:
  /** 在任何工具 Fiber 启动前，一次性原子插入整批 Prepared pending write。
    *
    * @param batchId
    *   `DurableToolBatch.executionBatchId(planId)` 的结果，用于按批次恢复
    * @return
    *   已写入的记录；已存在的记录保持原值，不会覆盖恢复结果
    */
  def prepareBatch(
      runId: RunId,
      batchId: String,
      items: Chunk[DurableToolPlanItem],
      at: Long
  ): IO[AgentError, NonEmptyChunk[ToolExecutionRecord]]

  /** Prepared/Failed → Running，并递增 attempt。 */
  def begin(existing: ToolExecutionRecord, at: Long): IO[AgentError, ToolExecutionRecord]

  /** Running → Succeeded，并写入结果。 */
  def succeed(
      active: ToolExecutionRecord,
      result: ToolResult,
      at: Long
  ): IO[AgentError, ToolExecutionRecord]

  /** Running → Failed 或 Unknown。
    *
    * @param mayReplayAfterCrash
    *   工具作者声明的崩溃后可重放性；决定失败是可重试的 `Failed` 还是必须人工确认的 `Unknown`
    */
  def fail(
      active: ToolExecutionRecord,
      mayReplayAfterCrash: Boolean,
      errorCategory: String,
      at: Long
  ): IO[AgentError, ToolExecutionRecord]

  /** Running → Unknown。崩溃恢复在无法判断外部副作用时的收口写入。 */
  def settleUnknown(existing: ToolExecutionRecord, at: Long): IO[AgentError, ToolExecutionRecord]

  def get(runId: RunId, callId: String): IO[AgentError, Option[ToolExecutionRecord]]

  /** 按批次读取全部 pending write，用于部分成功恢复。 */
  def getBatch(runId: RunId, batchId: String): IO[AgentError, Chunk[ToolExecutionRecord]]

object ToolLedger:
  def make(store: RunStore, extensions: RuntimeExtensions): ToolLedger =
    Live(store, extensions.toolLifecycleObservers)

  final private case class Live(
      store: RunStore,
      observers: Chunk[ToolLifecycleObserver]
  ) extends ToolLedger:
    def prepareBatch(
        runId: RunId,
        batchId: String,
        items: Chunk[DurableToolPlanItem],
        at: Long
    ): IO[AgentError, NonEmptyChunk[ToolExecutionRecord]] =
      ZIO
        .fromOption(NonEmptyChunk.fromChunk(items))
        .orElseFail(AgentError.PersistenceFailure(s"Run ${runId.asString} 的工具批次不能为空"))
        .flatMap { present =>
          val records = present.map { item =>
            ToolExecutionRecord(
              runId,
              batchId,
              item.ordinal,
              item.call.id,
              item.call.name,
              Some(s"${runId.asString}:${item.call.id}"),
              ToolExecutionStatus.Prepared,
              None,
              0,
              at
            )
          }
          store.prepareToolExecutions(records) *>
            ZIO
              .foreachDiscard(records)(record => notify(_.onPrepared(record.callId, record.toolName)))
              .as(records)
        }

    def begin(existing: ToolExecutionRecord, at: Long): IO[AgentError, ToolExecutionRecord] =
      transition(
        existing,
        existing.copy(
          status = ToolExecutionStatus.Running,
          attempt = existing.attempt + 1,
          updatedAtEpochMilli = at
        )
      ).tap(record => notify(_.onStarted(record.callId, record.toolName)))

    def succeed(
        active: ToolExecutionRecord,
        result: ToolResult,
        at: Long
    ): IO[AgentError, ToolExecutionRecord] =
      transition(
        active,
        active.copy(
          status = ToolExecutionStatus.Succeeded,
          result = Some(result),
          updatedAtEpochMilli = at
        )
      ).tap(record => notify(_.onCompleted(record.callId, record.toolName)))

    def fail(
        active: ToolExecutionRecord,
        mayReplayAfterCrash: Boolean,
        errorCategory: String,
        at: Long
    ): IO[AgentError, ToolExecutionRecord] =
      transition(
        active,
        active.copy(
          status = AgentKernel.toolFailureStatus(mayReplayAfterCrash),
          updatedAtEpochMilli = at
        )
      ).tap(record => notify(_.onFailed(record.callId, record.toolName, errorCategory)))

    def settleUnknown(existing: ToolExecutionRecord, at: Long): IO[AgentError, ToolExecutionRecord] =
      transition(
        existing,
        existing.copy(status = ToolExecutionStatus.Unknown, updatedAtEpochMilli = at)
      )

    def get(runId: RunId, callId: String): IO[AgentError, Option[ToolExecutionRecord]] =
      store.getToolExecution(runId, callId)

    def getBatch(runId: RunId, batchId: String): IO[AgentError, Chunk[ToolExecutionRecord]] =
      store.getToolExecutions(runId, batchId)

    /** 期望状态与 attempt 始终取自调用方读到的那条记录，因此并发推进只会有一个赢家。 */
    private def transition(
        expected: ToolExecutionRecord,
        next: ToolExecutionRecord
    ): IO[AgentError, ToolExecutionRecord] =
      store.transitionToolExecution(expected.status, expected.attempt, next)

    private def notify(event: ToolLifecycleObserver => UIO[Unit]): UIO[Unit] =
      ZIO.foreachDiscard(observers)(observer => event(observer).catchAllCause(_ => ZIO.unit))
