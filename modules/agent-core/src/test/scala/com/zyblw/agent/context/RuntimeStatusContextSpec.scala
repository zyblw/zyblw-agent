package com.zyblw.agent.context

import com.zyblw.agent.composition.RuntimeProfile
import com.zyblw.agent.core.*
import com.zyblw.agent.composition.RuntimeComposition
import java.time.Instant
import java.util.UUID
import zio.*
import zio.test.*

object RuntimeStatusContextSpec extends ZIOSpecDefault:
  def spec = suite("RuntimeStatusContext")(
    test("只投影低敏 typed 事实，不泄露 run/session/tenant 标识") {
      val definition = AgentDefinition(AgentId("status-agent"), "Status", "policy")
      val now        = Instant.parse("2026-09-16T00:00:00Z")
      val state = AgentState(
        RunId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
        SessionId(UUID.fromString("223e4567-e89b-12d3-a456-426614174000")),
        definition.id,
        RunStatus.Running,
        Chunk.empty,
        Chunk.empty,
        UsageSummary(modelCalls = 2, toolCalls = 3, inputTokens = 40, outputTokens = 10),
        BudgetState(RunLimits(maxModelCalls = 8, maxToolCalls = 9, maxInputTokens = 100), UsageSummary(), 4),
        None,
        now,
        now,
        Version.initial,
        definition,
        RuntimeComposition.fingerprint(RuntimeProfile.default, definition, definition.modelSettings),
        ThreadId("secret-thread"),
        runContext = RunContext(tenantId = Some("secret-tenant"))
      )
      val message = RuntimeStatusContext.message(state)
      assertTrue(
        message.role == MessageRole.User,
        message.text.contains("models=6"),
        message.text.contains("input=60"),
        !message.text.contains(state.runId.asString),
        !message.text.contains("secret-thread"),
        !message.text.contains("secret-tenant")
      )
    }
  )
