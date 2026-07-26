package com.zyblw.agent.context.llm

import com.zyblw.agent.core.*
import zio.*

/** `LlmContextCompressorConfig` 的 ZIO Config 描述与启动期加载器。
  *
  * 本配置只包含模型路由标识、输入输出上限、超时和降级策略，不读取 API Key。Provider Secret 继续由具体 ChatModel Adapter
  * 管理，因此打印本配置或把它放入普通应用配置快照不会泄漏凭据。
  *
  * 默认路径是 `zyblw.agent.context.compression`。使用 ZIO 默认环境变量 ConfigProvider 时，对应变量例如：
  *
  * {{ ZYBLW_AGENT_CONTEXT_COMPRESSION_MODEL_PROVIDER=deepseek
  * ZYBLW_AGENT_CONTEXT_COMPRESSION_MODEL_MODEL=deepseek-v4-flash
  * ZYBLW_AGENT_CONTEXT_COMPRESSION_MODEL_MAX_OUTPUT_TOKENS=1200
  * ZYBLW_AGENT_CONTEXT_COMPRESSION_BEHAVIOR_REQUEST_TIMEOUT=20s }}
  *
  * 是否启用模型辅助压缩不由一个容易漂移的 `enabled` 布尔值决定。业务必须同时：
  *
  *   1. 在 AgentDefinition 中设置 `CompressionMode.ModelAssisted`；
  *   2. 使用 `AgentApplication.*WithContextCompressor` 装配入口；
  *   3. 提供 `LlmContextCompressor.configured(config)`。
  *
  * 三项缺一都会在本地装配或 Context 构建阶段明确失败，不会悄悄产生费用或静默改回确定性算法。
  */
object LlmContextCompressorConfigLoader:
  /** 默认 shell-safe 点分路径；环境变量 Provider 会映射为 `ZYBLW_AGENT_CONTEXT_COMPRESSION_*`。 */
  val DefaultPrefix: String = "zyblw.agent.context.compression"

  /** 构造纯配置描述，不在调用时读取环境。
    *
    * @param prefix
    *   可为不同 Agent 集群指定独立路径
    * @return
    *   交给 `ZIO.config` 的描述；默认值保持低成本、有限修复和默认禁止独立 Tool 输出模型压缩
    */
  def description(prefix: String = DefaultPrefix): Config[LlmContextCompressorConfig] =
    val base = ((modelDescription ++ limitsDescription).map(ConfigParts.apply) ++ behaviorDescription)
      .mapAttempt { case (parts, behavior) =>
        LlmContextCompressorConfig(
          modelSettings = ModelSettings(
            provider = normalizeOptional(parts.model.provider),
            model = normalizeOptional(parts.model.model),
            temperature = Some(0.0),
            maxOutputTokens = Some(parts.model.maxOutputTokens)
          ),
          maxMessages = parts.limits.maxMessages,
          maxInputCodePoints = parts.limits.maxInputCodePoints,
          maxItems = parts.limits.maxItems,
          maxEvidenceQuoteCodePoints = parts.limits.maxEvidenceQuoteCodePoints,
          maxReferencesPerItem = parts.limits.maxReferencesPerItem,
          maxArgumentsCharacters = parts.limits.maxArgumentsCharacters,
          requestTimeout = behavior.requestTimeout,
          maxSchemaRepairs = behavior.maxSchemaRepairs,
          compressorVersion = behavior.compressorVersion.trim,
          allowStandaloneToolOutput = behavior.allowStandaloneToolOutput,
          deterministicFallbackOnValidationExhausted = behavior.deterministicFallback
        )
      }
    ZioConfigPath.nested(base, prefix)

  /** 从当前 Fiber 的 ConfigProvider 加载并校验配置。
    *
    * 错误统一映射到框架 `InvalidConfiguration`；消息只包含路径和约束，不包含 Provider 响应、Prompt 或 API Key。
    */
  def load(
      prefix: String = DefaultPrefix
  ): IO[AgentError.InvalidConfiguration, LlmContextCompressorConfig] =
    ZIO
      .config(description(prefix))
      .mapError(error => AgentError.InvalidConfiguration(s"Context 压缩配置无效: $error"))

  /** 可选 ZLayer 适配器。
    *
    * 官方推荐以 `ZIO.config` 作为配置读取前端；该 Layer 只方便既有应用启动图暴露已验证配置，不会在服务构造器里读取 全局环境或隐藏最终值。
    */
  def layer(
      prefix: String = DefaultPrefix
  ): Layer[AgentError.InvalidConfiguration, LlmContextCompressorConfig] =
    ZLayer.fromZIO(load(prefix))

  /** Provider 路由与输出上限。空 provider/model 被规范化为 None，表示使用 ChatModel 路由的受控默认值。 */
  private lazy val modelDescription: Config[ModelConfig] =
    (
      Config.string("provider").optional ++
        Config.string("model").optional ++
        Config.int("max_output_tokens").withDefault(1200)
    ).map(ModelConfig.apply).nested("model")

  /** 输入、证据、工具参数和输出项目的硬资源边界。 */
  private lazy val limitsDescription: Config[LimitsConfig] =
    (
      Config.int("max_messages").withDefault(96) ++
        Config.int("max_input_code_points").withDefault(80_000) ++
        Config.int("max_items").withDefault(40) ++
        Config.int("max_evidence_quote_code_points").withDefault(600) ++
        Config.int("max_references_per_item").withDefault(8) ++
        Config.int("max_arguments_characters").withDefault(40_000)
    ).map(LimitsConfig.apply).nested("limits")

  /** 超时、有限修复、协议版本和安全降级行为。 */
  private lazy val behaviorDescription: Config[BehaviorConfig] =
    (
      Config.duration("request_timeout").withDefault(20.seconds) ++
        Config.int("max_schema_repairs").withDefault(1) ++
        Config.string("compressor_version").withDefault("llm-extractive-v1") ++
        Config.boolean("allow_standalone_tool_output").withDefault(false) ++
        Config.boolean("deterministic_fallback").withDefault(true)
    ).map(BehaviorConfig.apply).nested("behavior")

  /** 空白可选项表示让受控 ChatModel 路由选择默认值；非空值仍由配置构造器执行长度与控制字符校验。 */
  private def normalizeOptional(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)

  /** 仅用于降低大型 ZIO Config 组合的静态类型复杂度，不会进入公共配置协议。 */
  final private case class ConfigParts(model: ModelConfig, limits: LimitsConfig)

  /** 压缩模型选择；API Key 不属于这个结构。 */
  final private case class ModelConfig(provider: Option[String], model: Option[String], maxOutputTokens: Int)

  /** 所有输入与 schema 上限。 */
  final private case class LimitsConfig(
      maxMessages: Int,
      maxInputCodePoints: Int,
      maxItems: Int,
      maxEvidenceQuoteCodePoints: Int,
      maxReferencesPerItem: Int,
      maxArgumentsCharacters: Int
  )

  /** 调用行为、版本和降级边界。 */
  final private case class BehaviorConfig(
      requestTimeout: Duration,
      maxSchemaRepairs: Int,
      compressorVersion: String,
      allowStandaloneToolOutput: Boolean,
      deterministicFallback: Boolean
  )
