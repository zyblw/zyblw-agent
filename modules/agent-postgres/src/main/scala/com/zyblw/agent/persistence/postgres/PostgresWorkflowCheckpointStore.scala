package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.workflow.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{Connection, ResultSet, SQLException, Types}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL Workflow checkpoint 的容量边界。
  *
  * @param maxCheckpointBytes
  *   完整 checkpoint JSON 的 UTF-8 上限；编码和读取都执行相同限制
  * @param maxVisitEntries
  *   单个 checkpoint 可保存的节点访问计数上限
  */
final case class PostgresWorkflowCheckpointStoreConfig(
    maxCheckpointBytes: Int = 2 * 1024 * 1024,
    maxVisitEntries: Int = 10_000
):
  require(
    maxCheckpointBytes >= 1024 && maxCheckpointBytes <= 16 * 1024 * 1024,
    "maxCheckpointBytes 必须位于 1KiB..16MiB"
  )
  require(maxVisitEntries >= 1 && maxVisitEntries <= 100_000, "maxVisitEntries 必须位于 1..100000")

/** 使用 PostgreSQL 保存声明式 Workflow 的完整恢复快照。
  *
  * 每个 `runId` 只有一行权威 checkpoint。写入允许相同内容幂等重放，或在相同 workflow/version/session identity 内推进到更大 step；陈旧写入、同 step
  * 不同内容和 identity 漂移均 fail-closed。完整 JSON 同时保存确定性 TEXT、JSONB 分析投影和 SHA-256，读取时重新验证容量、checksum、领域约束与冗余列。
  *
  * 该 Store 只解决节点边界的耐久恢复，不提供分布式执行所有权。多 Worker 调度仍需后续 execution lease/fencing，节点外部 副作用仍必须幂等。
  */
