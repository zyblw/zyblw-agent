-- 0.6 演进：同一正文在 Query / Indexing / Memory 用途下可通过不同 instruction 编码，缓存主键必须包含用途。
-- 已有 0.5 及之前的行标记为 legacy；新代码绝不读取 legacy，从而安全失效旧缓存而不删除可审计的派生数据。
ALTER TABLE agent_embedding_cache
  ADD COLUMN purpose TEXT NOT NULL DEFAULT 'legacy'
  CHECK (purpose IN ('query', 'indexing', 'memory', 'legacy'));

ALTER TABLE agent_embedding_cache
  DROP CONSTRAINT agent_embedding_cache_pkey;

ALTER TABLE agent_embedding_cache
  ADD PRIMARY KEY (tenant_id, purpose, provider, model, dimension, key_version, content_hash);

DROP INDEX agent_embedding_cache_expiry_idx;

CREATE INDEX agent_embedding_cache_expiry_idx
  ON agent_embedding_cache(expires_at, tenant_id, purpose, provider, model, dimension, key_version, content_hash);

COMMENT ON COLUMN agent_embedding_cache.purpose IS
  'Embedding 的可信用途（query/indexing/memory）；legacy 为升级前安全失效的缓存行';
