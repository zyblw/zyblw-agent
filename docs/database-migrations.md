# PostgreSQL 自动迁移、结构校验与知识库基线

> 状态：0.9.0 单文件全新基线
> 最后核验：2026-09-17
> 事实来源：`AgentPostgresMigrations.scala`、migration resource、PostgreSQL 18 集成测试

## 默认模型

不依赖任何私有仓库的完整 `DataSource → migration → durable application → typed read-only tool → scoped
shutdown` 路径见 [PostgreSQL 独立宿主快速接入](postgres-quickstart.md)。

框架 migration 位于：

```text
classpath:com/zyblw/agent/persistence/postgres/migration
```

核心 location 只包含 `V001__zyblw_agent_0_9_baseline.sql` 与每次覆盖写入的
`R__zyblw_agent_schema_comments.sql`。知识 location 是 `optional/pgvector_1024/V001__agent_knowledge_0_9_baseline.sql`。
当前 0.9.0 只提供折叠后的全新基线，不提供历史升级路径。`AgentPostgresMigrations.resetAll`
会丢弃两个 schema 与两份 Flyway history。框架不会因为 JAR 或 `DataSource` 出现在 classpath 就修改数据库；宿主需要在受控启动阶段选择
下面一种模式。

升级预检（只读，不执行 DDL）：

```scala
AgentPostgresMigrations.inspect(dataSource)
AgentUpgradePreflight.inspect(dataSource)
```

`inspect` 返回当前 Flyway version 与 pending 列表。`AgentUpgradePreflight` 再附上非终态 Run、排队和已领取
命令计数。`ProductionSupportHost status` 是这条路径的可执行入口。空库或尚未建表时计数为 0，不要求 drain。

部署任务/DBA 迁移模式：

```scala
for
  _ <- AgentPostgresMigrations.migrate(dataSource)
  _ <- AgentPostgresMigrations.migrateKnowledge1024(dataSource) // 新库 RAG 主线
yield ()
```

应用启动自动迁移模式：

```scala
val core = PostgresAgentPersistence.migratedLayer
val rag  = PostgresAgentPersistence.migratedKnowledge1024()
```

`migrated*` ZLayer 在构建服务之前执行一次 Flyway migrate/validate 和结构后置探针；失败会阻止 Worker/HTTP 启动，不会回退内存。已经由
部署平台统一执行 DDL 的生产环境应继续使用普通 `layer/knowledge`，让运行账号只保留 DML 权限。

核心 history table 位于宿主当前 schema，名为 `flyway_zyblw_agent_schema_history`。新库的 1024 维知识库使用独立 location，固定管理
`zyblw_agent_knowledge` schema，并把独立 `flyway_zyblw_agent_knowledge_1024_history` 放在其中；实际 location 只有一份
`V001__agent_knowledge_0_9_baseline.sql`，已一次性包含 Space/Profile/census/chunks/audit/withdrawn、FTS、HNSW、parent/neighbor、
heading/page/bbox/block lineage；`R__agent_knowledge_1024_comments.sql` 幂等维护中文表/字段数据字典。核心与知识库都有 V001，绝不能放进同一个 Flyway history，也不能让两个 Flyway 实例共同管理
同一个非空 schema。通用 `migrate(config)` 会拒绝 1024 knowledge location，防止调用方绕过专属 schema；知识库必须使用
`migrateKnowledge1024` 或 `migrateCoreAndKnowledge1024`。

## 自动创建和检测的准确语义

当核心目标 schema 为空且数据库账号具备权限时，Flyway 会创建 history 并执行核心表。知识入口会创建/校验专属
`CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public` 仍取决于托管数据库/DBA 是否允许应用账号安装扩展；权限不足会明确失败。

迁移完成后框架还会检查：

- 核心 32 张权威表是否存在于当前 schema，避免临时表或其他 schema 的同名对象蒙混通过；
- 知识库七张 Space/Profile/census/chunk/audit/withdrawn 权威表是否确实位于 `zyblw_agent_knowledge`，且 `parent/ordinal/previous/next/heading/page/origin/block` 列完整；
- `vector` 扩展是否位于 `public` 且版本至少为 0.8.0；
- staging/active 两张表的 embedding 是否真实为 `vector(1024)`。
- 七张知识表与所有业务字段是否拥有非空、非泛化的中文数据字典说明。
- 核心 32 张权威表的全部字段是否同样拥有专属中文说明（由 `R__zyblw_agent_schema_comments.sql` 每次覆盖写入）。

Flyway checksum 负责发现已执行脚本被修改；结构探针负责发现 history 仍在但关键表/列被人工删除。这不是通用 schema diff，生产仍应禁止手工 DDL并监控
Flyway/数据库审计。

## 0.9.0 只支持 fresh install

不支持把 `0.8.x` 或更早 history 原地升级为当前 0.9 V001。必须创建空 schema/新数据库；不得使用 Flyway
`repair`、删除 history、伪造 checksum 或 `baselineOnMigrate` 隐藏未知表。旧数据库是否删除属于宿主的数据治理决策，
框架不会自动清理。

启动顺序：

```text
创建共享 DataSource
  -> 数据库 readiness
  -> core migrate/validate/verify
  -> knowledge-1024 schema migrate/validate/verify（新库 RAG 时）
  -> 业务 migration
  -> 构造 PostgresAgentPersistence
  -> 启动 timer/command/agent Worker 与 HTTP
```

迁移和事务中禁止调用模型、Embedding、外部工具或 HTTP。

## baseline 冻结点

自 `0.9.0` 起，核心与知识各一份 V001 永久冻结。旧候选 migration 已删除，机器门禁会阻止它们重新出现。
后续已发布线上的 DDL 必须使用新的追加 migration，不能再合并回 V001，也不得用 `repair` 掩盖 checksum 漂移。
