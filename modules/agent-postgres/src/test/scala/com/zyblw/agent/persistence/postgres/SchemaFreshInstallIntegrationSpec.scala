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
      },
      test("dispatcher 只能指向同一 Run 的命令，事件序号不重复建 btree") {
        (for
          dataSource <- ZIO.service[DataSource]
          _          <- AgentPostgresMigrations.migrate(dataSource)
          outcome    <- ZIO.attemptBlocking {
            val connection = dataSource.getConnection
            try
              val runA      = UUID.randomUUID()
              val runB      = UUID.randomUUID()
              val session   = UUID.randomUUID()
              val commandA  = UUID.randomUUID()
              val insertRun = connection.prepareStatement(
                """INSERT INTO agent_runs
                  |(run_id, session_id, agent_id, status, version, schema_version, state_json, created_at, updated_at)
                  |VALUES (?::uuid, ?::uuid, 'schema', 'Created', 0, 1, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""".stripMargin
              )
              try
                insertRun.setString(1, runA.toString)
                insertRun.setString(2, session.toString)
                insertRun.executeUpdate()
                insertRun.setString(1, runB.toString)
                insertRun.executeUpdate()
              finally insertRun.close()
              val insertCommand = connection.prepareStatement(
                """INSERT INTO agent_run_commands
                  |(command_id, run_id, command_type, payload, idempotency_key, status)
                  |VALUES (?::uuid, ?::uuid, 'Cancel', '{}'::jsonb, 'same-run', 'Queued')""".stripMargin
              )
              try
                insertCommand.setString(1, commandA.toString)
                insertCommand.setString(2, runA.toString)
                insertCommand.executeUpdate()
              finally insertCommand.close()
              val sameRun = connection.prepareStatement(
                """INSERT INTO agent_run_dispatch
                  |(run_id, status, current_command_id, lease_owner, lease_token, claimed_at, lease_expires_at)
                  |VALUES (?::uuid, 'Leased', ?::uuid, 'owner', gen_random_uuid(), CURRENT_TIMESTAMP,
                  |        CURRENT_TIMESTAMP + INTERVAL '30 seconds')""".stripMargin
              )
              try
                sameRun.setString(1, runA.toString)
                sameRun.setString(2, commandA.toString)
                sameRun.executeUpdate()
              finally sameRun.close()
              val crossRun = connection.prepareStatement(
                """INSERT INTO agent_run_dispatch
                  |(run_id, status, current_command_id, lease_owner, lease_token, claimed_at, lease_expires_at)
                  |VALUES (?::uuid, 'Leased', ?::uuid, 'owner', gen_random_uuid(), CURRENT_TIMESTAMP,
                  |        CURRENT_TIMESTAMP + INTERVAL '30 seconds')""".stripMargin
              )
              val sqlState =
                try
                  crossRun.setString(1, runB.toString)
                  crossRun.setString(2, commandA.toString)
                  crossRun.executeUpdate()
                  "ok"
                catch case error: java.sql.SQLException => error.getSQLState
                finally crossRun.close()
              val indexes = connection.prepareStatement(
                """SELECT indexname FROM pg_indexes
                  |WHERE schemaname = current_schema() AND tablename = 'agent_events'""".stripMargin
              )
              val names =
                try
                  val rows  = indexes.executeQuery()
                  val found = scala.collection.mutable.ListBuffer.empty[String]
                  while rows.next() do found += rows.getString(1)
                  found.toList
                finally indexes.close()
              (sqlState, names)
            finally connection.close()
          }
        yield assertTrue(
          outcome._1 == "23503",
          !outcome._2.contains("agent_events_run_sequence_idx"),
          outcome._2.exists(_.contains("run_id"))
        )).provideLayer(dataSourceLayer)
      }
    ) @@ PostgresIntegrationAspect.enabled @@ TestAspect.timeout(3.minutes) @@ TestAspect.sequential
