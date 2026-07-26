package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import zio.*

/** Durable Run SPI。实现必须保证乐观锁和事件 ID 幂等；它不是完整 Event Sourcing 接口。
  */
trait RunStore:
  /** 在同一存储事务中创建初始 Run 并写入首批事件。
    *
    * @param state
    *   初始完整状态；其 `lastEventSequence` 应与最后一个事件序号一致
    * @param events
    *   `RunCreated` 等不可缺失的首批非空领域事件
    * @return
    *   创建成功返回 Unit；状态或事件任一失败都不得留下半成品 Run
    */
  def createWithEvents(state: AgentState, events: NonEmptyChunk[PersistedAgentEvent]): IO[StoreError, Unit]

  /** 按 runId 加载最新完整状态；不存在返回 RunNotFound。 */
  def load(runId: RunId): IO[StoreError, AgentState]

  /** 乐观锁保存状态。
    * @param expectedVersion
    *   调用方读取到的版本
    * @param state
    *   待保存的新状态
    * @return
    *   成功写入后的新版本；版本不匹配返回 OptimisticLock
    */
  def save(expectedVersion: Version, state: AgentState): IO[StoreError, Version]

  /** 在一个存储事务中同时保存状态与领域事件。
    *
    * @param expectedVersion
    *   调用方读取状态时看到的版本，数据库以它实现 compare-and-set
    * @param state
    *   要写入的新状态；实现负责把版本推进一位
    * @param events
    *   与本次状态转换不可分割的非空事件批次
    * @return
    *   成功提交后的状态版本；任一步失败时状态和事件必须一起回滚
    */
  def commit(
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent]
  ): IO[StoreError, Version]

  /** 在有效租约的 fencing 保护下原子提交状态与事件。
    *
    * 生产实现必须在同一数据库事务内同时验证 owner、token、generation、未过期时间，并完成状态版本 CAS 与事件追加； 先在应用层调用 `RunCommandStore.get` 再执行普通
    * `commit` 会留下 TOCTOU 竞态，不能作为正确实现。
    *
    * @param lease
    *   当前 worker 从 claim 获得的不可伪造执行凭证，其 runId 必须与 state.runId 相同
    * @param expectedVersion
    *   worker 读取到的 AgentState 版本
    * @param state
    *   要提交的新完整状态
    * @param events
    *   与状态不可分割的连续领域事件
    * @return
    *   成功后的新版本；租约失效返回 `LeaseLost`，版本变化返回 `OptimisticLock`
    */
  def commitFenced(
      lease: RunCommandLease,
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent]
  ): IO[StoreError, Version]

  /** 幂等追加非空事件批次；相同 EventId 不得重复保存。 */
  def appendEvents(runId: RunId, events: NonEmptyChunk[PersistedAgentEvent]): IO[StoreError, Unit]

  /** 查询指定序号之后的事件并按 sequence 升序返回。
    *
    * @param runId
    *   目标运行
    * @param afterSequence
    *   只返回严格大于该游标的事件；`-1` 表示从 sequence 0 开始
    * @param limit
    *   单次最多返回条数；耐久 SSE 必须分页，不能把长 Run 的全部事件一次装入内存
    */
  def events(
      runId: RunId,
      afterSequence: Long = -1L,
      limit: Int = 512
  ): IO[StoreError, Chunk[PersistedAgentEvent]]

  /** 持久化取消意图，使其他进程或重启后的 worker 也能观察。 */
  def requestCancellation(runId: RunId): IO[StoreError, Unit]

  /** 查询是否存在取消请求。 */
  def cancellationRequested(runId: RunId): IO[StoreError, Boolean]

  /** 在任何工具 Fiber 启动前原子插入整批 Prepared pending writes；已存在记录保持原值，不能覆盖恢复结果。
    * @param records
    *   同一 runId、同一 batchId 且 ordinal/callId 唯一的非空记录
    */
  def prepareToolExecutions(records: NonEmptyChunk[ToolExecutionRecord]): IO[StoreError, Unit]

  /** 使用 status+attempt compare-and-set 推进一条工具账本。
    * @param expectedStatus
    *   调用方读取到的状态
    * @param expectedAttempt
    *   调用方读取到的尝试次数
    * @param next
    *   包含下一状态、结果和新 attempt 的完整记录
    */
  def transitionToolExecution(
      expectedStatus: ToolExecutionStatus,
      expectedAttempt: Int,
      next: ToolExecutionRecord
  ): IO[StoreError, ToolExecutionRecord]

  /** 查询工具执行账本，用于恢复时避免重复副作用。 */
  def getToolExecution(runId: RunId, callId: String): IO[StoreError, Option[ToolExecutionRecord]]

  /** 按 ordinal 查询一个批次的全部 pending writes，用于部分成功恢复。 */
  def getToolExecutions(runId: RunId, batchId: String): IO[StoreError, Chunk[ToolExecutionRecord]]

  /** 删除 Run 及其级联数据；生产实现必须由数据库外键保证原子清理。 */
  def delete(runId: RunId): IO[StoreError, Unit]

