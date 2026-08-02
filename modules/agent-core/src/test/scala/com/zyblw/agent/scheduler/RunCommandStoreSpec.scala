package com.zyblw.agent.scheduler

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import zio.*
import zio.test.*

/** 内存 command dispatcher 契约测试。
  *
  * 这些测试使用 TestClock 验证状态机和 Fiber 抢占；跨进程事务与 SQL 约束由 PostgreSQL Testcontainers 套件负责。
  */
object RunCommandStoreSpec extends ZIOSpecDefault:
  def spec = suite("RunCommandStore")(
    test("相同幂等键和正文复用原命令，不同正文被拒绝") {
      (for
        store    <- ZIO.service[RunCommandStore]
        runId    <- RunId.random
        first    <- store.submit(runId, RunCommandPayload.Recover, "recover:0")
        same     <- store.submit(runId, RunCommandPayload.Recover, "recover:0")
        conflict <- store.submit(runId, RunCommandPayload.Retry("different"), "recover:0").exit
      yield assertTrue(
        first.commandId == same.commandId,
        conflict.isFailure
      )).provideLayer(RunCommandStore.inMemory)
    },
    test("同一个 Run 的命令严格串行，不同 commandId 不会并发 claim") {
      (for
        store  <- ZIO.service[RunCommandStore]
        runId  <- RunId.random
        first  <- store.submit(runId, RunCommandPayload.Recover, "recover:1", priority = 10)
        second <- store.submit(runId, RunCommandPayload.Retry("manual"), "retry:1", priority = 0)
        lease1 <- store
          .claim(WorkerId("worker-1"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("first claim missing"))
        blocked <- store.claim(WorkerId("worker-2"), 30.seconds, 3)
        _       <- store.complete(lease1)
        lease2  <- store
          .claim(WorkerId("worker-2"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("second claim missing"))
      yield assertTrue(
        lease1.commandId == first.commandId,
        blocked.isEmpty,
        lease2.commandId == second.commandId
      )).provideLayer(RunCommandStore.inMemory)
    },
    test("Cancel 原子撤销旧租约并在完成后 supersede 旧恢复命令") {
      (for
        store   <- ZIO.service[RunCommandStore]
        runId   <- RunId.random
        recover <- store.submit(runId, RunCommandPayload.Recover, "recover:2")
        old     <- store
          .claim(WorkerId("old-worker"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("old claim missing"))
        cancel <- store
          .submit(runId, RunCommandPayload.Cancel(Some("user-request")), "cancel", priority = Int.MaxValue)
        stale   <- store.heartbeat(old, 30.seconds).exit
        current <- store
          .claim(WorkerId("cancel-worker"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("cancel claim missing"))
        _       <- store.complete(current)
        records <- store.list(runId)
        recoverState = records.find(_.commandId == recover.commandId).map(_.status)
        cancelState  = records.find(_.commandId == cancel.commandId).map(_.status)
      yield assertTrue(
        stale.isFailure,
        current.commandId == cancel.commandId,
        recoverState.contains(RunCommandStatus.Superseded),
        cancelState.contains(RunCommandStatus.Completed)
      )).provideLayer(RunCommandStore.inMemory)
    },
    test("取消抢占导致 heartbeat 失败并中断业务 Fiber 的 finalizer") {
      (for
        store <- ZIO.service[RunCommandStore]
        runId <- RunId.random
        _     <- store.submit(runId, RunCommandPayload.Recover, "recover:3")
        lease <- store
          .claim(WorkerId("running-worker"), 5.seconds, 3)
          .someOrFail(AgentError.Unexpected("claim missing"))
        finalizerRan <- Promise.make[Nothing, Unit]
        supervisor = CommandLeaseSupervisor(
          store,
          WorkerHostConfig(leaseDuration = 5.seconds, heartbeatEvery = 1.second)
        )
        fiber     <- supervisor.supervise(lease)(ZIO.never.ensuring(finalizerRan.succeed(()).unit)).fork
        _         <- store.submit(runId, RunCommandPayload.Cancel(None), "cancel", priority = Int.MaxValue)
        _         <- TestClock.adjust(1.second)
        exit      <- fiber.await
        finalized <- finalizerRan.isDone
      yield assertTrue(exit.isFailure, finalized)).provideLayer(RunCommandStore.inMemory)
    },
    test("DeadLetter 只能显式人工重试并保留 manualRetryCount") {
      (for
        store  <- ZIO.service[RunCommandStore]
        runId  <- RunId.random
        record <- store.submit(runId, RunCommandPayload.Retry("operator"), "retry:dead")
        lease  <- store
          .claim(WorkerId("worker"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("claim missing"))
        _       <- store.deadLetter(lease, "category=Validation,retryable=false")
        retried <- store.retry(record.commandId)
        claimed <- store
          .claim(WorkerId("worker-2"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("retry claim missing"))
      yield assertTrue(
        retried.status == RunCommandStatus.Queued,
        retried.attempt == 0,
        retried.manualRetryCount == 1,
        claimed.commandId == record.commandId,
        claimed.command.attempt == 1
      )).provideLayer(RunCommandStore.inMemory)
    },
    test("队列快照只聚合可调度、租约与 DeadLetter 状态") {
      (for
        store     <- ZIO.service[RunCommandStore]
        leasedRun <- RunId.random
        futureRun <- RunId.random
        deadRun   <- RunId.random
        _         <- store.submit(leasedRun, RunCommandPayload.Recover, "snapshot:leased", priority = 100)
        _         <- store.submit(
          futureRun,
          RunCommandPayload.Recover,
          "snapshot:future",
          availableAt = java.time.Instant.ofEpochSecond(3600L)
        )
        _      <- store.submit(deadRun, RunCommandPayload.Recover, "snapshot:dead", priority = 50)
        active <- store
          .claim(WorkerId("snapshot-active"), 5.seconds, 3)
          .someOrFail(AgentError.Unexpected("active claim missing"))
        dead <- store
          .claim(WorkerId("snapshot-dead"), 5.seconds, 3)
          .someOrFail(AgentError.Unexpected("dead claim missing"))
        _      <- store.deadLetter(dead, "operator-review")
        before <- store.queueSnapshot
        _      <- TestClock.adjust(6.seconds)
        after  <- store.queueSnapshot
      yield assertTrue(
        active.runId == leasedRun,
        before.queuedCommands == 1L,
        before.dispatchableRuns == 0L,
        before.leasedRuns == 1L,
        before.expiredLeases == 0L,
        before.deadLetterCommands == 1L,
        before.oldestDispatchableAgeMillis.isEmpty,
        after.expiredLeases == 1L
      )).provideLayer(RunCommandStore.inMemory)
    }
  )
