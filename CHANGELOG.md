# Changelog

## Unreleased — 首版 0.9.0 空库基线

本站与当前框架候选尚未发布。当前所有能力构成同一个首次发布候选，不提供旧候选升级或兼容读取路径。Git 历史中的候选记录不参与当前构建；远端既有 tag/制品不改写。

- Provider-neutral Scala 3/ZIO 2 Runtime，Functional Kernel + Driver；耐久命令、lease/fencing、取消、恢复、统一 Suspension、权限与工具审计。
- 分层 Prompt、上下文预算、受治理 Memory/RAG、模型目录/路由、冻结调用身份与单价。模型调用最低必要元数据可审计；正文采集可关闭。Token 输入包含缓存读写、输出包含推理，明细不能重复累加。
- PaddleOCR JSON/Markdown/PDF 配对、HTML 表格、章节层级、页码/几何与来源 SHA；document-structure-v4 按实际 tokenizer 约束完整 dense 文本。
- 独立不可变结构快照、Value Search、有界模型导航、确定性降级、可组合 Recipe/Strategy、跨书固定 Profile 与原文证据装配；结构摘要不能成为事实引用。
- `StructureOutline` 把整书目录渲染成带页码的紧凑大纲，超预算时先去提示再折叠深层且编号不变；`ModelStructureNavigation.outlineSelect` 让模型一次读完目录选章，只接受目录内节点。
- `KnowledgeProfileMigration` 把换切分器/Embedding 后的 active 语料分页重建到目标 Profile，语料一致且宿主评测通过后带普查 CAS 切换，否则旧 Profile 继续服务；`KnowledgeIndexStore` 新增 `spaceProfileState`、`profileManifests` 只读查询。
- 核心、知识与共享扩展分别使用 `zyblw_agent_core`、`zyblw_agent_knowledge`、`zyblw_extensions`；运行 SQL/探针显式限定，移除 shared-public baseline 特例。旧候选须备份后显式重建。
- 核心与 1024 维知识各唯一 V001，结构检索直接包含在知识 V001；中文数据字典与启动结构探针，无历史 V002/旧 schema 迁移。
- HTTP v1 初始 OpenAPI 1.0.0 单快照；十一项 Maven artifact 同一精确版本。独立消费者、PostgreSQL、确定性评测及公开 PDF/RAG 门禁。
- 实际平台问答使用同源框架结构装配；多分支使用 RagApplication.querySession，业务切块参数通过 DocumentStructureChunker.aligned(config) 落实。
- ModelCallExecutionRecord 记录冻结 ModelPrice、价目摘要与 usageReporting；内存和 PostgreSQL 转移拒绝改写历史单价。
- ModelPrice 可保留原币报价、来源身份与汇率依据；ModelPolicySource.pricesFor 和 LiveComposition.freezeFor 允许宿主按冻结指纹恢复旧路由价目，未找到旧版本仍拒绝漂移。
- 有费用上限的直连模型复用路由预算预检，发送前补齐输出上限并拒绝预计 token/费用越界，避免仅在响应后发现超支；主调用预算按最高输入分类价格准入，覆盖更贵的缓存写入。
- 费用上限下直连 Disabled 保留 MetadataOnly 账本；缺失用量的付费调用阻止后续工具/模型动作，崩溃恢复读取同一账本门禁。
- ChatResponse.usageReported 区分真实零与缺失用量；内建非流式与流式适配器按响应事实设置，模型账本缺用量保留 None。
- 宿主管理的模型注册表：`ModelConnectionSpec`/`ModelRegistrySnapshot`/`ModelRegistrySource` 用值（而非环境变量）描述连接；`ChatAdapterFactory` 装配四种 wire 协议，`LiveModelRegistry` 按版本增量重建路由（换 Key/URL 只重建该连接），`ModelCatalogLive.live` 与 `ModelAdminLive.live` 随版本投影目录与探活，`probeSpec` 可探测尚未保存的连接。`OpenAICompatibleConfig.extraHeaders` 支持聚合平台的非机密请求头；明文 HTTP 允许 `localhost`/`host.docker.internal`。
- `ModelDiscovery` 读取 OpenRouter `/models`（能力、上下文、每百万 token USD 单价）与任意 OpenAI-compatible `/models`。
- `ModelRoleSource`：`AgentCommandServiceLive.configuredWithRoles`、`HarnessCommandServiceLive.configuredWithRoles`、`AgentRuntimeDriver.layerWithRoleSource`、`AgentApplication.durableGovernedWithRoles` 在每次创建 Run 时读取角色目录，结果仍冻结进组合指纹。
- `ProfileEmbeddingRouter`：`DefaultRetriever(profileEmbeddings = …)` 在解析 pinned/active Profile 之后选择查询向量模型，换 Embedding 的蓝绿切换期间旧 Profile 继续可查，目标 Profile 验收探测使用新模型。
- 工具链对齐 2026-10-05 已验证最新稳定线：sbt 2.0.10、Flyway 13.9.0、Tika 4.1.0、示例模块 HikariCP 7.1.0。ZIO 2.1.26、ZIO HTTP 3.11.6、zio-schema 1.8.7 与 zio-json 1.0.0 已是这条 HTTP 官方依赖线的最新组合；Scala 留在 3.9.0 LTS。Loader 身份改为 `apache-tika-4.1.0`。

能力成熟度依 canonical roadmap。首次空库构建通过不代表真实领域模型、容量、soak 或生产发布已经验收。首版公开发布后才冻结 V001 与兼容基线。
