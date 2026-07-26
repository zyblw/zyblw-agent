package com.zyblw.agent.context.llm

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 验证模型只能选择真实逐字证据，并且修复次数、Provider usage、确定性降级和调用预算都进入稳定契约。
  */
object LlmContextCompressorSpec extends ZIOSpecDefault:

  /** 创建支持 strict/specific tool choice 的可观察测试模型。 */
  private def model(
      completeEffect: ChatRequest => IO[AgentError, ChatResponse],
      captured: Ref[Chunk[ChatRequest]]
  ): ChatModel = new ChatModel:
    val provider            = "context-compressor-test"
    override val descriptor = ProviderDescriptor(
      provider,
      "Context compressor test",
      "test",
      ModelCapabilities(
        toolCalls = true,
        strictToolSchema = true,
        specificToolChoice = true,
        usageReporting = true
      )
    )
    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      captured.update(_ :+ request) *> completeEffect(request)

  /** 生成一次唯一工具调用响应。 */
  private def response(arguments: Json, usage: TokenUsage = TokenUsage(20L, 10L)): ChatResponse =
    ChatResponse(
      AgentMessage.assistantToolCalls(
        Chunk(ToolCall("summary-call-1", "submit_context_summary", arguments))
      ),
      FinishReason.ToolCalls,
      usage
    )

  /** 构造一条摘要项。 */
  private def arguments(
      quote: String,
      sourceIndex: Int = 0,
      kind: String = "constraint",
      priority: Int = 5,
      references: Chunk[String] = Chunk.empty
  ): Json =
    Json.Obj(
      Chunk(
        "items" -> Json.Arr(
          Chunk(
            Json.Obj(
              Chunk(
                "kind"               -> Json.Str(kind),
                "sourceMessageIndex" -> Json.Num(sourceIndex),
                "evidenceQuote"      -> Json.Str(quote),
                "priority"           -> Json.Num(priority),
                "references"         -> Json.Arr(references.map(Json.Str(_)))
              )
            )
          )
        )
      )
    )

  private val config = LlmContextCompressorConfig(
    modelSettings = ModelSettings(
      provider = Some("context-compressor-test"),
      model = Some("cheap-summary-v1"),
      temperature = Some(0.8),
      maxOutputTokens = Some(400)
    ),
    requestTimeout = 2.seconds,
    maxSchemaRepairs = 1
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LlmContextCompressor")(
    test("唯一 strict tool、逐字证据、引用和 usage 构成可持久化摘要结果") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        compressor = LlmContextCompressor(
          model(
            _ =>
              ZIO.succeed(
                response(arguments("必须使用中文回答，并保留 cite-1。", references = Chunk("cite-1")))
              ),
            captured
          ),
          config
        )
        result <- compressor.compress(
          Chunk(AgentMessage.user("后续必须使用中文回答，并保留 cite-1。")),
          targetTokens = 200L,
          maxModelCalls = 2
        )
        requests <- captured.get
        request = requests.head
      yield assertTrue(
        result.message.text.contains("[约束]"),
        result.message.text.contains("必须使用中文回答，并保留 cite-1。"),
        result.message.text.contains("refs=cite-1"),
        result.usage == TokenUsage(20L, 10L),
        result.modelCalls == 1,
        result.compressorVersion == "llm-extractive-v1",
        request.tools.length == 1,
        request.tools.head.strict,
        request.settings.toolChoice == ToolChoice.Specific("submit_context_summary"),
        request.settings.temperature.contains(0.0),
        request.settings.maxOutputTokens.contains(200)
      )
    },
    test("伪造证据触发安全 repair，成功后累计两次响应 usage") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        calls    <- Ref.make(0)
        stub = model(
          _ =>
            calls.updateAndGet(_ + 1).map { count =>
              if count == 1 then response(arguments("原文中不存在"), TokenUsage(10L, 4L))
              else response(arguments("必须使用中文回答"), TokenUsage(12L, 5L))
            },
          captured
        )
        result <- LlmContextCompressor(stub, config).compress(
          Chunk(AgentMessage.user("后续必须使用中文回答。")),
          targetTokens = 200L,
          maxModelCalls = 2
        )
        requests <- captured.get
      yield assertTrue(
        requests.length == 2,
        requests(1).messages.last.text.contains("上一次工具参数未通过"),
        !requests(1).messages.last.text.contains("原文中不存在"),
        result.modelCalls == 2,
        result.usage == TokenUsage(22L, 9L),
        result.message.text.contains("必须使用中文回答")
      )
    },
    test("validation 持续失败时只降级到确定性摘要，并保留已发生的模型 usage") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        stub = model(
          _ => ZIO.succeed(response(arguments("伪造的敏感结论"), TokenUsage(7L, 3L))),
          captured
        )
        result <- LlmContextCompressor(stub, config.copy(maxSchemaRepairs = 0)).compress(
          Chunk(AgentMessage.user("真实历史内容")),
          targetTokens = 100L,
          maxModelCalls = 1
        )
      yield assertTrue(
        result.message.text.contains("真实历史内容"),
        !result.message.text.contains("伪造的敏感结论"),
        result.usage == TokenUsage(7L, 3L),
        result.modelCalls == 1,
        result.compressorVersion == "llm-extractive-v1.fallback"
      )
    },
    test("辅助模型调用预算为零时在 Provider 前失败") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        compressor = LlmContextCompressor(
          model(_ => ZIO.succeed(response(arguments("真实内容"))), captured),
          config
        )
        exit <- compressor
          .compress(Chunk(AgentMessage.user("真实内容")), targetTokens = 100L, maxModelCalls = 0)
          .exit
        requests <- captured.get
      yield assertTrue(
        exit.isFailure,
        requests.isEmpty,
        exit.causeOption.flatMap(_.failureOption).exists(_.message == "context-compressor-model-budget")
      )
    },
    test("默认不允许为单条 Tool 结果发起独立付费摘要") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        compressor = LlmContextCompressor(
          model(_ => ZIO.succeed(response(arguments("工具结果"))), captured),
          config
        )
        exit <- compressor
          .compress(
            Chunk(AgentMessage.tool("call-1", "lookup", ToolResult(Json.Obj("answer" -> Json.Str("工具结果"))))),
            targetTokens = 100L,
            maxModelCalls = 1
          )
          .exit
        requests <- captured.get
      yield assertTrue(
        exit.isFailure,
        requests.isEmpty,
        exit.causeOption
          .flatMap(_.failureOption)
          .exists(_.message == "context-compressor-standalone-tool-disabled")
      )
    },
    test("模型超时是可重试 ContextError，Fiber 不会悬挂") {
      for
        captured <- Ref.make(Chunk.empty[ChatRequest])
        compressor = LlmContextCompressor(
          model(_ => ZIO.never, captured),
          config.copy(requestTimeout = 50.millis, maxSchemaRepairs = 0)
        )
        exit <- compressor
          .compress(Chunk(AgentMessage.user("真实内容")), targetTokens = 100L, maxModelCalls = 1)
          .exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption
          .flatMap(_.failureOption)
          .exists(error => error.message == "context-compressor-timeout" && error.retryable)
      )
    }
  ) @@ TestAspect.withLiveClock
