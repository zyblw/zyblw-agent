-- zyblw-agent next-minor 1024-dimension RAG greenfield baseline.
-- Unique knowledge V001: Space / Profile / census / chunks / audit / withdrawn.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE TABLE agent_knowledge_spaces (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  knowledge_space_id TEXT NOT NULL CHECK (length(btrim(knowledge_space_id)) BETWEEN 1 AND 200),
  active_profile_id TEXT CHECK (active_profile_id IS NULL OR length(btrim(active_profile_id)) BETWEEN 1 AND 200),
  revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, knowledge_space_id),
  CHECK (updated_at >= created_at)
);

CREATE TABLE agent_knowledge_profiles (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  knowledge_space_id TEXT NOT NULL CHECK (length(btrim(knowledge_space_id)) BETWEEN 1 AND 200),
  profile_id TEXT NOT NULL CHECK (length(btrim(profile_id)) BETWEEN 1 AND 200),
  profile_version BIGINT NOT NULL CHECK (profile_version > 0),
  status TEXT NOT NULL CHECK (status IN ('building', 'ready', 'active', 'superseded', 'failed', 'retired', 'cancelled')),
  embedding_provider TEXT NOT NULL CHECK (length(btrim(embedding_provider)) BETWEEN 1 AND 200),
  embedding_model TEXT NOT NULL CHECK (length(btrim(embedding_model)) BETWEEN 1 AND 500),
  embedding_dimension INTEGER NOT NULL CHECK (embedding_dimension = 1024),
  embedding_max_batch_size INTEGER NOT NULL CHECK (embedding_max_batch_size BETWEEN 1 AND 10000),
  embedding_supports_dimensions BOOLEAN NOT NULL,
  dense_distance TEXT NOT NULL DEFAULT 'cosine',
  sparse_provider TEXT,
  sparse_model TEXT,
  sparse_dimension INTEGER CHECK (sparse_dimension IS NULL OR sparse_dimension > 0),
  lexical_analyzer TEXT NOT NULL DEFAULT 'simple',
  lexical_strategy_id TEXT NOT NULL DEFAULT 'simple-zh',
  chunking_strategy_id TEXT NOT NULL,
  normalization_id TEXT NOT NULL DEFAULT 'display-retrieval-v1',
  normalization_version TEXT NOT NULL DEFAULT '1',
  metadata_schema_id TEXT NOT NULL DEFAULT 'generic',
  metadata_schema_version TEXT NOT NULL DEFAULT '1',
  fusion_strategy TEXT NOT NULL DEFAULT 'weighted-rrf',
  fusion_version TEXT NOT NULL DEFAULT '1',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ready_at TIMESTAMPTZ,
  activated_at TIMESTAMPTZ,
  publication_evaluation_id TEXT CHECK (publication_evaluation_id IS NULL OR length(btrim(publication_evaluation_id)) BETWEEN 1 AND 200),
  publication_census_sha256 CHAR(64) CHECK (publication_census_sha256 IS NULL OR publication_census_sha256 ~ '^[0-9a-f]{64}$'),
  publication_document_count INTEGER CHECK (publication_document_count IS NULL OR publication_document_count BETWEEN 1 AND 100000),
  failure_code TEXT CHECK (failure_code IS NULL OR length(btrim(failure_code)) BETWEEN 1 AND 160),
  PRIMARY KEY (tenant_id, knowledge_space_id, profile_id),
  UNIQUE (tenant_id, knowledge_space_id, profile_version),
  FOREIGN KEY (tenant_id, knowledge_space_id) REFERENCES agent_knowledge_spaces(tenant_id, knowledge_space_id) ON DELETE CASCADE
);

