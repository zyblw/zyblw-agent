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

/** 进入组合指纹的一个能力贡献者身份。
  *
  * 相比裸 `"id@version"` 字符串多带一个 [[CapabilityKind]]：漂移报告要回答的是"哪一类装配变了"，而字符串列表只能回答"某个 id 不见了"。运维看到 `Skill`
  * 少了一个和看到 `ApprovalReview` 少了一个，处置优先级完全不同。
  */
final case class CapabilityRef(
    kind: CapabilityKind,
    id: String,
    version: String = "1"
) derives JsonCodec:
  require(id.trim.nonEmpty && !id.contains('@'), "CapabilityRef.id 不能为空且不含 @")

  /** 与 `ExtensionDescriptor.sourceId`、`ContextContributor.sourceId` 同一形状，作为哈希材料与展示名。 */
  def sourceId: String = s"$id@$version"

object CapabilityRef:
  /** 解析 `id@version`；没有 `@` 时版本记为 `1`，与贡献者默认版本一致。 */
  def parse(kind: CapabilityKind, raw: String): Option[CapabilityRef] =
    raw.trim.split('@') match
      case Array(id) if id.nonEmpty          => Some(CapabilityRef(kind, id))
      case Array(id, version) if id.nonEmpty => Some(CapabilityRef(kind, id, version))
      case _                                 => None

  /** 规范化一组引用：去空、去重、按 `kind` 再按 `sourceId` 排序，使指纹与迭代顺序无关。 */
  def normalize(refs: Chunk[CapabilityRef]): Chunk[CapabilityRef] =
    Chunk.fromIterable(refs.toList.distinct.sortBy(ref => ref.kind.toString -> ref.sourceId))

  /** 从裸 `id@version` 列表升级为带类别的引用；解析失败的条目丢弃而不是伪造一个空 id。 */
  def parseAll(kind: CapabilityKind, raw: Chunk[String]): Chunk[CapabilityRef] =
    normalize(raw.flatMap(parse(kind, _)))