final class PostgresWorkflowCheckpointStore[S: JsonCodec](
    dataSource: DataSource,
    config: PostgresWorkflowCheckpointStoreConfig = PostgresWorkflowCheckpointStoreConfig()
) extends WorkflowCheckpointStore[S]:
  import PostgresWorkflowCheckpointStore.*

  override def save(runId: RunId, checkpoint: WorkflowCheckpoint[S]): IO[StoreError, Unit] =
    for
      encoded <- encode(checkpoint)
      result  <- withConnection { connection =>
        upsert(connection, runId, checkpoint, encoded).flatMap {
          case true  => ZIO.succeed(SaveResult.Written)
          case false =>
            select(connection, runId).map {
              case Some(row) => SaveResult.Existing(row)
              case None      => SaveResult.ConflictRowMissing
            }
        }
      }
      _ <- result match
        case SaveResult.Written       => ZIO.unit
        case SaveResult.Existing(row) =>
          decode(row).flatMap { stored =>
            if stored == checkpoint then ZIO.unit
            else ZIO.fail(AgentError.WorkflowCheckpointConflict(runId, "non-monotonic-write"))
          }
        case SaveResult.ConflictRowMissing =>
          ZIO.fail(corrupted("conflict-row-missing"))
    yield ()

  override def load(runId: RunId): IO[StoreError, Option[WorkflowCheckpoint[S]]] =
    withConnection(connection => select(connection, runId))
      .flatMap(row => ZIO.foreach(row)(decode))

  private def encode(checkpoint: WorkflowCheckpoint[S]): IO[StoreError, EncodedCheckpoint] =
    validateCheckpoint(checkpoint, corrupted = false) *>
      ZIO
        .attempt {
          val stored = StoredCheckpoint(
            schemaVersion = CurrentSchemaVersion,
            workflowId = checkpoint.workflowId.value,
            definitionVersion = checkpoint.definitionVersion.value,
            sessionId = checkpoint.sessionId.asString,
            cursor = checkpoint.cursor match
              case WorkflowCursor.At(node)  => StoredCursor("At", Some(node.value))
              case WorkflowCursor.Completed => StoredCursor("Completed", None),
            state = checkpoint.state,
            step = checkpoint.step,
            visits = Chunk.fromIterable(
              checkpoint.visits.toList
                .sortBy(_._1.value)
                .map { case (node, count) => StoredVisit(node.value, count) }
            )
          )
          val json  = stored.toJson
          val bytes = json.getBytes(StandardCharsets.UTF_8)
          EncodedCheckpoint(json, bytes.length, sha256(bytes))
        }
        .mapError(_ => AgentError.PersistenceFailure("PostgreSQL workflow checkpoint encode-failed"))
        .flatMap { encoded =>
          ZIO
            .fail(AgentError.PersistenceFailure("PostgreSQL workflow checkpoint record-too-large"))
            .when(encoded.byteLength > config.maxCheckpointBytes)
            .as(encoded)
        }

  private def upsert(
      connection: Connection,
      runId: RunId,
      checkpoint: WorkflowCheckpoint[S],
      encoded: EncodedCheckpoint
  ): IO[StoreError, Boolean] =
    jdbc("save") {
      val statement = connection.prepareStatement(
        """INSERT INTO agent_workflow_checkpoints
          |(run_id, schema_version, workflow_id, definition_version, session_id,
          | cursor_kind, node_id, step, checkpoint_sha256, checkpoint_payload, checkpoint_json, updated_at)
          |VALUES (?::uuid, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
          |ON CONFLICT (run_id) DO UPDATE SET
          | schema_version = EXCLUDED.schema_version,
          | workflow_id = EXCLUDED.workflow_id,
          | definition_version = EXCLUDED.definition_version,
          | session_id = EXCLUDED.session_id,
          | cursor_kind = EXCLUDED.cursor_kind,
          | node_id = EXCLUDED.node_id,
          | step = EXCLUDED.step,
          | checkpoint_sha256 = EXCLUDED.checkpoint_sha256,
          | checkpoint_payload = EXCLUDED.checkpoint_payload,
          | checkpoint_json = EXCLUDED.checkpoint_json,
          | updated_at = CURRENT_TIMESTAMP
          |WHERE agent_workflow_checkpoints.workflow_id = EXCLUDED.workflow_id
          |  AND agent_workflow_checkpoints.definition_version = EXCLUDED.definition_version
          |  AND agent_workflow_checkpoints.session_id = EXCLUDED.session_id
          |  AND agent_workflow_checkpoints.cursor_kind <> 'Completed'
          |  AND agent_workflow_checkpoints.step < EXCLUDED.step""".stripMargin
      )
      try
        statement.setString(1, runId.asString)
        statement.setInt(2, CurrentSchemaVersion)
        statement.setString(3, checkpoint.workflowId.value)
        statement.setInt(4, checkpoint.definitionVersion.value)
        statement.setString(5, checkpoint.sessionId.asString)
        checkpoint.cursor match
          case WorkflowCursor.At(node) =>
            statement.setString(6, "At")
            statement.setString(7, node.value)
          case WorkflowCursor.Completed =>
            statement.setString(6, "Completed")
            statement.setNull(7, Types.VARCHAR)
        statement.setInt(8, checkpoint.step)
        statement.setString(9, encoded.sha256)
        statement.setString(10, encoded.json)
        statement.setString(11, encoded.json)
        statement.executeUpdate() == 1
      finally statement.close()
    }

  private def select(connection: Connection, runId: RunId): IO[StoreError, Option[StoredRow]] =
    jdbc("load") {
      val statement = connection.prepareStatement(
        """SELECT workflow_id, definition_version, session_id::text, cursor_kind, node_id, step,
          |       checkpoint_sha256, checkpoint_payload
          |FROM agent_workflow_checkpoints
          |WHERE run_id = ?::uuid""".stripMargin
      )
      try
        statement.setString(1, runId.asString)
        val result = statement.executeQuery()
        if result.next() then Some(readRow(result)) else None
      finally statement.close()
    }

  private def readRow(result: ResultSet): StoredRow =
    StoredRow(
      workflowId = result.getString(1),
      definitionVersion = result.getInt(2),
      sessionId = result.getString(3),
      cursorKind = result.getString(4),
      nodeId = Option(result.getString(5)),
      step = result.getInt(6),
      sha256 = result.getString(7),
      json = result.getString(8)
    )

  private def decode(row: StoredRow): IO[StoreError, WorkflowCheckpoint[S]] =
    val json     = Option(row.json).getOrElse("")
    val checksum = Option(row.sha256).getOrElse("")
    val bytes    = json.getBytes(StandardCharsets.UTF_8)
    for
      _ <- ZIO
        .fail(corrupted("record-too-large"))
        .when(bytes.length > config.maxCheckpointBytes)
      _ <- ZIO
        .fail(corrupted("invalid-checksum"))
        .unless(checksum.matches("[0-9a-f]{64}"))
      actualHash = sha256(bytes)
      _ <- ZIO
        .fail(corrupted("checksum-mismatch"))
        .unless(
          MessageDigest.isEqual(
            actualHash.getBytes(StandardCharsets.US_ASCII),
            checksum.getBytes(StandardCharsets.US_ASCII)
          )
        )
      stored <- ZIO
        .fromEither(json.fromJson[StoredCheckpoint[S]])
        .mapError(_ => corrupted("invalid-checkpoint-json"))
      checkpoint <- fromStored(stored)
      _          <- validateCheckpoint(checkpoint, corrupted = true)
      columnsMatch =
        stored.schemaVersion == CurrentSchemaVersion &&
          checkpoint.workflowId.value == row.workflowId &&
          checkpoint.definitionVersion.value == row.definitionVersion &&
          checkpoint.sessionId.asString == row.sessionId &&
          checkpoint.step == row.step &&
          (checkpoint.cursor match
            case WorkflowCursor.At(node)  => row.cursorKind == "At" && row.nodeId.contains(node.value)
            case WorkflowCursor.Completed => row.cursorKind == "Completed" && row.nodeId.isEmpty)
      _ <- ZIO.fail(corrupted("column-checkpoint-mismatch")).unless(columnsMatch)
    yield checkpoint

  private def fromStored(stored: StoredCheckpoint[S]): IO[StoreError, WorkflowCheckpoint[S]] =
    for
      _ <- ZIO
        .fail(corrupted("schema-version"))
        .unless(stored.schemaVersion == CurrentSchemaVersion)
      workflowId <- ZIO
        .fromEither(WorkflowId.fromString(stored.workflowId))
        .mapError(_ => corrupted("workflow-id"))
      definitionVersion <- ZIO
        .fromEither(WorkflowVersion.fromInt(stored.definitionVersion))
        .mapError(_ => corrupted("definition-version"))
      sessionId <- ZIO
        .fromEither(SessionId.fromString(stored.sessionId))
        .mapError(_ => corrupted("session-id"))
      cursor <- stored.cursor match
        case StoredCursor("At", Some(nodeId)) =>
          ZIO
            .fromEither(NodeId.fromString(nodeId))
            .map(WorkflowCursor.At.apply)
            .mapError(_ => corrupted("node-id"))
        case StoredCursor("Completed", None) => ZIO.succeed(WorkflowCursor.Completed)
        case _                               => ZIO.fail(corrupted("cursor"))
      _ <- ZIO.fail(corrupted("step")).when(stored.step < 0)
      _ <- ZIO
        .fail(corrupted("visit-count"))
        .when(
          stored.visits.length > config.maxVisitEntries ||
            stored.visits.exists(_.count <= 0)
        )
      visits <- ZIO.foreach(stored.visits) { visit =>
        ZIO
          .fromEither(NodeId.fromString(visit.nodeId))
          .map(_ -> visit.count)
          .mapError(_ => corrupted("visit-node-id"))
      }
      _ <- ZIO
        .fail(corrupted("duplicate-visit-node"))
        .unless(visits.map(_._1).distinct.length == visits.length)
      _ <- ZIO
        .fail(corrupted("step-visit-mismatch"))
        .unless(visits.map(_._2.toLong).sum == stored.step.toLong)
    yield WorkflowCheckpoint(
      workflowId,
      definitionVersion,
      sessionId,
      cursor,
      stored.state,
      stored.step,
      visits.toMap
    )

  private def validateCheckpoint(
      checkpoint: WorkflowCheckpoint[S],
      corrupted: Boolean
  ): IO[StoreError, Unit] =
    val invalid =
      checkpoint.step < 0 ||
        checkpoint.visits.size > config.maxVisitEntries ||
        checkpoint.visits.values.exists(_ <= 0) ||
        checkpoint.visits.valuesIterator.map(_.toLong).sum != checkpoint.step.toLong
    if !invalid then ZIO.unit
    else if corrupted then ZIO.fail(PostgresWorkflowCheckpointStore.corrupted("invalid-domain"))
    else ZIO.fail(AgentError.PersistenceFailure("PostgreSQL workflow checkpoint invalid-domain"))

  private def withConnection[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(jdbc("acquire-connection")(dataSource.getConnection))(connection =>
          ZIO.attemptBlocking(connection.close()).ignore
        )
        .flatMap(use)
    }

  private def jdbc[A](operation: String)(effect: => A): IO[StoreError, A] =
    ZIO.attemptBlocking(effect).mapError(error => databaseError(operation, error))

  private def databaseError(operation: String, error: Throwable): StoreError = error match
    case sql: SQLException =>
      val state     = Option(sql.getSQLState).getOrElse("unknown")
      val retryable =
        state.startsWith("08") ||
          state.startsWith("40") ||
          state.startsWith("53") ||
          state == "57014" ||
          Set("57P01", "57P02", "57P03").contains(state)
      AgentError.DatabaseFailure(
        s"PostgreSQL workflow checkpoint $operation 失败",
        state,
        retryable,
        Some(sql)
      )
    case other =>
      AgentError.PersistenceFailure(
        s"PostgreSQL workflow checkpoint $operation 失败",
        Some(other)
      )

