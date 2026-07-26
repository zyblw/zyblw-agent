package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 验证 Embedding 缓存隔离、请求内去重、原子配额和幂等冲突。 */
object EmbeddingGovernanceSpec extends ZIOSpecDefault:
  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  /** 记录每次真正进入付费 Provider 的去重后文本批次。 */
  private def provider(calls: Ref[Chunk[Chunk[String]]]): EmbeddingService = new EmbeddingService:
    val dimension           = 2
    override val descriptor = EmbeddingProviderDescriptor("test-provider", "test-model", 2, 100, false)
    def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
      calls.update(_ :+ texts).as(texts.map(text => Embedding(Chunk(text.length.toFloat, 1.0f))))
    override def embedDetailed(texts: Chunk[String]): IO[RetrievalError, EmbeddingBatchResult] =
      embed(texts).map(values =>
        EmbeddingBatchResult(values, Some(EmbeddingUsage(texts.length, texts.length)))
      )

  /** 为每个测试创建独立 Cache/Quota Ref，避免窗口用量相互污染。 */
  private def stores: UIO[(EmbeddingCacheStore, EmbeddingQuotaStore)] =
    (for
      cache <- ZIO.service[EmbeddingCacheStore]
      quota <- ZIO.service[EmbeddingQuotaStore]
    yield cache -> quota).provide(
      ZLayer.make[EmbeddingCacheStore & EmbeddingQuotaStore](
        EmbeddingCacheStore.inMemory,
        EmbeddingQuotaStore.inMemory
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Embedding governance")(
    test("同租户请求内去重并命中缓存，其他租户不能观察或复用向量") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        dependencies <- stores
        service = GovernedEmbeddingService(
          provider(calls),
          dependencies._1,
          dependencies._2,
          EmbeddingQuotaPolicy(),
          GovernedEmbeddingConfig()
        )
        first <- service.embedScoped(
          EmbeddingRequestContext(tenantA, EmbeddingPurpose.Indexing, "request-a-1"),
          Chunk("甲", "甲", "乙")
        )
        second <- service.embedScoped(
          EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "request-a-2"),
          Chunk("甲", "乙")
        )
        third <- service.embedScoped(
          EmbeddingRequestContext(tenantB, EmbeddingPurpose.Query, "request-b-1"),
          Chunk("甲")
        )
        batches <- calls.get
      yield assertTrue(
        batches == Chunk(Chunk("甲", "乙"), Chunk("甲")),
        first.embeddings.length == 3,
        second.embeddings.length == 2,
        second.usage.isEmpty,
        third.embeddings.length == 1
      )
    },
    test("并发请求的字符配额在同一原子临界区只有一个成功") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        dependencies <- stores
        service = GovernedEmbeddingService(
          provider(calls),
          dependencies._1,
          dependencies._2,
          EmbeddingQuotaPolicy(maxRequests = 10, maxTexts = 10, maxCharacters = 3),
          GovernedEmbeddingConfig()
        )
        results <- ZIO.collectAllPar(
          Chunk(
            service.embedScoped(
              EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "parallel-1"),
              Chunk("甲乙")
            ),
            service.embedScoped(
              EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "parallel-2"),
              Chunk("丙丁")
            )
          ).map(_.either)
        )
        batches <- calls.get
      yield assertTrue(
        results.count(_.isRight) == 1,
        results.count(_.left.exists(_.category == ErrorCategory.RateLimit)) == 1,
        batches.length == 1
      )
    },
    test("同 requestId 同正文幂等预留，不同正文明确冲突且不会再次调用 Provider") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        dependencies <- stores
        noCache = new EmbeddingCacheStore:
          def get(keys: Chunk[EmbeddingCacheKey], now: java.time.Instant) = ZIO.succeed(Map.empty)
          def put(entries: Chunk[EmbeddingCacheEntry])                    = ZIO.unit
          def purgeExpired(now: java.time.Instant, limit: Int)            = ZIO.succeed(0L)
        service = GovernedEmbeddingService(
          provider(calls),
          noCache,
          dependencies._2,
          EmbeddingQuotaPolicy(maxRequests = 1),
          GovernedEmbeddingConfig()
        )
        context = EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "stable-request")
        _        <- service.embedScoped(context, Chunk("相同"))
        retry    <- service.embedScoped(context, Chunk("相同")).either
        conflict <- service.embedScoped(context, Chunk("不同")).either
        batches  <- calls.get
      yield assertTrue(
        retry.isRight,
        conflict.left.exists(_.category == ErrorCategory.Validation),
        batches.length == 2
      )
    },
    test("Governed 门面拒绝缺少 tenant scope 的原始 embed 调用") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        dependencies <- stores
        service = GovernedEmbeddingService(
          provider(calls),
          dependencies._1,
          dependencies._2,
          EmbeddingQuotaPolicy(),
          GovernedEmbeddingConfig()
        )
        result <- service.embed(Chunk("不能绕过")).either
      yield assertTrue(result.left.exists(_.category == ErrorCategory.Validation))
    },
    test("不同窗口长度不会共享配额，过期窗口清理会同步释放幂等 requestId") {
      for
        dependencies <- stores
        quota       = dependencies._2
        now         = java.time.Instant.parse("2026-07-15T00:00:00Z")
        context     = EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "reusable-after-purge")
        reservation = EmbeddingQuotaReservation(context, "a" * 64, 1L, 1L, 1L)
        minute  <- quota.reserve(reservation, EmbeddingQuotaPolicy(window = 1.minute), now)
        day     <- quota.usage(tenantA, EmbeddingQuotaPolicy(window = 1.day), now)
        purged  <- quota.purgeWindows(now.plusSeconds(61), 10)
        retried <- quota.reserve(
          reservation.copy(requestHash = "b" * 64),
          EmbeddingQuotaPolicy(window = 1.minute),
          now.plusSeconds(61)
        )
      yield assertTrue(
        minute.requests == 1L,
        day.requests == 0L,
        purged == 1L,
        retried.requests == 1L
      )
    }
  )
