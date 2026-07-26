package com.zyblw.agent.http.contract

import zio.json.*
import zio.test.*

/** HTTP v1 公共协议的机械兼容性门禁。
  *
  * 这些断言不是普通实现测试，而是首次发布基线：删除路径、修改主版本、泄漏内部字段或改变关键 JSON 字段名都会直接失败。 有意的不兼容变更必须创建 `/api/v2` 和独立基线，不能顺手更新本测试来伪装兼容。
  */
object AgentHttpContractSpec extends ZIOSpecDefault:

  private val requiredPaths = Set(
    "/api/v1/agents/{agentId}/runs",
    "/api/v1/runs/{runId}",
    "/api/v1/runs/{runId}/approval",
    "/api/v1/runs/{runId}/recover",
    "/api/v1/runs/{runId}/retry",
    "/api/v1/runs/{runId}/commands",
    "/api/v1/runs/{runId}/inspection",
    "/api/v1/runs/{runId}/events",
    "/api/v1/runs/{runId}/events/stream",
    "/api/v1/commands/{commandId}",
    "/api/v1/commands/{commandId}/retry"
  )

  /** v1 已承诺的关键 wire 字段。这里不复制完整 OpenAPI 快照，而是先对最容易被内部重构误删的字段建立硬门禁；发布流水线 后续仍应保存完整规范并执行结构化 compatibility diff。
    */
  private val requiredWireFields = Set(
    "threadId",
    "input",
    "commandId",
    "runId",
    "commandType",
    "status",
    "manualRetryCount",
    "modelCalls",
    "toolCalls",
    "inputTokens",
    "outputTokens",
    "cachedInputTokens",
    "reasoningOutputTokens",
    "totalTokens",
    "estimatedCost",
    "pendingApproval",
    "stateVersion",
    "eventId",
    "sequence",
    "eventType",
    "atEpochMilli",
    "context",
    "approval",
    "tool",
    "timeline",
    "diagnostics",
    "instructionFingerprint",
    "completeHistory",
    "consistent"
  )

  def spec = suite("Agent HTTP v1 contract")(
    test("OpenAPI 版本和全部稳定路径来自 Endpoint 单一事实源") {
      val json = AgentHttpContract.openApiJson
      assertTrue(
        AgentHttpContract.openApi.info.version == AgentHttpProtocol.ContractVersion,
        AgentHttpContract.openApi.info.title == "zyblw-agent API",
        AgentHttpProtocol.BasePath == "/api/v1",
        requiredPaths.forall(path => json.contains(s"\"$path\"")),
        requiredWireFields.forall(field => json.contains(s"\"$field\"")),
        json.contains("\"202\""),
        json.contains("text/event-stream")
      )
    },
    test("OpenAPI 不包含内部状态、工具参数、结果或认证上下文") {
      val forbidden = Set(
        "state_json",
        "pendingToolPlan",
        "runContext",
        "argumentsHash",
        "ToolExecutionRecord",
        "AgentState",
        "PersistedAgentEvent"
      )
      val leaked = forbidden.filter(AgentHttpContract.openApiJson.contains)
      assertTrue(leaked.isEmpty)
    },
    test("关键 DTO 保持固定字段名并允许未来客户端增加未知字段") {
      val receipt       = CommandReceipt("command-1", "run-1", "Start", "Queued")
      val futureRequest = """{"threadId":"thread-1","input":"hello","futureOptionalField":true}"""
      assertTrue(
        receipt.toJson ==
          """{"commandId":"command-1","runId":"run-1","commandType":"Start","status":"Queued"}""",
        futureRequest.fromJson[CreateRunRequest] == Right(CreateRunRequest("thread-1", "hello"))
      )
    },
    test("公共事件信封只使用扁平稳定字段和可选扩展") {
      val event = RunEventView(
        eventId = "event-1",
        runId = "run-1",
        sequence = 7L,
        eventType = "RunCompleted",
        atEpochMilli = 1000L,
        status = Some("Completed"),
        output = Some("done"),
        usage = Some(UsageView(1, 0, 10, 2, 12, "0"))
      )
      val encoded = event.toJson
      assertTrue(
        encoded.contains("\"eventType\":\"RunCompleted\""),
        encoded.contains("\"sequence\":7"),
        encoded.contains("\"output\":\"done\""),
        !encoded.contains("AgentMessage"),
        // usage.toolCalls 是允许公开的低敏计数；这里禁止的是内部工具调用数组与参数正文。
        !encoded.contains("\"toolCalls\":["),
        !encoded.contains("\"arguments\":"),
        !encoded.contains("\"result\":")
      )
    }
  )
