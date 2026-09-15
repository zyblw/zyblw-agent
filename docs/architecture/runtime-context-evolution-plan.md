# Runtime、Context 与 Prompt Cache 一体化演进方案

> 状态：Accepted 设计基线；Phase 0–1 与 Phase 2/3 核心切片已落地
>
> 当前源码核验：2026-09-16
>
> 输入：[0915 架构说明](../文档说明/0915架构说明.md)、[0915 上下文架构说明](../文档说明/0915上下文架构说明.md)、[0915 KV Cache 缓存架构](../文档说明/0915kv-cache缓存架构.md)
>
> 已实现架构基线：[ADR-0018](0018-next-generation-runtime-kernel.md)、[ADR-0019](0019-typed-extensions-and-constrained-execution.md)、[ADR-0028](0028-functional-kernel-runtime-driver.md)、[ADR-0029](0029-context-authority-prompt-lineage.md)
>
> 当前接入说明：[Prompt Runtime](../prompt-runtime.md)
>
> 本文定位：不建立第二条 Runtime 路线；把三份输入中仍有价值的部分裁剪、串联为可实施方案。下文“当前问题”保留审查时的原文，落地状态以本节与分阶段标注为准。

## 1. 最终结论

当前框架不需要再做一次“大内核重写”。执行主干已经具备正确形态：纯 `AgentKernel` 负责决定与状态迁移，唯一的 `AgentRuntimeDriver` 负责 ZIO 效果、Provider、工具、Store、Scope 与 Fiber；Run、Command、Tool、ModelCall、Suspension 和 Artifact 也已经拥有明确的耐久边界。

下一阶段真正值得投入的工作，审查时集中在四处；截至 2026-09-16 的落地状态：

1. **修正上下文权限语义**：已落地。Memory、RAG、World Section 和历史摘要使用转义后的 `User` data envelope，不再以 `System` 发送。
2. **建立一个可验证的 Prompt 编译边界**：已落地。`PromptCompiler` 校验 authority、Secret、role 与稳定 lineage；指纹写入既有 ModelCall ledger。
3. **让压缩与恢复确定一致**：核心切片已落地（artifact-first、Tool 单次物化、确定性摘要 checkpoint）。模型辅助摘要仍有 Provider 调用与 checkpoint 提交之间的不确定窗口，生产默认仍是确定性压缩。
4. **把 Prompt Cache 做成 Provider 优化与可观测能力**：usage/成本/capability 已归一化；显式 cache dialect（OpenAI 断点、Anthropic `cache_control`）仍待。缓存永远不参与业务正确性、恢复和授权。

不采纳或暂不采纳的核心提案：

- 不新增公共 `AgentCompiler`、`CapabilityGraph`、`CompositionManifest`、`CacheManager`、`PromptStore`、`StatusStore`。
- 不把 Kernel、预算、授权、提交或终态迁移做成插件。
- 不为缓存命中率把稳定的 typed tools 替换成一个通用执行工具。
- 不在缺少大工具集固定 Eval 前引入动态 Tool Search；工具集合变化本身会破坏可缓存前缀。
- 不在 child run 尚无真实消费者时设计父子 Agent 共享缓存协议。
- 不把模型生成的摘要或结构化提取当成新的权威状态。

## 2. 当前源码审查

### 2.1 已经正确，不应换名重写

| 能力 | 当前实现 | 结论 |
|---|---|---|
| 执行内核 | `AgentKernel` 纯决定，`AgentRuntimeDriver` 独占效果 | 保留；后续能力从 Driver 提供事实，不进入 Kernel 取数 |
| 耐久运行 | `AgentState`、CAS/fenced commit、Worker lease/heartbeat、Command Store | 保留单一事实链；不新增工作流式第二状态机 |
| 外部动作恢复 | Tool ledger 的 Prepared/Running/Succeeded/Failed/Unknown；ModelCall intent/effect/settlement | 保留；所有新派生输入必须在模型调用 intent 前冻结 |
| 等待状态 | 统一 `Suspension` 及过期处理 | 保留；审批、时间、信号、人类任务不再建平行字段 |
| 组合身份 | `RuntimeCompositionFingerprint` 结构化保存并 fail-closed 比较 | 深化字段与 diff；不另存 Manifest |
| 扩展 | `ToolProvider`、`ContextContributor`、`SkillProvider`、typed contribution | 保留窄接口；不扩张为万能 lifecycle/hook 系统 |
| Context 增量 | `ContextSectionSnapshot`、游标、`ContextSummaryCheckpoint` | 保留；修正消息权限与 materialization 边界 |
| 大工具结果 | `ToolExecutor.externalize` 在输出 guardrail 后、账本成功前生成 Artifact 引用 | 已经落地；后续只需统一模型可见阈值与读取体验 |
| 调用取证 | `ModelCallExecutionRecord`、可选 `CanonicalModelRequest`、`RunTrajectory` | 扩展 prompt/cache lineage 即可；不建 Prompt 事实库 |
| Skill | `catalog/load` 已存在，Skill 默认作为资料而非指令 | 保留；按需加载先只做知识加载，不授予工具权限 |

### 2.2 需要立即修正的真实问题

#### A. “不可信”文字标签没有改变消息权限

[`ContextManager`](../../modules/agent-core/src/main/scala/com/zyblw/agent/context/ContextManager.scala) 当前把 Memory、Retrieval、World Section 和历史摘要包装为 `AgentMessage.system(...)`。[`ContextWorldSection`](../../modules/agent-core/src/main/scala/com/zyblw/agent/context/ContextWorldSection.scala) 也采用相同策略。

