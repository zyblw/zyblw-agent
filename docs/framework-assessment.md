# zyblw-agent 能力审计、框架对照与演进判断

> 状态：当前审计
> 最后核验：2026-09-15
> 事实来源：当前源码、测试、构建、迁移、发布工作流，以及文末列出的官方框架资料
> 演进排序的权威来源：[ADR-0019](architecture/0019-typed-extensions-and-constrained-execution.md) 的 Wave 0–3；本文用于能力现状与竞品对照，不另立路线

本文回答四个问题：

1. `zyblw-agent` 现在是否已经能承担真实 Agent 业务；
2. 它相对主流框架的优势和不足是什么；
3. 哪些前沿能力值得吸收，哪些不应照搬；
4. 下一阶段应该用什么证据决定开发，而不是靠模块数量判断“完整”。

## 一、结论先行

当前项目不是一个玩具型 tool-calling wrapper。它已经拥有单 Agent 的完整控制循环、类型化工具、硬预算、审批暂停、
耐久状态、命令队列、worker lease/fencing、故障恢复、RAG 引用、评测、Provider 契约、PostgreSQL 和 ZIO HTTP。
这组能力足以支撑 `zyblw-server` 的可信知识问答和受控业务工具。

但它也还不是经过大规模生产证明的通用平台。完成度最高的是“正确、安全、可恢复地执行一次 Agent Run”；完成度较弱的是：

- 完整生产参考宿主，而不是无数据库五分钟路径；
- 真实长会话下的 Context 质量与 Prompt Cache 成本证据；
- 一等的计划、目标、任务清单与按需 Skill；Artifact 已有 PostgreSQL 耐久 Adapter，但对象存储、线上保留期和真实业务治理仍未闭环；
- 跨版本数据库/JSON/API 兼容演练；
- trace viewer、调试 UI、客户端 SDK 和独立外部用户反馈；
- 多节点 soak、容量模型、SLO、备份恢复与真实攻击测试。

因此，项目当前最重要的动作不是继续拆模块或增加多 Agent 名词，而是让已有单 Agent 主线更易用、更可测、更可运营。

## 当前上线门禁判定

### 判定

`zyblw-agent 0.9.0` 空库基线的**单 Agent 耐久运行主线可以作为受限生产的框架依赖**（本地用 sibling 源码验证；Maven Central 发布是后续步骤）：类型化工具、预算、审批、命令队列、
lease/fencing、PostgreSQL、RAG 引用和低敏观测已经形成可信地基。它不应被表述为“所有能力通用生产就绪”。

当前合理发布方式是：本地/迭代吃源码；生产在 `0.9.0` Central 之后固定精确坐标。只开放单 Agent、只读 RAG 和已验证的低风险工具（含可选 `web_search`），从白名单/小流量开始。
多 Agent、自动写入、任意 MCP、长期记忆和动态图编排不得随核心主线一起默认启用。

### 本次工作树证据

| 证据 | 结果 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| `sbt -batch 'scalafmtCheckAll;scalafmtSbtCheck;testFull'` | pass | 全模块格式、确定性单元和契约基线可编译、可执行 | 真实 Provider、数据库主备、长时负载 |
| `RUN_POSTGRES_INTEGRATION=1 sbt -DRUN_POSTGRES_INTEGRATION=1 -batch postgres/testFull` | pass，105/105 | PostgreSQL 16/Testcontainers 的迁移、并发、事务、RAG ACL、embedding 身份 fail-closed、恢复契约 | 生产数据量、跨可用区故障、RPO/RTO |
| 259 个主 Scala 源码文件、186 个测试 Scala 源码文件 | 事实 | 不是示例级代码库，测试投入较高 | 文件数量和测试数量本身不等于成熟度 |
| `AgentRuntimeDriver.scala` 2,053 行、`PostgresWorkflowCheckpointStore.scala` 1,879 行 | 风险信号 | Driver 已与纯 Kernel 分离，但效果编排仍集中、需要按变化原因继续收口 | 不能仅凭行数判定设计错误 |

