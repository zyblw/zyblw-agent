# zyblw-agent 成熟度、取舍与路线

> 状态：路线图
> 最后核验：2026-07-25
> 事实来源：`build.sbt`、模块源码、测试、发布工作流、迁移与当前文档

## 成熟度语义

- **Foundation**：核心契约清晰，主路径和错误路径有确定性测试，可作为框架内部依赖。
- **Beta**：真实实现存在并可集成，但 API、运维、兼容或真实负载证据仍不足。
- **Experimental**：用于验证方向，契约和 Schema 可能改变。
- **Planned**：意图，不是当前能力。

代码量和模块数量不代表成熟；Foundation 也不等于经过大规模生产证明。

版本语义与能力成熟度是两条轴：整个仓库当前按 `0.x` Early SemVer 发布；表中 Foundation 只表示内部契约和测试基础
较稳，不表示已经承诺 `1.x` 的长期二进制兼容或大规模生产 SLO。

## 当前矩阵

| 能力 | Artifact / package | 等级 | 已有证据 | 主要缺口 |
|---|---|---|---|---|
| ID/State/Event/Error/Limits | core / `core` | Foundation | codec、状态、不变量测试 | 长期 schema 兼容演练 |
| 分层指令与指纹 | core / `core` | Foundation | System/Developer 顺序、版本、重复和稳定 fingerprint 测试 | 动态指令函数与 eval 身份自动关联 |
| Provider-neutral 模型流 | core / `model` | Foundation | 统一事件和测试模型 | capability 矩阵持续演进 |
| 类型化工具与策略 | core / `tools`,`guardrails` | Foundation | schema、allowlist、风险和结果测试 | policy 管理 UX |
| 单 Agent loop | core / `runtime` | Foundation | budget、工具、审批、恢复、遥测测试 | 长运行与大负载故障注入 |
| durable command worker | core / `app`,`scheduler`,`runtime` | Foundation | claim/lease/heartbeat/fencing | 多节点 soak、运维 dashboard |
| HTTP v1 公共协议 | zio-http / `http.contract`,`http` | Foundation/Beta | 独立 DTO、Endpoint、OpenAPI、route test | 客户端 SDK、兼容升级演练 |
| Run Inspector / Timeline | core + zio-http / `inspection` | Foundation/Beta | 低敏投影、分页、授权、结构诊断与泄漏测试 | CLI/UI、筛选导出、真实事故验证 |
| Context | core / `context` | Beta | 有界装配、确定性压缩测试 | 真实长会话数据集 |
| 模型辅助压缩 | core / `context.llm` | Beta | evidence 校验和 eval | 多 Provider 质量/成本基线 |
| Memory | core / `memory`,`memory.llm` | Beta | Store/SPI 与治理设计 | 用户查看/删除 UX、长期质量 |
| RAG | rag、document-loaders、rerank | Beta/Experimental | retriever、citation、eval 和 adapters | 大规模 ingestion、ACL、撤回、线上质量 |
| PostgreSQL | postgres | Beta | Testcontainers、迁移、并发、连接耗尽测试 | 大库升级、备份恢复、性能 |
| OpenAI-compatible | providers / `integrations.openai` | Beta | stream/tool/error stub 与 smoke | 长期真实 Provider 观测 |
| Anthropic/Gemini | providers / 对应 package | Beta | Provider contract tests | zyblw QA 业务尚未启用 |
| OTLP/Langfuse | opentelemetry | Beta | 基数、脱敏、stub tests | 生产告警与 SLO |
| Cache/Reasoning token | core/providers/opentelemetry | Beta | OpenAI 两类协议、状态累计、指标测试 | 其他 Provider 明细语义与真实成本基线 |
| Eval/趋势门禁 | evals；仓库内 eval-cli | Experimental | snapshot、trend、release gate | 固定真实数据集与人工校准 |
| MCP client | mcp / `mcp` | Beta/Experimental | 协议与测试基础 | OAuth/server/Roots、供应链与隔离 |
| 可靠写工具 | core / `sideeffects` | Experimental | outbox/inbox/补偿抽象 | 真实 transport 与业务案例 |
| Workflow | core / `workflow` | Experimental | 边界和基础类型 | 尚无证据需要扩展 |
| Workspace/Sandbox | mcp / `workspace` | Experimental | 能力边界 | 真实 OCI 隔离与攻击测试 |
| Multimodal | core / `multimodal` | Experimental | 抽象 | 产品场景、Provider 与 eval |

## 已被真实业务验证的主线

```text
AgentApplication durable submit
 -> PostgreSQL Run/Event/Command
 -> Worker lease/heartbeat/fencing
 -> AgentRuntime
 -> bounded Context + Provider
 -> typed read-only search_articles
 -> tool evidence
 -> completed state
 -> server QaAnswerProjector
 -> verified citations + product message
```

这条路径同时验证了控制面与实际用户闭环。近期应加深它的质量、性能、故障和运维证据，不再横向扩模块。

## 关键取舍

### 单 Agent 优先

