package com.zyblw.agent.examples

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.model.*
import com.zyblw.agent.rag.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json

/** DocumentInput → Loader → 原子索引 → 权限检索 → Agent 引用回答的最小可运行示例。 */
object RagAgentExample extends ZIOAppDefault:
  final case class LookupInput(query: String) derives JsonCodec
  final case class LookupOutput(excerpts: Chunk[String], citations: Chunk[String]) derives JsonCodec

  private val markdown =
    "# 阴阳\n\n## 定义\n\n阴阳用于描述相互关联事物的对立统一关系。本示例仅用于中医学习，不提供诊疗建议。"

  private val markdownLoader = new DocumentLoader:
    override val id: String                       = "example-markdown"
    override val supportedMediaTypes: Set[String] = Set("text/markdown")

    override def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
      input.content.runCollect.map(bytes =>
        SourceDocument(
          input.id,
          String(bytes.toArray, StandardCharsets.UTF_8),
          input.sourceUri,
          representation = DocumentRepresentation.Markdown
        )
      )

  /** 业务 composition root：编译器会检查 Loader、Indexer、Retriever 两侧都已接入同一个知识快照。 */
  private val localRagLayer: ZLayer[Any, RetrievalError, RagApplication] =
    ZLayer.make[RagApplication](
      DocumentLoaderRegistry.layer(Chunk(markdownLoader)),
      ZLayer.succeed[EmbeddingService](HashEmbedding(64)),
      InMemoryKnowledgeIndexStore.knowledge,
      MarkdownStructureChunker.layer,
      KnowledgeIndexer.layer(),
      DocumentIngestionService.layer(failureMode = DocumentIngestionFailureMode.FailFast),
      Reranker.identity,
      DefaultRetriever.layer,
      RagApplication.layer
    )

  private val script = Chunk(
    ChatResponse(
      AgentMessage.assistantToolCalls(
        Chunk(ToolCall("lookup-1", "knowledge_lookup", Json.Obj("query" -> Json.Str("什么是阴阳"))))
      ),
      FinishReason.ToolCalls
    ),
    ChatResponse(
      AgentMessage.assistant("阴阳可用于描述相互关联事物的对立统一关系。[cite-1]"),
      FinishReason.Stop
    )
  )

  def run =
    (for
      rag <- ZIO.service[RagApplication]
      tenant = TenantId("demo-tenant")
      input  = DocumentInput.fromBytes(
        "doc-yinyang",
        "memory://docs/yinyang.md",
        "yinyang.md",
        "text/markdown",
        Chunk.fromArray(markdown.getBytes(StandardCharsets.UTF_8))
      )
      indexed <- rag.ingestOne(
        DocumentIngestionRequest(
          input,
          tenant,
          Set("knowledge:read"),
          "rag-example-upload-1",
          ActiveVersionExpectation.NoActiveVersion
        )
      )
      lookup = Tool.json[Any, LookupInput, AgentError.ToolExecutionFailed, LookupOutput](
        ToolName("knowledge_lookup"),
        "检索有来源的学习资料",
        TestSchemas.stringObject("query", "问题"),
        None,
        ToolMetadata(ToolRisk.UserScopedRead, SideEffect.None, requiredScopes = Set("knowledge:read"))
      ) { (input, context) =>
        rag
          .retrieve(
            RagQuery(
              input.query,
              RetrievalScope(
                TenantId(context.runContext.tenantId.getOrElse("demo-tenant")),
                context.runContext.scopes
              ),
              Some(3)
            )
          )
          .map(result => LookupOutput(result.hits.map(_.chunk.text), result.citations.map(_.id)))
          .mapError(error =>
            AgentError.ToolExecutionFailed("knowledge_lookup", error.message, error.retryable)
          )
      }
      registered <- RegisteredTool.make(lookup)
      model      <- ScriptedChatModel.make(script)
      appConfig = AgentApplicationConfig(
        toolPolicy = ToolPolicyConfig(allowedTools = Set(ToolName("knowledge_lookup")))
      )
      agent <- AgentDefinitionBuilder(AgentId("rag-demo"), "RAG 示例")
        .withInstructions("只根据检索资料回答并标注引用。")
        .allowTool(ToolName("knowledge_lookup"))
        .buildFor(appConfig.toolPolicy)
      outcome <- (for
        app     <- ZIO.service[AgentApplication]
        command <- app.submit(
          agent,
          RunRequest(
            ThreadId("rag-demo"),
            AgentMessage.user("什么是阴阳？"),
            RunContext(Some("demo-user"), Some("demo-tenant"), Set("knowledge:read"))
          ),
          "rag-example-request"
        )
        _     <- app.claimOnce
        state <- app.inspect(command.runId)
      yield state).provide(
        ZLayer.succeed[ChatModel](model),
        RegisteredToolRegistry.fromTools(List(registered)),
        AgentApplication.inMemoryDefaults(WorkerId("rag-example-worker"), appConfig)
      )
      _ <- Console.printLine(
        s"RAG ingestion=${indexed.productPrefix}, Run 状态=${outcome.status}, " +
          s"answer=${outcome.messages.lastOption.map(_.text).getOrElse("")}"
      )
    yield ()).provide(localRagLayer)
