# 模块与发布坐标

> 状态：当前
> 最后核验：2026-07-30
> 事实来源：`build.sbt`、各模块 `src/main`、`maturity-and-roadmap.md`

## 先理解两个不同的边界

`zyblw-agent` 不再把每个 Scala package 都发布成一个 Maven artifact：

- **package 是代码边界**：`core`、`model`、`tools`、`memory`、`context`、`runtime`、`app`
  仍然各自表达职责，禁止随意反向依赖。
- **artifact 是交付边界**：只有需要独立选择、会引入重型依赖、使用独立协议或具有不同运行生命周期的能力才拆分。

这一原则把旧的三十多个可发布薄模块收敛为 11 个公共 artifact。减少 artifact 不等于取消架构边界，而是避免让使用方承担
框架维护者的内部组织成本。

所有发布模块使用统一坐标：

```scala
val zyblwAgentVersion = "<已发布版本>"
"io.github.zyblw" %% "<artifact>" % zyblwAgentVersion
```

`%%` 会为 Scala 3 选择 `_3` artifact。不要手写 `_3`，也不要在同一次构建中混用源码 `ProjectRef` 与 Maven 依赖。

## 最小接入

普通业务从两个依赖开始：

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"      % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-providers" % zyblwAgentVersion
)
```

`core` 已提供 Agent 定义、模型 SPI、类型化工具、权限、Context、Memory、单 Agent loop、应用 Builder、调度和观测 SPI。
`providers` 提供 OpenAI-compatible、OpenAI Responses、Anthropic Messages 与 Gemini Interactions 适配器。业务无需分别理解十几个
基础 artifact。

## 公共 artifact

| Artifact | 何时引入 | 为什么单独发布 |
|---|---|---|
| `zyblw-agent-core` | 所有应用 | 稳定、Provider-neutral、无数据库和 HTTP Server |
| `zyblw-agent-providers` | 调用内置模型 Provider | 独立外部协议；只依赖 ZIO HTTP，不引入厂商 SDK |
| `zyblw-agent-rag` | `RagApplication`、结构切分、版本化摄取、知识检索、引用回答 | RAG 是可选业务能力，不污染最小 tool loop |
| `zyblw-agent-document-loaders` | PDF/EPUB/Office 摄取 | Apache Tika 与 Docling Serve HTTP 都是可选重型/协议边界 |
| `zyblw-agent-rerank` | 调用外部 reranker | 会产生网络和数据驻留边界 |
| `zyblw-agent-postgres` | 耐久 Run、Workflow execution/checkpoint、Memory、RAG、评测 | JDBC、Flyway、数据库 schema 与生命周期独立 |
| `zyblw-agent-zio-http` | 暴露控制面或独立 Agent 服务 | ZIO HTTP Endpoint、routes、host 属于传输边界 |
| `zyblw-agent-mcp` | MCP client 与受控 workspace | 外部工具互操作和执行安全边界独立；server 仍在路线图 |
| `zyblw-agent-opentelemetry` | OTLP traces/metrics | SDK/exporter 有资源与后台线程，不进入零成本 SPI |
| `zyblw-agent-evals` | 离线评测、发布门禁、趋势 | 通常只在测试和 CI 使用 |
| `zyblw-agent-testkit` | Stub Provider、Runtime contract | 只在 Test scope 使用 |

例如，使用 PostgreSQL 与 ZIO HTTP 的生产应用：

```scala
libraryDependencies ++= Seq(
  "io.github.zyblw" %% "zyblw-agent-core"      % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-providers" % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-postgres"  % zyblwAgentVersion,
  "io.github.zyblw" %% "zyblw-agent-zio-http"  % zyblwAgentVersion
)
```

RAG 业务还需加入 `zyblw-agent-rag`；PDF/EPUB 再加入 `zyblw-agent-document-loaders`，模型 rerank 再加入
`zyblw-agent-rerank`。不要因为只想使用结构切分就被迫引入 Tika、Docling 或远程 reranker。

## 内核中的 package

下列能力属于同一个 `zyblw-agent-core` artifact，但保持独立 package：

| Package | 职责 |
|---|---|
| `core` | ID、错误、Definition、分层 Instruction、State、Event、Run SPI |
| `model` | Provider-neutral `ChatModel` 与流事件 |
| `tools` | Tool schema、注册表、权限和结构化执行结果 |
| `guardrails` | 输入、输出和工具调用策略 |
| `context` | 上下文预算、确定性压缩与可选模型摘要 |
| `memory` | 短期/长期记忆 SPI、命令队列和租约模型 |
| `artifacts` | 版本化二进制 Artifact SPI 与开发/测试内存 Adapter；不把正文放入 State 或 Prompt |
| `runtime` | 单 Agent loop、预算、重试、审批、恢复 |
| `scheduler` | Worker 调度与任务领取 |
| `observability` | 无 exporter 的 trace/metrics SPI |
| `app` | 面向业务宿主的 Builder 与 ZLayer 装配入口 |
| `sideeffects` | 有副作用工具的 outbox/idempotency 模型 |
| `workflow` | 显式确定性图、identity/version、checkpoint 与 execution ledger/fencing SPI；不是多 Agent 编排平台 |
| `multimodal` | Provider-neutral 内容部件 ADT |

把这些 package 拆成十几个 artifact 的收益很小：它们共享 ZIO 基础依赖、经常共同变更，业务也几乎总是一起使用。过去的拆法
反而放大了 POM、版本兼容、文档选择和构建图成本。

## 仓库内项目

`agent-examples`、`agent-benchmarks`、`agent-eval-cli` 和根聚合项目设置为 `publish / skip := true`。它们参与源码验证，
但不是库契约。可执行 CLI 若形成独立需求，应发布原生安装包或容器，而不是混入普通类库依赖。

## 何时允许新增 artifact

新增 artifact 必须至少满足一项，并写 ADR：

1. 引入明显的重型或冲突依赖；
2. 对接独立外部协议/厂商，且用户可不选择它；
3. 拥有独立资源生命周期，例如连接池、Server、后台 exporter；
4. 安全或许可证边界要求物理隔离；
5. 已有至少两个真实使用方证明需要独立版本化。

仅仅“代码概念不同”“有一个 trait”“将来可能扩展”都不是新增 artifact 的充分理由。

## 兼容策略

当前采用 early SemVer：

- `0.x` minor 可包含明确记录的破坏性调整；
- 同一 minor 的 patch 应保持源码和二进制兼容；
- wire schema、数据库 migration 与 Scala API 分别维护兼容性；
- 所有破坏性变化写入 `CHANGELOG.md` 和迁移指南；
- 发布前必须通过 MiMa/下游二进制消费测试（在首次公开版本前补齐）。
