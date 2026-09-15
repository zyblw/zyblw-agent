# ADR 0019：Typed Extensions、Precise Approvals 与 Constrained Execution

> 状态：**Accepted / 规划合同**（决策已定，实现按 Wave 路线落地；不改变 `0.6.x` 已发布契约，直至对应代码与 migration 落地）
> 日期：2026-08-22
> 影响：审批模型、扩展接口、工具执行边界、Context 装配、公共协议；继承并扩展 [ADR-0018](0018-next-generation-runtime-kernel.md)
>
> 配套工作手册：[next-generation-runtime.md](next-generation-runtime.md)

## 背景

ADR-0018 确立的 P0（ModelCall 账本、CanonicalModelRequest、重建不变量、crash matrix）、P1（组合指纹、ContextContributor、v5 DurableToolPlan 契约冻结与单调审批）与 P2（Harness ADT/CAS、Steering/FollowUp、ArtifactReference、成对 Eval、跨 Run 预算）均已在代码落地，并有确定性失败注入、独立 JVM `SIGKILL`、PostgreSQL restart 与多 Worker 有界 soak 证据。

2026-08 对 OpenAI Codex（Rust 实现的本地 coding agent 及其运行基础设施）当前 `main` 的审视，连同此前对 Pi Agent 与 DeepSeek Harness 的分析，产生了五项与本仓库 Scala 3 / ZIO 2 类型体系高度契合、且 ADR-0018 尚未覆盖的设计。四个参考项目各自最有价值的部分可压缩为：

| 来源 | 一句话精华 |
|---|---|
| Pi | Keep it simple（Minimal Core、DX、Effect Sandwich） |
| DeepSeek Harness | Model-visible ⇒ reconstructable；capability seam 组合 |
| OpenAI Codex | Typed extension、constrained execution、precise approval、stable protocol |
| ZIO | Effect 系统拥有并发、取消、资源与组合 |

Codex **没有推翻** ADR-0018 的路线；它进一步证明：Kernel 应该小，Goal/Skill/Context/MCP 等复杂能力应通过稳定 typed seam 接入，而不是继续扩大 Agent loop。

## 需要解决的问题

1. 当前审批绑定的是「冻结的 callId + 工具契约指纹」，尚未绑定「具体副作用」的完整主体（输入指纹、执行环境、权限 profile、policy 版本）。放宽后的审批可能在环境或参数漂移后仍被复用。
2. `ContextContributor` 已是 seam，但 ToolProvider / SkillProvider / ApprovalReviewer / LifecycleObserver 等扩展面尚无正式契约；缺一份「Extension 能拿到什么、绝不能做什么」的合同。
3. 工具默认在宿主进程直接执行；sandbox 只是 MCP 模块内的 Experimental 能力。执行环境与权限没有一级抽象，无法支撑 Docker/K8s/远程执行器等未来实现。
4. Contributor 每轮全量渲染上下文；长运行 Agent 缺少「状态未变则不重复发给模型」的 world-state 语义。
5. HTTP `http.contract` 已把公共 DTO 与内部恢复 Schema 解耦,但缺 stable/experimental 分级与独立于内部实现的协议版本化声明。

## 决定

采纳以下五项，全部建立在 ADR-0018 六条铁律之上；任何一项与铁律冲突时，铁律优先。

### 1. ApprovalSubject：审批绑定具体副作用，不是工具名

