-- 1024 维生产 RAG 数据字典。作为 repeatable migration，可在不重建索引的情况下持续完善注释。
COMMENT ON SCHEMA zyblw_agent_knowledge IS
  'zyblw-agent 管理的 1024 维可重建知识索引；Space/Profile 是检索身份，业务原件仍由宿主拥有。';

COMMENT ON TABLE agent_knowledge_spaces IS
  '知识空间与 CAS 活动 Profile 指针；检索只读 pinned active_profile_id。';
COMMENT ON TABLE agent_knowledge_profiles IS
  '不可变索引 Profile：dense/sparse/lexical/切分/融合身份。换模必须新建 Profile。';
COMMENT ON TABLE agent_knowledge_profile_documents IS
  'Profile 内文档 census：幂等摄取身份、块数与发布状态，不保存原文。';
COMMENT ON TABLE agent_knowledge_profile_chunk_staging IS
  'building 文档的幂等暂存块；activate 短事务发布到正式 Profile chunks。';
COMMENT ON TABLE agent_knowledge_profile_chunks IS
  '不可变 Profile 块：display/lexical 文本、dense 向量、可选 sparse、ACL 与谱系。';
COMMENT ON TABLE agent_knowledge_profile_activation_audit IS
  'Space 指针 CAS 激活审计；回滚也写新行。';
