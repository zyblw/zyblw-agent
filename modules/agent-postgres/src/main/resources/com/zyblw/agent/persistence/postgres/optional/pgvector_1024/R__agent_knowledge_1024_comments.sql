-- 1024 维生产 RAG 数据字典。作为 repeatable migration，可在不重建索引的情况下持续完善注释。
COMMENT ON SCHEMA zyblw_agent_knowledge IS
  'zyblw-agent 管理的 1024 维可重建知识索引；Space/Profile 是检索身份，业务原件仍由宿主拥有。';

COMMENT ON TABLE agent_knowledge_spaces IS
  '知识空间与 CAS 活动 Profile 指针；检索只读 pinned active_profile_id。';
COMMENT ON TABLE agent_knowledge_profiles IS
  '索引 Profile：完整不可变构建规格及其摘要。active/building 可写，superseded 仅接受显式补齐，终止状态封存；换模必须新建 Profile。';
COMMENT ON TABLE agent_knowledge_profile_documents IS
  'Profile 内文档构建：来源谱系（原件修订/解析产物/结构/正文摘要）、构建规格摘要、块集合摘要与发布状态，不保存原文。';
COMMENT ON TABLE agent_knowledge_profile_chunk_staging IS
  'building 文档的幂等暂存块；activate 短事务发布到正式 Profile chunks。';
COMMENT ON TABLE agent_knowledge_profile_chunks IS
  '不可变 Profile 块：display/lexical 文本、dense 向量、可选 sparse、ACL 与谱系。';
COMMENT ON TABLE agent_knowledge_profile_activation_audit IS
  'Space 指针 CAS 激活审计；回滚也写新行。';
COMMENT ON TABLE agent_knowledge_withdrawn IS
  '按租户、空间、文档和来源修订撤回的墓碑；阻止该修订重新索引，不影响其他空间或其他修订。';

DO $comments$
DECLARE
  column_record RECORD;
  column_comment TEXT;
