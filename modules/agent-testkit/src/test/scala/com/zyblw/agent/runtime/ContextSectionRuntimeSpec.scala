package com.zyblw.agent.runtime

import com.zyblw.agent.composition.RuntimeProfile
import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunStore
import com.zyblw.agent.testkit.*
import zio.*
import zio.json.*
import zio.test.*

/** world-state 差量进入 lineage；CanonicalModelRequest 仍是模型可见内容的权威表示。 */
object ContextSectionRuntimeSpec extends ZIOSpecDefault:
  private val agent = AgentDefinition(
    AgentId("section-runtime"),
    "Section Runtime",
    "直接完成请求。"
  )

  private val world = new ContextContributor:
    val id                                                                              = "world"
    def contribute(state: AgentState, definition: AgentDefinition): UIO[ContextSources] =
      val _ = (state, definition)
      ZIO.succeed(ContextSources(sections = Chunk(ContextSectionSnapshot.of("goal", "SECRET-OBJECTIVE"))))

  def spec = suite("ContextSection 运行时")(
    test("差量决策进入 lineage，游标与 Inspector 不含正文，账本重建含本回合渲染结果") {
      for
        model <- ScriptedChatModel.make(
          Chunk(ChatResponse(AgentMessage.assistant("ok"), FinishReason.Stop, TokenUsage(3, 2)))
        )
        result <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("section-run"), AgentMessage.user("继续")))
          runId = outcome match
            case RunOutcome.Completed(id, _, _, _, _) => id
            case RunOutcome.Suspended(id, _, _, _, _) => id
          ledger <- store.getModelCalls(runId)
          state  <- store.load(runId)
        yield (outcome, ledger, state)).provideLayer(
          TestAgentRuntime.inMemory(
            model,
            profile = RuntimeProfile(capturePolicy = CapturePolicy.Replayable),
            contextSources = ContextContributor.asResolver(world)
          )
        )
        (outcome, ledger, state) = result
        reconstructed            = ledger.headOption.flatMap(_.canonicalRequest.map(_.toChatRequest))
      yield assertTrue(
        outcome.isInstanceOf[RunOutcome.Completed],
        ledger.headOption.exists(_.lineage.sectionDecisions.exists(_.startsWith("goal:rendered:"))),
        !ledger.headOption.exists(_.lineage.sectionDecisions.exists(_.contains("SECRET-OBJECTIVE"))),
        !state.worldSectionCursors.toJson.contains("SECRET-OBJECTIVE"),
        reconstructed.exists(_.messages.exists(_.text.contains("SECRET-OBJECTIVE")))
      )
    }
  )
