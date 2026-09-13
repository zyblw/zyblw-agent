package com.zyblw.agent.examples.knowledge

import com.zyblw.agent.admin.{IngestionJobStore, KnowledgeService}
import com.zyblw.agent.app.{AgentApplication, AgentApplicationConfig}
import com.zyblw.agent.core.*
import com.zyblw.agent.model.ChatModel
import com.zyblw.agent.examples.production.{
  ProductionSupportConfig,
  ProductionSupportLayers,
  ProductionSupportMode
}
import com.zyblw.agent.integrations.openai.{OpenAICompatibleEmbeddingConfig, OpenAICompatibleEmbeddingService}
import com.zyblw.agent.http.*
import com.zyblw.agent.http.host.{AgentHttpAdditionalRoutes, AgentHttpHostConfig}
import com.zyblw.agent.loaders.{
  LocalDocumentDirectoryConfig,
  LocalDocumentDirectorySource,
  TikaDocumentLoader
}
import com.zyblw.agent.memory.{MemoryStore, WorkerId}
import com.zyblw.agent.persistence.postgres.PostgresAgentPersistence
import com.zyblw.agent.rag.*
import com.zyblw.agent.rag.tools.KnowledgeTools
import com.zyblw.agent.testkit.ScriptedChatModel
import com.zyblw.agent.tools.{RegisteredTool, RegisteredToolRegistry}
import javax.sql.DataSource
import zio.*
import zio.http.Client

/** PDF 书籍问答宿主的 RAG / 知识 HTTP 装配。 */
object KnowledgeQaLayers:
  val tenant: TenantId               = TenantId("demo-tenant")
  val readerPermissions: Set[String] = Set("knowledge:read")
  val permissions: Set[String]       = Set("knowledge:read", "knowledge:write", "knowledge:admin")
  val defaultDirectory: String       = "data/books"

  def booksDirectory: String =
    sys.env.get("ZYBLW_AGENT_BOOKS_DIR").map(_.trim).filter(_.nonEmpty).getOrElse(defaultDirectory)

  type Stack = RagApplication & KnowledgeService & Retriever

  def directorySource(root: String): LocalDocumentDirectorySource =
    LocalDocumentDirectorySource(LocalDocumentDirectoryConfig(java.nio.file.Path.of(root)))

  val directoryResolver: ULayer[KnowledgeSourceResolver] =
    ZLayer.succeed {
      val source = directorySource(booksDirectory)
      new KnowledgeSourceResolver:
        def load(tenantId: TenantId, documentId: String): IO[RetrievalError, Option[DocumentInput]] =
          val _ = tenantId
          source.loadById(documentId)
    }

  private val ragAndKnowledge: ZLayer[
    EmbeddingModel & KnowledgeIndexStore & VectorStore & KnowledgeIndexDirectory,
    RetrievalError,
    RagApplication & KnowledgeService & Retriever
  ] =
    ZLayer.makeSome[
      EmbeddingModel & KnowledgeIndexStore & VectorStore & KnowledgeIndexDirectory,
      RagApplication & KnowledgeService & Retriever
    ](
      DocumentLoaderRegistry.layer(Chunk(TikaDocumentLoader())),
      DocumentStructureChunker.layer,
      KnowledgeIndexer.layer(),
      DocumentIngestionService.layer(failureMode = DocumentIngestionFailureMode.Continue),
      Reranker.identity,
      DefaultRetriever.layer,
      RagApplication.layer,
      IngestionJobStore.inMemory,
      ZLayer.succeed(RetrievalPolicySource.default),
      directoryResolver,
      KnowledgeReindexService.layer,
      KnowledgeAdminLive.layer(),
      KnowledgeServiceLive.layer
    )

  val inMemoryStack: ZLayer[Any, RetrievalError, Stack] =
    ZLayer.make[Stack](
      ZLayer.succeed[EmbeddingModel](HashEmbedding(64)),
      KnowledgeIndexDirectory.inMemoryKnowledge,
      ragAndKnowledge
    )

  val postgresStack: ZLayer[DataSource & EmbeddingModel, RetrievalError, Stack] =
    ZLayer.makeSome[DataSource & EmbeddingModel, Stack](
      PostgresAgentPersistence.knowledge(1024),
      ragAndKnowledge
    )

  val contractEmbedding: ULayer[EmbeddingModel] =
    ZLayer.succeed(HashEmbedding(1024))

  def require1024(
      config: OpenAICompatibleEmbeddingConfig
  ): IO[AgentError, OpenAICompatibleEmbeddingConfig] =
    ZIO
      .fail(
        AgentError.InvalidConfiguration(
          s"当前知识基线要求 embedding 维度与选定 identity 一致（默认 1024），实际为 ${config.dimension}"
        )
      )
      .when(config.dimension != 1024)
      .as(config)

  val liveEmbedding: ZLayer[Client, AgentError, EmbeddingModel] =
    ZLayer.fromZIO {
      for
        config <- OpenAICompatibleEmbeddingConfig.fromEnvironment.flatMap(require1024)
        client <- ZIO.service[Client]
      yield OpenAICompatibleEmbeddingService(client, config)
    }

  def embedding(config: ProductionSupportConfig): ZLayer[Client, AgentError, EmbeddingModel] =
    config.mode match
      case ProductionSupportMode.Contract => contractEmbedding
      case ProductionSupportMode.Live     => liveEmbedding

  val scriptedModel: ULayer[ChatModel] =
    ScriptedChatModel.layer(
      Chunk(ChatResponse(AgentMessage.assistant("已根据授权知识回答。"), FinishReason.Stop, TokenUsage(8, 10)))
    )

  val tools: ZLayer[RagApplication, AgentError, RegisteredToolRegistry] =
    KnowledgeTools.layer >>>
      ZLayer.fromZIO(ZIO.serviceWithZIO[Chunk[RegisteredTool]](RegisteredToolRegistry.make(_)))

  def additionalRoutes: URLayer[MemoryHttpApi & KnowledgeHttpApi, AgentHttpAdditionalRoutes] =
    ZLayer.fromFunction((memory: MemoryHttpApi, knowledge: KnowledgeHttpApi) =>
      AgentHttpAdditionalRoutes(memory.routes ++ knowledge.routes)
    )

  def applicationConfig(config: ProductionSupportConfig): AgentApplicationConfig =
    AgentApplicationConfig(toolPolicy = KnowledgeTools.policyFragment, worker = config.worker)

  def hostConfig(config: ProductionSupportConfig): ULayer[AgentHttpHostConfig] =
    ZLayer.succeed(
      AgentHttpHostConfig(
        serviceName = "zyblw-agent-knowledge-qa",
        serviceVersion = "0.9.0",
        environment = config.mode.toString.toLowerCase
      )
    )

  def durableApplication(
      config: ProductionSupportConfig
  ): ZLayer[
    ChatModel & RegisteredToolRegistry & DataSource & Retriever & MemoryStore,
    AgentError,
    AgentApplication.Services
  ] =
    ZLayer.makeSome[
      ChatModel & RegisteredToolRegistry & DataSource & Retriever & MemoryStore,
      AgentApplication.Services
    ](
      PostgresAgentPersistence.layer,
      MemoryRagContextSourceResolver.configured(MemoryRagContextPolicy()),
      ProductionSupportLayers.guardrails,
      ProductionSupportLayers.observer(config),
      AgentApplication.durable(WorkerId(config.workerId), applicationConfig(config))
    )
