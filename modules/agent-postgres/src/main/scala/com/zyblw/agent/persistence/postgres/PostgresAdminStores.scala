package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunCommandStore
import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.{Instant, ZoneOffset}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** 管理面 PostgreSQL Adapter 共用的 JDBC 样板。
  *
  * 抽出来是因为三个 Adapter 都需要同一套“借连接、映射 SQLSTATE、写 TIMESTAMPTZ”的处理，而复制三份会让某一处 遗漏 SQLSTATE 分类时表现出与其它 Store 不一致的重试语义。
  */
private trait PostgresAdminSupport:
  protected def dataSource: DataSource

  /** 借用连接并保证归还；JDBC 阻塞调用全部走 blocking executor。 */
  protected def withConnection[A](use: Connection => Task[A]): IO[StoreError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(
            ZIO
              .attemptBlocking(dataSource.getConnection)
              .mapError(error => AgentError.PersistenceFailure("获取数据库连接失败", Some(error)))
          )(connection => ZIO.attemptBlocking(connection.close()).orDie)
          .flatMap(use)
      }
      .mapError {
        case error: StoreError => error
        case error             => databaseError("管理面数据库操作失败", error)
      }

  /** 与 `PostgresRunStore` 一致的 SQLSTATE 到框架错误分类，保证重试决策在所有 Store 上相同。 */
  protected def databaseError(operation: String, error: Throwable): StoreError = error match
    case sql: java.sql.SQLException =>
      val state     = Option(sql.getSQLState).getOrElse("unknown")
      val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
      AgentError.DatabaseFailure(operation, state, retryable, Some(sql))
    case other => AgentError.PersistenceFailure(operation, Some(other))

  /** 以 UTC `OffsetDateTime` 写入 `TIMESTAMPTZ`；PostgreSQL JDBC 不为裸 `Instant` 推断 SQL 类型。 */
  protected def setInstant(statement: PreparedStatement, index: Int, value: Instant): Unit =
    statement.setObject(index, value.atOffset(ZoneOffset.UTC))

  /** 读取 `TIMESTAMPTZ` 为 epoch 毫秒；管理面所有时间字段统一使用数字表示。 */
  protected def epochMilli(result: ResultSet, column: String): Long =
    Option(result.getTimestamp(column)).map(_.toInstant.toEpochMilli).getOrElse(0L)

  /** 读取可能为 SQL NULL 的文本列。 */
  protected def optionalString(result: ResultSet, column: String): Option[String] =
    Option(result.getString(column)).map(_.trim).filter(_.nonEmpty)

  /** 读取可能为 SQL NULL 的整数列；`getInt` 对 NULL 返回 0，必须配合 `wasNull` 判断。 */
  protected def optionalInt(result: ResultSet, column: String): Option[Int] =
    val value = result.getInt(column)
    Option.unless(result.wasNull())(value)

  /** 读取可能为 SQL NULL 的长整数列。 */
  protected def optionalLong(result: ResultSet, column: String): Option[Long] =
    val value = result.getLong(column)
    Option.unless(result.wasNull())(value)

/** Run 目录的 PostgreSQL 实现。
  *
  * 过滤全部下推到 SQL，并命中 V002 建立的生成列索引；只有最终一页的 `state_json` 会被解码。这一点很重要： 内存实现会把全部 Run 加载进堆，而管理台在生产环境面对的可能是数百万条 Run。
  *
  * 排序固定为 `(updated_at DESC, run_id DESC)`，与 `RunDirectory` 契约和内存实现完全一致，因此同一个游标 在两种实现下含义相同。
  */
