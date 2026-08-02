# PostgreSQL 迁移与 0.3 基线

> 状态：0.3.0 发布契约
> 最后核验：2026-08-02
> 事实来源：`AgentPostgresMigrations.scala`、migration resource、PostgreSQL 16 集成测试

## 默认模型

框架 migration 位于：

```text
classpath:com/zyblw/agent/persistence/postgres/migration
```

当前 location 只有一个 `V001__zyblw_agent_0_3_baseline.sql`，完整描述最新框架 schema。框架不会因 JAR 出现在 classpath
自动迁移；宿主必须在受控启动阶段显式调用：

```scala
AgentPostgresMigrations.migrate(dataSource)
```

默认 history table 是 `flyway_zyblw_agent_schema_history`，与业务 `flyway_schema_history` 隔离。pgvector baseline 仍是
显式 opt-in 的独立 location，业务必须先确认扩展权限和固定向量维度。

## 0.3.0 只支持 fresh install

不支持把 `0.2.x` 的 V001/V007/V008/V009 history 原地升级为新的 V001。必须创建空 schema/新数据库；不得使用 Flyway
`repair`、删除 history、伪造 checksum 或 `baselineOnMigrate` 隐藏未知表。旧数据库是否删除属于宿主的数据治理决策，
框架不会自动清理。

启动顺序：

```text
创建 DataSource
  -> 数据库 readiness
  -> AgentPostgresMigrations.migrate（空 schema）
  -> 业务 migration
  -> 构造 PostgresAgentPersistence
  -> 启动 timer/command/agent Worker 与 HTTP
```

迁移和事务中禁止调用模型、Embedding、外部工具或 HTTP。

## baseline 冻结点

该 V001 从 `0.3.0` 发布提交起永久冻结；后续 `0.3.x` 只能追加更高版本，并执行
expand/migrate/contract、代表性升级测试和向前修复规则。任何 checksum 变化都属于发布阻断，不得用 `repair` 掩盖。
