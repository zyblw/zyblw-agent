package com.zyblw.agent.integrations.openai

import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.CredentialReference
import com.zyblw.agent.model.*
import zio.*
import zio.json.ast.Json

/** OpenAI Responses 原生协议配置。
  *
  * 该配置与 [[OpenAICompatibleConfig]] 刻意分离：两者虽然使用同一认证方式，但请求结构、工具定义、 流式事件和响应状态机并不相同。若把两种协议塞进一组布尔开关，后续维护时很容易把 Chat
  * Completions 的 `choices/delta` 误用到 Responses 的 typed event 上。
  *
  * @param baseUrl
  *   OpenAI API 根地址，通常为 `https://api.openai.com/v1`；测试可替换为本地 stub
  * @param apiKey
  *   Bearer API Key，只能来自环境变量或 Secret Manager，禁止写入日志和持久化状态
  * @param defaultModel
  *   请求未指定 `ModelSettings.model` 时使用的模型；框架不硬编码“最新模型”
  * @param organization
  *   可选组织标识，只有多组织账号需要发送
  * @param project
  *   可选项目标识，用于 OpenAI 项目级额度和审计隔离
  * @param requestTimeout
  *   单次完整响应或流的最长持续时间；超时会进入 typed error channel
  * @param store
  *   是否让 OpenAI 保存 Response；默认关闭，使 PostgreSQL RunStore 保持唯一事实源
  * @param parallelToolCalls
  *   是否允许模型在同一响应提出多个工具；真正执行仍由 Durable Runtime 调度
  * @param defaultOptions
  *   部署级额外选项，例如 `reasoning`；保留协议字段不能通过此处覆盖
  */
final case class OpenAIResponsesConfig(
    baseUrl: String,
    apiKey: String,
    defaultModel: String,
    organization: Option[String] = None,
    project: Option[String] = None,
    requestTimeout: Duration = 90.seconds,
    store: Boolean = false,
    parallelToolCalls: Boolean = true,
    defaultOptions: Map[String, Json] = Map.empty
):
  require(baseUrl.nonEmpty, "baseUrl must not be empty")
  require(apiKey.nonEmpty, "apiKey must not be empty")
  require(defaultModel.nonEmpty, "defaultModel must not be empty")

  /** Responses 创建端点；`stripSuffix` 防止用户配置尾部斜线后产生双斜线。 */
  val responsesUrl: String = s"${baseUrl.stripSuffix("/")}/responses"

  /** 返回可安全进入日志的配置摘要。 API Key 永远只显示为 `<redacted>`，避免排障日志成为凭据泄漏通道。
    */
  override def toString: String =
    s"OpenAIResponsesConfig(baseUrl=$baseUrl, apiKey=<redacted>, defaultModel=$defaultModel, " +
      s"requestTimeout=$requestTimeout, store=$store, parallelToolCalls=$parallelToolCalls)"

object OpenAIResponsesConfig:
  /** 本 loader 读取 API Key 的环境变量名；管理面据此展示凭据来源，而不是猜一个名字。 */
  val ApiKeyVariable: String = "OPENAI_API_KEY"

  /** 可展示的凭据引用；只含变量名，不含值。 */
  val credentialReference: String = CredentialReference.environment(ApiKeyVariable)

  /** Responses 原生协议的 ZIO Config 描述。 Secret 不进入 case class 的自动打印路径；最终配置自身也覆盖了 `toString`，形成加载期和运行期两层脱敏。
    */
  val environmentConfig: Config[OpenAIResponsesConfig] =
    (
      Config.string("OPENAI_BASE_URL").withDefault("https://api.openai.com/v1") ++
        Config.secret(ApiKeyVariable) ++
        Config.string("OPENAI_MODEL") ++
        Config.string("OPENAI_ORGANIZATION").optional ++
        Config.string("OPENAI_PROJECT").optional
    ).mapAttempt { case (baseUrl, apiKey, model, organization, project) =>
      OpenAIResponsesConfig(
        baseUrl = baseUrl,
        apiKey = apiKey.stringValue,
        defaultModel = model,
        organization = organization.filter(_.trim.nonEmpty),
        project = project.filter(_.trim.nonEmpty)
      )
    }

  /** 从当前 ZIO ConfigProvider 构造原生 Responses 配置。
    *
    * @return
    *   缺少 `OPENAI_API_KEY` 或 `OPENAI_MODEL` 时以 `InvalidConfiguration` 快速失败
    */
  def fromEnvironment: IO[AgentError, OpenAIResponsesConfig] =
    ZIO
      .config(environmentConfig)
      .mapError(error => AgentError.InvalidConfiguration(s"OpenAI Responses 配置无效: $error"))

/** Responses 原生协议的静态能力声明；具体模型仍可在未来通过模型矩阵覆盖。 */
private[openai] object OpenAIResponsesDescriptor:
  val value: ProviderDescriptor = ProviderDescriptor(
    id = "openai-responses",
    displayName = "OpenAI Responses",
    protocol = "openai-responses",
    capabilities = ModelCapabilities(
      toolCalls = true,
      strictToolSchema = true,
      specificToolChoice = true,
      developerRole = true,
      thinking = true,
      vision = true,
      streaming = true,
      parallelToolCalls = true,
      usageReporting = true
    )
  )
