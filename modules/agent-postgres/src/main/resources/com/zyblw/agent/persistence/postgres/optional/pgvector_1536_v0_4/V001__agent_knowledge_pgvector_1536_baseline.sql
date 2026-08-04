-- zyblw-agent 0.4 知识索引全新基线（1536 维）。
--
-- 设计边界：
-- 1. 本 location 固定管理 zyblw_agent_knowledge schema，并把独立 Flyway history 放在该 schema；
--    不与 public 中的核心控制面或 0.3 pgvector migration 混用。
-- 2. 这是 fresh-install 基线；表、索引不使用 IF NOT EXISTS，遇到同名脏结构必须失败，不能静默接受漂移。
-- 3. vector(1536) 是物理契约；更换维度必须使用另一套 location/history/table，不允许同列混存。
-- 4. 原始 PDF 字节与整份 Markdown 不写入每个向量行；这里只保存可检索 chunk、来源 URI 和可追溯谱系。
-- 5. tenant/permissions 是可信业务边界，模型和文档正文都无权生成或扩大它们。

-- 扩展通常由 DBA 预装；IF NOT EXISTS 允许托管 PostgreSQL 已经启用 vector。
-- 迁移结束后 AgentPostgresMigrations 会再次校验扩展、表、谱系列和 vector(1536) 类型。
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

-- 文档索引 manifest：记录一次确定性摄取的身份、Embedding 契约和发布状态，不保存正文。
CREATE TABLE agent_knowledge_documents (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  document_id TEXT NOT NULL CHECK (length(btrim(document_id)) BETWEEN 1 AND 1000),
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  ingestion_id TEXT NOT NULL CHECK (length(btrim(ingestion_id)) BETWEEN 1 AND 500),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (
    cardinality(permissions) <= 256 AND array_position(permissions, NULL) IS NULL
  ),
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB CHECK (jsonb_typeof(metadata) = 'object'),
  embedding_provider TEXT NOT NULL CHECK (length(btrim(embedding_provider)) BETWEEN 1 AND 200),
  embedding_model TEXT NOT NULL CHECK (length(btrim(embedding_model)) BETWEEN 1 AND 500),
  embedding_dimension INTEGER NOT NULL CHECK (embedding_dimension = 1536),
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

-- PostgreSQL 部分唯一索引是“每个租户文档只能有一个 active 版本”的最终并发仲裁者。
CREATE UNIQUE INDEX agent_knowledge_documents_one_active_idx
  ON agent_knowledge_documents(tenant_id, document_id)
  WHERE active;

-- 崩溃恢复按最旧 Building 版本有界扫描；status 已被部分谓词固定，不重复放入索引键。
CREATE INDEX agent_knowledge_documents_recovery_idx
  ON agent_knowledge_documents(updated_at, tenant_id, document_id, index_version)
  WHERE status = 'building';

-- retention 只允许领取非活动终态，避免清理 Building 或当前 Ready 版本。
CREATE INDEX agent_knowledge_documents_retention_idx
  ON agent_knowledge_documents(updated_at, tenant_id, document_id, index_version)
  WHERE active = FALSE AND status IN ('superseded', 'failed', 'retired');

-- 构建暂存表：Embedding 已在事务外生成，worker 可按同一复合主键幂等重放 batch。
-- 该表通过外键绑定 manifest；删除失败/过期 manifest 时自动清理其未发布 chunk。
CREATE TABLE agent_knowledge_chunk_staging (
  tenant_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  chunk_id TEXT NOT NULL CHECK (length(btrim(chunk_id)) BETWEEN 1 AND 1200),
  chunk_text TEXT NOT NULL CHECK (length(btrim(chunk_text)) > 0),
  search_text TEXT NOT NULL CHECK (length(btrim(search_text)) > 0),
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (
    cardinality(permissions) <= 256 AND array_position(permissions, NULL) IS NULL
  ),
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB CHECK (jsonb_typeof(metadata) = 'object'),
  embedding public.vector(1536) NOT NULL,
  parent_id TEXT CHECK (parent_id IS NULL OR length(btrim(parent_id)) BETWEEN 1 AND 1200),
  lineage_ordinal INTEGER CHECK (lineage_ordinal >= 0),
  previous_chunk_id TEXT CHECK (
    previous_chunk_id IS NULL OR length(btrim(previous_chunk_id)) BETWEEN 1 AND 1200
  ),
  next_chunk_id TEXT CHECK (next_chunk_id IS NULL OR length(btrim(next_chunk_id)) BETWEEN 1 AND 1200),
  heading_path TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (
    cardinality(heading_path) <= 128 AND array_position(heading_path, NULL) IS NULL
  ),
  page_numbers INTEGER[] NOT NULL DEFAULT ARRAY[]::INTEGER[] CHECK (
    cardinality(page_numbers) <= 10000 AND array_position(page_numbers, NULL) IS NULL
  ),
  origins JSONB NOT NULL DEFAULT '[]'::JSONB CHECK (jsonb_typeof(origins) = 'array'),
  block_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (
    cardinality(block_ids) <= 10000 AND array_position(block_ids, NULL) IS NULL
  ),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_id, index_version, chunk_id),
  FOREIGN KEY (tenant_id, document_id, index_version)
    REFERENCES agent_knowledge_documents(tenant_id, document_id, index_version)
    ON DELETE CASCADE,
  CHECK (updated_at >= created_at)
);

