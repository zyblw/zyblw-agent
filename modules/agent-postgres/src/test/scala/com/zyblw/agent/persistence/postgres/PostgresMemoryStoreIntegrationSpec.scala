package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.time.Instant
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 使用真实 PostgreSQL 验证 Memory CAS、租户隔离、tombstone 清空和过期清理。 */
object PostgresMemoryStoreIntegrationSpec extends ZIOSpecDefault:

  /** DataSource 用于额外确认删除后数据库不再保存 value/search_text 正文。 */
  final private case class Harness(store: PostgresMemoryStore, dataSource: DataSource)

  /** 启动 PostgreSQL 16 并执行正式 V001 baseline。 */
  private val harnessLayer: ZLayer[Any, Throwable, Harness] = ZLayer.scoped {
    for
      container <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val value =
            PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"))
          value.start()
          value
        }
      )(value => ZIO.attemptBlocking(value.stop()).orDie)
      dataSource <- ZIO.attempt {
        val value = PGSimpleDataSource()
        value.setURL(container.jdbcUrl)
        value.setUser(container.username)
        value.setPassword(container.password)
        value: DataSource
      }
      _ <- ZIO.attemptBlocking {
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations(AgentPostgresMigrations.DefaultLocation)
          .load()
          .migrate()
      }
    yield Harness(PostgresMemoryStore(dataSource), dataSource)
  }

  /** 构造带真实绝对时间的用户偏好。 */
  private def entry(key: String, value: String, now: Instant): MemoryEntry = MemoryEntry(
    key,
    Json.Str(value),
    importance = 0.9,
    sourceRunId = None,
    createdAtEpochMilli = now.toEpochMilli,
    expiresAtEpochMilli = None,
    kind = MemoryKind.Preference,
    confidence = 1.0,
    sensitivity = MemorySensitivity.Personal,
    evidence = MemoryEvidence.UserStated,
    extractorVersion = "integration-v1"
  )

  /** 直接读取 tombstone 的正文空值，用来证明删除不是仅靠 WHERE status 隐藏。 */
  private def tombstoneCleared(dataSource: DataSource, scopeKey: String, key: String): Task[Boolean] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          """SELECT status, value_json IS NULL, search_text IS NULL
          |FROM agent_memories WHERE scope_kind = 'user' AND scope_key = ? AND memory_key = ?""".stripMargin
        )
        try
          statement.setString(1, scopeKey)
          statement.setString(2, key)
          val result = statement.executeQuery()
          result.next() && result.getString(1) == "deleted" && result.getBoolean(2) && result.getBoolean(3)
        finally statement.close()
      finally connection.close()
    }

  /** 读取低敏审计投影，确认没有正文列且 hash/主体/数量正确落库。 */
  private def auditSnapshot(dataSource: DataSource): Task[(Long, String, String, Long, String, String)] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          """SELECT count(*) OVER (), action, memory_key_hash, affected_count, actor_tenant_id, actor_user_id
          |FROM agent_memory_audit ORDER BY occurred_at, audit_id LIMIT 1""".stripMargin
        )
        try
          val result = statement.executeQuery()
          if !result.next() then throw IllegalStateException("缺少 Memory 审计记录")
          (
            result.getLong(1),
            result.getString(2),
            result.getString(3),
            result.getLong(4),
            result.getString(5),
            result.getString(6)
          )
        finally statement.close()
      finally connection.close()
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PostgreSQL MemoryStore")(
    test("并发 CAS 只有一个胜出，租户隔离、中文搜索、删除清空和过期清理成立") {
      (for
        harness <- ZIO.service[Harness]
        now     <- Clock.instant
        scopeA = MemoryScope.User(TenantId("tenant-a"), UserId("same-user"))
        scopeB = MemoryScope.User(TenantId("tenant-b"), UserId("same-user"))
        created <- harness.store.compareAndSet(scopeA, 0L, entry("learning.classic", "正在学习伤寒论", now))
        races   <- ZIO.foreachPar(Chunk("伤寒论·黄帝内经", "伤寒论·金匮要略")) { value =>
          harness.store.compareAndSet(scopeA, created.version, entry("learning.classic", value, now)).exit
        }
        searchA          <- harness.store.search(scopeA, "伤寒", 10)
        _                <- harness.store.put(scopeB, entry("learning.classic", "另一个租户的秘密", now))
        searchBFromA     <- harness.store.search(scopeA, "秘密", 10)
        latest           <- harness.store.get(scopeA, "learning.classic")
        _                <- harness.store.delete(scopeA, "learning.classic")
        absent           <- harness.store.get(scopeA, "learning.classic")
        staleAfterDelete <- harness.store
          .compareAndSet(
            scopeA,
            latest.map(_.version).getOrElse(0L),
            entry("learning.classic", "迟到写", now)
          )
          .exit
        canonicalScopeKey = s"${"tenant-a".length}:tenant-a:same-user"
        cleared <- tombstoneCleared(harness.dataSource, canonicalScopeKey, "learning.classic")
        expiring = entry("session.temporary", "临时偏好", now).copy(
          kind = MemoryKind.Episodic,
          expiresAtEpochMilli = Some(now.plusSeconds(5).toEpochMilli)
        )
        _       <- harness.store.put(scopeA, expiring)
        purged  <- harness.store.purgeExpired(now.plusSeconds(6).toEpochMilli, 10)
        expired <- harness.store.get(scopeA, "session.temporary")
      yield assertTrue(
        races.count(_.isSuccess) == 1,
        races.count(_.isFailure) == 1,
        searchA.map(_.key) == Chunk("learning.classic"),
        searchBFromA.isEmpty,
        absent.isEmpty,
        staleAfterDelete.isFailure,
        cleared,
        purged == 1L,
        expired.isEmpty
      )).provideLayer(harnessLayer)
    } @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential,
    test("用户纠正与审计同事务：审计约束失败会回滚 Memory 更新，成功记录只含 hash 和低敏主体") {
      (for
        harness <- ZIO.service[Harness]
        now     <- Clock.instant
        auditId <- Random.nextUUID
        scope = MemoryScope.User(TenantId("tenant-a"), UserId("user-a"))
        created <- harness.store.compareAndSet(scope, 0L, entry("learning.classic", "伤寒论", now))
        invalidAudit = MemoryAuditRecord(
          auditId,
          MemoryAuditAction.Correct,
          MemoryAuditActor.Authenticated(None, None),
          scope,
          Some(MemoryGovernanceService.hashKey(created.key)),
          Some(created.version),
          Some(created.version + 1L),
          1L,
          Some("user-confirmed"),
          now
        )
        failed <- harness.store
          .correct(
            scope,
            created.version,
            created.copy(value = Json.Str("不应提交"), updatedAtEpochMilli = now.plusSeconds(1).toEpochMilli),
            invalidAudit
          )
          .exit
        afterRollback <- harness.store.get(scope, created.key)
        repository = harness.store: MemoryGovernanceRepository
        service    = MemoryGovernanceService(harness.store, repository, MemoryGovernancePolicy())
        updated <- service.correct(
          RunContext(userId = Some("user-a"), tenantId = Some("tenant-a")),
          scope,
          MemoryCorrection(
            created.key,
            created.version,
            Json.Str("黄帝内经"),
            0.95,
            MemoryKind.Preference,
            MemorySensitivity.Personal,
            None
          )
        )
        audit <- auditSnapshot(harness.dataSource)
      yield assertTrue(
        failed.isFailure,
        afterRollback.exists(value => value.value == Json.Str("伤寒论") && value.version == created.version),
        updated.value == Json.Str("黄帝内经"),
        updated.version == created.version + 1L,
        audit._1 == 1L,
        audit._2 == "correct",
        audit._3 == MemoryGovernanceService.hashKey(created.key),
        audit._4 == 1L,
        audit._5 == "tenant-a",
        audit._6 == "user-a"
      )).provideLayer(harnessLayer)
    } @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.timeout(
      3.minutes
    ) @@ TestAspect.sequential
  )