发布候选仍必须补充精确 Central 制品的独立 consumer、真实 Provider 小额度 smoke、业务固定数据集、部署环境恢复和
生产观测证据。测试通过只说明当前已编码契约成立，不授予未测试能力更高成熟度。

### 上线前必须收口的框架事项

| 优先级 | 事项 | 验收条件 |
|---|---|---|
| P0 | 发布契约与文档消除版本漂移 | README、成熟度、生产基线、迁移说明只描述当前 0.9 空库基线；不保留旧安装入口；链接与命令在 CI 校验 |
| P0 | 生产故障与容量证据 | 在真实部署完成节点丢失、数据库主备切换、滚动发布、数小时/数天 soak；给出队列、恢复、DB pool、内存和成本的容量曲线与 SLO |
| P0 | 真实 Provider/RAG 业务门禁 | 固定模型、Prompt、索引和数据集版本；正常、无证据、冲突、注入、越权、超时、429/5xx 都有结果/轨迹/安全/资源四轴门禁 |
| P0 | 宿主权限契约 | 明确框架只执行 `RunContext` 中宿主授予的 tenant/user/scopes；下游必须证明这些值来自认证与授权服务，不能硬编码或从模型参数取得 |
| P1 | 大文件按真实职责收口 | 先加 characterization/conformance test，再把 Runtime 阶段、SQL 映射/查询和诊断投影按变化原因拆开；不为降低行数制造单实现接口 |
| P1 | 运维闭环 | Run/队列/成本/RAG/Provider dashboard、低敏日志、告警阈值、值班负责人、止损动作和事故回放可执行 |
| P1 | RAG 摄取生产化 | 真实 OCR/Docling smoke、恶意与畸形 PDF corpus、对象存储 Source resolver、保留/撤回 Worker、领域召回与引用趋势通过 |
| P2 | 生态与易用性 | 生产最小示例、独立下游反馈、兼容 diff、客户端 SDK/CLI 按真实消费者需求增加 |

### 与业务平台的推荐组合

```text
产品事实/不可变 revision/asset
        -> 同事务 outbox
        -> 平台知识投影 Worker
        -> RagApplication（派生索引，可重建，绝不反向成为业务事实源）

已认证用户 -> 平台 AuthorizationService -> RunContext(tenant,user,scopes)
        -> AgentApplication durable submit
        -> 只读 Tool / RAG
        -> AgentState citations + retrievalEvidence
        -> 平台 AnswerProjector 再校验引用、安全与公开 URL
```

这是合适的耦合：业务仓库拥有身份、权限、内容和产品消息；框架拥有运行、工具、索引和恢复协议；两者只通过发布制品、
窄端口和版本化 DTO 连接。不要让 Agent 直接写业务表，也不要让平台复制第二套 Runtime、向量索引或 Python Agent 服务。
需要特别注意：框架的 ACL 只能保证“按宿主传入的 scope 过滤”，不能修正宿主把 private 内容错误映射为公共 scope 的问题。

## 二、当前能力的真实分层

### 1. 已形成闭环的基础

- `AgentDefinition`、`AgentState`、Event、Command 和 typed `AgentError` 是明确的领域模型。
- `AgentRuntime` 是唯一循环；模型只提出文本或工具调用，Runtime 才能执行。
- `RunLimits` 同时限制步骤、模型调用、工具调用、重复动作、输入/输出/总 token、费用和 wall-clock。
- 工具具有输入/输出 Schema、风险、scope、副作用、并行冲突、超时、重试和审批语义。
- 工具名称在注册阶段强制唯一；重复名称不会因集合顺序而静默替换实现。
- `InstructionSet` 将稳定指令分成 System/Developer 块，逐块版本化，并生成不泄漏正文的稳定 fingerprint。
- `TokenUsage` 区分输入、输出、缓存输入和推理输出；缓存/推理是明细子集，不会被重复计入总预算。
- Context 具有分区预算、来源去重、工具结果上限、原子 tool-call 回合裁剪、摘要 checkpoint 和 rot signal。
- PostgreSQL 状态、事件、命令、工具账本和副作用组件具备事务、幂等、lease、heartbeat 和 fencing 基础。
- WorkerHost 以配置化有界 lane 并行推进不同 Run，同一 Run 保持 dispatcher 串行；所有 lane 由同一 ZIO 父 effect
  fail-fast 监督，不会让部分 lane 静默死亡。
