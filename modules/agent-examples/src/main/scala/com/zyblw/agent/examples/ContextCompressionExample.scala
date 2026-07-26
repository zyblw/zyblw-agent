package com.zyblw.agent.examples

import com.zyblw.agent.app.*
import com.zyblw.agent.context.*
import com.zyblw.agent.context.llm.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.model.*
import com.zyblw.agent.runtime.AgentRuntime
import com.zyblw.agent.scheduler.WorkerHostConfig
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 可直接运行的模型辅助 Context 压缩示例。
  *
  * 示例使用 `ScriptedChatModel`，因此不会访问公网或消耗真实 Token，但执行路径与生产一致：
  *
  *   1. 主模型提出只读工具调用；
  *   2. 工具结果让第二回合历史超过 recentMessages 分区；
  *   3. 同一个 ChatModel 路由被 `LlmContextCompressor` 用唯一 strict tool 抽取逐字证据；
  *   4. Runtime 先持久化 checkpoint 与辅助 usage，再调用主模型完成回答；
  *   5. 最终从权威 AgentState 读取总模型调用数和 compressorVersion。
  *
  * 真实业务只需把 `ScriptedChatModel.layer` 替换为 DeepSeek、GLM、OpenAI、Anthropic 或 Gemini Provider Layer。
  */
