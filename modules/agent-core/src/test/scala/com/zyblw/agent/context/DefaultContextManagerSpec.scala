package com.zyblw.agent.context

import com.zyblw.agent.core.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 默认 ContextManager 的预算、安全裁剪与诊断契约。
  *
  * 测试重点不是某个 tokenizer 的精确数字，而是跨 tokenizer 都必须成立的性质：分区不能互相侵占、安全指令不静默丢弃、 tool_call/result 不被拆开、JSON
  * 工具结果会计费、Debug View 不返回正文，且高淘汰率会产生稳定 Context Rot 信号。
  */
object DefaultContextManagerSpec extends ZIOSpecDefault:

  /** 测试 tokenizer 采用与默认估算相同的“三个 code point 约一个 token”，保持完全确定性。 */
  private val counter = new TokenCounter:
    def count(text: String): UIO[Long] =
      ZIO.succeed(((text.codePointCount(0, text.length) + 2) / 3).toLong.max(1L))

  /** 返回极短摘要，便于测试把焦点放在选择策略而非摘要算法。 */
  private val compressor = new ContextCompressor:
    override val supportsModelAssisted: Boolean = true

    def compress(
        messages: Chunk[AgentMessage],
        targetTokens: Long,
        maxModelCalls: Int
    ): IO[ContextError, ContextCompressionResult] =
      ZIO.succeed(
        ContextCompressionResult(
          AgentMessage.system(s"summary-of-${messages.size}"),
          compressorVersion = "test-summary-v1"
        )
      )

  private val manager = DefaultContextManager(counter, compressor)

  /** 创建只含测试消息的最小权威状态。 */
  private def state(messages: Chunk[AgentMessage]): AgentState =
    val now = Instant.parse("2026-07-15T00:00:00Z")
    AgentState(
      runId = RunId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
      sessionId = SessionId(UUID.fromString("223e4567-e89b-12d3-a456-426614174000")),
      agentId = AgentId("context-test"),
      status = RunStatus.Running,
      messages = messages,
      steps = Chunk.empty,
      usage = UsageSummary(),
      budget = BudgetState(RunLimits(), UsageSummary(), 0),
      pendingApproval = None,
      createdAt = now,
      updatedAt = now,
      version = Version.initial
    )

  /** 固定 Agent 指令，外部来源不能覆盖。 */
  private val definition =
    AgentDefinition(AgentId("context-test"), "Context Test", "stable-system-instruction")

  /** 创建总量合法、各分区可单独调整的策略。 */
  private def policy(
      system: Long = 100,
      memory: Long = 100,
      retrieval: Long = 100,
      recent: Long = 300,
      toolCharacters: Int = 200,
      historyCompression: CompressionMode = CompressionMode.Deterministic,
      toolCompression: CompressionMode = CompressionMode.Deterministic
  ): ContextPolicy = ContextPolicy(
    budget = ContextBudget(
      total = 1000,
      system = system,
      tools = 100,
      recentMessages = recent,
      memory = memory,
      retrieval = retrieval,
      outputReserve = 100,
      safetyMargin = 100
    ),
    maxToolResultCharacters = toolCharacters,
    historyCompression = historyCompression,
    toolOutputCompression = toolCompression
  )

  def spec = suite("DefaultContextManager")(
    test("Memory/RAG 各守分区预算，重复正文去重且 Debug View 不泄漏来源正文") {
      val sources = ContextSources(
        memories = Chunk(
          ContextMemory("huge", "超长敏感记忆" * 500, importance = 10.0),
          ContextMemory("preferred-language", "只使用中文", importance = 5.0),
          ContextMemory("duplicate", "只使用中文", importance = 1.0)
        ),
        retrieval = Chunk(
          ContextDocument("doc-duplicate", "只使用中文", "private-source"),
          ContextDocument("doc-huge", "超长敏感知识" * 500, "private-source"),
          ContextDocument("doc-small", "黄帝内经学习资料", "book-1")
        )
      )
      manager.build(state(Chunk(AgentMessage.user("question"))), definition, sources, policy()).map {
        prepared =>
          val renderedDebug = prepared.debug.toJson
          assertTrue(
            prepared.usage.droppedMemories == 2,
            prepared.usage.droppedRetrieval == 2,
            prepared.messages.exists(_.text.contains("只使用中文")),
            prepared.messages.exists(_.text.contains("黄帝内经学习资料")),
            prepared.debug.rotSignals.exists(_.code == "context-duplicate-source"),
            prepared.debug.rotSignals.exists(_.code == "context-memory-dropped"),
            prepared.debug.rotSignals.exists(_.code == "context-retrieval-dropped"),
            !renderedDebug.contains("超长敏感记忆"),
            !renderedDebug.contains("private-source")
          )
      }
    },
    test("JSON ToolResult 参与计费并在字符上限压缩，仍保留 Tool role/callId") {
      val tool = AgentMessage.tool(
        "call-1",
        "lookup",
        ToolResult(Json.Obj("payload" -> Json.Str("敏感工具结果" * 200)))
      )
      manager
        .build(
          state(
            Chunk(AgentMessage.assistantToolCalls(Chunk(ToolCall("call-1", "lookup", Json.Obj()))), tool)
          ),
          definition,
          ContextSources(),
          policy(recent = 300, toolCharacters = 120)
        )
        .map { prepared =>
          val compressed = prepared.messages.find(_.role == MessageRole.Tool)
          assertTrue(
            prepared.usage.truncatedToolResults == 1,
            prepared.usage.recentTokens > 0,
            compressed.exists(_.toolCallId.contains("call-1")),
            compressed.exists(_.metadata.get("contextCompressed").contains("true")),
            compressed.exists(_.text.length <= 120),
            prepared.debug.rotSignals.exists(_.code == "context-tool-output-truncated")
          )
        }
    },
    test("recent 裁剪不会留下孤立 Tool result，assistant tool_calls 与结果作为原子组共同淘汰") {
      val toolCall = AgentMessage.assistantToolCalls(
        Chunk(ToolCall("call-atomic", "large-tool", Json.Obj("query" -> Json.Str("x" * 300))))
      )
      val toolResult = AgentMessage.tool(
        "call-atomic",
        "large-tool",
        ToolResult(Json.Obj("payload" -> Json.Str("y" * 400)))
      )
      val messages =
        Chunk(AgentMessage.user("old"), toolCall, toolResult, AgentMessage.user("latest-question"))
      manager
        .build(
          state(messages),
          definition,
          ContextSources(),
          policy(recent = 80, toolCharacters = 1000, toolCompression = CompressionMode.Disabled)
        )
        .map { prepared =>
          assertTrue(
            prepared.messages.exists(_.text.contains("latest-question")),
            !prepared.messages.exists(_.role == MessageRole.Tool),
            !prepared.messages.exists(_.toolCalls.nonEmpty),
            prepared.usage.usedSummary,
            prepared.usage.droppedMessages >= 3
          )
        }
    },
    test("安全指令超过 system 分区时 fail-closed，不能为保住普通历史而静默删除") {
      val sources = ContextSources(safetyInstructions = Chunk("必须遵守的安全策略" * 200))
      manager
        .build(
          state(Chunk(AgentMessage.user("question"))),
          definition,
          sources,
          policy(system = 10)
        )
        .exit
        .map(exit => assertTrue(exit.isFailure))
    },
    test("大量历史触发摘要和 heavy-drop rot signal，分区用量之和与 ContextUsage 一致") {
      val messages =
        Chunk.fromIterable((1 to 20).map(index => AgentMessage.user(s"history-$index-" + "内容" * 25)))
      manager
        .build(
          state(messages),
          definition,
          ContextSources(existingSummary = Some("older-summary")),
          policy(recent = 120)
        )
        .map { prepared =>
          val sectionsUsed = prepared.debug.sections.map(_.usedTokens).sum
          assertTrue(
            prepared.usage.usedSummary,
            prepared.usage.droppedMessages >= 10,
            prepared.debug.rotSignals.exists(_.code == "context-history-heavy-drop"),
            sectionsUsed == prepared.usage.estimatedTokens,
            prepared.debug.estimatedTokens == prepared.usage.estimatedTokens,
            prepared.messages.exists(_.text.contains("不可信历史摘要")),
            prepared.summaryUpdate.exists(_.coveredMessages == prepared.usage.droppedMessages)
          )
        }
    },
    test("Deterministic 策略始终使用框架本地算法，不会因为装配了模型压缩器而产生隐形调用") {
      val messages =
        Chunk.fromIterable((1 to 18).map(index => AgentMessage.user(s"history-$index-" + "内容" * 30)))
      for
        calls <- Ref.make(0)
        modelAssisted = new ContextCompressor:
          override val supportsModelAssisted: Boolean = true

          def compress(
              values: Chunk[AgentMessage],
              targetTokens: Long,
              maxModelCalls: Int
          ): IO[ContextError, ContextCompressionResult] =
            calls
              .update(_ + 1)
              .as(
                ContextCompressionResult(
                  AgentMessage.system(s"paid-summary-${values.size}"),
                  TokenUsage(100L, 20L),
                  modelCalls = 1,
                  compressorVersion = "paid-test-v1"
                )
              )
        prepared <- DefaultContextManager(counter, modelAssisted).build(
          state(messages),
          definition,
          ContextSources(),
          policy(recent = 120, historyCompression = CompressionMode.Deterministic)
        )
        totalCalls <- calls.get
      yield assertTrue(
        totalCalls == 0,
        prepared.usage.compressionModelCalls == 0,
        prepared.compressionUsage == TokenUsage(),
        prepared.summaryUpdate.exists(_.compressorVersion == "deterministic-v1"),
        prepared.messages.exists(_.text.contains("不可信历史摘要")),
        !prepared.messages.exists(_.text.contains("paid-summary"))
      )
    },
    test("ModelAssisted 策略缺少对应能力时在调用前 fail-closed，不静默退化为确定性压缩") {
      val messages =
        Chunk.fromIterable((1 to 18).map(index => AgentMessage.user(s"history-$index-" + "内容" * 30)))
      DefaultContextManager(counter, ContextCompressor.deterministicValue)
        .build(
          state(messages),
          definition,
          ContextSources(),
          policy(recent = 120, historyCompression = CompressionMode.ModelAssisted)
        )
        .exit
        .map { exit =>
          val error = exit.causeOption.flatMap(_.failureOption)
          assertTrue(
            exit.isFailure,
            error.exists(_.message == "context-model-assisted-compressor-not-configured")
          )
        }
    },
    test("摘要 checkpoint 持久化后相同历史直接复用，不重复调用压缩器或计费") {
      val messages =
        Chunk.fromIterable((1 to 16).map(index => AgentMessage.user(s"history-$index-" + "内容" * 30)))
      for
        calls <- Ref.make(0)
        counting = new ContextCompressor:
          override val supportsModelAssisted: Boolean = true

          def compress(
              values: Chunk[AgentMessage],
              targetTokens: Long,
              maxModelCalls: Int
          ): IO[ContextError, ContextCompressionResult] =
            calls.updateAndGet(_ + 1).map { _ =>
              ContextCompressionResult(
                AgentMessage.system(s"durable-summary-${values.size}"),
                TokenUsage(11L, 5L),
                modelCalls = 1,
                compressorVersion = "counting-v1"
              )
            }
        localManager = DefaultContextManager(counter, counting)
        first <- localManager.build(
          state(messages),
          definition,
          ContextSources(),
          policy(recent = 120, historyCompression = CompressionMode.ModelAssisted)
        )
        checkpoint <- ZIO
          .fromOption(first.summaryUpdate)
          .orElseFail(new AssertionError("首次压缩必须产生 checkpoint"))
        second <- localManager.build(
          state(messages).copy(contextSummary = Some(checkpoint)),
          definition,
          ContextSources(),
          policy(recent = 120, historyCompression = CompressionMode.ModelAssisted)
        )
        totalCalls <- calls.get
      yield assertTrue(
        totalCalls == 1,
        first.usage.compressionModelCalls == 1,
        first.compressionUsage == TokenUsage(11L, 5L),
        second.usage.compressionModelCalls == 0,
        second.compressionUsage == TokenUsage(),
        second.summaryUpdate.isEmpty,
        second.messages.exists(_.text == checkpoint.summary)
      )
    },
    test("已摘要消息前缀被改写时 sourceDigest 校验 fail-closed") {
      val messages =
        Chunk.fromIterable((1 to 16).map(index => AgentMessage.user(s"history-$index-" + "内容" * 30)))
      for
        first      <- manager.build(state(messages), definition, ContextSources(), policy(recent = 120))
        checkpoint <- ZIO
          .fromOption(first.summaryUpdate)
          .orElseFail(new AssertionError("首次压缩必须产生 checkpoint"))
        mutated = messages.updated(0, AgentMessage.user("被异常改写的历史"))
        exit <- manager
          .build(
            state(mutated).copy(contextSummary = Some(checkpoint)),
            definition,
            ContextSources(),
            policy(recent = 120)
          )
          .exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.flatMap(_.failureOption).exists(_.message.contains("源消息哈希不一致"))
      )
    },
    test("TokenCounter 默认实现把 JSON、tool arguments 与 image URL 纳入，而非只看 message.text") {
      val message = AgentMessage(
        MessageRole.Assistant,
        Chunk(
          ContentPart.JsonValue(Json.Obj("large" -> Json.Str("z" * 300))),
          ContentPart.ImageUrl("https://example.invalid/image.png")
        ),
        toolCalls = Chunk(ToolCall("call-count", "lookup", Json.Obj("argument" -> Json.Str("a" * 100))))
      )
      for
        full <- counter.countMessage(message)
        text <- counter.count(message.text)
      yield assertTrue(message.text.isEmpty, full > text)
    }
  )