ALTER TABLE agent_knowledge_spaces
  ADD CONSTRAINT agent_knowledge_spaces_active_profile_fk
  FOREIGN KEY (tenant_id, knowledge_space_id, active_profile_id)
  REFERENCES agent_knowledge_profiles(tenant_id, knowledge_space_id, profile_id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE agent_knowledge_profile_documents (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  knowledge_space_id TEXT NOT NULL DEFAULT 'default' CHECK (length(btrim(knowledge_space_id)) BETWEEN 1 AND 200),
  profile_id TEXT NOT NULL DEFAULT 'default' CHECK (length(btrim(profile_id)) BETWEEN 1 AND 200),
  document_id TEXT NOT NULL CHECK (length(btrim(document_id)) BETWEEN 1 AND 1000),
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  document_revision_id TEXT NOT NULL DEFAULT '1' CHECK (length(btrim(document_revision_id)) BETWEEN 1 AND 200),
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
  PRIMARY KEY (tenant_id, document_id, index_version),
  UNIQUE (tenant_id, document_id, ingestion_id),
  CHECK (updated_at >= created_at),
  CHECK (NOT active OR status = 'ready'),
  CHECK ((status = 'failed' AND failure_code IS NOT NULL) OR (status <> 'failed' AND failure_code IS NULL))
);
CREATE UNIQUE INDEX agent_knowledge_profile_documents_one_active_idx
  ON agent_knowledge_profile_documents(tenant_id, knowledge_space_id, profile_id, document_id) WHERE active;
CREATE INDEX agent_knowledge_profile_documents_recovery_idx
  ON agent_knowledge_profile_documents(updated_at, tenant_id, document_id, index_version) WHERE status = 'building';
CREATE INDEX agent_knowledge_profile_documents_retention_idx
  ON agent_knowledge_profile_documents(updated_at, tenant_id, document_id, index_version)
  WHERE active = FALSE AND status IN ('superseded', 'failed', 'retired');

CREATE TABLE agent_knowledge_profile_chunk_staging (
  tenant_id TEXT NOT NULL,
  knowledge_space_id TEXT NOT NULL DEFAULT 'default',
  profile_id TEXT NOT NULL DEFAULT 'default',
  document_id TEXT NOT NULL,
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  chunk_id TEXT NOT NULL CHECK (length(btrim(chunk_id)) BETWEEN 1 AND 1200),
  chunk_text TEXT NOT NULL CHECK (length(btrim(chunk_text)) > 0),
  search_text TEXT NOT NULL CHECK (length(btrim(search_text)) > 0),
  dense_text TEXT,
  display_sha256 CHAR(64),
  dense_sha256 CHAR(64),
  lexical_sha256 CHAR(64),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (cardinality(permissions) <= 256 AND array_position(permissions, NULL) IS NULL),
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB CHECK (jsonb_typeof(metadata) = 'object'),
  embedding public.vector(1024) NOT NULL,
  sparse_embedding TEXT,
  parent_id TEXT, lineage_ordinal INTEGER CHECK (lineage_ordinal >= 0), previous_chunk_id TEXT, next_chunk_id TEXT,
  heading_path TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[], page_numbers INTEGER[] NOT NULL DEFAULT ARRAY[]::INTEGER[],
  origins JSONB NOT NULL DEFAULT '[]'::JSONB CHECK (jsonb_typeof(origins) = 'array'), block_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_id, index_version, chunk_id),
  FOREIGN KEY (tenant_id, document_id, index_version)
    REFERENCES agent_knowledge_profile_documents(tenant_id, document_id, index_version) ON DELETE CASCADE,
  CHECK (updated_at >= created_at)
);
CREATE UNIQUE INDEX agent_knowledge_profile_chunk_staging_lineage_order_idx
  ON agent_knowledge_profile_chunk_staging(tenant_id, document_id, index_version, lineage_ordinal)
  WHERE lineage_ordinal IS NOT NULL;

