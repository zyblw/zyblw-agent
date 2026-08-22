package com.zyblw.agent.runtime

import com.zyblw.agent.composition.ApprovalSubject
import com.zyblw.agent.core.*
import com.zyblw.agent.extension.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.model.*
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 扩展可以拒绝副作用、观察生命周期，但不能批准、不能改主体、不能让 Kernel 在观察失败时停下来。 */
object ExtensionRuntimeSpec extends ZIOSpecDefault:
  final case class EchoInput(value: String) derives JsonCodec
  final case class EchoOutput(value: String) derives JsonCodec

  private val agent = AgentDefinition(
    AgentId("extension-runtime-agent"),
    "Extension Runtime Agent",
    "按需使用工具。",
    allowedTools = Set("echo")
  )

  private val policy = ToolPolicyConfig(allowedTools = Set(ToolName("echo")))

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

  private def reviewer(decision: ApprovalReview): ApprovalReviewer =
    new ApprovalReviewer:
      val descriptor = ExtensionDescriptor(ExtensionKind.ApprovalReview, "policy-bot", "1")
      def review(subject: ApprovalSubject, input: ExtensionInput) =
        val _ = (subject, input)
        ZIO.succeed(decision)

  private def layers(
      model: ChatModel,
      tool: RegisteredTool,
      extensions: RuntimeExtensions
  ) =
    TestAgentRuntime.inMemory(model, List(tool), toolPolicy = policy, extensions = extensions)

  def spec = suite("Typed Extension 运行时门禁")(
    test("RecommendAllow 不能跳过人工审批，副作用不会发生") {
      val extensions =
        RuntimeExtensions(approvalReviewers = Chunk(reviewer(ApprovalReview.RecommendAllow("ok"))))
      for
        executed <- Ref.make(Chunk.empty[String])
        tool     <- recordingEcho(executed)
        model    <- ScriptedChatModel.make(Chunk(toolResponse("call-allow", "draft"), finalResponse))
        outcome  <- ZIO
          .serviceWithZIO[AgentRuntime](
            _.run(agent, RunRequest(ThreadId("ext-allow"), AgentMessage.user("写入")))
          )
          .provideLayer(layers(model, tool, extensions))
        calls <- executed.get
      yield assertTrue(outcome.isInstanceOf[RunOutcome.Suspended], calls.isEmpty)
    },
    test("Deny 在副作用前拒绝，且不进入人工审批") {
      val extensions =
        RuntimeExtensions(approvalReviewers = Chunk(reviewer(ApprovalReview.Deny("host policy"))))
      for
        executed <- Ref.make(Chunk.empty[String])
        tool     <- recordingEcho(executed)
        model    <- ScriptedChatModel.make(Chunk(toolResponse("call-deny", "draft"), finalResponse))
        result   <- (for
          runtime <- ZIO.service[AgentRuntime]
          store   <- ZIO.service[RunStore]
          outcome <- runtime.run(agent, RunRequest(ThreadId("ext-deny"), AgentMessage.user("写入")))
          state   <- store.load(outcome match
            case RunOutcome.Completed(runId, _, _, _, _) => runId
            case RunOutcome.Suspended(runId, _, _, _, _) => runId)
          calls <- executed.get
        yield (outcome, state, calls)).provideLayer(layers(model, tool, extensions))
        (outcome, state, calls) = result
        toolError               = state.messages.toJson
      yield assertTrue(
        outcome.isInstanceOf[RunOutcome.Completed],
        state.pendingApproval.isEmpty,
        calls.isEmpty,
        toolError.contains("policy-bot@1")
      )
    },
    test("工具生命周期观察缺陷不能取消已执行的副作用") {
      val observer = new ToolLifecycleObserver:
        val descriptor = ExtensionDescriptor(ExtensionKind.ToolLifecycle, "noisy", "1")
        override def onStarted(callId: String, capability: String): UIO[Unit] =
          ZIO.die(RuntimeException("observer must not own invocation"))
      val extensions = RuntimeExtensions(toolLifecycleObservers = Chunk(observer))
      val readPolicy = ToolPolicyConfig(
        allowedTools = Set(ToolName("echo")),
        approvalPolicy = ApprovalPolicy.Never
      )
      for
        executed <- Ref.make(Chunk.empty[String])
        tool     <- recordingEcho(executed)
        model    <- ScriptedChatModel.make(Chunk(toolResponse("call-obs", "ok"), finalResponse))
        result   <- (for
          outcome <- ZIO.serviceWithZIO[AgentRuntime](
            _.run(agent, RunRequest(ThreadId("ext-obs"), AgentMessage.user("读")))
          )
          calls <- executed.get
        yield (outcome, calls)).provideLayer(
          TestAgentRuntime.inMemory(model, List(tool), toolPolicy = readPolicy, extensions = extensions)
        )
        (outcome, calls) = result
      yield assertTrue(outcome.isInstanceOf[RunOutcome.Completed], calls == Chunk("ok"))
    },
    test("创建 Run 时冻结扩展身份") {
      val extensions = RuntimeExtensions(approvalReviewers = Chunk(reviewer(ApprovalReview.Abstain)))
      for
        executed <- Ref.make(Chunk.empty[String])
        tool     <- recordingEcho(executed)
        model    <- ScriptedChatModel.make(Chunk(toolResponse("call-drift", "draft"), finalResponse))
        result   <- (for
          runtime   <- ZIO.service[AgentRuntime]
          store     <- ZIO.service[RunStore]
          suspended <- runtime.run(agent, RunRequest(ThreadId("ext-drift"), AgentMessage.user("写入")))
          runId = suspended match
            case RunOutcome.Suspended(id, _, _, _, _) => id
            case other                                => throw IllegalStateException(other.toString)
          frozen <- store.load(runId).map(_.composition.flatMap(_.extensionIds.headOption))
        yield (suspended, frozen)).provideLayer(layers(model, tool, extensions))
        (suspended, frozen) = result
      yield assertTrue(
        suspended.isInstanceOf[RunOutcome.Suspended],
        frozen.contains("policy-bot@1")
      )
    }
  )
