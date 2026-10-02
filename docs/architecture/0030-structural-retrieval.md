# ADR-0030：结构检索与原文证据共用 RAG 主线

状态：结构检索闭环已实现（确定性/数据库契约已验证）（Experimental，未发布）；最后核验：2026-10-01。

## 完善实施契约（2026-10-01）

用户授权按审查结果贯通摄取、结构索引与问答。范围：Paddle JSON/Markdown 配对加载、
页码/块身份/HTML 表格保真与完整性、真实 token 切分、摘要规格/缓存/有界构建、
受限树导航与降级、配方/自适应路由、跨书范围/快照/公平证据装配、HTTP/工具参数、
参考宿主和端到端回归。复用当前 Source/Loader/Indexer/Retriever/ChatModel/Store，
不增加 artifact，不修改已发布 V001，不接入另一套 RAG 或自由代理循环。

验收：配对产物从受限目录进入 PostgreSQL，章节/Block/Chunk 映射完整；页码与 bbox
可追溯，表格行列保留；所有 dense chunk 满足声明 tokenizer 上限；树预算不能突破；
所有召回/跨书/fetch/扩展遵守 tenant、文档范围、精确 Profile；引用由同一 EvidenceBundle
装配；缺能力和错误可观测，取消传播；模型摘要仅导航，不能成为事实引用。
使用确定性 ChatModel 验证模型适配与恶意输出，不把它当作真实模型效果证明。

兼容：新增参数有默认值，wire 为可选加法；未发布结构 API 允许收口，全部当前 API 合入首版，消费者按首版源码编译。数据库合入首版 V001。回退：关闭结构索引/检索或选择 Classic。
验证：相关模块测试与 examples 编译、全仓格式/testFull、真实 PostgreSQL 契约、
本地 publishM2 与独立 Maven consumer；真实领域金标/模型与生产容量另行记录，不宣称 100% 生产成熟。

## 目标与现状

吸收 PageIndex 的章节树、节点值搜索与有界导航；保留现有 PaddleOCR、Chunker、Embedding、
pgvector、ACL、Reranker、EvidenceBundle。`PaddleOcrVlDocument.Parsed` 的章节现已进入通用 `DocumentStructure`，
`DefaultRetriever` 在固定 Chunk Profile 后执行可选节点候选/原文物化阶段。

## 平台业务接入

实际 `zyblw-platform` 的 `/ask` 装配现在复用同一个 PostgresStructureStore、结构规格和 BookGrounded 配方；书籍审校载荷的 sections 经 loader 进入框架结构，原文扩展经跨分支融合重新按预算装配。新增 `RagApplication.querySession(scope)` 将 Profile 固定和检索缓存交给多分支业务查询；`DocumentStructureChunker.aligned(config)` 将业务切块配置与实际 tokenizer 对齐，原 alignedLayer 保持可用。

平台以同一 Paddle/审校/search/fetch/撤回场景在内存与真实 PostgreSQL 验证，并执行后端全量契约。管理员重建仅允许未修改的已发布书籍，保持既有 manifest 来源身份；草稿变更须走原审校发布边界。此业务接入仍在本地工作树，未更新生产 pin；不代表真实模型、浏览器或容量验收通过。

## 本次实施契约

- `DocumentSection` 是原书事实，加入通用结构和结构摘要。旧的三参数构造仍可使用；
  case class 形状变化需要消费者重新编译，不能宣称二进制兼容补丁。
- 独立 `StructureBuildSpec` 决定结构 Profile。不可变结构快照绑定 tenant/space/document、
  chunk profile/version、来源修订、结构摘要和 Chunk 集合摘要；调整结构规格不调用 Embedding。
- 先保存完整结构产物，再发布 Chunk；只有对应文档 Ready/active 且摘要吻合才可读。
  Disabled 为默认；Required 失败阻止新版本发布；BestEffort 的失败不阻止经典检索，显式报告状态。
- 第一版通过宿主固定的结构规格和精确 Chunk 版本读取不可变快照，避免增加一个可漂移的
  active-tree 指针。一次查询载入后只使用该快照；不实现全空间双指针热切换。
