-- zyblw-agent 0.9 1024-dimension RAG greenfield baseline.
-- Unique knowledge V001: Space / Profile(build spec) / document lineage / staging / chunks / audit / withdrawn.
--
-- Identity rules enforced here rather than in application code:
--   * every document key is (tenant, space, document); versions are numbered inside that key;
--   * a document build must carry the exact build-spec digest of its profile (composite FK);
--   * ready builds carry the chunk-set digest that activation verified;
--   * no identity column has a DEFAULT, so a caller that forgets space/profile fails loudly.
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
  tenant_id TEXT NOT NULL,
  knowledge_space_id TEXT NOT NULL,
  profile_id TEXT NOT NULL CHECK (length(btrim(profile_id)) BETWEEN 1 AND 200),
  profile_version BIGINT NOT NULL CHECK (profile_version > 0),
  status TEXT NOT NULL CHECK (status IN ('building', 'active', 'superseded', 'failed', 'retired', 'cancelled')),
  build_spec JSONB NOT NULL CHECK (jsonb_typeof(build_spec) = 'object'),
  build_spec_sha256 CHAR(64) NOT NULL CHECK (build_spec_sha256 ~ '^[0-9a-f]{64}$'),
  embedding_provider TEXT GENERATED ALWAYS AS (build_spec ->> 'embeddingProvider') STORED
    CHECK (length(btrim(embedding_provider)) BETWEEN 1 AND 200),
  embedding_model TEXT GENERATED ALWAYS AS (build_spec ->> 'embeddingModel') STORED
    CHECK (length(btrim(embedding_model)) BETWEEN 1 AND 500),
  embedding_dimension INTEGER GENERATED ALWAYS AS ((build_spec ->> 'embeddingDimension')::INTEGER) STORED
    CHECK (embedding_dimension = 1024),
  indexing_strategy TEXT GENERATED ALWAYS AS (build_spec ->> 'indexingStrategy') STORED
    CHECK (length(btrim(indexing_strategy)) BETWEEN 1 AND 500),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  activated_at TIMESTAMPTZ,
  publication_evaluation_id TEXT CHECK (publication_evaluation_id IS NULL OR length(btrim(publication_evaluation_id)) BETWEEN 1 AND 200),
  publication_census_sha256 CHAR(64) CHECK (publication_census_sha256 IS NULL OR publication_census_sha256 ~ '^[0-9a-f]{64}$'),
  publication_document_count INTEGER CHECK (publication_document_count IS NULL OR publication_document_count BETWEEN 1 AND 100000),
  failure_code TEXT CHECK (failure_code IS NULL OR length(btrim(failure_code)) BETWEEN 1 AND 160),
  PRIMARY KEY (tenant_id, knowledge_space_id, profile_id),
  UNIQUE (tenant_id, knowledge_space_id, profile_version),
  UNIQUE (tenant_id, knowledge_space_id, profile_id, build_spec_sha256),
  FOREIGN KEY (tenant_id, knowledge_space_id) REFERENCES agent_knowledge_spaces(tenant_id, knowledge_space_id) ON DELETE CASCADE,
  CHECK (status <> 'active' OR activated_at IS NOT NULL),
  CHECK ((status = 'failed') = (failure_code IS NOT NULL))
);

