# 当前兼容性与版本边界

> 状态：0.9.0 全新安装基线
> 最后核验：2026-09-23
>
> 2026-09-23 对照：现行安装是 0.9 空库（核心与 1024 知识各一份 V001）。执行内核是 `AgentKernel` + `AgentRuntimeDriver`。Memory、RAG 与摘要走 User envelope。等待使用 `Suspension`。稳定 HTTP 是 OpenAPI `1.2.0`。本页不提升 Experimental 能力的成熟度。
>

## 已验证工具链矩阵

| 组件 | 当前基线 | 选择原则 |
|---|---:|---|
| JDK | 21 LTS | 生产字节码与运行时基线；开发机更高 JDK 不改变目标版本 |
| Scala | 3.9.0 LTS | 当前 Scala 3 LTS 线 |
| sbt | 2.0.1 | 仓库 wrapper/构建基线 |
| ZIO | 2.1.26 | 当前稳定线 |
| ZIO HTTP | 3.11.6 | 当前稳定线，与 ZIO 2.1.26 对齐 |
| zio-schema / zio-json | 1.8.7 / 1.0.0 | schema-json 1.8.7 按 zio-json 1.0.0 编译；不可覆盖到 1.1.0，否则 Endpoint 解码会 NoSuchMethodError |
| PostgreSQL / pgvector | 18.6 / 0.8.6 | 真实 Testcontainers 与本地 Compose 基线 |
| JDBC / Flyway | 42.7.13 / 13.7.0 | 当前数据库门禁组合 |
| Apache Tika | 4.0.0 | 文档 Loader 已迁移到 Tika 4 API |
| OpenTelemetry | 1.66.0 | SDK/Exporter 统一版本 |
| Dashboard | Node 26.8.1 / npm 11.19.1 / Next 16.3.5 / React 19.3.0 / TypeScript 6.0.3 | 独立运维控制台；完整依赖树、类型、lint 与生产构建验证 |

“最新”表示最新稳定且被这张组合矩阵验证，不表示把每个传递依赖单独升到最高数字。升级任一核心组件必须同时通过编译、
确定性全量测试、真实 PostgreSQL/pgvector、公开 PDF/RAG、独立 Maven consumer 和宿主平台门禁，不能只依赖依赖解析成功。
公开 PDF/RAG 由 `./scripts/test-public-pdf-rag.sh` 单独执行；当前不在普通 CI 或 `verify-business-ready.sh` 内，发布前必须按
`docs/releasing.md` 留存该项结果。

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
- ModelSettings：新增带默认值的 `reasoningEffort`；它会进入组合指纹。显式设置但模型未在 `reasoningEfforts` 中声明支持时 fail closed。
- Database：核心、知识各有独立 schema/history；知识向量固定为 1024 维。`approval_requests` 归位为 `agent_suspensions`。
- Retrieval：支持 Hybrid、VectorOnly、LexicalOnly 与 Phrase，ACL 在打分和 fetch 前强制执行。
- Provider：业务只依赖 provider-neutral SPI；密钥只由宿主环境注入。

## 变更规则

当前仍使用 early SemVer。公共 Scala API、HTTP/wire、状态 JSON、数据库基线和 Maven 坐标是独立兼容面。已经发布的 tag、制品与 migration 永远不可修改；新的破坏性变化进入新的 minor 和新的空库基线。Beta/Experimental 能力必须明确标注，不能被文档描述为生产 GA。

## 发布验证

发布候选必须通过格式、全量测试、PostgreSQL/pgvector、故障恢复、soak、OpenAPI、Maven consumer、Dashboard 和真实业务宿主验证。平台接入只使用固定 commit 的 sibling 源码快照；公开制品往返只用于验证独立发布物完整性，不构成第二条业务运行路径。
