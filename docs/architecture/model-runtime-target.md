# Model Runtime 目标架构

> 状态：Phase 1 单次路由闭环已实现（Experimental）；完整目标仍 Proposed
>
> 最后核验：2026-09-05
>
> 决策来源：[ADR-0021](0021-provider-neutral-model-protocol.md) … [ADR-0026](0026-provider-capability-model.md)
>
> 事实来源： [现状审计](model-runtime-current-state.md)、现行 `ChatModel` / `AgentRuntimeDriver` / `AgentKernel` / `ModelCall`
>
> 配套： [路由](model-routing.md) · [预算](model-budget-and-cost.md) · [多模型执行](multi-model-execution.md)

## 当前落地边界：Phase 1 单次调用闭环（Experimental）

已实现的入口是 `RuntimeProfile.modelRouting: Option[ModelRoutingPolicy]`，默认 `None`。
开启后主调用走 `ModelRequirement → 固定候选顺序/硬过滤 → RouteDecision → ModelCall TX1 → ChatModel → TX2`。
运行编排仍内聚在 `AgentRuntimeDriver`；纯状态归约由 `AgentKernel` 承担。此阶段不新增 `ModelRuntime` Service 或模块。

- `ModelProfile` 仅 Fast / Standard / Reasoning。Vision 是能力要求；其余档位待真实调用与 Eval 再增加。
- `ModelSettings.requirement` 可选；未开启路由却声明需求会显式失败。Role 的旧冻结绑定继续按显式模型处理。
- 开启路由时显式 provider/model 必须成对且在候选目录中；不会擅自换模型。无显式模型按 Profile 过滤后取首个合格候选。
- 能力来自已注册 Adapter 的 `ModelCapabilities`；请求实际图片、工具和指定 tool choice 也参与检查，不能靠省略需求绕过。严格 Schema
  只有在 `ModelRequirement.strictToolSchema=true` 时作为硬约束；普通 `ToolDefinition.strict` 仍服从既有 Adapter 兼容策略。
- 配置通过已有 `RoutedChatModel` 注册表取 Adapter；不允许嵌套 `FallbackChatModel`。
- `RouteDecision` 为 ModelCall 的可选 JSON 字段，不建表、不改迁移；旧 JSON 缺字段仍可读取。内存与 PostgreSQL 的状态转换都禁止改写已冻结 Decision。
- 路由启用时 `CapturePolicy.Disabled` 只禁止正文采集，最小执行账本仍按 MetadataOnly 写入；未启用时保持旧 Disabled 语义。
- 路由配置内容摘要和价目内容摘要进入恢复兼容检查。即使 version 文本未变，改候选顺序或价格仍拒绝旧 Run 静默继续。
- 新 Run 可通过替换候选顺序换主模型；旧 Run 恢复需要原配置，或显式新建 Run。此阶段不实现历史配置自动查找。
- 原生 providerOptions 要求显式模型；候选路由只允许 FullSnapshot。辅助压缩/记忆调用尚未接入本面。

可执行接入与验收见 `ModelCallRuntimeSpec`；配置示例：

```scala
val routing = ModelRoutingPolicy(
  version = "standard-v1",
  candidates = Chunk(
    ModelRouteCandidate(ModelRef("primary", "standard-model")),
    ModelRouteCandidate(ModelRef("secondary", "standard-model"))
  ),
  defaultMaxOutputTokens = 1024,
  sensitivityFloor = DataSensitivity.Internal
)
val profile = RuntimeProfile(modelRouting = Some(routing))
// 将 profile 传入已有 AgentApplicationConfig 或 AgentRuntimeDriver.layerWithProfile。
// primary/secondary 必须是已有 Adapter 的注册身份，不能是密钥或任意 URL。
```

宿主也可把同一 `ModelRoutingPolicy` 的 JSON 放入
`zyblw.agent.runtime.model_routing_policy`（环境变量为
`ZYBLW_AGENT_RUNTIME_MODEL_ROUTING_POLICY`），由现有 `AgentApplicationConfigLoader` 在启动期解析和校验。
解析错误不会回显原始配置值。JSON 只允许路由元数据，禁止放 API key 或任意 endpoint；Provider 资源仍由宿主 ZLayer 装配。

