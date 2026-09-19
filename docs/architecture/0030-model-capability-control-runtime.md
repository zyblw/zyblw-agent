# ADR-0030：强模型时代的 Capability-aware Control Runtime

> 状态：Accepted；核心切片已实现，Provider-native 能力按门禁渐进接入
>
> 日期：2026-09-16
>
> 输入：[0916 核心抽象](../文档说明/0916核心抽象.md)、[0916 框架优化](../文档说明/0916框架优化.md)
>
> 前置决策：[ADR-0018](0018-next-generation-runtime-kernel.md)、[ADR-0019](0019-typed-extensions-and-constrained-execution.md)、[ADR-0026](0026-provider-capability-model.md)、[ADR-0028](0028-functional-kernel-runtime-driver.md)、[ADR-0029](0029-context-authority-prompt-lineage.md)

## 1. 决策摘要

模型越强，框架越不应该与模型争夺“如何推理”；框架的长期价值是把不确定推理接入确定的业务控制面。本项目采用以下定位：

```text
Model = proposal / interpretation / generation
Runtime = authorization / durability / execution / accounting / evidence
Business system = final domain invariants and source of truth
```

因此：

1. 保留一个 `AgentKernel`、一个 `AgentRuntimeDriver` 和一条 Run 事实链；不新增 `AgentBackend` 第二执行内核。
2. 以现有 `ModelCapabilities` 为唯一模型能力事实，增加结构化子能力，不平行引入 `CapabilityGraph`。
3. Provider-neutral 设置只表达稳定语义；厂商字段由 Adapter 映射。首个落地项是 `ReasoningEffort`。
4. 一次模型调用只读取一次 Provider+Model 能力，路由、校验、账本和 dispatch 共用该快照；低敏指纹进入 ModelCall lineage。
5. Provider-native 工具、会话、压缩和 Agent runtime 只有在授权、恢复、usage、审计与降级语义完整后才能进入框架。
6. 模型可以决定“建议调用什么”，不能自行扩大工具白名单、scope、预算、数据边界或副作用权限。
7. 没有真实消费者和固定 Eval 证明净收益的抽象不进入公共 API。

## 2. 为什么这套边界能适应更强模型

当前模型 API 正在快速增加推理档位、托管工具、服务端会话、长上下文、缓存和异步执行。具体字段与模型名会持续变化，但以下业务问题不会因模型更强而消失：

- 这个动作是否获授权，使用了谁的身份与哪些 scope；
- 外部写入是否已经发生，崩溃后能否安全重放；
- 同一 Run 恢复时使用的指令、工具、模型能力和价格合同是否漂移；
- token、缓存读写、工具、副作用和人工审批如何归账；
- 哪些内容是指令，哪些只是可能含注入的数据；
- 事故发生后能否在不泄露正文的前提下解释执行轨迹。

这些是控制系统问题，不是模型智力问题。把它们交给 Provider-native Agent 会形成第二事实源；把它们固定在 Runtime，则可以替换模型而不替换业务可靠性合同。

## 3. 当前源码审查与裁决

### 3.1 已有能力，继续深化而不重写

| 0916 概念 | 当前实现 | 裁决 |
|---|---|---|
| Agent Compiler / Composition Manifest | `AgentDefinition` + `LiveComposition` + `RuntimeCompositionFingerprint` | 不另建类型；冻结结果已经可恢复比较 |
| Capability Plane | `ModelCapabilities`、`CapabilityDescriptor`、`CapabilityRef` | 继续结构化 `ModelCapabilities`；不建第二张图 |
| Execution Core | `AgentKernel` + `AgentRuntimeDriver` | 已收口；Kernel 不接 Provider SDK、Store 或插件 |
| Drift reconciliation | `CompositionDrift` | 保留 Compatible / RequiresRevalidation / Incompatible |
| Durable action | Tool/ModelCall ledger + CAS/fencing | 保留 intent → effect → settlement；Unknown 禁止伪装成功 |
| Extension protocol | Tool/Skill/Context/Approval/Observer 窄接口 | 按真实变化原因扩展；拒绝万能 Hook |
| Inference policy | `ModelRequirement` + `ModelRoutingPolicy` + `RouteDecision` | 继续确定性硬过滤；不引入隐藏评分器 |
| Context plane | `ContextManager` + `PromptCompiler` + lineage | 保持数据权限、稳定布局和缓存非权威化 |
| Child run | 尚无完整生命周期消费者 | 暂缓；不先造父子共享状态与缓存协议 |

### 3.2 本次已实现的缺口

