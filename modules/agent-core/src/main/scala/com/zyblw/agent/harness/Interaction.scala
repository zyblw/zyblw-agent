package com.zyblw.agent.harness

import com.zyblw.agent.core.*
import java.util.UUID
import zio.*
import zio.json.*

/** 人机交互输入。不是控制命令：不能 Cancel、Recover、审批或 Retry。 */
enum InteractionKind derives JsonCodec:
  case Steer, FollowUp, UserMessage

opaque type InteractionId = UUID
object InteractionId:
  def apply(value: UUID): InteractionId                        = value
  def random: UIO[InteractionId]                               = Random.nextUUID.map(InteractionId(_))
  def fromString(value: String): Either[String, InteractionId] =
    scala.util.Try(UUID.fromString(value)).toEither.left.map(_ => s"非法 InteractionId: $value")
  extension (id: InteractionId) def asString: String = id.toString
  given JsonCodec[InteractionId] = JsonCodec.string.transformOrFail(fromString, _.asString)

/** 追加到 Goal 上的交互事实。正文按检索资料注入，不能升为 System，也不能授予工具。 */
final case class InteractionInput(
    id: InteractionId,
    goalId: GoalId,
    kind: InteractionKind,
    body: String,
    runId: Option[RunId] = None,
    sequence: Long = 0L,
    createdAtEpochMilli: Long = 0L
) derives JsonCodec:
  require(body.trim.nonEmpty && body.length <= 4000, "InteractionInput.body 必须为 1..4000 个字符")
  require(sequence >= 0L && createdAtEpochMilli >= 0L, "Interaction sequence/时间不能为负")