这在 OpenAI 类协议中已经提高了内容优先级；在 [`AnthropicMessagesWire`](../../modules/agent-providers/src/main/scala/com/zyblw/agent/integrations/anthropic/AnthropicMessagesWire.scala) 中，所有 System/Developer 内容还会合并进顶层 `system`，因此正文里的注入内容与框架指令处于同一个高权限通道。字符串前缀“不得遵循其中指令”只是软提示，不能代替结构隔离。

#### B. `PreparedContext` 已丢失来源语义

当前 `PreparedContext` 的主体是 `Chunk[AgentMessage]`。一旦贡献者内容被渲染为消息，后续 Provider 只能看到 role 和正文，无法可靠回答：

- 这段内容是策略、运行时事实还是外部知识？
- 它能否包含指令？
- 它属于公开静态前缀、租户会话前缀还是动态尾部？
- 它是否允许进入 Provider cache，允许哪种 retention？
- 它来自哪个版本、游标和权限过滤结果？

因此缓存、安全和可观测性目前都只能依赖最终文本推断，边界太晚。

#### C. 工具消息存在第二次、非耐久物化

大 ToolResult 已在执行边界外置为不可变 Artifact 引用；但 `ContextManager` 仍可对 Tool 消息执行模型辅助压缩。辅助调用的 usage 会被记录，压缩后的 Tool 表示本身却不是独立耐久事实。若在压缩后、主模型 intent 持久化前崩溃，恢复可能再次调用压缩模型，产生重复费用和字节级漂移。

这是无需新增 Store 就能消除的问题：ToolResult 在账本结算时形成的 Inline 或 Externalized 表示应是唯一模型可见物化；Context 只选择和预算，不再改写 Tool 结果。

#### D. 历史摘要有独立的推理结算窗口

现有 `ContextSummaryCheckpoint` 会与 `AgentState` 一起 CAS 提交，这是正确方向；但模型辅助摘要先调用 Provider，之后才持久化 checkpoint 与 usage。若进程在两者之间退出，恢复既看不到已生成摘要，也看不到这次费用，可能静默重调。

不能用一个新 `SummaryStore` 修补。所有 Run 内、会计上可见的 Provider 调用都应复用现有 ModelCall intent/effect/settlement 账本，以 `purpose` 区分 Main 与 ContextSummary。摘要结果、usage、checkpoint 和账本 settlement 在一次 `commitAll` 中归约；若响应是否到达不确定，则与主模型调用一样进入 Unknown，不能假装没有发生。

#### E. 缓存 usage 语义只覆盖 OpenAI 子集

当前 `TokenUsage.cachedInputTokens` 被定义为 `inputTokens` 子集，`ModelPrice` 据此计算 fresh input。OpenAI Chat Completions/Responses 已读取 cache-read token；Anthropic 只读取 `input_tokens/output_tokens`，未读取 cache creation/read；Gemini Interactions 只读取 `total_input_tokens/total_output_tokens`，未读取 `total_cached_tokens`。因此跨 Provider 的缓存成本、命中率和路由证据并不完整。

#### F. `promptCache: Boolean` 表达不了真实能力

不同 Provider 的缓存模式并不等价：隐式或显式、自动或断点、可用 retention、最小 token、usage 是否报告 read/write 都不同。一个 Boolean 无法支持 fail-closed 协商，也无法指导 Adapter 编码。

### 2.3 不是当前问题的问题

- Kernel 与 Driver 的纯/效果边界已经形成，无需引入 Free Monad 或 Action Interpreter。
- ZLayer 已经承担依赖图和 scoped resource recipe；无需自制 Capability DI 图。
- RunStore 已经是恢复权威；无需为 Prompt、Context、Cache 再建立事实库。
- Extension 已足够表达当前真实贡献者；没有证据支持 everything-is-capability。
- Multi-agent、父子缓存和动态工具目录都不是修复上述问题的前置条件。

## 3. 目标原则

### 3.1 六条硬不变量

1. **Kernel 只处理事实与决定**：Context、缓存、Provider wire format 不进入 `AgentKernel`。
2. **一类事实一个权威**：业务状态在 `AgentState/RunStore`；外部动作在 ledger；大正文在 Artifact；Provider cache 不是事实。
3. **只有可信拥有者能授予指令权限**：外部文档、Memory、Skill 正文、用户输入、工具输出和模型摘要不能成为 System/Developer 指令。
4. **恢复不依赖缓存命中**：缓存未命中、过期、清空或 Provider 降级只能改变费用与延迟，不能改变可恢复语义。
5. **同一冻结计划产生同一编码**：同版本 Prompt 编译器和 Provider encoder 对相同输入必须生成稳定顺序和稳定字节。
6. **先测量，后优化路由**：缓存数据先进入 telemetry/eval；没有真实命中分布前，不用猜测命中率改变模型选择。
7. **Run 内推理全部记账**：主模型、Context 摘要及未来任何付费派生推理共用一个 ModelCall ledger，以 purpose 区分，不能藏在临时对象中。

### 3.2 来源信任与指令权限必须正交

单一的 `ContextAuthority(System > Developer > ...)` 仍然会混淆两个问题：

- **数据是否可信**：例如预算数字由 Runtime 计算，可信度高；RAG 文档来自外部，可信度低。
- **数据能否命令 Agent**：预算数字即使可信，也不是一段任意指令；业务 Developer 配置才拥有指令权限。

