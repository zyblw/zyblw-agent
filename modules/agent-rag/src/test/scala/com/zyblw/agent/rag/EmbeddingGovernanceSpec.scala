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
        _               <- service.embed(req(context, Chunk("相同"), EmbeddingInputRole.Query))
        retry           <- service.embed(req(context, Chunk("相同"), EmbeddingInputRole.Query)).either
        conflict        <- service.embed(req(context, Chunk("不同"), EmbeddingInputRole.Query)).either
        diffInstruction <- service
          .embed(
            req(context, Chunk("相同"), EmbeddingInputRole.Query).copy(
              instruction = Some(EmbeddingInstruction("retrieval-zh", "v2", "检索中医经典"))
            )
          )
          .either
        diffSpace <- service
          .embed(
            req(
              context.copy(knowledgeSpaceId = Some(KnowledgeSpaceId("space-other"))),
              Chunk("相同"),
              EmbeddingInputRole.Query
            )
          )
          .either
        diffPerm <- service
          .embed(
            req(
              context.copy(permissionFingerprint = "perm-other"),
              Chunk("相同"),
              EmbeddingInputRole.Query
            )
          )
          .either
        batches <- calls.get
      yield assertTrue(
        retry.isRight,
        conflict.left.exists(_.category == ErrorCategory.Validation),
        diffInstruction.left.exists(_.category == ErrorCategory.Validation),
        diffSpace.left.exists(_.category == ErrorCategory.Validation),
        diffPerm.left.exists(_.category == ErrorCategory.Validation),
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
    },
    test("GovernedEmbeddingModel 将底层声称的 Sparse 能力收窄为仅 Dense，并在调用 delegate 前拒绝 DenseAndSparse 请求") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        dependencies <- stores
        hybridDelegate = new EmbeddingModel:
          override val capabilities: EmbeddingCapabilities =
            EmbeddingDefaults
              .denseCapabilities(2, 100)
              .copy(outputs = Set(EmbeddingOutputKind.Dense, EmbeddingOutputKind.DenseAndSparse))
          override val descriptor: EmbeddingProviderDescriptorV2 =
            EmbeddingProviderDescriptorV2("test-provider", "hybrid-model", capabilities)
          def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse] =
            calls.update(_ :+ request.texts) *> ZIO.fail(AgentError.RetrievalFailed("不应进入底层 delegate"))
        service = GovernedEmbeddingModel(
          hybridDelegate,
          dependencies._1,
          dependencies._2,
          EmbeddingQuotaPolicy(),
          GovernedEmbeddingConfig()
        )
        rejected <- service
          .embed(
            EmbeddingRequest(
              Chunk("中医辨证"),
              EmbeddingInputRole.Query,
              output = EmbeddingOutputKind.DenseAndSparse,
              context =
                EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "reject-sparse-before-delegate")
            )
          )
          .either
        recordedCalls <- calls.get
      yield assertTrue(
        service.capabilities.outputs == Set(EmbeddingOutputKind.Dense),
        service.descriptor.capabilities.outputs == Set(EmbeddingOutputKind.Dense),
        rejected.isLeft,
        recordedCalls.isEmpty
      )
    },
    test("长 UUID 租户与知识空间隔离维度的 keyVersion 长度固定不超过 100 且任意维度变化指纹不同") {
      for
        calls      <- Ref.make(Chunk.empty[Chunk[String]])
        captured   <- Ref.make(Chunk.empty[EmbeddingCacheEntry])
        (_, quota) <- stores
        capturingCache = new EmbeddingCacheStore:
          def get(keys: Chunk[EmbeddingCacheKey], now: java.time.Instant) = ZIO.succeed(Map.empty)
          def put(entries: Chunk[EmbeddingCacheEntry])                    = captured.update(_ ++ entries)
          def purgeExpired(now: java.time.Instant, limit: Int)            = ZIO.succeed(0L)
        service = GovernedEmbeddingModel(
          provider(calls),
          capturingCache,
          quota,
          EmbeddingQuotaPolicy(),
          GovernedEmbeddingConfig()
        )
        baseCtx = EmbeddingRequestContext(
          tenantId = TenantId("550e8400-e29b-41d4-a716-446655440000"),
          purpose = EmbeddingPurpose.Query,
          requestId = "req-1",
          knowledgeSpaceId = Some(KnowledgeSpaceId("6ba7b810-9dad-11d1-80b4-00c04fd430c8")),
          permissionFingerprint = "perm-sha256-abcdef0123456789abcdef0123456789abcdef0123456789"
        )
        diffSpaceCtx = baseCtx.copy(
          requestId = "req-2",
          knowledgeSpaceId = Some(KnowledgeSpaceId("6ba7b810-9dad-11d1-80b4-00c04fd430c9"))
        )
        _       <- service.embed(req(baseCtx, Chunk("测试"), EmbeddingInputRole.Query))
        _       <- service.embed(req(diffSpaceCtx, Chunk("测试"), EmbeddingInputRole.Query))
        entries <- captured.get
        key1 = entries(0).key
        key2 = entries(1).key
      yield assertTrue(
        entries.length == 2,
        key1.keyVersion.length <= 100,
        key2.keyVersion.length <= 100,
        key1.keyVersion != key2.keyVersion
      )
    },
    test("原样重试前其他请求填充部分缓存不会导致配额幂等哈希冲突") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        shouldFail   <- Ref.make(true)
        dependencies <- stores
        flakyDelegate = new EmbeddingModel:
          override val capabilities: EmbeddingCapabilities       = EmbeddingDefaults.denseCapabilities(2, 100)
          override val descriptor: EmbeddingProviderDescriptorV2 =
            EmbeddingProviderDescriptorV2("test-provider", "test-model", capabilities)
          def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse] =
            shouldFail.get.flatMap { failNow =>
              if failNow && request.context.requestId == "retry-request-id" then
                shouldFail.set(false) *> ZIO.fail(AgentError.RetrievalFailed("transient provider timeout"))
              else provider(calls).embed(request)
            }
        service = GovernedEmbeddingModel(
          flakyDelegate,
          dependencies._1,
          dependencies._2,
          EmbeddingQuotaPolicy(maxRequests = 5, maxTexts = 10, maxCharacters = 100),
          GovernedEmbeddingConfig()
        )
        retryCtx = EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "retry-request-id")
        otherCtx = EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "other-request-id")
        firstAttempt <- service
          .embed(req(retryCtx, Chunk("甲", "乙"), EmbeddingInputRole.Query))
          .either
        _             <- service.embed(req(otherCtx, Chunk("甲"), EmbeddingInputRole.Query))
        secondAttempt <- service
          .embed(req(retryCtx, Chunk("甲", "乙"), EmbeddingInputRole.Query))
          .either
        batches <- calls.get
      yield assertTrue(
        firstAttempt.isLeft,
        secondAttempt.isRight,
        secondAttempt.toOption.exists(_.denseEmbeddings.length == 2),
        batches == Chunk(Chunk("甲"), Chunk("乙"))
      )
    },
    test("FailOpen 模式下缓存读写异常递增 cacheDegradedCount 且不阻塞主链路") {
      for
        calls        <- Ref.make(Chunk.empty[Chunk[String]])
        dependencies <- stores
        brokenCache = new EmbeddingCacheStore:
          def get(keys: Chunk[EmbeddingCacheKey], now: java.time.Instant) =
            ZIO.fail(AgentError.RetrievalFailed("cache read timeout"))
          def put(entries: Chunk[EmbeddingCacheEntry]) =
            ZIO.fail(AgentError.RetrievalFailed("cache write timeout"))
          def purgeExpired(now: java.time.Instant, limit: Int) = ZIO.succeed(0L)
        service = GovernedEmbeddingModel(
          provider(calls),
          brokenCache,
          dependencies._2,
          EmbeddingQuotaPolicy(),
          GovernedEmbeddingConfig(cacheFailureMode = CacheFailureMode.FailOpen)
        )
        response <- service.embed(
          req(
            EmbeddingRequestContext(tenantA, EmbeddingPurpose.Query, "degraded-cache-req"),
            Chunk("甲"),
            EmbeddingInputRole.Query
          )
        )
        degraded <- service.cacheDegradedCount
      yield assertTrue(
        response.denseEmbeddings.length == 1,
        degraded == 2L
      )
    }
  )
