# PostgreSQL 数据模型与接入指南

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-30
>
> 事实来源：对应模块源码、测试与构建定义

## 设计边界

框架表只保存 Agent 基础设施事实，不保存网站的文章、用户画像、中医问诊或其他业务主数据。业务系统通过
`userId`、`tenantId`、`threadId` 和工具参数引用自己的表，避免框架 migration 反向控制业务数据库。

`agent_runs.state_json` 是可恢复的完整状态快照，`version` 是乐观锁；`agent_events` 是精选审计事件，不把系统
设计成全量 Event Sourcing。`PostgresRunStore.commit` 在一个短事务内完成状态 CAS 和事件追加，事务内绝不调用
模型、工具或外部网络。

## 框架必需表

| 表 | 事实与用途 | 主要保留策略 |
|---|---|---|
| `agent_runs` | Run 当前状态、版本、取消位；异步创建的作用域哈希、客户端幂等键和请求指纹 | 按租户业务合规要求归档/删除 |
| `agent_events` | 可审计的状态转换事件 | 可冷归档；不能早于 Run 排障窗口删除 |
| `tool_executions` | Prepared/Running/Unknown/Succeeded/Failed 副作用账本 | 至少覆盖副作用追溯与幂等窗口 |
| `approval_requests` | 人工审批请求和决定 | 涉及敏感操作时按审计政策保留 |
| `agent_messages` / `agent_steps` | 可选规范化查询投影 | 当前状态以 `state_json` 为事实来源 |
| `model_calls` / `usage_records` | Provider 调用与成本投影 | 用于成本、SLO 与异常排查 |
| `agent_run_commands` | Start/Recover/ResumeApproval/Cancel/Retry 正文、幂等键、优先级、尝试与死信审计 | 随 Run 级联；DeadLetter 需先完成排障 |
| `agent_run_dispatch` | 每 Run 一个串行租约槽、currentCommand、owner/token/generation | 随 Run 级联；Idle 行可长期保留 |
| `agent_business_operations` | producer 业务幂等键、请求指纹与可重放结果 | 至少覆盖客户端/Agent 最大重试窗口；按业务合规归档 |
| `agent_outbox_events` | 与业务状态同事务提交、事务外至少一次发送的稳定事件 | 不随 Run 级联；Published 归档前保留排障窗口 |
| `agent_inbox_messages` | consumer/messageId 去重与可重放消费结果 | 覆盖上游最长重试和灾备恢复窗口 |
| `agent_compensations` | 显式注册、激活、租约执行与死信的 Saga 补偿计划 | Succeeded/Cancelled 可归档；DeadLetter 必须先处理 |
| `agent_memories` | Session/User/Tenant 长期记忆、证据、敏感级别、CAS 版本与删除 tombstone | active 按过期策略注入；deleted 不保留 value/search 正文 |
| `agent_memory_audit` | 用户查看/搜索/纠正/删除的低敏不可变事实；只保存 key hash、版本和数量 | 不含正文/query/scopes；按合规审计窗口归档 |
| `agent_embedding_cache` | 不含正文的租户/模型/维度/版本/hash 精确向量缓存 | expires_at 有界清理；读取不写 last-access |
| `agent_embedding_quota_windows` | 租户、窗口长度和窗口起点范围内的请求/文本/字符硬计数 | 行锁保证跨 Worker 原子检查与累加 |
| `agent_embedding_quota_reservations` | requestId/hash 幂等预留 | 随所属窗口级联清理；同 ID 不同 hash 拒绝 |
| `agent_eval_snapshots` | Agent/RAG/Context Compression 的低敏不可变评测快照与发布基线 | 不含业务正文；按数据集治理策略归档，不能静默覆盖 |
| `agent_workflow_checkpoints` | Workflow identity、Session、游标、应用状态、step 与访问预算的完整恢复快照 | 独立于 `agent_runs`；按 Workflow Run 的保留策略删除 |
| `agent_workflow_node_executions` | 节点 Running/Prepared/Committed 台账、pending outcome 与 owner/token/generation fencing | 与 Workflow checkpoint 同保留窗口；Prepared 需覆盖最长故障恢复期 |

可选 RAG baseline 还包含：

| 表 | 事实与用途 | 可见性规则 |
|---|---|---|
| `agent_knowledge_documents` | ingestion 幂等键、内容 hash、Embedding/切分策略、版本状态和 active manifest | 每个 tenant/document 至多一个 `ready + active` |
| `agent_knowledge_chunk_staging` | Building 版本的可重放暂存向量 | Retriever 永远不查询该表 |
| `agent_knowledge_chunks` | 当前正式发布的完整文档块快照、FTS 与 pgvector 向量 | 只在 activate 短事务中按文档整体替换 |

