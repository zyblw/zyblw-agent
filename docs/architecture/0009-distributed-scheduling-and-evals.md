# ADR 0009：跨 worker 调度、工具冲突计划与业务评测

## 状态

已采纳并实现稳定内核；跨主机混沌与真实副作用 exactly-once 仍属于生产验证边界。

## 背景

单进程 Fiber 管理只能解决进程内取消，不能回答“某个 worker 崩溃后谁接手”“旧 worker 恢复网络后是否还能提交”以及
“多个工具是否可安全并行”。成熟 durable execution 的共同点是：工作边界持久化、任务可重新领取、副作用幂等、
恢复路径可评测。ZIO 的优势应体现在结构化并发、可中断 finalizer、TestClock 和类型化错误，而不只是把线程池换成 Fiber。

## 决策一：耐久命令、dispatcher 与 AgentState 分离

`agent_runs` 保存业务运行状态，`agent_run_commands` 保存命令与幂等审计，`agent_run_dispatch` 保存每 Run 唯一调度所有权。
claim 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 短事务；每次 claim 生成 token 并递增 generation。所有修改租约状态的操作都做 fencing。
模型、工具和 RAG 调用不进入数据库事务。

`CommandLeaseSupervisor` 让业务 effect 与 heartbeat loop 通过 `raceFirst` 绑定：丢租约会中断业务 Fiber，业务先结束会中断
heartbeat Fiber。ZIO Scope/finalizer 负责释放资源。该协议提供 at-least-once，不宣称外部系统 exactly-once。

`WorkerHost` 是正式装配入口，`CommandWorker` 必须把完整 `RunCommandLease` 而非单独 runId 传给
`LeaseAwareAgentRuntime.executeLeased`。Runtime 用 FiberRef 限定租约的动态作用域，并让每次状态/事件事务调用
`commitFenced`。PostgreSQL 在事务入口以 `FOR SHARE` 锁定有效 dispatcher 行，随后完成 AgentState CAS 和事件追加；
claim、heartbeat 与 Cancel 抢占不能穿过这一短事务，因此旧 generation 无法提交迟到状态。

## 决策二：工具并行必须显式声明

`ToolMetadata` 默认 `SequentialOnly`。工具作者只有在确认线程安全、幂等和资源边界后，才能声明
`ConflictAware` 和一个或多个 `ToolConflictAccess(group, Read|Write)`。同组 Read/Read 可并行；任一 Write 冲突；
未声明组不能解释为“无冲突”。

规划器保持模型顺序，生成连续无冲突批次；批次间顺序、批次内受限并行。执行报告收集全部 typed failure，并按原 ordinal
排序。主 loop 已通过 `DurableToolPlan`、整批 Prepared pending writes、status+attempt CAS 和批次级 AgentState commit
接入；需要审批、非自动重试、缺 scope 或元数据不完整的调用仍强制串行。

## 决策三：确定性业务 eval 是发布门禁

`agent-evals` 对工具必需/禁止集合、规范化引用 ID、恢复后重复副作用、Run 终态、延迟、token 和估算成本做规则评分。
这些事实不交给 LLM-as-judge。LLM judge 后续只能补充主观质量维度，不能覆盖硬门禁。

## 验证

- TestClock：租约过期、generation 递增、旧完成被拒绝。
- 内存 command queue：幂等冲突、同 Run 串行、Cancel 抢占、DeadLetter 与人工 retry。
- PostgreSQL 16 Testcontainers：24 worker `SKIP LOCKED`、过期抢占、Cancel 抢占和状态 fencing。
- heartbeat 故障注入：业务 Fiber 被中断且 finalizer 执行。
- Provider stub：断流、慢流、负 usage、429、5xx、取消传播。
- `pg_dump`/`pg_restore`：Run 快照、命令正文与 dispatcher generation/token 恢复。
- 连接耗尽：DataSource 超时映射为 typed、retryable 持久化错误。
- Runtime 故障注入：同批一个工具 Succeeded、另一个 Running 时中断；恢复复用成功结果，只重试未完成调用并保持 ordinal。
- PostgreSQL 最新基线：批量 Prepared、身份一致性、status+attempt CAS 和跨批重复 callId 拒绝。
- PostgreSQL 提交级 fencing：旧 generation 持有正确 state version 仍被拒绝，新 generation 能继续提交。
- WorkerHost：完整 command 租约传播、成功 complete、永久错误 dead-letter、可重试错误安全重排队和 Provider 原文脱敏。

## 仍然不声称完成的边界

尚未完成跨主机网络分区、SIGKILL、数据库主从切换、真实池饱和、数小时 soak 与灾备 RTO/RPO 演练。工具并行已进入
主 loop，但跨第三方写副作用仍只提供 at-least-once 与结果复用，不宣称天然 exactly-once。这些边界会写入上线门禁，
而不是用单元测试通过率掩盖。

审批、取消、恢复和显式重试已经是独立耐久命令；业务事务同边界的 outbox/inbox、幂等写工具与补偿 SPI 由 ADR 0011
实现。command queue 和 outbox 都不能被宣传成跨第三方副作用 exactly-once。
