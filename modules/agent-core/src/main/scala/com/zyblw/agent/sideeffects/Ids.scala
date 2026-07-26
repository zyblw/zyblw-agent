package com.zyblw.agent.sideeffects

import java.util.UUID
import zio.*
import zio.json.*

/** 一次已经通过本地数据库事务提交的业务写操作标识。
  *
  * 它与 `RunId`、模型 `callId` 不同：同一业务操作可能因客户端重试或新 Run 再次到达，只有业务幂等键才能把这些请求 收敛到同一个 `BusinessOperationId`。
  */
opaque type BusinessOperationId = UUID

object BusinessOperationId:
  /** 包装框架内部已经可信的 UUID。 */
  def apply(value: UUID): BusinessOperationId = value

  /** 生成不可预测的操作标识；只能在确定本次请求是新业务操作后生成。 */
  def random: UIO[BusinessOperationId] = Random.nextUUID.map(BusinessOperationId(_))

  /** 从数据库或 HTTP 边界解析 UUID，格式错误时返回可解释文本。 */
  def fromString(value: String): Either[String, BusinessOperationId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"无效的 BusinessOperationId: $value")

  /** 将 opaque UUID 转成 SQL、日志和 JSON 使用的稳定字符串。 */
  extension (value: BusinessOperationId) def asString: String = value.toString
  given JsonCodec[BusinessOperationId] = JsonCodec.string.transformOrFail(fromString, _.asString)

/** outbox 事件的稳定全局标识；发布重试必须保持不变，供下游 inbox 去重。 */
opaque type OutboxEventId = UUID

object OutboxEventId:
  /** 包装事务内部生成的可信 UUID。 */
  def apply(value: UUID): OutboxEventId = value

  /** 为同一事务中首次创建的 outbox 事件生成标识。 */
  def random: UIO[OutboxEventId] = Random.nextUUID.map(OutboxEventId(_))

  /** 从持久化文本解析事件标识。 */
  def fromString(value: String): Either[String, OutboxEventId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"无效的 OutboxEventId: $value")

  /** 返回 UUID 字符串；该值也应作为传输层 messageId。 */
  extension (value: OutboxEventId) def asString: String = value.toString
  given JsonCodec[OutboxEventId] = JsonCodec.string.transformOrFail(fromString, _.asString)

/** 补偿计划的稳定标识。补偿是新的显式业务动作，不是对历史事务的魔法回滚。 */
opaque type CompensationId = UUID

object CompensationId:
  /** 包装事务内部生成的可信 UUID。 */
  def apply(value: UUID): CompensationId = value

  /** 注册补偿计划时生成标识。 */
  def random: UIO[CompensationId] = Random.nextUUID.map(CompensationId(_))

  /** 从持久化文本解析补偿标识。 */
  def fromString(value: String): Either[String, CompensationId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"无效的 CompensationId: $value")

  /** 返回数据库和审计使用的 UUID 字符串。 */
  extension (value: CompensationId) def asString: String = value.toString
  given JsonCodec[CompensationId] = JsonCodec.string.transformOrFail(fromString, _.asString)

/** 业务系统定义的幂等键。
  *
  * 键应来自业务事实，例如 `publish-article:{draftId}:{version}`，而不是随机 UUID。框架限制长度和空白，避免把无界用户
  * 文本直接放入唯一索引；键本身不得包含密码、Token、病历原文等敏感信息。
  */
opaque type BusinessIdempotencyKey = String

object BusinessIdempotencyKey:
  private val MaxLength = 200

  /** 校验并创建业务幂等键。
    *
    * @param value
    *   由业务工具从已校验输入和可信资源版本构造的稳定键
    * @return
    *   合法键，或说明空白/超长问题的错误文本
    */
  def fromString(value: String): Either[String, BusinessIdempotencyKey] =
    val normalized = value.trim
    if normalized.isEmpty then Left("业务幂等键不能为空")
    else if normalized.length > MaxLength then Left(s"业务幂等键不能超过 $MaxLength 个字符")
    else Right(normalized)

  /** 仅返回已经通过构造器校验的字符串值。 */
  extension (value: BusinessIdempotencyKey) def value: String = value
  given JsonCodec[BusinessIdempotencyKey] = JsonCodec.string.transformOrFail(fromString, _.value)

/** 发布或补偿 worker 的稳定实例名；应包含部署名与进程随机后缀。 */
opaque type SideEffectWorkerId = String

object SideEffectWorkerId:
  /** 校验 worker 名称，防止空 owner 破坏租约审计。 */
  def fromString(value: String): Either[String, SideEffectWorkerId] =
    val normalized = value.trim
    Either.cond(
      normalized.nonEmpty && normalized.length <= 200,
      normalized,
      "SideEffectWorkerId 必须为 1-200 个字符"
    )

  /** 返回持久化 owner 字符串。 */
  extension (value: SideEffectWorkerId) def value: String = value
  given JsonCodec[SideEffectWorkerId] = JsonCodec.string.transformOrFail(fromString, _.value)

/** 每次 outbox/补偿 claim 重新生成的随机 fencing token。 */
opaque type SideEffectLeaseToken = UUID

object SideEffectLeaseToken:
  /** 包装 claim 内部生成的可信 UUID。 */
  def apply(value: UUID): SideEffectLeaseToken = value

  /** claim 时生成新 token；续租不得更换它。 */
  def random: UIO[SideEffectLeaseToken] = Random.nextUUID.map(SideEffectLeaseToken(_))

  /** 从数据库解析 token。 */
  def fromString(value: String): Either[String, SideEffectLeaseToken] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"无效的 SideEffectLeaseToken: $value")

  /** 返回 SQL 参数使用的 UUID 字符串。 */
  extension (value: SideEffectLeaseToken) def asString: String = value.toString
  given JsonCodec[SideEffectLeaseToken] = JsonCodec.string.transformOrFail(fromString, _.asString)
