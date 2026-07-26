package com.zyblw.agent.examples

import com.zyblw.agent.app.*
import com.zyblw.agent.core.*
import com.zyblw.agent.testkit.ScriptedChatModel
import zio.*

/** 不需要 API Key 或数据库的五分钟示例。
  *
  * 示例仍然完整经过框架的提交、命令 claim、Runtime 和状态读取路径，只把模型与控制面替换为确定性的进程内实现。 因此它适合学习与验证接线，不代表生产持久化方案。
  */
object QuickstartAgentExample extends ZIOAppDefault:

  private val modelResponse =
    ChatResponse(
      AgentMessage.assistant("你好，zyblw-agent 的最小运行链路已经完成。"),
      FinishReason.Stop,
      TokenUsage(inputTokens = 12, outputTokens = 9)
    )

  def run: ZIO[Any, Any, Unit] =
    (for
      agent <- AgentDefinitionBuilder(AgentId("quickstart"), "五分钟示例")
        .withInstructions("简洁、准确地回答，不调用任何工具。")
        .build
      state <- AgentQuickstart.run(
        agent,
        RunRequest(
          ThreadId("quickstart-thread"),
          AgentMessage.user("请确认框架已经可以运行。")
        )
      )
      answer = state.messages.lastOption.map(_.text).getOrElse("<没有模型输出>")
      _ <- Console.printLine(s"status=${state.status}, answer=$answer")
    yield ()).provide(ScriptedChatModel.layer(Chunk(modelResponse)))
