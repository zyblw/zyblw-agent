# 升级到 0.4.0

> 适用范围：从 0.3.0/0.3.x 采用 0.4 的结构化文档与 RAG 能力
> 原则：保留权威原文和业务权限，重建派生知识索引，不修改已发布 migration

## 变化摘要

0.4.0 保持 Agent Runtime、耐久命令、HTTP v1、Workflow outcome v2 和核心 PostgreSQL 控制面不变。主要变化集中在 Beta RAG：

- `SourceDocument` 可以携带结构化 blocks、标题、parent、页码、bbox 和稳定 block ID；
- `DocumentChunk`、`Citation` 可以携带 parent/previous/next、阅读顺序、heading path、页面和来源谱系；
- Docling Loader 同时请求 Markdown 与 JSON，并在有界解析后投影到 provider-neutral 类型；
- 新知识索引使用 manifest → staging → atomic activate，复合 `(tenant, document, chunk)` 身份和 ACL 后上下文扩展；
- 1536 维 pgvector 基线位于独立 `zyblw_agent_knowledge` schema 和独立 Flyway history。

## Scala 代码升级

把所有 `zyblw-agent-*` 模块统一升级到 `0.4.0`，然后完整重编译。使用框架默认构造器且依赖新增字段默认值的调用方通常只需重编译；以下实现需要
逐项复核：

1. 自定义 `DocumentLoader` 是否保留稳定 `sourceUri`，并在可用时生成结构化 `DocumentBlock`；不能用模型臆造页码或 bbox。
2. 自定义 `Chunker` 是否给 chunk 分配文档内稳定 ID，并保持 `documentId`、tenant、ACL、heading 和 origin lineage。
3. 自定义 `VectorStore` 是否把 `(documentId, chunkId)` 当成组合身份，并在相邻/父级扩展时重新执行 tenant/permission 过滤。
4. 自定义 citation/rendering 是否展示结构化页码/来源，而不是从 metadata 字符串反解析。

生产摄取优先使用 `DocumentStructureChunker`；只有 Loader 无法提供结构时才使用 `MarkdownStructureChunker` fallback。

## PostgreSQL 升级

1. 备份数据库，并确认权威 PDF/Markdown、稳定 document ID、tenant/ACL、内容 hash 和摄取参数可以重放。
2. 保留核心 `flyway_zyblw_agent_schema_history` 与 0.3 V001；0.4 不要求重建 Agent/Workflow 控制面。
3. 让部署账号执行 `AgentPostgresMigrations.migrateKnowledge1536(dataSource)`。它创建/校验
   `zyblw_agent_knowledge` schema、专属 history、`public.vector >= 0.8.0` 和固定 `vector(1536)` 表结构。
4. 用 0.4 Loader → Chunker → Embedding → `KnowledgeIndexStore` 从权威原文重新摄取。不要从旧向量反推原文，也不要复制未知模型生成的权限。
5. 运行 ACL、引用页码/bbox、召回、重排与撤回测试；切换业务查询后观察错误率、空召回和 P95/P99。
6. 只有确认不再回滚到旧查询路径后，才按宿主的数据保留审批清理 0.3 旧知识派生表。框架不会自动删除它们。

若使用非 1536 维 Embedding，不要强行写入当前表。应设计新的有界物理契约/location/schema，并同时更新模型 descriptor、迁移、Store、测试和运维文档。

## 推荐启动顺序

```text
共享 DataSource readiness
  -> core migrate/validate/verify
  -> knowledge-1536 migrate/validate/verify
  -> 构造普通 DML ZLayer
  -> 启动摄取 Worker、Agent Worker 与 HTTP
```

生产可由部署任务/DBA 持有 DDL 权限，应用账号只持有 `public` 核心表与 `zyblw_agent_knowledge` 知识表的必要 DML 权限。

## 禁止操作

- 不修改已发布的 0.3 核心/旧 pgvector V001；
- 不执行 Flyway `repair`、删除 history 或开启 `baselineOnMigrate` 来掩盖漂移；
- 不把两套 V001 放进同一 Flyway location/history；
- 不把 knowledge location 传给通用 `migrate(config)`；必须使用专属知识迁移入口；
- 不让运行时依赖连接 `search_path` 猜测知识表；0.4 Store 使用 schema-qualified SQL；
- 不在数据库事务中调用 OCR、LLM、Embedding 或远程对象存储。

## 验收清单

- 完整单元/契约测试通过；
- 真实 PostgreSQL 先核心、后知识迁移成功，重复启动执行 0 个新 migration；
- staging 对查询不可见，activate 原子切换，失败版本可回收；
- tenant/ACL 在向量、全文、父级和相邻扩展路径均 fail-closed；
- 引用能回到稳定 URI、页码/bbox/block；
- Maven-local 独立 consumer 编译并运行关键业务测试；
- 备份、恢复、容量曲线、SLO 和回滚决策已由具体业务环境验证。
