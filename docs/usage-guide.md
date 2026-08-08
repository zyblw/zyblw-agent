# zyblw-agent 总体使用手册

> 状态：0.5.0 当前使用契约
>
> 最后核验：2026-08-08
>
> 事实来源：公开源码、可运行示例、独立 Maven consumer、数据库 migration 与测试

本手册面向准备在业务项目中使用 `zyblw-agent` 的 Scala/ZIO 开发者。它给出从依赖选择、Agent 定义、ZLayer
装配、PostgreSQL、HTTP 到 PDF RAG 的完整主线；每个专题的细节仍由文末链接的专门文档维护。

## 1. 先选择正确的执行方式

不要把所有业务都写成 Agent：

| 需求 | 使用方式 | 事实与生命周期 |
|---|---|---|
| 可确定编排、规则、数据库查询 | 普通 ZIO Service | 业务代码和业务数据库 |
| 开放式分析、动态选择工具 | `AgentApplication` | Run/Event/Command/Tool ledger |
| 显式分支、等待、并行汇合、崩溃恢复 | `WorkflowEngine` | Checkpoint/Execution/Wait/Signal |
| 长任务的 Goal/Plan/Workspace | Agent + Harness 能力 | 当前仍在演进，不应假装已通用生产化 |

Agent 的核心边界始终是：模型提出文本、结构化结果或工具调用；Runtime 校验能力、权限、预算和状态，执行工具并决定何时停止。

## 2. 环境与依赖

框架 0.5.0 的开发基线是 JDK 21、Scala 3.8.4、sbt 2.0.1、ZIO 2.1.26。业务只引入实际需要的模块：

```scala
val zyblwAgentVersion = "0.5.0"

libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"      % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-providers" % zyblwAgentVersion
)
```

常见追加依赖：

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-postgres"         % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-zio-http"         % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-rag"              % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-document-loaders" % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-rerank"           % zyblwAgentVersion
)
```

所有模块必须使用同一精确版本。`%%` 会选择 Scala 3 的 `_3` 制品，不要手写后缀、版本范围或
`latest.release`。完整选择矩阵见[模块与发布坐标](modules.md)。

## 3. 从可运行的最小路径开始

在框架仓库中运行：

```bash
sbt "examples/runMain com.zyblw.agent.examples.QuickstartAgentExample"
```

它使用确定性 `ScriptedChatModel` 和内存控制面，但仍经过：

```text
AgentDefinition
  -> submit(Start command)
  -> Worker claim
  -> AgentRuntime
  -> Model response
  -> AgentState/Event commit
  -> inspect
```

因此它适合验证依赖、主循环和取消语义；进程退出后数据丢失，不能用于生产。

## 4. 定义 Agent 与工具

业务首先定义全局工具政策，再构造 Agent 的可见能力：

```scala
val toolPolicy = ToolPolicyConfig(
  allowedTools = Set(ToolName("knowledge_search")),
  maxCallsPerRun = 12,
  maxCallsPerStep = 3
)

val definition =
  AgentDefinitionBuilder(AgentId("knowledge-assistant"), "知识助手")
    .withInstructions("只根据已授权资料回答；证据不足时明确说明。")
    .allowTool(ToolName("knowledge_search"))
    .buildFor(toolPolicy)
```

`buildFor` 会在启动阶段拒绝缺失指令、重复/越权工具、敏感 metadata 和非法模型参数。空白名单表示不暴露任何工具。
工具实现通过 `RegisteredTool.make` 捕获显式 ZIO 环境，模型不能直接获得数据库连接、Secret 或业务身份。

业务调用统一经过 `AgentApplication`：

```scala
val program = for
  agent   <- definition
  app     <- ZIO.service[AgentApplication]
  receipt <- app.submit(
    agent,
    RunRequest(
      ThreadId("thread-1"),
      AgentMessage.user("请总结资料"),
      RunContext(Some("user-1"), Some("tenant-1"), Set("knowledge:read"))
    ),
    idempotencyKey = "client-generated-request-id"
  )
