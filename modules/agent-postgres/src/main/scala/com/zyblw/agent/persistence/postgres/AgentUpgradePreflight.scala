package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.RunStatus
import zio.*

import java.sql.Connection
import javax.sql.DataSource

/** 升级到新 minor 前的只读预检：Flyway 版本 + 进行中 Run/命令。
  *
  * 它不执行 DDL，不领取 lease，也不改变任何行。结果只含计数和版本号，不含 JDBC URL、租户或 Prompt。
  */
final case class AgentUpgradeReadiness(
    schema: AgentPostgresSchemaStatus,
    activeRuns: Long,
    waitingForApproval: Long,
    queuedCommands: Long,
    leasedCommands: Long
):
  require(
    activeRuns >= 0L && waitingForApproval >= 0L && queuedCommands >= 0L && leasedCommands >= 0L,
    "升级预检计数不能为负数"
  )
  require(waitingForApproval <= activeRuns, "等待审批的 Run 必须计入进行中 Run")

  /** 仍有可推进工作：应先停止提交并等待 Worker drain，再切换 0.7 进程。 */
  def drainRequired: Boolean =
    activeRuns > 0L || queuedCommands > 0L || leasedCommands > 0L

object AgentUpgradePreflight:
  private val ActiveStatuses: Chunk[String] =
    Chunk.fromArray(RunStatus.values.filterNot(RunStatus.isTerminal).map(_.toString))

  def inspect(dataSource: DataSource): Task[AgentUpgradeReadiness] =
    for
      schema <- AgentPostgresMigrations.inspect(dataSource)
      load   <- ZIO.attemptBlockingInterrupt(queryLoad(dataSource))
    yield AgentUpgradeReadiness(
      schema = schema,
      activeRuns = load.activeRuns,
      waitingForApproval = load.waitingForApproval,
      queuedCommands = load.queuedCommands,
      leasedCommands = load.leasedCommands
    )

  final private case class Load(
      activeRuns: Long,
      waitingForApproval: Long,
      queuedCommands: Long,
      leasedCommands: Long
  )

  private def queryLoad(dataSource: DataSource): Load =
    val connection = dataSource.getConnection
    try
      if !relationExists(connection, "agent_runs") then Load(0L, 0L, 0L, 0L)
      else
        val runs = queryCounts(
          connection,
          """SELECT
            |  COUNT(*) FILTER (WHERE status = ANY(?))::bigint,
            |  COUNT(*) FILTER (WHERE status = 'WaitingForApproval')::bigint
            |FROM agent_runs""".stripMargin,
          ActiveStatuses
        )
        val commands =
          if !relationExists(connection, "agent_run_commands") then (0L, 0L)
          else
            val counts = queryCounts(
              connection,
              """SELECT
                |  COUNT(*) FILTER (WHERE status = 'Queued')::bigint,
                |  COUNT(*) FILTER (WHERE status = 'Leased')::bigint
                |FROM agent_run_commands""".stripMargin,
              Chunk.empty
            )
            counts._1 -> counts._2
        Load(runs._1, runs._2, commands._1, commands._2)
    finally connection.close()

  private def relationExists(connection: Connection, relation: String): Boolean =
    val statement = connection.prepareStatement(
      "SELECT to_regclass(quote_ident(current_schema()) || '.' || quote_ident(?))"
    )
    try
      statement.setString(1, relation)
      val result = statement.executeQuery()
      try result.next() && result.getString(1) != null
      finally result.close()
    finally statement.close()

  private def queryCounts(
      connection: Connection,
      sql: String,
      statuses: Chunk[String]
  ): (Long, Long) =
    val statement = connection.prepareStatement(sql)
    try
      if statuses.nonEmpty then statement.setArray(1, connection.createArrayOf("text", statuses.toArray))
      val result = statement.executeQuery()
      try
        if !result.next() then (0L, 0L)
        else (result.getLong(1), result.getLong(2))
      finally result.close()
    finally statement.close()
