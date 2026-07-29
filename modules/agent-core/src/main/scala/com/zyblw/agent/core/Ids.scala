package com.zyblw.agent.core

import java.util.UUID
import zio.*
import zio.json.*

/** 框架中的标识符均使用 opaque type，避免在编译期把 runId、tenantId、toolName 等字符串混用。
  *
  * 对外部输入优先调用 `fromString`；`apply` 只用于应用内部已经完成校验的常量。
  */
opaque type RunId = UUID
object RunId:
  /** 包装已经可信的 UUID；外部字符串请使用 `fromString`。 */
  def apply(value: UUID): RunId = value

  /** 使用 ZIO `Random` 生成新 Run ID，因而可在测试中替换随机服务。 */
  def random: UIO[RunId] = Random.nextUUID.map(RunId(_))

  /** 解析外部 UUID 文本，失败时返回中文校验错误而不是抛异常。 */
  def fromString(value: String): Either[String, RunId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"非法 RunId: $value")
  extension (id: RunId)
    /** 取出底层 UUID，仅供 JDBC/协议适配层使用。 */
    def value: UUID = id

    /** 输出标准 UUID 字符串，用于日志、安全 URL 参数和 JSON。 */
    def asString: String = id.toString
  given JsonCodec[RunId] = JsonCodec.string.transformOrFail(fromString, _.asString)

/** 耐久控制命令的唯一标识。
  *
  * CommandId 与 RunId 分开建模，因为同一个 Run 会先后接收恢复、审批、取消等多条命令；把两者都表示成 UUID 但不使用不同 opaque type，会让 JDBC、HTTP
  * 和调度代码很容易把“命令”误当成“运行”。
  */
opaque type CommandId = UUID
object CommandId:
  /** 包装框架内部已经可信的 UUID。 */
  def apply(value: UUID): CommandId = value

  /** 使用可替换的 ZIO Random 生成命令 ID，便于确定性测试。 */
  def random: UIO[CommandId] = Random.nextUUID.map(CommandId(_))

  /** 解析 HTTP 或数据库返回的 UUID 文本。 */
  def fromString(value: String): Either[String, CommandId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"非法 CommandId: $value")
  extension (id: CommandId)
    /** 暴露底层 UUID 给 JDBC 适配层。 */
    def value: UUID = id

    /** 输出标准 UUID 字符串。 */
    def asString: String = id.toString
  given JsonCodec[CommandId] = JsonCodec.string.transformOrFail(fromString, _.asString)

opaque type SessionId = UUID
object SessionId:
  /** 包装可信 UUID 为会话 ID。 */
  def apply(value: UUID): SessionId = value

  /** 生成随机会话 ID；返回 UIO 表示无业务失败。 */
  def random: UIO[SessionId] = Random.nextUUID.map(SessionId(_))

  /** 从外部文本安全解析会话 ID。 */
  def fromString(value: String): Either[String, SessionId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"非法 SessionId: $value")
  extension (id: SessionId)
    /** 暴露底层 UUID 给持久化适配器。 */
    def value: UUID = id

    /** 返回标准 UUID 文本。 */
    def asString: String = id.toString
  given JsonCodec[SessionId] = JsonCodec.string.transformOrFail(fromString, _.asString)

/** 业务会话的稳定线程标识；它可以来自数据库主键或外部会话 ID，因此不强制 UUID。 */
opaque type ThreadId = String
object ThreadId:
  /** 去除首尾空白并拒绝空线程标识。 */
  def apply(value: String): ThreadId = requireNonBlank("ThreadId", value)

  /** 安全解析 HTTP、配置或数据库外部字符串，不把校验失败提升为 Fiber defect。
    * @param value
    *   尚未信任的线程标识
    * @return
    *   trim 后的 ThreadId，或可安全返回给调用方的校验错误
    */
  def fromString(value: String): Either[String, ThreadId] = parseNonBlank("ThreadId", value)

  /** 取出经过校验的业务线程字符串。 */
  extension (id: ThreadId) def value: String = id
  given JsonCodec[ThreadId]                  = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type AgentId = String
object AgentId:
  /** 构造非空 Agent 标识；该值应在业务 Agent 注册表中稳定不变。 */
  def apply(value: String): AgentId = requireNonBlank("AgentId", value)

  /** 安全解析 URL/配置中的 Agent ID，避免 `require` 成为未捕获缺陷。 */
  def fromString(value: String): Either[String, AgentId] = parseNonBlank("AgentId", value)

  /** 取出底层字符串用于配置、持久化和路由。 */
  extension (id: AgentId) def value: String = id
  given JsonCodec[AgentId]                  = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type ToolName = String
object ToolName:
  /** 构造非空工具名；名称一旦发布就应视作协议字段。 */
  def apply(value: String): ToolName = requireNonBlank("ToolName", value)

  /** 安全解析 Provider、MCP 或配置提供的工具名称。 */
  def fromString(value: String): Either[String, ToolName] = parseNonBlank("ToolName", value)

  /** 取出底层工具名。 */
  extension (id: ToolName) def value: String = id
  given JsonCodec[ToolName]                  = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type ToolCallId = String
object ToolCallId:
  /** 包装 Provider 返回或框架生成的稳定工具调用 ID。 */
  def apply(value: String): ToolCallId = requireNonBlank("ToolCallId", value)

  /** 安全解析不可信 Provider 响应中的调用 ID。 */
  def fromString(value: String): Either[String, ToolCallId] = parseNonBlank("ToolCallId", value)

  /** 取出底层调用 ID，用于结果关联和幂等键。 */
  extension (id: ToolCallId) def value: String = id
  given JsonCodec[ToolCallId]                  = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type ProviderId = String
