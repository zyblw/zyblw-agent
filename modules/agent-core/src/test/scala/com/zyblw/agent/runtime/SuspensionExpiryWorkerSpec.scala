package com.zyblw.agent.runtime

import com.zyblw.agent.composition.{RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{RunStore, SuspensionStore, WorkerId}
import zio.*
import zio.json.ast.Json
import zio.test.*

object SuspensionExpiryWorkerSpec extends ZIOSpecDefault:
  private val agent = AgentDefinition(AgentId("expiry-spec"), "Expiry", "挂起到期测试")

  private val silentObserver = new SuspensionExpiryObserver:
    def cycle(result: SuspensionExpiryCycle): UIO[Unit]                = ZIO.unit
    def leaseLost(): UIO[Unit]                                         = ZIO.unit
    def abandoned(category: ErrorCategory): UIO[Unit]                  = ZIO.unit
    def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] = ZIO.unit

  private def initial(runId: RunId, session: SessionId, at: java.time.Instant): AgentState =
    AgentState(
      runId,
      session,
      agent.id,
      RunStatus.Running,
      Chunk(AgentMessage.user("input")),
      Chunk.empty,
      UsageSummary(),
      BudgetState(RunLimits(), UsageSummary(), 0),
      None,
      at,
      at,
      Version.initial,
      agent,
      RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings),
      ThreadId("expiry-thread")
    )

  def spec = suite("SuspensionExpiryWorker")(
    test("到期 FailRun 经 RunCommitter 把 Run 收口为 TimedOut 并清空挂起") {
      ZIO.scoped {
        for
          store       <- ZIO.service[RunStore]
          suspensions <- ZIO.service[SuspensionStore]
          runId       <- RunId.random
          session     <- SessionId.random
          createdId   <- EventId.random
          now         <- Clock.instant
          base    = initial(runId, session, now)
          created = PersistedAgentEvent(
            createdId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, session, now.toEpochMilli),
            now.toEpochMilli
          )
          _      <- store.createWithEvents(base.copy(lastEventSequence = 0L), NonEmptyChunk(created))
          loaded <- store.load(runId)
          deadline = now.plusSeconds(1)
          call     = ToolCall("call-a", "write", Json.Obj())
          (_, waiting) <- ZIO.fromEither(
            AgentKernel.suspend(loaded, call, ToolRisk.ApprovalWrite, "confirm", None, now, Some(deadline))
          )
          committer <- RunCommitter.make(store, _ => ZIO.unit)
          _         <- committer.commitTransition(loaded, waiting)
          _         <- TestClock.adjust(2.seconds)
          worker = SuspensionExpiryWorker(
            WorkerId("expiry-worker"),
            store,
            suspensions,
            committer,
            silentObserver,
            SuspensionExpiryWorkerConfig()
          )
          cycle <- worker.runOnce
          after <- store.load(runId)
        yield assertTrue(
          cycle.expired == 1,
          cycle.claimed,
          cycle.completed,
          after.status == RunStatus.TimedOut,
          after.suspension.isEmpty
        )
      }
    },
    test("到期 ResumeWithDefault 只耐久入队，由持有命令租约的执行者恢复状态并完成") {
      ZIO.scoped {
        for
          store        <- ZIO.service[RunStore]
          suspensions  <- ZIO.service[SuspensionStore]
          commandStore <- ZIO.service[com.zyblw.agent.memory.RunCommandStore]
          runId        <- RunId.random
          session      <- SessionId.random
          createdId    <- EventId.random
          now          <- Clock.instant
          base    = initial(runId, session, now)
          created = PersistedAgentEvent(
            createdId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, session, now.toEpochMilli),
            now.toEpochMilli
          )
          _      <- store.createWithEvents(base.copy(lastEventSequence = 0L), NonEmptyChunk(created))
          loaded <- store.load(runId)
          deadline = now.plusSeconds(1)
          record = SuspensionRecord(Suspension.Timer, now, Some(deadline), SuspensionExpiry.ResumeWithDefault)
          transition = AgentKernel.suspendTransition(loaded, record, now)
          committer    <- RunCommitter.make(store, _ => ZIO.unit)
          _            <- committer.commitTransition(loaded, transition)
          _            <- TestClock.adjust(2.seconds)
          missingQueue <- SuspensionExpiryWorker(
            WorkerId("missing-command-queue"),
            store,
            suspensions,
            committer,
            silentObserver,
            SuspensionExpiryWorkerConfig()
          ).runOnce.either
          stillWaiting <- store.load(runId)
          _            <- TestClock.adjust(31.seconds)
          worker = SuspensionExpiryWorker(
            WorkerId("expiry-worker"),
            store,
            suspensions,
            committer,
            silentObserver,
            SuspensionExpiryWorkerConfig(),
            Some(commandStore)
          )
          cycle      <- worker.runOnce
          after      <- store.load(runId)
          queuedCmds <- commandStore.list(runId)
          cmdWorker = com.zyblw.agent.scheduler.CommandWorker(
            WorkerId("cmd-worker"),
            commandStore,
            com.zyblw.agent.scheduler.CommandLeaseSupervisor(
              commandStore,
              com.zyblw.agent.scheduler.WorkerHostConfig()
            ),
            com.zyblw.agent.scheduler.WorkerHostConfig(),
            lease =>
              committer.withLease(lease) {
                for
                  waitingState <- store.load(lease.runId)
                  doneNow      <- RunClock.instant
                  expiry       <- ZIO.fromEither(AgentKernel.suspensionExpiry(waitingState, doneNow))
                  resumedState <- expiry match
                    case AgentKernel.SuspensionExpiryAction.Resume(next) =>
                      committer.commitTransition(waitingState, next)
                    case _ => ZIO.fail(AgentError.Unexpected("测试预期到期恢复"))
                  completed <- ZIO.fromEither(
                    AgentKernel.complete(
                      resumedState,
                      AgentMessage.assistant("挂起到期后已自动恢复并完成"),
                      doneNow
                    )
                  )
                  _ <- committer.commitTransition(resumedState, completed)
                yield ()
              }
          )
          claimed   <- cmdWorker.claimOnce
          completed <- store.load(runId)
          finalCmds <- commandStore.list(runId)
        yield assertTrue(
          missingQueue.isLeft,
          stillWaiting.suspension.contains(record),
          cycle.completed,
          after.status == record.runStatus,
          after.suspension.contains(record),
          queuedCmds.map(_.payload) == Chunk(com.zyblw.agent.memory.RunCommandPayload.Recover),
          claimed,
          completed.status == RunStatus.Completed,
          completed.messages.lastOption.map(_.text).contains("挂起到期后已自动恢复并完成"),
          finalCmds.map(_.status) == Chunk(com.zyblw.agent.memory.RunCommandStatus.Completed)
        )
      }
    }
  ).provide(RunStore.inMemory, com.zyblw.agent.memory.RunCommandStore.inMemory)
