-- =============================================================================
-- zyblw-agent 1024 维生产 RAG 知识索引层（zyblw_agent_knowledge）中文数据字典（可重复迁移）
-- =============================================================================
-- 本文件为幂等 repeatable migration，集中维护 zyblw_agent_knowledge 全部 8 张表及所有字段的中文说明。
-- 采用按表精确分派（WHEN column_record.table_name = '...'），确保同名字段（如 status、index_version、
-- build_spec_sha256、chunk_set_sha256 等）在不同知识索引表中的状态机枚举与职责边界清晰可辨。

DO $comments$
DECLARE
  target_schema TEXT := current_schema();
  table_names TEXT[] := ARRAY[
    'agent_knowledge_spaces',
    'agent_knowledge_profiles',
    'agent_knowledge_profile_documents',
    'agent_knowledge_profile_chunk_staging',
    'agent_knowledge_profile_chunks',
    'agent_knowledge_profile_activation_audit',
    'agent_knowledge_withdrawn',
    'agent_knowledge_structures'
  ];
  table_name_item TEXT;
  table_record RECORD;
  column_record RECORD;
  comment_text TEXT;
BEGIN
  EXECUTE format(
    'COMMENT ON SCHEMA %I IS %L',
    target_schema,
    'zyblw-agent 管理的 1024 维可重建混合知识索引 Schema：以 Space/Profile 为检索与蓝绿换模身份，支持全文（tsvector/trgm）与稠密向量（HNSW 1024）召回，业务原件仍由宿主应用（zyblw_app）拥有。'
  );

  FOREACH table_name_item IN ARRAY table_names LOOP
    IF to_regclass(format('%I.%I', target_schema, table_name_item)) IS NULL THEN
      RAISE EXCEPTION 'zyblw-agent knowledge table %.% does not exist when applying Chinese comments',
        target_schema, table_name_item;
    END IF;
  END LOOP;

  FOR table_record IN
    SELECT c.relname AS table_name
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = target_schema
      AND c.relkind = 'r'
      AND c.relname NOT LIKE 'flyway_%'
    ORDER BY c.relname
  LOOP
    comment_text := CASE table_record.table_name
      WHEN 'agent_knowledge_spaces' THEN '知识空间（Knowledge Space）与 CAS 活动 Profile 指针表：维护租户内知识空间边界、乐观并发版本号及当前对外服务查询的 active_profile_id。'
      WHEN 'agent_knowledge_profiles' THEN '知识索引构建配置画像（Index Profile）表：持久化完整不可变构建规格（Embedding 模型/1024 维度/切分器/词法策略）及其 SHA-256 摘要，支撑蓝绿无停机换模重建。'
      WHEN 'agent_knowledge_profile_documents' THEN 'Profile 级知识文档构建台账表：按租户、空间、Profile、文档与索引版本记录来源谱系（原件修订/解析产物/结构/正文摘要）、块集合摘要、ACL 权限标签与发布状态。'
      WHEN 'agent_knowledge_profile_chunk_staging' THEN '知识文档构建期切片暂存（Staging）表：承接 building 状态文档的幂等切片与 1024 维向量写入，待 activate 短事务校验完整后原子发布至正式切片表。'
      WHEN 'agent_knowledge_profile_chunks' THEN '在线可检索知识切片（Chunk）主表：存储已激活文档版本的展示原文、全文检索向量（tsvector + pg_trgm）、1024 维稠密向量（HNSW）、ACL 权限标签与页码/坐标/章节谱系。'
      WHEN 'agent_knowledge_profile_activation_audit' THEN '知识空间活动 Profile 切换只追加审计表：记录每次通过 CAS 原子切换或回滚 active_profile_id 时的前后 Profile、期望版本号、切换原因及评测背书快照。'
      WHEN 'agent_knowledge_withdrawn' THEN '知识文档修订版本撤回墓碑（Tombstone）表：按租户、空间、文档与源修订版本（source_revision_id）阻断已下架/违规修订被重新入库或召回。'
      WHEN 'agent_knowledge_structures' THEN '绑定精确知识文档版本的不可变章节树结构产物表：保存章节层级与 Block/Chunk 映射快照，支撑树状引导检索（Tree-Guided RAG）。'
      ELSE NULL
    END;

    IF comment_text IS NULL THEN
      RAISE EXCEPTION 'zyblw-agent knowledge table %.% is missing a Chinese COMMENT mapping',
        target_schema, table_record.table_name;
    END IF;

    EXECUTE format('COMMENT ON TABLE %I.%I IS %L', target_schema, table_record.table_name, comment_text);
  END LOOP;

  FOR column_record IN
    SELECT c.relname AS table_name, a.attname AS column_name
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = target_schema
      AND c.relkind = 'r'
      AND c.relname NOT LIKE 'flyway_%'
      AND a.attnum > 0
      AND NOT a.attisdropped
    ORDER BY c.relname, a.attnum
  LOOP
    comment_text := CASE
      -- =======================================================================
      -- 1. agent_knowledge_spaces：知识空间与 CAS 活动 Profile 指针表（6 列）
      -- =======================================================================
      WHEN column_record.table_name = 'agent_knowledge_spaces' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '宿主注入的租户隔离标识（1–1000 字符，与 knowledge_space_id 组成复合主键）。'
        WHEN 'knowledge_space_id' THEN '租户内知识空间唯一标识（1–200 字符，无默认值，防止跨空间混用语料）。'
        WHEN 'active_profile_id' THEN '当前对外提供在线检索的活动 Index Profile 标识（延迟外键关联 agent_knowledge_profiles；尚未首次激活时为 NULL）。'
        WHEN 'revision' THEN '知识空间指针 CAS 乐观并发版本号（从 0 开始单调递增，切换 active_profile_id 时必须校验 expected_space_revision）。'
        WHEN 'created_at' THEN '知识空间首次注册创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '知识空间指针或状态最后更新时间（TIMESTAMPTZ，必须 >= created_at）。'
        ELSE NULL
      END

      -- =======================================================================
      -- 2. agent_knowledge_profiles：知识索引构建配置画像表（17 列）
      -- =======================================================================
      WHEN column_record.table_name = 'agent_knowledge_profiles' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '所属租户隔离标识（与 knowledge_space_id、profile_id 组成复合主键，外键级联关联知识空间）。'
        WHEN 'knowledge_space_id' THEN '所属知识空间标识。'
        WHEN 'profile_id' THEN '索引配置画像（Index Profile）唯一标识（1–200 字符，通常由构建规格摘要派生）。'
        WHEN 'profile_version' THEN '同一租户与知识空间内单调递增的 Profile 整数序号（正整数，同空间内唯一）。'
        WHEN 'status' THEN 'Profile 生命周期状态：building（后台构建预热中）、active（当前线上激活服务中）、superseded（已被新 Profile 取代，仅接受显式补齐）、failed（构建失败封存）、retired（已退役封存）、cancelled（已取消封存）。'
        WHEN 'build_spec' THEN '完整不可变索引构建规格（JSONB 对象，含 embeddingProvider、embeddingModel、embeddingDimension、indexingStrategy、分词与切分器配置）。'
        WHEN 'build_spec_sha256' THEN 'build_spec 规范化字节流的 SHA-256 摘要（64 位小写十六进制，供下属文档构建通过复合外键强制对齐构建规格）。'
        WHEN 'embedding_provider' THEN '从 build_spec->>''embeddingProvider'' 自动提取的稠密向量供应商生成列（STORED，1–200 字符）。'
        WHEN 'embedding_model' THEN '从 build_spec->>''embeddingModel'' 自动提取的稠密向量模型名称生成列（STORED，1–500 字符）。'
        WHEN 'embedding_dimension' THEN '从 build_spec->>''embeddingDimension'' 自动提取的向量维度生成列（STORED，本物理基线强制 CHECK = 1024）。'
        WHEN 'indexing_strategy' THEN '从 build_spec->>''indexingStrategy'' 自动提取的切分与词法索引策略标识生成列（STORED，1–500 字符）。'
        WHEN 'created_at' THEN 'Profile 注册创建时间（TIMESTAMPTZ）。'
        WHEN 'activated_at' THEN 'Profile 最近一次切换为 active 状态的时间戳（status = ''active'' 时必须非空）。'
        WHEN 'publication_evaluation_id' THEN '批准或评测发布该 Profile 的可信评测运行标识（可选，1–200 字符）。'
        WHEN 'publication_census_sha256' THEN '评测发布时核验的 Profile 完整文档清单 SHA-256 摘要（可选，64 位十六进制）。'
        WHEN 'publication_document_count' THEN '评测发布时核验的 Profile 文档总数量（可选，1–100000 之间）。'
        WHEN 'failure_code' THEN '当 status = ''failed'' 时的稳定低敏失败原因分类码（非 failed 状态强制为 NULL）。'
        ELSE NULL
      END

      -- =======================================================================
      -- 3. agent_knowledge_profile_documents：Profile 级知识文档构建台账表（25 列）
      -- =======================================================================
      WHEN column_record.table_name = 'agent_knowledge_profile_documents' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '所属租户隔离标识（与 knowledge_space_id、document_id、index_version 组成复合主键）。'
        WHEN 'knowledge_space_id' THEN '所属知识空间标识。'
        WHEN 'profile_id' THEN '所属 Index Profile 标识（与 tenant_id、knowledge_space_id、build_spec_sha256 组成复合外键关联 agent_knowledge_profiles）。'
        WHEN 'document_id' THEN '知识空间内稳定的业务文档逻辑标识（1–1000 字符，跨 index_version 保持不变）。'
        WHEN 'index_version' THEN '在 (tenant_id, knowledge_space_id, document_id) 内单调递增的文档索引构建版本号（正整数）。'
        WHEN 'ingestion_id' THEN '单次文档入库构建的幂等标识（1–200 字符，同文档内唯一，由调用方提供或由谱系与构建规格派生）。'
        WHEN 'source_uri' THEN '受控方案批准的源文档稳定定位符（1–8192 字符，严禁包含临时签名或密钥）。'
        WHEN 'source_id' THEN '租户内跨知识空间稳定的上游原件业务主标识（1–1000 字符，例如书籍或文章 resource_id）。'
        WHEN 'source_revision_id' THEN '上游原件的不可变业务修订版本标识（1–200 字符，撤回墓碑与蓝绿语料对齐均以此为准）。'
        WHEN 'source_sha256' THEN '上游原件原始字节流的 SHA-256 摘要（64 位小写十六进制）。'
        WHEN 'source_media_type' THEN '上游原件或解析输入的 MIME 媒体类型（1–200 字符）。'
        WHEN 'parser_id' THEN '产出文档正文与结构的 Loader/解析器组件标识及版本号（1–200 字符）。'
        WHEN 'artifact_sha256' THEN 'Loader 实际消费的解析产物字节流 SHA-256 摘要（64 位小写十六进制，如 OCR 结构化导出 JSON）。'
        WHEN 'structure_sha256' THEN '规范化文档结构树的 SHA-256 摘要（涵盖块身份、父子层级、顺序、类型、标题路径、页码与包围盒）。'
        WHEN 'text_sha256' THEN '抽取出的规范化全文纯文本 SHA-256 摘要（64 位小写十六进制）。'
        WHEN 'build_spec_sha256' THEN '构建该文档版本所遵循的 Profile 构建规格 SHA-256（复合外键强制与所属 Profile 严格一致）。'
        WHEN 'chunk_set_sha256' THEN 'activate 阶段从暂存切片集合重新汇总核验的 SHA-256 摘要（building 状态下为 NULL，ready/superseded 状态下非空）。'
        WHEN 'permissions' THEN '文档级可信访问控制（ACL）权限标签数组（TEXT[]，1–256 个非空标签；即使公开文档也必须携带显式标签）。'
        WHEN 'metadata' THEN '文档级低敏结构化过滤属性（JSONB 对象）。'
        WHEN 'status' THEN '文档构建生命周期状态：building（切片暂存写入中）、ready（已通过核验并发布至正式切片表）、superseded（已被同文档新版本取代）、failed（构建失败）、retired（已退役下线）。'
        WHEN 'active' THEN '该构建版本是否为当前 (tenant_id, knowledge_space_id, profile_id, document_id) 下唯一对外可检索的在线版本（布尔值，active = true 时 status 必须为 ready）。'
        WHEN 'chunk_count' THEN 'activate 阶段核验通过的正式知识切片总数量（building 时为 0，ready/superseded 时 > 0）。'
        WHEN 'failure_code' THEN '当 status = ''failed'' 时的稳定低敏失败原因码（非 failed 状态强制为 NULL）。'
        WHEN 'created_at' THEN '文档构建版本创建时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '文档构建版本状态最后更新时间（TIMESTAMPTZ，必须 >= created_at）。'
        ELSE NULL
      END

      -- =======================================================================
      -- 4. agent_knowledge_profile_chunk_staging：构建期切片暂存表（27 列）
      -- =======================================================================
      WHEN column_record.table_name = 'agent_knowledge_profile_chunk_staging' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '所属租户隔离标识（与 knowledge_space_id、document_id、index_version、chunk_id 组成 5 列复合主键）。'
        WHEN 'knowledge_space_id' THEN '所属知识空间标识。'
        WHEN 'profile_id' THEN '目标 Index Profile 标识（复合外键级联关联 agent_knowledge_profile_documents）。'
        WHEN 'document_id' THEN '所属知识文档标识。'
        WHEN 'index_version' THEN '所属文档构建版本号（正整数）。'
        WHEN 'chunk_id' THEN '文档内部稳定切片标识（1–1200 字符且不含制表符或换行符）。'
        WHEN 'chunk_text' THEN 'displayText：面向回答引用与原文展示的切片文本（严禁由大模型自由改写篡改原文）。'
        WHEN 'search_text' THEN 'lexicalText：面向 PostgreSQL 全文检索（FTS）与三元组匹配的标准化词法文本。'
        WHEN 'dense_text' THEN '送入 Embedding 模型计算稠密向量的输入文本（若与 chunk_text 相同则可为 NULL 以节省存储）。'
        WHEN 'display_sha256' THEN 'chunk_text（展示文本）的 SHA-256 哈希摘要（64 位小写十六进制）。'
        WHEN 'dense_sha256' THEN '稠密向量化输入文本（COALESCE(dense_text, chunk_text)）的 SHA-256 哈希摘要。'
        WHEN 'lexical_sha256' THEN 'search_text（词法检索文本）的 SHA-256 哈希摘要。'
        WHEN 'source_uri' THEN '切片对应的受控源文档定位符（1–8192 字符）。'
        WHEN 'permissions' THEN '切片级可信 ACL 权限标签数组（1–256 个非空标签）。'
        WHEN 'metadata' THEN '切片级结构化元数据（JSONB 对象）。'
        WHEN 'embedding' THEN '暂存的 1024 维稠密语义向量（zyblw_extensions.vector(1024)）。'
        WHEN 'sparse_embedding' THEN '可选的稀疏向量序列化载荷（格式：dimension|index:value,...）。'
        WHEN 'parent_id' THEN '父级章节或父级结构块标识（用于上下文窗口扩展）。'
        WHEN 'lineage_ordinal' THEN '切片在文档内的稳定线性阅读顺序号（从 0 开始，同文档构建版本内唯一）。'
        WHEN 'previous_chunk_id' THEN '同一文档内紧邻的前一个切片 ID（首块为 NULL）。'
        WHEN 'next_chunk_id' THEN '同一文档内紧邻的后一个切片 ID（末块为 NULL）。'
        WHEN 'heading_path' THEN '切片所在章节的多级标题面包屑路径（TEXT[] 数组）。'
        WHEN 'page_numbers' THEN '切片覆盖的原件页码数组（INTEGER[]，从 1 开始的页码集合）。'
        WHEN 'origins' THEN '切片溯源定位详情数组（JSONB array，记录页码、版面坐标 bbox 与源块映射）。'
        WHEN 'block_ids' THEN '构成该切片的上游解析器稳定源块 ID 数组（TEXT[]）。'
        WHEN 'created_at' THEN '暂存切片首次写入时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '暂存切片幂等覆盖更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =======================================================================
      -- 5. agent_knowledge_profile_chunks：在线可检索知识切片主表（29 列）
      -- =======================================================================
      WHEN column_record.table_name = 'agent_knowledge_profile_chunks' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '所属租户隔离标识（与 knowledge_space_id、profile_id、document_id、chunk_id 组成 5 列复合主键）。'
        WHEN 'knowledge_space_id' THEN '所属知识空间标识。'
        WHEN 'profile_id' THEN '所属 Index Profile 标识（检索时锁定空间当前的 active_profile_id）。'
        WHEN 'document_id' THEN '所属知识文档逻辑标识。'
        WHEN 'source_revision_id' THEN '切片对应的上游原件业务修订版本标识（1–200 字符，检索时用于比对撤回墓碑表过滤已下架版本）。'
        WHEN 'chunk_id' THEN '文档内部稳定切片唯一标识（1–1200 字符且不含制表符或换行符）。'
        WHEN 'index_version' THEN '产出该切片的文档索引构建版本号（复合外键级联关联 agent_knowledge_profile_documents）。'
        WHEN 'chunk_text' THEN 'displayText：供回答引用展示与原文核对的权威切片文本。'
        WHEN 'search_text' THEN 'lexicalText：供全文分词与三元组检索使用的标准化文本（建有 pg_trgm GIN 索引）。'
        WHEN 'dense_text' THEN '生成稠密向量实际使用的输入文本（与 chunk_text 一致时为 NULL）。'
        WHEN 'search_vector' THEN '基于 search_text 自动生成的 simple 分词全文检索向量（TSVECTOR STORED 生成列，建有 GIN 索引）。'
        WHEN 'display_sha256' THEN 'chunk_text 的 SHA-256 哈希摘要（64 位小写十六进制）。'
        WHEN 'dense_sha256' THEN '稠密向量输入文本的 SHA-256 哈希摘要（64 位小写十六进制）。'
        WHEN 'lexical_sha256' THEN 'search_text 的 SHA-256 哈希摘要（64 位小写十六进制）。'
        WHEN 'source_uri' THEN '切片关联的受控源文档 URI（1–8192 字符）。'
        WHEN 'permissions' THEN '切片级可信 ACL 权限标签数组（检索时通过 permissions <@ caller_permissions 执行行级安全过滤）。'
        WHEN 'metadata' THEN '切片级结构化过滤属性（JSONB 对象，建有 jsonb_path_ops GIN 索引）。'
        WHEN 'embedding' THEN '1024 维稠密语义向量（zyblw_extensions.vector(1024)，建有 HNSW vector_cosine_ops 索引 m=16, ef_construction=64）。'
        WHEN 'sparse_embedding' THEN '可选稀疏向量载荷（格式：dimension|index:value,...）。'
        WHEN 'parent_id' THEN '所属父级章节或结构节点标识（支持按父节点聚合相邻切片）。'
        WHEN 'lineage_ordinal' THEN '切片在文档内的稳定线性阅读顺序号（从 0 开始，同文档内唯一）。'
        WHEN 'previous_chunk_id' THEN '同一文档内前驱切片 ID（用于上下文前向扩展）。'
        WHEN 'next_chunk_id' THEN '同一文档内后继切片 ID（用于上下文后向扩展）。'
        WHEN 'heading_path' THEN '切片所在的多级章节标题路径数组（TEXT[]，建有 GIN 索引支持按章节标题过滤）。'
        WHEN 'page_numbers' THEN '切片跨越的原件 1-based 页码数组（INTEGER[]，建有部分 GIN 索引支持按页码范围过滤）。'
        WHEN 'origins' THEN '切片对应的原版式页码、包围盒（bbox）与源块溯源投影数组（JSONB array）。'
        WHEN 'block_ids' THEN '构成该切片的上游结构化源块 ID 数组（TEXT[]）。'
        WHEN 'created_at' THEN '正式切片发布写入时间（TIMESTAMPTZ）。'
        WHEN 'updated_at' THEN '正式切片最后更新时间（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =======================================================================
      -- 6. agent_knowledge_profile_activation_audit：Profile 切换审计表（11 列）
      -- =======================================================================
      WHEN column_record.table_name = 'agent_knowledge_profile_activation_audit' THEN CASE column_record.column_name
        WHEN 'audit_id' THEN 'Profile 激活切换审计记录自增主键（BIGINT GENERATED ALWAYS AS IDENTITY）。'
        WHEN 'tenant_id' THEN '所属租户隔离标识（1–1000 字符）。'
        WHEN 'knowledge_space_id' THEN '所属知识空间标识（1–200 字符）。'
        WHEN 'old_profile_id' THEN '本次 CAS 切换前的原活动 Profile 标识（空间首次激活 Profile 时为 NULL）。'
        WHEN 'new_profile_id' THEN '本次 CAS 切换后生效的新活动 Profile 标识（1–200 字符）。'
        WHEN 'expected_space_revision' THEN '执行本次 CAS 切换时校验通过的知识空间 revision 版本号（>= 0）。'
        WHEN 'reason' THEN '执行本次 Profile 激活或回滚切换的低敏原因说明（1–160 字符）。'
        WHEN 'evaluation_id' THEN '批准本次 Profile 切换的可信离线评测执行 ID（可选，1–200 字符）。'
        WHEN 'evaluated_census_sha256' THEN '评测放行时核验的 Profile 完整文档清单 SHA-256 摘要（可选，64 位十六进制）。'
        WHEN 'document_count' THEN '本次激活 Profile 包含的已就绪文档总数量（可选，1–100000 之间）。'
        WHEN 'activated_at' THEN 'Profile 切换动作完成并写入审计的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =======================================================================
      -- 7. agent_knowledge_withdrawn：知识文档修订版本撤回墓碑表（5 列）
      -- =======================================================================
      WHEN column_record.table_name = 'agent_knowledge_withdrawn' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '所属租户隔离标识（与 knowledge_space_id、document_id、source_revision_id 组成 4 列复合主键）。'
        WHEN 'knowledge_space_id' THEN '所属知识空间标识（撤回墓碑仅作用于指定空间，不影响其他空间）。'
        WHEN 'document_id' THEN '被撤回的逻辑知识文档标识（1–1000 字符）。'
        WHEN 'source_revision_id' THEN '被撤回封禁的具体上游原件业务修订版本标识（1–200 字符；阻止该特定修订再次入库或被检索召回，不影响后续新修订）。'
        WHEN 'withdrawn_at' THEN '撤回墓碑记录写入生效的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      -- =======================================================================
      -- 8. agent_knowledge_structures：绑定精确文档版本的章节树结构产物表（10 列）
      -- =======================================================================
      WHEN column_record.table_name = 'agent_knowledge_structures' THEN CASE column_record.column_name
        WHEN 'tenant_id' THEN '所属租户隔离标识（与 knowledge_space_id、profile_id、document_id、index_version、structure_profile_id 组成 6 列复合主键）。'
        WHEN 'knowledge_space_id' THEN '结构产物所属的知识空间标识。'
        WHEN 'profile_id' THEN '结构产物绑定的目标 Chunk Index Profile 标识（复合外键级联关联 agent_knowledge_profile_documents）。'
        WHEN 'document_id' THEN '结构产物所属的逻辑知识文档标识。'
        WHEN 'index_version' THEN '结构产物绑定的精确文档索引版本号（正整数）。'
        WHEN 'structure_profile_id' THEN '独立文档结构构建规格的 SHA-256 摘要（64 位小写十六进制）。'
        WHEN 'generation' THEN '完整不可变结构快照内容的 SHA-256 摘要（64 位小写十六进制）。'
        WHEN 'chunk_set_sha256' THEN '生成该结构树时所绑定核验的正式切片集合 SHA-256（必须与对应文档版本的 chunk_set_sha256 一致）。'
        WHEN 'snapshot' THEN '文档章节树及章节到 Block/Chunk 的映射结构快照（JSONB 对象，上限 16MB，不含原文正文或稠密向量）。'
        WHEN 'created_at' THEN '不可变结构产物首次写入的时间戳（TIMESTAMPTZ）。'
        ELSE NULL
      END

      ELSE NULL
    END;

    IF comment_text IS NULL THEN
      RAISE EXCEPTION 'zyblw-agent knowledge column %.%.% is missing a Chinese COMMENT mapping',
        target_schema, column_record.table_name, column_record.column_name;
    END IF;

    EXECUTE format(
      'COMMENT ON COLUMN %I.%I.%I IS %L',
      target_schema,
      column_record.table_name,
      column_record.column_name,
      comment_text
    );
  END LOOP;
END
$comments$;
