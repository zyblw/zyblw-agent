# 0914 架构说明审查与演进裁决

> 状态：当前审查结论
> 最后核验：2026-09-16
> 输入：[0914 架构说明](文档说明/0914架构说明.md)、当前源码、全模块测试与现行 ADR
> 路线权威：[ADR-0019](architecture/0019-typed-extensions-and-constrained-execution.md)；本文不另建一套竞争路线

## 结论

文档的长期方向基本正确：`zyblw-agent` 应保持小而强的执行内核，把模型、工具、Context、Memory、Workflow、Harness 和外部系统放在受约束的能力面与 Adapter 中。最值得立即采纳的不是再造插件系统，而是让运行时形成可直接测试的 **Functional Kernel + Effectful Driver**。

这一步已经实现：旧 `AgentRuntimeLive` 被删除，纯状态决定进入 `AgentKernel`，ZIO 效果编排进入唯一的 `AgentRuntimeDriver`。没有保留兼容别名或第二套 Runtime，也没有改变 HTTP、数据库、`AgentState` wire shape 或 Flyway。

文档中不少“建议新增”的能力，当前仓库其实已经存在等价或更严格的实现。它们应继续深化，不应换名重写。剩余提案必须由真实消费者、固定评测或生产证据触发，避免把一次正确的内核拆分扩张成平台重写。

## 当前完成度与模块协作

| 能力面 | 当前判断 | 衔接状态 | 主要未完成项 |
|---|---|---|---|
| Functional Kernel / Driver | Foundation | Kernel 只依赖领域、组合与工具策略值；Driver 独占效果和 Store 命令翻译 | 继续按真实变化原因抽纯决定，不追求机械缩行 |
| 单 Agent Runtime | Foundation | Context → Model → Tool → settlement 共用同一 `AgentState`、事件和账本 | 长运行、节点丢失与容量 SLO 生产证据 |
| Application / Worker / HTTP | Foundation/Beta | HTTP 只提交命令，Worker 持 lease 调用同一 Runtime；公共读侧读取同一耐久事实 | 客户端 SDK、发布制品消费与部署演练 |
| Composition / Extension / ExecutionEnvironment | Foundation | 创建时冻结，恢复和每次模型调用前比较；扩展只能贡献或收窄 | 低敏结构化 drift diff/provenance |
| Context / Skill / Artifact | Beta | Contributor 和按需 Skill 不改 Kernel；大 ToolResult 已 artifact-first，Context 只验证冻结表示 | 真实长会话 Eval、`read_artifact` range/page 与对象存储 Adapter |
| Provider / Routing / Fallback | Beta | provider-neutral 请求、能力矩阵、价格和 ModelCall ledger 已接入同一结算 | 真实 Provider 长期 smoke、能力/价格运维证据、显式 cache dialect |
| RAG / Memory | Beta | 经 `ContextSourceResolver` 接入；Memory/RAG/摘要走 User data envelope，不进入 System | OCR/恶意 PDF、对象存储、用户治理和线上质量证据 |
| Workflow / Harness / Side effects / MCP | Experimental | 均使用独立窄 Store/协议并复用核心权限、预算或 Run 边界 | 真实业务 Eval、故障注入、OAuth/供应链与长时恢复证据 |
| Suspension / Trajectory | Foundation/Beta | `Suspension` 统一等待代数；`RunTrajectory` 只读投影供 Inspector/Eval | 宿主 soak、事故回放与人工校准 |

依赖方向目前是融洽的：公开宿主依赖 `AgentRuntime`/`AgentApplication`，Application 构造 Driver，Driver 调用 core SPI，PostgreSQL、HTTP、Provider、MCP、RAG 和 OTLP 等可选模块单向依赖 core；core 不反向 import 这些 Adapter。第二轮收口后，`AgentKernel` 也不再 import `model.RouteDecision` 或 `memory.ModelCallWrite`：路由只向 Kernel 提供“是否已预留调用”的领域事实，模型结算记录由 Driver 在提交边界转换为 Store 写入。

因此当前问题不是模块方向失控，而是成熟度不均衡：核心执行与耐久控制面已经闭环，外围能力多数仍缺生产证据。下一阶段应提高证据和运维完成度，而不是继续增加横向模块。

## 逐项裁决