> **实施状态（2026-08-22）：已落地。** `com.zyblw.agent.composition.ApprovalSubject`，AgentState schema v6。
> 实际绑定为 capability、callId、`ToolContractFingerprint`、`CanonicalInputFingerprint`、`ExecutionEnvironmentId`、
> `PermissionProfile`、`AuthorizationFingerprint`（租户 + 主体 + 已授予 scope）、`ApprovalPolicyFingerprint`、risk 与 sideEffect。
> 环境身份现有 `local` 与 `mcp-sandbox`；权限剖面是审批主体字段，缺省宿主权限，旧 JSON 缺该字段仍按宿主读取。
> 子 scope 只能收窄。主体形状变化会使历史审批失效，不需要再改一次持久化契约。
>
> 落地时另外收紧了一条本节未预见的弱点：`ApprovalRequest.id` 原为 `runId + callId` 的确定式取值，主体刷新后与旧请求同名，
> 旧控制台页面提交的决定会应用到一条它从未展示过的副作用上。现在标识包含主体摘要前缀，陈旧 `approvalId` 会被控制面拒绝。
>
> 漂移的处置方式选择了**重新请求审批**而不是失败：直接失败会让 `pendingApproval` 停在旧主体上，形成永远无法通过的死锁。

引入 `ApprovalSubject`，审批的对象从「callId」升级为一个规范化指纹主体，至少绑定：

```text
effect / call 身份
capability（工具）身份 + 契约指纹（沿用 v5 ToolContractFingerprint）
规范化输入指纹
ExecutionEnvironment 身份
PermissionProfile 指纹
policy 指纹（生效审批策略版本）
risk / sideEffect 分类
tenant / principal scope
```

语义：

- 审批表示「批准这个具体副作用在这个环境、这个权限、这个策略下执行」。
- 任一绑定属性变化（收件人变了、环境变了、权限 profile 变了、工具 schema 变了）⇒ 旧审批**不得**静默继续有效，必须重新审批或显式 revalidate。
- 与 v5 的单调审批语义合并：现场策略可以追加审批要求；放宽策略不能取消历史要求。ApprovalSubject 是 v5 冻结 callId 的超集，不是并行的第二套审批。
- 恢复路径先比较 ApprovalSubject 指纹，再进入任何审批事件或副作用；不完整主体视为持久化损坏，fail-closed。

### 2. Typed Extension API：窄契约，不是万能 Plugin

> **实施状态（2026-08-22）：已落地第一刀。** `com.zyblw.agent.extension`：`ToolProvider`、`SkillProvider`、
> `ApprovalReviewer`、`ToolLifecycleObserver`，由 `RuntimeExtensions` 一次装配。既有 `ContextContributor`、
> `Guardrail*`、`RunObserver` 保持原 trait，不复制第二套插件。
> `ApprovalReview.RecommendAllow` **不能**跳过人工审批或改写 `ApprovalSubject`；只有 `Deny` 能在副作用前 fail-closed。
> 工具生命周期观察缺陷被 Runtime 吞掉。扩展 `id@version` 冻结进 `RuntimeCompositionFingerprint.extensionIds`，
> 组合变化 `Incompatible`。Skill catalog 不含正文、`grantedTools` 恒为空；正文仍须经 `SkillMaterializer`，Trusted 也升不成 System。
> 执行环境不是 extension descriptor：默认 `Local` 不得进入 `extensionIds`。Host 把 `ExecutionEnvironment` 放在
> `RuntimeExtensions.environment`（`local` 或 MCP `mcp-sandbox` 适配器）。

正式化一组各司其职的扩展契约（名称落地时按审计定，语义不变）：

```text
ContextContributor          （已有，向 ContextSection 演进）
ToolProvider                （提供 RegisteredTool 目录）
SkillProvider               （catalog / search / on-demand load）
Guardrail                   （已有）
ApprovalReviewer            （审批决策的可插拔评审者）
RunLifecycleObserver        （只观察，不重写）
ToolLifecycleObserver       （只观察执行，不拥有 invocation）
ExecutionEnvironmentProvider
ArtifactProvider
TelemetryContributor
```

约束（合同级，不可协商）：