CREATE TABLE agent_knowledge_profile_chunks (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  knowledge_space_id TEXT NOT NULL DEFAULT 'default' CHECK (length(btrim(knowledge_space_id)) BETWEEN 1 AND 200),
  profile_id TEXT NOT NULL DEFAULT 'default' CHECK (length(btrim(profile_id)) BETWEEN 1 AND 200),
  document_id TEXT NOT NULL CHECK (length(btrim(document_id)) BETWEEN 1 AND 1000),
  document_revision_id TEXT NOT NULL DEFAULT '1',
  chunk_id TEXT NOT NULL CHECK (length(btrim(chunk_id)) BETWEEN 1 AND 1200),
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  chunk_text TEXT NOT NULL CHECK (length(btrim(chunk_text)) > 0),
  search_text TEXT NOT NULL CHECK (length(btrim(search_text)) > 0),
  dense_text TEXT,
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
  display_sha256 CHAR(64),
  dense_sha256 CHAR(64),
  lexical_sha256 CHAR(64),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (cardinality(permissions) <= 256 AND array_position(permissions, NULL) IS NULL),
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB CHECK (jsonb_typeof(metadata) = 'object'),
  embedding public.vector(1024) NOT NULL,
  sparse_embedding TEXT,
  parent_id TEXT, lineage_ordinal INTEGER CHECK (lineage_ordinal >= 0), previous_chunk_id TEXT, next_chunk_id TEXT,
  heading_path TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[], page_numbers INTEGER[] NOT NULL DEFAULT ARRAY[]::INTEGER[],
  origins JSONB NOT NULL DEFAULT '[]'::JSONB CHECK (jsonb_typeof(origins) = 'array'), block_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, knowledge_space_id, profile_id, document_id, chunk_id),
  CHECK (updated_at >= created_at)
);
CREATE INDEX agent_knowledge_profile_chunks_document_version_idx
  ON agent_knowledge_profile_chunks(tenant_id, document_id, index_version);
CREATE INDEX agent_knowledge_profile_chunks_permissions_idx
  ON agent_knowledge_profile_chunks USING GIN(permissions);
CREATE INDEX agent_knowledge_profile_chunks_search_vector_idx
  ON agent_knowledge_profile_chunks USING GIN(search_vector);
CREATE INDEX agent_knowledge_profile_chunks_parent_order_idx
  ON agent_knowledge_profile_chunks(tenant_id, document_id, parent_id, lineage_ordinal, chunk_id)
  WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX agent_knowledge_profile_chunks_lineage_order_idx
  ON agent_knowledge_profile_chunks(tenant_id, knowledge_space_id, profile_id, document_id, lineage_ordinal)
  WHERE lineage_ordinal IS NOT NULL;
CREATE INDEX agent_knowledge_profile_chunks_pages_idx
  ON agent_knowledge_profile_chunks USING GIN(page_numbers) WHERE cardinality(page_numbers) > 0;
CREATE INDEX agent_knowledge_profile_chunks_embedding_hnsw_idx
  ON agent_knowledge_profile_chunks USING hnsw(embedding public.vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX agent_knowledge_profile_chunks_metadata_idx
  ON agent_knowledge_profile_chunks USING GIN (metadata jsonb_path_ops);
CREATE INDEX agent_knowledge_profile_chunks_heading_path_idx
  ON agent_knowledge_profile_chunks USING GIN (heading_path);
CREATE INDEX agent_knowledge_profile_chunks_search_text_trgm_idx
  ON agent_knowledge_profile_chunks USING GIN (search_text public.gin_trgm_ops);
CREATE INDEX agent_knowledge_profile_chunks_profile_idx
  ON agent_knowledge_profile_chunks(tenant_id, knowledge_space_id, profile_id);
CREATE INDEX agent_knowledge_profile_chunk_staging_metadata_idx
  ON agent_knowledge_profile_chunk_staging USING GIN (metadata jsonb_path_ops);

CREATE TABLE agent_knowledge_profile_activation_audit (
  tenant_id TEXT NOT NULL,
  knowledge_space_id TEXT NOT NULL,
  old_profile_id TEXT,
  new_profile_id TEXT NOT NULL,
  expected_space_revision BIGINT NOT NULL,
  reason TEXT NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 160),
  evaluation_id TEXT CHECK (evaluation_id IS NULL OR length(btrim(evaluation_id)) BETWEEN 1 AND 200),
  evaluated_census_sha256 CHAR(64) CHECK (evaluated_census_sha256 IS NULL OR evaluated_census_sha256 ~ '^[0-9a-f]{64}$'),
  document_count INTEGER CHECK (document_count IS NULL OR document_count BETWEEN 1 AND 100000),
  activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX agent_knowledge_profile_activation_audit_idx
  ON agent_knowledge_profile_activation_audit(tenant_id, knowledge_space_id, activated_at DESC);

CREATE TABLE agent_knowledge_withdrawn (
  tenant_id TEXT NOT NULL,
  knowledge_space_id TEXT NOT NULL DEFAULT 'default',
  document_id TEXT NOT NULL,
  document_revision_id TEXT NOT NULL,
  withdrawn_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, knowledge_space_id, document_id, document_revision_id)
);
