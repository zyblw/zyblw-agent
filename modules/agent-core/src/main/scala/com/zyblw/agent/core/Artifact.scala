package com.zyblw.agent.core

import zio.json.JsonCodec

/** Artifact 的可信隔离域。
  *
  * Session 与 User 两种域复用长期 Memory 的隔离语义：用户级 Artifact 必须同时带 tenant。`Run` 是工具大结果外置的默认域—— 由
  * [[ToolExecutionContext]] 的 `runId`/`threadId` 确定性派生，绝不接受模型自报。
  *
  * 此类型只描述存储键；HTTP、CLI 或 Tool Adapter 仍必须从已经认证的上下文推导它。
  */
enum ArtifactScope derives JsonCodec:
  case Session(sessionId: SessionId)
  case User(tenantId: TenantId, userId: UserId)
  case Run(runId: RunId, threadId: ThreadId)

  /** 不含 Artifact 名称或正文的稳定诊断标签。 */
  def diagnostic: String = this match
    case ArtifactScope.Session(sessionId)     => s"session:${sessionId.asString}"
    case ArtifactScope.User(tenantId, userId) => s"user:${tenantId.value}:${userId.value}"
    case ArtifactScope.Run(runId, threadId)   => s"run:${runId.asString}:${threadId.value}"

object ArtifactScope:
  /** 工具结果外置使用的隔离域；只由可信执行上下文派生。 */
  def of(context: ToolExecutionContext): ArtifactScope =
    ArtifactScope.Run(context.runId, context.threadId)

/** Artifact 的低敏、不可变引用。
  *
  * 引用不携带二进制、私有 metadata 或创建时间，也不授予读取权限。调用方仍必须从已认证上下文推导并授权 scope；sha256/大小/mediaType 用于读取后验证名称与版本没有被错误 Adapter
  * 重新绑定。
  */
final case class ArtifactReference(
    scope: ArtifactScope,
    name: ArtifactName,
    version: Long,
    mediaType: String,
    byteSize: Long,
    sha256: String
) derives JsonCodec:
  require(version > 0L && byteSize >= 0L, "ArtifactReference version 必须为正，byteSize 不能为负")
  require(ArtifactReference.validMediaType(mediaType), "ArtifactReference mediaType 必须是有界 type/subtype")
  require(sha256.matches("[0-9a-f]{64}"), "ArtifactReference sha256 必须是小写 SHA-256")

object ArtifactReference:
  def validMediaType(value: String): Boolean =
    value.length <= 127 &&
      value.count(_ == '/') == 1 &&
      value.indexOf('/') > 0 &&
      value.lastIndexOf('/') < value.length - 1 &&
      !value.exists(_.isControl)
