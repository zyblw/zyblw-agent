package com.zyblw.agent.composition

import com.zyblw.agent.core.*
import com.zyblw.agent.execution.PermissionProfile
import com.zyblw.agent.model.ChatModel
import com.zyblw.agent.tools.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 一次部署的运行组合。它描述装配，不授予权限，也不能绕过 ToolPolicy / Guardrail / 审批。 */
final case class RuntimeProfile(
    id: String = "default",
    capturePolicy: CapturePolicy = CapturePolicy.MetadataOnly,
    modelRouting: Option[com.zyblw.agent.model.ModelRoutingPolicy] = None
) derives JsonCodec:
  require(id.trim.nonEmpty && id.length <= 64, "RuntimeProfile.id 必须为 1..64 个字符")

object RuntimeProfile:
  val default: RuntimeProfile              = RuntimeProfile()
  val defaultLayer: ULayer[RuntimeProfile] = ZLayer.succeed(default)

/** 能力目录条目。只用于 introspection 和组合指纹，不是授权决定。 */
enum CapabilityKind derives JsonCodec:
  case Model, Tool, Context, Guardrail, Persistence, Observer, Skill, ApprovalReview, ToolLifecycle,
    ExecutionEnvironment

final case class CapabilityDescriptor(
    kind: CapabilityKind,
    name: String,
    version: Option[String] = None
) derives JsonCodec:
  require(name.trim.nonEmpty, "CapabilityDescriptor.name 不能为空")

/** Run 创建时冻结的组合快照；恢复时与现场组合比较，禁止 silent drift。 */
final case class RuntimeCompositionFingerprint(
    value: String,
    profileId: String,
    instructionFingerprint: Option[String],
    allowedTools: Chunk[String],
    modelRef: String,
    capturePolicy: CapturePolicy,
    /** 上下文来源身份，格式 `id@version`；缺省表示未声明贡献者。 */
    sourceIds: Chunk[String] = Chunk.empty,
    /** typed extension 身份，格式 `id@version`；缺省表示未声明扩展。不进入 `value` 哈希，比较时单独判定。 */
    extensionIds: Chunk[String] = Chunk.empty,
    /** 执行环境身份；缺省 `local`。不进入 `value` 哈希，比较时单独判定。 */
    executionEnvironmentId: String = "local",
    /** 权限剖面摘要；缺省视为宿主权限。不进入 `value` 哈希，比较时单独判定。 */
    permissionProfileFingerprint: String = "",
    modelRoutingFingerprint: Option[String] = None,
    modelPricingFingerprint: Option[String] = None
) derives JsonCodec:
  require(value.matches("[0-9a-f]{64}"), "组合指纹必须是 SHA-256 十六进制")

/** 组合与审批指纹共用的规范化摘要工具。
  *
  * 摘要必须与 JSON 对象字段书写顺序、Set 迭代顺序无关，否则同一份事实会在不同进程算出不同指纹，把「无变化」误报成漂移。
  */
private[agent] object CanonicalDigest:
  /** 递归按字段名排序 JSON 对象，使摘要只取决于内容本身。 */
  def canonicalize(value: Json): Json = value match
    case Json.Obj(fields) =>
      Json.Obj(
        Chunk.fromIterable(
          fields.toList.map { case (name, child) => name -> canonicalize(child) }.sortBy(_._1)
        )
      )
    case Json.Arr(values) => Json.Arr(values.map(canonicalize))
    case scalar           => scalar

  /** 对已规范化的文本取 SHA-256 十六进制摘要。 */
  def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  /** 把无序字符串集合摘要成稳定数组。 */
  def strings(values: Set[String]): Json.Arr =
    Json.Arr(Chunk.fromIterable(values.toList.sorted.map(Json.Str(_))))

/** 一个注册工具的低敏恢复契约摘要。
  *
  * 摘要覆盖模型可见 definition 与 Runtime 强制执行的风险、scope、副作用、脱敏及并行元数据。它不保存 Schema、说明文字、 scope
  * 名称或工具参数正文；用于阻止已持久化工具计划在部署漂移后按另一套安全语义静默执行。
  */
final case class ToolContractFingerprint(value: String) derives JsonCodec:
  require(value.matches("[0-9a-f]{64}"), "工具契约指纹必须是 SHA-256 十六进制")