- Provider、HTTP、数据库、MCP、OTLP、RAG 等通过 SPI/Adapter 隔离，没有反向污染业务领域。

### 2. 可用但仍需生产证据

- OpenAI-compatible、OpenAI Responses、Anthropic、Gemini 都有真实协议 Adapter 和 stub contract test，但真实流量历史仍短。
- RAG 已有 Tika/Docling PDF 摄取、结构感知 Markdown chunk、版本化原子发布、hybrid retrieval、rerank、citation 和 eval；
  已有 block/page/bbox lineage、结构切分和 ACL 后相邻/同父级扩展；仍缺恶意 PDF/真实 OCR、tokenizer-aligned
  chunking、大规模容量与线上领域质量证据。
- 长期 Memory 有治理和删除语义，但业务 UI、人工纠错和健康敏感信息的真实运营流程不足。
- MCP 客户端已经覆盖主要协议能力；server、OAuth、Roots 和第三方供应链治理还没有形成完整发布承诺。
- Workflow 已是显式 nodes/edges、运行前验证、循环访问预算和可恢复 checkpoint 的小型 StateGraph；仍不是成熟的
  分布式图执行平台。
- OpenTelemetry 和 Langfuse 已有安全投影，仍需真实 dashboard、告警阈值和事故演练。

### 3. 尚不应承诺

- 自动多 Agent 能稳定优于单 Agent；
- 任意工具都可以安全并行或自动重试；
- 任意数据库迁移都可以零停机；
- 任意 Provider 的高级字段语义完全等价；
- 长期记忆永远正确；
- Agent 能自动给出诊断、处方、剂量或替代医生判断；
- 当前 `0.x` API 已具有 `1.x` 的长期二进制兼容承诺。

## 三、与优秀框架比较

这不是功能打勾比赛。每个框架服务的抽象层次不同。

| 参考 | 值得学习 | zyblw-agent 当前对应 | 不应照搬 |
|---|---|---|---|
| OpenAI Agents SDK | 少量核心原语、动态指令、tools/handoffs/guardrails/sessions、HITL、trace、详细 usage | 单 Agent loop、工具/审批/guardrail、耐久 session、trace；新增分层指令和缓存/推理 token | 把 Provider 托管能力直接变成 core 唯一语义 |
| LangGraph | checkpoint、interrupt/resume、pending writes、time travel、fork | snapshot+event、审批恢复、工具批次账本、worker 恢复 | 在没有复杂确定性流程需求时强制所有业务画图 |
| Google ADK | session/memory/artifact、context filter、workflow/multi-agent、action confirmation、eval | session/memory、Context 分区、审批、eval；Artifact 具备内存版本化/隔离 SPI | 因示例丰富就提前构建多 Agent 平台 |
| Pydantic AI | 类型化开发体验、toolset/deferred tool、compaction、durable execution 适配、test/eval | Scala 类型化 Tool、Context 压缩、原生耐久 Runtime、testkit/evals | 追逐 Python 生态每个集成或 workflow backend |
| Microsoft Agent Framework / Semantic Kernel | function-first、Agent/Workflow 分层、类型化路由、superstep checkpoint、session/context provider | `AgentApplication` 与 Workflow 分层；ZIO typed effect/Scope；checkpoint/ledger 与持久化信封校验 | 把 Kernel 变成 Service Locator；反序列化不可信 checkpoint；追随实验性多 Agent API |
| Anthropic 工程实践 | 最简单可行架构、区分 workflow/agent、重视工具说明和测试、最小高信号 Context | 单 Agent 优先、Workflow 实验化、工具契约、分区 Context | 用自动反思或角色数量掩盖工具/数据质量 |
| OpenAI Codex | typed extension contributors、审批绑定具体副作用、ExecutionEnvironment/PermissionProfile、world-state Context section、skill catalog 按需加载、stable/experimental 协议分级、bounded queue | v5 契约冻结与单调审批已是前身；ApprovalSubject/Typed Extension/ExecutionEnvironment 进入 Wave 1，ContextSection/SkillCatalog/AgentProtocol 进入 Wave 2（见 [ADR-0019](architecture/0019-typed-extensions-and-constrained-execution.md)） | shell/apply_patch 核心化、Rust ExtensionData 动态容器、本地桌面信任模型、动态 Plugin 树 |
| ZIO / ZIO HTTP | typed effect、Scope、Fiber、Queue/Stream 背压、ZLayer 资源图、声明式 Endpoint | 是项目最有辨识度的执行语义 | 在 Builder 中藏全局可变单例或绕开 Scope 启 daemon |