目标模型至少保留以下正交维度：

```scala
enum ContextPurpose:
  case Policy, CapabilityCatalog, RuntimeControl, Knowledge,
       Conversation, ToolEvidence, CurrentInput

enum InstructionAuthority:
  case System, Developer, None

enum ContentTrust:
  case RuntimeDerived, HostTrusted, UserProvided,
       ExternalUntrusted, ModelGenerated

enum DataSensitivity:
  case Public, Internal, Sensitive, Secret

enum CacheStability:
  case Static, SessionStable, Dynamic

final case class ContextBlock(
  id: String,
  purpose: ContextPurpose,
  authority: InstructionAuthority,
  trust: ContentTrust,
  sensitivity: DataSensitivity,
  stability: CacheStability,
  provenance: ContextProvenance,
  content: ContextContent
)
```

这些类型先保持 `private[agent]` 或 `private[context]`。只有出现宿主需要直接构造它们的真实用例后，才提升为公共 SPI。

编译期验证规则：

- `System/Developer` 只允许 `Policy + RuntimeDerived/HostTrusted`。
- 外部、用户或模型生成内容的 `authority` 必须为 `None`。
- `RuntimeControl` 只能由 typed state 投影生成，不接收任意正文。
- `Secret` 默认禁止进入 Provider 请求；允许时必须由部署策略显式批准且禁止扩展 retention。
- ToolEvidence 必须保持 call/result 原子关系和 Tool role，不转换成 System。
- 任意非法组合在模型调用前 fail closed，并产生低敏审计事件。

## 4. 目标架构

```text
AgentDefinition / RuntimeComposition / AgentState
                         │
                         ▼
               ContextAssembler (effectful)
 memory / retrieval / skill / section snapshot / artifact refs
                         │ frozen sources
                         ▼
                PromptCompiler (pure)
 validate authority → dedupe → budget → compact → order → fingerprint
                         │
                         ▼
                     PromptPlan
 static prefix │ session prefix │ dynamic tail │ tools │ cache policy
                         │
                         ▼
              Provider Adapter / Encoder
 OpenAI cache options │ Anthropic cache_control │ Gemini implicit layout
                         │
                         ▼
               ModelCallCoordinator
 persist intent + lineage → dispatch → normalize usage → settle/unknown
                         │
                         ▼
 AgentKernel transition → RunStore CAS/fenced commit → trajectory/metrics
```

### 4.1 最小实现边界

为了避免把一次修复扩成平台建设，只保留两个核心协作者：

- `ContextAssembler`：取得和权限过滤外部来源、加载明确选中的 Skill、读取 section/cursor、准备摘要 checkpoint。它可以有效果，但不能决定消息权限。
- `PromptCompiler`：纯函数，接收冻结来源与 State 投影，返回 `PromptPlan` 或 typed validation error。

现有 `ContextManager` 在迁移期作为兼容 facade；内部改为调用这两者。迁移完成后删除其旧拼接/压缩实现，不同时保留两套算法。

不新增 `AgentCompiler`。Agent 定义校验、Builder preflight、ZLayer 装配和 `RuntimeCompositionFingerprint` 已经覆盖当前真实需求；若以后确实出现跨定义的纯规范化步骤，可在 Builder 内增加 package-private 函数，而不是先建公共编译框架。

### 4.2 `PromptPlan` 是调用计划，不是新的事实库

建议形状：

```scala
final case class PromptPlan(
  blocks: Chunk[ContextBlock],
  conversation: Chunk[AgentMessage],
  tools: Chunk[ToolDefinition],
  settings: ModelSettings,
  cachePolicy: PromptCachePolicy,
  lineage: PromptLineage
)

final case class PromptLineage(
  compilerVersion: String,
  layoutVersion: String,
  sourceFingerprints: Chunk[SourceFingerprint],
  stablePrefixFingerprint: String,
  planFingerprint: String
)
```

约束：

- `PromptPlan` 在每次 ModelCall 前临时生成，不进入 `AgentState`。
- `ModelCallExecutionRecord` 保存低敏 lineage；`CapturePolicy.Replayable` 时，扩展 `CanonicalModelRequest` 以保存足够的最终请求和 cache directive。
- Metadata-only 模式只保存摘要、fingerprint、版本和计数，不保存正文。
- Provider encoder 版本进入模型调用记录；同一 plan + encoder version 必须稳定编码。
- `RuntimeCompositionFingerprint` 只冻结 compiler/layout/cache-policy/encoder 协议版本，不保存动态 plan 或 cache key。

### 4.3 Provider 渲染规则

| 语义 | OpenAI 类 | Anthropic Messages | Gemini Interactions |
|---|---|---|---|
| System/Developer Policy | 使用原生高权限角色 | 合并到顶层 `system`，保留稳定顺序 | 使用原生系统指令能力 |
| RuntimeControl | 仅渲染枚举、数字、ID 摘要等 typed 字段；独立稳定模板 | 同左；禁止混入用户/文档自由文本 | 同左 |
| Knowledge / Memory / Skill | 非指令数据 envelope；不得使用 System/Developer | 放入 user content 数据块并明确 provenance | 放入 user content 数据块 |
| CurrentInput | User role，保持原始回合边界 | User content | User input |
| ToolEvidence | Tool role，保持 call/result 对应 | `tool_result` 原生块 | `function_result`/原生对应结构 |
| History summary | ModelGenerated + authority=None 的数据块 | user data block | user data block |

