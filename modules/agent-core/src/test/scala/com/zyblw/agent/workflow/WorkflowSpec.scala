package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import zio.*
import zio.test.*

/** 显式边、静态校验、节点 checkpoint、有界循环与结构化 fan-out 的确定性契约。 */
object WorkflowSpec extends ZIOSpecDefault:
  private val testWorkflowId      = WorkflowId("workflow-spec")
  private val testWorkflowVersion = WorkflowVersion(1)

  private def context(runId: RunId, sessionId: SessionId) = WorkflowContext(runId, sessionId)

  def spec = suite("WorkflowEngine")(
    test("Workflow、版本和节点 ID 在配置边界使用有界安全格式") {
      assertTrue(
        WorkflowId.fromString("article-review.v2").contains(WorkflowId("article-review.v2")),
        WorkflowId.fromString("../other").isLeft,
        WorkflowId.fromString("line\nbreak").isLeft,
        WorkflowVersion.fromInt(1).contains(WorkflowVersion(1)),
        WorkflowVersion.fromInt(0).isLeft,
        NodeId.fromString("review_1").contains(NodeId("review_1")),
        NodeId.fromString("review/../../secret").isLeft
      )
    },
    test("显式静态边逐节点执行并保存 Completed checkpoint") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowCheckpointStore[Int]]
        entry      = NodeId("entry")
        finish     = NodeId("finish")
        definition = validDefinition(
          entry,
          Map(
            entry  -> node(entry)(state => ZIO.succeed(NodeOutcome.Succeeded(state + 1))),
            finish -> node(finish)(state => ZIO.succeed(NodeOutcome.Succeeded(state + 1)))
          ),
          Map(
            entry  -> WorkflowTransition.Next(finish),
            finish -> WorkflowTransition.Complete()
          )
        )
        engine = WorkflowEngine.make(definition, store, sumReducer)
        events     <- engine.run(0, context(runId, sessionId)).runCollect
        checkpoint <- store.load(runId)
        resumed    <- engine.resume(context(runId, sessionId)).runCollect
      yield assertTrue(
        events == Chunk(
          WorkflowEvent.NodeStarted(entry, 0, 1),
          WorkflowEvent.NodeCompleted(entry, 0, 1),
          WorkflowEvent.NodeStarted(finish, 1, 1),
          WorkflowEvent.NodeCompleted(finish, 1, 2),
          WorkflowEvent.Completed(2)
        ),
        checkpoint.contains(
          savedCheckpoint(
            sessionId,
            WorkflowCursor.Completed,
            2,
            2,
            Map(entry -> 1, finish -> 1)
          )
        ),
        resumed == Chunk(WorkflowEvent.Completed(2))
      )
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("暂停后从同一节点和持久化访问预算恢复") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowCheckpointStore[Int]]
        approval   = NodeId("approval")
        definition = validDefinition(
          approval,
          Map(
            approval -> node(approval) { state =>
              if state == 0 then ZIO.succeed(NodeOutcome.Suspended(1, "等待审批"))
              else ZIO.succeed(NodeOutcome.Succeeded(state + 1))
            }
          ),
          Map(approval -> WorkflowTransition.Complete())
        )
        engine = WorkflowEngine.make(definition, store, sumReducer)
        first   <- engine.run(0, context(runId, sessionId)).runCollect
        resumed <- engine.resume(context(runId, sessionId)).runCollect
      yield assertTrue(
        first == Chunk(
          WorkflowEvent.NodeStarted(approval, 0, 1),
          WorkflowEvent.Suspended(approval, "等待审批", 1)
        ),
        resumed == Chunk(
          WorkflowEvent.NodeStarted(approval, 1, 2),
          WorkflowEvent.NodeCompleted(approval, 1, 2),
          WorkflowEvent.Completed(2)
        )
      )
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("fan-out 显式声明 AllSucceeded 和 join，分支按目标顺序确定性归并") {
      for
        runId      <- RunId.random
        sessionId  <- SessionId.random
        executions <- Ref.make(0)
        store      <- ZIO.service[WorkflowCheckpointStore[Int]]
        start      = NodeId("start")
        worker1    = NodeId("worker-1")
        worker2    = NodeId("worker-2")
        join       = NodeId("join")
        definition = validDefinition(
          start,
          Map(
            start   -> node(start)(state => ZIO.succeed(NodeOutcome.Succeeded(state))),
            worker1 -> node(worker1)(state => executions.update(_ + 1).as(NodeOutcome.Succeeded(state + 1))),
            worker2 -> node(worker2)(state => executions.update(_ + 1).as(NodeOutcome.Succeeded(state + 2))),
            join    -> node(join)(state => ZIO.succeed(NodeOutcome.Succeeded(state)))
          ),
          Map(
            start -> WorkflowTransition.FanOut(
              NonEmptyChunk(worker1, worker2),
              join,
              FanInPolicy.AllSucceeded
            ),
            worker1 -> WorkflowTransition.Complete(),
            worker2 -> WorkflowTransition.Complete(),
            join    -> WorkflowTransition.Complete()
          )
        )
        engine = WorkflowEngine.make(definition, store, sumReducer, maxParallelism = 2)
        events     <- engine.run(0, context(runId, sessionId)).runCollect
        count      <- executions.get
        checkpoint <- store.load(runId)
      yield assertTrue(
        count == 2,
        events.contains(
          WorkflowEvent.FanOutStarted(
            start,
            Chunk(worker1, worker2),
            join,
            FanInPolicy.AllSucceeded,
            0
          )
        ),
        events.contains(WorkflowEvent.FanOutCompleted(start, 2, 0)),
        events.contains(WorkflowEvent.NodeStarted(join, 3, 1)),
        events.lastOption.contains(WorkflowEvent.Completed(3)),
        checkpoint.contains(
          savedCheckpoint(
            sessionId,
            WorkflowCursor.Completed,
            3,
            4,
            Map(start -> 1, worker1 -> 1, worker2 -> 1, join -> 1)
          )
        )
      )
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("fan-out 在启动分支前预检全局节点预算") {
      for
        runId      <- RunId.random
        sessionId  <- SessionId.random
        executions <- Ref.make(0)
        store      <- ZIO.service[WorkflowCheckpointStore[Int]]
        start      = NodeId("start")
        worker     = NodeId("worker")
        join       = NodeId("join")
        definition = validDefinition(
          start,
          Map(
            start  -> node(start)(state => ZIO.succeed(NodeOutcome.Succeeded(state))),
            worker -> node(worker)(state => executions.update(_ + 1).as(NodeOutcome.Succeeded(state))),
            join   -> node(join)(state => ZIO.succeed(NodeOutcome.Succeeded(state)))
          ),
          Map(
            start -> WorkflowTransition.FanOut(
              NonEmptyChunk(worker),
              join,
              FanInPolicy.AllSucceeded
            ),
            worker -> WorkflowTransition.Complete(),
            join   -> WorkflowTransition.Complete()
          )
        )
        exit <- WorkflowEngine
          .make(definition, store, sumReducer, maxSteps = 2)
          .run(0, context(runId, sessionId))
          .runCollect
          .exit
        count      <- executions.get
        checkpoint <- store.load(runId)
      yield assertTrue(exit.isFailure, count == 0, checkpoint.isEmpty)
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("静态校验一次报告缺失目标、不可达节点和没有访问上限的循环") {
      val entry       = NodeId("entry")
      val loop        = NodeId("loop")
      val missing     = NodeId("missing")
      val unreachable = NodeId("unreachable")
      val nodes       = Map[NodeId, WorkflowNode[Any, Int]](
        entry       -> node(entry)(state => ZIO.succeed(NodeOutcome.Succeeded(state))),
        loop        -> node(loop)(state => ZIO.succeed(NodeOutcome.Succeeded(state))),
        unreachable -> node(unreachable)(state => ZIO.succeed(NodeOutcome.Succeeded(state)))
      )
      val issues = WorkflowDefinition.validate(
        entry,
        nodes,
        Map(
          entry       -> WorkflowTransition.Next(loop),
          loop        -> WorkflowTransition.Route(NonEmptyChunk(loop, missing), _ => Right(loop)),
          unreachable -> WorkflowTransition.Complete()
        )
      )
      assertTrue(
        issues.contains(WorkflowValidationIssue.TransitionTargetMissing(loop, missing)),
        issues.contains(WorkflowValidationIssue.NodeUnreachable(unreachable)),
        issues.contains(WorkflowValidationIssue.CycleVisitLimitMissing(loop))
      )
    },
    test("运行时拒绝 Route 选择未声明目标") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowCheckpointStore[Int]]
        entry      = NodeId("entry")
        declared   = NodeId("declared")
        rogue      = NodeId("rogue")
        definition = validDefinition(
          entry,
          Map(
            entry    -> node(entry)(state => ZIO.succeed(NodeOutcome.Succeeded(state))),
            declared -> node(declared)(state => ZIO.succeed(NodeOutcome.Succeeded(state)))
          ),
          Map(
            entry -> WorkflowTransition.Route(
              NonEmptyChunk(declared),
              _ => Right(rogue)
            ),
            declared -> WorkflowTransition.Complete()
          )
        )
        result <- WorkflowEngine
          .make(definition, store, sumReducer)
          .run(0, context(runId, sessionId))
          .runCollect
          .exit
      yield assertTrue(result.isFailure)
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("循环访问预算随 checkpoint 推进并在上限处终止") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowCheckpointStore[Int]]
        loop       = NodeId("loop")
        definition = validDefinition(
          loop,
          Map(loop -> node(loop)(state => ZIO.succeed(NodeOutcome.Succeeded(state + 1)))),
          Map(loop -> WorkflowTransition.Next(loop)),
          Map(loop -> 2)
        )
        exit <- WorkflowEngine
          .make(definition, store, sumReducer)
          .run(0, context(runId, sessionId))
          .runCollect
          .exit
        checkpoint <- store.load(runId)
      yield assertTrue(
        exit.isFailure,
        checkpoint.contains(
          savedCheckpoint(sessionId, WorkflowCursor.At(loop), 2, 2, Map(loop -> 2))
        )
      )
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("checkpoint identity 不匹配时拒绝恢复") {
      for
        runId          <- RunId.random
        storedSession  <- SessionId.random
        resumedSession <- SessionId.random
        store          <- ZIO.service[WorkflowCheckpointStore[Int]]
        entry      = NodeId("entry")
        definition = validDefinition(
          entry,
          Map(entry -> node(entry)(state => ZIO.succeed(NodeOutcome.Succeeded(state + 1)))),
          Map(entry -> WorkflowTransition.Complete())
        )
        _ <- store.save(
          runId,
          savedCheckpoint(storedSession, WorkflowCursor.At(entry), 0, 0, Map.empty)
        )
        exit <- WorkflowEngine
          .make(definition, store, sumReducer)
          .resume(context(runId, resumedSession))
          .runCollect
          .exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.exists(
          _.failureOption.exists(_.message == "checkpoint-session-mismatch")
        )
      )
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("checkpoint store 只允许 identity 内单调推进并保持相同快照幂等") {
      for
        runId   <- RunId.random
        session <- SessionId.random
        store   <- ZIO.service[WorkflowCheckpointStore[Int]]
        entry     = NodeId("entry")
        first     = savedCheckpoint(session, WorkflowCursor.At(entry), 1, 1, Map(entry -> 1))
        advanced  = savedCheckpoint(session, WorkflowCursor.Completed, 2, 2, Map(entry -> 2))
        divergent = savedCheckpoint(session, WorkflowCursor.At(entry), 999, 2, Map(entry -> 2))
        reopened  = savedCheckpoint(session, WorkflowCursor.At(entry), 999, 3, Map(entry -> 3))
        _                <- store.save(runId, first)
        same             <- store.save(runId, first).either
        _                <- store.save(runId, advanced)
        conflict         <- store.save(runId, divergent).either
        terminalConflict <- store.save(runId, reopened).either
        loaded           <- store.load(runId)
      yield assertTrue(
        same.isRight,
        conflict.left.exists(_.category == ErrorCategory.Conflict),
        terminalConflict.left.exists(_.category == ErrorCategory.Conflict),
        loaded.contains(advanced)
      )
    }.provide(WorkflowCheckpointStore.inMemory[Int]),
    test("execution lease 过期后递增 generation 并拒绝旧 owner 的迟到写") {
      for
        runId        <- RunId.random
        session      <- SessionId.random
        otherSession <- SessionId.random
        store        <- ZIO.service[WorkflowExecutionStore[Int]]
        entry = NodeId("entry")
        key   = WorkflowExecutionKey(
          runId,
          testWorkflowId,
          testWorkflowVersion,
          session,
          entry,
          step = 0,
          visit = 1
        )
        firstClaim    <- store.claim(key, WorkerId("worker-old"), 30.seconds)
        first         <- acquired(firstClaim)
        busy          <- store.claim(key, WorkerId("worker-busy"), 30.seconds)
        identityDrift <- store
          .claim(key.copy(sessionId = otherSession), WorkerId("worker-drift"), 30.seconds)
          .either
        _           <- TestClock.adjust(31.seconds)
        secondClaim <- store.claim(key, WorkerId("worker-new"), 30.seconds)
        second      <- acquired(secondClaim)
        oldWrite    <- store.prepare(first, NodeOutcome.Succeeded(1)).either
        prepared    <- store.prepare(second, NodeOutcome.Succeeded(1))
        checkpoint = savedCheckpoint(
          session,
          WorkflowCursor.Completed,
          state = 1,
          step = 1,
          visits = Map(entry -> 1)
        )
        _      <- store.commit(NonEmptyChunk(second), checkpoint)
        record <- store.get(key)
        loaded <- store.load(runId)
      yield assertTrue(
        busy match
          case WorkflowExecutionClaim.Busy(owner, 1L, _) => owner == WorkerId("worker-old")
          case _                                         => false,
        identityDrift.left.exists {
          case AgentError.WorkflowCheckpointConflict(_, reason) =>
            reason.endsWith(":execution-identity")
          case _ => false
        },
        second.generation == first.generation + 1,
        oldWrite.left.exists(_.isInstanceOf[AgentError.LeaseLost]),
        prepared.status == WorkflowExecutionStatus.Prepared,
        record.exists(_.status == WorkflowExecutionStatus.Committed),
        loaded.contains(checkpoint)
      )
    }.provide(WorkflowExecutionStore.inMemory[Int]),
    test("execution timeline 使用复合游标稳定分页且不暴露 outcome 与 lease token") {
      for
        runId   <- RunId.random
        other   <- RunId.random
        session <- SessionId.random
        store   <- ZIO.service[WorkflowExecutionStore[Int]]
        firstKey = WorkflowExecutionKey(
          runId,
          testWorkflowId,
          testWorkflowVersion,
          session,
          NodeId("a-node"),
          step = 0,
          visit = 1
        )
        secondKey = firstKey.copy(nodeId = NodeId("b-node"))
        otherKey  = firstKey.copy(runId = other, nodeId = NodeId("other-node"))
        first <- store.claim(firstKey, WorkerId("worker-a"), 30.seconds).flatMap(acquired)
        _     <- store.claim(secondKey, WorkerId("worker-b"), 30.seconds).flatMap(acquired)
        _     <- store.claim(otherKey, WorkerId("worker-other"), 30.seconds)
        drift <- store
          .claim(
            secondKey.copy(step = 1, workflowId = WorkflowId("other-workflow")),
            WorkerId("worker-drift"),
            30.seconds
          )
          .either
        _            <- store.prepare(first, NodeOutcome.Succeeded(10))
        page1        <- store.timeline(runId, limit = 1)
        page2        <- store.timeline(runId, page1.lastOption.map(_.cursor), limit = 10)
        invalidLimit <- store.timeline(runId, limit = 0).either
      yield assertTrue(
        page1.map(_.cursor.nodeId) == Chunk(NodeId("a-node")),
        page1.headOption.exists(entry =>
          entry.status == WorkflowExecutionStatus.Prepared &&
            entry.outcomeAvailable &&
            entry.generation == 1L
        ),
        page2.map(_.cursor.nodeId) == Chunk(NodeId("b-node")),
        page2.headOption.exists(entry =>
          entry.status == WorkflowExecutionStatus.Running && !entry.outcomeAvailable
        ),
        drift.left.exists {
          case AgentError.WorkflowCheckpointConflict(_, reason) =>
            reason.endsWith(":run-execution-identity")
          case _ => false
        },
        invalidLimit.left.exists(_.category == ErrorCategory.Persistence)
      )
    }.provide(WorkflowExecutionStore.inMemory[Int]),
    test("checkpoint 提交前失败后复用 Prepared outcome，节点不会重复执行") {
      for
        runId      <- RunId.random
        sessionId  <- SessionId.random
        baseStore  <- ZIO.service[WorkflowExecutionStore[Int]]
        failOnce   <- Ref.make(true)
        executions <- Ref.make(0)
        entry      = NodeId("entry")
        definition = validDefinition(
          entry,
          Map(
            entry -> node(entry)(state => executions.update(_ + 1).as(NodeOutcome.Succeeded(state + 1)))
          ),
          Map(entry -> WorkflowTransition.Complete())
        )
        initial = savedCheckpoint(
          sessionId,
          WorkflowCursor.At(entry),
          state = 0,
          step = 0,
          visits = Map.empty
        )
        _ <- baseStore.save(runId, initial)
        flakyStore = new WorkflowExecutionStore[Int]:
          def save(runId: RunId, checkpoint: WorkflowCheckpoint[Int]) =
            baseStore.save(runId, checkpoint)
          def load(runId: RunId) = baseStore.load(runId)
          def claim(key: WorkflowExecutionKey, owner: WorkerId, leaseDuration: Duration) =
            baseStore.claim(key, owner, leaseDuration)
          def heartbeat(lease: WorkflowExecutionLease, leaseDuration: Duration) =
            baseStore.heartbeat(lease, leaseDuration)
          def prepare(lease: WorkflowExecutionLease, outcome: NodeOutcome[Int]) =
            baseStore.prepare(lease, outcome)
          def commit(
              leases: NonEmptyChunk[WorkflowExecutionLease],
              checkpoint: WorkflowCheckpoint[Int],
              waitCommit: WorkflowWaitCommit
          ) =
            failOnce.getAndSet(false).flatMap {
              case true  => ZIO.fail(AgentError.PersistenceFailure("injected-before-checkpoint"))
              case false => baseStore.commit(leases, checkpoint, waitCommit)
            }
          def get(key: WorkflowExecutionKey) = baseStore.get(key)
          def currentWait(runId: RunId)      = baseStore.currentWait(runId)
          def signal(
              waitKey: WorkflowWaitKey,
              signalId: WorkflowSignalId,
              name: WorkflowSignalName,
              payload: String
          ) = baseStore.signal(waitKey, signalId, name, payload)
          def expireDue(limit: Int) = baseStore.expireDue(limit)
          override def timeline(runId: RunId, after: Option[WorkflowTimelineCursor], limit: Int) =
            baseStore.timeline(runId, after, limit)
        policy = WorkflowExecutionPolicy(
          WorkerId("worker-a"),
          leaseDuration = 30.seconds,
          heartbeatInterval = 10.seconds
        )
        firstExit <- WorkflowEngine
          .makeDurable(definition, flakyStore, sumReducer, policy)
          .resume(context(runId, sessionId))
          .runCollect
          .exit
        countAfterFailure <- executions.get
        prepared          <- baseStore.get(
          WorkflowExecutionKey(
            runId,
            testWorkflowId,
            testWorkflowVersion,
            sessionId,
            entry,
            step = 0,
            visit = 1
          )
        )
        _       <- TestClock.adjust(31.seconds)
        resumed <- WorkflowEngine
          .makeDurable(
            definition,
            baseStore,
            sumReducer,
            policy.copy(owner = WorkerId("worker-b"))
          )
          .resume(context(runId, sessionId))
          .runCollect
        finalCount <- executions.get
        committed  <- baseStore.get(
          WorkflowExecutionKey(
            runId,
            testWorkflowId,
            testWorkflowVersion,
            sessionId,
            entry,
            step = 0,
            visit = 1
          )
        )
      yield assertTrue(
        firstExit.isFailure,
        countAfterFailure == 1,
        prepared.exists(_.status == WorkflowExecutionStatus.Prepared),
        resumed.lastOption.contains(WorkflowEvent.Completed(1)),
        finalCount == 1,
        committed.exists(record =>
          record.status == WorkflowExecutionStatus.Committed && record.generation == 2
        )
      )
    }.provide(WorkflowExecutionStore.inMemory[Int]),
    test("耐久 signal 注册、幂等接收与唤醒消费都落在 execution/checkpoint 边界") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowExecutionStore[Int]]
        approval   = NodeId("approval")
        signalName = WorkflowSignalName("approval.received")
        definition = validDefinition(
          approval,
          Map(
            approval -> nodeWithContext(approval) { (state, workflowContext) =>
              workflowContext.wakeup match
                case Some(WorkflowWakeup.SignalReceived(_, value)) if value.name == signalName =>
                  ZIO.succeed(NodeOutcome.Succeeded(state + 1))
                case Some(WorkflowWakeup.DeadlineElapsed(_, _)) =>
                  ZIO.succeed(NodeOutcome.Succeeded(state - 1))
                case _ =>
                  Clock.instant.map(now =>
                    NodeOutcome.Awaiting(
                      state,
                      WorkflowWaitRequest(
                        WorkflowWaitCondition.Signal(signalName),
                        now.plusSeconds(3600)
                      )
                    )
                  )
            }
          ),
          Map(approval -> WorkflowTransition.Complete())
        )
        engine = WorkflowEngine.makeDurable(
          definition,
          store,
          sumReducer,
          WorkflowExecutionPolicy(WorkerId("wait-worker"))
        )
        first         <- engine.run(10, context(runId, sessionId)).runCollect
        wait          <- store.currentWait(runId).someOrFail(AgentError.PersistenceFailure("wait missing"))
        pendingResume <- engine.resume(context(runId, sessionId)).runCollect.either
        accepted      <- store.signal(
          wait.key,
          WorkflowSignalId("signal-1"),
          signalName,
          "approved"
        )
        duplicate <- store.signal(
          wait.key,
          WorkflowSignalId("signal-1"),
          signalName,
          "approved"
        )
        conflicting <- store
          .signal(wait.key, WorkflowSignalId("signal-1"), signalName, "different")
          .either
        resumed       <- engine.resume(context(runId, sessionId)).runCollect
        remainingWait <- store.currentWait(runId)
      yield assertTrue(
        first.exists {
          case WorkflowEvent.Waiting(`approval`, key, WorkflowWaitCondition.Signal(`signalName`), _, 10) =>
            key == wait.key
          case _ => false
        },
        wait.status == WorkflowWaitStatus.Pending,
        pendingResume.left.exists(_.message == "workflow-wait-pending"),
        accepted.disposition == WorkflowSignalDisposition.Accepted,
        duplicate.disposition == WorkflowSignalDisposition.Duplicate,
        conflicting.left.exists(_.category == ErrorCategory.Conflict),
        resumed.lastOption.contains(WorkflowEvent.Completed(11)),
        remainingWait.isEmpty
      )
    }.provide(WorkflowExecutionStore.inMemory[Int]),
    test("恢复会拒绝 Store 注入另一节点身份的 wait") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        baseStore <- ZIO.service[WorkflowExecutionStore[Int]]
        gate       = NodeId("identity-gate")
        definition = validDefinition(
          gate,
          Map(
            gate -> nodeWithContext(gate) { (state, _) =>
              Clock.instant.map(now =>
                NodeOutcome.Awaiting(
                  state,
                  WorkflowWaitRequest(WorkflowWaitCondition.Timer, now.plusSeconds(60))
                )
              )
            }
          ),
          Map(gate -> WorkflowTransition.Complete())
        )
        policy = WorkflowExecutionPolicy(WorkerId("identity-worker"))
        _ <- WorkflowEngine
          .makeDurable(definition, baseStore, sumReducer, policy)
          .run(1, context(runId, sessionId))
          .runCollect
        corruptingStore = new WorkflowExecutionStore[Int]:
          def save(runId: RunId, checkpoint: WorkflowCheckpoint[Int]) =
            baseStore.save(runId, checkpoint)
          def load(runId: RunId) = baseStore.load(runId)
          def claim(key: WorkflowExecutionKey, owner: WorkerId, leaseDuration: Duration) =
            baseStore.claim(key, owner, leaseDuration)
          def heartbeat(lease: WorkflowExecutionLease, leaseDuration: Duration) =
            baseStore.heartbeat(lease, leaseDuration)
          def prepare(lease: WorkflowExecutionLease, outcome: NodeOutcome[Int]) =
            baseStore.prepare(lease, outcome)
          def commit(
              leases: NonEmptyChunk[WorkflowExecutionLease],
              checkpoint: WorkflowCheckpoint[Int],
              waitCommit: WorkflowWaitCommit
          ) = baseStore.commit(leases, checkpoint, waitCommit)
          def get(key: WorkflowExecutionKey) = baseStore.get(key)
          def currentWait(runId: RunId)      =
            baseStore
              .currentWait(runId)
              .map(_.map(record => record.copy(key = record.key.copy(nodeId = NodeId("other-node")))))
          def signal(
              waitKey: WorkflowWaitKey,
              signalId: WorkflowSignalId,
              name: WorkflowSignalName,
              payload: String
          ) = baseStore.signal(waitKey, signalId, name, payload)
          def expireDue(limit: Int) = baseStore.expireDue(limit)
          override def timeline(runId: RunId, after: Option[WorkflowTimelineCursor], limit: Int) =
            baseStore.timeline(runId, after, limit)
        resumed <- WorkflowEngine
          .makeDurable(definition, corruptingStore, sumReducer, policy)
          .resume(context(runId, sessionId))
          .runCollect
          .either
      yield assertTrue(resumed.left.exists(_.message == "workflow-wait-identity-mismatch"))
    }.provide(WorkflowExecutionStore.inMemory[Int]),
    test("deadline 后 signal 与 timer worker 并发时只产生 TimedOut 唯一胜者") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowExecutionStore[Int]]
        gate       = NodeId("timeout-gate")
        signalName = WorkflowSignalName("external.done")
        definition = validDefinition(
          gate,
          Map(
            gate -> nodeWithContext(gate) { (state, workflowContext) =>
              workflowContext.wakeup match
                case Some(WorkflowWakeup.DeadlineElapsed(_, _)) =>
                  ZIO.succeed(NodeOutcome.Succeeded(state + 100))
                case Some(WorkflowWakeup.SignalReceived(_, _)) =>
                  ZIO.succeed(NodeOutcome.Succeeded(state + 1))
                case None =>
                  Clock.instant.map(now =>
                    NodeOutcome.Awaiting(
                      state,
                      WorkflowWaitRequest(
                        WorkflowWaitCondition.Signal(signalName),
                        now.plusSeconds(60)
                      )
                    )
                  )
            }
          ),
          Map(gate -> WorkflowTransition.Complete())
        )
        engine = WorkflowEngine.makeDurable(
          definition,
          store,
          sumReducer,
          WorkflowExecutionPolicy(WorkerId("timeout-worker"))
        )
        _       <- engine.run(1, context(runId, sessionId)).runCollect
        wait    <- store.currentWait(runId).someOrFail(AgentError.PersistenceFailure("wait missing"))
        _       <- TestClock.adjust(61.seconds)
        results <- store
          .signal(wait.key, WorkflowSignalId("late-signal"), signalName, "late")
          .zipPar(store.expireDue())
        resolved <- store.currentWait(runId).someOrFail(AgentError.PersistenceFailure("wait missing"))
        resumed  <- engine.resume(context(runId, sessionId)).runCollect
      yield assertTrue(
        Set(
          WorkflowSignalDisposition.Late,
          WorkflowSignalDisposition.AlreadyResolved
        ).contains(results._1.disposition),
        results._2.size <= 1,
        resolved.status == WorkflowWaitStatus.TimedOut,
        resumed.lastOption.contains(WorkflowEvent.Completed(101))
      )
    }.provide(WorkflowExecutionStore.inMemory[Int]),
    test("AllSucceeded 分支失败会中断仍在运行的兄弟 Fiber 且不提交 join checkpoint") {
      for
        runId       <- RunId.random
        sessionId   <- SessionId.random
        store       <- ZIO.service[WorkflowCheckpointStore[Int]]
        slowReady   <- Promise.make[Nothing, Unit]
        interrupted <- Ref.make(false)
        start      = NodeId("start")
        failing    = NodeId("failing")
        slow       = NodeId("slow")
        join       = NodeId("join")
        definition = validDefinition(
          start,
          Map(
            start   -> node(start)(state => ZIO.succeed(NodeOutcome.Succeeded(state))),
            failing -> node(failing)(_ =>
              slowReady.await *> ZIO.fail(AgentError.WorkflowFailed("failing", "boom"))
            ),
            slow -> node(slow)(_ =>
              (slowReady.succeed(()) *> ZIO.never)
                .onInterrupt(interrupted.set(true))
            ),
            join -> node(join)(state => ZIO.succeed(NodeOutcome.Succeeded(state)))
          ),
          Map(
            start -> WorkflowTransition.FanOut(
              NonEmptyChunk(failing, slow),
              join,
              FanInPolicy.AllSucceeded
            ),
            failing -> WorkflowTransition.Complete(),
            slow    -> WorkflowTransition.Complete(),
            join    -> WorkflowTransition.Complete()
          )
        )
        exit <- WorkflowEngine
          .make(definition, store, sumReducer, maxParallelism = 2)
          .run(0, context(runId, sessionId))
          .runCollect
          .exit
        wasInterrupted <- interrupted.get
        checkpoint     <- store.load(runId)
      yield assertTrue(exit.isFailure, wasInterrupted, checkpoint.isEmpty)
    }.provide(WorkflowCheckpointStore.inMemory[Int])
  )

  private def node(id0: NodeId)(
      run: Int => IO[WorkflowError, NodeOutcome[Int]]
  ): WorkflowNode[Any, Int] =
    new WorkflowNode[Any, Int]:
      val id                                                                                 = id0
      def execute(state: Int, context: WorkflowContext): IO[WorkflowError, NodeOutcome[Int]] = run(state)

  private def nodeWithContext(id0: NodeId)(
      run: (Int, WorkflowContext) => IO[WorkflowError, NodeOutcome[Int]]
  ): WorkflowNode[Any, Int] =
    new WorkflowNode[Any, Int]:
      val id                                                                                 = id0
      def execute(state: Int, context: WorkflowContext): IO[WorkflowError, NodeOutcome[Int]] =
        run(state, context)

  private def validDefinition(
      entry: NodeId,
      nodes: Map[NodeId, WorkflowNode[Any, Int]],
      transitions: Map[NodeId, WorkflowTransition[Int]],
      visitLimits: Map[NodeId, Int] = Map.empty
  ): WorkflowDefinition[Any, Int] =
    WorkflowDefinition
      .make(testWorkflowId, testWorkflowVersion, entry, nodes, transitions, visitLimits)
      .fold(issues => throw new IllegalArgumentException(issues.map(_.message).mkString("; ")), identity)

  private def savedCheckpoint(
      sessionId: SessionId,
      cursor: WorkflowCursor,
      state: Int,
      step: Int,
      visits: Map[NodeId, Int]
  ): WorkflowCheckpoint[Int] =
    WorkflowCheckpoint(
      testWorkflowId,
      testWorkflowVersion,
      sessionId,
      cursor,
      state,
      step,
      visits
    )

  private val sumReducer = new StateReducer[Int]:
    def merge(base: Int, branches: Chunk[Int]): IO[WorkflowError, Int] =
      ZIO.succeed(base + branches.sum)

  private def acquired[S](
      claim: WorkflowExecutionClaim[S]
  ): IO[StoreError, WorkflowExecutionLease] = claim match
    case WorkflowExecutionClaim.Acquired(lease, _) => ZIO.succeed(lease)
    case WorkflowExecutionClaim.Busy(_, _, _)      =>
      ZIO.fail(AgentError.PersistenceFailure("unexpected busy workflow execution"))
    case WorkflowExecutionClaim.Committed(_, _) =>
      ZIO.fail(AgentError.PersistenceFailure("unexpected committed workflow execution"))