1. `ReasoningEffort(None/Low/Medium/High/Max)` 成为 `ModelSettings` 的稳定语义，并进入组合指纹。
2. `ModelCapabilities.reasoningEfforts` 声明当前 Adapter + Model 已验证的显式档位；未声明即 fail closed。
3. OpenAI Responses、OpenAI 官方兼容协议和 Gemini Interactions 由 Adapter 映射各自 wire 字段。
4. `ModelRouterGateway` 对直连和路由调用统一执行 capability lookup 与校验。
5. 同一次调用不再在 Driver 重读能力目录；`ModelCapabilities.fingerprint` 写入 ModelCall lineage。
6. 管理 API 与控制台展示已验证的推理档位，而不是把“模型会思考”误写成“所有档位都可配置”。

### 3.3 明确不采纳的过度抽象

- 不新增公共 `AgentCompiler`：当前不存在独立编译产物消费者，组合冻结已有唯一实现。
- 不新增 `CapabilityGraph`：ZLayer 负责依赖装配，`RuntimeCompositionFingerprint` 负责恢复合同，图只会复制事实。
- 不新增通用 `AgentBackend`：在只有本地 Runtime 一个可靠实现时，它会把授权与恢复压成最低公共分母。
- 不新增 `StatusStore`、`PromptStore`、`CacheStore`：它们会与 `AgentState`、ModelCall ledger、ArtifactStore 和 Provider cache 争夺权威。
- 不把每个厂商宣传能力立刻加成布尔字段；只有 Adapter 编码、解码、错误、恢复和 contract test 齐全的能力才声明支持。
- 不保存 chain-of-thought；只保存 reasoning token 数、配置档位、Provider continuation 所需的 opaque 原始项和低敏指纹。

## 4. 目标运行链

```text
trusted host configuration
        |
        v
AgentDefinition + RuntimeProfile + typed extensions
        |
        v
LiveComposition.freeze --------------------------+
        |                                         |
        v                                         v
ContextManager -> PromptCompiler            frozen Run contract
        |                                         |
        v                                         |
Canonical ChatRequest                             |
        |                                         |
        v                                         |
ModelRouterGateway                                |
  - resolve one adapter                           |
  - read capabilities once                        |
  - hard-filter policy/budget/data                 |
  - validate request                              |
  - freeze route + capability fingerprint         |
        |                                         |
        v                                         |
ModelCall intent commit <-------------------------+
        |
        v
Provider Adapter -> model proposes text/tool calls
        |
        +---------------- text --------------------> guarded settlement
        |
        v
DurableToolPlan -> ToolAuthorizationGate -> Tool ledger
        |                  |                   |
        |                  + approval/scope    + Unknown/recovery policy
        v
RegisteredTool -> Artifact-first result -> Run commit
```

任何 Provider-native 优化必须插在这条链上，而不能绕过它。

## 5. 权威、状态与存放

| 信息 | 唯一权威 | 可缓存/派生 | 禁止 |
|---|---|---|---|
| Run 状态、预算、消息、挂起、定义快照 | `AgentState` / `RunStore` | HTTP/Inspector 投影 | Provider session 反向覆盖 |
| 工具外部动作 | Tool ledger + 业务幂等/事务事实 | Timeline | 仅凭模型文本认定已执行 |
| 模型外部调用 | ModelCall ledger | Trajectory/Eval | 在临时 compressor 中隐形计费 |
| 大正文 | `ArtifactStore` immutable reference | 有界 preview | 在 State/Telemetry 复制全文 |
| 历史摘要边界 | `ContextSummaryCheckpoint` | Prompt data envelope | 作为 System 指令或第二记忆库 |
| Provider continuation | Assistant metadata 中的 opaque 原始项 | 可丢失性能优化需有 canonical fallback | 作为业务事实 |
| Prompt cache | Provider | read/write/fresh usage 与 prefix fingerprint | 参与授权、恢复或正确性判断 |
| 模型能力 | 已注册 Adapter 的 `ModelCapabilities` | 每次调用的 fingerprint | 从模型名或营销页猜测 |
| 价格 | 部署 `ModelPriceBook` | 调用时估算 | 框架内置易过期价格 |

## 6. 模型能力设计

### 6.1 稳定核心语义

核心只承载跨 Provider 真正稳定且会改变 Runtime 决策的语义：

- 上下文与输出上限；
- tool calling、并行调用、strict schema、specific choice；
- 多模态输入；
- reasoning token 报告与可配置 `ReasoningEffort`；
- Prompt Cache 的 kind、read/write 报告与 retention；
- continuation 是否可用。

现有布尔位不应一次性重写成深层对象。只有某一组字段出现至少两个真实消费者、需要独立验证或持续扩展时，才提升为值对象；`PromptCacheCapability` 已满足这个条件，reasoning 下一步也可在出现更多语义后演进为 `ReasoningCapabilities`。

