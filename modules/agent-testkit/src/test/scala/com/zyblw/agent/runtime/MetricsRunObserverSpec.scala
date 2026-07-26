package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.observability.*
import java.util.UUID
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 验证 Runtime 事件到生产指标的配对、耗时、暂停恢复和敏感字段边界。 */
object MetricsRunObserverSpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.fromString("00000000-0000-4000-8000-000000000101"))
  private val sessionId = SessionId(UUID.fromString("00000000-0000-4000-8000-000000000102"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MetricsRunObserver")(
    test("模型、工具和 Run 被配成确定性耗时，暂停恢复正确维护 active gauge") {
      val call     = ToolCall("call-sensitive-id", "write_sensitive_profile", Json.Obj())
      val approval = ApprovalRequest(
        id = "approval-sensitive-id",
        runId = runId,
        toolCall = call,
        risk = ToolRisk.ApprovalWrite,
        reason = "这里可能包含业务正文，因此不得进入指标",
        requestedAtEpochMilli = 2400L
      )
      val events = Chunk[AgentEvent](
        AgentEvent.RunCreated(runId, sessionId, 1000L),
        AgentEvent.RunStarted(runId, 1100L),
        AgentEvent.ModelCallStarted(runId, "deepseek", "deepseek-chat", 1200L),
        AgentEvent.ContextPrepared(runId, 1024L, 3, 1, 2, 4, Chunk("context-history-heavy-drop"), 1150L),
        AgentEvent.ContextCompacted(runId, 7, 1, TokenUsage(9L, 4L), "llm-extractive-v1", 1175L),
        AgentEvent.ModelCallCompleted(runId, TokenUsage(128L, 32L), 2200L),
        AgentEvent.ToolCallRequested(runId, call, 2300L),
        AgentEvent.ToolApprovalRequired(runId, approval, 2400L),
        AgentEvent.ToolExecutionStarted(runId, call.id, 3000L),
        AgentEvent.ToolExecutionCompleted(runId, call.id, ToolResult(Json.Str("敏感工具结果")), 3500L),
        AgentEvent.RunSuspended(runId, "敏感暂停原因", 3600L),
        AgentEvent.RunResumed(runId, 5000L),
        AgentEvent.RunCompleted(
          runId,
          AgentMessage.assistant("敏感最终回答"),
          UsageSummary(
            modelCalls = 1,
            toolCalls = 1,
            inputTokens = 128L,
            outputTokens = 32L,
            estimatedCost = BigDecimal("0.12")
          ),
          6000L
        )
      )
      for
        sink     <- ZIO.service[InMemoryAgentMetrics]
        observer <- MetricsRunObserver.make(sink)
        _        <- ZIO.foreachDiscard(events)(observer.emit)
        recorded <- sink.recorded
        active     = recorded.collect { case AgentMetric.ActiveRuns(delta) => delta }
        model      = recorded.collectFirst { case value: AgentMetric.ModelCallFinished => value }
        tool       = recorded.collectFirst { case value: AgentMetric.ToolCallFinished => value }
        run        = recorded.collectFirst { case value: AgentMetric.RunFinished => value }
        context    = recorded.collectFirst { case value: AgentMetric.ContextPrepared => value }
        compaction = recorded.collectFirst { case value: AgentMetric.ContextCompacted => value }
      yield assertTrue(
        active == Chunk(1L, -1L, 1L, -1L),
        model.contains(
          AgentMetric.ModelCallFinished(
            "deepseek",
            "deepseek-chat",
            MetricOutcome.Succeeded,
            Some(1.0),
            128L,
            32L
          )
        ),
        tool.contains(
          AgentMetric.ToolCallFinished(
            "write_sensitive_profile",
            "approvalwrite",
            MetricOutcome.Succeeded,
            Some(0.5)
          )
        ),
        run.contains(AgentMetric.RunFinished(MetricOutcome.Succeeded, Some(5.0), 0.12)),
        context.contains(AgentMetric.ContextPrepared(1024L, 3L, 1L, 2L, 4L, 1L)),
        compaction.contains(AgentMetric.ContextCompacted(7L, 1L, 9L, 4L)),
        !recorded.toString.contains("敏感工具结果"),
        !recorded.toString.contains("敏感最终回答"),
        !recorded.toString.contains("敏感暂停原因"),
        !recorded.toString.contains("approval-sensitive-id"),
        !recorded.toString.contains("call-sensitive-id")
      )
    }.provide(InMemoryAgentMetrics.layer),
    test("观察者热重启后收到孤立完成事件仍计数，但不伪造耗时") {
      for
        sink     <- ZIO.service[InMemoryAgentMetrics]
        observer <- MetricsRunObserver.make(sink)
        _        <- observer.emit(AgentEvent.ModelCallCompleted(runId, TokenUsage(7L, 3L), 100L))
        _        <- observer.emit(AgentEvent.ToolExecutionFailed(runId, "unknown-call", "timeout", 101L))
        recorded <- sink.recorded
      yield assertTrue(
        recorded.contains(
          AgentMetric.ModelCallFinished("unknown", "unknown", MetricOutcome.Succeeded, None, 7L, 3L)
        ),
        recorded.contains(AgentMetric.ToolCallFinished("unknown", "unknown", MetricOutcome.TimedOut, None))
      )
    }.provide(InMemoryAgentMetrics.layer)
  )
