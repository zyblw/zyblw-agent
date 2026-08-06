-- 管理面（Run 目录、运行时配置覆盖、文档摄入任务）所需的读模型与新表。
--
-- Run 目录需要按租户、Agent、状态和审批等待过滤，并按 (updated_at DESC, run_id DESC) 稳定 keyset 翻页。
-- 这些维度目前只存在于 state_json 内部，逐行解析 JSONB 无法走索引，因此把它们提升为生成列：
-- 生成列由 PostgreSQL 在写入时维护，运行时代码不需要改变任何写路径，也不会出现读模型与权威状态不一致。

ALTER TABLE agent_runs
  ADD COLUMN tenant_id TEXT
    GENERATED ALWAYS AS (state_json #>> '{runContext,tenantId}') STORED,
  ADD COLUMN user_id TEXT
    GENERATED ALWAYS AS (state_json #>> '{runContext,userId}') STORED,
  -- zio-json 省略 None 字段，因此“存在且为对象”是判断待审批的唯一可靠条件；
  -- COALESCE 处理键缺失时 jsonb_typeof 返回 NULL 的情况，保证生成列非空。
  ADD COLUMN awaiting_approval BOOLEAN NOT NULL
    GENERATED ALWAYS AS (COALESCE(jsonb_typeof(state_json -> 'pendingApproval') = 'object', FALSE)) STORED;

-- 管理台默认视图：跨租户按更新时间倒序翻页。
CREATE INDEX agent_runs_admin_updated_idx ON agent_runs(updated_at DESC, run_id DESC);

-- 单租户视图；多租户部署的管理台几乎总是先选租户再翻页。
CREATE INDEX agent_runs_admin_tenant_updated_idx
  ON agent_runs(tenant_id, updated_at DESC, run_id DESC)
  WHERE tenant_id IS NOT NULL;

-- 审批台：等待人工处理的 Run 通常只占总量的极小比例，部分索引避免扫描全表。
CREATE INDEX agent_runs_admin_approval_idx
  ON agent_runs(updated_at DESC, run_id DESC)
  WHERE awaiting_approval;

-- 按 Agent 维度排查某个 Agent 的近期运行。
CREATE INDEX agent_runs_admin_agent_updated_idx ON agent_runs(agent_id, updated_at DESC, run_id DESC);

-- 运行时配置覆盖。
--
-- 表保存**完整快照**而不是增量补丁：每一行就是那个版本下生效的全部覆盖，回滚等于重新提交某个历史版本的 overrides，
-- 不需要重放增量。version 单调递增并作为 CAS 令牌，两个管理员并发编辑时后提交者会因版本不匹配而被拒绝。
--
-- 表同时是覆盖存储和审计日志。分成两张表会引入“配置写入成功但审计写入失败”的窗口，而单表 append-only 天然一致。
CREATE TABLE agent_runtime_overrides (
  version BIGINT PRIMARY KEY CHECK (version > 0),
  overrides JSONB NOT NULL,
  -- 由认证层提供的操作者标签（tenant/user），不保存 token、IP 或完整身份负载。
  updated_by TEXT NOT NULL CHECK (length(trim(updated_by)) BETWEEN 1 AND 200),
  reason TEXT NOT NULL CHECK (length(reason) <= 512),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX agent_runtime_overrides_recent_idx ON agent_runtime_overrides(version DESC);

-- 异步文档摄入任务。
--
-- 任务状态机与 KnowledgeIndexStore 的 begin→stage→activate 协议对齐，因此管理台进度条对应真实索引状态，
-- 而不是一个按时间推进的假动画。正文本身不落这张表：字节在后台处理完即丢弃，索引结果由 knowledge_index_manifests 承载。
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
  -- 稳定失败分类，不保存 Provider 原始响应或异常堆栈。
  failure_code TEXT,
  submitted_by TEXT NOT NULL CHECK (length(trim(submitted_by)) BETWEEN 1 AND 200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK ((status = 'Failed') = (failure_code IS NOT NULL))
);

CREATE INDEX agent_ingestion_jobs_recent_idx ON agent_ingestion_jobs(created_at DESC, job_id DESC);
CREATE INDEX agent_ingestion_jobs_tenant_recent_idx
  ON agent_ingestion_jobs(tenant_id, created_at DESC, job_id DESC);
-- 进程重启后回收仍停在非终态的任务。
CREATE INDEX agent_ingestion_jobs_active_idx
  ON agent_ingestion_jobs(updated_at ASC)
  WHERE status NOT IN ('Completed', 'Failed');
