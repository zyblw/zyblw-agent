package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 长期记忆 CAS、tombstone、过期清理和治理规则的确定性 ZIO Test。 */
object MemoryLifecycleSpec extends ZIOSpecDefault:

  /** 创建最小结构化记忆；测试按需 copy 证据、类型和过期时间。 */
  private def entry(key: String, value: String): MemoryEntry = MemoryEntry(
    key,
    Json.Str(value),
    importance = 0.8,
    sourceRunId = None,
    createdAtEpochMilli = 0L,
    expiresAtEpochMilli = None
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Memory lifecycle")(
    test("CAS 版本阻止陈旧覆盖，删除 tombstone 阻止迟到 worker 复活") {
      (for
        store <- ZIO.service[MemoryStore]
        scope = MemoryScope.User(TenantId("tenant-a"), UserId("user-a"))
        created              <- store.compareAndSet(scope, 0L, entry("learning.classic", "伤寒论"))
        stale                <- store.compareAndSet(scope, 0L, entry("learning.classic", "黄帝内经")).exit
        _                    <- store.delete(scope, "learning.classic")
        absent               <- store.get(scope, "learning.classic")
        reviveWithOldVersion <- store
          .compareAndSet(
            scope,
            created.version,
            entry("learning.classic", "黄帝内经")
          )
          .exit
      yield assertTrue(
        created.version == 1L,
        stale.isFailure,
        absent.isEmpty,
        reviveWithOldVersion.isFailure
      )).provide(MemoryStore.inMemory)
    },
    test("过期清理按 limit 分批 tombstone 且不返回过期内容") {
      (for
        store <- ZIO.service[MemoryStore]
        scope = MemoryScope.Tenant(TenantId("tenant-a"))
        _ <- store.put(
          scope,
          entry("event.one", "一").copy(
            kind = MemoryKind.Episodic,
            expiresAtEpochMilli = Some(100L)
          )
        )
        _ <- store.put(
          scope,
          entry("event.two", "二").copy(
            kind = MemoryKind.Episodic,
            expiresAtEpochMilli = Some(100L)
          )
        )
        first  <- store.purgeExpired(100L, 1)
        second <- store.purgeExpired(100L, 10)
        listed <- store.list(scope, 10)
      yield assertTrue(first == 1L, second == 1L, listed.isEmpty)).provide(MemoryStore.inMemory)
    },
    test("治理拒绝低置信/敏感推断和无过期情节，强证据胜出且删除需授权") {
      (for
        store <- ZIO.service[MemoryStore]
        scope     = MemoryScope.User(TenantId("tenant-a"), UserId("user-a"))
        lifecycle = MemoryLifecycle(
          new MemoryExtractor:
            def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
              ZIO.succeed(Chunk.empty)
          ,
          store,
          MemoryGovernancePolicy()
        )
        candidates = Chunk(
          MemoryCandidate(0, MemoryMutation.Upsert(entry("learning.goal", "学习伤寒论"))),
          MemoryCandidate(
            1,
            MemoryMutation.Upsert(
              entry("learning.goal", "模型猜测").copy(
                evidence = MemoryEvidence.ModelInferred,
                confidence = 0.95,
                updatedAtEpochMilli = 10L
              )
            )
          ),
          MemoryCandidate(
            2,
            MemoryMutation.Upsert(
              entry("guess.low", "低置信").copy(
                evidence = MemoryEvidence.ModelInferred,
                confidence = 0.2
              )
            )
          ),
          MemoryCandidate(
            3,
            MemoryMutation.Upsert(
              entry("health.private", "敏感推断").copy(
                evidence = MemoryEvidence.ModelInferred,
                confidence = 0.99,
                sensitivity = MemorySensitivity.Sensitive
              )
            )
          ),
          MemoryCandidate(
            4,
            MemoryMutation.Upsert(
              entry("event.no-expiry", "临时事件").copy(
                kind = MemoryKind.Episodic
              )
            )
          ),
          MemoryCandidate(5, MemoryMutation.Delete("learning.goal", MemoryEvidence.ModelInferred)),
          MemoryCandidate(6, MemoryMutation.Delete("learning.goal", MemoryEvidence.UserStated))
        )
        report <- lifecycle.applyCandidates(scope, candidates)
        absent <- store.get(scope, "learning.goal")
      yield assertTrue(
        report.received == 7,
        report.written == 1,
        report.ignored == 1,
        report.deleted == 1,
        report.rejected.map(_._2).toSet == Set(
          "model-confidence-too-low",
          "model-sensitive-memory-forbidden",
          "episodic-expiry-required",
          "delete-evidence-not-authorized"
        ),
        absent.isEmpty
      )).provide(MemoryStore.inMemory)
    },
    test("用户手动修正锁定项不被后台提炼覆盖，同证据等级的新认知可随时间演进") {
      (for
        store <- ZIO.service[MemoryStore]
        scope     = MemoryScope.User(TenantId("tenant-a"), UserId("user-a"))
        lifecycle = MemoryLifecycle(
          new MemoryExtractor:
            def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
              ZIO.succeed(Chunk.empty)
          ,
          store,
          MemoryGovernancePolicy()
        )
        _ <- store.compareAndSet(
          scope,
          0L,
          entry("scholarly.locked", "只读伤寒论原文").copy(
            extractorVersion = MemoryGovernanceService.UserCorrectionVersion,
            updatedAtEpochMilli = 10L
          )
        )
        _ <- store.compareAndSet(
          scope,
          0L,
          entry("scholarly.evolving", "早期偏好温病条辨").copy(
            extractorVersion = "llm-tool-v1",
            confidence = 0.95,
            updatedAtEpochMilli = 10L
          )
        )
        report <- lifecycle.applyCandidates(
          scope,
          Chunk(
            MemoryCandidate(
              0,
              MemoryMutation.Upsert(
                entry("scholarly.locked", "AI自动覆盖尝试").copy(
                  extractorVersion = "llm-tool-v1",
                  confidence = 1.0,
                  updatedAtEpochMilli = 20L
                )
              )
            ),
            MemoryCandidate(
              1,
              MemoryMutation.Upsert(
                entry("scholarly.evolving", "近期转向伤寒与温病汇通").copy(
                  extractorVersion = "llm-tool-v1",
                  confidence = 0.90,
                  updatedAtEpochMilli = 20L
                )
              )
            )
          )
        )
        locked   <- store.get(scope, "scholarly.locked")
        evolving <- store.get(scope, "scholarly.evolving")
      yield assertTrue(
        report.written == 1,
        report.ignored == 1,
        locked.exists(_.value == Json.Str("只读伤寒论原文")),
        locked.exists(_.version == 1L),
        evolving.exists(_.value == Json.Str("近期转向伤寒与温病汇通")),
        evolving.exists(_.version == 2L)
      )).provide(MemoryStore.inMemory)
    },
    test("删除后的 tombstone 对 ModelInferred 保持静默跳过且不中断同批写入，对新 UserStated 支持重建") {
      (for
        store <- ZIO.service[MemoryStore]
        scope     = MemoryScope.User(TenantId("tenant-a"), UserId("user-a"))
        lifecycle = MemoryLifecycle(
          new MemoryExtractor:
            def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
              ZIO.succeed(Chunk.empty)
          ,
          store,
          MemoryGovernancePolicy()
        )
        _       <- store.put(scope, entry("deleted.topic", "旧内容"))
        _       <- store.delete(scope, "deleted.topic")
        report1 <- lifecycle.applyCandidates(
          scope,
          Chunk(
            MemoryCandidate(
              0,
              MemoryMutation.Upsert(
                entry("deleted.topic", "模型猜测不应复活已删键").copy(
                  evidence = MemoryEvidence.ModelInferred,
                  confidence = 0.92
                )
              )
            ),
            MemoryCandidate(
              1,
              MemoryMutation.Upsert(
                entry("fresh.topic", "同批其他新键正常落库")
              )
            )
          )
        )
        afterInferred <- store.get(scope, "deleted.topic")
        fresh         <- store.get(scope, "fresh.topic")
        report2       <- lifecycle.applyCandidates(
          scope,
          Chunk(
            MemoryCandidate(
              0,
              MemoryMutation.Upsert(
                entry("deleted.topic", "用户再次明确表达的新偏好").copy(
                  evidence = MemoryEvidence.UserStated,
                  confidence = 1.0
                )
              )
            )
          )
        )
        afterUserStated <- store.get(scope, "deleted.topic")
      yield assertTrue(
        report1.written == 1,
        report1.ignored == 1,
        afterInferred.isEmpty,
        fresh.exists(_.value == Json.Str("同批其他新键正常落库")),
        report2.written == 1,
        afterUserStated.exists(_.value == Json.Str("用户再次明确表达的新偏好"))
      )).provide(MemoryStore.inMemory)
    },
    test("删除屏障：单条删除或 deleteScope 发生在提炼期间时，迟到 UserStated 任务不得复活旧键或新增旧批次键") {
      (for
        store <- ZIO.service[MemoryStore]
        scope = MemoryScope.User(TenantId("tenant-a"), UserId("user-barrier"))
        _               <- store.put(scope, entry("existing.key", "初始值"))
        extractStarted1 <- Promise.make[Nothing, Unit]
        releaseExtract1 <- Promise.make[Nothing, Unit]
        slowExtractor1 = new MemoryExtractor:
          def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
            extractStarted1.succeed(()) *> releaseExtract1.await.as(
              Chunk(
                MemoryCandidate(
                  0,
                  MemoryMutation.Upsert(
                    entry("existing.key", "删除前慢提炼试图复活").copy(
                      evidence = MemoryEvidence.UserStated,
                      confidence = 1.0
                    )
                  )
                ),
                MemoryCandidate(
                  1,
                  MemoryMutation.Upsert(
                    entry("predeleted.unborn", "删除前尚未落库但已被点名删除").copy(
                      evidence = MemoryEvidence.UserStated,
                      confidence = 1.0
                    )
                  )
                )
              )
            )
        lifecycle1 = MemoryLifecycle(slowExtractor1, store, MemoryGovernancePolicy())
        runId1              <- RunId.random
        fiber1              <- lifecycle1.capture(scope, Chunk(AgentMessage.user("旧消息")), runId1).fork
        _                   <- extractStarted1.await
        _                   <- store.delete(scope, "existing.key")
        _                   <- store.delete(scope, "predeleted.unborn")
        _                   <- releaseExtract1.succeed(())
        report1             <- fiber1.join
        existingAfterSingle <- store.get(scope, "existing.key")
        unbornAfterSingle   <- store.get(scope, "predeleted.unborn")
        // 再验证 deleteScope 屏障阻止删除前慢任务插入全新 key (expectedVersion = 0)
        _               <- store.put(scope, entry("before.scope.clear", "将被整域清空"))
        extractStarted2 <- Promise.make[Nothing, Unit]
        releaseExtract2 <- Promise.make[Nothing, Unit]
        slowExtractor2 = new MemoryExtractor:
          def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
            extractStarted2.succeed(()) *> releaseExtract2.await.as(
              Chunk(
                MemoryCandidate(
                  0,
                  MemoryMutation.Upsert(
                    entry("brand.new.key", "清空前慢任务产生的新键").copy(
                      evidence = MemoryEvidence.UserStated,
                      confidence = 1.0
                    )
                  )
                )
              )
            )
        lifecycle2 = MemoryLifecycle(slowExtractor2, store, MemoryGovernancePolicy())
        runId2                <- RunId.random
        fiber2                <- lifecycle2.capture(scope, Chunk(AgentMessage.user("清空前消息")), runId2).fork
        _                     <- extractStarted2.await
        cleared               <- store.deleteScope(scope)
        _                     <- releaseExtract2.succeed(())
        report2               <- fiber2.join
        listedAfterScopeClear <- store.list(scope, 10)
        // 删除后新发起的提炼允许按策略正常写入
        freshExtractor = new MemoryExtractor:
          def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
            ZIO.succeed(
              Chunk(
                MemoryCandidate(
                  0,
                  MemoryMutation.Upsert(
                    entry("existing.key", "删除后新对话重新确认").copy(
                      evidence = MemoryEvidence.UserStated,
                      confidence = 1.0
                    )
                  )
                )
              )
            )
        lifecycle3 = MemoryLifecycle(freshExtractor, store, MemoryGovernancePolicy())
        runId3     <- RunId.random
        report3    <- lifecycle3.capture(scope, Chunk(AgentMessage.user("新消息")), runId3)
        revivedNew <- store.get(scope, "existing.key")
      yield assertTrue(
        report1.written == 0,
        report1.ignored == 2,
        existingAfterSingle.isEmpty,
        unbornAfterSingle.isEmpty,
        cleared == 1L,
        report2.written == 0,
        report2.ignored == 1,
        listedAfterScopeClear.isEmpty,
        report3.written == 1,
        revivedNew.exists(_.value == Json.Str("删除后新对话重新确认"))
      )).provide(MemoryStore.inMemory)
    },
    test("关闭记忆采集开关后不再调用提炼器，且在途候选全部静默忽略") {
      (for
        store <- ZIO.service[MemoryStore]
        scope = MemoryScope.User(TenantId("tenant-a"), UserId("user-disabled"))
        extractorCalls <- Ref.make(0)
        extractor = new MemoryExtractor:
          def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
            extractorCalls
              .update(_ + 1)
              .as(
                Chunk(MemoryCandidate(0, MemoryMutation.Upsert(entry("should.not.write", "禁止写入"))))
              )
        lifecycle = MemoryLifecycle(
          extractor,
          store,
          MemoryGovernancePolicy(collectionEnabled = false)
        )
        runId <- RunId.random
        r1    <- lifecycle.capture(scope, Chunk(AgentMessage.user("测试")), runId)
        r2    <- lifecycle.applyCandidates(
          scope,
          Chunk(MemoryCandidate(0, MemoryMutation.Upsert(entry("should.not.write", "禁止写入"))))
        )
        calls  <- extractorCalls.get
        listed <- store.list(scope, 10)
      yield assertTrue(
        calls == 0,
        r1.written == 0,
        r2.written == 0,
        r2.ignored == 1,
        listed.isEmpty
      )).provide(MemoryStore.inMemory)
    },
    test("R1/R11：屏障检查与 CAS 原子互斥、删后重建保留历史删除版本、保留键 __scope_barrier__ 禁止业务读写") {
      (for
        delegate <- ZIO.service[MemoryStore]
        scope = MemoryScope.User(TenantId("tenant-r1"), UserId("user-r1"))
        // 1. Window 1: 屏障检查与 CAS 之间交错发生 deleteScope 时必须拒绝旧任务新建 key
        captured1 <- delegate.currentBarrier(scope)
        once      <- Ref.make(false)
        racing = new MemoryStore:
          export delegate.{delete, deleteScope, get, list, purgeExpired, put, search}
          override def currentBarrier(s: MemoryScope): IO[StoreError, MemoryDeletionBarrier] =
            delegate.currentBarrier(s)
          def compareAndSet(s: MemoryScope, version: Long, e: MemoryEntry): IO[StoreError, MemoryEntry] =
            once.getAndSet(true).flatMap { already =>
              (if already then ZIO.unit else delegate.deleteScope(s).unit) *>
                delegate.compareAndSet(s, version, e)
            }
        noopExtractor = new MemoryExtractor:
          def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
            ZIO.succeed(Chunk.empty)
        racingLifecycle = MemoryLifecycle(noopExtractor, racing, MemoryGovernancePolicy.default)
        staleNew        = entry("stale.new.key", "before-scope-delete")
        _ <- racingLifecycle
          .applyCandidates(scope, Chunk(MemoryCandidate(0, MemoryMutation.Upsert(staleNew))), captured1)
        afterRace <- delegate.get(scope, staleNew.key)
        // 2. Window 3: t0 捕获屏障 -> t1 删除单键 -> t2 新对话重建该键 -> t3 旧任务迟到尝试覆盖，必须因历史删除记录被拒
        _         <- delegate.put(scope, entry("rebuilt.key", "v1"))
        captured0 <- delegate.currentBarrier(scope)
        _         <- delegate.delete(scope, "rebuilt.key")
        _         <- delegate.put(scope, entry("rebuilt.key", "v2-rebuilt-after-delete"))
        lifecycle = MemoryLifecycle(noopExtractor, delegate, MemoryGovernancePolicy.default)
        report3 <- lifecycle.applyCandidates(
          scope,
          Chunk(
            MemoryCandidate(
              0,
              MemoryMutation.Upsert(
                entry("rebuilt.key", "v0-stale-overwrite").copy(
                  evidence = MemoryEvidence.UserStated,
                  confidence = 1.0,
                  updatedAtEpochMilli = 999999L
                )
              )
            )
          ),
          captured0
        )
        afterRebuild <- delegate.get(scope, "rebuilt.key")
        // 3. R11: 保留键 __scope_barrier__ 禁止写入、更新、删除，且不出现在查询列表
        putReserved <- delegate.put(scope, entry(MemoryStore.ScopeBarrierKey, "illegal")).exit
        casReserved <- delegate.compareAndSet(scope, 0L, entry(MemoryStore.ScopeBarrierKey, "illegal")).exit
        delReserved <- delegate.delete(scope, MemoryStore.ScopeBarrierKey).exit
        getReserved <- delegate.get(scope, MemoryStore.ScopeBarrierKey)
        candReport  <- lifecycle.applyCandidates(
          scope,
          Chunk(MemoryCandidate(0, MemoryMutation.Upsert(entry(MemoryStore.ScopeBarrierKey, "illegal"))))
        )
      yield assertTrue(
        afterRace.isEmpty,
        report3.written == 0,
        report3.ignored == 1,
        afterRebuild.exists(_.value == Json.Str("v2-rebuilt-after-delete")),
        putReserved.isFailure,
        casReserved.isFailure,
        delReserved.isFailure,
        getReserved.isEmpty,
        candReport.written == 0,
        candReport.rejected.length == 1
      )).provide(MemoryStore.inMemory)
    },
    test("F8：自定义 MemoryStore 复用 4 参数默认 compareAndSet 兼容实现时，补偿不得误删删除后新写入的合法版本") {
      for
        delegate <- ZIO.service[MemoryStore]
        scope = MemoryScope.User(TenantId("tenant-f8"), UserId("user-f8"))
        barrier0 <- delegate.currentBarrier(scope)
        legacyAdapter = new MemoryStore:
          export delegate.{delete, deleteScope, get, list, purgeExpired, put, search}
          override def currentBarrier(s: MemoryScope): IO[StoreError, MemoryDeletionBarrier] =
            delegate.currentBarrier(s)
          def compareAndSet(
              s: MemoryScope,
              expectedVersion: Long,
              e: MemoryEntry
          ): IO[StoreError, MemoryEntry] =
            for
              written <- delegate.compareAndSet(s, expectedVersion, e)
              _       <- delegate.delete(s, e.key)
              _       <- delegate.put(s, e.copy(value = Json.Str("fresh-post-delete-value")))
            yield written
        staleExit <- legacyAdapter
          .compareAndSet(scope, 0L, entry("compat.key", "stale-value"), Some(barrier0))
          .exit
        preserved <- delegate.get(scope, "compat.key")
      yield assertTrue(
        staleExit.isFailure,
        preserved.exists(_.value == Json.Str("fresh-post-delete-value"))
      )
    }.provide(MemoryStore.inMemory)
  )
