package com.zyblw.agent.sideeffects

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.json.*
import zio.json.ast.Json

/** outbox 事件的持久状态。只有 `Pending` 会被新 worker 领取。 */
enum OutboxStatus derives JsonCodec:
  /** 已随业务状态提交，等待发送。 */
  case Pending

  /** 某个发布 worker 持有有限租约，正在执行事务外网络发送。 */
  case Publishing

  /** 传输层已确认成功。 */
  case Published

  /** 达到重试上限或发生永久错误，必须人工检查。 */
  case DeadLetter

/** 业务 mutation 希望在本地事务中写入的事件草稿。
  *
  * @param destination
  *   逻辑目的地，例如 Kafka topic、内部 webhook 名称或消息总线 route；不是任意 URL
  * @param eventType
  *   稳定事件类型，例如 `article.published.v1`
  * @param aggregateType
  *   业务聚合类型，例如 `article`
  * @param aggregateId
  *   聚合稳定 ID，供同一实体顺序分区和排障
  * @param partitionKey
  *   传输层分区键；需要顺序时通常等于 aggregateId
  * @param payload
  *   经过业务筛选的事件正文，不得包含密钥或不必要的敏感数据
  * @param headers
  *   小型、非敏感传输元数据；框架会额外传递 eventId 作为 messageId
  */
final case class OutboxEventDraft(
    destination: String,
    eventType: String,
    aggregateType: String,
    aggregateId: String,
    partitionKey: String,
    payload: Json,
    headers: Map[String, String] = Map.empty
) derives JsonCodec:
  require(destination.trim.nonEmpty && destination.length <= 200, "outbox destination 必须为 1-200 个字符")
  require(eventType.trim.nonEmpty && eventType.length <= 200, "outbox eventType 必须为 1-200 个字符")
  require(aggregateType.trim.nonEmpty && aggregateType.length <= 100, "outbox aggregateType 必须为 1-100 个字符")
  require(aggregateId.trim.nonEmpty && aggregateId.length <= 200, "outbox aggregateId 必须为 1-200 个字符")
  require(partitionKey.trim.nonEmpty && partitionKey.length <= 200, "outbox partitionKey 必须为 1-200 个字符")
  require(headers.size <= 32, "outbox headers 最多 32 项")
  require(
    headers.forall((key, value) => key.nonEmpty && key.length <= 100 && value.length <= 1000),
    "outbox header 超出长度限制"
  )

/** 已经与业务状态一起提交的 outbox 事实记录。
  *
  * `attempt` 在 claim 时递增；`generation` 每次重新领取递增。payload 不应在发布重试中变化，否则相同 messageId 会代表 不同业务含义。
  */
final case class OutboxEventRecord(
    eventId: OutboxEventId,
    operationId: BusinessOperationId,
    runId: RunId,
    toolCallId: String,
    scopeKey: String,
    ordinal: Int,
    draft: OutboxEventDraft,
    status: OutboxStatus,
    attempt: Int,
    generation: Long,
    availableAt: Instant,
    lastFailure: Option[String],
    createdAt: Instant,
    publishedAt: Option[Instant]
) derives JsonCodec:
  require(toolCallId.trim.nonEmpty && scopeKey.trim.nonEmpty, "outbox 调用与作用域标识不能为空")
  require(ordinal >= 0 && attempt >= 0 && generation >= 0L, "outbox ordinal/attempt/generation 不能为负数")

/** 发布 worker 对单条 outbox 事件的有限所有权凭证。 */
final case class OutboxLease(
    event: OutboxEventRecord,
    owner: SideEffectWorkerId,
    token: SideEffectLeaseToken,
    generation: Long,
    claimedAt: Instant,
    expiresAt: Instant
):
  require(generation > 0L, "outbox lease generation 必须大于零")
  require(expiresAt.isAfter(claimedAt), "outbox lease 过期时间必须晚于领取时间")

/** outbox 调度存储 SPI。
  *
  * 这里故意没有公开 `insert`：事件只能由数据库事务适配器在业务 mutation 的同一 transaction 中创建。若业务代码可以 先提交状态、随后调用
  * `OutboxStore.insert`，框架就无法满足 transactional outbox 的核心不变量。
  */
