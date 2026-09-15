package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*

/** 对象存储读取授权：引用本身不授予跨租户或 session 外泄能力。 */
object ArtifactAccessGrant:
  def authorize(reference: ArtifactReference, tenant: TenantId): Either[String, ArtifactReference] =
    reference.scope match
      case ArtifactScope.User(owner, _) if owner == tenant => Right(reference)
      case ArtifactScope.User(_, _)                        => Left("artifact-tenant-mismatch")
      case ArtifactScope.Session(_)                        => Left("artifact-session-scope-not-exportable")
      case ArtifactScope.Run(_, _)                         => Left("artifact-run-scope-not-exportable")

  /** 工具读取授权：只允许当前 Run 自己外置的结果，忽略模型自报的 scope。 */
  def authorize(
      reference: ArtifactReference,
      context: ToolExecutionContext
  ): Either[String, ArtifactReference] =
    reference.scope match
      case ArtifactScope.Run(runId, threadId) if runId == context.runId && threadId == context.threadId =>
        Right(reference)
      case ArtifactScope.Run(_, _) => Left("artifact-run-scope-mismatch")
      case ArtifactScope.User(owner, _) if context.runContext.tenantId.contains(owner.value) =>
        Right(reference)
      case ArtifactScope.User(_, _) => Left("artifact-tenant-mismatch")
      case ArtifactScope.Session(_) => Left("artifact-session-scope-not-readable-via-tool")

  /** 过期 grant 不能再兑换字节；调用方必须用已认证租户。 */
  final case class Issued(
      reference: ArtifactReference,
      tenantId: TenantId,
      expiresAtEpochMilli: Long
  ):
    require(expiresAtEpochMilli > 0L, "ArtifactAccessGrant 过期时间必须为正")

  def issue(
      reference: ArtifactReference,
      tenant: TenantId,
      nowEpochMilli: Long,
      ttlMillis: Long
  ): Either[String, Issued] =
    if ttlMillis <= 0L then Left("artifact-grant-ttl")
    else authorize(reference, tenant).map(Issued(_, tenant, nowEpochMilli + ttlMillis))

  def redeem(grant: Issued, caller: TenantId, nowEpochMilli: Long): Either[String, ArtifactReference] =
    if nowEpochMilli >= grant.expiresAtEpochMilli then Left("artifact-grant-expired")
    else if caller != grant.tenantId then Left("artifact-tenant-mismatch")
    else authorize(grant.reference, caller)
