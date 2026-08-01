# ADR-0006：确定性 Workflow 与 Agent 边界

## 背景与问题

开放式推理适合 Agent，审批和业务状态机适合确定性工作流。用一个自由循环承载所有流程会削弱审计和恢复能力。

## 候选方案

1. 所有流程都由 Agent 决策。
2. 复制完整 LangGraph。
3. 提供轻量、类型化、可检查点的 Workflow SPI，Agent 只是节点类型之一。

## 决定

采用方案 3，但把节点计算与控制边进一步分离：

- `WorkflowNode` 返回 `NodeOutcome.Succeeded`、`NodeOutcome.Suspended`，或在 durable engine 中返回
  `NodeOutcome.Awaiting` 注册耐久 wait；
- `WorkflowTransition` 在节点外声明 `Next`、`Route`、`FanOut` 和 `Complete`；
- `WorkflowDefinition.make` 在运行前验证入口、目标、可达性、重复边、fan-out 约束与循环访问上限；
- `WorkflowDefinition` 必须声明稳定 `WorkflowId/WorkflowVersion`；`WorkflowCheckpoint` 同时保存该 identity、Session、
  游标、不可变状态、step 和访问次数；
- 第一阶段 fan-in 只提供 `AllSucceeded`，任一失败由 ZIO 结构化并发中断兄弟 Fiber，且不提交 join checkpoint。
- 内存与 PostgreSQL checkpoint Store 都只允许相同 identity 内按 step 单调推进；PostgreSQL Adapter 提供经过 PostgreSQL 16
  Testcontainers 验证的完整快照、checksum 和损坏拒绝。
- 生产耐久模式使用 `WorkflowExecutionStore`：claim 比较 owner/token/generation/expiry，节点结果先进入 `Prepared`，
  再把一个节点或整个 fan-out 的 execution 与 checkpoint 在同一原子临界区/数据库事务提交；
- 0.3 fresh baseline 保存节点 execution ledger。进程在 prepare 后、checkpoint 前失败时，新 owner 领取更高 generation 并复用
  pending outcome；旧 owner 的 heartbeat、prepare 与 commit 均被 fencing 拒绝。
- wait 注册/消费与 execution/checkpoint 同事务提交；signal 使用稳定 ID/payload hash 去重，signal 与 timeout 锁定同一行并由
  数据库时钟裁决唯一胜者。

节点状态由应用定义，持久化通过 SPI。Handoff 是受深度、上下文和工具策略限制的 Agent 转移，不自动继承全部权限。

## 未选择原因

方案 1 不适合强审计业务；方案 2 的 reducer、子图命名空间和调度语义在当前阶段成本过高。

## 风险与演化

当前实现有意限定 fan-out 分支为单步 `Complete` 节点。durable timer/signal 的状态机与 Store 已实现，但受监督 timer worker、
耐久 wake-command 交接、人工任务、子图和 quorum/race join 尚未实现。下一阶段先补 worker 交接、进程 kill/数据库重启与
多 Worker soak，再由真实业务和 eval 决定是否增加更复杂图语义。
保留 Temporal/zio-temporal Adapter 边界，不让 core 依赖具体工作流引擎。

简单 Agent loop 和顺序 ZIO 组合不强制图化；多 Agent 只有在固定 eval 中持续优于单 Agent时才进入图调度。