-- 同一文档版本的阅读顺序必须唯一；无结构纯文本允许 lineage_ordinal 为 NULL。
CREATE UNIQUE INDEX agent_knowledge_chunk_staging_lineage_order_idx
  ON agent_knowledge_chunk_staging(tenant_id, document_id, index_version, lineage_ordinal)
  WHERE lineage_ordinal IS NOT NULL;

-- 正式检索表是 active read model。复合主键允许不同文档合法复用局部 chunk ID，避免跨文档覆盖。
CREATE TABLE agent_knowledge_chunks (
  tenant_id TEXT NOT NULL CHECK (length(btrim(tenant_id)) BETWEEN 1 AND 1000),
  document_id TEXT NOT NULL CHECK (length(btrim(document_id)) BETWEEN 1 AND 1000),
  chunk_id TEXT NOT NULL CHECK (length(btrim(chunk_id)) BETWEEN 1 AND 1200),
  index_version BIGINT NOT NULL CHECK (index_version > 0),
  chunk_text TEXT NOT NULL CHECK (length(btrim(chunk_text)) > 0),
  -- 中文语料可在摄取阶段写入受控分词结果；未配置分词器时 Adapter 写原文。
  search_text TEXT NOT NULL CHECK (length(btrim(search_text)) > 0),
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
  source_uri TEXT NOT NULL CHECK (length(btrim(source_uri)) BETWEEN 1 AND 8192),
  permissions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (
    cardinality(permissions) <= 256 AND array_position(permissions, NULL) IS NULL
  ),
  metadata JSONB NOT NULL DEFAULT '{}'::JSONB CHECK (jsonb_typeof(metadata) = 'object'),
  embedding public.vector(1536) NOT NULL,
  parent_id TEXT CHECK (parent_id IS NULL OR length(btrim(parent_id)) BETWEEN 1 AND 1200),
  lineage_ordinal INTEGER CHECK (lineage_ordinal >= 0),
  previous_chunk_id TEXT CHECK (
    previous_chunk_id IS NULL OR length(btrim(previous_chunk_id)) BETWEEN 1 AND 1200
  ),
  next_chunk_id TEXT CHECK (next_chunk_id IS NULL OR length(btrim(next_chunk_id)) BETWEEN 1 AND 1200),
  heading_path TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (
    cardinality(heading_path) <= 128 AND array_position(heading_path, NULL) IS NULL
  ),
  page_numbers INTEGER[] NOT NULL DEFAULT ARRAY[]::INTEGER[] CHECK (
    cardinality(page_numbers) <= 10000 AND array_position(page_numbers, NULL) IS NULL
  ),
  -- bbox/origin 只整体回读，不承担过滤；JSONB 比拆成稀疏关系表更符合当前访问模式。
  origins JSONB NOT NULL DEFAULT '[]'::JSONB CHECK (jsonb_typeof(origins) = 'array'),
  block_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[] CHECK (
    cardinality(block_ids) <= 10000 AND array_position(block_ids, NULL) IS NULL
  ),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_id, chunk_id),
  CHECK (updated_at >= created_at)
);

