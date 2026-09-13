# 模型预算与成本

> 状态：Run 预算与单次路由准入已实现（Experimental）；预留/重试结算仍 Proposed
>
> 最后核验：2026-09-05
>
> 决策来源：[ADR-0025](0025-model-cost-and-budget.md)
>
> 现行类型：`RunLimits`、`BudgetState`、`ContextBudget`、`ModelPriceBook`、`HarnessBudget`

Budget 必须是 Runtime 一等公民，且 **只有一份计数器**。ModelRuntime 在 dispatch 前读取同一 `BudgetState`，禁止再做一套「路由已消费 / loop 已消费」双账。

---

## 今天已经有的

`RunLimits`（每次外部动作前 `ensureBudget`）：

```text
maxSteps
maxModelCalls
maxToolCalls
maxRepeatedActions
maxInputTokens / maxOutputTokens / maxTotalTokens
maxEstimatedCost
maxDuration
```

另外：

- `ContextBudget` 分区：system / tools / recentMessages / memory / retrieval / outputReserve / safetyMargin
- Harness `GoalBudgetPolicy`：跨 Run 预留 / 结算
- Embedding `EmbeddingQuotaStore`：只治理 embedding，不治理 chat
- `ModelPriceBook`：部署声明单价；缺条目费用为 0，不伪造账单；禁止混币

后续缺口：步骤级 soft limit、Profile 降档、`maxReasoningCalls`、持久预留与未知用量对账、Tenant 级 chat 预算。

---

## 目标层级

```text
Tenant / User     （后阶段；可先从 Run 聚合）
  Agent / Goal    （Harness 已有预留）
    Run           （已有硬预算）
      Step        （新建：单次模型步骤）
```

至少控制：

```text
maxModelCalls
maxInputTokens / maxOutputTokens
maxEstimatedCost / maxActualCost
maxReasoningCalls
```

soft vs hard：

| 种类 | 行为 |
|---|---|
| Soft | 可以 Escalation 反向：Expert→Reasoning→Standard；或拒绝高价候选 |
| Hard | 终止 Run 或进入 Approval，不得再 dispatch |

「业务结果不好」不是预算事件。那是 Escalation 策略，见 [ADR-0023](0023-retry-fallback-escalation.md)。

---

## 路由前过滤

Router 必须在选 Expert / 长上下文候选前看到：

- 剩余 model-call 次数
- 剩余估计费用
- 本次请求的粗估 input tokens（ContextManager 已有 `estimatedTokens`）
- 候选单价（`pricingVersion`）

预估超 hard limit → 淘汰该候选。全部淘汰 → `BudgetExceeded`，与今天 loop 终止语义一致。

---

## Cost Ledger

每次结算写入 normalized usage（多数字段 `TokenUsage` 已有）：

```text
inputTokens
outputTokens
cachedTokens
reasoningTokens
estimatedCost
actualCost          （若账单回执后阶段才有；第一版可空）
currency
pricingVersion
```

由统一计价函数完成——今天是 `ModelPriceBook.estimate`，演进为带 version 的 `PricingCatalog`。业务系统不得自己用 token × 猜测单价。

计价规则保持现码不变量：

- `cachedInputTokens` 是 `inputTokens` 子集，不得对同一 token 收两次
- `reasoningOutputTokens` 是 `outputTokens` 子集，默认不单独加价
- 旧展示接口未知价格仍返回 0；路由用 `Option[ModelPrice]` 区分未报价与零价格。有费用硬限且未报价时禁止 dispatch。

---

## PricingCatalog

不要把实时价格写死在 Scala 源码。部署提供价目，并带：

```text
version
effectiveFrom
currency
(provider, model) → ModelPrice
```

历史 `ModelCall` 保存当时 `pricingVersion`，费用可重算、可审计。第一版可以仍是进程内配置 + 账本字段，不必先建 `model_price_catalog` 表。当需要跨进程共享与对账时再 append PostgreSQL。

---

## Context 预算与压缩

