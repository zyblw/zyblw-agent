# ADR 0024：Planner 与耐久执行（后置）

> 状态：**Accepted 原则 / Deferred 实现**（不早于 Phase 6）
> 日期：2026-09-05
> 影响：未来 Workflow 节点、Harness 与 LLM Plan 的边界；**本轮不改代码**
> 不推翻：[ADR-0006](0006-workflow-boundary.md)、[ADR-0016](0016-agent-application-runtime.md)、[ADR-0018](0018-next-generation-runtime-kernel.md)

## Context

复杂业务（例如 Amazon 90 天诊断）需要「先规划、再执行」。若让 Reasoning 模型直接打工具、写 SQL、启 Workflow，模型会成为权限主体。若另造一套多 Agent 讨论圈，会丢掉现有 Run/Command/lease 不变量。

仓库已有：单 Agent loop、Durable Workflow（Experimental）、Harness Goal/Plan/Todo（Experimental，且 **Plan 不是 LLM 合同**）。缺的是：LLM 产出严格 `TaskPlan` proposal，由 Runtime 编译进现有耐久执行。

## Decision

1. **Phase 1–5 不做 Planner / PlanCompiler / ModelStep。** 先把协议、路由、预算、Qwen/DeepSeek/Gemini 与 Runtime 接入做完。
2. 将来 Planner 只产出 **严格 schema 的 proposal**。Runtime 负责 validate / authorize / compile / execute。
3. `PlanCompiler` 的输出必须是现有 Workflow 定义或现有 Command/Tool 计划，而不是任意可执行文本。
4. 简单任务走 Interactive loop，**禁止**强制 Planner。
5. Workflow 用 **Agent-as-node + `ModelRequirement`** 表达步骤模型档，禁止 `DeepSeekStep`。
6. Router 不负责选择 loop 还是 Workflow。
7. 多 Agent 仍要 Eval 门禁；callee 是 SubRun，沿用 `AgentAsTool` / `HandoffBoundary` 只收窄。

## Alternatives

- **A. Phase 1 就做 Planner。** 否决：在路由和预算未稳时引入 LLM 图生成，会把不可靠规划写进耐久状态。
- **B. 让 Planner 直接执行 tool/SQL/shell。** 否决：与「模型只提议」铁律冲突。
- **C. 把 Harness Plan 当成 LLM TaskPlan。** 否决：Harness Plan 是人工/系统任务支架，已有「Plan ≠ 权限」不变量；混用会让文字计划变成授权源。

## Consequences

- 开发顺序锁死：没有稳定 RouteDecision 与 ModelCall 结算，就不能让 Planner 开子步骤。
- 文档可以先写 Amazon / 中医级联示例，作为 Phase 6 验收故事，而不是现在的 API。
- Workflow 引擎第一阶段零改动，降低与 Experimental 图语义的纠缠。
- 若业务提前需要多步骤，先手写 Workflow + 不同 AgentDefinition/Requirement，不要等通用 Planner。