- Extension 只拿 Host 显式提供的稳定输入、ID 和自己的 extension state，**永远拿不到** `AgentRuntimeDriver` 或可变 Runtime 内部。
- Extension 不能：绕过权限、绕过审批、无 fencing 提交状态、任意修改 RunState、修改已审批 effect、在 canonical compiler 之外改动最终 Provider 请求。
- Kernel owns lifecycle；Extension contributes capability。
- 组合方式是 Scala `trait` + `ZLayer`，组合可 introspect（沿用 RuntimeProfile / 组合指纹）。
- 明确否决：`trait Plugin { beforeEverything/afterEverything }`、动态 plugin tree、service locator、`Map[String, Any]` extension state bag。若未来确需动态 extension state，用 `ExtensionStateKey[A]` 类型化键，不用无类型容器。

### 3. ExecutionEnvironment + PermissionProfile：受约束执行是一级概念

> **实施状态（2026-08-22）：已落地第一刀。** `com.zyblw.agent.execution`：`PermissionProfile` 单调收窄；
> `LocalExecutionEnvironment` 显式化宿主 JVM；`ConstrainedExecutionEnvironment` 承载非 local 身份。
> MCP 适配器 `McpSandboxEnvironment` 在 `agent-mcp` 把 Workspace / OCI sandbox 映射为 `mcp-sandbox`
> （workspace 根、无宿主网络/进程/secret）。身份与权限摘要进入 `ApprovalSubject` 与
> `RuntimeCompositionFingerprint.executionEnvironmentId` / `permissionProfileFingerprint`（不进入 `value` 哈希；
> 旧 JSON 缺字段视为 `local` + 宿主权限）。Docker/K8s/远程执行器仍是后续 adapter，不进 Kernel。

工具不再隐含「直接跑在宿主上」。引入：

```text
ExecutionEnvironment
  id / workspace / filesystem / network / process / secrets / sandbox

PermissionProfile
  filesystem policy / network policy / process policy / secret policy
```

语义：

- 执行链固定为：Tool → Policy → Approval（ApprovalSubject 含环境与权限指纹）→ ExecutionEnvironment → Effect → Settlement。
- 环境生命周期由 `ZLayer.scoped` + `Scope` 管理；Run 结束即释放进程、连接、workspace 句柄等资源。
- 能力继承单调收窄：子 scope 可以更窄，变宽必须由受信 Runtime 显式授予。
- 第一批实现：`Local`（现状语义显式化）+ 现有 MCP workspace/sandbox 适配。Docker / Kubernetes / 远程执行器 / 浏览器沙箱是后续 adapter，不进 Kernel。
- 环境语义变化属于组合漂移，触发 `RequiresRevalidation`（沿用 ADR-0018 漂移分类）。

### 4. ContextSection：world-state 快照与差量渲染

> **实施状态（2026-08-22）：已落地安全传递语义。** `ContextSectionSnapshot` + `ContextWorldSections.plan`；
> `AgentState.worldSectionCursors` 只存身份与指纹。Secret 永不渲染。差量决策进入
> `ModelCallContextLineage.sectionDecisions`。Debug 分区名 `ContextSection` 保持不变，避免与 world-state 类型撞名。
> 普通无状态请求默认 `FullSnapshot`；只有宿主证明 Provider continuation 保留前序上下文才可启用
> `TrustedStatefulDelta`。`CanonicalModelRequest` 仍是模型可见权威。Skill 目录经 `SkillCatalogSection` 投影为
> Metadata section，不含正文；`SkillContextContributor` 只按宿主选择调用 `SkillProvider.load`。

`ContextContributor` 升级为可选的 section 语义：

```text
ContextSectionSnapshot(id, version, fingerprint, sensitivity, payload)
PreviousContextSection = Absent | Unknown | Known(previous)
render(previous, current) => Option[ContextFragment]
```

语义：

- 每个 section 有稳定身份和指纹。普通 Chat/Responses 请求在框架层没有可证明的跨调用隐藏状态，因此默认每次发送完整
  snapshot；只有受信 stateful continuation adapter 才可在指纹相同时省略正文，变化时渲染 delta。
