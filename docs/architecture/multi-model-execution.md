# 多模型执行

> 状态：Proposed（Phase 6+ 为主；本文件先冻结原则，避免过早实现）
>
> 最后核验：2026-09-05
>
> 决策来源：[ADR-0023](0023-retry-fallback-escalation.md)、[ADR-0024](0024-planner-and-durable-execution.md)
>
> 配套： [目标架构](model-runtime-target.md) · [路由](model-routing.md)

多模型协作发生在 **步骤级**，不是五个 Agent 互相聊天。默认单 Agent loop；复杂任务用 Durable Workflow 串起不同 Profile 的步骤。只有 Eval 证明有收益才引入多 Agent，且 callee 是 SubRun，不是「另一个模型」。

---

## 三种机制必须分开

| 机制 | 触发 | 换什么 | 谁决定 |
|---|---|---|---|
| **Retry** | Timeout、瞬时 5xx、可重试 429 | 同一 ModelRef | ModelRuntime |
| **Fallback** | Provider 不可用、熔断、额度耗尽 | 另一模型 / Provider | Router + 错误分类 |
| **Escalation** | schema 连续失败、低置信、业务风险、Eval 策略 | 更高 Profile | Agent / Workflow 策略，不是基础设施自动乱枪 |

不能因为模型回答「库存正常」就自动再叫三个模型。那不是 Fallback。

无限 fallback 禁止。链有界，耗尽则 typed fail。`CapabilityUnsupported` / `InvalidRequest` 不得顺着候选列表扫射。

模型 retry 与工具 retry 完全不同。有副作用的 Tool 必须带 idempotency key，遵守现有 Command / fencing。LLM 再请求十次，已结算 Tool 仍是一次。

---

## 默认成本策略：Cascade，不是 Debate

```text
cheap model（Fast / Standard）
        ↓
   结果足够？（schema / verifier / 业务规则）
    ┌───┴───┐
   yes     no
    │       │
   返回    升到 Reasoning / Expert
```

不要默认 5 模型讨论 + 投票 + 总结。那种路径贵、慢、难回放。Eval 证明明显增益后再开。

推荐升档：

```text
Fast → Standard → Reasoning / Expert
```

降档（soft budget）：

```text
Expert → Reasoning → Standard
```

---

## 两种任务模式

Router **不**选择 Workflow。Agent Runtime / 业务策略选择。

### Interactive Agent Loop

适合：简单问答、单工具、少量工具、RAG、短任务。

全程可以只用 Standard；必要时单步 Escalation。

### Durable Workflow

适合：Amazon 经营分析、批量、跨系统、长时、审批、高价值。

节点表达 `ModelRequirement`，不表达厂商名。实现上复用 `AgentRuntime` 子 Run，而不是在 Workflow 引擎里直接打 HTTP。

---

## Planner / Executor（Phase 6）

复杂任务：

```text
User Request
     ↓
Planner（Reasoning Profile）→ Structured TaskPlan proposal
     ↓
Runtime validate / authorize / compile
     ↓
Executor 步骤（大量 Fast / Standard + 确定性 Tool / ZIO Service）
     ↓
Synthesizer（Standard）
     ↓
Verifier（Expert，仅当风险/价值需要）
```

Planner **绝对不能**直接控制基础设施。它不能生成任意 Scala class、SQL、shell 后直接执行。

`TaskPlan` 是严格 schema：

```text
steps
dependencies
expected outputs
required capabilities
```

`PlanCompiler` 检查：未知工具、非法依赖、环、步数、loop budget、权限、审批、费用预估。通过后编译成现有 Workflow / Commands。这把「LLM 规划」和「可靠执行」分开。

简单任务 **禁止**强制 Planner。

---

## 典型：Amazon ASIN 90 天诊断

```text
Run
 ├─ classify          Fast
 ├─ plan              Reasoning
 ├─ fetch sales       确定性 Tool
 ├─ fetch inventory   确定性 Tool
 ├─ fetch ads         确定性 Tool
 ├─ fetch competitors Tool / RAG
 ├─ normalize         ZIO Service（不要交给 LLM）
 ├─ summarize data    Fast
 ├─ reason            Reasoning
 ├─ synthesize        Standard
 └─ verify            Expert only when required
```

这才是 Multi-model Agent：同一次 Run、多个步骤、多个 Profile。不是并行开五个聊天。

---

## 典型：中医 RAG

```text
用户问题
 ↓ Query normalization（规则或 Fast）
 ↓ Embedding（独立 SPI，不必与生成同厂商）
 ↓ Dense + lexical → fusion → rerank
 ↓ ContextBuilder + ContextBudgeter
 ↓ Standard 生成
 ↓ 必要时 Reasoning
 ↓ Citation Validation（确定性 / 规则优先）
```

Qwen embedding 不必搭配 Qwen generation。检索与生成的 Router 分开。

---

## Conversation 与崩溃恢复

canonical `ConversationState` = `AgentState.messages` + context checkpoint + 两个账本。

进程在「模型已成功、工具未跑」或相反时，恢复只看 ledger 状态。ModelCall `Unknown` 保持今天 fail-closed，不自动重放（除非显式 Recover 且策略允许）。已成功 Tool 不重复执行。

这覆盖验收 Test F，且不需要新的执行引擎。

---

## 未来 Multi-Agent

第一阶段不建设 marketplace、谈判、投票、Agent 经济。

将来 Agent A 调 Agent B：B 是 **SubRun / Agent Capability**，仍进 Run / Event / Command / Budget / Permission / Trace。不要把 B 当成 `ChatModel`。

现有 `AgentAsTool` + `HandoffBoundary.shrink` 已是正确方向：权限与预算只收窄。

---

## 观测与评测（Phase 7）

span：

```text
agent.run
  └─ workflow.step
       └─ model.route
            └─ model.call
                 └─ provider.http
```

RouterEval：同一任务跑 Fast / Standard / Reasoning / Expert，得到质量 / 成本 / 延迟 / 可靠性，形成内部 Pareto。Router 的 quality 分只许来自这里。

Shadow：新模型并行调用，不执行 Tool、无副作用、不回用户。Canary 按 1%→5%→20%→50%→100% 晋级，门禁是 task success / cost / latency / error，不是新闻稿。
