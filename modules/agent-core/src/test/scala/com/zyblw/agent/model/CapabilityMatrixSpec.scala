package com.zyblw.agent.model

import zio.test.*

/** 声明能力与探测结果必须一致；未覆盖的模型 fail-closed。 */
object CapabilityMatrixSpec extends ZIOSpecDefault:
  private val declared = ModelCapabilities(toolCalls = true, streaming = true, vision = false)

  def spec: Spec[TestEnvironment, Any] = suite("CapabilityMatrix")(
    test("精确命中时要求声明与探测逐字段相等") {
      val matrix = CapabilityMatrix(Map(("openai", "gpt-4.1") -> declared))
      assertTrue(
        matrix.requireConsistent("openai", "gpt-4.1", declared).isRight,
        matrix.requireConsistent("openai", "gpt-4.1", declared.copy(vision = true)).isLeft,
        matrix
          .requireConsistent("openai", "gpt-4.1", declared.copy(promptCache = PromptCacheCapability.implicitRead))
          .isLeft,
        matrix.requireConsistent("openai", "missing", declared).isLeft
      )
    },
    test("从 descriptor 导出时不猜测未列出的模型") {
      val descriptor = ProviderDescriptor(
        "relay",
        "Relay",
        "openai-compatible",
        declared,
        models = Map("deepseek-v4" -> declared)
      )
      val matrix = CapabilityMatrix.fromDescriptor(descriptor)
      assertTrue(
        matrix.requireConsistent("relay", "deepseek-v4", declared).isRight,
        matrix.requireConsistent("relay", "*", declared).isLeft
      )
    }
  )
