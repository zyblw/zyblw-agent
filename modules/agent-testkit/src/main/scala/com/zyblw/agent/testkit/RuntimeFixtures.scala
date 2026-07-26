package com.zyblw.agent.testkit

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.stream.*

/** 记录所有请求的 Provider 包装器，用于统一契约和回归测试。 */
final class RecordingModelProvider(underlying: ChatModel, requests: Ref[Chunk[ChatRequest]])
    extends ChatModel:
  def provider: String                        = underlying.provider
  override def descriptor: ProviderDescriptor = underlying.descriptor
  def complete(request: ChatRequest): IO[com.zyblw.agent.core.AgentError, ChatResponse] =
    requests.update(_ :+ request) *> underlying.complete(request)
  override def stream(request: ChatRequest): ZStream[Any, com.zyblw.agent.core.AgentError, ModelStreamEvent] =
    ZStream.fromZIO(requests.update(_ :+ request)) *> underlying.stream(request)
  def recorded: UIO[Chunk[ChatRequest]] = requests.get

object RecordingModelProvider:
  def make(underlying: ChatModel): UIO[RecordingModelProvider] =
    Ref.make(Chunk.empty[ChatRequest]).map(RecordingModelProvider(underlying, _))

final class FixedTokenCounter(tokens: Long) extends com.zyblw.agent.context.TokenCounter:
  def count(text: String): UIO[Long] = ZIO.succeed(tokens)

trait IdGenerator:
  def nextRunId: UIO[RunId]

final class DeterministicIdGenerator private (ids: Ref[Chunk[RunId]]) extends IdGenerator:
  def nextRunId: UIO[RunId] = ids.modify {
    case values if values.nonEmpty => (values.head, values.tail)
    case _                         => throw IllegalStateException("deterministic ids exhausted")
  }

object DeterministicIdGenerator:
  def make(values: Chunk[RunId]): UIO[DeterministicIdGenerator] =
    Ref.make(values).map(DeterministicIdGenerator(_))
