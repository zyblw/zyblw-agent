package com.zyblw.agent.persistence.postgres

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource
import zio.*

enum SchemaRelationKind:
  case Authoritative, DeadProjection, OptionalKnowledge

final case class SchemaRelation(name: String, kind: SchemaRelationKind)

final case class SchemaCensusRow(
    name: String,
    kind: SchemaRelationKind,
    exists: Boolean,
    rowCount: Option[Long]
):
  require(rowCount.forall(_ >= 0L), "行数不能为负数")

final case class SchemaCensus(rows: Chunk[SchemaCensusRow], checksum: String):
  def deadOccupied: Chunk[SchemaCensusRow] =
    rows.filter(row => row.kind == SchemaRelationKind.DeadProjection && row.rowCount.exists(_ > 0L))

  def missingAuthoritative: Chunk[SchemaCensusRow] =
    rows.filter(row => row.kind == SchemaRelationKind.Authoritative && !row.exists)

object AgentSchemaInventory:
  val DeadProjections: Chunk[String] =
    Chunk("model_calls", "agent_messages", "agent_steps", "usage_records")

  val Authoritative: Chunk[String] = Chunk(
    "agent_runs",
    "agent_events",
    "tool_executions",
    "agent_suspensions",
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
    "agent_ingestion_jobs",
    "model_call_executions",
    "harness_goals",
    "harness_plans",
    "harness_skills",
    "harness_interactions",
    "harness_goal_budgets",
    "harness_budget_reservations",
    "agent_artifacts",
    "agent_artifact_versions",
    "agent_artifact_audit"
  )

  val relations: Chunk[SchemaRelation] =
    Authoritative.map(SchemaRelation(_, SchemaRelationKind.Authoritative)) ++
      DeadProjections.map(SchemaRelation(_, SchemaRelationKind.DeadProjection))

/** 只读表普查：存在性、行数和低敏 checksum。不导出正文，不修改任何行。 */
object AgentSchemaCensus:
  def inspect(dataSource: DataSource): Task[SchemaCensus] =
    ZIO.attemptBlockingInterrupt {
      val connection = dataSource.getConnection
      try
        val rows = AgentSchemaInventory.relations.map { relation =>
          if !relationExists(connection, relation.name) then
            SchemaCensusRow(relation.name, relation.kind, exists = false, rowCount = None)
          else
            SchemaCensusRow(
              relation.name,
              relation.kind,
              exists = true,
              rowCount = Some(countRows(connection, relation.name))
            )
        }
        SchemaCensus(rows, checksum(rows))
      finally connection.close()
    }

  private def relationExists(connection: Connection, relation: String): Boolean =
    val statement =
      connection.prepareStatement(
        "SELECT to_regclass(quote_ident(current_schema()) || '.' || quote_ident(?))"
      )
    try
      statement.setString(1, relation)
      val result = statement.executeQuery()
      try result.next() && result.getString(1) != null
      finally result.close()
    finally statement.close()

  private def countRows(connection: Connection, relation: String): Long =
    val statement = connection.prepareStatement("SELECT COUNT(*)::bigint FROM " + quoteIdent(relation))
    try
      val result = statement.executeQuery()
      try if result.next() then result.getLong(1) else 0L
      finally result.close()
    finally statement.close()

  private def quoteIdent(value: String): String =
    require(value.matches("[A-Za-z_][A-Za-z0-9_]*"), s"非法关系名: $value")
    "\"" + value + "\""

  private def checksum(rows: Chunk[SchemaCensusRow]): String =
    val canonical = rows
      .map(row => s"${row.name}:${row.kind}:${row.exists}:${row.rowCount.getOrElse(-1L)}")
      .mkString("\n")
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