### 图工程讨论的判断

三篇图工程讨论给出的方向有价值，但值得吸收的是控制面纪律，而不是“更多 Agent”：

- 节点是有边界的状态变换，边表达真实数据依赖；
- 静态边、条件路由目标、循环上限和 fan-in policy 必须在运行前可见；
- 每个耐久节点边界都能 checkpoint，失败是结构化数据，实际路径可以追踪；
- 并行只放在天然独立的分支，关键结论由独立 verifier/reviewer 检查；
- 高成本、不可逆和权限升级决策保留人工审批。

本轮据此重构 `core.workflow`：节点不再通过返回值隐藏下一跳；`WorkflowDefinition.make` 先验证完整图；
`WorkflowCheckpoint` 保存访问预算；fan-out 明确采用 `AllSucceeded`，由 ZIO 结构化并发传播失败与取消。随后完成的
`WorkflowExecutionStore` 已补上 pending outcome、耐久账本、lease/fencing 与 prepare→checkpoint 故障注入；后续版本又补齐
durable wait/signal 的原子注册/消费、稳定 signal ID 去重与 deadline 竞态裁决。当前仍故意只支持单步 fan-out 分支，避免在
没有子图命名空间、kill/restart 和多节点 soak 前假装拥有完整图平台。当前版本进一步以 wait 行作为 durable wake command，
补齐 Scoped `WorkflowWakeWorker`、heartbeat、延迟释放和 PostgreSQL `SKIP LOCKED` fencing。

内存/PostgreSQL 共享低敏 execution timeline、wait 状态机与 wake lease 契约；通用 command Worker 与 Workflow wake
Worker 都已完成独立 JVM `SIGKILL` 后跨进程 generation 接管，后者还同时验证 node execution generation、wait 消费与
终态 checkpoint；两条路径还都跨过同实例 PostgreSQL restart。通用 command Worker 另有正式 Start/Runtime 路径下
3 Worker/6 lane、120 Run 的本机有界 soak 回归基线；Workflow wake 也以 3 个独立 Store/Worker 完成 126 Run，wake/
execution claim 与完成 cycle 一一对应。下一步是部署节点丢失、数据库主备切换、长期 soak、生产 SLO 和完整 Graph Inspector。完整契约与边界见
[声明式 Workflow Graph](workflow.md)。

### zyblw-agent 的差异化优势

1. **ZIO 原生可靠性**：取消、超时、资源释放、并发和背压不是回调约定，而是 effect/Scope 语义。
2. **类型化失败**：业务可以区分配置、Provider、权限、预算、审批、持久化和恢复错误。
3. **耐久控制面较深**：命令队列、lease/fencing、工具执行账本、outbox/inbox/补偿不是 demo 级内存状态。
4. **安全边界显式**：模型是 proposer，后端是 enforcer；用户/租户/scope 来自认证层，不接受模型或请求正文自报。
5. **对业务数据库友好**：可以和 Scala/ZIO/PostgreSQL 宿主共享连接池、事务、迁移与运维边界。
6. **测试确定性**：Provider wire、SSE 任意分块、恢复、审批、预算和评测都有不依赖真实额度的契约测试。

