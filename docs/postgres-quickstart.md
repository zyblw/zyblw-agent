# PostgreSQL 独立宿主快速接入

> 状态：0.6.2 可执行接入路径
> 最后核验：2026-08-14
> 事实来源：`PostgresQuickstartExample.scala`、`AgentPostgresMigrations.scala`、`PostgresAgentPersistence.scala`

本页面面向任何独立 Scala/ZIO 宿主，不假设私有产品仓库、业务 schema 或领域类型。目标是用一个宿主拥有的
`DataSource` 完成以下最小生产形态：

```text
host DataSource
  -> Flyway migrate / validate / structure probe
  -> PostgreSQL durable stores
  -> AgentApplication.durable
  -> typed read-only tool
  -> scoped Worker shutdown
```

完整可编译代码在
[`PostgresQuickstartExample.scala`](../modules/agent-examples/src/main/scala/com/zyblw/agent/examples/PostgresQuickstartExample.scala)。
它使用固定模型响应和固定只读 SQL，因此不需要 Provider API Key，也不会把模型输入拼接进 SQL。

## 1. 依赖与数据库

宿主只需选择现有 artifact，不需要新模块：

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"     % "0.6.2",
  "io.github.zyblw" %% "zyblw-agent-postgres" % "0.6.2"
)
```

快速示例要求一个空 PostgreSQL database，以及能在启动阶段执行 DDL 的账号。迁移会创建框架自己的
`flyway_zyblw_agent_schema_history` 和 Agent 基础设施表。它不会创建宿主业务表，也不会要求宿主引入任何框架业务 schema。

已有业务表的 `public` schema 不能直接使用默认 fresh-install 策略。若确实需要共享非空 `public`，先确认其中没有旧
zyblw-agent 表，再显式使用 `AgentPostgresMigrationConfig.sharedPublicSchema`；该策略只允许 version 0 baseline，仍会拒绝
把未知框架表伪装成已迁移。生产更推荐由部署任务/DBA 执行 migration，并让常驻应用账号只拥有 DML 权限。

## 2. DataSource 与 migration

框架只接受宿主提供的 `javax.sql.DataSource`，不会暗中创建连接池。常驻服务应复用宿主已经配置监控、上限、TLS、Secret
和关闭 finalizer 的连接池：

```scala
val persistence =
  PostgresAgentPersistence.migratedLayer
```

`migratedLayer` 的输入是同一个 `DataSource`。构建 Layer 时会依次执行 core Flyway migrate、checksum validate、关键关系与
数据字典探针，然后才创建 `RunStore + RunCommandStore + RunSubmissionStore`。任何一步失败都会阻止 Worker 启动，不会回退到
内存。

若 DDL 已由独立部署阶段完成，则启动任务先执行：

```scala
AgentPostgresMigrations.migrate(dataSource)
```

常驻应用改用 `PostgresAgentPersistence.layer`。两种模式复用同一 migration 资源，宿主不要复制或改写已发布 V migration。

## 3. 类型化只读工具

示例的 `database_info` 工具具有类型化输入/输出、固定 JSON Schema 和明确的只读元数据：

```scala
ToolMetadata(ToolRisk.ReadOnly, SideEffect.None)
```

它只执行固定的 `SELECT current_database(), current_schema()`，并在 blocking 区域关闭 result、statement 和 connection。模型只能
决定是否返回 schema，不能提供 SQL、表名或权限。真实宿主工具也应保持这一模式：用窄输入表达业务意图，在工具实现中完成授权并
构造固定或参数化 SQL，不把自由文本当作 SQL。

## 4. durable application 与关闭

`AgentApplication.durable` 强制要求 PostgreSQL 三个 Store、模型、工具注册表、Context resolver、Guardrail 和 Observer
全部显式提供。示例没有知识或敏感业务上下文，因此明确接入空 resolver、空 guardrail 和 noop observer；生产宿主应替换为自己的
授权 Context、策略和低敏观测实现。

Worker 在应用 Scope 中启动：

```scala
ZIO.scoped {
  for
    app <- ZIO.service[AgentApplication]
    _   <- app.startWorkerScoped
    // submit and inspect durable runs
  yield ()
}
```

Scope 正常结束、失败或收到 SIGINT/SIGTERM 时，`forkScoped` Worker 都会被中断；heartbeat、模型调用和工具子 Fiber 继续遵循
结构化取消。宿主拥有的连接池也应通过 `ZLayer.scoped` 或等价 finalizer 绑定到更外层应用 Scope，使关闭顺序保持
Worker → JDBC pool。

ZIO Scope 的官方语义见 [Scope](https://zio.dev/reference/resource/scope.md)，Fiber 结构化生命周期见
[Fiber](https://zio.dev/reference/fiber/fiber.md)。

## 5. 运行仓库内示例

不要把密码写进命令历史、文档或源码；以下变量应由本地 Secret 注入：

```bash
export ZYBLW_AGENT_JDBC_URL='jdbc:postgresql://localhost:5432/agent_quickstart'
export ZYBLW_AGENT_DB_USER='agent_app'
export ZYBLW_AGENT_DB_PASSWORD='...'
sbt "examples/runMain com.zyblw.agent.examples.PostgresQuickstartExample"
```

成功时会完成一次真实 migration、耐久命令提交、Worker claim、只读工具调用和终态查询，然后退出 Scope。再次运行会验证已有
Flyway history；示例为每次 Run 生成新的幂等键，生产调用方必须改用客户端请求的稳定幂等键。

需要 RAG 时，再独立执行 `migrateKnowledge1024` 并装配 `knowledge(1024)`；不要把 core 与 knowledge 的两个 V001 放入同一
Flyway history。完整迁移约束见[数据库迁移](database-migrations.md)。
