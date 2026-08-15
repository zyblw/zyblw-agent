package com.zyblw.agent.examples

import com.zyblw.agent.app.*
import com.zyblw.agent.context.ContextSourceResolver
import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.GuardrailEngine
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.model.*
import com.zyblw.agent.persistence.postgres.PostgresAgentPersistence
import com.zyblw.agent.runtime.RunObserver
import com.zyblw.agent.testkit.ScriptedChatModel
import com.zyblw.agent.tools.*
import org.postgresql.ds.PGSimpleDataSource
import zio.*
import zio.json.*
import zio.json.ast.Json

import javax.sql.DataSource

/** 无业务领域依赖的 PostgreSQL 快速接入示例。
  *
  * 它展示一条完整宿主路径：创建共享 DataSource、执行框架 migration、装配 durable application、注册类型化只读工具， 并在 Scope 关闭时中断
  * Worker。`PGSimpleDataSource` 只为保持示例零新增依赖；常驻生产宿主应注入自己监控和关闭的连接池。
  *
  * 运行前为一个空 PostgreSQL database 设置 `ZYBLW_AGENT_JDBC_URL`、`ZYBLW_AGENT_DB_USER` 和
  * `ZYBLW_AGENT_DB_PASSWORD`，然后执行：
  *
  * {{{
  * sbt "examples/runMain com.zyblw.agent.examples.PostgresQuickstartExample"
  * }}}
  */
object PostgresQuickstartExample extends ZIOAppDefault:
  final case class DatabaseInfoInput(includeSchema: Boolean) derives JsonCodec
  final case class DatabaseInfoOutput(database: String, schema: Option[String]) derives JsonCodec

  final private case class DatabaseConfig(jdbcUrl: String, user: String, password: String)

  private val databaseInfoSchema = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "includeSchema" -> Json.Obj(
        "type"        -> Json.Str("boolean"),
        "description" -> Json.Str("Whether to include the current PostgreSQL schema")
      )
    ),
    "required"             -> Json.Arr(Json.Str("includeSchema")),
    "additionalProperties" -> Json.Bool(false)
  )

  /** SQL 是固定字符串，模型输入不能变成 SQL；连接和 statement 始终在 blocking 区域内关闭。 */
  private val databaseInfoTool =
    Tool.json[DataSource, DatabaseInfoInput, AgentError.ToolExecutionFailed, DatabaseInfoOutput](
      ToolName("database_info"),
      "Read the current PostgreSQL database and optional schema without modifying state.",
      databaseInfoSchema,
      None,
      ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
    ) { (input, _) =>
      ZIO
        .serviceWithZIO[DataSource] { dataSource =>
          ZIO.attemptBlocking {
            val connection = dataSource.getConnection
            try
              val statement = connection.prepareStatement("SELECT current_database(), current_schema()")
              try
                val result = statement.executeQuery()
                try
                  if !result.next() then
                    throw IllegalStateException("database metadata query returned no row")
                  DatabaseInfoOutput(
                    database = result.getString(1),
                    schema = Option(result.getString(2)).filter(_ => input.includeSchema)
                  )
                finally result.close()
              finally statement.close()
            finally connection.close()
          }
        }
        .mapError(error => AgentError.ToolExecutionFailed("database_info", error.getClass.getSimpleName))
    }

  private val scriptedModel = Chunk(
    ChatResponse(
      AgentMessage.assistantToolCalls(
        Chunk(ToolCall("database-info-1", "database_info", Json.Obj("includeSchema" -> Json.Bool(true))))
      ),
      FinishReason.ToolCalls,
      TokenUsage(12L, 4L)
    ),
    ChatResponse(
      AgentMessage.assistant("PostgreSQL durable quickstart completed."),
      FinishReason.Stop,
      TokenUsage(16L, 6L)
    )
  )

  private val applicationConfig = AgentApplicationConfig(
    toolPolicy = ToolPolicyConfig(allowedTools = Set(ToolName("database_info")))
  )

  private def loadDatabaseConfig: Task[DatabaseConfig] =
    ZIO.attempt {
      def required(name: String): String =
        sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse {
          throw IllegalArgumentException(s"missing required environment variable: $name")
        }

      DatabaseConfig(
        jdbcUrl = required("ZYBLW_AGENT_JDBC_URL"),
        user = required("ZYBLW_AGENT_DB_USER"),
        password = required("ZYBLW_AGENT_DB_PASSWORD")
      )
    }

  private def makeDataSource(config: DatabaseConfig): Task[DataSource] =
    ZIO.attempt {
      val dataSource = PGSimpleDataSource()
      dataSource.setUrl(config.jdbcUrl)
      dataSource.setUser(config.user)
      dataSource.setPassword(config.password)
      dataSource.setConnectTimeout(10)
      dataSource
    }

  private def terminal(status: RunStatus): Boolean =
    status match
      case RunStatus.Created | RunStatus.Running => false
      case _                                     => true

  private def awaitTerminal(app: AgentApplication, runId: RunId): IO[AgentError, AgentState] =
    app.inspect(runId).flatMap { state =>
      if terminal(state.status) then ZIO.succeed(state)
      else ZIO.sleep(100.millis) *> awaitTerminal(app, runId)
    }

  override def gracefulShutdownTimeout: Duration = 10.seconds

  def run: ZIO[Any, Any, Any] =
    for
      config     <- loadDatabaseConfig
      dataSource <- makeDataSource(config)
      registered <- RegisteredTool.make(databaseInfoTool).provide(ZLayer.succeed[DataSource](dataSource))
      agent      <- AgentDefinitionBuilder(AgentId("postgres-quickstart"), "PostgreSQL quickstart")
        .withInstructions("Call database_info once, then report that the durable quickstart completed.")
        .allowTool(ToolName("database_info"))
        .buildFor(applicationConfig.toolPolicy)
      state <- ZIO.scoped {
        (for
          app     <- ZIO.service[AgentApplication]
          _       <- app.startWorkerScoped
          command <- app.submit(
            agent,
            RunRequest(ThreadId("postgres-quickstart-thread"), AgentMessage.user("Inspect the database.")),
            idempotencyKey = s"postgres-quickstart-${java.util.UUID.randomUUID()}"
          )
          state <- awaitTerminal(app, command.runId)
            .timeoutFail(IllegalStateException("durable quickstart did not finish within 20 seconds"))(
              20.seconds
            )
        yield state).provideSome[Scope](
          ZLayer.succeed[DataSource](dataSource),
          PostgresAgentPersistence.migratedLayer,
          ScriptedChatModel.layer(scriptedModel),
          RegisteredToolRegistry.fromTools(List(registered)),
          ContextSourceResolver.empty,
          GuardrailEngine.empty,
          RunObserver.noop,
          AgentApplication.durable(
            WorkerId(s"postgres-quickstart-${java.util.UUID.randomUUID()}"),
            applicationConfig
          )
        )
      }
      _ <- Console.printLine(s"run=${state.runId.asString} status=${state.status}")
    yield ()
