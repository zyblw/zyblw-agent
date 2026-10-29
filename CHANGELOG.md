# Changelog

## Unreleased

- 挂起超期耐久恢复与检索/清理闭环：`SuspensionExpiryWorker` 在 `SuspensionExpiry.ResumeWithDefault` 超期时不再于后台直接改写 `AgentState`，改为向 `RunCommandStore` 投递幂等 `RunCommandPayload.Recover` 命令，由 `AgentRuntimeDriver.recoverPending` 在排他命令租约（fencing lease）下原子应用 `AgentKernel.suspensionExpiry` 默认值并继续推演；同时补齐 `SuspensionExpiryWorker.layer`；`DefaultRetriever` 将 `RetrievalMode.LexicalOnly` 与 `RetrievalMode.Phrase` 从前置 `EmbeddingModel.embed` 调用中解耦，使向量模型不可用时纯词法/短语检索继续可用；`KnowledgeRetentionWorker` 新增有界 `runCycle`、`run`、`startScoped` 与 `ZLayer` 装配，在保留期内保护 `legalHold` 文档。
- 长期记忆删除屏障与并发安全闭环：新增 `MemoryDeletionBarrier(scopeGeneration, keyVersions, deletedAtEpochMilli, activeVersions)` 与 4 参数原子接口 `MemoryStore.compareAndSet(scope, expectedVersion, entry, barrier)`；`PostgresMemoryStore` 通过保留行 `__scope_barrier__` 与 `pg_advisory_xact_lock` 在同一数据库事务内原子复核代次与单键历史删除时间；`MemoryStore.ScopeBarrierKey` 全入口禁止业务读写；4 参数默认兼容路径仅在确认刚写入版本仍为当前活跃版本且未被更新墓碑超越时才执行补偿删除。
- 检索上下文裁剪对齐与中途撤回全载体清洗：`KnowledgeTools.SearchOutput` 新增 `excerptSources` 为每条摘录（含扩展邻块）绑定 `documentId/chunkId/sourceUri`；`DefaultContextManager.scrubWithdrawnJson` 按来源成对清洗 `excerpts`、`citations` 与 `results` 并重算 `acceptedCount`；`ContextSummaryCheckpoint` 记录 `sourceDocumentIds`，当存在撤回文档时自动失效旧摘要并从已清洗历史重算；`PreparedContext.deliveredDocumentDisplayIds` 与 `ContextAssembler.deliveredSources` 共享 `ContextDocument.displayIdFor` / `alignCitationId` 映射，确保纯数字、`cite-N`、`evidence-N` 在模型提示词与持久引用间严格一致。
- 运行时取证门禁与跨崩溃活跃耗时核算：`AgentRuntimeDriver.hasDeliveredToolEvidence` 要求非错误工具消息显式满足 `status == "ok"` 且包含有效引用/结果项；`recoverRunning` 在写入 `RunResumed` 前若存在崩溃前活跃账本时间戳则先行追加 `UsageUpdated` 闭合崩溃前活跃窗口，`consumedActiveMillis` 按 `(callId, attempt)` 完整核算多次崩溃重放下的已消耗活跃时长。
- 工具 Schema 严密预检与输出脱敏顺序：`JsonSchemaInstanceValidator` 在入口递归执行 `validateSchemaSupport`，任何嵌套于 `not`/`anyOf`/`oneOf`/`allOf` 内的未知关键字一律 Fail-Closed，并补齐 `allOf` 显式 `null` 识别；`RegisteredTool.make` 先对 `encodeRawOutput` 原始输出执行 `outputSchema` 校验再执行 `ToolMetadata.redactJson` 脱敏；`McpRegisteredTool` 新增 `redactMcpInlineEnvelope`，将 `content[].text` 与脱敏后的 `structuredContent` 同步重写，缺少 `structuredContent` 且 `content` 非结构化 JSON 对象时对声明敏感字段的工具直接 Fail-Closed。
- 结构化观测日志注解：`RunObserver.logging` 与 `Telemetry` 统一注入 `run_id`、`session_id`、`component`、`operation`、`error_kind` 等结构化 `LogAnnotation`，并对含空白、引号、等号或逗号的值做安全转义。
- 工具执行与审批前同时核对 Agent 和当前部署白名单、拒绝名单；收窄执行器不再扩大权限，下一次调用使用最新超时与结果上限；未获准工具在并发规划中自动降级为 `SequentialOnly` 单例批次，`WaitingForApproval` 挂起期间被策略撤权的工具收到 `Approve` 后直接进入门禁拒绝（`missingPermission`），不触发无效的二次人工审批。
- 最终答案通过模型结算但被输出护栏拒绝时，耐久 Run 收口为 Failed，保留完成提交失败后的恢复能力。
- 本地工作区 `LocalWorkspace` 原子写入时在 `list` 与 `totalBytes()` 中过滤 `.zyblw-write-*.tmp` 临时文件并加锁同步；`WorkspaceEdit` 支持安全行范围读取、统一 Diff 预览、精确出现次数替换与 UTF-8 BOM 保护。
- 管理台切换后端或凭据时重建查询缓存与本地投影，连接抽屉打开与清除凭据时同步重置草稿输入，宿主会话固定使用本站 BFF；连接统一保存，手机改用紧凑模块导航。

