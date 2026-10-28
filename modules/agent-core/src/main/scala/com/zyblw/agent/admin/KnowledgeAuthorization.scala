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

  /** 判断调用方是否具备全租户知识目录管理/写入视角。
    *
    * 普通业务检索调用方仅持有 `knowledge:read` 与自身业务 ACL scope（例如 `public`、`owner:<id>`），不应枚举或读取其他用户私有索引文档元数据及摄入作业详情。
    */
  def hasCatalogPrivilege(actor: RunContext): Boolean =
    has(
      actor,
      WriteScope,
      AdminScope,
      AdminAuthorization.ReadScope,
      AdminAuthorization.WriteScope
    )

  /** 计算文档目录与详情接口的文档级 ACL 过滤条件。
    *   - 具备管理/写入权限的调用方返回 `None`（查看当前租户全量索引清单）
    *   - 仅持有 `knowledge:read` 的普通调用方返回 `Some(actor.scopes)`，要求文档 `permissions` 非空且为 `actor.scopes` 的子集
    */
  def documentReadPermissions(actor: RunContext): Option[Set[String]] =
    if hasCatalogPrivilege(actor) then None else Some(actor.scopes)

  /** 摄入任务状态包含原始文件路径或业务来源 URI，仅允许具备写入或管理权限的调用方查询。 */
  def requireIngestionRead(actor: RunContext): IO[AgentError, Unit] =
    check(actor, WriteScope, hasCatalogPrivilege(actor))

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
