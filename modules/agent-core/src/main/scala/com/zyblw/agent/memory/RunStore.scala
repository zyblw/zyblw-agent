package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import zio.*

/** 与 AgentState/事件同一事务写入的主模型账本变更。 */
enum ModelCallWrite:
  case Insert(record: ModelCallExecutionRecord)
  case Transition(expectedStatus: ModelCallStatus, expectedAttempt: Int, next: ModelCallExecutionRecord)

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

  /** 乐观锁保存不改变事件游标的状态。
    * @param expectedVersion
    *   调用方读取到的版本
    * @param state
    *   待保存的新状态；`lastEventSequence` 必须与已存状态相同，需要推进事件时必须使用 `commit`
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
  ): IO[StoreError, Version] =
    commit(expectedVersion, state, events, None)

  /** 在一个存储事务中同时保存状态、领域事件和可选的主模型账本。 */
  def commit(
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent],
      modelCall: Option[ModelCallWrite]
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
  ): IO[StoreError, Version] =
    commitFenced(lease, expectedVersion, state, events, None)

  def commitFenced(
      lease: RunCommandLease,
      expectedVersion: Version,
      state: AgentState,
      events: NonEmptyChunk[PersistedAgentEvent],
      modelCall: Option[ModelCallWrite]
  ): IO[StoreError, Version]

  /** 幂等重放/补齐不超过状态游标的非空事件批次；相同 EventId 且完整事件相同才可复用。不能用它推进状态游标， 新状态转换必须使用 `commit`。
    */
  def appendEvents(runId: RunId, events: NonEmptyChunk[PersistedAgentEvent]): IO[StoreError, Unit]

  /** 查询指定序号之后的事件并按 sequence 升序返回。
    *
    * @param runId
    *   目标运行
    * @param afterSequence
    *   只返回严格大于该游标的事件；`-1` 表示从 sequence 0 开始
    * @param limit
    *   单次最多返回条数，必须位于 1..4096；耐久 SSE 必须分页，不能把长 Run 的全部事件一次装入内存
    * @return
    *   有界事件页；Run 不存在或已删除时返回空页
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
    *   已有 Run 下，同一 runId、同一 batchId 且 ordinal/callId 唯一的非空记录
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

  def getModelCall(runId: RunId, requestId: ModelRequestId): IO[StoreError, Option[ModelCallExecutionRecord]]

  def getModelCalls(runId: RunId): IO[StoreError, Chunk[ModelCallExecutionRecord]]

  /** 删除 Run 及其级联数据；生产实现必须由数据库外键保证原子清理。 */
  def delete(runId: RunId): IO[StoreError, Unit]

