# ADR-0005：检查点、乐观锁与恢复

## 背景与问题

进程可能在工具执行和状态确认之间崩溃。恢复时重复副作用是最危险的失败模式。

## 候选方案

1. 仅内存状态。
2. 每个 token 都做事务。
3. 在关键状态转换后保存版本化检查点，并单独记录工具执行账本。

## 决定

采用方案 3。提供 `InMemoryRunStore` 和 PostgreSQL Adapter；保存使用 expected version 乐观锁；事件以 event ID 幂等追加；工具执行记录 `Prepared/Running/Succeeded/Failed/Unknown`，恢复遇到模糊状态时暂停人工处理，不自动重放非幂等工具。

## 未选择原因

方案 1 不可用于生产；方案 2 成本高且仍无法让外部副作用和数据库事务原子化。

## 风险与演化

外部系统不支持幂等键时无法做到 exactly-once。框架明确提供 at-least-once/approval/manual-reconcile 语义，不做虚假保证。
