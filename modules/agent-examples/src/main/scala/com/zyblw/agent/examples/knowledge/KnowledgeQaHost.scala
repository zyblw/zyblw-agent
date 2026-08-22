package com.zyblw.agent.examples.knowledge

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.examples.production.{
  ProductionSupportConfig,
  ProductionSupportLayers,
  ProductionSupportMode
}
import com.zyblw.agent.http.*
import com.zyblw.agent.http.host.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.persistence.postgres.{AgentPostgresMigrations, AgentUpgradePreflight}
import com.zyblw.agent.rag.*
import com.zyblw.agent.rag.tools.KnowledgeTools
import com.zyblw.agent.runtime.DurableRunEventStream
import javax.sql.DataSource
import zio.*
import zio.http.{Client, Server}

/** PDF 书籍问答参考宿主。
  *
  * JDBC 存在时 `serve` 走 PostgreSQL 知识库与耐久 Run；未配置 JDBC 时仅用于 contract / 进程内验证。
  *
  * {{{
  * sbt "examples/runMain com.zyblw.agent.examples.knowledge.KnowledgeQaHost status"
  * sbt "examples/runMain com.zyblw.agent.examples.knowledge.KnowledgeQaHost migrate"
  * sbt "examples/runMain com.zyblw.agent.examples.knowledge.KnowledgeQaHost ingest data/books"
  * sbt "examples/runMain com.zyblw.agent.examples.knowledge.KnowledgeQaHost serve"
  * }}}
  */
