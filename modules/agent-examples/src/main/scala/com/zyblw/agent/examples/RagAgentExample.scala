package com.zyblw.agent.examples

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.model.*
import com.zyblw.agent.rag.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Markdown → 分块 → embedding → 带租户权限检索 → Agent 引用回答的最小可运行示例。 */
object RagAgentExample extends ZIOAppDefault:
  final case class LookupInput(query: String) derives JsonCodec
  final case class LookupOutput(excerpts: Chunk[String], citations: Chunk[String]) derives JsonCodec

  private val markdown = SourceDocument(
    "doc-yinyang",
    "# 阴阳\n阴阳用于描述相互关联事物的对立统一关系。本示例仅用于中医学习，不提供诊疗建议。",
    "memory://docs/yinyang.md"
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
      vectorStore <- ZIO.service[VectorStore]
      embeddings = HashEmbedding(64)
      tenant     = TenantId("demo-tenant")
      chunks  <- SlidingWindowChunker().split(markdown, tenant, Set("knowledge:read"))
      vectors <- embeddings.embed(chunks.map(_.text))
      _       <- vectorStore.upsert(chunks.zip(vectors).map(IndexedChunk.apply))
      reranker = new Reranker:
        def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int) = ZIO.succeed(hits.take(limit))
      retriever = DefaultRetriever(embeddings, vectorStore, reranker)
      lookup    = Tool.json[Any, LookupInput, AgentError.ToolExecutionFailed, LookupOutput](
        ToolName("knowledge_lookup"),
        "检索有来源的学习资料",
        TestSchemas.stringObject("query", "问题"),
        None,
        ToolMetadata(ToolRisk.UserScopedRead, SideEffect.None, requiredScopes = Set("knowledge:read"))
      ) { (input, context) =>
        retriever
          .retrieve(
            input.query,
            RetrievalScope(
              TenantId(context.runContext.tenantId.getOrElse("demo-tenant")),
              context.runContext.scopes
            ),
            3
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
        s"RAG Run 状态=${outcome.status}, answer=${outcome.messages.lastOption.map(_.text).getOrElse("")}"
      )
    yield ()).provide(InMemoryVectorStore.layer)
