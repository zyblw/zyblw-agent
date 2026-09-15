package com.zyblw.agent.evals

import com.zyblw.agent.composition.RuntimeProfile
import com.zyblw.agent.core.*
import com.zyblw.agent.inspection.RunTrajectory
import com.zyblw.agent.memory.RunStore
import com.zyblw.agent.model.*
import com.zyblw.agent.runtime.*
import com.zyblw.agent.testkit.*
import zio.*
import zio.test.*

/** 把 ModelCall Replayable 账本接到 eval 轨迹门禁。 */
object TrajectoryReplayEvalSpec extends ZIOSpecDefault:
  private val secret = "私密评测问题"
  private val agent  = AgentDefinition(
    AgentId("eval-replay-agent"),
    "Eval Replay Agent",
    "回答用户问题。"
  )

  private def layers(model: ChatModel, capture: CapturePolicy) =
    TestAgentRuntime.inMemory(model, profile = RuntimeProfile(capturePolicy = capture))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TrajectoryReplay eval fixture")(
    test("Replayable 运行的账本可通过轨迹评测门禁") {
      for
        model <- ScriptedChatModel.make(
          Chunk(ChatResponse(AgentMessage.assistant("ok"), FinishReason.Stop, TokenUsage(3, 2)))
        )
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("eval-replay"), AgentMessage.user(secret)))
          runId = outcome match
            case RunOutcome.Completed(id, _, _, _, _) => id
            case RunOutcome.Suspended(id, _, _, _, _) => id
          recorded <- model.recordedRequests
          ledger   <- store.getModelCalls(runId)
          events   <- store.events(runId)
          state    <- store.load(runId)
          trajectory  = RunTrajectory.build(state, events, ledger)
          evidence    = TrajectoryReplay.evidence(recorded, ledger, trajectory, List(secret))
          evalCase    = AgentEvalCase("replay-1", "v1", "脱敏输入")
          observation = AgentEvalObservation(
            Chunk.empty,
            Set.empty,
            recovered = false,
            duplicateSideEffects = 0,
            RunStatus.Completed,
            latencyMillis = 1L,
            TokenUsage(3L, 2L),
            BigDecimal(0)
          )
          report = AgentEvalGrader.grade(evalCase, observation, Some(evidence))
        yield report).provideLayer(layers(model, CapturePolicy.Replayable))
      yield assertTrue(
        result.passed,
        result.grades.exists(grade => grade.dimension == TrajectoryReplay.Dimension && grade.passed)
      )
    }
  )
