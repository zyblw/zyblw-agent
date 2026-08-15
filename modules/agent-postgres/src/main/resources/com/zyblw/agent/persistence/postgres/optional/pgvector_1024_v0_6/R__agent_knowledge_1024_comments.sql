-- 1024 维生产 RAG 数据字典。作为 repeatable migration，可在不重建索引的情况下持续完善注释。
COMMENT ON SCHEMA zyblw_agent_knowledge IS
  'zyblw-agent 管理的 1024 维可重建知识索引；业务原件、发布状态和账号权限仍由宿主应用拥有。';

COMMENT ON TABLE agent_knowledge_documents IS
  '知识文档索引清单：固定摄取幂等身份、Embedding 契约、发布状态和 active 版本，不保存原文正文。';
COMMENT ON TABLE agent_knowledge_chunk_staging IS
  'building 版本的幂等暂存块；只有 activate 短事务可把完整版本发布到正式检索表。';
COMMENT ON TABLE agent_knowledge_chunks IS
  '当前 active 知识块读模型；向量、全文、权限标签与原文谱系在同一行原子可见。';

DO $comments$
DECLARE
  column_record RECORD;
  column_comment TEXT;
BEGIN
  FOR column_record IN
    SELECT table_name, column_name
    FROM information_schema.columns
    WHERE table_schema = 'zyblw_agent_knowledge'
      AND table_name IN ('agent_knowledge_documents', 'agent_knowledge_chunk_staging', 'agent_knowledge_chunks')
    ORDER BY table_name, ordinal_position
  LOOP
    column_comment := CASE column_record.column_name
      WHEN 'tenant_id' THEN '宿主注入的租户隔离标识；任何检索和写入都必须显式匹配。'
      WHEN 'document_id' THEN '租户内稳定原始文档标识；不同索引版本保持不变。'
      WHEN 'index_version' THEN '文档内单调递增的索引快照版本；查询只读取 active 版本。'
      WHEN 'ingestion_id' THEN '调用方稳定幂等键；相同键必须绑定完全相同的内容、模型和切分策略。'
      WHEN 'source_uri' THEN '宿主提供的稳定来源标识；不得包含临时签名密钥或永久访问凭据。'
      WHEN 'content_hash' THEN '规范化原始文档内容的 SHA-256，用于拒绝相同 ingestion_id 的内容漂移。'
      WHEN 'permissions' THEN '可信权限标签；每个块只能继承或收窄，不能由正文或模型扩大。'
      WHEN 'metadata' THEN '低敏、受控的结构化属性；原始文档和 Provider 响应不存放于此。'
      WHEN 'embedding_provider' THEN '生成向量的稳定 Provider 标识，用于索引谱系和一致性审计。'
      WHEN 'embedding_model' THEN '生成向量的模型标识。'
      WHEN 'embedding_dimension' THEN '本物理基线固定为 1024；更换维度需要新的独立 migration 契约。'
      WHEN 'embedding_max_batch_size' THEN '摄取时使用的 Embedding 最大批大小，作为可重放配置的一部分。'
      WHEN 'embedding_supports_dimensions' THEN 'Provider 请求是否显式携带 dimensions 参数。'
      WHEN 'indexing_strategy' THEN '包含 Loader、Chunker 与关键参数版本的稳定策略标识。'
      WHEN 'status' THEN 'building/ready/superseded/failed/retired 生命周期；只有 ready 可成为 active。'
      WHEN 'active' THEN '是否为该租户文档当前可查询版本；部分唯一索引保证最多一个。'
      WHEN 'chunk_count' THEN 'activate 事务核验并记录的完整块数，防止部分 staging 被错误发布。'
      WHEN 'failure_code' THEN '摄取失败的稳定低敏分类；不保存 Provider、OCR 或数据库原始异常。'
      WHEN 'chunk_id' THEN '文档内部稳定块标识；引用、去重和谱系必须同时携带 document_id。'
      WHEN 'chunk_text' THEN '用于回答和引用的权威原文片段；不是模型生成的摘要。'
      WHEN 'search_text' THEN '用于 PostgreSQL FTS 的确定性文本或中文分词投影。'
      WHEN 'search_vector' THEN '由 search_text 确定性生成的全文检索向量。'
      WHEN 'embedding' THEN 'chunk_text 对应的 1024 维向量；模型身份记录在文档清单。'
      WHEN 'parent_id' THEN '文档内部父结构标识，例如章节；禁止跨文档扩展。'
      WHEN 'lineage_ordinal' THEN '文档内从零开始的稳定阅读顺序。'
      WHEN 'previous_chunk_id' THEN '同一文档内前一块标识；扩展时必须重新执行租户与权限过滤。'
      WHEN 'next_chunk_id' THEN '同一文档内后一块标识；扩展时必须重新执行租户与权限过滤。'
      WHEN 'heading_path' THEN '从文档根到当前块的结构化标题路径。'
      WHEN 'page_numbers' THEN '去重后的 1-based 原文页码，用于引用展示和阅读器回放。'
      WHEN 'origins' THEN '有序 page/bbox/block 来源投影；不保存 Loader 原始响应。'
      WHEN 'block_ids' THEN 'Loader 生成的稳定源块标识，用于追溯版面解析结果。'
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
