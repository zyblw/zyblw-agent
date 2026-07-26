package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{Connection, ResultSet, SQLException, Timestamp}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL 评测趋势仓库的容量配置。
  *
  * @param maxSnapshotBytes
  *   单条低敏快照 JSON 的最大 UTF-8 字节数；写入和读取都执行相同上限，防止异常行制造堆内存尖峰
  * @param maxHistoryLimit
  *   单次历史查询允许的最大行数；调用方需要更长趋势时应使用离线分析，而不是在发布门禁中无界读取
  */
final case class PostgresEvalTrendStoreConfig(
    maxSnapshotBytes: Int = 2 * 1024 * 1024,
    maxHistoryLimit: Int = 100_000
):
  require(
    maxSnapshotBytes >= 1024 && maxSnapshotBytes <= 16 * 1024 * 1024,
    "maxSnapshotBytes 必须位于 1KiB..16MiB"
  )
  require(maxHistoryLimit >= 1 && maxHistoryLimit <= 1_000_000, "maxHistoryLimit 必须位于 1..1000000")

/** 使用 PostgreSQL 保存长期低敏评测趋势。
  *
  * 该 Adapter 与 `FileEvalTrendStore` 实现相同语义，但面向多节点 CI 和长期查询：
  *
  *   - `evaluation_id` 是不可变主键；
  *   - `INSERT ... ON CONFLICT DO NOTHING` 负责并发幂等仲裁；
  *   - 同 ID 同快照成功，同 ID 不同快照明确冲突；
  *   - 每行保存 SHA-256，并在读取时重新计算和执行完整领域校验；
  *   - 基线查询使用 `(kind, suite, dataset, version, passed, finished time)` 复合/部分索引；
  *   - JDBC 只在 ZIO blocking executor 中运行，连接通过 `Scope` 保证归还；
  *   - Adapter 复用宿主提供的 `DataSource`，不会自行创建第二个连接池。
  *
  * 表中 `snapshot_json` 已经是 `EvalSuiteSnapshot` 的低敏投影，不包含问题、回答、引用正文或 `EvalGrade.details`。数据库权限
  * 仍应遵守最小权限：发布任务只需要该表的 SELECT/INSERT，不需要 UPDATE/DELETE。
  *
  * @param dataSource
  *   由宿主应用创建、监控并统一限流的共享连接池
  * @param config
  *   单行和查询容量上限
  */
