package com.zyblw.agent.composition

import com.zyblw.agent.core.*
import com.zyblw.agent.execution.PermissionProfile
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 组合指纹与漂移分类是纯值计算，不读取用户消息，也不授予权限。 */
object RuntimeCompositionSpec extends ZIOSpecDefault:
  private val agent = AgentDefinition(
    AgentId("composition-agent"),
    "Composition Agent",
    "按冻结指令回答。",
    allowedTools = Set("echo", "search"),
    modelSettings = ModelSettings(provider = Some("primary"), model = Some("defined-model")),
    instructionSet = Some(
      InstructionSet(
        Chunk(InstructionBlock("agent.core", InstructionAuthority.System, "keep answers short", "1"))
      )
    )
  )

  private val profile   = RuntimeProfile.default
  private val frozen    = RuntimeComposition.fingerprint(profile, agent, agent.modelSettings)
  private val liveTools = Set("echo", "search")

  private def registeredTool(
      inputSchema: Json.Obj,
      toolMetadata: ToolMetadata = ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
  ): RegisteredTool = new RegisteredTool:
    val definition = ToolDefinition("echo", "Echo one value", inputSchema, None)
    val metadata   = toolMetadata
    def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
      ZIO.succeed(ToolResult(arguments))

  /** 断言只针对属性名集合，而不针对错误文案：文案是给人看的，属性名才是稳定契约。 */
  private def changedFieldsOf(drift: CompositionDrift): Set[String] =
    drift.changedFields.map(_.field).toSet

  def spec: Spec[TestEnvironment & Scope, Any] = suite("RuntimeComposition")(
    test("相同组合判定 Compatible") {
      val live = RuntimeComposition.fingerprint(profile, agent, agent.modelSettings)
      assertTrue(
        frozen.value.length == 64,
        RuntimeComposition.compare(frozen, live, liveTools) == CompositionDrift.Compatible
      )
    },
    test("注册表缺少冻结工具时即使指纹相同也 Incompatible") {
      assertTrue(
        changedFieldsOf(
          RuntimeComposition.compare(
            frozen,
            frozen,
            liveToolNames = Set("echo"),
            requiredToolNames = Set("echo", "search")
          )
        ) == Set("requiredToolsMissing")
      )
    },
    test("指令指纹变化判定 Incompatible") {
      val changed = agent.copy(instructionSet =
        Some(
          InstructionSet(
            Chunk(InstructionBlock("agent.core", InstructionAuthority.System, "be verbose", "2"))
          )
        )
      )
      val live = RuntimeComposition.fingerprint(profile, changed, changed.modelSettings)
      assertTrue(
        changedFieldsOf(RuntimeComposition.compare(frozen, live, liveTools)) == Set("instructionFingerprint")
      )
    },
    test("生效模型引用变化判定 Incompatible") {
      val overlay = ModelSettings(provider = Some("primary"), model = Some("cheap-model"))
      val live    = RuntimeComposition.fingerprint(profile, agent, overlay)
      assertTrue(changedFieldsOf(RuntimeComposition.compare(frozen, live, liveTools)) == Set("modelRef"))
    },
    test("模型摘要覆盖完整设置且不受 Map 顺序影响") {
      val left = agent.modelSettings.copy(
        maxOutputTokens = Some(1024),
        providerOptions = Map("region" -> Json.Str("cn"), "tier" -> Json.Str("prod")),
        metadata = Map("owner" -> "runtime", "purpose" -> "primary")
      )
      val reordered = left.copy(
        providerOptions = List("tier" -> Json.Str("prod"), "region" -> Json.Str("cn")).toMap,
        metadata = List("purpose" -> "primary", "owner" -> "runtime").toMap
      )
      val changed       = left.copy(maxOutputTokens = Some(2048))
      val changedEffort = left.copy(reasoningEffort = Some(ReasoningEffort.High))
      assertTrue(
        RuntimeComposition.modelSettingsFingerprint(left) ==
          RuntimeComposition.modelSettingsFingerprint(reordered),
        RuntimeComposition.modelSettingsFingerprint(left) !=
          RuntimeComposition.modelSettingsFingerprint(changed),
        RuntimeComposition.modelSettingsFingerprint(left) !=
          RuntimeComposition.modelSettingsFingerprint(changedEffort),
        RuntimeComposition
          .compare(
            RuntimeComposition.fingerprint(profile, agent, left),
            RuntimeComposition.fingerprint(profile, agent, changed)
          )
          .isInstanceOf[CompositionDrift.Incompatible]
      )
    },
    test("仅 Profile 或 CapturePolicy 变化判定 RequiresRevalidation") {
      val live = RuntimeComposition.fingerprint(
        RuntimeProfile("eval", CapturePolicy.Replayable),
        agent,
        agent.modelSettings
      )
      val drift = RuntimeComposition.compare(frozen, live, liveTools)
      assertTrue(
        drift.isInstanceOf[CompositionDrift.RequiresRevalidation],
        changedFieldsOf(drift) == Set("profileId", "capturePolicy"),
        // 非安全变化才允许落到 RequiresRevalidation：这条断言防止安全字段被误标。
        drift.changedFields.forall(!_.securityRelevant)
      )
    },
    test("扩展身份变化判定 Incompatible") {
      val live = RuntimeComposition.fingerprint(
        profile,
        agent,
        agent.modelSettings,
        extensionIds = Chunk(CapabilityRef(CapabilityKind.ApprovalReview, "policy-bot"))
      )
      assertTrue(changedFieldsOf(RuntimeComposition.compare(frozen, live, liveTools)) == Set("extensionIds"))
    },
    test("同一个 id 换了能力类别也算漂移") {
      val asContext = RuntimeComposition.fingerprint(
        profile,
        agent,
        agent.modelSettings,
        sourceIds = Chunk(CapabilityRef(CapabilityKind.Context, "shared"))
      )
      val asSkill = RuntimeComposition.fingerprint(
        profile,
        agent,
        agent.modelSettings,
        sourceIds = Chunk(CapabilityRef(CapabilityKind.Skill, "shared"))
      )
      assertTrue(
        asContext.value != asSkill.value,
        changedFieldsOf(RuntimeComposition.compare(asContext, asSkill, liveTools)) == Set("sourceIds")
      )
    },
    test("一次部署同时改多项时报出全部变化项") {
      val live = RuntimeComposition.fingerprint(
        RuntimeProfile("eval", CapturePolicy.Replayable),
        agent,
        agent.modelSettings,
        extensionIds = Chunk(CapabilityRef(CapabilityKind.ApprovalReview, "policy-bot")),
        executionEnvironmentId = "mcp-sandbox"
      )
      assertTrue(
        changedFieldsOf(RuntimeComposition.compare(frozen, live, liveTools)) ==
          Set("extensionIds", "executionEnvironmentId", "profileId", "capturePolicy")
      )
    },
    test("执行环境身份变化判定 Incompatible") {
      val live = RuntimeComposition.fingerprint(
        profile,
        agent,
        agent.modelSettings,
        executionEnvironmentId = "mcp-sandbox"
      )
      assertTrue(
        changedFieldsOf(RuntimeComposition.compare(frozen, live, liveTools)) ==
          Set("executionEnvironmentId")
      )
    },
    test("权限剖面变化判定 Incompatible") {
      val live = RuntimeComposition.fingerprint(
        profile,
        agent,
        agent.modelSettings,
        permissionProfileFingerprint = PermissionProfile.denyAll.fingerprint
      )
      assertTrue(
        changedFieldsOf(RuntimeComposition.compare(frozen, live, liveTools)) ==
          Set("permissionProfileFingerprint")
      )
    },
    test("旧组合指纹缺 permissionProfileFingerprint 视为宿主权限") {
      val encoded  = frozen.toJson
      val stripped = encoded.fromJson[Json].map {
        case Json.Obj(fields) => Json.Obj(fields.filterNot(_._1 == "permissionProfileFingerprint"))
        case other            => other
      }
      val decoded = stripped.flatMap(_.toJson.fromJson[RuntimeCompositionFingerprint])
      assertTrue(
        decoded.exists(_.permissionProfileFingerprint.isEmpty),
        decoded.exists(value =>
          RuntimeComposition.compare(value, frozen, liveTools) == CompositionDrift.Compatible
        )
      )
    },
    test("旧组合指纹缺 executionEnvironmentId 视为 local") {
      val encoded  = frozen.toJson
      val stripped = encoded.fromJson[Json].map {
        case Json.Obj(fields) => Json.Obj(fields.filterNot(_._1 == "executionEnvironmentId"))
        case other            => other
      }
      val decoded = stripped.flatMap(_.toJson.fromJson[RuntimeCompositionFingerprint])
      assertTrue(
        decoded.exists(_.executionEnvironmentId == "local"),
        decoded.exists(value =>
          RuntimeComposition.compare(value, frozen, liveTools) == CompositionDrift.Compatible
        )
      )
    },
    test("上下文来源身份变化判定 Incompatible") {
      val live = RuntimeComposition.fingerprint(
        profile,
        agent,
        agent.modelSettings,
        Chunk(CapabilityRef(CapabilityKind.Context, "memory-rag"))
      )
      assertTrue(changedFieldsOf(RuntimeComposition.compare(frozen, live, liveTools)) == Set("sourceIds"))
    },
    test("工具契约指纹对 JSON/Set 顺序稳定，并检测安全元数据漂移") {
      val leftSchema = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj("value" -> Json.Obj("type" -> Json.Str("string")))
      )
      val rightSchema = Json.Obj(
        "properties" -> Json.Obj("value" -> Json.Obj("type" -> Json.Str("string"))),
        "type"       -> Json.Str("object")
      )
      val baselineMetadata = ToolMetadata(
        ToolRisk.ReadOnly,
        SideEffect.None,
        requiredScopes = Set("profile:read", "tenant:read"),
        sensitiveInputFields = Set("secret", "token")
      )
      val reorderedMetadata = baselineMetadata.copy(
        requiredScopes = List("tenant:read", "profile:read").toSet,
        sensitiveInputFields = List("token", "secret").toSet
      )
      val changedMetadata = baselineMetadata.copy(
        risk = ToolRisk.ApprovalWrite,
        sideEffect = SideEffect.NonIdempotentWrite
      )
      val baseline  = ToolContractFingerprint.registered(registeredTool(leftSchema, baselineMetadata))
      val reordered = ToolContractFingerprint.registered(registeredTool(rightSchema, reorderedMetadata))
      val changed   = ToolContractFingerprint.registered(registeredTool(leftSchema, changedMetadata))
      assertTrue(
        baseline == reordered,
        baseline != changed,
        baseline != ToolContractFingerprint.missing("echo")
      )
    },
    test("DurableToolPlan 新指纹可 JSON 往返") {
      val call        = ToolCall("call-echo", "echo", Json.Obj())
      val fingerprint = ToolContractFingerprint.registered(
        registeredTool(Json.Obj("type" -> Json.Str("object")))
      )
      val plan = DurableToolPlan(
        "plan-contract",
        Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, call)))),
        toolContractFingerprints = Map("echo" -> fingerprint),
        approvalSubjects = Map.empty
      )
      assertTrue(plan.toJson.fromJson[DurableToolPlan].contains(plan))
    },
    test("冻结指纹可 JSON 往返") {
      val now   = java.time.Instant.parse("2026-08-20T00:00:00Z")
      val state = AgentState(
        RunId(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")),
        SessionId(java.util.UUID.fromString("22222222-2222-2222-2222-222222222222")),
        agent.id,
        RunStatus.Created,
        Chunk(AgentMessage.user("hello")),
        Chunk.empty,
        UsageSummary(),
        BudgetState(RunLimits(), UsageSummary(), 0),
        None,
        now,
        now,
        Version.initial,
        agent,
        frozen,
        ThreadId("composition-thread")
      )
      assertTrue(state.toJson.fromJson[AgentState].exists(_.composition == frozen))
    }
  )
