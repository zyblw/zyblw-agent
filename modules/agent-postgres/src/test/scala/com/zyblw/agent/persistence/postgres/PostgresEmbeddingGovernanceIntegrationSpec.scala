package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 使用真实 PostgreSQL 16 验证 Embedding 缓存与配额的跨实例生产语义。
  *
  * 测试通过 `RUN_POSTGRES_INTEGRATION=1` 显式开启，避免普通单元测试隐式依赖 Docker。它关注内存实现无法证明的 行为：多 Store 实例共享缓存、tenant
  * 复合主键隔离、并发窗口行锁、幂等账本事务回滚以及级联 retention。
  */
object PostgresEmbeddingGovernanceIntegrationSpec extends ZIOSpecDefault:

  /** 两个 Cache/Quota 对象共享同一 DataSource，用来模拟不同 Worker 进程连接同一数据库。 */
  final private case class Harness(
      cacheA: PostgresEmbeddingCacheStore,
      cacheB: PostgresEmbeddingCacheStore,
      quotaA: PostgresEmbeddingQuotaStore,
      quotaB: PostgresEmbeddingQuotaStore
  )

  /** 启动干净 PostgreSQL 并执行正式 V001；任何 SQL 语法或 CHECK/FK 错误都会让测试立即失败。 */
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
    yield Harness(
      PostgresEmbeddingCacheStore(
        dataSource,
        PostgresEmbeddingCacheConfig(readBatchSize = 2, writeBatchSize = 2)
      ),
      PostgresEmbeddingCacheStore(
        dataSource,
        PostgresEmbeddingCacheConfig(readBatchSize = 3, writeBatchSize = 3)
      ),
      PostgresEmbeddingQuotaStore(dataSource),
      PostgresEmbeddingQuotaStore(dataSource)
    )
  }

  /** 生成与生产治理门面相同格式的小写 SHA-256，避免测试使用无效伪 hash。 */
  private def hash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  /** 构造包含模型契约和租户边界的缓存键。 */
  private def key(tenant: String, text: String): EmbeddingCacheKey =
    EmbeddingCacheKey(TenantId(tenant), "integration-provider", "embedding-v1", 2, "exact-v1", hash(text))

  /** 构造一次真实 Provider miss 对应的幂等预留。 */
  private def reservation(tenant: String, requestId: String, requestHash: String): EmbeddingQuotaReservation =
    EmbeddingQuotaReservation(
      EmbeddingRequestContext(TenantId(tenant), EmbeddingPurpose.Query, requestId),
      requestHash,
      requests = 1L,
      texts = 1L,
      characters = 2L
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PostgreSQL Embedding governance")(
    test("批量 cache upsert 可跨 Worker 命中，租户隔离、维度校验和有界过期清理成立") {
      (for
        harness <- ZIO.service[Harness]
        now     = Instant.parse("2026-07-15T00:00:00Z")
        a1      = key("tenant-a", "甲")
        a2      = key("tenant-a", "乙")
        b1      = key("tenant-b", "甲")
        expired = key("tenant-a", "过期")
        _ <- harness.cacheA.put(
          Chunk(
            EmbeddingCacheEntry(a1, Embedding(Chunk(1.0f, 2.0f)), now.plusSeconds(60)),
            EmbeddingCacheEntry(a2, Embedding(Chunk(3.0f, 4.0f)), now.plusSeconds(60)),
            EmbeddingCacheEntry(b1, Embedding(Chunk(5.0f, 6.0f)), now.plusSeconds(60)),
            EmbeddingCacheEntry(expired, Embedding(Chunk(7.0f, 8.0f)), now.minusSeconds(1))
          )
        )
        // cacheB 是另一个 Store 实例，命中证明状态不依赖进程内 Ref。
        read        <- harness.cacheB.get(Chunk(a1, a2, expired), now)
        crossTenant <- harness.cacheB.get(Chunk(b1), now)
        invalid     <- harness.cacheA
          .put(
            Chunk(
              EmbeddingCacheEntry(a1, Embedding(Chunk(1.0f)), now.plusSeconds(60))
            )
          )
          .exit
        purged <- harness.cacheB.purgeExpired(now, 1)
        none   <- harness.cacheA.get(Chunk(expired), now.minusSeconds(2))
      yield assertTrue(
        read.keySet == Set(a1, a2),
        crossTenant.keySet == Set(b1),
        invalid.isFailure,
        purged == 1L,
        none.isEmpty
      )).provideLayer(harnessLayer)
    },
    test("并发 Worker 只有一个通过硬配额，幂等冲突不计费，窗口清理释放 requestId") {
      (for
        harness <- ZIO.service[Harness]
        now    = Instant.parse("2026-07-15T00:00:00Z")
        policy = EmbeddingQuotaPolicy(
          window = 1.minute,
          maxRequests = 1L,
          maxTexts = 10L,
          maxCharacters = 100L
        )
        first  = reservation("quota-tenant", "parallel-a", hash("parallel-a"))
        second = reservation("quota-tenant", "parallel-b", hash("parallel-b"))
        races <- ZIO.collectAllPar(
          Chunk(
            harness.quotaA.reserve(first, policy, now).either,
            harness.quotaB.reserve(second, policy, now).either
          )
        )
        winner = if races.head.isRight then first else second
        retry    <- harness.quotaB.reserve(winner, policy, now).either
        conflict <- harness.quotaA
          .reserve(
            winner.copy(requestHash = hash("different-payload")),
            policy,
            now
          )
          .either
        used  <- harness.quotaA.usage(TenantId("quota-tenant"), policy, now)
        other <- harness.quotaB.reserve(
          reservation("other-tenant", "parallel-a", hash("other")),
          policy,
          now
        )
        purged     <- harness.quotaA.purgeWindows(now.plusSeconds(61), 10)
        afterPurge <- harness.quotaB.reserve(
          winner.copy(requestHash = hash("new-window-payload")),
          policy,
          now.plusSeconds(61)
        )
      yield assertTrue(
        races.count(_.isRight) == 1,
        races.count(_.left.exists(_.category == ErrorCategory.RateLimit)) == 1,
        retry.isRight,
        conflict.left.exists(_.category == ErrorCategory.Validation),
        used.requests == 1L,
        other.requests == 1L,
        purged == 2L,
        afterPurge.requests == 1L
      )).provideLayer(harnessLayer)
    }
  ) @@ TestAspect.ifEnvSet("RUN_POSTGRES_INTEGRATION") @@ TestAspect.timeout(
    3.minutes
  ) @@ TestAspect.sequential