| 文档提案 | 当前证据 | 裁决 |
|---|---|---|
| Functional Kernel + Effectful Driver | 原 Runtime 混合纯判断和 I/O；核心恢复、预算、结算可纯化 | **立即采纳，已落地 ADR-0028** |
| 不做 Everything-is-Plugin | 当前安全、持久化、预算都依赖闭合控制流 | **采纳为硬约束**；Kernel、commit、fencing、budget settlement、terminal transition 不开放插件替换 |
| ZLayer 作为依赖图 | Application、Provider、Store、Worker 已按 scoped layer 装配 | **保留并深化**；不新增自制 DI/服务图 |
| 一等 Capability | 已有 typed `ExtensionContribution`、`ToolProvider`、`ContextContributor`、`SkillProvider` | **吸收语义，不立即重写 API**；出现两个以上需要同一 acquire/release/validate 生命周期的真实贡献者后再上收 |
| Capability per-run Scope | ZIO `Scope`、`acquireRelease`、worker/stream scoped fiber 已负责生命周期 | **按资源需要采用**；不为纯值贡献创建 Scope 或 Fiber |
| Typed lifecycle / hook DAG | Extension 已有窄 typed seam，但没有任意 hook 排序协议 | **有条件采纳**；只有跨能力排序冲突形成真实需求时引入显式阶段和 DAG，禁止 `before/after(String)` 万能 Hook |
| On-demand Capability / Tool Search | `SkillCatalog` 已目录化和按需物化；320 工具 Eval 已作门禁，Runtime API 未扩张 | **证据驱动**；没有收益证据前不把按需目录变成公共装配面 |
| CompositionManifest | `RuntimeCompositionFingerprint` 已逐字段保存并 fail-closed 比较 | **不做换名迁移**；下一刀应增加可操作 diff/provenance，而不是再存第二份组合事实 |
| Toolset 高于 Registry | `ToolProvider` 已能组合目录，Registry 强制唯一并执行策略 | **保留现状**；仅在动态目录与按需搜索落地时增加只读 Toolset view，不替换执行 Registry |
| Context contribution / world-state delta | `ContextContributor`、`ContextSection`、游标和 lineage 已落地 | **已采纳**；继续补长会话质量与缓存收益证据 |
| Artifact-first 大结果 | Artifact Store、Run 域、`ToolResult.Externalized`、`read_artifact` 已落地；Context 不再二次压缩 Tool | **已采纳核心切片**；继续补 range/page、对象存储与长会话 Eval |
| Memory 极简核心 | Memory 已在 SPI/治理层，不在 Agent loop 中构造“认知模型”；正文走 User envelope | **已采纳**；不把 reflection/persona/world model 塞进 Kernel |
| 不建 MultiAgentEngine | 已有 Handoff、HumanTask、Workflow 子图和 Harness Goal/Plan | **采纳**；未来复用 child run/handoff/join 原语，多 Agent 仍由固定 Eval 证明后开放 |
| 层级预算 | Run 硬预算与 Harness Goal 跨 Run 预留/结算已落地 | **部分已采纳**；租户/组织层只在宿主有真实配额消费者时追加 |
| Workflow 与 Agent 分离 | Workflow 有独立 checkpoint/lease/wait/signal，Agent 保持动态 loop | **已采纳**；禁止把 Workflow 图解释器塞进 Kernel |
| 统一 suspension / interrupt | `AgentState.suspension`、`agent_suspensions`、到期 Worker 已落地；审批是 `Suspension.Approval` | **已采纳核心切片**；继续用同一代数覆盖 Workflow wait 的公共过期/取消语义 |
| 统一 Trajectory | `RunTrajectory` 只读投影已收成时间线、账本、挂起、命令与组合对照 | **已采纳只读投影**；不新增事实源或保存隐藏推理 |
| Provider Capability Matrix | `CapabilityMatrix` 和 model requirements 已逐字段 fail-closed | **已采纳**；继续由 Provider contract 与真实 smoke 驱动 |
| Durable fallback | 模型 fallback 已区分 retryable/fallbackable，未结算调用进入 Unknown | **已采纳**；不得在流已可见或结算不确定后切换并拼接第二个答案 |
| Side-effect contract / outbox / inbox / compensation | 工具账本、Unknown、幂等、outbox/inbox/compensation 已存在 | **已采纳但仍 Experimental**；优先补真实 transport、事务和生产恢复证据 |
| ExecutionEnvironment | Local、MCP sandbox、PermissionProfile、审批主体绑定已落地 | **已采纳**；下一步是攻击矩阵和真实 OCI 证据，不是继续加环境枚举 |
| 外部 Durable Backend SPI | RunStore/PostgreSQL 已是唯一耐久事实，Workflow 也有独立受控 Store | **暂缓**；没有第二个生产后端前不抽象，任何外部引擎不得成为第二事实源 |
| Free Monad / 通用 Action Interpreter | ZIO 已提供 typed effect、Scope、Fiber 和 Layer | **拒绝**；会复制效果系统并扩大 API 面 |

## 落地后的目标边界