BEGIN
  FOR column_record IN
    SELECT table_name, column_name
    FROM information_schema.columns
    WHERE table_schema = 'zyblw_agent_knowledge'
      AND table_name IN (
        'agent_knowledge_spaces',
        'agent_knowledge_profiles',
        'agent_knowledge_profile_documents',
        'agent_knowledge_profile_chunk_staging',
        'agent_knowledge_profile_chunks',
        'agent_knowledge_profile_activation_audit',
        'agent_knowledge_withdrawn'
      )
    ORDER BY table_name, ordinal_position
  LOOP
    column_comment := CASE column_record.column_name
      WHEN 'tenant_id' THEN '宿主注入的租户隔离标识；任何检索和写入都必须显式匹配。'
      WHEN 'knowledge_space_id' THEN '租户内知识空间标识；文档键的一部分，无数据库默认值。'
      WHEN 'active_profile_id' THEN '当前可查询的 Profile；空表示尚未激活。'
      WHEN 'revision' THEN 'Space CAS 版本；激活必须携带 expected revision。'
      WHEN 'profile_id' THEN 'Index Profile 标识，由构建规格摘要派生。'
      WHEN 'profile_version' THEN '同一 Space 内单调递增的 Profile 版本。'
      WHEN 'build_spec' THEN '完整构建规格 JSON：embedding Provider/模型/维度/距离、tokenizer、文档 instruction、切分与词法策略、Enricher。'
      WHEN 'build_spec_sha256' THEN '构建规格的规范化 SHA-256；文档构建通过复合外键必须与所属 Profile 一致。'
      WHEN 'embedding_provider' THEN '由 build_spec 生成：dense 向量 Provider。'
      WHEN 'embedding_model' THEN '由 build_spec 生成：dense 向量模型。'
      WHEN 'embedding_dimension' THEN '由 build_spec 生成：本物理基线固定为 1024。'
      WHEN 'indexing_strategy' THEN '由 build_spec 生成：切分与词法策略稳定标识。'
      WHEN 'publication_evaluation_id' THEN '最近一次评测发布该 Profile 的可信评测执行标识；记录而非封存。'
      WHEN 'publication_census_sha256' THEN '最近一次评测发布的完整文档清单 SHA-256。'
      WHEN 'publication_document_count' THEN '最近一次评测发布的文档数量。'
      WHEN 'old_profile_id' THEN 'CAS 前的 active Profile。'
      WHEN 'new_profile_id' THEN 'CAS 后的 active Profile。'
      WHEN 'expected_space_revision' THEN '激活时读取到的 Space revision。'
      WHEN 'reason' THEN '激活或回滚原因的低敏分类。'
      WHEN 'evaluation_id' THEN '批准本次 Profile 切换的可信评测执行标识。'
      WHEN 'evaluated_census_sha256' THEN '评测绑定的完整 Profile 文档清单 SHA-256。'
      WHEN 'document_count' THEN '评测并发布的完整 Profile 文档数量。'
      WHEN 'audit_id' THEN '审计行主键，单调递增。'
      WHEN 'activated_at' THEN 'Profile 最近一次成为 active 或审计写入的时间。'
      WHEN 'document_id' THEN '空间内稳定业务文档标识；不同索引版本保持不变。'
      WHEN 'index_version' THEN '在 (tenant, space, document) 内单调递增的构建版本。'
      WHEN 'ingestion_id' THEN '调用方稳定幂等键，或由谱系与构建规格 HMAC 推导。'
      WHEN 'source_uri' THEN '批准 scheme 的稳定来源标识；不得包含临时签名。'
      WHEN 'source_id' THEN '租户内跨空间稳定的原件标识，例如一本书。'
      WHEN 'source_revision_id' THEN '原件业务修订；撤回与蓝绿切换的语料比较都按此粒度。'
      WHEN 'source_sha256' THEN '原件字节 SHA-256；宿主未提供时等于解析产物摘要。'
      WHEN 'source_media_type' THEN '原件或解析输入的媒体类型。'
      WHEN 'parser_id' THEN '产出正文与结构的 Loader/解析器标识及版本。'
      WHEN 'artifact_sha256' THEN 'Loader 实际消费的字节 SHA-256，例如 OCR 导出 JSON。'
      WHEN 'structure_sha256' THEN '规范化结构摘要：block 身份、父子、顺序、类型、标题路径、页码与 bbox。'
      WHEN 'text_sha256' THEN '抽取正文的 SHA-256。'
      WHEN 'chunk_set_sha256' THEN 'activate 从暂存块重新计算并核验的块集合摘要；building/failed 时为空。'
      WHEN 'permissions' THEN '可信权限标签，1..256 个；租户内公开也必须使用显式标签。'
      WHEN 'metadata' THEN '低敏结构化属性；Enricher 不能改 tenant/ACL/URI。'
      WHEN 'status' THEN '生命周期状态；检索只读 active Profile 的 ready 文档。'
      WHEN 'active' THEN '文档是否为所属 Profile 内当前可查询版本。'
      WHEN 'chunk_count' THEN 'activate 核验的完整块数。'
      WHEN 'failure_code' THEN '失败的稳定低敏分类。'
      WHEN 'chunk_id' THEN '文档内部稳定块标识；不含制表符或换行。'
      WHEN 'chunk_text' THEN 'displayText：引用与展示原文，禁止 LLM 改写。'
      WHEN 'search_text' THEN 'lexicalText：PostgreSQL FTS 用的标准化文本。'
      WHEN 'dense_text' THEN 'dense embedding 输入文本；与 display 相同时为空。'
      WHEN 'search_vector' THEN '由 search_text 生成的 tsvector。'
      WHEN 'display_sha256' THEN 'displayText 的 SHA-256。'
      WHEN 'dense_sha256' THEN 'denseText 的 SHA-256。'
      WHEN 'lexical_sha256' THEN 'lexicalText 的 SHA-256。'
      WHEN 'embedding' THEN '1024 维 dense 向量。'
      WHEN 'sparse_embedding' THEN '可选 sparse 载荷，格式 dimension|index:value,...；无 ANN 索引。'
      WHEN 'parent_id' THEN '文档内部父结构标识。'
      WHEN 'lineage_ordinal' THEN '文档内从零开始的稳定阅读顺序。'
      WHEN 'previous_chunk_id' THEN '同一文档前一块标识。'
      WHEN 'next_chunk_id' THEN '同一文档后一块标识。'
      WHEN 'heading_path' THEN '结构化标题路径。'
      WHEN 'page_numbers' THEN '1-based 原文页码。'
      WHEN 'origins' THEN 'page/bbox/block 来源投影。'
      WHEN 'block_ids' THEN 'Loader 稳定源块标识。'
      WHEN 'withdrawn_at' THEN '墓碑写入时间。'
      WHEN 'created_at' THEN '记录创建时间（带时区）。'
      WHEN 'updated_at' THEN '记录最后更新时间（带时区）。'
      ELSE NULL
    END;
    IF column_comment IS NULL THEN
      RAISE EXCEPTION '知识索引字段 %.% 缺少中文数据字典说明',
        column_record.table_name, column_record.column_name;
    END IF;
    EXECUTE format(
      'COMMENT ON COLUMN %I.%I.%I IS %L',
      'zyblw_agent_knowledge', column_record.table_name, column_record.column_name, column_comment
    );
  END LOOP;
END
$comments$;
