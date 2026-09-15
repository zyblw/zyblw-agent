package com.zyblw.agent.runtime

import com.zyblw.agent.composition.*
import com.zyblw.agent.core.*
import com.zyblw.agent.execution.*
import com.zyblw.agent.extension.RuntimeExtensions
import com.zyblw.agent.memory.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 执行环境进入组合指纹与审批主体；MCP sandbox 不能绕过人工审批。 */
object ExecutionEnvironmentRuntimeSpec extends ZIOSpecDefault:
  final case class EchoInput(value: String) derives JsonCodec
  final case class EchoOutput(value: String) derives JsonCodec

  private val agent = AgentDefinition(
    AgentId("execution-env-agent"),
    "Execution Env Agent",
    "按需使用工具。",
    allowedTools = Set("echo")
  )

  private val policy = ToolPolicyConfig(allowedTools = Set(ToolName("echo")))

  private val sandbox = ConstrainedExecutionEnvironment(
    ExecutionEnvironmentId.mcpSandbox,
    PermissionProfile(
      filesystem = FilesystemAccess.Workspace("/workspace", writable = true),
      network = NetworkAccess.DenyAll,
      process = ProcessAccess.DenyAll,
      secrets = SecretAccess.DenyAll
    )
  )

  private def toolResponse(callId: String, value: String): ChatResponse =
    ChatResponse(
      AgentMessage.assistantToolCalls(Chunk(ToolCall(callId, "echo", Json.Obj("value" -> Json.Str(value))))),
      FinishReason.ToolCalls,
      TokenUsage(4, 2)
    )

  private def finalResponse: ChatResponse =
    ChatResponse(AgentMessage.assistant("done"), FinishReason.Stop, TokenUsage(3, 2))

  private def recordingEcho(executed: Ref[Chunk[String]]): UIO[RegisteredTool] =
    val tool = Tool.json[Any, EchoInput, AgentError.ToolExecutionFailed, EchoOutput](
      ToolName("echo"),
      "返回输入内容",
      TestSchemas.stringObject("value", "需要回显的文本"),
      None,
      ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
    )((input, _) => executed.update(_ :+ input.value).as(EchoOutput(input.value)))
    RegisteredTool.make(tool)

  def spec = suite("ExecutionEnvironment 运行时门禁")(
    test("mcp-sandbox 写工具仍须人工审批，副作用不会发生") {
      val extensions = RuntimeExtensions(environment = sandbox)
      for
        executed <- Ref.make(Chunk.empty[String])
        tool     <- recordingEcho(executed)
        model    <- ScriptedChatModel.make(Chunk(toolResponse("call-sandbox", "draft"), finalResponse))
        result   <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("env-sandbox"), AgentMessage.user("写入")))
          runId = outcome match
            case RunOutcome.Suspended(id, _, _, _, _) => id
            case other                                => throw IllegalStateException(other.toString)
          state <- store.load(runId)
          calls <- executed.get
        yield (outcome, state, calls)).provideLayer(
          TestAgentRuntime.inMemory(model, List(tool), toolPolicy = policy, extensions = extensions)
        )
        (outcome, state, calls) = result
      yield assertTrue(
        outcome.isInstanceOf[RunOutcome.Suspended],
        calls.isEmpty,
        state.pendingApproval.flatMap(_.subject).exists(_.environment == ExecutionEnvironmentId.mcpSandbox),
        state.composition.executionEnvironmentId == "mcp-sandbox"
      )
    },
    test("local 冻结的 Run 换到 mcp-sandbox 后恢复 Incompatible") {
      val frozen = RuntimeComposition.fingerprint(RuntimeProfile.default, agent, agent.modelSettings)
      for
        executed <- Ref.make(Chunk.empty[String])
        tool     <- recordingEcho(executed)
        model    <- ScriptedChatModel.make(Chunk(finalResponse))
        result   <- (for
          store   <- ZIO.service[RunStore]
          runtime <- ZIO.service[AgentRuntime]
          now     <- Clock.instant
          runId   <- RunId.random
          eventId <- EventId.random
          state = RunInitialization.initialState(
            runId,
            agent,
            RunRequest(ThreadId("env-drift"), AgentMessage.user("hello")),
            32,
            now,
            frozen
          )
          event = PersistedAgentEvent(
            eventId,
            runId,
            0L,
            AgentEvent.RunCreated(runId, state.sessionId, now.toEpochMilli),
            now.toEpochMilli
          )
          _    <- store.createWithEvents(state, NonEmptyChunk(event))
          exit <- runtime.recover(runId).exit
        yield exit).provideLayer(
          TestAgentRuntime.inMemory(
            model,
            List(tool),
            toolPolicy = policy,
            extensions = RuntimeExtensions(environment = sandbox)
          )
        )
      yield assertTrue(
        result.causeOption.flatMap(_.failureOption).exists {
          case AgentError.CompositionIncompatible(_, changed, _) =>
            changed.map(_.field).contains("executionEnvironmentId")
          case _ => false
        }
      )
    }
  )