/** Run 创建时冻结的组合快照；恢复时与现场组合比较，禁止 silent drift。 */
final case class RuntimeCompositionFingerprint(
    value: String,
    profileId: String,
    instructionFingerprint: Option[String],
    allowedTools: Chunk[String],
    modelRef: String,
    capturePolicy: CapturePolicy,
    /** 上下文来源身份；缺省表示未声明贡献者。带 [[CapabilityKind]] 以便漂移报告说出"哪一类来源变了"。 */
    sourceIds: Chunk[CapabilityRef] = Chunk.empty,
    /** typed extension 身份；缺省表示未声明扩展。不进入 `value` 哈希，比较时单独判定。 */
    extensionIds: Chunk[CapabilityRef] = Chunk.empty,
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

/** 一个发生了变化的组合属性。
  *
  * 只携带属性名与它属于哪类能力，**不携带任何一侧的取值**。这让 drift 结果可以安全进入面向运维的错误信息、事件流与
  * IncidentPack：知道"指令变了"足以决定拒绝恢复，而知道"指令从什么变成什么"会把 prompt 泄漏到日志里。
  *
  * @param field
  *   稳定的属性名，可作为 metric 维度与文档锚点
  * @param kind
  *   该属性所属的能力类别；用于把 drift 归因到具体装配面
  * @param securityRelevant
  *   变化是否直接改变安全边界。安全相关的变化永远是 `Incompatible`，不允许降级为"重新校验"
  */
final case class CompositionDriftField(
    field: String,
    kind: CapabilityKind,
    securityRelevant: Boolean
) derives JsonCodec

/** 冻结组合与现场组合的比较结果。
  *
  * 两个非兼容分支携带**全部**变化项而不是第一个：一次部署常常同时改动多项，只报第一项会让运维修掉它以后再撞第二次。
  */
enum CompositionDrift derives JsonCodec:
  case Compatible

  /** 只有非安全相关的元数据变化；仍然 fail-closed，但可解释为"需要重新确认"而不是"能力已被替换"。 */
  case RequiresRevalidation(changed: Chunk[CompositionDriftField])

  /** 至少一项安全相关能力已变化，拒绝按原 Run 静默继续。 */
  case Incompatible(changed: Chunk[CompositionDriftField])

object CompositionDrift:
  extension (drift: CompositionDrift)
    /** 变化项列表；`Compatible` 为空。 */
    def changedFields: Chunk[CompositionDriftField] = drift match
      case CompositionDrift.Compatible                    => Chunk.empty
      case CompositionDrift.RequiresRevalidation(changed) => changed
      case CompositionDrift.Incompatible(changed)         => changed

    /** 供错误信息与事件使用的低敏摘要；只有属性名，没有取值。 */
    def explain: String =
      val names = drift.changedFields.map(_.field).toList.sorted
      drift match
        case CompositionDrift.Compatible              => "组合未变化"
        case CompositionDrift.RequiresRevalidation(_) =>
          s"组合元数据已变化（${names.mkString("、")}），需要重新确认后才能继续"
        case CompositionDrift.Incompatible(_) =>
          s"组合能力已变化（${names.mkString("、")}），拒绝按原 Run 静默继续"

    /** metric 与事件使用的稳定 kind 编码。 */
    def code: String = drift match
      case CompositionDrift.Compatible              => "compatible"
      case CompositionDrift.RequiresRevalidation(_) => "requires-revalidation"
      case CompositionDrift.Incompatible(_)         => "incompatible"

/** 当前进程装配的组合读出口。
  *
  * 存在的理由是"冻结规则只能有一份实现"：Run 创建、恢复门禁与管理面 diff 如果各自拼装 `RuntimeComposition.freeze` 的七个参数，
  * 任何一处漏传（例如忘了权限指纹）都会让创建与恢复互相拒绝，或者让管理面显示一份不存在的漂移。这里把那组参数固定成一个服务。
  */
trait LiveComposition:
  /** 当前进程对给定 Agent 定义会冻结出的组合指纹。 */
  def freeze(agent: AgentDefinition): RuntimeCompositionFingerprint

  /** 带显式生效模型设置的现场指纹；用于单次模型调用前的比较。
    *
    * 价格表是否进入指纹由本实现按"路由是否启用"决定，与 [[freeze]] 保持同一口径；调用方只需交出价格表本身。
    */
  def fingerprint(
      agent: AgentDefinition,
      effectiveModel: ModelSettings,
      prices: ModelPriceBook
  ): RuntimeCompositionFingerprint

  /** 生效模型设置：把部署侧模型策略应用到 Agent 声明上。 */
  def effectiveModelSettings(agent: AgentDefinition): ModelSettings

object LiveComposition:
  /** 从装配事实构造。`sourceIds`/`extensionIds` 等按名传入，因为它们随部署变化而不是随 Run 变化。 */
  def make(
      profile: RuntimeProfile,
      modelPolicies: ModelPolicySource,
      sourceIds: => Chunk[CapabilityRef],
      extensionIds: => Chunk[CapabilityRef],
      executionEnvironmentId: => String,
      permissionProfileFingerprint: => String
  ): LiveComposition = new LiveComposition:
    def freeze(agent: AgentDefinition): RuntimeCompositionFingerprint =
      RuntimeComposition.freeze(
        profile,
        agent,
        modelPolicies,
        sourceIds,
        extensionIds,
        executionEnvironmentId,
        permissionProfileFingerprint
      )

    def fingerprint(
        agent: AgentDefinition,
        effectiveModel: ModelSettings,
        prices: ModelPriceBook
    ): RuntimeCompositionFingerprint =
      RuntimeComposition.fingerprint(
        profile,
        agent,
        effectiveModel,
        sourceIds,
        extensionIds,
        executionEnvironmentId,
        permissionProfileFingerprint,
        Option.when(profile.modelRouting.nonEmpty)(prices.fingerprint)
      )

    def effectiveModelSettings(agent: AgentDefinition): ModelSettings =
      modelPolicies.current().applyTo(agent.modelSettings)

object RuntimeComposition:
  /** 用当前 Profile 与生效模型覆盖冻结一次组合。不读取用户消息。 */
  def freeze(
      profile: RuntimeProfile,
      agent: AgentDefinition,
      modelPolicies: ModelPolicySource,
      sourceIds: Chunk[CapabilityRef] = Chunk.empty,
      extensionIds: Chunk[CapabilityRef] = Chunk.empty,
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
      sourceIds: Chunk[CapabilityRef] = Chunk.empty,
      extensionIds: Chunk[CapabilityRef] = Chunk.empty,
      executionEnvironmentId: String = "local",
      permissionProfileFingerprint: String = "",
      modelPricingFingerprint: Option[String] = None
  ): RuntimeCompositionFingerprint =
    val tools         = Chunk.fromIterable(agent.allowedTools.toList.sorted)
    val sources       = CapabilityRef.normalize(sourceIds)
    val extensions    = CapabilityRef.normalize(extensionIds)
    val instructionFp = agent.instructionSet.map(_.fingerprint)
    val modelRef      = modelSettingsFingerprint(effectiveModel)
    // 哈希材料里的来源用 `kind:id@version`：同名 id 换了能力类别必须算作不同组合。
    val material = List(
      profile.id,
      profile.capturePolicy.toString,
      instructionFp.getOrElse(""),
      tools.mkString(","),
      modelRef,
      sources.map(ref => s"${ref.kind}:${ref.sourceId}").mkString(",")
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
    val changed = Chunk.fromIterable(
      List(
        // 安全相关：这些属性直接决定"允许发生什么副作用"或"用什么身份发生"。
        drifted(
          "requiredToolsMissing",
          CapabilityKind.Tool,
          securityRelevant = true,
          requiredToolNames.exists(!liveToolNames.contains(_))
        ),
        drifted(
          "extensionIds",
          CapabilityKind.Observer,
          securityRelevant = true,
          frozen.extensionIds.toSet != live.extensionIds.toSet
        ),
        drifted(
          "executionEnvironmentId",
          CapabilityKind.ExecutionEnvironment,
          securityRelevant = true,
          frozen.executionEnvironmentId != live.executionEnvironmentId
        ),
        drifted(
          "permissionProfileFingerprint",
          CapabilityKind.ExecutionEnvironment,
          securityRelevant = true,
          permissionsOf(frozen) != permissionsOf(live)
        ),
        drifted(
          "modelRoutingFingerprint",
          CapabilityKind.Model,
          securityRelevant = true,
          frozen.modelRoutingFingerprint != live.modelRoutingFingerprint
        ),
        // 价格表变化不改变权限，但会让同一个 Run 混用两份计价合同，因此同样不接受静默继续。
        drifted(
          "modelPricingFingerprint",
          CapabilityKind.Model,
          securityRelevant = true,
          frozen.modelPricingFingerprint != live.modelPricingFingerprint
        ),
        drifted(
          "instructionFingerprint",
          CapabilityKind.Model,
          securityRelevant = true,
          frozen.instructionFingerprint != live.instructionFingerprint
        ),
        drifted(
          "modelRef",
          CapabilityKind.Model,
          securityRelevant = true,
          frozen.modelRef != live.modelRef
        ),
        drifted(
          "allowedTools",
          CapabilityKind.Tool,
          securityRelevant = true,
          frozen.allowedTools.toSet != live.allowedTools.toSet
        ),
        drifted(
          "sourceIds",
          CapabilityKind.Context,
          securityRelevant = true,
          frozen.sourceIds.toSet != live.sourceIds.toSet
        ),
        // 非安全相关：装配身份与采集档位变了，需要人确认，但没有替换任何能力。
        drifted(
          "profileId",
          CapabilityKind.Observer,
          securityRelevant = false,
          frozen.profileId != live.profileId
        ),
        drifted(
          "capturePolicy",
          CapabilityKind.Observer,
          securityRelevant = false,
          frozen.capturePolicy != live.capturePolicy
        )
      ).flatten
    )
    if changed.isEmpty then CompositionDrift.Compatible
    else if changed.exists(_.securityRelevant) then CompositionDrift.Incompatible(changed)
    else CompositionDrift.RequiresRevalidation(changed)

  private def drifted(
      field: String,
      kind: CapabilityKind,
      securityRelevant: Boolean,
      condition: Boolean
  ): Option[CompositionDriftField] =
    Option.when(condition)(CompositionDriftField(field, kind, securityRelevant))

  /** 对完整生效 ModelSettings 取规范摘要，覆盖 provider/model、采样、输出上限、tool choice 与扩展选项。 */
  def modelSettingsFingerprint(settings: ModelSettings): String =
    val material = Json.Obj(
      "provider"        -> Json.Str(settings.provider.getOrElse("")),
      "model"           -> Json.Str(settings.model.getOrElse("")),
      "temperature"     -> Json.Str(settings.temperature.fold("")(_.toString)),
      "maxOutputTokens" -> Json.Str(settings.maxOutputTokens.fold("")(_.toString)),
      "reasoningEffort" -> Json.Str(settings.reasoningEffort.fold("")(_.toString)),
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
      sourceIds: Chunk[CapabilityRef] = Chunk.empty,
      extensionIds: Chunk[CapabilityRef] = Chunk.empty,
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
    ) ++ (sourceIds ++ extensionIds).map(ref => CapabilityDescriptor(ref.kind, ref.id, Some(ref.version)))

  private def permissionsOf(fingerprint: RuntimeCompositionFingerprint): String =
    Option(fingerprint.permissionProfileFingerprint)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(PermissionProfile.hostFingerprint)
end RuntimeComposition
