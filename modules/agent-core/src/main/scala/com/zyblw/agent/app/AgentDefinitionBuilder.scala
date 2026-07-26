package com.zyblw.agent.app

import com.zyblw.agent.core.*
import com.zyblw.agent.tools.ToolPolicyConfig
import zio.*

/** 不可变、可逐步组合的 Agent 定义 Builder。
  *
  * `AgentDefinition` 保持轻量纯数据，方便 JSON 快照和数据库恢复；启动期易错配置则由本 Builder 集中校验。每个 `with*` 方法都返回新
  * Builder，因此可以安全地把公共基线复用给多个业务 Agent，不会出现可变 Builder 被并发修改的问题。
  *
  * @param id
  *   Agent 发布后保持稳定的协议 ID
  * @param displayName
  *   用户界面和运维页面展示的名称
  * @param agentInstructions
  *   最高优先级业务指令；在 `build` 前必须显式设置
  * @param tools
  *   模型可见的类型化工具名称；空集合代表默认拒绝全部工具
  * @param settings
  *   Provider-neutral 模型参数；具体协议仍会在 Adapter 边界做能力校验
  * @param context
  *   Context 总量、分区、压缩和工具结果硬策略
  * @param agentMetadata
  *   低敏版本/展示元数据；不得保存密钥、令牌或业务正文
  * @param systemInstructions
  *   框架/安全策略拥有的附加 System 指令块
  * @param developerInstructions
  *   业务应用拥有的 Developer 指令块
  */
