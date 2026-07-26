package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.observability.*
import java.util.UUID
import zio.*
import zio.test.*

/** 验证敏感正文在 Runtime 投影与 exporter 包装两层都会被阻断。 */
object TelemetrySafetySpec extends ZIOSpecDefault:
  private val runId = RunId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Telemetry sensitive boundary")(
    test("模型文本、最终答案和错误安全消息都不会进入 TelemetryEvent") {
      val delta     = TelemetryRunObserver.toTelemetry(AgentEvent.ModelTextDelta(runId, "患者隐私", 1L))
      val completed = TelemetryRunObserver.toTelemetry(
        AgentEvent.RunCompleted(runId, AgentMessage.assistant("患者隐私答案"), UsageSummary(inputTokens = 3), 2L)
      )
      val failed =
        TelemetryRunObserver.toTelemetry(AgentEvent.RunFailed(runId, "provider", "secret-message", 3L))
      assertTrue(
        delta.isEmpty,
        completed.exists(event => !event.toString.contains("患者隐私")),
        failed.exists(event => !event.toString.contains("secret-message"))
      )
    },
    test("MetadataOnly 删除正文并固定替换认证字段") {
      for
        sink     <- ZIO.service[InMemoryTelemetry]
        redactor <- ZIO.service[Redactor]
        safe = SanitizingTelemetry.make(sink, ContentRecordingPolicy.MetadataOnly, redactor)
        _ <- safe.emit(
          TelemetryEvent(
            "sensitive-test",
            Some(runId),
            None,
            Map(
              "prompt"             -> "患者隐私",
              "http.authorization" -> "Bearer real-secret-token",
              "agent.provider"     -> "anthropic"
            ),
            atEpochMilli = 1L
          )
        )
        events <- sink.events
        attrs = events.head.attributes
      yield assertTrue(
        !attrs.contains("prompt"),
        attrs.get("http.authorization").contains("<redacted>"),
        attrs.get("agent.provider").contains("anthropic"),
        !events.toString.contains("患者隐私"),
        !events.toString.contains("real-secret-token")
      )
    }.provide(InMemoryTelemetry.layer, Redactor.default),
    test("ContextPrepared 只投影计数和白名单 rot code，未知 code 被折叠为 other") {
      val projected = TelemetryRunObserver.toTelemetry(
        AgentEvent.ContextPrepared(
          runId,
          estimatedTokens = 1200L,
          droppedMessages = 3,
          truncatedToolResults = 1,
          droppedMemories = 2,
          droppedRetrieval = 4,
          rotSignalCodes = Chunk("context-history-heavy-drop", "患者隐私作为伪造code"),
          atEpochMilli = 10L
        )
      )
      assertTrue(
        projected.exists(_.name == "agent.context.prepared"),
        projected.exists(
          _.attributes.get("agent.context.rot.codes").contains("context-history-heavy-drop,other")
        ),
        projected.exists(_.measurements.get("agent.context.estimated_tokens").contains(1200.0)),
        projected.exists(_.measurements.get("agent.context.dropped_retrieval").contains(4.0)),
        !projected.toString.contains("患者隐私")
      )
    },
    test("ContextCompacted 只投影版本、计数与 usage，不暴露摘要和源哈希") {
      val projected = TelemetryRunObserver.toTelemetry(
        AgentEvent.ContextCompacted(
          runId,
          coveredMessages = 12,
          modelCalls = 2,
          usage = TokenUsage(30L, 8L),
          compressorVersion = "llm-extractive-v1",
          atEpochMilli = 11L
        )
      )
      assertTrue(
        projected.exists(_.name == "agent.context.compacted"),
        projected.exists(_.attributes.get("agent.context.compressor.version").contains("llm-extractive-v1")),
        projected.exists(_.measurements.get("agent.context.covered_messages").contains(12.0)),
        projected.exists(_.measurements.get("agent.context.compression.model_calls").contains(2.0)),
        !projected.toString.contains("summary"),
        !projected.toString.contains("sourceDigest")
      )
    },
    test("模型与工具开始结束被配对为 Langfuse 持续时间 observation 且不保存正文") {
      val call = ToolCall(
        "private-call-id",
        "safe_lookup",
        zio.json.ast.Json.Obj("query" -> zio.json.ast.Json.Str("患者问题"))
      )
      for
        sink     <- ZIO.service[InMemoryTelemetry]
        observer <- TelemetryRunObserver.make(sink)
        _        <- ZIO.foreachDiscard(
          Chunk[AgentEvent](
            AgentEvent.RunCreated(
              runId,
              SessionId(UUID.fromString("00000000-0000-4000-8000-000000000002")),
              1000L
            ),
            AgentEvent.ModelCallStarted(runId, "deepseek", "deepseek-chat", 1100L),
            AgentEvent.ModelTextDelta(runId, "模型敏感正文", 1200L),
            AgentEvent.ModelCallCompleted(runId, TokenUsage(10L, 5L), 2100L),
            AgentEvent.ToolCallRequested(runId, call, 2200L),
            AgentEvent.ToolExecutionStarted(runId, call.id, 2300L),
            AgentEvent.ToolExecutionCompleted(
              runId,
              call.id,
              ToolResult(zio.json.ast.Json.Str("工具敏感结果")),
              2800L
            ),
            AgentEvent.RunCompleted(runId, AgentMessage.assistant("最终敏感答案"), UsageSummary(), 3000L)
          )
        )(observer.emit)
        events <- sink.events
        model = events.find(_.name == "agent.model.call")
        tool  = events.find(_.name == "agent.tool.execute")
        run   = events.find(_.name == "agent.run")
      yield assertTrue(
        model.flatMap(_.startedAtEpochMilli).contains(1100L),
        model.exists(_.atEpochMilli == 2100L),
        model.exists(_.attributes.get("langfuse.observation.type").contains("generation")),
        model.exists(_.measurements.get("gen_ai.usage.input_tokens").contains(10.0)),
        tool.flatMap(_.startedAtEpochMilli).contains(2300L),
        tool.exists(_.attributes.get("langfuse.observation.type").contains("tool")),
        run.flatMap(_.startedAtEpochMilli).contains(1000L),
        run.exists(_.attributes.get("langfuse.observation.type").contains("agent")),
        !events.toString.contains("患者问题"),
        !events.toString.contains("模型敏感正文"),
        !events.toString.contains("工具敏感结果"),
        !events.toString.contains("最终敏感答案"),
        !events.toString.contains("private-call-id")
      )
    }.provide(InMemoryTelemetry.layer)
  )