“数据 envelope”必须由 encoder 生成固定结构和转义，不能只拼接一个中文警告。需要对 XML/JSON 边界逃逸、角色交替、空消息、Tool call 原子组做 golden tests。

### 4.4 Runtime Status Bar

Status Bar 是 `AgentState` 和可选 Harness task projection 的纯视图，不存储、不写回、不成为第二事实源。它位于动态尾部，避免每次变化破坏静态前缀。

只包含模型下一步决策真正需要的 typed 字段：

- 当前 Run 状态、阶段、step index；
- 剩余硬预算，而不是累计账单正文；
- 当前 suspension 类型与允许动作；
- 待执行工具批次的名称/ordinal 摘要；
- 已激活 Skill 的 id/version；
- 由代码计算的冲突、过期或降级警告。

不包含 worker/lease/fencing、tenant/user 原始标识、Secret、完整工具参数、完整历史、日志或动态时间戳。用户提供的目标文本不是 Runtime 指令，留在 Conversation/CurrentInput 数据通道。初始 token 上限建议设为约 384，最终值由长会话 Eval 调整，而不是把 200–800 当成固定真理。

## 5. Context 获取、裁剪与压缩

### 5.1 固定处理顺序

```text
取得并权限过滤来源
  → 规范化 provenance 与 authority
  → 去除完全重复和已覆盖的陈旧片段
  → 保留 System/Developer Policy 稳定前缀
  → 保留 Tool call/result 原子组
  → 使用已经冻结的 Artifact 引用
  → 复用耐久 summary checkpoint
  → 必要时生成下一代 history checkpoint
  → 最后才做确定性硬裁剪
  → 编译稳定前缀、会话前缀、动态尾部
```

### 5.2 ToolResult：只物化一次

把 `ToolExecutionPolicy.externalizeAboveBytes` 与 Context 允许的最大 inline ToolResult 对齐：

- 小结果：账本和 `AgentState.messages` 保存相同 Inline 表示。
- 大结果：guardrail 校验完整结果后，保存 Artifact，账本和消息只保存不可变引用、摘要预览、media type、sha/版本。
- 模型如需正文，使用受权限和预算控制的 `read_artifact` 工具读取范围；读取结果仍走相同外置规则。
- Context 不再执行 `compressToolMessage` 的模型辅助路径，也不把 Tool 消息改成 System summary。
- 如真实 Eval 证明需要“语义型 Artifact 摘要”，它应是内容寻址、版本化、可复用的派生 Artifact，并在主 ModelCall intent 前持久化；不能作为隐藏临时结果。

这同时解决重复费用、崩溃漂移、Tool role 丢失和大正文污染缓存前缀四个问题。

### 5.3 历史摘要：保留 checkpoint，但限制职责

现有 `ContextSummaryCheckpoint(summary, coveredMessages, sourceDigest, compressorVersion)` 是合理的最小耐久边界，继续保留在 `AgentState` 的同一 CAS 事务中。

摘要只负责压缩“对话中尚无其他权威载体的承诺与上下文”。以下信息不得复制进摘要作为权威：

- 预算与 usage：来自 `AgentState/BudgetState`；
- 审批与等待：来自 `Suspension`；
- 工具完成与幂等：来自 Tool ledger/steps；
- 模型结算：来自 ModelCall ledger；
- Artifact 完整性：来自 ArtifactStore descriptor；
- Context section 版本：来自 cursor/snapshot lineage；
- Citation/Evidence：来自已有 typed fields。

这些事实由 Status Bar 或 Evidence block 每回合重新投影。模型生成的摘要始终是 `ModelGenerated + authority=None`，即使格式验证通过也不能授予工具、改变预算、宣称审批完成或覆盖终态。

摘要更新采用 checkpoint 代际，而不是每回合改写：只有淘汰前缀跨过确定阈值时才生成；相同 source digest 和 compressor version 必须直接复用。模型辅助压缩像主模型调用一样先写 intent，并以 `ModelCallPurpose.ContextSummary` 记录 usage、价格和 tracing。Provider 返回后，summary checkpoint、usage 和 ledger settlement 通过一次 `RunStore.commitAll` 原子提交；崩溃造成的不确定调用进入 Unknown，不静默重试。

### 5.4 Skill 的渐进披露

当前 `SkillProvider.catalog/load` 已经具备目录与按需加载语义，不需要新建 Skill Registry。

第一阶段只做：

- 稳定、短小的 Skill catalog 进入 SessionStable 数据段；
- 宿主或一个只读、不可授予权限的 Skill 查询/加载工具选择 `id + version`；
- 加载正文作为 Knowledge，保留 trust/provenance；
- 只有 Host 明确标记且现有 `SkillMaterializer.asDeveloperInstruction` 校验通过的内容才可成为 Developer Policy；任何 Skill 都不能成为 System；
- 激活的 id/version 进入 plan lineage 和 Runtime Status，不自动扩张工具白名单。

动态 Tool Search 暂缓。只有“大工具集”固定 Eval 同时证明 token、正确率和延迟收益，并证明权限/组合漂移可恢复后，再单独设计。

## 6. Prompt Cache 设计

### 6.1 定位

Prompt Cache 只优化 Provider 调用，不是应用缓存：

- 框架不保存 Provider KV cache 内容；
- cache miss 必须可以透明回退为完整计算；
- AgentState 不保存动态 cache key、hit 状态或过期时间；
- Runtime 不因“预计命中”跳过 Context、授权、预算或 ModelCall intent；
- 硬 token 预算按模型实际看到的逻辑输入计数，不按折扣价格减少。

