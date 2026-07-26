-- 可选 RAG/pgvector 表。执行前确认 Embedding 维度确实为 1536。
BEGIN;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS agent_knowledge_documents (
  tenant_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  ingestion_id TEXT NOT NULL,
  source_uri TEXT NOT NULL,
  content_hash TEXT NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
  embedding_provider TEXT NOT NULL,
  embedding_model TEXT NOT NULL,
  embedding_dimension INTEGER NOT NULL CHECK (embedding_dimension > 0),
  embedding_max_batch_size INTEGER NOT NULL CHECK (embedding_max_batch_size > 0),
  embedding_supports_dimensions BOOLEAN NOT NULL,
  indexing_strategy TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('building', 'ready', 'superseded', 'failed', 'retired')),
  active BOOLEAN NOT NULL DEFAULT FALSE,
  chunk_count INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
  failure_code TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, document_id, index_version),
  UNIQUE (tenant_id, document_id, ingestion_id),
  CHECK (NOT active OR status = 'ready')
);
CREATE UNIQUE INDEX IF NOT EXISTS agent_knowledge_documents_one_active_idx
  ON agent_knowledge_documents(tenant_id, document_id) WHERE active;
CREATE INDEX IF NOT EXISTS agent_knowledge_documents_recovery_idx
  ON agent_knowledge_documents(status, updated_at) WHERE status = 'building';
CREATE INDEX IF NOT EXISTS agent_knowledge_documents_retention_idx
  ON agent_knowledge_documents(updated_at, tenant_id, document_id, index_version)
  WHERE active = FALSE AND status IN ('superseded', 'failed', 'retired');

CREATE TABLE IF NOT EXISTS agent_knowledge_chunk_staging (
  tenant_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  index_version BIGINT NOT NULL,
  chunk_id TEXT NOT NULL,
  chunk_text TEXT NOT NULL,
  search_text TEXT NOT NULL,
  source_uri TEXT NOT NULL,
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
  embedding VECTOR(1536) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, document_id, index_version, chunk_id),
  FOREIGN KEY (tenant_id, document_id, index_version)
    REFERENCES agent_knowledge_documents(tenant_id, document_id, index_version) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS agent_knowledge_chunks (
  tenant_id TEXT NOT NULL,
  chunk_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  index_version BIGINT NOT NULL DEFAULT 1 CHECK (index_version > 0),
  chunk_text TEXT NOT NULL,
  -- 中文业务可在摄取阶段把分词后的空格文本写入 search_text。
  search_text TEXT NOT NULL,
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
  source_uri TEXT NOT NULL,
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
  embedding VECTOR(1536) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, chunk_id)
);
CREATE INDEX IF NOT EXISTS agent_knowledge_chunks_document_idx
  ON agent_knowledge_chunks(tenant_id, document_id, index_version);
CREATE INDEX IF NOT EXISTS agent_knowledge_chunks_permissions_idx ON agent_knowledge_chunks USING GIN(permissions);
CREATE INDEX IF NOT EXISTS agent_knowledge_chunks_metadata_idx ON agent_knowledge_chunks USING GIN(metadata jsonb_path_ops);
CREATE INDEX IF NOT EXISTS agent_knowledge_chunks_search_vector_idx ON agent_knowledge_chunks USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS agent_knowledge_chunks_embedding_hnsw_idx
  ON agent_knowledge_chunks USING hnsw(embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);
COMMIT;
