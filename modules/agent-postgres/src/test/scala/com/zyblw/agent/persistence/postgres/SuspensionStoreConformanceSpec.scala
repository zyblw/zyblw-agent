package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.time.Instant
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 内存与 PostgreSQL 共用挂起到期索引不变量：commit 同步 `agent_suspensions`、expireDue 排他、claim/resolve 经同一 Store。 */
object SuspensionStoreConformanceSpec extends ZIOSpecDefault:
  private val agent = AgentDefinition(AgentId("suspension-conformance"), "Suspension", "挂起到期一致性")

  private def state(runId: RunId, sessionId: SessionId, at: Instant): AgentState =
    AgentState(
      runId,
      sessionId,
      agent.id,
      RunStatus.Created,
      Chunk.empty,
      Chunk.empty,
      UsageSummary(),
      BudgetState(RunLimits(), UsageSummary(), 0),
      None,
      at,
      at,
      Version.initial,
      agent,
      RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings),
      ThreadId("suspension-conformance")
    )

  private val postgres: ZLayer[Any, Throwable, RunStore & SuspensionStore] =
    ZLayer.scopedEnvironment {
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
        env <- PostgresAgentPersistence.migratedLayer.build.provideSome[Scope](
          ZLayer.succeed[DataSource](dataSource)
        )
      yield ZEnvironment[RunStore](env.get[RunStore]) ++
        ZEnvironment[SuspensionStore](env.get[SuspensionStore])
    }

  private def contract(label: String, layer: ZLayer[Any, Throwable, RunStore & SuspensionStore]) =
    suite(label)(
      test("到期索引随状态提交，expireDue 后可 claim 并 resolve") {
        for
          store       <- ZIO.service[RunStore]
          suspensions <- ZIO.service[SuspensionStore]
          runId       <- RunId.random
          sessionId   <- SessionId.random
          createdId   <- EventId.random
          suspendedId <- EventId.random
          now         <- Clock.instant
          created = PersistedAgentEvent(
            createdId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, sessionId, now.toEpochMilli),
            now.toEpochMilli
          )
          initial = state(runId, sessionId, now).copy(lastEventSequence = 0L)
          _ <- store.createWithEvents(initial, NonEmptyChunk(created))
          createdAt = now.minusSeconds(10)
          deadline  = now.minusSeconds(1)
          waiting   = initial.copy(
            status = RunStatus.Suspended,
            suspension =
              Some(SuspensionRecord(Suspension.Timer, createdAt, Some(deadline), SuspensionExpiry.FailRun)),
            lastEventSequence = 1L
          )
          suspendedEvent = PersistedAgentEvent(
            suspendedId,
            runId,
            1L,
            AgentEvent.RunSuspended(runId, "等待计时器到期", now.toEpochMilli),
            now.toEpochMilli
          )
          _       <- store.commit(Version.initial, waiting, NonEmptyChunk(suspendedEvent))
          expired <- suspensions.expireDue(8)
          leases  <- suspensions.claimExpired(WorkerId("suspension-conformance"), 30.seconds)
          _       <- ZIO.foreachDiscard(leases)(suspensions.resolve)
        yield assertTrue(
          expired.contains(runId),
          leases.exists(lease => lease.runId == runId && lease.kind == "timer")
        )
      }
    ).provideLayer(layer) @@ TestAspect.timeout(3.minutes)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SuspensionStoreConformance")(
      contract("in-memory", RunStore.inMemory),
      contract("postgres", postgres) @@ PostgresIntegrationAspect.enabled
    )
