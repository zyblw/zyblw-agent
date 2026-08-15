-- 0.3 V001 已随 Maven Central 制品发布，Flyway checksum 必须永久保持不变。
-- 本 repeatable migration 只维护数据库目录中的中文领域说明，不改变表、约束或运行语义。
-- COMMENT ON 每次幂等覆盖；说明文字变化时 Flyway 会安全重放，而不会伪造新的结构版本。
-- 未映射的控制面字段会让本 migration 失败，避免泛化兜底长期残留。

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
COMMENT ON TABLE agent_embedding_cache IS '按 tenant/purpose/provider/model/dimension/version/hash 精确命中的 Embedding 缓存，不保存原文';
COMMENT ON TABLE agent_embedding_quota_windows IS 'Embedding 租户硬配额窗口；行锁保证并发检查与累加原子完成';
COMMENT ON TABLE agent_embedding_quota_reservations IS 'Embedding request_id/hash 幂等账本；防止网络或 worker 重试重复计费';
COMMENT ON TABLE agent_eval_snapshots IS 'Agent/RAG/Context Compression 的低敏不可变评测快照与发布趋势事实';
COMMENT ON TABLE agent_workflow_checkpoints IS '声明式 Workflow 完整恢复快照；只允许同一 identity 的 step 单调推进';
COMMENT ON TABLE agent_workflow_node_executions IS 'Workflow 节点 execution ledger；Prepared outcome 可跨进程恢复并被 fencing 保护';
COMMENT ON TABLE agent_workflow_waits IS 'Durable timer/signal 等待及 wake lease；resolve/consume 与 checkpoint 形成唯一推进边界';
COMMENT ON TABLE agent_workflow_signals IS '外部 Workflow signal 的幂等 receipt；相同 signal_id 不会被重复应用';
COMMENT ON TABLE agent_runtime_overrides IS '管理面追加式运行配置覆盖；敏感值只能引用外部 secret，不能保存明文';
COMMENT ON TABLE agent_ingestion_jobs IS 'RAG 文档摄取任务投影；记录有界状态、进度与低敏失败分类，不保存原始文件';

DO $comments$
DECLARE
  column_record RECORD;
  column_comment TEXT;
