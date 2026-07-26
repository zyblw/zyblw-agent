# ADR-0006：确定性 Workflow 与 Agent 边界

## 背景与问题

开放式推理适合 Agent，审批和业务状态机适合确定性工作流。用一个自由循环承载所有流程会削弱审计和恢复能力。

## 候选方案

1. 所有流程都由 Agent 决策。
2. 复制完整 LangGraph。
3. 提供轻量、类型化、可检查点的 Workflow SPI，Agent 只是节点类型之一。

## 决定

采用方案 3。Workflow 节点返回显式 `NodeResult`，支持下一节点、完成、暂停和有界 fan-out；节点状态由应用定义，持久化通过 SPI。Handoff 是受深度、上下文和工具策略限制的 Agent 转移，不自动继承全部权限。

## 未选择原因

方案 1 不适合强审计业务；方案 2 的 reducer、子图命名空间和调度语义在当前阶段成本过高。

## 风险与演化

第一版不提供分布式调度。保留 Temporal/zio-temporal Adapter 边界，不让 core 依赖具体工作流引擎。
