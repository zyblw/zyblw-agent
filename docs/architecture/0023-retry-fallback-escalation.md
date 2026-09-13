# ADR 0023：Retry、Fallback 与 Escalation 三分

> 状态：**Accepted 原则 / 分阶段实施**（Retry/Fallback 在 Phase 3；Escalation 在 Phase 6）
> 日期：2026-09-05
> 影响：`AgentError`、`FallbackChatModel`、`AgentRuntimeLive.invokeModel`、工具账本隔离
> 配套：[multi-model-execution.md](multi-model-execution.md)

## Context

今天模型路径几乎没有同模型 Retry：瞬时失败直接打到 Run 失败或人工 `Retry` 整个 Run。`FallbackChatModel` 能在 retryable 错误上换 Provider，但平台 QA 未接线，且它把「换模型」和「再试一次」混在同一条链。更危险的是产品层可能把「答案不满意」也当成要换模型——那会把成本、不确定性和副作用放大。

工具路径已经有 `ToolRetryPolicy` 与崩溃重放分离。模型路径必须对齐同一纪律，且 **不得**因此重跑已结算 Tool。

## Decision

三种机制分开实现、分开记账、分开观测：

| 名称 | 含义 | 允许的原因 |
|---|---|---|
| Retry | 同一 `ModelRef` 再请求 | Timeout、有界 5xx、Retry-After 内的 429 |
| Fallback | 换模型或 Provider | Unavailable、熔断、额度耗尽、健康摘除 |
| Escalation | 换更高（或预算下更低）Profile | schema 连续失败、显式置信/风险/Eval 策略 |

规则：

1. 每种 `AgentError` 必须能回答 `retryable` / `fallbackable` / `fatal`。能力不匹配、非法请求、鉴权失败、安全拦截、取消 = fatal 于路由意义，不扫射候选。
2. Fallback 链有界；耗尽 fail-closed。
3. 「不喜欢业务答案」不得触发 Fallback。那只能走 Escalation，且必须是声明策略（verifier / schema / risk），不是心情。
4. 模型 Retry/Fallback **只重做 LLM Activity**。Tool 成功记录保持；idempotency key 与 fencing 不变。
5. 跨 Provider Fallback 必须用 canonical messages 重建请求，禁用依赖原厂商 continuation 的 delta。

默认成本形态是 **Cascade**（便宜模型不够再升档），不是 Debate。

## Alternatives

- **A. 继续只靠 Run 级人工 Retry。** 否决：Provider 抖动会把整次经营分析打成 Failed，运维只能重跑，Tool 风险更高。
- **B. 失败就换下一个模型直到有一个「看起来像答案」。** 否决：掩盖路由/提示词 bug，放大费用，破坏可解释性。
- **C. 用多模型投票当默认可靠性。** 否决：贵、慢、难评测；与单 Agent 优先冲突。

## Consequences

- Phase 3 接线已有 `RetryPolicy` / `CircuitBreakerPolicy` / `RateLimitPolicy`，不必新造一套政策类型。
- `FallbackChatModel` 的 fail-closed 规则成为 Router 的规范实现，类本身可在接线后降为测试替身。
- ModelCall 账本需要 `attempt`（已有）以及 fallback/escalation 的 decision codes；不把每次尝试写成独立 Run。
- Property test：fallback 无环；hard budget 含重试累加；Tool 成功次数不随 LLM retry 增加。
- Escalation API 可以晚于 Retry/Fallback 出现，但文档与错误分类从现在就禁止混用名词。

当前实现边界与后置验收以 [目标架构](model-runtime-target.md) 的 Phase 1 节和 [预算状态表](model-budget-and-cost.md) 为准。
