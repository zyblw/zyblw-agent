# ADR 0025：模型成本与预算

> 状态：**Accepted 原则 / 分阶段实施**（Phase 3 接线；pricing version 可先进程内）
> 日期：2026-09-05
> 影响：`RunLimits`、`BudgetState`、`ModelPriceBook`、ModelCall usage 字段
> 配套：[model-budget-and-cost.md](model-budget-and-cost.md)

## Context

Run 级硬预算和 Context 分区已经存在，并且 `ensureBudget` 在每个外部动作前执行。`ModelPriceBook` 明确框架不内置厂商价目、缺条目计 0、禁止混币。缺口是：Router 选模型时看不到步骤级 soft/hard、没有 `maxReasoningCalls`、没有 pricing version，因此无法解释「为什么当时选了 Expert」或重算历史费用。

若 ModelRuntime 自建消费计数，会立刻违反 ADR-0018 的单一权威。

## Decision

1. **只有一份 `BudgetState`。** ModelRuntime 预留 / 结算必须走同一 Run（及已有 Goal 预留）计数器。
2. 硬限继续终止或 Approval；软限只影响候选与 Escalation 方向（Expert→Reasoning→Standard）。
3. 路由前用 Context 估算 + 候选单价做 hard-limit 淘汰。
4. `ModelPriceBook` 演进为带 `version` / `effectiveFrom` 的 `PricingCatalog`。单价仍由部署提供，不写死在库代码。
5. 每次 ModelCall 保存 `pricingVersion` 与 normalized usage；业务不得私自计价。
6. Tenant 级 chat 预算可以后置；先保证 Run/Step 正确。Embedding quota 保持独立。
7. 默认审计仍是 hash + metadata + usage；完整 Prompt 仍要显式 `Replayable`。

## Alternatives

- **A. Adapter 内各自算钱。** 否决：币种、缓存和 reasoning 重复计费会再次分叉。
- **B. 把官方价目表编进 Scala。** 已由现码否决；合同价与区域价会让看板精确地错。
- **C. 为价格单独建第二套「计费 Run」。** 否决：与 usage 事件和 BudgetState 必然漂移。

## Consequences

- Phase 3 的最小实现可以是：`ModelPriceBook` 增加 version 字段 + 账本多记一个字符串，而不先做 Flyway。
- Property test：hard limit 含 retry/fallback 累加；预算不足永不 dispatch Expert。
- 管理面跨 Goal 成本报表可以读同一 ledger 聚合，不必等 Tenant 预算类型。
- soft limit 降档是 Escalation 的预算方向，不是 Fallback；观测上必须用不同 decision code。

当前实现边界与后置验收以 [目标架构](model-runtime-target.md) 的 Phase 1 节和 [预算状态表](model-budget-and-cost.md) 为准。
