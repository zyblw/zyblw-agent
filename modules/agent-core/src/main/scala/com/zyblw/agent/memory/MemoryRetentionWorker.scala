package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*

/** 长期记忆过期清理任务的有界执行参数。
  *
  * @param batchSize
  *   每条数据库语句最多 tombstone 的行数；PostgreSQL Adapter 使用 `FOR UPDATE SKIP LOCKED`
  * @param maxBatchesPerCycle
  *   单轮最多执行批次数，防止积压很大时 retention 独占连接池
  * @param interval
  *   两轮清理之间的固定间隔；即使上一轮失败也不会忙等
  * @param retryInitialDelay
  *   瞬时数据库错误的指数退避起点
  * @param maxRetries
  *   每一批在原始尝试之外最多重试次数；永久错误不会重试
  */
final case class MemoryRetentionConfig(
    batchSize: Int = 500,
    maxBatchesPerCycle: Int = 20,
    interval: Duration = 5.minutes,
    retryInitialDelay: Duration = 250.millis,
    maxRetries: Int = 5
):
  require(batchSize > 0, "Memory retention batchSize 必须大于零")
  require(maxBatchesPerCycle > 0, "Memory retention maxBatchesPerCycle 必须大于零")
  require(interval > Duration.Zero, "Memory retention interval 必须大于零")
  require(retryInitialDelay > Duration.Zero, "Memory retention retryInitialDelay 必须大于零")
  require(maxRetries >= 0, "Memory retention maxRetries 不能为负数")

/** 一轮 retention 的低敏摘要。
  *
  * @param cutoff
  *   本轮冻结的过期判定时间；所有批次使用同一时间，保证一次 cycle 语义确定
  * @param batches
  *   实际执行批次数
  * @param purgedRows
  *   转换为 tombstone 的总行数
  * @param reachedCycleLimit
  *   true 表示每批都满且达到 maxBatchesPerCycle，数据库可能仍有积压
  */
final case class MemoryRetentionReport(
    cutoff: Instant,
    batches: Int,
    purgedRows: Long,
    reachedCycleLimit: Boolean
):
  require(batches >= 0 && purgedRows >= 0L, "Memory retention 报告计数不能为负数")

/** Retention 的低敏观测 SPI。
  *
  * Memory 模块不能反向依赖 observability 模块，因此通过此 SPI 让宿主接入 OTel/Langfuse/指标。接口只暴露计数、 错误类别和
  * retryable，不允许正文、scope、memory key 或 SQL 进入观测出口。
  */
trait MemoryRetentionObserver:
  /** 一轮成功结束，包括清理 0 行的正常空闲周期。 */
  def completed(report: MemoryRetentionReport): UIO[Unit]

  /** 一轮在重试耗尽或永久错误后失败；worker 随后等待 interval 再进行下一轮。 */
  def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit]

object MemoryRetentionObserver:
  /** 不启用观测时的零成本实现。 */
  val noop: ULayer[MemoryRetentionObserver] = ZLayer.succeed(new MemoryRetentionObserver:
    def completed(report: MemoryRetentionReport): UIO[Unit]            = ZIO.unit
    def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] = ZIO.unit)

  /** 只输出低敏计数和错误类别的结构化日志实现。 */
  val logging: ULayer[MemoryRetentionObserver] = ZLayer.succeed(new MemoryRetentionObserver:
    def completed(report: MemoryRetentionReport): UIO[Unit] =
      ZIO.logInfo(
        s"memory-retention completed batches=${report.batches} purged=${report.purgedRows} " +
          s"cycleLimit=${report.reachedCycleLimit}"
      )
    def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] =
      ZIO.logError(s"memory-retention failed category=$category retryable=$retryable"))

/** 可跨 Worker 安全部署的长期记忆过期清理器。
  *
  * 跨进程互斥不在本地 Fiber 中伪造：生产正确性来自 PostgreSQL `purgeExpired` 的行级锁和 `SKIP LOCKED`。多个 `MemoryRetentionWorker`
  * 可以同时运行，它们领取不同过期行；本类只负责有界批处理、退避、节流和结构化取消。
  *
  * @param store
  *   提供单批原子 tombstone 的 Store；生产应使用 PostgresMemoryStore
  * @param observer
  *   低敏观测出口
  * @param config
  *   批次、周期和重试参数
  */
final class MemoryRetentionWorker(
    store: MemoryStore,
    observer: MemoryRetentionObserver,
    config: MemoryRetentionConfig
):

  /** 执行一轮有界清理。
    *
    * cutoff 只读取一次，避免长 cycle 中刚刚到期的行进入同一轮并造成不可复现的批次数。若某批返回少于 batchSize，说明当前快照没有更多可领取行，立即结束；若每批都满，则在
    * maxBatchesPerCycle 后主动让出资源。
    */
  def runOnce: IO[StoreError, MemoryRetentionReport] =
    Clock.instant.flatMap(cutoff => loop(cutoff, batch = 0, total = 0L))

  /** 持续运行直到父 Fiber/Scope 取消。
    *
    * 单轮失败被观测后等待 interval，不会导致整个 WorkerHost 进程退出，也不会立即热循环。中断 Cause 不会被 `catchAll` 当作普通 StoreError 吞掉，所以应用关闭能沿
    * ZIO 结构化并发迅速传播。
    */
  def run: UIO[Nothing] =
    (runOnce.foldZIO(
      error => observer.failed(error.category, error.retryable),
      observer.completed
    ) *> ZIO.sleep(config.interval)).forever

  /** 把后台 Fiber 绑定到调用方 Scope。Scope 关闭时 ZIO 会中断 sleep 或正在执行的 JDBC effect，并等待 finalizer。
    */
  def startScoped: URIO[Scope, Fiber.Runtime[Nothing, Nothing]] = run.forkScoped

  /** 递归只受 maxBatchesPerCycle 限制，不依赖数据规模，因此不会无限占用单轮执行。 */
  private def loop(cutoff: Instant, batch: Int, total: Long): IO[StoreError, MemoryRetentionReport] =
    if batch >= config.maxBatchesPerCycle then
      ZIO.succeed(MemoryRetentionReport(cutoff, batch, total, reachedCycleLimit = true))
    else
      purgeBatch(cutoff).flatMap { purged =>
        val nextBatch = batch + 1
        val nextTotal = Math.addExact(total, purged)
        if purged < config.batchSize.toLong then
          ZIO.succeed(MemoryRetentionReport(cutoff, nextBatch, nextTotal, reachedCycleLimit = false))
        else loop(cutoff, nextBatch, nextTotal)
      }

  /** 只对 retryable StoreError 应用指数退避和抖动；校验、授权、CAS 等永久错误第一次失败就返回。
    */
  private def purgeBatch(cutoff: Instant): IO[StoreError, Long] =
    val retryableOnly = Schedule.recurWhile[StoreError](_.retryable)
    val backoff       = Schedule.exponential(config.retryInitialDelay).jittered
    val attempts      = Schedule.recurs(config.maxRetries)
    store.purgeExpired(cutoff.toEpochMilli, config.batchSize).retry(retryableOnly && backoff && attempts)

object MemoryRetentionWorker:
  /** 从显式配置和 ZIO 环境中的 Store/Observer 装配 Worker。 */
  def layer(
      config: MemoryRetentionConfig
  ): URLayer[MemoryStore & MemoryRetentionObserver, MemoryRetentionWorker] =
    ZLayer.fromFunction((store: MemoryStore, observer: MemoryRetentionObserver) =>
      MemoryRetentionWorker(store, observer, config)
    )
