package com.zyblw.agent.tools

import com.zyblw.agent.artifacts.ArtifactStore
import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolExecutorExternalizeSpec extends ZIOSpecDefault:
  def spec = suite("ToolExecutor.externalize")(
    test("超过阈值的 Inline 结果外置为 Run 域引用，preview 有界且不含全文") {
      val payload = Json.Obj("blob" -> Json.Str("x" * 4000))
      val call    = ToolCall("call-big", "lookup", Json.Obj())
      val context = ToolExecutionContext(
        RunId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000501")),
        ThreadId("ext-thread"),
        call.id,
        RunContext()
      )
      val policy = ToolPolicyConfig(
        allowedTools = Set(ToolName("lookup")),
        externalizeAboveBytes = 64L,
        maxResultBytes = 256L * 1024L
      )
      for
        artifacts <- ZIO.service[ArtifactStore]
        executor  <- ToolExecutor.make(policy, artifacts)
        result    <- executor.externalize(call, context, ToolResult(payload))
        stored    <- artifacts.read(ArtifactScope.of(context), ArtifactName("tool-results/call-big.json"))
      yield assertTrue(
        result.isExternalized,
        result.asInstanceOf[ToolResult.Externalized].preview.length == ToolResult.PreviewLimit,
        !result.contextPayload.toJson.contains("x" * 4000),
        stored.exists(_.bytes.nonEmpty)
      )
    },
    test("Agent Context 字符上限比部署字节阈值更小时提前外置") {
      val payload = Json.Obj("blob" -> Json.Str("x" * 200))
      val call    = ToolCall("call-context-limit", "lookup", Json.Obj())
      val context = ToolExecutionContext(
        RunId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000502")),
        ThreadId("ext-thread"),
        call.id,
        RunContext()
      )
      val policy = ToolPolicyConfig(
        allowedTools = Set(ToolName("lookup")),
        externalizeAboveBytes = 32L * 1024L,
        maxResultBytes = 256L * 1024L
      )
      for
        artifacts <- ZIO.service[ArtifactStore]
        executor  <- ToolExecutor.make(policy, artifacts)
        result    <- executor.externalize(call, context, ToolResult(payload), maxInlineCharacters = 64)
      yield assertTrue(result.isExternalized)
    }
  ).provide(ArtifactStore.inMemory())
