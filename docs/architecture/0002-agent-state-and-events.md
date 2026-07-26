# ADR-0002：显式状态、步骤与分层事件

## 背景与问题

Agent 必须能够检查、暂停、恢复、重放和审计。仅保存消息列表无法表达审批、预算、工具幂等和失败位置。

## 候选方案

1. 只保存聊天消息。
2. 从第一版开始完整 Event Sourcing。
3. 版本化快照为事实状态，同时保存精选持久化事件。

## 决定

采用方案 3。`AgentState` 不可变并携带 `Version`；`AgentStep` 表达模型、工具、Guardrail、审批和 Handoff；UI 流事件、持久化事件和内部诊断事件语义分开。Store 以乐观锁保存快照，以事件 ID 和序号保证幂等与顺序。

## 未选择原因

消息列表不能安全恢复；完整 Event Sourcing 会过早引入投影、迁移和兼容负担。

## 风险与演化

状态模式升级需要迁移。所有持久化状态包含 schema version，后续增加 codec migration 和 time-travel API。