### 相对不足

1. **易用性仍落后**：主流 Python/TypeScript 框架通常能用更少代码完成第一个结果；本项目生产装配更安全，但学习曲线更陡。
2. **文档示例密度不足**：需要按“最小内存 → 真实 Provider → 工具 → PostgreSQL → HTTP → RAG”逐步递进。
3. **Context 工程仍缺线上闭环**：已有预算和压缩机制，但缓存命中率、上下文丢弃与答案质量的关联还没有长期数据。
4. **Harness 有基础设施、缺业务证据**：Goal/Plan/Todo/Skill ADT、CAS Store、PostgreSQL Adapter、Steering/FollowUp、
   ArtifactReference 与跨 Run 任务预算已落地，但真实脱敏长任务数据、人工校准与 on-demand SkillCatalog（Wave 2）仍缺；
   Artifact 已有 PostgreSQL 耐久 Adapter；对象存储、多模态正文、线上保留治理和 Tool 接入仍待真实需求验证。
5. **开发工具仍处早期**：已有安全 Run Inspector、分页 Timeline 和机械一致性诊断，但尚无成熟 CLI/UI、筛选导出和
   checkpoint fork/time-travel。
6. **生态小**：没有独立下游、第三方 Provider/Tool 插件和真实公开发布反馈。

## 四、本轮吸收的能力

### 1. 分层、版本化指令

`InstructionSet` 解决“把所有规则拼成一个字符串”的问题：

- System 只放框架不变量、安全与合规；
- Developer 放业务角色、输出格式和领域规则；
- 用户输入、Memory、RAG、MCP 和工具结果不能构造可信指令块；
- 每块使用稳定 `id/version`；
- Context 中按固定顺序编译为最多一条 System 和一条 Developer 消息；
- `fingerprint` 可关联 eval、trace 和回滚，不记录正文。

完整说明见 [指令、Context 与成本工程](instruction-context-cost.md)。

### 2. Cache/Reasoning usage

OpenAI Chat Completions 与 Responses Adapter 会读取：

- `cached_tokens`；
- `reasoning_tokens`。

Runtime、状态、事件、Trace 和 OpenTelemetry 指标会持续累加明细。Provider 没报告就保持零；明细为负或大于对应总量时
fail-closed。框架仍不会保存隐藏推理正文。

### 3. 工具注册唯一性

同名工具现在在 `RegisteredToolRegistry.make/fromTools` 阶段返回
`AgentError.InvalidConfiguration`。这消除了“最后一个实现悄悄获胜”的装配不确定性。

### 4. 生产参考入口与安全 Run Inspector

`ProductionSupportHost` 是官方入口：PostgreSQL、真实 Provider、可信身份、审批写工具和 ZIO HTTP。
`AgentQuickstart` 已删除；未注册工具仍在正式 Application 路径上于模型调用前失败。`RunInspection`
把权威状态和事件投影为低敏 Timeline，检查 sequence、审批、usage 与终态一致性，
OpenAPI `1.1.0` 已提供授权后的 `/api/v1/runs/{runId}/inspection`。

这只是调试基础，不是成熟 Run Studio，也不是可执行 time-travel。详细边界见
[Run Inspector、Timeline 与安全调试](run-inspection.md)。

## 五、下一阶段优先级

### 本轮前沿文档的指导意义

文档对方向的判断成立：竞争核心已经从“再加 Provider、Tool 或 Agent 数量”转向 Agent Application Runtime。与官方资料
交叉核验后，最值得进入本项目架构的部分是：

- Agent、Harness、Workflow 三层分工；
- Provider-neutral 基线加 capability/native extension，而不是最低公分母；
- Goal/Plan/Todo、Artifact、Workspace、Memory 与 Context 使用不同生命周期和治理；
- durable graph 的 node ledger、pending writes、signal/timer 与故障恢复；
- outcome 优先、trajectory 辅助、`pass@k` 与 `pass^k` 并看的评测制度；
- MCP、A2A、内部 Workflow 各守协议边界。

