package com.zyblw.agent.mcp

import zio.*
import zio.json.ast.Json
import zio.test.*

/** sampling/elicitation 治理与实验性 Tasks 的确定性契约测试。 */
object McpInteractiveAndTasksSpec extends ZIOSpecDefault:

  private val serverId = McpServerId("interactive-contract")

  /** 最小合法 sampling 参数。 */
  private def samplingParams(withTools: Boolean): Json.Obj =
    val base = Chunk[(String, Json)](
      "messages" -> Json.Arr(
        Json.Obj(
          "role"    -> Json.Str("user"),
          "content" -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("private prompt"))
        )
      ),
      "maxTokens" -> Json.Num(128)
    )
    Json.Obj(
      base ++ Option.when(withTools)(
        "tools" -> Json.Arr(
          Json.Obj("name" -> Json.Str("lookup"), "inputSchema" -> Json.Obj("type" -> Json.Str("object")))
        )
      )
    )

  def spec = suite("MCP interactive governance and tasks")(
    test("sampling.tools 未协商时在审批前拒绝，服务与审批都不会被调用") {
      for
        approvals <- Ref.make(0)
        samples   <- Ref.make(0)
        approval = new McpInteractiveApproval:
          def authorize(summary: McpInteractiveApprovalSummary) =
            approvals.update(_ + 1).as(McpInteractiveDecision.Approve)
        service = new McpSamplingService:
          def createMessage(serverId: McpServerId, request: McpSamplingRequest) =
            samples.update(_ + 1).as(McpSamplingResult("model", "assistant", Json.Obj()))
        handler = GovernedMcpClientRequestHandler(
          McpClientCapabilities(sampling = true, samplingTools = false),
          approval,
          service,
          McpElicitationService.decline
        )
        result        <- handler.handle(serverId, "sampling/createMessage", samplingParams(withTools = true))
        approvalCount <- approvals.get
        sampleCount   <- samples.get
      yield assertTrue(result.left.exists(_.code == -32602), approvalCount == 0, sampleCount == 0)
    },
    test("审批通过后才调用 sampling service，审批摘要不包含 prompt 正文") {
      for
        summaries <- Ref.make(Chunk.empty[McpInteractiveApprovalSummary])
        approval = new McpInteractiveApproval:
          def authorize(summary: McpInteractiveApprovalSummary) =
            summaries.update(_ :+ summary).as(McpInteractiveDecision.Approve)
        service = new McpSamplingService:
          def createMessage(serverId: McpServerId, request: McpSamplingRequest) =
            ZIO.succeed(
              McpSamplingResult(
                "cost-effective-model",
                "assistant",
                Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("answer")),
                Some("endTurn")
              )
            )
        handler = GovernedMcpClientRequestHandler(
          McpClientCapabilities(sampling = true),
          approval,
          service,
          McpElicitationService.decline
        )
        result   <- handler.handle(serverId, "sampling/createMessage", samplingParams(withTools = false))
        observed <- summaries.get
      yield assertTrue(
        result.exists {
          case obj: Json.Obj => McpJson.field(obj, "model").contains(Json.Str("cost-effective-model"))
          case _             => false
        },
        observed.map(_.maxTokens) == Chunk(Some(128L)),
        !observed.toString.contains("private prompt")
      )
    },
    test("elicitation 被拒绝时返回 decline，不调用 UI；URL 强制 HTTPS") {
      for
        calls <- Ref.make(0)
        service = new McpElicitationService:
          def elicit(serverId: McpServerId, request: McpElicitationRequest) =
            calls.update(_ + 1).as(McpElicitationResult(McpElicitationAction.Accept))
        handler = GovernedMcpClientRequestHandler(
          McpClientCapabilities(elicitationForm = true, elicitationUrl = true),
          McpInteractiveApproval.denyAll,
          McpSamplingService.unavailable,
          service
        )
        form <- handler.handle(
          serverId,
          "elicitation/create",
          Json.Obj(
            "mode"            -> Json.Str("form"),
            "message"         -> Json.Str("需要补充资料"),
            "requestedSchema" -> Json.Obj(
              "type"       -> Json.Str("object"),
              "properties" -> Json.Obj("name" -> Json.Obj("type" -> Json.Str("string")))
            )
          )
        )
        insecure <- handler.handle(
          serverId,
          "elicitation/create",
          Json.Obj(
            "mode"          -> Json.Str("url"),
            "message"       -> Json.Str("登录"),
            "elicitationId" -> Json.Str("opaque-1"),
            "url"           -> Json.Str("http://example.com/login")
          )
        )
        count <- calls.get
      yield assertTrue(
        form.exists {
          case obj: Json.Obj => McpJson.field(obj, "action").contains(Json.Str("decline"))
          case _             => false
        },
        insecure.isLeft,
        count == 0
      )
    },
    test("实验性 Tasks 经过能力门禁并严格解析状态、时间和 TTL") {
      val task = Json.Obj(
        "taskId"        -> Json.Str("task-1"),
        "status"        -> Json.Str("working"),
        "createdAt"     -> Json.Str("2026-07-15T00:00:00Z"),
        "lastUpdatedAt" -> Json.Str("2026-07-15T00:00:01Z"),
        "ttl"           -> Json.Num(60000),
        "pollInterval"  -> Json.Num(1000)
      )
      val init = Json.Obj(
        "protocolVersion" -> Json.Str("2025-11-25"),
        "capabilities"    -> Json.Obj("tasks" -> Json.Obj()),
        "serverInfo"      -> Json.Obj("name" -> Json.Str("task-server"), "version" -> Json.Str("1"))
      )
      for
        transport <- ScriptedMcpTransport.successful(
          Map(
            "initialize"   -> Chunk(init),
            "tasks/list"   -> Chunk(Json.Obj("tasks" -> Json.Arr(task))),
            "tasks/get"    -> Chunk(task),
            "tasks/result" -> Chunk(Json.Obj("answer" -> Json.Str("done"))),
            "tasks/cancel" -> Chunk(task.copy(fields = task.fields.map {
              case ("status", _) => "status" -> Json.Str("cancelled")
              case other         => other
            }))
          )
        )
        result <- ZIO.scoped {
          for
            client <- DefaultMcpClient.scoped(
              transport,
              McpClientConfig(serverId, McpImplementation("test", "1"))
            )
            listed    <- client.listTasks
            current   <- client.getTask("task-1")
            payload   <- client.taskResult("task-1")
            cancelled <- client.cancelTask("task-1")
          yield (listed, current, payload, cancelled)
        }
      yield assertTrue(
        result._1.head.status == McpTaskStatus.Working,
        result._1.head.ttlMillis.contains(60000L),
        result._2.pollIntervalMillis.contains(1000L),
        result._3 == Json.Obj("answer" -> Json.Str("done")),
        result._4.status == McpTaskStatus.Cancelled
      )
    }
  )