- 节点只保存章节、Block 引用和 Chunk 映射，不重复保存整段原文或向量。Canonical 章节不合并。
  无可靠章节/映射时明确不可用，不伪造“完整目录”。
- Value Search 从已授权经典候选聚合节点，随后在节点映射范围重新执行原检索模式。
  节点值只排序节点，不能冒充 cosine 或证据分数；所有新候选必须重新验证版本、ACL 和 filter。
- 结构检索与经典检索使用同一融合、重排、阈值、上下文预算、引用路径；通用构造默认关闭，Knowledge QA 参考宿主已接通并默认使用 BookGrounded。
  输入、候选、节点、映射和时间均有界；错误降级可观测，中断不能吞掉。
- PostgreSQL 结构表合入首版 V001；结构数据以复合外键归属原 manifest，清理级联。
  读取每次检查原 manifest，撤回、下线和新版本替换立即阻止旧结构继续服务。

## 范围与验收

完成通用章节、确定性树/多对多映射/质量验证、独立结构存储、摄入与检索接入、
PostgreSQL 适配、可执行示例与确定性回归。可选高级导航必须先受相同快照和证据边界约束。
不引入 PageIndex Python 服务、新向量数据库、GraphRAG、MCTS 或多 Agent。
真实领域问题的质量增益、生产容量与 OCR 效果须另有金标证据，不能由单测推断。

验收覆盖：默认行为；章节身份变化；环/悬空父节点；映射缺失；跨租户/空间/Profile/版本；
重复发布和冲突；结构构建失败；撤回；限额、超时、中断；结构定位后原文引用。
验证命令：`sbt -batch 'rag/testFull; documentLoaders/testFull; postgres/Test/compile; examples/compile'`；
真实库：`RUN_POSTGRES_INTEGRATION=1 sbt -batch 'postgres/testOnly *Structure*'`；
格式：对应模块 `scalafmt` / `scalafmtCheck`。以实际任务名与执行结果为准。

## 回退

关闭结构 publication/检索配置恢复经典链路；保留结构表和数据不会改变旧查询。
结构规格改变发布新快照；不覆盖同一身份下内容。源码修改尚未发布，不提升 RAG 成熟度。