这些内容已经通过 [ADR 0016](architecture/0016-agent-application-runtime.md) 和
[成熟度路线](maturity-and-roadmap.md) 进入正式决策。八个能力平面用于发现缺口，不会机械拆成八个模块。

2026-08-21 的复核进一步确认：LangGraph 与 Microsoft Agent Framework 都把 checkpoint 作为恢复、暂停和跨进程执行的
核心边界，且 Microsoft 明确要求把 checkpoint storage 视为信任边界；PydanticAI 的价值仍在 typed state/output 和可验证
序列化，而不是 Python API 本身。本项目据此没有新增 Graph 或序列化依赖，而是在 `PostgresRunStore` 读取边界对 State、
Event、Tool ledger 和 ModelCall ledger 交叉验证关系列与 Scala typed JSON，使用 `ZIO.attemptBlocking`、`Scope` 和 typed
`StoreError` fail-closed。这是“吸收设计语义、保持 Scala/ZIO 形态”的具体例子。

需要收敛或延后的部分也很明确：不是所有任务都需要 Graph；A2A 和多 Agent 不能早于单 Agent、Harness 和 durable
execution；Graph Studio、复杂 GraphRAG 和 Provider 全特性矩阵不能替代真实数据、恢复与发布证据。

#### 逐项采纳判断

| 提案内容 | 判断 | 进入 zyblw-agent 的方式 |
|---|---|---|
| Function → Workflow → Agent 的最小复杂度原则 | 采纳 | 由业务用例确定性选型；不让模型默认决定执行模式 |
| Agent / Harness / Workflow 分工 | 采纳 | 作为 `agent-core` 内可组合概念，复用唯一 Runtime |
| model proposer / runtime enforcer | 已是核心不变量 | 继续覆盖 capability、权限、审批、预算、fencing 与审计 |
| execution ledger、pending writes、lease/fencing | 已落地并继续加深 | `WorkflowExecutionStore` 已有本机进程 kill 双租约接管及 3 Worker/126 Run 有界 soak；下一步补部署节点丢失与长期/failover 证据 |
| timer、signal | 状态机、wait-as-command、Scoped Worker、真库故障恢复、独立 JVM 接管与有界多 Worker soak 已实现 | 下一步做部署节点丢失、数据库主备 failover、长期 soak 与生产 backlog/恢复 SLO |
| human task | 采纳但尚未实现 | 在 timer/signal 之上补可信主体、权限、撤销、升级与审计，不用 Prompt 约定冒充人工任务 |
| 低敏 execution timeline / wake queue | 已落地 | 复合游标分页与按定义聚合 backlog；不泄露状态、signal、outcome 或 lease token |
| Goal/Plan/Todo/Completion/Verification | 采纳为 Harness H1 | 先做小型 ADT/Store SPI 和 eval，不一次构造通用项目管理平台 |
| 定义版本/指纹冻结 | 继续采纳 | Workflow version、Instruction/Skill fingerprint 已有；v5 durable tool plan 已冻结 Schema/安全元数据摘要与单调审批要求，完整应用级 Definition manifest 仍按真实消费者推进 |
| Context pipeline、RAG/Memory 分治 | 已有 lineage、parent/neighbor、ACL 前置与原子发布 | 真实 OCR/敌对 PDF、token-aware、长期质量与保留治理 |
| Artifact 与 Message 分离 | 采纳 | 现有实验 SPI；下一步 durable metadata/object-store、ACL 与 retention |
| Provider-neutral + native capability | 采纳 | 公共最小契约加 capability/受控扩展，不伪装 Provider 完全等价 |
| MCP 与 A2A 分工 | 采纳边界，A2A 延后 | MCP 先完成身份/OAuth/Roots/信任；A2A 不替代内部 Workflow |
| outcome/trajectory/safety/resource 分离 | 采纳 | Eval Q1，先完成结果与过程评分及多试验趋势 |