- 该机制只优化 Context 工程，**不放松** ADR-0018 的重建不变量：最终 `CanonicalModelRequest` 仍是模型可见内容的唯一权威表示，delta 决策本身进入 lineage。
- Memory / RAG / Goal / Plan / Skill Catalog / Workspace / 业务状态可以经此统一接入；新增业务 section 不修改
  Kernel。任何迁移到 delta 的贡献者必须先证明所选 Provider continuation 语义。
- Instruction 继续独立于普通 section：`authority + provenance + scope + version + fingerprint`（沿用现有 InstructionSet），不可信来源永远不能升级为 System/Developer。

### 5. 稳定 AgentProtocol：公共协议独立于内部状态

> **实施状态（2026-08-22）：已落地声明。** `AgentProtocolStability` 与 `/api/v1/experimental` 前缀。
> 稳定 OpenAPI 不含 experimental 路径；能力毕业必须显式改路径并更新契约测试。有界队列合同沿用现有 HTTP 边界。

在 `http.contract` 已有投影的基础上，正式声明公共协议合同：

- 外部概念限于：`Run / RunStatus / RunEvent / InteractionItem / ApprovalRequest / Usage / Error` 一级的产品语义。
- 内部概念（fencing generation、checkpoint 内部、ToolLedger 游标、Provider 实现状态）**永不**进入公共契约。
- 协议独立版本化；引入 stable / experimental 两级面：不成熟能力先走 experimental 前缀，毕业需要显式决定。
- Schema 尽可能生成（现有 ZIO Schema / OpenAPI 路径），并与运行版本对应。
- 所有对外队列（提交、SSE、事件）保持 bounded + 显式 overflow 策略；饱和时明确拒绝并建议退避，不无限积压（现状已如此，升级为合同）。

### 6. 跨切面不变量：租户、主体与 Secret

以下三项不属于任何单一 Wave,是所有切片的合同级约束:

**多租户与主体隔离**

- `TenantId` / `PrincipalId` 是 typed 身份,与 `RunId` / `SessionId` 同级,不得以 String 互换。
- 受信 `AuthorizationContext`（tenant、principal、grants）由宿主在提交边界建立,随 Run 耐久传播;模型输出、用户 JSON、RAG、MCP、Skill 永远不能自声明或提升它。
- 数据面隔离是查询层不变量:RunStore / Memory / RAG / Artifact / Harness 的每条读写路径都必须带租户谓词,禁止「先查全量再过滤」;跨租户读取是持久化损坏级错误,fail-closed。
- 预算与配额可按租户/主体分层(现有 `RunLimits` + Goal 预算是 Run/任务层;租户层配额是 Wave 1 之后按真实需求追加,不提前造抽象)。
- ApprovalSubject 已绑定 tenant/principal scope;审批不得跨租户复用。

**Secret 处置**

- Secret 值永不进入:模型 Context、trajectory、日志、遥测、工具结果、审批主体正文、公共协议。指纹与计数可以。
- 能力尽量以受控 handle/reference 暴露(如 `SecretRef`),由 ExecutionEnvironment 在执行边界解引用;解引用访问应可审计。
- 沿用现有数据分级(Public / Metadata / Sensitive / Secret):Secret 永不保存;Sensitive 仅 Replayable + 显式授权。

**Bounded by default**

- 所有队列、Hub、流缓冲、并发度必须有显式容量与 overflow 策略;饱和时明确拒绝并建议退避。耐久正确性事件永不丢弃;低优先级遥测可丢。此约束覆盖内部与公共协议两侧。

## 明确否决（来自 Codex 审视，不采纳）

| Codex 设计 | 本仓库结论 |
|---|---|
| Thread / Turn / Item 命名 | 借鉴语义，不复制名字 |
| Rust `ExtensionData` 动态状态容器 | REJECT，用 typed service + ADT + ZLayer |
| shell / apply_patch 作为核心 Runtime 概念 | REJECT，不适合通用业务 Kernel |
| 本地桌面信任模型 / coding-only workspace | ADAPT 为 ExecutionEnvironment 抽象 |
| 大量 Hook | 只保留 typed seam |
| 动态 Plugin 系统 | REJECT（与 DeepSeek Cordis 同理） |
| Goal Runtime 实现 | 借鉴 Extension 思路，不复制实现 |
| Fork / Subagent / Code Mode | 维持 DEFER（P3 / P4 / Experimental） |

