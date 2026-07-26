package com.zyblw.agent.integrations.gemini

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.json.ast.Json

/** Gemini Interactions API 的部署配置。
  *
  * 本适配器刻意默认使用无状态模式：Agent 的权威状态属于 `RunStore`，而不是厂商服务端的 interaction。这样才能对崩溃恢复、审计、数据驻留和 Provider 切换给出一致语义。
  *
  * @param baseUrl
  *   Google Generative Language API 根地址；生产默认使用稳定版 `/v1`
  * @param apiKey
  *   `x-goog-api-key` 凭据，只能来自环境变量或 Secret Manager
  * @param defaultModel
  *   请求未显式选择模型时使用的 Gemini 模型 ID
  * @param requestTimeout
  *   单次非流式调用或整条流的最大持续时间
  * @param defaultOptions
  *   经过白名单校验的 Gemini 顶层扩展项；不能覆盖框架管理字段
  */
final case class GeminiInteractionsConfig(
    baseUrl: String,
    apiKey: String,
    defaultModel: String,
    requestTimeout: Duration = 90.seconds,
    defaultOptions: Map[String, Json] = Map.empty
):
  require(baseUrl.trim.nonEmpty, "baseUrl must not be empty")
  require(apiKey.trim.nonEmpty, "apiKey must not be empty")
  require(defaultModel.trim.nonEmpty, "defaultModel must not be empty")

  /** 创建 interaction 的稳定版端点。流式调用在同一路径增加 `alt=sse`。 */
  val interactionsUrl: String = s"${baseUrl.stripSuffix("/")}/interactions"

  /** 安全日志摘要：任何异常、配置打印或测试报告都不能泄漏 API Key。 */
  override def toString: String =
    s"GeminiInteractionsConfig(baseUrl=$baseUrl, apiKey=<redacted>, defaultModel=$defaultModel, " +
      s"requestTimeout=$requestTimeout)"

object GeminiInteractionsConfig:
  /** Gemini Interactions 的 ZIO Config 描述。 API Key 通过 `Config.Secret` 加载；自定义 ConfigProvider 使测试和 Secret
    * backend 无需修改 Adapter 代码。
    */
  val environmentConfig: Config[GeminiInteractionsConfig] =
    (
      Config.string("GEMINI_BASE_URL").withDefault("https://generativelanguage.googleapis.com/v1") ++
        Config.secret("GEMINI_API_KEY") ++
        Config.string("GEMINI_MODEL") ++
        Config.duration("GEMINI_REQUEST_TIMEOUT").withDefault(90.seconds)
    ).mapAttempt { case (baseUrl, apiKey, model, timeout) =>
      GeminiInteractionsConfig(baseUrl, apiKey.stringValue, model, timeout)
    }

  /** 从当前 ZIO ConfigProvider 构造配置。
    *
    * 缺失凭据会在 ZLayer 启动期以 typed error 失败，而不是等到第一位用户请求才暴露。
    */
  def fromEnvironment: IO[AgentError, GeminiInteractionsConfig] =
    ZIO
      .config(environmentConfig)
      .mapError(error => AgentError.InvalidConfiguration(s"Gemini Interactions 配置无效: $error"))

/** Interactions 的保守能力声明。
  *
  * Gemini 支持更多多模态和托管工具能力，但本模块尚未给这些能力建立统一授权与契约前不声明支持； 能力矩阵必须描述“本适配器已可靠实现什么”，而不是厂商宣传页列出了什么。
  */
private[gemini] object GeminiInteractionsDescriptor:
  val value: ProviderDescriptor = ProviderDescriptor(
    id = "gemini",
    displayName = "Google Gemini Interactions",
    protocol = "gemini-interactions",
    capabilities = ModelCapabilities(
      toolCalls = true,
      strictToolSchema = false,
      specificToolChoice = false,
      developerRole = false,
      thinking = true,
      vision = false,
      streaming = true,
      parallelToolCalls = true,
      usageReporting = true
    )
  )
