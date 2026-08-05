# 架构总览

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-08-02
>
> 事实来源：对应模块源码、测试与构建定义

## 设计边界

模型只提出文本、结构化结果或工具调用。Runtime 负责能力校验、授权、执行、持久化、预算、终止和观测。Provider、数据库、HTTP、OpenTelemetry、MCP 与业务服务不能反向污染 `agent-core`。

框架的应用语义分为 Agent、Harness 与 Workflow：Agent 负责开放式模型循环；Harness 负责 Goal/Plan/Todo、Workspace、
Artifact、Skill、Context 和 Sandbox 等长任务支架；Workflow 负责显式步骤、路由和恢复边界。它们按需组合，不是三个
强制部署服务，也不自动对应三个 Maven artifact。完整决策见
[ADR 0016](architecture/0016-agent-application-runtime.md)。

`zyblw-agent-core` 中的 `app` package 位于业务宿主与运行控制层之间，只组合
`AgentRuntime/AgentCommandService/WorkerHost` 及其稳定 SPI。它不会定义
第二套状态、隐藏数据库 fallback 或把 Provider 类型引入 core；生产 `durable` 入口要求业务显式提供持久化、Context、
Guardrail 和 Observer。WorkerHost 在单个父 effect 下运行有界 claim lane：不同 Run 可以并行，同一 Run 仍由 dispatcher
严格串行；任一 lane 失败会中断其余 lane 并交给外层 Supervisor，避免形成部分失效进程。

`zyblw-agent-zio-http` artifact 位于最外层传输边界；其中 `http.host` package 只把 `AgentHttpApi` routes、command
worker、健康探针与 ZIO HTTP Server 放入同一个子 Scope。它不反向进入 Runtime，也不创建 DataSource、认证或 Provider
Secret；嵌入已有业务服务器时只组装 routes，不启动独立 Host。

同一 artifact 中的 `http.contract` package 位于客户端与 HTTP Adapter 之间，只承载 `/api/v1` DTO、ZIO Schema、
Endpoint 与 OpenAPI；`http` package 通过显式公共投影把授权后的 `AgentState/AgentEvent` 转成低敏协议对象，内部恢复
Schema 不再等于外部 wire Schema。首次公开版本不再为这个很小的 package 单发 artifact；如果出现两个以上只消费协议
而不运行 JVM Runtime 的真实客户端，再以 ADR 评估拆分。

## 代码与模块分层

依赖方向从外围指向内核，不能反向：

```text
业务 Host / examples
  -> zio-http、postgres、document-loaders、providers、telemetry、mcp
  -> rag、context、memory
  -> agent-core
  -> ZIO
```

- `agent-core`：provider-neutral ADT、Agent Runtime、Tool/权限/Guardrail、耐久命令 SPI、Workflow/Harness 与应用装配；
- `agent-rag`：文档、结构谱系、切分、Embedding、Retriever、Reranker 和知识发布 SPI，不依赖 Docling/JDBC；
- `agent-document-loaders`：本地目录边界与 Docling HTTP Adapter，把外部 JSON/Markdown 投影为 `agent-rag` 类型；
- `agent-postgres`：Run/Command/Workflow/Memory/RAG JDBC Adapter、Flyway 和数据库结构探针；
- `agent-zio-http`：Endpoint/OpenAPI/Routes/SSE 和可选 Host 生命周期；
- Provider、OpenTelemetry、MCP 分别留在独立协议/安全/依赖边界，不进入 core。

业务应在自己的 composition root 用 `ZLayer` 选择实现并明确提供依赖；库代码不调用 `Runtime.default.unsafe.run`，不隐藏全局单例，
不在 Adapter 中创建第二套业务状态。资源由 `ZLayer.scoped`/`Scope` 管理，阻塞 JDBC/文件 API 留在对应 Adapter 的阻塞边界。

## 结构化 RAG 数据路径

```mermaid
flowchart LR
  Source["PDF / Markdown / 受控目录"] --> Loader["DocumentLoader / Docling Adapter"]
  Loader --> Document["SourceDocument + blocks/page/bbox"]
  Document --> Chunker["DocumentStructureChunker / Markdown fallback"]
  Chunker --> Chunks["DocumentChunk + parent/neighbor lineage"]
  Chunks --> Embed["GovernedEmbeddingService"]
  Embed --> Staging["KnowledgeIndexStore staging"]
  Staging --> Active["原子 activate"]
  Active --> Knowledge[("zyblw_agent_knowledge schema")]
  Query["RetrievalScope + query"] --> Hybrid["ACL-first vector + FTS + RRF"]
  Knowledge --> Hybrid
  Hybrid --> Rerank["可选 Reranker"]
  Rerank --> Expand["ACL-first parent/neighbor expansion"]
  Expand --> Citation["bounded context + page/bbox citation"]
```

原始 PDF/完整 Markdown 属于宿主对象存储；知识表只保存稳定 URI、hash、可回答 chunk、向量、ACL 与可追溯谱系。核心表和知识表使用同一
`DataSource` 时仍有独立 Flyway 生命周期：核心管理宿主默认 schema，0.4 知识索引固定管理 `zyblw_agent_knowledge`，运行时 SQL 不依赖
`search_path`。OCR、LLM、Embedding 和对象存储调用全部在数据库事务之外完成。

下图是当前已经落地的主路径。HTTP、CLI 与恢复 worker 都调用同一个 `AgentRuntime`；`ContextManager`、
`GuardrailEngine`、`RegisteredToolRegistry`、`ToolExecutor` 和 `RunStore` 已进入同一状态机。