ALTER TABLE agent_knowledge_spaces
  ADD CONSTRAINT agent_knowledge_spaces_active_profile_fk
  FOREIGN KEY (tenant_id, knowledge_space_id, active_profile_id)
  REFERENCES agent_knowledge_profiles(tenant_id, knowledge_space_id, profile_id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE agent_knowledge_profile_documents (
  tenant_id TEXT NOT NULL,
  knowledge_space_id TEXT NOT NULL,
  profile_id TEXT NOT NULL,
  document_id TEXT NOT NULL CHECK (length(btrim(document_id)) BETWEEN 1 AND 1000),
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  ingestion_id TEXT NOT NULL CHECK (length(btrim(ingestion_id)) BETWEEN 1 AND 200),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  source_id TEXT NOT NULL CHECK (length(btrim(source_id)) BETWEEN 1 AND 1000),
  source_revision_id TEXT NOT NULL CHECK (length(btrim(source_revision_id)) BETWEEN 1 AND 200),
  source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
  source_media_type TEXT NOT NULL CHECK (length(btrim(source_media_type)) BETWEEN 1 AND 200),
  parser_id TEXT NOT NULL CHECK (length(btrim(parser_id)) BETWEEN 1 AND 200),
  artifact_sha256 CHAR(64) NOT NULL CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
  structure_sha256 CHAR(64) NOT NULL CHECK (structure_sha256 ~ '^[0-9a-f]{64}$'),
  text_sha256 CHAR(64) NOT NULL CHECK (text_sha256 ~ '^[0-9a-f]{64}$'),
  build_spec_sha256 CHAR(64) NOT NULL,
  chunk_set_sha256 CHAR(64) CHECK (chunk_set_sha256 IS NULL OR chunk_set_sha256 ~ '^[0-9a-f]{64}$'),
  permissions TEXT[] NOT NULL CHECK (cardinality(permissions) BETWEEN 1 AND 256 AND array_position(permissions, NULL) IS NULL),
  metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
  status TEXT NOT NULL CHECK (status IN ('building', 'ready', 'superseded', 'failed', 'retired')),
  active BOOLEAN NOT NULL,
  chunk_count INTEGER NOT NULL CHECK (chunk_count >= 0),
  failure_code TEXT CHECK (failure_code IS NULL OR length(btrim(failure_code)) BETWEEN 1 AND 160),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, knowledge_space_id, document_id, index_version),
  UNIQUE (tenant_id, knowledge_space_id, document_id, ingestion_id),
  CONSTRAINT agent_knowledge_profile_documents_identity_key
    UNIQUE (tenant_id, knowledge_space_id, profile_id, document_id, index_version),
  CONSTRAINT agent_knowledge_profile_documents_build_spec_fk
    FOREIGN KEY (tenant_id, knowledge_space_id, profile_id, build_spec_sha256)
    REFERENCES agent_knowledge_profiles(tenant_id, knowledge_space_id, profile_id, build_spec_sha256)
    ON DELETE CASCADE,
  CHECK (updated_at >= created_at),
  CHECK (NOT active OR status = 'ready'),
  CHECK ((status = 'failed') = (failure_code IS NOT NULL)),
  CHECK (status NOT IN ('ready', 'superseded') OR (chunk_set_sha256 IS NOT NULL AND chunk_count > 0)),
  CHECK (status <> 'building' OR (chunk_set_sha256 IS NULL AND chunk_count = 0))
);
CREATE UNIQUE INDEX agent_knowledge_profile_documents_one_active_idx
  ON agent_knowledge_profile_documents(tenant_id, knowledge_space_id, profile_id, document_id) WHERE active;
CREATE INDEX agent_knowledge_profile_documents_recovery_idx
  ON agent_knowledge_profile_documents(updated_at, tenant_id, knowledge_space_id, document_id, index_version)
  WHERE status = 'building';
CREATE INDEX agent_knowledge_profile_documents_retention_idx
  ON agent_knowledge_profile_documents(updated_at, tenant_id, knowledge_space_id, document_id, index_version)
  WHERE active = FALSE AND status IN ('superseded', 'failed', 'retired');
CREATE INDEX agent_knowledge_profile_documents_directory_idx
  ON agent_knowledge_profile_documents(
    tenant_id, updated_at DESC, knowledge_space_id COLLATE "C" DESC, document_id COLLATE "C" DESC, index_version DESC
  );

CREATE TABLE agent_knowledge_profile_chunk_staging (
  tenant_id TEXT NOT NULL,
  knowledge_space_id TEXT NOT NULL,
  profile_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  chunk_id TEXT NOT NULL CHECK (length(btrim(chunk_id)) BETWEEN 1 AND 1200 AND chunk_id !~ '[\t\n\r]'),
  chunk_text TEXT NOT NULL CHECK (length(btrim(chunk_text)) > 0),
  search_text TEXT NOT NULL CHECK (length(btrim(search_text)) > 0),
  dense_text TEXT,
  display_sha256 CHAR(64) NOT NULL CHECK (display_sha256 ~ '^[0-9a-f]{64}$'),
  dense_sha256 CHAR(64) NOT NULL CHECK (dense_sha256 ~ '^[0-9a-f]{64}$'),
  lexical_sha256 CHAR(64) NOT NULL CHECK (lexical_sha256 ~ '^[0-9a-f]{64}$'),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  permissions TEXT[] NOT NULL CHECK (cardinality(permissions) BETWEEN 1 AND 256 AND array_position(permissions, NULL) IS NULL),
  metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
  embedding public.vector(1024) NOT NULL,
  sparse_embedding TEXT,
  parent_id TEXT, lineage_ordinal INTEGER CHECK (lineage_ordinal >= 0), previous_chunk_id TEXT, next_chunk_id TEXT,
  heading_path TEXT[] NOT NULL, page_numbers INTEGER[] NOT NULL,
  origins JSONB NOT NULL CHECK (jsonb_typeof(origins) = 'array'), block_ids TEXT[] NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, knowledge_space_id, document_id, index_version, chunk_id),
  FOREIGN KEY (tenant_id, knowledge_space_id, profile_id, document_id, index_version)
    REFERENCES agent_knowledge_profile_documents(tenant_id, knowledge_space_id, profile_id, document_id, index_version)
    ON DELETE CASCADE,
  CHECK (updated_at >= created_at)
);
CREATE UNIQUE INDEX agent_knowledge_profile_chunk_staging_lineage_order_idx
  ON agent_knowledge_profile_chunk_staging(tenant_id, knowledge_space_id, document_id, index_version, lineage_ordinal)
  WHERE lineage_ordinal IS NOT NULL;

