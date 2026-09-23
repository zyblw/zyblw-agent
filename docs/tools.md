# 工具系统

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-09-23
>
> 2026-09-23 对照：现行安装是 0.9 空库（核心与 1024 知识各一份 V001）。执行内核是 `AgentKernel` + `AgentRuntimeDriver`。Memory、RAG 与摘要走 User envelope。等待使用 `Suspension`。稳定 HTTP 是 OpenAPI `1.2.0`。本页不提升 Experimental 能力的成熟度。
>
>
> 事实来源：对应模块源码、测试与构建定义

## 安全默认值

- 工具默认拒绝，必须进入显式白名单。
- 注册名称必须唯一；`RegisteredToolRegistry.make/fromTools` 在启动期以 typed configuration error 拒绝重复，而不是
  由集合顺序静默覆盖实现。
- 未知工具、非法参数、缺少 scope 和越权请求直接拒绝。
- 写操作和危险操作默认审批。
- 只有 `SideEffect.None`、经过业务审查的 `IdempotentWrite`，或由专用工厂创建的
  `TransactionalOutboxWrite` 才允许**在线热重试**（部署 `ToolRetryPolicy.IdempotentOnly` 且
  `onlineRetryable`）。崩溃恢复另看 `ToolRecoveryPolicy`：只读为 `ReplaySafe`，幂等写为
  `Idempotent`，普通写为 `NeverReplay`，破坏性操作为 `RequiresApproval`。部署 `ToolRetryPolicy.Never`
  不会禁止 ReplaySafe 工具在进程死后用同一 callId 再执行。
- 输出超过 `maxResultBytes` 时失败；后续可接对象存储引用策略。

## 内置示例

- `CalculatorTool`：显式四则运算，不执行表达式脚本。
- `CurrentTimeTool`：基于 ZIO Clock 和 IANA 时区。
- `DangerousActionTool`：演示审批，不执行真实危险操作。
- `knowledge_search` / `knowledge_fetch`：`agent-rag` 的 `KnowledgeTools`。tenant/permissions 由运行时注入，模型不得覆盖。
  `knowledge_search` 支持 `hybrid|vector|lexical|phrase` 与结构化过滤。需要 `knowledge:read`。
  宿主必须显式声明资料范围：`documentScope=unrestricted` 才允许在该 tenant 的授权库内检索；`scopeDocumentId` 或 JSON 数组 `scopeDocumentIds` 限定文档。属性缺失、空白或无法解析都会拒绝，不会退回全库。模型把 `documentIds` 改到范围外，或 `knowledge_fetch` 请求范围外的块，都会在查询前失败，范围外正文不会进入工具结果。

生产工具应实现业务级幂等键；支付、删除、发布和外部消息不能仅靠 callId 或普通 Runtime 工具账本默认重试。
PostgreSQL 业务写应使用 `PostgresReliableWriteTool.make`，它强制经同事务执行器运行，不能用普通 `Tool.json` 后只修改
`SideEffect` 枚举来声称拥有 transactional outbox 保证。

## 读写冲突组与确定性并行计划

`ToolMetadata.parallelism` 默认是 `SequentialOnly`。只有工具作者完成线程安全、幂等和业务资源审查后，才可改为
`ConflictAware`，并声明 `conflictAccesses`：

```scala
ToolMetadata(
  risk = ToolRisk.ReadOnly,
  sideEffect = SideEffect.None,
  parallelism = ToolParallelism.ConflictAware,
  conflictAccesses = Set(ToolConflictAccess("knowledge.documents", ToolAccessMode.Read))
)
```

冲突规则是：同组 Read/Read 不冲突；同组只要一方 Write 就冲突；不同组不冲突；未声明或 SequentialOnly 与任何
调用冲突。`ToolBatchPlanner` 不跨写操作重排模型意图，而是按原 ordinal 生成连续批次。`ToolBatchExecutor` 批次间
顺序、批次内有界并行，收集全部 typed failure，最后仍按原 ordinal 返回。

主 `AgentRuntimeDriver` 已接入这套规划与执行语义，批次状态由 `AgentKernel` 纯归约；并行不是全局开关：Runtime 会把需要审批、缺 scope、未知、
崩溃后不可自动重放（`NeverReplay` / `RequiresApproval`）或没有完整冲突声明的工具强制降级为单调用批次。只有同时满足以下条件才真正进入批内并行：

1. `parallelism = ConflictAware` 且至少声明一个冲突组；
2. `mayReplayAfterCrash`（`ReplaySafe` 或 `Idempotent`，对应 `None` / `IdempotentWrite` / `TransactionalOutboxWrite`）；
3. 当前策略不要求审批；
4. 调用者已具备工具要求的全部 scope；
5. 与同批其他调用不存在读写冲突。

执行前，Runtime 一次写入整批 `Prepared` pending writes；各 Fiber 独立推进到 `Running/Succeeded/Failed/Unknown`；
只有整批结果齐备后，才把 Tool 消息、步骤、用量和 `nextBatchIndex` 通过一次 `RunStore.commit` 写入 `AgentState`。
因此模型永远看不到半批结果，Fiber 完成顺序也不会改变 Provider 原始 ordinal。

`DurableToolPlan` 还为每个实际调用的工具保存 `ToolContractFingerprint`。它对 definition JSON 做对象字段规范化，
对 scope/敏感字段/冲突集合排序，然后摘要工具说明、输入/输出 Schema、strict、risk、side effect、权限、脱敏和并行声明；
状态不保存这些正文。恢复先比较摘要，再产生审批事件或执行副作用。同名工具换 Schema/风险，或规划时未知工具在恢复时
突然出现，都会返回 `CompositionIncompatible`。

v6 计划另以 `approvalSubjects` 冻结每个需要审批调用的 `ApprovalSubject`：capability、callId、工具契约指纹、规范化输入
摘要、`ExecutionEnvironmentId`、权限剖面、授权上下文摘要、生效审批策略摘要与 risk/sideEffect。批准只授权这一个主体，副作用发生前
Runtime 会重算现场主体并逐字段比较；不一致时带新主体重新请求授权，并刷新 `approvalId` 使旧页面提交的决定失效。当前
策略变严可追加审批，变松不能撤销历史要求（`frozenApprovalCallIds` 是唯一判定入口）。`None` 只代表 v5 及更早快照，v6
若缺少完整摘要/审批主体会 fail-closed。主体只保存摘要，工具参数正文不会因审批链路二次落盘。

恢复时，`Succeeded` 直接复用。`ReplaySafe` / `Idempotent` 工具在 `Running/Failed/Prepared` 时继续执行；
`NeverReplay` / `RequiresApproval` 在 `Running` 会先转为 `Unknown` 并暂停，即使历史上已经批准过也不得自动重放。
在线 `ToolRetryPolicy` 不参与这次判断。`callId` 只允许在同一个
`planId + batchIndex + ordinal + toolName` 身份下幂等重放；若 Provider 在同一 Run 复用了 callId，Store 会拒绝
把旧结果嫁接给新调用。

真实写工具、outbox worker、下游 inbox 和补偿 handler 的完整接入方法见 [side-effects.md](side-effects.md)。
