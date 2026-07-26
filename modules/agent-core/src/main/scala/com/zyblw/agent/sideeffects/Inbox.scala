package com.zyblw.agent.sideeffects

import java.time.Instant
import zio.json.*
import zio.json.ast.Json

/** 传给下游消费者的稳定消息信封。
  *
  * @param messageId
  *   必须等于生产端 `OutboxEventId`；发布重试期间不得变化
  * @param eventType
  *   版本化业务事件类型
  * @param payload
  *   不可信外部数据；consumer 必须在业务处理前完成 schema 与权限校验
  * @param headers
  *   非敏感传输元数据
  * @param occurredAt
  *   生产端业务事务创建事件的时间，而不是本次重试到达时间
  */
final case class InboxMessage(
    messageId: OutboxEventId,
    eventType: String,
    payload: Json,
    headers: Map[String, String],
    occurredAt: Instant
) derives JsonCodec:
  require(eventType.trim.nonEmpty && eventType.length <= 200, "inbox eventType 必须为 1-200 个字符")
  require(headers.size <= 32, "inbox headers 最多 32 项")

/** inbox 消费结果。
  *
  * @param value
  *   首次消费的业务结果，或从已提交 inbox 记录恢复的相同结果
  * @param duplicate
  *   为 true 表示 handler 没有再次执行，而是复用了同 consumer/messageId 的已提交结果
  */
final case class InboxConsumeResult[+A](value: A, duplicate: Boolean)

/** 下游消费者的稳定名称。
  *
  * 同一 messageId 可以由不同 consumer 各处理一次，因此唯一键是 `(consumerName, messageId)`。名称发布后不能随部署实例 改变，通常使用业务职责，例如
  * `search-index-projector-v1`。
  */
opaque type InboxConsumerName = String

object InboxConsumerName:
  /** 校验消费者名称，拒绝空白和无界文本。 */
  def fromString(value: String): Either[String, InboxConsumerName] =
    val normalized = value.trim
    Either.cond(
      normalized.nonEmpty && normalized.length <= 200,
      normalized,
      "InboxConsumerName 必须为 1-200 个字符"
    )

  /** 返回唯一约束和日志使用的底层名称。 */
  extension (value: InboxConsumerName) def value: String = value
  given JsonCodec[InboxConsumerName] = JsonCodec.string.transformOrFail(fromString, _.value)
