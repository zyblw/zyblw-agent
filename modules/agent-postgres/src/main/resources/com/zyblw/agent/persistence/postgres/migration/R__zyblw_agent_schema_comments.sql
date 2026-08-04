-- 0.3 V001 已随 Maven Central 制品发布，Flyway checksum 必须永久保持不变。
-- 本 repeatable migration 只维护数据库目录中的中文领域说明，不改变表、约束或运行语义。
-- COMMENT ON 是幂等赋值；说明文字变化时 Flyway 会安全重放，而不会伪造新的结构版本。

COMMENT ON TABLE agent_runs IS 'Agent Run 当前耐久快照；version 承担乐观并发控制，state_json 是运行恢复事实';
COMMENT ON TABLE agent_events IS 'Run 的不可变顺序事件；(run_id, sequence) 保证单调、无重复的耐久时间线';
COMMENT ON TABLE tool_executions IS '工具调用执行账本；Prepared/Running/Succeeded/Failed/Unknown 支撑崩溃恢复和幂等重放判断';
COMMENT ON TABLE agent_messages IS 'Agent 消息快照投影；按 run_id/ordinal 保持确定顺序，不替代事件审计';
COMMENT ON TABLE agent_steps IS 'Agent 推理与执行步骤的耐久投影；按 run_id/ordinal 稳定恢复';
COMMENT ON TABLE model_calls IS '模型调用低敏记录和 token usage；不应写入 API Key 或原始 Provider 敏感响应';
COMMENT ON TABLE approval_requests IS '高风险工具的耐久审批请求与决定；相同 approval_id 不允许产生相反事实';
COMMENT ON TABLE usage_records IS 'Run 的追加式 token/成本记录；精确费用规则由带版本的业务定价策略负责';
COMMENT ON TABLE agent_memories IS '跨 Run 长期记忆；删除保留版本 tombstone，但清空 value_json 与 search_text 正文';
COMMENT ON TABLE agent_memory_audit IS '记忆读取、纠正、删除和 retention 的低敏不可变审计，不保存 query、正文或原始 key';
COMMENT ON TABLE agent_run_commands IS 'Agent 控制命令队列；同 Run 由 dispatcher 串行，失败只保存稳定低敏分类';
COMMENT ON TABLE agent_run_dispatch IS '每个 Run 的唯一调度租约和 generation fence；陈旧 worker 不能提交新状态';
COMMENT ON TABLE agent_business_operations IS '可靠写工具的 producer 幂等事实；与业务 mutation、outbox 在同一事务提交';
COMMENT ON TABLE agent_outbox_events IS '事务提交后的至少一次事件投递队列；event_id 在所有重试中保持稳定';
COMMENT ON TABLE agent_inbox_messages IS '下游 consumer/message_id 去重事实；必须与消费者业务 mutation 同事务提交';
COMMENT ON TABLE agent_compensations IS '显式 Saga 补偿计划；Registered 只有经策略或人工激活后才能被 worker 执行';
COMMENT ON TABLE agent_embedding_cache IS '按 tenant/provider/model/dimension/version/hash 精确命中的 Embedding 缓存，不保存原文';
COMMENT ON TABLE agent_embedding_quota_windows IS 'Embedding 租户硬配额窗口；行锁保证并发检查与累加原子完成';
COMMENT ON TABLE agent_embedding_quota_reservations IS 'Embedding request_id/hash 幂等账本；防止网络或 worker 重试重复计费';
COMMENT ON TABLE agent_eval_snapshots IS 'Agent/RAG/Context Compression 的低敏不可变评测快照与发布趋势事实';
COMMENT ON TABLE agent_workflow_checkpoints IS '声明式 Workflow 完整恢复快照；只允许同一 identity 的 step 单调推进';
COMMENT ON TABLE agent_workflow_node_executions IS 'Workflow 节点 execution ledger；Prepared outcome 可跨进程恢复并被 fencing 保护';
COMMENT ON TABLE agent_workflow_waits IS 'Durable timer/signal 等待及 wake lease；resolve/consume 与 checkpoint 形成唯一推进边界';
COMMENT ON TABLE agent_workflow_signals IS '外部 Workflow signal 的幂等 receipt；相同 signal_id 不会被重复应用';

COMMENT ON COLUMN agent_runs.state_json IS '完整 AgentState JSONB；读取时由应用校验 schema/version/事件序号一致性';
COMMENT ON COLUMN agent_runs.version IS 'AgentState compare-and-set 版本；任何更新必须匹配调用方读取版本';
COMMENT ON COLUMN agent_events.payload IS '内部领域事件 JSONB；公共 HTTP/SSE 必须先投影和脱敏，不能直接返回本列';
COMMENT ON COLUMN agent_run_dispatch.generation IS '每次租约换主后单调递增的 fencing token 组成部分';
COMMENT ON COLUMN agent_run_dispatch.lease_token IS '随机租约令牌；日志、指标、公共 API 都不得暴露';
COMMENT ON COLUMN agent_embedding_cache.embedding IS '仅用于完整主键等值缓存的 REAL[]；不同维度可共表但必须匹配 dimension';
COMMENT ON COLUMN agent_eval_snapshots.snapshot_payload IS '用于 SHA-256 精确校验的确定性 UTF-8 JSON 文本表示';
COMMENT ON COLUMN agent_workflow_waits.accepted_signal_payload IS '已接受 signal 的受限正文；不得进入 timeline、日志或指标';