## 0.10.0 - 2026-10-23

`0.10.0` 是稳定演进安装基线，包含自 `0.9.0` 发布以来的全部架构加固与新特性。核心与 1024 知识各有一份 V001 基线（含结构检索与大纲快照）。执行内核为 `AgentKernel` + `AgentRuntimeDriver`。稳定业务 HTTP 契约维持 OpenAPI `1.0.0`。全部十一项 Maven 制品保持同一精确版本发布。

- 记忆治理与四大认知支柱演进：`MemoryGovernance` 与 `MemoryLifecycle` 支持 `expectedVersion = 0` 的用户显式新建（`UserStated`）及删除墓碑（tombstone）后的显式重建，`Episodic` 记忆缺省自动补齐 30 天安全 TTL；用户手动锁定记忆（`user-correction-v1`）免受后台 `ModelInferred` 覆盖，同等级推断认知可随时间演进，迟到 `ModelInferred` 遇到 tombstone 静默跳过而不中断同批其他候选。`LlmMemoryExtractor` 升级四大认知维度（`preference` / `semantic` / `procedural` / `episodic`）提炼规约与医疗隐私隔离边界。
- 运行时取证韧性与持久层事务加固：`AgentRuntimeDriver` 与 `RunTerminator` 在已成功获取有效检索证据/引用但后续生成模型连接或超时失败时支持优雅降级（`degradeModelFailureWithEvidence`），保留已核验引用而不丢弃整轮证据；`PostgresMemoryStore`、`PostgresWorkflowCheckpointStore`、`PostgresRunCommandStore`、`PostgresRunSubmissionStore` 统一使用 `ZIO.uninterruptibleMask` 保护事务，确保 Fiber 中断、缺陷或 `commit` 失败时强制先 `rollback` 再恢复原始 `autoCommit`，消除 JDBC `setAutoCommit(true)` 隐式提交半截事务的风险。
- AIHubMix 渠道协议与富目录对齐：`OpenAICompatibleConfig` 对 `aihubmix.com` 端点自动注入 `APP-Code: PXLD0553` 请求头（覆盖对话、流式、向量与模型发现），`ModelDiscovery` 支持解析 AIHubMix 富目录单价与能力元数据。

