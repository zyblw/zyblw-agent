package com.zyblw.agent.integrations.gemini

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

/** Google Gemini Interactions API 的原生 ZIO Provider。
  *
  * 适配器直接实现 steps、function_call/function_result 和 typed SSE，不经过 OpenAI-compatible 翻译层。这样 function-call
  * ID、thought signature、usage 和协议升级都拥有独立契约测试。
  *
  * @param client
  *   宿主应用统一管理的 ZIO HTTP Client，负责连接池、TLS 和中断传播
  * @param config
  *   Gemini endpoint、Secret、默认模型和请求超时
  */
final class GeminiInteractionsChatModel(client: Client, config: GeminiInteractionsConfig)
    extends ModelProvider:
  val providerId: ProviderId                  = ProviderId(GeminiInteractionsDescriptor.value.id)
  override val descriptor: ProviderDescriptor = GeminiInteractionsDescriptor.value

  /** 执行一次非流式 interaction。
    *
    * @param request
    *   Provider-neutral 消息、工具与生成参数
    * @return
    *   归一化文本/工具调用、usage、结束原因与 interaction ID
    */
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    for
      json     <- ZIO.fromEither(GeminiInteractionsWire.encodeRequest(request, config, streaming = false))
      response <- client
        .batched(withHeaders(Request.post(config.interactionsUrl, Body.fromString(json.toJson))))
        .timeoutFail(timeoutError)(config.requestTimeout)
        .mapError(mapTransportError)
      body    <- response.body.asString.mapError(mapTransportError)
      decoded <-
        if response.status.isSuccess then GeminiInteractionsWire.decodeResponse(body)
        else ZIO.fail(httpError(response.status.code, body))
    yield enrich(decoded, request)

  /** 执行 typed SSE interaction。
    *
    * ZIO HTTP streaming 回调把 response body 与返回的 ZStream Scope 绑定。下游取消、预算超时或 Worker 失租时 Fiber 中断会关闭
    * socket，不会留下一个仍在消费 token 的后台请求。
    */
  override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
    ZStream.unwrap {
      for
        json <- ZIO.fromEither(GeminiInteractionsWire.encodeRequest(request, config, streaming = true))
        url         = s"${config.interactionsUrl}?alt=sse"
        httpRequest = withHeaders(Request.post(url, Body.fromString(json.toJson)))
          .addHeader(Header.Accept(MediaType.text.`event-stream`))
        responseStream = client
          .stream(httpRequest) { response =>
            if response.status.isSuccess then GeminiInteractionsSse.events(response.body.asStream)
            else
              ZStream.fromZIO(
                response.body.asString.flatMap(body => ZIO.fail(httpError(response.status.code, body)))
              )
          }
          .mapError(mapTransportError)
          .timeoutFail(timeoutError)(config.requestTimeout)
          .map {
            case ModelStreamEvent.Completed(response) =>
              ModelStreamEvent.Completed(enrich(response, request))
            case event => event
          }
      yield responseStream
    }

  /** 添加 Gemini API Key、JSON Content-Type 和新 steps schema 的显式 revision。 */
  private def withHeaders(request: Request): Request =
    request
      .addHeader("x-goog-api-key", config.apiKey)
      .addHeader("Api-Revision", "2026-05-20")
      .addHeader(Header.ContentType(MediaType.application.json))

  /** 补充可安全记录的 Provider、模型与协议元数据，不包含 Secret 或原始 steps。 */
  private def enrich(response: ChatResponse, request: ChatRequest): ChatResponse =
    response.copy(metadata =
      response.metadata ++ Map(
        "provider" -> provider,
        "model"    -> request.settings.model.getOrElse(config.defaultModel),
        "protocol" -> descriptor.protocol
      )
    )

  /** 已经归一化的错误保持不变；网络、TLS 和连接关闭统一视为可重试模型故障。 */
  private def mapTransportError(error: Throwable): AgentError = error match
    case value: AgentError => value
    case other => AgentError.ModelFailure(provider, "Gemini transport failure", retryable = true, Some(other))

  /** HTTP 错误只保留状态与 Google error status，永不保存完整 response body。 */
  private def httpError(status: Int, body: String): AgentError.ModelHttpFailure =
    AgentError.ModelHttpFailure(provider, status, GeminiInteractionsWire.errorStatus(body))

  /** 完整请求或流超过部署预算时使用的稳定、可重试错误。 */
  private def timeoutError: AgentError =
    AgentError.ModelFailure(provider, "Gemini request timed out", retryable = true)

object GeminiInteractionsChatModel:
  /** 从共享 Client 与环境配置创建 Provider。 */
  val layer: URLayer[Client & GeminiInteractionsConfig, ChatModel] =
    ZLayer.fromFunction(GeminiInteractionsChatModel.apply)

  /** 使用显式配置创建只依赖 Client 的 Layer。
    *
    * @param config
    *   已从 Secret Manager/环境变量构造且通过 require 校验的配置
    */
  def configured(config: GeminiInteractionsConfig): URLayer[Client, ChatModel] =
    ZLayer.succeed(config) >>> layer
