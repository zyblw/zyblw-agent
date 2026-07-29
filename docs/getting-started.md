# 快速开始

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-27
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 引入依赖与环境

宿主项目使用 JDK 21、Scala 3，并按能力选择制品：

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"      % "0.1.0",
  "io.github.zyblw" %% "zyblw-agent-providers" % "0.1.0"
)
```

需要 ZIO HTTP 控制面再加入 `zyblw-agent-zio-http`；需要 PostgreSQL 耐久化再加入
`zyblw-agent-postgres`。完整矩阵见 [模块选择](modules.md)。

`0.1.0` 已发布到 Maven Central。验证后续尚未发布的候选时，可以在框架目录执行
`sbt -batch 'set ThisBuild / version := "0.1.1-local.1"; publishM2'`，宿主临时使用同一唯一版本并显式启用 Maven
Local；不要覆盖旧本地版本，也不要把本地版本或 `SNAPSHOT` 当作可重复生产发布物。完整命令见
[server 消费指南](consuming-from-server.md)。

### 先在仓库内用五分钟确认主链路

不配置 API Key、不安装数据库也可以执行：

```bash
sbt "examples/runMain com.zyblw.agent.examples.QuickstartAgentExample"
```

预期输出包含：

```text
status=Completed, answer=你好，zyblw-agent 的最小运行链路已经完成。
```

这个示例使用确定性 `ScriptedChatModel` 与隔离的内存控制面，但没有另写一套“简化 Agent 循环”：它仍完整经过
`submit -> command claim -> AgentRuntime -> inspect`。因此它可以证明依赖和主接线正确，却不能证明真实 Provider、
PostgreSQL、跨节点恢复或生产负载已经通过。

## 2. 定义工具

```scala
val calculatorRegistry: Layer[
  AgentError.InvalidConfiguration,
  RegisteredToolRegistry
] =
  ZLayer
    .fromZIO(RegisteredTool.make(CalculatorTool.live))
    .flatMap { environment =>
      RegisteredToolRegistry.fromTools(
        List(environment.get[RegisteredTool])
      )
    }
```

`Tool[R, I, E, O]` 的 `R` 是工具所需服务；`RegisteredTool.make` 在 ZLayer 装配阶段捕获环境，运行时不会使用 `Any`
强制转换。注册表会在启动阶段拒绝重复工具名，因此它的 Layer 错误通道不是 `Nothing`；宿主必须把该配置错误保留在
启动错误通道，不能用 `orDie` 隐藏装配问题。

## 3. 定义 Agent

```scala
val agent = AgentDefinitionBuilder(AgentId("learning-agent"), "学习助手")
  .withInstructions("只依据可信资料回答，资料不足时明确说明。")
  .allowTool(ToolName("calculator"))
  .buildFor(toolPolicy)
```

`buildFor` 会在启动阶段校验 Agent 可见工具没有超出全局执行白名单，并拒绝敏感 metadata、非法模型数值和缺失指令。
需要纯数据构造时仍可直接使用 `AgentDefinition`。

## 4. 运行

优先使用 `AgentApplication.inMemoryDefaults/inMemory/durable` 组装统一 Runtime、控制面与 WorkerHost，完整说明见
[AgentApplication、Builder 与业务接入](application-builder.md)，可运行代码见
[BasicAgentExample.scala](../modules/agent-examples/src/main/scala/com/zyblw/agent/examples/BasicAgentExample.scala)。业务项目应通过构造器把 ArticleService、BookService 等注入工具，不允许模型直连数据库。

工具白名单是默认拒绝语义：`allowedTools = Set.empty` 表示不向模型暴露任何工具。不要把空集合当作“允许全部”。

生产宿主可通过 `AgentApplicationConfigLoader.load()` 从当前 ZIO `ConfigProvider` 加载工具、审批、重试与 Worker
lease/heartbeat 参数。默认环境变量前缀为 `ZYBLW_AGENT_`；完整字段、校验规则和 Secret 边界见
[AgentApplication、Builder 与业务接入](application-builder.md#4-使用-zio-config-加载治理参数)。

## 5. 接入真实模型

复制 `.env.example`，只设置环境变量；不要把 API Key 放入 AgentDefinition、日志或文档。配置说明见 [providers.md](providers.md)。

## 6. 使用耐久 RunStore

测试或单进程开发推荐一次提供完整内存控制面：

```scala
val persistenceLayer: ULayer[RunStore & RunCommandStore & RunSubmissionStore] =
  AgentPersistence.inMemory
