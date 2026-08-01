# zyblw-agent 文档地图

> 状态：当前
> 最后核验：2026-08-02
> 事实来源：`build.sbt`、发布工作流、模块源码、测试与数据库迁移

## 按目标选择阅读路径

### 我要先跑起来

1. [快速开始](getting-started.md)
2. [模块选择](modules.md)
3. [AgentApplication 与 Builder](application-builder.md)
4. [Provider 与能力协商](providers.md)

### 我要开始构建生产业务

1. [生产接入基线与发布候选判定](production-readiness.md)
2. [AgentApplication 与 Builder](application-builder.md)
3. [持久化](persistence.md)与[宿主数据库迁移](database-migrations.md)
4. [ZIO HTTP 生产宿主](http-host.md)
5. [安全](security.md)、[可观测性](observability.md)与[测试](testing.md)

### 我要理解内核

1. [核心概念](core-concepts.md)
2. [架构总览](architecture.md)
3. [Agent Application Runtime ADR](architecture/0016-agent-application-runtime.md)
4. [运行时](runtime.md)
5. [工具](tools.md) 与 [可靠写工具](side-effects.md)
6. [持久化](persistence.md) 与 [数据库 Schema](database-schema.md)
7. [声明式 Workflow](workflow.md)

### 我要深入读源码和参与开发

1. [学习指南](learning-guide.md)
2. [源码阅读路线](source-tour.md)
3. [代码注释与源码阅读约定](code-commenting-guide.md)
4. [测试](testing.md)
5. [能力审计与框架对照](framework-assessment.md)
6. [成熟度与路线](maturity-and-roadmap.md)

### 我要接入知识库

1. [Context、Memory 与 RAG](context-memory-rag.md)
2. [文档 Loader、PDF→Markdown 与结构切分](document-loaders.md)
3. [Embedding 治理](embedding-governance.md)
4. [Reranker](reranker.md)
5. [RAG 评测](rag-evaluation.md)

## 开源维护与发布

- [贡献指南](../CONTRIBUTING.md)
- [代码注释与源码阅读约定](code-commenting-guide.md)
- [VS Code 与 Metals](vscode-metals.md)
- [模块与依赖选择](modules.md)
- [版本、Maven Central 发布与回滚](releasing.md)
- [兼容性契约与版本边界](compatibility.md)
- [进入 0.3.0 开发线：清库重建](upgrading-to-0.3.0.md)
- [从 0.1.0 升级到 0.2.0](upgrading-to-0.2.0.md)
- [从 0.2.0 升级到 0.2.1](upgrading-to-0.2.1.md)
- [宿主数据库迁移](database-migrations.md)
- [业务仓库消费与跨仓联调](consuming-from-server.md)
- [独立公开仓库 ADR](architecture/0015-independent-public-repository.md)
- [已被取代的原开源边界 ADR](architecture/0013-open-source-release-boundary.md)
- [公共模块收敛 ADR](architecture/0014-consolidate-public-modules.md)
- [安全报告政策](../SECURITY.md)
- [变更日志](../CHANGELOG.md)

## 接入与运行

- [快速开始](getting-started.md)
- [AgentApplication 与 Builder](application-builder.md)
- [声明式 Workflow Graph](workflow.md)
- [Provider 与能力协商](providers.md)
- [ProviderContract 2.0](provider-contract-2.md)
- [真实 Provider smoke](provider-live-smoke.md)
- [HTTP 公共协议与版本](http-api-versioning.md)
- [Run Inspector、Timeline 与安全调试](run-inspection.md)
- [ZIO HTTP 生产宿主](http-host.md)
- [耐久 SSE](durable-streaming.md)

## 知识与上下文

- [指令、Context 与成本工程](instruction-context-cost.md)
- [Context、Memory 与 RAG](context-memory-rag.md)
- [确定性/模型辅助 Context 压缩](context-compression.md)
- [Context 压缩评测](context-compression-evaluation.md)
- [长期记忆治理](memory-governance.md)
- [Embedding 治理](embedding-governance.md)
- [Reranker](reranker.md)
- [文档 Loader、PDF→Markdown 与结构切分](document-loaders.md)
- [RAG 评测](rag-evaluation.md)

## 安全与扩展

- [能力审计、竞品对照与演进判断](framework-assessment.md)
- [安全](security.md)
- [MCP](mcp.md)
- [Workspace 与 Sandbox](sandbox.md)
- [ADR](architecture.md#adr)

所有 ADR 保留设计理由，但当前行为以源码、测试、构建和本页标记的当前文档为准。
