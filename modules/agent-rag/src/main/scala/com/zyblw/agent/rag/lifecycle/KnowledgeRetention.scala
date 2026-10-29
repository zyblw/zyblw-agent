package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*

/** Profile/文档保留作业：小批、可中断，不在启动时全量扫库。 */
final case class KnowledgeRetentionPolicy(
    retainAfter: Duration = 14.days,
    batchSize: Int = 50,
    legalHold: Set[KnowledgeDocumentKey] = Set.empty,
    interval: Duration = 6.hours,
    maxBatchesPerCycle: Int = 10
):
  require(retainAfter > Duration.Zero, "retention window 必须为正")
  require(batchSize > 0 && batchSize <= 1000, "retention batchSize 必须位于 1..1000")
  require(interval > Duration.Zero, "retention interval 必须为正")
  require(maxBatchesPerCycle > 0 && maxBatchesPerCycle <= 100, "retention maxBatchesPerCycle 必须位于 1..100")

final class KnowledgeRetentionWorker(
    store: KnowledgeIndexStore,
    policy: KnowledgeRetentionPolicy = KnowledgeRetentionPolicy(),
    cache: Option[EmbeddingCacheStore] = None
):
  /** 撤回一个空间中一个文档的一个来源修订，并失效该租户 embedding cache。 */
  def withdraw(key: KnowledgeDocumentKey, sourceRevisionId: String): IO[RetrievalError, Unit] =
    store.withdraw(key, sourceRevisionId) *>
      cache.fold(ZIO.unit)(_.invalidateTenant(key.tenantId))

  /** 清理到期且非 active 的终态；legal-hold 文档不得进入 purge。 */
  def runOnce(now: Instant = Instant.now()): IO[RetrievalError, Long] =
    val cutoff = now.minusMillis(policy.retainAfter.toMillis)
    store.purgeInactive(cutoff, policy.batchSize, policy.legalHold)

  /** 执行一轮有界批次清理，单轮最多执行 `maxBatchesPerCycle` 批。 */
  def runCycle: IO[RetrievalError, Long] =
    Clock.instant.flatMap { now =>
      def loop(batch: Int, total: Long): IO[RetrievalError, Long] =
        if batch >= policy.maxBatchesPerCycle then ZIO.succeed(total)
        else
          runOnce(now).flatMap { purged =>
            val nextTotal = Math.addExact(total, purged)
            if purged < policy.batchSize.toLong then ZIO.succeed(nextTotal)
            else loop(batch + 1, nextTotal)
          }
      loop(0, 0L)
    }

  /** 持续运行直到父 Scope 取消。单轮异常记录告警并等待下一周期，不中断宿主。 */
  def run: UIO[Nothing] =
    (runCycle.foldZIO(
      error =>
        ZIO.logWarning(
          s"knowledge retention cycle failed: category=${error.category}, retryable=${error.retryable}"
        ),
      purged =>
        ZIO
          .logInfo(s"knowledge retention cycle purged $purged inactive document manifests")
          .when(purged > 0L)
          .unit
    ) *> ZIO.sleep(policy.interval)).forever

  /** 在当前 Scope 内启动后台保留清理 Fiber。 */
  def startScoped: URIO[Scope, Fiber.Runtime[Nothing, Nothing]] =
    run.forkScoped

object KnowledgeRetentionWorker:
  def layer(
      policy: KnowledgeRetentionPolicy = KnowledgeRetentionPolicy()
  ): URLayer[KnowledgeIndexStore, KnowledgeRetentionWorker] =
    ZLayer.fromFunction((store: KnowledgeIndexStore) => new KnowledgeRetentionWorker(store, policy, None))
