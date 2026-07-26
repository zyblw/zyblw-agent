package com.zyblw.agent.scheduler

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.runtime.*
import zio.*
import zio.test.*

/** 验证 WorkerHost 从 command claim 到 Runtime、complete/requeue/dead-letter 的完整控制流。 */
object WorkerHostSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkerHost")(
    test("完整 command/owner/token/generation 进入 Runtime，成功后 fenced complete") {
      for
        environment <- RunCommandStore.inMemory.build
        store = environment.get[RunCommandStore]
        runId  <- RunId.random
        record <- store.submit(runId, RunCommandPayload.Recover, "recover:host")
        seen   <- Promise.make[Nothing, RunCommandLease]
        runtime = new LeaseAwareAgentRuntime:
          def executeLeased(lease: RunCommandLease): IO[AgentError, Unit] = seen.succeed(lease).unit
        host <- WorkerHost
          .make(WorkerId("host-success"), WorkerHostConfig())
          .provide(ZLayer.succeed(store), ZLayer.succeed(runtime))
        claimed <- host.claimOnce
        lease   <- seen.await
        saved   <- store.get(record.commandId)
      yield assertTrue(
        claimed,
        lease.commandId == record.commandId,
        lease.runId == runId,
        lease.owner == WorkerId("host-success"),
        lease.generation == 1L,
        lease.token.value.nonEmpty,
        saved.status == RunCommandStatus.Completed
      )
    },
    test("可重试错误只保存安全类别并重新排队") {
      for
        environment <- RunCommandStore.inMemory.build
        store = environment.get[RunCommandStore]
        runId  <- RunId.random
        record <- store.submit(runId, RunCommandPayload.Recover, "recover:failure")
        runtime = new LeaseAwareAgentRuntime:
          def executeLeased(lease: RunCommandLease): IO[AgentError, Unit] =
            ZIO.fail(AgentError.ModelFailure("stub", "敏感 Provider 原文", retryable = true))
        host <- WorkerHost
          .make(WorkerId("host-failure"), WorkerHostConfig(retryDelay = Duration.Zero))
          .provide(ZLayer.succeed(store), ZLayer.succeed(runtime))
        _     <- host.claimOnce
        saved <- store.get(record.commandId)
      yield assertTrue(
        saved.status == RunCommandStatus.Queued,
        saved.lastFailure.exists(_.contains("category=Unavailable")),
        saved.lastFailure.forall(!_.contains("敏感 Provider 原文"))
      )
    },
    test("永久错误直接 DeadLetter，不进行无意义自动热重试") {
      for
        environment <- RunCommandStore.inMemory.build
        store = environment.get[RunCommandStore]
        runId  <- RunId.random
        record <- store.submit(
          runId,
          RunCommandPayload.ResumeApproval("missing", ApprovalDecision.Approve),
          "approval:missing"
        )
        runtime = new LeaseAwareAgentRuntime:
          def executeLeased(lease: RunCommandLease): IO[AgentError, Unit] =
            ZIO.fail(AgentError.InvalidResume(lease.runId, "approval mismatch"))
        host <- WorkerHost
          .make(WorkerId("host-permanent"), WorkerHostConfig())
          .provide(ZLayer.succeed(store), ZLayer.succeed(runtime))
        _     <- host.claimOnce
        saved <- store.get(record.commandId)
      yield assertTrue(
        saved.status == RunCommandStatus.DeadLetter,
        saved.lastFailure.exists(_.contains("retryable=false"))
      )
    }
  )
