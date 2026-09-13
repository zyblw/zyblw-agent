package com.zyblw.agent.examples.production

import com.zyblw.agent.core.*
import com.zyblw.agent.http.*
import com.zyblw.agent.http.host.*
import com.zyblw.agent.persistence.postgres.{
  AgentPostgresMigrations,
  AgentSchemaCensus,
  AgentSchemaManifest,
  AgentUpgradePreflight,
  AgentUpgradeReadiness,
  SchemaCensus
}
import com.zyblw.agent.runtime.DurableRunEventStream
import javax.sql.DataSource
import zio.*
import zio.http.{Client, Server}

/** 客户支持场景的完整生产参考宿主。
  *
  * 这条路径同时包含 PostgreSQL 控制面、类型化只读/审批写工具、可信身份头、ZIO HTTP、健康检查和结构化关闭。 `ZYBLW_AGENT_RUNTIME_MODE=contract`
  * 仅用于自动化验证，README 默认命令始终是真实 Provider + 数据库。
  *
  * {{{
  * sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost status"
  * sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost migrate"
  * sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost serve"
  * }}}
  */
object ProductionSupportHost extends ZIOAppDefault:

  override def gracefulShutdownTimeout: Duration = 20.seconds

  def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      args   <- getArgs
      config <- ProductionSupportConfig.load(args)
      _      <- runCommand(config)
    yield ()

  private def runCommand(config: ProductionSupportConfig): ZIO[Any, Any, Any] =
    config.command match
      case ProductionSupportCommand.Migrate => migrate(config)
      case ProductionSupportCommand.Serve   => serve(config)
      case ProductionSupportCommand.Status  => status(config)
      case ProductionSupportCommand.All     => migrate(config) *> serve(config)

  private def migrate(config: ProductionSupportConfig): ZIO[Any, Any, Unit] =
    (for
      dataSource <- ZIO.service[DataSource]
      readiness  <- AgentUpgradePreflight.inspect(dataSource).option
      _          <- ZIO.foreachDiscard(readiness)(warnIfBusy)
      _          <- AgentPostgresMigrations.migrate(dataSource)
      _          <- applyBusinessSchema(dataSource)
      _          <- Console.printLine("核心控制面与 support_* 业务表已迁移并通过结构探针。")
    yield ()).provide(ProductionSupportLayers.dataSource(config))

  private def status(config: ProductionSupportConfig): ZIO[Any, Any, Unit] =
    config.requireDurableDatabase *>
      (for
        dataSource <- ZIO.service[DataSource]
        readiness  <- AgentUpgradePreflight.inspect(dataSource)
        census     <- AgentSchemaCensus.inspect(dataSource)
        _          <- Console.printLine(renderStatus(readiness, census))
      yield ()).provide(ProductionSupportLayers.dataSource(config))

  private def warnIfBusy(readiness: AgentUpgradeReadiness): UIO[Unit] =
    ZIO
      .when(readiness.drainRequired)(
        Console
          .printLine(
            s"警告：仍有进行中工作 activeRuns=${readiness.activeRuns} queued=${readiness.queuedCommands} leased=${readiness.leasedCommands}。切换候选进程前应先停止新提交并完成 drain。"
          )
          .orDie
      )
      .unit

  private def renderStatus(
      readiness: AgentUpgradeReadiness,
      census: SchemaCensus
  ): String =
    val schema  = readiness.schema.currentVersion.getOrElse("empty")
    val pending =
      if readiness.schema.hasPending then readiness.schema.pendingVersions.mkString(",") else "none"
    val verdict =
      if readiness.schema.hasPending && readiness.drainRequired then
        "先 drain 进行中 Run，再在迁移前的数据库副本上测量后执行 migrate。"
      else if readiness.schema.hasPending then "可以执行 migrate；完成后用新进程替换 serve。"
      else if readiness.drainRequired then "schema 已是目标版本，但仍有进行中工作，切换进程前先 drain。"
      else "可以切换新进程或继续 serve。"
    s"""升级预检
  核心 schema: $schema
  pending: $pending
  进行中 Run: ${readiness.activeRuns}
  等待审批: ${readiness.waitingForApproval}
  排队命令: ${readiness.queuedCommands}
  已领取命令: ${readiness.leasedCommands}
  死投影占用: ${if census.deadOccupied.isEmpty then "none" else census.deadOccupied.map(_.name).mkString(",")}
  缺失权威表: ${
        if census.missingAuthoritative.isEmpty then "none"
        else census.missingAuthoritative.map(_.name).mkString(",")
      }
  census: ${census.checksum.take(12)}
  基线切换: ${baselineAdvice(census)}
  结论: $verdict"""

  private def baselineAdvice(census: SchemaCensus): String =
    AgentSchemaManifest.verifyImport(AgentSchemaManifest.fromCensus(census, 0L), census) match
      case Right(_)     => "当前库可通过 census 自检；切换下一基线前先导出清单并在副本导入后核对行数。"
      case Left(reason) => s"census 自检未通过: $reason"

  private def applyBusinessSchema(dataSource: DataSource): Task[Unit] =
    ZIO.attemptBlockingInterrupt {
      val connection = dataSource.getConnection
      try
        val statement = connection.createStatement()
        try statement.execute(ProductionSupportTools.businessSchemaSql)
        finally statement.close()
      finally connection.close()
    }.unit

  private def serve(config: ProductionSupportConfig): ZIO[Any, Any, Nothing] =
    config.mode match
      case ProductionSupportMode.Contract if config.jdbcUrl.isEmpty =>
        serveInMemory(config)
      case _ =>
        config.requireDurableDatabase *> serveDurable(config)

  private def serveInMemory(config: ProductionSupportConfig): ZIO[Any, Any, Nothing] =
    ProductionSupportLayers.supportAgent(config.application).flatMap { agent =>
      Console.printLine(
        s"contract 内存宿主监听 http://127.0.0.1:${config.httpPort}；该模式不能用于生产。"
      ) *> AgentHttpHost.serve.provide(
        ProductionSupportLayers.scriptedModel,
        ProductionSupportLayers.inMemoryToolsLayer,
        ProductionSupportLayers.inMemoryApplication(config),
        AgentRegistry.fromAgents(List(agent)),
        ProductionSupportLayers.identity(config),
        DurableRunEventStream.default,
        AgentHttpApi.layer,
        AgentHostReadiness.alwaysReady,
        ProductionSupportLayers.inMemoryMemory,
        MemoryHttpApi.layer,
        ProductionSupportLayers.memoryRoutes,
        ProductionSupportLayers.hostConfig(config),
        Server.defaultWithPort(config.httpPort),
        AgentHttpServer.zioHttp,
        AgentHttpHost.fromApplication
      )
    }

  private def serveDurable(config: ProductionSupportConfig): ZIO[Any, Any, Nothing] =
    ProductionSupportLayers.supportAgent(config.application).flatMap { agent =>
      Console.printLine(
        s"生产参考宿主监听 http://127.0.0.1:${config.httpPort}；Worker=${config.workerId}。"
      ) *> AgentHttpHost.serve.provide(
        ProductionSupportLayers.dataSource(config),
        Client.default,
        modelLayer(config),
        ProductionSupportLayers.durableToolsLayer,
        ProductionSupportLayers.durableApplication(config),
        AgentRegistry.fromAgents(List(agent)),
        ProductionSupportLayers.identity(config),
        DurableRunEventStream.default,
        AgentHttpApi.layer,
        AgentHostReadiness.jdbc,
        ProductionSupportLayers.durableMemory,
        MemoryHttpApi.layer,
        ProductionSupportLayers.memoryRoutes,
        ProductionSupportLayers.durableSideEffects(config),
        ProductionSupportLayers.hostConfig(config),
        Server.defaultWithPort(config.httpPort),
        AgentHttpServer.zioHttp,
        ProductionSupportLayers.durableHost
      )
    }

  private def modelLayer(
      config: ProductionSupportConfig
  ): ZLayer[Client, AgentError, com.zyblw.agent.model.ChatModel] =
    config.mode match
      case ProductionSupportMode.Contract => ProductionSupportLayers.scriptedModel
      case ProductionSupportMode.Live     => ProductionSupportLayers.liveModel