object KnowledgeQaHost extends ZIOAppDefault:
  override def gracefulShutdownTimeout: Duration = 20.seconds

  def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      args <- getArgs
      command = args.headOption.getOrElse("status")
      config <- ProductionSupportConfig.load(Chunk("serve"))
      _      <- command match
        case "reset"   => reset(config)
        case "migrate" => migrate(config)
        case "ingest"  => ingest(config, args.lift(1).getOrElse(KnowledgeQaLayers.booksDirectory))
        case "reindex" => reindex(config)
        case "serve"   => serve(config)
        case "all"     =>
          migrate(config) *>
            ingest(config, args.lift(1).getOrElse(KnowledgeQaLayers.booksDirectory)) *>
            serve(config)
        case "status" => status(config)
        case other    => ZIO.fail(AgentError.InvalidConfiguration(s"未知命令 $other"))
    yield ()

  private def reset(config: ProductionSupportConfig): ZIO[Any, Any, Unit] =
    (for
      dataSource <- ZIO.service[DataSource]
      _          <- AgentPostgresMigrations.resetAll(dataSource)
      _          <- AgentPostgresMigrations.migrateCoreAndKnowledge1024(dataSource)
      _          <- Console.printLine("已重建核心与 1024 知识 schema。")
    yield ()).provide(ProductionSupportLayers.dataSource(config))

  private def migrate(config: ProductionSupportConfig): ZIO[Any, Any, Unit] =
    (for
      dataSource <- ZIO.service[DataSource]
      _          <- AgentPostgresMigrations.migrateCoreAndKnowledge1024(dataSource)
      _          <- Console.printLine("核心控制面与知识 schema 已迁移。")
    yield ()).provide(ProductionSupportLayers.dataSource(config))

  private def status(config: ProductionSupportConfig): ZIO[Any, Any, Unit] =
    config.requireDurableDatabase *>
      (for
        dataSource <- ZIO.service[DataSource]
        readiness  <- AgentUpgradePreflight.inspect(dataSource)
        knowledge  <- AgentPostgresMigrations.inspectKnowledge1024(dataSource)
        _          <- Console.printLine(renderStatus(readiness, knowledge))
      yield ()).provide(ProductionSupportLayers.dataSource(config))

  private def renderStatus(
      readiness: com.zyblw.agent.persistence.postgres.AgentUpgradeReadiness,
      knowledge: com.zyblw.agent.persistence.postgres.AgentPostgresSchemaStatus
  ): String =
    val core    = readiness.schema.currentVersion.getOrElse("empty")
    val pending =
      if readiness.schema.hasPending then readiness.schema.pendingVersions.mkString(",") else "none"
    val knowledgeVersion = knowledge.currentVersion.getOrElse("empty")
    val knowledgePending =
      if knowledge.hasPending then knowledge.pendingVersions.mkString(",") else "none"
    val verdict =
      if readiness.schema.hasPending || knowledge.hasPending then "先 migrate 空库或补齐 pending，再 serve。"
      else if readiness.drainRequired then "schema 已是 0.8 基线，但仍有进行中工作，切换进程前先 drain。"
      else "可以 serve 或继续摄入。"
    s"""问答宿主预检
  核心 schema: $core
  核心 pending: $pending
  知识 schema: $knowledgeVersion
  知识 pending: $knowledgePending
  进行中 Run: ${readiness.activeRuns}
  等待审批: ${readiness.waitingForApproval}
  排队命令: ${readiness.queuedCommands}
  已领取命令: ${readiness.leasedCommands}
  结论: $verdict"""

  private def ingest(config: ProductionSupportConfig, directory: String): ZIO[Any, Any, Unit] =
    val source  = KnowledgeQaLayers.directorySource(directory)
    val program =
      for
        rag      <- ZIO.service[RagApplication]
        outcomes <- rag
          .ingestInputs(
            source.inputs,
            KnowledgeQaLayers.tenant,
            KnowledgeQaLayers.readerPermissions,
            "qa-ingest"
          )
          .runCollect
        indexed = outcomes.count {
          case DocumentIngestionOutcome.Indexed(_, _) => true
          case _                                      => false
        }
        _ <- Console.printLine(s"摄入完成：成功 $indexed / ${outcomes.length}，目录 $directory。")
      yield ()
    if config.jdbcUrl.exists(_.nonEmpty) then
      program.provide(
        ProductionSupportLayers.dataSource(config),
        Client.default,
        KnowledgeQaLayers.embedding(config),
        KnowledgeQaLayers.postgresStack
      )
    else program.provide(KnowledgeQaLayers.inMemoryStack)

  private def reindex(config: ProductionSupportConfig): ZIO[Any, Any, Unit] =
    config.requireDurableDatabase *>
      (for
        service <- ZIO.service[com.zyblw.agent.admin.KnowledgeService]
        report  <- service.reindex(
          KnowledgeQaLayers.tenant.value,
          KnowledgeQaLayers.permissions,
          None,
          32
        )
        _ <- Console.printLine(s"重建 ${report.items.length} 份文档，hasMore=${report.hasMore}。")
      yield ()).provide(
        ProductionSupportLayers.dataSource(config),
        Client.default,
        KnowledgeQaLayers.embedding(config),
        KnowledgeQaLayers.postgresStack
      )

  private def serve(config: ProductionSupportConfig): ZIO[Any, Any, Nothing] =
    AgentDefinitionBuilder(AgentId("knowledge-qa"), "书籍问答")
      .withInstructions("只根据已授权知识回答，并给出可核验引用。资料不足时明确拒绝编造。")
      .allowTools(KnowledgeTools.Allowed)
      .withMetadata("scenario", "knowledge-qa")
      .buildFor(KnowledgeQaLayers.applicationConfig(config).toolPolicy)
      .flatMap { agent =>
        if config.jdbcUrl.exists(_.nonEmpty) then config.requireDurableDatabase *> serveDurable(config, agent)
        else serveInMemory(config, agent)
      }

  private def serveInMemory(
      config: ProductionSupportConfig,
      agent: AgentDefinition
  ): ZIO[Any, Any, Nothing] =
    Console.printLine(
      s"知识问答 contract 内存宿主监听 http://127.0.0.1:${config.httpPort}；该模式不能用于生产。"
    ) *> AgentHttpHost.serve.provide(
      KnowledgeQaLayers.inMemoryStack,
      KnowledgeQaLayers.tools,
      KnowledgeQaLayers.scriptedModel,
      MemoryRagContextSourceResolver.configured(MemoryRagContextPolicy()),
      ProductionSupportLayers.guardrails,
      ProductionSupportLayers.observer(config),
      AgentApplication.inMemory(WorkerId(config.workerId), KnowledgeQaLayers.applicationConfig(config)),
      AgentRegistry.fromAgents(List(agent)),
      ProductionSupportLayers.identity(config),
      DurableRunEventStream.default,
      AgentHttpApi.layer,
      AgentHostReadiness.alwaysReady,
      ProductionSupportLayers.inMemoryMemory,
      MemoryHttpApi.layer,
      KnowledgeHttpApi.layer,
      KnowledgeQaLayers.additionalRoutes,
      KnowledgeQaLayers.hostConfig(config),
      Server.defaultWithPort(config.httpPort),
      AgentHttpServer.zioHttp,
      AgentHttpHost.fromApplication
    )

  private def serveDurable(
      config: ProductionSupportConfig,
      agent: AgentDefinition
  ): ZIO[Any, Any, Nothing] =
    Console.printLine(
      s"知识问答宿主监听 http://127.0.0.1:${config.httpPort}；Worker=${config.workerId}。"
    ) *> AgentHttpHost.serve.provide(
      ProductionSupportLayers.dataSource(config),
      Client.default,
      modelLayer(config),
      KnowledgeQaLayers.embedding(config),
      KnowledgeQaLayers.postgresStack,
      KnowledgeQaLayers.tools,
      ProductionSupportLayers.durableMemory,
      KnowledgeQaLayers.durableApplication(config),
      AgentRegistry.fromAgents(List(agent)),
      ProductionSupportLayers.identity(config),
      DurableRunEventStream.default,
      AgentHttpApi.layer,
      AgentHostReadiness.jdbc,
      MemoryHttpApi.layer,
      KnowledgeHttpApi.layer,
      KnowledgeQaLayers.additionalRoutes,
      ProductionSupportLayers.durableSideEffects(config),
      KnowledgeQaLayers.hostConfig(config),
      Server.defaultWithPort(config.httpPort),
      AgentHttpServer.zioHttp,
      ProductionSupportLayers.durableHost
    )

  private def modelLayer(
      config: ProductionSupportConfig
  ): ZLayer[Client, AgentError, com.zyblw.agent.model.ChatModel] =
    config.mode match
      case ProductionSupportMode.Contract => KnowledgeQaLayers.scriptedModel
      case ProductionSupportMode.Live     => ProductionSupportLayers.liveModel
