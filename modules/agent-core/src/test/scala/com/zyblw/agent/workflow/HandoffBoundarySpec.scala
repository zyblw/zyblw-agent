package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import com.zyblw.agent.execution.{NetworkAccess, PermissionProfile}
import com.zyblw.agent.tools.AgentAsTool
import zio.*
import zio.test.*

object HandoffBoundarySpec extends ZIOSpecDefault:
  private val read         = ToolName("lookup_order")
  private val write        = ToolName("issue_refund")
  private val parentLimits = RunLimits(maxSteps = 8, maxModelCalls = 4, maxToolCalls = 8)
  private val parentPerms  = PermissionProfile.host

  def spec = suite("HandoffBoundary")(
    test("工具、预算和权限都只能收窄") {
      val childLimits = parentLimits.copy(maxSteps = 2, maxToolCalls = 2)
      val childPerms  = PermissionProfile(network = NetworkAccess.DenyAll)
      val ok          = HandoffBoundary.shrink(
        Set(read, write),
        Set(read),
        parentLimits,
        childLimits,
        parentPerms,
        childPerms
      )
      val widenedTools = HandoffBoundary.shrink(
        Set(read),
        Set(read, write),
        parentLimits,
        childLimits,
        parentPerms,
        childPerms
      )
      val widenedBudget = HandoffBoundary.shrink(
        Set(read),
        Set(read),
        parentLimits,
        parentLimits.copy(maxSteps = 99),
        parentPerms,
        childPerms
      )
      assertTrue(
        ok.exists(_.tools == Set(read)),
        widenedTools == Left("handoff-tools-widened"),
        widenedBudget == Left("handoff-limits-widened")
      )
    },
    test("agent-as-tool 复用同一收窄合同") {
      val granted = AgentAsTool.grant(
        ToolName("delegate_support"),
        AgentId("support-agent"),
        Set(read, write),
        Set(read),
        parentLimits,
        parentLimits.copy(maxModelCalls = 1),
        parentPerms,
        PermissionProfile.denyAll
      )
      assertTrue(granted.exists(_.callee == AgentId("support-agent")), granted.exists(_.tools == Set(read)))
    }
  )
