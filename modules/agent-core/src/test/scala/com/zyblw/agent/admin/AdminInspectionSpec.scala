package com.zyblw.agent.admin

import com.zyblw.agent.composition.{LiveComposition, RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunStore
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object AdminInspectionSpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val sessionId = SessionId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
  private val agent     = AgentDefinition(AgentId("inspect"), "Inspect", "检查投影", allowedTools = Set("echo"))
  private val frozen    = RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings)

  private def state(status: RunStatus, suspension: Option[SuspensionRecord]): AgentState =
    AgentState(
      runId,
      sessionId,
      agent.id,
      status,
      Chunk.empty,
      Chunk.empty,
      UsageSummary(),
      BudgetState(RunLimits(), UsageSummary(), 0),
      suspension,
      Instant.EPOCH,
      Instant.EPOCH,
      Version.initial,
      agent,
      frozen,
      ThreadId("inspect-thread"),
      lastEventSequence = 0L
    )

  private def persist(current: AgentState): ZIO[RunStore, StoreError, Unit] =
    EventId.random.flatMap { eventId =>
      val event = PersistedAgentEvent(
        eventId,
        runId,
        0L,
        AgentEvent.RunCreated(runId, sessionId, 0L),
        0L
      )
      ZIO.serviceWithZIO[RunStore](_.createWithEvents(current, NonEmptyChunk(event)))
    }

  def spec = suite("RunInspectionAdmin")(
    test("组合对照嵌套 frozen/live/diff，缺现场组合时不伪造兼容") {
      val live = new LiveComposition:
        def freeze(definition: AgentDefinition) = frozen.copy(profileId = "eval")
        def fingerprint(
            agent: AgentDefinition,
            effectiveModel: ModelSettings,
            prices: ModelPriceBook
        ) = freeze(agent)
        def effectiveModelSettings(agent: AgentDefinition) = agent.modelSettings
      (for
        store <- ZIO.service[RunStore]
        _     <- persist(state(RunStatus.Running, None))
        none     = RunInspectionAdmin.fromStore(store)
        withLive = RunInspectionAdmin.fromStore(store, Some(live))
        missing <- none.composition(runId)
        drifted <- withLive.composition(runId)
      yield assertTrue(
        missing.exists(_.composition.live.isEmpty),
        missing.exists(_.composition.driftKind.isEmpty),
        drifted.exists(_.composition.live.exists(_.profileId == "eval")),
        drifted.exists(_.composition.changedFields.nonEmpty)
      )).provide(RunStore.inMemory)
    },
    test("挂起投影覆盖非审批等待；审批仍可从同一记录派生") {
      val request = ApprovalRequest(
        id = "approval-1",
        runId = runId,
        toolCall = ToolCall("call-1", "delete_account", Json.Obj()),
        risk = ToolRisk.ApprovalWrite,
        reason = "需要人工确认",
        requestedAtEpochMilli = 0L
      )
      (for
        store <- ZIO.service[RunStore]
        _     <- persist(state(RunStatus.WaitingForApproval, Some(SuspensionRecord.of(request))))
        admin = RunInspectionAdmin.fromStore(store)
        pending <- admin.suspension(runId)
        subject <- admin.approval(runId)
      yield assertTrue(
        pending.exists(_.record.exists(_.kind == "approval")),
        subject.exists(_.toolName == "delete_account"),
        !pending.fold("")(_.toJson).contains("需要人工确认")
      )).provide(RunStore.inMemory)
    }
  )
