# 兼容性契约与版本边界

> 状态：0.8.0 全新安装基线；0.6.x 已发布制品冻结；0.7.0 候选作废
> 最后核验：2026-08-23
> 事实来源：`build.sbt`、公共源码、HTTP Schema、数据库 baseline、测试与发布工作流

## 0.8.0 边界

`0.8.0` 是破坏性全新安装。核心 Flyway 折叠为单个 `V001__zyblw_agent_0_8_baseline.sql`，知识 schema 折叠为
`optional/pgvector_1024/V001__agent_knowledge_0_8_baseline.sql`。不提供从 0.6.x 或未发布 0.7.0 候选的原地升级。
`AgentState` schemaVersion 为 7（有界 citations / retrievalEvidence）。稳定 HTTP OpenAPI 为 `1.2.0`，含
`GET /api/v1/runs/{runId}/citations` 与 `/api/v1/knowledge/**`。管理面不再挂载知识路由。

已发布的 `0.6.2` Maven 坐标与 tag 仍冻结；它们不是本版本的升级起点。见 [升级到 0.8.0](upgrading-to-0.8.0.md)。

## 当前结论

`0.8.0` 是全新部署基线：核心与 1024 知识各一份折叠 V001，RAG 固定 `zyblw_agent_knowledge.vector(1024)`。
业务部署从空库开始，使用精确 `0.8.0` 坐标。框架不提供从 0.6.x / 0.7.0 候选库的原地升级，也不提供旧知识
schema 或旧向量维度入口。

下面保留已发布 `0.6.x` / 作废 `0.7.0` 候选的历史边界，便于对照冻结制品，不是当前安装路径。

已发布 Maven 制品、tag 和 migration 永远不可变。`0.6.x` patch 必须保持本页定义的公共 Scala API、HTTP/schema、状态 JSON、
Maven 坐标与 1024 RAG 物理契约；任何删除 API、改变 wire/state 语义、向量维度或数据库基线的变化都必须进入新的 minor。

`0.7.0` 删除公共 `AgentQuickstart` 与无消费者的 `ConversationStore`，这是已记录的 early-semver minor 破坏。
官方接入改为 `ProductionSupportHost` / `AgentApplication.durable`。`ContentPart` 增加 `ImageArtifact`（digest/MIME/大小，
不含字节）；这是对穷尽匹配的源码破坏，旧 JSON 无该 case 仍可解码。Provider 出站图片必须先
`ArtifactBoundMedia.bind`，远程 `ImageUrl` fail-closed。

`0.7.0` 候选已扩展 `RunStore`/`HarnessStore`/`RunSubmissionStore` SPI，为 Goal/Plan/Todo JSON 增加具有空集合读取默认值的 ArtifactReference，并通过追加式 V008 扩展 PostgreSQL；H3-C 又以 V009 增加 `HarnessComparison` 评测 kind，H3-D 以 V010 增加 Goal 预算计数器与 Run reservation。`RunStartSubmission` 新增具有 `None` 默认值的可选 `GoalBudgetAdmission`；普通 Start wire/state 不变，Harness Start 则要求 Adapter 在同一事务预留预算。不支持该 admission 的自定义 Adapter 必须 fail-closed，不能忽略字段。同时收紧 `RunStore.save`、`appendEvents` 与跨批事件连续性语义，
并为 Experimental `WorkflowExecutionStore` 增加具有 typed-failure 默认实现的低敏 `wakeQueueSnapshot`。因此这些变化只能随下一
minor 发布，不能回填为 `0.6.x` patch。`AgentState` 当前 schemaVersion 从 4 依次提升到 5 和 6：v5 让新建 `DurableToolPlan`
冻结工具契约 SHA-256 与规划时审批要求；v6 进一步把审批要求从 callId 集合升级为 `ApprovalSubject`，并为 `ApprovalRequest`
增加 `subject`。v6 缺失完整契约指纹或审批主体时拒绝恢复，v5 及更早计划继续使用旧门禁。`RuntimeCompositionFingerprint`
新增具有空集合读取默认值的 `extensionIds`：旧 JSON 缺该字段仍可解码，并与空扩展组合判定 Compatible；非空扩展身份变化
为 Incompatible。新增 `executionEnvironmentId`（缺省 `local`）与 `permissionProfileFingerprint`（缺省视为宿主权限）：旧 JSON
缺字段仍与 Local 宿主组合 Compatible；换成 `mcp-sandbox` 或变宽权限为 Incompatible。`ApprovalSubject` 新增具有宿主缺省的
`permissions` 字段。新增字段有 JSON
默认值只为读取旧状态，不代表把 v6 损坏状态降级成旧状态。`ApprovalRequest.id` 现在包含主体摘要前缀，因此主体刷新后
控制面必须重新读取待审批请求，不能复用旧 `approvalId`。`AgentState` 新增具有空集合默认值的 `worldSectionCursors`：
旧 JSON 缺该字段仍可解码；游标只有 section 身份与指纹，不含正文。第三方 Store Adapter 必须通过共享 conformance 后再声明兼容。

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
