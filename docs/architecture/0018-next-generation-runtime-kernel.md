# ADR 0018：下一代 Runtime Kernel — Durable Truth、ModelCall 与 ZIO 原生执行

> 状态：**Accepted / 部分实现**（P0–P2 已纳入 `0.9.0` 当前合同）
> 日期：2026-08-20
> 影响：`0.9.0` Runtime / 状态 / 持久化 / Inspector 当前基线与后续演进
>
> 配套工作手册：[next-generation-runtime.md](next-generation-runtime.md)
> 后续扩展：[ADR-0019](0019-typed-extensions-and-constrained-execution.md) 在本 ADR 铁律之上新增 Typed Extensions、ApprovalSubject、ExecutionEnvironment、ContextSection 与 stable AgentProtocol 五项决策,并把实施顺序重排为 Wave 0–3

## 背景

`zyblw-agent` 当前且唯一支持的版本线是 `0.9.0`。仓库已经具备可恢复的 Durable Runtime、工具执行账本、lease/fencing、审批、Context、RAG、Workflow 与 Inspector。ADR-0002/0005/0008/0010/0016 把「模型只提议、Runtime 控制执行、ZLayer 管依赖、Snapshot 管恢复、Ledger 管副作用」写成了控制不变量。

同时出现了两类压力：

1. **模型交互仍是黑盒。** 当时 `AgentRuntimeLive` 在调用 Provider 前实时组装 `ChatRequest`，调用后即丢弃。系统能恢复「执行到哪了」，不能证明「第 N 次模型调用当时看到了什么」。该实现已由 [ADR-0028](0028-functional-kernel-runtime-driver.md) 替换为 `AgentKernel` + `AgentRuntimeDriver`；Prompt lineage 由 [ADR-0029](0029-context-authority-prompt-lineage.md) 补齐。
2. **Kernel 有膨胀风险。** 若把 Conversation Tree、Lane、Action Interpreter、Harness、Multi-Agent 一次塞进核心，会在 ZIO 之上再造一套 Runtime，并制造多份互相竞争的 Durable Truth。

Pi Agent / Durable Harness 与 DeepSeek Harness 提供了有价值的设计证据，但都不是本仓库的目标架构。Pi 优化本地 Coding Agent 的 DX 与 session tree；DeepSeek 优化 TypeScript 下的 capability seam 与 session log reconstruction。`zyblw-agent` 的差异化应落在：**生产级、多租户、可取消、可恢复、可治理的 Scala / ZIO Agent Application Runtime**。

## 需要解决的问题

1. 如何在不丢失已证明不变量的前提下，让 Model Call 获得与 Tool 同级的耐久语义。
2. 如何只允许「一类事实一个权威来源」，避免 Trajectory / Event / Conversation / State 四套并存。
3. 如何明确 ZIO 与 PostgreSQL 的职责，避免在 ZIO 上再实现 scheduler、生命周期或通用 Effect Interpreter。
4. 如何让 RAG / Skill / Goal / Subagent 成为 Capability，而不是修改 Kernel 的默认理由。
5. 如何诚实对待 `0.6.x` 兼容：允许下一阶段 breaking，但禁止假兼容、禁止改写已发布 migration。

## 候选方案

### A. 最小补丁：在现有 `AgentState` 旁增加独立 `RunTrajectoryStore`

保留上帝快照，另建一套模型请求日志。迁移成本最低。

否决：立刻制造第四套权威；Runtime 仍从 `AgentState` 恢复，Inspector 从 Trajectory 恢复，两者必然漂移。

### B. 三类 Durable Truth + ModelCall Ledger + Trajectory 投影（推荐）

把所有跨进程事实分成：

1. **Current Execution State** — 下一步做什么（可演进自今日 `AgentState`，优先 typed phase，而不是 80 个 `Option`）。
2. **Immutable Facts** — 已经发生什么（精选 durable events / 交互记录 / 按 CapturePolicy 保存的 canonical request）。
3. **Ledgers** — 外部 effect 处于什么状态（今日 `tool_executions` + 新增与之同构的 ModelCall ledger + usage + approval）。

底层一次 PostgreSQL 事务可以同时推进三者。Trajectory / Inspector / Eval / Telemetry 只是投影。Model Call 采用与 Tool 相同的 Intent → Effect → Settlement / Unknown 三明治。ZIO 负责执行；PostgreSQL 负责跨进程事实。

### C. Clean-slate：完整 Event Sourcing，或 Pi Session fold，或通用 Action Interpreter

从零重建 session log 为唯一 SoT，或在 ZIO 上再写一套 Action VM。

