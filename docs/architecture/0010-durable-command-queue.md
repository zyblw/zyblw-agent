# ADR 0010：审批、取消、恢复与重试的耐久控制命令队列

## 状态

已采纳并实现。HTTP、WorkerHost、Runtime、PostgreSQL migration、内存 Adapter 和真实 PostgreSQL 16 契约测试均已接入。

## 问题

早期调度表以 `run_id` 为主键，只能表达“恢复这个 Run”。它不能回答：

- 这次执行是恢复、审批、取消还是人工重试？
- 同一审批请求的重复 HTTP 请求是否会产生相反决定？
- 一个 Run 先后多条命令如何审计、排序和独立重试？
- Cancel 如何抢占正在进行的分布式模型/工具 Fiber，而不是排在长任务之后？
- Runtime 已经提交状态、进程却在 command complete 前崩溃时如何幂等重放？

把 decision 或 command type 临时塞入 AgentState metadata 会制造第二套隐式协议，也无法为命令自身建立 claim、DeadLetter、
幂等键和重试历史，因此不采用。

## 决策

采用“不可变命令 + 每 Run dispatcher”双表模型：

```text
agent_run_commands
  command_id / run_id / command_type / payload
  idempotency_key / priority / available_at
  status / attempt / manual_retry_count / last_failure

agent_run_dispatch
  run_id (PK)
  status / current_command_id
  owner / token / generation / expires_at
```

`agent_run_commands` 保存控制意图和审计；`agent_run_dispatch` 只保存当前执行所有权。多个 Run 通过不同 dispatcher 并行；
同 Run 所有命令共享一个 dispatcher，因而不会同时修改同一 AgentState。

## 命令 ADT

`RunCommandPayload` 当前包含：

- `Recover`：根据 AgentState 与工具账本恢复。
- `ResumeApproval(approvalId, decision)`：决定绑定确切审批请求。
- `Cancel(reason)`：高优先级抢占并收敛到 Cancelled。
- `Retry(reason)`：显式要求恢复非终态 Run，保留人工审计原因。

命令正文是数据，不是任意可执行代码。Provider、数据库连接、工具实例和授权上下文都不进入 payload。

## 幂等规则

数据库唯一约束为 `(run_id, idempotency_key)`：

- 同 key、同 payload：返回原命令。
- 同 key、不同 payload：`CommandIdempotencyConflict`。
- 审批键固定为 `approval:{approvalId}`，因此 Approve 与 Reject 不能各自创建成功。
- Recover 键绑定 AgentState version。
- Cancel 使用 Run 级固定键。
- 显式 Retry 使用业务调用方提供的稳定 requestId。

Store 不使用 `ON CONFLICT DO UPDATE payload`，因为覆盖命令正文会破坏审批审计和恢复确定性。

## Claim 与 fencing

claim 短事务执行：

1. 回收过期 dispatcher，将旧 Leased command 恢复为 Queued。
2. 把达到 maxAttempts 的命令推进到 DeadLetter。
3. 使用固定排序寻找候选。
4. `FOR UPDATE OF dispatcher, command SKIP LOCKED`。
5. 命令变为 Leased，dispatcher 生成新 token 并递增 generation。

`RunCommandLease` 包含 commandId、runId、owner、token、generation、claimedAt 和 expiresAt。heartbeat、complete、abandon、
deadLetter 与 `RunStore.commitFenced` 必须比较全部 fencing 字段。

PostgreSQL 行锁只存在于 claim/状态提交等短事务中。模型、工具、RAG 与第三方 HTTP 全部在事务外执行。

## Cancel 抢占

Cancel 不是普通低延迟队列项。新 Cancel 首次提交时，存储事务会：

1. 锁定该 Run dispatcher。
2. 将当前 Leased command 放回 Queued。
3. 清空 owner/token/currentCommand，使旧租约立即失效。
4. 唤醒 dispatcher，并让最高优先级 Cancel 先被 claim。

旧 Worker 会在 heartbeat 或下一次 AgentState commit 时得到 `LeaseLost`，ZIO `raceFirst` 随后中断 Runtime Fiber 和子 Fiber。
Cancel 完成后，其他 Queued command 变为 Superseded，防止已取消 Run 被旧 Recover 再次唤醒。

此协议不能撤回已经到达第三方系统的副作用；真实写工具仍必须使用幂等键、outbox/inbox 或补偿。

## 崩溃窗口

命令执行与 command complete 之间仍可能进程崩溃，这是刻意接受的 at-least-once 窗口：

- Recover 重放依赖 AgentState 与工具账本。
- Cancel 对终态 Run 幂等。
- ResumeApproval 先检查历史 ApprovalStep。若决定已经记录且 Run 仍 Running，则转入 recover；若已经完成、取消或进入下一次
  审批，命令直接视为已应用；历史决定不同则永久失败。
- command complete 使用当前租约 fencing，旧 worker 不能迟到完成。

## 错误和人工重试

- `retryable=true`：abandon，按 availableAt 自动重试。
- `retryable=false`：直接 DeadLetter。
- 达到 maxAttempts：DeadLetter。
- 人工 retry：只允许 DeadLetter，attempt 重置，manualRetryCount 加一。
- LeaseLost：旧 Worker 不再修改命令状态。

队列只保存错误类别和 retryable 标志，不保存 Provider 原文、用户输入、密钥或堆栈。

## HTTP 控制面

创建、审批、取消、恢复和重试返回 `202 Accepted` 与 `CommandReceipt`。这表示命令已经耐久接收，不表示 AgentState 已经完成。
客户端通过 `/api/v1/commands/{commandId}` 查询状态。公共 `CommandView` 不返回完整 payload、lease 或幂等键。

`AgentCommandService` 在入队前验证 Run 的 tenantId/userId；`agent:commands:admin` scope 可以执行受审计的跨用户运维操作。
认证信息必须来自宿主认证中间件的 `AgentRequestContextResolver`，不能信任请求 JSON 中的身份字段。

## 验证

- 内存：幂等冲突、同 Run 串行、Cancel 抢占、Fiber finalizer、DeadLetter 与人工 retry。
- Runtime：approvalId 绑定与决定重放窗口。
- HTTP：202 回执、命令查询与 payload 隔离。
- PostgreSQL 16：Start 四事实原子提交/并发幂等、24 worker claim、租约过期 generation 抢占、AgentState fencing、Cancel
  抢占、备份恢复。

## 未解决边界

- 新 Run 已由 `RunSubmissionStore` 原子提交 Created/RunCreated/Start/dispatcher；跨节点精选耐久事件已通过 sequence SSE
  实现断点续传。逐 token delta 的高吞吐 relay 仍未实现，后续应采用有界消息总线而不是把 token 全量写入 PostgreSQL。
- command queue 只保证框架控制面，不保证业务数据库与第三方系统 exactly-once。
- 跨主机 SIGKILL、网络分区、数据库切换和长时间 soak 仍需生产级故障演练。
