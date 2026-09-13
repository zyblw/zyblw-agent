# ADR 0022：Model Profile 与确定性路由

> 状态：**Accepted 原则 / 分阶段实施**（Phase 2 起编码）
> 日期：2026-09-05
> 影响：`ModelSettings`、`ModelRoleCatalog`、ModelCall 账本、部署配置
> 配套：[model-routing.md](model-routing.md)、[model-runtime-target.md](model-runtime-target.md)

## Context

今天选模型有三条并行路径：Agent 写死 `provider/model`、`ModelRole` 1:1 绑定、`ModelPolicy` 稀疏覆盖。`RoutedChatModel` 只按名字转发。业务无法表达「这一步要 Reasoning + ToolCalling」，部署也无法在不改 Agent 的情况下只换 Standard 的主模型。

`ModelRole`（planner / summarizer）描述的是 **任务角色**，不是 **能力/成本等级**。把 Role 直接扩成 Fast/Reasoning 会混淆「做什么」和「用多强的模型」。

## Decision

1. 新增 **`ModelProfile`**：首个实现固定 Fast / Standard / Reasoning；其余档位待真实需求与 Eval 后再引入。
2. 业务主输入是 **`ModelRequirement(profile, capabilities, sensitivity)`**，不是模型名。
3. **`ModelRole` 保留**。配置：`Role → 默认 Profile → Router → ModelRef 候选`。
4. Router **默认确定性**：硬约束（能力、数据策略、预算、健康、限流）先过滤，再软评分（内部 Eval、成本、延迟、cache affinity）。
5. **`RouteDecision` 可持久化、可回放**，进入现有 ModelCall 权威，不另建路由日志库。
6. 显式 `provider`/`model`（含 Role 已解析的冻结值、Policy 覆盖）时，Router 只做硬校验，记录 `legacy-explicit-model`，不改写作者选择。
7. 可选 `TaskClassifier` 只能提议 Profile，不能突破策略。

禁止：`Reasoning == DeepSeek` 写进代码；默认 LLM Router；为静态 alias 建数据库表。

## Alternatives

- **A. 废除 ModelRole，只留 Profile。** 否决：现网 `planner`/`summarizer` 绑定与组合指纹已冻结进 Run；废除会破坏恢复。
- **B. 只用 LLM 做 Router。** 否决：不可解释、不可回放、能绕过预算与安全。
- **C. 继续只做 1:1 Role 绑定，不加候选评分。** 否决：无法在故障、成本和能力之间做步骤级调度，只能靠运维改死绑定。

## Consequences

- 改 `profiles.standard.primary` 成为核心验收：业务 Agent / Tool / RAG 代码为零修改。
- 旧 Run 按冻结 definition 恢复，不被新 Catalog 改写历史模型。
- `RoutedChatModel` 退化为 Adapter 查找；新代码走 `ModelRuntime`。
- 第一版评分权重来自配置；quality 分在 RouterEval 落地前用保守默认。
- Phase 1 收窄为单次主调用纵向闭环；后续才加入动态评分与自动重试。

当前实现边界与后置验收以 [目标架构](model-runtime-target.md) 的 Phase 1 节和 [预算状态表](model-budget-and-cost.md) 为准。
