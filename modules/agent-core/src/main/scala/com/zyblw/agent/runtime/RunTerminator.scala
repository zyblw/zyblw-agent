package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.{GuardrailContext, GuardrailEngine}
import com.zyblw.agent.memory.{ModelCallWrite, RunStore}
import zio.*

/** Run 终态的收口：完成、暂停、失败与取消。
  *
  * 这四条路径共用一条不变量：终态一旦写入就不能被迟到的写入覆盖。判定本身在 [[AgentKernel]]（返回 `None` 表示已终结、 不产生事件），这里只负责执行判定结果并处理与并发提交的竞争。
  */
final private[agent] class RunTerminator(
    store: RunStore,
    committer: RunCommitter,
    guardrails: GuardrailEngine,
    publisher: RunEventPublisher,
    activeRuns: Ref[Map[RunId, Fiber.Runtime[AgentError, RunOutcome]]]
):
  /** 在输出 Guardrail 通过后提交 Completed，并构造稳定公开结果。
    *
    * TX2 已写入最终助手消息但尚未把 Run 标为 Completed 时，恢复会走到这里收口，而不是再调一次 Provider。
    */
  def complete(
      state: AgentState,
      answer: AgentMessage,
      disposition: Option[CompletionDisposition] = None
  ): IO[AgentError, RunOutcome] =
    for
      decisions  <- guardrails.checkOutput(answer, guardrailContext(state))
      _          <- publisher.emitGuardrails(state.runId, "output", decisions)
      now        <- RunClock.instant
      transition <- ZIO.fromEither(AgentKernel.complete(state, answer, now, disposition))
      saved      <- committer.commitTransition(state, transition)
      outcome    <- ZIO.fromEither(AgentKernel.completedOutcome(saved))
    yield outcome

  /** 在任何受控副作用之前保存 ApprovalRequest 和完整工具游标。
    *
    * @param subject
    *   本次批准所授权的具体副作用。崩溃恢复类暂停不携带主体：它请求的是"是否允许重放一个结果未知的副作用"，与授权一次 新副作用不是同一个判定，重放边界仍由 `ToolRecoveryPolicy`
    *   与执行账本决定
    */
  def suspend(
      state: AgentState,
      call: ToolCall,
      risk: ToolRisk,
      reason: String,
      subject: Option[com.zyblw.agent.composition.ApprovalSubject] = None
  ): IO[AgentError, RunOutcome] =
    for
      now             <- RunClock.instant
      (_, transition) <- ZIO.fromEither(AgentKernel.suspend(state, call, risk, reason, subject, now))
      suspended       <- committer.commitTransition(state, transition)
      outcome         <- ZIO.fromEither(AgentKernel.suspendedOutcome(suspended))
    yield outcome

  /** 对非终态 Run 写入可跨进程观察的取消位，并提交 Cancelled 状态事件。 */
  def cancel(runId: RunId): IO[AgentError, Unit] =
    for
      state <- store.load(runId)
      _     <- ZIO.unless(AgentKernel.terminalStatus(state.status)) {
        for
          _     <- store.requestCancellation(runId)
          fiber <- activeRuns.modify(current => (current.get(runId), current - runId))
          _     <- ZIO.foreachDiscard(fiber)(_.interrupt)
          _     <- markCancelled(runId)
        yield ()
      }
    yield ()

  /** 尽力把主失败写成终态与安全事件；记录失败不能覆盖调用方收到的原始 AgentError。 */
  def markFailed(runId: RunId, error: AgentError): UIO[Unit] =
    (for
      current <- store.load(runId)
      now     <- RunClock.instant
      _       <- ZIO.foreachDiscard(AgentKernel.fail(current, error, now))(
        commitTerminalWithOpenCall(current, _, now.toEpochMilli, Some(error.category.toString))
      )
    yield ())
      .retry(
        Schedule.recurs(2) && Schedule.recurWhile[AgentError] {
          case _: AgentError.OptimisticLock => true
          case _                            => false
        }
      )
      .ignore

  /** 将 Fiber 中断或外部取消请求收敛为幂等耐久终态。
    *
    * 并发取消可能与非终态检查点提交竞争；遇到 OptimisticLock 时自动重读重试，若已被其他终态先行写入则由 AgentKernel 幂等跳过。
    */
  def markCancelled(runId: RunId): UIO[Unit] =
    (for
      state <- store.load(runId)
      now   <- RunClock.instant
      _     <- ZIO.foreachDiscard(AgentKernel.cancel(state, now))(
        commitTerminalWithOpenCall(state, _, now.toEpochMilli, Some(ErrorCategory.Conflict.toString))
      )
    yield ())
      .retry(
        Schedule.recurs(2) && Schedule.recurWhile[AgentError] {
          case _: AgentError.OptimisticLock => true
          case _                            => false
        }
      )
      .ignore

  /** 终态落盘时若仍存在悬挂的 Prepared/Dispatched 模型调用或 Prepared/Running 工具调用，一并收敛为 Unknown，绝不遗留孤儿进行中记录。 */
  private def commitTerminalWithOpenCall(
      current: AgentState,
      transition: AgentKernel.Transition,
      nowMs: Long,
      errorCategory: Option[String]
  ): IO[AgentError, Unit] =
    for
      calls <- store.getModelCalls(current.runId).orElseSucceed(Chunk.empty)
      openRecord = current.pendingModelCall
        .flatMap(pending => calls.find(_.requestId == pending.requestId))
        .orElse(calls.find(c => AgentKernel.openModelCall(c.status)))
        .filter(c => AgentKernel.openModelCall(c.status))
      modelWrite = openRecord.map { record =>
        ModelCallWrite.Transition(
          record.status,
          record.attempt,
          record.copy(
            status = ModelCallStatus.Unknown,
            errorCategory = errorCategory.orElse(record.errorCategory),
            updatedAtEpochMilli = nowMs
          )
        )
      }
      events = openRecord.fold(transition.events) { record =>
        AgentEvent.ModelCallUnknown(current.runId, record.requestId.asString, nowMs) +: transition.events
      }
      cleanedTransition = transition.copy(
        state = transition.state.copy(pendingModelCall = None, pendingToolPlan = None),
        events = events
      )
      _     <- committer.commitTransition(current, cleanedTransition, modelWrite)
      tools <- store.listToolExecutions(current.runId).orElseSucceed(Chunk.empty)
      openTools = tools.filter(t =>
        t.status == ToolExecutionStatus.Prepared || t.status == ToolExecutionStatus.Running
      )
      _ <- ZIO.foreachDiscard(openTools) { tool =>
        store
          .transitionToolExecution(
            tool.status,
            tool.attempt,
            tool.copy(status = ToolExecutionStatus.Unknown, updatedAtEpochMilli = nowMs)
          )
          .ignore
      }
    yield ()

  /** 构造各阶段 Guardrail 共用的只读可信上下文。 */
  private def guardrailContext(state: AgentState): GuardrailContext =
    GuardrailContext(state.runId, state.runContext, state.agentId)