COMMENT ON TABLE agent_knowledge_withdrawn IS
  'Space 级 tombstone；回滚不得复活已撤回文档修订。';

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
      WHEN 'knowledge_space_id' THEN '租户内知识空间标识；默认 default。'
      WHEN 'active_profile_id' THEN '当前可查询的不可变 Profile；空表示尚未激活。'
      WHEN 'revision' THEN 'Space CAS 版本；激活必须携带 expected revision。'
      WHEN 'profile_id' THEN '不可变 Index Profile 标识。'
      WHEN 'profile_version' THEN '同一 Space 内单调递增的 Profile 版本。'
      WHEN 'dense_distance' THEN 'dense ANN 距离；当前基线为 cosine。'
      WHEN 'sparse_provider' THEN '可选 sparse Provider；未启用时为空。'
      WHEN 'sparse_model' THEN '可选 sparse 模型标识。'
      WHEN 'sparse_dimension' THEN 'sparse 词表维度；未启用时为空。'
      WHEN 'lexical_analyzer' THEN 'PostgreSQL FTS analyzer 身份。'
      WHEN 'lexical_strategy_id' THEN 'lexical 标准化策略稳定 ID。'
      WHEN 'chunking_strategy_id' THEN '切分器策略稳定 ID。'
      WHEN 'normalization_id' THEN 'display/retrieval 标准化器 ID。'
      WHEN 'normalization_version' THEN '标准化器版本。'
      WHEN 'metadata_schema_id' THEN '宿主 metadata schema ID，框架不内置领域词。'
      WHEN 'metadata_schema_version' THEN 'metadata schema 版本。'
      WHEN 'fusion_strategy' THEN '候选融合策略，默认 weighted-rrf。'
      WHEN 'fusion_version' THEN '融合策略版本。'
      WHEN 'ready_at' THEN 'Profile 通过 census/校验成为 Ready 的时间。'
      WHEN 'activated_at' THEN 'Profile 被 CAS 指为 active 的时间。'
      WHEN 'publication_evaluation_id' THEN '封闭该 Profile census 的可信评测执行标识；非空后禁止继续写入。'
      WHEN 'publication_census_sha256' THEN '封闭并发布的完整 Profile 文档 census SHA-256。'
      WHEN 'publication_document_count' THEN '封闭并发布时的完整 Profile 文档数量。'
      WHEN 'old_profile_id' THEN 'CAS 前的 active Profile。'
      WHEN 'new_profile_id' THEN 'CAS 后的 active Profile。'
      WHEN 'expected_space_revision' THEN '激活时读取到的 Space revision。'
      WHEN 'reason' THEN '激活或回滚原因的低敏分类。'
      WHEN 'evaluation_id' THEN '批准本次 Profile census 的可信评测执行标识。'
      WHEN 'evaluated_census_sha256' THEN '评测绑定的完整 Profile 文档 census SHA-256。'
      WHEN 'document_count' THEN '评测并发布的完整 Profile 文档数量。'
      WHEN 'activated_by' THEN '低敏操作者标识。'
      WHEN 'activated_at' THEN '审计时间。'
      WHEN 'document_id' THEN '租户内稳定原始文档标识；不同索引版本保持不变。'
      WHEN 'index_version' THEN '文档修订号；查询只读 active Profile 内 Ready 文档。'
      WHEN 'document_revision_id' THEN '文档内容修订身份，撤回按此粒度生效。'
      WHEN 'ingestion_id' THEN '可推导或调用方稳定幂等键。'
      WHEN 'source_uri' THEN '批准 scheme 的稳定来源标识；不得包含临时签名。'
      WHEN 'content_hash' THEN '规范化原始文档内容的 SHA-256。'
      WHEN 'permissions' THEN '可信权限标签；不能由正文或模型扩大。'
      WHEN 'metadata' THEN '低敏结构化属性；Enricher 不能改 tenant/ACL/URI。'
      WHEN 'embedding_provider' THEN '生成 dense 向量的稳定 Provider 标识。'
      WHEN 'embedding_model' THEN '生成 dense 向量的模型标识。'
      WHEN 'embedding_dimension' THEN '本物理基线固定为 1024。'
      WHEN 'embedding_max_batch_size' THEN '摄取时 Embedding 最大批大小。'
      WHEN 'embedding_supports_dimensions' THEN 'Provider 是否显式携带 dimensions。'
      WHEN 'indexing_strategy' THEN 'Loader/Chunker/lexical 策略的稳定标识。'
      WHEN 'status' THEN '生命周期状态；检索只读 active Profile 的 ready 文档。'
      WHEN 'active' THEN '文档是否为当前 Profile 内可查询修订。'
      WHEN 'chunk_count' THEN 'activate 核验的完整块数。'
      WHEN 'failure_code' THEN '失败的稳定低敏分类。'
      WHEN 'chunk_id' THEN '文档内部稳定块标识。'
      WHEN 'chunk_text' THEN 'displayText：引用与展示原文，禁止 LLM 改写。'
      WHEN 'search_text' THEN 'lexicalText：PostgreSQL FTS 用的标准化文本。'
      WHEN 'dense_text' THEN 'dense embedding 输入文本；可与 display 不同。'
      WHEN 'search_vector' THEN '由 search_text 生成的 tsvector。'
      WHEN 'display_sha256' THEN 'displayText 的 SHA-256。'
      WHEN 'dense_sha256' THEN 'denseText 的 SHA-256。'
      WHEN 'lexical_sha256' THEN 'lexicalText 的 SHA-256。'
      WHEN 'embedding' THEN '1024 维 dense 向量。'
      WHEN 'sparse_embedding' THEN '可选 sparse 的 JSON 载荷；无 ANN 索引。'
      WHEN 'parent_id' THEN '文档内部父结构标识。'
      WHEN 'lineage_ordinal' THEN '文档内从零开始的稳定阅读顺序。'
      WHEN 'previous_chunk_id' THEN '同一文档前一块标识。'
      WHEN 'next_chunk_id' THEN '同一文档后一块标识。'
      WHEN 'heading_path' THEN '结构化标题路径。'
      WHEN 'page_numbers' THEN '1-based 原文页码。'
      WHEN 'origins' THEN 'page/bbox/block 来源投影。'
      WHEN 'block_ids' THEN 'Loader 稳定源块标识。'
      WHEN 'withdrawn_at' THEN 'tombstone 写入时间。'
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
