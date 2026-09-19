# Agent Runtime

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-09-16
>
> 事实来源：对应模块源码、测试与构建定义

`AgentRuntime` 是唯一运行入口：`run`/`resume`/`recover` 返回 `RunOutcome`，对应的 `runEvents`、
`resumeEvents`、`recoverEvents` 返回有界 `ZStream[Any, AgentError, AgentEvent]`。`inspect` 返回最新
`AgentState`，`persistedEvents` 按 sequence 游标读取耐久事件。

内部实现分为无 I/O 的 `AgentKernel` 与唯一效果外壳 `AgentRuntimeDriver`。Kernel 只根据 `AgentState` 和 Driver 已取得的事实
决定恢复路径、验证预算、归约模型/工具结算及重建 outcome；Driver 负责 Clock、Context、Provider、Tool、Fiber/Scope 和
`RunStore.commit/commitFenced`。Kernel 会拒绝非法源状态、模型工具调用与耐久计划不一致以及终态迟到回写；它只返回领域结算事实，
由 Driver 翻译成 `ModelCallWrite`，因此不依赖路由或持久化 package。Kernel 不是可替换插件，也不拥有第二份状态。完整决定见
[ADR 0028](architecture/0028-functional-kernel-runtime-driver.md)。

跨节点 HTTP 订阅使用 `DurableRunEventStream`，它以数据库侧 `limit` 分页、验证 sequence 连续性，并通过
`Last-Event-ID` 恢复；不要把单进程 `RunObserver.hub` 当成集群事实源。详细协议见
[跨节点耐久事件流与 SSE](durable-streaming.md)。

`run/runEvents` 是单进程嵌入和测试入口；生产 HTTP 创建使用 `AgentCommandService.submitStart`，不会在请求 Fiber 中调用
模型。它把 `AgentState(Created)`、`RunCreated`、`Start` 命令和 dispatcher 原子提交，由 WorkerHost 获得 lease 后启动。

业务应用不希望手工重复装配这些服务时，使用 `zyblw-agent-core` 中 `app` package 的
`AgentApplication.durable`；它同时输出
`AgentApplication/AgentRuntime/AgentCommandService/WorkerHost`，但不会把 `LeaseAwareAgentRuntime` 暴露给普通业务代码。
完整示例见 [AgentApplication、Builder 与业务接入](application-builder.md)。

关键语义：

1. `runEvents` 的生产 Fiber 由流 Scope 管理。
2. 客户端停止消费会关闭 Scope 并中断模型流和运行 Fiber。
3. 队列容量固定为 256，慢消费者通过 `Queue.bounded` 反压生产者。
4. `cancel(runId)` 先持久化取消意图，再中断本进程活跃 Fiber，并以乐观锁保存 `Cancelled`。
5. 模型流必须发出 Completed；空流和提前结束显式失败。
6. 生命周期从 `Created` 开始：Run 与 `RunCreated` 同事务完成，再执行输入 Guardrail；通过后提交 `Running`，
   拒绝则提交 `Failed`，因此安全拒绝同样可审计。
7. 后续状态与精选领域事件通过 `RunStore.commit` 原子提交；Adapter 会拒绝错误 runId、乱序或断号事件批次。
8. 审批发生在副作用之前；拒绝也会生成结构化 ToolResult 回填模型。批准绑定 `ApprovalSubject`（capability、callId、
   工具契约指纹、规范化输入摘要、执行环境、权限剖面、授权上下文摘要、生效审批策略摘要、risk/sideEffect），而不是工具名或
   Provider 给出的 callId。任一属性在暂停期间变化，历史批准即不再匹配：Runtime 带刷新后的主体和新的 `approvalId`
   重新请求授权，旧控制台页面提交的决定会被拒绝。`ApprovalReviewer` 扩展只能额外拒绝，不能把 `RecommendAllow` 当成批准，
   也不能修改冻结主体。
9. 一次模型响应的工具调用会先持久化为 `DurableToolPlan`；安全调用可按冲突组形成并行 super-step。新建 v6
   计划同时冻结每个工具的低敏契约指纹和需要审批调用的 `ApprovalSubject`。指纹覆盖模型可见 Schema/说明与 Runtime 强制的
   risk、side-effect、scope、脱敏和冲突并行元数据，但状态中只保存 SHA-256，不复制 Schema、scope 名称或工具参数正文。
10. 每个 super-step 先批量落 `Prepared`，所有账本结果齐备后再用一次 CAS 提交工具消息、步骤、用量和游标。
11. 主模型调用在 Provider 之前把 Intent 写入 `model_call_executions`（与状态同一 `commit`）。TX1 之后崩溃记为
    `Unknown`，不会自动重放以免重复计费。Settlement 已提交但尚未 `Completed` 时，恢复会收口而不是再调模型。
    生产 `CapturePolicy` 默认 `MetadataOnly`；`Replayable` 才保存可重建请求；`Disabled` 不写账本。
12. 分布式 Worker 通过 `LeaseAwareAgentRuntime.executeLeased` 执行 Start/Recover/ResumeApproval/Cancel/Retry；FiberRef 会把完整
    commandId/token/generation 凭证传播到所有状态提交。