### 6.2 稳定分段

建议顺序：

```text
Static
  framework system policy
  agent system/developer policy
  stable tool definitions
  public stable skill/catalog metadata

SessionStable
  tenant/session-scoped policy facts
  selected skill versions
  stable knowledge snapshot references
  durable history checkpoint

Dynamic
  recent conversation
  current tool evidence
  runtime status
  current user input
```

排序本身是 correctness contract。禁止在静态段加入时间戳、随机 ID、Map 非确定迭代、动态预算、命中率或 worker 信息。Tool definition 必须按规范化名称稳定排序；Schema 使用 canonical JSON；模板版本显式进入 fingerprint。

### 6.3 能力模型

删除 `ModelCapabilities.promptCache: Boolean`，改成一个小而可扩展的值对象：

```scala
enum PromptCacheKind:
  case Unsupported, Implicit, Explicit, Hybrid

final case class PromptCacheCapability(
  kind: PromptCacheKind,
  reportsReadTokens: Boolean,
  reportsWriteTokens: Boolean,
  supportedRetention: Set[CacheRetention]
)

enum CachePreference:
  case Disabled, Preferred, Required

enum CacheRetention:
  case Ephemeral, Extended
```

Provider 的最小 token、breakpoint 数量、具体 TTL 名称、wire 字段和自动缓存行为留在 Adapter dialect 内，不扩张为长期稳定的 core Boolean 集合。`Required` 在 capability negotiation 时 fail closed；`Preferred` 不改变正确性，可回退到无缓存编码。

### 6.4 Provider 策略

| Provider | 初始策略 | 关键约束 |
|---|---|---|
| OpenAI | 先支持隐式稳定前缀与 usage；显式 breakpoint/retention 按具体协议能力开启 | 精确前缀和 tools/instructions 顺序稳定；不把 tenant 原始 ID 当 cache key |
| Anthropic | Adapter 根据 PromptPlan 设置自动顶层或少量显式 `cache_control` 断点 | tools → system → messages 的层级变化会级联失效；不得超过 Provider breakpoint 约束 |
| Gemini Interactions | 初始只利用隐式缓存，保持大而稳定内容在前 | 解析 `total_cached_tokens`；不虚构显式 cache controls |
| OpenAI-compatible | 默认 Unsupported | 只有特定 endpoint 的 contract test 证明字段语义后才声明能力，不能按品牌猜测 |