### 6.2 Provider-specific 能力

独特协议通过 Adapter 私有 typed config 表达，不进入通用 `Map[String, Any]`：

- OpenAI Responses 的 reasoning、hosted tools、continuation；
- Gemini Interactions 的 thinking level、thought signature、托管工具；
- Anthropic 的 adaptive thinking、content blocks、signature、显式 cache control；
- 兼容端点的特定 thinking replay 规则。

`providerOptions: Map[String, Json]` 只作为受白名单约束的迁移出口。某字段一旦影响路由、预算、恢复或安全，就必须提升为 core typed setting 或 Provider typed extension，并从自由 Map 的允许列表删除。

### 6.3 能力快照不等于远端探测

每次请求远端查询模型清单会增加故障点，也无法证明响应到 dispatch 之间没有变化。生产依据是启动期已注册、contract-tested 的本地 capability catalog；一次调用读取一次并冻结 fingerprint。远端探测只用于启动验证或管理面诊断，不直接授予运行权限。

## 7. 执行位置与信任边界

“在哪里执行”与“谁有权授权”是两个维度：

| 执行位置 | 当前支持 | 授权责任 | 恢复责任 |
|---|---|---|---|
| Runtime registered tool | 是 | Framework + business policy | Tool ledger + recovery policy |
| Constrained/remote sandbox wrapper | 是，作为 registered tool 的实现 | Framework；环境只可收窄权限 | Tool ledger；sandbox 仅执行 |
| Governed external service | 是，作为 registered tool 的实现 | Framework + service-side auth | ledger + idempotency/outbox |
| Provider-native read-only tool | 尚未接入统一合同 | 必须先有 framework policy bridge | Provider usage + canonical evidence |
| Provider-native write/computer-use | 不支持 | 不允许仅由 Provider 自行授权 | 未定义前不得上线 |
| Provider-native Agent runtime | 不支持 | 不能绕过 Run/Tool/Approval | 未定义前不得宣称耐久 |

当前不增加一个只用于描述、却不驱动任何执行器的 `ExecutionPlacement` 公共字段。首个 Provider-native 工具接入时，应同时加入：

1. placement ADT 与 capability 声明；
2. 允许的数据级别、scope、风险和 side-effect 分类；
3. Provider 请求编码与结果证据；
4. usage、取消、超时和错误映射；
5. 恢复语义与降级策略；
6. 与 Runtime tool 的对照 Eval。

在这六项齐全前，“模型原生支持”只是厂商能力，不是 zyblw-agent 能力。

## 8. 推理、路由与升级

默认策略应简单且确定：

1. 业务或 Agent 选择 `ModelProfile` 与可选 `ReasoningEffort`。
2. 路由先做数据敏感级、能力、上下文、输出和硬预算过滤。
3. 在剩余候选中按配置顺序选择，不用模型自评决定自己的权限或价格。
4. 失败只有在明确的 typed error、重试预算和幂等边界内重试。
5. 升级到更强模型或更高 effort 必须是可观察政策，不在 Prompt 中隐式发生。

未来可增加 `InferenceEscalationPolicy`，但必须满足：

- 输入是错误分类、Eval 可验证信号与剩余预算，不读取隐藏 chain-of-thought；
- 每次升级生成 `RouteDecision`；
- 最大尝试次数、最大成本和允许的 Provider 列表可冻结；
- 对写工具没有额外授权效果。

## 9. 强模型能力的接入顺序

### Phase A：Control contract（本次完成）

- Provider-neutral reasoning effort；
- per-model supported effort；
- 一次 capability snapshot；
- capability fingerprint lineage；
- Provider wire contract tests 与管理面展示。

### Phase B：补齐现有非确定窗口

- ContextSummary 辅助调用复用 ModelCall intent/effect/settlement，以 `purpose` 区分 Main/Summary；
- settlement、usage、summary checkpoint 在一次 CAS/fenced commit 中归约；
- Provider 返回不确定时进入 Unknown，不自动重复付费；
- 完成 OpenAI/Anthropic 显式 cache dialect 前继续使用隐式缓存，不引入 CacheStore。

### Phase C：Provider-native read-only capability

优先选择 web/file search 或 code execution 中一个真实业务场景：

- 只读或隔离执行；
- 对照 Runtime-native 实现建立固定 Eval；
- 输出转成普通 evidence/artifact，不成为高权限指令；
- 证明质量/延迟/成本净收益后再公开 placement contract。

### Phase D：Stateful session

只把 Provider session 当可丢失 continuation：

