package com.zyblw.agent.workflow

// 覆盖节点边界事件、暂停恢复和显式 fan-out/join，确保工作流行为可观察且可重放。

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object WorkflowSpec extends ZIOSpecDefault:
  private def context(runId: RunId, sessionId: SessionId) = WorkflowContext(runId, sessionId)

  def spec = suite("WorkflowEngine")(
    test("每个节点输出 Started/Completed，并在最终节点完成") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowCheckpointStore[Int]]
        entry  = NodeId("entry")
        finish = NodeId("finish")
        nodes  = Map[NodeId, WorkflowNode[Any, Int]](
          entry  -> node(entry)(state => ZIO.succeed(NodeResult.Next(state + 1, finish))),
          finish -> node(finish)(state => ZIO.succeed(NodeResult.Complete(state + 1)))
        )
        engine = new WorkflowEngine[Any, Int](nodes, store, sumReducer)
        events <- engine.run(entry, 0, context(runId, sessionId)).runCollect
      yield assertTrue(
        events == Chunk(
          WorkflowEvent.NodeStarted(entry, 0),
          WorkflowEvent.NodeCompleted(entry, 0, 1),
          WorkflowEvent.NodeStarted(finish, 1),
          WorkflowEvent.Completed(2)
        )
      )
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("暂停后从同一节点和状态恢复") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowCheckpointStore[Int]]
        approval = NodeId("approval")
        nodes    = Map[NodeId, WorkflowNode[Any, Int]](
          approval -> node(approval) { state =>
            if state == 0 then ZIO.succeed(NodeResult.Suspend(1, "等待审批"))
            else ZIO.succeed(NodeResult.Complete(state + 1))
          }
        )
        engine = new WorkflowEngine[Any, Int](nodes, store, sumReducer)
        first   <- engine.run(approval, 0, context(runId, sessionId)).runCollect
        resumed <- engine.resume(context(runId, sessionId)).runCollect
      yield assertTrue(
        first.lastOption.contains(WorkflowEvent.Suspended(approval, "等待审批", 1)),
        resumed == Chunk(WorkflowEvent.NodeStarted(approval, 0), WorkflowEvent.Completed(2))
      )
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("fan-out 使用显式 join，worker 各执行一次") {
      for
        runId      <- RunId.random
        sessionId  <- SessionId.random
        executions <- Ref.make(0)
        store      <- ZIO.service[WorkflowCheckpointStore[Int]]
        start   = NodeId("start")
        worker1 = NodeId("worker-1")
        worker2 = NodeId("worker-2")
        join    = NodeId("join")
        nodes   = Map[NodeId, WorkflowNode[Any, Int]](
          start -> node(start)(state =>
            ZIO.succeed(NodeResult.FanOut(state, NonEmptyChunk(worker1, worker2), join))
          ),
          worker1 -> node(worker1)(state => executions.update(_ + 1).as(NodeResult.Complete(state + 1))),
          worker2 -> node(worker2)(state => executions.update(_ + 1).as(NodeResult.Complete(state + 2))),
          join    -> node(join)(state => ZIO.succeed(NodeResult.Complete(state)))
        )
        engine = new WorkflowEngine[Any, Int](nodes, store, sumReducer, maxParallelism = 2)
        events <- engine.run(start, 0, context(runId, sessionId)).runCollect
        count  <- executions.get
      yield assertTrue(count == 2, events.lastOption.contains(WorkflowEvent.Completed(3)))
    }.provide(WorkflowCheckpointStore.inMemory[Int])
  )

  private def node(id0: NodeId)(run: Int => IO[WorkflowError, NodeResult[Int]]): WorkflowNode[Any, Int] =
    new WorkflowNode[Any, Int]:
      val id                                                                                = id0
      def execute(state: Int, context: WorkflowContext): IO[WorkflowError, NodeResult[Int]] = run(state)

  private val sumReducer = new StateReducer[Int]:
    def merge(base: Int, branches: Chunk[Int]): IO[WorkflowError, Int] = ZIO.succeed(base + branches.sum)
