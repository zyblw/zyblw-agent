package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*

/** Profile/文档保留作业：小批、可中断，不在启动时全量扫库。 */
final case class KnowledgeRetentionPolicy(
    retainAfter: Duration = 14.days,
    batchSize: Int = 50,
    legalHoldDocumentIds: Set[String] = Set.empty
):
  require(retainAfter > Duration.Zero, "retention window 必须为正")
  require(batchSize > 0 && batchSize <= 1000, "retention batchSize 必须位于 1..1000")

final class KnowledgeRetentionWorker(
    store: KnowledgeIndexStore,
    policy: KnowledgeRetentionPolicy = KnowledgeRetentionPolicy(),
    cache: Option[EmbeddingCacheStore] = None
):
  /** 撤回一个空间中的一个文档修订，并失效该租户 embedding cache。 */
  def withdraw(
      key: KnowledgeDocumentKey,
      documentRevisionId: String,
      knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId("default")
  ): IO[RetrievalError, Unit] =
    store.withdraw(key, documentRevisionId, knowledgeSpaceId) *>
      cache.fold(ZIO.unit)(_.invalidateTenant(key.tenantId))

  /** 清理到期且非 active 的终态；legal-hold 文档不得进入 purge。 */
  def runOnce(now: Instant = Instant.now()): IO[RetrievalError, Long] =
    val cutoff = now.minusMillis(policy.retainAfter.toMillis)
    store.purgeInactive(cutoff, policy.batchSize, policy.legalHoldDocumentIds)