object ProviderId:
  /** 构造 Provider 路由 ID，例如 `deepseek` 或 `openai`。 */
  def apply(value: String): ProviderId = requireNonBlank("ProviderId", value)

  /** 安全解析配置或远端能力声明中的 Provider ID。 */
  def fromString(value: String): Either[String, ProviderId] = parseNonBlank("ProviderId", value)

  /** 取出 Provider 字符串。 */
  extension (id: ProviderId) def value: String = id
  given JsonCodec[ProviderId]                  = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type ModelId = String
object ModelId:
  /** 构造 Provider 范围内的模型 ID。 */
  def apply(value: String): ModelId = requireNonBlank("ModelId", value)

  /** 安全解析配置、路由策略或 Provider 返回的模型 ID。 */
  def fromString(value: String): Either[String, ModelId] = parseNonBlank("ModelId", value)

  /** 取出传给 Provider 的模型字符串。 */
  extension (id: ModelId) def value: String = id
  given JsonCodec[ModelId]                  = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type TenantId = String
object TenantId:
  /** 构造租户 ID；所有跨租户存储和检索必须显式携带它。 */
  def apply(value: String): TenantId = requireNonBlank("TenantId", value)

  /** 安全解析已经验签的租户 claim 或数据库值。 */
  def fromString(value: String): Either[String, TenantId] = parseNonBlank("TenantId", value)

  /** 取出底层租户字符串。 */
  extension (id: TenantId) def value: String = id
  given JsonCodec[TenantId]                  = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type UserId = String
object UserId:
  /** 构造业务用户 ID。 */
  def apply(value: String): UserId = requireNonBlank("UserId", value)

  /** 安全解析已经验签的用户 claim 或数据库值。 */
  def fromString(value: String): Either[String, UserId] = parseNonBlank("UserId", value)

  /** 取出底层用户字符串。 */
  extension (id: UserId) def value: String = id
  given JsonCodec[UserId]                  = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type PromptId = String
object PromptId:
  /** 构造稳定提示词版本标识，便于评测和审计。 */
  def apply(value: String): PromptId = requireNonBlank("PromptId", value)

  /** 安全解析配置、注册表或持久化中的提示词版本 ID。 */
  def fromString(value: String): Either[String, PromptId] = parseNonBlank("PromptId", value)

  /** 取出提示词标识字符串。 */
  extension (id: PromptId) def value: String = id
  given JsonCodec[PromptId]                  = JsonCodec.string.transformOrFail(fromString, _.value)

/** Artifact 在其隔离域内的稳定名称。
  *
  * Artifact 不是文件系统路径：禁止根路径、反斜杠和 `.`/`..` 段，避免调用方把模型生成的名称直接传给本地或对象存储 Adapter 时产生路径逃逸。
  */
opaque type ArtifactName = String
object ArtifactName:
  /** 构造已经校验的 Artifact 名称；外部输入优先使用 `fromString`。 */
  def apply(value: String): ArtifactName =
    fromString(value).fold(message => throw new IllegalArgumentException(message), identity)

  /** 规范化并校验外部 Artifact 名称。 */
  def fromString(value: String): Either[String, ArtifactName] =
    val normalized = value.trim
    Either.cond(
      normalized.nonEmpty &&
        normalized.length <= 255 &&
        !normalized.startsWith("/") &&
        !normalized.contains('\\') &&
        normalized.split('/').forall(segment => segment.nonEmpty && segment != "." && segment != "..") &&
        !normalized.exists(_.isControl),
      normalized,
      "ArtifactName 必须是长度 1..255 的相对名称，且不能包含路径逃逸或控制字符"
    )

  extension (name: ArtifactName) def value: String = name
  given JsonCodec[ArtifactName]                    = JsonCodec.string.transformOrFail(fromString, _.value)

opaque type EventId = UUID
object EventId:
  /** 包装可信 UUID 为事件 ID。 */
  def apply(value: UUID): EventId = value

  /** 生成事件唯一 ID，用于幂等追加。 */
  def random: UIO[EventId] = Random.nextUUID.map(EventId(_))

  /** 安全解析持久化或 API 返回的事件 UUID。 */
  def fromString(value: String): Either[String, EventId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"非法 EventId: $value")

  /** 输出标准 UUID 文本。 */
  extension (id: EventId) def asString: String = id.toString
  given JsonCodec[EventId]                     = JsonCodec.string.transformOrFail(fromString, _.asString)

opaque type Version = Long
object Version:
  val initial: Version = 0L

  /** 构造非负版本号；负值说明持久化数据或调用方存在错误。 */
  def apply(value: Long): Version =
    fromLong(value).fold(message => throw new IllegalArgumentException(message), identity)

  /** 安全解析 JSON、数据库或外部协议中的乐观锁版本。 */
  def fromLong(value: Long): Either[String, Version] =
    Either.cond(value >= 0L, value, "Version 不能为负数")
  extension (version: Version)
    /** 取出用于 SQL 条件和指标的 Long 值。 */
    def value: Long = version

    /** 返回下一个乐观锁版本，不原地修改当前值。 */
    def next: Version = Version(version + 1L)
  given JsonCodec[Version] = JsonCodec.long.transformOrFail(fromLong, _.value)

/** 对字符串型 opaque ID 统一执行 trim 和非空校验。 */
private def requireNonBlank(label: String, value: String): String =
  parseNonBlank(label, value).fold(message => throw new IllegalArgumentException(message), identity)

/** 外部输入使用的非空解析器；与内部 `apply` 共享完全相同的规范化规则。 */
private def parseNonBlank(label: String, value: String): Either[String, String] =
  Option(value).map(_.trim).filter(_.nonEmpty).toRight(s"$label 不能为空")
