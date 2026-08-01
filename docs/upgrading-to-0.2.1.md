# 从 0.2.0 升级到 0.2.1

> 状态：`0.2.1` 已发布升级指南
>
> 最后核验：2026-08-01

`0.2.1` 是 `0.2.x` 兼容 patch。Agent Runtime、Provider、HTTP `/api/v1`、RAG、MCP 和原有 checkpoint-only Workflow
调用不需要源码迁移；所有消费者仍应重新编译并运行自己的契约测试。

## 谁需要采取额外动作

只有启用新增 durable Workflow execution 的应用需要数据库动作：

1. 应用 append-only V009 migration；
2. 使用 `PostgresWorkflowCheckpointStore` 的 `WorkflowExecutionStore` 能力；
3. 通过 `WorkflowEngine.makeDurable` 装配可信 worker identity、lease duration 和 heartbeat interval；
4. 在暴露 timeline 前，由 HTTP/CLI Adapter 使用可信 tenant/user 身份验证 `runId` 的读取权限。

仍使用 `WorkflowEngine.make` 和 `WorkflowCheckpointStore` 的应用可以保持原装配。

## 新增公共能力

- 节点 execution ledger：`Running`、`Prepared`、`Committed`；
- owner/token/generation/expiry 完整 fencing；
- outcome prepare 与 checkpoint/ledger 原子 commit；
- 过期 Prepared outcome 由新 generation 复用，避免再次调用节点；
- 低敏 `WorkflowExecutionStore.timeline`，使用排他的 `(step, nodeId)` 复合游标分页。

这些能力不会让节点内部的外部副作用自动获得 exactly-once 语义。写工具仍需稳定业务幂等键、outbox/inbox 或显式补偿。

## 自定义 Store

自定义 `WorkflowExecutionStore` 在源码层面仍可编译，因为 `timeline` 提供返回 typed persistence failure 的具体默认实现。
在生产中提供 Workflow inspection 前必须实现 timeline，并保证：

- 按 `(step, nodeId)` 稳定排序和排他翻页；
- `limit` 只接受 `1..500`；
- 不返回应用状态、pending outcome、lease token 或工具正文；
- 不混合不同 `runId`；
- Store 本身不冒充授权层。

## 数据库升级

V009 新增 `agent_workflow_node_executions`，不修改 V001–V008。按
[宿主数据库迁移](database-migrations.md) 在空库和代表性 `0.2.0` 数据库上验证。

如果宿主 migration 历史已占用 V009，不能复制同版本不同 checksum 的框架 SQL；应使用隔离 migration runner 或宿主自有
高位版本桥接，具体方案以宿主 Flyway 历史为准。

## 滚动升级建议

1. 停止创建新的 durable Workflow Run；
2. 等待 Running 节点提交或让 lease 自然过期；
3. 执行 V009；
4. 部署 `0.2.1` worker；
5. 用暂停/恢复、Prepared 接管和 stale worker 拒绝用例验证；
6. 恢复提交并观察 generation、lease 和完成延迟。

不要把已由 `makeDurable` 执行的活跃 Run 直接交给 `0.2.0` checkpoint-only worker。旧 worker 不读取 execution ledger，可能
重复执行已 Prepared 但尚未推进 checkpoint 的节点。紧急回滚时先停止领取、隔离活跃 Run，并按业务副作用幂等能力制定恢复。

## 验证命令

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.2.1-local"; publishM2'
cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.2.1-local sbt -batch clean compile
```

完整兼容面见 [兼容性契约](compatibility.md)，用户可见变化见 [CHANGELOG](../CHANGELOG.md)。