BEGIN
  FOR column_record IN
    SELECT relation.relname AS table_name,
           attribute.attname AS column_name
    FROM pg_attribute attribute
    JOIN pg_class relation ON relation.oid = attribute.attrelid
    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = current_schema()
      AND relation.relname IN (
        'agent_runs', 'agent_events', 'tool_executions', 'agent_messages', 'agent_steps',
        'model_calls', 'approval_requests', 'usage_records', 'agent_memories', 'agent_memory_audit',
        'agent_run_commands', 'agent_run_dispatch', 'agent_business_operations', 'agent_outbox_events',
        'agent_inbox_messages', 'agent_compensations', 'agent_embedding_cache',
        'agent_embedding_quota_windows', 'agent_embedding_quota_reservations', 'agent_eval_snapshots',
        'agent_workflow_checkpoints', 'agent_workflow_node_executions', 'agent_workflow_waits',
        'agent_workflow_signals', 'agent_runtime_overrides', 'agent_ingestion_jobs'
      )
      AND relation.relkind = 'r'
      AND attribute.attnum > 0
      AND NOT attribute.attisdropped
    ORDER BY relation.relname, attribute.attnum
  LOOP
    column_comment := CASE
      WHEN column_record.table_name = 'agent_runs'
           AND column_record.column_name = 'version' THEN
        'AgentState compare-and-set 版本；任何更新必须匹配调用方读取版本。'
      WHEN column_record.table_name = 'agent_runtime_overrides'
           AND column_record.column_name = 'version' THEN
        '覆盖快照的单调版本，同时作为管理员并发编辑的 CAS 令牌。'
      WHEN column_record.table_name = 'agent_memories'
           AND column_record.column_name = 'version' THEN
        '记忆条目 CAS 版本；删除 tombstone 仍递增，防止迟到提炼任务复活正文。'
      WHEN column_record.table_name = 'agent_runs'
           AND column_record.column_name = 'state_json' THEN
        '完整 AgentState JSONB；读取时由应用校验 schema/version/事件序号一致性。'
      WHEN column_record.table_name = 'agent_events'
           AND column_record.column_name = 'payload' THEN
        '内部领域事件 JSONB；公共 HTTP/SSE 必须先投影和脱敏，不能直接返回本列。'
      WHEN column_record.table_name = 'agent_workflow_signals'
           AND column_record.column_name = 'payload' THEN
        '外部 signal 正文；不得进入 timeline、日志或指标。'
      WHEN column_record.table_name = 'agent_embedding_cache'
           AND column_record.column_name = 'purpose' THEN
        'Embedding 的可信用途（query/indexing/memory）；legacy 为升级前安全失效的缓存行。'
      WHEN column_record.table_name = 'agent_embedding_quota_reservations'
           AND column_record.column_name = 'purpose' THEN
        '本次配额预留对应的 Embedding 用途：query、indexing 或 memory。'
      WHEN column_record.table_name = 'agent_runs'
           AND column_record.column_name = 'tenant_id' THEN
        '从 state_json.runContext.tenantId 生成的租户标识，供管理台索引，不另存权威副本。'
      WHEN column_record.table_name = 'agent_runs'
           AND column_record.column_name = 'user_id' THEN
        '从 state_json.runContext.userId 生成的用户标识，供管理台过滤。'
      WHEN column_record.table_name = 'agent_embedding_cache'
           AND column_record.column_name = 'embedding' THEN
        '仅用于完整主键等值缓存的 REAL[]；不同维度可共表但必须匹配 dimension。'
      WHEN column_record.table_name = 'agent_eval_snapshots'
           AND column_record.column_name = 'snapshot_payload' THEN
        '用于 SHA-256 精确校验的确定性 UTF-8 JSON 文本表示。'
      WHEN column_record.table_name = 'agent_workflow_waits'
           AND column_record.column_name = 'accepted_signal_payload' THEN
        '已接受 signal 的受限正文；不得进入 timeline、日志或指标。'
      WHEN column_record.table_name = 'agent_run_dispatch'
           AND column_record.column_name = 'generation' THEN
        '每次租约换主后单调递增的 fencing token 组成部分。'
      WHEN column_record.table_name = 'agent_run_dispatch'
           AND column_record.column_name = 'lease_token' THEN
        '随机租约令牌；日志、指标、公共 API 都不得暴露。'
      ELSE CASE column_record.column_name
      WHEN 'accepted_signal_id' THEN '已被等待条件接受的外部 signal 稳定标识。'
      WHEN 'accepted_signal_payload' THEN '已被等待条件接受的 signal 正文。'
      WHEN 'accepted_signal_sha256' THEN '已接受 signal 正文的 SHA-256，用于校验未被改写。'
      WHEN 'action' THEN '记忆治理动作：read、list、search、correct、delete、delete_scope 或 retention_purge。'
      WHEN 'actor_kind' THEN '审计主体类型：authenticated 或 system。'
      WHEN 'actor_system_name' THEN '系统主体名称；仅 actor_kind=system 时存在。'
      WHEN 'actor_tenant_id' THEN '认证主体所属租户；系统动作必须为空。'
      WHEN 'actor_user_id' THEN '认证主体用户标识；系统动作必须为空。'
      WHEN 'affected_count' THEN '本条审计影响的记忆条目数量。'
      WHEN 'agent_id' THEN '智能体定义稳定标识。'
      WHEN 'aggregate_id' THEN 'outbox 事件所属聚合根标识。'
      WHEN 'aggregate_type' THEN 'outbox 事件所属聚合类型。'
      WHEN 'approval_id' THEN '高风险工具审批请求的稳定标识。'
      WHEN 'attempt' THEN '当前轮次已尝试次数；人工重试会重置本轮但保留历史计数。'
      WHEN 'audit_id' THEN '记忆治理审计事件唯一标识。'
      WHEN 'available_at' THEN '命令、事件或补偿最早可被领取的时间。'
      WHEN 'awaiting_approval' THEN '由 pendingApproval 对象是否存在生成的待审批标记，供管理台部分索引使用。'
      WHEN 'batch_id' THEN '同一批工具调用的稳定批次标识。'
      WHEN 'call_id' THEN '单次模型或工具调用的稳定标识。'
      WHEN 'cancel_requested' THEN '是否已收到取消请求；取消事实仍须经命令队列推进。'
      WHEN 'characters' THEN '本窗口或预留已计入的字符数。'
      WHEN 'checkpoint_json' THEN 'Workflow checkpoint 的 JSONB 分析副本；必须与 payload 解析结果相等。'
      WHEN 'checkpoint_payload' THEN 'Workflow checkpoint 的确定性 UTF-8 文本，用于 SHA-256 校验。'
      WHEN 'checkpoint_sha256' THEN 'checkpoint_payload 的 SHA-256。'
      WHEN 'chunk_count' THEN '摄取任务或文档版本核验后的块数。'
      WHEN 'claimed_at' THEN '当前租约被领取的时间。'
      WHEN 'command_id' THEN '控制命令稳定标识。'
      WHEN 'command_type' THEN '命令类型：Start、Recover、ResumeApproval、Cancel 或 Retry。'
      WHEN 'commit_sha' THEN '产生该评测快照的代码提交标识；可为空。'
      WHEN 'compensation_id' THEN 'Saga 补偿计划唯一标识。'
      WHEN 'completed_at' THEN '调用、操作、补偿或节点执行完成时间。'
      WHEN 'condition_kind' THEN '等待条件类型：Signal 或 Timer。'
      WHEN 'confidence' THEN '记忆置信度，范围 0 到 1。'
      WHEN 'consumed_at' THEN '已决议 wait 被 checkpoint 消费的时间。'
      WHEN 'consumer_name' THEN '下游消费者稳定名称，与 message_id 共同去重。'
      WHEN 'content_hash' THEN '规范化正文的 SHA-256，不保存原文。'
      WHEN 'created_at' THEN '记录创建时间（带时区）。'
      WHEN 'current_command_id' THEN 'dispatcher 当前正在执行的命令；非 Leased 时必须为空。'
      WHEN 'cursor_kind' THEN 'Workflow 游标：At 表示停在某节点，Completed 表示工作流结束。'
      WHEN 'dataset_id' THEN '评测数据集稳定标识。'
      WHEN 'dataset_version' THEN '评测数据集版本。'
      WHEN 'deadline' THEN 'timer/signal 等待的绝对截止时间。'
      WHEN 'decided_at' THEN '审批作出决定的时间。'
      WHEN 'decision_json' THEN '审批决定的结构化低敏载荷。'
      WHEN 'definition_version' THEN 'Workflow 定义版本；与 workflow_id、session_id 共同构成 identity。'
      WHEN 'deleted_at' THEN '记忆被删除并清空正文的时间。'
      WHEN 'destination' THEN 'outbox 事件投递目的地标识。'
      WHEN 'dimension' THEN '缓存向量维度；必须与 embedding 数组长度一致。'
      WHEN 'disposition' THEN 'signal 接收处置：Accepted、Late 或 AlreadyResolved。'
      WHEN 'document_id' THEN '知识文档稳定标识；摄取任务完成后回填。'
      WHEN 'embedding' THEN '缓存或索引使用的向量值。'
      WHEN 'estimated_cost' THEN '本条 usage 的估算费用；精确计费由带版本的定价策略负责。'
      WHEN 'evaluation_id' THEN '评测快照稳定主键；相同 ID 不允许覆盖。'
      WHEN 'event_id' THEN '事件稳定标识，重试期间保持不变。'
      WHEN 'event_type' THEN '领域或投递事件类型。'
      WHEN 'evidence' THEN '记忆证据来源：user_stated、tool_observed、imported 或 model_inferred。'
      WHEN 'expected_version' THEN '治理操作期望的记忆 CAS 版本。'
      WHEN 'expires_at' THEN '记录、记忆或缓存失效时间（带时区）。'
      WHEN 'extractor_version' THEN '写入该记忆的提炼器版本。'
      WHEN 'failure_code' THEN '稳定低敏失败分类；不保存 Provider 或数据库原始异常。'
      WHEN 'file_name' THEN '摄取任务的原始文件名，不含文件正文。'
      WHEN 'finished_at' THEN '评测结束时间。'
      WHEN 'finished_epoch_second' THEN '评测结束时刻的 epoch 秒，用于确定性排序。'
      WHEN 'finished_nano' THEN '评测结束时刻的纳秒，与 epoch 秒一起保证 JVM Instant 排序。'
      WHEN 'generation' THEN '租约换主后递增的 fencing 代数。'
      WHEN 'handler_name' THEN '补偿处理器稳定名称。'
      WHEN 'harness_version' THEN '执行该评测的 harness 版本。'
      WHEN 'headers' THEN 'outbox 事件的低敏头对象。'
      WHEN 'heartbeat_at' THEN '当前租约最近一次心跳时间。'
      WHEN 'idempotency_key' THEN '命令、工具或业务操作的幂等键。'
      WHEN 'importance' THEN '记忆重要性，范围 0 到 1。'
      WHEN 'index_version' THEN '知识索引快照版本。'
      WHEN 'input_tokens' THEN '累计或本条输入令牌数。'
      WHEN 'job_id' THEN '文档摄取任务唯一标识。'
      WHEN 'key_version' THEN 'Embedding 缓存键版本，用于指令或规范化策略演进。'
      WHEN 'last_failure' THEN '最近一次失败的稳定低敏分类；不得保存原始异常全文。'
      WHEN 'lease_expires_at' THEN '当前租约到期时间。'
      WHEN 'lease_owner' THEN '当前持有租约的 Worker 标识。'
      WHEN 'lease_token' THEN '随机租约令牌，必须与 owner/generation 一同校验。'
      WHEN 'manual_retry_count' THEN '人工重试累计次数，与本轮 attempt 分离。'
      WHEN 'media_type' THEN '摄取对象 MIME 类型。'
      WHEN 'memory_key' THEN '记忆业务键；审计表只保存其哈希。'
      WHEN 'memory_key_hash' THEN '记忆键的 SHA-256，用于审计追溯而不暴露原文键。'
      WHEN 'memory_kind' THEN '记忆类型：preference、semantic、episodic 或 procedural。'
      WHEN 'message_hash' THEN 'inbox 消息规范化摘要，防止同 ID 不同载荷被静默接受。'
      WHEN 'message_id' THEN '下游消息稳定标识。'
      WHEN 'model' THEN '模型标识。'
      WHEN 'node_id' THEN 'Workflow 节点稳定标识。'
      WHEN 'occurred_at' THEN '审计事实实际发生时间。'
      WHEN 'operation_id' THEN '可靠写业务操作标识。'
      WHEN 'operation_name' THEN '可靠写操作名称，与 scope_key、幂等键共同唯一。'
      WHEN 'ordinal' THEN '同一父对象内的稳定顺序。'
      WHEN 'outcome_json' THEN '节点 Prepared/Committed 结果的 JSONB 分析副本。'
      WHEN 'outcome_payload' THEN '节点结果的确定性 UTF-8 文本。'
      WHEN 'outcome_sha256' THEN 'outcome_payload 的 SHA-256。'
      WHEN 'output_tokens' THEN '累计或本条输出令牌数。'
      WHEN 'overrides' THEN '该版本生效的完整运行配置覆盖对象；敏感值只能是外部 secret 引用。'
      WHEN 'partition_key' THEN 'outbox 分区键，保证同一聚合有序投递。'
      WHEN 'pass_rate' THEN '评测通过率，范围 0 到 1。'
      WHEN 'passed' THEN '该评测快照是否整体通过。'
      WHEN 'payload' THEN '结构化内部载荷；公共 API 返回前必须投影与脱敏。'
      WHEN 'payload_sha256' THEN 'signal 正文 SHA-256，同 ID 不同哈希会 fail-closed。'
      WHEN 'pricing_version' THEN '估算费用使用的定价策略版本。'
      WHEN 'priority' THEN '命令领取优先级，数值越大越先被 claim。'
      WHEN 'processed_at' THEN 'inbox 消息成功消费时间。'
      WHEN 'progress_percent' THEN '摄取任务 0 到 100 的有界进度。'
      WHEN 'provider' THEN '模型或 Embedding Provider 稳定标识。'
      WHEN 'published_at' THEN 'outbox 事件被标记 Published 的时间。'
      WHEN 'purpose' THEN '用途隔离键。'
      WHEN 'reason' THEN '管理员提交覆盖时的变更原因。'
      WHEN 'reason_code' THEN '治理操作的稳定原因码。'
      WHEN 'received_at' THEN 'inbox 消息或 signal 接收时间。'
      WHEN 'record_json' THEN '工具执行账本记录；包含低敏身份、状态和可重放结果。'
      WHEN 'request_hash' THEN '规范化请求 SHA-256，用于拒绝幂等键误用。'
      WHEN 'request_id' THEN '租户内唯一的 Embedding 请求标识。'
      WHEN 'request_json' THEN '审批请求的结构化低敏载荷。'
      WHEN 'requests' THEN '本窗口或预留已计入的请求次数。'
      WHEN 'resolved_at' THEN 'wait 被 signal 或超时决议的时间。'
      WHEN 'result_json' THEN '业务操作或 inbox 消费的可重放结果。'
      WHEN 'resulting_version' THEN '治理操作完成后的记忆版本。'
      WHEN 'role' THEN '消息角色投影。'
      WHEN 'run_id' THEN 'Agent 或 Workflow Run 稳定标识。'
      WHEN 'schema_version' THEN '状态或快照 JSON 契约版本。'
      WHEN 'scope_key' THEN '记忆、可靠写或补偿所属作用域键。'
      WHEN 'scope_kind' THEN '记忆作用域：session、user 或 tenant。'
      WHEN 'search_text' THEN '供全文检索的确定性文本；删除后必须清空。'
      WHEN 'search_vector' THEN '由 search_text 生成的 simple FTS 向量。'
      WHEN 'sensitivity' THEN '记忆敏感级别：public、personal 或 sensitive。'
      WHEN 'sequence' THEN '同一 Run 内事件单调序号。'
      WHEN 'session_id' THEN '会话或 Workflow session 标识。'
      WHEN 'signal_id' THEN '外部 signal 稳定标识。'
      WHEN 'signal_name' THEN '等待或投递的 signal 名称。'
      WHEN 'signal_received_at' THEN 'wait 接受 signal 的时间。'
      WHEN 'snapshot_json' THEN '评测快照 JSONB 分析副本；必须与 payload 解析结果相等。'
      WHEN 'snapshot_payload' THEN '评测快照确定性文本。'
      WHEN 'snapshot_sha256' THEN 'snapshot_payload 的 SHA-256。'
      WHEN 'source_run_id' THEN '提炼出该记忆的来源 Run；可为空。'
      WHEN 'source_uri' THEN '宿主提供的稳定来源标识；不得包含临时签名。'
      WHEN 'start_idempotency_key' THEN '异步 StartRun 的客户端幂等键。'
      WHEN 'start_request_hash' THEN '异步 StartRun 请求指纹 SHA-256。'
      WHEN 'start_scope_hash' THEN '异步 StartRun 作用域指纹 SHA-256。'
      WHEN 'started_at' THEN '模型调用或评测开始时间。'
      WHEN 'state_json' THEN '可恢复状态快照。'
      WHEN 'status' THEN format('「%s」记录当前状态机状态。', column_record.table_name)
      WHEN 'step' THEN 'Workflow 单调步进序号。'
      WHEN 'step_type' THEN '推理或执行步骤类型。'
      WHEN 'submitted_by' THEN '提交摄取任务的操作者标签，不保存令牌或 IP。'
      WHEN 'suite_id' THEN '评测套件稳定标识。'
      WHEN 'suite_kind' THEN '评测套件类别：Agent、Rag 或 ContextCompression。'
      WHEN 'target_scope_key' THEN '治理目标作用域键。'
      WHEN 'target_scope_kind' THEN '治理目标作用域类型。'
      WHEN 'target_session_id' THEN '治理目标 session；仅 session 作用域时存在。'
      WHEN 'target_tenant_id' THEN '治理目标租户。'
      WHEN 'target_user_id' THEN '治理目标用户。'
      WHEN 'tenant_id' THEN '宿主注入的租户隔离标识。'
      WHEN 'texts' THEN '本窗口或预留已计入的文本条数。'
      WHEN 'tool_call_id' THEN '产生该业务操作或 outbox 事件的工具调用标识。'
      WHEN 'tool_name' THEN '工具稳定名称。'
      WHEN 'updated_at' THEN '记录最后更新时间（带时区）。'
      WHEN 'updated_by' THEN '写入该覆盖快照的操作者标签。'
      WHEN 'usage_json' THEN '模型调用的低敏 token usage 对象。'
      WHEN 'user_id' THEN '宿主注入的用户标识。'
      WHEN 'value_json' THEN '记忆正文；deleted 行必须为空。'
      WHEN 'version' THEN '乐观并发或数据契约版本。'
      WHEN 'visit' THEN '同一节点在本 Run 中的访问次数。'
      WHEN 'wait_node_id' THEN 'signal receipt 关联的 wait 节点。'
      WHEN 'wait_step' THEN 'signal receipt 关联的 wait step。'
      WHEN 'wake_available_at' THEN '已决议 wait 可被唤醒领取的时间。'
      WHEN 'wake_claimed_at' THEN 'wake 租约被领取的时间。'
      WHEN 'wake_generation' THEN 'wake 租约 fencing 代数。'
      WHEN 'wake_heartbeat_at' THEN 'wake 租约最近心跳时间。'
      WHEN 'wake_lease_expires_at' THEN 'wake 租约到期时间。'
      WHEN 'wake_owner' THEN '当前持有 wake 租约的 Worker。'
      WHEN 'wake_token' THEN 'wake 租约随机令牌。'
      WHEN 'window_millis' THEN '配额窗口长度（毫秒），进入主键以免不同策略共用桶。'
      WHEN 'window_start' THEN '配额窗口起点。'
      WHEN 'workflow_id' THEN 'Workflow 定义稳定标识。'
      ELSE NULL
    END
    END;
    IF column_comment IS NULL THEN
      RAISE EXCEPTION '控制面字段 %.% 缺少中文数据字典说明',
        column_record.table_name, column_record.column_name;
    END IF;
    EXECUTE format(
      'COMMENT ON COLUMN %I.%I.%I IS %L',
      current_schema(), column_record.table_name, column_record.column_name, column_comment
    );
  END LOOP;
END
$comments$;
