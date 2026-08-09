# 升级到 0.6.0：1024 维 RAG 新库基线与缓存用途隔离

> 状态：0.6.0 生产部署指南。
> 最后核验：2026-08-09

## 变更边界

0.6.0 为新建 RAG 库增加 `AgentPostgresMigrations.migrateCoreAndKnowledge1024`：Agent core 使用独立的 history，knowledge
index 使用 `zyblw_agent_knowledge` schema、`flyway_zyblw_agent_knowledge_1024_history` 和固定 `vector(1024)`。启动后会验证
pgvector >= 0.8、关键谱系/ACL 表与 embedding 维度；漂移会阻止宿主 HTTP 启动。

这是全新数据库主线：以空知识 schema 建立 1024 collection，再执行 retrieval/citation/eval 后激活。不同模型身份或维度
不得混入同一 active collection。

`EmbeddingCacheKey` 还增加了受信 `EmbeddingPurpose`，`GovernedEmbeddingService` 会把该上下文传给真实 Provider 的
`embedScoped`，避免 query 与 indexing 在使用不同 instruction 时错误共用缓存。核心 migration
`V003__embedding_cache_purpose.sql` 为缓存增加 `purpose`，将旧项标为 `legacy` 并重建精确主键/过期索引；缓存属于可再生派生
数据，新代码不会猜测旧项用途。

## 宿主动作

1. 在预发布数据库运行 core 与 1024 knowledge migration；验证框架的两套 Flyway history、pgvector 与 schema probe（平台若另有自己的 history，应保持独立）。
2. 重新编译所有手写 `EmbeddingCacheKey(...)` 的 Adapter/测试，提供 purpose；按用途追加 instruction 的 Adapter 覆盖
   `EmbeddingService.embedScoped`。
3. 把 embedding 模型、base URL、维度与 `EMBEDDING_PROVIDER_ID` 当成一套不可分身份；新模型必须建立独立 snapshot 并通过评测。
4. 平台/CI/生产固定精确 `0.6.0` Maven 坐标，不使用 source、SNAPSHOT 或版本范围。

详细背景保留在 [RAG 设计说明](upgrading-to-next-minor-rag.md)。