object RunStore:
  val MaxEventPageSize = 4096

  final private case class InMemoryData(
      states: Map[RunId, AgentState] = Map.empty,
      events: Map[RunId, Vector[PersistedAgentEvent]] = Map.empty,
      cancellations: Set[RunId] = Set.empty,
      toolExecutions: Map[(RunId, String), ToolExecutionRecord] = Map.empty,
      modelCalls: Map[(RunId, ModelRequestId), ModelCallExecutionRecord] = Map.empty
  )

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
    val nonNegative   = actual.forall(_ >= 0L)
    if validRunIds && validStart && nonNegative && actual == expected &&
      actual.lastOption.contains(state.lastEventSequence)
    then ZIO.unit
    else
      ZIO.fail(
        AgentError.PersistenceFailure(
          s"事件批次不满足 Run/sequence 不变量: runId=${state.runId.asString}, sequences=${actual.mkString(",")}, stateLast=${state.lastEventSequence}"
        )
      )

  /** 校验独立追加的事件确实属于目标 Run；EventId 与 sequence 冲突由 Adapter 在原子写入时核对。 */
  def validateAppendedEvents(
      runId: RunId,
      events: NonEmptyChunk[PersistedAgentEvent]
  ): IO[StoreError, Unit] =
    if !events.forall(_.runId == runId) then
      ZIO.fail(AgentError.PersistenceFailure(s"追加事件包含不匹配的 runId: ${runId.asString}"))
    else if events.exists(_.sequence < 0L) then
      ZIO.fail(AgentError.PersistenceFailure(s"追加事件包含负 sequence: ${runId.asString}"))
    else ZIO.unit

  def validateEventPage(afterSequence: Long, limit: Int): IO[StoreError, Unit] =
    if afterSequence < -1L then ZIO.fail(AgentError.PersistenceFailure("事件查询游标不能小于 -1"))
    else if limit <= 0 || limit > MaxEventPageSize then
      ZIO.fail(AgentError.PersistenceFailure(s"事件查询 limit 必须在 1 到 $MaxEventPageSize 之间"))
    else ZIO.unit

  /** ZIO 环境访问器：加载 Run。 */
  def load(runId: RunId): ZIO[RunStore, StoreError, AgentState] = ZIO.serviceWithZIO[RunStore](_.load(runId))

  /** 测试和单进程开发实现；生产必须使用 PostgreSQL 或其他 durable adapter。 */
  val inMemory: ULayer[RunStore] = ZLayer.fromZIO {
    // ponytail: 单一状态会串行化测试 Adapter 更新；只有基准证明争用时才分片，生产始终使用 durable Adapter。
    for data <- Ref.Synchronized.make(InMemoryData())
    yield new RunStore:
      /** 单个同步 Ref 同时持有状态与事件，使创建语义与 PostgreSQL 的事务边界一致。 */
      def createWithEvents(
          state: AgentState,
          incoming: NonEmptyChunk[PersistedAgentEvent]
      ): IO[StoreError, Unit] =
        RunStore.validateEventBatch(state, incoming, requireStartAtZero = true) *>
          data.modifyZIO { current =>
            if current.states.contains(state.runId) then
              ZIO.fail(AgentError.PersistenceFailure(s"Run 已存在: ${state.runId.asString}"))
            else
              mergeEvents(current.events, state.runId, incoming).map { nextEvents =>
                () -> current.copy(
                  states = current.states.updated(state.runId, state),
                  events = nextEvents
                )
              }
          }

      def load(runId: RunId): IO[StoreError, AgentState] =
        data.get.flatMap(current =>
          ZIO.fromOption(current.states.get(runId)).orElseFail(AgentError.RunNotFound(runId))
        )

      /** `Ref.Synchronized.modifyZIO` 将比较版本和写入合并为一个原子临界区。 */
      def save(expectedVersion: Version, state: AgentState): IO[StoreError, Version] =
        data.modifyZIO { current =>
          current.states.get(state.runId) match
            case None => ZIO.fail(AgentError.RunNotFound(state.runId))
            case Some(existing) if existing.version != expectedVersion =>
              ZIO.fail(AgentError.OptimisticLock(expectedVersion, existing.version))
            case Some(existing) if existing.lastEventSequence != state.lastEventSequence =>
              ZIO.fail(
                AgentError.PersistenceFailure(
                  s"save 不能改变事件游标: runId=${state.runId.asString}, expected=${existing.lastEventSequence}, incoming=${state.lastEventSequence}"
                )
              )
            case Some(_) =>
              val next = expectedVersion.next
              ZIO.succeed(
                next -> current.copy(states = current.states.updated(state.runId, state.copy(version = next)))
              )
        }

      /** 状态 CAS、事件追加和 ModelCall ledger 在同一个同步 Ref 更新中全有或全无。 */
      def commit(
          expectedVersion: Version,
          state: AgentState,
          incoming: NonEmptyChunk[PersistedAgentEvent],
          modelCall: Option[ModelCallWrite]
      ): IO[StoreError, Version] =
        RunStore.validateEventBatch(state, incoming, requireStartAtZero = false) *>
          data.modifyZIO { current =>
            current.states.get(state.runId) match
              case None => ZIO.fail(AgentError.RunNotFound(state.runId))
              case Some(existing) if existing.version != expectedVersion =>
                ZIO.fail(AgentError.OptimisticLock(expectedVersion, existing.version))
              case Some(existing) if incoming.head.sequence != existing.lastEventSequence + 1L =>
                ZIO.fail(
                  AgentError.PersistenceFailure(
                    s"事件批次没有紧接已提交游标: runId=${state.runId.asString}, previous=${existing.lastEventSequence}, first=${incoming.head.sequence}"
                  )
                )
              case Some(_) =>
                applyModelCallWrite(current.modelCalls, modelCall).flatMap { nextModelCalls =>
                  val nextVersion = expectedVersion.next
                  mergeEvents(current.events, state.runId, incoming).map { nextEvents =>
                    nextVersion -> current.copy(
                      states = current.states.updated(state.runId, state.copy(version = nextVersion)),
                      events = nextEvents,
                      modelCalls = nextModelCalls
                    )
                  }
                }
          }

      def commitFenced(
          lease: RunCommandLease,
          expectedVersion: Version,
          state: AgentState,
          incoming: NonEmptyChunk[PersistedAgentEvent],
          modelCall: Option[ModelCallWrite]
      ): IO[StoreError, Version] =
        if lease.runId != state.runId then
          ZIO.fail(
            AgentError.LeaseLost(state.runId, lease.owner.value, lease.generation, "租约与 AgentState 不属于同一 Run")
          )
        else commit(expectedVersion, state, incoming, modelCall)

      private def applyModelCallWrite(
          current: Map[(RunId, ModelRequestId), ModelCallExecutionRecord],
          write: Option[ModelCallWrite]
      ): IO[StoreError, Map[(RunId, ModelRequestId), ModelCallExecutionRecord]] =
        write match
          case None                                => ZIO.succeed(current)
          case Some(ModelCallWrite.Insert(record)) =>
            current.get(record.runId -> record.requestId) match
              case Some(existing) if !RunStore.sameModelCallIdentity(existing, record) =>
                ZIO.fail(
                  AgentError.PersistenceFailure(
                    s"模型调用 ${record.requestId.asString} 已属于其他 fingerprint 或模型，拒绝错误复用账本"
                  )
                )
              case Some(_) => ZIO.succeed(current)
              case None    => ZIO.succeed(current.updated(record.runId -> record.requestId, record))
          case Some(ModelCallWrite.Transition(expectedStatus, expectedAttempt, next)) =>
            current.get(next.runId -> next.requestId) match
              case Some(existing)
                  if existing.status == expectedStatus &&
                    existing.attempt == expectedAttempt &&
                    RunStore.sameModelCallIdentity(existing, next) =>
                ZIO.succeed(current.updated(next.runId -> next.requestId, next))
              case _ =>
                ZIO.fail(
                  AgentError.ModelCallConflict(
                    next.runId,
                    next.requestId.asString,
                    expectedStatus.toString,
                    expectedAttempt
                  )
                )

      /** 先验证 Run 存在，再按 EventId 去重并按 sequence 排序。 */
      def appendEvents(runId: RunId, incoming: NonEmptyChunk[PersistedAgentEvent]): IO[StoreError, Unit] =
        validateAppendedEvents(runId, incoming) *>
          data.modifyZIO { current =>
            current.states.get(runId) match
              case None => ZIO.fail(AgentError.RunNotFound(runId))
              case Some(state) if incoming.exists(_.sequence > state.lastEventSequence) =>
                ZIO.fail(
                  AgentError.PersistenceFailure(
                    s"appendEvents 不能推进状态事件游标: runId=${runId.asString}, stateLast=${state.lastEventSequence}, incomingMax=${incoming.map(_.sequence).max}"
                  )
                )
              case Some(_) =>
                mergeEvents(current.events, runId, incoming).map(next => () -> current.copy(events = next))
          }

      /** 从内存事件向量中过滤游标之后的数据，并遵守与 PostgreSQL 相同的有界分页契约。 */
      def events(runId: RunId, afterSequence: Long, limit: Int): IO[StoreError, Chunk[PersistedAgentEvent]] =
        validateEventPage(afterSequence, limit) *>
          data.get.map { current =>
            Chunk.fromIterable(
              current.events
                .getOrElse(runId, Vector.empty)
                .iterator
                .filter(_.sequence > afterSequence)
                .take(limit)
                .toVector
            )
          }

      /** 将 runId 加入取消集合；集合天然保证重复请求幂等。 */
      def requestCancellation(runId: RunId): IO[StoreError, Unit] =
        data.modifyZIO { current =>
          if current.states.contains(runId) then
            ZIO.succeed(() -> current.copy(cancellations = current.cancellations + runId))
          else ZIO.fail(AgentError.RunNotFound(runId))
        }

      /** 检查取消集合，同时对未知 Run 保持一致的 RunNotFound 语义。 */
      def cancellationRequested(runId: RunId): IO[StoreError, Boolean] =
        data.get.flatMap { current =>
          if current.states.contains(runId) then ZIO.succeed(current.cancellations.contains(runId))
          else ZIO.fail(AgentError.RunNotFound(runId))
        }

      /** 在单个同步 Ref 临界区验证并插入整批 Prepared 记录。 */
      def prepareToolExecutions(records: NonEmptyChunk[ToolExecutionRecord]): IO[StoreError, Unit] =
        validateToolBatch(records) *> data.modifyZIO { current =>
          val runId = records.head.runId
          if !current.states.contains(runId) then ZIO.fail(AgentError.RunNotFound(runId))
          else
            records.collectFirst {
              case expected
                  if current.toolExecutions
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
                val next = records.foldLeft(current.toolExecutions) { (all, record) =>
                  all.updatedWith(record.runId -> record.callId) {
                    case existing @ Some(_) => existing
                    case None               => Some(record)
                  }
                }
                ZIO.succeed(() -> current.copy(toolExecutions = next))
        }

      /** status+attempt 同时匹配才允许推进，模拟 PostgreSQL 条件 UPDATE。 */
      def transitionToolExecution(
          expectedStatus: ToolExecutionStatus,
          expectedAttempt: Int,
          next: ToolExecutionRecord
      ): IO[StoreError, ToolExecutionRecord] =
        data.modifyZIO { current =>
          current.toolExecutions.get(next.runId -> next.callId) match
            case Some(existing)
                if existing.status == expectedStatus &&
                  existing.attempt == expectedAttempt &&
                  sameToolExecutionIdentity(existing, next) =>
              ZIO.succeed(
                next -> current.copy(toolExecutions =
                  current.toolExecutions.updated(next.runId -> next.callId, next)
                )
              )
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
        data.get.map(_.toolExecutions.get(runId -> callId))

      /** 过滤同一 run/batch 并按原始 ordinal 排序。 */
      def getToolExecutions(runId: RunId, batchId: String): IO[StoreError, Chunk[ToolExecutionRecord]] =
        data.get.map(current =>
          Chunk.fromIterable(
            current.toolExecutions.valuesIterator
              .filter(record => record.runId == runId && record.batchId == batchId)
              .toVector
              .sortBy(_.ordinal)
          )
        )

      def getModelCall(
          runId: RunId,
          requestId: ModelRequestId
      ): IO[StoreError, Option[ModelCallExecutionRecord]] =
        data.get.map(_.modelCalls.get(runId -> requestId))

      def getModelCalls(runId: RunId): IO[StoreError, Chunk[ModelCallExecutionRecord]] =
        data.get.map(current =>
          Chunk.fromIterable(
            current.modelCalls.valuesIterator.filter(_.runId == runId).toVector.sortBy(_.updatedAtEpochMilli)
          )
        )

      def delete(runId: RunId): IO[StoreError, Unit] =
        data.modifyZIO { current =>
          if !current.states.contains(runId) then ZIO.fail(AgentError.RunNotFound(runId))
          else
            ZIO.succeed(
              () -> current.copy(
                states = current.states - runId,
                events = current.events - runId,
                cancellations = current.cancellations - runId,
                toolExecutions =
                  current.toolExecutions.filterNot { case ((storedRunId, _), _) => storedRunId == runId },
                modelCalls = current.modelCalls.filterNot { case ((storedRunId, _), _) =>
                  storedRunId == runId
                }
              )
            )
        }
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

  def sameModelCallIdentity(existing: ModelCallExecutionRecord, expected: ModelCallExecutionRecord): Boolean =
    existing.runId == expected.runId &&
      existing.requestId == expected.requestId &&
      existing.provider == expected.provider &&
      existing.model == expected.model &&
      existing.fingerprint == expected.fingerprint &&
      existing.capturePolicy == expected.capturePolicy

  private def mergeEvents(
      all: Map[RunId, Vector[PersistedAgentEvent]],
      runId: RunId,
      incoming: NonEmptyChunk[PersistedAgentEvent]
  ): IO[StoreError, Map[RunId, Vector[PersistedAgentEvent]]] =
    val values       = incoming.toChunk.toVector
    val byId         = all.valuesIterator.flatten.map(event => event.eventId -> event).toMap
    val bySequence   = all.getOrElse(runId, Vector.empty).map(event => event.sequence -> event).toMap
    val duplicateIds = values.groupBy(_.eventId).valuesIterator.collectFirst {
      case duplicates if duplicates.distinct.size > 1 => duplicates.last
    }
    val duplicateSequences = values.groupBy(_.sequence).valuesIterator.collectFirst {
      case duplicates if duplicates.distinct.size > 1 => duplicates.last
    }
    val conflict = duplicateIds
      .orElse(duplicateSequences)
      .orElse(values.find(event => byId.get(event.eventId).exists(_ != event)))
      .orElse(values.find(event => bySequence.get(event.sequence).exists(_ != event)))
    conflict match
      case Some(conflicting) =>
        ZIO.fail(
          AgentError.PersistenceFailure(
            s"事件 ${conflicting.eventId.asString} 的 EventId 或 sequence 已属于其他事件，拒绝错误幂等复用"
          )
        )
      case None =>
        val existing = all.getOrElse(runId, Vector.empty)
        val added    = values.distinctBy(_.eventId).filterNot(event => byId.contains(event.eventId))
        ZIO.succeed(all.updated(runId, (existing ++ added).sortBy(_.sequence)))