- `AgentState` 和 canonical request 仍可在 session 丢失后重建；
- session ID 按租户/保留期治理且不得进入公共日志；
- 恢复先验证 Provider/model/capability fingerprint；
- 无 canonical fallback 的功能标记 Experimental，不能进入高可靠业务。

### Phase E：Native Agent backend

只有出现至少一个真实 Adapter 和一个无法由 ChatModel + typed tools 表达的业务消费者时立项。它必须证明：

- Framework 保留 runId、budget、authorization、approval、artifact、ledger 与 audit 主权；
- Provider 执行可被取消、结算、对账和安全降级；
- 不与现有 Driver 并行维护第二套状态机。

若做不到，则保持 Provider-native Agent 为框架外部服务，通过一个受治理工具调用。

## 10. 删除与收敛

随实现推进应删除而不是长期兼容的内容：

1. 业务代码直接写 `reasoning_effort`、`thinking_level`、`enable_thinking` 的路径；迁到 typed setting 后从 allow-list 删除。
2. 用 `thinking: Boolean` 推断“可配置任意 effort”的逻辑；该布尔值只表示 Adapter 能处理 reasoning 输出。
3. Driver 或 Provider 在路由后再次读取 capabilities 的路径。
4. 从模型名字符串猜能力、价格、上下文窗口或执行模式的分支。
5. 把 Provider session/cache 当恢复权威的实现。
6. 没有运行时消费者的 `AgentCompiler`、`CapabilityGraph`、`CompositionManifest`、`AgentBackend` shell。
7. 旧文档中未经官方资料核验的未来模型名、价格和发布日期；架构只记录稳定语义。

## 11. 测试与发布门禁

### 单元与协议

- capability fingerprint 对 Set/Map 顺序稳定，任一语义位变化必须改变；
- 显式 effort 未声明时 fail closed；
- 每个 Adapter 的字段映射、保留字段冲突和不支持档位；
- 路由只读取一次 capabilities；
- ModelCall lineage 保存 fingerprint 前缀而不保存 capability 配置正文；
- reasoning delta 与 chain-of-thought 不进入日志、HTTP 或 RunState。

### 恢复与集成

- 路由后 capability catalog 改变，不影响已经冻结并 dispatch 的调用；
- Run 恢复时组合、模型设置和 capability lineage 可共同解释漂移；
- Provider 中断遵守 ModelCall Unknown；
- 写工具仍经过 scope/approval/ledger，effort 提升不改变权限。

### Eval

至少记录：任务成功率、首次成功成本、总 token、reasoning token、cache read/write/fresh、工具错误率、审批率、P50/P95 延迟和恢复冲突率。优化目标是“每个成功任务的质量/成本/延迟”，不是单独追求最高模型分数或最高缓存命中率。

### 发布

- Core/Provider contract tests 全绿；
- 管理 HTTP/OpenAPI 与 Dashboard 类型一致；
- 破坏性 capability 配置迁移写入 CHANGELOG/compatibility；
- 真实 Provider smoke 由凭据保护，不把网络可用性伪装成 CI 单测；
- 新 native capability 默认 Experimental，只有固定 Eval 和故障演练通过后提升稳定级别。

## 12. 回滚策略

- `reasoningEffort = None`（Scala 的 `Option.None`）即可回到模型默认推理行为；不需要旧厂商字段。
- 路由候选可删除新模型并恢复原候选顺序；冻结 Run 若组合不匹配会 fail closed，而不是静默换模型。
- Provider-native 能力必须有 Runtime-native 或“明确不可用”的降级，不能回滚到绕过授权的自由调用。
- Prompt cache 可随时关闭或失效；请求、恢复和业务结果不依赖命中。
- 任何新能力出现事故时，先从 capability catalog 撤销声明，再下线 Adapter；未声明能力的请求会在发网前失败。

## 13. 外部事实依据

以下官方资料只用于验证“协议能力正在演进”，不把具体模型名或价格固化进框架：

- [OpenAI Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create)：reasoning、hosted tools、MCP、conversation 与 prompt cache 选项。
- [OpenAI latest model guide](https://developers.openai.com/api/docs/guides/latest-model)：推理档位、工具调用与长任务能力随模型演进。
- [Gemini thinking](https://ai.google.dev/gemini-api/docs/thinking)：Interactions 的 `thinking_level` 与模型相关支持集合。
- [Gemini tools](https://ai.google.dev/gemini-api/docs/tools)：原生工具与客户端执行工具并存。
- [Anthropic prompting/thinking guidance](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/prompt-templates-and-variables)：adaptive thinking 与 effort 的演进，以及工具自治仍需外部安全边界。

这些事实支持本 ADR 的核心判断：Provider 能力变化很快，因此框架应稳定语义、冻结能力事实并控制副作用，而不是冻结某一代模型的参数表。