阶段后置项：同模型 Retry、Fallback、动态健康/限流、预算预留与未知费用对账、完整过滤输入回放、租户策略解析、
Qwen 真实质量评测、Planner。Qwen 的一级 Chat Completions preset、区域配置和 smoke 入口已经具备；下面的完整目标图与 API 是后续设计，不能当作当前实现。

zyblw-agent 的下一步不是模型聚合 SDK，而是在现有 Durable Runtime 上增加 **推理资源调度面**。

```text
Business Agent
      │  不写厂商模型名
      ▼
ModelRequirement(profile, capabilities, sensitivity)
      │
      ▼
ModelRuntime
      ├── Catalog / Profiles / Pricing(version)
      ├── RoutingPolicy（确定性）
      ├── CostBudget（同一 BudgetState）
      ├── ProviderHealth / RateLimit / CircuitBreaker
      ├── Retry / Fallback（基础设施）
      └── CostLedger / ModelCall 账本
              │
              ▼
        RouteDecision
              │
      ChatModel adapters（Qwen / DeepSeek / Gemini / …）
              │
              ▼
        ChatResponse（已有 ADT）
              │
              ▼
        Agent Runtime（工具 / RAG / ZIO Service）
```

---

## Before / After

**Before（今天）**

```text
AgentDefinition.modelSettings.provider/model
        或 ModelRole → 1:1 binding
        或 ModelPolicy 稀疏覆盖
                │
                ▼
        RoutedChatModel.select(name)
                │
                ▼
        ChatModel.stream
```

业务或部署必须知道 `deepseek` / `gemini` / 具体 model id。没有步骤级能力档、没有可解释的 RouteDecision、没有同模型 Retry。

**After（目标）**

```text
Agent 或 Workflow 步骤
        │
        ▼
ModelRequirement(Standard, ToolCalling+StructuredOutput)
        │
        ▼
ModelRuntime.execute
        │
        ├─ 硬约束过滤
        ├─ 软评分
        └─ RouteDecision 入账本
                │
                ▼
        现有 ChatModel.stream
```

改 `profiles.standard.primary` 不改 Agent、Workflow、Tool、RAG、业务 Service。

---

## 四个概念必须分开

| 概念 | 是什么 | 不是什么 | 现有对应 |
|---|---|---|---|
| **Agent** | 业务角色：指令、工具、权限、知识 | 不是某个厂商的 wrapper | `AgentDefinition` |
| **ModelProvider** | 访问协议 + Adapter | 不是 Agent 子类 | `ChatModel` / `ModelProvider` |
| **Model** | `Provider + modelId + endpoint + version` | 不是 Profile | `ModelSettings.provider/model`、Catalog 条目 |
| **ModelProfile** | 能力/成本等级：Fast、Standard、Reasoning… | 不是 `DeepSeek` 的别名 | **新建**；`ModelRole` 仍表示任务角色 |

`ModelRole`（planner / summarizer / extraction）继续存在。配置把它映射到默认 Profile，再由 Router 选 `ModelRef`：

```text
ModelRole --config--> ModelProfile --Router--> ModelRef candidates
```

若 Agent 已显式写 `provider`/`model`，Router 只做能力、数据策略、预算硬校验，并记录 `legacy-explicit-model`。不覆盖作者选择。

---

## 目标 API 体验

业务与 Runtime 内部都应趋近：

```scala
modelRuntime.execute(
  requirement = ModelRequirement(
    profile = ModelProfile.Standard,
    capabilities = Set(ModelCapability.ToolCalling, ModelCapability.StructuredOutput)
  ),
  request = chatRequest,          // 现有 ChatRequest，不是新 ModelRequest
  context = executionContext      // runId、tenant、budget 快照、sensitivity
)
```

`ModelRuntime` 负责：route、budget reserve、call、retry、fallback、usage、cost、trace、persist。Agent loop 继续负责：context 装配、guardrail、tool plan、审批、终止。

不把 `execute` 做成几十个单方法 Service。建议内聚为：

- `ModelRuntime`：对外入口
- `ModelRouter`：纯决策（可单测、可回放）
- 现有 `ModelCatalog` / `ModelPolicySource` / `ModelPriceBook` 的演进，而不是再注册五个环境服务

