# 升级到 0.8.0

> 状态：破坏性全新安装  
> 最后核验：2026-08-23
> 事实来源：折叠后的 Flyway、检索 mode、知识 HTTP、`KnowledgeQaHost` 与评测门禁

`0.8.0` **不支持**从 `0.6.x` 或未发布的 `0.7.0` 候选库原地升级。必须使用空数据库，并按当前 tokenizer /
embedding / 切分器重新摄入全部文档。

## 1. 数据库

1. 备份后丢弃旧库，或对新库执行 `AgentPostgresMigrations.resetAll`。
2. 调用 `AgentPostgresMigrations.migrateCoreAndKnowledge1024`。
3. 核心 history 只有 `V001__zyblw_agent_0_8_baseline.sql`；知识 history 只有
   `optional/pgvector_1024/V001__agent_knowledge_0_8_baseline.sql`（含 pg_trgm、metadata/heading GIN）。
4. 换 `strategyId`、Embedding 模型或维度不能原地覆盖；必须新建索引版本。

已发布的 `0.6.2` Maven 制品与 V001–V003 / 旧 1024 knowledge V001 仍冻结在对应 tag 中；它们不是本版本的升级起点。

## 2. 检索与引用

- `RetrievalMode`：`Hybrid`、`VectorOnly`、`LexicalOnly`、`Phrase`。
- `RetrievalFilter` 在 ACL 之后、打分之前生效：document / chunk / page / heading / metadata。
- 默认切分按 cl100k BPE 装箱（`maxTokens=512`）。中文 lexical 是 `simple-cjk-bigram-v2`（NFKC、繁转简、停用词、
  unigram+bigram）。整句再识别用 `Phrase`（pg_trgm），不引入 zhparser/jieba。
- `knowledge_search` / `knowledge_fetch` 是正式工具。模型不得覆盖 `tenantId` / `permissions`。
- `AgentState` schemaVersion 为 7：有界 `citations` 与 `retrievalEvidence`。HTTP OpenAPI 为 `1.2.0`。

## 3. 知识 HTTP 与授权

稳定面是 `/api/v1/knowledge/**`（documents、search、ingestions、reindex）。管理面不再挂载知识路由。
tenant 只来自 `AgentRequestContextResolver`。请求体出现 `tenantId` / `permissions` 会被拒绝。

索引 ACL 只写入 `knowledge:read`。chunk 权限必须是检索方 scope 的子集；把 write/admin 写进文档会让只读检索变成 0 条。

## 4. 问答宿主

`KnowledgeQaHost` 是书籍问答组合根。配置了 `ZYBLW_AGENT_JDBC_URL` 后，`serve` 走 PostgreSQL 知识库与耐久 Run；
`status` 同时报告核心与 1024 知识 Flyway。未配置 JDBC 的 `serve` 只用于 contract / 进程内验证。

`ZYBLW_AGENT_RUNTIME_MODE=live` 时，摄入 / 重建 / serve 必须提供 `EMBEDDING_API_KEY`、`EMBEDDING_MODEL` 与
`EMBEDDING_DIMENSION=1024`，不会回退到 `HashEmbedding`。`contract` 模式才允许哈希向量做确定性验证。

重建从 `ZYBLW_AGENT_BOOKS_DIR` 或 `data/books` 按稳定 documentId 回读原文。多副本无共享盘时，宿主必须自己提供
对象存储版 `KnowledgeSourceResolver`。

```bash
export EMBEDDING_API_KEY=...
export EMBEDDING_MODEL=text-embedding-3-small
export EMBEDDING_DIMENSION=1024

sbt "examples/runMain com.zyblw.agent.examples.knowledge.KnowledgeQaHost status"
sbt "examples/runMain com.zyblw.agent.examples.knowledge.KnowledgeQaHost migrate"
sbt "examples/runMain com.zyblw.agent.examples.knowledge.KnowledgeQaHost ingest data/books"
sbt "examples/runMain com.zyblw.agent.examples.knowledge.KnowledgeQaHost serve"
```

客户支持与审批写工具仍走 `ProductionSupportHost`。

## 5. 兼容面

相对已发布 `0.6.2`，这是 early-semver minor 破坏：折叠 Flyway、删除管理知识路由、state v7、OpenAPI 1.2.0、
检索 mode/filter。业务 HTTP `/api/v1` 路径保留；引用字段是加法。第三方 Store Adapter 必须能读写 state v7 的
citations/evidence 默认值，并覆盖 `VectorStore.searchFiltered`（`DefaultRetriever` 不再走 `searchHybrid`）。
只覆盖 `searchHybrid` 的旧 Adapter 会被默认 `searchFiltered` 退化成纯向量候选池后再内存过滤。