object RunStore:
  /** 校验状态快照与非空事件批次的基本不变量，防止 Adapter 把错误 runId、乱序或有缺口的事件写进数据库。
    *
    * @param state
    *   与事件一起提交的完整状态
    * @param events
    *   应连续递增且最后序号等于 `state.lastEventSequence` 的事件批次
    * @param requireStartAtZero
    *   创建 Run 时为 true，强制首事件 sequence=0
    */
  def validateEventBatch(
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent],
      requireStartAtZero: Boolean
  ): IO[StoreError, Unit] =
    val values        = events.toVector
    val actual        = values.map(_.sequence)
    val expectedStart = state.lastEventSequence - values.length + 1L
    val expected      = Vector.tabulate(values.length)(index => expectedStart + index.toLong)
    val validRunIds   = values.forall(_.runId == state.runId)
    val validStart    = !requireStartAtZero || expectedStart == 0L
    if validRunIds && validStart && actual == expected && actual.lastOption.contains(state.lastEventSequence)
    then ZIO.unit
    else
      ZIO.fail(
        AgentError.PersistenceFailure(
          s"事件批次不满足 Run/sequence 不变量: runId=${state.runId.asString}, sequences=${actual.mkString(",")}, stateLast=${state.lastEventSequence}"
        )
      )

  /** ZIO 环境访问器：加载 Run。 */
  def load(runId: RunId): ZIO[RunStore, StoreError, AgentState] = ZIO.serviceWithZIO[RunStore](_.load(runId))

  /** 测试和单进程开发实现；生产必须使用 PostgreSQL 或其他 durable adapter。 */
  val inMemory: ULayer[RunStore] = ZLayer.fromZIO {
    for
      states        <- Ref.Synchronized.make(Map.empty[RunId, AgentState])
      storedEvents  <- Ref.Synchronized.make(Map.empty[RunId, Vector[PersistedAgentEvent]])
      cancellations <- Ref.Synchronized.make(Set.empty[RunId])
      executions    <- Ref.Synchronized.make(Map.empty[(RunId, String), ToolExecutionRecord])
    yield new RunStore:
      /** 内存事件写入不会失败；整个两次 Ref 更新放入 uninterruptible 区域，避免测试 Fiber 恰好在二者之间中断。 跨进程、掉电和数据库约束下的真正事务原子性由 PostgreSQL
        * 实现保证。
        */
      def createWithEvents(
          state: AgentState,
          incoming: NonEmptyChunk[PersistedAgentEvent]
      ): IO[StoreError, Unit] =
        RunStore.validateEventBatch(state, incoming, requireStartAtZero = true) *> ZIO.uninterruptible {
          states.modifyZIO { current =>
            if current.contains(state.runId) then
              ZIO.fail(AgentError.PersistenceFailure(s"Run 已存在: ${state.runId.asString}"))
            else ZIO.succeed(((), current.updated(state.runId, state)))
          } *> storedEvents.update(_.updated(state.runId, incoming.toVector.sortBy(_.sequence)))
        }

      def load(runId: RunId): IO[StoreError, AgentState] =
        states.get.flatMap(current =>
          ZIO.fromOption(current.get(runId)).orElseFail(AgentError.RunNotFound(runId))
        )

      /** `Ref.Synchronized.modifyZIO` 将比较版本和写入合并为一个原子临界区。 */
      def save(expectedVersion: Version, state: AgentState): IO[StoreError, Version] =
        states.modifyZIO { current =>
          current.get(state.runId) match
            case None => ZIO.fail(AgentError.RunNotFound(state.runId))
            case Some(existing) if existing.version != expectedVersion =>
              ZIO.fail(AgentError.OptimisticLock(expectedVersion, existing.version))
            case Some(_) =>
              val next = expectedVersion.next
              ZIO.succeed((next, current.updated(state.runId, state.copy(version = next))))
        }

      /** 内存实现先在同步 Ref 中完成版本 CAS，再追加不会失败的内存事件。 它适合单进程测试；跨进程原子性必须由 PostgreSQL 实现提供。
        */
      def commit(
          expectedVersion: Version,
          state: AgentState,
          incoming: NonEmptyChunk[PersistedAgentEvent]
      ): IO[StoreError, Version] =
        RunStore.validateEventBatch(state, incoming, requireStartAtZero = false) *>
          ZIO.uninterruptible(save(expectedVersion, state).tap(_ => appendEvents(state.runId, incoming)))

      /** 内存 Adapter 没有与 RunCommandStore 共享同一个原子 Ref，无法真实模拟跨进程 fencing。 它仍允许测试 Runtime 的 FiberRef 传播和状态机；生产
        * fencing 正确性只由 PostgreSQL 契约测试声明。
        */
      def commitFenced(
          lease: RunCommandLease,
          expectedVersion: Version,
          state: AgentState,
          incoming: NonEmptyChunk[PersistedAgentEvent]
      ): IO[StoreError, Version] =
        if lease.runId != state.runId then
          ZIO.fail(
            AgentError.LeaseLost(state.runId, lease.owner.value, lease.generation, "租约与 AgentState 不属于同一 Run")
          )
        else commit(expectedVersion, state, incoming)

      /** 先验证 Run 存在，再按 EventId 去重并按 sequence 排序。 */
      def appendEvents(runId: RunId, incoming: NonEmptyChunk[PersistedAgentEvent]): IO[StoreError, Unit] =
        states.get.flatMap { current =>
          if !current.contains(runId) then ZIO.fail(AgentError.RunNotFound(runId))
          else
            storedEvents.update { all =>
              val existing     = all.getOrElse(runId, Vector.empty)
              val knownIds     = existing.iterator.map(_.eventId).toSet
              val deduplicated = incoming.filterNot(event => knownIds.contains(event.eventId)).toVector
              all.updated(runId, (existing ++ deduplicated).sortBy(_.sequence))
            }
        }

      /** 从内存事件向量中过滤游标之后的数据，并遵守与 PostgreSQL 相同的有界分页契约。 */
      def events(runId: RunId, afterSequence: Long, limit: Int): IO[StoreError, Chunk[PersistedAgentEvent]] =
        if limit <= 0 then ZIO.fail(AgentError.PersistenceFailure("事件查询 limit 必须为正数"))
        else
          states.get.flatMap { current =>
            if !current.contains(runId) then ZIO.fail(AgentError.RunNotFound(runId))
            else
              storedEvents.get.map(all =>
                Chunk.fromIterable(
                  all
                    .getOrElse(runId, Vector.empty)
                    .iterator
                    .filter(_.sequence > afterSequence)
                    .take(limit)
                    .toVector
                )
              )
          }

      /** 将 runId 加入取消集合；集合天然保证重复请求幂等。 */
      def requestCancellation(runId: RunId): IO[StoreError, Unit] =
        states.get.flatMap { current =>
          if current.contains(runId) then cancellations.update(_ + runId)
          else ZIO.fail(AgentError.RunNotFound(runId))
        }

      /** 检查取消集合，同时对未知 Run 保持一致的 RunNotFound 语义。 */
      def cancellationRequested(runId: RunId): IO[StoreError, Boolean] =
        states.get.flatMap { current =>
          if current.contains(runId) then cancellations.get.map(_.contains(runId))
          else ZIO.fail(AgentError.RunNotFound(runId))
        }

      /** 在单个同步 Ref 临界区验证并插入整批 Prepared 记录。 */
      def prepareToolExecutions(records: NonEmptyChunk[ToolExecutionRecord]): IO[StoreError, Unit] =
        validateToolBatch(records) *> executions.modifyZIO { current =>
          records.collectFirst {
            case expected
                if current
                  .get(expected.runId -> expected.callId)
                  .exists(existing => !sameToolExecutionIdentity(existing, expected)) =>
              expected
          } match
            case Some(conflicting) =>
              ZIO.fail(
                AgentError.PersistenceFailure(
                  s"工具 callId ${conflicting.callId} 已属于其他批次或 ordinal，拒绝错误复用账本"
                )
              )
            case None =>
              val next = records.foldLeft(current) { (all, record) =>
                all.updatedWith(record.runId -> record.callId) {
                  case existing @ Some(_) => existing
                  case None               => Some(record)
                }
              }
              ZIO.succeed(() -> next)
        }

      /** status+attempt 同时匹配才允许推进，模拟 PostgreSQL 条件 UPDATE。 */
      def transitionToolExecution(
          expectedStatus: ToolExecutionStatus,
          expectedAttempt: Int,
          next: ToolExecutionRecord
      ): IO[StoreError, ToolExecutionRecord] =
        executions.modifyZIO { current =>
          current.get(next.runId -> next.callId) match
            case Some(existing)
                if existing.status == expectedStatus &&
                  existing.attempt == expectedAttempt &&
                  sameToolExecutionIdentity(existing, next) =>
              ZIO.succeed(next -> current.updated(next.runId -> next.callId, next))
            case _ =>
              ZIO.fail(
                AgentError.ToolExecutionConflict(
                  next.runId,
                  next.callId,
                  expectedStatus.toString,
                  expectedAttempt
                )
              )
        }

      /** 按复合键查询执行记录。 */
      def getToolExecution(runId: RunId, callId: String): IO[StoreError, Option[ToolExecutionRecord]] =
        executions.get.map(_.get(runId -> callId))

      /** 过滤同一 run/batch 并按原始 ordinal 排序。 */
      def getToolExecutions(runId: RunId, batchId: String): IO[StoreError, Chunk[ToolExecutionRecord]] =
        executions.get.map(current =>
          Chunk.fromIterable(
            current.valuesIterator
              .filter(record => record.runId == runId && record.batchId == batchId)
              .toVector
              .sortBy(_.ordinal)
          )
        )

      /** 删除状态及所有分散在内存 Ref 中的关联记录。 */
      def delete(runId: RunId): IO[StoreError, Unit] =
        states.modifyZIO { current =>
          if !current.contains(runId) then ZIO.fail(AgentError.RunNotFound(runId))
          else ZIO.succeed(((), current - runId))
        } *> storedEvents.update(_ - runId) *>
          cancellations.update(_ - runId) *>
          executions.update(_.filterNot { case ((storedRunId, _), _) => storedRunId == runId })
  }

  /** 验证整批 pending writes 的归属、状态和唯一性。 */
  def validateToolBatch(records: NonEmptyChunk[ToolExecutionRecord]): IO[StoreError, Unit] =
    val values       = records.toChunk
    val first        = values.head
    val sameRunBatch = values.forall(record => record.runId == first.runId && record.batchId == first.batchId)
    val uniqueCalls  = values.map(_.callId).distinct.length == values.length
    val uniqueOrdinals = values.map(_.ordinal).distinct.length == values.length
    val allPrepared    =
      values.forall(record => record.status == ToolExecutionStatus.Prepared && record.attempt == 0)
    if sameRunBatch && uniqueCalls && uniqueOrdinals && allPrepared then ZIO.unit
    else
      ZIO.fail(AgentError.PersistenceFailure("工具批次 pending writes 不满足同 Run/批次、唯一序号或 Prepared/attempt=0 不变量"))

  /** 判断恢复时遇到的既有记录是否真属于同一个逻辑调用位置。 状态、结果、attempt 会随执行推进而变化，因此不参与比较；其余身份字段一旦写入就不得漂移。
    */
  def sameToolExecutionIdentity(existing: ToolExecutionRecord, expected: ToolExecutionRecord): Boolean =
    existing.runId == expected.runId &&
      existing.batchId == expected.batchId &&
      existing.ordinal == expected.ordinal &&
      existing.callId == expected.callId &&
      existing.toolName == expected.toolName &&
      existing.idempotencyKey == expected.idempotencyKey
