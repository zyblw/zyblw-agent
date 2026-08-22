package com.zyblw.agent.composition

import com.zyblw.agent.core.*
import com.zyblw.agent.execution.{
  FilesystemAccess,
  NetworkAccess,
  PermissionProfile,
  ProcessAccess,
  SecretAccess
}
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 审批主体把人工授权绑定到具体副作用，而不是工具名或 Provider 给出的 callId。 */
object ApprovalSubjectSpec extends ZIOSpecDefault:
  private val strictPolicy = ToolPolicyConfig(allowedTools = Set(ToolName("write")))
  private val alwaysPolicy = strictPolicy.copy(approvalPolicy = ApprovalPolicy.Always)

  private def writeTool(
      description: String = "写入草稿",
      toolMetadata: ToolMetadata = ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
  ): RegisteredTool = new RegisteredTool:
    val definition = ToolDefinition("write", description, Json.Obj("type" -> Json.Str("object")), None)
    val metadata   = toolMetadata
    def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
      ZIO.succeed(ToolResult(arguments))

  private def call(arguments: Json = Json.Obj()): ToolCall = ToolCall("call-1", "write", arguments)

  private def subjectOf(
      toolCall: ToolCall = call(),
      tool: RegisteredTool = writeTool(),
      policy: ToolPolicyConfig = strictPolicy,
      context: RunContext = RunContext()
  ): ApprovalSubject =
    ApprovalSubject.of(
      toolCall,
      tool.metadata,
      ToolContractFingerprint.registered(tool),
      ApprovalPolicyFingerprint.of(policy, ToolName(toolCall.name)),
      AuthorizationFingerprint.of(context)
    )

  def spec = suite("ApprovalSubject")(
    test("同一副作用在任意 JSON 字段书写顺序下得到同一主体") {
      val ordered  = subjectOf(call(Json.Obj("a" -> Json.Num(1), "b" -> Json.Num(2))))
      val shuffled = subjectOf(call(Json.Obj("b" -> Json.Num(2), "a" -> Json.Num(1))))
      assertTrue(ordered == shuffled, ordered.value == shuffled.value, ordered.driftFrom(shuffled).isEmpty)
    },
    test("参数变化让批准失效，且只报告 input 漂移") {
      val approved = subjectOf(call(Json.Obj("path" -> Json.Str("/tmp/a"))))
      val proposed = subjectOf(call(Json.Obj("path" -> Json.Str("/etc/passwd"))))
      assertTrue(
        approved != proposed,
        approved.value != proposed.value,
        approved.driftFrom(proposed) == List("input")
      )
    },
    test("审批策略变化让批准失效") {
      val underRiskBased = subjectOf(policy = strictPolicy)
      val underAlways    = subjectOf(policy = alwaysPolicy)
      assertTrue(underRiskBased.driftFrom(underAlways) == List("policy"))
    },
    test("调用者授权上下文变化让批准失效") {
      val granted = subjectOf(context = RunContext(scopes = Set("draft:write")))
      val widened = subjectOf(context = RunContext(scopes = Set("draft:write", "admin")))
      val other   = subjectOf(context = RunContext(tenantId = Some("t-2")))
      assertTrue(
        granted.driftFrom(widened) == List("authorization"),
        granted.driftFrom(other) == List("authorization")
      )
    },
    test("工具契约或风险等级变化让批准失效") {
      val original  = subjectOf(tool = writeTool("写入草稿"))
      val redocked  = subjectOf(tool = writeTool("直接写入生产库"))
      val escalated = subjectOf(tool =
        writeTool(toolMetadata = ToolMetadata(ToolRisk.AdminApproval, SideEffect.Destructive))
      )
      assertTrue(
        original.driftFrom(redocked) == List("toolContract"),
        original.driftFrom(escalated) == List("toolContract", "risk", "sideEffect")
      )
    },
    test("执行环境或权限剖面变化让批准失效") {
      val local   = subjectOf()
      val sandbox = subjectOf().copy(
        environment = ExecutionEnvironmentId.mcpSandbox,
        permissions = PermissionProfile(
          filesystem = FilesystemAccess.Workspace("/workspace", writable = true),
          network = NetworkAccess.DenyAll,
          process = ProcessAccess.DenyAll,
          secrets = SecretAccess.DenyAll
        )
      )
      val denied = subjectOf().copy(permissions = PermissionProfile.denyAll)
      assertTrue(
        local.driftFrom(sandbox) == List("environment", "permissions"),
        local.driftFrom(denied) == List("permissions"),
        sandbox.environment == ExecutionEnvironmentId.mcpSandbox
      )
    },
    test("主体经 JSON 往返后仍然相等，可用于判定历史批准") {
      val subject = subjectOf(call(Json.Obj("path" -> Json.Str("/tmp/a"))))
      val decoded = subject.toJson.fromJson[ApprovalSubject]
      assertTrue(decoded == Right(subject), decoded.map(_.value) == Right(subject.value))
    },
    test("旧主体缺 permissions 视为宿主权限") {
      val subject  = subjectOf()
      val encoded  = subject.toJson
      val stripped = encoded.fromJson[Json].map {
        case Json.Obj(fields) => Json.Obj(fields.filterNot(_._1 == "permissions"))
        case other            => other
      }
      val decoded = stripped.flatMap(_.toJson.fromJson[ApprovalSubject])
      assertTrue(
        decoded.exists(_.permissions == PermissionProfile.host),
        decoded.exists(_.driftFrom(subject).isEmpty)
      )
    },
    test("主体与漂移说明都不携带参数正文") {
      val subject = subjectOf(call(Json.Obj("secret" -> Json.Str("hunter2"))))
      assertTrue(
        subject.value.matches("[0-9a-f]{64}"),
        !subject.toJson.contains("hunter2"),
        !subject.driftFrom(subjectOf()).mkString("、").contains("hunter2")
      )
    }
  ) + suite("DurableToolPlan 审批快照")(
    test("同一份审批事实不能同时以 v5 callId 和 v6 主体存在") {
      assertTrue(
        scala.util
          .Try(
            DurableToolPlan(
              "plan-1",
              Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, call())))),
              approvalRequiredCallIds = Some(Set("call-1")),
              approvalSubjects = Some(Map("call-1" -> subjectOf()))
            )
          )
          .isFailure
      )
    },
    test("审批主体必须与其 callId 键一致，且只能引用计划内的调用") {
      val batches = Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, call()))))
      assertTrue(
        scala.util
          .Try(DurableToolPlan("plan-1", batches, approvalSubjects = Some(Map("other" -> subjectOf()))))
          .isFailure
      )
    },
    test("v6 主体优先，v5 快照回落到 callId 集合，更早快照没有冻结事实") {
      val batches = Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, call()))))
      val v6      = DurableToolPlan("p", batches, approvalSubjects = Some(Map("call-1" -> subjectOf())))
      val v5      = DurableToolPlan("p", batches, approvalRequiredCallIds = Some(Set("call-1")))
      val legacy  = DurableToolPlan("p", batches)
      assertTrue(
        v6.frozenApprovalCallIds.contains(Set("call-1")),
        v5.frozenApprovalCallIds.contains(Set("call-1")),
        legacy.frozenApprovalCallIds.isEmpty
      )
    }
  )
