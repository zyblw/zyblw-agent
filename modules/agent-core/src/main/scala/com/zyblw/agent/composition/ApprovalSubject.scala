package com.zyblw.agent.composition

import com.zyblw.agent.core.*
import com.zyblw.agent.execution.PermissionProfile
import com.zyblw.agent.tools.*
import zio.json.*
import zio.json.ast.Json

/** 一次副作用实际发生所处的执行环境身份。
  *
  * 审批必须绑定环境：同一条命令在宿主进程、受限容器或远端执行器中的攻击面并不相同，「批准过这个命令」不等于「批准它在 任何地方运行」。取值由装配的
  * [[com.zyblw.agent.execution.ExecutionEnvironment]] 提供；环境或权限剖面变化会使历史审批失效。
  */
opaque type ExecutionEnvironmentId = String

object ExecutionEnvironmentId:
  /** 宿主进程内直接执行，除工具自身实现外没有额外的文件系统、网络或进程隔离。 */
  val local: ExecutionEnvironmentId = "local"

  /** 现有 MCP workspace / OCI sandbox 适配器。与 `local` 不是同一个审批主体。 */
  val mcpSandbox: ExecutionEnvironmentId = "mcp-sandbox"

  /** 安全解析配置或持久化中的环境标识。 */
  def fromString(value: String): Either[String, ExecutionEnvironmentId] =
    Option(value).map(_.trim).filter(_.nonEmpty).toRight("ExecutionEnvironmentId 不能为空")

  extension (id: ExecutionEnvironmentId) def value: String = id
  given JsonCodec[ExecutionEnvironmentId] = JsonCodec.string.transformOrFail(fromString, _.value)

/** 规范化工具输入的摘要。
  *
  * 只保存摘要而不保存参数正文，因此工具参数中的密钥、个人信息不会因为审批链路而落到额外的持久化位置；同时任何一个字节的 参数改动都会让审批主体改变。
  */
final case class CanonicalInputFingerprint(value: String) derives JsonCodec:
  require(value.matches("[0-9a-f]{64}"), "输入指纹必须是 SHA-256 十六进制")

object CanonicalInputFingerprint:
  /** 对工具参数取与字段书写顺序无关的摘要。 */
  def of(arguments: Json): CanonicalInputFingerprint =
    CanonicalInputFingerprint(CanonicalDigest.sha256(CanonicalDigest.canonicalize(arguments).toJson))

/** 判定该调用是否需要审批时所依据的生效治理配置摘要。
  *
  * 只覆盖与「是否需要人工授权」直接相关的字段：审批策略本身，以及该能力当时是否在显式白名单内。超时、并发度、结果上限等 执行治理项不进入摘要——它们在每次执行时都会重新强制，把它们绑进审批只会制造无意义的重复审批。
  */
final case class ApprovalPolicyFingerprint(value: String) derives JsonCodec:
  require(value.matches("[0-9a-f]{64}"), "审批策略指纹必须是 SHA-256 十六进制")

object ApprovalPolicyFingerprint:
  def of(config: ToolPolicyConfig, capability: ToolName): ApprovalPolicyFingerprint =
    val material = Json.Obj(
      "approvalPolicy" -> Json.Str(config.approvalPolicy.toString),
      "allowed"        -> Json.Bool(config.allowedTools.contains(capability)),
      "denied"         -> Json.Bool(config.deniedTools.contains(capability))
    )
    ApprovalPolicyFingerprint(CanonicalDigest.sha256(CanonicalDigest.canonicalize(material).toJson))

/** 提出该调用的可信授权上下文摘要。
  *
  * 覆盖租户、主体与已授予 scope：换一个租户、换一个用户或换一组权限之后，先前那次人工授权就不再是对同一件事的授权。 [[RunContext.attributes]]
  * 不参与，因为它是展示与追踪用的低敏元数据，不是授权输入。
  */
final case class AuthorizationFingerprint(value: String) derives JsonCodec:
  require(value.matches("[0-9a-f]{64}"), "授权上下文指纹必须是 SHA-256 十六进制")

object AuthorizationFingerprint:
  def of(context: RunContext): AuthorizationFingerprint =
    val material = Json.Obj(
      "tenantId" -> context.tenantId.fold(Json.Null: Json)(Json.Str(_)),
      "userId"   -> context.userId.fold(Json.Null: Json)(Json.Str(_)),
      "scopes"   -> CanonicalDigest.strings(context.scopes)
    )
    AuthorizationFingerprint(CanonicalDigest.sha256(CanonicalDigest.canonicalize(material).toJson))

