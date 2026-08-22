#!/usr/bin/env python3
"""Fold published V001–V013 into a single 0.8.0 fresh-install baseline.

This is a one-shot generator used while constructing the 0.8.0 candidate. The
resulting SQL is the source of truth; the script is kept for auditability.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (
    ROOT
    / "modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/migration"
)
KNOWLEDGE_OLD = (
    ROOT
    / "modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/optional/pgvector_1024_v0_6"
)
KNOWLEDGE_NEW = (
    ROOT
    / "modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/optional/pgvector_1024"
)


def strip_dead_tables(sql: str) -> str:
    dead = [
        (
            "CREATE TABLE agent_messages (",
            "CREATE TABLE approval_requests (",
        ),
        (
            "CREATE TABLE usage_records (",
            "\n-- 长期记忆与 Run 状态分离。",
        ),
    ]
    for start, end in dead:
        start_i = sql.find(start)
        end_i = sql.find(end)
        if start_i < 0 or end_i < 0 or end_i <= start_i:
            raise SystemExit(f"failed to locate dead table block starting {start!r}")
        sql = sql[:start_i] + sql[end_i:]
    return sql


def inject_run_generated_columns(sql: str) -> str:
    needle = """  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,"""
    replacement = """  tenant_id TEXT
    GENERATED ALWAYS AS (state_json #>> '{runContext,tenantId}') STORED,
  user_id TEXT
    GENERATED ALWAYS AS (state_json #>> '{runContext,userId}') STORED,
  awaiting_approval BOOLEAN NOT NULL
    GENERATED ALWAYS AS (COALESCE(jsonb_typeof(state_json -> 'pendingApproval') = 'object', FALSE)) STORED,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,"""
    if needle not in sql:
        raise SystemExit("agent_runs timestamp columns not found")
    sql = sql.replace(needle, replacement, 1)
    extra_indexes = """
CREATE INDEX agent_runs_admin_updated_idx ON agent_runs(updated_at DESC, run_id DESC);
CREATE INDEX agent_runs_admin_tenant_updated_idx
  ON agent_runs(tenant_id, updated_at DESC, run_id DESC)
  WHERE tenant_id IS NOT NULL;
CREATE INDEX agent_runs_admin_approval_idx
  ON agent_runs(updated_at DESC, run_id DESC)
  WHERE awaiting_approval;
CREATE INDEX agent_runs_admin_agent_updated_idx ON agent_runs(agent_id, updated_at DESC, run_id DESC);
"""
    marker = "CREATE UNIQUE INDEX agent_runs_start_idempotency_idx"
    end = sql.find(";\n", sql.find(marker)) + 2
    sql = sql[:end] + extra_indexes + sql[end:]
    return sql


def inject_embedding_purpose(sql: str) -> str:
    sql = sql.replace(
        """CREATE TABLE agent_embedding_cache (
  tenant_id TEXT NOT NULL CHECK (length(trim(tenant_id)) BETWEEN 1 AND 1000),
  provider TEXT NOT NULL CHECK (length(trim(provider)) BETWEEN 1 AND 200),""",
        """CREATE TABLE agent_embedding_cache (
  tenant_id TEXT NOT NULL CHECK (length(trim(tenant_id)) BETWEEN 1 AND 1000),
  purpose TEXT NOT NULL CHECK (purpose IN ('query', 'indexing', 'memory')),
  provider TEXT NOT NULL CHECK (length(trim(provider)) BETWEEN 1 AND 200),""",
        1,
    )
    sql = sql.replace(
        "PRIMARY KEY(tenant_id, provider, model, dimension, key_version, content_hash),",
        "PRIMARY KEY(tenant_id, purpose, provider, model, dimension, key_version, content_hash),",
        1,
    )
    sql = sql.replace(
        "ON agent_embedding_cache(expires_at, tenant_id, provider, model, dimension, key_version, content_hash);",
        "ON agent_embedding_cache(expires_at, tenant_id, purpose, provider, model, dimension, key_version, content_hash);",
        1,
    )
    return sql


def update_eval_and_audit(sql: str) -> str:
    sql = sql.replace(
        "CHECK (suite_kind IN ('Agent', 'Rag', 'ContextCompression')),",
        "CHECK (suite_kind IN ('Agent', 'AgentReliability', 'HarnessComparison', 'Rag', 'ContextCompression')),",
        1,
    )
    sql = sql.replace(
        "CHECK (action IN ('read', 'list', 'search', 'correct', 'delete', 'delete_scope', 'retention_purge')),",
        "CHECK (action IN ('read', 'list', 'search', 'correct', 'delete', 'delete_scope', 'retention_purge', 'export')),",
        1,
    )
    return sql


def append_later_tables() -> str:
    return r"""
CREATE TABLE agent_runtime_overrides (
  version BIGINT PRIMARY KEY CHECK (version > 0),
  overrides JSONB NOT NULL,
  updated_by TEXT NOT NULL CHECK (length(trim(updated_by)) BETWEEN 1 AND 200),
  reason TEXT NOT NULL CHECK (length(reason) <= 512),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX agent_runtime_overrides_recent_idx ON agent_runtime_overrides(version DESC);

CREATE TABLE agent_ingestion_jobs (
  job_id UUID PRIMARY KEY,
  tenant_id TEXT NOT NULL CHECK (length(trim(tenant_id)) BETWEEN 1 AND 200),
  source_uri TEXT NOT NULL CHECK (length(trim(source_uri)) > 0),
  file_name TEXT NOT NULL CHECK (length(trim(file_name)) BETWEEN 1 AND 400),
  media_type TEXT NOT NULL CHECK (length(trim(media_type)) > 0),
  status TEXT NOT NULL CHECK (
    status IN ('Queued', 'Loading', 'Chunking', 'Embedding', 'Staging', 'Activating', 'Completed', 'Failed')
  ),
  progress_percent INTEGER NOT NULL CHECK (progress_percent BETWEEN 0 AND 100),
  document_id TEXT,
  index_version BIGINT CHECK (index_version IS NULL OR index_version > 0),
  chunk_count INTEGER CHECK (chunk_count IS NULL OR chunk_count >= 0),
  failure_code TEXT,
  submitted_by TEXT NOT NULL CHECK (length(trim(submitted_by)) BETWEEN 1 AND 200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK ((status = 'Failed') = (failure_code IS NOT NULL))
);
CREATE INDEX agent_ingestion_jobs_recent_idx ON agent_ingestion_jobs(created_at DESC, job_id DESC);
CREATE INDEX agent_ingestion_jobs_tenant_recent_idx
  ON agent_ingestion_jobs(tenant_id, created_at DESC, job_id DESC);
CREATE INDEX agent_ingestion_jobs_active_idx
  ON agent_ingestion_jobs(updated_at ASC)
  WHERE status NOT IN ('Completed', 'Failed');

CREATE TABLE model_call_executions (
  run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
  request_id UUID NOT NULL,
  attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  status TEXT NOT NULL CHECK (status IN ('Prepared', 'Dispatched', 'Succeeded', 'Failed', 'Unknown')),
  provider TEXT NOT NULL CHECK (length(trim(provider)) > 0),
  model TEXT NOT NULL CHECK (length(trim(model)) > 0),
  capture_policy TEXT NOT NULL CHECK (capture_policy IN ('Disabled', 'MetadataOnly', 'Replayable')),
  fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  message_count INTEGER NOT NULL CHECK (message_count >= 0),
  tool_count INTEGER NOT NULL CHECK (tool_count >= 0),
  record_json JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (run_id, request_id)
);
CREATE INDEX model_call_executions_run_status_idx
  ON model_call_executions(run_id, status, updated_at);

CREATE TABLE harness_goals (
  goal_id UUID PRIMARY KEY,
  thread_id TEXT NOT NULL CHECK (char_length(btrim(thread_id)) BETWEEN 1 AND 255),
  objective TEXT NOT NULL CHECK (char_length(btrim(objective)) BETWEEN 1 AND 4000),
  status TEXT NOT NULL CHECK (status IN ('Draft', 'Active', 'Completed', 'Cancelled', 'Failed')),
  run_id UUID,
  revision BIGINT NOT NULL CHECK (revision > 0),
  artifacts_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(artifacts_json) = 'array'),
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX harness_goals_thread_updated_idx ON harness_goals(thread_id, updated_at);

CREATE TABLE harness_plans (
  plan_id UUID PRIMARY KEY,
  goal_id UUID NOT NULL REFERENCES harness_goals(goal_id) ON DELETE CASCADE,
  summary TEXT NOT NULL CHECK (char_length(btrim(summary)) BETWEEN 1 AND 4000),
  todos_json JSONB NOT NULL,
  artifacts_json JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(artifacts_json) = 'array'),
  revision BIGINT NOT NULL CHECK (revision > 0),
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX harness_plans_goal_updated_idx ON harness_plans(goal_id, updated_at);

CREATE TABLE harness_skills (
  skill_id TEXT NOT NULL CHECK (char_length(btrim(skill_id)) BETWEEN 1 AND 64 AND position('@' IN skill_id) = 0),
  skill_version TEXT NOT NULL CHECK (skill_version ~ '^[A-Za-z0-9._-]{1,32}$'),
  source TEXT NOT NULL CHECK (char_length(btrim(source)) BETWEEN 1 AND 256),
  trust TEXT NOT NULL CHECK (trust IN ('Untrusted', 'Reviewed', 'Trusted')),
  body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 16000),
  fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY (skill_id, skill_version)
);

CREATE TABLE harness_interactions (
  interaction_id UUID PRIMARY KEY,
  goal_id UUID NOT NULL REFERENCES harness_goals(goal_id) ON DELETE CASCADE,
  run_id UUID,
  kind TEXT NOT NULL CHECK (kind IN ('Steer', 'FollowUp', 'UserMessage')),
  body TEXT NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 4000),
  sequence BIGINT NOT NULL CHECK (sequence > 0),
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (goal_id, sequence)
);
CREATE INDEX harness_interactions_goal_created_idx ON harness_interactions(goal_id, created_at);

CREATE TABLE harness_goal_budgets (
  goal_id UUID PRIMARY KEY REFERENCES harness_goals(goal_id) ON DELETE CASCADE,
  max_runs BIGINT NOT NULL CHECK (max_runs > 0),
  max_model_calls BIGINT NOT NULL CHECK (max_model_calls > 0),
  max_tool_calls BIGINT NOT NULL CHECK (max_tool_calls > 0),
  max_input_tokens BIGINT NOT NULL CHECK (max_input_tokens > 0),
  max_output_tokens BIGINT NOT NULL CHECK (max_output_tokens > 0),
  max_total_tokens BIGINT NOT NULL CHECK (
    max_total_tokens > 0 AND
    max_total_tokens::numeric <= max_input_tokens::numeric + max_output_tokens::numeric
  ),
  max_estimated_cost NUMERIC CHECK (max_estimated_cost > 0),
  reserved_runs BIGINT NOT NULL DEFAULT 0 CHECK (reserved_runs >= 0),
  reserved_model_calls BIGINT NOT NULL DEFAULT 0 CHECK (reserved_model_calls >= 0),
  reserved_tool_calls BIGINT NOT NULL DEFAULT 0 CHECK (reserved_tool_calls >= 0),
  reserved_input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (reserved_input_tokens >= 0),
  reserved_output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (reserved_output_tokens >= 0),
  reserved_total_tokens BIGINT NOT NULL DEFAULT 0 CHECK (reserved_total_tokens >= 0),
  reserved_estimated_cost NUMERIC NOT NULL DEFAULT 0 CHECK (reserved_estimated_cost >= 0),
  consumed_runs BIGINT NOT NULL DEFAULT 0 CHECK (consumed_runs >= 0),
  consumed_model_calls BIGINT NOT NULL DEFAULT 0 CHECK (consumed_model_calls >= 0),
  consumed_tool_calls BIGINT NOT NULL DEFAULT 0 CHECK (consumed_tool_calls >= 0),
  consumed_input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (consumed_input_tokens >= 0),
  consumed_output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (consumed_output_tokens >= 0),
  consumed_total_tokens BIGINT NOT NULL DEFAULT 0 CHECK (consumed_total_tokens >= 0),
  consumed_estimated_cost NUMERIC NOT NULL DEFAULT 0 CHECK (consumed_estimated_cost >= 0),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE harness_budget_reservations (
  run_id UUID PRIMARY KEY,
  goal_id UUID NOT NULL REFERENCES harness_goal_budgets(goal_id) ON DELETE CASCADE,
  limits_json JSONB NOT NULL CHECK (jsonb_typeof(limits_json) = 'object'),
  status TEXT NOT NULL CHECK (status IN ('Reserved', 'Settled', 'Released', 'Exceeded')),
  usage_json JSONB,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (
    (status IN ('Reserved', 'Released') AND usage_json IS NULL) OR
    (status IN ('Settled', 'Exceeded') AND jsonb_typeof(usage_json) = 'object')
  )
);
CREATE INDEX harness_budget_reservations_goal_status_idx
  ON harness_budget_reservations(goal_id, status);
CREATE INDEX harness_budget_reservations_status_created_idx
  ON harness_budget_reservations(status, created_at, run_id);

CREATE TABLE agent_artifacts (
  scope_kind TEXT NOT NULL CHECK (scope_kind IN ('session', 'user')),
  scope_key TEXT NOT NULL CHECK (length(trim(scope_key)) > 0 AND length(scope_key) <= 500),
  name TEXT NOT NULL CHECK (length(trim(name)) > 0 AND length(name) <= 255),
  latest_version BIGINT NOT NULL CHECK (latest_version > 0),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (scope_kind, scope_key, name)
);

CREATE TABLE agent_artifact_versions (
  scope_kind TEXT NOT NULL,
  scope_key TEXT NOT NULL,
  name TEXT NOT NULL,
  version BIGINT NOT NULL CHECK (version > 0),
  media_type TEXT NOT NULL,
  byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
  sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  metadata_json JSONB NOT NULL,
  bytes BYTEA,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (scope_kind, scope_key, name, version),
  FOREIGN KEY (scope_kind, scope_key, name)
    REFERENCES agent_artifacts(scope_kind, scope_key, name)
    ON DELETE CASCADE
);
CREATE INDEX agent_artifact_versions_created_idx
  ON agent_artifact_versions(created_at, scope_kind, scope_key, name, version);

CREATE TABLE agent_artifact_audit (
  audit_id UUID PRIMARY KEY,
  action TEXT NOT NULL CHECK (action IN ('save', 'read', 'delete', 'purge')),
  scope_kind TEXT NOT NULL CHECK (scope_kind IN ('session', 'user')),
  scope_key TEXT NOT NULL,
  name_hash TEXT NOT NULL CHECK (name_hash ~ '^[0-9a-f]{64}$'),
  version BIGINT CHECK (version IS NULL OR version > 0),
  reason_code TEXT NOT NULL CHECK (length(reason_code) BETWEEN 1 AND 80),
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX agent_artifact_audit_scope_idx
  ON agent_artifact_audit(scope_kind, scope_key, occurred_at DESC);
"""


def fold_core() -> Path:
    source = (MIGRATION / "V001__zyblw_agent_0_3_baseline.sql").read_text()
    sql = source
    sql = sql.replace(
        "-- zyblw-agent 0.3 全新数据库基线。",
        "-- zyblw-agent 0.8 全新数据库基线。",
        1,
    )
    sql = sql.replace(
        "-- 这是开发阶段允许破坏性重构后的唯一默认 migration；不支持从 0.2.x 的 Flyway 历史原地升级。",
        "-- 这是开发阶段允许破坏性重构后的唯一默认 migration；不支持从 0.7.x 及更早 Flyway 历史原地升级。",
        1,
    )
    sql = strip_dead_tables(sql)
    sql = inject_run_generated_columns(sql)
    sql = inject_embedding_purpose(sql)
    sql = update_eval_and_audit(sql)
    sql = sql.rstrip() + "\n" + append_later_tables()
    target = MIGRATION / "V001__zyblw_agent_0_8_baseline.sql"
    target.write_text(sql)
    return target


def fold_knowledge() -> Path:
    KNOWLEDGE_NEW.mkdir(parents=True, exist_ok=True)
    source = (KNOWLEDGE_OLD / "V001__agent_knowledge_pgvector_1024_baseline.sql").read_text()
    sql = source.replace(
        "-- Fresh 1024-dimension RAG baseline.",
        "-- zyblw-agent 0.8 fresh 1024-dimension RAG baseline.",
        1,
    )
    sql = sql.replace(
        "CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;",
        "CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;\nCREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;",
        1,
    )
    extra = """
CREATE INDEX agent_knowledge_chunks_metadata_idx
  ON agent_knowledge_chunks USING GIN (metadata jsonb_path_ops);
CREATE INDEX agent_knowledge_chunks_heading_path_idx
  ON agent_knowledge_chunks USING GIN (heading_path);
CREATE INDEX agent_knowledge_chunks_search_text_trgm_idx
  ON agent_knowledge_chunks USING GIN (search_text public.gin_trgm_ops);
CREATE INDEX agent_knowledge_chunk_staging_metadata_idx
  ON agent_knowledge_chunk_staging USING GIN (metadata jsonb_path_ops);
"""
    target = KNOWLEDGE_NEW / "V001__agent_knowledge_0_8_baseline.sql"
    target.write_text(sql.rstrip() + "\n" + extra)
    comments = (KNOWLEDGE_OLD / "R__agent_knowledge_1024_comments.sql").read_text()
    (KNOWLEDGE_NEW / "R__agent_knowledge_1024_comments.sql").write_text(comments)
    return target


def delete_old_core() -> None:
    keep = {
        "V001__zyblw_agent_0_8_baseline.sql",
        "R__zyblw_agent_schema_comments.sql",
    }
    for path in MIGRATION.iterdir():
        if path.name not in keep and path.suffix == ".sql":
            path.unlink()


def main() -> None:
    core = fold_core()
    knowledge = fold_knowledge()
    delete_old_core()
    print(f"wrote {core}")
    print(f"wrote {knowledge}")


if __name__ == "__main__":
    main()