object ContextCompressionExample extends ZIOAppDefault:
  /** 只读工具输入；字段边界由 JSON Schema 在执行前验证。 */
  final case class LookupInput(query: String) derives JsonCodec

  /** 返回较长资料，使第二次模型调用能够稳定触发历史压缩。 */
  final case class LookupOutput(content: String) derives JsonCodec

  private val toolName = ToolName("lookup_context_example")

  /** 示例工具没有外部副作用，因此可以安全自动执行，不需要人工审批。 */
  private val lookupTool = Tool.json[Any, LookupInput, Nothing, LookupOutput](
    toolName,
    "返回一段用于演示 Context 压缩的只读资料。",
    TestSchemas.stringObject("query", "查询词"),
    None,
    ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
  ) { (_, _) =>
    // 资料足以让“旧用户消息 + 最新工具回合”超过分区，但最新工具原子组本身仍能放入压缩后的 recent suffix。
    ZIO.succeed(LookupOutput("资料" * 90))
  }

  /** 首条用户消息既是主模型任务，也是压缩模型必须逐字引用的来源。 */
  private val userText = "请基于工具资料回答，并保留这个用户约束：" + "只用中文。" * 24

  /** 三次确定性响应依次对应：主模型工具请求、压缩模型证据提交、主模型最终回答。
    *
    * 压缩工具参数没有自由 summaryText；`evidenceQuote` 必须逐字存在于 sourceMessageIndex=0 的消息渲染中。
    */
  private val scriptedResponses = Chunk(
    ChatResponse(
      AgentMessage.assistantToolCalls(
        Chunk(ToolCall("lookup-call", toolName.value, Json.Obj("query" -> Json.Str("示例"))))
      ),
      FinishReason.ToolCalls,
      TokenUsage(20L, 5L)
    ),
    ChatResponse(
      AgentMessage.assistantToolCalls(
        Chunk(
          ToolCall(
            "summary-call",
            "submit_context_summary",
            Json.Obj(
              "items" -> Json.Arr(
                Json.Obj(
                  "kind"               -> Json.Str("constraint"),
                  "sourceMessageIndex" -> Json.Num(0),
                  "evidenceQuote"      -> Json.Str("只用中文。"),
                  "priority"           -> Json.Num(5),
                  "references"         -> Json.Arr()
                )
              )
            )
          )
        )
      ),
      FinishReason.ToolCalls,
      TokenUsage(18L, 4L)
    ),
    ChatResponse(
      AgentMessage.assistant("这是使用中文生成的最终示例回答。"),
      FinishReason.Stop,
      TokenUsage(24L, 8L)
    )
  )

  /** recentMessages 刻意设置较小；生产值应由实际模型窗口、Prompt Cache 和业务 eval 决定。 */
  private val contextPolicy = ContextPolicy(
    budget = ContextBudget(
      total = 1_000L,
      system = 100L,
      tools = 100L,
      // 旧消息 + 最新工具组约 142 tokens；压缩后 recent suffix 预算约 93，可完整保留约 91-token 的工具原子组。
      recentMessages = 124L,
      memory = 100L,
      retrieval = 100L,
      outputReserve = 100L,
      safetyMargin = 100L
    ),
    maxToolResultCharacters = 1_000,
    historyCompression = CompressionMode.ModelAssisted,
    toolOutputCompression = CompressionMode.Deterministic
  )

  /** 压缩模型仍受独立输出、超时、修复次数和协议版本约束。 */
  private val compressorConfig = LlmContextCompressorConfig(
    modelSettings = ModelSettings(maxOutputTokens = Some(200)),
    requestTimeout = 5.seconds,
    maxSchemaRepairs = 0,
    compressorVersion = "context-example-v1"
  )

  private val applicationConfig = AgentApplicationConfig(
    toolPolicy = ToolPolicyConfig(allowedTools = Set(toolName)),
    worker = WorkerHostConfig(
      leaseDuration = 5.seconds,
      heartbeatEvery = 1.second,
      pollEvery = 10.millis,
      retryDelay = Duration.Zero,
      maxAttempts = 3
    )
  )

  /** 执行完整异步命令路径并打印低敏结果。
    *
    * 不打印 checkpoint.summary，因为真实摘要可能含用户数据；示例只展示版本、覆盖数量和累计 usage。
    */
  def run: ZIO[Any, Any, Any] =
    for
      estimated <- (for
        counter <- ZIO.service[TokenCounter]
        values  <- ZIO.foreach(
          Chunk(
            AgentMessage.user(userText),
            scriptedResponses.head.message,
            AgentMessage.tool(
              "lookup-call",
              toolName.value,
              ToolResult(Json.Obj("content" -> Json.Str("资料" * 90)))
            )
          )
        )(counter.countMessage)
      yield values).provide(TokenCounter.approximate)
      _          <- Console.printLine(s"预估消息 tokens=${estimated.mkString(",")}, total=${estimated.sum}")
      registered <- RegisteredTool.make(lookupTool)
      definition <- AgentDefinitionBuilder(AgentId("context-compression-example"), "Context 压缩示例")
        .withInstructions("使用已授权工具完成请求。")
        .allowTool(toolName)
        .withContextPolicy(contextPolicy)
        .buildFor(applicationConfig.toolPolicy)
      result <- (for
        app     <- ZIO.service[AgentApplication]
        runtime <- ZIO.service[AgentRuntime]
        command <- app.submit(
          definition,
          RunRequest(ThreadId("context-example-thread"), AgentMessage.user(userText)),
          "context-example-request"
        )
        _      <- app.claimOnce
        state  <- app.inspect(command.runId)
        events <- runtime.persistedEvents(command.runId)
      yield (state, events)).provide(
        ScriptedChatModel.layer(scriptedResponses),
        RegisteredToolRegistry.fromTools(List(registered)),
        LlmContextCompressor.configured(compressorConfig),
        AgentApplication.inMemoryDefaultsWithContextCompressor(
          WorkerId("context-example-worker"),
          applicationConfig
        )
      )
      (state, events) = result
      _ <- Console.printLine(
        s"运行结果: status=${state.status}, messages=${state.messages.size}, " +
          s"modelCalls=${state.usage.modelCalls}, hasCheckpoint=${state.contextSummary.nonEmpty}"
      )
      _ <- ZIO.foreachDiscard(events.collect {
        case PersistedAgentEvent(_, _, _, failed: AgentEvent.RunFailed, _) => failed
      })(failed => Console.printLine(s"失败分类=${failed.category}, safeMessage=${failed.safeMessage}"))
      checkpoint <- ZIO
        .fromOption(state.contextSummary)
        .orElseFail(AgentError.Unexpected("示例没有生成 ContextSummaryCheckpoint"))
      _ <- Console.printLine(
        s"status=${state.status}, modelCalls=${state.usage.modelCalls}, " +
          s"coveredMessages=${checkpoint.coveredMessages}, compressorVersion=${checkpoint.compressorVersion}"
      )
    yield ()
