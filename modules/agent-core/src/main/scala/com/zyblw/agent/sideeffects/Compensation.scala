package com.zyblw.agent.sideeffects

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 补偿计划的耐久状态。注册并不等于执行，必须由明确失败策略或人工操作激活。 */
enum CompensationStatus derives JsonCodec:
  /** 随原业务事务保存，但尚未决定执行。 */
  case Registered

  /** 已明确激活，等待补偿 worker。 */
  case Pending

  /** 某个 worker 正在执行。 */
  case Running

  /** 补偿 handler 已确认完成。 */
  case Succeeded

  /** 原操作已经达到最终目标，无需补偿。 */
  case Cancelled

  /** 达到尝试上限或发生永久失败，需要人工处理。 */
  case DeadLetter

/** 原业务 mutation 同事务保存的补偿计划草稿。
  *
  * @param handlerName
  *   业务注册表中的窄补偿 handler，例如 `unpublish-article-v1`
  * @param payload
  *   执行补偿所需的最小快照或稳定资源引用；不能依赖模型再次自由推理
  */
final case class CompensationDraft(handlerName: String, payload: Json) derives JsonCodec:
  require(handlerName.trim.nonEmpty && handlerName.length <= 200, "补偿 handlerName 必须为 1-200 个字符")

/** 一条已经持久化的显式补偿计划。 */
final case class CompensationRecord(
    compensationId: CompensationId,
    operationId: BusinessOperationId,
    runId: RunId,
    scopeKey: String,
    draft: CompensationDraft,
    status: CompensationStatus,
    attempt: Int,
    generation: Long,
    availableAt: Instant,
    lastFailure: Option[String],
    createdAt: Instant,
    completedAt: Option[Instant]
) derives JsonCodec:
  require(scopeKey.trim.nonEmpty, "补偿 scopeKey 不能为空")
  require(attempt >= 0 && generation >= 0L, "补偿 attempt/generation 不能为负数")

/** 补偿 worker 的 fencing 租约。 */
final case class CompensationLease(
    record: CompensationRecord,
    owner: SideEffectWorkerId,
    token: SideEffectLeaseToken,
    generation: Long,
    claimedAt: Instant,
    expiresAt: Instant
):
  require(generation > 0L && expiresAt.isAfter(claimedAt), "补偿 lease 时间或 generation 非法")

/** 业务补偿实现。
  *
  * handler 必须是确定、窄权限、可幂等重试的应用代码；模型不能动态生成 handlerName 或把补偿解释为重新执行任意工具。
  */
trait CompensationHandler:
  /** 与 `CompensationDraft.handlerName` 精确匹配的稳定名称。 */
  def name: String

  /** 执行补偿。
    * @param record
    *   包含原 operation/run/scope 和持久 payload 的完整计划
    * @return
    *   只有业务已经达到补偿后状态时才成功
    */
  def compensate(record: CompensationRecord): IO[AgentError, Unit]

/** 只读补偿 handler 注册表；重复名称在装配时被拒绝。 */
trait CompensationRegistry:
  /** 按稳定名称查找 handler。 */
  def get(name: String): IO[AgentError, CompensationHandler]

object CompensationRegistry:
  /** 构建注册表。
    * @param handlers
    *   宿主显式允许的补偿实现
    * @return
    *   名称重复时以 InvalidConfiguration 失败，避免最后写入者静默覆盖
    */
  def make(
      handlers: Iterable[CompensationHandler]
  ): IO[AgentError.InvalidConfiguration, CompensationRegistry] =
    val values     = handlers.toList
    val duplicates =
      values.groupBy(_.name).collect { case (name, same) if same.size > 1 => name }.toList.sorted
    if duplicates.nonEmpty then
      ZIO.fail(AgentError.InvalidConfiguration(s"重复补偿 handler: ${duplicates.mkString(",")}"))
    else
      val byName = values.map(handler => handler.name -> handler).toMap
      ZIO.succeed(
        new CompensationRegistry:
          def get(name: String): IO[AgentError, CompensationHandler] =
            ZIO
              .fromOption(byName.get(name))
              .orElseFail(AgentError.InvalidConfiguration(s"未注册补偿 handler: $name"))
      )