final class PostgresRunDirectory(protected val dataSource: DataSource)
    extends RunDirectory
    with PostgresAdminSupport:

  def list(query: RunDirectoryQuery): IO[StoreError, RunDirectoryPage] = withConnection { connection =>
    val limit      = query.boundedLimit
    val conditions = ChunkBuilder.make[String]()
    val binders    = ChunkBuilder.make[(PreparedStatement, Int) => Unit]()

    query.tenantId.foreach { tenant =>
      conditions += "tenant_id = ?"
      binders += ((statement, index) => statement.setString(index, tenant))
    }
    query.agentId.foreach { agent =>
      conditions += "agent_id = ?"
      binders += ((statement, index) => statement.setString(index, agent))
    }
    if query.statuses.nonEmpty then
      // 用 = ANY(?) 而不是拼接 IN 列表：状态值来自枚举，但保持参数化可以让计划缓存复用同一条语句。
      conditions += "status = ANY(?)"
      val values = query.statuses.map(_.toString).toArray
      binders += ((statement, index) =>
        statement.setArray(index, statement.getConnection.createArrayOf("text", values.map(identity[Object])))
      )
    if query.awaitingApprovalOnly then conditions += "awaiting_approval"
    query.updatedAfterEpochMilli.foreach { millis =>
      conditions += "updated_at >= ?"
      binders += ((statement, index) => setInstant(statement, index, Instant.ofEpochMilli(millis)))
    }
    query.updatedBeforeEpochMilli.foreach { millis =>
      conditions += "updated_at <= ?"
      binders += ((statement, index) => setInstant(statement, index, Instant.ofEpochMilli(millis)))
    }
    query.cursor.foreach { cursor =>
      // 行值比较 (updated_at, run_id) < (?, ?) 让 PostgreSQL 直接在复合索引上定位游标位置，
      // 而拆成 OR 条件通常退化为顺序扫描。
      conditions += "(updated_at, run_id) < (?, ?::uuid)"
      binders += ((statement, index) =>
        setInstant(statement, index, CursorTime.toInstant(cursor.updatedAtEpochMicro))
      )
      binders += ((statement, index) => statement.setString(index, cursor.runId))
    }

    val where    = conditions.result()
    val whereSql = if where.isEmpty then "" else where.mkString(" WHERE ", " AND ", "")
    val bindings = binders.result()
    // 同时取出排序列本身：游标必须精确等于排序列的值，用 state_json 里的时间戳重建游标会因为
    // 两者精度不同而丢行。
    val sql =
      s"""SELECT state_json::text, updated_at FROM agent_runs$whereSql
         |ORDER BY updated_at DESC, run_id DESC
         |LIMIT ?""".stripMargin

    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(sql)
        try
          bindings.zipWithIndex.foreach((bind, offset) => bind(statement, offset + 1))
          // 多取一条用于判定 hasMore，避免额外一次 COUNT 查询。
          statement.setInt(bindings.length + 1, limit + 1)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[(RunSummaryView, Long)]()
          while result.next() do
            val view = RunSummaryView.from(
              result
                .getString(1)
                .fromJson[AgentState]
                .fold(error => throw IllegalStateException(error), identity)
            )
            builder += view -> CursorTime.epochMicro(result.getTimestamp(2).toInstant)
          builder.result()
        finally statement.close()
      }
      .map { rows =>
        val window  = rows.take(limit)
        val hasMore = rows.length > window.length
        RunDirectoryPage(
          window.map(_._1),
          window.lastOption
            .filter(_ => hasMore)
            .map((view, micro) => RunDirectoryCursor(micro, view.runId).encoded),
          hasMore
        )
      }
      .mapError(databaseError("查询 Run 目录失败", _))
  }

  /** 用一条 `GROUP BY status` 聚合首屏卡片，而不是为每个状态发一次 COUNT。 */
  def overview(tenantId: Option[String]): IO[StoreError, RunDirectoryOverview] = withConnection {
    connection =>
      val filter = tenantId.fold("")(_ => " WHERE tenant_id = ?")
      ZIO
        .attemptBlocking {
          val statement = connection.prepareStatement(
            s"""SELECT status, COUNT(*) AS total, COUNT(*) FILTER (WHERE awaiting_approval) AS approving
             |FROM agent_runs$filter GROUP BY status""".stripMargin
          )
          try
            tenantId.foreach(statement.setString(1, _))
            val result   = statement.executeQuery()
            val counts   = scala.collection.mutable.Map.empty[String, Long]
            var total    = 0L
            var awaiting = 0L
            while result.next() do
              val status = result.getString("status")
              val count  = result.getLong("total")
              counts.update(status, count)
              total += count
              awaiting += result.getLong("approving")
            (counts.toMap, total, awaiting)
          finally statement.close()
        }
        .mapError(databaseError("聚合 Run 目录总览失败", _))
        .flatMap { case (counts, total, awaiting) =>
          Clock.instant.map(now => RunDirectoryOverview(now.toEpochMilli, total, counts, awaiting))
        }
  }