object ToolContractFingerprint:
  /** 对已注册工具生成与 JSON 对象字段顺序、Set 迭代顺序无关的摘要。 */
  def registered(tool: RegisteredTool): ToolContractFingerprint =
    val definition = tool.definition
    val metadata   = tool.metadata
    val conflicts  = metadata.conflictAccesses.toList.sortBy(access => access.group -> access.mode.toString)
    val material   = Json.Obj(
      "kind"         -> Json.Str("registered"),
      "name"         -> Json.Str(definition.name),
      "description"  -> Json.Str(definition.description),
      "inputSchema"  -> CanonicalDigest.canonicalize(definition.inputSchema),
      "outputSchema" -> definition.outputSchema
        .map(schema => CanonicalDigest.canonicalize(schema): Json)
        .getOrElse(Json.Null),
      "strict"           -> Json.Bool(definition.strict),
      "risk"             -> Json.Str(metadata.risk.toString),
      "sideEffect"       -> Json.Str(metadata.sideEffect.toString),
      "requiredScopes"   -> CanonicalDigest.strings(metadata.requiredScopes),
      "sensitiveInputs"  -> CanonicalDigest.strings(metadata.sensitiveInputFields),
      "sensitiveOutputs" -> CanonicalDigest.strings(metadata.sensitiveOutputFields),
      "parallelism"      -> Json.Str(metadata.parallelism.toString),
      "conflicts"        -> Json.Arr(
        Chunk.fromIterable(conflicts.map(access => Json.Str(s"${access.group}:${access.mode}")))
      )
    )
    ToolContractFingerprint(CanonicalDigest.sha256(CanonicalDigest.canonicalize(material).toJson))

  /** 冻结“规划时未注册”这一事实，防止同名工具在恢复前出现后被静默升级为可执行副作用。 */
  def missing(name: String): ToolContractFingerprint =
    ToolContractFingerprint(
      CanonicalDigest.sha256(Json.Obj("kind" -> Json.Str("missing"), "name" -> Json.Str(name)).toJson)
    )

enum CompositionDrift derives JsonCodec:
  case Compatible
  case RequiresRevalidation(reason: String)
  case Incompatible(reason: String)