/** 补偿计划的激活、领取和状态推进 SPI。 */
trait CompensationStore:
  /** 将 Registered 计划显式激活；重复激活 Pending/Running/Succeeded 时保持幂等。 */
  def activate(compensationId: CompensationId, availableAt: Instant): IO[StoreError, CompensationRecord]

  /** 当原操作已经达到目标时取消未激活计划。 */
  def cancel(compensationId: CompensationId): IO[StoreError, CompensationRecord]

  /** 领取已激活计划；实现必须使用 token/generation fencing。 */
  def claim(
      owner: SideEffectWorkerId,
      batchSize: Int,
      leaseDuration: Duration,
      maxAttempts: Int
  ): IO[StoreError, Chunk[CompensationLease]]

  /** 延长补偿 lease。 */
  def heartbeat(lease: CompensationLease, extendBy: Duration): IO[StoreError, Instant]

  /** 标记补偿成功。 */
  def complete(lease: CompensationLease): IO[StoreError, Unit]

  /** 可重试失败重新排队。 */
  def abandon(lease: CompensationLease, safeFailure: String, availableAt: Instant): IO[StoreError, Unit]

  /** 永久失败进入 DeadLetter。 */
  def deadLetter(lease: CompensationLease, safeFailure: String): IO[StoreError, Unit]

  /** 查询单条计划。 */
  def get(compensationId: CompensationId): IO[StoreError, CompensationRecord]

/** 补偿 worker 参数；补偿默认低并发，避免同时放大故障。 */
final case class CompensationWorkerConfig(
    batchSize: Int = 8,
    parallelism: Int = 2,
    leaseDuration: Duration = 60.seconds,
    heartbeatInterval: Duration = 15.seconds,
    pollInterval: Duration = 1.second,
    retryDelay: Duration = 5.seconds,
    maxAttempts: Int = 5
):
  require(batchSize > 0 && parallelism > 0 && maxAttempts > 0)
  require(heartbeatInterval > Duration.Zero && heartbeatInterval < leaseDuration)

/** 结构化执行已经激活的补偿计划。 */
final class CompensationWorker(
    store: CompensationStore,
    registry: CompensationRegistry,
    owner: SideEffectWorkerId,
    config: CompensationWorkerConfig
):
  /** 处理一批计划并返回领取数量。 */
  def runOnce: IO[AgentError, Int] =
    store.claim(owner, config.batchSize, config.leaseDuration, config.maxAttempts).flatMap { leases =>
      ZIO.foreachPar(leases)(executeOne).withParallelism(config.parallelism).as(leases.length)
    }

  /** 持续轮询，直到宿主中断 Fiber。 */
  def run: IO[AgentError, Nothing] = (runOnce *> Clock.sleep(config.pollInterval)).forever

  /** 查找固定 handler，在 heartbeat 保护下执行，并根据 typed error 分类重试。 */
  private def executeOne(lease: CompensationLease): IO[AgentError, Unit] =
    val heartbeat =
      (Clock.sleep(config.heartbeatInterval) *> store.heartbeat(lease, config.leaseDuration)).forever
    registry
      .get(lease.record.draft.handlerName)
      .flatMap(_.compensate(lease.record))
      .raceFirst(heartbeat)
      .foldZIO(
        {
          // 旧 worker 已被新的 generation 抢占时没有任何状态写权限；新 owner 会按存储中的事实继续处理。
          case _: AgentError.CompensationLeaseLost => ZIO.unit
          case error if error.retryable            =>
            Clock.instant.flatMap(now =>
              store.abandon(lease, error.category.toString, now.plusMillis(config.retryDelay.toMillis))
            )
          case error => store.deadLetter(lease, error.category.toString)
        },
        _ => store.complete(lease)
      )
