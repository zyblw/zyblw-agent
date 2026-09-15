package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import java.util.concurrent.atomic.AtomicReference
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 人工批准只对被批准的那个副作用生效。
  *
  * 这里覆盖的是「批准绑定工具名或 callId」时会被静默放行、而绑定审批主体后必须重新授权的两类场景。
  */
object ApprovalSubjectRuntimeSpec extends ZIOSpecDefault:
  final case class EchoInput(value: String) derives JsonCodec
  final case class EchoOutput(value: String) derives JsonCodec

  private val agent = AgentDefinition(
    AgentId("approval-subject-agent"),
    "Approval Subject Agent",
    "按需使用工具。",
    allowedTools = Set("echo")
  )

  private val basePolicy = ToolPolicyConfig(allowedTools = Set(ToolName("echo")))

  /** 模拟管理面在运行中途替换生效配置：解析器始终返回当前被引用的不可变值。 */
  final private class SwitchableToolPolicy(initial: ToolPolicyConfig) extends ToolPolicySource:
    private val reference                        = new AtomicReference(initial)
    def current(): ToolPolicyConfig              = reference.get()
    def set(config: ToolPolicyConfig): UIO[Unit] = ZIO.succeed(reference.set(config))

  private def toolResponse(callId: String, value: String): ChatResponse =
    ChatResponse(
      AgentMessage.assistantToolCalls(Chunk(ToolCall(callId, "echo", Json.Obj("value" -> Json.Str(value))))),
      FinishReason.ToolCalls,
      TokenUsage(4, 2)
    )

  private def finalResponse: ChatResponse =
    ChatResponse(AgentMessage.assistant("done"), FinishReason.Stop, TokenUsage(3, 2))

  private def runIdOf(outcome: RunOutcome): RunId = outcome match
    case RunOutcome.Completed(runId, _, _, _, _) => runId
    case RunOutcome.Suspended(runId, _, _, _, _) => runId

  /** 记录每次实际进入业务执行的参数，用于验证未授权副作用确实没有发生。 */
  private def recordingEcho(executed: Ref[Chunk[String]]): UIO[RegisteredTool] =
    val tool = Tool.json[Any, EchoInput, AgentError.ToolExecutionFailed, EchoOutput](
      ToolName("echo"),
      "返回输入内容",
      TestSchemas.stringObject("value", "需要回显的文本"),
      None,
      ToolMetadata(ToolRisk.ApprovalWrite, SideEffect.NonIdempotentWrite)
    )((input, _) => executed.update(_ :+ input.value).as(EchoOutput(input.value)))
    RegisteredTool.make(tool)

  private def layers(model: ChatModel, tool: RegisteredTool, policies: ToolPolicySource) =
    TestAgentRuntime.inMemoryWithToolPolicySource(model, List(tool), policies)

  def spec = suite("ApprovalSubject 运行时门禁")(
    test("待审批请求与批准步骤都记录同一个可审计主体") {
      val script = Chunk(toolResponse("call-audit", "draft"), finalResponse)
      for
        executed <- Ref.make(Chunk.empty[String])
        tool     <- recordingEcho(executed)
        model    <- ScriptedChatModel.make(script)
        policies = SwitchableToolPolicy(basePolicy)
        result <- (for
          runtime   <- ZIO.service[AgentRuntime]
          store     <- ZIO.service[RunStore]
          suspended <- runtime.run(agent, RunRequest(ThreadId("approval-audit"), AgentMessage.user("写入")))
          runId = runIdOf(suspended)
          pending   <- store.load(runId).map(_.pendingApproval.flatMap(_.subject))
          completed <- runtime.resume(runId, ApprovalDecision.Approve)
          state     <- store.load(runId)
          calls     <- executed.get
        yield (suspended, pending, completed, state, calls))
          .provideLayer(layers(model, tool, policies))
        (suspended, pending, completed, state, calls) = result
        recorded                                      = state.steps.collectFirst {
          case AgentStep.ApprovalStep(_, request, Some(ApprovalDecision.Approve), _) => request.subject
        }.flatten
      yield assertTrue(
        suspended.isInstanceOf[RunOutcome.Suspended],
        // 暂停时冻结的主体必须完整描述这次副作用，并原样进入审批历史。
        pending.exists(subject =>
          subject.callId == "call-audit" &&
            subject.capability == "echo" &&
            subject.risk == ToolRisk.ApprovalWrite &&
            subject.sideEffect == SideEffect.NonIdempotentWrite
        ),
        recorded == pending,
        // 主体只保存摘要，参数正文不会因为审批链路而落到第二个持久化位置。
        pending.exists(subject => !subject.toJson.contains("draft")),
        completed.isInstanceOf[RunOutcome.Completed],
        calls == Chunk("draft")
      )
    },
    test("审批策略在批准之前收紧时，重新请求授权而不是执行旧主体") {
      val script = Chunk(toolResponse("call-tighten", "draft"), finalResponse)
      for
        executed <- Ref.make(Chunk.empty[String])
        tool     <- recordingEcho(executed)
        model    <- ScriptedChatModel.make(script)
        policies = SwitchableToolPolicy(basePolicy)
        result <- (for
          runtime   <- ZIO.service[AgentRuntime]
          store     <- ZIO.service[RunStore]
          suspended <- runtime.run(agent, RunRequest(ThreadId("policy-tighten"), AgentMessage.user("写入")))
          runId = runIdOf(suspended)
          before     <- store.load(runId).map(_.pendingApproval)
          _          <- policies.set(basePolicy.copy(approvalPolicy = ApprovalPolicy.Always))
          stale      <- runtime.resume(runId, ApprovalDecision.Approve)
          afterStale <- executed.get
          after      <- store.load(runId).map(_.pendingApproval)
          // 重新审批不能死锁：刷新后的主体必须能被下一次批准通过。
          completed  <- runtime.resume(runId, ApprovalDecision.Approve)
          afterFinal <- executed.get
        yield (before, stale, afterStale, after, completed, afterFinal))
          .provideLayer(layers(model, tool, policies))
        (before, stale, afterStale, after, completed, afterFinal) = result
        frozen                                                    = before.flatMap(_.subject)
        refreshed                                                 = after.flatMap(_.subject)
      yield assertTrue(
        stale.isInstanceOf[RunOutcome.Suspended],
        // 旧主体的批准没有放行任何副作用。
        afterStale.isEmpty,
        refreshed.exists(subject => frozen.exists(_.driftFrom(subject) == List("policy"))),
        // 刷新出的请求必须换一个 ID，否则旧页面提交的决定仍会应用到它从未展示过的副作用上。
        after.map(_.id) != before.map(_.id),
        stale.asInstanceOf[RunOutcome.Suspended].approval.exists(_.reason.contains("审批主体")),
        completed.isInstanceOf[RunOutcome.Completed],
        afterFinal == Chunk("draft")
      )
    }
  )
