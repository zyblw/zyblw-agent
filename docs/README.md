# zyblw-agent 文档地图

> 状态：当前
> 最后核验：2026-09-16
> 事实来源：`build.sbt`、发布工作流、模块源码、测试与数据库迁移

## 按目标选择阅读路径

### 我要按生产参考接入

1. [总体使用手册](usage-guide.md)
2. [快速开始](getting-started.md)
3. [PostgreSQL 生产接入](postgres-quickstart.md)、[Docker + 自管 PostgreSQL](operations-docker-vm.md) 与 [升级到 0.9.0](fresh-install-0.9.0.md)
4. [AgentApplication 与 Builder](application-builder.md)
5. [Provider 与能力协商](providers.md)
6. [ZIO HTTP 生产宿主](http-host.md)

### 我要运行维护

1. [生产接入基线与发布候选判定](production-readiness.md)
2. [持久化](persistence.md)与[宿主数据库迁移](database-migrations.md)
3. [安全](security.md)、[可观测性](observability.md)、[值班 runbook](operations-runbook.md)
4. [Docker/VM 安装升级恢复](operations-docker-vm.md)
5. [管理 API 与运维控制台](admin-console.md)
6. [Wave 0 生产证据清单](evidence/wave-0-checklist.md)（机器门禁与宿主实测严格分开）

### 我要理解内核

1. [核心概念](core-concepts.md)
2. [架构总览](architecture.md)
3. [Agent Application Runtime ADR](architecture/0016-agent-application-runtime.md)
4. [agent-dashboard 与管理 API 边界 ADR](architecture/0017-agent-dashboard-architecture.md)
5. [下一代 Runtime Kernel ADR](architecture/0018-next-generation-runtime-kernel.md)、[Typed Extensions 与 Constrained Execution ADR](architecture/0019-typed-extensions-and-constrained-execution.md)、[现代化基线 ADR](architecture/0020-modernization-baseline.md) 与 [开发手册](architecture/next-generation-runtime.md) — P0、P1 与 P2 Harness 已落地；Wave 1 与 Wave 2 安全切片已落地；后续 Wave 3 分支与编排仍为 Proposed。Wave 0 宿主环境证据仍待。
6. [Functional Kernel / Runtime Driver ADR](architecture/0028-functional-kernel-runtime-driver.md) 与 [Context Authority / Prompt Lineage ADR](architecture/0029-context-authority-prompt-lineage.md) — 纯 `AgentKernel` + 唯一 `AgentRuntimeDriver` 已落地；Memory/RAG/摘要不再走 System 通道，Prompt Cache 不是事实源。
7. [Model Runtime 现状审计](architecture/model-runtime-current-state.md) 与 [目标架构](architecture/model-runtime-target.md) — Phase 0 文档；补推理资源调度面，不另起执行内核。路由 / Provider 契约 / 预算 / 多模型执行见同目录；ADR-0021…0026 演进 ADR-0004/0018。[ADR-0027 RAG Runtime 绿场基线](architecture/0027-rag-retrieval-runtime.md)。
8. [运行时](runtime.md) 与 [Prompt Runtime、Context Authority 与 Cache](prompt-runtime.md)
9. [工具](tools.md) 与 [可靠写工具](side-effects.md)
10. [持久化](persistence.md) 与 [数据库 Schema](database-schema.md)
11. [声明式 Workflow](workflow.md)

### 我要深入读源码和参与开发

1. [学习指南](learning-guide.md)
2. [源码阅读路线](source-tour.md)
3. [代码注释与源码阅读约定](code-commenting-guide.md)
4. [测试](testing.md)
5. [Agent Eval 数据集治理](eval-dataset-governance.md)
6. [评测趋势仓库与 CI 发布门禁](eval-trend-and-release-gate.md)
7. [能力审计与框架对照](framework-assessment.md)
8. [成熟度与路线](maturity-and-roadmap.md)
9. [下一代 Runtime 开发手册](architecture/next-generation-runtime.md)（含 Wave 0–3 路线、Adoption Matrix 与 Phase 0 审计证据表）
10. [0914 架构说明审查与演进裁决](0914-architecture-review.md)（逐项对照当前实现，区分采纳、深化、暂缓与拒绝）
11. [Runtime、Context 与 Prompt Cache 一体化演进方案](architecture/runtime-context-evolution-plan.md)（0915 输入的裁剪基线；Phase 0–1 与 Phase 2/3 核心切片已落地，显式 Provider cache dialect 仍待）
12. [0914/0915 日期式输入稿](文档说明/README.md)（咨询与演进输入，不是当前契约）

