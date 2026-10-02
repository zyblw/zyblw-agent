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
  TikaDocumentLoader,
  PaddleOcrDocumentLoader
}
import com.zyblw.agent.memory.{MemoryStore, WorkerId}
import com.zyblw.agent.persistence.postgres.PostgresAgentPersistence
import com.zyblw.agent.rag.*
import com.zyblw.agent.integrations.rerank.{QwenRerankConfig, QwenRerankModel}
import com.zyblw.agent.rag.tools.{DocumentScope, KnowledgeTools}
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

  def directoryResolverFor(root: String): ULayer[KnowledgeSourceResolver] =
    ZLayer.succeed {
      val source = directorySource(root)
      new KnowledgeSourceResolver:
        def load(tenantId: TenantId, documentId: String): IO[RetrievalError, Option[DocumentInput]] =
          val _ = tenantId
          source.loadById(documentId)
    }

  val directoryResolver: ULayer[KnowledgeSourceResolver] = directoryResolverFor(booksDirectory)

  private def ragAndKnowledge(root: String): ZLayer[
    EmbeddingModel & KnowledgeIndexStore & VectorStore & KnowledgeIndexDirectory & StructureStore &
      ChatModel & Reranker,
    RetrievalError,
    RagApplication & KnowledgeService & Retriever
  ] =
    ZLayer.makeSome[
      EmbeddingModel & KnowledgeIndexStore & VectorStore & KnowledgeIndexDirectory & StructureStore &
        ChatModel & Reranker,
      RagApplication & KnowledgeService & Retriever
    ](
      DocumentLoaderRegistry.layer(Chunk(TikaDocumentLoader(), new PaddleOcrDocumentLoader)),
      DocumentStructureChunker.alignedLayer,
      ZLayer.fromZIO {
        for
          store <- ZIO.service[StructureStore]
          model <- ZIO.service[ChatModel]
          modelSummary = sys.env.get("ZYBLW_AGENT_STRUCTURE_SUMMARY_MODEL").map(_.trim).filter(_.nonEmpty)
          provider     = sys.env
            .get("ZYBLW_AGENT_STRUCTURE_SUMMARY_PROVIDER")
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse(model.provider)
          _ <- ZIO
            .fail(
              AgentError.RetrievalFailed(
                "routed summary model requires an exact ZYBLW_AGENT_STRUCTURE_SUMMARY_PROVIDER"
              )
            )
            .when(modelSummary.nonEmpty && provider == "router")
          summary = modelSummary.fold(NodeSummarySpec("internal", "rule-v1"))(id =>
            NodeSummarySpec(provider, id)
          )
          spec      = StructureBuildSpec(summary = Some(summary))
          navigator =
            if sys.env.get("ZYBLW_AGENT_TREE_MODEL_ENABLED").contains("1") then
              ModelStructureNavigation.navigator(model)
            else TreeNavigator.deterministic
        yield StructuralRetrieval(store, spec, navigator = navigator)
      },
      ZLayer.fromZIO {
        for
          chunker    <- ZIO.service[Chunker]
          model      <- ZIO.service[EmbeddingModel]
          store      <- ZIO.service[KnowledgeIndexStore]
          structural <- ZIO.service[StructuralRetrieval]
          chat       <- ZIO.service[ChatModel]
          cache      <- InMemoryNodeSummaryStore.make
          summary = Option.when(structural.spec.summary.exists(_.provider != "internal"))(
            NodeSummarizer.cached(ModelStructureNavigation.summarizer(chat), cache)
          )
        yield KnowledgeIndexer(
          chunker,
          model,
          store,
          structureIndexing = Some(
            StructureIndexing(
              structural.store,
              StructurePublicationPolicy.BestEffort,
              structural.spec,
              summarizer = summary
            )
          )
        )
      },
      DocumentIngestionService.layer(failureMode = DocumentIngestionFailureMode.Continue),
      ZLayer.fromFunction(
        (model: EmbeddingModel, vectors: VectorStore, reranker: Reranker, structural: StructuralRetrieval) =>
          DefaultRetriever(
            model,
            vectors,
            reranker,
            structural = Some(structural),
            defaultRecipe = Some(RetrievalRecipe.BookGrounded)
          ): Retriever
      ),
      ZLayer.fromFunction(
        (ingestion: DocumentIngestionService, retriever: Retriever, directory: KnowledgeIndexDirectory) =>
          RagApplication(ingestion, retriever, catalog = Some(directory))
      ),
      IngestionJobStore.inMemory,
      ZLayer.succeed(RetrievalPolicySource.default),
      directoryResolverFor(root),
      KnowledgeReindexService.layer,
      KnowledgeAdminLive.structuredLayer,
      KnowledgeServiceLive.layer
    )

  lazy val inMemoryStack: ZLayer[Any, RetrievalError, Stack] =
    ZLayer.make[Stack](
      ZLayer.succeed[EmbeddingModel](HashEmbedding(64)),
      KnowledgeIndexDirectory.inMemoryKnowledge,
      ZLayer
        .fromZIO(ZIO.serviceWithZIO[KnowledgeIndexStore](InMemoryStructureStore.make))
        .map(env => ZEnvironment[StructureStore](env.get[InMemoryStructureStore])),
      scriptedModel,
      Reranker.identity,
      ragAndKnowledge(booksDirectory)
    )

  lazy val postgresStack: ZLayer[DataSource & EmbeddingModel & ChatModel & Client, RetrievalError, Stack] =
    postgresStackFor(booksDirectory)

  def postgresStackFor(
      root: String
  ): ZLayer[DataSource & EmbeddingModel & ChatModel & Client, RetrievalError, Stack] =
    ZLayer.makeSome[DataSource & EmbeddingModel & ChatModel & Client, Stack](
      configuredReranker,
      PostgresAgentPersistence.knowledge(1024),
      com.zyblw.agent.persistence.postgres.PostgresStructureStore.layer,
      ragAndKnowledge(root)
    )

  /** No remote rerank call unless an exact model is configured; credentials stay in the adapter. */
  val configuredReranker: ZLayer[Client, RetrievalError, Reranker] = ZLayer.fromZIO {
    sys.env.get("ZYBLW_AGENT_RERANK_MODEL").map(_.trim).filter(_.nonEmpty) match
      case None =>
        ZIO.succeed(
          new Reranker:
            def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): UIO[Chunk[RetrievalHit]] =
              ZIO.succeed(hits.take(limit))
        )
      case Some(id) =>
        for
          client <- ZIO.service[Client]
          key    <- ZIO
            .fromOption(sys.env.get("ZYBLW_AGENT_RERANK_API_KEY").filter(_.trim.nonEmpty))
            .orElseFail(AgentError.RetrievalFailed("configured reranker requires ZYBLW_AGENT_RERANK_API_KEY"))
          config <- ZIO
            .attempt(
              QwenRerankConfig(
                key,
                id,
                baseUrl =
                  sys.env.getOrElse("ZYBLW_AGENT_RERANK_BASE_URL", "https://dashscope.aliyuncs.com/api/v1")
              )
            )
            .mapError(_ => AgentError.RetrievalFailed("reranker configuration invalid"))
        yield ModelReranker(QwenRerankModel(client, config), ModelRerankerPolicy(maxCandidates = 100))
  }

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

  /** live 摄入必须显式声明 tokenizer，不能把 cl100k 当成所有 Embedding 模型的默认值。 */
  def requireDeclaredTokenizer(
      config: OpenAICompatibleEmbeddingConfig
  ): IO[AgentError, OpenAICompatibleEmbeddingConfig] =
    OpenAICompatibleEmbeddingConfig.requireDeclaredTokenizer(config)

  val liveEmbedding: ZLayer[Client, AgentError, EmbeddingModel] =
    ZLayer.fromZIO {
      for
        config <- OpenAICompatibleEmbeddingConfig.fromEnvironment
          .flatMap(require1024)
          .flatMap(requireDeclaredTokenizer)
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

  /** 参考宿主显式允许已授权资料库。单书会话应在身份解析结果上改成非空文档范围。 */
  def knowledgeIdentity(
      config: ProductionSupportConfig
  ): ULayer[com.zyblw.agent.http.AgentRequestContextResolver] =
    ProductionSupportLayers.identity(config) >>> ZLayer.fromFunction {
      (inner: com.zyblw.agent.http.AgentRequestContextResolver) =>
        new com.zyblw.agent.http.AgentRequestContextResolver:
          def resolve(request: zio.http.Request): IO[AgentError, RunContext] =
            inner.resolve(request).map { context =>
              val mentioned = context.attributes.contains(DocumentScope.ModeAttribute) ||
                context.attributes.contains(DocumentScope.SingleAttribute) ||
                context.attributes.contains(DocumentScope.ManyAttribute)
              if mentioned then context
              else context.copy(attributes = context.attributes ++ DocumentScope.unrestrictedAttributes)
            }
    }

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
      MemoryRagContextSourceResolver.configured(
        MemoryRagContextPolicy(lowEvidenceResponse = LowEvidenceResponse.RequireExplicitRefusal)
      ),
      ProductionSupportLayers.guardrails,
      ProductionSupportLayers.observer(config),
      AgentApplication.durable(WorkerId(config.workerId), applicationConfig(config))
    )
