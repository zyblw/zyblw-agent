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

/** 真实 PostgreSQL 18 上验证 0.9 全新基线：死投影表不存在、bytes 可外置。 */
object SchemaFreshInstallIntegrationSpec extends ZIOSpecDefault:
  private val dataSourceLayer: ZLayer[Any, Throwable, DataSource] = ZLayer.scoped {
    for container <- ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val value =
            PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse("postgres:18-alpine"))
          value.start()
          value
        }
      )(value => ZIO.attemptBlocking(value.stop()).orDie)
    yield
      val value = PGSimpleDataSource()
      value.setURL(container.jdbcUrl)
      value.setUser(container.username)
      value.setPassword(container.password)
      value
  }

  private def tableExists(dataSource: DataSource, name: String): Task[Boolean] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")
        try
          statement.setString(1, name)
          val result = statement.executeQuery()
          result.next() && result.getBoolean(1)
        finally statement.close()
      finally connection.close()
    }

  private def bytesNullable(dataSource: DataSource): Task[Boolean] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          """SELECT is_nullable
            |FROM information_schema.columns
            |WHERE table_schema = current_schema()
            |  AND table_name = 'agent_artifact_versions'
            |  AND column_name = 'bytes'""".stripMargin
        )
        try
          val result = statement.executeQuery()
          result.next() && result.getString(1) == "YES"
        finally statement.close()
      finally connection.close()
    }

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SchemaFreshInstall")(
      test("fresh migrate 后权威表在、死投影表不在、bytes 可外置") {
        (for
          dataSource <- ZIO.service[DataSource]
          _          <- AgentPostgresMigrations.migrate(dataSource)
          census     <- AgentSchemaCensus.inspect(dataSource)
          deadGone   <- ZIO.foreach(AgentSchemaInventory.DeadProjections)(tableExists(dataSource, _))
          nullable   <- bytesNullable(dataSource)
        yield assertTrue(
          census.missingAuthoritative.isEmpty,
          census.deadOccupied.isEmpty,
          deadGone.forall(exists => !exists),
          nullable,
          census.checksum.nonEmpty
        )).provideLayer(dataSourceLayer)
      },
      test("resetAll 后可以重新 migrate") {
        (for
          dataSource <- ZIO.service[DataSource]
          _          <- AgentPostgresMigrations.migrate(dataSource)
          _          <- AgentPostgresMigrations.resetAll(dataSource)
          replayed   <- AgentPostgresMigrations.migrate(dataSource)
          census     <- AgentSchemaCensus.inspect(dataSource)
        yield assertTrue(replayed.success, census.missingAuthoritative.isEmpty)).provideLayer(dataSourceLayer)
      },
      test("配置 ArtifactBlobStore 后 PostgreSQL 不保存 inline bytes") {
        (for
          dataSource <- ZIO.service[DataSource]
          _          <- AgentPostgresMigrations.migrate(dataSource)
          result     <- (
            for
              blobs <- ZIO.service[ArtifactBlobStore]
              store = PostgresArtifactStore(dataSource, ArtifactStorePolicy(), Some(blobs))
              scope = ArtifactScope.Session(
                SessionId(UUID.fromString("00000000-0000-0000-0000-000000000031"))
              )
              name  = ArtifactName("offload.bin")
              bytes = Chunk[Byte](7, 8, 9)
              saved <- store.save(scope, name, ArtifactInput(bytes, "application/pdf"))
              read  <- store.read(scope, name)
              empty <- ZIO.attemptBlocking {
                val connection = dataSource.getConnection
                try
                  val statement = connection.prepareStatement(
                    "SELECT bytes IS NULL FROM agent_artifact_versions WHERE name = ? AND version = ?"
                  )
                  try
                    statement.setString(1, name.value)
                    statement.setLong(2, saved.version)
                    val rows = statement.executeQuery()
                    rows.next() && rows.getBoolean(1)
                  finally statement.close()
                finally connection.close()
              }
            yield assertTrue(
              empty,
              read.exists(_.bytes == bytes),
              saved.sha256 == ArtifactStore.digestPublic(bytes)
            )
          ).provide(ArtifactBlobStore.inMemory)
        yield result).provideLayer(dataSourceLayer)
      }
    ) @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(3.minutes) @@ TestAspect.sequential
