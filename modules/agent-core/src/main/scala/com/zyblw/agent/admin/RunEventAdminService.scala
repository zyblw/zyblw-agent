package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import com.zyblw.agent.runtime.{AgentRuntime, DurableRunEventStream}
import zio.*
import zio.stream.ZStream

/** 管理台读取单个 Run 低敏耐久事件的窄能力。
  *
  * HTTP 层必须先完成 `agent:admin:read` 授权，并把内部事件投影成不含业务正文的管理视图。本服务只负责在响应头发出前 验证 Run
  * 与游标，并返回已经具备跨节点恢复、连续性校验和背压语义的内部耐久流；它不是可直接序列化的 wire API。
  */
trait RunEventAdminService:
  /** 打开 `afterSequence` 之后的事件流。
    *
    * 返回外层 `IO` 是有意的：不存在的 Run 和超前游标必须在创建 SSE 响应前成为正常 HTTP 错误，而不能退化成 `200 OK` 后才发送 `stream_error`。
    */
  def open(
      runId: RunId,
      afterSequence: Long
  ): IO[AgentError, ZStream[Any, AgentError, PersistedAgentEvent]]

final class RunEventAdminServiceLive(
    runtime: AgentRuntime,
    events: DurableRunEventStream
) extends RunEventAdminService:
  def open(
      runId: RunId,
      afterSequence: Long
  ): IO[AgentError, ZStream[Any, AgentError, PersistedAgentEvent]] =
    if afterSequence < -1L then ZIO.fail(AgentError.InvalidConfiguration("事件游标不能小于 -1"))
    else
      runtime.inspect(runId).flatMap { state =>
        ZIO
          .fail(
            AgentError.InvalidConfiguration(
              s"事件游标 $afterSequence 超过 Run 当前最后序号 ${state.lastEventSequence}"
            )
          )
          .when(afterSequence > state.lastEventSequence)
          .as(events.events(runId, afterSequence))
      }

object RunEventAdminService:
  def make(runtime: AgentRuntime, events: DurableRunEventStream): RunEventAdminService =
    RunEventAdminServiceLive(runtime, events)

  val layer: URLayer[AgentRuntime & DurableRunEventStream, RunEventAdminService] =
    ZLayer.fromFunction(RunEventAdminServiceLive.apply)
