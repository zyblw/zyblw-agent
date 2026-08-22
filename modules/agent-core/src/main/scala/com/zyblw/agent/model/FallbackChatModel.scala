package com.zyblw.agent.model

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

/** 有界、显式的 Provider 降级链。
  *
  * 只对 `retryable` 的模型/Provider 错误尝试下一个候选。能力不匹配、配置错误、安全拒绝一律 fail-closed， 避免“这个模型不会用工具，换一个会用的”悄悄改变 Agent
  * 行为。链在装配期冻结；运行中改候选需要重启。
  *
  * 降级事实只写入 `ModelSettings.metadata` 的低基数键，供 ModelCall 账本和遥测读取，不进入 prompt。
  */
final class FallbackChatModel private (
    val defaultProvider: String,
    val candidates: Chunk[ChatModel]
) extends ChatModel:
  val provider: String = "fallback"

  override val descriptor: ProviderDescriptor =
    ProviderDescriptor(provider, "Fallback router", "fallback", ModelCapabilities())

  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    invoke(request, stream = false).map(_._1)

  override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
    ZStream.unwrap {
      invoke(request, stream = true).map { case (response, used) =>
        val text = response.message.text
        ZStream.fromIterable(Option.when(text.nonEmpty)(ModelStreamEvent.TextDelta(text))) ++
          ZStream.succeed(
            ModelStreamEvent.Completed(
              response.copy(metadata = response.metadata.updated("fallback-used", used))
            )
          )
      }
    }

  private def invoke(request: ChatRequest, stream: Boolean): IO[AgentError, (ChatResponse, String)] =
    val preferred = request.settings.provider.getOrElse(defaultProvider)
    val ordered   = order(preferred)
    def loop(
        remaining: List[ChatModel],
        attempted: Chunk[String]
    ): IO[AgentError, (ChatResponse, String)] =
      remaining match
        case Nil =>
          ZIO.fail(
            AgentError.ModelFailure(
              preferred,
              s"降级链已耗尽: ${attempted.mkString(" -> ")}",
              retryable = false
            )
          )
        case head :: tail =>
          val nextRequest = request.copy(settings =
            request.settings.copy(
              provider = Some(head.provider),
              metadata = request.settings.metadata
                ++ Map("fallback-chain" -> ordered.map(_.provider).mkString(","))
                ++ Option.when(attempted.nonEmpty)("fallback-from" -> attempted.mkString(",")).toMap
            )
          )
          val call =
            head.capabilities(nextRequest.settings.model).flatMap { capabilities =>
              CapabilityValidator.validate(nextRequest, capabilities) *>
                (if stream then
                   head.stream(nextRequest).runCollect.flatMap { events =>
                     events.lastOption match
                       case Some(ModelStreamEvent.Completed(response)) => ZIO.succeed(response)
                       case _                                          =>
                         ZIO.fail(
                           AgentError.ModelFailure(head.provider, "fallback stream missing Completed", false)
                         )
                   }
                 else head.complete(nextRequest))
            }
          call.map(response => response -> head.provider).catchSome {
            case error if FallbackChatModel.retryable(error) && tail.nonEmpty =>
              loop(tail, attempted :+ head.provider)
          }
    loop(ordered.toList, Chunk.empty)

  private def order(preferred: String): Chunk[ChatModel] =
    val matching = candidates.filter(_.provider == preferred)
    val rest     = candidates.filterNot(_.provider == preferred)
    if matching.isEmpty then candidates else matching ++ rest

object FallbackChatModel:
  /** 装配降级链；候选必须至少两个且名称唯一。 */
  def make(defaultProvider: String, models: Iterable[ChatModel]): IO[AgentError, FallbackChatModel] =
    val entries = Chunk.fromIterable(models)
    val names   = entries.map(_.provider)
    val dup     = names.groupBy(identity).collect { case (name, group) if group.length > 1 => name }
    if entries.length < 2 then ZIO.fail(AgentError.InvalidConfiguration("降级链至少需要两个 Provider"))
    else if dup.nonEmpty then
      ZIO.fail(AgentError.InvalidConfiguration(s"降级链 Provider 重复: ${dup.toList.sorted.mkString(",")}"))
    else if !names.contains(defaultProvider) then
      ZIO.fail(AgentError.InvalidConfiguration(s"降级链缺少默认 Provider: $defaultProvider"))
    else ZIO.succeed(FallbackChatModel(defaultProvider, entries))

  def layer(defaultProvider: String, models: Iterable[ChatModel]): ZLayer[Any, AgentError, ChatModel] =
    ZLayer.fromZIO(make(defaultProvider, models))

  /** 能力不匹配、配置和安全错误不可降级；仅瞬时模型/HTTP 失败可换候选。 */
  private[model] def retryable(error: AgentError): Boolean = error match
    case _: AgentError.UnsupportedModelCapability => false
    case _: AgentError.InvalidConfiguration       => false
    case _: AgentError.ProviderNotFound           => false
    case other                                    => other.retryable
