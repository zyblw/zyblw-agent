package com.zyblw.agent.persistence.postgres

import org.flywaydb.core.Flyway
import zio.*

import java.sql.Connection
import javax.sql.DataSource

/** 框架拥有的 Flyway 资源与显式 migration 入口。
  *
  * JAR 出现在 classpath 不会自动修改数据库；宿主必须在受控启动阶段调用 [[migrate]]，或显式选择
  * [[PostgresAgentPersistence.migratedLayer]]。核心控制面与 1024 维知识库具有不同扩展权限和生命周期，因此使用独立 location、schema 与 history
  * table，不能把两个 V001 放进同一个 Flyway 实例或让两套 history 共同管理同一 schema。
  */
object AgentPostgresMigrations:
  val DefaultLocation: String =
    "classpath:com/zyblw/agent/persistence/postgres/migration"

  /** 1024 维 embedding 的独立 fresh-install RAG baseline。 */
  val OptionalPgVector1024Location: String =
    "classpath:com/zyblw/agent/persistence/postgres/optional/pgvector_1024_v0_6"

  /** 已随 0.3.0 发布的 pgvector location，只保留 checksum/源码审计，不用于 0.4 fresh install。 */
  val LegacyPgVector03Location: String =
    "classpath:com/zyblw/agent/persistence/postgres/optional/pgvector"

  val DefaultHistoryTable: String = "flyway_zyblw_agent_schema_history"

  val Knowledge1024HistoryTable: String = "flyway_zyblw_agent_knowledge_1024_history"

  /** 1024 知识索引的专属 PostgreSQL schema；与核心控制面隔离 Flyway 生命周期和对象命名空间。 */
  val Knowledge1024Schema: String = "zyblw_agent_knowledge"

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
    "agent_workflow_signals",
    "agent_runtime_overrides",
    "agent_ingestion_jobs"
  )

  private val KnowledgeRelations = Chunk(
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
            "1024 knowledge location 必须通过 migrateKnowledge1024 执行，不能绕过专属 schema"
          )
        )
        .when(
          validated.locations.contains(OptionalPgVector1024Location)
        )
      _ <- rejectExistingCoreRelationsOnFreshSharedBaseline(dataSource, validated.historyTable)
        .when(validated.isSharedPublicSchemaBaseline)
      result <- runMigration(dataSource, validated)
      _      <- verifyCore(dataSource).when(validated.locations.contains(DefaultLocation))
    yield result

  /** 创建或校验 1024 维知识索引 schema。 */
  def migrateKnowledge1024(
      dataSource: DataSource,
      config: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig.knowledge1024
  ): Task[AgentPostgresMigrationResult] =
    for
      validated <- ZIO.fromEither(config.validated).mapError(message => new IllegalArgumentException(message))
      _         <- ZIO
        .fail(new IllegalArgumentException("knowledge migration 必须只使用 1024 维 pgvector location"))
        .unless(validated.locations == List(OptionalPgVector1024Location))
      result <- runMigration(dataSource, validated, Some(Knowledge1024Schema))
      _      <- verifyKnowledge1024(dataSource)
    yield result

  /** 一站式启动入口：核心控制面 + 1024 维知识索引，各自保留独立 Flyway history。 */
  def migrateCoreAndKnowledge1024(
      dataSource: DataSource,
      coreConfig: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig(),
      knowledgeConfig: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig.knowledge1024
  ): Task[AgentPostgresBootstrapResult] =
    for
      core      <- migrate(dataSource, coreConfig)
      knowledge <- migrateKnowledge1024(dataSource, knowledgeConfig)
    yield AgentPostgresBootstrapResult(core, knowledge)

  /** 校验 Flyway 之外的核心结构后置条件。Flyway checksum 发现脚本漂移，本检查发现关键表被人工删除。 */
  def verifyCore(dataSource: DataSource): Task[AgentPostgresSchemaVerification] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        verifyRelations(connection, "core", CoreRelations, None)
        val schema = querySingleString(connection, "SELECT current_schema()")
          .getOrElse("public")
        verifyCommentCoverage(connection, "core", schema, CoreRelations)
        AgentPostgresSchemaVerification("core", CoreRelations, Chunk.empty, None)
      finally connection.close()
    }

  /** 1024 baseline 的启动后置探针，校验 manifest、谱系、ACL 与向量维度契约。 */
  def verifyKnowledge1024(dataSource: DataSource): Task[AgentPostgresSchemaVerification] =
    verifyKnowledgeDimension(dataSource, "knowledge-1024", Knowledge1024Schema, 1024)

  private def verifyKnowledgeDimension(
      dataSource: DataSource,
      component: String,
      schema: String,
      dimension: Int
  ): Task[AgentPostgresSchemaVerification] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        verifyRelations(connection, component, KnowledgeRelations, Some(schema))
        val extensionVersion = querySingleString(
          connection,
          "SELECT extversion FROM pg_extension WHERE extname = 'vector'"
        ).getOrElse(throw IllegalStateException(s"$component 缺少 PostgreSQL vector 扩展"))
        if !versionAtLeast(extensionVersion, 0, 8, 0) then
          throw IllegalStateException(s"$component 要求 pgvector >= 0.8.0")
        val columns = requiredColumns(
          connection,
          schema,
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
        Chunk("agent_knowledge_chunk_staging", "agent_knowledge_chunks").foreach { table =>
          val actual = querySingleString(
            connection,
            "SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = to_regclass(quote_ident(?) || '.' || quote_ident(?)) AND attname = 'embedding' AND NOT attisdropped",
            Chunk(schema, table)
          ).getOrElse("missing")
          if actual != s"vector($dimension)" then
            throw IllegalStateException(s"$component 表 $table 的 embedding 类型错误: $actual")
        }
        verifyCommentCoverage(connection, component, schema, KnowledgeRelations)
        AgentPostgresSchemaVerification(component, KnowledgeRelations, columns, Some(extensionVersion))
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
      config.baselineVersion.foreach(version => flywayConfiguration.baselineVersion(version))
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
      connection: Connection,
      component: String,
      relations: Chunk[String],
      schema: Option[String]
  ): Unit =
    val missing = relations.filter(relation => !relationExists(connection, schema, relation))
    if missing.nonEmpty then
      throw IllegalStateException(s"$component migration 缺少关键关系: ${missing.mkString(",")}")

  /** 宿主业务表可与 agent core 共用 public，但绝不能让首次 baseline 静默接管已有 agent 对象。
    *
    * 此检查只服务于 [[AgentPostgresMigrationConfig.sharedPublicSchema]]：已有普通业务表、Flyway 记录或 pgvector 扩展时允许创建 agent
    * history 的 version 0 基线；只要发现任一 framework core relation，就拒绝启动并要求使用 空库或经过审查的迁移路径。
    */
  private def rejectExistingCoreRelationsOnFreshSharedBaseline(
      dataSource: DataSource,
      historyTable: String
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        // 已有 agent history 代表此前已由本框架接管；此时交给 Flyway 校验 migration 状态，不能把
        // 正常存在的 core 表误报为旧库冲突。只有 history 不存在时才执行 version-0 baseline 前检查。
        if !relationExists(connection, None, historyTable) then
          val existing = CoreRelations.filter(relation => relationExists(connection, None, relation))
          if existing.nonEmpty then
            throw IllegalStateException(
              s"拒绝对包含既有 zyblw-agent core 表的 public schema 执行 baseline: ${existing.mkString(",")}. " +
                "请使用空数据库，或先审查并迁移/清理这些旧 agent 表。"
            )
      finally connection.close()
    }

  private def relationExists(connection: Connection, schema: Option[String], relation: String): Boolean =
    val statement = connection.prepareStatement(
      "SELECT to_regclass(quote_ident(COALESCE(?, current_schema())) || '.' || quote_ident(?))"
    )
    try
      statement.setString(1, schema.orNull)
      statement.setString(2, relation)
      val result = statement.executeQuery()
      try result.next() && result.getString(1) != null
      finally result.close()
    finally statement.close()

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
      throw IllegalStateException(s"knowledge schema 表 $table 缺少谱系列: ${missing.mkString(",")}")
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

  private def querySingleLong(
      connection: Connection,
      sql: String,
      schema: String,
      relations: Chunk[String]
  ): Long =
    val statement = connection.prepareStatement(sql)
    try
      statement.setString(1, schema)
      statement.setArray(2, connection.createArrayOf("text", relations.toArray))
      val result = statement.executeQuery()
      try
        if !result.next() then throw IllegalStateException("schema comment probe did not return a row")
        result.getLong(1)
      finally result.close()
    finally statement.close()

  private def verifyCommentCoverage(
      connection: Connection,
      component: String,
      schema: String,
      relations: Chunk[String]
  ): Unit =
    val uncommentedTables = querySingleLong(
      connection,
      """SELECT count(*)
        |FROM pg_class relation
        |JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
        |WHERE namespace.nspname = ?
        |  AND relation.relname::text = ANY(?)
        |  AND relation.relkind = 'r'
        |  AND NULLIF(btrim(obj_description(relation.oid, 'pg_class')), '') IS NULL""".stripMargin,
      schema,
      relations
    )
    if uncommentedTables != 0 then
      throw IllegalStateException(s"$component 存在 $uncommentedTables 张没有数据字典说明的表")
    val uncommentedColumns = querySingleLong(
      connection,
      """SELECT count(*)
        |FROM pg_attribute attribute
        |JOIN pg_class relation ON relation.oid = attribute.attrelid
        |JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
        |WHERE namespace.nspname = ?
        |  AND relation.relname::text = ANY(?)
        |  AND relation.relkind = 'r'
        |  AND attribute.attnum > 0
        |  AND NOT attribute.attisdropped
        |  AND NULLIF(btrim(col_description(relation.oid, attribute.attnum)), '') IS NULL""".stripMargin,
      schema,
      relations
    )
    if uncommentedColumns != 0 then
      throw IllegalStateException(
        s"$component 存在 $uncommentedColumns 个没有数据字典说明的字段"
      )

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
    baselineOnMigrate: Boolean = false,
    baselineVersion: Option[String] = None
):
  /** 唯一允许 agent core 使用 Flyway baseline 的受限形态。
    *
    * 宿主的业务表和 `vector` 扩展已存在时，Flyway 必须先写入 version 0 history，才能继续运行 agent 自己的 V001+。 只允许默认 core location、默认
    * history table 与 version 0；调用 migration 前还会检查 public 中不存在旧 agent 表。
    */
  private[postgres] def isSharedPublicSchemaBaseline: Boolean =
    baselineOnMigrate &&
      baselineVersion.contains("0") &&
      locations == List(AgentPostgresMigrations.DefaultLocation) &&
      historyTable == AgentPostgresMigrations.DefaultHistoryTable

  private[postgres] def validated: Either[String, AgentPostgresMigrationConfig] =
    val validIdentifier = historyTable.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
    if !validIdentifier then
      Left("Flyway history table must be a PostgreSQL identifier of at most 63 characters")
    else if baselineOnMigrate && !isSharedPublicSchemaBaseline then
      Left("baselineOnMigrate is only allowed for the explicit shared-public-schema version 0 policy")
    else if !baselineOnMigrate && baselineVersion.nonEmpty then
      Left("baselineVersion requires the explicit shared-public-schema baseline policy")
    else if locations.isEmpty then Left("At least one Flyway classpath location is required")
    else if locations.exists(location => !location.startsWith("classpath:") || location.length > 500) then
      Left("Flyway locations must be bounded classpath resources")
    else Right(copy(locations = locations.distinct))

object AgentPostgresMigrationConfig:
  /** 独立宿主与 agent 共用非空 `public` schema 的安全 core 策略。
    *
    * 首次仅当 history 不存在且 `public` 不包含 agent core 表时使用；后续启动以已创建的 history 继续 Flyway 校验。这让业务表、宿主 Flyway 表和
    * pgvector 扩展不会阻止全新 agent 安装，同时不会把旧 agent 数据伪装成已迁移状态。
    */
  val sharedPublicSchema: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig(
    baselineOnMigrate = true,
    baselineVersion = Some("0")
  )

  /** 1024 维知识库迁移配置。 */
  val knowledge1024: AgentPostgresMigrationConfig = AgentPostgresMigrationConfig(
    historyTable = AgentPostgresMigrations.Knowledge1024HistoryTable,
    locations = List(AgentPostgresMigrations.OptionalPgVector1024Location)
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
    knowledge1024: AgentPostgresMigrationResult
)

/** 启动后数据库结构探针结果；只公开固定对象名和扩展版本，不包含 JDBC URL、用户名或 SQL 错误正文。 */
final case class AgentPostgresSchemaVerification(
    component: String,
    relations: Chunk[String],
    requiredColumns: Chunk[String],
    extensionVersion: Option[String]
)