trait OutboxStore:
  /** 非阻塞领取待发送事件并生成 fencing lease。
    *
    * @param owner
    *   当前发布 worker 的稳定实例 ID
    * @param batchSize
    *   单次最多领取数量
    * @param leaseDuration
    *   租约有效期；应显著大于 heartbeat 间隔
    * @param maxAttempts
    *   自动发送最大尝试数；达到上限的记录进入 DeadLetter
    */
  def claim(
      owner: SideEffectWorkerId,
      batchSize: Int,
      leaseDuration: Duration,
      maxAttempts: Int
  ): IO[StoreError, Chunk[OutboxLease]]

  /** 延长仍属于当前 owner/token/generation 的租约。
    * @param lease
    *   claim 返回的完整凭证
    * @param extendBy
    *   从数据库当前时间起延长的时长
    * @return
    *   新的过期时间
    */
  def heartbeat(lease: OutboxLease, extendBy: Duration): IO[StoreError, Instant]

  /** 以完整 fencing 条件把已确认发送的事件推进到 Published。 */
  def markPublished(lease: OutboxLease): IO[StoreError, Unit]

  /** 释放可重试失败的租约并重新排队。
    * @param safeFailure
    *   脱敏后的稳定错误类别，不得写入响应正文、凭据或堆栈
    * @param availableAt
    *   下一次允许领取的时间
    */
  def abandon(lease: OutboxLease, safeFailure: String, availableAt: Instant): IO[StoreError, Unit]

  /** 将永久失败或人工判定不可重试的事件推进到 DeadLetter。 */
  def deadLetter(lease: OutboxLease, safeFailure: String): IO[StoreError, Unit]

  /** 按 ID 读取完整记录，供运维诊断和人工恢复工具使用。 */
  def get(eventId: OutboxEventId): IO[StoreError, OutboxEventRecord]

  /** 按业务操作读取事件并保持 ordinal 顺序。 */
  def list(operationId: BusinessOperationId): IO[StoreError, Chunk[OutboxEventRecord]]

/** 网络传输适配器；实现可以连接 Kafka、NATS、SQS 或受控 webhook。 */
trait OutboxTransport:
  /** 发送一条已经提交的事件。
    *
    * 实现必须把 `event.eventId` 作为稳定 messageId 传给下游。成功返回只表示传输层确认；若进程在确认后、 `markPublished` 前崩溃，事件会再次发送，因此下游仍需 inbox
    * 去重。
    */
  def publish(event: OutboxEventRecord): IO[AgentError, Unit]

/** 发布 worker 的有限并发、租约和重试配置。 */
final case class OutboxPublisherConfig(
    batchSize: Int = 32,
    parallelism: Int = 8,
    leaseDuration: Duration = 30.seconds,
    heartbeatInterval: Duration = 10.seconds,
    pollInterval: Duration = 500.millis,
    retryDelay: Duration = 2.seconds,
    maxAttempts: Int = 10
):
  require(batchSize > 0 && parallelism > 0 && maxAttempts > 0, "outbox 批次、并发和尝试上限必须为正数")
  require(leaseDuration > Duration.Zero && heartbeatInterval > Duration.Zero && pollInterval > Duration.Zero)
  require(heartbeatInterval < leaseDuration, "outbox heartbeatInterval 必须小于 leaseDuration")

/** 使用 ZIO Fiber 把发送和 heartbeat 绑定为一个结构化生命周期。
  *
  * 网络发送发生在 claim transaction 结束之后，不占用数据库连接。heartbeat 失败会中断 publish Fiber；publish 完成会 中断永不自行结束的 heartbeat
  * Fiber。对外仍是 at-least-once，因为远端确认与本地 Published 更新无法处于同一事务。
  */
final class OutboxPublisher(
    store: OutboxStore,
    transport: OutboxTransport,
    owner: SideEffectWorkerId,
    config: OutboxPublisherConfig
):
  /** 领取并处理一批事件；空队列直接返回零，便于测试和宿主指标采集。 */
  def runOnce: IO[AgentError, Int] =
    store
      .claim(owner, config.batchSize, config.leaseDuration, config.maxAttempts)
      .flatMap(leases =>
        ZIO.foreachPar(leases)(publishOne).withParallelism(config.parallelism).as(leases.length)
      )

  /** 持续轮询，直到宿主 Scope 中断 worker Fiber。 */
  def run: IO[AgentError, Nothing] =
    (runOnce *> Clock.sleep(config.pollInterval)).forever

  /** 处理单个 lease，并根据错误的 retryable 分类选择重新排队或 DeadLetter。 租约已丢失时不再尝试修改事件状态，防止旧 worker 覆盖新 generation。
    */
  private def publishOne(lease: OutboxLease): IO[AgentError, Unit] =
    val heartbeat =
      (Clock.sleep(config.heartbeatInterval) *> store.heartbeat(lease, config.leaseDuration)).forever
    transport
      .publish(lease.event)
      .raceFirst(heartbeat)
      .foldZIO(
        {
          case _: AgentError.OutboxLeaseLost => ZIO.unit
          case error if error.retryable      =>
            Clock.instant.flatMap(now =>
              store.abandon(lease, error.category.toString, now.plusMillis(config.retryDelay.toMillis))
            )
          case error => store.deadLetter(lease, error.category.toString)
        },
        _ => store.markPublished(lease)
      )

object OutboxPublisher:
  /** 从 ZIO 环境装配 publisher，宿主可以替换 Store 或 Transport 进行确定性测试。 */
  def layer(
      owner: SideEffectWorkerId,
      config: OutboxPublisherConfig
  ): URLayer[OutboxStore & OutboxTransport, OutboxPublisher] =
    ZLayer.fromFunction((store: OutboxStore, transport: OutboxTransport) =>
      OutboxPublisher(store, transport, owner, config)
    )