---

## 后续候选 Profile（当前只实现 Fast / Standard / Reasoning）

| Profile | 语义 | 典型步骤 |
|---|---|---|
| Fast | 低成本、低延迟、高吞吐 | 分类、抽取、翻译、简单总结 |
| Standard | 默认生产业务 | 普通 Tool Agent、RAG 问答 |
| Reasoning | 复杂分析、规划、诊断 | Planner、根因分析 |
| Expert | 极高价值 / 高困难，稀有升级 | Verifier、高风险决策 |
| Vision | 图片 / 截图 | 商品图、后台截图 |
| Research | 长文档综合 | 报告、书籍 |
| Coding | 代码分析与修改 | 仓库内 coding agent |

以后可加 LongContext / Extraction / Translation / Classification / Safety，但第一版不扩张。

Profile **只表达需求**。禁止 `Reasoning == DeepSeek` 写进 Scala。候选列表属于部署配置。

---

## 包与模块

不新增 sbt module。优先：

```text
agent-core/src/main/scala/com/zyblw/agent/model/
  ChatModel.scala          （保持）
  profile/                 （ModelProfile, ModelRequirement）
  routing/                 （Router, RouteDecision, RoutingPolicy）
  FallbackChatModel.scala  （收敛进 Runtime 后仍可作测试替身）
```

`agent-providers` 继续放 Adapter。`agent-postgres` 只在 RouteDecision / pricing version 需要耐久时 append 列或邻接 JSON，不为 alias 建表。

---

## 接入现有 Runtime

唯一第一刀生产接合点：`AgentRuntimeDriver.persistAndInvokeModel` / `invokeModel`。

```text
loop
  → ContextManager.build
  → 从 AgentDefinition / 步骤策略得到 ModelRequirement
  → ModelRuntime.execute
  → 仍把 ChatResponse 交给现有 tool plan / complete
```

约束：

- 不新增 `RunCommandPayload.CallModel`
- `RouteDecision` 挂在 `ModelCallExecutionRecord`（或同事务邻接字段），遵守 ADR-0018「一类事实一个权威」
- 取消 / 超时 / lease fencing 语义不变：中断必须传到 HTTP 流
- 模型 retry 不得重入已结算 Tool

第二刀旁路：`LlmContextCompressor`、`LlmMemoryExtractor`、`VisionPageDocumentLoader`。它们默认走 Fast / 明确 Profile，不得自己点名厂商。

---

## Workflow 与 Harness

第一阶段 **不改** Workflow 引擎。

后续 `ModelStep` 的正确形状是 Agent-as-node：

```text
WorkflowNode
  → AgentRuntime.run(definition.copy(requirement = Reasoning), ...)
  → 子 Run 自带 ModelCall 账本
```

禁止 `DeepSeekStep`。Harness `Plan` / `Todo` 仍是任务支架，不是 LLM 产出的可执行 `TaskPlan`。Planner 编译见 [多模型执行](multi-model-execution.md) 与 [ADR-0024](0024-planner-and-durable-execution.md)，排在 Phase 6。

---

## 配置替换是验收标准

部署应能表达（示意，不是文件格式承诺）：

```text
profiles.standard.candidates = [qwen-standard, deepseek-standard]
profiles.standard.primary    = qwen-standard
```

logical alias 在 Catalog 里解析到 `Provider + modelId + endpoint`。密钥只通过 `SecretRef` / 环境变量，永不进 Catalog JSON、Event、trace。

切换 Standard primary 后：

- Agent 代码为零修改
- 已冻结的旧 Run 仍按组合指纹 / 显式 model 恢复，不得被新 Catalog 改写历史调用

---

## 明确不进入本面的东西

- 第二套 Agent loop 或 Action VM
- 默认多 Agent 辩论 / 投票 / marketplace
- 把 Embedding 和 Chat 绑成同一厂商
- 在 Adapter 里偷偷做安全或预算过滤
- 把完整 Prompt 作为默认审计字段

Kernel 准入仍用 ADR-0018：如果明天没有 RAG / MCP / 多 Agent，这个能力对一次可靠的模型调用是否仍然成立？成立才能进 `ModelRuntime`。
