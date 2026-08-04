# 兼容性契约与版本边界

> 状态：0.4.0 发布候选契约
> 最后核验：2026-08-02
> 事实来源：`build.sbt`、公共源码、HTTP Schema、数据库 baseline、测试与发布工作流

## 当前结论

`0.4.0` 是 RAG/document-loading 的下一 minor 基线。它保持 0.3.0 的 Agent Runtime、耐久命令、HTTP v1、Workflow
state/outcome 与核心 PostgreSQL 控制面契约，但有意改变尚处于 Beta 的 RAG Scala 类型和知识索引物理结构。已经发布的 Maven
制品、Git 标签和 migration 永远保持不可变；0.3 使用者可以留在 0.3.x，采用新 RAG 契约的应用必须显式升级到 0.4。

`0.4.0` 发布后，`0.4.x` patch 保护本页定义的公共表面。下一次需要删除公共 API、改变 wire/state 语义或重建数据库基线时，必须提升
minor，不能伪装成 patch。

## 0.4.0 发布边界

| 表面 | 0.4.0 基线 | 后续 0.4.x patch 承诺 |
|---|---|---|
| Agent/Core Scala API | 延续 0.3.0 Runtime、Tool、权限、命令和应用装配语义 | 不删除或改变公开签名语义 |
| RAG Scala API | `SourceDocument`、`DocumentChunk`、`Citation` 增加结构化 provenance/lineage；自定义 Loader/Chunker 需重编译 | 不删除字段或改变身份、ACL、谱系语义 |
| Maven 坐标/POM | 保持 11 个有真实依赖边界的 artifact | 坐标保持，依赖只做兼容升级 |
| HTTP | 继续使用 `/api/v1`；0.4 RAG 结构没有被泄漏为 HTTP wire schema | 已发布路径与 wire 字段兼容 |
| State/outcome JSON | 继续使用 0.3 Workflow outcome v2 | 新字段必须有安全默认读取语义 |
| Workflow | 延续 durable wait/signal/wakeup、fencing 与原子提交 | identity、唯一唤醒和原子提交不得弱化 |
| 核心 PostgreSQL | `public`/宿主默认 schema 中冻结的 0.3 V001 加 repeatable 中文 catalog 注释 | 已发布 V001 不修改；只追加 migration |
| 0.4 知识 PostgreSQL | 独立 `zyblw_agent_knowledge` schema、独立 history、单一 fresh V001、固定 1536 维 | V001 发布后冻结；patch 只追加 migration |
| RAG 派生数据 | 文档 manifest、staging、active read model、FTS/vector 与结构谱系可由原始文档重建 | source identity、ACL、撤回、复合 chunk 身份与 lineage 语义稳定 |

## 数据库采用契约

核心控制面继续由 `V001__zyblw_agent_0_3_baseline.sql` 管理，不需要因为 0.4 重建。0.4 知识索引使用新的 classpath location，
Flyway 固定管理 `zyblw_agent_knowledge` schema，并把 `flyway_zyblw_agent_knowledge_1536_history` 放在该 schema。这样两套
V001 不共用 history，也不让两套 Flyway 生命周期共同管理非空 `public` schema。

0.3 的旧知识表是可重建派生数据，不原地改表或修改已发布脚本。采用 0.4 时应保留权威 PDF/Markdown、来源 ID、ACL 和内容 hash，执行新知识
baseline 后重新摄取；确认引用、权限过滤和召回评测通过后，再按业务审批清理旧派生表。禁止用 `repair`、删除 history、伪造 checksum 或
`baselineOnMigrate` 掩盖未知结构。

完整步骤见[升级到 0.4.0](upgrading-to-0.4.0.md)。

## Durable Workflow 不变量

`WorkflowExecutionStore.commit` 是一个事务语义：Prepared execution、checkpoint、旧 wait 消费和新 wait 注册必须全部成功或全部
回滚。外部 signal 使用 `(waitKey, signalId)` 去重；相同 ID 不同 payload 冲突。PostgreSQL 使用数据库时钟判断 deadline，达到
deadline 后 timeout 胜出。恢复只消费由 owner/token/generation/expiry 完整 fence 领取的 Signaled/TimedOut；Pending 不允许执行
节点，已决议 wait 也不能通过普通 `resume` 绕过 claim。wait 行本身是 durable wake command，消费与下一 checkpoint 原子提交。

这些保证不自动让节点内部第三方副作用 exactly-once；支付、发信等写操作仍需业务幂等键或 outbox/inbox。

## 发布门禁

`0.4.0` 发布前必须同时通过：格式检查、完整 `testFull`、真实 PostgreSQL 16/pgvector、核心后知识的组合迁移、重复启动、
`publishM2`、独立 Maven consumer、HTTP/OpenAPI 契约和 RAG 摄取/发布/检索/ACL/lineage 测试。发布标签必须来自远端 `main`，且
CHANGELOG 与 `docs/upgrading-to-0.4.0.md` 一致。
