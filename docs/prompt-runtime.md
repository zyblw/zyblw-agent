# Prompt Runtime、Context Authority 与 Cache 接入

> 状态：当前实现说明
> 最后核验：2026-09-16
> 决策依据：[ADR-0029](architecture/0029-context-authority-prompt-lineage.md)

## 当前数据流

```text
AgentState + AgentDefinition + ACL-filtered ContextSources
                         |
                         v
                 DefaultContextManager
      dedupe / partition / summary checkpoint / atomic tool groups
                         |
                         v
                   PromptCompiler
       authority validation / data envelope / stable lineage
                         |
                         v
                  PreparedContext
                         |
                         v
               ModelCallCoordinator
 persist intent + lineage -> Provider -> normalize usage -> settle
```

`PromptCompiler` 不是第二个 Runtime，也不是公开 Prompt DSL。它是 `ContextManager` 内部的最后纯门禁，确保 Provider 请求不会因为一个贡献者的格式化选择而提升正文权限。

## 权限与顺序

模型可见顺序是：

```text
System/Developer policy
-> session-stable Memory
-> durable history summary
-> dynamic world-state/RAG
-> recent conversation/tool evidence
-> Runtime Status（有剩余预算时）
```

- Agent 和 Host 配置是唯一高权限指令来源。
- Memory、RAG、world-state 和 summary 使用 `User` role 的 `<context-data>` envelope，并对 XML 边界字符转义。
- Tool evidence 保持原生 Tool role 和 call/result 对应，绝不转成 System 摘要。
- Runtime Status 只包含状态、步数、剩余硬预算、pending tool 名称与 suspension kind；不含 Run/Session/Tenant/User ID、参数正文、时间戳或 lease。
- System/Developer 不是连续前缀、Secret 出站、RuntimeControl 来源不可证明时都 fail closed。

## 预算与单次物化

`ContextBudget` 仍是唯一预算定义。Runtime Status 只使用 recent 分区在保留摘要和最新原子消息组后的剩余空间；不足时省略并记录 `context-runtime-status-omitted`，不挤掉当前用户输入。

ToolResult 执行链是：

```text
tool.invoke -> hard byte limit -> output guardrail
-> externalize when deployment-byte OR agent-character threshold is exceeded
-> Tool ledger Succeeded -> AgentState Tool message
```

因此 Context 不再压缩 Tool message。已外置的结果只暴露 immutable Run-scoped artifact reference 和有界 preview；未外置却超过 Agent 字符上限的旧/损坏状态直接拒绝，不在调用前临时改写事实。

## Prompt lineage

`PreparedContext.promptLineage` 和 `ModelCallExecutionRecord.lineage` 只保存低敏结构证据：

- `promptCompilerVersion` / `promptLayoutVersion`；
- `stablePrefixMessages`；
- 带冻结 tools/settings 的 `stablePrefixFingerprint`；
- 整个消息计划的 `promptPlanFingerprint`。

动态尾部变化不改变稳定前缀指纹；Policy、会话稳定资料、tools 或 settings 变化必须改变对应指纹。`CapturePolicy.Replayable` 仍以 `CanonicalModelRequest` 作为完整请求权威；lineage 不保存正文，不能用于重放。

## Cache usage 与成本

```text
cacheReadInputTokens + cacheWriteInputTokens <= inputTokens
freshInputTokens = inputTokens - cacheReadInputTokens - cacheWriteInputTokens
totalTokens = inputTokens + outputTokens
```

- Anthropic：逻辑输入为 `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`。
- Gemini Interactions：`total_input_tokens` 已是逻辑总输入，`total_cached_tokens` 映射 cache read。
- OpenAI Responses：`input_tokens` 是逻辑总输入，details 存在时映射 read/write；缺失的明细保持零，不估算。

`ModelPrice` 的 fresh/read/write/output 单价分开。缺少 cache read/write 单价时回退到普通 input 单价；缺少整个 provider/model 价格条目仍估算为零，不伪造账单。硬 token 预算始终使用逻辑总输入，不会因缓存折扣而放宽。

`PromptCacheCapability` 已取代 Boolean，管理面可见 kind、read/write 报告和 retention。当前 OpenAI Responses 与 Gemini Interactions 只声明已验证的 implicit-read；Anthropic 在尚未完成 `cache_control` wire contract 前保持 Unsupported，即使 usage decoder 已能安全读取 read/write 字段。

## 恢复与当前边界

- Prompt Cache 未命中、过期或 Provider 清空不改变请求正文、授权、预算或恢复选择。
- 确定性历史摘要是默认生产基线。checkpoint 保存未附加 envelope 的原始摘要正文，复用时只包装一次，避免重复转义与 token 膨胀。
- 可选模型辅助摘要的 checkpoint 和 usage 会同一状态提交，但 Provider 调用与该提交之间仍有崩溃不确定窗口。在它复用现有 ModelCall intent/settlement 之前，不应把 ModelAssisted 宣称为 exactly-once 计费。

## 接入检查

1. Context 来源先完成 tenant/ACL 过滤，再进入 `ContextSources`。
2. 生产默认使用 `CompressionMode.Deterministic`。
3. `externalizeAboveBytes` 不得高于 `maxResultBytes`；Agent 的 `maxToolResultCharacters` 会作为更严阈值。
4. 评审 RunTrajectory 中的 compiler/layout/prefix/plan 指纹变化，不把完整 hash 当 metrics label。
5. 费用与延迟数据达到稳定样本前，不启用 cache-aware 路由。