object PostgresRunDirectory:
  val layer: URLayer[DataSource, RunDirectory] = ZLayer.fromFunction(PostgresRunDirectory.apply)

/** 运行时配置覆盖的 PostgreSQL 实现。
  *
  * 表是 append-only 的：每次写入插入一行新版本，历史行永不修改。这让配置存储同时是审计日志，也让 CAS 退化成 主键唯一约束——两个并发写入者都尝试插入 `expectedVersion +
  * 1`，其中一个必然撞到唯一冲突。
  */
final class PostgresRuntimeOverrideStore(protected val dataSource: DataSource)
    extends RuntimeOverrideStore
    with PostgresAdminSupport:
  import PostgresRuntimeOverrideStore.UniqueViolation

  def current: IO[StoreError, RuntimeOverrideRecord] = withConnection { connection =>
    ZIO
      .attemptBlocking(readLatest(connection).getOrElse(RuntimeOverrideRecord.initial))
      .mapError(databaseError("读取运行时配置覆盖失败", _))
  }

  def put(
      expectedVersion: Long,
      overrides: RuntimeOverrides,
      updatedBy: String,
      reason: String
  ): IO[StoreError, RuntimeOverrideRecord] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val next      = expectedVersion + 1L
        val statement = connection.prepareStatement(
          """INSERT INTO agent_runtime_overrides(version, overrides, updated_by, reason, updated_at)
            |SELECT ?, ?::jsonb, ?, ?, CURRENT_TIMESTAMP
            |WHERE COALESCE((SELECT MAX(version) FROM agent_runtime_overrides), 0) = ?
            |RETURNING version, overrides::text, updated_by, reason, updated_at""".stripMargin
        )
        try
          statement.setLong(1, next)
          statement.setString(2, overrides.toJson)
          statement.setString(3, updatedBy.take(200))
          statement.setString(4, reason.take(RuntimeOverrideRecord.MaxReasonLength))
          statement.setLong(5, expectedVersion)
          val result = statement.executeQuery()
          if result.next() then decode(result)
          else
            // WHERE 子句不成立意味着期间有人写入了新版本；重新读取真实版本以生成准确的冲突错误。
            throw OverrideVersionConflict(readLatest(connection).fold(0L)(_.version))
        finally statement.close()
      }
      .mapError {
        case OverrideVersionConflict(actual) =>
          AgentError.OptimisticLock(Version(expectedVersion.max(0L)), Version(actual.max(0L)))
        // 语句内的 MAX(version) 子查询在 READ COMMITTED 下看不到并发事务尚未提交的插入，因此两个管理员
        // 同时保存时 WHERE 对双方都成立，冲突最终由 version 主键拦截。这里必须把唯一冲突也归类为乐观锁，
        // 否则一次正常的并发编辑会以 500 返回，而管理台只有在收到 409 时才会提示重新加载。
        case error: java.sql.SQLException if error.getSQLState == UniqueViolation =>
          AgentError.OptimisticLock(Version(expectedVersion.max(0L)), Version(expectedVersion.max(0L) + 1L))
        case error => databaseError("写入运行时配置覆盖失败", error)
      }
  }

  def history(limit: Int): IO[StoreError, Chunk[RuntimeOverrideRecord]] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT version, overrides::text, updated_by, reason, updated_at
            |FROM agent_runtime_overrides ORDER BY version DESC LIMIT ?""".stripMargin
        )
        try
          statement.setInt(1, limit.max(1).min(RuntimeOverrideStore.MaxHistoryLimit))
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[RuntimeOverrideRecord]()
          while result.next() do builder += decode(result)
          builder.result()
        finally statement.close()
      }
      .mapError(databaseError("读取运行时配置覆盖历史失败", _))
  }

  /** 读取最新版本；表为空返回 None，由调用方决定使用初始记录还是报告冲突。 */
  private def readLatest(connection: Connection): Option[RuntimeOverrideRecord] =
    val statement = connection.prepareStatement(
      """SELECT version, overrides::text, updated_by, reason, updated_at
        |FROM agent_runtime_overrides ORDER BY version DESC LIMIT 1""".stripMargin
    )
    try
      val result = statement.executeQuery()
      Option.when(result.next())(decode(result))
    finally statement.close()

  /** 解码失败直接抛出：一份无法解析的覆盖不能被当成“没有覆盖”静默忽略，那会让部署悄悄回到基线策略。 */
  private def decode(result: ResultSet): RuntimeOverrideRecord = RuntimeOverrideRecord(
    version = result.getLong("version"),
    overrides = result
      .getString("overrides")
      .fromJson[RuntimeOverrides]
      .fold(error => throw IllegalStateException(s"运行时配置覆盖解码失败: $error"), identity),
    updatedBy = result.getString("updated_by"),
    reason = Option(result.getString("reason")).getOrElse(""),
    updatedAtEpochMilli = epochMilli(result, "updated_at")
  )

final private case class OverrideVersionConflict(actual: Long) extends RuntimeException

object PostgresRuntimeOverrideStore:
  /** PostgreSQL 唯一约束冲突的 SQLSTATE。 */
  private val UniqueViolation: String = "23505"

  val layer: URLayer[DataSource, RuntimeOverrideStore] =
    ZLayer.fromFunction(PostgresRuntimeOverrideStore.apply)

/** 摄入任务的 PostgreSQL 实现。 */
final class PostgresIngestionJobStore(protected val dataSource: DataSource)
    extends IngestionJobStore
    with PostgresAdminSupport:

  def create(job: IngestionJobView): IO[StoreError, IngestionJobView] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(
          """INSERT INTO agent_ingestion_jobs
            |(job_id, tenant_id, source_uri, file_name, media_type, status, progress_percent,
            | document_id, index_version, chunk_count, failure_code, submitted_by, created_at, updated_at)
            |VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
        )
        try
          statement.setString(1, job.jobId)
          statement.setString(2, job.tenantId)
          statement.setString(3, job.sourceUri)
          statement.setString(4, job.fileName)
          statement.setString(5, job.mediaType)
          statement.setString(6, job.status.toString)
          statement.setInt(7, job.progressPercent)
          statement.setString(8, job.documentId.orNull)
          setNullableLong(statement, 9, job.indexVersion)
          setNullableInt(statement, 10, job.chunkCount)
          statement.setString(11, job.failureCode.orNull)
          statement.setString(12, job.submittedBy.take(200))
          setInstant(statement, 13, Instant.ofEpochMilli(job.createdAtEpochMilli))
          setInstant(statement, 14, Instant.ofEpochMilli(job.updatedAtEpochMilli))
          statement.executeUpdate()
          job
        finally statement.close()
      }
      .mapError(databaseError("登记摄入任务失败", _))
  }

  /** 用 `COALESCE(?, column)` 实现部分更新，让调用方只传本次真正确定的字段。
    *
    * 例如进入 Embedding 阶段时还不知道 `chunkCount`，传 None 必须保留上一阶段写入的值，而不是把它抹成 NULL。
    */
  def transition(
      jobId: String,
      status: IngestionJobStatus,
      documentId: Option[String],
      indexVersion: Option[Long],
      chunkCount: Option[Int],
      failureCode: Option[String]
  ): IO[StoreError, IngestionJobView] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(
          """UPDATE agent_ingestion_jobs SET
            |  status = ?, progress_percent = ?,
            |  document_id = COALESCE(?, document_id),
            |  index_version = COALESCE(?, index_version),
            |  chunk_count = COALESCE(?, chunk_count),
            |  failure_code = COALESCE(?, failure_code),
            |  updated_at = CURRENT_TIMESTAMP
            |WHERE job_id = ?::uuid
            |RETURNING job_id, tenant_id, source_uri, file_name, media_type, status, progress_percent,
            |  document_id, index_version, chunk_count, failure_code, submitted_by, created_at, updated_at""".stripMargin
        )
        try
          statement.setString(1, status.toString)
          statement.setInt(2, status.progressPercent)
          statement.setString(3, documentId.orNull)
          setNullableLong(statement, 4, indexVersion)
          setNullableInt(statement, 5, chunkCount)
          statement.setString(6, failureCode.orNull)
          statement.setString(7, jobId)
          val result = statement.executeQuery()
          if result.next() then decode(result) else throw MissingIngestionJob(jobId)
        finally statement.close()
      }
      .mapError {
        case MissingIngestionJob(id) => AgentError.PersistenceFailure(s"摄入任务不存在: $id")
        case error                   => databaseError("推进摄入任务状态失败", error)
      }
  }

  def get(jobId: String): IO[StoreError, Option[IngestionJobView]] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement =
          connection.prepareStatement(s"SELECT $Columns FROM agent_ingestion_jobs WHERE job_id = ?::uuid")
        try
          statement.setString(1, jobId)
          val result = statement.executeQuery()
          Option.when(result.next())(decode(result))
        finally statement.close()
      }
      .mapError(databaseError("读取摄入任务失败", _))
      // 非法 UUID 文本会让 PostgreSQL 报 22P02；对查询接口而言这等价于“不存在”，不是服务端故障。
      .catchSome { case AgentError.DatabaseFailure(_, "22P02", _, _) => ZIO.none }
  }

  def list(tenantId: Option[String], limit: Int): IO[StoreError, Chunk[IngestionJobView]] = withConnection {
    connection =>
      ZIO
        .attemptBlocking {
          val filter    = tenantId.fold("")(_ => " WHERE tenant_id = ?")
          val statement = connection.prepareStatement(
            s"""SELECT $Columns FROM agent_ingestion_jobs$filter
               |ORDER BY created_at DESC, job_id DESC LIMIT ?""".stripMargin
          )
          try
            val limitIndex = tenantId.fold(1) { value =>
              statement.setString(1, value); 2
            }
            statement.setInt(limitIndex, limit.max(1).min(IngestionJobStore.MaxLimit))
            val result  = statement.executeQuery()
            val builder = ChunkBuilder.make[IngestionJobView]()
            while result.next() do builder += decode(result)
            builder.result()
          finally statement.close()
        }
        .mapError(databaseError("列出摄入任务失败", _))
  }

  private def setNullableLong(statement: PreparedStatement, index: Int, value: Option[Long]): Unit =
    value.fold(statement.setNull(index, java.sql.Types.BIGINT))(statement.setLong(index, _))

  private def setNullableInt(statement: PreparedStatement, index: Int, value: Option[Int]): Unit =
    value.fold(statement.setNull(index, java.sql.Types.INTEGER))(statement.setInt(index, _))

  private def decode(result: ResultSet): IngestionJobView = IngestionJobView(
    jobId = result.getString("job_id"),
    tenantId = result.getString("tenant_id"),
    sourceUri = result.getString("source_uri"),
    fileName = result.getString("file_name"),
    mediaType = result.getString("media_type"),
    status = IngestionJobStatus.values
      .find(_.toString == result.getString("status"))
      .getOrElse(throw IllegalStateException(s"未知摄入任务状态: ${result.getString("status")}")),
    progressPercent = result.getInt("progress_percent"),
    documentId = optionalString(result, "document_id"),
    indexVersion = optionalLong(result, "index_version"),
    chunkCount = optionalInt(result, "chunk_count"),
    failureCode = optionalString(result, "failure_code"),
    submittedBy = result.getString("submitted_by"),
    createdAtEpochMilli = epochMilli(result, "created_at"),
    updatedAtEpochMilli = epochMilli(result, "updated_at")
  )

  private val Columns: String =
    """job_id, tenant_id, source_uri, file_name, media_type, status, progress_percent,
      |document_id, index_version, chunk_count, failure_code, submitted_by, created_at, updated_at""".stripMargin
      .replace("\n", " ")

