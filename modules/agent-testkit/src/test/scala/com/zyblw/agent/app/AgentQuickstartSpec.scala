package com.zyblw.agent.app

import com.zyblw.agent.core.*
import com.zyblw.agent.testkit.ScriptedChatModel
import zio.*
import zio.test.*

object AgentQuickstartSpec extends ZIOSpecDefault:

  def spec = suite("AgentQuickstart")(
    test("用最小入口完整执行隔离的异步命令路径") {
      val definition = AgentDefinitionBuilder(AgentId("quickstart-agent"), "Quickstart")
        .withInstructions("Return a concise answer.")
        .build
      val response = ChatResponse(
        AgentMessage.assistant("done"),
        FinishReason.Stop,
        TokenUsage(inputTokens = 8L, outputTokens = 2L, cachedInputTokens = 4L)
      )
      for
        agent <- definition
        state <- AgentQuickstart
          .run(
            agent,
            RunRequest(ThreadId("quickstart-thread"), AgentMessage.user("hello"))
          )
          .provide(ScriptedChatModel.layer(Chunk(response)))
      yield assertTrue(
        state.status == RunStatus.Completed,
        state.messages.lastOption.exists(_.text == "done"),
        state.usage.modelCalls == 1,
        state.usage.cachedInputTokens == 4L,
        state.lastEventSequence >= 0L
      )
    },
    test("在模型调用前拒绝未注册的 Agent 白名单工具") {
      for
        agent <- AgentDefinitionBuilder(AgentId("quickstart-invalid"), "Quickstart invalid")
          .withInstructions("Use the missing tool.")
          .allowTool(ToolName("missing_tool"))
          .build
        result <- AgentQuickstart
          .run(
            agent,
            RunRequest(ThreadId("quickstart-invalid-thread"), AgentMessage.user("hello"))
          )
          .provide(ScriptedChatModel.layer(Chunk.empty))
          .either
      yield assertTrue(
        result.left.exists {
          case AgentError.InvalidConfiguration(message) => message.contains("missing_tool")
          case _                                        => false
        }
      )
    }
  )
