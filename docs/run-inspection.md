# Run Inspector、Timeline 与安全调试

> 状态：当前说明（低敏读模型已实现；可视化 Studio 尚未实现）
>
> 最后核验：2026-07-25
>
> 事实来源：`inspection/RunInspection.scala`、HTTP contract/projection/routes 及其测试

## 1. 它解决什么问题

Agent 的失败通常不是一句“模型答错了”可以解释的。维护者需要知道：

- Run 是否成功创建、开始、暂停、恢复和终止；
- Context 何时构建或压缩；
- 模型调用、工具提议、审批、Guardrail 和持久化按什么顺序发生；
- 事件序号是否连续，状态与预算累计是否一致；
- 当前看到的是完整历史还是一个分页窗口。

直接把 `AgentState`、Event Store JSON、Prompt 和工具结果暴露给调试界面虽然方便，却会把恢复协议、高敏正文与客户端协议绑死。
当前实现因此引入 `RunInspection`：它是从权威状态和耐久事件生成的**只读低敏投影**，不是第二套状态机，也不会重新执行
模型或工具。

## 2. 当前接口

```http
GET /api/v1/runs/{runId}/inspection
Last-Event-ID: <已确认的最后 sequence，可选>
```

响应由四部分组成：

- `run`：不含 threadId、最终答案和审批正文的低敏运行摘要；
- `timeline`：本页事件的阶段、结果、时间、步骤和低敏计数；
- `diagnostics`：事件连续性、状态/预算/审批一致性的固定结构诊断；
- `nextCursor/hasMore/completeHistory/consistent`：分页与证据完整性。

调用者必须先读取身份上下文，随后按 tenant/user/scope 对 Run 授权。`Last-Event-ID` 超过权威最后序号时会直接拒绝；不能用它
跳到尚未发生的未来。

首次请求不带游标。若 `hasMore=true`，使用 `nextCursor` 作为下一次 `Last-Event-ID`。只有从 `-1` 开始的一页已经覆盖完整
历史时，`completeHistory` 才为 true，Inspector 才会执行“是否缺少 RunCreated/终态事件”这类全历史诊断。分页分别读取后，
客户端可以展示完整 Timeline，但不能把最后一页误标成“完整历史已在服务端整体诊断”。

## 3. Timeline 的稳定语义

内部 `AgentEvent` 被显式映射为：

- `phase`：Lifecycle、Context、Model、Tool、Guardrail、Approval、Persistence；
- `outcome`：Started、Progress、Succeeded、Failed、Waiting、Cancelled；
- 稳定 `eventType`、单调 `sequence`、相对开始时间 `elapsedMillis`；
- 可选 step、toolName、callId、固定 category；
- 输入/输出、缓存输入、推理输出等低敏 usage 计数。

映射是穷尽式 match。新增内部事件时，编译器会迫使维护者决定公开名称、阶段和脱敏规则，避免类名重构悄悄破坏 HTTP
客户端。

## 4. 明确不会返回什么

Inspector 不返回：

- System/Developer 指令正文、用户消息或模型文本；
- 工具 arguments、ToolResult 和外部响应正文；
- Provider 原始错误、SQL、堆栈、Authorization 或 Secret；
- 模型隐藏推理或 chain-of-thought；
- Memory、RAG 原文或健康信息。

它可以返回 `instructionFingerprint`，用于关联版本、评测、延迟和成本趋势，但 fingerprint 不能反推出指令正文。
最终业务答案若确实需要展示，应另行调用经过业务内容授权的普通 `RunView`；运维角色是否可以查看用户正文仍由宿主产品权限决定，不能
因拥有 Timeline 权限而自动获得。

## 5. 当前机械诊断

| code | 含义 |
|---|---|
| `event_run_mismatch` | 事件页混入其他 Run |
| `event_sequence_gap` | sequence 未从游标后连续递增 |
| `event_cursor_ahead_of_state` | 事件超过权威状态的最后序号 |
| `event_page_missing` | 状态表明有后续事件，但事件页为空 |
| `waiting_without_approval` | 等待审批状态没有审批记录 |
| `approval_outside_waiting_state` | 非等待状态仍残留审批记录 |
| `budget_usage_mismatch` | Run usage 与预算累计不一致 |
| `definition_snapshot_missing` | 旧数据缺少创建时定义快照 |
| `instruction_fingerprint_missing` | 旧式指令无法关联趋势 |
| `run_created_event_missing` | 完整历史缺少创建事件 |
| `terminal_event_missing` | 终态缺少对应终态事件 |

`consistent=true` 只表示当前可验证范围没有 Error 级结构问题，不表示答案正确、引用可信、系统通过性能 SLO 或已经生产验证。
这些仍需 eval、业务反馈、指标和故障演练。

## 6. 为什么先做读模型，不先做 time-travel

只读 Timeline 可以在不改变执行语义的情况下显著提升可诊断性。可执行 checkpoint fork/time-travel 则必须回答：

- 新分支如何生成新 runId、继承哪一版定义和权限；
- 已经执行的外部写操作是否允许重放；
- 工具幂等、outbox、补偿和人工审批如何隔离；
- 数据迁移后旧 checkpoint 能否继续解释；
- fork 的费用、审计和责任属于谁。

因此下一步应先做 Inspector CLI/轻量 UI、诊断筛选与受控导出。只有长任务 eval 证明 fork 带来明确收益，并完成副作用隔离
设计后，才实现“从安全 checkpoint 派生新 Run”；不提供原 Run 上的任意历史覆写。

## 7. 阅读代码的最短路径

1. `inspection/RunInspection.scala`：理解读模型、诊断和脱敏；
2. `http/AgentHttpProjection.scala`：理解 core 到 wire DTO 的 allow-list 投影；
3. `http/contract/AgentHttpProtocol.scala`：理解 Endpoint/OpenAPI 契约；
4. `http/AgentHttpApi.scala`：理解身份解析、授权、游标与读取顺序；
5. `RunInspectionSpec`、`AgentHttpProjectionSpec`、`AgentHttpApiSpec`、`AgentHttpContractSpec`：理解安全边界如何被机械锁定。

## 8. 后续成功标准

- 调试一个失败 Run 不需要读取数据库原始 JSON；
- Timeline 在多节点、分页和重连下保持稳定 sequence；
- Inspector 导出扫描不到 Prompt、消息、工具参数/结果和隐藏推理；
- 诊断 code 可进入告警，中文 message 只用于人读；
- UI 只是当前读模型的消费者，不拥有新的执行权限；
- 任何 time-travel 都先通过副作用隔离、迁移与审计评测。
