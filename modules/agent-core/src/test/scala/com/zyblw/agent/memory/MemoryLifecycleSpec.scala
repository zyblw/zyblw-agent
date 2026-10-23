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
    }
  )