```text
AgentApplication / Worker / HTTP
              │
              ▼
     AgentRuntimeDriver (ZIO)
 clock / scope / fiber / model / tool / store
              │ facts
              ▼
         AgentKernel (pure)
 decision / validation / state transition / events
              │ transition
              ▼
 RunStore commit / commitFenced / ledgers
```

边界规则：

1. Kernel 只接受不可变值并返回 `Either`、决定或 `Transition`，不得读取 Clock、环境或 Store。
2. Kernel 只返回领域状态、事件和模型结算事实，不依赖路由决定或 Store 写入类型；Driver 负责取得事实、执行 effect 并翻译持久化命令，但不能绕过 Kernel 中已经建立的预算、恢复和终态规则。
3. 所有耐久写继续通过同一个 CAS/fenced commit；纯化不等于把状态写拆成多个事务。
4. Capability、Extension、Tool、Context 和 Provider 只能贡献受限数据或实现窄端口，不能得到 Runtime 内部状态的写权限。
5. ZIO `Scope` 拥有 Fiber、队列、连接与临时资源；纯对象不伪装成需要生命周期的服务。

## 本次实现范围

`AgentKernel` 当前承接：

- `RunStatus` 的恢复分派；
- 动作前预算和 Provider 结算后预算的不同边界语义；
- 模型响应、累计 usage、工具调用预算、DurableToolPlan 与 ModelCall ledger settlement；
- 模型工具调用与新建 DurableToolPlan 的一一对应校验；
- 工具批次按 ordinal 的确定性提交、游标推进、失败计数和事件；
- 完成、审批暂停、失败、取消的状态与领域事件；
- 合法源状态约束、审批主体漂移后的暂停刷新与终态迟到写保护；
- Completed/Suspended outcome 重建，含 input/output/cached/reasoning 完整 token 用量；
- 审批 ID、终态分类、最终答案和待处理工具等纯判断。

`AgentRuntimeDriver` 保留：

- Provider/Tool/Guardrail/Context/Store 调用；
- Clock、Fiber、Scope、stream 与中断传播；
- lease/generation fencing、CAS 和事务提交；
- Effect 失败到 typed error/Unknown 的收口。

这是结构重建的第一条完整垂直切片，不是终点。Driver 仍然较大，但后续只按“变化原因”继续抽纯决策或窄执行阶段；不以文件行数为目标制造单实现 trait。

## 后续顺序

顺序继续服从 ADR-0019，而不是因为文档列了 42 节就并行开工：

1. **Wave 0：生产证据优先。** 节点丢失、数据库主备、长时 soak、SLO、真实 Provider/RAG、事故回放。
2. **继续收窄 Driver。** 每次先补 characterization/conformance test，再抽一个纯迁移族；不得同时改 durable schema。
3. **组合可解释性。** 在现有 fingerprint 上提供低敏、结构化 drift diff 与 provenance；不增加并行 Manifest 存储。
4. **按需目录实验。** 320 工具 Eval 已作为门禁存在；达标并证明收益前不把 Tool Search 变成公共装配面。
5. **显式 Prompt Cache dialect。** Authority / PromptCompiler / usage 归一化已落地；OpenAI/Anthropic 显式断点仍待 wire contract。
6. **分支与多 Agent。** 复用 Run、Handoff、Workflow 原语；只有评测证明收益时才从 Experimental 晋级。

## 删除与兼容原则

- 当前为 `0.9.0` 绿场阶段，低层 `AgentRuntimeLive` 直接删除并由 `AgentRuntimeDriver` 替代；公开 `AgentRuntime` 与 `AgentApplication` 不变。
- 不保留旧别名、deprecated 双轨、第二 checkpoint、metadata 镜像或双写迁移。
- 已发布/权威数据库契约仍受保护；Kernel/Driver 拆分没有改 wire shape。统一 `Suspension`、`agent_suspensions` 与 Artifact Run 域属于 0.9 绿场 V001 折叠，不是旧库原地升级。
- 后续若改变 Scala API、HTTP、数据库、durable JSON 或坐标，必须分别写出影响、迁移、回滚和验证，不能用“全面重构”跳过事实兼容。

## 验证结论

- 纯 Kernel 有直接测试，覆盖恢复、预算等号边界、模型结算、响应/计划一致性、合法源状态、终态迟到写、工具预算、乱序结果、缺失 ordinal、完成/暂停完整 usage、失败与取消。
- 既有 Runtime、ModelCall、审批主体、崩溃恢复、Worker、HTTP、Provider、Workflow、RAG 和持久化测试继续验证行为等价。
- 本次全模块 `scalafmtCheckAll`、`scalafmtSbtCheck`、`testFull` 通过；依赖真实 PostgreSQL 的 opt-in 套件仍按环境忽略，不能把忽略项表述成已验证生产证据。
