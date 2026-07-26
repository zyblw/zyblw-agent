package com.zyblw.agent.app

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import com.zyblw.agent.model.ChatModel
import com.zyblw.agent.tools.*
import zio.*

/** 五分钟本地体验入口。
  *
  * 它为每次调用创建隔离的内存控制面，并完整走过 `submit -> command claim -> AgentRuntime -> inspect`，因此示例不会发明
  * 第二套简化循环。该入口进程退出即丢失状态，不提供真实 Guardrail、ContextSource 或 Observer，禁止用于生产和多副本。
  */
object AgentQuickstart:

  /** 在隔离的进程内环境运行一次 Agent 并返回权威最终状态。
    *
    * @param agent
    *   已通过 Builder 校验的不可变 Agent 定义
    * @param request
    *   用户输入、线程、可信上下文与预算
    * @param tools
    *   已在装配阶段捕获依赖的类型化工具；默认没有工具
    * @param idempotencyKey
    *   本次示例调用的稳定幂等键
    */
  def run(
      agent: AgentDefinition,
      request: RunRequest,
      tools: Iterable[RegisteredTool] = Nil,
      idempotencyKey: String = "quickstart-run"
  ): ZIO[ChatModel, AgentError, AgentState] =
    for
      model    <- ZIO.service[ChatModel]
      registry <- RegisteredToolRegistry.make(tools)
      allowed = agent.allowedTools.map(ToolName(_))
      visible <- registry.definitions(allowed)
      registered = visible.map(_.name).toSet
      missing    = agent.allowedTools -- registered
      _ <- ZIO
        .fail(
          AgentError.InvalidConfiguration(
            s"Agent 白名单包含未注册工具: ${missing.toList.sorted.mkString(",")}"
          )
        )
        .when(missing.nonEmpty)
      config = AgentApplicationConfig(
        toolPolicy = ToolPolicyConfig(allowedTools = allowed)
      )
      state <- (for
        app     <- ZIO.service[AgentApplication]
        command <- app.submit(agent, request, idempotencyKey)
        claimed <- app.claimOnce
        _       <- ZIO
          .fail(AgentError.Unexpected("隔离的 Quickstart 控制面没有 claim 到刚提交的 Start 命令"))
          .unless(claimed)
        result <- app.inspect(command.runId)
      yield result).provide(
        ZLayer.succeed(model),
        ZLayer.succeed(registry),
        AgentApplication.inMemoryDefaults(WorkerId("quickstart-worker"), config)
      )
    yield state