#### 必须修正或不适合直接照搬的部分

1. **PostgreSQL 是参考耐久控制面，不是 core 的唯一语义。** 当前 PostgreSQL Adapter 最成熟，也适合事务、lease 和
   outbox；但领域契约仍依赖 Store SPI，内存实现服务确定性测试，未来其他后端必须通过同一契约测试，而不是把 JDBC
   类型放入 core。
2. **八层图是审计视角，不是代码目录和部署拓扑。** 把每一层都拆 artifact/服务会增加发布、故障和认知成本；只有依赖、
   生命周期、协议、安全或许可证边界成立才拆模块。
3. **不能一次引入完整领域名词表。** `AgentIdentity`、Definition Snapshot、Remote Task、Workspace、Skill、Plan、
   Timer 等都需要权限、序列化、迁移、删除和恢复语义。只增加 case class 会制造“看起来完整”的空壳架构。
4. **普通函数/Workflow/Agent 不是由一个万能 Selector 自动推断。** 业务契约、风险和合规决定模式；模型最多提出建议，
   不能自行升级到更高权限或更高成本执行层。
5. **Timer/Signal 不等于 `ZIO.sleep`/`Promise`。** ZIO 的内存并发原语适合进程内协调和确定性测试，但跨重启等待必须保存
   `fireAt`/deduplication identity，并把接收、状态推进与恢复命令放入明确事务边界。
6. **“所有代码都逐行中文注释”不是质量目标。** 公共契约、安全/耐久不变量和非直观算法必须详细说明；显然 getter 和局部
   语法不应机械翻译。统一标准见[代码注释与源码阅读约定](code-commenting-guide.md)。
7. **大型 Studio、自动 Swarm、无限反思和动态下载 Skill 暂不进入稳定核心。** 它们晚于可靠性、Eval、权限、保留/删除和
   外部用户证据。

### P0：开发体验和真实发布

- 当前空库安装基线为 `0.9.0`；构建、CI 和业务接入不引用旧制品或旧安装入口；
- 维持 `ProductionSupportHost` 作为唯一用户可见生产参考，并保持 contract 测试不依赖付费密钥；
- 以当前 0.9 源码契约检查 Scala API、JSON 快照、HTTP schema 和空库 V001，并以
  [兼容性契约](compatibility.md)分开记录各兼容表面；
- `zyblw-platform` 用 `.github/zyblw-agent.sha` 固定 sibling 源码 commit；`0.9.0-local` 只验证独立制品 consumer；
- 在已有安全 Timeline/inspect HTTP 读模型上增加 CLI 与轻量界面，不急着做大型 Web Studio。

成功标准：陌生 Scala/ZIO 开发者只读 README 能运行；失败时能从 typed error 和 trace 找到边界。

### P0：Context/成本质量闭环

- dashboard 显示 cache hit ratio、reasoning ratio、Context 分区、压缩次数、答案质量和费用；
- 固定长会话数据集验证 summary/citation/禁止内容不会随压缩丢失；
- 指令 fingerprint、模型版本、数据集版本共同进入 eval 身份；
- 以真实结果决定是否加入 Provider 显式 prompt-cache control。

成功标准：Prompt/Context 修改必须给出质量、延迟、token 和费用的前后对比。

### P1：Harness（Plan、Goal、Artifact、Skill）

不是先新增四个 artifact，而是先设计四个小型 Provider-neutral ADT：

- Plan/Goal/Todo 是可恢复任务状态，不只是 Prompt；
- 跨 Run 任务预算已采用 DeepSeek/Pi Harness 值得吸收的“显式任务边界 + 可重建事实”思想，但实现保持 ZIO/Scala 原生：`RunLimits` 是唯一 Run 额度，Goal 账本只做幂等 reserve/settle/release；PostgreSQL 行锁防并发透支，Start 五事实同事务，终态 Reconciler 修复崩溃窗口；没有第二套模型循环；
- Artifact 已完成 core SPI 与 PostgreSQL durable Adapter（V011）：独立二进制、session/user 隔离、不可变版本、名称/容量/metadata 边界，以及保存/读取/删除/过期审计。Goal/Plan/Todo 已通过有界 typed reference 关联且不加载正文。下一步由真实需求决定 Tool 接入、多模态正文和线上保留期演练；
- Skill 是版本化说明与能力清单，按需加载，不在每轮塞入完整正文；
- approval 和外部副作用继续由现有 Runtime/Store 承担。

