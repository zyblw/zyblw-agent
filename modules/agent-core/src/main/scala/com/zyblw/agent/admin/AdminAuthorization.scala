package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import zio.*

/** 管理面的固定 scope 常量与 fail-closed 授权判定。
  *
  * 管理面读取的是跨租户聚合（Run 目录、队列积压、配置快照、评测趋势），因此不能复用 `RunAuthorization` 的“归属即可读”规则： 归属规则保护的是单个 Run
  * 的所有者视角，而管理台看到的是整个部署。这里要求显式管理 scope，缺失一律拒绝。
  *
  * 三个 scope 故意分开，而不是合并成一个 `agent:admin`：
  *
  *   - [[ReadScope]] 只读聚合，泄漏面最小，可以发给值班与监控；
  *   - [[WriteScope]] 能改变部署行为（工具白名单、审批策略、死信重排、索引退役），必须单独授予；
  *   - [[DebugScope]] 会触发真实 Provider 调用并产生费用（检索沙盒、文档摄入），因此既不被 [[WriteScope]] 蕴含，也不被 [[ReadScope]] 蕴含。
  *
  * [[WriteScope]] 蕴含 [[ReadScope]]：能改配置的人必然要先看到当前配置，强制业务同时授予两个 scope 只会制造无意义的配置错误。
  */
object AdminAuthorization:
  /** 读取管理面聚合、Run 目录、有效配置快照与评测趋势。 */
  val ReadScope: String = "agent:admin:read"

  /** 修改运行时配置覆盖、重排死信命令、退役知识索引等改变部署行为的操作。 */
  val WriteScope: String = "agent:admin:write"

  /** 执行会产生真实 Provider 费用的调试操作：检索沙盒与文档摄入。 */
  val DebugScope: String = "agent:admin:debug"

  /** 校验只读权限；`WriteScope` 蕴含读权限。 */
  def requireRead(actor: RunContext): IO[AgentError, Unit] =
    check(actor, ReadScope, actor.scopes.contains(ReadScope) || actor.scopes.contains(WriteScope))

  /** 校验写权限。 */
  def requireWrite(actor: RunContext): IO[AgentError, Unit] =
    check(actor, WriteScope, actor.scopes.contains(WriteScope))

  /** 校验付费调试权限；写权限不蕴含它。 */
  def requireDebug(actor: RunContext): IO[AgentError, Unit] =
    check(actor, DebugScope, actor.scopes.contains(DebugScope))

  /** 统一构造授权错误。
    *
    * 消息回显调用方自己的租户而不是已授予的 scope 集合：租户能帮运维认出自己用错了哪个环境的令牌，而列出已授予的 scope 会把「离可用权限还差哪一个」告诉一个尚未获得授权的调用方。
    */
  private def check(actor: RunContext, required: String, granted: Boolean): IO[AgentError, Unit] =
    if granted then ZIO.unit
    else
      ZIO.fail(
        AgentError.PermissionDenied(
          "admin",
          s"缺少管理 scope $required；当前主体 tenant=${actor.tenantId.getOrElse("-")}"
        )
      )
