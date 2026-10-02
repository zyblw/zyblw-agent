# Changelog

## Unreleased — 首版 0.9.0 空库基线

本站与当前框架候选尚未发布。当前所有能力构成同一个首次发布候选，不提供旧候选升级或兼容读取路径。Git 历史中的候选记录不参与当前构建；远端既有 tag/制品不改写。

- Provider-neutral Scala 3/ZIO 2 Runtime，Functional Kernel + Driver；耐久命令、lease/fencing、取消、恢复、统一 Suspension、权限与工具审计。
- 分层 Prompt、上下文预算、受治理 Memory/RAG、模型目录/路由、冻结调用身份与单价。模型调用最低必要元数据可审计；正文采集可关闭。Token 输入包含缓存读写、输出包含推理，明细不能重复累加。
- PaddleOCR JSON/Markdown/PDF 配对、HTML 表格、章节层级、页码/几何与来源 SHA；document-structure-v4 按实际 tokenizer 约束完整 dense 文本。
- 独立不可变结构快照、Value Search、有界模型导航、确定性降级、可组合 Recipe/Strategy、跨书固定 Profile 与原文证据装配；结构摘要不能成为事实引用。
- 核心与 1024 维知识各唯一 V001，结构检索直接包含在知识 V001；中文数据字典与启动结构探针，无历史 V002/旧 schema 迁移。
- HTTP v1 初始 OpenAPI 1.0.0 单快照；十一项 Maven artifact 同一精确版本。独立消费者、PostgreSQL、确定性评测及公开 PDF/RAG 门禁。
- 实际平台问答使用同源框架结构装配；多分支使用 RagApplication.querySession，业务切块参数通过 DocumentStructureChunker.aligned(config) 落实。
- ModelCallExecutionRecord 记录冻结 ModelPrice、价目摘要与 usageReporting；内存和 PostgreSQL 转移拒绝改写历史单价。
- ModelPrice 可保留原币报价、来源身份与汇率依据；ModelPolicySource.pricesFor 和 LiveComposition.freezeFor 允许宿主按冻结指纹恢复旧路由价目，未找到旧版本仍拒绝漂移。
- 有费用上限的直连模型复用路由预算预检，发送前补齐输出上限并拒绝预计 token/费用越界，避免仅在响应后发现超支；主调用预算按最高输入分类价格准入，覆盖更贵的缓存写入。
- 费用上限下直连 Disabled 保留 MetadataOnly 账本；缺失用量的付费调用阻止后续工具/模型动作，崩溃恢复读取同一账本门禁。
- ChatResponse.usageReported 区分真实零与缺失用量；内建非流式与流式适配器按响应事实设置，模型账本缺用量保留 None。

能力成熟度依 canonical roadmap。首次空库构建通过不代表真实领域模型、容量、soak 或生产发布已经验收。首版公开发布后才冻结 V001 与兼容基线。