object RuntimeComposition:
  /** 用当前 Profile 与生效模型覆盖冻结一次组合。不读取用户消息。 */
  def freeze(
      profile: RuntimeProfile,
      agent: AgentDefinition,
      modelPolicies: ModelPolicySource,
      sourceIds: Chunk[String] = Chunk.empty,
      extensionIds: Chunk[String] = Chunk.empty,
      executionEnvironmentId: String = "local",
      permissionProfileFingerprint: String = ""
  ): RuntimeCompositionFingerprint =
    fingerprint(
      profile,
      agent,
      modelPolicies.current().applyTo(agent.modelSettings),
      sourceIds,
      extensionIds,
      executionEnvironmentId,
      permissionProfileFingerprint,
      Option.when(profile.modelRouting.nonEmpty)(modelPolicies.prices.fingerprint)
    )

  /** 从已冻结的 Agent 定义、生效模型设置和 Profile 计算组合指纹。不读取用户消息。 */
  def fingerprint(
      profile: RuntimeProfile,
      agent: AgentDefinition,
      effectiveModel: ModelSettings,
      sourceIds: Chunk[String] = Chunk.empty,
      extensionIds: Chunk[String] = Chunk.empty,
      executionEnvironmentId: String = "local",
      permissionProfileFingerprint: String = "",
      modelPricingFingerprint: Option[String] = None
  ): RuntimeCompositionFingerprint =
    val tools         = Chunk.fromIterable(agent.allowedTools.toList.sorted)
    val sources       = Chunk.fromIterable(sourceIds.map(_.trim).filter(_.nonEmpty).toList.distinct.sorted)
    val extensions    = Chunk.fromIterable(extensionIds.map(_.trim).filter(_.nonEmpty).toList.distinct.sorted)
    val instructionFp = agent.instructionSet.map(_.fingerprint)
    val modelRef      = modelSettingsFingerprint(effectiveModel)
    val material      = List(
      profile.id,
      profile.capturePolicy.toString,
      instructionFp.getOrElse(""),
      tools.mkString(","),
      modelRef,
      sources.mkString(",")
    ).mkString("\n")
    RuntimeCompositionFingerprint(
      value = CanonicalDigest.sha256(material),
      profileId = profile.id,
      instructionFingerprint = instructionFp,
      allowedTools = tools,
      modelRef = modelRef,
      capturePolicy = profile.capturePolicy,
      sourceIds = sources,
      extensionIds = extensions,
      executionEnvironmentId =
        Option(executionEnvironmentId).map(_.trim).filter(_.nonEmpty).getOrElse("local"),
      permissionProfileFingerprint = Option(permissionProfileFingerprint)
        .map(_.trim)
        .filter(_.nonEmpty)
        .getOrElse(PermissionProfile.hostFingerprint),
      modelRoutingFingerprint = profile.modelRouting.map(_.fingerprint),
      modelPricingFingerprint = modelPricingFingerprint
    )

  /** 比较冻结组合与当前进程组合。指令/模型/允许工具集合变化视为不兼容。
    *
    * @param requiredToolNames
    *   当前必须能执行的工具（通常来自 pendingToolPlan）。空集合表示本轮不核验注册表。
    * @param liveToolNames
    *   当前注册表中实际存在的工具名
    */
  def compare(
      frozen: RuntimeCompositionFingerprint,
      live: RuntimeCompositionFingerprint,
      liveToolNames: Set[String] = Set.empty,
      requiredToolNames: Set[String] = Set.empty
  ): CompositionDrift =
    val missing = requiredToolNames.filterNot(liveToolNames.contains)
    if missing.nonEmpty then
      CompositionDrift.Incompatible(s"冻结工具已从当前注册表消失: ${missing.toList.sorted.mkString(",")}")
    else if frozen.extensionIds.toSet != live.extensionIds.toSet then
      CompositionDrift.Incompatible("扩展组合已变化，拒绝按原 Run 静默继续")
    else if frozen.executionEnvironmentId != live.executionEnvironmentId then
      CompositionDrift.Incompatible("执行环境已变化，拒绝按原 Run 静默继续")
    else if permissionsOf(frozen) != permissionsOf(live) then
      CompositionDrift.Incompatible("权限剖面已变化，拒绝按原 Run 静默继续")
    else if frozen.modelRoutingFingerprint != live.modelRoutingFingerprint then
      CompositionDrift.Incompatible("模型路由配置已变化，拒绝按原 Run 静默继续")
    else if frozen.modelPricingFingerprint != live.modelPricingFingerprint then
      CompositionDrift.Incompatible("模型价格表已变化，拒绝在同一 Run 混用计价合同")
    else if frozen.value == live.value then CompositionDrift.Compatible
    else if frozen.instructionFingerprint != live.instructionFingerprint then
      CompositionDrift.Incompatible("指令指纹已变化，拒绝按原 Run 静默继续")
    else if frozen.modelRef != live.modelRef then CompositionDrift.Incompatible("生效模型引用已变化，拒绝静默继续")
    else if frozen.allowedTools.toSet != live.allowedTools.toSet then
      CompositionDrift.Incompatible("允许工具集合已变化，拒绝静默继续")
    else if frozen.sourceIds.toSet != live.sourceIds.toSet then
      CompositionDrift.Incompatible("上下文来源组合已变化，拒绝静默继续")
    else
      CompositionDrift.RequiresRevalidation(
        s"组合元数据变化：profile ${frozen.profileId}->${live.profileId}, capture ${frozen.capturePolicy}->${live.capturePolicy}"
      )

  /** 对完整生效 ModelSettings 取规范摘要，覆盖 provider/model、采样、输出上限、tool choice 与扩展选项。 */
  def modelSettingsFingerprint(settings: ModelSettings): String =
    val material = Json.Obj(
      "provider"        -> Json.Str(settings.provider.getOrElse("")),
      "model"           -> Json.Str(settings.model.getOrElse("")),
      "temperature"     -> Json.Str(settings.temperature.fold("")(_.toString)),
      "maxOutputTokens" -> Json.Str(settings.maxOutputTokens.fold("")(_.toString)),
      "toolChoice"      -> Json.Str(settings.toolChoice.toJson),
      "providerOptions" -> Json.Obj(
        Chunk.fromIterable(
          settings.providerOptions.toList.sortBy(_._1).map { case (name, value) =>
            name -> CanonicalDigest.canonicalize(value)
          }
        )
      ),
      "metadata" -> Json.Obj(
        Chunk.fromIterable(
          settings.metadata.toList.sortBy(_._1).map { case (name, value) => name -> Json.Str(value) }
        )
      )
    )
    // 未声明 requirement 时保持旧指纹字节不变。
    val routed = settings.requirement.fold(material)(requirement =>
      Json.Obj(material.fields :+ ("requirement" -> Json.Str(requirement.toJson)))
    )
    CanonicalDigest.sha256(routed.toJson)

  /** 低敏能力目录，供 DX / 运维 introspection。 */
  def catalog(
      model: ChatModel,
      tools: Iterable[RegisteredTool],
      profile: RuntimeProfile,
      sourceIds: Chunk[String] = Chunk.empty,
      extensionIds: Chunk[String] = Chunk.empty,
      executionEnvironmentId: String = "local"
  ): Chunk[CapabilityDescriptor] =
    Chunk(
      CapabilityDescriptor(CapabilityKind.Model, model.provider),
      CapabilityDescriptor(CapabilityKind.Persistence, "RunStore"),
      CapabilityDescriptor(
        CapabilityKind.Observer,
        s"capture:${profile.capturePolicy}",
        Some(profile.id)
      ),
      CapabilityDescriptor(CapabilityKind.ExecutionEnvironment, executionEnvironmentId)
    ) ++ Chunk.fromIterable(
      tools.map(tool => CapabilityDescriptor(CapabilityKind.Tool, tool.definition.name))
    ) ++ sourceIds.map { raw =>
      raw.split('@') match
        case Array(name, version) => CapabilityDescriptor(CapabilityKind.Context, name, Some(version))
        case _                    => CapabilityDescriptor(CapabilityKind.Context, raw)
    } ++ extensionIds.map { raw =>
      raw.split('@') match
        case Array(name, version) => CapabilityDescriptor(CapabilityKind.Observer, name, Some(version))
        case _                    => CapabilityDescriptor(CapabilityKind.Observer, raw)
    }

  private def permissionsOf(fingerprint: RuntimeCompositionFingerprint): String =
    Option(fingerprint.permissionProfileFingerprint)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(PermissionProfile.hostFingerprint)
end RuntimeComposition
