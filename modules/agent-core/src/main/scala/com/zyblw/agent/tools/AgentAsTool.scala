package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import com.zyblw.agent.execution.PermissionProfile
import com.zyblw.agent.workflow.HandoffBoundary

/** manager 调用另一个 Agent 时的工具边界：callee 只能获得 caller 的子集。 */
final case class AgentAsToolGrant(
    name: ToolName,
    callee: AgentId,
    tools: Set[ToolName],
    limits: RunLimits,
    permissions: PermissionProfile
)

object AgentAsTool:
  def grant(
      name: ToolName,
      callee: AgentId,
      callerTools: Set[ToolName],
      calleeTools: Set[ToolName],
      callerLimits: RunLimits,
      calleeLimits: RunLimits,
      callerPermissions: PermissionProfile,
      calleePermissions: PermissionProfile
  ): Either[String, AgentAsToolGrant] =
    HandoffBoundary
      .shrink(
        callerTools,
        calleeTools,
        callerLimits,
        calleeLimits,
        callerPermissions,
        calleePermissions
      )
      .map(grant => AgentAsToolGrant(name, callee, grant.tools, grant.limits, grant.permissions))
