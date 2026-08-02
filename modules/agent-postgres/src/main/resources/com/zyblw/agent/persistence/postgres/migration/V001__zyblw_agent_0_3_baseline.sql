-- zyblw-agent 0.3 全新数据库基线。
--
-- 这是开发阶段允许破坏性重构后的唯一默认 migration；不支持从 0.2.x 的 Flyway 历史原地升级。
-- 采用本版本的应用必须使用空 schema/新数据库并重新构建派生索引。已发布版本的 migration 只保留在对应 Maven artifact/tag 中。
-- zyblw-agent PostgreSQL 首次正式发布基线；本文件直接描述当前完整结构。

CREATE TABLE agent_runs (
  run_id UUID PRIMARY KEY,
  session_id UUID NOT NULL,
  agent_id TEXT NOT NULL CHECK (length(trim(agent_id)) > 0),
  status TEXT NOT NULL,
  version BIGINT NOT NULL CHECK (version >= 0),
  schema_version INTEGER NOT NULL CHECK (schema_version > 0),
  state_json JSONB NOT NULL,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  -- 仅异步 StartRun 使用；哈希列不保存原始身份或提示词。
  start_scope_hash CHAR(64),
  start_idempotency_key TEXT,
  start_request_hash CHAR(64),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (
    (start_scope_hash IS NULL AND start_idempotency_key IS NULL AND start_request_hash IS NULL)
    OR
    (start_scope_hash IS NOT NULL AND start_idempotency_key IS NOT NULL AND start_request_hash IS NOT NULL
      AND length(trim(start_idempotency_key)) BETWEEN 1 AND 200)
  )
);

CREATE INDEX agent_runs_session_updated_idx ON agent_runs(session_id, updated_at DESC);
CREATE INDEX agent_runs_status_updated_idx ON agent_runs(status, updated_at DESC);
CREATE UNIQUE INDEX agent_runs_start_idempotency_idx
  ON agent_runs(start_scope_hash, start_idempotency_key)
  WHERE start_scope_hash IS NOT NULL;

CREATE TABLE agent_events (
  event_id UUID PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  sequence BIGINT NOT NULL CHECK (sequence >= 0),
  event_type TEXT NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE(run_id, sequence)
);

CREATE INDEX agent_events_run_sequence_idx ON agent_events(run_id, sequence);

CREATE TABLE tool_executions (
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  batch_id TEXT NOT NULL CHECK (length(trim(batch_id)) > 0),
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  call_id TEXT NOT NULL CHECK (length(trim(call_id)) > 0),
  tool_name TEXT NOT NULL CHECK (length(trim(tool_name)) > 0),
  idempotency_key TEXT,
  status TEXT NOT NULL CHECK (status IN ('Prepared', 'Running', 'Succeeded', 'Failed', 'Unknown')),
  attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  record_json JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(run_id, call_id)
);

