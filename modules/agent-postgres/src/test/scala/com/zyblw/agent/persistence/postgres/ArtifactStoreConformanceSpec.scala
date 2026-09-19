package com.zyblw.agent.persistence.postgres

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zyblw.agent.artifacts.*
import com.zyblw.agent.core.*
import java.util.UUID
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

/** 内存与 PostgreSQL ArtifactStore 共用删除、保留期和审计不变量。
  *
  * 覆盖 `agent_artifacts` / `agent_artifact_versions` / `agent_artifact_audit`，含 Run 域外置读回。
  */
object ArtifactStoreConformanceSpec extends ZIOSpecDefault:
  private val session =
    ArtifactScope.Session(SessionId(UUID.fromString("00000000-0000-0000-0000-000000000021")))
  private val other =
    ArtifactScope.User(TenantId("tenant-b"), UserId("user-b"))
  private val name = ArtifactName("reports/answer.pdf")

  private val postgres: ZLayer[Any, Throwable, ArtifactStore] =
    ArtifactBlobStore.inMemory >>> ZLayer.scoped {
      for
        blobs     <- ZIO.service[ArtifactBlobStore]
        container <- ZIO.acquireRelease(
          ZIO.attemptBlocking {
            val value =
              PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:18-alpine"))
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
        _     <- AgentPostgresMigrations.migrate(dataSource)
        store <- ZIO.succeed(PostgresArtifactStore(dataSource, ArtifactStorePolicy(), Some(blobs)))
      yield store
    }

  private def contract(label: String, layer: ZLayer[Any, Throwable, ArtifactStore]) =
    suite(label)(
      test("跨 scope 隔离，删除最新版本 fail-closed，历史删除写审计") {
        (for
          store <- ZIO.service[ArtifactStore]
          _     <- store.save(session, name, ArtifactInput(Chunk(1.toByte), "application/pdf"))
          _     <- store.save(session, name, ArtifactInput(Chunk(2.toByte), "application/pdf"))
          _ <- store.save(other, ArtifactName("private.txt"), ArtifactInput(Chunk(3.toByte), "text/plain"))
          denied <- store.delete(session, name, 2L).either
          gone   <- store.delete(session, name, 1L)
          leaked <- store.read(other, name)
          audits <- store.audits(20)
        yield assertTrue(
          denied.left.exists {
            case AgentError.ArtifactPolicyRejected(_, reason) => reason == "cannot-delete-latest"
            case _                                            => false
          },
          gone == 1L,
          leaked.isEmpty,
          audits.exists(_.action == ArtifactAuditAction.Delete),
          !audits.exists(_.reasonCode.contains("pdf"))
        )).provide(layer)
      },
      test("Run 域外置结果可按引用读回，并与 Session 域隔离") {
        val runScope = ArtifactScope.Run(
          RunId(UUID.fromString("00000000-0000-0000-0000-000000000031")),
          ThreadId("artifact-run")
        )
        val payload = Chunk.fromArray("tool-result-body".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        (for
          store      <- ZIO.service[ArtifactStore]
          descriptor <- store.save(
            runScope,
            ArtifactName("tool-results/call.json"),
            ArtifactInput(payload, "application/json")
          )
          loaded <- store.read(descriptor.reference)
          leaked <- store.read(session, ArtifactName("tool-results/call.json"))
        yield assertTrue(
          loaded.exists(_.bytes == payload),
          leaked.isEmpty,
          descriptor.scope == runScope
        )).provide(layer)
      }
    ) @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(3.minutes)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ArtifactStoreConformance")(
      suite("memory")(
        test("内存实现通过同一组删除不变量") {
          (for
            store <- ZIO.service[ArtifactStore]
            _     <- store.save(session, name, ArtifactInput(Chunk(1.toByte), "application/pdf"))
            _     <- store.save(session, name, ArtifactInput(Chunk(2.toByte), "application/pdf"))
            gone  <- store.delete(session, name, 1L)
          yield assertTrue(gone == 1L)).provide(ArtifactStore.inMemory())
        },
        test("内存 Run 域外置结果可按引用读回") {
          val runScope = ArtifactScope.Run(
            RunId(UUID.fromString("00000000-0000-0000-0000-000000000032")),
            ThreadId("memory-run")
          )
          val payload =
            Chunk.fromArray("inline-then-externalized".getBytes(java.nio.charset.StandardCharsets.UTF_8))
          (for
            store      <- ZIO.service[ArtifactStore]
            descriptor <- store.save(
              runScope,
              ArtifactName("tool-results/call.json"),
              ArtifactInput(payload, "application/json")
            )
            loaded <- store.read(descriptor.reference)
          yield assertTrue(loaded.exists(_.bytes == payload))).provide(ArtifactStore.inMemory())
        }
      ),
      contract("postgres", postgres)
    )
