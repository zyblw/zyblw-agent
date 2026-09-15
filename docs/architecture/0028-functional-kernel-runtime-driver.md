# ADR 0028：Functional Kernel 与 ZIO Runtime Driver

> 状态：Accepted / 已实现
> 日期：2026-09-15
> 影响：`agent-core` Runtime 内部结构、低层装配 API；不改变 HTTP、数据库或 `AgentState` wire 形状

## 背景

`AgentRuntimeLive` 已增长到 2,270 行，同时包含两类职责：

- 预算、恢复分派、模型结算、工具批次游标等纯状态判断；
- Clock、Provider、Tool、RunStore、Fiber、Scope、stream 与 fenced commit 等效果执行。

这些职责共处一个类不会立刻破坏正确性，但会让任何新能力都倾向于继续修改主循环，并使关键边界只能通过完整 Runtime 测试验证。

## 决定

采用 **Functional Kernel + Effectful Driver**：

```text
AgentState + 已取得的事实
            │
            ▼
       AgentKernel
  pure decision / transition
            │
            ▼
    AgentRuntimeDriver
 model/tool/store/clock/fiber/scope
            │
            ▼
 RunStore commit / commitFenced
```

`AgentKernel` 是 package-private 的无状态对象，不做成 trait、Free Monad 或可替换插件。它目前拥有：

- `RunStatus -> RecoveryDecision`；
- 下一外部动作与 Provider 结算后的预算验证；
- Model turn 的状态、事件与领域 settlement 归约，并验证响应与耐久工具计划一致；
- Tool batch 的稳定排序、游标推进、usage 与事件归约；
- Completed/Suspended outcome 重建以及完成、暂停、失败、取消迁移；
- 状态迁移源约束，允许审批主体漂移时刷新暂停，同时防止终态被迟到失败、完成、暂停或工具提交覆盖；
- 审批需求和 pending tool identity 等纯判断。

`AgentRuntimeDriver` 是唯一效果外壳。它取得时间和外部结果，调用 Kernel 得到转换，把领域层模型结算事实转换为 `ModelCallWrite`，再通过现有 `RunStore.commit` / `commitFenced` 原子提交。Kernel 不依赖 `memory` 或 `model` package；Extension、Capability、Context、Provider 与 Tool 均不能取得 Kernel 可变内部或绕过 Driver 的提交路径。

旧 `AgentRuntimeLive` 类型和文件直接删除，不保留别名或并行 Runtime。公开业务入口仍是 `AgentRuntime` / `AgentApplication`；低层装配改用 `AgentRuntimeDriver.layer*`。

## 保留的不变量

- 一类事实一个耐久权威；Kernel 不新增 Store。
- 模型与工具继续遵守 Intent → Effect → Settlement / Unknown。
- lease、generation fencing、CAS、审批前置、预算、取消和事件连续性不变。
- ZIO 继续拥有并发、取消和资源生命周期；Kernel 不解释 ZIO effect。
- PostgreSQL、HTTP、Provider、MCP、RAG 类型不进入 Kernel。

## 被否决的方案

- 只按文件大小拆若干 helper：不能形成可验证的纯/效果边界。
- `trait AgentKernel` + 单实现 ZLayer：没有替换需求，只增加装配与公共 API。
- Free Monad / 通用 Action Interpreter：ZIO 已经是效果运行时。
- 同时重写 durable state、Capability 与 Driver：会扩大故障半径，无法用现有恢复测试证明行为等价。

## 验证

`AgentKernelSpec` 直接验证恢复决定、预算等号边界、模型结算、模型响应/工具计划一致性、非法源状态、终态迟到写、工具结果乱序归约与缺失 ordinal 拒绝。现有 Runtime、ModelCall、审批、取消、崩溃恢复和 Worker 测试继续作为等价性门禁。

## 后续

继续按变化原因从 Driver 抽纯归约；只有两个以上真实贡献者需要统一生命周期时，才把当前 typed extensions 上收为 authoring-level Capability。`RuntimeCompositionFingerprint` 已保存结构化字段并执行逐字段 fail-closed 比较；在加入可操作 drift diff 前，不做只改名的 Manifest 迁移。
