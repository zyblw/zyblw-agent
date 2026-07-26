package com.zyblw.agent.http

import com.zyblw.agent.core.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 验证内部领域对象经过公共投影后不泄漏工具参数、结果和消息历史。 */
object AgentHttpProjectionSpec extends ZIOSpecDefault:
  private val runId = RunId(UUID.randomUUID())
  private val now   = Instant.parse("2026-01-01T00:00:00Z")

  private def persisted(event: AgentEvent, sequence: Long = 0L): PersistedAgentEvent =
    PersistedAgentEvent(EventId(UUID.randomUUID()), runId, sequence, event, now.toEpochMilli)

  def spec = suite("AgentHttpProjection")(
    test("工具请求只公开 callId/name，不公开 arguments") {
      val internal = persisted(
        AgentEvent.ToolCallRequested(
          runId,
          ToolCall("call-1", "draft_article", Json.Obj("secret" -> Json.Str("must-not-leak"))),
          now.toEpochMilli
        )
      )
      val encoded = AgentHttpProjection.event(internal).toJson
      assertTrue(
        encoded.contains("draft_article"),
        encoded.contains("call-1"),
        !encoded.contains("must-not-leak"),
        !encoded.contains("arguments")
      )
    },
    test("工具完成事件不公开 ToolResult 正文") {
      val internal = persisted(
        AgentEvent.ToolExecutionCompleted(
          runId,
          "call-2",
          ToolResult(Json.Obj("private" -> Json.Str("result-secret"))),
          now.toEpochMilli
        )
      )
      val encoded = AgentHttpProjection.event(internal).toJson
      assertTrue(encoded.contains("call-2"), !encoded.contains("result-secret"), !encoded.contains("private"))
    },
    test("Context 压缩事件只公开计数、版本和 usage，不公开摘要或源哈希") {
      val encoded = AgentHttpProjection
        .event(
          persisted(
            AgentEvent.ContextCompacted(
              runId,
              coveredMessages = 12,
              modelCalls = 1,
              usage = TokenUsage(30L, 8L),
              compressorVersion = "llm-extractive-v1",
              atEpochMilli = now.toEpochMilli
            )
          )
        )
        .toJson
      assertTrue(
        encoded.contains("ContextCompacted"),
        encoded.contains("llm-extractive-v1"),
        encoded.contains("已压缩 12 条历史消息"),
        encoded.contains("\"totalTokens\":38"),
        !encoded.contains("sourceDigest"),
        !encoded.contains("summary")
      )
    },
    test("Run 视图提供最终输出和累计用量，但不序列化历史消息") {
      val state = AgentState(
        runId = runId,
        sessionId = SessionId(UUID.randomUUID()),
        agentId = AgentId("projection-agent"),
        status = RunStatus.Completed,
        messages = Chunk(AgentMessage.user("private-input"), AgentMessage.assistant("public-output")),
        steps = Chunk.empty,
        usage = UsageSummary(
          modelCalls = 1,
          inputTokens = 10,
          outputTokens = 3,
          cachedInputTokens = 4,
          reasoningOutputTokens = 2
        ),
        budget = BudgetState(
          RunLimits(),
          UsageSummary(
            modelCalls = 1,
            inputTokens = 10,
            outputTokens = 3,
            cachedInputTokens = 4,
            reasoningOutputTokens = 2
          ),
          1
        ),
        pendingApproval = None,
        createdAt = now,
        updatedAt = now,
        version = Version(2),
        threadId = Some(ThreadId("thread-1"))
      )
      val encoded = AgentHttpProjection.run(state).toJson
      assertTrue(
        encoded.contains("public-output"),
        encoded.contains("\"totalTokens\":13"),
        encoded.contains("\"cachedInputTokens\":4"),
        encoded.contains("\"reasoningOutputTokens\":2"),
        !encoded.contains("private-input"),
        !encoded.contains("messages")
      )
    },
    test("Inspector 摘要不复用业务 RunView，不公开 threadId、最终输出或审批正文") {
      val state = AgentState(
        runId = runId,
        sessionId = SessionId(UUID.randomUUID()),
        agentId = AgentId("inspection-agent"),
        status = RunStatus.Completed,
        messages = Chunk(AgentMessage.user("private-input"), AgentMessage.assistant("private-output")),
        steps = Chunk.empty,
        usage = UsageSummary(),
        budget = BudgetState(RunLimits(), UsageSummary(), 0),
        pendingApproval = None,
        createdAt = now,
        updatedAt = now,
        version = Version(2),
        threadId = Some(ThreadId("private-thread"))
      )
      val encoded = AgentHttpProjection.inspection(state, Chunk.empty, -1L).toJson
      assertTrue(
        encoded.contains("\"agentId\":\"inspection-agent\""),
        !encoded.contains("private-input"),
        !encoded.contains("private-output"),
        !encoded.contains("private-thread"),
        !encoded.contains("\"output\":"),
        !encoded.contains("\"pendingApproval\":")
      )
    }
  )
