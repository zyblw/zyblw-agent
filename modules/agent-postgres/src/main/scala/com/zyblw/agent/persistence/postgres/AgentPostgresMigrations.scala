package com.zyblw.agent.persistence.postgres

import org.flywaydb.core.Flyway
import zio.*

import java.sql.Connection
import javax.sql.DataSource

/** 框架拥有的 Flyway 资源与显式 migration 入口。
  *
  * JAR 出现在 classpath 不会自动修改数据库；宿主必须在受控启动阶段调用 [[migrate]]，或显式选择
  * [[PostgresAgentPersistence.migratedLayer]]。核心控制面与 1536 维知识库具有不同扩展权限和生命周期，因此使用独立 location、schema 与 history
  * table，不能把两个 V001 放进同一个 Flyway 实例或让两套 history 共同管理同一 schema。
  */
object AgentPostgresMigrations:
  val DefaultLocation: String =
    "classpath:com/zyblw/agent/persistence/postgres/migration"

  val OptionalPgVectorLocation: String =
    "classpath:com/zyblw/agent/persistence/postgres/optional/pgvector_1536_v0_4"

  /** 已随 0.3.0 发布的 pgvector location，只保留 checksum/源码审计，不用于 0.4 fresh install。 */
  val LegacyPgVector03Location: String =
    "classpath:com/zyblw/agent/persistence/postgres/optional/pgvector"

  val DefaultHistoryTable: String = "flyway_zyblw_agent_schema_history"

  val Knowledge1536HistoryTable: String = "flyway_zyblw_agent_knowledge_1536_history"

  /** 0.4 知识索引的专属 PostgreSQL schema；与核心控制面隔离 Flyway 生命周期和对象命名空间。 */
  val Knowledge1536Schema: String = "zyblw_agent_knowledge"

  private val CoreRelations = Chunk(
    "agent_runs",
    "agent_events",
    "tool_executions",
    "agent_messages",
    "agent_steps",
    "model_calls",
    "approval_requests",
    "usage_records",
    "agent_memories",
    "agent_memory_audit",
    "agent_run_commands",
    "agent_run_dispatch",
    "agent_business_operations",
    "agent_outbox_events",
    "agent_inbox_messages",
    "agent_compensations",
    "agent_embedding_cache",
    "agent_embedding_quota_windows",
    "agent_embedding_quota_reservations",
    "agent_eval_snapshots",
    "agent_workflow_checkpoints",
    "agent_workflow_node_executions",
    "agent_workflow_waits",
    "agent_workflow_signals"
  )

  private val Knowledge1536Relations = Chunk(
    "agent_knowledge_documents",
    "agent_knowledge_chunk_staging",
    "agent_knowledge_chunks"
  )

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
      _         <- ZIO
        .fail(
          new IllegalArgumentException(
            "0.4 knowledge location 必须通过 migrateKnowledge1536 执行，不能绕过专属 schema"
          )
        )
        .when(validated.locations.contains(OptionalPgVectorLocation))
      result <- runMigration(dataSource, validated)
      _      <- verifyCore(dataSource).when(validated.locations.contains(DefaultLocation))
    yield result

  /** 创建或校验 1536 维知识索引 schema。
    *
    * 该入口使用专属 schema 和独立 history table，因此可在核心 migration 之前或之后执行；两者不会发生 V001 版本冲突，也不会把非空 `public` schema
    * 误判为待接管对象。迁移成功后还会检查 vector 扩展版本、三个关键表、heading_path 谱系列以及两张向量表的真实 `vector(1536)` 类型，避免 history
    * 成功但对象被人工破坏后继续启动。
    */
  def migrateKnowledge1536(
      dataSource: DataSource,
      config: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig.knowledge1536
  ): Task[AgentPostgresMigrationResult] =
    for
      validated <- ZIO.fromEither(config.validated).mapError(message => new IllegalArgumentException(message))
      _         <- ZIO
        .fail(new IllegalArgumentException("knowledge migration 必须只使用 0.4 的 1536 维 pgvector location"))
        .unless(validated.locations == List(OptionalPgVectorLocation))
      result <- runMigration(dataSource, validated, Some(Knowledge1536Schema))
      _      <- verifyKnowledge1536(dataSource)
    yield result

  /** 常见完整宿主的一次性启动入口：先迁移核心控制面，再迁移知识库，各自保留独立审计 history。 */
  def migrateCoreAndKnowledge1536(
      dataSource: DataSource,
      coreConfig: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig(),
      knowledgeConfig: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig.knowledge1536
  ): Task[AgentPostgresBootstrapResult] =
    for
      core      <- migrate(dataSource, coreConfig)
      knowledge <- migrateKnowledge1536(dataSource, knowledgeConfig)
    yield AgentPostgresBootstrapResult(core, knowledge)

  /** 校验 Flyway 之外的核心结构后置条件。Flyway checksum 发现脚本漂移，本检查发现关键表被人工删除。 */
  def verifyCore(dataSource: DataSource): Task[AgentPostgresSchemaVerification] =
    verifyRelations(dataSource, "core", CoreRelations)

  /** 校验知识 schema、pgvector 版本、谱系列和固定向量维度。 */
  def verifyKnowledge1536(dataSource: DataSource): Task[AgentPostgresSchemaVerification] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        verifyRelations(connection, "knowledge-1536", Knowledge1536Relations, Some(Knowledge1536Schema))
        val extensionVersion = querySingleString(
          connection,
          """SELECT extension.extversion
            |FROM pg_extension extension
            |JOIN pg_namespace namespace ON namespace.oid = extension.extnamespace
            |WHERE extension.extname = 'vector' AND namespace.nspname = 'public'""".stripMargin
        ).getOrElse(throw IllegalStateException("knowledge-1536 缺少 public schema 中的 PostgreSQL vector 扩展"))
        if !versionAtLeast(extensionVersion, 0, 8, 0) then
          throw IllegalStateException("knowledge-1536 要求 pgvector >= 0.8.0")
        val vectorTypes = Chunk("agent_knowledge_chunk_staging", "agent_knowledge_chunks").map { table =>
          val sql =
            """SELECT format_type(attribute.atttypid, attribute.atttypmod)
               |FROM pg_attribute attribute
               |WHERE attribute.attrelid = to_regclass(
               |  quote_ident(?) || '.' || quote_ident(?)
               |)
               |  AND attribute.attname = 'embedding'
               |  AND NOT attribute.attisdropped""".stripMargin
          table -> querySingleString(connection, sql, Chunk(Knowledge1536Schema, table)).getOrElse("missing")
        }
        vectorTypes.find(_._2 != "vector(1536)").foreach { case (table, actual) =>
          throw IllegalStateException(s"knowledge-1536 表 $table 的 embedding 类型错误: $actual")
        }
        val lineageColumns = requiredColumns(
          connection,
          Knowledge1536Schema,
          "agent_knowledge_chunks",
          Chunk(
            "parent_id",
            "lineage_ordinal",
            "previous_chunk_id",
            "next_chunk_id",
            "heading_path",
            "page_numbers",
            "origins",
            "block_ids"
          )
        )
        AgentPostgresSchemaVerification(
          "knowledge-1536",
          Knowledge1536Relations,
          lineageColumns,
          Some(extensionVersion)
        )
      finally connection.close()
    }

  private def runMigration(
      dataSource: DataSource,
      config: AgentPostgresMigrationConfig,
      managedSchema: Option[String] = None
  ): Task[AgentPostgresMigrationResult] =
    ZIO.attemptBlocking {
      val flywayConfiguration = Flyway
        .configure()
        .dataSource(dataSource)
        .locations(config.locations*)
        .table(config.historyTable)
        .baselineOnMigrate(config.baselineOnMigrate)
        .validateOnMigrate(true)
        .cleanDisabled(true)
      managedSchema.foreach(schema => flywayConfiguration.defaultSchema(schema).schemas(schema))
      val migration = flywayConfiguration
        .load()
        .migrate()
      if !migration.success then throw IllegalStateException("Flyway migration did not report success")
      AgentPostgresMigrationResult(
        success = migration.success,
        migrationsExecuted = migration.migrationsExecuted,
        targetVersion = Option(migration.targetSchemaVersion).map(_.toString)
      )
    }

  private def verifyRelations(
      dataSource: DataSource,
      component: String,
      relations: Chunk[String]
  ): Task[AgentPostgresSchemaVerification] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        verifyRelations(connection, component, relations, None)
        AgentPostgresSchemaVerification(component, relations, Chunk.empty, None)
      finally connection.close()
    }

  private def verifyRelations(
      connection: Connection,
      component: String,
      relations: Chunk[String],
      schema: Option[String]
  ): Unit =
    val missing = relations.filter { relation =>
      val statement = connection.prepareStatement(
        "SELECT to_regclass(quote_ident(COALESCE(?, current_schema())) || '.' || quote_ident(?))"
      )
      try
        statement.setString(1, schema.orNull)
        statement.setString(2, relation)
        val result = statement.executeQuery()
        !result.next() || result.getString(1) == null
      finally statement.close()
    }
    if missing.nonEmpty then
      throw IllegalStateException(s"$component migration 缺少关键关系: ${missing.mkString(",")}")

  private def requiredColumns(
      connection: Connection,
      schema: String,
      table: String,
      columns: Chunk[String]
  ): Chunk[String] =
    val missing = columns.filter { column =>
      val statement = connection.prepareStatement(
        """SELECT EXISTS (
          |  SELECT 1 FROM pg_attribute
          |  WHERE attrelid = to_regclass(quote_ident(?) || '.' || quote_ident(?))
          |    AND attname = ? AND NOT attisdropped
          |)""".stripMargin
      )
      try
        statement.setString(1, schema)
        statement.setString(2, table)
        statement.setString(3, column)
        val result = statement.executeQuery()
        !result.next() || !result.getBoolean(1)
      finally statement.close()
    }
    if missing.nonEmpty then
      throw IllegalStateException(s"knowledge-1536 表 $table 缺少谱系列: ${missing.mkString(",")}")
    columns

  private def querySingleString(
      connection: Connection,
      sql: String,
      parameters: Chunk[String] = Chunk.empty
  ): Option[String] =
    val statement = connection.prepareStatement(sql)
    try
      parameters.zipWithIndex.foreach { case (value, index) => statement.setString(index + 1, value) }
      val result = statement.executeQuery()
      if result.next() then Option(result.getString(1)) else None
    finally statement.close()

  private def versionAtLeast(actual: String, major: Int, minor: Int, patch: Int): Boolean =
    val parsed = actual.split("[^0-9]+").iterator.filter(_.nonEmpty).take(3).map(_.toInt).toVector.padTo(3, 0)
    parsed(0) > major ||
    (parsed(0) == major && parsed(1) > minor) ||
    (parsed(0) == major && parsed(1) == minor && parsed(2) >= patch)