知识 manifest 状态为 Building/Ready/Superseded/Failed/Retired。`retire` 在文档 advisory lock 和 active-version 乐观条件下
原子删除正式块并写 Retired；`purgeInactive` 通过部分索引和 `SKIP LOCKED` 只清理截止时间前的非活动终态，绝不删除
Building 或 Ready/active。

通用 `agent_checkpoints` 不存在于全新基线。Agent Runtime 直接保存 `AgentState`。生产异步创建通过
`PostgresRunSubmissionStore` 在同一事务写 `agent_runs + agent_events + agent_run_commands + agent_run_dispatch`；任一插入
失败全部回滚。`start_scope_hash/start_request_hash` 是 SHA-256，只有 `start_idempotency_key` 保存客户端不透明键。

V008 的 `agent_workflow_checkpoints` 是另一条明确边界：它只服务声明式 Workflow，不复制 Agent Runtime 状态。每个 runId
一行，完整 checkpoint 同时保存确定性 TEXT、JSONB 与 SHA-256；冗余 identity/cursor/step 列用于约束和诊断。表不引用
`agent_runs`，因为无模型的确定性 Workflow 也可以独立运行。

V009 的 `agent_workflow_node_executions` 以 `(run_id, step, node_id)` 建立执行槽。Running/Prepared 必须拥有有效期字段，
Committed 必须清除有效期并记录完成时间；Prepared/Committed 必须同时拥有 outcome TEXT、JSONB 与 SHA-256。claim 只会
覆盖已过期 Running/Prepared，并递增 generation、换发随机 token；`PostgresWorkflowCheckpointStore.commit` 锁定 ledger
行后，在同一事务推进 V008 checkpoint 与全部 execution 终态。该表同样不引用 `agent_runs`。

`agent_memories` 不属于 Run checkpoint：Run 删除不会级联删除用户长期记忆。User scope 同时保存 tenant/user，并以
无歧义 canonical scope key 建主键；相同 userId 在不同 tenant 中是不同命名空间。删除会清空 `value_json` 与
`search_text`、写 `deleted_at` 并递增 version，防止迟到提炼任务以旧 CAS 版本复活内容。FTS 使用 `simple`，同时保留
substring 分支以支持未分词中文短语。

业务用户纠正和删除必须经过 `MemoryGovernanceService` 与 `MemoryGovernanceRepository`。PostgreSQL Adapter 在同一短事务
完成 Memory CAS/tombstone 与 `agent_memory_audit` INSERT；审计约束、连接中断或取消都会回滚数据变更。审计表不保存
value、query、原始 key、认证 scopes/attributes。Retention 使用 `MemoryRetentionWorker` 控制批次/退避/Scope 生命周期，
数据库 `SKIP LOCKED` 提供跨 Worker 行级领取。完整说明见 [Memory 治理指南](memory-governance.md)。

Embedding cache 使用 `REAL[]` 而不是 pgvector，因为它只按完整主键等值命中，并允许不同模型维度共表。数据库 CHECK
保证数组长度等于 key 中的 dimension；正文不写入该表。quota window 的主键包含 `window_millis`，防止不同配额策略
共享桶。reservation 与计数在同一短事务提交，删除已结束窗口时由外键级联释放 requestId。

`agent_eval_snapshots` 的主键是稳定 `evaluation_id`，查询身份由
`suite_kind + suite_id + dataset_id + dataset_version` 共同组成。发布门禁通过部分索引读取最近 `passed=true` 快照；
历史查询先按 `finished_epoch_second + finished_nano + evaluation_id` 降序取最近 N 行，再升序返回，不使用深 OFFSET。

快照同时保存 `snapshot_payload TEXT` 和 `snapshot_json JSONB`：前者保留确定性 UTF-8 字节供 SHA-256 校验，后者用于
SQL 分析；数据库 CHECK 保证二者解析后的 JSONB 相等。不能使用 `snapshot_json::text` 复算应用 checksum，因为 JSONB
会规范化文本表示。Adapter 读取时还会验证领域结构、完整身份、passed/passRate 和精确排序字段，任何不一致都
fail-closed。

## 推荐宿主连接方式

- 框架接受 `javax.sql.DataSource`，连接池由宿主统一创建；不要让每个 Adapter 各建一个池。
- 连接池总量必须小于 PostgreSQL `max_connections` 并为 migration、管理和故障恢复留余量。
- JDBC 是阻塞 API，Adapter 使用 `ZIO.attemptBlocking`；ZIO Fiber 不等于把 JDBC 变成非阻塞驱动。
- 事务只包围数据库读写。模型推理、HTTP、embedding 和工具执行必须在事务之外。
- 乐观锁冲突由调用方重新加载状态再决定是否重试；不能无条件覆盖新版本。
- SQLSTATE `40001`、`40P01`、`08xxx` 和 `57014` 被标为可重试，约束错误不会自动重试。