## 路线重排：Wave 0–3

详细验收证据见工作手册。禁止并行推进 Wave；每个 Wave 内按垂直切片交付。

| Wave | 内容 | 性质 |
|---|---|---|
| **Wave 0 — 生产证据**（最高优先） | v6 PostgreSQL 真实门禁全量；部署环境 Pod/VM 丢失、数据库主备切换、数小时业务 soak；SLO 校准；dashboard / 告警 / runbook | 仓库内：v6 审批主体 JSONB 门禁 + [值班 runbook](../operations-runbook.md) 已落地；宿主环境证据仍待 |
| **Wave 1 — 安全与执行边界** | ~~ApprovalSubject~~（**已落地**，AgentState v6）；~~Typed Extension API~~（**已落地**）；~~ExecutionEnvironment / PermissionProfile 第一刀~~（**已落地**：Local + MCP sandbox 适配） | 允许 schema / API breaking |
| **Wave 2 — 上下文与协议** | ~~ContextSection 快照/差量~~ **已落地第一刀**；~~SkillCatalog~~ **已落地目录面**；~~stable/experimental AgentProtocol~~ **已落地声明** | 建立在 Wave 1 之上 |
| **Wave 3 — 分支与编排** | P3 Tree / Fork / Replay、人工任务、子图；P4 Subagent / Multi-Agent 仍以固定 Eval 证据为门禁 | 维持 DEFER 纪律 |

## 兼容与迁移

- 已发布 Flyway `V001`–`V010` 不得修改；ApprovalSubject 与 ExecutionEnvironment 相关持久化按追加 migration 落地。
- ApprovalSubject 已把 AgentState schema 升至 v6：v6 快照缺完整契约指纹或审批主体视为损坏；v5 及更早经 `DurableToolPlan.frozenApprovalCallIds` 回落到 callId 门禁（沿用 v4→v5 的处理方式）。审批主体存在 `state_json` 内，`agent_runs.schema_version` 无上界约束，因此本次不需要 migration。
- 若某切片必须 breaking：按 Scala API、HTTP、数据库 schema、durable payload、Maven 坐标分面写清，禁止一行「Breaking change」概括。
- 宁可明确不兼容，不要假兼容；过渡实现必须有删除里程碑。

## 风险

- ApprovalSubject 指纹集合过宽会造成审批风暴（每次微小变化都要重审）。落地时需区分「安全相关绑定」与「非安全元数据」，只有前者进指纹。
- ExecutionEnvironment 若一步做成通用沙箱平台会重新膨胀 Kernel；第一刀只做 Local 语义显式化与既有 MCP sandbox 适配。
- ContextSection 差量渲染若实现不慎会破坏重建不变量；每个 delta 决策必须进入 lineage 并被重建测试覆盖。
- Wave 0 是证据工作，容易被「写新代码更有趣」挤掉优先级；本 ADR 明确其为最高优先。

## 参考

- [ADR-0018](0018-next-generation-runtime-kernel.md)：六条铁律、Kernel 准入、三类 Durable Truth
- [下一代 Runtime 开发手册](next-generation-runtime.md)：Adoption Matrix、Wave 验收、测试矩阵
- OpenAI Codex `main`（extension-api contributors、ApprovalSubject 前身的 Approval Action 类型、WorldStateSectionContribution、app-server Thread/Turn/Item 协议、bounded queue 语义）— 设计参考，非实现模板
- 2026-08-20/22 的三份过程底稿已吸收进本 ADR 与工作手册后删除；其历史内容可从 git 历史追溯
