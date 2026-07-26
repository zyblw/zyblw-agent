package com.zyblw.agent.core

import zio.json.*
import zio.test.*

/** 验证 Token 明细不会被重复计入预算，并保持旧持久化 JSON 的向后读取能力。 */
object TokenUsageSpec extends ZIOSpecDefault:
  def spec = suite("TokenUsage")(
    test("累计缓存与推理明细但总预算只计算输入输出总量") {
      val combined = TokenUsage(10, 4, 6, 2) + TokenUsage(5, 3, 1, 1)
      val summary  = UsageSummary().addModel(combined)
      assertTrue(
        combined == TokenUsage(15, 7, 7, 3),
        combined.totalTokens == 22,
        summary.totalTokens == 22,
        summary.cachedInputTokens == 7,
        summary.reasoningOutputTokens == 3
      )
    },
    test("新增 UsageSummary 字段具有默认值，可读取旧快照") {
      val legacy =
        """{"modelCalls":1,"toolCalls":2,"inputTokens":10,"outputTokens":4,"estimatedCost":0}"""
      val decoded = legacy.fromJson[UsageSummary]
      assertTrue(
        decoded.exists(summary => summary.cachedInputTokens == 0L && summary.reasoningOutputTokens == 0L)
      )
    }
  )
