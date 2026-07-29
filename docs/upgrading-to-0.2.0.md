# 从 0.1.0 升级到 0.2.0

> 状态：升级指南
> 最后核验：2026-07-29
> 适用范围：Scala 3 公共 API、RAG 索引身份、Workflow checkpoint 与 Flyway migration

`0.2.0` 是第二个公开版本，也是 Early SemVer 下允许明确破坏性改进的 minor 版本。没有使用 Experimental Workflow 或自定义
RAG `Chunker` 的普通 `AgentRuntime`、Provider、Tool 和 HTTP v1 消费者通常不需要改源码，但仍应重新编译并运行自己的
测试，不应只替换 JAR。

## 1. Workflow Graph

节点不再决定下一跳。把节点返回值改为：

```scala
NodeOutcome.Succeeded(nextState)
NodeOutcome.Suspended(nextState, reason)
```

然后在 `WorkflowDefinition.make` 中显式提供：

```scala
id = WorkflowId("article-review")
version = WorkflowVersion(1)
transitions = Map(
  prepare -> WorkflowTransition.Next(review),
  review  -> WorkflowTransition.Complete()
)
```

每个可达节点都必须声明 transition；循环节点必须有正数 `visitLimits`。`Route` 只能选择预先声明的目标。

`WorkflowCheckpointStore` 现在保存 workflow/version/session identity、cursor、state、step 和 visit counts 的完整单调
checkpoint。旧的自定义 Store 实现必须按新 SPI 重写；不要把 `0.1.0` 的实验性 checkpoint JSON 直接伪装成新 schema。
当前没有自动 upcaster，因为 `0.1.0` 没有发布 PostgreSQL Workflow migration，生产使用者应从明确业务输入重新启动
实验 Run。

## 2. RAG

`SourceDocument` 新增 `representation`，默认是 `DocumentRepresentation.PlainText`。Loader 产生结构化 Markdown 时应显式
设置 `Markdown`，让 `MarkdownStructureChunker` 保留标题、表格和 fenced code 边界。

每个自定义 `Chunker` 必须实现稳定、参数完整的：

```scala
def strategyId: String
```

例如 `semantic-v2:model=...:max=...`。`KnowledgeIndexer` 会把真实 `strategyId` 固化到 manifest；升级后应构建新索引版本，
不能把新切分结果写入仍声称使用旧策略的 active snapshot。

推荐业务改用 `RagApplication` 作为摄取和查询入口，并保证 `KnowledgeIndexStore & VectorStore` 来自同一个
`RagStorageLayers` 组合层，避免索引发布和检索读取不同事实源。

## 3. 数据库

先备份并在代表性副本演练 Flyway 升级。`V008__agent_workflow_checkpoints.sql` 只新增框架自有表，不修改已经发布的 V001–V007。
宿主仍应使用 `AgentPostgresMigrations` 的独立 location，不要把框架 migration 复制进业务仓库后手工修改。

## 4. 验证

建议升级分支至少执行：

```bash
sbt -batch test
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
```

框架维护者还必须运行完整 `testFull`、`publishM2`、独立 Maven consumer 和发布后 Central 下游回归。任何自定义 Workflow
Store、Chunker、索引 manifest 或数据库权限都需要单独 contract test。

## 5. 回滚

Maven Central artifact 不可覆盖。业务回滚时恢复依赖版本 `0.1.0`，并继续保留 V008 表；不要 down-migrate 或编辑已执行
的 migration。`0.2.0` 创建的新 Workflow checkpoint 和新 RAG 索引不能交给 `0.1.0` 代码读取。