CREATE UNIQUE INDEX tool_executions_idempotency_idx
  ON tool_executions(run_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX tool_executions_batch_ordinal_idx ON tool_executions(run_id, batch_id, ordinal);
CREATE INDEX tool_executions_batch_status_idx ON tool_executions(run_id, batch_id, status, ordinal);

CREATE TABLE agent_messages (
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  role TEXT NOT NULL,
  payload JSONB NOT NULL,
  PRIMARY KEY(run_id, ordinal)
);

CREATE TABLE agent_steps (
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  step_type TEXT NOT NULL,
  payload JSONB NOT NULL,
  PRIMARY KEY(run_id, ordinal)
);

CREATE TABLE model_calls (
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  call_id UUID NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  status TEXT NOT NULL,
  usage_json JSONB,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  PRIMARY KEY(run_id, call_id)
);

CREATE TABLE approval_requests (
  approval_id TEXT PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  call_id TEXT NOT NULL,
  status TEXT NOT NULL,
  request_json JSONB NOT NULL,
  decision_json JSONB,
  created_at TIMESTAMPTZ NOT NULL,
  decided_at TIMESTAMPTZ
);

CREATE INDEX approval_requests_run_status_idx ON approval_requests(run_id, status);

CREATE TABLE usage_records (
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  ordinal BIGSERIAL,
  input_tokens BIGINT NOT NULL CHECK (input_tokens >= 0),
  output_tokens BIGINT NOT NULL CHECK (output_tokens >= 0),
  estimated_cost NUMERIC(20, 8),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(run_id, ordinal)
);


-- 长期记忆与 Run 状态分离。Deleted 行保留版本 tombstone，但不保留记忆正文。
CREATE TABLE agent_memories (
  scope_kind TEXT NOT NULL CHECK (scope_kind IN ('session', 'user', 'tenant')),
  scope_key TEXT NOT NULL CHECK (length(trim(scope_key)) > 0),
  tenant_id TEXT,
  user_id TEXT,
  session_id UUID,
  memory_key TEXT NOT NULL CHECK (length(trim(memory_key)) BETWEEN 1 AND 200),
  value_json JSONB,
  search_text TEXT,
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', COALESCE(search_text, ''))) STORED,
  memory_kind TEXT NOT NULL CHECK (memory_kind IN ('preference', 'semantic', 'episodic', 'procedural')),
  importance DOUBLE PRECISION NOT NULL CHECK (importance BETWEEN 0.0 AND 1.0),
  confidence DOUBLE PRECISION NOT NULL CHECK (confidence BETWEEN 0.0 AND 1.0),
  sensitivity TEXT NOT NULL CHECK (sensitivity IN ('public', 'personal', 'sensitive')),
  evidence TEXT NOT NULL CHECK (evidence IN ('user_stated', 'tool_observed', 'imported', 'model_inferred')),
  extractor_version TEXT NOT NULL CHECK (length(trim(extractor_version)) BETWEEN 1 AND 100),
  source_run_id UUID,
  version BIGINT NOT NULL CHECK (version > 0),
  status TEXT NOT NULL CHECK (status IN ('active', 'deleted')),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ,
  PRIMARY KEY(scope_kind, scope_key, memory_key),
  CHECK (
    (scope_kind = 'session' AND session_id IS NOT NULL AND tenant_id IS NULL AND user_id IS NULL)
    OR (scope_kind = 'user' AND session_id IS NULL AND tenant_id IS NOT NULL AND user_id IS NOT NULL)
    OR (scope_kind = 'tenant' AND session_id IS NULL AND tenant_id IS NOT NULL AND user_id IS NULL)
  ),
  CHECK (
    (status = 'active' AND value_json IS NOT NULL AND search_text IS NOT NULL AND deleted_at IS NULL)
    OR (status = 'deleted' AND value_json IS NULL AND search_text IS NULL AND deleted_at IS NOT NULL)
  ),
  CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE INDEX agent_memories_scope_active_idx
  ON agent_memories(scope_kind, scope_key, updated_at DESC, memory_key ASC)
  WHERE status = 'active';
CREATE INDEX agent_memories_search_idx ON agent_memories USING GIN(search_vector) WHERE status = 'active';
CREATE INDEX agent_memories_expiry_idx ON agent_memories(expires_at, scope_kind, scope_key, memory_key)
  WHERE status = 'active' AND expires_at IS NOT NULL;
CREATE INDEX agent_memories_user_governance_idx ON agent_memories(tenant_id, user_id, updated_at DESC)
  WHERE scope_kind = 'user';

-- 用户记忆治理的低敏不可变审计。禁止保存 value_json、search_text、原始 memory_key、query、认证 scopes/attributes。
CREATE TABLE agent_memory_audit (
  audit_id UUID PRIMARY KEY,
  action TEXT NOT NULL CHECK (action IN ('read', 'list', 'search', 'correct', 'delete', 'delete_scope', 'retention_purge')),
  actor_kind TEXT NOT NULL CHECK (actor_kind IN ('authenticated', 'system')),
  actor_tenant_id TEXT,
  actor_user_id TEXT,
  actor_system_name TEXT,
  target_scope_kind TEXT NOT NULL CHECK (target_scope_kind IN ('session', 'user', 'tenant')),
  target_scope_key TEXT NOT NULL CHECK (length(trim(target_scope_key)) > 0),
  target_tenant_id TEXT,
  target_user_id TEXT,
  target_session_id UUID,
  memory_key_hash TEXT CHECK (memory_key_hash IS NULL OR memory_key_hash ~ '^[0-9a-f]{64}$'),
  expected_version BIGINT CHECK (expected_version IS NULL OR expected_version >= 0),
  resulting_version BIGINT CHECK (resulting_version IS NULL OR resulting_version > 0),
  affected_count BIGINT NOT NULL CHECK (affected_count >= 0),
  reason_code TEXT CHECK (reason_code IS NULL OR length(reason_code) BETWEEN 1 AND 80),
  occurred_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (
    (actor_kind = 'authenticated' AND actor_system_name IS NULL AND (actor_tenant_id IS NOT NULL OR actor_user_id IS NOT NULL))
    OR (actor_kind = 'system' AND actor_system_name IS NOT NULL AND actor_tenant_id IS NULL AND actor_user_id IS NULL)
  ),
  CHECK (
    (target_scope_kind = 'session' AND target_session_id IS NOT NULL AND target_tenant_id IS NULL AND target_user_id IS NULL)
    OR (target_scope_kind = 'user' AND target_session_id IS NULL AND target_tenant_id IS NOT NULL AND target_user_id IS NOT NULL)
    OR (target_scope_kind = 'tenant' AND target_session_id IS NULL AND target_tenant_id IS NOT NULL AND target_user_id IS NULL)
  )
);
CREATE INDEX agent_memory_audit_actor_idx
  ON agent_memory_audit(actor_tenant_id, actor_user_id, occurred_at DESC, audit_id);
CREATE INDEX agent_memory_audit_target_idx
  ON agent_memory_audit(target_scope_kind, target_scope_key, occurred_at DESC, audit_id);
CREATE INDEX agent_memory_audit_action_idx
  ON agent_memory_audit(action, occurred_at DESC, audit_id);

-- 每条控制命令都是独立审计事实；同 Run 通过 dispatcher 保证一次只执行一条命令。
CREATE TABLE agent_run_commands (
  command_id UUID PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  command_type TEXT NOT NULL CHECK (command_type IN ('Start', 'Recover', 'ResumeApproval', 'Cancel', 'Retry')),
  payload JSONB NOT NULL,
  idempotency_key TEXT NOT NULL CHECK (length(trim(idempotency_key)) > 0),
  status TEXT NOT NULL CHECK (status IN ('Queued', 'Leased', 'Completed', 'DeadLetter', 'Superseded')),
  priority INTEGER NOT NULL DEFAULT 0,
  available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  manual_retry_count INTEGER NOT NULL DEFAULT 0 CHECK (manual_retry_count >= 0),
  last_failure TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(run_id, idempotency_key)
);

CREATE INDEX agent_run_commands_claimable_idx
  ON agent_run_commands(priority DESC, available_at ASC, created_at ASC, command_id ASC)
  WHERE status = 'Queued';
CREATE INDEX agent_run_commands_dead_letter_idx
  ON agent_run_commands(updated_at ASC, command_id ASC)
  WHERE status = 'DeadLetter';
CREATE INDEX agent_run_commands_run_created_idx
  ON agent_run_commands(run_id, created_at ASC, command_id ASC);

CREATE TABLE agent_run_dispatch (
  run_id UUID PRIMARY KEY REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK (status IN ('Idle', 'Queued', 'Leased')),
  current_command_id UUID REFERENCES agent_run_commands(command_id) ON DELETE SET NULL,
  generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
  lease_owner TEXT,
  lease_token UUID,
  claimed_at TIMESTAMPTZ,
  lease_expires_at TIMESTAMPTZ,
  heartbeat_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (
    (status = 'Leased' AND current_command_id IS NOT NULL AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
      AND claimed_at IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR
    (status <> 'Leased' AND current_command_id IS NULL AND lease_owner IS NULL AND lease_token IS NULL
      AND claimed_at IS NULL AND lease_expires_at IS NULL)
  )
);

CREATE INDEX agent_run_dispatch_owner_idx
  ON agent_run_dispatch(lease_owner, lease_expires_at) WHERE status = 'Leased';

-- producer-side 业务幂等记录。Executing 与业务 mutation/outbox 在同一事务内，因此不会单独留存。
CREATE TABLE agent_business_operations (
  operation_id UUID PRIMARY KEY,
  scope_key TEXT NOT NULL CHECK (length(trim(scope_key)) > 0),
  operation_name TEXT NOT NULL CHECK (length(trim(operation_name)) BETWEEN 1 AND 200),
  idempotency_key TEXT NOT NULL CHECK (length(trim(idempotency_key)) BETWEEN 1 AND 200),
  request_hash CHAR(64) NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('Executing', 'Succeeded')),
  result_json JSONB,
  run_id UUID NOT NULL,
  tool_call_id TEXT NOT NULL CHECK (length(trim(tool_call_id)) > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ,
  UNIQUE(scope_key, operation_name, idempotency_key),
  CHECK (
    (status = 'Executing' AND result_json IS NULL AND completed_at IS NULL)
    OR (status = 'Succeeded' AND result_json IS NOT NULL AND completed_at IS NOT NULL)
  )
);

CREATE INDEX agent_business_operations_run_idx ON agent_business_operations(run_id, created_at DESC);

-- 事件只能由 PostgresTransactionalWriteExecutor 与业务状态同事务写入。
CREATE TABLE agent_outbox_events (
  event_id UUID PRIMARY KEY,
  operation_id UUID NOT NULL REFERENCES agent_business_operations(operation_id) ON DELETE RESTRICT,
  run_id UUID NOT NULL,
  tool_call_id TEXT NOT NULL,
  scope_key TEXT NOT NULL,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  destination TEXT NOT NULL CHECK (length(trim(destination)) BETWEEN 1 AND 200),
  event_type TEXT NOT NULL CHECK (length(trim(event_type)) BETWEEN 1 AND 200),
  aggregate_type TEXT NOT NULL CHECK (length(trim(aggregate_type)) BETWEEN 1 AND 100),
  aggregate_id TEXT NOT NULL CHECK (length(trim(aggregate_id)) BETWEEN 1 AND 200),
  partition_key TEXT NOT NULL CHECK (length(trim(partition_key)) BETWEEN 1 AND 200),
  payload JSONB NOT NULL,
  headers JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(headers) = 'object'),
  status TEXT NOT NULL CHECK (status IN ('Pending', 'Publishing', 'Published', 'DeadLetter')),
  attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
  available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_owner TEXT,
  lease_token UUID,
  lease_expires_at TIMESTAMPTZ,
  heartbeat_at TIMESTAMPTZ,
  last_failure TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMPTZ,
  UNIQUE(operation_id, ordinal),
  CHECK (
    (status = 'Publishing' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR (status <> 'Publishing' AND lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
  ),
  CHECK ((status = 'Published' AND published_at IS NOT NULL) OR status <> 'Published')
);

CREATE INDEX agent_outbox_pending_idx
  ON agent_outbox_events(available_at ASC, created_at ASC, event_id ASC)
  WHERE status = 'Pending';
CREATE INDEX agent_outbox_operation_idx ON agent_outbox_events(operation_id, ordinal);
CREATE INDEX agent_outbox_lease_idx
  ON agent_outbox_events(lease_owner, lease_expires_at) WHERE status = 'Publishing';

-- downstream inbox。消费者业务 mutation 必须与 Processing -> Succeeded 使用同一个 transaction。
CREATE TABLE agent_inbox_messages (
  consumer_name TEXT NOT NULL CHECK (length(trim(consumer_name)) BETWEEN 1 AND 200),
  message_id UUID NOT NULL,
  event_type TEXT NOT NULL CHECK (length(trim(event_type)) BETWEEN 1 AND 200),
  message_hash CHAR(64) NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('Processing', 'Succeeded')),
  result_json JSONB,
  received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at TIMESTAMPTZ,
  PRIMARY KEY(consumer_name, message_id),
  CHECK (
    (status = 'Processing' AND result_json IS NULL AND processed_at IS NULL)
    OR (status = 'Succeeded' AND result_json IS NOT NULL AND processed_at IS NOT NULL)
  )
);

CREATE INDEX agent_inbox_received_idx ON agent_inbox_messages(consumer_name, received_at DESC);

-- 显式 Saga 补偿计划。Registered 不会被 worker 自动执行，必须先 activate。
CREATE TABLE agent_compensations (
  compensation_id UUID PRIMARY KEY,
  operation_id UUID NOT NULL UNIQUE REFERENCES agent_business_operations(operation_id) ON DELETE RESTRICT,
  run_id UUID NOT NULL,
  scope_key TEXT NOT NULL,
  handler_name TEXT NOT NULL CHECK (length(trim(handler_name)) BETWEEN 1 AND 200),
  payload JSONB NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('Registered', 'Pending', 'Running', 'Succeeded', 'Cancelled', 'DeadLetter')),
  attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
  available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_owner TEXT,
  lease_token UUID,
  lease_expires_at TIMESTAMPTZ,
  heartbeat_at TIMESTAMPTZ,
  last_failure TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ,
  CHECK (
    (status = 'Running' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR (status <> 'Running' AND lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
  )
);

CREATE INDEX agent_compensations_pending_idx
  ON agent_compensations(available_at ASC, created_at ASC, compensation_id ASC)
  WHERE status = 'Pending';
CREATE INDEX agent_compensations_lease_idx
  ON agent_compensations(lease_owner, lease_expires_at) WHERE status = 'Running';

COMMENT ON TABLE agent_business_operations IS
  '可靠写工具的 producer-side 业务幂等记录；与业务 mutation/outbox 同事务提交';
COMMENT ON TABLE agent_outbox_events IS
  '事务提交后由独立 worker 至少一次发送；event_id 在重试期间保持稳定';
COMMENT ON TABLE agent_inbox_messages IS
  '下游 consumer/messageId 去重；必须与消费者业务状态同事务提交';
COMMENT ON TABLE agent_compensations IS
  '显式 Saga 补偿计划；Registered 需主动激活，不代表数据库回滚';


-- Embedding 精确缓存不依赖 pgvector：它按完整契约键等值命中，并允许不同 Provider 使用不同维度。
CREATE TABLE agent_embedding_cache (
  tenant_id TEXT NOT NULL CHECK (length(trim(tenant_id)) BETWEEN 1 AND 1000),
  provider TEXT NOT NULL CHECK (length(trim(provider)) BETWEEN 1 AND 200),
  model TEXT NOT NULL CHECK (length(trim(model)) BETWEEN 1 AND 200),
  dimension INTEGER NOT NULL CHECK (dimension > 0),
  key_version TEXT NOT NULL CHECK (length(trim(key_version)) BETWEEN 1 AND 100),
  content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  embedding REAL[] NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, provider, model, dimension, key_version, content_hash),
  CHECK (cardinality(embedding) = dimension)
);

CREATE INDEX agent_embedding_cache_expiry_idx
  ON agent_embedding_cache(expires_at, tenant_id, provider, model, dimension, key_version, content_hash);

-- 一个窗口行是配额累加与 SELECT FOR UPDATE 的锁粒度；窗口长度必须进入主键。
CREATE TABLE agent_embedding_quota_windows (
  tenant_id TEXT NOT NULL CHECK (length(trim(tenant_id)) BETWEEN 1 AND 1000),
  window_millis BIGINT NOT NULL CHECK (window_millis > 0),
  window_start TIMESTAMPTZ NOT NULL,
  requests BIGINT NOT NULL DEFAULT 0 CHECK (requests >= 0),
  texts BIGINT NOT NULL DEFAULT 0 CHECK (texts >= 0),
  characters BIGINT NOT NULL DEFAULT 0 CHECK (characters >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, window_millis, window_start)
);

CREATE INDEX agent_embedding_quota_windows_cleanup_idx
  ON agent_embedding_quota_windows(window_start, window_millis, tenant_id);

-- request_id 在租户内唯一，保证 HTTP/worker 重试不会重复计费；窗口删除时级联释放幂等键。
CREATE TABLE agent_embedding_quota_reservations (
  tenant_id TEXT NOT NULL CHECK (length(trim(tenant_id)) BETWEEN 1 AND 1000),
  request_id TEXT NOT NULL CHECK (length(trim(request_id)) BETWEEN 1 AND 500),
  request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  purpose TEXT NOT NULL CHECK (purpose IN ('query', 'indexing', 'memory')),
  window_millis BIGINT NOT NULL CHECK (window_millis > 0),
  window_start TIMESTAMPTZ NOT NULL,
  requests BIGINT NOT NULL CHECK (requests > 0),
  texts BIGINT NOT NULL CHECK (texts > 0),
  characters BIGINT NOT NULL CHECK (characters > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, request_id),
  FOREIGN KEY(tenant_id, window_millis, window_start)
    REFERENCES agent_embedding_quota_windows(tenant_id, window_millis, window_start)
    ON DELETE CASCADE
);

CREATE INDEX agent_embedding_quota_reservations_window_idx
  ON agent_embedding_quota_reservations(tenant_id, window_millis, window_start);

COMMENT ON TABLE agent_embedding_cache IS
  '按 tenant/provider/model/dimension/key-version/hash 隔离的 Embedding 精确缓存，不保存原始文本';
COMMENT ON TABLE agent_embedding_quota_windows IS
  'Embedding 租户硬配额窗口；同一窗口的检查与累加由行锁串行化';
COMMENT ON TABLE agent_embedding_quota_reservations IS
  'Embedding requestId/hash 幂等账本；与窗口计数在同一事务提交';

-- 长期评测趋势只保存 EvalSuiteSnapshot 低敏投影，不保存问题、回答、引用正文或 grade details。
CREATE TABLE agent_eval_snapshots (
  evaluation_id TEXT PRIMARY KEY CHECK (evaluation_id ~ '^[A-Za-z0-9._-]{1,160}$'),
  schema_version INTEGER NOT NULL CHECK (schema_version = 1),
  suite_kind TEXT NOT NULL CHECK (suite_kind IN ('Agent', 'Rag', 'ContextCompression')),
  suite_id TEXT NOT NULL CHECK (suite_id ~ '^[A-Za-z0-9._-]{1,160}$'),
  dataset_id TEXT NOT NULL CHECK (dataset_id ~ '^[A-Za-z0-9._-]{1,160}$'),
  dataset_version TEXT NOT NULL CHECK (dataset_version ~ '^[A-Za-z0-9._-]{1,160}$'),
  harness_version TEXT NOT NULL CHECK (harness_version ~ '^[A-Za-z0-9._-]{1,160}$'),
  provider TEXT CHECK (provider IS NULL OR length(provider) BETWEEN 1 AND 120),
  model TEXT CHECK (model IS NULL OR length(model) BETWEEN 1 AND 240),
  pricing_version TEXT CHECK (pricing_version IS NULL OR pricing_version ~ '^[A-Za-z0-9._-]{1,160}$'),
  commit_sha TEXT CHECK (commit_sha IS NULL OR commit_sha ~ '^[A-Za-z0-9._-]{1,160}$'),
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ NOT NULL,
  -- TIMESTAMPTZ 为微秒精度；额外保存秒/nano，保证 JVM Instant 的确定性排序。
  finished_epoch_second BIGINT NOT NULL,
  finished_nano INTEGER NOT NULL CHECK (finished_nano BETWEEN 0 AND 999999999),
  passed BOOLEAN NOT NULL,
  pass_rate DOUBLE PRECISION NOT NULL CHECK (
    pass_rate <> 'NaN'::double precision AND pass_rate BETWEEN 0.0 AND 1.0
  ),
  snapshot_sha256 CHAR(64) NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
  snapshot_payload TEXT NOT NULL CHECK (octet_length(snapshot_payload) BETWEEN 2 AND 16777216),
  snapshot_json JSONB NOT NULL CHECK (jsonb_typeof(snapshot_json) = 'object'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (finished_at >= started_at),
  CHECK (snapshot_json = snapshot_payload::jsonb)
);

CREATE INDEX agent_eval_snapshots_history_idx
  ON agent_eval_snapshots(
    suite_kind, suite_id, dataset_id, dataset_version,
    finished_epoch_second DESC, finished_nano DESC, evaluation_id DESC
  );

CREATE INDEX agent_eval_snapshots_latest_passing_idx
  ON agent_eval_snapshots(
    suite_kind, suite_id, dataset_id, dataset_version,
    finished_epoch_second DESC, finished_nano DESC, evaluation_id DESC
  )
  WHERE passed = TRUE;

COMMENT ON TABLE agent_eval_snapshots IS
  'Agent/RAG/Context Compression 的低敏不可变评测快照；evaluation_id 幂等且同 ID 不允许覆盖';

-- 声明式 Workflow 的耐久恢复事实。
-- 不引用 agent_runs：Workflow 可以独立于模型 Agent Run 使用，但 run_id 仍是全局 UUID。
CREATE TABLE agent_workflow_checkpoints (
  run_id UUID PRIMARY KEY,
  schema_version INTEGER NOT NULL CHECK (schema_version = 1),
  workflow_id TEXT NOT NULL CHECK (workflow_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'),
  definition_version INTEGER NOT NULL CHECK (definition_version > 0),
  session_id UUID NOT NULL,
  cursor_kind TEXT NOT NULL CHECK (cursor_kind IN ('At', 'Completed')),
  node_id TEXT CHECK (
    node_id IS NULL OR node_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'
  ),
  step INTEGER NOT NULL CHECK (step >= 0),
  checkpoint_sha256 CHAR(64) NOT NULL CHECK (checkpoint_sha256 ~ '^[0-9a-f]{64}$'),
  checkpoint_payload TEXT NOT NULL CHECK (octet_length(checkpoint_payload) BETWEEN 2 AND 16777216),
  checkpoint_json JSONB NOT NULL CHECK (jsonb_typeof(checkpoint_json) = 'object'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (
    (cursor_kind = 'At' AND node_id IS NOT NULL) OR
    (cursor_kind = 'Completed' AND node_id IS NULL)
  ),
  CHECK (checkpoint_json = checkpoint_payload::jsonb)
);

CREATE INDEX agent_workflow_checkpoints_identity_idx
  ON agent_workflow_checkpoints(workflow_id, definition_version, session_id, updated_at DESC);

COMMENT ON TABLE agent_workflow_checkpoints IS
  '声明式 Workflow 的完整恢复快照；只允许相同 identity 内按 step 单调推进';

-- Workflow 节点 execution ledger、pending outcome 与分布式 fencing。
-- PostgresWorkflowCheckpointStore.commit 在同一事务推进 execution、checkpoint 与 durable wait。
CREATE TABLE agent_workflow_node_executions (
  run_id UUID NOT NULL,
  workflow_id TEXT NOT NULL CHECK (workflow_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'),
  definition_version INTEGER NOT NULL CHECK (definition_version > 0),
  session_id UUID NOT NULL,
  node_id TEXT NOT NULL CHECK (node_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'),
  step INTEGER NOT NULL CHECK (step >= 0),
  visit INTEGER NOT NULL CHECK (visit > 0),
  status TEXT NOT NULL CHECK (status IN ('Running', 'Prepared', 'Committed')),
  generation BIGINT NOT NULL CHECK (generation > 0),
  lease_owner TEXT NOT NULL CHECK (length(btrim(lease_owner)) BETWEEN 1 AND 200),
  lease_token UUID NOT NULL,
  claimed_at TIMESTAMPTZ NOT NULL,
  lease_expires_at TIMESTAMPTZ,
  heartbeat_at TIMESTAMPTZ,
  outcome_sha256 CHAR(64) CHECK (
    outcome_sha256 IS NULL OR outcome_sha256 ~ '^[0-9a-f]{64}$'
  ),
  outcome_payload TEXT CHECK (
    outcome_payload IS NULL OR octet_length(outcome_payload) BETWEEN 2 AND 16777216
  ),
  outcome_json JSONB CHECK (
    outcome_json IS NULL OR jsonb_typeof(outcome_json) = 'object'
  ),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ,
  PRIMARY KEY (run_id, step, node_id),
  CHECK (
    (status = 'Running'
      AND lease_expires_at IS NOT NULL
      AND heartbeat_at IS NOT NULL
      AND outcome_sha256 IS NULL
      AND outcome_payload IS NULL
      AND outcome_json IS NULL
      AND completed_at IS NULL)
    OR
    (status = 'Prepared'
      AND lease_expires_at IS NOT NULL
      AND heartbeat_at IS NOT NULL
      AND outcome_sha256 IS NOT NULL
      AND outcome_payload IS NOT NULL
      AND outcome_json IS NOT NULL
      AND completed_at IS NULL)
    OR
    (status = 'Committed'
      AND lease_expires_at IS NULL
      AND heartbeat_at IS NULL
      AND outcome_sha256 IS NOT NULL
      AND outcome_payload IS NOT NULL
      AND outcome_json IS NOT NULL
      AND completed_at IS NOT NULL)
  ),
  CHECK (
    (outcome_payload IS NULL AND outcome_json IS NULL)
    OR outcome_json = outcome_payload::jsonb
  )
);

CREATE INDEX agent_workflow_node_executions_claim_idx
  ON agent_workflow_node_executions(status, lease_expires_at, updated_at)
  WHERE status IN ('Running', 'Prepared');

CREATE INDEX agent_workflow_node_executions_identity_idx
  ON agent_workflow_node_executions(workflow_id, definition_version, session_id, run_id, step);

COMMENT ON TABLE agent_workflow_node_executions IS
  'Workflow 节点执行台账；Prepared outcome 可跨进程恢复，commit 与 checkpoint 在同一事务完成';

-- Durable Workflow timer/signal：等待注册与 checkpoint/execution commit 共享事务。
CREATE TABLE agent_workflow_waits (
  run_id UUID NOT NULL,
  step INTEGER NOT NULL CHECK (step >= 0),
  node_id TEXT NOT NULL CHECK (node_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'),
  workflow_id TEXT NOT NULL CHECK (workflow_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'),
  definition_version INTEGER NOT NULL CHECK (definition_version > 0),
  session_id UUID NOT NULL,
  condition_kind TEXT NOT NULL CHECK (condition_kind IN ('Signal', 'Timer')),
  signal_name TEXT CHECK (
    signal_name IS NULL OR signal_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'
  ),
  deadline TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('Pending', 'Signaled', 'TimedOut', 'Consumed')),
  accepted_signal_id TEXT CHECK (
    accepted_signal_id IS NULL OR accepted_signal_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$'
  ),
  accepted_signal_payload TEXT CHECK (
    accepted_signal_payload IS NULL OR octet_length(accepted_signal_payload) <= 65536
  ),
  accepted_signal_sha256 CHAR(64) CHECK (
    accepted_signal_sha256 IS NULL OR accepted_signal_sha256 ~ '^[0-9a-f]{64}$'
  ),
  signal_received_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMPTZ,
  consumed_at TIMESTAMPTZ,
  wake_available_at TIMESTAMPTZ,
  wake_generation BIGINT NOT NULL DEFAULT 0 CHECK (wake_generation >= 0),
  wake_owner TEXT,
  wake_token UUID,
  wake_claimed_at TIMESTAMPTZ,
  wake_lease_expires_at TIMESTAMPTZ,
  wake_heartbeat_at TIMESTAMPTZ,
  PRIMARY KEY (run_id, step, node_id),
  FOREIGN KEY (run_id, step, node_id)
    REFERENCES agent_workflow_node_executions(run_id, step, node_id),
  CHECK (
    (condition_kind = 'Signal' AND signal_name IS NOT NULL) OR
    (condition_kind = 'Timer' AND signal_name IS NULL)
  ),
  CHECK (
    (status = 'Pending'
      AND resolved_at IS NULL
      AND consumed_at IS NULL
      AND wake_available_at IS NULL
      AND wake_generation = 0
      AND accepted_signal_id IS NULL
      AND accepted_signal_payload IS NULL
      AND accepted_signal_sha256 IS NULL
      AND signal_received_at IS NULL)
    OR
    (status = 'Signaled'
      AND resolved_at IS NOT NULL
      AND consumed_at IS NULL
      AND wake_available_at IS NOT NULL
      AND accepted_signal_id IS NOT NULL
      AND accepted_signal_payload IS NOT NULL
      AND accepted_signal_sha256 IS NOT NULL
      AND signal_received_at IS NOT NULL)
    OR
    (status = 'TimedOut'
      AND resolved_at IS NOT NULL
      AND consumed_at IS NULL
      AND wake_available_at IS NOT NULL
      AND accepted_signal_id IS NULL
      AND accepted_signal_payload IS NULL
      AND accepted_signal_sha256 IS NULL
      AND signal_received_at IS NULL)
    OR
    (status = 'Consumed'
      AND resolved_at IS NOT NULL
      AND consumed_at IS NOT NULL
      AND wake_available_at IS NULL
      AND wake_generation > 0)
  ),
  CHECK (
    (wake_owner IS NULL
      AND wake_token IS NULL
      AND wake_claimed_at IS NULL
      AND wake_lease_expires_at IS NULL
      AND wake_heartbeat_at IS NULL)
    OR
    (status IN ('Signaled', 'TimedOut')
      AND wake_owner IS NOT NULL
      AND wake_token IS NOT NULL
      AND wake_claimed_at IS NOT NULL
      AND wake_lease_expires_at IS NOT NULL
      AND wake_heartbeat_at IS NOT NULL
      AND wake_generation > 0
      AND wake_lease_expires_at > wake_claimed_at
      AND wake_heartbeat_at >= wake_claimed_at
      AND wake_heartbeat_at < wake_lease_expires_at)
  )
);

CREATE UNIQUE INDEX agent_workflow_waits_one_active_per_run_idx
  ON agent_workflow_waits(run_id)
  WHERE status <> 'Consumed';

CREATE INDEX agent_workflow_waits_due_idx
  ON agent_workflow_waits(deadline, run_id, step, node_id)
  WHERE status = 'Pending';

CREATE INDEX agent_workflow_waits_wake_claim_idx
  ON agent_workflow_waits(
    workflow_id, definition_version, wake_available_at, resolved_at, run_id, step, node_id
  )
  WHERE status IN ('Signaled', 'TimedOut');

-- 所有 signal 尝试都保存稳定 receipt；相同 wait/signal_id 不会被重复应用。
CREATE TABLE agent_workflow_signals (
  run_id UUID NOT NULL,
  wait_step INTEGER NOT NULL CHECK (wait_step >= 0),
  wait_node_id TEXT NOT NULL,
  signal_id TEXT NOT NULL CHECK (signal_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$'),
  signal_name TEXT NOT NULL CHECK (signal_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'),
  payload TEXT NOT NULL CHECK (octet_length(payload) <= 65536),
  payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  disposition TEXT NOT NULL CHECK (disposition IN ('Accepted', 'Late', 'AlreadyResolved')),
  received_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (run_id, wait_step, wait_node_id, signal_id),
  FOREIGN KEY (run_id, wait_step, wait_node_id)
    REFERENCES agent_workflow_waits(run_id, step, node_id)
);

COMMENT ON TABLE agent_workflow_waits IS
  'Workflow durable timer/signal；resolved/consumed 与 execution/checkpoint 形成唯一唤醒边界';
COMMENT ON TABLE agent_workflow_signals IS
  '外部 Workflow signal 去重 receipt；payload 不进入 timeline、日志或指标';
