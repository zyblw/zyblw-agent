# 持久化与恢复

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-08-01
>
> 事实来源：对应模块源码、测试与构建定义

## InMemoryRunStore

用于测试和单进程开发，已覆盖：

- expected version 乐观锁。
- eventId 幂等追加。
- 单调事件序号查询。
- 持久化取消请求。
- 工具执行账本。
- Run 及其关联内存数据删除。

## 唯一 Agent Runtime

`AgentRuntimeLive` 直接使用 `AgentState` 与 `RunStore`。本地同步入口通过 `RunStore.createWithEvents` 原子创建初始状态与
`RunCreated`；生产异步入口改用 `RunSubmissionStore`，把初始状态、首事件、Start 命令和 dispatcher 放在一个事务内。
后续转换通过 expected version 乐观锁和 `RunStore.commit` 在一个事务内
提交状态与领域事件。所有 Adapter 在写入前验证事件 runId、连续 sequence 与 `state.lastEventSequence` 一致，
创建批次必须从 sequence=0 开始。

模型返回工具调用时，完整 `DurableToolPlan` 会与助手消息一起保存。每个冲突无关批次在 Fiber 启动前一次性写入
`Prepared` pending writes；单调用账本独立推进，但 AgentState 只有在整批结果齐备后才原子写入工具消息并推进
`nextBatchIndex`。因此进程既能在批次边界恢复，也能恢复“同批部分成功”的中间状态。

## PostgreSQL

框架默认迁移位于专属 classpath：

```text
modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/migration/
```

主要表：`agent_runs`、`agent_events`、`agent_run_commands`、`agent_run_dispatch`、`agent_messages`、`agent_steps`、
`model_calls`、`tool_executions`、`approval_requests`、`usage_records`、`agent_business_operations`、
`agent_outbox_events`、`agent_inbox_messages`、`agent_compensations`、`agent_memories`、`agent_embedding_cache`、
`agent_embedding_quota_windows`、`agent_embedding_quota_reservations`、`agent_eval_snapshots`、
`agent_workflow_checkpoints`、`agent_workflow_node_executions`、`agent_workflow_waits` 与 `agent_workflow_signals`。

当前 `main` 是明确不兼容 0.2 的 0.3 开发线，默认 location 只有一个
`V001__zyblw_agent_0_3_baseline.sql`，只支持空 schema/新数据库。旧环境不能通过 `repair`、手改 history 或假装 baseline
接管；应建立新 schema、重新导入业务允许保留的数据并重建派生索引。0.3.0 发布后才冻结该基线并恢复只追加 migration 的
发布纪律。完整操作见[PostgreSQL 迁移发布契约](database-migrations.md)与[升级到 0.3.0](upgrading-to-0.3.0.md)。

框架不会因 JAR 被加载而自动修改数据库。宿主显式调用 `AgentPostgresMigrations.migrate`，默认使用独立历史表
`flyway_zyblw_agent_schema_history`。

`PostgresRunStore`、`PostgresRunCommandStore` 与 `PostgresRunSubmissionStore` 使用同一个宿主 JDBC DataSource。
`PostgresRunStore` 使用 blocking executor、连接 Scope、JSONB 状态和 `WHERE version = ?`
乐观锁。Run 创建/首事件以及状态 CAS/后续事件分别在短事务中提交；SQLSTATE 会区分可重试的连接、序列化、
死锁和取消错误。

Testcontainers 已使用 PostgreSQL 16 真库验证 migration、事务、JSONB/UUID/TIMESTAMPTZ、乐观锁、审批状态、
工具账本和并发取消。运行命令：

```bash
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresRunStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresRunCommandStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresEmbeddingGovernanceIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresEvalTrendStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresWorkflowCheckpointStoreIntegrationSpec"
```

`PostgresWorkflowCheckpointStore[S: JsonCodec]` 保存完整 Workflow identity、Session、游标、状态、step 和访问预算。相同
快照幂等，相同 identity 只能推进到更大的 step；checksum、JSON、identity 或冗余列异常全部 fail-closed。

同一 Adapter 同时实现 `WorkflowExecutionStore[S]`，推荐通过
`PostgresAgentPersistence.workflowExecutions[S]` 装配生产 Workflow。0.3 基线为每次节点访问保存 Running/Prepared/Committed
台账；claim、heartbeat、prepare 与 commit 比较 owner/token/generation/未过期时间。`commit` 在一个短事务中锁定全部
Prepared execution、推进 checkpoint、注册/消费 durable wait，并把台账改为 Committed；任何一步失败都整体回滚。过期
Prepared 被新 owner 领取时保留 outcome，恢复不重新调用节点。`timeline` 复用 execution 主键按 `(step,nodeId)` 稳定分页
并返回低敏投影；官方
内存/PostgreSQL Adapter 已实现，第三方 `WorkflowExecutionStore` 若尚未实现会明确返回 typed persistence failure，而不是
返回不完整数据。claim 还会在同一原子边界验证该 Run 已有 checkpoint/其他 step 的 Workflow/version/session identity；
PostgreSQL 用 transaction-scoped advisory lock 关闭并发首次 claim 的检查-插入窗口。

`WorkflowExecutionStore.signal` 使用稳定 signal ID 和 payload hash 跨 Worker 去重；同 ID 不同 payload 冲突。
`expireDue(limit)` 用有界 `FOR UPDATE SKIP LOCKED` 批次裁决到期 wait。两条路径锁定相同行并使用数据库时钟，确保 signal 与
deadline 竞态只有一个胜者。Signaled/TimedOut wait 行本身同时是 durable wake command；`claimWakeups` 使用
`FOR UPDATE SKIP LOCKED` 换发 token 并递增 generation，`heartbeatWakeup`、`abandonWakeup` 和 checkpoint commit 都重验当前
数据库租约。`WorkflowWakeWorker.startScoped` 将轮询、恢复和 heartbeat 绑定到 ZIO Scope，不把长时间等待实现成长寿命 sleep Fiber。

删除使用 `DELETE FROM agent_runs`，所有子表依赖 migration 中的 `ON DELETE CASCADE` 由 PostgreSQL 原子清理。

`PostgresTransactionalWriteExecutor` 在同一 transaction 中提交业务 mutation、producer 业务幂等结果、outbox 和补偿
计划；`PostgresTransactionalInbox` 在消费端同一 transaction 中提交 inbox 去重与 consumer 业务 mutation。外部网络确认
仍不可能与本地数据库形成一个原子事务，因此发布语义诚实保持 at-least-once，并使用稳定 eventId/messageId 去重。

`PostgresEmbeddingCacheStore` 提供租户/模型完整键批量缓存和有界 TTL 清理；`PostgresEmbeddingQuotaStore` 在窗口行锁下
原子提交 requestId/hash 预留与三项硬计数。两者通过 `PostgresAgentPersistence.embeddingGovernance` 共用宿主连接池，
不会在事务内调用 Embedding Provider。详细语义见 [embedding-governance.md](embedding-governance.md)。

`PostgresEvalTrendStore` 提供跨节点 CI 的不可变低敏发布事实：完整评测身份、最近成功基线部分索引、同 ID 并发幂等、
TEXT checksum 事实与 JSONB 分析投影。它通过 `PostgresAgentPersistence.evalTrends` 装配；详细语义见
[eval-trend-and-release-gate.md](eval-trend-and-release-gate.md)。

完整表说明、唯一 migration 事实源与 pgvector 接入见 [database-schema.md](database-schema.md)。