```mermaid
flowchart LR
  Business[业务应用] --> Host[zio-http / host 可选部署宿主]
  Business --> Workflow[Workflow Engine]
  Host --> App[core / app 易用装配层]
  Host --> HTTP[HTTP Adapter + Health]
  HTTP --> Contract[HTTP v1 Contract + OpenAPI]
  App --> CLI[CLI Adapter]
  App --> HTTP
  App --> Control[AgentCommandService]
  App --> Scheduler[WorkerHost]
  HTTP --> Control
  HTTP --> Runtime[ZIO Agent Runtime]
  Runtime --> Context[Context Manager]
  Runtime --> Provider[Model Provider SPI]
  Runtime --> Policy[Tool Policy + Guardrails]
  Runtime --> Store[RunStore]
  Store --> Inspector[低敏 Run Inspector]
  Inspector --> HTTP
  Runtime --> Telemetry[Telemetry SPI]
  Context --> Memory[Memory]
  Context --> RAG[RAG/Retriever]
  Policy --> Tools[Typed Tools]
  Tools --> Effects[Transactional Side Effects]
  Provider --> OpenAI[OpenAI-compatible]
  Store --> PostgreSQL[PostgreSQL]
  Control --> Queue[RunCommandStore]
  Scheduler --> Runtime
  Scheduler --> Queue
  Queue --> PostgreSQL
  Effects --> PostgreSQL
  Effects --> Transport[Kafka/NATS/SQS/Webhook Adapter]
  Workflow --> WorkflowStore[Execution Store + Checkpoint]
  WorkflowStore --> PostgreSQL
  WorkflowStore --> WorkflowTimeline[低敏 Execution Timeline]
  WorkflowStore --> WorkflowWait[Durable Wait + Signal]
  WorkflowWake[Scoped Wake Worker] --> WorkflowStore
  WorkflowWake --> Workflow
```

Inspector 从授权后的权威 State 与耐久 Event 生成只读 Timeline 和一致性诊断。它不承担恢复和重放，因此不会成为与
Runtime 竞争的第二套状态；它也不复制 Prompt、消息、工具参数/结果或隐藏推理。边界见
[Run Inspector、Timeline 与安全调试](run-inspection.md)。

Workflow execution timeline 遵守同一原则：它从节点账本投影 node/step/status/generation/owner/时间戳，不复制应用状态、
pending outcome 或 fencing token。durable wait 则保存条件、绝对 deadline 和唯一决议；signal payload 不进入 timeline。
已决议 wait 自身作为 wake command，由 Scoped Worker 以租约领取并恢复；恢复仍只读取权威 checkpoint、ledger 与 wait。
外部 Adapter 必须在查询前验证 Run 的 tenant/user 读取权限。

## Agent Run 时序

```mermaid
sequenceDiagram
  participant U as User
  participant H as HTTP/API
  participant Q as CommandService/Queue
  participant W as WorkerHost
  participant R as AgentRuntime
  participant S as RunStore
  participant C as ContextManager
  participant M as ModelProvider
  participant P as Policy/Guardrail
  participant T as Tool
  U->>H: POST Start + Idempotency-Key
  H->>Q: submitStart
  Q->>S: 原子 Created/RunCreated/Start/dispatcher
  H-->>U: 202 + runId + commandId
  W->>Q: claim + lease/heartbeat
  W->>R: executeLeased
  R->>S: load AgentState
  R->>C: build bounded context
  R->>M: capabilities + stream
  M-->>R: text/tool/usage events
  R->>P: validate + authorize
  alt approval required
    R->>S: 保存 WaitingForApproval
    U->>H: 提交审批决定
    H->>Q: ResumeApproval 命令
  else allowed
    R->>T: scoped/cancellable execute
    T-->>R: structured ToolResult
    R->>S: commit state/events
    R->>M: next model turn
  end
  R->>S: fenced terminal commit
  W->>Q: fenced complete
  U->>H: 查询状态/耐久 SSE
```

## ADR

- [0001 核心运行时](architecture/0001-core-runtime.md)
- [0002 状态和事件](architecture/0002-agent-state-and-events.md)
- [0003 工具安全](architecture/0003-tool-security-model.md)
- [0004 Provider](architecture/0004-provider-abstraction.md)
- [0005 持久化恢复](architecture/0005-persistence-and-recovery.md)
- [0006 Workflow 边界](architecture/0006-workflow-boundary.md)
- [0007 可观测性](architecture/0007-observability.md)
- [0008 成熟框架参考与 ZIO 原生演进](architecture/0008-framework-evolution.md)
- [0009 跨 worker 调度、工具冲突计划与业务评测](architecture/0009-distributed-scheduling-and-evals.md)
- [0010 耐久控制命令队列](architecture/0010-durable-command-queue.md)
- [0011 事务写工具、Outbox/Inbox 与显式补偿](architecture/0011-transactional-side-effects.md)
- [0012 独立、版本化的 HTTP 公共契约](architecture/0012-versioned-http-contract.md)
- [0013 开源发布边界](architecture/0013-open-source-release-boundary.md)
- [0014 收敛公共模块](architecture/0014-consolidate-public-modules.md)
- [0015 独立公共仓库](architecture/0015-independent-public-repository.md)
- [0016 Agent Application Runtime 与三层边界](architecture/0016-agent-application-runtime.md)
- [0017 agent-dashboard 前端控制台架构与设计方案](architecture/0017-agent-dashboard-architecture.md)