-- 版本、删除和文档级读取路径；主键已覆盖 tenant/document/chunk 的精确访问。
CREATE INDEX agent_knowledge_chunks_document_version_idx
  ON agent_knowledge_chunks(tenant_id, document_id, index_version);

-- `<@ caller_permissions` 的授权过滤索引。tenant_id 仍由查询中的等值谓词强制限定。
CREATE INDEX agent_knowledge_chunks_permissions_idx
  ON agent_knowledge_chunks USING GIN(permissions);

-- PostgreSQL 全文候选；simple 配置不会错误套用英文词干，中文分词由 search_text 输入承担。
CREATE INDEX agent_knowledge_chunks_search_vector_idx
  ON agent_knowledge_chunks USING GIN(search_vector);

-- 同父级扩展按文档阅读顺序读取；部分索引不保存没有结构谱系的纯文本块。
CREATE INDEX agent_knowledge_chunks_parent_order_idx
  ON agent_knowledge_chunks(tenant_id, document_id, parent_id, lineage_ordinal, chunk_id)
  WHERE parent_id IS NOT NULL;

CREATE UNIQUE INDEX agent_knowledge_chunks_lineage_order_idx
  ON agent_knowledge_chunks(tenant_id, document_id, lineage_ordinal)
  WHERE lineage_ordinal IS NOT NULL;

-- 页码数组用于引用定位和文档阅读器回放；bbox 保持在 origins 中，不建立高写放大的通用 JSONB 索引。
CREATE INDEX agent_knowledge_chunks_pages_idx
  ON agent_knowledge_chunks USING GIN(page_numbers)
  WHERE cardinality(page_numbers) > 0;

