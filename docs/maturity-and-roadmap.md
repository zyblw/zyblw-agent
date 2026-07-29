# zyblw-agent 成熟度、取舍与路线

> 状态：路线图
> 最后核验：2026-07-30
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
| Artifact | core / `artifacts` | Experimental | session/user 隔离、不可变版本、二进制不进入 State/JSON、容量与 metadata 限制、内存契约测试 | durable Adapter、保留/删除审计、Tool 与多模态接入证据 |
| 模型辅助压缩 | core / `context.llm` | Beta | evidence 校验和 eval | 多 Provider 质量/成本基线 |
| Memory | core / `memory`,`memory.llm` | Beta | Store/SPI 与治理设计 | 用户查看/删除 UX、长期质量 |
| RAG | rag、document-loaders、rerank | Beta/Experimental | `RagApplication`、同源存储层、Docling/Tika、结构切分、版本化 ingestion、hybrid retrieval、rerank、citation 与 eval | block/page lineage、parent-child、恶意 PDF/真实 OCR、线上质量 |
| PostgreSQL | postgres | Beta | Testcontainers、迁移、并发、连接耗尽测试 | 大库升级、备份恢复、性能 |
| OpenAI-compatible | providers / `integrations.openai` | Beta | stream/tool/error stub 与 smoke | 长期真实 Provider 观测 |
| Anthropic/Gemini | providers / 对应 package | Beta | Provider contract tests | zyblw QA 业务尚未启用 |
| OTLP/Langfuse | opentelemetry | Beta | 基数、脱敏、stub tests | 生产告警与 SLO |
| Cache/Reasoning token | core/providers/opentelemetry | Beta | OpenAI 两类协议、状态累计、指标测试 | 其他 Provider 明细语义与真实成本基线 |
| Eval/趋势门禁 | evals；仓库内 eval-cli | Experimental | snapshot、trend、release gate、有界多试验与 `pass@k`/`pass^k` | outcome/trajectory 分离、固定真实数据集与人工校准 |
| MCP client | mcp / `mcp` | Beta/Experimental | 协议与测试基础 | OAuth/server/Roots、供应链与隔离 |
| 可靠写工具 | core / `sideeffects` | Experimental | outbox/inbox/补偿抽象 | 真实 transport 与业务案例 |
| Workflow Graph | core + postgres / `workflow` | Experimental | 显式 nodes/edges、identity/version、启动校验、单调 checkpoint、`AllSucceeded` 取消、execution ledger/pending outcome、lease/fencing、13 个 core 测试与 5 个 PostgreSQL 16 契约 | timer/signal、子图、多 Worker soak、Inspector 与图级 eval |
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

1. **已完成**：Maven Central namespace、签名密钥和 GitHub release environment 已配置，短期 token 可独立轮换。
2. **已完成**：`0.1.0` 的 11 个 POM、binary、sources、Scaladoc JAR 和签名已发布并可从 Central 解析。
3. **已完成**：第二版 `0.2.0` 的签名制品、独立 Maven consumer、GitHub Release 与 Central 公共解析均已验证。
4. **已完成**：`zyblw-server` 已分别通过源码、Maven-local 候选和正式 Central `0.1.0` 的 PostgreSQL 门禁；`0.2.0`
   发布后下游 Central 回归仍需按 runbook 执行。
5. **部分完成**：HTTP/OpenAPI 兼容测试、格式门禁和已发布 JVM 基线已建立；结构化 OpenAPI diff 仍待自动化。
6. **部分完成**：五分钟最小应用已通过；独立 PostgreSQL 教程仍待补充，PostgreSQL 16/pgvector CI job 已定义并在本地
   完整通过。
7. **部分完成**：安全 timeline/inspection 读模型与故障诊断文档已落地；CLI、轻量 UI 和真实事故验证仍待完成。

退出标准：陌生用户只依据 README 能完成依赖解析、最小运行和清理；维护者能按 runbook 发布、升级和回滚。

## P0-B：现有问答可运营

1. 建固定中文中医学习数据集，覆盖正常、无来源、冲突来源、注入和医疗高风险。
2. 对启用 Provider 持续测工具、citation、safety、latency、token、cost。
3. 多节点 Worker soak：lease 抢占、网络抖动、数据库重启、Provider 断流、进程 kill/recover。
4. 建 SLO/dashboard：提交、排队、运行、工具、投影、恢复和反馈。
5. 演练 PostgreSQL 备份恢复、migration upgrade 与数据保留。
6. 以 instruction fingerprint 关联 eval，并建立 cache hit、reasoning、Context 分区、质量与费用 dashboard。

退出标准：真实路径和故障恢复有可重复证据，而不只是单测绿色。

## P0-C：结果优先的可靠性评测

1. **已完成 Q0**：`AgentEvalRunner.runRepeated` 对用例 × attempt 使用一个共享的有界并发 job 集合，保留确定顺序；
2. **已完成 Q0**：报告逐次成功率、至少一次成功的 `pass@k` 估算和连续全成功的 `pass^k` 估算；
3. **下一步 Q1**：评分显式区分最终 outcome、执行 trajectory、safety 与 resource，结果正确不自动证明过程安全；
4. **下一步 Q1**：把多试验低敏投影纳入趋势仓库与发布策略，补置信区间和最小样本规则；
5. **随后 Q2**：以真实失败、事故和人工分歧构建 capability/regression 数据集，定期阅读 transcript 校准 grader。

