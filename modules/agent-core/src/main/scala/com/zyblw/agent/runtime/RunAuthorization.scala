package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import zio.*

/** Run 状态、事件和控制面的统一资源归属判定。
  *
  * 把规则放在 Runtime 模块而不是 HTTP Handler 中，是为了避免状态查询、SSE、命令查询各自复制一份稍有差异的 tenant/user 判断。模型、请求 JSON 和 URL
  * 都不能提供这里使用的身份；`actor` 必须来自已经验签的认证上下文。
  */
object RunAuthorization:
  /** 读取 Run 状态或耐久事件时允许使用的跨资源管理员 scope。 */
  val ReadAdminScope: String = "agent:runs:read:admin"

  /** 控制审批、取消、恢复和重试时允许使用的跨资源管理员 scope。 */
  val CommandAdminScope: String = "agent:commands:admin"

  /** 验证调用者是否可以读取或控制给定 Run。
    *
    * 规则有意保持简单且默认拒绝：管理员 scope 可以跨资源；否则 Run 中已经冻结的 tenantId/userId 必须都与认证 上下文匹配。租户级 Run 没有
    * userId，因此同租户用户可以访问；匿名 Run 没有两个 ID，适合宿主明确声明的公开 Agent。若业务不允许公开 Run，应在 `AgentRequestContextResolver`
    * 或上层路由拒绝匿名调用。
    *
    * @param state
    *   权威 AgentState，不接受客户端自报的资源所有者
    * @param actor
    *   认证中间件解析出的可信调用者
    * @param action
    *   错误审计使用的低敏动作名，例如 `read` 或 `command`
    * @param adminScopes
    *   能绕过归属匹配的显式管理员 scope 集合
    * @return
    *   允许时返回原 state，便于调用方继续使用同一快照；拒绝时返回 Authorization 错误
    */
  def authorize(
      state: AgentState,
      actor: RunContext,
      action: String,
      adminScopes: Set[String]
  ): IO[AgentError, AgentState] =
    val admin         = actor.scopes.exists(adminScopes.contains)
    val tenantMatches = state.runContext.tenantId.forall(expected => actor.tenantId.contains(expected))
    val userMatches   = state.runContext.userId.forall(expected => actor.userId.contains(expected))
    if admin || (tenantMatches && userMatches) then ZIO.succeed(state)
    else ZIO.fail(AgentError.PermissionDenied(s"agent-run:$action", "无权访问其他用户或租户的 Run"))

  /** 使用读取管理员 scope 验证状态、事件与 SSE 订阅。 */
  def read(state: AgentState, actor: RunContext): IO[AgentError, AgentState] =
    authorize(state, actor, "read", Set(ReadAdminScope, CommandAdminScope))

  /** 使用控制管理员 scope 验证审批、取消、恢复与重试。 */
  def command(state: AgentState, actor: RunContext): IO[AgentError, AgentState] =
    authorize(state, actor, "command", Set(CommandAdminScope))
