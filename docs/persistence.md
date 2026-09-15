# 持久化与恢复

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-09-14
>
> 事实来源：对应模块源码、测试与构建定义

## InMemoryRunStore

用于测试和单进程开发，已覆盖：

- expected version 乐观锁。
- eventId 精确幂等追加；跨 Run、sequence 或 payload 复用会失败。
- 跨批事件连续性；`save` 不能改变事件游标，`commit` 首序号必须紧接已提交游标。
- Store 边界拒绝负 sequence、小于 -1 的查询游标和超过 4096 的事件页。
- 单调事件序号查询。
- 持久化取消请求。
- 工具执行账本。
- Run 及其关联内存数据删除。

全部事实由一个 `Ref.Synchronized` 持有，使状态 CAS、事件、工具账本、取消位和 ModelCall 账本共享原子更新边界；它仍只用于测试和单进程开发。

## 唯一 Agent Runtime

`AgentRuntimeDriver` 直接使用 `AgentState` 与 `RunStore`；纯 `AgentKernel` 不访问持久化。 本地同步入口通过 `RunStore.createWithEvents` 原子创建初始状态与
`RunCreated`；生产异步入口改用 `RunSubmissionStore`，把初始状态、首事件、Start 命令和 dispatcher 放在一个事务内。
后续转换通过 expected version 乐观锁和 `RunStore.commit` 在一个事务内
提交状态与领域事件。所有 Adapter 在写入前验证事件 runId、批内连续 sequence 与 `state.lastEventSequence` 一致，
并在原子 CAS 中验证本批首序号紧接已持久化状态的最后游标；创建批次必须从 sequence=0 开始。无事件的 `save`
不得改变 `lastEventSequence`。`appendEvents` 只用于幂等重放或补齐不超过权威状态游标的事件，不能替代 `commit` 推进状态。

模型返回工具调用时，完整 `DurableToolPlan` 会与助手消息一起保存。每个冲突无关批次在 Fiber 启动前一次性写入
`Prepared` pending writes；单调用账本独立推进，但 AgentState 只有在整批结果齐备后才原子写入工具消息并推进
`nextBatchIndex`。因此进程既能在批次边界恢复，也能恢复“同批部分成功”的中间状态。

## PostgreSQL

框架默认迁移位于专属 classpath：

```text
modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/migration/
```

主要表：`agent_runs`、`agent_events`、`agent_run_commands`、`agent_run_dispatch`、
`model_call_executions`、`tool_executions`、`agent_suspensions`、`agent_business_operations`、
`agent_outbox_events`、`agent_inbox_messages`、`agent_compensations`、`agent_memories`、`agent_embedding_cache`、
`agent_embedding_quota_windows`、`agent_embedding_quota_reservations`、`agent_eval_snapshots`、
`agent_workflow_checkpoints`、`agent_workflow_node_executions`、`agent_workflow_waits`、`agent_workflow_signals`、
`harness_goals`、`harness_plans`、`harness_skills`、`harness_interactions`、`harness_goal_budgets` 与
`harness_budget_reservations`。

`0.9.0` 默认 location 只有一个 `V001__zyblw_agent_0_9_baseline.sql`，只支持空 schema/新数据库。启动探针会拒绝非当前
基线，不能通过 `repair`、手改 history 或伪造 baseline 接管。完整操作见
[PostgreSQL 迁移发布契约](database-migrations.md)与[0.9.0 全新安装](fresh-install-0.9.0.md)。

框架不会因 JAR 或普通 Store Layer 被加载而自动修改数据库。宿主可显式调用 `AgentPostgresMigrations.migrate`，也可选择
`PostgresAgentPersistence.migratedLayer` 在服务构建前自动 migrate/validate/verify；两者都使用独立历史表
`flyway_zyblw_agent_schema_history`，失败时阻止启动而不是回退内存。1024 维知识库另用
`migrateKnowledge1024`/`migratedKnowledge1024`，固定管理 `zyblw_agent_knowledge` schema 及其中的独立 history，避免与
`public` 核心 V001 冲突。运行时知识 SQL 使用完整 schema 名，不依赖连接 `search_path`。