/** 一次人工审批所授权的**具体副作用**，而不是一个工具名。
  *
  * 既有实现把审批绑定在 `callId` 上：只要历史里出现过对同一 callId 的批准，后续执行就被视为已授权。这在两个方向上都不够： `callId` 来自 Provider
  * 响应，模型可以在后续轮次复用同一个 ID 提出不同参数的调用；而部署侧的工具契约、审批策略或调用者 权限发生变化时，旧的批准也会静默继续生效。
  *
  * 审批主体把授权判定收敛成一次结构相等比较：只要能力、调用位置、工具契约、规范化输入、执行环境、授权上下文、生效审批策略、 风险或副作用等级中的任意一项发生变化，先前的批准就不再匹配，Runtime
  * 会在副作用之前重新请求人工授权。它是既有 `callId` 门禁的**超集**，不是另一套并行的审批系统。
  *
  * @param capability
  *   被授权的工具名
  * @param callId
  *   计划内该副作用的稳定位置
  * @param toolContract
  *   审批时工具的 Schema 与安全元数据摘要
  * @param input
  *   审批时规范化参数的摘要
  * @param environment
  *   副作用实际发生的执行环境
  * @param permissions
  *   该环境下生效的权限剖面；子 scope 只能收窄
  * @param authorization
  *   提出调用的租户、主体与已授予 scope 摘要
  * @param policy
  *   判定需要审批时的生效治理配置摘要
  * @param risk
  *   工具作者声明的风险等级
  * @param sideEffect
  *   工具作者声明的副作用等级
  */
final case class ApprovalSubject(
    capability: String,
    callId: String,
    toolContract: ToolContractFingerprint,
    input: CanonicalInputFingerprint,
    environment: ExecutionEnvironmentId,
    permissions: PermissionProfile = PermissionProfile.host,
    authorization: AuthorizationFingerprint,
    policy: ApprovalPolicyFingerprint,
    risk: ToolRisk,
    sideEffect: SideEffect
) derives JsonCodec:
  require(capability.trim.nonEmpty, "审批主体的能力名不能为空")
  require(callId.trim.nonEmpty, "审批主体的 callId 不能为空")

  /** 全部绑定属性的稳定摘要。
    *
    * 授权判定用的是本类的结构相等，`value` 只用于日志、事件和审计中的紧凑引用——它不参与判定，因此摘要碰撞不会放宽授权。
    */
  lazy val value: String =
    val material = Json.Obj(
      "capability"    -> Json.Str(capability),
      "callId"        -> Json.Str(callId),
      "toolContract"  -> Json.Str(toolContract.value),
      "input"         -> Json.Str(input.value),
      "environment"   -> Json.Str(environment.value),
      "permissions"   -> Json.Str(permissions.fingerprint),
      "authorization" -> Json.Str(authorization.value),
      "policy"        -> Json.Str(policy.value),
      "risk"          -> Json.Str(risk.toString),
      "sideEffect"    -> Json.Str(sideEffect.toString)
    )
    CanonicalDigest.sha256(CanonicalDigest.canonicalize(material).toJson)

  /** 说明本主体与另一主体在哪些绑定属性上不同；空列表表示两者可互相授权。
    *
    * 只返回属性名，不返回任何一侧的取值，因此可以安全进入面向运维的错误信息与事件。
    */
  def driftFrom(other: ApprovalSubject): List[String] =
    List(
      Option.when(capability != other.capability)("capability"),
      Option.when(callId != other.callId)("callId"),
      Option.when(toolContract != other.toolContract)("toolContract"),
      Option.when(input != other.input)("input"),
      Option.when(environment != other.environment)("environment"),
      Option.when(permissions.fingerprint != other.permissions.fingerprint)("permissions"),
      Option.when(authorization != other.authorization)("authorization"),
      Option.when(policy != other.policy)("policy"),
      Option.when(risk != other.risk)("risk"),
      Option.when(sideEffect != other.sideEffect)("sideEffect")
    ).flatten

object ApprovalSubject:
  /** 用一次调用当时的工具契约、治理配置与可信授权上下文构造审批主体。
    *
    * @param call
    *   模型提出的调用；`arguments` 仍是不可信输入，这里只取它的摘要
    * @param metadata
    *   已注册工具的安全元数据；模型不能修改
    * @param toolContract
    *   同一时刻对该工具计算的契约指纹
    * @param policy
    *   判定审批需求时读取的治理配置摘要
    * @param authorization
    *   Run 的可信授权上下文摘要
    * @param environment
    *   副作用将要发生的执行环境
    * @param permissions
    *   该环境的权限剖面；缺省为宿主进程权限
    */
  def of(
      call: ToolCall,
      metadata: ToolMetadata,
      toolContract: ToolContractFingerprint,
      policy: ApprovalPolicyFingerprint,
      authorization: AuthorizationFingerprint,
      environment: ExecutionEnvironmentId = ExecutionEnvironmentId.local,
      permissions: PermissionProfile = PermissionProfile.host
  ): ApprovalSubject =
    ApprovalSubject(
      capability = call.name,
      callId = call.id,
      toolContract = toolContract,
      input = CanonicalInputFingerprint.of(call.arguments),
      environment = environment,
      permissions = permissions,
      authorization = authorization,
      policy = policy,
      risk = metadata.risk,
      sideEffect = metadata.sideEffect
    )
