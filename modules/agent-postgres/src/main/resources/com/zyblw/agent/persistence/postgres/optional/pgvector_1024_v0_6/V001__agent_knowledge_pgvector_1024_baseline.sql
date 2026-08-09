-- Fresh 1024-dimension RAG baseline.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE agent_knowledge_documents (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  document_id TEXT NOT NULL CHECK (length(btrim(document_id)) BETWEEN 1 AND 1000),
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  ingestion_id TEXT NOT NULL CHECK (length(btrim(ingestion_id)) BETWEEN 1 AND 500),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (cardinality(permissions) <= 256 AND array_position(permissions, NULL) IS NULL),
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB CHECK (jsonb_typeof(metadata) = 'object'),
  embedding_provider TEXT NOT NULL CHECK (length(btrim(embedding_provider)) BETWEEN 1 AND 200),
  embedding_model TEXT NOT NULL CHECK (length(btrim(embedding_model)) BETWEEN 1 AND 500),
  embedding_dimension INTEGER NOT NULL CHECK (embedding_dimension = 1024),
  embedding_max_batch_size INTEGER NOT NULL CHECK (embedding_max_batch_size BETWEEN 1 AND 10000),
  embedding_supports_dimensions BOOLEAN NOT NULL,
  indexing_strategy TEXT NOT NULL CHECK (length(btrim(indexing_strategy)) BETWEEN 1 AND 500),
  status TEXT NOT NULL CHECK (status IN ('building', 'ready', 'superseded', 'failed', 'retired')),
  active BOOLEAN NOT NULL DEFAULT FALSE,
  chunk_count INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
  failure_code TEXT CHECK (failure_code IS NULL OR length(btrim(failure_code)) BETWEEN 1 AND 160),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_id, index_version), UNIQUE (tenant_id, document_id, ingestion_id),
  CHECK (updated_at >= created_at), CHECK (NOT active OR status = 'ready'),
  CHECK ((status = 'failed' AND failure_code IS NOT NULL) OR (status <> 'failed' AND failure_code IS NULL))
);
CREATE UNIQUE INDEX agent_knowledge_documents_one_active_idx ON agent_knowledge_documents(tenant_id, document_id) WHERE active;
CREATE INDEX agent_knowledge_documents_recovery_idx ON agent_knowledge_documents(updated_at, tenant_id, document_id, index_version) WHERE status = 'building';
CREATE INDEX agent_knowledge_documents_retention_idx ON agent_knowledge_documents(updated_at, tenant_id, document_id, index_version) WHERE active = FALSE AND status IN ('superseded', 'failed', 'retired');

CREATE TABLE agent_knowledge_chunk_staging (
  tenant_id TEXT NOT NULL, document_id TEXT NOT NULL, index_version BIGINT NOT NULL CHECK (index_version > 0),
  chunk_id TEXT NOT NULL CHECK (length(btrim(chunk_id)) BETWEEN 1 AND 1200),
  chunk_text TEXT NOT NULL CHECK (length(btrim(chunk_text)) > 0), search_text TEXT NOT NULL CHECK (length(btrim(search_text)) > 0),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (cardinality(permissions) <= 256 AND array_position(permissions, NULL) IS NULL),
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB CHECK (jsonb_typeof(metadata) = 'object'), embedding public.vector(1024) NOT NULL,
  parent_id TEXT, lineage_ordinal INTEGER CHECK (lineage_ordinal >= 0), previous_chunk_id TEXT, next_chunk_id TEXT,
  heading_path TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[], page_numbers INTEGER[] NOT NULL DEFAULT ARRAY[]::INTEGER[],
  origins JSONB NOT NULL DEFAULT '[]'::JSONB CHECK (jsonb_typeof(origins) = 'array'), block_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_id, index_version, chunk_id),
  FOREIGN KEY (tenant_id, document_id, index_version) REFERENCES agent_knowledge_documents(tenant_id, document_id, index_version) ON DELETE CASCADE,
  CHECK (updated_at >= created_at)
);
CREATE UNIQUE INDEX agent_knowledge_chunk_staging_lineage_order_idx ON agent_knowledge_chunk_staging(tenant_id, document_id, index_version, lineage_ordinal) WHERE lineage_ordinal IS NOT NULL;

CREATE TABLE agent_knowledge_chunks (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000), document_id TEXT NOT NULL CHECK (length(btrim(document_id)) BETWEEN 1 AND 1000),
  chunk_id TEXT NOT NULL CHECK (length(btrim(chunk_id)) BETWEEN 1 AND 1200), index_version BIGINT NOT NULL CHECK (index_version > 0),
  chunk_text TEXT NOT NULL CHECK (length(btrim(chunk_text)) > 0), search_text TEXT NOT NULL CHECK (length(btrim(search_text)) > 0),
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (cardinality(permissions) <= 256 AND array_position(permissions, NULL) IS NULL),
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB CHECK (jsonb_typeof(metadata) = 'object'), embedding public.vector(1024) NOT NULL,
  parent_id TEXT, lineage_ordinal INTEGER CHECK (lineage_ordinal >= 0), previous_chunk_id TEXT, next_chunk_id TEXT,
  heading_path TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[], page_numbers INTEGER[] NOT NULL DEFAULT ARRAY[]::INTEGER[],
  origins JSONB NOT NULL DEFAULT '[]'::JSONB CHECK (jsonb_typeof(origins) = 'array'), block_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_id, chunk_id), CHECK (updated_at >= created_at)
);
CREATE INDEX agent_knowledge_chunks_document_version_idx ON agent_knowledge_chunks(tenant_id, document_id, index_version);
CREATE INDEX agent_knowledge_chunks_permissions_idx ON agent_knowledge_chunks USING GIN(permissions);
CREATE INDEX agent_knowledge_chunks_search_vector_idx ON agent_knowledge_chunks USING GIN(search_vector);
CREATE INDEX agent_knowledge_chunks_parent_order_idx ON agent_knowledge_chunks(tenant_id, document_id, parent_id, lineage_ordinal, chunk_id) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX agent_knowledge_chunks_lineage_order_idx ON agent_knowledge_chunks(tenant_id, document_id, lineage_ordinal) WHERE lineage_ordinal IS NOT NULL;
CREATE INDEX agent_knowledge_chunks_pages_idx ON agent_knowledge_chunks USING GIN(page_numbers) WHERE cardinality(page_numbers) > 0;
CREATE INDEX agent_knowledge_chunks_embedding_hnsw_idx ON agent_knowledge_chunks USING hnsw(embedding public.vector_cosine_ops) WITH (m = 16, ef_construction = 64);
