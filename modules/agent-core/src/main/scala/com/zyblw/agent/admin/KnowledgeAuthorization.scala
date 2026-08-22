package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import zio.*

/** 稳定知识 HTTP 面的 scope 与 fail-closed 判定。
  *
  * tenant 永远来自认证上下文。`knowledge:write` 蕴含读；`knowledge:admin` 蕴含写与读。列出已授予 scope 会帮助未授权调用方探测权限缺口，因此拒绝消息只回显租户。
  */
object KnowledgeAuthorization:
  val ReadScope: String  = "knowledge:read"
  val WriteScope: String = "knowledge:write"
  val AdminScope: String = "knowledge:admin"

  def requireRead(actor: RunContext): IO[AgentError, Unit] =
    check(actor, ReadScope, has(actor, ReadScope, WriteScope, AdminScope))

  def requireWrite(actor: RunContext): IO[AgentError, Unit] =
    check(actor, WriteScope, has(actor, WriteScope, AdminScope))

  def requireAdmin(actor: RunContext): IO[AgentError, Unit] =
    check(actor, AdminScope, actor.scopes.contains(AdminScope))

  /** 写入索引的 ACL。chunk 权限必须是检索方 scope 的子集，因此不能把 write/admin 写进文档。 */
  def indexPermissions(scopes: Set[String]): Set[String] =
    if has(scopes, ReadScope, WriteScope, AdminScope) then Set(ReadScope) else Set.empty

  def requireTenant(actor: RunContext): IO[AgentError, String] =
    ZIO
      .fromOption(actor.tenantId.map(_.trim).filter(_.nonEmpty))
      .orElseFail(
        AgentError.PermissionDenied("knowledge", "缺少可信 tenant，拒绝访问知识面")
      )

  private def has(scopes: Set[String], candidates: String*): Boolean =
    candidates.exists(scopes.contains)

  private def has(actor: RunContext, scopes: String*): Boolean =
    has(actor.scopes, scopes*)

  private def check(actor: RunContext, required: String, granted: Boolean): IO[AgentError, Unit] =
    if granted then ZIO.unit
    else
      ZIO.fail(
        AgentError.PermissionDenied(
          "knowledge",
          s"缺少知识 scope $required；当前主体 tenant=${actor.tenantId.getOrElse("-")}"
        )
      )
