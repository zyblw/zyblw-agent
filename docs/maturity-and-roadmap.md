# zyblw-agent 成熟度、取舍与路线

> 状态：路线图
> 最后核验：2026-08-22
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
| 类型化工具与策略 | core / `tools`,`guardrails` | Foundation | schema、allowlist、风险和结果测试；v5 durable plan 冻结工具契约摘要与单调审批要求，恢复漂移 fail-closed | policy 管理 UX 与历史计划人工重校验流程 |
| 管理面与运维控制台 | core / `admin`；zio-http；agent-dashboard | Beta | scope fail-closed、CAS 覆盖写入与审计、keyset 游标（含亚毫秒回归）、能力探测、七个面板、无真实凭据的 Playwright 浏览器契约 | SSE 调试器、跨 Run 成本聚合、嵌入式部署 |
| 模型治理与运行时切换 | core / `admin`,`core`；providers | Beta | 覆盖到达真实请求、目录 fail-closed 校验、探活不泄漏凭据、HTTP 错误稳定分类、计费口径、模型页浏览器契约 | 按 Agent 粒度覆盖、Provider 自动降级链 |
| 单 Agent loop | core / `runtime` | Foundation | budget、工具、审批、恢复、遥测测试 | 长运行与大负载故障注入 |
| durable command worker | core / `app`,`scheduler`,`runtime` | Foundation | 有界多 Run lane、同 Run 串行、claim/lease/heartbeat/fencing、三实例 drain、中断重领、独立 JVM `SIGKILL` + 同实例 PostgreSQL restart 后接管、正式 Runtime 3 Worker/6 lane/120 Run 有界 soak 与低敏 P95 报告 | 长时多节点 soak、节点/主备丢失、容量曲线、生产 SLO/dashboard |
| HTTP v1 公共协议 | zio-http / `http.contract`,`http` | Foundation/Beta | 独立 DTO、Endpoint、OpenAPI、route test | 客户端 SDK、兼容升级演练 |
| Run Inspector / Timeline | core + zio-http / `inspection` | Foundation/Beta | 低敏投影、分页、授权、结构诊断与泄漏测试；`IncidentPackCliApp` 可从文件/stdin 再编码并查泄漏 | UI、筛选导出、真实事故验证 |
| Context | core / `context` | Beta | 有界装配、确定性压缩测试 | 真实长会话数据集 |
| Artifact | core / `artifacts`；postgres | Beta | session/user 隔离、不可变版本、容量与 metadata 限制；内存与 `PostgresArtifactStore`（V011）共用删除/过期/审计 conformance；Goal/Plan/Todo 保存有界 typed reference，Context 不加载正文；`ImageArtifact` + tenant grant | 真实对象存储 Adapter、线上保留期与容量演练 |
| 模型辅助压缩 | core / `context.llm` | Beta | evidence 校验和 eval | 多 Provider 质量/成本基线 |
| Memory | core / `memory`,`memory.llm` | Beta | Store/SPI 与治理设计 | 用户查看/删除 UX、长期质量 |
| RAG | rag、document-loaders、rerank | Beta | `RagApplication`、目录 Source、`loadById` 重建、Docling Markdown+JSON、提取质量门禁、Tika→OCR→VLM 级联、可选逐页视觉转录（JPEG 经 Artifact 绑定）、page/bbox lineage、cl100k 结构切分、版本摄取、hybrid/vector/lexical/phrase + ACL 后过滤、rerank/谱系扩展、state v7 citation、稳定 `/api/v1/knowledge/**`、`KnowledgeQaHost`、book-corpus eval；OCR 抽出注入按检索资料拦截 | 真实 Tesseract/Docling smoke、对象存储 Source resolver、容量与线上质量 |
| PostgreSQL | postgres | Beta | Testcontainers、迁移、并发、连接耗尽、pause/recover、command/Workflow 同实例 restart、Worker JVM `SIGKILL` 恢复、pg_dump/restore | 大库升级、主备切换、节点级故障、性能/RTO |
| OpenAI-compatible | providers / `integrations.openai` | Beta | stream/tool/error stub 与 smoke | 长期真实 Provider 观测 |
| Anthropic/Gemini | providers / 对应 package | Beta | Provider contract tests | zyblw QA 业务尚未启用 |
| OTLP/Langfuse | opentelemetry | Beta | 基数、脱敏、stub tests | 生产告警与 SLO |
| Cache/Reasoning token | core/providers/opentelemetry | Beta | OpenAI 两类协议、状态累计、指标测试 | 其他 Provider 明细语义与真实成本基线 |
| 成本估算 | core / `core` | Beta | 部署声明价格表、缓存/推理 token 不重复计费、混币拒绝 | 框架不内置厂商价目表，价格由部署提供 |
| Eval/趋势门禁 | evals；仓库内 eval-cli | Experimental | 四轴证据、snapshot、trend、release gate、有界多试验、Wilson 区间；Harness 同 case/attempt 成对门禁与独立趋势 | 固定真实数据集、事故回放与人工校准 |
| MCP client | mcp / `mcp` | Beta/Experimental | 默认锁定并测试 `2025-11-25`；协商到 `2026-07-28` 仍 fail-closed。专用 `Mcp2026Client` + `Mcp2026HttpTransport` 覆盖 discover/`_meta`/POST-only header、MRTR 重试、`subscriptions/listen` 类型与 issuer/CIMD SSRF 契约，尚未替换默认客户端 | 完整 OAuth 交换、长连接 listen SSE、官方 SDK 互操作与攻击矩阵通过后才能删除 2025 transport 并把 2026 标为 Supported |
| 可靠写工具 | core / `sideeffects` | Experimental | outbox/inbox/补偿抽象 | 真实 transport 与业务案例 |
| Workflow Graph | core + postgres / `workflow` | Experimental | 显式图与 checkpoint、execution ledger/pending outcome、lease/fencing、低敏 timeline/wake queue、durable wait/signal；真库组合故障、独立 JVM 双 generation 接管及 3 Worker/126 Run 有界 soak | 数据库主备 failover、节点丢失、长时 soak、人工任务、子图、完整 Inspector 与图级 eval |
| Agent Harness | core + postgres / `harness` | Experimental | Goal/Plan/Todo/Skill/Interaction/ArtifactReference；跨 Run 预算预留/结算、Start 原子准入、终态 Reconciler；同 case/attempt 成对 Eval | 真实脱敏长任务数据、人工 grader 校准、长期对账与容量证据 |
| Workspace/Sandbox | mcp / `workspace` | Experimental | inspect/apply/rollback、路径穿越与 symlink 拒绝、OCI 交互 shell deny | 真实 OCI 攻击矩阵、archive bomb 与供应链测试 |

