package com.zyblw.agent.examples.knowledge

import com.zyblw.agent.admin.{KnowledgeAuthorization, KnowledgeRetrievalRequest, KnowledgeService}
import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.examples.production.{
  ProductionAuthMode,
  ProductionSupportConfig,
  ProductionSupportLayers,
  ProductionSupportMode
}
import com.zyblw.agent.http.*
import com.zyblw.agent.http.contract.KnowledgeSearchResponse
import com.zyblw.agent.integrations.openai.OpenAICompatibleEmbeddingConfig
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.rag.*
import com.zyblw.agent.rag.tools.KnowledgeTools
import com.zyblw.agent.scheduler.WorkerHostConfig
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

/** 书籍问答主线：摄入 → 稳定知识 HTTP 检索引用 → Run 写入 retrieval evidence。 */
object KnowledgeQaHostContractSpec extends ZIOSpecDefault:
  private val fastWorker = WorkerHostConfig(
    leaseDuration = 5.seconds,
    heartbeatEvery = 1.second,
    pollEvery = 10.millis,
    retryDelay = Duration.Zero,
    maxAttempts = 3
  )

  private val contractConfig = ProductionSupportConfig(
    command = com.zyblw.agent.examples.production.ProductionSupportCommand.Serve,
    mode = ProductionSupportMode.Contract,
    httpPort = 18081,
    workerId = "knowledge-qa-contract",
    jdbcUrl = None,
    dbUser = None,
    dbPasswordEnv = Some("ZYBLW_AGENT_DB_PASSWORD"),
    authMode = ProductionAuthMode.AnonymousContract,
    otlpEndpoint = None,
    worker = fastWorker
  )

  private val actor = RunContext(
    userId = Some("reader-1"),
    tenantId = Some(KnowledgeQaLayers.tenant.value),
    scopes = Set(KnowledgeAuthorization.ReadScope),
    attributes = com.zyblw.agent.rag.tools.DocumentScope.unrestrictedAttributes
  )

  private val resolver = new AgentRequestContextResolver:
    def resolve(request: Request): IO[AgentError, RunContext] =
      val _ = request
      ZIO.succeed(actor)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeQaHost")(
    test("status 在缺少数据库配置时 fail-closed") {
      contractConfig.requireDurableDatabase.either.map { result =>
        assertTrue(result.left.exists(_.message.contains("live 模式必须提供")))
      }
    },
    test("live Embedding 拒绝与 1024 schema 不一致的维度") {
      KnowledgeQaLayers
        .require1024(OpenAICompatibleEmbeddingConfig.openAI("test-key", dimension = 1536))
        .either
        .map { result =>
          assertTrue(result.left.exists(_.message.contains("1024")))
        }
    },
    test("live Embedding 缺少 tokenizer 声明时拒绝启动") {
      KnowledgeQaLayers
        .requireDeclaredTokenizer(
          OpenAICompatibleEmbeddingConfig.openAI("test-key", dimension = 1024).copy(tokenizerId = None)
        )
        .either
        .map { result =>
          assertTrue(result.left.exists(_.message.contains("EMBEDDING_TOKENIZER")))
        }
    },
    test("live Embedding 接受 1024 维配置") {
      KnowledgeQaLayers
        .require1024(OpenAICompatibleEmbeddingConfig.openAI("test-key", dimension = 1024))
        .map(config => assertTrue(config.dimension == 1024))
    },
    test("摄入后知识搜索返回引用，Run 写入 retrieval evidence") {
      (for
        rag       <- ZIO.service[RagApplication]
        knowledge <- ZIO.service[KnowledgeService]
        http      <- ZIO.service[KnowledgeHttpApi]
        app       <- ZIO.service[AgentApplication]
        outcome   <- rag.ingestOne(
          DocumentIngestionRequest(
            DocumentInput.fromBytes(
              "suwen-1",
              "book://suwen/1",
              "suwen.md",
              "text/markdown",
              Chunk.fromArray(
                "# 阴阳\n\n阴阳者，天地之道也。桂枝汤由桂枝、芍药组成。".getBytes(java.nio.charset.StandardCharsets.UTF_8)
              )
            ),
            KnowledgeQaLayers.tenant,
            KnowledgeQaLayers.readerPermissions,
            "qa-contract-1"
          )
        )
        search <- knowledge.search(
          KnowledgeQaLayers.tenant.value,
          actor.scopes,
          KnowledgeRetrievalRequest(
            query = "桂枝汤",
            tenantId = KnowledgeQaLayers.tenant.value,
            permissions = actor.scopes,
            mode = "phrase"
          )
        )
        response <- http.routes.runZIO(
          Request.post(
            URL.root / "api" / "v1" / "knowledge" / "search",
            Body.fromString("""{"query":"桂枝汤","mode":"phrase"}""")
          )
        )
        body <- response.body.asString
        decoded = body.fromJson[KnowledgeSearchResponse]
        agent <- AgentDefinitionBuilder(AgentId("knowledge-qa"), "书籍问答")
          .withInstructions("只根据已授权知识回答。")
          .allowTools(KnowledgeTools.Allowed)
          .buildFor(KnowledgeQaLayers.applicationConfig(contractConfig).toolPolicy)
        _       <- app.startWorkerScoped
        command <- app.submit(
          agent,
          RunRequest(ThreadId("qa-contract"), AgentMessage.user("桂枝汤由什么组成？"), actor),
          "qa-contract-run-1"
        )
        done <- awaitStatus(app, command.runId, RunStatus.Completed)
      yield assertTrue(
        outcome.isInstanceOf[DocumentIngestionOutcome.Indexed],
        search.citations.nonEmpty,
        response.status == Status.Ok,
        decoded.exists(_.citations.nonEmpty),
        done.retrievalEvidence.nonEmpty,
        done.messages.lastOption.exists(_.text.contains("授权知识"))
      )).provideSome[Scope](
        KnowledgeQaLayers.inMemoryStack,
        KnowledgeQaLayers.tools,
        KnowledgeQaLayers.scriptedModel,
        MemoryRagContextSourceResolver.configured(MemoryRagContextPolicy()),
        ProductionSupportLayers.guardrails,
        ProductionSupportLayers.observer(contractConfig),
        AgentApplication
          .inMemory(WorkerId(contractConfig.workerId), KnowledgeQaLayers.applicationConfig(contractConfig)),
        ProductionSupportLayers.inMemoryMemory,
        ZLayer.succeed[AgentRequestContextResolver](resolver),
        KnowledgeHttpApi.layer
      )
    }
  ) @@ TestAspect.timeout(20.seconds) @@ TestAspect.withLiveClock

  private def awaitStatus(
      app: AgentApplication,
      runId: RunId,
      expected: RunStatus
  ): IO[AgentError, AgentState] =
    app
      .inspect(runId)
      .flatMap { state =>
        if state.status == expected then ZIO.succeed(state)
        else if terminal(state.status) && state.status != expected then
          ZIO.fail(AgentError.Unexpected(s"Run 以 ${state.status} 结束，期望 $expected"))
        else ZIO.sleep(20.millis) *> awaitStatus(app, runId, expected)
      }
      .timeoutFail(AgentError.Unexpected(s"等待 $expected 超时"))(8.seconds)

  private def terminal(status: RunStatus): Boolean =
    status match
      case RunStatus.Completed | RunStatus.Failed | RunStatus.Cancelled | RunStatus.TimedOut |
          RunStatus.BudgetExceeded =>
        true
      case _ => false
