# zyblw-agent 文档地图

> 状态：当前
> 最后核验：2026-07-29
> 事实来源：`build.sbt`、发布工作流、模块源码、测试与数据库迁移

## 推荐学习路径

1. [快速开始](getting-started.md)：先从依赖、最小 Agent 和宿主装配跑通一条路径。
2. [模块选择](modules.md)：只选择业务真正需要的模块，并理解 Stable/Beta/Experimental 边界。
3. [能力审计与框架对照](framework-assessment.md)：先客观理解强项、缺口、取舍和演进证据。
4. [学习指南](learning-guide.md)：建立 ZIO Agent 的完整心智模型。
5. [源码阅读路线](source-tour.md)：按文件、方法、测试和完成标准深入主链路。
6. [核心概念](core-concepts.md)：认识 ID、Definition、Run、State、Event、Command。
7. [架构总览](architecture.md)：理解模块依赖和端到端时序。
8. [运行时](runtime.md)：阅读单 Agent loop、预算、工具计划、审批、恢复。
9. [声明式 Workflow Graph](workflow.md)：理解显式边、启动校验、循环预算、并行汇合和 checkpoint。
10. [指令、Context 与成本](instruction-context-cost.md)：理解可信指令、动态资料、缓存和推理 token。
11. [工具](tools.md) 与 [可靠写工具](side-effects.md)：理解能力、权限、副作用和幂等。
12. [Context/Memory/RAG](context-memory-rag.md)：区分短期上下文、长期记忆和外部知识。
13. [持久化](persistence.md) 与 [数据库 Schema](database-schema.md)：理解耐久性。
14. [Run Inspector](run-inspection.md)、[测试](testing.md)、[可观测性](observability.md)、[评测趋势门禁](eval-trend-and-release-gate.md)。
15. [成熟度与路线](maturity-and-roadmap.md)：客观看待哪些能力可依赖。

## 开源维护与发布

- [贡献指南](../CONTRIBUTING.md)
- [VS Code 与 Metals](vscode-metals.md)
- [模块与依赖选择](modules.md)
- [版本、Maven Central 发布与回滚](releasing.md)
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