final class PostgresEvalTrendStore(
    dataSource: DataSource,
    config: PostgresEvalTrendStoreConfig = PostgresEvalTrendStoreConfig()
) extends EvalTrendStore:
  import PostgresEvalTrendStore.*

  /** 幂等追加一份低敏快照。
    *
    * PostgreSQL 唯一索引负责不同进程之间的线性化点。冲突后必须读取已有行并重新验证完整内容，不能仅因为主键存在就把 “同 ID 不同候选”误判为成功。
    *
    * @param snapshot
    *   已由 Eval 投影器产生或调用方手工构造的候选快照
    */
  override def append(snapshot: EvalSuiteSnapshot): IO[AgentError, Unit] =
    for
      _       <- EvalSuiteSnapshot.validate(snapshot)
      encoded <- encode(snapshot)
      _       <- withConnection { connection =>
        insert(connection, snapshot, encoded).flatMap {
          case true  => ZIO.unit
          case false =>
            loadByEvaluationId(connection, snapshot.metadata.evaluationId).flatMap {
              case Some(existing) if existing == snapshot => ZIO.unit
              case Some(_)                                =>
                ZIO.fail(AgentError.InvalidConfiguration("eval-trend:evaluation-id-conflict"))
              case None =>
                // ON CONFLICT 已经观察到冲突后，同一 READ COMMITTED 连接应能看到已提交行；不可见说明数据库状态异常。
                ZIO.fail(corrupted("conflict-row-missing"))
            }
        }
      }
    yield ()

  /** 使用部分索引读取最近一个成功基线。
    *
    * @param identity
    *   包含 kind、suite、dataset 与 datasetVersion 的完整身份
    * @return
    *   没有成功历史时为 None；任何完整性或解码问题都 fail-closed
    */
  override def latestPassing(identity: EvalTrendIdentity): IO[AgentError, Option[EvalSuiteSnapshot]] =
    EvalTrendIdentity.validate(identity) *>
      withConnection { connection =>
        queryRows(
          connection,
          """SELECT evaluation_id, suite_kind, suite_id, dataset_id, dataset_version,
            |       finished_epoch_second, finished_nano, passed, pass_rate,
            |       snapshot_sha256, snapshot_payload
            |FROM agent_eval_snapshots
            |WHERE suite_kind = ? AND suite_id = ? AND dataset_id = ? AND dataset_version = ? AND passed = TRUE
            |ORDER BY finished_epoch_second DESC, finished_nano DESC, evaluation_id DESC
            |LIMIT 1""".stripMargin,
          identity,
          limit = None
        ).flatMap(rows => ZIO.foreach(rows)(decodeStored).map(_.headOption))
      }

  /** 读取同一身份最近的有界历史，并以完成时间升序返回。
    *
    * SQL 内层先利用降序复合索引取得最近 N 行，外层再升序排列，因此深历史不会使用 OFFSET，也不会扫描并丢弃所有旧行。
    *
    * @param identity
    *   完整趋势身份
    * @param limit
    *   最近记录数量，必须位于 1..maxHistoryLimit
    */
  override def history(
      identity: EvalTrendIdentity,
      limit: Int
  ): IO[AgentError, Chunk[EvalSuiteSnapshot]] =
    if limit <= 0 || limit > config.maxHistoryLimit then
      ZIO.fail(AgentError.InvalidConfiguration("eval-trend:invalid-history-limit"))
    else
      EvalTrendIdentity.validate(identity) *>
        withConnection { connection =>
          queryRows(
            connection,
            """SELECT evaluation_id, suite_kind, suite_id, dataset_id, dataset_version,
              |       finished_epoch_second, finished_nano, passed, pass_rate,
              |       snapshot_sha256, snapshot_payload
              |FROM (
              |  SELECT evaluation_id, suite_kind, suite_id, dataset_id, dataset_version,
              |         finished_epoch_second, finished_nano, passed, pass_rate,
              |         snapshot_sha256, snapshot_payload
              |  FROM agent_eval_snapshots
              |  WHERE suite_kind = ? AND suite_id = ? AND dataset_id = ? AND dataset_version = ?
              |  ORDER BY finished_epoch_second DESC, finished_nano DESC, evaluation_id DESC
              |  LIMIT ?
              |) recent
              |ORDER BY finished_epoch_second ASC, finished_nano ASC, evaluation_id ASC""".stripMargin,
            identity,
            limit = Some(limit)
          ).flatMap(rows => ZIO.foreach(rows)(decodeStored))
        }

  /** 将合法快照编码为确定性 JSON 和 SHA-256；字节上限在借数据库连接之前执行。 */
  private def encode(snapshot: EvalSuiteSnapshot): IO[AgentError, EncodedSnapshot] =
    ZIO
      .attempt {
        val json  = snapshot.toJson
        val bytes = json.getBytes(StandardCharsets.UTF_8)
        EncodedSnapshot(json, bytes.length, sha256(bytes))
      }
      .mapError(_ => AgentError.InvalidConfiguration("eval-trend:encode-failed"))
      .flatMap { encoded =>
        ZIO
          .fail(AgentError.InvalidConfiguration("eval-trend:record-too-large"))
          .when(encoded.byteLength > config.maxSnapshotBytes)
          .as(encoded)
      }

  /** 执行单条幂等 INSERT。
    *
    * @return
    *   true 表示本次创建新行；false 表示 evaluation_id 已存在，需要调用方比较内容
    */
  private def insert(
      connection: Connection,
      snapshot: EvalSuiteSnapshot,
      encoded: EncodedSnapshot
  ): IO[AgentError, Boolean] =
    jdbc("append eval snapshot") {
      val metadata  = snapshot.metadata
      val statement = connection.prepareStatement(
        """INSERT INTO agent_eval_snapshots
          |(evaluation_id, schema_version, suite_kind, suite_id, dataset_id, dataset_version,
          | harness_version, provider, model, pricing_version, commit_sha,
          | started_at, finished_at, finished_epoch_second, finished_nano,
          | passed, pass_rate, snapshot_sha256, snapshot_payload, snapshot_json)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
          |ON CONFLICT (evaluation_id) DO NOTHING""".stripMargin
      )
      try
        statement.setString(1, metadata.evaluationId)
        statement.setInt(2, snapshot.schemaVersion)
        statement.setString(3, snapshot.kind.toString)
        statement.setString(4, metadata.suiteId)
        statement.setString(5, metadata.datasetId)
        statement.setString(6, metadata.datasetVersion)
        statement.setString(7, metadata.harnessVersion)
        setOptionalString(statement, 8, metadata.provider)
        setOptionalString(statement, 9, metadata.model)
        setOptionalString(statement, 10, metadata.pricingVersion)
        setOptionalString(statement, 11, metadata.commitSha)
        statement.setTimestamp(12, Timestamp.from(metadata.startedAt))
        statement.setTimestamp(13, Timestamp.from(metadata.finishedAt))
        statement.setLong(14, metadata.finishedAt.getEpochSecond)
        statement.setInt(15, metadata.finishedAt.getNano)
        statement.setBoolean(16, snapshot.passed)
        statement.setDouble(17, snapshot.passRate)
        statement.setString(18, encoded.sha256)
        statement.setString(19, encoded.json)
        statement.setString(20, encoded.json)
        statement.executeUpdate() == 1
      finally statement.close()
    }

  /** 主键冲突后读取既有事实；返回前仍会校验 hash、身份、排序字段与领域语义。 */
  private def loadByEvaluationId(
      connection: Connection,
      evaluationId: String
  ): IO[AgentError, Option[EvalSuiteSnapshot]] =
    jdbc("load eval snapshot by id") {
      val statement = connection.prepareStatement(
        """SELECT evaluation_id, suite_kind, suite_id, dataset_id, dataset_version,
          |       finished_epoch_second, finished_nano, passed, pass_rate,
          |       snapshot_sha256, snapshot_payload
          |FROM agent_eval_snapshots
          |WHERE evaluation_id = ?""".stripMargin
      )
      try
        statement.setString(1, evaluationId)
        val result = statement.executeQuery()
        if result.next() then Some(readStored(result)) else None
      finally statement.close()
    }.flatMap(value => ZIO.foreach(value)(decodeStored))

  /** 执行身份查询并把 JDBC ResultSet 投影为纯数据。
    *
    * JSON 解析刻意在 blocking JDBC 区域之外执行，避免较大的 CPU 解码工作长期占用数据库连接操作线程。
    */
  private def queryRows(
      connection: Connection,
      sql: String,
      identity: EvalTrendIdentity,
      limit: Option[Int]
  ): IO[AgentError, Chunk[StoredSnapshot]] =
    jdbc("query eval trend") {
      val statement = connection.prepareStatement(sql)
      try
        statement.setString(1, identity.kind.toString)
        statement.setString(2, identity.suiteId)
        statement.setString(3, identity.datasetId)
        statement.setString(4, identity.datasetVersion)
        limit.foreach(statement.setInt(5, _))
        val result  = statement.executeQuery()
        val builder = ChunkBuilder.make[StoredSnapshot]()
        while result.next() do builder += readStored(result)
        builder.result()
      finally statement.close()
    }

  /** 按 SELECT 列顺序读取一行；所有字符串仍是不可信数据库输入，后续必须经过 decodeStored。 */
  private def readStored(result: ResultSet): StoredSnapshot =
    StoredSnapshot(
      evaluationId = result.getString(1),
      kind = result.getString(2),
      suiteId = result.getString(3),
      datasetId = result.getString(4),
      datasetVersion = result.getString(5),
      finishedEpochSecond = result.getLong(6),
      finishedNano = result.getInt(7),
      passed = result.getBoolean(8),
      passRate = result.getDouble(9),
      sha256 = result.getString(10),
      json = result.getString(11)
    )

  /** 重新验证数据库行。
    *
    * 数据库管理员、错误脚本或存储损坏都可能绕过应用层，因此这里同时检查容量、checksum、JSON、领域规则以及用于查询的 冗余列。任何不一致都返回稳定低敏错误，不会跳过坏行后继续选择更早基线。
    */
  private def decodeStored(stored: StoredSnapshot): IO[AgentError, EvalSuiteSnapshot] =
    val bytes = stored.json.getBytes(StandardCharsets.UTF_8)
    for
      _ <- ZIO
        .fail(corrupted("record-too-large"))
        .when(bytes.length > config.maxSnapshotBytes)
      _ <- ZIO
        .fail(corrupted("invalid-checksum"))
        .unless(stored.sha256.matches("[0-9a-f]{64}"))
      actualHash = sha256(bytes)
      _ <- ZIO
        .fail(corrupted("checksum-mismatch"))
        .unless(
          MessageDigest.isEqual(
            actualHash.getBytes(StandardCharsets.US_ASCII),
            stored.sha256.getBytes(StandardCharsets.US_ASCII)
          )
        )
      decoded <- ZIO
        .fromEither(stored.json.fromJson[EvalSuiteSnapshot])
        .mapError(_ => corrupted("invalid-snapshot-json"))
      _ <- EvalSuiteSnapshot.validate(decoded).mapError(_ => corrupted("invalid-snapshot"))
      metadata     = decoded.metadata
      columnsMatch =
        metadata.evaluationId == stored.evaluationId &&
          decoded.kind.toString == stored.kind &&
          metadata.suiteId == stored.suiteId &&
          metadata.datasetId == stored.datasetId &&
          metadata.datasetVersion == stored.datasetVersion &&
          metadata.finishedAt.getEpochSecond == stored.finishedEpochSecond &&
          metadata.finishedAt.getNano == stored.finishedNano &&
          decoded.passed == stored.passed &&
          java.lang.Double
            .doubleToLongBits(decoded.passRate) == java.lang.Double.doubleToLongBits(stored.passRate)
      _ <- ZIO.fail(corrupted("column-snapshot-mismatch")).unless(columnsMatch)
    yield decoded

  /** 从宿主连接池借还连接；成功、失败或 Fiber 取消都会运行 finalizer。 */
  private def withConnection[A](use: Connection => IO[AgentError, A]): IO[AgentError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(jdbc("acquire connection")(dataSource.getConnection))(connection =>
          ZIO.attemptBlocking(connection.close()).ignore
        )
        .flatMap(use)
    }

  /** 所有 JDBC 阻塞调用进入专用 blocking executor，并按 SQLSTATE 分类重试语义。 */
  private def jdbc[A](operation: String)(effect: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(effect).mapError(error => databaseError(operation, error))

  /** 连接、序列化、死锁、资源耗尽、取消和数据库切换可重试；约束/语法错误不可盲目重试。 */
  private def databaseError(operation: String, error: Throwable): AgentError = error match
    case sql: SQLException =>
      val state     = Option(sql.getSQLState).getOrElse("unknown")
      val retryable =
        state.startsWith("08") ||
          state.startsWith("40") ||
          state.startsWith("53") ||
          state == "57014" ||
          Set("57P01", "57P02", "57P03").contains(state)
      AgentError.DatabaseFailure(s"PostgreSQL eval trend $operation 失败", state, retryable, Some(sql))
    case other =>
      AgentError.PersistenceFailure(s"PostgreSQL eval trend $operation 失败", Some(other))

object PostgresEvalTrendStore:
  final private case class EncodedSnapshot(json: String, byteLength: Int, sha256: String)

  /** JDBC 查询行的纯数据投影；离开 ResultSet 后才能安全异步解码。 */
  final private case class StoredSnapshot(
      evaluationId: String,
      kind: String,
      suiteId: String,
      datasetId: String,
      datasetVersion: String,
      finishedEpochSecond: Long,
      finishedNano: Int,
      passed: Boolean,
      passRate: Double,
      sha256: String,
      json: String
  )

  /** 对可选字符串使用 JDBC NULL，而不是写入空字符串。 */
  private def setOptionalString(
      statement: java.sql.PreparedStatement,
      index: Int,
      value: Option[String]
  ): Unit =
    value match
      case Some(text) => statement.setString(index, text)
      case None       => statement.setNull(index, java.sql.Types.VARCHAR)

  /** 生成固定小写 SHA-256，兼容数据库 CHAR(64) CHECK。 */
  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  /** 数据库事实不一致必须 fail-closed，错误只保留稳定 code。 */
  private def corrupted(code: String): AgentError.DatabaseFailure =
    AgentError.DatabaseFailure(
      s"PostgreSQL eval trend 数据损坏 (code=$code)",
      "data-corruption",
      retryable = false
    )

  /** 使用默认容量配置，通过宿主 DataSource 提供 EvalTrendStore。 */
  val layer: URLayer[DataSource, EvalTrendStore] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresEvalTrendStore(dataSource): EvalTrendStore)

  /** 使用显式容量配置构造 Layer，适合按 CI 数据集规模进行部署调优。 */
  def configured(config: PostgresEvalTrendStoreConfig): URLayer[DataSource, EvalTrendStore] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresEvalTrendStore(dataSource, config): EvalTrendStore
    )
