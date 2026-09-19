package com.zyblw.agent.core

import zio.*
import zio.test.*

object CanonicalModelRequestSpec extends ZIOSpecDefault:
  def spec = suite("CanonicalModelRequest")(
    test("from/toChatRequest 往返保持相等，fingerprint 稳定") {
      val request = ChatRequest(
        Chunk(AgentMessage.system("sys"), AgentMessage.user("hello")),
        Chunk.empty,
        ModelSettings(model = Some("m"), temperature = Some(0.2))
      )
      val canonical = CanonicalModelRequest.from(request)
      val first     = CanonicalModelRequest.fingerprint(request, Some("abc"))
      val second    = CanonicalModelRequest.fingerprint(request, Some("abc"))
      val other     = CanonicalModelRequest.fingerprint(request, Some("def"))
      assertTrue(
        canonical.toChatRequest == request,
        first == second,
        first.matches("[0-9a-f]{64}"),
        first != other
      )
    },
    test("toolDefinitionsFingerprint 只摘要发给模型的工具定义") {
      val lookup = ToolDefinition("lookup_order", "查询订单", zio.json.ast.Json.Obj())
      val refund = ToolDefinition("issue_refund", "退款", zio.json.ast.Json.Obj())
      val first  = CanonicalModelRequest.toolDefinitionsFingerprint(Chunk(lookup))
      val same   = CanonicalModelRequest.toolDefinitionsFingerprint(Chunk(lookup))
      val other  = CanonicalModelRequest.toolDefinitionsFingerprint(Chunk(lookup, refund))
      assertTrue(first == same, first != other, first.matches("[0-9a-f]{64}"))
    },
    test("稳定前缀指纹包含冻结工具与设置，不包含动态尾部") {
      val stable = AgentMessage.system("policy")
      val base   = ChatRequest(
        Chunk(stable, AgentMessage.user("dynamic-a")),
        Chunk(ToolDefinition("lookup", "query", zio.json.ast.Json.Obj())),
        ModelSettings(model = Some("m"))
      )
      val changedTail = base.copy(messages = Chunk(stable, AgentMessage.user("dynamic-b")))
      val changedTool = base.copy(tools = Chunk(ToolDefinition("other", "query", zio.json.ast.Json.Obj())))
      assertTrue(
        CanonicalModelRequest.stablePrefixFingerprint(base, 1) ==
          CanonicalModelRequest.stablePrefixFingerprint(changedTail, 1),
        CanonicalModelRequest.stablePrefixFingerprint(base, 1) !=
          CanonicalModelRequest.stablePrefixFingerprint(changedTool, 1)
      )
    },
    test("Replayable 账本拒绝工具指纹与 Canonical 不一致") {
      val tools     = Chunk(ToolDefinition("lookup_order", "查询订单", zio.json.ast.Json.Obj()))
      val canonical = CanonicalModelRequest(Chunk(AgentMessage.user("hi")), tools, ModelSettings())
      val lineage   = ModelCallContextLineage(
        estimatedTokens = 1,
        droppedMessages = 0,
        truncatedToolResults = 0,
        droppedMemories = 0,
        droppedRetrieval = 0,
        toolDefinitionsFingerprint = Some("0" * 64)
      )
      val record = ModelCallExecutionRecord(
        runId = RunId(java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
        requestId = ModelRequestId(java.util.UUID.fromString("223e4567-e89b-12d3-a456-426614174000")),
        attempt = 1,
        status = ModelCallStatus.Succeeded,
        provider = "test",
        model = "m",
        capturePolicy = CapturePolicy.Replayable,
        fingerprint = "a" * 64,
        messageCount = 1,
        toolCount = 1,
        lineage = lineage,
        instructionFingerprint = None,
        canonicalRequest = Some(canonical),
        updatedAtEpochMilli = 0L
      )
      assertTrue(record.verifyFrozenTools.isLeft, record.toChatRequest.isLeft)
    }
  )
