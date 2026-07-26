-- 长期评测趋势只保存 EvalSuiteSnapshot 低敏投影，不保存问题、回答、引用正文或 grade details。
-- V001 已部署且 checksum 不可变，因此本能力必须通过新的前向 migration 引入。
-- 测试环境曾在 Flyway 记账前人工预建过同构表；首次正式纳管时允许保留该表，再由 Flyway 记录 V007。
-- 生产环境仍必须先通过结构审计确认既有对象与本文件完全一致，不能把 IF NOT EXISTS 当作结构校验。
CREATE TABLE IF NOT EXISTS agent_eval_snapshots (
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

CREATE INDEX IF NOT EXISTS agent_eval_snapshots_history_idx
  ON agent_eval_snapshots(
    suite_kind, suite_id, dataset_id, dataset_version,
    finished_epoch_second DESC, finished_nano DESC, evaluation_id DESC
  );

CREATE INDEX IF NOT EXISTS agent_eval_snapshots_latest_passing_idx
  ON agent_eval_snapshots(
    suite_kind, suite_id, dataset_id, dataset_version,
    finished_epoch_second DESC, finished_nano DESC, evaluation_id DESC
  )
  WHERE passed = TRUE;

COMMENT ON TABLE agent_eval_snapshots IS
  'Agent/RAG/Context Compression 的低敏不可变评测快照；evaluation_id 幂等且同 ID 不允许覆盖';
