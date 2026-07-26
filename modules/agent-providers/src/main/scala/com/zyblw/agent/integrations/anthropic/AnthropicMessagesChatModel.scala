package com.zyblw.agent.integrations.anthropic

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

/** Anthropic Messages API 的原生 ZIO Provider。
  *
  * 它直接实现 Anthropic 的 content blocks、tool_use/tool_result 与 typed SSE，不经过 OpenAI 兼容层。 因此 extended thinking
  * block、工具回填顺序、stop_reason 和 usage 都能保持原生语义。
  *
  * @param client
  *   宿主统一管理的 ZIO HTTP Client，复用 TLS、连接池和取消语义
  * @param config
  *   Anthropic endpoint、凭据、协议版本与默认模型配置
  */
final class AnthropicMessagesChatModel(client: Client, config: AnthropicMessagesConfig) extends ModelProvider:
  val providerId: ProviderId                  = ProviderId(AnthropicMessagesDescriptor.value.id)
  override val descriptor: ProviderDescriptor = AnthropicMessagesDescriptor.value

  /** 发送非流式 Messages 请求并归一化文本、工具、usage 与 stop reason。 */
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    for
      json     <- ZIO.fromEither(AnthropicMessagesWire.encodeRequest(request, config, streaming = false))
      response <- client
        .batched(withHeaders(Request.post(config.messagesUrl, Body.fromString(json.toJson))))
        .timeoutFail(timeoutError)(config.requestTimeout)
        .mapError(mapTransportError)
      body    <- response.body.asString.mapError(mapTransportError)
      decoded <-
        if response.status.isSuccess then AnthropicMessagesWire.decodeResponse(body)
        else ZIO.fail(httpError(response.status.code, body))
    yield enrich(decoded, request)

  /** 发送 Anthropic typed SSE，并让 HTTP Body Scope 与下游 ZStream 生命周期一致。 消费者中断时 ZIO HTTP 会关闭连接，ProviderContract 的
    * cancellation probe 会验证这一点。
    */
  override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
    ZStream.unwrap {
      for
        json <- ZIO.fromEither(AnthropicMessagesWire.encodeRequest(request, config, streaming = true))
        httpRequest = withHeaders(Request.post(config.messagesUrl, Body.fromString(json.toJson)))
          .addHeader(Header.Accept(MediaType.text.`event-stream`))
        stream = client
          .stream(httpRequest) { response =>
            if response.status.isSuccess then AnthropicMessagesSse.events(response.body.asStream)
            else
              ZStream.fromZIO(
                response.body.asString.flatMap(body => ZIO.fail(httpError(response.status.code, body)))
              )
          }
          .mapError(mapTransportError)
          .timeoutFail(timeoutError)(config.requestTimeout)
          .map {
            case ModelStreamEvent.Completed(response) => ModelStreamEvent.Completed(enrich(response, request))
            case event                                => event
          }
      yield stream
    }

  /** 添加 Anthropic 必需的认证、协议版本和 JSON headers。 */
  private def withHeaders(request: Request): Request =
    request
      .addHeader("x-api-key", config.apiKey)
      .addHeader("anthropic-version", config.anthropicVersion)
      .addHeader(Header.ContentType(MediaType.application.json))

  /** 追加只含 Provider、模型和协议的安全元数据。 */
  private def enrich(response: ChatResponse, request: ChatRequest): ChatResponse =
    response.copy(metadata =
      response.metadata ++ Map(
        "provider" -> provider,
        "model"    -> request.settings.model.getOrElse(config.defaultModel),
        "protocol" -> descriptor.protocol
      )
    )

  /** 已归一化的 AgentError 保持不变，网络/TLS/连接异常映射为可重试 ModelFailure。 */
  private def mapTransportError(error: Throwable): AgentError = error match
    case value: AgentError => value
    case other             =>
      AgentError.ModelFailure(provider, "Anthropic transport failure", retryable = true, Some(other))

  /** HTTP 错误只记录状态与 Anthropic error type，不保存可能包含敏感请求片段的完整 body。 408、409、429、529 和全部 5xx 允许可靠性层退避重试。
    */
  private def httpError(status: Int, body: String): AgentError.ModelFailure =
    val retryable = status == 408 || status == 409 || status == 429 || status == 529 || status >= 500
    AgentError.ModelFailure(
      provider,
      s"Anthropic HTTP $status (${AnthropicMessagesWire.errorType(body).getOrElse("unknown_error")})",
      retryable
    )

  /** 单次请求超过部署预算后的稳定错误。 */
  private def timeoutError: AgentError =
    AgentError.ModelFailure(provider, "Anthropic request timed out", retryable = true)

object AnthropicMessagesChatModel:
  /** 从共享 Client 与环境配置创建 Provider。 */
  val layer: URLayer[Client & AnthropicMessagesConfig, ChatModel] =
    ZLayer.fromFunction(AnthropicMessagesChatModel.apply)

  /** 使用显式配置创建只依赖 Client 的 ZLayer，便于业务装配与 stub 测试。 */
  def configured(config: AnthropicMessagesConfig): URLayer[Client, ChatModel] =
    ZLayer.succeed(config) >>> layer
