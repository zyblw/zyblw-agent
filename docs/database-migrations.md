# PostgreSQL 迁移发布契约

> 状态：当前
> 最后核验：2026-07-30
> 事实来源：`AgentPostgresMigrations.scala`、migration resources、PostgreSQL 集成测试

## 默认模型：框架与业务各自拥有迁移历史

发布 JAR 中的默认资源位置是：

```text
classpath:com/zyblw/agent/persistence/postgres/migration
```

框架不会因 JAR 出现在 classpath 就自动迁移。宿主在启动顺序中显式调用：

```scala
val migrated =
  AgentPostgresMigrations.migrate(dataSource)
```

默认 history table 是 `flyway_zyblw_agent_schema_history`，业务应用继续使用自己的 `flyway_schema_history`。这样框架
可以拥有 V001、V002，业务也可以拥有 V001、V002，不发生版本碰撞。

当前默认 location 依次包含 V001、V007、V008 与 V009；V008/V009 分别建立 Workflow checkpoint 和 node execution
ledger。已发布 migration 的文件名、内容与 checksum 永久冻结。

需要 pgvector 时必须显式评估维度和扩展权限，再增加
`AgentPostgresMigrations.OptionalPgVectorLocation`。它不属于默认迁移。

## 启动顺序

```text
创建宿主 DataSource
  -> SELECT 1 / readiness probe
  -> AgentPostgresMigrations.migrate
  -> 业务 Flyway migrate
  -> 构造 PostgresAgentPersistence
  -> 启动 Worker 与 HTTP
```

同一个连接池可以共享，但模型、Embedding、外部工具和 HTTP 不能在迁移或数据库事务中执行。

## 已有统一 Flyway history 的应用

`zyblw-server` 在早期已经把 Agent V001/V007 与业务 V002–V008 放进同一个
`flyway_schema_history`。它不能直接切换到默认专属 history，否则 Flyway 会把框架 baseline 当作未执行并尝试重建表。

只有两个 location 的版本集合完全不冲突时，兼容模式才可以显式配置两个 location 并继续使用原 history：

```scala
.locations(
  AgentPostgresMigrations.DefaultLocation,
  "classpath:db/migration"
)
```

框架从 V008 起可能与宿主已有业务 V008 发生版本碰撞，因此不能把上述片段视为 0.2.x 的通用迁移办法。发现同版本不同
description/checksum 时必须停止启动；先用专门的 adoption migration/运维方案核对框架表、约束、索引和已执行 checksum，
再迁入专属 history 并执行尚未应用的框架 migration。禁止仅使用 `baselineOnMigrate` 隐藏未知 schema，也禁止删除或改写
原 history 来“让 Flyway 通过”。

## 发布规则

- 发布后 migration 永久不可修改；
- 只增加新版本，不能重排或复用版本；
- DDL 与代码必须支持滚动升级的 expand/migrate/contract；
- 大回填、长锁和 `VALIDATE CONSTRAINT` 使用独立运维任务；
- 每个版本至少有空库执行测试和代表性升级测试；
- optional migration 必须有独立 location/history 计划；
- 框架表不保存宿主业务主数据，业务外键默认不反向进入框架 migration。