### 我要接入知识库

1. [RAG / Knowledge Retrieval Runtime 目标架构与实施规范](architecture/rag-runtime-target.md)（Proposed，含现状审计、全流程架构、迁移、评测与 Cursor 分阶段上下文包）
2. [Context、Memory 与 RAG](context-memory-rag.md)
3. [PDF RAG 生产流水线](pdf-rag-pipeline.md)
4. [文档 Loader、PDF→Markdown 与结构切分](document-loaders.md)
5. [Embedding 治理](embedding-governance.md)
6. [Reranker](reranker.md)
7. [RAG 评测](rag-evaluation.md)

## 开源维护与发布

- [贡献指南](../CONTRIBUTING.md)
- [代码注释与源码阅读约定](code-commenting-guide.md)
- [VS Code 与 Metals](vscode-metals.md)
- [模块与依赖选择](modules.md)
- [版本、Maven Central 发布与回滚](releasing.md)
- [兼容性契约与版本边界](compatibility.md)
- [0.9.0 全新安装基线与知识问答](fresh-install-0.9.0.md)
- [宿主数据库迁移](database-migrations.md)
- [PostgreSQL 生产接入与运维](postgres-quickstart.md)
- [Docker/VM 安装、升级与恢复](operations-docker-vm.md)
- [业务仓库消费与跨仓联调](consuming-from-server.md)
- [独立公开仓库 ADR](architecture/0015-independent-public-repository.md)
- [已被取代的原开源边界 ADR](architecture/0013-open-source-release-boundary.md)
- [公共模块收敛 ADR](architecture/0014-consolidate-public-modules.md)
- [安全报告政策](../SECURITY.md)
- [变更日志](../CHANGELOG.md)

## 接入与运行

- [总体使用手册](usage-guide.md)
- [快速开始](getting-started.md)
- [AgentApplication 与 Builder](application-builder.md)
- [声明式 Workflow Graph](workflow.md)
- [Provider 与能力协商](providers.md)
- [Model Runtime 现状审计](architecture/model-runtime-current-state.md)
- [Model Runtime 目标架构](architecture/model-runtime-target.md)
- [模型路由](architecture/model-routing.md)
- [Provider 契约与 Adapter](architecture/model-provider-contract.md)
- [模型预算与成本](architecture/model-budget-and-cost.md)
- [多模型执行](architecture/multi-model-execution.md)
- [ProviderContract 2.0](provider-contract-2.md)
- [真实 Provider smoke](provider-live-smoke.md)
- [HTTP 公共协议与版本](http-api-versioning.md)
- [Run Inspector、Timeline 与安全调试](run-inspection.md)
- [管理 API 与运维控制台](admin-console.md)
- [ZIO HTTP 生产宿主](http-host.md)
- [耐久 SSE](durable-streaming.md)

## 知识与上下文

- [RAG / Knowledge Retrieval Runtime 目标架构与实施规范](architecture/rag-runtime-target.md)（Proposed）
- [指令、Context 与成本工程](instruction-context-cost.md)
- [Prompt Runtime、Context Authority 与 Cache](prompt-runtime.md)
- [Context、Memory 与 RAG](context-memory-rag.md)
- [确定性/模型辅助 Context 压缩](context-compression.md)
- [Context 压缩评测](context-compression-evaluation.md)
- [长期记忆治理](memory-governance.md)
- [Embedding 治理](embedding-governance.md)
- [Reranker](reranker.md)
- [文档 Loader、PDF→Markdown 与结构切分](document-loaders.md)
- [PDF RAG 生产流水线](pdf-rag-pipeline.md)
- [RAG 评测](rag-evaluation.md)

## 安全与扩展

- [能力审计、竞品对照与演进判断](framework-assessment.md)
- [安全](security.md)
- [MCP](mcp.md)
- [Workspace 与 Sandbox](sandbox.md)
- [ADR](architecture.md#adr)

所有 ADR 保留设计理由，但当前行为以源码、测试、构建和本页标记的当前文档为准。
