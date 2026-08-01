package com.zyblw.agent.examples

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.workflow.*
import zio.*

/** 展示业务接入 durable signal 与受监督 wake worker 的最小完整路径。
  *
  * 示例使用内存 Store 便于直接运行；生产只需把 Store 换成 `PostgresAgentPersistence.workflowExecutions`，节点定义、signal 幂等契约和 Worker
  * 生命周期保持不变。HTTP webhook 只调用 `signal`，不在请求 Fiber 中恢复 Workflow。
  */
object DurableWorkflowWakeExample extends ZIOAppDefault:
  final case class ApprovalState(approved: Boolean)

  private val workflowId = WorkflowId("approval-example")
  private val version    = WorkflowVersion(1)
  private val approval   = NodeId("approval")
  private val signalName = WorkflowSignalName("approval.received")

  private val approvalNode = new WorkflowNode[Any, ApprovalState]:
    val id = approval

    def execute(
        state: ApprovalState,
        context: WorkflowContext
    ): IO[WorkflowError, NodeOutcome[ApprovalState]] =
      context.wakeup match
        case Some(WorkflowWakeup.SignalReceived(_, value)) if value.name == signalName =>
          ZIO.succeed(NodeOutcome.Succeeded(state.copy(approved = true)))
        case Some(WorkflowWakeup.DeadlineElapsed(_, _)) =>
          ZIO.succeed(NodeOutcome.Succeeded(state))
        case _ =>
          Clock.instant.map(now =>
            NodeOutcome.Awaiting(
              state,
              WorkflowWaitRequest(
                WorkflowWaitCondition.Signal(signalName),
                now.plusSeconds(30)
              )
            )
          )

  private val definition = WorkflowDefinition
    .make(
      workflowId,
      version,
      approval,
      Map(approval -> approvalNode),
      Map(approval -> WorkflowTransition.Complete())
    )
    .fold(
      issues => throw new IllegalArgumentException(issues.map(_.message).mkString("; ")),
      identity
    )

  private val reducer = new StateReducer[ApprovalState]:
    def merge(
        base: ApprovalState,
        branches: Chunk[ApprovalState]
    ): IO[WorkflowError, ApprovalState] = ZIO.succeed(base)

  def run: ZIO[Any, Any, Unit] =
    (for
      store     <- ZIO.service[WorkflowExecutionStore[ApprovalState]]
      observer  <- ZIO.service[WorkflowWakeObserver]
      runId     <- RunId.random
      sessionId <- SessionId.random
      engine = WorkflowEngine.makeDurable(
        definition,
        store,
        reducer,
        WorkflowExecutionPolicy(WorkerId("approval-node-worker"))
      )
      first <- engine
        .run(ApprovalState(approved = false), WorkflowContext(runId, sessionId))
        .runCollect
      wait <- store
        .currentWait(runId)
        .someOrFail(AgentError.PersistenceFailure("approval wait missing"))
      receipt <- store.signal(
        wait.key,
        WorkflowSignalId("example-webhook-1"),
        signalName,
        "approved"
      )
      worker = WorkflowWakeWorker(
        WorkerId("approval-wake-worker"),
        store,
        engine,
        observer,
        WorkflowWakeWorkerConfig()
      )
      cycle      <- worker.runOnce
      checkpoint <- store.load(runId).someOrFail(AgentError.PersistenceFailure("checkpoint missing"))
      wasWaiting = first.exists {
        case WorkflowEvent.Waiting(_, _, _, _, _) => true
        case _                                    => false
      }
      _ <- Console.printLine(
        s"waiting=$wasWaiting " +
          s"signal=${receipt.disposition} completed=${cycle.completed} state=${checkpoint.state}"
      )
    yield ()).provide(
      WorkflowExecutionStore.inMemory[ApprovalState],
      WorkflowWakeObserver.logging
    )