已删除、不要再当作当前能力：`com.zyblw.agent.multimodal` 与 `com.zyblw.agent.knowledge.KnowledgeGraph`。它们没有
Provider、消费者或评测；未来若重建必须带真实场景和测试，而不是恢复空壳。

## 0.6.0 收口状态与下一批必须完成项

`0.5.0` 已经收口的范围：管理面授权边界、能力探测、keyset 目录、CAS 配置覆盖与审计历史、Run SSE 调试器、模型目录
fail-closed 校验与运行时切换、脱敏 HTTP 失败分类、部署声明价目表的成本估算，以及七面板控制台的浏览器契约。核心
`V002` 只做加法，业务 HTTP v1、workflow outcome v2 和 0.4 知识 schema 未变。

**明确不在 0.5 范围内、已知仍缺口的项**（按下一步优先级排列，详细验收条件见后续各节）：

| 缺口 | 影响面 | 归属章节 |
|---|---|---|
| ModelCall ledger 已落地但生产恢复证据尚未收口 | 排障、恢复语义、Eval trajectory | [P0-D](#p0-d模型调用耐久与可重建) Kernel 确定性切片与共享 Store conformance 已落地；生产 soak 与 Eval Replayable 轨迹仍待 |
| 多 Worker 长时 soak、部署节点丢失、数据库主备切换与容量曲线 | 本地 Docker 主备演练脚本已落地（`verified_local`）；生产 failover/SLO 仍 `deferred` | P0-B、P1-A G3-A2c |
| 固定真实数据集、事故回放与人工 grader 校准 | 维护者双审公开 fixture 与文件后端 smoke 已落地；领域专家校准与宿主趋势基线仍 `deferred` | P0-C Q2、P1-C H3-C |
| 真实 OCR、恶意 PDF corpus、tokenizer-aligned chunking、低证据拒答门禁 | 低证据拒答评测门禁与 registry 字节上限已落地；真实 OCR/恶意 PDF corpus 仍缺 | P1-B R2-C |
| 管理面跨 Goal/Agent 成本报表、按 Agent 粒度模型覆盖、Provider 自动降级链 | 管理面运营深度 | 管理面矩阵行 |
| Harness 的真实业务收益证据 | ADT、PostgreSQL、Steering、Artifact、任务预算与成对 Eval 基础设施已落地；仍缺脱敏长任务数据和人工校准 | P1-C H3-C |
| MCP OAuth、server identity、Roots、供应链与真实 OCI 隔离 | 互操作与沙箱安全 | P3 |
| 结构化 OpenAPI diff 与历史 artifact 二进制 diff 自动化 | 兼容性门禁仍依赖人工判断 | P0-A 第 5 项 |
| 独立 PostgreSQL 教程、Inspector CLI/UI、真实事故验证 | 新用户与值班体验 | P0-A 第 6、7 项 |

这些缺口都不是“再加一个模块”能解决的，它们需要的是运行证据。在拿到证据之前，相关能力在本页保持 Beta 或
Experimental，README 与其他文档不得升级它们的措辞。

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
3. **已完成**：`0.2.0` 与兼容 patch `0.2.1` 的签名制品、独立 Maven consumer、GitHub Release 与 Central
   公共解析均已验证；`0.2.1` 还验证了 tag/main/CHANGELOG/升级指南来源一致性门禁。
4. **已完成**：`zyblw-server` 已分别通过源码、Maven-local 候选和正式 Central `0.1.0` 的 PostgreSQL 门禁；`0.2.0`
   发布后下游 Central 回归仍需按 runbook 执行。
5. **部分完成**：HTTP/OpenAPI 兼容测试、格式门禁、兼容面文档和 tag/main/CHANGELOG/升级指南一致性门禁已建立；
   结构化 OpenAPI diff 与真实历史 artifact 的二进制 diff 仍待自动化。
6. **已转向生产参考**：无数据库五分钟 Quickstart 已删除；权威入口是 `ProductionSupportHost` 与
  `docs/postgres-quickstart.md`。PostgreSQL 16/pgvector CI job 已定义；Docker/VM 包在 `deploy/docker/`。
  Wave 0 宿主实测仍待。
7. **部分完成**：安全 timeline/inspection 读模型与故障诊断文档已落地；CLI、轻量 UI 和真实事故验证仍待完成。

退出标准：陌生用户只依据 README 能完成依赖解析、最小运行和清理；维护者能按 runbook 发布、升级和回滚。

## P0-B：现有问答可运营

1. 建固定中文中医学习数据集，覆盖正常、无来源、冲突来源、注入和医疗高风险。
2. 对启用 Provider 持续测工具、citation、safety、latency、token、cost。
3. **短时协议证据已完成**：三 Worker/六 lane/48 Run 排他 drain、Fiber 中断后 generation 重领、Worker 消失 + 数据库 pause/recover 组合故障后代际接管、command Worker 独立 JVM `SIGKILL` + 同实例 PostgreSQL restart 后 generation 2 / attempt 2 接管，以及 Workflow Worker 在相同组合故障后 wake/execution 双 generation 2 接管；正式 Start/Runtime 有界 soak 以 3 Worker/6 lane 完成 120 Run；Workflow wake 有界 soak 以 3 个独立 Store/Worker 完成 126 Run；两者均无异常重领并输出低敏 P95；
   下一步在真实部署做数小时 soak、数据库主备切换、Provider 断流和 Pod/VM 节点丢失。
4. 基于 `queueSnapshot` 建 SLO/dashboard：提交、排队、运行、工具、投影、恢复和反馈。
5. 演练 PostgreSQL 备份恢复、migration upgrade 与数据保留。
6. 以 instruction fingerprint 关联 eval，并建立 cache hit、reasoning、Context 分区、质量与费用 dashboard。

退出标准：真实路径和故障恢复有可重复证据，而不只是单测绿色。

## P0-C：结果优先的可靠性评测

1. **已完成 Q0**：`AgentEvalRunner.runRepeated` 对用例 × attempt 使用一个共享的有界并发 job 集合，保留确定顺序；
2. **已完成 Q0**：报告逐次成功率、至少一次成功的 `pass@k` 估算和连续全成功的 `pass^k` 估算；
3. **已完成 Q1**：`EvalAxis` 显式区分 outcome、trajectory、safety 与 resource；禁止工具、重复副作用、轨迹重建和 Inspector 脱敏分别门禁，结果正确不自动证明过程安全；
4. **已完成 Q1**：`AgentReliability` 独立低敏 kind 纳入趋势仓库与发布策略；Wilson 95% 区间、最小样本、观察成功率分别门禁，V007 扩展 PostgreSQL kind CHECK；
5. **已完成 Q2 治理地基**：`AgentEvalDataset` 用来源类别、change/owner/reviewer、审查时间与确定性 SHA-256 绑定整批用例；草稿、版本漂移、重复 ID 或审查后篡改在任何 Provider/工具调用前 fail-closed；
6. **下一步 Q2 数据闭环**：以真实失败、事故和人工分歧持续扩充 capability/regression 数据集，执行双人标注或分歧仲裁，并定期阅读 transcript 校准 grader。治理清单不能替代真实样本与人工判断。

退出标准：面向用户的关键路径不再因“一次跑绿”发布；稳定性、结果、过程、安全和成本可以分别解释。

## P0-D：模型调用耐久与可重建

这是 [ADR-0018](architecture/0018-next-generation-runtime-kernel.md) 定义的下一代 Kernel 第一刀。详细步骤见 [开发手册](architecture/next-generation-runtime.md)。

1. **已完成**：ModelCall `Dispatched → Succeeded|Failed|Unknown`；与 `RunStore.commit` 同一事务写入 `model_call_executions`（V004）。
2. **已完成**：`CanonicalModelRequest` 在 `CapturePolicy.Replayable` 下与 `ScriptedChatModel.recordedRequests` 深比较相等。
3. **已完成**：TX1 之后崩溃恢复为 Unknown，不自动重放 Provider；公共 Inspector / HTTP 投影无 prompt。
4. **已完成**：`ToolRetryPolicy`（在线 429/timeout）与 `ToolRecoveryPolicy`（崩溃重放）拆分；未改工具账本表。
5. **已完成**：确定性 crash matrix（Disabled、settlement 失败、Complete 窗口、lease lost、Context 后预算保留）；Postgres stale fenced ModelCall 拒绝（需集成开关）。
6. **已完成修复**：in-memory `RunStore` 以单一 `Ref.Synchronized` 原子更新 state/event/tool/model-call 数据；ModelCall 冲突失败后不再留下已推进状态或事件。
7. **已完成硬化**：同一套 `RunStore` conformance 在内存与真实 PostgreSQL 16 上验证 ModelCall 冲突原子回滚、事件分页/幂等/级联删除、工具账本身份冲突、EventId 跨 Run 漂移拒绝、`save` 游标漂移/跨批 sequence gap 的原子回滚、同 version/sequence 并发提交唯一胜者，以及负 sequence/非法游标/超限页面拒绝。
8. **已完成持久化信封校验**：PostgreSQL 读取 `AgentState`、`PersistedAgentEvent`、Tool ledger 与 ModelCall ledger 时交叉核对关系列和 JSON 负载；真实数据库篡改测试证明 status/sequence 漂移不会被静默接受。
9. **部分完成、继续推进**：command 与 Workflow Worker 的本机独立 JVM kill/reclaim 均已有一键证据；下一步仍是生产 soak、部署节点丢失与数据库 failover（P0-B）；Eval 使用 Replayable 轨迹。
10. **不在本项**：Conversation Tree、Harness Goal/Plan/Skill、独立 Trajectory 权威、重写 Tool ledger。

退出标准（第一刀）：Fake Model 捕获的请求与从耐久账本深比较相等；TX1 之后崩溃表示为 Unknown；公共 Inspector / Telemetry 仍无 prompt 正文。

## P1-A：耐久 Workflow Graph

图工程方向可行，但优先级是执行语义而不是 Agent 数量：

1. **已完成 G1**：节点与边分离；定义在运行前验证缺失目标、不可达节点、重复目标和无访问预算循环；
2. **已完成 G1**：checkpoint 同时保存游标、不可变状态、step 和访问次数；动态 Route 只能选择已声明目标；
3. **已完成 G1**：单步 fan-out 显式采用 `AllSucceeded`，有界并发且失败会中断兄弟 Fiber，不写 join checkpoint；
4. **已完成 G2-A**：`PostgresWorkflowCheckpointStore` 绑定 workflow/version/session，提供容量/checksum/JSONB
   完整性、幂等重放、单调 step 与跨 Store 暂停恢复；
5. **已完成 G2-B**：`WorkflowExecutionStore` 把节点 execution ledger、Prepared outcome、lease heartbeat/fencing 与
   checkpoint 组成一个原子提交边界；PostgreSQL Adapter 支持过期 Prepared 跨 owner/generation 恢复，故障注入证明
   prepare 后、checkpoint 前失败不会重复执行节点；
6. **已完成 G3-A1**：内存与 PostgreSQL `WorkflowExecutionStore.timeline` 使用 `(step,nodeId)` 排他复合游标稳定分页；
   低敏投影不暴露状态、pending outcome 或 lease token，并保留 generation/owner/时间戳用于抢占诊断；
7. **已完成 G3-A2a**：`NodeOutcome.Awaiting`、绝对 deadline、typed wakeup、wait 注册/消费与 checkpoint 原子提交；
   内存/PostgreSQL Store 实现 signal ID/payload 去重、数据库时钟裁决超时竞态和有界 `expireDue`；
8. **已完成 G3-A2b**：`WorkflowWakeWorker` 以 ZIO Scope 监督恢复与 heartbeat；Signaled/TimedOut wait 行直接作为
   durable wake command，claim 使用 owner/token/generation/expiry fencing。PostgreSQL 以 `FOR UPDATE SKIP LOCKED` 和数据库
   时钟实现排他领取，消费 wait 与 checkpoint/execution 原子提交；真实双 Store 测试覆盖唯一领取、租约过期重领与旧 fence 拒绝；
9. **已完成 G3-A2c 本机可靠性基线**：wake Worker 消失与 PostgreSQL pause/recover 后由新 generation 接管；独立 JVM `SIGKILL` + PostgreSQL restart 证明 wake/execution 双 generation 2 收敛；三个独立 Store/Worker 的 126 Run 有界 soak 又证明 claim/terminal P95、并发和零异常重领。下一步仍是部署环境的数据库主备切换、节点丢失、长时间 soak，并校准生产 backlog/claim latency/lease-lost/恢复时延 SLO；
10. **已完成 G3-A2d 低敏运维面**：`wakeQueueSnapshot` 由内存/PostgreSQL 共享，按 workflow/version 返回 Pending/Due、dispatchable/leased/expired 与最早年龄；只读且不含业务身份/正文，soak 直接采样并门禁；
11. **随后 G3-B**：基于真实需求加入人工任务、子图或更多 fan-in policy；
12. Graph Inspector、实际路径 trace、质量/延迟/token/费用 eval 达标后，才讨论通用 Agent 节点和多 Agent 调度。

不把普通单 Agent loop 或几个顺序函数强制图化。完整当前契约见[声明式 Workflow Graph](workflow.md)。

退出标准：进程可在任一节点边界崩溃并恢复，不重复已登记的外部副作用；静态定义错误不能进入运行期；并发失败和恢复路径有
PostgreSQL Testcontainers 与故障注入证据。

## P1-B：可信 RAG 生产化

1. **已完成 R1**：来源 URI、content hash/index version、tenant ACL、乐观撤回和原子 active 发布；
2. **已完成 R1**：ingestion 幂等、Building/stage/activate、批量有界并发、失败隔离与取消传播；
3. **已完成 R1**：Embedding model/dimension 身份、租户缓存、原子配额、pgvector+FTS weighted RRF 与模型 Reranker；
4. **已完成 R2-A**：可选 Tika 与 Docling Serve v1 PDF→Markdown+JSON Adapter，Markdown 与无损 structure 切分，
   Unicode 有界窗口、内容寻址 chunk ID、自动 `Chunker.strategyId`；
5. **已完成 R2-A**：Recall/Precision/MRR/NDCG、citation evidence、tenant authorization、禁止片段、数值与延迟 gate；
6. **已完成 R2-A 接入收口**：`RagApplication` 固定业务主入口，内存/PostgreSQL 同源组合层保证
   `KnowledgeIndexStore & VectorStore` 指向相同 active snapshot，示例不再绕过 Loader/Indexer；
7. **已完成 R2-B 契约层**：保留 Docling JSON block/page/bbox lineage，`DocumentStructureChunker` 生成 parent/neighbor，
   0.4 单文件 pgvector 基线随 active snapshot 原子发布；rerank 后相邻/同父级扩展重新应用 tenant ACL 和数量上限；
8. **下一步 R2-C**：真实 Docling/OCR smoke、恶意 PDF corpus、与 Embedding tokenizer 对齐的切分、索引构建性能/成本/质量趋势、
   低证据拒答门禁和保留期 Worker。提取质量门禁、可回放解析级联和可选逐页 VLM 转录已作为契约落地，但不能代替上述运行证据。
9. **随后 R2-D**：在视觉复杂 corpus 证明布局/OCR 仍不足后，再引入 late-interaction 页面检索 Adapter。整页 VLM 转录只是摄取回退，不是检索替换。

退出标准：质量和权限评测通过，语料可追溯/撤回，成本可预测。

## P1-C：Agent Harness

Harness 不是第二套 Agent Runtime，而是长任务的 Provider-neutral 支架：

1. **已有地基**：Artifact、Workspace/Sandbox、Context/Memory、Approval、Inspector 可独立组合；
2. **已完成 H1 第一刀**：Goal、Plan、Todo 与按需 Skill 的小型 ADT/`HarnessStore` SPI（内存 CAS）；经 `HarnessContextContributor` 注入上下文，不改 Kernel。Goal Active 不自动开跑；Plan 不是权限；Skill 不能授工具、不能升为 System。
3. **已完成 H2 Adapter**：`PostgresHarnessStore` 与 Flyway `V005`；CAS、Goal 外键、Skill 指纹冲突与内存实现一致。
4. **已完成 Steering/FollowUp 第一刀**：追加式 `InteractionInput`，与 Cancel/Recover/Approval/Retry 分离；Flyway `V006`。
5. **已完成 H3-A 查询边界**：Interaction Store 以排他 `beforeSequence` 倒序取最近页、最多 512 条并按 sequence 升序交付；Contributor 只向 Store 请求最近 16 条，不再加载 Goal 全历史。内存与 PostgreSQL 16 测试覆盖分页和非法 limit。
6. **下一步 H3-A 生命周期**：在明确租户合规窗口和 Goal 删除入口后补交互归档/删除策略；当前只保证随 Goal 外键级联删除，不增加无业务依据的自动 TTL。
7. **已完成 H3-B**：Goal/Plan/Todo 保存有界 typed `ArtifactReference`；引用不含正文、二进制和私有 metadata，也不授予读取权限。`harness@2` 只把不含 scope 的低敏引用元数据投影到 Context；Flyway `V008` 与真实 PostgreSQL 16 往返测试已通过。
8. **已完成 H3-C 基础设施**：`HarnessEvalRunner` 在同一已审查 dataset/case/attempt 上成对比较 baseline 与 Harness，衡量四轴、Wilson 可靠性、人工介入以及 latency/token/cost 倍率；安全失败不可被收益抵消。低敏 `HarnessComparison` 趋势通过 V009 与其它 kind 隔离。
9. **已完成 H3-D 任务预算**：不可变 `GoalBudgetPolicy` + 稳定 Run 预留/结算/释放；内存 `Ref.Synchronized` 与 PostgreSQL V010 行锁语义一致，并由同一套 Harness budget conformance 防止 Adapter 漂移。费用总限要求 Run 明确费用额度；超出预留的真实 usage 记为 `Exceeded`，不能回滚隐藏。`HarnessCommandService` 将预留与 Created/首事件/Start/dispatcher 同事务提交；`HarnessBudgetReconciler` 有界循环扫描并只结算耐久终态，非终态/缺失 Run 不自动释放。
10. **下一步 H3-C 业务证据**：用真实脱敏长任务样本和人工校准执行成对试验；只有持续通过显式策略，才能声称 Harness 带来收益。基础设施的合成测试不构成产品效果证据。
11. 在两个以上真实独立消费者证明依赖或生命周期边界前，不拆新的 Harness artifact。

完整边界见 [ADR 0016](architecture/0016-agent-application-runtime.md)。

## P2：长期记忆与受控写工具

长期记忆先完成用户可见、编辑、删除、过期、来源和审计；健康信息更严格。后续可以吸收 LlamaIndex/Letta 的
有界 memory block、稳定 label、priority 和可移植导出，但 memory 内容始终是数据，不能授予工具或成为 System 权威。
模型产生的记忆更新先作为 proposal，由宿主策略验证 owner/purpose/consent/conflict/retention 后提交。写工具从 draft-only
开始，使用稳定幂等键、approval、outbox/inbox、补偿和审计。

## P3：互操作与多 Agent

1. MCP 先解决 OAuth、server identity、Roots、allowlist、脱敏、注入与隔离；
2. checkpoint fork/time travel 必须隔离已发生的非幂等副作用；
3. A2A 只用于不透明 Agent 应用间的任务/消息/Artifact 互操作，不替代 MCP、内部函数或 Workflow。`A2AAgentCard` 已能 fail-closed 解析 1.0 Card（HTTPS、securitySchemes、禁止私网），但还不是 A2A client/server；
4. 多 Agent 只在固定 eval 中持续胜过单 Agent且成本可接受时采用，并复用已经验证的 Workflow Graph 控制面；
5. 通用 A2A server、Agent marketplace 和大型 Graph Studio 均晚于 Workflow G2-B、Harness H1 与 outcome eval。

## 外部框架吸收任务

完整取舍矩阵见[下一代 Runtime 开发手册 §5](architecture/next-generation-runtime.md#adoption-matrix)；OpenAI Codex 五项决策与 Wave 0–3 实施顺序见 [ADR-0019](architecture/0019-typed-extensions-and-constrained-execution.md)。路线图只保留有明确交付物的吸收项：

| 阶段 | 借鉴来源 | 候选交付物 | 进入条件 |
|---|---|---|---|
| Wave 0 / P0 hardening | LangGraph、PydanticAI、Microsoft Agent Framework | 共用 Store conformance；pending-write/step identity 故障测试；bounded snapshot 与持久化信封校验 | 不新增公共抽象，先关闭当前事务、篡改检测与恢复缺口 |
| Wave 1 安全与执行边界 | OpenAI Codex | ~~`ApprovalSubject`~~ **已落地**；~~Typed Extension API~~ **已落地**；~~`ExecutionEnvironment`/`PermissionProfile` 第一刀~~ **已落地** | Wave 2 ContextSection / SkillCatalog / AgentProtocol |
| Wave 2 上下文与协议 | OpenAI Codex、DeepSeek Harness | ~~`ContextSection` 安全快照/受信差量~~ **已落地**；~~SkillProvider 目录与按需 load~~ **已落地**；~~AgentProtocol 分级~~ **已落地声明**；~~每调用 ModelSettings 摘要与连续运行漂移门禁~~ **已落地** | Wave 3 仍维持 DEFER；无状态 Provider 禁止启用 delta，Skill body 只能由宿主显式选择并进入 Retrieval |
| P1 Composition / DX | LLM4S、PydanticAI、Microsoft Agent Framework | Provider capability matrix；adapter contract suite；可读 composition manifest；typed capability bundle | 至少一个现有 Provider/业务消费者证明需要，Kernel 无修改 |
| P1 Eval | PydanticAI、LangChain/LangGraph | 可版本化 Eval fixture；outcome/trajectory/safety/resource 独立报告；多次试验与置信区间 | 固定数据集、确定性 grader 与人工校准样本存在 |
| P2 Harness / Memory | LlamaIndex、Letta、MetaGPT | Interaction 有界分页；governed memory block；typed Artifact/SOP 合同；用户导出/删除 | owner、ACL、来源、保留期、预算和低敏投影全部定义 |
| P3 Replay | LangGraph、Pi | Inspection、recorded Model Replay、隔离 Fork | 不重放真实非幂等 Tool；新 Run/budget/lease/ledger |
| P4 Orchestration | OpenAI Agents SDK、MetaGPT、Semantic Kernel/Agent Framework | agent-as-tool 与 handoff 合同；typed message/artifact；取消/预算传播 | 固定 Eval 持续胜过单 Agent 基线且成本可接受 |
| P4 Environment | Pig computer-use | screenshot/action artifact、machine lease、human yield/resume Adapter | 有真实 Windows/GUI 消费者和 sandbox/approval/attack eval |

每个候选项仍需通过新抽象 admission test：拥有哪份状态、维护什么不变量、处理何种失败、是否改变权限边界、如何测试。
来源框架的 API 流行度或功能数量本身不是进入理由。

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
