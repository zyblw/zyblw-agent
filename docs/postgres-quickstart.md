# PostgreSQL 生产接入与运维

> 状态：0.9.0 空库安装指南
> 最后核验：2026-08-23
> 事实来源：`ProductionSupportHost`、`KnowledgeQaHost`、`AgentPostgresMigrations`、`PostgresAgentPersistence`

本页是独立宿主把 PostgreSQL 接成耐久控制面的权威路径。它不再提供“无密钥五分钟脚本”。客户支持参考是
[`ProductionSupportHost`](../modules/agent-examples/src/main/scala/com/zyblw/agent/examples/production/ProductionSupportHost.scala)；
书籍问答参考是
[`KnowledgeQaHost`](../modules/agent-examples/src/main/scala/com/zyblw/agent/examples/knowledge/KnowledgeQaHost.scala)。

```text
宿主 Hikari DataSource
  -> migrate：Flyway + 结构探针 + 业务表
  -> serve：PostgresAgentPersistence.layer（仅 DML）
  -> AgentApplication.durable
  -> 类型化只读查询 / 审批写工具
  -> ZIO HTTP + JDBC readiness
```

## 1. 依赖

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"      % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-postgres"  % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-providers" % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-zio-http"  % zyblwAgentVersion
)
```

需要一个空 PostgreSQL 18 库；当前发布门禁验证 18.6。框架创建 `flyway_zyblw_agent_schema_history` 和 Agent 基础设施表，不会替你设计业务表。参考宿主的 `support_orders` / `support_refunds` 只属于示例，不进入核心 Flyway history。

已有业务表的 `public` schema 不能直接使用默认 fresh-install 策略。若必须共享非空 `public`，先确认没有旧 zyblw-agent 表，再显式使用 `AgentPostgresMigrationConfig.sharedPublicSchema`。生产更推荐独立 schema/库，并由部署任务执行 migration。

## 2. 分离 migrate 与 serve

```bash
export ZYBLW_AGENT_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/zyblw_agent
export ZYBLW_AGENT_DB_USER=zyblw_migrate
export ZYBLW_AGENT_DB_PASSWORD=...
sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost migrate"

export ZYBLW_AGENT_DB_USER=zyblw_runtime
sbt "examples/runMain com.zyblw.agent.examples.production.ProductionSupportHost serve"
```

`migrate` 使用部署账号跑 `AgentPostgresMigrations.migrate` 和业务 DDL。`serve` 使用 `PostgresAgentPersistence.layer`，不会在运行账号上执行 Flyway。数据库密码只通过环境变量名引用，不写入 `AgentApplicationConfig`。

常驻进程必须使用受监控连接池。参考宿主使用 HikariCP，并设置 `statement_timeout` 与 `lock_timeout`。不要在生产使用 `PGSimpleDataSource`。

## 3. 核心组合

最小生产层是 `PostgresAgentPersistence.layer`：`RunStore`、`RunCommandStore`、`RunSubmissionStore` 共享同一个 `DataSource`。需要工件时显式叠加 `PostgresAgentPersistence.layerWithArtifacts`，不要假设最小层已经包含 Artifact、Harness 或知识索引。

`0.9.0` 只支持空库执行核心 V001 与 1024 知识 V001。书籍问答用
`KnowledgeQaHost migrate`，它调用 `migrateCoreAndKnowledge1024`；`status` 同时报告两套 Flyway。细节见
[升级到 0.9.0](fresh-install-0.9.0.md) 与 [宿主数据库迁移](database-migrations.md)。

## 4. Docker / VM

仓库第一支持面是 Docker 或 Linux VM，外加自管 PostgreSQL。见 [Docker/VM 手册](operations-docker-vm.md) 与 [`deploy/docker/`](../deploy/docker/README.md)。内置 PostgreSQL 只属于 `compose.staging.yml` 的本机演练；`compose.business.yml` 连接外部库，和生产同构。知识索引里的 `staging` 表是数据模型，不是第三套产品环境。
