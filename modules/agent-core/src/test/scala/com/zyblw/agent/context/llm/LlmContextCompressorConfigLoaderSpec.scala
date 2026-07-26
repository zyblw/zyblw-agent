package com.zyblw.agent.context.llm

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 验证模型辅助 Context 压缩配置的部署协议。
  *
  * 这些测试使用进程内 ConfigProvider，不读取开发机环境，也不要求真实 Provider 密钥。重点是证明默认值安全、完整配置可以
  * 映射到强类型对象、非法资源上限会在启动期失败，而且空白模型路由不会被误当成一个真实 Provider ID。
  */
object LlmContextCompressorConfigLoaderSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("LlmContextCompressorConfigLoader")(
    test("完整解析模型路由、资源上限、超时、修复和降级策略") {
      val values = Map(
        "zyblw.agent.context.compression.model.provider"                        -> "deepseek",
        "zyblw.agent.context.compression.model.model"                           -> "deepseek-v4-flash",
        "zyblw.agent.context.compression.model.max_output_tokens"               -> "900",
        "zyblw.agent.context.compression.limits.max_messages"                   -> "48",
        "zyblw.agent.context.compression.limits.max_input_code_points"          -> "40000",
        "zyblw.agent.context.compression.limits.max_items"                      -> "24",
        "zyblw.agent.context.compression.limits.max_evidence_quote_code_points" -> "320",
        "zyblw.agent.context.compression.limits.max_references_per_item"        -> "4",
        "zyblw.agent.context.compression.limits.max_arguments_characters"       -> "16000",
        "zyblw.agent.context.compression.behavior.request_timeout"              -> "12s",
        "zyblw.agent.context.compression.behavior.max_schema_repairs"           -> "2",
        "zyblw.agent.context.compression.behavior.compressor_version"           -> "tcm-learning-v2",
        "zyblw.agent.context.compression.behavior.allow_standalone_tool_output" -> "true",
        "zyblw.agent.context.compression.behavior.deterministic_fallback"       -> "false"
      )

      LlmContextCompressorConfigLoader.load().provide(configProvider(values)).map { config =>
        assertTrue(
          config.modelSettings.provider.contains("deepseek"),
          config.modelSettings.model.contains("deepseek-v4-flash"),
          config.modelSettings.temperature.contains(0.0),
          config.modelSettings.maxOutputTokens.contains(900),
          config.maxMessages == 48,
          config.maxInputCodePoints == 40_000,
          config.maxItems == 24,
          config.maxEvidenceQuoteCodePoints == 320,
          config.maxReferencesPerItem == 4,
          config.maxArgumentsCharacters == 16_000,
          config.requestTimeout == 12.seconds,
          config.maxSchemaRepairs == 2,
          config.compressorVersion == "tcm-learning-v2",
          config.allowStandaloneToolOutput,
          !config.deterministicFallbackOnValidationExhausted
        )
      }
    },
    test("空配置采用安全默认值并允许受控 ChatModel 路由选择默认 Provider") {
      LlmContextCompressorConfigLoader.load().provide(configProvider(Map.empty)).map { config =>
        assertTrue(
          config.modelSettings.provider.isEmpty,
          config.modelSettings.model.isEmpty,
          config.modelSettings.temperature.contains(0.0),
          config.modelSettings.maxOutputTokens.contains(1200),
          config.maxMessages == 96,
          config.maxSchemaRepairs == 1,
          config.requestTimeout == 20.seconds,
          !config.allowStandaloneToolOutput,
          config.deterministicFallbackOnValidationExhausted
        )
      }
    },
    test("空白 provider/model 规范化为 None，不向路由发送空标识") {
      val values = Map(
        "zyblw.agent.context.compression.model.provider" -> "   ",
        "zyblw.agent.context.compression.model.model"    -> ""
      )
      LlmContextCompressorConfigLoader.load().provide(configProvider(values)).map { config =>
        assertTrue(
          config.modelSettings.provider.isEmpty,
          config.modelSettings.model.isEmpty
        )
      }
    },
    test("非法资源上限和 compressorVersion 在应用启动阶段以 typed 配置错误失败") {
      val values = Map(
        "zyblw.agent.context.compression.limits.max_items"            -> "0",
        "zyblw.agent.context.compression.behavior.compressor_version" -> "bad version with spaces"
      )
      LlmContextCompressorConfigLoader.load().provide(configProvider(values)).exit.map { exit =>
        val error = exit.causeOption.flatMap(_.failureOption)
        assertTrue(
          exit.isFailure,
          error.exists(_.isInstanceOf[AgentError.InvalidConfiguration]),
          error.exists(_.message.contains("Context 压缩配置无效"))
        )
      }
    },
    test("自定义 prefix 可以为同一进程中的不同 Agent 集群隔离压缩模型") {
      val values = Map(
        "research.model.provider"           -> "anthropic",
        "research.model.model"              -> "claude-sonnet",
        "research.behavior.request_timeout" -> "8s"
      )
      LlmContextCompressorConfigLoader.load("research").provide(configProvider(values)).map { config =>
        assertTrue(
          config.modelSettings.provider.contains("anthropic"),
          config.modelSettings.model.contains("claude-sonnet"),
          config.requestTimeout == 8.seconds
        )
      }
    }
  )

  /** 为单个测试安装不可变 Map ConfigProvider，避免并行测试相互污染。 */
  private def configProvider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))