/** 由宿主应用拥有的 migration 策略。
  *
  * `baselineOnMigrate` 默认 false；fresh baseline 不允许用它伪装接管未知 schema。核心和知识库 location 必须分开执行；知识库由框架固定到专属
  * schema，调用方只可配置 history 表名和有界 classpath 资源。
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
    else if baselineOnMigrate then Left("baselineOnMigrate is disabled for zyblw-agent fresh-install schemas")
    else if locations.isEmpty then Left("At least one Flyway classpath location is required")
    else if locations.exists(location => !location.startsWith("classpath:") || location.length > 500) then
      Left("Flyway locations must be bounded classpath resources")
    else Right(copy(locations = locations.distinct))

object AgentPostgresMigrationConfig:
  /** 0.4 的 1536 维知识库迁移配置。schema 由迁移入口固定；调用方通常只需覆盖 historyTable 名称，不应更换 location。 */
  val knowledge1536: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig(
    historyTable = AgentPostgresMigrations.Knowledge1536HistoryTable,
    locations = List(AgentPostgresMigrations.OptionalPgVectorLocation)
  )

/** [[AgentPostgresMigrations.migrate]] 返回的低耦合结果。 */
final case class AgentPostgresMigrationResult(
    success: Boolean,
    migrationsExecuted: Int,
    targetVersion: Option[String]
)

/** 核心与知识库两个独立 Flyway 生命周期的一次启动结果。 */
final case class AgentPostgresBootstrapResult(
    core: AgentPostgresMigrationResult,
    knowledge1536: AgentPostgresMigrationResult
)

/** 启动后数据库结构探针结果；只公开固定对象名和扩展版本，不包含 JDBC URL、用户名或 SQL 错误正文。 */
final case class AgentPostgresSchemaVerification(
    component: String,
    relations: Chunk[String],
    requiredColumns: Chunk[String],
    extensionVersion: Option[String]
)