只有真实长任务 eval 证明收益后才进入 Foundation。

### P2：checkpoint fork/time travel

先支持只读历史检查和从安全 checkpoint 派生新的 Run；任何 fork 都必须有新 runId、审计和副作用隔离。不能重放已经发生的
非幂等外部写入。

### P3：多 Agent/A2A

只有在固定任务集上满足以下全部条件才推进：

- 单 Agent 持续失败的原因确实是角色/上下文隔离；
- 多 Agent 的成功率提升超过延迟、费用和故障面增加；
- 权限、handoff 深度、循环、预算和责任归属可测试；
- 运维人员能看懂跨 Agent timeline。

## 六、明确不做

- 不因竞品有功能就新增 artifact；
- 不构建“支持所有 Provider”的无限适配矩阵；
- 不保存 chain-of-thought；
- 不允许模型直接取得数据库连接、密钥或任意 shell；
- 不把 Memory/RAG/MCP 内容当作可信指令；
- 不在医疗高风险场景自动诊断、开方、给剂量或绕过人工审核；
- 不以单测数量代替真实发布、真实数据、真实负载和事故演练。

## 七、如何客观评价一次改进

每个能力至少回答：

1. 哪个真实用户任务失败了；
2. 最小变更是什么；
3. 成功率、正确性、引用、延迟、token、费用分别如何变化；
4. 新增了哪些权限、恢复、数据和运维风险；
5. 如何迁移、回滚、删除；
6. 哪个 maturity 等级与证据匹配。

没有固定数据集、基线和退出标准的“智能化升级”只是演示，不是框架能力。

## 官方参考

- [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/)
- [OpenAI Agents SDK usage](https://openai.github.io/openai-agents-python/usage/)
- [OpenAI Agents SDK tracing](https://openai.github.io/openai-agents-python/tracing/)
- [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [Google Agent Development Kit](https://adk.dev/)
- [Pydantic AI](https://ai.pydantic.dev/)
- [Microsoft Agent Framework](https://learn.microsoft.com/en-us/agent-framework/overview/)
- [Microsoft Agent Framework checkpoints](https://learn.microsoft.com/en-us/agent-framework/workflows/checkpoints)
- [Anthropic：Building effective agents](https://www.anthropic.com/engineering/building-effective-agents)
- [Anthropic：Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)
- [Anthropic：Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Temporal：Durable timers](https://docs.temporal.io/develop/java/workflows/timers)
- [Temporal：Workflow signals 与 message passing](https://docs.temporal.io/develop/java/workflows/message-passing)
- [ZIO ZLayer](https://zio.dev/reference/contextual/zlayer/)
- [ZIO resource management](https://zio.dev/reference/resource/)
- [ZIO TestClock](https://zio.dev/reference/test/services/clock/)
- [ZIO HTTP Endpoint](https://ziohttp.com/reference/endpoint/)

## 本轮图工程讨论来源

- [0xCodez：Graph Engineering with Claude](https://x.com/0xCodez/status/2079165300625330317)
- [wandermist：Graph Engineering System Design](https://x.com/wandermist/status/2080974834851340400)
- [0xMorlex：图执行的状态、校验、checkpoint 与恢复约束](https://x.com/0xMorlex/status/2080598414576812378)
- [ZIO Fiber 与结构化并发](https://zio.dev/reference/fiber/)
- [ZIO Ref](https://zio.dev/reference/concurrency/ref/)
- [ZStream](https://zio.dev/reference/stream/zstream/)
