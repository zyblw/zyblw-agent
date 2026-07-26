# 指令、Context 与成本工程

> 状态：当前说明  
> 最后核验：2026-07-25  
> 事实来源：`Instructions.scala`、`AgentDefinitionBuilder.scala`、`ContextManager.scala`、Provider Adapter、Runtime 与测试

Agent 质量不只取决于模型。运行时每一轮实际发送了哪些可信规则、哪些不可信事实、多少历史、多少工具结果，以及哪些 token
命中了缓存，都会改变结果和成本。本章用当前代码解释这条链路。

## 1. 五类内容不能混为一谈

| 内容 | 信任 | 生命周期 | 当前承载 |
|---|---|---|---|
| 框架安全/合规规则 | 最高 | 跨 Run 稳定 | System `InstructionBlock` |
| 业务角色/输出规则 | 高 | 随 Agent 版本 | Developer `InstructionBlock` |
| 用户当前请求 | 不可信输入 | 当前 turn | User message |
| Memory/RAG/MCP/网页 | 不可信事实候选 | 按需取回 | 有边界标签的 Context data |
| 工具结果 | 受控执行结果，内容仍不可信 | tool turn | Tool message |

“不可信”不是“没用”，而是内容只能作为事实候选，不能改变权限、工具策略、安全规则或审批要求。

## 2. 定义分层指令

```scala
val definition =
  AgentDefinitionBuilder(AgentId("tcm-learning"), "中医学习助手")
    .withInstructions(
      "保持事实准确；不知道时明确说明，不得编造来源。"
    )
    .addSystemInstruction(
      id = "safety.medical",
      version = "2026-07",
      content = "只提供学习信息，不替代医生诊疗；高风险问题提示线下就医。"
    )
    .addDeveloperInstruction(
      id = "answer.citations",
      version = "3",
      content = "关键结论必须关联已授权资料引用；证据不足时拒绝下结论。"
    )
    .allowTool(ToolName("search_knowledge"))
    .buildFor(toolPolicy)
```

`withInstructions` 仍是最小入口，并被编译为 `agent.core@1` System 块。附加块必须使用安全的稳定 id/version；重复 ID、空正文、
非法控制字符、总量超过 100000 字符或 System 出现在 Developer 之后都会在启动阶段失败。

不要把日期、requestId、用户名或每轮动态资料放入稳定指令。那会破坏 Prompt Cache 的共同前缀，也会把运行时数据提升为策略。

## 3. 稳定 fingerprint 用来做什么

```scala
val fingerprint: Option[String] =
  definition.instructionSet.map(_.fingerprint)
```

Fingerprint 是规范化指令的 SHA-256，可用于：

- eval case/result 身份；
- trace 中关联 Prompt 版本；
- 比较发布前后的质量；
- 回滚到已知指令版本。

它不能用于鉴权，也不能代替代码/配置版本。禁止为了调试把完整 Prompt 复制到低权限日志。

## 4. ContextManager 的固定顺序

当前顺序是：

```text
System 指令
→ 运行时安全约束
→ Developer 指令
→ 长期 Memory
→ RAG 文档
→ 已持久化历史摘要
→ 最近完整消息组
```

每个分区有独立预算，最终还要通过总输入预算。工具调用与对应结果作为原子组裁剪，避免留下孤立 Tool message。
模型辅助压缩产生的 usage 和 checkpoint 会进入 Run 状态；相同历史前缀恢复时复用 checkpoint，不重复付费。

## 5. TokenUsage 的语义

```scala
TokenUsage(
  inputTokens = 1200,
  outputTokens = 300,
  cachedInputTokens = 800,
  reasoningOutputTokens = 120
)
```

其中：

- `cachedInputTokens <= inputTokens`；
- `reasoningOutputTokens <= outputTokens`；
- `totalTokens = inputTokens + outputTokens`；
- 两个明细字段不能再次加入总数。

Provider 不返回明细时值为零，含义是“未知/未报告”，不是“确认没有缓存或推理”。框架不会离线估算后冒充 Provider 账单。

OpenAI Chat Completions 与 Responses 当前会读取缓存/推理明细。其他 Provider 在各自 wire contract 明确且测试完成前保持零，
避免用相似字段名猜测账单语义。

## 6. 观测什么

Run Trace/measurement 包含：

- `gen_ai.usage.input_tokens`
- `gen_ai.usage.output_tokens`
- `agent.usage.cached_input_tokens`
- `agent.usage.reasoning_output_tokens`

OpenTelemetry Metrics 还包含：

- `gen_ai.client.token.usage`
- `zyblw.agent.model.cached.input.token.count`
- `zyblw.agent.model.reasoning.output.token.count`

建议派生：

```text
cache_hit_ratio = cached_input_tokens / input_tokens
reasoning_ratio = reasoning_output_tokens / output_tokens
```

分母为零或 Provider 未报告时不要伪造 0%。

## 7. 如何优化而不破坏质量

1. 保持 System/Developer 前缀稳定，把动态数据放后面；
2. 缩短重复、冲突、低信号指令，而不是盲目增加总预算；
3. 减少重叠工具，让模型更容易选择；
4. Memory/RAG 只按当前任务取回，做 tenant/scope 过滤和来源去重；
5. 大工具结果保存为 Artifact/数据库记录，消息中只放结构化摘要和引用；
6. 每次 Prompt/Context 变化都跑相同 eval，并比较质量、延迟、cache、reasoning 和费用；
7. Context 过大时优先寻找无关内容来源，不先提高模型窗口。

## 8. 常见错误

- 把用户输入拼接进 System 字符串；
- 让 RAG 文档中的“忽略之前规则”成为指令；
- 每轮给稳定 Prompt 加当前时间，导致缓存前缀失效；
- 同一规则在三个位置重复且措辞冲突；
- 只看总 token，不看缓存、压缩调用和质量；
- 记录隐藏推理正文；
- 为追求 cache hit 保留已经过时或无关的上下文。

Prompt Cache 是成本优化，不是正确性来源。最终门禁仍是工具证据、引用、Guardrail、业务校验和 eval。
