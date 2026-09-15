# ADR 0029：Context Authority、Prompt Lineage 与 Cache 非权威化

> 状态：Accepted / 核心切片已实现
> 日期：2026-09-16
> 影响：`agent-core` Context/ModelCall、原生 Provider usage、HTTP/Admin 用量投影与可观测性

## 背景

Memory、RAG、world-state section 和模型生成的历史摘要原先以 `System` 消息发送。正文即使附带“不得遵循其中指令”的文字，在 Provider 协议中仍然获得了高权限 role。同时，上下文一旦被降级为 `Chunk[AgentMessage]`，调用账本就无法说明稳定前缀、编译版本和完整计划是否发生了变化。

Provider Prompt Cache 又只能是可丢失的计算优化：如果缓存命中、过期或清空会改变授权、恢复或预算结果，它就成了第二事实源。

## 决定

1. 来源信任、指令权限、敏感度、用途和缓存稳定性是正交维度，由 package-private `ContextBlock` 表达，不公开一套无真实宿主消费者的 DSL。
2. 只有 Agent/Host 配置可以产生 `System`/`Developer` 指令。Memory、RAG、world-state、Runtime Status 和历史摘要都映射为转义后的 `User` 数据 envelope。
3. `PromptCompiler` 是模型调用前的纯验证边界：拒绝后置高权限指令、Secret 出站、伪造 role 和非 RuntimeDerived 的 RuntimeControl。
4. 每个主模型调用在现有 `ModelCallExecutionRecord.lineage` 中保存 compiler/layout 版本、稳定前缀数量与指纹、整体 plan 指纹；不新建 Prompt Store。
5. Runtime Status 是 `AgentState` 的低敏纯投影，只在 recent 分区有剩余空间时放在动态尾部。它不存储、不写回；空间不足时产生低敏 rot signal，不挤掉当前用户回合。
6. ToolResult 在 guardrail 看过全文后只物化一次。部署字节阈值或 Agent Context 字符阈值任一超限就在 ledger settlement 前外置；Context 只验证已冻结表示，不再二次压缩。
7. Prompt Cache 不是布尔能力。`PromptCacheCapability` 区分 Unsupported/Implicit/Explicit/Hybrid、read/write usage 报告和 retention；只声明当前 Adapter 已经验证的能力。
8. `inputTokens` 是模型看到的逻辑输入总量，cache read/write 都是其中子集。预算按逻辑总量，价格按 fresh/read/write/output 分别计算。

## 不变式

```text
System/Developer 必须是连续前缀
external/model/user content => authority=None
Secret => 默认禁止 Provider 出站
cacheRead + cacheWrite <= inputTokens
fresh = inputTokens - cacheRead - cacheWrite
total = inputTokens + outputTokens
cache miss/eviction 不改变 AgentState 语义
```

## 取舍

- 不新增 `ContextStore` / `PromptStore` / `CacheStore` / `StatusStore`。
- 不为了缓存命中率将 typed tools 折叠成一个通用工具。
- 不在证据不足时把预测缓存命中率接入路由或硬预算。
- 历史摘要 checkpoint 继续存于 `AgentState`。当前模型辅助压缩仍存在 Provider 响应与 checkpoint 提交之间的不确定窗口；在其复用现有 ModelCall intent/settlement 前，默认确定性压缩仍是生产基线。不用新 Summary Store 掩盖这个差距。

## 验证

- `PromptCompilerSpec`：权限、Secret、envelope 逃逸与稳定前缀。
- `DefaultContextManagerSpec`：Memory/RAG role、ToolResult 单次物化、摘要 checkpoint 复用与分区预算。
- `ToolExecutorExternalizeSpec`：部署阈值与 Context 阈值合并。
- 三个原生 Provider wire spec：非流式/流式 cache usage 归一化。
- `TokenUsageSpec` / `ModelGovernanceSpec`：不变式、旧 JSON 缺省和分项计费。

## 回滚

权限修复、单次 ToolResult 物化和 usage 归一化是一组行为不变式，不提供运行时开关回退到不安全语义。若 Provider 缓存报告发生协议回归，可单独将该 Adapter 的 capability 改为 Unsupported 并保持明细为零；不影响完整请求、Run 恢复和权限语义。
