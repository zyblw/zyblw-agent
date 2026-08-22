# ADR 0020：现代化基线、死合同清理与无损迁移

> 状态：**Accepted / 实施中**
> 日期：2026-08-22
> 影响：下一 fresh-install 数据库基线、公共 API 清理、文档权威路径
> 事实来源：源码引用审计、Flyway V001–V011、`AgentPostgresMigrations.CoreRelations`

## 决策

持续开发、暂不打 `v0.7.0`。允许下一 fresh-install 使用破坏性新基线，但已有库必须先经只读普查、checksum 导出和导入核对，才能切换。已发布到 Maven Central 的 `0.6.2` migration 文件不得改写。

## 分类

| 项 | 判定 | 处理 |
|---|---|---|
| `AgentRuntime` / Run / Command / Tool / ModelCall 账本 | KEEP | 唯一循环 |
| `PostgresArtifactStore` + V011 | KEEP | Beta 元数据 |
| Memory 治理 / HTTP 导出删除 | KEEP | 补宿主装配 |
| `ConfiguredGuardrails` 输入/输出/工具/Run | KEEP | 本轮追加 retrieval/remote |
| `model_calls` / `agent_messages` / `agent_steps` / `usage_records` | DELETE after empty census | V012 在行数为 0 时 DROP；有数据则 fail-closed。已发布 V001 文件不改写 |
| `optional/pgvector` 与 `pgvector_1536_v0_4` | DELETE | 无 Scala API；1024 是唯一知识基线 |
| `LegacyPgVector03Location` | DELETE | 仅指向已删 location |
| Vercel/React agent skills | DELETE | 与框架运行时无关；dashboard 继续用仓库内前端约定 |
| Multimodal / KnowledgeGraph / Quickstart / ConversationStore | DELETE | 已删，禁止复活空壳 |
| MCP `2025-11-25` transport | REPLACE | 新实现 `2026-07-28` 通过互操作测试后再删旧握手 |
| Harness / Workflow / A2A | KEEP as Experimental | 无 Eval 证据不升成熟度 |

## 无损迁移

`AgentSchemaCensus` 对已知表做存在性与行数统计，并计算低敏 checksum。`AgentSchemaManifest` 把普查结果序列化为可保存的核对物：只含关系名、种类、存在性和行数，不含正文。切换下一基线前必须：

1. 在副本上跑 census 并保存 `SchemaManifest`；
2. 确认死投影表行数为 0 或已导出；
3. 权威表行数在导入后用 `AgentSchemaManifest.verifyImport` 逐表相等；
4. 失败则留在旧 history，不执行 down migration。

`ProductionSupportHost status` 会打印 census 自检与基线切换建议。下一基线的 SQL 只在 census 工具和本 ADR 稳定后单独开切，不修改 V001–V011。
