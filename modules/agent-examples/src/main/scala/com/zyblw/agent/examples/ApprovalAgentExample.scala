package com.zyblw.agent.examples

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.testkit.*
import com.zyblw.agent.tools.*
import com.zyblw.agent.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 演示危险工具先暂停、保存检查点，再由调用方批准后恢复。 */
object ApprovalAgentExample extends ZIOAppDefault:
  final case class DeleteDraftInput(id: String) derives JsonCodec
  final case class DeleteDraftOutput(deleted: Boolean) derives JsonCodec

  /** 非幂等破坏性工具必须显式声明 `Destructive`，恢复时结果不确定则不会被框架自动重放。 */
  private val dangerous = Tool.json[Any, DeleteDraftInput, Nothing, DeleteDraftOutput](
    ToolName("delete_draft"),
    "模拟删除草稿",
    TestSchemas.stringObject("id", "草稿 ID"),
    None,
    ToolMetadata(ToolRisk.AdminApproval, SideEffect.Destructive)
  )((_, _) => ZIO.succeed(DeleteDraftOutput(deleted = true)))

  private val script = Chunk(
    ChatResponse(
      AgentMessage.assistantToolCalls(
        Chunk(ToolCall("delete-1", "delete_draft", Json.Obj("id" -> Json.Str("draft-42"))))
      ),
      FinishReason.ToolCalls
    ),
    ChatResponse(AgentMessage.assistant("草稿删除模拟操作已完成。"), FinishReason.Stop)
  )

  /** 写工具必须同时进入 Agent 可见白名单和应用执行白名单。 */
  private val appConfig = AgentApplicationConfig(
    toolPolicy = ToolPolicyConfig(allowedTools = Set(ToolName("delete_draft")))
  )

  /** 演示审批不是同步 callback，而是一条可持久化、可跨进程恢复的控制命令。 */
  def run = for
    registered <- RegisteredTool.make(dangerous)
    agent      <- AgentDefinitionBuilder(AgentId("approval-demo"), "审批恢复示例")
      .withInstructions("危险操作必须等待人工审批。")
      .allowTool(ToolName("delete_draft"))
      .buildFor(appConfig.toolPolicy)
    result <- (for
      app   <- ZIO.service[AgentApplication]
      start <- app.submit(
        agent,
        RunRequest(ThreadId("approval-demo"), AgentMessage.user("删除草稿 draft-42")),
        "approval-example-request"
      )
      _        <- app.claimOnce
      waiting  <- app.inspect(start.runId)
      approval <- ZIO
        .fromOption(waiting.pendingApproval)
        .orElseFail(AgentError.Unexpected("预期出现待审批请求"))
      _      <- Console.printLine(s"运行已暂停，approvalId=${approval.id}")
      resume <- app.decide(start.runId, ApprovalDecision.Approve, RunContext())
      _      <- Console.printLine(s"审批命令已提交: ${resume.commandId.asString}")
      _      <- app.claimOnce
      state  <- app.inspect(start.runId)
    yield state).provide(
      ScriptedChatModel.layer(script),
      RegisteredToolRegistry.fromTools(List(registered)),
      AgentApplication.inMemoryDefaults(WorkerId("approval-example-worker"), appConfig)
    )
    _ <- Console.printLine(s"恢复后的最终状态: ${result.status}")
  yield ()