Embedding 治理 Store 可统一装配：

```scala
val embeddingStores: URLayer[DataSource, EmbeddingCacheStore & EmbeddingQuotaStore] =
  PostgresAgentPersistence.embeddingGovernance
```

缓存与配额清理均使用有界 `SKIP LOCKED` 批次；不要用无上限 `DELETE` 在高峰期长时间持锁。完整调用方式见
[Embedding 缓存、租户配额与生产调用边界](embedding-governance.md)。

评测发布事实源可独立装配：

```scala
val evalTrendStore: URLayer[DataSource, EvalTrendStore] =
  PostgresAgentPersistence.evalTrends
```

它复用同一连接池，但不会在 Run 状态事务中执行离线评测；两者生命周期不同，不应制造跨表长事务。

## 工具账本恢复规则

`tool_executions` 使用 `(run_id, call_id)` 作为调用唯一键，并另外保存 `batch_id`、`ordinal`、`attempt`。
Runtime 在启动任何批内 Fiber 前，用一个短事务和 `INSERT ... ON CONFLICT DO NOTHING` 准备整批记录；事务提交前会
核对既有记录的 batch/ordinal/tool/idempotency identity，防止重复 callId 错误复用。状态推进使用
`status + attempt` compare-and-set，同时校验不可变身份字段，迟到 Fiber 不能覆盖新尝试。

| 账本状态 | 恢复动作 |
|---|---|
| `Prepared` | 外部调用尚未开始，可执行 |
| `Running` + 可重试工具 | 允许以相同 callId/idempotency key 重试 |
| `Running` + 非幂等工具 | 先转 `Unknown`，暂停并人工核对外部系统 |
| `Unknown` + 非幂等工具 | 禁止自动重放；人工确认后才能继续 |
| `Succeeded` | 复用持久化结果，不再次执行 |
| `Failed` | 依据 typed error、工具副作用和重试策略决定，不假设安全 |

数据库事务无法与第三方 HTTP 副作用形成天然 exactly-once。真正可靠的写工具仍需业务端幂等键、唯一约束、
outbox/inbox 或补偿流程。

## 真实写工具事务边界

`PostgresTransactionalWriteExecutor` 对 `(scope_key, operation_name, idempotency_key)` 使用唯一约束和 `FOR UPDATE`
仲裁并发请求。首次请求在一条连接、一次 transaction 中依次完成：

1. 创建 `agent_business_operations` Executing 占位；
2. 使用同一 JDBC `Connection` 修改宿主业务表；
3. 写入零到多条 `agent_outbox_events`；
4. 可选写入一条 `agent_compensations` Registered 计划；
5. 保存受限的结果 JSON，并推进业务操作为 Succeeded。

任意一步失败都会回滚全部动作。Executing 不会作为半成品独立提交；并发重复请求等待首次事务提交后复用同一结果。
同一键对应不同 request hash 会失败，不覆盖原事实。

outbox 发布与 PostgreSQL commit 不能跨系统原子确认，因此传输语义是 at-least-once。远端确认后、本地 Published 前崩溃会
用同一 `event_id` 重发。下游若使用 `PostgresTransactionalInbox`，则 `(consumer_name, message_id)`、consumer 业务
mutation 和结果在同一个 transaction 中提交；只在事务前查一次 inbox 并不能获得该保证。

补偿记录最初为 Registered，worker 只领取 Pending。必须由明确失败策略或人工操作调用 `activate`；目标已经成功时调用
`cancel`。补偿 handler 仍需幂等，因为第三方补偿同样只能承诺 at-least-once。

`agent_business_operations`、`agent_outbox_events` 和 `agent_compensations` 的 `run_id` 是审计关联而非外键，避免删除一次
Agent Run 时级联删除仍需投递或审计的业务事实。宿主必须为两类数据分别制定清理策略。

## pgvector

可选 migration 位于：

`modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/optional/pgvector/V001__agent_knowledge_pgvector_1536.sql`

它默认使用 `vector(1536)`。维度是表和索引契约，必须与 Embedding Provider 一致，不能在同一列混用不同维度。
`PostgresPgVectorStore` 的查询先执行 tenant 与权限包含关系，再进行 cosine/全文排序。`searchHybrid` 使用
`websearch_to_tsquery + ts_rank_cd` 和 pgvector 两个候选集，通过 weighted RRF 合并；原始分数和名次保存在
`RetrievalHit.signals`。