官方当前语义依据：[OpenAI Prompt Caching](https://developers.openai.com/api/docs/guides/prompt-caching)、[Anthropic Prompt Caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)、[Gemini Context Caching](https://ai.google.dev/gemini-api/docs/caching)。这些能力会演进，Adapter 必须以协议 contract test 和显式版本为准。

### 6.5 Usage 与成本归一化

建议把 `cachedInputTokens` 明确迁移为 `cacheReadInputTokens`，并加入 `cacheWriteInputTokens`：

```scala
final case class TokenUsage(
  inputTokens: Long,              // 模型看到的逻辑输入总量
  outputTokens: Long,
  cacheReadInputTokens: Long = 0,
  cacheWriteInputTokens: Long = 0,
  reasoningOutputTokens: Long = 0
)
```

统一不变量：

```text
0 <= read + write <= inputTokens
freshInputTokens = inputTokens - read - write
totalTokens = inputTokens + outputTokens
```

Adapter 映射：

- OpenAI：`input_tokens/prompt_tokens` 已是逻辑总输入；details 中 cache read/write 分别映射。
- Anthropic：逻辑总输入由 `input_tokens + cache_creation_input_tokens + cache_read_input_tokens` 归一化，不能继续把 `input_tokens` 单独当总输入。
- Gemini：`total_input_tokens` 为逻辑总输入，`total_cached_tokens` 映射 cache read；没有 write 报告时保持 0/unknown capability，而不是猜测。

`ModelPrice` 增加可选 cache-write 单价；fresh/read/write 分别计价。预算始终使用逻辑总输入，价格只影响 cost。数据库 JSON codec、HTTP DTO、OpenAPI、RunDirectory、HarnessBudget、metrics、trajectory 和 Provider fixture 必须在同一兼容迁移中更新。

对既有 0.9.0 JSON：读取旧 `cachedInputTokens` 时迁移为 `cacheReadInputTokens`，缺失 write 默认为 0；新写只写新字段。公共 HTTP 可以先在一个兼容窗口保留 deprecated alias，但内部只保留一个含义。

### 6.6 隔离和数据治理

- `Public + Static` 才能显式允许跨会话共享前缀。
- Internal/Sensitive 默认 tenant + session 分区；Provider key 使用宿主提供的随机或 HMAC 派生 opaque 值，禁止发送原始 tenant/user/email。
- Secret 默认 `CachePreference.Disabled`，并由出站策略决定是否能进入模型请求。
- Extended retention 必须同时通过 Provider 数据保留能力、部署策略和内容敏感度检查。
- cache key、fingerprint、telemetry label 不能包含原文或可逆业务标识。
- Provider 缓存的数据保留属性必须纳入部署文档；例如 OpenAI 的缓存选择与 Zero Data Retention 条件需要按当前官方 [Data Controls](https://platform.openai.com/docs/models/default-usage-policies-by-endpoint) 核验，不能由框架静默假定。

### 6.7 路由策略

第一版只做能力门禁：

- `Required`：过滤不支持所需缓存模式/retention 的模型；无候选则明确失败。
- `Preferred`：不硬过滤，最多在其他质量、权限、上下文窗口约束相同后作为 tie-breaker。
- `Disabled`：Adapter 不发送显式缓存控制。

暂不把“预计命中率 × 缓存价”塞进 `ModelRouter`。先积累按 provider/model/layoutVersion 的 read/write/fresh token、eligible prefix、命中延迟和成本分布；有稳定样本后再以历史窗口估算，并保留无缓存上界作为预算门禁。

## 7. 状态与存储归属

| 数据 | 唯一权威 | 是否进入 AgentState | 说明 |
|---|---|---:|---|
| Agent 定义快照 | AgentState | 是 | 恢复语义 |
| Runtime 组合指纹 | AgentState | 是 | 增加 compiler/layout/cache-policy 版本，不加命中状态 |
| 对话与 ToolResult 引用 | AgentState | 是 | 大正文只存 Artifact ref/preview |
| 历史摘要 checkpoint | AgentState | 是 | 与消息覆盖边界同事务提交 |
| Context section cursor | AgentState | 是 | 增量读取恢复边界 |
| suspension/预算/usage | AgentState | 是 | Status Bar 从这里投影 |
| 工具执行阶段与结果 | Tool ledger | 否，State 只保存已归约步骤/消息 | Intent/Effect/Settlement |
| 所有 Run 内模型调用 intent/settlement | ModelCall ledger | 否，State 只保存 pending/step/summary 投影 | 加 purpose、Prompt lineage 与 encoder version；主调用和摘要调用不分库 |
| 完整可重放请求 | ModelCall ledger | 否 | 仅 `CapturePolicy.Replayable`；敏感策略不允许时不保存 |
| 大正文与派生摘要 | ArtifactStore | 只存引用 | 内容寻址、immutable、授权读取 |
| Skill catalog/body | SkillProvider | 只冻结实际使用的 id/version | 不复制整个 Skill 库 |
| Provider KV cache | Provider | 否 | 可丢失的外部优化 |
| Status Bar | 无独立存储 | 否 | 每次纯投影 |
| lease/heartbeat/fencing | Run/Command Store | 否 | 不进入 Prompt |

因此明确不创建：`ContextStore`、`PromptStore`、`CacheStore`、`StatusStore`、第二份 `CompositionManifestStore`。

## 8. 单回合与恢复时序

### 8.1 正常模型回合

1. Driver 从 RunStore 读取 `AgentState`，执行 composition drift 检查和预算门禁。
2. `ContextAssembler` 取得已经做 tenant/ACL 过滤的来源及稳定版本/游标。
3. 若需要新的 history checkpoint，`ModelCallCoordinator` 先以 `ContextSummary` purpose 写 intent，再调用 Provider；摘要返回后把 checkpoint、辅助 usage 和 ledger settlement 通过同一个 `commitAll` 提交。CAS 冲突不得覆盖新消息，且已发生的调用仍保留结算证据。
4. `PromptCompiler` 纯编译并校验 authority、敏感度、预算、原子组、顺序和 fingerprint。
5. Provider Adapter 根据能力把 `PromptPlan` 编码为最终请求与 cache directive。
6. `ModelCallCoordinator` 以 `Main` purpose 在网络调用前持久化 intent、provider/model、plan/stable-prefix fingerprint、compiler/layout/encoder version、cache policy；Replayable 模式还保存 canonical request。
7. 执行 Provider 调用；任何缓存结果只体现在 usage/headers，不改变状态迁移。
8. Adapter 归一化 read/write/fresh usage，成本模型结算；Kernel 验证预算和响应，Driver 原子提交状态、事件和 ModelCall settlement。

### 8.2 崩溃恢复

- **intent 前崩溃**：没有外部模型效果，可以重新取得来源和编译。
- **intent 后、settlement 前崩溃**：沿现有模型 Unknown 契约处理；只有当前 capture/replay policy 明确允许时才重放，不能因为“可能缓存命中”而擅自重试。
- **history 压缩 dispatch 后、提交前崩溃**：ContextSummary ModelCall 留在 Dispatched/Unknown；恢复不得再次生成并漏计旧调用。宿主按现有不确定调用策略人工确认或明确重试。
- **history checkpoint 提交后崩溃**：checkpoint、辅助 usage 和 ModelCall settlement 已在同一事务，恢复直接复用。
- **Artifact 保存后、Tool ledger 成功前崩溃**：以内容寻址/幂等命名重试；未引用对象由生命周期清理，不把 Artifact 写成功等同工具副作用成功。
- **cache miss/eviction**：完整请求仍然有效，只改变延迟和价格。
- **encoder 或 layout 版本漂移**：恢复时由 composition/model-call lineage fail closed；运维显式迁移或在安全边界重启新 Run。

### 8.3 Streaming

流式模型调用继续由 `Scope` 拥有连接、读取 Fiber 和取消传播；请求 materialization 与 intent 必须在流开始前完成。任何内容已对外可见后，不能切换 Provider 拼接第二份答案。

ZIO 的 `Scope`/`ZLayer.scoped` 用于资源生命周期，child fiber 继承结构化生命周期；不需要把这些能力复制进自制 Kernel 抽象。依据：[ZIO Scope](https://zio.dev/reference/resource/scope/)、[ZLayer](https://zio.dev/reference/contextual/zlayer/)、[Fiber](https://zio.dev/reference/fiber/fiber.md/)。ZIO HTTP 流式客户端同样需要 Scope；批量请求才物化完整 body，详见 [ZIO HTTP Client](https://ziohttp.com/reference/client/)。

## 9. 分阶段实施计划

### Phase 0：冻结基线与决策记录 — 已完成

目标：先把错误边界和兼容影响变成可测试事实。

- 新增 [ADR-0029](0029-context-authority-prompt-lineage.md)，确认“来源信任与指令权限正交”“Prompt Cache 非正确性边界”“不新增 Store”。
- 为 Memory/RAG/World/Summary 权限与 envelope 增加回归测试（`PromptCompilerSpec`、`DefaultContextManagerSpec`）。
- 为 OpenAI、Anthropic、Gemini 收集官方 usage fixtures，并完成 read/write 归一化。
- 对相同 Context 输入重复编译，建立 message/tool/schema 顺序的 golden baseline。
- 长会话、注入、工具大结果、崩溃点、缓存稳定性五类固定 Eval 数据集仍在补齐，不阻止核心切片发布。

完成门禁：问题可以由测试复现；迁移字段已列入 CHANGELOG / compatibility。

### Phase 1：Typed Context 与 PromptCompiler — 已完成

目标：先修安全和语义，不开启任何 Provider 显式缓存。

- 内部 `ContextBlock`、正交 authority/trust/sensitivity/stability。
- Contributor/Memory/RAG/Skill/World Section 先产出 typed block，不直接产出 System message。
- 纯 `PromptCompiler` 和 deterministic ordering/fingerprint。
- Provider-neutral data envelope。
- 纯 Runtime Status 投影，放在动态尾部。
- 删除旧的 Memory/RAG/摘要 System 包装路径。

完成门禁：所有外部/用户/模型生成内容均不能进入高权限通道；同输入重复编译稳定。

### Phase 2：确定性压缩与 Artifact 闭环 — 核心切片已完成

目标：一次物化，恢复复用。

- 已删除 Tool message 的模型辅助压缩路径；Context 不再二次改写 Tool。
- 已统一 Tool externalization 与 Context inline 阈值。
- `read_artifact` 已按当前 Run 域授权读取；range/page 仍待。
- 模型辅助 history summary 尚未完全纳入 ModelCall intent/effect/settlement；生产默认仍是确定性压缩。
- history checkpoint 只在代际阈值触发并复用。

完成门禁（剩余）：辅助摘要崩溃注入；`read_artifact` range/page；对象存储 Adapter。

### Phase 3：Usage、成本与能力语义 — 核心切片已完成

目标：先获得可信数据，再开放缓存策略。

- 已迁移 `cacheReadInputTokens/cacheWriteInputTokens` 与价格模型；JSON 仍保留 `cachedInputTokens` 别名。
- Anthropic/Gemini/OpenAI usage decoder 已归一化；Anthropic `cache_control` wire 仍未启用。
- Boolean 能力已迁移为 `PromptCacheCapability`。
- HTTP/OpenAPI/metrics/trajectory 已按当前字段更新。

完成门禁（剩余）：Anthropic 显式 cache_control contract；OpenAI 显式断点。

### Phase 4：Provider Cache Dialect — 未开始

目标：在不改变业务结果的前提下启用缓存。

- OpenAI：稳定前缀、opaque partition key、能力允许时的 retention/breakpoint。
- Anthropic：自动或最多必要数量的显式断点；按 tools/system/messages 层级验证失效范围。
- Gemini：先利用隐式缓存和稳定排序，不发送不存在的显式控制。
- 为每个 Adapter 建立 wire golden tests：相同 plan/version 字节一致，动态尾部变化不改变 stable-prefix fingerprint。
- `Required/Preferred/Disabled` 接入 capability negotiation；不接入预测命中率路由。

完成门禁：cache disabled 与 miss 的输出/状态/恢复语义一致；跨 tenant 隔离测试通过；Provider 不支持时 fail closed 或按 Preferred 明确降级。

### Phase 5：Skill 渐进披露与证据驱动优化 — 部分已有 Skill catalog，动态 Tool Search 仍待 Eval

目标：只引入固定 Eval 证明有效的动态能力。

- 先上线稳定 Skill catalog + 只读加载，不改变工具权限。
- 比较“全部 Skill 正文预载”和“catalog + 按需加载”的成功率、token、延迟、注入抵抗。
- 累积真实 cache read/write/fresh、eligible prefix、延迟与成本。
- 只有样本稳定后，才考虑 cache-aware tie-breaker；只有大工具集 Eval 通过后，才立项 Tool Search。
- 父子 Run 共享公开静态前缀留到 child run 有真实生命周期与隔离需求后再设计。

完成门禁：新增动态能力必须在固定数据集上带来明确净收益，且安全、恢复与权限门禁零回退。

## 10. 测试与评测矩阵

| 维度 | 必测场景 | 硬断言 |
|---|---|---|
| 权限 | RAG/Memory/World/Skill/summary 内含 prompt injection | 不进入 System/Developer；不能授工具、改预算、伪造审批 |
| 编译 | Map 顺序、Contributor 顺序、空段、Unicode、Schema 顺序 | 同输入同版本 fingerprint 和 wire 稳定 |
| 工具 | 大结果、恶意结果、Artifact 读取、并行 batch | guardrail 看完整结果；模型只见冻结表示；call/result 原子 |
| 压缩 | 阈值边界、CAS 冲突、辅助 intent 前后、压缩响应后崩溃 | checkpoint 可复用；不静默重调或漏计费用；不覆盖新消息 |
| 恢复 | intent 前后、stream 中断、settlement 丢失 | 遵守 Unknown；缓存状态不参与恢复决定 |
| 用量 | OpenAI/Anthropic/Gemini 非流式和流式 fixtures | logical input 与 read/write/fresh 不变量成立 |
| 成本 | read/write 不同单价、未知价格、多币种拒绝 | 不重复计费；未知仍明确为未知/零估算约定 |
| 缓存 | prefix 不变、动态尾变化、tool/schema/policy 变化 | 该命中时前缀稳定；该失效时 fingerprint 必变 |
| 隔离 | tenant/session/secret/extended retention | 无原始身份泄露；禁止不合规共享或 retention |
| 能力 | Unsupported/Implicit/Explicit、Required/Preferred | Required fail closed；Preferred 可安全降级 |

观测指标至少包括：

- `prompt.logical_input_tokens`、`prompt.cache_read_tokens`、`prompt.cache_write_tokens`、`prompt.fresh_input_tokens`；
- `prompt.eligible_prefix_tokens`、`prompt.stable_prefix_changed`、`prompt.layout_version`；
- `context.block_tokens{purpose}`、淘汰/去重/summary/artifact 次数；
- 编译、Provider 首 token、总调用延迟；
- 估算 fresh/read/write/output 成本；
- cache policy 降级与 capability mismatch 计数。

标签只能使用低基数 provider/model/version/kind，不使用 runId、tenantId、正文 hash 全值或 cache key。逐 Run 诊断进入低敏 trajectory，不进入 metrics label。

## 11. 删除与收敛清单

实现阶段应主动删除以下旧路径，避免新旧并存：

1. Memory/RAG/World/Summary 的 `AgentMessage.system` 包装。
2. `compressToolMessage` 的模型辅助 Tool 改写，以及 Tool→System summary。
3. `ModelCapabilities.promptCache: Boolean`。
4. 内部语义含糊的 `cachedInputTokens`，在完成兼容读取后统一为 read/write。
5. 没有任何 Adapter 支持且路由明确拒绝的 `TrustedStatefulDelta` 公共承诺；需要时由真实 Provider 会话协议重新引入。
6. 迁移期结束后的旧 Context 拼接算法和重复排序逻辑。
7. 输入文档中仅为概念完整性提出、但当前没有第二消费者的 Compiler/Graph/Manifest/Cache Store 抽象。

## 12. 兼容与发布策略

- Context 消息角色变化会改变模型行为，应作为用户可见行为变化记录在 CHANGELOG，并以 Eval 而非仅单测放行。
- `TokenUsage`/HTTP/OpenAPI 字段迁移按仓库兼容政策执行；数据库 JSON 先兼容读，再切换写，最后在下一个破坏性窗口删除 alias。
- `ChatModel` 公共 SPI 尽量保持不变：先给 `ChatRequest` 增加带默认值的 provider-neutral prompt metadata/cache directive；只有实证表明消息索引无法表达 Provider 断点时，再在下一 major 版本切换到 `PromptPlan` SPI。
- 每个 Provider capability 只描述当前 Adapter 已通过 contract test 的行为，不照抄厂商营销能力。
- 计划中的类型默认内部可见；不要为了文档对称一次性公开整个 authoring API。

## 13. 推荐的第一批代码切片

按价值、风险与依赖排序，五个切片的当前状态：

1. **Authority 修复**：已完成。typed block + Memory/RAG/World/Summary 非 System 渲染。
2. **Materialization 收口**：核心完成。删除 Tool 模型压缩、阈值统一；ContextSummary 完全复用 ModelCall ledger 仍待。
3. **Prompt lineage**：已完成。纯编译排序、stable/plan fingerprint、ModelCall 低敏记录。
4. **Usage 归一化**：核心完成。Anthropic/Gemini/OpenAI read/write、成本、HTTP/JSON 兼容读取。
5. **Provider cache dialect**：未开始。在前四项稳定后逐个 Provider 开启，先 OpenAI/Anthropic，Gemini 只做隐式布局。

前四个切片完成后，框架即使暂不启用任何显式缓存，也已经得到更安全、更可恢复、更易测试的 Context Runtime；这才是本方案最主要的价值。

## 14. 最终目标状态

完成上述阶段后，框架应具备以下性质：

- **简洁**：仍是一套 Kernel、一套 Driver、一套 Run 事实链；Prompt/Cache 不自立门户。
- **安全**：指令权限由类型和 Provider 编码共同保证，而不是靠正文中的警告语。
- **可靠**：工具结果一次物化，所有 Run 内推理先有 intent，摘要 checkpoint 与调用结算原子归约；cache miss 不影响正确性。
- **可解释**：每次模型调用都能说明用了哪些来源、为何保留、哪段稳定、由哪个版本编码，但默认不泄露正文。
- **经济**：先精确测量 logical/read/write/fresh，再优化缓存和路由；不以猜测换复杂度。
- **可演进**：Skill、Tool Search、父子 Run 和新 Provider 都接在 PromptPlan/Capability/lineage 上，不侵入 Kernel。

这条路线吸收了三份 0915 文档中真正有价值的部分：功能内核、上下文分层、渐进披露、运行状态投影、artifact-first、耐久摘要、稳定前缀和 Provider 缓存适配；同时删除了当前没有真实消费者的宏大抽象，使所有新增设计都落在安全、恢复、成本或可观测性这四类可验证价值上。