`PostgresRunStore`、`PostgresRunCommandStore` 与 `PostgresRunSubmissionStore` 使用同一个宿主 JDBC DataSource。
`PostgresRunStore` 使用 blocking executor、连接 Scope、JSONB 状态和
`WHERE version = ? AND state_json.lastEventSequence = ?` 的复合 CAS。Run 创建/首事件以及状态 CAS/后续事件分别在短事务中提交；SQLSTATE 会区分可重试的连接、序列化、
死锁和取消错误。

`agent_runs` 的 identity/status/version/schema 列与 `state_json`、`agent_events` 的 identity/sequence/type 列与
`payload`、Tool/ModelCall ledger 的 identity/status/attempt 列与 `record_json` 共同组成持久化信封。Adapter 每次读取都
解码类型化对象并交叉核对稳定字段；任一漂移都会返回 typed `PersistenceFailure`，不会选一份数据继续运行。错误只包含
记录 ID 与冲突字段名，不回显状态、事件、工具结果或模型请求正文。时间列不参与交叉比较，避免 PostgreSQL/JDBC 精度差异
产生虚假冲突。该规则不增加第二事实源：JSON 仍是可恢复负载，冗余列负责约束、索引和完整性证明。

`PostgresHarnessStore` 将 Goal/Plan 的有界 `ArtifactReference` 写入 JSONB；Todo 引用随 `todos_json` 保存。引用不含制品 bytes、私有 metadata 或创建时间。读取时必须成功解码为领域类型，否则返回持久化失败；引用本身不授予 ArtifactStore 读取权限。`agent_artifacts` / `agent_artifact_versions` / `agent_artifact_audit` 保存 Artifact 元数据和审计；配置 `ArtifactBlobStore` 时版本表的 `bytes` 可空，正文按 sha256 外置。上述现行结构都由核心 0.9 V001 一次建立。

`harness_goal_budgets` 与 `harness_budget_reservations` 由核心 0.9 V001 建立。前者保存不可变总策略以及 Reserved/Consumed 原子计数器；后者以全局 RunId 保存完整 `RunLimits`、状态与结算后的 `UsageSummary`。预留事务先锁 Goal budget 行，再验证所有剩余额度并同时更新计数器和 reservation；相同 RunId/limits 幂等，不同绑定冲突。`NUMERIC` 保存费用，不能经过浮点数。`RunLimits`/`UsageSummary` 虽有 Scala 构造默认值，Adapter 读取耐久 JSON 时仍要求当前全部字段存在，避免 `{}` 被静默解码为宽松默认配置。

异步 Harness Start 使用 `HarnessCommandService`。`RunInitialization.prepareForGoal` 将 GoalId 绑定进 request hash；`PostgresRunSubmissionStore` 在原有 Created State、RunCreated、Start command、dispatcher 事务中追加预算预留。预算失败会回滚全部五类事实；同一 HTTP 幂等请求只返回第一条 Run/command/reservation。普通非 Harness `AgentCommandService.submitStart` 的事务和行为不变。

终态提交与预算结算之间仍是两个短事务，因此可能在进程崩溃时暂留 `Reserved`。`HarnessBudgetReconciler` 使用 `(created_at, run_id)` 排他 keyset 游标、1–512 页限制和进程内循环游标检查这些记录，只在 `RunStore` 已显示 Completed/Failed/Cancelled/TimedOut/BudgetExceeded 时按完整 usage 幂等结算。活跃、暂停或缺失 Run 保持 Reserved，等待恢复或人工核对；框架绝不按 TTL 推断“没有副作用”。宿主应把 `reconcileNext` 纳入受监督的周期任务并监控 `failedRunIds`。

`PostgresRunCommandStore.queueSnapshot` 使用数据库时钟返回低敏聚合：Queued 命令、当前可领取 Run、Leased Run、已过期
lease、DeadLetter 以及最早可领取命令的等待毫秒数。它不执行回收、不领取命令，也不返回 runId、tenant、payload、
idempotency key 或 token；业务可经 `AgentApplication.queueSnapshot` 定时采集并建立 backlog/SLO 告警。

