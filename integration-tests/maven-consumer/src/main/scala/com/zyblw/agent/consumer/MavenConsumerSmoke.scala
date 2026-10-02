package com.zyblw.agent.consumer

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.persistence.postgres.PostgresAgentPersistence
import com.zyblw.agent.rag.*
import com.zyblw.agent.loaders.{PaddleOcrArtifact, PaddleOcrDocumentLoader}
import com.zyblw.agent.persistence.postgres.PostgresStructureStore
import com.zyblw.agent.scheduler.*
import com.zyblw.agent.tools.*
import javax.sql.DataSource
import zio.*
import zio.Unsafe

/** 只依赖 Maven 制品编译的外部消费者契约。
  *
  * 除最小 ADT 外，这里刻意组装生产常用的 Worker 配置、Agent Definition、PostgreSQL 控制面、知识存储和 Durable
  * Application Layer。发布流水线由此验证公开 POM 与跨 artifact 类型确实能被独立业务项目使用，而不是只验证类文件存在。
  */
object MavenConsumerSmoke:
  private val toolPolicy = ToolPolicyConfig.secureDefault

  val applicationConfig: AgentApplicationConfig = AgentApplicationConfig(
    toolPolicy = toolPolicy,
    worker = WorkerHostConfig(parallelism = 4)
  )

  val definition: IO[AgentError.InvalidConfiguration, AgentDefinition] =
    AgentDefinitionBuilder(AgentId("maven-consumer"), "Maven consumer")
      .withInstructions("Return a deterministic consumer contract response.")
      .buildFor(toolPolicy)

  val postgresControlPlane: URLayer[
    DataSource,
    RunStore & RunCommandStore & RunSubmissionStore
  ] = PostgresAgentPersistence.layer

  val postgresKnowledge: URLayer[DataSource, KnowledgeIndexStore & VectorStore] =
    PostgresAgentPersistence.knowledge(dimension = 1024)

  val postgresStructures: URLayer[DataSource, StructureStore] = PostgresStructureStore.layer
  val paddleLoader: DocumentLoader = new PaddleOcrDocumentLoader
  val paddleArtifact: PaddleOcrArtifact = PaddleOcrArtifact("[]", pageCount = Some(1))
  val structureSpec: StructureBuildSpec = StructureBuildSpec(summary = Some(NodeSummarySpec("internal", "rule-v1")))

  def bookRetriever(model: EmbeddingModel, vectors: VectorStore, reranker: Reranker, structures: StructureStore): Retriever =
    DefaultRetriever(model, vectors, reranker, structural = Some(StructuralRetrieval(structures, structureSpec)),
      defaultRecipe = Some(RetrievalRecipe.BookGrounded))

  val durableApplication: URLayer[AgentApplication.DurableDependencies, AgentApplication.Services] =
    AgentApplication.durable(WorkerId("maven-consumer-worker"), applicationConfig)

  val providerUnauthorized: AgentError.ModelHttpFailure =
    AgentError.ModelHttpFailure("consumer-provider", 401, Some("invalid_api_key"))

  // Public accounting metadata must be usable from published artifacts.
  def reportedCost(call: ModelCallExecutionRecord): Option[BigDecimal] =
    for
      tokens <- call.usage if call.usageReporting
      price <- call.priceSnapshot if call.priceBookFingerprint.nonEmpty
    yield price.estimate(tokens)

  def main(args: Array[String]): Unit =
    val agentId = AgentId("maven-consumer")
    val message = AgentMessage.user("consumer contract")

    require(agentId.value == "maven-consumer")
    require(message.text == "consumer contract")
    require(!ChatResponse(AgentMessage.assistant("ok"), FinishReason.Stop, usageReported = false).usageReported)
    val evidence = ModelPriceProvenance("quote", "manual", "model", "https://example.com/pricing", "USD",
      "1", "2", None, None, "7", "2026-09-30", "https://frankfurter.dev/", "2026-10-01T00:00:00Z")
    val prices = ModelPriceBook.of(("provider", "model", ModelPrice(7, 14, currency = "CNY", provenance = Some(evidence))))
    require(ModelPolicySource.static(ModelPolicy.default, prices).pricesFor(Some(prices.fingerprint)) == prices)
    require(prices.prices("provider" -> "model").provenance.exists(_.nativeCurrency == "USD"))
    require(applicationConfig.worker.parallelism == 4)
    require(providerUnauthorized.category == ErrorCategory.Authentication)
    Unsafe.unsafe { implicit unsafe =>
      val embeddings = Runtime.default.unsafe
        .run(EmbeddingModelOps.embedTexts(HashEmbedding(8), Chunk("consumer-contract")))
        .getOrThrowFiberFailure()
      require(embeddings.length == 1)
      require(embeddings.head.values.length == 8)
    }