13. 状态、JSON 事件和耐久 SSE 在返回数据前统一执行 `RunAuthorization.read`，防止只凭 runId 跨租户读取。
14. 创建 Run 时冻结 `RuntimeCompositionFingerprint`（Profile、指令指纹、允许工具、完整生效 ModelSettings 摘要、CapturePolicy、上下文 `sourceIds`、扩展 `extensionIds`、执行环境身份与权限剖面摘要）。恢复以及每次模型调用前都与当前进程比较，避免连续运行中的管理面覆盖绕过冻结；漂移一律 fail-closed。`pendingToolPlan` 引用的工具若已从注册表消失，同样 fail-closed；v6 计划还会在任何审批或副作用前比较实际注册工具契约，同名 Schema/安全元数据变化和“原先缺失、恢复时出现”都拒绝执行。规划时审批要求采用单调规则：新策略可收紧，不能通过放宽取消已冻结要求。v6 缺少完整计划快照（契约指纹或审批主体）视为持久化损坏；v5 及更早状态仍按旧名称/权限/callId 门禁恢复。缺少组合指纹的旧状态允许恢复，但不声称 Compatible。新增 Memory/RAG/Skill 来源应实现 `ContextContributor` 并组成 `ContextSourceResolver`，不必修改 Runtime 循环。默认 `Local` 执行环境不进入 `extensionIds`。可选 world-state `ContextSources.sections` 默认以 `FullSnapshot` 每次渲染，因为普通 Provider 请求不保留上一回合隐藏上下文；只有宿主证明 stateful continuation 时才可启用 `TrustedStatefulDelta`。Secret 永不渲染，决策与完整 ModelSettings 摘要进入 ModelCall lineage；`CanonicalModelRequest` 仍是该回合模型可见内容的唯一权威。
15. `HarnessStore` 保存 Goal/Plan/Todo/Skill、追加式 Steer/FollowUp/UserMessage、有界 typed `ArtifactReference` 与跨 Run 预算账本。Goal `Active` 不会自动领取 Worker 或调用 `AgentRuntime`。Plan 不能改变 `allowedTools`。Skill 可由 `HarnessSkillProvider` 按无正文目录加载，再经 `SkillContextContributor`/`SkillMaterializer` 只注入 Retrieval；Interaction 仍经 `HarnessContextContributor` 注入。两条路径都不能修改 `allowedTools`。Artifact 只投影不含 scope/正文/私有 metadata 的引用信息（`harness@2`），读取权限仍由宿主授权。Trusted Skill 也不得成为 System 指令。Steer 不能进入 `RunCommandPayload`。`HarnessCommandService` 只准备普通 Start；生产 Adapter 将预算预留与 Created/首事件/Start/dispatcher 同事务提交，之后仍由唯一 WorkerHost/Runtime 执行。`HarnessBudgetReconciler` 只按已持久化终态结算，不恢复 Run、不执行模型、不使用 TTL 猜测释放。生产 Adapter 是 `PostgresHarnessStore`；其现行表、列和约束都已折叠进核心 0.9 V001。
16. `AgentState` fresh-install schema v1 保存有界 `citations` 与 `retrievalEvidence`。检索成功时 Runtime 发出 `AgentEvent.RetrievalCited`，HTTP 投影到 `RunView` 与 `GET /api/v1/runs/{runId}/citations`。

运行时直接操作版本化 `AgentState/RunStore`，并把 `ContextManager`、Input/Output/Tool/Run
`GuardrailEngine`、`RegisteredToolRegistry`、`ToolExecutor`、审批和工具执行账本放入同一状态机。
`recover(runId)` 根据 Prepared/Running/Unknown/Succeeded/Failed 与 `ToolRecoveryPolicy` 决定复用、崩溃重放或暂停人工核对；
同一次在线执行的 429/timeout 由部署 `ToolRetryPolicy` 单独控制。主模型若 Intent 已提交但未结算，恢复为 `Unknown` 且不重放
Provider；若助手消息已结算但 Run 尚未 `Completed`，恢复直接收口，不再次调用模型。已计入的预算不会在恢复时清零。
组合不兼容时恢复返回 `CompositionIncompatible`，Run 保持原状态，命令进入 DeadLetter。

## 部分成功后的恢复语义

批内并行时，工具账本与 AgentState 承担不同职责：账本记录每个外部动作的真实进度；AgentState 只在整批完成后
公开结果。假设 A 已 `Succeeded`、B 仍 `Running` 时 worker 崩溃，重启后会复用 A 的结果，只对 `mayReplayAfterCrash`
的 B 再次执行，最后按 A、B 的原 ordinal 一次性提交 Tool 消息。这个协议提供 at-least-once 尝试与成功结果去重，
不宣称跨第三方系统的天然 exactly-once；写工具仍必须使用业务幂等键、唯一约束或 outbox。

## WorkerHost 与提交级 fencing

`WorkerHost` 是分布式部署的框架入口，不再要求业务代码手工拼接 claim、heartbeat 和 Runtime：

