package com.zyblw.agent.core

import zio.test.*

/** 验证可信内部构造与不可信外部解析拥有相同规范化规则，但失败通道不同。 */
object IdsSpec extends ZIOSpecDefault:

  def spec = suite("opaque IDs")(
    test("ThreadId.fromString trim 后返回类型化结果，空白不会抛异常") {
      assertTrue(
        ThreadId.fromString("  thread-1  ").map(_.value) == Right("thread-1"),
        ThreadId.fromString("   ") == Left("ThreadId 不能为空"),
        ThreadId.fromString(null) == Left("ThreadId 不能为空")
      )
    },
    test("AgentId.fromString 可安全用于 URL 与配置边界") {
      assertTrue(
        AgentId.fromString("  learning-agent ").map(_.value) == Right("learning-agent"),
        AgentId.fromString("").isLeft
      )
    },
    test("Provider、模型、工具和租户标识的 JSON 解码失败保留在 Either，不产生异常") {
      import zio.json.*
      assertTrue(
        "\"deepseek\"".fromJson[ProviderId].map(_.value) == Right("deepseek"),
        "\"  \"".fromJson[ProviderId].isLeft,
        "\"glm-5\"".fromJson[ModelId].map(_.value) == Right("glm-5"),
        "\"search\"".fromJson[ToolName].map(_.value) == Right("search"),
        "\"tenant-a\"".fromJson[TenantId].map(_.value) == Right("tenant-a"),
        "-1".fromJson[Version].isLeft
      )
    }
  )