```

宿主使用 PostgreSQL 时提供共享 `DataSource`，推荐直接组装 `PostgresAgentPersistence.layer`；它同时提供
`PostgresRunStore`、`PostgresRunCommandStore` 和 `PostgresRunSubmissionStore` 三个 SPI。Adapter 必须共享宿主连接池，
不要为 Agent 重复建池。详细语义见
[persistence.md](persistence.md)。

`PostgresAgentPersistence.layer` 应作为 `AgentApplication.durable` 的输入；生产层不会在 PostgreSQL 缺失或故障时自动
回退到内存 Store。

需要跨 Run 长期记忆时改用 `PostgresAgentPersistence.layerWithMemory`，它在同一个 DataSource 上额外提供
`MemoryStore`。pgvector 知识表需要显式选择固定维度并执行 optional migration，因此不会被该组合层偷偷启用。

框架不会在加载 JAR 或创建 ZLayer 时偷偷修改数据库。宿主在启动阶段显式调用
`AgentPostgresMigrations.migrate(dataSource)`，默认只扫描框架专属 classpath，并使用独立
`flyway_zyblw_agent_schema_history`。已有共享 Flyway 历史的系统必须按
[数据库迁移指南](database-migrations.md) 制定一次性接管方案，不能直接在存量库上启用新历史表。

## 7. 暴露 ZIO HTTP API

`AgentHttpApi.layer` 除 Runtime 和 AgentRegistry 外，强制要求 `AgentCommandService` 与
`AgentRequestContextResolver`。业务后端应从已验签的 JWT claim 或服务端 session 构造
`RunContext(userId, tenantId, scopes)`；创建 Run 的 JSON 正文只有 `threadId` 和 `input`，框架不会接受客户端自报
身份或权限。只有确实公开、无用户权限的 Agent 才能使用 `AgentRequestContextResolver.anonymous`。

创建 Run 使用 `POST /api/v1/agents/{agentId}/runs`，正文只含 `threadId/input`，并必须携带客户端生成的
`Idempotency-Key` 请求头。该请求不会持有模型执行 Fiber，而是原子提交 Created 状态、RunCreated、Start 命令和
dispatcher，立即返回 `202 Accepted + runId + commandId`。取消、审批、崩溃恢复和显式重试同样先写命令队列。部署中的
`WorkerHost` claim 命令、维护 heartbeat，并在有效 lease 下调用
`LeaseAwareAgentRuntime.executeLeased`。调用方应查询 `/api/v1/commands/{commandId}` 判断最终结果，不能把 `202` 理解为
AgentState 已完成。

`GET /api/v1/runs/{runId}/events` 提供一次性耐久公共事件页；`GET /api/v1/runs/{runId}/events/stream` 提供按 sequence
保存的跨节点 SSE。两者输出脱敏 `RunEventView`，不是内部 Event Store JSON。前端断线后把最后 SSE `id` 放入
`Last-Event-ID` 即可续传。两条读取路径都会执行 tenant/user 归属校验。OpenAPI 位于 `/api/v1/openapi.json`，版本与
兼容规则见 [HTTP API、OpenAPI 与 Schema 演进](http-api-versioning.md)。逐 token delta 仍是单进程 Hub 语义；完整边界见
[durable-streaming.md](durable-streaming.md)。

`GET /api/v1/runs/{runId}/inspection` 在相同授权之后聚合低敏 Run、Timeline 和一致性诊断。它不返回 Prompt、消息历史、
工具参数/结果或隐藏推理；分页、诊断语义与未来调试界面的权限边界见
[Run Inspector、Timeline 与安全调试](run-inspection.md)。

## 8. 启动独立 ZIO HTTP 宿主

独立部署 Agent 服务时引入 `zyblw-agent-zio-http`，用 `AgentHttpHost.fromApplication` 把上面的 `AgentHttpApi`、
`AgentApplication` command worker、ZIO HTTP Server 和健康检查置于同一个子 Scope。生产 PostgreSQL 部署应提供
`AgentHostReadiness.jdbc`；它以硬超时执行轻量 `SELECT 1`，不会调用付费模型。

Host 提供 `/health/live` 和 `/health/ready`。任一关键 Worker 退出会关闭 Server，Server 失败也会中断 Worker；调用方中断
Host Fiber 时，Provider 流、工具 Fiber 和资源 finalizer 一起收敛。Host 不会创建 DataSource、匿名认证、默认端口或
Provider Secret。完整 ZLayer 代码、配置和 Kubernetes 探针见 [ZIO HTTP 生产宿主](http-host.md)。

若 routes 嵌入已有 `zyblw-server`，不要启动第二个 Host；把 `AgentHttpApi.routes` 合并到现有服务器，并由现有应用 Scope
启动一次 Worker 即可。

## 9. 接入真实写工具

不要让一个普通工具先更新业务表，再单独发送消息。实现 `PostgresBusinessMutation`，使用传入的 JDBC `Connection`
修改业务表，并通过 `PostgresReliableWriteTool.make` 构造工具；框架会把业务幂等结果、outbox 和可选补偿计划放入同一
transaction。

独立启动 `OutboxPublisher` 执行事务外发送；下游若也使用 PostgreSQL，使用 `PostgresTransactionalInbox` 将去重记录
与下游业务 mutation 同事务提交。完整代码、幂等键设计、补偿激活和运维边界见 [side-effects.md](side-effects.md)。

## 10. 接入知识库 RAG

业务 Controller、Job 和 Tool 优先依赖 `RagApplication`，不要分别调用 Loader、Indexer 和 VectorStore：

```scala
val localRagLayer = ZLayer.make[RagApplication](
  DocumentLoaderRegistry.layer(Chunk(markdownLoader)),
  ZLayer.succeed[EmbeddingService](embeddingService),
  InMemoryKnowledgeIndexStore.knowledge,
  MarkdownStructureChunker.layer,
  KnowledgeIndexer.layer(),
  DocumentIngestionService.layer(
    maxParallelism = 2,
    failureMode = DocumentIngestionFailureMode.FailFast
  ),
  Reranker.identity,
  DefaultRetriever.layer,
  RagApplication.configured(RagApplicationConfig(defaultTopK = 5, maxTopK = 20))
)
```

生产环境把 `InMemoryKnowledgeIndexStore.knowledge` 替换为
`PostgresAgentPersistence.knowledge(dimension = 1536)`；后者同时提供版本化摄取和 hybrid 查询所需 SPI，并共享宿主
`DataSource`、向量维度和正式快照。仍需显式执行匹配维度的 optional pgvector migration，不会因构建 ZLayer 自动改库。

单文档调用 `rag.ingestOne(request)`；队列调用 `rag.ingest(requestStream)`；查询使用
`rag.retrieve(RagQuery(text, trustedScope, limit))`。门面会在 Embedding/数据库之前拒绝空 query、超长 query 和越界
topK。完整可运行路径见
[RagAgentExample.scala](../modules/agent-examples/src/main/scala/com/zyblw/agent/examples/RagAgentExample.scala)，PDF/Tika/Docling
配置见 [文档 Loader](document-loaders.md)。