```scala
val program: ZIO[RunCommandStore & LeaseAwareAgentRuntime, AgentError, Nothing] =
  WorkerHost
    .make(
      WorkerId(s"agent-worker-${java.util.UUID.randomUUID()}"),
      WorkerHostConfig()
    )
    .flatMap(_.run)
```

每次 claim 的 command、owner、随机 token 和 generation 会原样进入 `executeLeased`。Runtime 使用 `FiberRef.locally` 把租约限制
在当前工作 Fiber 的动态作用域内；子 Fiber 继承凭证，任务结束后自动恢复为空。`saveEvents` 检测到租约时只调用
`RunStore.commitFenced`，不会退回普通 commit。

`WorkerHostConfig.parallelism` 限制单实例同时运行的 claim lane，默认 4、硬上限 256。每个 lane 一次只持有一条命令，
`RunCommandStore` 的 dispatcher 继续保证同一 Run 严格串行，因此该并发只扩大不同 Run 的吞吐。所有 lane 由同一父 effect
结构化监督；取消、抢占或 generation 接管产生的 `LeaseLost` 只中断当前命令，旧 owner 继续受 fencing 约束，lane 随后
领取其它 Run。可重试的 claim/存储错误在 lane 内按 `retryDelay` 退避；无法由命令 dead-letter 协议收敛的永久
Worker/Store 错误、defect 或意外结束才会中断其余 lane，并由 `AgentHttpHost` 或部署 Supervisor 重启整个实例。生产取值
必须结合 Provider rate limit、JDBC pool、工具下游、内存和 P95 排队时间压测，不应把 256 当成推荐值。

`RunCommandStore.queueSnapshot` / `AgentApplication.queueSnapshot` 提供数据库时钟下的低敏运维快照：Queued、可领取 Run、
Leased、过期 lease、DeadLetter 与最长可领取等待。快照不触发 claim/reclaim，也不包含 runId、tenant、payload 或 token，
适合由宿主定时转成指标；它不是业务状态事实源。

普通 `run/resume/recover/cancel` 仍保留给单进程、嵌入式和测试模式。生产集群 HTTP 控制面已经改为
`AgentCommandService`：新建、审批、取消、恢复和显式重试先耐久入队并返回 `202 Accepted + commandId`，WorkerHost claim
后才推进 AgentState。不要在集群模式旁路调用普通 `run/resume` 并把它误认为 fenced 异步执行。

## 控制命令 HTTP 语义

| 请求 | 命令 | 响应 |
|---|---|---|
| `POST /api/v1/agents/{agentId}/runs` + `Idempotency-Key` | `Start` | `202 + CommandReceipt` |
| `POST /api/v1/runs/{runId}/approval` | `ResumeApproval(approvalId, decision)` | `202 + CommandReceipt` |
| `DELETE /api/v1/runs/{runId}` | 高优先级 `Cancel` | `202 + CommandReceipt` |
| `POST /api/v1/runs/{runId}/recover` | `Recover` | `202 + CommandReceipt` |
| `POST /api/v1/runs/{runId}/retry` | `Retry(requestId, reason)` | `202 + CommandReceipt` |
| `POST /api/v1/commands/{commandId}/retry` | DeadLetter 人工重新排队 | `202 + CommandReceipt` |
| `GET /api/v1/commands/{commandId}` | 查询状态 | `CommandView` |

审批幂等键固定绑定 approvalId，相反决定不能各自创建成功；显式 Retry 要求调用方提供稳定 requestId。Cancel 会在提交事务中
撤销当前 dispatcher 租约，旧 Worker 的 heartbeat 和下一次状态提交都会失败；Cancel 完成后，同 Run 尚未执行的旧命令
进入 `Superseded`，不会重新唤醒已取消 Run。

创建幂等性不是 Run 内唯一：第一次响应丢失时客户端尚不知道 runId。框架使用“可信 tenant/user/agent 作用域哈希 +
Idempotency-Key”建立全局提交键，并保存不含随机 ID/时间的规范化请求指纹。同键同请求返回原 runId/commandId；同键不同
请求返回 409。数据库只保存 SHA-256 作用域/请求摘要，不把原始身份拼接串或提示词复制到索引列。

`AgentEvent` 同时服务实时 UI 与 `RunObserver`；其中只有随状态事务写入 `PersistedAgentEvent` 的精选事件承担
审计和断线续传职责。隐藏推理 `ReasoningDelta` 不进入事件、日志或 HTTP。

工具策略不是装饰配置：`maxCallsPerStep`、总工具预算、结果大小、超时、Semaphore 并发许可、
`ApprovalPolicy` 和 `IdempotentOnly(RetryPolicy)` 都在实际执行路径生效。只有 `SideEffect.None` 或
`IdempotentWrite` 且错误标记 `retryable` 时，才使用带最大次数、最大延迟、jitter 和总时限的指数退避。

预算采用“动作前 + 响应后”双门禁：发起模型调用前检查步骤、模型次数和已有用量；Provider 返回后立即检查累计
输入 token、输出 token、总 token、估算费用，并在写入任何 Prepared 或启动工具 Fiber 前检查本响应的全部工具
是否会突破总工具预算。这样单次超大响应不能绕过只在循环开头执行的预算检查。
