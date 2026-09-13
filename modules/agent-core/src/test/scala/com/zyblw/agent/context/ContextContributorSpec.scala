package com.zyblw.agent.context

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.test.*

/** 新增上下文来源只需实现 Contributor，不必改 Kernel。 */
object ContextContributorSpec extends ZIOSpecDefault:
  private val agent = AgentDefinition(AgentId("contributor-agent"), "Contributor Agent", "根据资料回答")

  private def state: UIO[AgentState] =
    for
      runId     <- RunId.random
      sessionId <- SessionId.random
    yield AgentState(
      runId,
      sessionId,
      agent.id,
      RunStatus.Running,
      Chunk(AgentMessage.user("你好")),
      Chunk.empty,
      UsageSummary(),
      BudgetState(RunLimits(), UsageSummary(), 0),
      None,
      Instant.EPOCH,
      Instant.EPOCH,
      Version.initial,
      definition = Some(agent),
      runContext = RunContext(Some("user-a"), Some("tenant-a"))
    )

  private val memories = new ContextContributor:
    val id                                                                              = "static-memory"
    def contribute(state: AgentState, definition: AgentDefinition): UIO[ContextSources] =
      val _ = (state, definition)
      ZIO.succeed(ContextSources(memories = Chunk(ContextMemory("语言", "中文", 1.0))))

  private val safety = new ContextContributor:
    val id                                                                              = "static-safety"
    override val version                                                                = "2"
    def contribute(state: AgentState, definition: AgentDefinition): UIO[ContextSources] =
      val _ = (state, definition)
      ZIO.succeed(ContextSources(safetyInstructions = Chunk("资料不足时拒绝编造")))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ContextContributor")(
    test("多个贡献者按声明顺序合并，并暴露 id@version") {
      val resolver = ContextContributor.resolver(memories, safety)
      for
        current <- state
        sources <- resolver.resolve(current, agent)
      yield assertTrue(
        resolver.sourceIds == Chunk("static-memory@1", "static-safety@2"),
        sources.memories.map(_.key) == Chunk("语言"),
        sources.safetyInstructions == Chunk("资料不足时拒绝编造"),
        sources.priorTurns.isEmpty
      )
    },
    test("priorTurns 按贡献者顺序合并，且不是 System 策略") {
      val history = new ContextContributor:
        val id                                                                              = "prior-turns"
        def contribute(state: AgentState, definition: AgentDefinition): UIO[ContextSources] =
          val _ = (state, definition)
          ZIO.succeed(
            ContextSources(priorTurns =
              Chunk(AgentMessage.user("刚才那本伤寒论"), AgentMessage.assistant("可先看太阳病提纲。"))
            )
          )
      val resolver = ContextContributor.resolver(history, safety)
      for
        current <- state
        sources <- resolver.resolve(current, agent)
      yield assertTrue(
        sources.priorTurns.map(_.text) == Chunk("刚才那本伤寒论", "可先看太阳病提纲。"),
        sources.safetyInstructions == Chunk("资料不足时拒绝编造"),
        !sources.priorTurns.exists(_.role == MessageRole.System)
      )
    }
  )
