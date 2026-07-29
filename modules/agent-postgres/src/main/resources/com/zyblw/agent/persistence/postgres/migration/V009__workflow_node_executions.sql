-- Workflow 节点 execution ledger、pending outcome 与分布式 fencing。
-- checkpoint 仍由 V008 的单行事实源保存；PostgresWorkflowCheckpointStore.commit 在同一事务推进两张表。
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
