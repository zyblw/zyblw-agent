package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import zio.test.*

/** SideEffect 同时推导在线可重试性与崩溃恢复策略，二者集合碰巧重叠但不共用一个开关。 */
object ToolRecoveryPolicySpec extends ZIOSpecDefault:
  def spec = suite("ToolRecoveryPolicy")(
    test("SideEffect 映射到独立的崩溃恢复策略") {
      val readOnly      = ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
      val idempotent    = ToolMetadata(ToolRisk.DraftWrite, SideEffect.IdempotentWrite)
      val outbox        = ToolMetadata(ToolRisk.DraftWrite, SideEffect.TransactionalOutboxWrite)
      val nonIdempotent = ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
      val destructive   = ToolMetadata(ToolRisk.AdminApproval, SideEffect.Destructive)
      assertTrue(
        readOnly.recoveryPolicy == ToolRecoveryPolicy.ReplaySafe,
        readOnly.mayReplayAfterCrash,
        readOnly.onlineRetryable,
        idempotent.recoveryPolicy == ToolRecoveryPolicy.Idempotent,
        idempotent.mayReplayAfterCrash,
        outbox.recoveryPolicy == ToolRecoveryPolicy.Idempotent,
        nonIdempotent.recoveryPolicy == ToolRecoveryPolicy.NeverReplay,
        !nonIdempotent.mayReplayAfterCrash,
        !nonIdempotent.onlineRetryable,
        destructive.recoveryPolicy == ToolRecoveryPolicy.RequiresApproval,
        !destructive.mayReplayAfterCrash,
        !destructive.onlineRetryable,
        readOnly.automaticallyRetryable == readOnly.onlineRetryable
      )
    }
  )
