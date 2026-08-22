package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import zio.*

/** 把已校验的子图当作一个节点。子图必须使用独立 checkpoint store，不能覆盖父 Run 游标。 */
object WorkflowSubgraph:
  def node[R, S](
      nodeId: NodeId,
      child: WorkflowDefinition[R, S],
      store: WorkflowCheckpointStore[S],
      reducer: StateReducer[S]
  ): WorkflowNode[R, S] =
    new WorkflowNode[R, S]:
      val id: NodeId = nodeId

      def execute(state: S, context: WorkflowContext): ZIO[R, WorkflowError, NodeOutcome[S]] =
        WorkflowEngine
          .make(child, store, reducer)
          .run(state, context)
          .runCollect
          .flatMap { events =>
            events.lastOption match
              case Some(WorkflowEvent.Completed(next)) =>
                ZIO.succeed(NodeOutcome.Succeeded(next))
              case Some(WorkflowEvent.Suspended(_, reason, next)) =>
                ZIO.succeed(NodeOutcome.Suspended(next, reason))
              case Some(_) =>
                ZIO.fail(AgentError.WorkflowFailed(nodeId.value, "subgraph-did-not-complete"))
              case None =>
                ZIO.fail(AgentError.WorkflowFailed(nodeId.value, "subgraph-empty"))
          }
