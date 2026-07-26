# 核心概念

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

- `ChatModel` / `ModelProvider`：模型调用和流事件 SPI。
- `ModelCapabilities`：模型能力事实，不由 runtime 猜测。
- `Tool` / `RegisteredTool`：类型安全定义与受控运行时擦除。
- `AgentDefinition`：声明式 Agent 身份、指令、模型和工具集合。
- `RunPolicy` / `RunLimits`：步骤、模型、工具、token、费用和时长预算。
- `AgentState`：版本化不可变事实状态。
- `AgentStep`：模型、工具、Guardrail、审批和 Handoff 决策记录。
- `AgentEvent` / `PersistedAgentEvent` / `TelemetryEvent`：内部实时领域流、耐久事件信封和非关键遥测。内部事件可能包含工具
  参数/结果，必须经过出口专用 allow-list 投影，不能直接等同于公共 SSE。
- `RunStore`：快照、乐观锁、幂等事件、取消和工具执行账本。
- `RunCommandStore`：审批、取消、恢复和显式重试的耐久命令、每 Run 串行 dispatcher、lease、heartbeat 与 fencing。
- `AgentCommandService`：校验租户/用户所有权并生成稳定幂等键的控制面；接收命令不等于命令已经执行完成。
- `WorkerHost`：结构化组合 claim、heartbeat、租约失效抢占和 `LeaseAwareAgentRuntime` 的集群工作进程入口。
- `PostgresTransactionalWriteExecutor`：把业务 mutation、producer 幂等结果、outbox 与可选补偿计划放入同一 PostgreSQL transaction。
- `OutboxPublisher` / `OutboxTransport`：事务外至少一次发布；稳定 eventId、有限 lease、heartbeat 与 generation fencing。
- `PostgresTransactionalInbox`：把 consumer/messageId 去重、下游业务 mutation 和可重放结果放入同一 transaction。
- `CompensationStore` / `CompensationHandler`：显式激活的确定性 Saga 补偿，不允许模型自由生成反向动作。
- `ContextManager`：系统指令、最近消息、Memory、RAG、摘要与输出预留。
- `WorkflowNode`：确定性业务节点；Agent 只是一种可组合节点。

Memory 保存跨会话形成的信息；RAG 检索当前问题所需外部知识，两者不能混用。

`ThreadId/AgentId/ToolName/ProviderId/ModelId/TenantId/UserId/PromptId/Version` 等 opaque type 的 `apply` 只用于已经校验的
内部常量；HTTP、JSON、配置和 Provider 响应必须使用 `fromString/fromLong` 或对应的 `transformOrFail` codec，把非法值保留
在 typed error/Either 通道，不能让 `require` 变成 Fiber defect。

`RunStore` 与 `RunCommandStore` 也不能混用：前者保存 Agent 的事实状态，后者保存“谁要求何时推进这个状态”的控制
意图。命令可因 at-least-once 调度而重放，因此 Runtime 必须依赖 AgentState、审批历史和工具账本实现确定性恢复。

`tool_executions` 记录“Agent 是否开始/完成某次工具调用”，`agent_business_operations` 记录“某个跨 Run 业务意图是否已
提交”，两者不能互相替代。outbox 保证本地状态与待发送事实共同提交；它不保证第三方系统只收到一次。
