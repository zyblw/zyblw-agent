package com.zyblw.agent.examples

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.model.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json

object BasicAgentExample extends ZIOAppDefault:
  final case class SearchInput(query: String) derives JsonCodec
  final case class SearchOutput(results: Chunk[String]) derives JsonCodec

  /** 使用新类型化 Tool 契约声明输入、输出、风险和副作用，运行时只接触捕获环境后的 RegisteredTool。 */
  private val searchTool = Tool.json[Any, SearchInput, Nothing, SearchOutput](
    ToolName("search_articles"),
    "Search trusted article titles. This demo has no medical advice capability.",
    TestSchemas.stringObject("query", "Search phrase"),
    None,
    ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
  ) { (input, _) =>
    ZIO.succeed(SearchOutput(Chunk(s"Result for: ${input.query}")))
  }

  private val scripted = Chunk(
    ChatResponse(
      AgentMessage.assistantToolCalls(
        Chunk(ToolCall("call-1", "search_articles", Json.Obj("query" -> Json.Str("阴阳基础"))))
      ),
      FinishReason.ToolCalls,
      TokenUsage(20, 5)
    ),
    ChatResponse(AgentMessage.assistant("已找到一条可信学习资料。"), FinishReason.Stop, TokenUsage(30, 10))
  )

  /** Application 与 Runtime/CommandService 共享的工具硬策略；Builder 会在启动期校验定义没有越权工具。 */
  private val appConfig = AgentApplicationConfig(
    toolPolicy = ToolPolicyConfig(allowedTools = Set(ToolName("search_articles")))
  )

  /** 演示最小业务接入：定义 Agent、耐久提交 Start、由 WorkerHost claim，再读取权威 AgentState。 即使是内存示例也不旁路异步生产路径；替换为 PostgreSQL
    * 时业务调用方式保持一致。
    */
  def run: ZIO[Any, Any, Any] = for
    registered <- RegisteredTool.make(searchTool)
    agent      <- AgentDefinitionBuilder(AgentId("learning-demo"), "学习助手示例")
      .withInstructions("你是一个只使用可信资料的学习助手。")
      .allowTool(ToolName("search_articles"))
      .withMetadata("version", "example-v1")
      .buildFor(appConfig.toolPolicy)
    state <- (for
      app     <- ZIO.service[AgentApplication]
      command <- app.submit(
        agent,
        RunRequest(ThreadId("demo-thread"), AgentMessage.user("帮我找阴阳基础资料")),
        idempotencyKey = "basic-example-request"
      )
      _     <- Console.printLine(s"命令已提交: ${command.commandId.asString}, status=${command.status}")
      _     <- app.claimOnce
      state <- app.inspect(command.runId)
    yield state).provide(
      ScriptedChatModel.layer(scripted),
      RegisteredToolRegistry.fromTools(List(registered)),
      AgentApplication.inMemoryDefaults(WorkerId("basic-example-worker"), appConfig)
    )
    _ <- Console.printLine(
      s"最终状态: ${state.status}, answer=${state.messages.lastOption.map(_.text).getOrElse("")}"
    )
  yield ()
