package com.zyblw.agent.inspection

import com.zyblw.agent.composition.{CompositionComparisonView, RuntimeComposition, RuntimeProfile}
import com.zyblw.agent.core.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.test.*

object IncidentPackCliSpec extends ZIOSpecDefault:
  private val runId     = RunId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
  private val sessionId = SessionId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
  private val startedAt = Instant.parse("2026-08-22T00:00:00Z")

  private val cliAgent = AgentDefinition(AgentId("cli"), "CLI", "导出事故包")

  private val state = AgentState(
    runId = runId,
    sessionId = sessionId,
    agentId = AgentId("cli"),
    status = RunStatus.Failed,
    messages = Chunk.empty,
    steps = Chunk.empty,
    usage = UsageSummary(),
    budget = BudgetState(RunLimits(), UsageSummary(), 0),
    suspension = None,
    createdAt = startedAt,
    updatedAt = startedAt,
    version = Version.initial,
    definition = cliAgent,
    composition = RuntimeComposition.fingerprint(RuntimeProfile.default, cliAgent, cliAgent.modelSettings),
    threadId = ThreadId("cli-thread"),
    lastEventSequence = 0L
  )

  private val composition = CompositionComparisonView.of(state.composition, None)

  private val events = Chunk(
    PersistedAgentEvent(
      EventId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
      runId,
      0L,
      AgentEvent.RunCreated(runId, sessionId, startedAt.toEpochMilli),
      startedAt.toEpochMilli
    )
  )

  def spec = suite("IncidentPackCli")(
    test("合法事故包编码成功，密钥子串使 CLI 以泄漏码退出") {
      val pack =
        IncidentPack.build(
          RunTrajectory.build(state, events).copy(composition = composition),
          generatedAtEpochMilli = 1L
        )
      val ok   = IncidentPackCli.encode(pack.toJson)
      val leak = IncidentPackCli.encode(
        pack
          .copy(trajectory =
            pack.trajectory.copy(composition =
              composition.copy(frozen = composition.frozen.copy(profileId = "secret-prompt"))
            )
          )
          .toJson,
        Chunk("secret-prompt")
      )
      val bad = IncidentPackCli.encode("{")
      assertTrue(
        ok.exitCode == 0,
        ok.json.exists(_.contains(runId.asString)),
        leak.exitCode == IncidentPackCli.LeakExitCode,
        bad.exitCode == IncidentPackCli.ConfigurationExitCode
      )
    },
    test("从文件或 stdin 读取，文件不可读时以配置码退出") {
      val pack =
        IncidentPack.build(
          RunTrajectory.build(state, events).copy(composition = composition),
          generatedAtEpochMilli = 1L
        )
      val fromStdin = IncidentPackCli.run(Chunk.empty, pack.toJson)
      val fromFile  = IncidentPackCli.run(
        Chunk("/tmp/pack.json"),
        stdin = "",
        readFile = _ => Right(pack.toJson)
      )
      val missing = IncidentPackCli.run(Chunk("/missing.json"), "", readFile = _ => Left("unreadable"))
      assertTrue(
        fromStdin.exitCode == 0,
        fromFile.exitCode == 0,
        missing.exitCode == IncidentPackCli.ConfigurationExitCode
      )
    }
  )