仓库级 `DurableWorkerSoakProbe` 复用正式 Start 事务、`WorkerHost`、唯一 `AgentRuntime` 与本 Store，持续执行“有界批次 →
完全 drain”循环。它只输出队列高水位、Worker/并发计数、保守 10ms 延迟直方图、generation/attempt 聚合和门禁结果；
不输出 RunId、身份、Prompt、模型正文、命令 payload 或 lease token。脚本只连接自己创建的临时 PostgreSQL，并要求
`ZYBLW_AGENT_SOAK_CONFIRM_DISPOSABLE=true`，防止误写共享数据库。该报告是同机/CI 回归证据，不是生产容量或 SLO 承诺。

`WorkflowWakeWorkerSoakProbe` 对 wait-as-command 路径做同等级验证：每个 `WorkflowWakeWorker` 使用独立 PostgreSQL Store
与 Engine，共同领取 durable signal；报告只保留 wake/execution claim 数、参与 Worker 数、并发高水位、双 generation
重领、异常周期、未完成量和延迟直方图。脚本同样只允许一次性数据库，不产生新的队列表、Runtime 或公开 Store API。

Testcontainers 已使用 PostgreSQL 16 真库验证 migration、事务、JSONB/UUID/TIMESTAMPTZ、乐观锁、审批状态、工具账本、
状态/事件/Tool/ModelCall 持久化信封篡改拒绝、
并发取消、三实例六 lane drain、Worker 中断后过期重领、Worker 消失 + PostgreSQL pause/unpause 组合故障后的 generation 接管、
command 与 Workflow 两条路径的独立 JVM `SIGKILL` + 同实例 PostgreSQL restart 后跨进程重领、连接恢复及
`pg_dump/pg_restore`。运行命令：

```bash
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresRunStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresRunCommandStoreIntegrationSpec"
./integration-tests/command-worker-kill-recovery.sh --restart-postgres
./integration-tests/workflow-wake-worker-kill-recovery.sh --restart-postgres
./integration-tests/durable-worker-soak.sh
./integration-tests/workflow-wake-worker-soak.sh
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresEmbeddingGovernanceIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresEvalTrendStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresWorkflowCheckpointStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.PostgresHarnessStoreIntegrationSpec"
RUN_POSTGRES_INTEGRATION=1 sbt "postgres/testOnly com.zyblw.agent.persistence.postgres.RunStoreConformanceSpec"
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

`wakeQueueSnapshot(workflowId, definitionVersion)` 使用一次数据库权威时钟聚合 Pending/Due wait、可领取/活跃 wakeup、
过期 wake lease 与最早可领取年龄。该查询不调用 `expireDue`、不领取、不回收，也不返回 Run/Session/payload/owner/token；
内存与 PostgreSQL Adapter 共享相同契约，第三方 Adapter 未实现时明确 typed-fail。

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

`PostgresHarnessStore` 保存 Goal/Plan/Skill、追加式 Steer/FollowUp/UserMessage 与任务预算。CAS、Goal 外键、Skill 指纹冲突、交互序号和预算预留状态机与内存实现一致。`listInteractions` 使用排他的 `beforeSequence` 游标在数据库侧倒序截取最近页，limit 为 1–512，返回值恢复为升序；`HarnessContextContributor` 固定只读取最近 16 条。预算扫描使用 `(createdAt, runId)` 排他游标，limit 同样为 1–512。交互和预算当前随 Goal 级联删除，自动 TTL 要等宿主明确合规保留窗口后再引入。通过
`PostgresAgentPersistence.harness` 装配，不加入默认 Runtime `layer`，以免未使用 Harness 的宿主被迫构造 Adapter。
核心 Flyway 只执行 0.9 V001；Harness、Artifact 与预算的现行表、列和约束均由该基线一次建立。

完整表说明、唯一 migration 事实源与 pgvector 接入见 [database-schema.md](database-schema.md)。