final private case class MissingIngestionJob(jobId: String) extends RuntimeException

object PostgresIngestionJobStore:
  val layer: URLayer[DataSource, IngestionJobStore] = ZLayer.fromFunction(PostgresIngestionJobStore.apply)

/** 队列运维的 PostgreSQL 实现。
  *
  * 队列快照与按 ID 重排复用已发布的 `RunCommandStore`；这里只补上它契约里没有的“列出全部死信”查询， 该查询命中 V001 已建立的
  * `agent_run_commands_dead_letter_idx` 部分索引。
  */
final class PostgresOpsAdmin(protected val dataSource: DataSource, commands: RunCommandStore)
    extends PostgresAdminSupport:

  /** 组装成 core 的 SPI 形态，宿主装配时无需知道死信查询是如何实现的。 */
  val service: OpsAdminService = OpsAdminService.fromCommandStore(commands, deadLetters)

  /** 只 SELECT 视图需要的列。
    *
    * `command_type` 是独立列而不是从 `payload` JSONB 里取，因此死信清单完全不需要读取命令正文—— 审批决定与取消原因不会离开数据库。
    */
  private def deadLetters(limit: Int): IO[StoreError, Chunk[DeadLetterCommandView]] = withConnection {
    connection =>
      ZIO
        .attemptBlocking {
          val statement = connection.prepareStatement(
            """SELECT command_id, run_id, command_type, attempt, manual_retry_count,
              |  last_failure, created_at, updated_at
              |FROM agent_run_commands WHERE status = 'DeadLetter'
              |ORDER BY updated_at DESC, command_id DESC LIMIT ?""".stripMargin
          )
          try
            statement.setInt(1, limit.max(1).min(OpsAdminService.MaxDeadLetterLimit))
            val result  = statement.executeQuery()
            val builder = ChunkBuilder.make[DeadLetterCommandView]()
            while result.next() do
              builder += DeadLetterCommandView(
                commandId = result.getString("command_id"),
                runId = result.getString("run_id"),
                commandType = result.getString("command_type"),
                attempt = result.getInt("attempt"),
                manualRetryCount = result.getInt("manual_retry_count"),
                lastFailure = optionalString(result, "last_failure"),
                createdAtEpochMilli = epochMilli(result, "created_at"),
                updatedAtEpochMilli = epochMilli(result, "updated_at")
              )
            builder.result()
          finally statement.close()
        }
        .mapError(databaseError("列出死信命令失败", _))
  }

object PostgresOpsAdmin:
  /** 直接构造管理面 SPI。 */
  val layer: URLayer[DataSource & RunCommandStore, OpsAdminService] =
    ZLayer.fromFunction((dataSource: DataSource, commands: RunCommandStore) =>
      PostgresOpsAdmin(dataSource, commands).service
    )