-- HNSW 用 cosine 距离服务查询密集知识库。m/ef_construction 使用 pgvector 默认起点，必须以真实 corpus 调优。
CREATE INDEX agent_knowledge_chunks_embedding_hnsw_idx
  ON agent_knowledge_chunks USING hnsw(embedding public.vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

COMMENT ON TABLE agent_knowledge_documents IS
  '知识文档索引 manifest：固定摄取幂等身份、Embedding 契约、发布状态和 active 版本，不保存原文正文';
COMMENT ON TABLE agent_knowledge_chunk_staging IS
  'Building 版本的幂等暂存块；只有 activate 短事务可把完整版本发布到正式检索表';
COMMENT ON TABLE agent_knowledge_chunks IS
  '当前 active 知识块 read model；向量、全文、ACL 与原文谱系在同一行原子可见';

COMMENT ON COLUMN agent_knowledge_documents.ingestion_id IS
  '调用方稳定幂等键；相同键必须绑定完全相同的内容 hash、模型和切分策略';
COMMENT ON COLUMN agent_knowledge_documents.index_version IS
  '租户文档内单调递增的索引快照版本；查询只读取 active 版本发布出的 read model';
COMMENT ON COLUMN agent_knowledge_documents.source_uri IS
  '宿主提供的稳定来源标识；可以指向受控对象存储，但不应包含临时签名密钥';
COMMENT ON COLUMN agent_knowledge_documents.content_hash IS
  '原始规范化文档内容的 SHA-256，用于拒绝同 ingestion_id 内容漂移';
COMMENT ON COLUMN agent_knowledge_documents.permissions IS
  '整份文档摄取时的可信权限标签；每个 chunk 必须继承或收窄，不能由正文或模型扩大';
COMMENT ON COLUMN agent_knowledge_documents.metadata IS
  '低敏、受控的文档属性 JSON 对象；原始 PDF、完整 Markdown 和模型响应不存放于此';
COMMENT ON COLUMN agent_knowledge_documents.embedding_dimension IS
  '本物理基线固定为 1536；更换维度需要新 location/history/table 契约';
COMMENT ON COLUMN agent_knowledge_documents.indexing_strategy IS
  '包含切分器及关键参数版本的稳定策略 ID；变化时应创建新索引版本';
COMMENT ON COLUMN agent_knowledge_documents.status IS
  'building/ready/superseded/failed/retired 生命周期；只有 ready 可成为 active';
COMMENT ON COLUMN agent_knowledge_documents.active IS
  '是否为该 tenant/document 当前可查询版本；部分唯一索引保证最多一个';
COMMENT ON COLUMN agent_knowledge_documents.chunk_count IS
  'activate 事务核验并记录的完整块数；防止部分 staging 被错误发布';
COMMENT ON COLUMN agent_knowledge_documents.failure_code IS
  '摄取失败的稳定低敏分类；不保存 Provider、OCR 或数据库原始错误正文';

COMMENT ON COLUMN agent_knowledge_chunk_staging.chunk_id IS
  '文档内部稳定 chunk ID；与 tenant/document/version 共同构成幂等暂存身份';
COMMENT ON COLUMN agent_knowledge_chunk_staging.chunk_text IS
  '用于回答和引用的原文片段；必须来自受控 Loader/Chunker，不是模型补写内容';
COMMENT ON COLUMN agent_knowledge_chunk_staging.search_text IS
  '用于 PostgreSQL FTS 的原文或确定性分词文本；与展示正文分离';
COMMENT ON COLUMN agent_knowledge_chunk_staging.embedding IS
  'chunk_text 对应的 1536 维向量；模型身份与切分策略记录在 manifest';
COMMENT ON COLUMN agent_knowledge_chunk_staging.lineage_ordinal IS
  '文档版本内从零开始的稳定阅读顺序；有值时由唯一索引防止顺序冲突';
COMMENT ON COLUMN agent_knowledge_chunk_staging.origins IS
  '有序 page/bbox/block 来源数组；activate 时原样复制到 active read model';

COMMENT ON COLUMN agent_knowledge_chunks.document_id IS
  '租户内稳定原始文档 ID；chunk_id 只要求在同一文档内唯一';
COMMENT ON COLUMN agent_knowledge_chunks.chunk_id IS
  '文档内部稳定 chunk ID；必须与 document_id 组成引用、关联和去重身份';
COMMENT ON COLUMN agent_knowledge_chunks.index_version IS
  '生成本 active chunk 的 manifest 版本，用于引用审计和重建判断';
COMMENT ON COLUMN agent_knowledge_chunks.chunk_text IS
  '回答上下文和引用 excerpt 的权威原文片段；不得替换为摘要向量文本';
COMMENT ON COLUMN agent_knowledge_chunks.search_text IS
  '用于 FTS 的受控文本；可为中文分词结果，绝不能由模型提升权限或写入指令';
COMMENT ON COLUMN agent_knowledge_chunks.source_uri IS
  '引用回到原始文档/Markdown/对象存储的稳定 URI；访问仍须由业务层鉴权';
COMMENT ON COLUMN agent_knowledge_chunks.permissions IS
  '知识块所需权限标签；查询使用 permissions <@ caller_permissions，空数组表示租户内公开';
COMMENT ON COLUMN agent_knowledge_chunks.parent_id IS
  '文档内部父结构 ID，例如章节；必须和 document_id 组合使用，禁止跨文档扩展';
COMMENT ON COLUMN agent_knowledge_chunks.lineage_ordinal IS
  '文档内稳定阅读顺序，用于相邻块和同父章节扩展；纯文本无结构时可为空';
COMMENT ON COLUMN agent_knowledge_chunks.previous_chunk_id IS
  '同一 document_id 内的前一块 ID；检索扩展必须重新执行租户和权限过滤';
COMMENT ON COLUMN agent_knowledge_chunks.next_chunk_id IS
  '同一 document_id 内的后一块 ID；检索扩展必须重新执行租户和权限过滤';
COMMENT ON COLUMN agent_knowledge_chunks.heading_path IS
  '从文档根到 chunk 的标题路径，保持结构化数组，不再从 metadata 文本反解析';
COMMENT ON COLUMN agent_knowledge_chunks.page_numbers IS
  '去重后的 1-based 原文页码集合，用于引用展示和页面回放';
COMMENT ON COLUMN agent_knowledge_chunks.origins IS
  '有序 page/bbox/block 来源投影；用于引用高亮，不保存 Docling 原始响应';
COMMENT ON COLUMN agent_knowledge_chunks.block_ids IS
  'Loader 生成的稳定源 block ID 列表，用于从 chunk 追溯到版面解析结果';
COMMENT ON COLUMN agent_knowledge_chunks.embedding IS
  '固定 1536 维向量；Provider/model/strategy 身份记录在对应文档 manifest';
