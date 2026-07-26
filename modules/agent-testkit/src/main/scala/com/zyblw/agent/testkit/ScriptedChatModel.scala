package com.zyblw.agent.testkit

// 可编排模型替身：按预设顺序返回响应并记录请求，用于确定性验证多轮 Agent Loop。

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.stream.*

final class ScriptedChatModel private (responses: Ref[Chunk[ChatResponse]], requests: Ref[Chunk[ChatRequest]])
    extends ChatModel:
  val provider: String = "scripted"

  def complete(request: ChatRequest): IO[com.zyblw.agent.core.AgentError, ChatResponse] =
    requests.update(_ :+ request) *>
      responses.modify {
        case chunk if chunk.nonEmpty => (Right(chunk.head), chunk.tail)
        case empty => (Left(AgentError.ModelFailure(provider, "script exhausted", false)), empty)
      }.absolve

  def recordedRequests: UIO[Chunk[ChatRequest]] = requests.get

  override def stream(request: ChatRequest): ZStream[Any, com.zyblw.agent.core.AgentError, ModelStreamEvent] =
    ZStream.fromZIO(complete(request)).flatMap { response =>
      val text = response.message.text
      ZStream.fromIterable(Option.when(text.nonEmpty)(ModelStreamEvent.TextDelta(text))) ++
        ZStream.succeed(ModelStreamEvent.Completed(response))
    }

object ScriptedChatModel:
  def make(script: Chunk[ChatResponse]): UIO[ScriptedChatModel] =
    for
      responses <- Ref.make(script)
      requests  <- Ref.make(Chunk.empty[ChatRequest])
    yield ScriptedChatModel(responses, requests)

  def layer(script: Chunk[ChatResponse]): ULayer[ChatModel] = ZLayer.fromZIO(make(script))
