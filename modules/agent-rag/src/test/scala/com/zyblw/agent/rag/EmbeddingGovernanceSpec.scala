package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 验证 Embedding 缓存隔离、请求内去重、原子配额和幂等冲突。 */
object EmbeddingGovernanceSpec extends ZIOSpecDefault:
  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  private def provider(calls: Ref[Chunk[Chunk[String]]]): EmbeddingModel = new EmbeddingModel:
    override val capabilities: EmbeddingCapabilities       = EmbeddingDefaults.denseCapabilities(2, 100)
    override val descriptor: EmbeddingProviderDescriptorV2 =
      EmbeddingProviderDescriptorV2("test-provider", "test-model", capabilities)
    def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse] =
      calls
        .update(_ :+ request.texts)
        .as(
          EmbeddingResponse(
            request.texts.map(text => EmbeddingItem(Some(Embedding(Chunk(text.length.toFloat, 1.0f))), None)),
            descriptor,
            Some(EmbeddingUsage(request.texts.length.toLong, request.texts.length.toLong))
          )
        )

  private def req(
      context: EmbeddingRequestContext,
      texts: Chunk[String],
      role: EmbeddingInputRole = EmbeddingInputRole.Document
  ): EmbeddingRequest =
    EmbeddingRequest(texts, role, context = context)

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
    test("同租户同用途请求内去重并命中缓存，其他用途或租户不能复用向量") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        dependencies <- stores
        service = GovernedEmbeddingModel(
          provider(calls),
          dependencies._1,
          dependencies._2,
          EmbeddingQuotaPolicy(),
          GovernedEmbeddingConfig()
        )
        first <- service.embed(
          req(
            EmbeddingRequestContext(tenantA, EmbeddingPurpose.Indexing, "request-a-1"),
            Chunk("甲", "甲", "乙")
          )
        )
        second <- service.embed(
          req(
            EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "request-a-2"),
            Chunk("甲", "乙"),
            EmbeddingInputRole.Query
          )
        )
        third <- service.embed(
          req(
            EmbeddingRequestContext(tenantB, EmbeddingPurpose.Query, "request-b-1"),
            Chunk("甲"),
            EmbeddingInputRole.Query
          )
        )
        batches <- calls.get
      yield assertTrue(
        batches == Chunk(Chunk("甲", "乙"), Chunk("甲", "乙"), Chunk("甲")),
        first.denseEmbeddings.length == 3,
        second.denseEmbeddings.length == 2,
        second.usage.nonEmpty,
        third.denseEmbeddings.length == 1
      )
    },
    test("并发请求的字符配额在同一原子临界区只有一个成功") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        dependencies <- stores
        service = GovernedEmbeddingModel(
          provider(calls),
          dependencies._1,
          dependencies._2,
          EmbeddingQuotaPolicy(maxRequests = 10, maxTexts = 10, maxCharacters = 3),
          GovernedEmbeddingConfig()
        )
        results <- ZIO.collectAllPar(
          Chunk(
            service.embed(
              req(
                EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "parallel-1"),
                Chunk("甲乙"),
                EmbeddingInputRole.Query
              )
            ),
            service.embed(
              req(
                EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "parallel-2"),
                Chunk("丙丁"),
                EmbeddingInputRole.Query
              )
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
        service = GovernedEmbeddingModel(
          provider(calls),
          noCache,
          dependencies._2,
          EmbeddingQuotaPolicy(maxRequests = 1),
          GovernedEmbeddingConfig()
        )
        context = EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "stable-request")
        _        <- service.embed(req(context, Chunk("相同"), EmbeddingInputRole.Query))
        retry    <- service.embed(req(context, Chunk("相同"), EmbeddingInputRole.Query)).either
        conflict <- service.embed(req(context, Chunk("不同"), EmbeddingInputRole.Query)).either
        batches  <- calls.get
      yield assertTrue(
        retry.isRight,
        conflict.left.exists(_.category == ErrorCategory.Validation),
        batches.length == 2
      )
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