final case class AgentDefinitionBuilder private (
    id: AgentId,
    displayName: String,
    agentInstructions: Option[String],
    tools: Set[ToolName],
    settings: ModelSettings,
    context: ContextPolicy,
    agentMetadata: Map[String, String],
    systemInstructions: Chunk[InstructionBlock],
    developerInstructions: Chunk[InstructionBlock]
):

  /** 设置 Agent 指令；空白或过大的文本在 build 时返回 typed configuration error。 */
  def withInstructions(value: String): AgentDefinitionBuilder = copy(agentInstructions = Some(value))

  /** 增加一段最高优先级 System 策略。
    *
    * 适合安全边界、框架不变量和合规要求；普通业务语气或领域规则应使用 `addDeveloperInstruction`，避免所有内容都膨胀为 最高权限 Prompt。
    */
  def addSystemInstruction(id: String, version: String, content: String): AgentDefinitionBuilder =
    copy(systemInstructions =
      systemInstructions :+ InstructionBlock(id, InstructionAuthority.System, content, version)
    )

  /** 增加一段业务应用拥有的 Developer 指令。
    *
    * 独立 id/version 使 Prompt 修改可以进入评测与 Trace，而不需要记录指令正文。
    */
  def addDeveloperInstruction(id: String, version: String, content: String): AgentDefinitionBuilder =
    copy(
      developerInstructions =
        developerInstructions :+ InstructionBlock(id, InstructionAuthority.Developer, content, version)
    )

  /** 增加一个模型可见工具；Set 使重复调用保持幂等。 */
  def allowTool(name: ToolName): AgentDefinitionBuilder = copy(tools = tools + name)

  /** 增加一组模型可见工具；最终输出按 Set 语义去重。 */
  def allowTools(names: Iterable[ToolName]): AgentDefinitionBuilder = copy(tools = tools ++ names)

  /** 移除一个工具，便于从公共 Agent 基线派生更小权限版本。 */
  def denyTool(name: ToolName): AgentDefinitionBuilder = copy(tools = tools - name)

  /** 完整替换 Provider-neutral 模型设置。 */
  def withModelSettings(value: ModelSettings): AgentDefinitionBuilder = copy(settings = value)

  /** 设置 Provider 路由 ID，而不绑定任何具体 SDK 类型。 */
  def withProvider(provider: ProviderId): AgentDefinitionBuilder =
    copy(settings = settings.copy(provider = Some(provider.value)))

  /** 设置 Provider 范围内模型 ID。 */
  def withModel(model: ModelId): AgentDefinitionBuilder =
    copy(settings = settings.copy(model = Some(model.value)))

  /** 设置 Context 分区和压缩策略；策略构造器自身已经校验预算不变量。 */
  def withContextPolicy(value: ContextPolicy): AgentDefinitionBuilder = copy(context = value)

  /** 增加一项低敏元数据，例如 `version -> 2026-07`。 metadata 不参与权限判断；安全字段校验集中在 build 阶段，避免不同调用顺序产生不一致结果。
    */
  def withMetadata(key: String, value: String): AgentDefinitionBuilder =
    copy(agentMetadata = agentMetadata.updated(key, value))

  /** 仅执行 AgentDefinition 自身校验。
    *
    * 适合先构建定义、稍后再选择部署策略的配置加载器。生产启动更推荐 `buildFor(toolPolicy)`，同时检测 Agent 白名单和全局 执行白名单是否漂移。
    */
  def build: IO[AgentError.InvalidConfiguration, AgentDefinition] =
    for
      valid        <- validate
      instructions <- InstructionSet.make(
        Chunk(
          InstructionBlock(
            id = "agent.core",
            authority = InstructionAuthority.System,
            content = valid.instructions,
            version = "1"
          )
        ) ++ systemInstructions ++ developerInstructions
      )
    yield toDefinition(valid, instructions)

  /** 校验并构建可在指定全局工具策略下运行的定义。
    *
    * Agent 允许列表必须是全局执行白名单的子集。若只在 AgentDefinition 中声明工具，模型能够提出调用，但 Runtime 最终 会安全拒绝；启动期提前失败能避免这种配置直到生产请求才暴露。
    *
    * @param toolPolicy
    *   当前应用传给 AgentRuntime 和 AgentCommandService 的同一份工具硬策略
    */
  def buildFor(toolPolicy: ToolPolicyConfig): IO[AgentError.InvalidConfiguration, AgentDefinition] =
    for
      definition <- build
      missing = tools -- toolPolicy.allowedTools
      _ <- ZIO
        .fail(
          AgentError.InvalidConfiguration(
            s"Agent ${id.value} 的工具白名单超出全局 ToolPolicyConfig: ${missing.toList.map(_.value).sorted.mkString(",")}"
          )
        )
        .when(missing.nonEmpty)
    yield definition

  /** 把已规范化字段转换为最终可持久化定义。 */
  private def toDefinition(
      valid: AgentDefinitionBuilder.Validated,
      instructions: InstructionSet
  ): AgentDefinition = AgentDefinition(
    id = id,
    name = valid.name,
    instructions = valid.instructions,
    allowedTools = tools.map(_.value),
    modelSettings = settings,
    contextPolicy = context,
    metadata = valid.metadata,
    instructionSet = Some(instructions)
  )

  /** 集中执行所有可能由配置文件或环境变量引入的字符串/数值校验。 */
  private def validate: IO[AgentError.InvalidConfiguration, AgentDefinitionBuilder.Validated] =
    val normalizedName         = displayName.trim
    val normalizedInstructions = agentInstructions.map(_.trim).getOrElse("")
    val normalizedMetadata     = agentMetadata.map((key, value) => key.trim -> value.trim)
    val invalidTool            = tools.find { name =>
      val value = name.value
      value.length > 200 || value.exists(_.isControl)
    }
    val duplicateMetadataKeys = normalizedMetadata.size != agentMetadata.size
    val sensitiveKeys         =
      Set("api_key", "apikey", "authorization", "password", "secret", "access_token", "refresh_token")
    val forbiddenKey    = normalizedMetadata.keys.find(key => sensitiveKeys.contains(key.toLowerCase))
    val invalidMetadata = normalizedMetadata.find { case (key, value) =>
      key.isEmpty || key.length > 100 || value.length > 500 || key.exists(_.isControl) || value.exists(
        _.isControl
      )
    }
    val invalidTemperature = settings.temperature.exists(value => !value.isFinite || value < 0.0)
    val invalidOutputLimit = settings.maxOutputTokens.exists(_ <= 0)
    val validationError    =
      Option
        .when(id.value.length > 200 || id.value.exists(_.isControl))("Agent id 不能超过 200 字符或包含控制字符")
        .orElse(
          Option.when(normalizedName.isEmpty || normalizedName.length > 200)("Agent name 长度必须位于 1..200")
        )
        .orElse(
          Option.when(normalizedInstructions.isEmpty || normalizedInstructions.length > 100_000)(
            "Agent instructions 长度必须位于 1..100000"
          )
        )
        .orElse(invalidTool.map(name => s"工具名非法或超过 200 字符: ${name.value.take(80)}"))
        .orElse(Option.when(agentMetadata.size > 32)("Agent metadata 最多允许 32 项"))
        .orElse(Option.when(duplicateMetadataKeys)("Agent metadata key 规范化后发生冲突"))
        .orElse(forbiddenKey.map(key => s"Agent metadata 禁止保存敏感字段: $key"))
        .orElse(invalidMetadata.map(_ => "Agent metadata key/value 为空、过长或含控制字符"))
        .orElse(Option.when(invalidTemperature)("模型 temperature 必须是非负有限数"))
        .orElse(Option.when(invalidOutputLimit)("模型 maxOutputTokens 必须大于零"))
    validationError match
      case Some(message) => ZIO.fail(AgentError.InvalidConfiguration(message))
      case None          =>
        ZIO.succeed(
          AgentDefinitionBuilder.Validated(normalizedName, normalizedInstructions, normalizedMetadata)
        )

object AgentDefinitionBuilder:
  /** Builder 校验后的内部规范化值，避免 build 时再次 trim 导致快照漂移。 */
  final private case class Validated(name: String, instructions: String, metadata: Map[String, String])

  /** 创建最小 Builder；调用方随后必须使用 `withInstructions`。
    * @param id
    *   稳定 Agent ID
    * @param name
    *   业务展示名称
    */
  def apply(id: AgentId, name: String): AgentDefinitionBuilder = AgentDefinitionBuilder(
    id = id,
    displayName = name,
    agentInstructions = None,
    tools = Set.empty,
    settings = ModelSettings(),
    context = ContextPolicy(),
    agentMetadata = Map.empty,
    systemInstructions = Chunk.empty,
    developerInstructions = Chunk.empty
  )
