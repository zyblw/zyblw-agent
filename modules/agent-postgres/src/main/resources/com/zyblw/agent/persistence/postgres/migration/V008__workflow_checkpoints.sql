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