- 工具协议加固：补齐知识检索数组元素类型；发网前校验可移植 Schema 与 strict 硬约束；所有适配器和 Runtime 验证工具身份/参数，旧非法计划恢复收口为 Failed。OpenAI-compatible 忽略正常空身份分片、拒绝身份冲突，保留通用中转 reasoning_content 与按 call id 关联的 Gemini opaque extra_content。ModelProbeConfig 增加可选 ToolRoundTrip 模式，覆盖流式工具调用与结果回填；默认基本探活不变。
- 注入监测补齐 Unicode 空白归一化，并固定 Locale.ROOT 大小写转换，避免宿主区域设置影响现有词表；不扩大匹配词或做 NFKC 转换。
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
- 修复 Gemini Interactions 思考 token 漏计、OpenAI 兼容缓存写入漏计；普通/SSE 共享校验并拒绝无效计数及别名冲突。免费细分项不能用缺失数量补零；分摊缺口以 BillingUsage.issues 保留，旧快照仍可读。
- 可选 BillingTariff 提供互斥上下文/执行条件与多计量项、原币规则证据、确定性费用明细和 typed 待计价原因；TokenUsage.billing 保存调用事实，旧 JSON 仍可读。缓存/音频分类避免重复收费；预算预检使用保守上界，缺失额外收费上界拒绝准入。普通/SSE 适配保留服务等级、缓存 TTL 和已报告的音频/搜索计量；该能力仍是成本估算 Beta，不是供应商账单结算。
- ModelPrice 可保留原币报价、来源身份与汇率依据；ModelPolicySource.pricesFor 和 LiveComposition.freezeFor 允许宿主按冻结指纹恢复旧路由价目，未找到旧版本仍拒绝漂移。
- 有费用上限的直连模型复用路由预算预检，发送前补齐输出上限并拒绝预计 token/费用越界，避免仅在响应后发现超支；主调用预算按最高输入分类价格准入，覆盖更贵的缓存写入。
- 费用上限下直连 Disabled 保留 MetadataOnly 账本；缺失用量的付费调用阻止后续工具/模型动作，崩溃恢复读取同一账本门禁。
- ChatResponse.usageReported 区分真实零与缺失用量；内建非流式与流式适配器按响应事实设置，模型账本缺用量保留 None。
- 宿主管理的模型注册表：`ModelConnectionSpec`/`ModelRegistrySnapshot`/`ModelRegistrySource` 用值（而非环境变量）描述连接；`ChatAdapterFactory` 装配四种 wire 协议，`LiveModelRegistry` 按版本增量重建路由（换 Key/URL 只重建该连接），`ModelCatalogLive.live` 与 `ModelAdminLive.live` 随版本投影目录与探活，`probeSpec` 可探测尚未保存的连接。`OpenAICompatibleConfig.extraHeaders` 支持聚合平台的非机密请求头；明文 HTTP 允许 `localhost`/`host.docker.internal`。
- `ModelDiscovery` 读取 OpenRouter `/models`（能力、上下文、每百万 token USD 单价）与任意 OpenAI-compatible `/models`。
- `ModelRoleSource`：`AgentCommandServiceLive.configuredWithRoles`、`HarnessCommandServiceLive.configuredWithRoles`、`AgentRuntimeDriver.layerWithRoleSource`、`AgentApplication.durableGovernedWithRoles` 在每次创建 Run 时读取角色目录，结果仍冻结进组合指纹。
- `ProfileEmbeddingRouter`：`DefaultRetriever(profileEmbeddings = …)` 在解析 pinned/active Profile 之后选择查询向量模型，换 Embedding 的蓝绿切换期间旧 Profile 继续可查，目标 Profile 验收探测使用新模型。
- 工具链对齐 2026-10-05 已验证最新稳定线：sbt 2.0.10、Flyway 13.9.0、Tika 4.1.0、示例模块 HikariCP 7.1.0。ZIO 2.1.26、ZIO HTTP 3.11.6、zio-schema 1.8.7 与 zio-json 1.0.0 已是这条 HTTP 官方依赖线的最新组合；Scala 留在 3.9.0 LTS。Loader 身份改为 `apache-tika-4.1.0`。
- `ModelCallExecutionRecord.providerRequestId` 保存通过长度与控制字符检查的供应商请求号。旧账本缺该字段时为空，不用本地 requestId 代替。
- 管理面在装配 `AdminCapabilities.commands` 后暴露 `capabilities.runControl`，以及 `POST /api/v1/admin/runs/{runId}/approval`（`agent:admin:write`）。请求体是 `approve` 或 `reject`，驳回可带原因；成功返回 `202` 与命令回执。未装配时路由不存在。
- 控制台改为左侧栏加工作区。页签仍由 capabilities 决定。运行检查页只在 `runControl` 为真时提交审批。
- `AgentTransition`、`AgentHandoffEnvelope` 与 `HandoffPolicy` 是核心里的交接类型。内核步进仍走现有 `Transition`，宿主不能把该枚举当成已经执行的多智能体交接。
- `knowledge_outline` 可注入 `RagApplication`，查询前固定当前知识 Profile，避免持久化结构已发布却返回 not_found；保留宿主文档范围和 ACL 校验。
- `knowledge_search` / `knowledge_fetch` 在未命中或证据不足时返回 `guidance`。`knowledge_fetch` 可设 `expandNeighbors` 再读至多 4 个相邻块。`knowledge_outline` 读取已授权文档的结构目录，不进入默认 `KnowledgeTools.layer`，也不产生 chunk 引用。
- `DeterministicQueryPlanner` 结构化对比检索增强（`comparisonParts` 支持「对比/比较 A 与/和 B 的异同/区别」等复合谓词），`DefaultRetriever` 对比题仅对主路径执行 assist 改写并保留对偶分支，触发 Hybrid + Phrase 精确短语双重召回，杜绝单侧证据静默丢失；`AuthorizedDocumentDiscovery` 扫描达到上限时保留已扫描文档并记录告警。
- OpenAI 兼容协议优化：`OpenAICompatibility.relay` 将 `developer` 角色消息映射为 `system` 角色，增强通用中转站兼容性；`OpenAICompatibleConfig` 自动识别 4sAPI 根端点并规整为 `/v1`。
- `BookCorpusRagEval` 增加伤寒、金匮、神农本草经比对测试用例与对比阈值，全面通过确定性离线回归。

能力成熟度依 canonical roadmap。首次空库构建通过不代表真实领域模型、容量、soak 或生产发布已经验收。首版公开发布后才冻结 V001 与兼容基线。
