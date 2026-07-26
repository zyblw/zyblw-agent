package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.tools.ToolPolicyConfig
import java.time.Instant
import zio.*
import zio.json.ast.Json
import zio.test.*

/** 验证控制面在入队前完成 approvalId、状态、幂等键与租户/用户归属校验。 */
object AgentCommandServiceSpec extends ZIOSpecDefault:
  private val now = Instant.parse("2026-01-01T00:00:00Z")

  /** 组装共享同一 RunStore/RunCommandStore 的内存原子提交 Adapter。 */
  private def makeService(
      runs: RunStore,
      commands: RunCommandStore
  ): ZIO[Scope, Nothing, AgentCommandServiceLive] =
    RunSubmissionStore.inMemory.build
      .provideSome[Scope](ZLayer.succeed(runs), ZLayer.succeed(commands))
      .map(environment =>
        AgentCommandServiceLive(
          runs,
          commands,
          environment.get[RunSubmissionStore],
          ToolPolicyConfig.secureDefault
        )
      )

  /** 创建一个最小 WaitingForApproval Run，供审批命令测试。 */
  private def createWaiting(
      runs: RunStore,
      tenant: String = "tenant-a",
      user: String = "user-a"
  ): IO[AgentError, AgentState] =
    for
      runId     <- RunId.random
      sessionId <- SessionId.random
      eventId   <- EventId.random
      call     = ToolCall("call-approval", "write-profile", Json.Obj())
      approval = ApprovalRequest("approval-1", runId, call, ToolRisk.ApprovalWrite, "需要确认", now.toEpochMilli)
      plan = DurableToolPlan("plan-approval", Chunk(DurableToolBatch(0, Chunk(DurableToolPlanItem(0, call)))))
      state = AgentState(
        runId,
        sessionId,
        AgentId("command-test"),
        RunStatus.WaitingForApproval,
        Chunk(AgentMessage.user("test")),
        Chunk.empty,
        UsageSummary(),
        BudgetState(RunLimits(), UsageSummary(), 0),
        Some(approval),
        now,
        now,
        Version.initial,
        threadId = Some(ThreadId("command-thread")),
        runContext = RunContext(Some(user), Some(tenant)),
        pendingToolPlan = Some(plan),
        lastEventSequence = 0L
      )
      created = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, sessionId, now.toEpochMilli),
        now.toEpochMilli
      )
      _ <- runs.createWithEvents(state, NonEmptyChunk(created))
    yield state

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentCommandService")(
    test("同一 approvalId 的相同决定幂等复用，相反决定冲突") {
      for
        runEnv <- RunStore.inMemory.build
        cmdEnv <- RunCommandStore.inMemory.build
        runs     = runEnv.get[RunStore]
        commands = cmdEnv.get[RunCommandStore]
        service <- makeService(runs, commands)
        state   <- createWaiting(runs)
        actor = RunContext(Some("user-a"), Some("tenant-a"))
        first    <- service.submitApproval(state.runId, ApprovalDecision.Approve, actor)
        same     <- service.submitApproval(state.runId, ApprovalDecision.Approve, actor)
        conflict <- service.submitApproval(state.runId, ApprovalDecision.Reject("changed"), actor).exit
      yield assertTrue(
        first.commandId == same.commandId,
        first.idempotencyKey == "approval:approval-1",
        conflict.isFailure
      )
    },
    test("非所属 tenant/user 无法提交控制命令，管理员 scope 可以") {
      for
        runEnv <- RunStore.inMemory.build
        cmdEnv <- RunCommandStore.inMemory.build
        runs     = runEnv.get[RunStore]
        commands = cmdEnv.get[RunCommandStore]
        service <- makeService(runs, commands)
        state   <- createWaiting(runs)
        denied  <- service.submitCancel(state.runId, None, RunContext(Some("other"), Some("tenant-b"))).exit
        admin   <- service.submitCancel(
          state.runId,
          Some("operator"),
          RunContext(scopes = Set("agent:commands:admin"))
        )
      yield assertTrue(
        denied.isFailure,
        admin.payload == RunCommandPayload.Cancel(Some("operator"))
      )
    },
    test("显式 retry 要求稳定 requestId，DeadLetter 重试保留人工次数") {
      for
        runEnv <- RunStore.inMemory.build
        cmdEnv <- RunCommandStore.inMemory.build
        runs     = runEnv.get[RunStore]
        commands = cmdEnv.get[RunCommandStore]
        service <- makeService(runs, commands)
        state   <- createWaiting(runs)
        actor = RunContext(Some("user-a"), Some("tenant-a"))
        invalid <- service.submitRetry(state.runId, " ", "operator retry", actor).exit
        record  <- service.submitRetry(state.runId, "request-1", "operator retry", actor)
        lease   <- commands
          .claim(WorkerId("worker"), 30.seconds, 3)
          .someOrFail(AgentError.Unexpected("claim missing"))
        _       <- commands.deadLetter(lease, "permanent")
        retried <- service.retryDeadLetter(record.commandId, actor)
      yield assertTrue(
        invalid.isFailure,
        retried.status == RunCommandStatus.Queued,
        retried.manualRetryCount == 1
      )
    },
    test("Start 创建在 HTTP 重试下返回同一 run/command，同键不同输入明确冲突") {
      for
        runEnv <- RunStore.inMemory.build
        cmdEnv <- RunCommandStore.inMemory.build
        runs     = runEnv.get[RunStore]
        commands = cmdEnv.get[RunCommandStore]
        service <- makeService(runs, commands)
        agent   = AgentDefinition(AgentId("start-test"), "Start Test", "仅回答测试")
        context = RunContext(Some("user-a"), Some("tenant-a"))
        request = RunRequest(ThreadId("start-thread"), AgentMessage.user("same"), context)
        first    <- service.submitStart(agent, request, "client-request-1")
        same     <- service.submitStart(agent, request, "client-request-1")
        conflict <- service
          .submitStart(agent, request.copy(input = AgentMessage.user("changed")), "client-request-1")
          .exit
        initial <- runs.load(first.runId)
        events  <- runs.events(first.runId)
      yield assertTrue(
        first.commandId == same.commandId,
        first.runId == same.runId,
        first.payload == RunCommandPayload.Start,
        initial.status == RunStatus.Created,
        initial.definition.contains(agent),
        events.map(_.sequence) == Chunk(0L),
        conflict.isFailure
      )
    }
  )
