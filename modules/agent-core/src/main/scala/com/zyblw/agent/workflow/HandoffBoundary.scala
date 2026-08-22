package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import com.zyblw.agent.execution.PermissionProfile

/** 跨 Agent 边界只能收窄：工具、预算和权限都不能放大。 */
final case class HandoffGrant(
    tools: Set[ToolName],
    limits: RunLimits,
    permissions: PermissionProfile
)

object HandoffBoundary:
  def shrink(
      parentTools: Set[ToolName],
      childTools: Set[ToolName],
      parentLimits: RunLimits,
      childLimits: RunLimits,
      parentPermissions: PermissionProfile,
      childPermissions: PermissionProfile
  ): Either[String, HandoffGrant] =
    if !childTools.subsetOf(parentTools) then Left("handoff-tools-widened")
    else if !limitsShrink(parentLimits, childLimits) then Left("handoff-limits-widened")
    else if !parentPermissions.permits(childPermissions) then Left("handoff-permissions-widened")
    else Right(HandoffGrant(childTools, childLimits, childPermissions))

  def limitsShrink(parent: RunLimits, child: RunLimits): Boolean =
    child.maxSteps <= parent.maxSteps &&
      child.maxModelCalls <= parent.maxModelCalls &&
      child.maxToolCalls <= parent.maxToolCalls &&
      child.maxRepeatedActions <= parent.maxRepeatedActions &&
      child.maxInputTokens <= parent.maxInputTokens &&
      child.maxOutputTokens <= parent.maxOutputTokens &&
      child.maxTotalTokens <= parent.maxTotalTokens &&
      child.maxDuration.toMillis <= parent.maxDuration.toMillis &&
      ((parent.maxEstimatedCost, child.maxEstimatedCost) match
        case (None, Some(_))                     => false
        case (Some(parentCost), Some(childCost)) => childCost <= parentCost
        case _                                   => true)