否决（当前无证据）：恢复成本、隐私分层、多 Worker fencing、以及「ZIO 已经是 effect runtime」都与 C 冲突。Pi v2 本身仍有 `HarnessNotImplemented` 路径，不能当成熟实现模板。

## 决定

采用 **方案 B**。开发原则：

> Nothing is sacred, but proven invariants are expensive assets.
>
> 没有任何旧 class 必须保留；已经通过失败、并发、安全场景证明正确的不变量，除非新设计明显更强，否则不要丢掉。

最终定位：

> A typed and durable Agent Application Runtime for production Scala 3 / ZIO 2 systems.

不成为 Scala LangGraph、Scala Pi、Scala DeepSeek 或「功能最多的 Agent 库」。不把 local terminal coding agent 作为唯一优化目标。

### 六条铁律

1. **One durable truth per fact.** 投影可重复，权威不可重复。
2. **ZIO is the execution runtime.** 不自建 generic scheduler、resource lifecycle framework、cancellation token framework、Action VM、plugin lifecycle engine。
3. **PostgreSQL owns durability; ZIO owns execution.** 禁止 `TRef[RunState]` 与数据库 `RunState` 双权威。STM 是内存事务，没有 durability。
4. **Every uncertain external effect has Intent → Effect → Settlement（或 Unknown）。** 尤其包括 Model Call。
5. **Model-visible means reconstructable**，且必须受显式 `CapturePolicy` 约束。
6. **Kernel grows only for universal invariants.** RAG / Memory / Skill / Goal / Plan / Subagent / MCP / PDF / Provider SDK / UI 不能成为修改 Kernel 的默认理由。

### Kernel 准入

新增能力进入 `agent-core` Runtime Kernel 前只问：

> 如果明天世界上没有 RAG、Skill、MCP、Multi-Agent，这个能力对于一个可靠的 Agent 执行仍然成立吗？

成立：Run state、Cancellation、Budget、Model call、Tool execution、Approval、Durable transition、Fencing、Effect uncertainty。

通常不成立：RAG、Memory、Skill、Goal、Plan、Conversation Branch、MCP 协议细节、Subagent。它们走 Capability / Harness，经 `ContextContributor` 或独立 SPI 接入。

### 必须保留的不变量（可换实现形式）

- lease / heartbeat / generation fencing / `commitFenced`
- optimistic concurrency（`commit(expectedVersion, state, events)` 的原子形状）
- approval-before-effect
- fail-closed tool permission（空白名单 ≠ 全部工具）
- `ToolExecutionStatus`：`Prepared → Running → Succeeded | Failed | Unknown`
- 非自动可重放工具在 `Unknown` 时暂停，不自动重放副作用
- recovery cursor（恢复是读当前耐久状态，不是猜 event 缺口）
- 结构化并发与 ZIO Scope 资源寿命
- 生产入口禁止数据库失败时静默回退内存

**禁止**因为看到 Pi 的 Effect Sandwich 就再造 `ToolIntentV2` / `ToolLedgerV2`。正确做法是让 Model Call 达到 Tool 已经证明的等级。

### Trajectory 不是第四套 Runtime

除非未来证明必须拥有独立权威，禁止：

```text
RunStore + RunTrajectoryStore + ConversationStore + EventStore + ModelInteractionStore
```

各自成为 writer。允许 query SPI 分开；不允许事务边界被 SPI 切开。

### 小型 Decision Layer，不是第二套 Runtime

允许：

```text
RunState → pure decision → NextStep → execute: ZIO[RuntimeEnv, AgentError, Transition]
```

禁止：Free Monad、通用 Action VM、Workflow bytecode、在 ZIO Fiber 之外再实现一套调度器。

### ZIO 与安全 Scope 不得混名

- **ZIO `Scope`**：资源寿命（HTTP client、MCP、sandbox、provider stream）。
- **Authorization / Grant / PermissionSet / RunContext**：安全授权。

ZLayer 构建**应用级**稳定依赖图，不在每个 Run 或每个 Step 重建。Run 级配置是 immutable data（profile、model ref、allow-list、budget、authorization context）。

### 与既有 ADR 的关系

| 既有 ADR | 本决策如何对待 |
|---|---|
| 0002 Snapshot + 精选事件 | 思想保留，映射为 Execution State + Immutable Facts；不升级为完整 Event Sourcing |
| 0003 工具安全管道 | 保留；任何 Skill / Profile / MCP 不得绕过 |
| 0005 恢复与 at-least-once | 保留；Model Call 的 uncertain window 显式为 Unknown |
| 0007 遥测低敏 | 保留；Telemetry ≠ Durable Truth |
| 0008 ZIO 原生控制面 | 加强：ZIO 就是执行 Runtime |
| 0010 命令队列与 fencing | 实现可重写，不变量必须保留 |
| 0016 Agent / Harness / Workflow | 三层仍成立；Harness 降为 P2，不得成为第二套 LLM loop |