参考：[PageIndex Hybrid Tree Search](https://docs.pageindex.ai/tutorials/tree-search/hybrid)。
官方说明纯摘要导航可能丢失细节且增加延迟；因此本框架先以 Value Search 验证结构收益。

## 实现结果与方案取舍

本次完成两份建议的第一、第二阶段：Canonical Section → 不可变结构产物 →
Node/Block/Chunk 映射 → Value Search → 节点内原文检索 → RRF → 原有 Rerank/EvidenceBundle。
`PaddleOcrVlDocument.Parsed.structure/toSourceDocument` 保留解码器恢复的层级；
Loader 注册表对新增章节实施数量与字段上限。未声明章节的旧 Loader 继续支持经典检索，
不根据同名标题猜测树身份。

与草案的四张新增表和空间双 active 指针不同，当前采用一张有复合外键的
`agent_knowledge_structures` 保存不可变、最多 16 MiB 的 JSONB 产物。
节点只存章节和直接归属的 Block/Chunk ID；父子边保留，正文与向量不复制。
配置显式固定 StructureBuildSpec，查询按已钉住的 Chunk Profile/文档版本读取。
这已经消除了查询中切树、旧树引用新块的问题，不需要为尚无在线树编辑的系统增加另一套
空间级发布状态机。将来需要空间级结构 Profile 灰度或百万节点随机导航时，再引入关系化
节点表和双指针 CAS；届时必须保留这里的 binding、不可变代次和证据边界。

`StructureBuilder` 的质量门是确定性的：节点和顺序唯一、父节点存在、层级和阅读顺序递增、
父子页码包含、每个正文 Block 有章节归属、每个 Chunk 有已知 Block、所有 Block 被映射。
严格递增的层级/顺序同时排除了环；多对多映射允许一个 Chunk 包含不同章节的 Block。
文档内的无标题前言需要解析器给出可信容器；当前不自动捏造章节来让覆盖率好看。

Value Search 按已有命中的名次贡献聚合节点，并按节点映射块数归一化。
候选节点只从经典分支发现的文档产生；因此**不能找回经典检索完全遗漏的整本文档**。
节点内重新调用原 mode 的 `searchFiltered`，强制 tenant、space、profile、版本、原始 filter
与节点 Chunk 集合同时成立。节点大于 `maxChunksPerNode` 时保留已有种子并标记
`structure-budget`，不按阅读顺序截断到前 N 块后伪称完整搜索。
跨分支采用 RRF，原始 dense/lexical/phrase 信号仍决定证据接受；RRF 不被当作概率。

Required 在付费 Embedding 前完成结构质量门和暂存；只有原 manifest Ready/active 且
Chunk digest 一致时结构可读。重试已 Ready 的摄入可以补建新的结构规格，不重复调用
Embedding。BestEffort 返回 `Unavailable`，查询缺树/超时返回 `structure-fallback`；
`failOpen=false` 可要求查询失败。取消继续传播，不转成成功的降级结果。

检索诊断通过 `RetrievalDiagnostics.structureSelections` 记录 documentId、generation、
nodeId、materializedCount；`structure_search` stage 记录耗时与候选数。
新证据仍来自原文，并继续受引用、token、多样性和上下文裁剪限制。
同时修复原主链：未经验证的候选不能先发送到远端 Reranker，扩展块必须属于 seed 的相同
文档/版本/Profile/修订，最终上下文继续遵守请求 filter。

## 接入与回退

```scala
val structureStore = PostgresStructureStore(dataSource)
val spec = StructureBuildSpec()
val indexer = KnowledgeIndexer(chunker, embeddings, knowledgeIndexStore,
  structureIndexing = Some(StructureIndexing(structureStore,
    StructurePublicationPolicy.Required, spec)))
val retriever = DefaultRetriever(embeddings, vectorStore, reranker,
  structural = Some(StructuralRetrieval(structureStore, spec)))
```

原有 `DocumentIngestionService` / `RagApplication` 接收这两个实例即可。
PaddleOCR 摄取用 `parsed.toSourceDocument(id, sourceUri, trustedMetadata)`；
已知原件修订继续通过 `IngestionProvenance` 传入。默认 constructor 不传结构选项，行为保持经典路径。
生产先运行知识迁移；运行账号不获得 DDL 权限，不在请求内迁移。撤回/下线仍调用原
KnowledgeIndexStore，结构读取立即受同一 manifest 控制；原 retention/legal hold 通过 FK
级联保留或删除结构。内存 Store 只用于测试，不提供进程外持久性。

可运行本地示例：

```bash
sbt -batch 'examples/runMain com.zyblw.agent.examples.knowledge.StructuralRagExample'
```

## 业务配方与进阶能力落地

本次迭代已进一步补齐以下工程抽象与能力：
1. **统一业务配方（RetrievalRecipe & RetrievalStrategy）**：提供 `Classic`, `BookFast`, `BookGrounded`, `BookDeep`, `LowLatency`。业务直接通过 `RagQuery(recipe = Some(RetrievalRecipe.BookGrounded))` 声明场景，自动编排候选预算、扩展策略与结构分支开关。`Classic` 配方可对单次请求安全跳过结构检索。
2. **结构感知上下文扩展（StructuralExpansion）**：当命中章节时，在同 Section 边界内对 peer chunk 执行有界扩展，分值按衰减因子处理并带上 `context.structureNode` 标记，严格受限于同一文档版本、Profile、Tenant ACL 和请求 Filter。
3. **强类型检索信号（RetrievalSignals）**：统一管理 `VectorScore`, `TextScore`, `PhraseScore`, `ContextStructureNode`, `StructuralValue` 等信号，杜绝魔法字符串。
4. **多级结构评测（StructuralRagEval）**：建立 `StructuralTestCase` / `StructuralCaseMetrics` / `StructuralEvalReport`，独立量化 `DocumentRecall`, `NodeRecall`, `ChunkRecall`, `CitationPrecision` 与支持率，支持精准诊断“是章节没选对”还是“章节内物化漏召回”。
5. **受限模型导航与摘要**：复用 `ChatModel`，校验 JSON 前沿 ID，限制 beam/depth/访问/调用/输入/输出/时间；模型失败用确定性导航降级，取消传播。摘要用精确 Provider/model 固定身份，父节点使用后代原文的有界代表采样，只服务导航。
6. **恢复与缓存**：摘要规格参与结构 Profile；缓存隔离标题、模式、原文、租户/空间。摄入专用 `getForBuild` 可复用 Building 快照；普通 `get/getActive` 仍只返回 Ready/active。读回验证完整 binding/节点映射，不能借恢复 API 为查询暴露未发布结构。
7. **跨书与业务接入**：目录配对 Loader、RagApplication 授权书名发现、全目标有界协调、公平原文证据、共享 query embedding、工具/HTTP 可选配方与宿主均已接通。单目标也收窄 filter；固定 Profile，重建/撤回仍沿用原协议。
8. **评测真实性**：节点按文档限定；引用校验原文/来源/页码/几何；空与歧义金标拒绝。未标注节点/块不伪造 recall=1，汇总报告实际评测数量。此处 citation precision 衡量金标来源与证据关联，不代替生成答案的逐条蕴含审查。

## 验证与适用边界

确定性回归覆盖 JSON→Markdown 层级→页/bbox→表格→真实 token 切分→完整结构映射→
发布→双书检索/公平引用→重放→产物更新；真实 PostgreSQL 验证首版 V001、摘要恢复、Ready/active/ACL、
Reasoned 原文引用与撤回/替换/retention。模型 Adapter 用可控 ChatModel 测试，无真实模型效果结论。
全部 PostgreSQL testFull 已验证；当前修改仍未公开发布，纳入首次发布候选并验证独立消费者。

2026-10-01 本轮验证记录：

| 检查 | 结果 |
| --- | --- |
| `scalafmtCheckAll; scalafmtSbtCheck; testFull` | 1019 通过，0 失败，86 默认忽略（包括显式启用的外部集成） |
| 真实 PostgreSQL/pgvector `postgres/testFull` | 123 通过，0 失败，0 忽略 |
| `scripts/test-public-pdf-rag.sh` | 2 通过，公开 PDF SHA 校验、页码、发布后检索与固定负例排序 |
| `publishM2` + 独立 Maven consumer compile/runMain | 精确 `0.9.0-local` 制品成功，不是公开发布 |
| OpenAPI 契约快照 + `git diff --check` | 通过；1.3 保留 1.2 业务路径与原有字段 |

公开 PDF 用于验证解析与检索契约；模型导航测试使用可控 ChatModel，以上均不作为真实模型领域效果或 SLO 证明。

结构树保持 Canonical Section；不做未经领域消融证明的 thinning/merge、MCTS 或多 Agent。
Adaptive 是基于查询/模式/已装配能力的确定性启发式，缺失实际树时有显式 fallback，未宣称智能分类器效果。
摘要每节点最多采样 128 个代表 Block、每模型请求有字符与输出限额，进程缓存最多 10000 项；
跨查询持久事实是不可变 StructureSnapshot，不把 RAM 缓存当发布事实源。
树导航 maxVisitedNodes/maxModelCalls 等硬限额不突破；大叶章节按 `structureSectionId` 下推搜索，
不截断到前 N 块。旧 Profile 缺少该 metadata 时保留经典种子，升级需重新摄入完整 Profile。
大内部节点受物化预算约束并报告 structure-budget；缺树/导航失败报告 structure-fallback。
结构 expansion 的 peer 以 seed 附近优先，引用始终来自重新校验的原文，不能跨 tenant/space/profile/version/filter。

生产质量还需要真实 PaddleOCR corpus、100–300 条领域金标、模型 A/B/费用、复杂表格/插图审校、
生产容量与长时 SLO。上述外部证据未提供，不能将工程闭环或单测通过写成“100% 生产成熟”。