object PostgresWorkflowCheckpointStore:
  private val CurrentSchemaVersion = 1

  final private case class StoredCursor(kind: String, nodeId: Option[String]) derives JsonCodec
  final private case class StoredVisit(nodeId: String, count: Int) derives JsonCodec
  final private case class StoredCheckpoint[S](
      schemaVersion: Int,
      workflowId: String,
      definitionVersion: Int,
      sessionId: String,
      cursor: StoredCursor,
      state: S,
      step: Int,
      visits: Chunk[StoredVisit]
  ) derives JsonCodec

  final private case class EncodedCheckpoint(json: String, byteLength: Int, sha256: String)
  private enum SaveResult:
    case Written
    case Existing(row: StoredRow)
    case ConflictRowMissing

  final private case class StoredRow(
      workflowId: String,
      definitionVersion: Int,
      sessionId: String,
      cursorKind: String,
      nodeId: Option[String],
      step: Int,
      sha256: String,
      json: String
  )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def corrupted(code: String): StoreError =
    AgentError.DatabaseFailure(
      s"PostgreSQL workflow checkpoint 数据损坏 (code=$code)",
      "data-corruption",
      retryable = false
    )

  def layer[S: JsonCodec: Tag]: URLayer[DataSource, WorkflowCheckpointStore[S]] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresWorkflowCheckpointStore[S](dataSource): WorkflowCheckpointStore[S]
    )

  def configured[S: JsonCodec: Tag](
      config: PostgresWorkflowCheckpointStoreConfig
  ): URLayer[DataSource, WorkflowCheckpointStore[S]] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresWorkflowCheckpointStore[S](dataSource, config): WorkflowCheckpointStore[S]
    )