yield receipt
```

`submit` 返回耐久命令回执，不代表模型已经完成。常驻 Worker 通过 `runWorker` 消费命令；测试可以显式调用
`claimOnce`，然后使用 `inspect(runId)` 读取状态。

## 5. ZLayer 装配原则

ZLayer 是依赖和资源构造图，不是隐藏的 Service Locator。业务 composition root 应遵守：

1. `DataSource`、Provider Client、HTTP Server 和 exporter 只创建一次，并由外层 Scope 管理。
2. `RunStore`、`RunCommandStore`、`RunSubmissionStore` 必须指向同一耐久事实源。
3. 生产 `ContextSourceResolver`、`GuardrailEngine`、`RunObserver` 必须显式提供。
4. 不在数据库失败时回退到内存 Store；这会在多副本中制造分叉状态。
5. 后台 Worker 使用 scoped Fiber 或 `AgentHttpHost` 统一托管，不使用 `forkDaemon`。

教程入口：

```scala
AgentApplication.inMemoryDefaults(WorkerId("local-worker"), applicationConfig)
```

生产入口：

```scala
AgentApplication.durable(WorkerId("unique-worker-id"), applicationConfig)
```

使用模型辅助 Context 压缩时必须改用 `durableWithContextCompressor` 并提供 `ContextCompressor`；策略与装配不一致会在
付费模型调用前失败。完整 composition root 见 [AgentApplication 与 Builder](application-builder.md)。

## 6. PostgreSQL：显式迁移或启动迁移

框架接受宿主共享的 `javax.sql.DataSource`，不隐藏第二个连接池。数据库有两种接入模式。

由部署任务或 DBA 管理 DDL：

```scala
for
  _ <- AgentPostgresMigrations.migrate(dataSource)
  _ <- AgentPostgresMigrations.migrateKnowledge1536(dataSource) // 使用 RAG 时
yield ()
```

应用账号只保留 DML 权限，然后使用：

```scala
PostgresAgentPersistence.layer
PostgresAgentPersistence.knowledge(1536)
```

由应用启动负责 DDL：

```scala
PostgresAgentPersistence.migratedLayer
PostgresAgentPersistence.migratedKnowledge1536()
```

`migrated*` 在 ZLayer 构建时执行 Flyway migrate/validate 和结构探针；缺表、版本不匹配、pgvector 低于 0.8.0 或
`vector(1536)` 不一致都会阻止应用启动。核心控制面位于宿主默认 schema；知识表及其独立 history 固定在
`zyblw_agent_knowledge`，vector 类型来自 `public`。不能把两个 V001 放入同一 Flyway 实例或让两套 history 管理同一 schema。
正式生产通常推荐独立 migration Job 和最小权限运行账号，详细说明见[数据库迁移](database-migrations.md)。

## 7. HTTP 服务

`zyblw-agent-zio-http` 提供 `/api/v1` Endpoint、OpenAPI、异步命令 API、耐久 SSE 与健康检查。身份必须由业务从已验签
JWT/session/mTLS 转换成 `RunContext`，不能采信请求正文里的 tenant、user 或 scope。

独立 Agent 服务使用 `AgentHttpHost.fromApplication`，它把 Server 和 command worker 放入同一子 Scope；嵌入已有业务
服务时只合并 `AgentHttpApi.routes`，不要再启动第二个 Server 或重复 Worker。

主要调用顺序：

```text
POST /api/v1/agents/{agentId}/runs + Idempotency-Key
  -> 202 { runId, commandId }
GET  /api/v1/commands/{commandId}
GET  /api/v1/runs/{runId}
GET  /api/v1/runs/{runId}/events/stream + Last-Event-ID
GET  /api/v1/runs/{runId}/inspection
```

SSE 读取耐久 Event sequence，可以跨节点续传；模型 token delta 仍是进程内实时信号，不能替代耐久事件。OpenAPI 位于
`/api/v1/openapi.json`。完整配置和 Kubernetes 探针见 [ZIO HTTP 生产宿主](http-host.md)。

## 8. PDF/Markdown RAG

生产知识摄取使用 `RagApplication` 作为 Controller、Job 和 Tool 的统一门面：

```text
Directory/Object Storage
  -> DocumentInput(ZStream[Byte])
  -> Tika 或 DoclingDocumentLoader
  -> SourceDocument(Markdown + DocumentStructure)
  -> DocumentStructureChunker
  -> Governed Embedding
  -> KnowledgeIndexer(Building -> stage -> activate)
  -> PostgreSQL FTS + pgvector
  -> ACL-first hybrid retrieval
  -> rerank
  -> parent/neighbor expansion
  -> Citation(page/bbox/source)