「`RunStore` 是唯一生产事实来源」升级为：**一次逻辑转换只通过一个 Durable 事务边界提交；该边界内可以同时有 state、facts、ledgers，但同一事实不得在事务外再有第二份权威。**

## 实施顺序（详见工作手册）

禁止并行实现 P0–P4。

1. **P0 Kernel**：typed RunState 重审、CanonicalModelRequest、ModelCall Intent/Settlement/Unknown、同一事务、请求重建、crash injection、Inspector 投影。Tool 的 online retry 与 crash replay 已拆（不换 ledger）。
2. **P1 Composition / DX**：RuntimeProfile、CapabilityDescriptor、ContextContributor、CapturePolicy 产品化、更小的 Public API。
3. **P2 Harness**：Goal / Plan / Todo / Skill / Steering / FollowUp，作为耐久任务状态。
4. **P3 Branch / Replay**：Conversation Tree、Fork、Inspection Replay。Schema 可预留 `parentEntryId`，不提前实现 tree runtime。
5. **P4 Advanced**：Lane、Subagent、Code Mode、A2A — 仅当 eval 证明单 Agent 不够。

P1 当前实现还把每次模型提出的工具集合冻结进 v5 `DurableToolPlan`：注册工具的 definition 与安全元数据只保存规范化
SHA-256，规划时审批要求保存为 callId 集合。恢复先完成契约比较，再进入审批或副作用；更严格现场策略可以追加审批，
放宽策略不能取消已冻结要求。该计划级 fencing 补充 Run 级 composition fingerprint，不引入第二套 Runtime 或事实源。

## 兼容与迁移

- 已发布 Flyway `V001`–`V003` **不得修改**。
- P0 优先追加 `V004`（或等价）ModelCall ledger，与 `tool_executions` 同构；不因本 ADR 推翻全部表。
- 若某一步必须改变 recovery 语义：按 Scala API、HTTP、数据库 schema、durable payload、Maven 坐标**分面**写清 breaking，禁止一行 “Breaking change” 覆盖所有表面。
- 宁可明确不兼容，不要假兼容。
- 禁止长期 `RuntimeV2` 与旧 loop 并存；过渡期必须有删除里程碑。

## 风险

- 把 `AgentState` 收成 typed phase 时，codec / 旧 Run 恢复需要明确 upcast 或拒绝策略（UNKNOWN：现网是否存在必须平滑升级的 `state_json`；落地前用测试与部署调查关闭）。
- Replayable CapturePolicy 会显著增加存储与隐私面；生产默认必须是 MetadataOnly。
- 若有人把 Trajectory 投影误当成恢复路径，会重新引入双真相。测试必须证明 Inspector 失败不影响 `commit`。

## 未选择原因（摘要）

- 独立 TrajectoryStore：第四套权威。
- 完整 Event Sourcing / Pi 单写者无多记录事务：与多 Worker fencing 和 PostgreSQL 原子推进冲突。
- 通用 Action Interpreter：ZIO 已是 effect runtime。
- 第一阶段做 Tree / Lane / Harness / Multi-Agent：违背 Minimal Core，且当前业务主路径不需要。

## 参考

- 现行实现：`AgentKernel`、`AgentRuntimeDriver`、`RunStore`、`ToolExecutionRecord`、`PromptCompiler`、ADR-0002/0005/0008/0010/0016/0028/0029
- [Pi Durable AgentHarness design](https://github.com/earendil-works/pi/blob/main/packages/agent/docs/harness-v2.md)（设计参考，非实现模板）
- DeepSeek Harness：`Model-visible means logged` 与 request reconstruction invariant
- [ZIO](https://zio.dev/reference/core/zio.md)
- [ZIO Fiber](https://zio.dev/reference/fiber/fiber.md)
- [ZIO Scope](https://zio.dev/reference/resource/scope.md)
- [ZIO STM 导论](https://zio.dev/reference/index-14.md)（内存事务，无 durability）
- [ZIO Dependency Injection](https://zio.dev/reference/di/dependency-injection-in-zio.md)

开发步骤、Adoption Matrix、Crash/Test 矩阵与阶段完成模板见 [next-generation-runtime.md](next-generation-runtime.md)。
