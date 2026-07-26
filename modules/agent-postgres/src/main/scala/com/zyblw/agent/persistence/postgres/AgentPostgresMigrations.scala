package com.zyblw.agent.persistence.postgres

import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource

/** Framework-owned Flyway resources and the opt-in migration entry point.
  *
  * The framework deliberately does not migrate a database merely because its JAR is on the classpath.
  * Applications must call [[migrate]] during their controlled startup sequence, or add [[DefaultLocation]] to
  * an existing Flyway instance.
  *
  * New applications should keep the dedicated history table. It prevents an application's own `V1`, `V2`, ...
  * migrations from colliding with framework versions. Existing applications that already share one Flyway
  * history must follow the documented legacy adoption path instead of silently switching history tables.
  */
object AgentPostgresMigrations:
  val DefaultLocation: String =
    "classpath:com/zyblw/agent/persistence/postgres/migration"

  val OptionalPgVectorLocation: String =
    "classpath:com/zyblw/agent/persistence/postgres/optional/pgvector"

  val DefaultHistoryTable: String = "flyway_zyblw_agent_schema_history"

  /** Validates and applies framework migrations using a dedicated Flyway history table.
    *
    * This effect is blocking because Flyway and JDBC are blocking APIs. The returned projection does not
    * expose Flyway classes, so a later Flyway upgrade does not become part of the public API.
    */
  def migrate(
      dataSource: DataSource,
      config: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig()
  ): Task[AgentPostgresMigrationResult] =
    for
      validated <- ZIO.fromEither(config.validated).mapError(message => new IllegalArgumentException(message))
      result    <- ZIO.attemptBlocking {
        val migration = Flyway
          .configure()
          .dataSource(dataSource)
          .locations(validated.locations*)
          .table(validated.historyTable)
          .baselineOnMigrate(validated.baselineOnMigrate)
          .validateOnMigrate(true)
          .cleanDisabled(true)
          .load()
          .migrate()
        AgentPostgresMigrationResult(
          success = migration.success,
          migrationsExecuted = migration.migrationsExecuted,
          targetVersion = Option(migration.targetSchemaVersion).map(_.toString)
        )
      }
    yield result

/** Migration policy owned by the host application.
  *
  * `baselineOnMigrate` defaults to false: adopting an existing schema must be an explicit, audited operation.
  * Additional locations are supported for opt-in framework capabilities, but every location must be a
  * classpath resource.
  */
final case class AgentPostgresMigrationConfig(
    historyTable: String = AgentPostgresMigrations.DefaultHistoryTable,
    locations: List[String] = List(AgentPostgresMigrations.DefaultLocation),
    baselineOnMigrate: Boolean = false
):
  private[postgres] def validated: Either[String, AgentPostgresMigrationConfig] =
    val validIdentifier = historyTable.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
    if !validIdentifier then
      Left("Flyway history table must be a PostgreSQL identifier of at most 63 characters")
    else if locations.isEmpty then Left("At least one Flyway classpath location is required")
    else if locations.exists(location => !location.startsWith("classpath:") || location.length > 500) then
      Left("Flyway locations must be bounded classpath resources")
    else Right(copy(locations = locations.distinct))

/** Low-coupling result returned by [[AgentPostgresMigrations.migrate]]. */
final case class AgentPostgresMigrationResult(
    success: Boolean,
    migrationsExecuted: Int,
    targetVersion: Option[String]
)
