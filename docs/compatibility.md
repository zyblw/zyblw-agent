# 当前兼容性与版本边界

> 状态：0.9.0 全新安装基线
> 最后核验：2026-09-16

## 唯一安装基线

当前源码只支持 `0.9.0` 绿场安装。核心数据库只执行
`V001__zyblw_agent_0_9_baseline.sql`，1024 维知识库只执行
`V001__agent_knowledge_0_9_baseline.sql`。框架不提供旧 schema、旧向量维度、旧 Flyway history 或旧 Run 状态的转换入口；结构探针发现非当前基线时直接拒绝启动。

旧发布物只存在于不可变 Git tag 和制品仓库中，不参与当前构建、CI、发布评估或业务接入。当前开发线不设置历史 MiMa/version-policy 基线。`0.9.0` 正式发布后，后续 `0.9.x` 才以该版本为兼容基线。

## 当前公共契约

- Scala：十一项公开 Maven artifact 使用同一精确版本，禁止版本范围和 SNAPSHOT 进入生产。
- HTTP：稳定业务协议是 `/api/v1` 与 OpenAPI `1.2.0`；`/api/v1/admin/**` 是 Beta 管理面。`RunView.suspension` 为可选加法字段。
- State：`AgentState` schemaVersion 1，包含有界 citation、retrieval evidence 与 `suspension`。
- ModelCall：`lineage` 增加 compiler/layout 版本、稳定前缀数量与指纹、整体 plan 指纹；不保存 Prompt 正文。
- TokenUsage：`cachedInputTokens` 仍是 JSON 字段名（含义为 cache read），另加 `cacheWriteInputTokens`；硬预算使用逻辑 `inputTokens`。
- Database：核心、知识各有独立 schema/history；知识向量固定为 1024 维。`approval_requests` 归位为 `agent_suspensions`。
- Retrieval：支持 Hybrid、VectorOnly、LexicalOnly 与 Phrase，ACL 在打分和 fetch 前强制执行。
- Provider：业务只依赖 provider-neutral SPI；密钥只由宿主环境注入。

## 变更规则

当前仍使用 early SemVer。公共 Scala API、HTTP/wire、状态 JSON、数据库基线和 Maven 坐标是独立兼容面。已经发布的 tag、制品与 migration 永远不可修改；新的破坏性变化进入新的 minor 和新的空库基线。Beta/Experimental 能力必须明确标注，不能被文档描述为生产 GA。

## 发布验证

发布候选必须通过格式、全量测试、PostgreSQL/pgvector、故障恢复、soak、OpenAPI、Maven consumer、Dashboard 和真实业务宿主验证。平台接入只使用固定 commit 的 sibling 源码快照；公开制品往返只用于验证独立发布物完整性，不构成第二条业务运行路径。
