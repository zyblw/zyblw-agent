package com.zyblw.agent.context

import com.zyblw.agent.core.*

/** 从 AgentState 权威事实即时投影的模型状态栏；不存储、不写回、不含业务正文。 */
private[agent] object RuntimeStatusContext:
  def message(state: AgentState): AgentMessage =
    val usage  = state.usage
    val limits = state.budget.limits
    val pending = state.pendingToolPlan
      .flatMap(_.currentBatch)
      .fold("none")(batch =>
        batch.items.map(_.call.name).distinct.sorted.mkString(s"batch-${batch.index}:[", ",", "]")
      )
    val suspension = state.suspension.fold("none")(_.kind.kind)
    val content =
      s"state=${state.status} step=${state.budget.steps} " +
        s"left(steps=${remaining(limits.maxSteps.toLong, state.budget.steps.toLong)}," +
        s"models=${remaining(limits.maxModelCalls.toLong, usage.modelCalls.toLong)}," +
        s"tools=${remaining(limits.maxToolCalls.toLong, usage.toolCalls.toLong)}," +
        s"input=${remaining(limits.maxInputTokens, usage.inputTokens)}," +
        s"output=${remaining(limits.maxOutputTokens, usage.outputTokens)}," +
        s"total=${remaining(limits.maxTotalTokens, usage.totalTokens)}) " +
        s"pending=$pending wait=$suspension"
    PromptCompiler.data(
      ContextBlock(
        "runtime-status",
        ContextPurpose.RuntimeControl,
        ContextInstructionAuthority.None,
        ContentTrust.RuntimeDerived,
        DataSensitivity.Internal,
        CacheStability.Dynamic,
        content
      )
    )

  private def remaining(limit: Long, used: Long): Long = (limit - used).max(0L)
