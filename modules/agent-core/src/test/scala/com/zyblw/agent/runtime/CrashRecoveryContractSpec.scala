package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.tools.*
import zio.test.*

/** 崩溃恢复矩阵的可执行合同。完整 Runtime 路径见 ModelCallRuntimeSpec；这里锁定策略与账本不变量。 */
object CrashRecoveryContractSpec extends ZIOSpecDefault:
  def spec = suite("CrashRecoveryContract")(
    test("未结算模型调用不得声称 exact replay") {
      val record = ModelCallExecutionRecord(
        runId = RunId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")),
        requestId = ModelRequestId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")),
        attempt = 1,
        status = ModelCallStatus.Dispatched,
        provider = "test",
        model = "m",
        capturePolicy = CapturePolicy.MetadataOnly,
        fingerprint = "ab" * 32,
        messageCount = 1,
        toolCount = 0,
        lineage = ModelCallContextLineage(1, 0, 0, 0, 0),
        instructionFingerprint = None,
        canonicalRequest = None,
        updatedAtEpochMilli = 0L
      )
      assertTrue(
        record.toChatRequest.isLeft,
        record.verifyFrozenTools.isRight,
        record.status != ModelCallStatus.Succeeded
      )
    },
    test("工具崩溃恢复：只读可重放，破坏性必须再次审批") {
      val read    = ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
      val write   = ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
      val destroy = ToolMetadata(ToolRisk.AdminApproval, SideEffect.Destructive)
      assertTrue(
        read.mayReplayAfterCrash && read.recoveryPolicy == ToolRecoveryPolicy.ReplaySafe,
        !write.mayReplayAfterCrash && write.recoveryPolicy == ToolRecoveryPolicy.NeverReplay,
        !destroy.mayReplayAfterCrash && destroy.recoveryPolicy == ToolRecoveryPolicy.RequiresApproval
      )
    }
  )
