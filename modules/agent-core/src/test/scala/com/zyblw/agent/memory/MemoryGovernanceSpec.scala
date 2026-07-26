package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 验证用户 Memory 治理的授权、CAS、低敏审计和删除语义。 */
object MemoryGovernanceSpec extends ZIOSpecDefault:

  private val ownScope = MemoryScope.User(TenantId("tenant-a"), UserId("user-a"))
  private val actor    = RunContext(
    userId = Some("user-a"),
    tenantId = Some("tenant-a"),
    scopes = Set("ordinary-user"),
    attributes = Map("authorization" -> "Bearer must-not-be-audited")
  )

  /** 创建一条用户明确陈述的偏好，作为治理页面读取和纠正的权威初始值。 */
  private def initial(value: String): MemoryEntry = MemoryEntry(
    key = "learning.preferred_classic",
    value = Json.Str(value),
    importance = 0.8,
    sourceRunId = None,
    createdAtEpochMilli = 0L,
    expiresAtEpochMilli = None,
    kind = MemoryKind.Preference,
    confidence = 1.0,
    sensitivity = MemorySensitivity.Personal,
    evidence = MemoryEvidence.UserStated,
    extractorVersion = "manual-v1"
  )

  /** 为每个测试创建独立 Store、审计 Repository 和服务，避免并行测试相互污染。 */
  private def withService[A](
      use: (MemoryStore, InMemoryMemoryGovernanceRepository, MemoryGovernanceService) => ZIO[Any, Any, A]
  ): ZIO[Any, Any, A] =
    ZIO
      .service[MemoryStore]
      .flatMap { store =>
        InMemoryMemoryGovernanceRepository.make(store).flatMap { repository =>
          use(store, repository, MemoryGovernanceService(store, repository, MemoryGovernancePolicy()))
        }
      }
      .provide(MemoryStore.inMemory)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Memory governance")(
    test("普通用户只能访问自己的 User scope，Session 与跨租户默认拒绝") {
      withService { (store, _, service) =>
        for
          _         <- store.put(ownScope, initial("伤寒论"))
          own       <- service.get(actor, ownScope, "learning.preferred_classic")
          cross     <- service.list(actor, MemoryScope.User(TenantId("tenant-b"), UserId("user-a")), 20).exit
          sessionId <- SessionId.random
          session   <- service.list(actor, MemoryScope.Session(sessionId), 20).exit
          anonymous <- service
            .list(
              RunContext(scopes = Set(MemoryGovernanceService.ReadAdminScope)),
              ownScope,
              20
            )
            .exit
        yield assertTrue(
          own.exists(_.value == Json.Str("伤寒论")),
          cross.isFailure,
          session.isFailure,
          anonymous.isFailure
        )
      }
    },
    test("用户纠正强制 UserStated、CAS 版本并写入不含正文/key/scopes/attributes 的审计") {
      withService { (store, repository, service) =>
        for
          created <- store.compareAndSet(ownScope, 0L, initial("伤寒论"))
          updated <- service.correct(
            actor,
            ownScope,
            MemoryCorrection(
              key = created.key,
              expectedVersion = created.version,
              value = Json.Str("黄帝内经"),
              importance = 0.95,
              kind = MemoryKind.Preference,
              sensitivity = MemorySensitivity.Personal,
              expiresAtEpochMilli = None
            )
          )
          stale <- service
            .correct(
              actor,
              ownScope,
              MemoryCorrection(
                created.key,
                created.version,
                Json.Str("陈旧覆盖"),
                0.9,
                MemoryKind.Preference,
                MemorySensitivity.Personal,
                None
              )
            )
            .exit
          audits <- repository.records
          audit = audits.last
        yield assertTrue(
          updated.version == created.version + 1L,
          updated.value == Json.Str("黄帝内经"),
          updated.evidence == MemoryEvidence.UserStated,
          updated.confidence == 1.0,
          updated.extractorVersion == MemoryGovernanceService.UserCorrectionVersion,
          stale.isFailure,
          audit.action == MemoryAuditAction.Correct,
          audit.memoryKeyHash.exists(hash => hash.length == 64 && hash != created.key),
          audit.actor == MemoryAuditActor.Authenticated(Some("tenant-a"), Some("user-a")),
          !audit.toString.contains("黄帝内经"),
          !audit.toString.contains("ordinary-user"),
          !audit.toString.contains("Bearer")
        )
      }
    },
    test("单条和 scope 删除保持幂等，并审计实际 affectedCount") {
      withService { (store, repository, service) =>
        for
          _          <- store.put(ownScope, initial("伤寒论"))
          first      <- service.delete(actor, ownScope, "learning.preferred_classic")
          second     <- service.delete(actor, ownScope, "learning.preferred_classic")
          _          <- store.put(ownScope, initial("黄帝内经").copy(key = "learning.second"))
          scopeCount <- service.deleteScope(actor, ownScope)
          remaining  <- store.list(ownScope, 20)
          audits     <- repository.records
        yield assertTrue(
          first == 1L,
          second == 0L,
          scopeCount == 1L,
          remaining.isEmpty,
          audits.map(_.affectedCount) == Chunk(1L, 0L, 1L),
          audits.map(_.action) == Chunk(
            MemoryAuditAction.Delete,
            MemoryAuditAction.Delete,
            MemoryAuditAction.DeleteScope
          )
        )
      }
    },
    test("搜索不把 query 写入审计，分页上限在访问 Store 前失败") {
      withService { (store, repository, service) =>
        val secretQuery = "不应进入审计的私密搜索词"
        for
          _       <- store.put(ownScope, initial(secretQuery))
          result  <- service.search(actor, ownScope, secretQuery, 10)
          invalid <- service.list(actor, ownScope, MemoryGovernanceService.MaxPageSize + 1).exit
          audits  <- repository.records
        yield assertTrue(
          result.length == 1,
          invalid.isFailure,
          audits.length == 1,
          audits.head.action == MemoryAuditAction.Search,
          audits.head.memoryKeyHash.isEmpty,
          !audits.head.toString.contains(secretQuery)
        )
      }
    }
  )
