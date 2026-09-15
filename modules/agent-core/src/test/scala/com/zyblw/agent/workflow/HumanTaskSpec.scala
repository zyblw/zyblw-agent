package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import java.time.{Duration, Instant}
import zio.*
import zio.test.*

object HumanTaskSpec extends ZIOSpecDefault:
  private val testWorkflowId      = WorkflowId("human-task-spec")
  private val testWorkflowVersion = WorkflowVersion(1)

  def spec = suite("HumanTask")(
    test("人工任务名称固定 human. 前缀，拒绝用户正文和空类型") {
      val ok     = HumanTask.signalName("approve-refund")
      val dotted = HumanTask.signalName("a.b")
      val empty  = HumanTask.signalName("  ")
      val wait   = HumanTask.await("approve-refund", Instant.EPOCH)
      val node   = HumanTask.node[Int](
        NodeId("approval"),
        "a.b",
        Duration.ofSeconds(60),
        onApproved = (state, _) => state,
        onTimeout = identity
      )
      assertTrue(
        ok.contains(WorkflowSignalName("human.approve-refund")),
        dotted.isLeft,
        empty.isLeft,
        wait.exists(_.condition == WorkflowWaitCondition.Signal(WorkflowSignalName("human.approve-refund"))),
        ok.exists(HumanTask.isHuman),
        !HumanTask.isHuman(WorkflowSignalName("timer.elapsed")),
        node.isLeft
      )
    },
    test("HumanTask 节点注册 human signal，唤醒后完成") {
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowExecutionStore[Int]]
        approval   = NodeId("approval")
        signalName = WorkflowSignalName("human.approve-refund")
        human      = HumanTask
          .node[Int](
            approval,
            "approve-refund",
            Duration.ofSeconds(3600),
            onApproved = (state, _) => state + 1,
            onTimeout = state => state - 1
          )
          .fold(message => throw IllegalArgumentException(message), identity)
        definition = WorkflowDefinition
          .make(
            testWorkflowId,
            testWorkflowVersion,
            approval,
            Map(approval -> human),
            Map(approval -> WorkflowTransition.Complete())
          )
          .fold(issues => throw IllegalArgumentException(issues.map(_.message).mkString("; ")), identity)
        engine = WorkflowEngine.makeDurable(
          definition,
          store,
          sumReducer,
          WorkflowExecutionPolicy(WorkerId("human-worker"))
        )
        first    <- engine.run(10, WorkflowContext(runId, sessionId)).runCollect
        wait     <- store.currentWait(runId).someOrFail(AgentError.PersistenceFailure("wait missing"))
        accepted <- store.signal(
          wait.key,
          WorkflowSignalId("human-1"),
          signalName,
          "approved",
          com.zyblw.agent.composition.AuthorizationFingerprint.of(RunContext())
        )
        wakeLease <- store
          .claimWakeups(testWorkflowId, testWorkflowVersion, WorkerId("wake-worker"), 30.seconds)
          .flatMap(value =>
            ZIO.fromOption(value.headOption).orElseFail(AgentError.PersistenceFailure("wakeup missing"))
          )
        resumed <- engine.resumeClaimed(WorkflowContext(runId, sessionId), wakeLease).runCollect
      yield assertTrue(
        first.exists {
          case WorkflowEvent.Waiting(`approval`, key, WorkflowWaitCondition.Signal(`signalName`), _, 10) =>
            key == wait.key
          case _ => false
        },
        accepted.disposition == WorkflowSignalDisposition.Accepted,
        resumed.lastOption.contains(WorkflowEvent.Completed(11))
      )
    }.provide(WorkflowExecutionStore.inMemory[Int]),
    test("HumanTask 绑定期望授权指纹后拒绝其他主体的 signal") {
      val expected = com.zyblw.agent.composition.AuthorizationFingerprint.of(
        RunContext(tenantId = Some("tenant-a"))
      )
      val other = com.zyblw.agent.composition.AuthorizationFingerprint.of(
        RunContext(tenantId = Some("tenant-b"))
      )
      for
        runId     <- RunId.random
        sessionId <- SessionId.random
        store     <- ZIO.service[WorkflowExecutionStore[Int]]
        approval   = NodeId("approval")
        signalName = WorkflowSignalName("human.approve-refund")
        human      = HumanTask
          .node[Int](
            approval,
            "approve-refund",
            Duration.ofSeconds(3600),
            onApproved = (state, _) => state + 1,
            onTimeout = state => state - 1,
            expectedAuthorization = Some(expected)
          )
          .fold(message => throw IllegalArgumentException(message), identity)
        definition = WorkflowDefinition
          .make(
            testWorkflowId,
            testWorkflowVersion,
            approval,
            Map(approval -> human),
            Map(approval -> WorkflowTransition.Complete())
          )
          .fold(issues => throw IllegalArgumentException(issues.map(_.message).mkString("; ")), identity)
        engine = WorkflowEngine.makeDurable(
          definition,
          store,
          sumReducer,
          WorkflowExecutionPolicy(WorkerId("human-auth-worker"))
        )
        _         <- engine.run(10, WorkflowContext(runId, sessionId)).runCollect
        wait      <- store.currentWait(runId).someOrFail(AgentError.PersistenceFailure("wait missing"))
        accepted  <- store.signal(wait.key, WorkflowSignalId("human-other"), signalName, "approved", other)
        wakeLease <- store
          .claimWakeups(testWorkflowId, testWorkflowVersion, WorkerId("wake-worker"), 30.seconds)
          .flatMap(value =>
            ZIO.fromOption(value.headOption).orElseFail(AgentError.PersistenceFailure("wakeup missing"))
          )
        resumed <- engine.resumeClaimed(WorkflowContext(runId, sessionId), wakeLease).runCollect.either
      yield assertTrue(
        accepted.disposition == WorkflowSignalDisposition.Accepted,
        expected != other,
        resumed.left.exists(_.message.contains("human-task-authorization-mismatch"))
      )
    }.provide(WorkflowExecutionStore.inMemory[Int])
  )

  private val sumReducer = new StateReducer[Int]:
    def merge(base: Int, branches: Chunk[Int]): IO[WorkflowError, Int] =
      ZIO.succeed(base + branches.sum)
