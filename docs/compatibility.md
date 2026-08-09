# 兼容性契约与版本边界

> 状态：0.6.0 发布契约
> 最后核验：2026-08-09
> 事实来源：`build.sbt`、公共源码、HTTP Schema、数据库 baseline、测试与发布工作流

## 当前结论

`0.6.0` 是全新部署基线：RAG 固定使用独立 `zyblw_agent_knowledge` schema 中的 `vector(1024)`，Embedding 缓存键固定包含
`purpose`，并由核心 `V003__embedding_cache_purpose.sql` 落库。业务部署从空库开始，所有 `zyblw-agent-*` artifact 必须使用精确
`0.6.0` 坐标。框架不提供旧知识 schema、旧向量维度或其迁移入口。

已发布 Maven 制品、tag 和 migration 永远不可变。`0.6.x` patch 必须保持本页定义的公共 Scala API、HTTP/schema、状态 JSON、
Maven 坐标与 1024 RAG 物理契约；任何删除 API、改变 wire/state 语义、向量维度或数据库基线的变化都必须进入新的 minor。

## 0.6.0 发布边界

| 表面 | 0.6.0 基线 | 0.6.x patch 承诺 |
|---|---|---|
| Agent/Core Scala API | Runtime、Tool、权限、命令、应用装配和可选管理 SPI | 不删除或改变公开签名语义 |
| RAG Scala API | `EmbeddingPurpose`、用途隔离缓存、受控 lexical representation、检索证据状态 | 不改变租户、用途、ACL、citation 与 evidence 语义 |
| PostgreSQL Core | V001/V002/V003 顺序执行；V003 缓存主键含 `purpose` | 已发布 migration 不修改，只追加兼容 migration |
| PostgreSQL RAG | 独立 1024 history、单一 fresh V001、固定 `vector(1024)` | 不改变 schema、history 或向量维度 |
| 业务 HTTP | `/api/v1`、`AgentHttpContract` 与 OpenAPI | 已发布路径和 wire 字段兼容 |
| 管理 HTTP | `/api/v1/admin/**` 为 Beta，不纳入稳定业务 OpenAPI | 可随控制台在 minor 内演进 |
| State/outcome JSON | Workflow outcome v2 与可恢复 Agent state | 新字段必须具有安全默认读取语义 |

## 数据库采用契约

生产从空库开始，先执行 `AgentPostgresMigrations.migrate`，需要 RAG 时执行
`AgentPostgresMigrations.migrateKnowledge1024`，或使用相应 `migrated*` 层。核心 history 与知识 history 各自独立；
`migrate(config)` 拒绝知识 location，防止两个 V001 混入同一 Flyway history。启动后的结构探针要求 pgvector >= 0.8、关键
谱系/ACL 列和两张 embedding 表均为 `vector(1024)`。

禁止 `repair`、删除 history、伪造 checksum 或以 `baselineOnMigrate` 掩盖未知 framework 表。缓存是可再生派生数据：V003 将旧项
标记为 `legacy`，当前 0.6.0 代码只读带明确用途的项。

## 发布门禁

`0.6.0` 发布前必须同时通过格式检查、完整 `testFull`、真实 PostgreSQL 16/pgvector（V001→V002→V003、1024 knowledge baseline、
缓存用途隔离与 keyset 目录）、`publishM2`、独立 Maven consumer、HTTP/OpenAPI 与管理面授权边界测试；控制台还必须通过
`typecheck`、lint、生产构建与 Playwright 浏览器契约。发布 tag 必须来自远端 `main`，且 CHANGELOG 与
`docs/upgrading-to-0.6.0.md` 一致；`.github/scripts/verify-release.sh` 会 fail-closed 校验。
