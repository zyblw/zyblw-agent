package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.guardrails.{GuardrailContext, GuardrailEngine}
import com.zyblw.agent.memory.RunStore
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
  def complete(state: AgentState, answer: AgentMessage): IO[AgentError, RunOutcome] =
    for
      decisions  <- guardrails.checkOutput(answer, guardrailContext(state))
      _          <- publisher.emitGuardrails(state.runId, "output", decisions)
      now        <- RunClock.instant
      transition <- ZIO.fromEither(AgentKernel.complete(state, answer, now))
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
        committer.commitBestEffort(current, _)
      )
    yield ()).ignore

  /** 将 Fiber 中断或外部取消请求收敛为幂等耐久终态。
    *
    * 并发取消可能与完成提交竞争；乐观锁失败会被忽略，最终状态始终由数据库中先成功的终态决定。
    */
  def markCancelled(runId: RunId): UIO[Unit] =
    (for
      state <- store.load(runId)
      now   <- RunClock.instant
      _     <- ZIO.foreachDiscard(AgentKernel.cancel(state, now))(committer.commitBestEffort(state, _))
    yield ()).ignore

  /** 构造各阶段 Guardrail 共用的只读可信上下文。 */
  private def guardrailContext(state: AgentState): GuardrailContext =
    GuardrailContext(state.runId, state.runContext, state.agentId)