退出标准：面向用户的关键路径不再因“一次跑绿”发布；稳定性、结果、过程、安全和成本可以分别解释。

## P1-A：耐久 Workflow Graph

图工程方向可行，但优先级是执行语义而不是 Agent 数量：

1. **已完成 G1**：节点与边分离；定义在运行前验证缺失目标、不可达节点、重复目标和无访问预算循环；
2. **已完成 G1**：checkpoint 同时保存游标、不可变状态、step 和访问次数；动态 Route 只能选择已声明目标；
3. **已完成 G1**：单步 fan-out 显式采用 `AllSucceeded`，有界并发且失败会中断兄弟 Fiber，不写 join checkpoint；
4. **已完成 G2-A**：V008 `PostgresWorkflowCheckpointStore` 绑定 workflow/version/session，提供容量/checksum/JSONB
   完整性、幂等重放、单调 step 与跨 Store 暂停恢复；
5. **已完成 G2-B**：`WorkflowExecutionStore` 把节点 execution ledger、Prepared outcome、lease heartbeat/fencing 与
   checkpoint 组成一个原子提交边界；V009 PostgreSQL Adapter 支持过期 Prepared 跨 owner/generation 恢复，故障注入证明
   prepare 后、checkpoint 前失败不会重复执行节点；
6. **下一步 G3-A**：耐久 timer、外部 signal 与可查询 execution timeline，并补数据库重启、进程 kill 和多 Worker soak；
7. **随后 G3-B**：基于真实需求加入人工任务、子图或更多 fan-in policy；
8. Graph Inspector、实际路径 trace、质量/延迟/token/费用 eval 达标后，才讨论通用 Agent 节点和多 Agent 调度。

不把普通单 Agent loop 或几个顺序函数强制图化。完整当前契约见[声明式 Workflow Graph](workflow.md)。

退出标准：进程可在任一节点边界崩溃并恢复，不重复已登记的外部副作用；静态定义错误不能进入运行期；并发失败和恢复路径有
PostgreSQL Testcontainers 与故障注入证据。

## P1-B：可信 RAG 生产化

1. **已完成 R1**：来源 URI、content hash/index version、tenant ACL、乐观撤回和原子 active 发布；
2. **已完成 R1**：ingestion 幂等、Building/stage/activate、批量有界并发、失败隔离与取消传播；
3. **已完成 R1**：Embedding model/dimension 身份、租户缓存、原子配额、pgvector+FTS weighted RRF 与模型 Reranker；
4. **已完成 R2-A**：可选 Tika 与 Docling Serve v1 PDF→Markdown Adapter，Markdown 标题/表格/fenced code 感知切分，
   Unicode 有界窗口、内容寻址 chunk ID、自动 `Chunker.strategyId`；
5. **已完成 R2-A**：Recall/Precision/MRR/NDCG、citation evidence、tenant authorization、禁止片段、数值与延迟 gate；
6. **已完成 R2-A 接入收口**：`RagApplication` 固定业务主入口，内存/PostgreSQL 同源组合层保证
   `KnowledgeIndexStore & VectorStore` 指向相同 active snapshot，示例不再绕过 Loader/Indexer；
7. **下一步 R2-B**：保留 Docling JSON block/page/bbox lineage，建立 parent-child retrieval 与相邻块扩展，同时保证 ACL
   过滤仍发生在候选排名之前；
8. **随后 R2-C**：真实 Docling/OCR smoke、恶意 PDF corpus、索引构建性能/成本/质量趋势、低证据拒答门禁和保留期 Worker。

退出标准：质量和权限评测通过，语料可追溯/撤回，成本可预测。

## P1-C：Agent Harness

Harness 不是第二套 Agent Runtime，而是长任务的 Provider-neutral 支架：

1. **已有地基**：Artifact、Workspace/Sandbox、Context/Memory、Approval、Inspector 可独立组合；
2. **下一步 H1**：Goal、Plan、Todo 与按需 Skill 的小型 ADT/Store SPI；所有变更可审计、可恢复，不只写进 Prompt；
3. **随后 H2**：PostgreSQL durable Adapter、任务总预算、Artifact/Workspace 关联和受控 Skill materialization；
4. **随后 H3**：固定长任务 eval 证明 Harness 对成功率、人工介入、恢复、token 和费用的收益；
5. 在两个以上真实独立消费者证明依赖或生命周期边界前，不拆新的 Harness artifact。

完整边界见 [ADR 0016](architecture/0016-agent-application-runtime.md)。

## P2：长期记忆与受控写工具

长期记忆先完成用户可见、编辑、删除、过期、来源和审计；健康信息更严格。写工具从 draft-only 开始，使用稳定幂等键、approval、outbox/inbox、补偿和审计。

## P3：互操作与多 Agent

1. MCP 先解决 OAuth、server identity、Roots、allowlist、脱敏、注入与隔离；
2. checkpoint fork/time travel 必须隔离已发生的非幂等副作用；
3. A2A 只用于不透明 Agent 应用间的任务/消息/Artifact 互操作，不替代 MCP、内部函数或 Workflow；
4. 多 Agent 只在固定 eval 中持续胜过单 Agent且成本可接受时采用，并复用已经验证的 Workflow Graph 控制面；
5. 通用 A2A server、Agent marketplace 和大型 Graph Studio 均晚于 Workflow G2-B、Harness H1 与 outcome eval。

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