CREATE TABLE agent_knowledge_profile_chunks (
  tenant_id TEXT NOT NULL,
  knowledge_space_id TEXT NOT NULL,
  profile_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  source_revision_id TEXT NOT NULL CHECK (length(btrim(source_revision_id)) BETWEEN 1 AND 200),
  chunk_id TEXT NOT NULL CHECK (length(btrim(chunk_id)) BETWEEN 1 AND 1200 AND chunk_id !~ '[\t\n\r]'),
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  chunk_text TEXT NOT NULL CHECK (length(btrim(chunk_text)) > 0),
  search_text TEXT NOT NULL CHECK (length(btrim(search_text)) > 0),
  dense_text TEXT,
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
  display_sha256 CHAR(64) NOT NULL CHECK (display_sha256 ~ '^[0-9a-f]{64}$'),
  dense_sha256 CHAR(64) NOT NULL CHECK (dense_sha256 ~ '^[0-9a-f]{64}$'),
  lexical_sha256 CHAR(64) NOT NULL CHECK (lexical_sha256 ~ '^[0-9a-f]{64}$'),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  permissions TEXT[] NOT NULL CHECK (cardinality(permissions) BETWEEN 1 AND 256 AND array_position(permissions, NULL) IS NULL),
  metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
  embedding public.vector(1024) NOT NULL,
  sparse_embedding TEXT,
  parent_id TEXT, lineage_ordinal INTEGER CHECK (lineage_ordinal >= 0), previous_chunk_id TEXT, next_chunk_id TEXT,
  heading_path TEXT[] NOT NULL, page_numbers INTEGER[] NOT NULL,
  origins JSONB NOT NULL CHECK (jsonb_typeof(origins) = 'array'), block_ids TEXT[] NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, knowledge_space_id, profile_id, document_id, chunk_id),
  FOREIGN KEY (tenant_id, knowledge_space_id, profile_id, document_id, index_version)
    REFERENCES agent_knowledge_profile_documents(tenant_id, knowledge_space_id, profile_id, document_id, index_version)
    ON DELETE CASCADE,
  CHECK (updated_at >= created_at)
);
-- ACL is applied as a row filter (permissions <@ caller). A GIN index on permissions is deliberately absent:
-- broad caller permissions make the planner pick a full bitmap scan instead of the HNSW order-by.
CREATE INDEX agent_knowledge_profile_chunks_search_vector_idx
  ON agent_knowledge_profile_chunks USING GIN(search_vector);
CREATE INDEX agent_knowledge_profile_chunks_parent_order_idx
  ON agent_knowledge_profile_chunks(tenant_id, knowledge_space_id, profile_id, document_id, parent_id, lineage_ordinal, chunk_id)
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

CREATE TABLE agent_knowledge_profile_activation_audit (
  audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  knowledge_space_id TEXT NOT NULL CHECK (length(btrim(knowledge_space_id)) BETWEEN 1 AND 200),
  old_profile_id TEXT,
  new_profile_id TEXT NOT NULL CHECK (length(btrim(new_profile_id)) BETWEEN 1 AND 200),
  expected_space_revision BIGINT NOT NULL CHECK (expected_space_revision >= 0),
  reason TEXT NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 160),
  evaluation_id TEXT CHECK (evaluation_id IS NULL OR length(btrim(evaluation_id)) BETWEEN 1 AND 200),
  evaluated_census_sha256 CHAR(64) CHECK (evaluated_census_sha256 IS NULL OR evaluated_census_sha256 ~ '^[0-9a-f]{64}$'),
  document_count INTEGER CHECK (document_count IS NULL OR document_count BETWEEN 1 AND 100000),
  activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX agent_knowledge_profile_activation_audit_idx
  ON agent_knowledge_profile_activation_audit(tenant_id, knowledge_space_id, activated_at DESC);

CREATE TABLE agent_knowledge_withdrawn (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  knowledge_space_id TEXT NOT NULL CHECK (length(btrim(knowledge_space_id)) BETWEEN 1 AND 200),
  document_id TEXT NOT NULL CHECK (length(btrim(document_id)) BETWEEN 1 AND 1000),
  source_revision_id TEXT NOT NULL CHECK (length(btrim(source_revision_id)) BETWEEN 1 AND 200),
  withdrawn_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, knowledge_space_id, document_id, source_revision_id)
);