状态简单、评测明确、权限集中、延迟和成本较低。只有 eval 反复证明工具/角色分离能改善质量，且收益大于协调成本，才采用多 Agent。

### Event + Snapshot

Snapshot 快速恢复，Event 支持流、审计与诊断。完全 event sourcing 的重放/版本复杂度暂不值得；代价是必须原子提交并测试两者一致性。

### PostgreSQL command queue

命令与 Run、幂等和业务数据库紧密协调，PostgreSQL 提供事务、查询和现有运维基础。Kafka 等平台只有在真实吞吐、隔离或消费拓扑要求出现后再引入，并通过 outbox/inbox 衔接。

### Provider-neutral core

可切 Provider、可做 contract test、业务规则不复制；代价是厂商高级能力必须通过 capability/扩展表达，不能假装完全等价。

### 独立 HTTP contract package

公共兼容与内部恢复解耦；代价是维护投影和契约测试，这是值得支付的稳定性成本。

### 可选重型 Adapter

Tika、OTLP SDK、数据库和 Provider 不进入 core，减少依赖、线程和漏洞面；代价是装配显式，core 的 `app` package
只降低复杂度，不隐藏生产 fallback。

## P0-A：建立可信的开源发布基线

1. **待外部配置**：完成 Maven Central namespace、签名密钥和 GitHub release secrets 的一次性配置。
2. **待正式发布**：发布 `0.1.0`，验证签名与 Central 可解析性；本地 `0.1.0-local` 的 POM、binary、sources 和
   Scaladoc JAR 已通过。
3. **部分完成**：`zyblw-server` 已分别通过源码与 Maven-local 二进制测试；正式 Central 版本仍需重复同一门禁。
4. **部分完成**：HTTP/OpenAPI 兼容测试、格式门禁已建立；首个已发布 JVM API 基线与结构化 OpenAPI diff 仍待发布后建立。
5. **部分完成**：五分钟最小应用已通过；独立 PostgreSQL 教程仍待补充，PostgreSQL 16/pgvector CI job 已定义并在本地
   完整通过。
6. **部分完成**：安全 timeline/inspection 读模型与故障诊断文档已落地；CLI、轻量 UI 和真实事故验证仍待完成。

退出标准：陌生用户只依据 README 能完成依赖解析、最小运行和清理；维护者能按 runbook 发布、升级和回滚。

## P0-B：现有问答可运营

1. 建固定中文中医学习数据集，覆盖正常、无来源、冲突来源、注入和医疗高风险。
2. 对启用 Provider 持续测工具、citation、safety、latency、token、cost。
3. 多节点 Worker soak：lease 抢占、网络抖动、数据库重启、Provider 断流、进程 kill/recover。
4. 建 SLO/dashboard：提交、排队、运行、工具、投影、恢复和反馈。
5. 演练 PostgreSQL 备份恢复、migration upgrade 与数据保留。
6. 以 instruction fingerprint 关联 eval，并建立 cache hit、reasoning、Context 分区、质量与费用 dashboard。

退出标准：真实路径和故障恢复有可重复证据，而不只是单测绿色。

## P1：可信 RAG 生产化

- 来源许可、hash/version、chunk lineage、撤回和 tenant ACL；
- ingestion 幂等、重试、批量、失败隔离和观测；
- embedding model/version、缓存、配额和重建；
- recall、rerank、citation 与权限泄露 gate；
- 低证据时诚实拒答/降级。

退出标准：质量和权限评测通过，语料可追溯/撤回，成本可预测。

## P2：长期记忆与受控写工具

长期记忆先完成用户可见、编辑、删除、过期、来源和审计；健康信息更严格。写工具从 draft-only 开始，使用稳定幂等键、approval、outbox/inbox、补偿和审计。

## P3：Plan/Goal/Artifact/Skill、MCP、Workflow、多 Agent

1. 先把 Plan/Goal/Todo、Artifact 引用和按需 Skill 做成小型可持久化 ADT，不先拆新 artifact；
2. MCP 先解决 OAuth、server identity、Roots、allowlist、脱敏、注入与隔离；
3. Workflow 只承载确定性长流程，不强制图化简单逻辑；
4. checkpoint fork/time travel 必须隔离已发生的非幂等副作用；
5. 多 Agent 只在固定 eval 中持续胜过单 Agent且成本可接受时采用。

## 不建议投入

- 为“框架完整”实现所有 Provider 特性；
- 没有真实知识库就优化复杂 GraphRAG；
- 没有写工具业务就建通用事务编排平台；
- 用自动反思代替外部 eval 与人工反馈；
- 保存完整 chain-of-thought 作为审计；
- 提前拆独立 Agent 微服务。

## 每个里程碑所需证据

- API/schema 兼容测试；
- 故障注入与恢复报告；
- 真实数量级性能与成本；
- 固定 eval 趋势及人工校准；
- 安全/隐私审查；
- 真实用户任务完成与反馈；
- 回滚、迁移和删除路径。

证据缺失时应写“可运行/实验”，不能写“生产就绪”。