```

目录导入使用 `LocalDocumentDirectorySource`；复杂 PDF 推荐 Docling Markdown+JSON，结构切分会保留
heading/parent/previous/next/page/bbox/block lineage。原始 PDF 放对象存储，知识表保存稳定 URI、内容 hash、chunk 原文、向量和
可追溯谱系，不在每一行复制整份文件。

摄取和查询：

```scala
val indexed = rag.ingestOne(
  DocumentIngestionRequest(
    input,
    TenantId("tenant-a"),
    Set("knowledge:read"),
    ingestionId = "stable-upload-id"
  )
)

val result = rag.retrieve(
  RagQuery(
    "问题文本",
    RetrievalScope(TenantId("tenant-a"), Set("knowledge:read")),
    limit = Some(5)
  )
)
```

模型可以决定检索什么，但 tenant/permissions 必须由运行时注入。向量、FTS、rerank 和相邻块扩展都不能扩大已经授权的
候选集合。完整路径见 [PDF RAG 生产流水线](pdf-rag-pipeline.md)。

## 9. 代码架构与扩展位置

```text
modules/
  agent-core/              Provider-neutral ADT、Runtime、Tool、Context、Memory、Workflow SPI
  agent-providers/         模型 HTTP 协议 Adapter
  agent-rag/               Loader/Chunker/Embedding/Retriever 契约与门面
  agent-document-loaders/  Tika、Docling、目录 Source
  agent-rerank/            可选远程重排
  agent-postgres/          JDBC Store、Flyway、pgvector
  agent-zio-http/          HTTP contract、routes、host
  agent-mcp/               MCP client、Workspace/Sandbox
  agent-opentelemetry/     OTLP/Langfuse exporter
  agent-evals/             质量评测与发布门禁
  agent-testkit/           Fake/Stub 和 Runtime 契约
  agent-examples/          可执行接入示例，不发布
```

新增业务能力时优先实现 core 中已有 SPI 的业务 Adapter；只有出现新的依赖、协议、生命周期、安全或许可证边界时才新增
artifact。Provider 类型不能进入 core，数据库 DTO 不能成为 HTTP wire schema，模型输出不能绕过 Runtime 直接执行副作用。
更完整的依赖方向与状态时序见[架构总览](architecture.md)和[源码阅读路线](source-tour.md)。

## 10. 错误、取消与安全

- 保留 typed error 与 `retryable` 语义；不要把所有失败统一 `orDie` 或无限重试。
- ZIO interruption 必须传播到 Provider Body、工具 Fiber、文档流和 heartbeat，finalizer 负责回收资源。
- 外部 PDF、网页、MCP 描述、Memory 和工具结果全部是不可信数据，不能提升为 System/Developer 指令。
- 写工具需要稳定业务幂等键、审批以及 outbox/inbox 或等价事务边界。
- 日志、Trace、Inspector 和 Eval 不保存 API Key、Prompt、工具参数/结果、完整 Provider 响应或隐藏推理。

## 11. 业务发布前验证

框架仓库的候选门禁：

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
sbt -batch 'set ThisBuild / version := "0.5.0-local.1"; publishM2'

cd integration-tests/maven-consumer
ZYBLW_AGENT_VERSION=0.5.0-local.1 sbt -batch 'clean; compile'
```

业务还必须补充自己的权限、质量、成本、容量、数据库重启、Worker kill、Provider 断流、备份恢复和数据删除验证。框架测试
通过不等于某个业务已经生产就绪，具体清单见[生产接入基线](production-readiness.md)。

## 12. 下一步阅读

1. [快速开始](getting-started.md)：逐步接入工具、Provider、PostgreSQL、HTTP 和 RAG。
2. [AgentApplication 与 Builder](application-builder.md)：完整 ZLayer composition root。
3. [核心概念](core-concepts.md)与[运行时](runtime.md)：理解状态机和执行循环。
4. [数据库 Schema](database-schema.md)与[数据库迁移](database-migrations.md)：理解耐久事实和部署权限。
5. [源码阅读路线](source-tour.md)：从示例、门面、Runtime 读到 Adapter 和测试。
6. [成熟度与路线](maturity-and-roadmap.md)：区分已实现、Beta、Experimental 与 Planned。
