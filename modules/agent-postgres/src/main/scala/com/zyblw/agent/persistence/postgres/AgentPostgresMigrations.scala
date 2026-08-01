package com.zyblw.agent.persistence.postgres

import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource

/** 框架拥有的 Flyway 资源与显式 migration 入口。
  *
  * JAR 出现在 classpath 不会自动修改数据库；宿主必须在受控启动阶段调用 [[migrate]]，或把 [[DefaultLocation]] 加入自己的 Flyway。当前 0.3 开发线只提供
  * fresh-install V001，必须使用空 schema/新数据库，不接管 0.2 history。
  */
object AgentPostgresMigrations:
  val DefaultLocation: String =
    "classpath:com/zyblw/agent/persistence/postgres/migration"

  val OptionalPgVectorLocation: String =
    "classpath:com/zyblw/agent/persistence/postgres/optional/pgvector"

  val DefaultHistoryTable: String = "flyway_zyblw_agent_schema_history"

  /** 校验配置并用独立 Flyway history 表应用框架 migration。
    *
    * Flyway/JDBC 是阻塞 API，因此该 effect 运行在 blocking executor。返回值不暴露 Flyway 类型，避免将其版本变成公共 API。
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

/** 由宿主应用拥有的 migration 策略。
  *
  * `baselineOnMigrate` 默认 false；0.3 fresh baseline 不允许用它伪装接管旧 schema。额外 location 服务 pgvector 等可选能力， 且必须是
  * classpath 资源。
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

/** [[AgentPostgresMigrations.migrate]] 返回的低耦合结果。 */
final case class AgentPostgresMigrationResult(
    success: Boolean,
    migrationsExecuted: Int,
    targetVersion: Option[String]
)
