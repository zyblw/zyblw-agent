# 工具系统

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-25
>
> 事实来源：对应模块源码、测试与构建定义

## 安全默认值

- 工具默认拒绝，必须进入显式白名单。
- 注册名称必须唯一；`RegisteredToolRegistry.make/fromTools` 在启动期以 typed configuration error 拒绝重复，而不是
  由集合顺序静默覆盖实现。
- 未知工具、非法参数、缺少 scope 和越权请求直接拒绝。
- 写操作和危险操作默认审批。
- 只有 `SideEffect.None`、经过业务审查的 `IdempotentWrite`，或由专用工厂创建的
  `TransactionalOutboxWrite` 才允许自动重试。
- 输出超过 `maxResultBytes` 时失败；后续可接对象存储引用策略。

## 内置示例

- `CalculatorTool`：显式四则运算，不执行表达式脚本。
- `CurrentTimeTool`：基于 ZIO Clock 和 IANA 时区。
- `DangerousActionTool`：演示审批，不执行真实危险操作。
- RAG 示例中的 `knowledge_lookup`：先按 tenant/scope 过滤再检索。

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

主 `AgentRuntimeLive` 已接入这套规划与执行语义，但并行不是全局开关：Runtime 会把需要审批、缺 scope、未知、
非自动重试或没有完整冲突声明的工具强制降级为单调用批次。只有同时满足以下条件才真正进入批内并行：

1. `parallelism = ConflictAware` 且至少声明一个冲突组；
2. `sideEffect` 是 `None`、`IdempotentWrite` 或由专用工厂产生的 `TransactionalOutboxWrite`；
3. 当前策略不要求审批；
4. 调用者已具备工具要求的全部 scope；
5. 与同批其他调用不存在读写冲突。

执行前，Runtime 一次写入整批 `Prepared` pending writes；各 Fiber 独立推进到 `Running/Succeeded/Failed/Unknown`；
只有整批结果齐备后，才把 Tool 消息、步骤、用量和 `nextBatchIndex` 通过一次 `RunStore.commit` 写入 `AgentState`。
因此模型永远看不到半批结果，Fiber 完成顺序也不会改变 Provider 原始 ordinal。

恢复时，`Succeeded` 直接复用，仍处于 `Running/Failed/Prepared` 的可重试工具继续执行。`callId` 只允许在同一个
`planId + batchIndex + ordinal + toolName` 身份下幂等重放；若 Provider 在同一 Run 复用了 callId，Store 会拒绝
把旧结果嫁接给新调用。

真实写工具、outbox worker、下游 inbox 和补偿 handler 的完整接入方法见 [side-effects.md](side-effects.md)。