`ContextBudget` 已按 Profile 无关的固定分区工作。目标允许按 Profile **动态分配**，但仍是同一套分区字段：

```text
system / history / memory / rag / tools / output reserve
```

防止无限塞历史、Tool Result、RAG chunk。压缩顺序保持现有政策：先 drop / dedup / 确定性裁剪，最后才 Fast Profile 摘要。逼近预算才压缩。

跨 Provider fallback 时必须重建 FullSnapshot，不能依赖上一厂商的 cache / continuation 省上下文。

---

## Prompt Cache

Router 软评分可以加 cache affinity。Agent 不知道厂商 cache 细节。账本记录 `cache eligible` / `cached tokens`。cache 不得压过安全与能力硬约束。

---

## 观测与隐私

span / 账本默认：

```text
request hash, response hash
profile, route codes
provider, model
usage, cost, pricingVersion
status, retry/fallback count
```

原始 Prompt / Response 仅 `CapturePolicy.Replayable` 且显式开启。继续走现有红线与 retention。API Key 永不入账。

---

## 测试不变量

Property / 确定性测试必须锁住：

1. 调度不得超出已知剩余预算放行；实际用量可能超过粗估，必须保留实际用量并停止后续动作。不能声称粗估保证实际账单绝不超限。
2. 预算不足时 Expert 不得 dispatch。
3. 模型 retry 不增加 tool 成功次数。
4. 同一 `pricingVersion` + 同一 usage 重算费用稳定。
5. 混币价格表装配期失败（已有）。

## Phase 1 结算与恢复状态表

本阶段只有一次物理尝试，无自动 Retry/Fallback，无并行模型调用。预算仍是 `AgentState.usage` 与同事务的
`BudgetState.consumed`；没有第二套消费计数器。

| 时点 | 权威提交 | 预算与恢复 |
|---|---|---|
| 路由/硬过滤失败 | 不写 Dispatched，不调用 Provider | 不计调用；返回 typed failure |
| TX1 失败 | 整个提交失败 | 不调用 Provider，不计调用 |
| TX1 成功 | Decision + Dispatched + pending 游标 + modelCalls 加一，同一 fenced commit | Provider 调用只能发生在此后 |
| Provider 完成且 TX2 成功 | Succeeded + usage + 状态/工具计划同事务 | 已计次数不再加一；以本次捕获的价目结算 |
| 实际 token 超限 | 先提交实际 usage，再终止为 BudgetExceeded | 不执行工具、不发下一次模型请求，不丢已发生费用 |
| Provider 失败/部分流/中断 | 路由调用保守标为 Unknown；取消并发提交可留下 Dispatched | 保留调用次数，usage 未知不伪造为零消费；禁止自动恢复 |
| Provider 返回但 TX2 丢失/lease 丢失 | Dispatched 或 Unknown | 恢复只收口为 Unknown，不重新请求 |

前置估算包含 Context 估计与有工具时的 Context 工具预算，输出明确封顶。它是准入估算，不是厂商 tokenizer 或账单保证。
成功结果在工具计划校验/持久化前失败时仍按不确定调用处理，不冒称已经完成结算。

开启重试前必须另行实现：每个物理 attempt 的稳定身份与独立结算、持久预留、失败/取消的用量不确定处理、
重试等待的总 deadline、reservation 的释放/保留、Recovery 权限。Unknown 不能自动释放成“没有消费”。
有这些证据前不接通重试，也不声称 Tenant 级硬额度或实际费用硬封顶。

第一版 pricing version 采用 `ModelPriceBook.fingerprint` 的内容寻址摘要，并在 Decision 保存选中 `ModelPrice`。
未开启路由时保留旧价目 API；开启后同一 Run 的价目漂移会阻止继续，避免混币或静默改价。

Goal 自动对账也检查同一 ModelCall 账本：有 Prepared/Dispatched/Unknown 时保留 Reserved，不把终态当作费用已知。
需要人工或后续对账协议解决未知消费；第一版不会自动释放这笔预留。
