# 模型路由

> 状态：固定顺序主调用路由已实现（Experimental）；动态评分/健康/限流仍 Proposed
>
> 最后核验：2026-09-05
>
> 决策来源：[ADR-0022](0022-model-profile-and-routing.md)、[ADR-0023](0023-retry-fallback-escalation.md)
>
> 配套： [现状](model-runtime-current-state.md) · [目标](model-runtime-target.md)

Router 是 **确定性策略引擎**，不是另一个 LLM。它可以调用廉价分类器作输入，但分类器只能提议 `ModelProfile`，不能指定未允许的 Provider，不能改预算，不能执行工具。

---

## 决策流程

```text
ModelRequirement
       ↓
Capability Filter          硬约束
Security / Data Policy     硬约束
Budget Filter              硬约束
Provider Health            硬约束（熔断摘除）
Rate Limit Availability    硬约束（无额度则跳过或排队）
Profile Candidates         配置
Internal Eval Quality      软评分
Cost / Latency / Cache     软评分
       ↓
RouteDecision
```

硬约束不满足直接淘汰。软评分只在剩余候选上计算。权重来自配置，不来自厂商营销榜。

第一版不要强化学习、Bandit 或神经网络 Router。

---

## 硬约束

必须在 Adapter 之外、Router 之内执行：

| 约束 | 来源 | 失败 |
|---|---|---|
| 能力 | `ModelRequirement.capabilities` vs `ModelCapabilities` | 永不选中；这是路由 bug 或不支持，不是 retry 理由 |
| 上下文尺寸 | 估算 input + output reserve vs `maxInputTokens` | 淘汰或先走 Context 压缩，不换十个同样溢的模型 |
| 数据驻留 / 敏感级 | `DataSensitivity` + tenant allow/deny list | Restricted 数据不得发往禁止 Provider |
| Provider allowlist | 部署 + 租户策略 | 分类器不能突破 |
| 预算硬限 | 同一 `BudgetState` | Expert / 高价候选直接去掉 |
| 可用性 | Health / circuit breaker | 临时摘除，不从 Catalog 删除 |

`CapabilityValidator` 今天已经做请求级预检。Router 把它提前到 **选模型之前**，避免先选再 fail。

---

## 软评分

示意，不是冻结公式：

```text
score = wq * quality
      + wr * reliability
      + wl * latencyScore
      + wc * costScore
      + wk * cacheAffinity
```

- `quality` 来自内部 RouterEval / 任务类别趋势，不是 LMSYS 截图。
- `reliability` 来自近期 error rate、schema success、tool-call success。
- `cacheAffinity` 可以提高同分候选排名，**不能**压过能力或安全。
- 缺失 Eval 分数时用保守默认，不得假装「未测即最优」。

---

## RouteDecision

必须是可持久化、可回放的领域对象，不能只记 `selected model = xxx`。

至少包含：

```text
requestedProfile
requiredCapabilities
dataSensitivity
selectedModel          （Provider + modelId）
fallbackModels         （有序列表，可空）
policyVersion
catalogVersion
pricingVersion
decisionCodes          （如 capability-ok, budget-soft, health-skip-gemini）
estimatedCost
budgetSnapshot         （低敏：剩余额度，不含 Prompt）
healthSnapshot         （低敏：摘除名单）
legacyExplicitModel    （若作者写死了 provider/model）
```

挂入现有 `ModelCallExecutionRecord` 或同事务邻接 JSON。不新建与 RunStore 竞争的路由日志库。

Inspector / HTTP 只暴露低敏字段：profile、codes、provider、model、versions、usage。不暴露 Prompt、候选完整价目合同、密钥。

---

## ModelRole 与显式模型

| 输入 | Router 行为 |
|---|---|
| 只有 `ModelProfile` | 走候选 + 硬/软评分 |
| 只有 `ModelRole` | 查角色 → 默认 Profile → 同上 |
| 已写 `provider`/`model` | 硬约束通过则选用；失败 fail-closed，不擅自换模型 |
| `ModelPolicy` 覆盖 | 视为部署指定的显式组合，同样只做硬校验 |

这保持今天「作者写死的模型是行为契约」与「运维可在已注册组合间切换」两条现网语义。

---

## 语义 Router 只能辅助

