package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object WorkflowSubgraphSpec extends ZIOSpecDefault:
  private def node(id0: NodeId)(run: Int => UIO[NodeOutcome[Int]]): WorkflowNode[Any, Int] =
    new WorkflowNode[Any, Int]:
      val id                                                                   = id0
      def execute(state: Int, context: WorkflowContext): UIO[NodeOutcome[Int]] = run(state)

  private val reducer = new StateReducer[Int]:
    def merge(base: Int, branches: Chunk[Int]): UIO[Int] = ZIO.succeed(base + branches.sum)

  def spec = suite("WorkflowSubgraph")(
    test("子图完成后父节点成功，且使用独立 checkpoint") {
      val childEntry = NodeId("child-entry")
      val child      = WorkflowDefinition
        .make(
          WorkflowId("child"),
          WorkflowVersion(1),
          childEntry,
          Map(childEntry -> node(childEntry)(state => ZIO.succeed(NodeOutcome.Succeeded(state + 3)))),
          Map(childEntry -> WorkflowTransition.Complete())
        )
        .fold(issues => throw IllegalArgumentException(issues.map(_.message).mkString("; ")), identity)
      val parentEntry = NodeId("parent-entry")
      (for
        parentStore <- ZIO.service[WorkflowCheckpointStore[Int]]
        outcome     <- (
          for
            childStore <- ZIO.service[WorkflowCheckpointStore[Int]]
            subgraph = WorkflowSubgraph.node(parentEntry, child, childStore, reducer)
            parent   = WorkflowDefinition
              .make(
                WorkflowId("parent"),
                WorkflowVersion(1),
                parentEntry,
                Map(parentEntry -> subgraph),
                Map(parentEntry -> WorkflowTransition.Complete())
              )
              .fold(issues => throw IllegalArgumentException(issues.map(_.message).mkString("; ")), identity)
            runId     <- RunId.random
            sessionId <- SessionId.random
            events    <- WorkflowEngine
              .make(parent, parentStore, reducer)
              .run(1, WorkflowContext(runId, sessionId))
              .runCollect
            parentCheckpoint <- parentStore.load(runId)
            childCheckpoint  <- childStore.load(runId)
          yield assertTrue(
            events.lastOption.contains(WorkflowEvent.Completed(4)),
            parentCheckpoint.exists(_.cursor == WorkflowCursor.Completed),
            childCheckpoint.exists(_.workflowId == WorkflowId("child"))
          )
        ).provide(WorkflowCheckpointStore.inMemory[Int])
      yield outcome).provide(WorkflowCheckpointStore.inMemory[Int])
    }
  )