```scala
val vectorStoreLayer: ZLayer[DataSource, Nothing, VectorStore] =
  PostgresPgVectorStore.layer(dimension = 1536)

val indexStoreLayer: ZLayer[DataSource, Nothing, KnowledgeIndexStore] =
  PostgresKnowledgeIndexStore.layer(dimension = 1536)
```

索引写入不是逐行覆盖正式表。`begin` 分配 Building 版本，`stage` 分批幂等 upsert，`activate` 在文档 advisory
transaction lock 下校验块数并原子切换 active 快照。Embedding HTTP 不进入数据库事务。失败版本保留稳定
`failure_code`，相同 ingestionId 重试前会清空残留 staging；已 superseded 的旧 ingestion 不允许重新激活。

全文基线固定使用 `simple` regconfig。中文可把可信分词结果写入 `search_text`；使用 pg_jieba 时必须同时修改
migration 生成列与运行时 `textSearchConfig`。

HNSW 的参数不是通用最优值；应使用自己的中医文档、引用正确率和延迟评测集调优。pgvector 的索引、过滤和
迭代扫描能力以 [pgvector 官方文档](https://github.com/pgvector/pgvector) 为准。

## 执行 SQL

- 正式环境优先让 Flyway 执行 classpath 下的默认 migration。
- 手工初始化可执行 [zyblw-agent-postgresql.sql](sql/zyblw-agent-postgresql.sql)。
- 需要 RAG 时，再确认 extension 权限和 embedding 维度后执行 [zyblw-agent-pgvector-1536.sql](sql/zyblw-agent-pgvector-1536.sql)。
- 不要把数据库密码写入 SQL、README 或 Git；通过部署平台 Secret 注入 DataSource 配置。

PostgreSQL 的事务、行锁和 `SKIP LOCKED` 等语义应以
[PostgreSQL 官方 SELECT 文档](https://www.postgresql.org/docs/current/sql-select.html) 为准。

## 耐久 command dispatcher 协议

`agent_run_commands` 保存不可变命令事实，`agent_run_dispatch` 保存“当前谁有权推进这个 Run”，`agent_runs` 保存
Agent 业务状态。一个 Run 可以拥有多条命令，但只有一个 dispatcher，因此不同 Run 可并行、同 Run 命令严格串行。
claim 在短事务中按 `priority DESC, available_at ASC, created_at ASC, command_id ASC` 选择候选，并用
`FOR UPDATE OF d, c SKIP LOCKED` 同时锁定 dispatcher 和命令。

命令状态为 `Queued/Leased/Completed/DeadLetter/Superseded`。只有 typed error 标记 `retryable=true` 才自动 abandon；
永久错误直接 DeadLetter。人工 retry 会重置本轮 `attempt`，同时单调递增 `manual_retry_count`，不会删除故障历史。

每次 claim 生成随机 `lease_token` 并递增 dispatcher `generation`。heartbeat、complete、abandon、deadLetter 和
AgentState 提交必须匹配 `run_id + current_command_id + owner + token + generation`，并确认未过期。Cancel 入队会在同一
事务撤销活动租约并重新排队被抢占命令；Cancel 完成后其余 Queued 命令进入 Superseded。

AgentState 提交也使用相同 fencing。`PostgresRunStore.commitFenced` 在状态/事件短事务入口执行：

```sql
SELECT 1
FROM agent_run_dispatch
WHERE run_id = :run_id
  AND status = 'Leased'
  AND current_command_id = :command_id
  AND lease_owner = :owner
  AND lease_token = :token
  AND generation = :generation
  AND lease_expires_at > CURRENT_TIMESTAMP
FOR SHARE;
```

该行锁一直持有到 AgentState 版本 CAS 与事件追加共同提交。claim、heartbeat、complete、abandon 和 Cancel 抢占都需要更新同一行，
因此无法在“检查通过”和“状态写入”之间抢占 generation。事务内没有模型、工具或外部 HTTP，只会短暂阻塞一次心跳。

推荐初始参数：30 秒租约、10 秒 heartbeat、500 毫秒空队列轮询，并根据生产 p99 模型/工具延迟和数据库抖动
调整。heartbeat 间隔必须显著小于租约；不要用无限租约，也不要在 claim 事务中调用模型或工具。

租约提供 at-least-once 调度和旧 worker fencing，不自动创造 exactly-once 外部副作用。完整可靠写接入和故障窗口见
[可靠写工具、Outbox、Inbox 与补偿指南](side-effects.md)。