当 Agent 无法声明 Profile 时，允许 `TaskClassifier`：

- 只用 Fast Profile
- 严格 schema，例如 `{ profile, needsVision, needsTools, complexity }`
- 输出是 **提议**，再进确定性 Router
- 失败则回退 Agent / 部署默认 Profile，不得猜 Expert

分类器禁止：

- 绕过 security / budget
- 指定未允许 Provider
- 直接执行工具
- 修改 Runtime policy

---

## 数据安全进入路由

`ModelSettings.requirement` / 执行上下文允许携带：

```text
dataSensitivity: Public | Internal | Confidential | Restricted
tenant
region
allowedProviders
forbiddenProviders
```

Amazon 财务或病历类数据可以禁止特定国际 Provider。过滤发生在评分之前。Adapter 不得「先发出去再看响应」。

密钥继续只以 `SecretRef` 解析，不得进入 Decision、Catalog、Event。

---

## Health、限流、熔断

第一版用已有 `ReliabilityPolicy` 类型接线，不建服务发现：

- 统一 `ProviderRateLimiter`：requests / tokens / concurrency × tenant × provider × model
- Circuit breaker：连续失败或高 429 则摘除，到期半开
- 指标：availability、error rate、rate-limit rate、p50/p95、schema success、tool-call success

限流不得只藏在某个 Adapter 内部；Adapter 仍负责把 429 映成 `ErrorCategory.RateLimit`。

---

## 与现有装配的关系

```text
今天：RoutedChatModel(name) / FallbackChatModel(chain)
目标：ModelRuntime.route → 选中的 ChatModel
```

`RoutedChatModel` 退化为「按已决定的 provider 取 Adapter」的查找表。`FallbackChatModel` 的候选顺序由 `RouteDecision.fallbackModels` 取代，规则（只对 retryable/fallbackable、能力失败 fail-closed）保留。

---

## 回放

给定同一 `ModelRequirement` + 同一 `policyVersion` + 同一 `catalogVersion` + 同一 health/budget 快照，Router 必须得到同一 `RouteDecision`。这是 property test 目标，也是事故解释的前提。

## Phase 1 的精确执行合同

第一版不使用软评分、Health、cache 或限流可用性。候选目录是 `ModelRoutingPolicy.candidates` 的有序序列，
不是另一个服务或数据库 Catalog。Provider 的 endpoint/协议版本由宿主注册身份治理；替换连接目标必须改变该身份，
不能在同名 Adapter 下绕过组合冻结。

`RouteDecision.candidates` 保存本次有序过滤结果与拒绝码，`ModelRouter.select` 对这些证据执行纯选择。
**这证明选择可复现，不等于整个过滤过程已可重算**。完整过滤回放还需要当时能力、预算与安全策略输入快照；
未来引入评分时也必须冻结每项分值、输入版本和同分规则，不能只加一个 policyVersion。

当前保存选中单价、价目内容指纹与估计输入/输出。选中价格足以按同一 usage 重算该调用费用；不依赖部署保留旧价目表。
恢复会验证 Decision 选中项与账本 provider/model、候选证据、估算费用及 Replayable 请求一致。
管理面 ModelCall 投影和 Incident Pack 只暴露 profile、policy version、低敏 decision codes、价格摘要和估计费用，
不投影 Prompt、完整单价合同或 API key。

`ModelRequirement.strictToolSchema` 才表示步骤必须获得 Provider 的严格 Schema 保证。`ToolDefinition.strict` 保持既有 wire
兼容语义：不支持该字段的 Adapter 可以按其固定档案省略，但 Runtime 仍会验证工具参数；两者不能混作同一个硬约束。

敏感级取 `max(policy.sensitivityFloor, requirement.sensitivity)`。宿主必须按消息、RAG、工具结果、记忆等整份上下文
声明上界，调用方不能降低部署下限。第一版不自动推导各来源标签，也不提供动态 tenant/region allowlist；不同信任域应使用
各自已限制候选的 Runtime 装配。辅助 LLM 压缩与记忆提取仍须由宿主单独治理。

未来动态政策撤销优先于恢复：历史决策用于解释，不能作为绕过当前安全拒绝的授权。没有旧配置或新政策拒绝时应停止，
不能自动找另一个 Provider。
