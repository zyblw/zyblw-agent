package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.GuardrailDecision
import zio.*
import zio.stream.*

/** 进程内实时事件的唯一发布出口。
  *
  * 观察者与 SSE 共用同一领域类型，但只有 `RunStore` 中的精选事件承担审计职责：本发布器会丢事件（有界队列 + 慢消费者 断开），因此需要断线续传或审计的消费者必须读
  * `RunStore.events`，不能依赖这里。
  *
  * 全部方法返回 `UIO`：观测缺陷不得反向破坏 Agent 的业务结果。
  */
private[agent] trait RunEventPublisher:
  /** 发布一条已经过安全边界筛选、可以暴露给业务客户端的事件。 */
  def emit(event: AgentEvent): UIO[Unit]

  /** 把 Guardrail 的每条命名判定转换成可观测事件。
    *
    * @param stage
    *   `input`、`run`、`retrieval`、`tool.before`、`tool.after`、`remote` 或 `output`
    */
  def emitGuardrails(
      runId: RunId,
      stage: String,
      decisions: Chunk[(String, GuardrailDecision)]
  ): UIO[Unit]

  /** 把一个 Run effect 转换成带背压的 `AgentEvent` 流。
    *
    * @return
    *   绑定当前 Scope 的事件流；释放 Scope 会中断 Fiber、持久化取消并关闭队列
    */
  def stream(
      runId: RunId,
      effect: IO[AgentError, RunOutcome],
      onInterrupt: RunId => UIO[Unit]
  ): ZIO[Scope, Nothing, ZStream[Any, AgentError, AgentEvent]]

object RunEventPublisher:
  /** 队列容量刻意有界：饱和时明确背压，不无限积压。 */
  private val StreamQueueCapacity = 256

  def make(
      observer: RunObserver,
      activeRuns: Ref[Map[RunId, Fiber.Runtime[AgentError, RunOutcome]]]
  ): URIO[Scope, RunEventPublisher] =
    FiberRef
      .make(Option.empty[Queue[Take[AgentError, AgentEvent]]])
      .map(new Live(observer, activeRuns, _))

  final private class Live(
      observer: RunObserver,
      activeRuns: Ref[Map[RunId, Fiber.Runtime[AgentError, RunOutcome]]],
      eventQueue: FiberRef[Option[Queue[Take[AgentError, AgentEvent]]]]
  ) extends RunEventPublisher:
    def emit(event: AgentEvent): UIO[Unit] =
      observer.emit(event) *> eventQueue.get.flatMap {
        case Some(queue) => queue.offer(Take.single(event)).unit
        case None        => ZIO.unit
      }

    def emitGuardrails(
        runId: RunId,
        stage: String,
        decisions: Chunk[(String, GuardrailDecision)]
    ): UIO[Unit] =
      if decisions.isEmpty then ZIO.unit
      else
        RunClock.millis.flatMap { at =>
          ZIO.foreachDiscard(decisions) { case (name, decision) =>
            emit(AgentEvent.GuardrailEvaluated(runId, s"$stage:$name", decision.allowed, at))
          }
        }

    /** 终止 `Take` 由 daemon offer 投递：正常消费者继续拉取时它最终进入队列；消费者已断开且队列已满时，Scope finalizer 会 shutdown 队列并唤醒该
      * offer，避免不可中断的 `onExit` 自身阻塞。
      */
    def stream(
        runId: RunId,
        effect: IO[AgentError, RunOutcome],
        onInterrupt: RunId => UIO[Unit]
    ): ZIO[Scope, Nothing, ZStream[Any, AgentError, AgentEvent]] =
      for
        queue <- Queue.bounded[Take[AgentError, AgentEvent]](StreamQueueCapacity)
        fiber <- eventQueue
          .locally(Some(queue))(
            effect
              .onInterrupt(onInterrupt(runId))
              .onExit {
                case Exit.Success(_)     => queue.offer(Take.end).forkDaemon.unit
                case Exit.Failure(cause) => queue.offer(Take.failCause(cause)).forkDaemon.unit
              }
          )
          .forkScoped
        _ <- activeRuns.update(_.updated(runId, fiber))
        _ <- ZIO.addFinalizer(activeRuns.update(_ - runId) *> fiber.interrupt.unit *> queue.shutdown)
      yield ZStream.fromQueue(queue).flattenTake
