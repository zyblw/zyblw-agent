# zyblw-agent 能力审计、框架对照与演进判断

> 状态：当前审计
> 最后核验：2026-07-29
> 事实来源：当前源码、测试、构建、迁移、发布工作流，以及文末列出的官方框架资料

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

- 陌生开发者的五分钟成功体验；
- 真实长会话下的 Context 质量与 Prompt Cache 成本证据；
- 一等的计划、目标、任务清单与按需 Skill；Artifact 现有实验性的隔离/版本化 SPI，但尚无耐久 Adapter；
- 跨版本数据库/JSON/API 兼容演练；
- trace viewer、调试 UI、客户端 SDK 和独立外部用户反馈；
- 多节点 soak、容量模型、SLO、备份恢复与真实攻击测试。

因此，项目当前最重要的动作不是继续拆模块或增加多 Agent 名词，而是让已有单 Agent 主线更易用、更可测、更可运营。

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
- Provider、HTTP、数据库、MCP、OTLP、RAG 等通过 SPI/Adapter 隔离，没有反向污染业务领域。

### 2. 可用但仍需生产证据

- OpenAI-compatible、OpenAI Responses、Anthropic、Gemini 都有真实协议 Adapter 和 stub contract test，但真实流量历史仍短。
- RAG 已有 Tika/Docling PDF 摄取、结构感知 Markdown chunk、版本化原子发布、hybrid retrieval、rerank、citation 和 eval；
  仍缺 block/page lineage、parent-child retrieval、恶意 PDF/真实 OCR 证据与线上领域质量。
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
| Anthropic 工程实践 | 最简单可行架构、区分 workflow/agent、重视工具说明和测试、最小高信号 Context | 单 Agent 优先、Workflow 实验化、工具契约、分区 Context | 用自动反思或角色数量掩盖工具/数据质量 |
| ZIO / ZIO HTTP | typed effect、Scope、Fiber、Queue/Stream 背压、ZLayer 资源图、声明式 Endpoint | 是项目最有辨识度的执行语义 | 在 Builder 中藏全局可变单例或绕开 Scope 启 daemon |

### 图工程讨论的判断

三篇图工程讨论给出的方向有价值，但值得吸收的是控制面纪律，而不是“更多 Agent”：

- 节点是有边界的状态变换，边表达真实数据依赖；
- 静态边、条件路由目标、循环上限和 fan-in policy 必须在运行前可见；
- 每个耐久节点边界都能 checkpoint，失败是结构化数据，实际路径可以追踪；
- 并行只放在天然独立的分支，关键结论由独立 verifier/reviewer 检查；
- 高成本、不可逆和权限升级决策保留人工审批。

本轮据此重构 `core.workflow`：节点不再通过返回值隐藏下一跳；`WorkflowDefinition.make` 先验证完整图；
`WorkflowCheckpoint` 保存访问预算；fan-out 明确采用 `AllSucceeded`，由 ZIO 结构化并发传播失败与取消。当前故意只支持单步
fan-out 分支，避免在没有 pending writes、耐久账本和故障注入前假装拥有完整图平台。

这条路的下一步不是堆 Agent，而是 PostgreSQL checkpoint、节点 execution ledger、进程崩溃恢复和 Graph Inspector。
完整契约与边界见[声明式 Workflow Graph](workflow.md)。

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
4. **计划/目标/Skill 不是一等状态**：长任务仍主要依靠消息和 Workflow 状态，缺少统一持久化协议；Artifact 已有实验性 SPI，
   但生产耐久、治理和 Tool 接入仍待真实需求验证。
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

### 4. 五分钟入口与安全 Run Inspector

`AgentQuickstart.run` 用隔离内存控制面完整走过提交、claim、Runtime 和读取，不维护第二套演示循环；仓库示例无需 API Key
或数据库即可运行。`RunInspection` 则把权威状态和事件投影为低敏 Timeline，检查 sequence、审批、usage 与终态一致性，
HTTP `1.1.0` 已提供授权后的 `/api/v1/runs/{runId}/inspection`。

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

需要收敛或延后的部分也很明确：不是所有任务都需要 Graph；A2A 和多 Agent 不能早于单 Agent、Harness 和 durable
execution；Graph Studio、复杂 GraphRAG 和 Provider 全特性矩阵不能替代真实数据、恢复与发布证据。

### P0：开发体验和真实发布

- 已发布首个可解析的 Maven Central `0.1.0`，按 Early SemVer 和破坏性变更范围收口第二版 `0.2.0`；
- 维持已经可运行的五分钟纯内存 sample，并补一个独立 PostgreSQL sample；
- 以 `0.1.0` 为已发布基线持续检查 Scala API、JSON 快照、HTTP schema 和数据库 migration；
- 用发布制品而不是源码完整验证 `zyblw-server`；
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
- Artifact 已完成最小 core SPI：独立二进制、session/user 隔离、不可变版本、名称/容量/metadata 边界；下一步必须由真实需求
  决定 durable Adapter、保留期、删除审计和经过审查的 Tool 接入；
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
- [Pydantic AI](https://pydantic.dev/docs/ai/overview/)
- [Pydantic AI toolsets 与按需工具](https://pydantic.dev/docs/ai/tools-toolsets/toolsets/)
- [Pydantic AI deferred tools](https://pydantic.dev/docs/ai/tools-toolsets/deferred-tools/)
- [Anthropic：Building effective agents](https://www.anthropic.com/engineering/building-effective-agents)
- [Anthropic：Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)
- [Anthropic：Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [ZIO ZLayer](https://zio.dev/reference/contextual/zlayer/)
- [ZIO resource management](https://zio.dev/reference/resource/)
- [ZIO HTTP Endpoint](https://ziohttp.com/concepts/endpoint/)

## 本轮图工程讨论来源

- [0xCodez：Graph Engineering with Claude](https://x.com/0xCodez/status/2079165300625330317)
- [wandermist：Graph Engineering System Design](https://x.com/wandermist/status/2080974834851340400)
- [0xMorlex：图执行的状态、校验、checkpoint 与恢复约束](https://x.com/0xMorlex/status/2080598414576812378)
- [ZIO Fiber 与结构化并发](https://zio.dev/reference/fiber/)
- [ZIO Ref](https://zio.dev/reference/concurrency/ref/)
- [ZStream](https://zio.dev/reference/stream/zstream/)
