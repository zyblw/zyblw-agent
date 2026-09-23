# RAG / Knowledge Retrieval Runtime 目标架构与实施规范

> 状态：Implemented baseline + staged roadmap（“当前事实”以源码和测试为准，后续能力仍按阶段门禁）  
> 适用基线：zyblw-agent 0.9.x 当前代码  
> 最后核验：2026-09-23
> 面向读者：框架维护者、Cursor/编码代理、宿主应用开发者、评测与运维负责人

本文给出 zyblw-agent RAG 能力的完整目标架构、兼容演进方式、数据库模型、Provider 契约、检索与重排流水线、评测、安全、可观测性、上线和回滚方案。它不是“从零重写”提案，而是在现有 `agent-rag`、`agent-document-loaders`、`agent-rerank`、`agent-providers`、`agent-postgres` 和 `agent-evals` 上做可验证的增强。

本文的核心结论是：

1. 保留现有模块边界、`RagApplication` 门面、结构化切分、PostgreSQL 混合检索、RRF、rerank、citation、ACL 和索引 staging/activation；这些已经构成可靠基线。
2. Space/Profile、原子激活、census 与 EvidenceBundle 已进入 0.9 基线。下一步是真实 OCR/恶意 PDF、tokenizer 对齐切分和领域评测，而不是再堆一套检索内核。Embedding 稀疏路与 planner assist 默认关闭。
3. Qwen3.7 dense+sparse、query/document 区分和 instruct 应由能力协商表达；Qwen 稀疏向量是可选的第三路召回，不替换 PostgreSQL FTS，也不成为 Provider 无关内核的硬依赖。
4. RAG 的授权边界必须在召回和展开之前生效；检索内容永远是不可信数据，不能改变 Agent 权限、工具策略或系统指令。
5. 任何增强都必须通过固定数据集的消融实验获得增益证明；GraphRAG、通用 agentic retrieval、multimodal、late interaction 暂不进入当前主线。

---

## 1. 目标、非目标与设计原则

### 1.1 目标

目标是把当前 RAG 层演进成一个：

- Provider-neutral：公共 SPI 不绑定 Qwen、OpenAI、Cohere 或某个数据库扩展；
- hybrid：支持 dense、PostgreSQL lexical、可选 model sparse、精确/元数据召回；
- domain-aware：宿主可以注入中医药等领域元数据、同义词、实体和指令，但公共框架不硬编码领域；
- versioned：文档版本和全知识空间检索配置都可追踪、并行构建、原子切换、快速回滚；
- evidence-first：生成模型消费的是有边界、有出处、有预算、有谱系的证据包；
- evaluable：离线质量、安全、延迟、成本和稳定性都有固定数据集、基线和发布门禁；
- production-safe：租户/权限前置过滤，索引发布不会制造混合身份窗口，删除、撤回和保留策略可闭环；
- ZIO-native：资源、并发、超时、取消、重试和流式背压遵循 ZIO 2 语义。

### 1.2 非目标

本轮不把以下内容纳入必须实现范围：

- GraphRAG、Neo4j、知识图谱查询语言；
- 让 LLM 自由循环调用搜索工具的通用 agentic retrieval；
- 图片/音频/视频的跨模态向量库；
- ColBERT/late-interaction 或自研 ANN 引擎；
- 把完整文档原文、四份文本副本和大对象都塞入 PostgreSQL；
- 用一个大而全的新模块替换现有六个 RAG 相关模块；
- 在没有评测证据时把所有建议同时打开。

这些能力并非永远否决，而是必须先出现明确业务场景、独立依赖或安全边界，并通过现有混合 RAG 无法满足的证据来触发 ADR。

### 1.3 约束原则

- **单一运行时**：扩展现有 `RagApplication`，不引入第二套平行门面。
- **权限早于相关性**：tenant、principal/permission、document scope 在每条候选分支的 SQL 或 store 查询中先过滤，不能在合并后补过滤。
- **身份显式化**：模型、维度、输出类型、切分器、标准化器、元数据 schema 和融合策略都进入不可变 Profile。
- **外部调用不占数据库事务**：解析、embedding、rerank 和 LLM contextualization 均在事务外完成；事务只做短暂 staging/activation 状态变更。
- **失败可恢复**：批次幂等、检查点明确、取消可传播、旧 Profile 可回滚。
- **默认简单**：先 dense + FTS + RRF + rerank；sparse、query rewrite、contextual retrieval 均按评测逐项启用。
- **公共框架不含业务知识**：中医药词典、实体 schema、指令模板和规则由宿主注入。

---

## 2. 当前实现审计

### 2026-09 绿场落地状态

本表记录协议接线后的**当前事实**，不把整篇历史审计改写成已实现。

| 项目 | 状态 |
| --- | --- |
| `EmbeddingModel.embed(EmbeddingRequest)` 唯一 SPI | 已接线 |
| 摄入 checkpoint / quarantine / `SourceArtifactRef` | 已接线；quarantine 落 `Failed` + `ingestion.quarantine`，不激活 |
| Space/Profile 列写入、请求内 `pinnedProfileId`、换模 building + 空间级 CAS | 已接线 |
| 空 Space 首文档增量 bootstrap | 文档质量门禁后可自动建立首个 active Profile；不是全量 corpus 评测发布，批量初始化不得借此逐文档暴露 |
| `ContextAssembler` token/多样性裁剪，citation 只用 `displayText` | 已接线 |
| Profile 完整 census + 评测绑定 + active corpus 对齐 + CAS 审计/回滚 | 已接线；缺文档、失败文档、质量失败和 revision 漂移拒绝切换 |
| EvidenceBundle → 工具/管理 API/RAG Inspector；embed/search/rerank/expand/assemble span | 已接线 |
| Comparison 子查询 / `includeExact`→Phrase / legal-hold purge | 已接线 |
| sparse / query assist | **默认关**；管线真实，发布基线仍是 dense+FTS+rerank |
| GraphRAG / 自由 agentic retrieval / ColBERT | deferred |
| 宿主中医校准质量阈值 | deferred |
| live Provider HTTP 与平台重摄取问答 | 2026-09-05 本机 compose `zyblw_dev`：seed 文章重摄取后 hybrid retrieve 1 hit，问答 `Completed` + 1 citation；未作为 CI 默认门禁 |

### 2.1 已具备的可靠基线

当前实现不是简单的“向量库 demo”，已经包含以下生产化骨架：

| 能力 | 当前实现 | 判断 |
| --- | --- | --- |
| 文档与切分 | `SourceDocument`、`DocumentChunk`、`DocumentStructure`、`ChunkLineage`、`DocumentStructureChunker`、`MarkdownStructureChunker` | 保留并演进 |
| Token 对齐 | 默认 Cl100k tokenizer、结构优先、约 512 token 上限 | 可作为初始基线，不当作所有 Provider 的永恒默认 |
| Embedding | `EmbeddingModel.embed(EmbeddingRequest)`、query/document role、capability、usage、quota/cache/governance、OpenAI-compatible 与 Qwen dense adapter | 当前主契约 |
| 索引生命周期 | `KnowledgeIndexer`、staging、quarantine、retire、purge、manifest、稳定 ingestion id、Space/Profile CAS 发布 | 当前主契约 |
| 检索 | Hybrid / VectorOnly / LexicalOnly / Phrase、metadata filter、dense + PostgreSQL FTS、weighted RRF | 继续作为默认路径 |
| 安全 | tenant + permissions 前置到 store 查询；reranker 和扩展结果再次验证 | 必须保持为不变量 |
| 重排 | `RerankerModel` SPI、Cohere adapter、candidate identity 校验 | 增加 instruction 和 Qwen native adapter |
| 上下文 | parent/neighbor expansion、`ContextAssembler`、`EvidenceBundle`、`MemoryRagContextSourceResolver`、低证据拒答 | 当前主契约 |
| 引用 | `Citation`、locator/lineage、knowledge search/fetch typed tools | 保留；把 seed/expanded 和 profile provenance 补齐 |
| 评测 | Recall、Precision、MRR、binary nDCG、citation、ACL、延迟 | 扩成领域数据集和端到端质量门禁 |
| 可观测性 | retrieval operation、阶段 span、Evidence 取舍、profile/space、hit count、OTel/Langfuse 映射 | 继续禁止原文默认上报；生产 SLO 待宿主校准 |
| PostgreSQL | `vector(1024)`、HNSW、GIN permissions/search_vector/metadata/heading/pages、trigram、Space/Profile/census/chunk/audit/withdrawn | 0.9 fresh-install V001 当前基线 |

### 2.2 本轮已关闭的关键协议缺口

#### 2.2.1 Embedding 请求语义

旧 `EmbeddingService` 不能完整表达以下语义，现已由唯一的
`EmbeddingModel.embed(EmbeddingRequest)` 与 `EmbeddingCapabilities` 显式表达并校验：

- query 与 document 的非对称编码；
- instruction 的稳定身份与正文；
- dense、sparse 或 dense+sparse 输出；
- 稀疏向量维度、索引和值的合法性；
- 不同用途的 Provider 能力限制。

`EmbeddingPurpose` 已用于治理上下文，但 purpose 不是 Provider 的 `text_type`，两者不能混为一个枚举：前者回答“为什么调用”，后者回答“如何编码”。

#### 2.2.2 全量换模的活跃索引混合窗口

旧的逐文档激活会产生混合 embedding identity 窗口。当前实现使用
Knowledge Space + immutable Profile：并行构建目标 corpus，经完整 census/质量评测门禁后，以 revision CAS 原子切换；
查询在开始时 pin profile，旧 Profile 保留供审计和回滚。

#### 2.2.3 候选预算与最终证据预算

当前 `RetrievalOptions` 与 `ContextAssembler` 已分别约束候选、rerank/扩展和最终证据预算。后续若引入更多召回分支，仍须保持以下概念分离：

- 每条一阶段召回分支的 candidate budget；
- fusion 后进入 rerank 的预算；
- rerank seed 预算；
- expansion 预算；
- 最终 evidence chunk 与 token 预算。

否则 top-k 的含义会模糊，也无法进行稳定消融。

#### 2.2.4 证据装配边界

当前 `ChunkRepresentations`、`ContextAssembler` 与 `EvidenceBundle` 已明确区分：

- 用于引用和展示的原样文本；
- 用于 dense embedding 的派生文本；
- 用于 lexical index 的标准化文本；
- seed 命中与 parent/neighbor 扩展；
- 去重、来源多样性、token 裁剪及其原因。

这些决定可由管理调试 API 和 RAG Inspector 检查，不隐藏在字符串拼接里。

#### 2.2.5 评测仍偏机制验证

现有测试和小型 corpus 很适合守住算法与权限契约，但不能回答以下生产问题：

- 中医药领域查询的 graded relevance 是否提升；
- rerank、contextualization、sparse 或 rewrite 分别贡献多少；
- 无答案时是否会正确拒答；
- 引用是否真正支持答案中的具体主张；
- 真实 PDF/OCR/表格/古籍异体文本是否稳定；
- p95/p99 延迟和每千次检索成本是否可接受。

### 2.3 当前事实与目标态边界

当前已落地 Embedding v2、Knowledge Space/Profile、EvidenceBundle 和全量蓝绿切换；它们仍是 0.x / Beta，
不能仅因协议和测试存在就升级为 Stable。Qwen sparse、三路 RRF、模型辅助 Query Planner、真实领域
100–300 条金标、生产容量/SLO 与真实 OCR 安全 corpus 仍是 Proposed 或 deferred。

### 2.4 当前源码证据索引

本节只列当前事实的主要入口，方便实现者复核；目标设计不能覆盖源码事实。

- [Rag.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/Rag.scala)：当前文档/chunk/embedding/retrieval/citation 模型、`EmbeddingService`、`DefaultRetriever`；
- [RetrievalQuery.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/RetrievalQuery.scala)：当前检索 mode、filter 和 request；
- [RetrievalLineage.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/RetrievalLineage.scala)：parent/neighbor/page/bbox 谱系；
- [KnowledgeIndex.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/KnowledgeIndex.scala)：逐文档 begin/stage/activate/retire/purge 协议；
- [KnowledgeReindexService.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/KnowledgeReindexService.scala)：当前分页逐文档重建和 source resolver；
- [EmbeddingGovernance.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/EmbeddingGovernance.scala)：purpose、quota 和 cache 治理；
- [ModelReranker.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/ModelReranker.scala)：当前 reranker SPI 与候选验证边界；
- [MemoryRagContextSourceResolver.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/MemoryRagContextSourceResolver.scala)：Memory/RAG 组合和低证据处理；
- [KnowledgeTools.scala](../../modules/agent-rag/src/main/scala/com/zyblw/agent/rag/tools/KnowledgeTools.scala)：受 trusted scope 约束的 search/fetch 工具；
- [DoclingDocumentLoader.scala](../../modules/agent-document-loaders/src/main/scala/com/zyblw/agent/loaders/DoclingDocumentLoader.scala)：当前隔离 Docling Serve、输入/响应/超时/结构化输出边界；
- [OpenAICompatibleEmbeddingService.scala](../../modules/agent-providers/src/main/scala/com/zyblw/agent/integrations/openai/OpenAICompatibleEmbeddingService.scala)：当前 dense embedding adapter；
- [CohereRerankModel.scala](../../modules/agent-rerank/src/main/scala/com/zyblw/agent/integrations/rerank/CohereRerankModel.scala)：当前 rerank adapter；
- [PostgresPgVectorStore.scala](../../modules/agent-postgres/src/main/scala/com/zyblw/agent/persistence/postgres/PostgresPgVectorStore.scala)：当前 ACL 前置的 dense/FTS/RRF 和 embedding identity 校验；
- [V001 pgvector baseline](../../modules/agent-postgres/src/main/resources/com/zyblw/agent/persistence/postgres/optional/pgvector_1024/V001__agent_knowledge_0_9_baseline.sql)：当前不可修改的 1024 维正式 schema；
- [RagEvaluation.scala](../../modules/agent-evals/src/main/scala/com/zyblw/agent/evals/RagEvaluation.scala) 与 [BookCorpusRagEval.scala](../../modules/agent-evals/src/main/scala/com/zyblw/agent/evals/BookCorpusRagEval.scala)：当前机制指标和小型 corpus 基线。

---

## 3. 对原建议的采纳矩阵

| 原建议 | 决策 | 在 zyblw-agent 中的落法 |
| --- | --- | --- |
| Provider 无关 RAG runtime | 采纳 | 公共 ADT/SPI 在 `agent-rag`，具体 HTTP/JSON 在 adapter 模块 |
| Qwen3.7 query/document/instruct | 采纳 | Embedding v2 能力协商；Qwen native adapter，不污染通用枚举 |
| dense + sparse | 条件采纳 | dense + PostgreSQL FTS 为默认；Qwen sparse 通过评测后成为第三路召回 |
| 默认 1024 维 | 采纳为初始 profile | 与当前 `vector(1024)` 一致；2560 不直接启用 |
| 结构化 parent/child chunk | 采纳 | 扩展当前 `DocumentStructureChunker` / lineage，不另写第二套 chunker |
| raw/normalized/retrieval/display 四份文本 | 调整 | 原始文件/权威提取物放对象存储；chunk 内只保留 display、dense-derived、lexical-derived 及 hash |
| 中医药 metadata/entity | 采纳为宿主扩展 | 公共框架提供 schema/version/extractor SPI，不硬编码方剂、证候、药材类型 |
| exact + dense + sparse + metadata | 采纳为可配置计划 | 所有分支同样执行 ACL；exact 不等于绕过权限的“捷径” |
| RRF 后 Qwen rerank | 采纳 | 保持现有 RRF；在 `agent-rerank` 增加 Qwen native adapter |
| 查询理解与 rewrite | 分阶段采纳 | 先确定性 normalizer/router；LLM planner 仅在固定评测证明净增益后启用 |
| 新建 5 个 RAG artifact | 不采纳 | 当前 11 artifact 边界足够；只在真实依赖/生命周期/安全边界出现时再拆 |
| 60–100 候选、top 8 等固定参数 | 作为实验起点 | 写入 profile，由数据集调参，不成为全局常量 |
| GraphRAG / agentic / multimodal | 延后 | 需要独立 ADR、场景与消融证据 |

---

## 4. 目标架构总览

### 4.1 在线检索路径

```text
Trusted RetrievalScope
        │  tenant / permissions / knowledgeSpaceId
        ▼
QueryNormalizer ──► QueryPlanner (deterministic first, optional model-assisted)
        │                         │ bounded plan, no authority mutation
        └─────────────────────────┘
                         ▼
                   RetrievalPlan
                         │
          ┌──────────────┼───────────────┬────────────────┐
          ▼              ▼               ▼                ▼
     Dense ANN      PostgreSQL FTS   Model Sparse*    Exact/Metadata
          │              │               │                │
          └──────────────┴──── ACL/filter in every branch ┘
                         ▼
                  Weighted RRF Fusion
                         ▼
                Candidate validation
                         ▼
                 Cross-encoder rerank
                         ▼
              Parent / neighbor expansion
                         ▼
          ContextAssembler: dedupe/diversity/budget
                         ▼
                    EvidenceBundle
                         ▼
          ContextManager / generator / citations

* Model Sparse 初始关闭，仅在 Provider、数据库和评测均满足门槛后启用。
```

### 4.2 离线摄取与索引发布路径

```text
Approved source URI + trusted ownership/ACL
        ▼
Source resolver / object store
        ▼
Bounded loader cascade (native text → structured parser → OCR fallback)
        ▼
Deterministic normalization + integrity hash
        ▼
Structure-aware chunking + lineage
        ▼
Optional contextualizer / domain metadata extractor
        ▼
Chunk representations + validation
        ▼
Dense embedding (+ optional sparse) outside DB transaction
        ▼
Profile staging + checkpoints + corpus census
        ▼
Quality/security/capacity gates
        ▼
KnowledgeSpace.activeProfileId CAS activation
        ▼
Old profile retained for rollback → retention purge
```

### 4.3 模块归属

不增加新发布 artifact。目标能力映射如下：

| 模块 | 目标职责 |
| --- | --- |
| `agent-rag` | 文档/证据模型、Embedding v2 SPI、Profile、ingestion orchestration、query plan、fusion、retriever、context assembler |
| `agent-document-loaders` | Tika/PDF/Markdown 等较重解析器、结构化提取、OCR/Docling 外部桥接 SPI 或 adapter |
| `agent-providers` | Qwen native embedding adapter、OpenAI-compatible dense adapter、区域 endpoint/config/redaction |
| `agent-rerank` | Cohere 和 Qwen native rerank adapter；provider response validation |
| `agent-postgres` | 追加 Flyway migration、profile store、dense/FTS/可选 sparse 查询、activation CAS、retention |
| `agent-evals` | 数据集、检索/答案/安全/成本指标、ablation runner、发布证据 |
| `agent-core` | 只消费经过治理的 context/evidence；不吸收 RAG 存储和算法细节 |
| `agent-dashboard` | 只展示安全投影：profile/build/retrieval stage/评测趋势，不成为控制面事实源 |

拆出第 12 个 artifact 的条件必须至少满足一项：出现独立且显著的运行时依赖、独立安全边界、独立生命周期/部署形态或需要独立发布兼容承诺。单纯“文件变多”不构成拆分理由。

---

## 5. 核心领域模型

以下 Scala 仅表示目标 API 形状；编码时应按二进制/源码兼容要求拆成小提交，并以实际编译为准。

### 5.1 Knowledge Space 与 Index Profile

```scala
opaque type KnowledgeSpaceId = String
opaque type IndexProfileId   = String

final case class KnowledgeSpace(
  id: KnowledgeSpaceId,
  tenantId: TenantId,
  activeProfileId: Option[IndexProfileId],
  revision: Long
)

enum IndexProfileStatus:
  case Building, Ready, Active, Superseded, Failed, Retired

final case class IndexProfile(
  id: IndexProfileId,
  knowledgeSpaceId: KnowledgeSpaceId,
  version: Long,
  status: IndexProfileStatus,
  dense: DenseIndexIdentity,
  sparse: Option[SparseIndexIdentity],
  lexical: LexicalIndexIdentity,
  chunking: ChunkingIdentity,
  normalization: TransformationIdentity,
  contextualization: Option[TransformationIdentity],
  metadataSchema: MetadataSchemaIdentity,
  fusion: FusionIdentity,
  createdAt: Instant
)
```

Profile 是不可变的“检索产物身份”，不是可随意热改的配置对象。任何会改变检索结果可比性的字段都生成新 Profile：

- dense provider/model/revision/dimension/distance/normalization；
- sparse provider/model/revision/index dimension/output semantics；
- tokenizer/chunker/version/参数；
- normalizer/contextualizer/instruction 的 ID 和版本；
- lexical analyzer/dictionary/schema version；
- metadata schema 和 fusion strategy version。

运行时限流、连接池大小和 telemetry sample rate 不属于 Profile，因为它们不改变索引身份。

### 5.2 文档版本与派生文本

```scala
final case class SourceArtifactRef(
  uri: String,
  mediaType: String,
  contentSha256: String,
  extractedArtifactUri: Option[String],
  extractedSha256: Option[String]
)

final case class ChunkRepresentations(
  displayText: String,
  denseText: String,
  lexicalText: String,
  displaySha256: String,
  denseSha256: String,
  lexicalSha256: String
)
```

约束：

- `displayText` 是引用和展示的权威 chunk 文本，尽量保留原始措辞、标点、表格语义和页码定位；
- `denseText` 可由标题路径、短上下文前缀和正文构成，只用于 embedding/rerank，不能伪装成原文引用；
- `lexicalText` 用于 FTS/trigram，可做确定性字符归一、同义词展开或领域词典映射；
- 原始 bytes 和完整权威 Markdown/JSON 文档放在宿主治理的对象存储或内容仓库，数据库只保留 URI、hash、谱系和必要 chunk；
- 不保存四份完整原文；如果 `denseText == displayText`，持久层可以只保存 hash/派生规则，按需重建；
- 所有派生器必须有稳定 version，hash 参与幂等键和审计。

### 5.3 结构与谱系

沿用 `ChunkLineage`，补足以下语义：

- `documentRevisionId`：文档内容版本；
- `profileId`：派生该 chunk 的全局检索 Profile；
- `parentId` / `previousId` / `nextId`；
- heading path、page range、bounding box、block/table/figure type；
- `seedChunkId`：证据由哪个命中种子扩展而来；
- extraction provider/version/confidence；
- source content hash 和 display hash。

切分建议不是“固定 256/512”而是约束区间：

- child 以结构边界优先，初始 hard max 512 provider tokens；
- 很短段落可与同标题邻段合并，但不能跨越表格、列表或明确章节边界；
- parent 指向结构节点或可重建范围，不必把 1,500–4,000 token 的父文本复制到每行；
- 表格优先生成可读 Markdown/行列语义，同时保留页码/bbox；
- overlap 仅用于无结构长文本，结构化文档优先用 parent/neighbor lineage，避免重复污染召回。

---

## 6. Embedding v2 契约

### 6.1 请求和输出模型

```scala
enum EmbeddingInputRole:
  case Query, Document

enum EmbeddingOutputKind:
  case Dense, Sparse, DenseAndSparse

final case class EmbeddingInstruction(
  id: String,          // 稳定、可审计，不含租户或用户原文
  version: String,
  text: String
)

final case class EmbeddingRequest(
  texts: Chunk[String],
  role: EmbeddingInputRole,
  output: EmbeddingOutputKind,
  dimension: Option[Int],
  instruction: Option[EmbeddingInstruction],
  context: EmbeddingRequestContext
)

final case class SparseEmbeddingEntry(index: Int, value: Float)

final case class SparseEmbedding(
  dimension: Int,
  entries: Chunk[SparseEmbeddingEntry]
)

final case class EmbeddingItem(
  dense: Option[Embedding],
  sparse: Option[SparseEmbedding]
)

final case class EmbeddingResponse(
  items: Chunk[EmbeddingItem],
  descriptor: EmbeddingProviderDescriptorV2,
  usage: Option[EmbeddingUsage],
  providerRequestId: Option[String]
)
```

关键不变量：

- response item 数量与输入严格相等，顺序可被验证；Provider 返回 index 时必须完整、唯一且范围合法；
- dense 维度与请求/Profile 相等，所有值有限，禁止 NaN/Infinity；
- sparse index 非负、严格唯一、按 index 排序后存储；value 有限且非零；
- sparse dimension 来自 Provider descriptor/Profile，不在通用内核硬编码 Qwen 词表值；
- instruction 是否允许、只允许 query 还是 query/document 都允许，由 capability 校验；
- 不能因 Provider 不支持 sparse 而静默降级；是否 fallback 必须由显式策略决定并写入 telemetry/result；
- 不把 Provider 返回的 token 字符串写入日志或数据库，检索只需要 index/value。

### 6.2 能力协商

```scala
final case class EmbeddingCapabilities(
  inputRoles: Set[EmbeddingInputRole],
  outputs: Set[EmbeddingOutputKind],
  minDenseDimension: Int,
  maxDenseDimension: Int,
  defaultDenseDimension: Int,
  maxTextsPerRequest: Int,
  maxTokensPerRequest: Option[Long],
  instructionPolicy: InstructionPolicy,
  reportsUsage: Boolean
)
```

配置加载时和每次调用前分别做静态、动态校验。Profile 创建阶段应拒绝能力不匹配，而不是等第一条生产查询失败。

### 6.3 兼容策略

不要直接给现有 `EmbeddingService` 增加新的抽象方法，这会破坏第三方实现。建议：

1. 新增 `EmbeddingModel`（或等价 v2 名称）承载 `embed(request)`；
2. 为现有 `EmbeddingService` 提供 dense-only adapter；
3. `embed(texts)` / `embedDetailed(texts)` 保留至少一个 minor，标记 deprecated，并固定映射为 Document + Dense；查询侧必须显式通过新接口传 Query；
4. 内置 Provider adapter 优先迁移；consumer compatibility test 覆盖旧实现；
5. 移除旧 SPI 只能发生在下一个明确的 breaking version。

若不希望增加新 trait，也可以给现有 trait 增加有默认实现的非抽象方法，但必须确认 Scala trait binary compatibility；无论采用哪种形式，都需要 MiMa/consumer compilation 或等价的外部消费验证。

### 6.4 Qwen3.7 adapter

Qwen3.7 的高级 embedding 能力应走 DashScope native API；OpenAI-compatible endpoint 继续只作为 dense 通用途径。adapter 配置至少包含：

```text
provider = qwen
region = beijing | singapore
baseUrl = explicit validated endpoint
workspace = optional secret reference
apiKey = secret reference
model = explicit model id
dimension = 1024
output = dense | sparse | dense_and_sparse
maxBatchTexts = 20
maxBatchTokens = 131072
requestTimeout
retryPolicy
```

实现要求：

- endpoint 由区域枚举生成或显式 allowlist 验证，不能由 query/document 内容拼接；
- Query 请求使用 query role；索引请求使用 document role；
- instruction 只在模型能力允许的角色上发送，模板文本使用英文并有稳定版本；
- 默认 dense 维度 1024，与现有 pgvector baseline 对齐；
- Provider 429/5xx/timeout 分类成可重试错误，4xx schema/auth 错误不可盲重试；
- 记录 provider request id 和 usage，但日志中不出现 API key、原始文本或完整响应；
- 取消 HTTP request 后不得继续后台重试或写入 staging。

1024 是当前合理默认，不只是模型建议，也与 pgvector `vector(1024)` 及 HNSW 的常规 `vector` 维度上限兼容。若未来使用 2560，必须选择 `halfvec` 或其他经验证的存储策略，并用同一数据集比较质量、存储、建索引时间和在线延迟；不能只改模型参数。

### 6.5 实时与批量 embedding

- 在线 query embedding 必须使用同步低延迟 API；
- 小批量文档摄取按 Provider 单请求限制分块并 bounded parallel；
- 大规模全量重建可接 Provider 的异步 batch/file API，但它属于 durable job：要有 job id、轮询/回调状态、校验、超时、取消与重放，不能由进程内 Fiber 睡眠等待数小时；
- 无论同步还是异步，最终都转换成同一个 `EmbeddingResponse` 验证和 staging 协议。

---

## 7. Query Planning 与检索计划

### 7.1 权限不可由 planner 决定

```scala
final case class TrustedRetrievalContext(
  scope: RetrievalScope,
  knowledgeSpaceId: KnowledgeSpaceId,
  activeProfileId: IndexProfileId
)

final case class RetrievalPlan(
  normalizedQuery: String,
  subqueries: Chunk[String],
  filters: RetrievalFilter,
  modes: Set[CandidateSource],
  budgets: CandidateBudgets,
  fusion: FusionPlan,
  embeddingInstructionId: Option[String],
  rerankInstructionId: Option[String]
)
```

Planner 只能产生检索意图，不能产生或修改：

- tenantId、permission/principal scope；
- knowledgeSpaceId 和 activeProfileId；
- 任意 SQL、任意表名或任意 source URI；
- 超过宿主上限的 top-k、subquery 数、token budget；
- 未注册 instruction/template ID。

所有 planner 输出先通过纯函数 validator，失败时回退到单查询默认计划。

### 7.2 第一阶段：确定性 planner

先实现无 LLM 的 `QueryNormalizer` 和 `RuleBasedQueryPlanner`：

- Unicode、空白、全半角和可配置标点归一；
- 领域词典通过宿主 `QueryLexicon` 注入；
- 精确来源/标题/页码/文档 ID 查询路由；
- 比较型查询最多拆成 2–3 个受限子查询；
- 解释型查询提高 lexical+dense 召回预算，但不扩权；
- 重复、空或过长子查询被拒绝或裁剪，并记录原因。

不要在公共模块中写死“方剂”“证候”“药材”等关键词。中医药宿主提供规则和 schema，框架只提供组合接口。

### 7.3 第二阶段：模型辅助 planner

只有确定性基线无法覆盖的 query type 在评测中有明确损失时，才引入 LLM planner。要求：

- typed JSON schema；
- 最多 3 个 subquery；
- 独立 timeout 和低成本模型预算；
- validation failure、timeout、provider failure 均回退默认计划；
- planner 的输入不含不必要的 ACL、秘密和完整历史；
- 单独计算 planner latency/cost/gain；
- 不能让 planner 直接循环调用检索形成无限 agent loop。

---

## 8. 候选召回、融合与重排

### 8.1 候选来源

目标支持四类候选源：

1. **Dense ANN**：query dense embedding 与 profile dense index；
2. **PostgreSQL lexical**：`tsvector`/phrase/trigram，继续作为默认关键词和专名召回；
3. **Model sparse**：可选 Qwen sparse embedding，只有 Profile 启用时执行；
4. **Exact/metadata**：受限的 document/chunk/page/heading/metadata 条件和精确词命中。

每一条 SQL 都必须具备相同的 tenant、permission、knowledge space、active profile、document retirement 条件。候选查询 API 应接收完整 `TrustedRetrievalContext`，避免调用者漏传 profile。

### 8.2 PostgreSQL FTS 与 model sparse 的关系

两者互补，不应二选一：

- FTS 可解释、便宜、可运维，对专名、数字、固定词组和精确文本非常有效；
- model sparse 可以学习语义化 token 权重，但绑定 Provider 词表/模型身份，存储与 ANN 限制也不同；
- 将 model sparse 上线为第三路召回前，必须证明它在目标语料上对 dense+FTS 有增量，而不是只证明单独可用。

pgvector 的 `sparsevec` HNSW/IVFFlat 对单向量非零元素数存在上限。实现必须在 adapter 和 store 两侧验证 NNZ；超过数据库能力时，显式失败或按 Profile 的公开 fallback 策略退回 dense+FTS，禁止静默截断，因为截断会改变相关性且不可审计。

### 8.3 候选预算

建议把当前由最终 `limit` 推导的预算拆开：

```scala
final case class CandidateBudgets(
  dense: Int,
  lexical: Int,
  sparse: Int,
  exact: Int,
  fused: Int,
  rerankedSeeds: Int,
  expanded: Int,
  finalEvidence: Int,
  finalEvidenceTokens: Int
)
```

首轮实验基线可设为：dense 60、lexical 60、sparse 关闭、exact 20、fused 80、reranked seeds 8、neighbor radius 1、expanded 最多 16、final evidence 6–8、RAG evidence 预算 4k–8k tokens。它们是实验起点，不是公共 API 的硬编码默认；最终值按 query type、生成模型上下文和数据集调优。

`RagApplication` 对外仍可保留简单 `topK`，但内部要映射到命名 Profile/Policy；高级调用者可以选择注册过的 retrieval policy，不能任意突破服务端上限。

### 8.4 Weighted RRF

沿用当前 RRF，增加可审计的分支身份和 profile version：

```text
rrfScore(document) = Σ sourceWeight / (rrfK + rankInSource)
```

要求：

- 默认 `rrfK = 60` 可保留为基线；
- 同一 chunk 在同一路只计一次，在多路分数累加；
- tie-break 使用稳定键，例如 `(rrfScore desc, bestSourceRank, documentId, chunkId)`；
- 权重属于 Profile/FusionIdentity；
- RRF 分数只有排序含义，不能用固定全局阈值判断“有答案”；
- exact 命中可用独立 pin/boost 规则，但规则必须有上限，不能吞掉所有语义候选。

### 8.5 Reranker v2

```scala
final case class RerankInstruction(id: String, version: String, text: String)

final case class RerankRequestV2(
  query: String,
  candidates: Chunk[RerankCandidate],
  topN: Int,
  instruction: Option[RerankInstruction],
  profileId: IndexProfileId
)
```

继续保留当前的安全校验：返回 candidate id 必须属于输入集合、不得重复、score 必须有限、adapter 不能注入新文本或放大权限。另加：

- topN 不得大于 candidate count 和宿主上限；
- response index/id 映射完整可验证；
- usage/request id 可观测；
- timeout/failure 的 fallback 是“继续使用 RRF 排序”或“失败关闭”，必须由 policy 明确；
- Qwen rerank 的分数只在同一请求内用于排序，不能跨请求比较，也不能直接设全局拒答阈值；
- instruct 使用稳定 ID/version，文本不进入低基数 metrics。

Qwen3.7 rerank adapter 放入 `agent-rerank`，使用 native endpoint；与 Cohere adapter 共享公共 SPI 和 conformance suite。

### 8.6 低证据拒答

拒答应基于经过校准的多信号策略，不依赖某个 Provider 的单个 score：

- 是否存在满足 ACL 的候选；
- top evidence 的 lexical/dense/rerank 排名形态；
- top1-topN margin、来源一致性和引用覆盖；
- query type 特定门槛；
- 无答案数据集上的目标 precision/recall；
- 可选轻量 answerability classifier，但必须独立评测。

结果中返回机器可读的 `EvidenceDecision`：`Sufficient`、`Insufficient(reason)`、`Degraded(reason)`，而不是只返回空字符串。

---

## 9. Context Assembler 与 Evidence Bundle

### 9.1 处理顺序

建议保持“对 seed rerank，再做谱系扩展”的方向：

1. 校验并排序 reranked seeds；
2. 按 seed 获取 parent/neighbor，所有 fetch 再执行相同 ACL/profile 条件；
3. 按 `(documentRevisionId, chunkId)` 和 `displaySha256` 去重；
4. 在 token 预算内选择证据；
5. 只有数据集证明同一来源过度集中时，才启用 MMR/来源配额；
6. 生成带安全分隔符、稳定引用标记和 provenance 的 Evidence Bundle。

不要先展开几十个邻居再交给昂贵 reranker；也不要对 expanded chunk 伪造 rerank score。expanded evidence 应继承 seed 关系，同时保留自己的 lexical/dense signal 为空或单独计算。

### 9.2 数据模型

```scala
enum EvidenceOrigin:
  case RetrievedSeed
  case ParentOf(seedChunkId: String)
  case PreviousOf(seedChunkId: String)
  case NextOf(seedChunkId: String)

final case class EvidenceItem(
  citation: Citation,
  displayText: String,
  origin: EvidenceOrigin,
  sourceRanks: Map[String, Int],
  scores: Map[String, Double],
  tokenCount: Int,
  profileId: IndexProfileId,
  documentRevisionId: String
)

final case class EvidenceBundle(
  queryFingerprint: String,
  knowledgeSpaceId: KnowledgeSpaceId,
  profileId: IndexProfileId,
  retrievalPlanId: String,
  items: Chunk[EvidenceItem],
  totalTokens: Int,
  decision: EvidenceDecision,
  truncations: Chunk[EvidenceTruncation],
  degradedStages: Set[String]
)
```

`queryFingerprint` 使用带服务端 secret 的 HMAC 或一次性 request id；不能用可逆 hash 暴露低熵医疗查询。原始 query 默认只在宿主明确批准的受控日志/评测数据集中保存。

### 9.3 Prompt 边界

Evidence Bundle 渲染时：

- 每条证据有 `BEGIN/END RETRIEVED EVIDENCE` 等明确边界；
- 明示内容是引用数据，不是系统或工具指令；
- 引用 ID 由框架生成，文档文本不能声明自己的 citation id；
- 证据后的系统指令再次约束模型只基于证据回答并保留不确定性；
- 不允许 retrieved content 直接构造 tool call、approval 或 trusted runtime metadata；
- `denseText` 的 contextual prefix 不作为原文引用，citation 只对应 `displayText`。

---

## 10. 摄取、解析、标准化与领域增强

### 10.1 Loader cascade

沿用当前“轻到重、可观察、有限制”的级联：

1. 对可验证文本层使用原生/轻量解析；
2. Tika 等通用 loader 提取文本与 metadata；
3. 结构复杂或质量不足时调用结构化 PDF parser；
4. 扫描件或低置信度页面才进入 OCR；
5. 表格/版面/VLM 路径只在文档类型和质量门槛触发。

当前 `agent-document-loaders` 已有 `DoclingDocumentLoader`，通过隔离的 Docling Serve 把 PDF 转成 Markdown 和 provider-neutral 结构，并具备 endpoint、字节数、响应、超时和 secret redaction 边界。应复用并补齐生产证据，而不是另写一套 adapter；Docling 继续不能成为 `agent-rag` 的强依赖。生产化要求：

- 在隔离进程/容器运行，CPU、内存、页数、分辨率和 wall-clock 都有限制；
- 禁止不可信 LaTeX/PDF 打开 shell escape；
- 输入先做 magic bytes/MIME/大小/压缩炸弹检查；
- 保存 parser/model/version、每页置信度和 fallback 记录；
- 用真实恶意 PDF、损坏 PDF、大表格、竖排/双栏/OCR 样本做 corpus test；
- 失败时返回 typed extraction error，不吞掉页面或伪造成功。

### 10.2 标准化

标准化分两层：

- **display normalization**：只做保证引用可读且不改语义的确定性处理，例如换行、Unicode 规范、明显重复页眉页脚；
- **retrieval normalization**：可做简繁/异体映射、单位格式、领域同义词、标题路径前缀等，分别生成 dense/lexical 表示。

每一步都是纯函数或受控 effect，输出包含：transform id/version、输入 hash、输出 hash、warnings。禁止 LLM “润色”覆盖 displayText。

### 10.3 Contextual retrieval

可选 contextualizer 为每个 chunk 生成短前缀，例如“本段来自《…》第…章，讨论…”。使用原则：

- 只写入 `denseText`/`lexicalText`，不改 `displayText`；
- 输出长度硬限制，例如 50–100 tokens；
- 不得包含未在源文档出现的诊疗结论；
- 输入是文档/章节的最小必要上下文；
- provider/model/prompt/version 进入 Profile；
- 缓存键包含 source hash + chunk hash + contextualizer version；
- 必须通过“无 contextualization vs 有 contextualization”消融后再默认启用。

Contextual Retrieval 的公开案例显示它在特定数据集上可以提高召回，但这是经验性技术，不是普适定理；zyblw-agent 应以自己的中医药/业务语料结果为准。

### 10.4 领域 metadata 与实体

公共 SPI 建议如下：

```scala
trait DocumentEnricher:
  def descriptor: EnricherDescriptor
  def enrich(document: SourceDocument, chunks: Chunk[DocumentChunk])
    : IO[EnrichmentError, EnrichmentResult]
```

`EnrichmentResult` 只能返回 schema 校验后的 metadata、normalized aliases 和 provenance，不能改变 tenant/permissions/source URI。中医药宿主可定义：

- 书名、篇章、版本、作者/年代；
- 证候、症状、治法、方剂、药材、剂量、炮制法；
- 古今词/别名/异体字映射；
- 内容类型（原文、注释、医案、现代研究、法规等）；
- 人工审核状态和数据来源等级。

自动抽取实体不是事实源。保存 extractor/model/version/confidence，必要字段进入人工抽检；生成回答时始终以可引用 displayText 为证据。

### 10.5 幂等和检查点

将当前重建流程里不稳定的时间随机 ingestion id 替换为可推导键：

```text
ingestionKey = HMAC(
  knowledgeSpaceId,
  profileId,
  documentId,
  documentRevisionId,
  sourceContentSha256
)
```

同一输入重试应返回现有 build 或安全续跑；源 hash/Profile 改变则自然产生新 build。检查点至少覆盖：resolved、extracted、normalized、chunked、enriched、embedded、staged、validated、ready。

### 10.6 ZIO 并发模型

- 用 `ZStream` 表达 document/chunk 批次，依靠 pull/backpressure 控制上游；
- `mapZIOPar(n)` 做有界并行且保持结果顺序；只有确实不要求顺序时才用 unordered 版本；
- Provider 的全局并发/RPM/TPM 再由 `Semaphore.withPermit` 或明确 limiter 控制；中断和失败必须释放 permit；
- `Schedule` 只重试 typed transient errors，并加指数退避、jitter、最大次数/总时长；
- HTTP client、连接池、临时文件、parser process 用 `Scope` / `ZLayer.scoped` 管理；
- 外部调用完成并验证后才进入短数据库事务；
- 取消构建时中断子 fibers、停止新批次、保留可识别的 staging/checkpoint，并把 build 标记为 Cancelled/Failed；
- 长时、跨进程批任务复用现有 durable command/workflow 基础设施，不新造内存 job scheduler。

---

## 11. 索引版本、数据库与零停机切换

### 11.1 两层版本模型

需要同时保留：

- **Document Revision / Index Version**：处理单篇文档更新、撤回和局部重试；
- **Index Profile**：处理整个 Knowledge Space 的模型、维度、切分、标准化、schema、fusion 等变化。

检索只读 `KnowledgeSpace.activeProfileId` 指向的完整数据集。同一请求开始时解析一次 active profile，并贯穿所有候选、rerank、expansion 和 citation；处理中即使管理员切换 profile，该请求也不能半途跨版本。

### 11.2 概念表模型

在不修改已发布 V001 baseline 的前提下，通过追加迁移引入 v2 结构。概念上至少需要：

```sql
agent_knowledge_spaces(
  tenant_id, knowledge_space_id,
  active_profile_id, revision,
  created_at, updated_at
)

agent_knowledge_profiles(
  tenant_id, knowledge_space_id, profile_id,
  profile_version, status,
  dense_identity_json, sparse_identity_json,
  lexical_identity_json, chunking_identity_json,
  normalization_identity_json, metadata_schema_json,
  fusion_identity_json,
  created_at, ready_at, activated_at, failure_json
)

agent_knowledge_profile_documents(
  tenant_id, knowledge_space_id, profile_id,
  document_id, document_revision_id,
  source_uri, source_sha256,
  permissions, metadata,
  status, expected_chunks, staged_chunks,
  created_at, updated_at
)

agent_knowledge_profile_chunks(
  tenant_id, knowledge_space_id, profile_id,
  document_id, document_revision_id, chunk_id,
  display_text, dense_text_or_recipe, lexical_text,
  search_vector, dense_embedding,
  sparse_embedding,
  permissions, metadata, lineage,
  display_sha256, dense_sha256, lexical_sha256,
  created_at
)

agent_knowledge_profile_activation_audit(
  tenant_id, knowledge_space_id,
  old_profile_id, new_profile_id,
  expected_space_revision, activated_by, reason,
  activated_at
)
```

物理上可以把 staging 和 active/superseded 分表，也可以在 immutable profile chunk 表上按 profile/status 索引；选择依据是写放大、查询计划、清理复杂度和现有 store 复用。首选最小可行结构：不可变 profile chunks + 独立 space pointer + profile document census，避免为每个状态复制 schema。

### 11.3 索引与约束

最低约束：

- space：`PRIMARY KEY (tenant_id, knowledge_space_id)`；
- profile：`UNIQUE (tenant_id, knowledge_space_id, profile_id)` 与 `UNIQUE (..., profile_version)`；
- chunk：`PRIMARY KEY (tenant_id, knowledge_space_id, profile_id, document_id, chunk_id)`；
- source revision：在同一 profile/document 上唯一；
- permissions 使用 GIN，metadata 使用经过查询模式验证后的 GIN/表达式索引；
- dense 使用 cosine HNSW，参数先沿用当前 `m=16, ef_construction=64`，以容量基准调整；
- lexical 使用生成/维护的 `tsvector` GIN；
- sparse 只有功能启用且样本通过时才建 ANN index，避免空能力拖累写入和维护；
- 常用过滤列必须是普通列，不能把所有租户/状态/版本条件藏进 JSONB。

`metadata` 的值和 key 都要有大小/数量限制。只为真实查询建立索引；使用 `EXPLAIN (ANALYZE, BUFFERS)` 和生产形态数据确认 query plan，不能因为 JSONB 灵活就预建大量无用 GIN。

### 11.4 原子激活协议

```text
1. create immutable BUILDING profile
2. snapshot expected document set/revisions
3. process every eligible document into profile staging/chunks
4. validate:
   - document census complete
   - expected/staged chunk counts match
   - dense/sparse dimensions and identities match
   - no missing ACL/source/hash/lineage
   - evaluation/security/capacity gates pass
5. mark profile READY
6. short transaction:
   UPDATE knowledge_spaces
      SET active_profile_id = :new, revision = revision + 1
    WHERE tenant_id = :tenant
      AND knowledge_space_id = :space
      AND revision = :expectedRevision;
   insert activation audit
7. new requests resolve new profile; in-flight requests finish on pinned old profile
8. retain old profile for rollback window
9. retire and purge according to policy
```

CAS 更新 0 行表示并发冲突，必须重新读取状态，不能覆盖另一个管理员的激活。

### 11.5 回滚

回滚不重建向量：只要旧 profile 仍完整且未过保留期，就对 space pointer 做一次经过审计的 CAS 切换。触发条件可包括：

- 安全泄漏或 ACL 回归；
- no-answer/引用质量越过红线；
- p95/p99 延迟或数据库资源持续超标；
- provider/profile identity 不一致；
- 线上 canary 相比基线显著退化。

回滚后保留失败 profile 供调查，禁止立即物理删除证据。

### 11.6 从 0.9 baseline 迁移

- `0.9.0` 尚未发布，当前 `optional/pgvector_1024/V001__agent_knowledge_0_9_baseline.sql`
  是 fresh-install Space/Profile 基线；冻结发布后不得修改；
- 已使用旧 0.9 candidate 或 0.8 数据库的宿主必须建立新库，并通过 `KnowledgeSourceResolver` 从权威源重建，
  不对开发候选库执行 `flyway repair`；
- 0.9.0 发布后的 schema 变更才使用 `V002__...` 等追加迁移；
- 不直接 SQL 复制旧向量到身份不同的新 profile；
- 新版本兼容声明、migration checksum test、fresh-install test 和真实 Postgres/pgvector integration test 必须同时更新。

### 11.7 RLS 的位置

现有显式 tenant/permissions SQL 条件是主安全边界。PostgreSQL RLS 可以作为 defense in depth，但不是替代品。若启用：

- 使用每事务 `SET LOCAL` 或等价安全 session context；
- 在连接池复用、事务取消、异常回滚和并发租户测试中证明 context 不泄漏；
- table owner/BYPASSRLS 行为和 maintenance role 明确；
- RLS 与应用过滤均有 cross-tenant integration test。

在这些证据齐备前，不要仅为“看起来更安全”引入 RLS 复杂度。

---

## 12. 缓存、删除、撤回与保留

### 12.1 缓存键

Embedding/cache/retrieval cache 至少包含：

- tenant 或隔离后的 knowledge space；
- active profile id；
- provider/model/revision/dimension/output kind；
- role、instruction id/version；
- normalized input HMAC/hash；
- permission scope fingerprint；
- retrieval policy/version。

不能跨权限 scope 复用最终 retrieval result。Embedding 的纯文本缓存如果跨租户共享，需要独立隐私评审；默认租户隔离。

### 12.2 删除传播

文档删除/撤回必须产生可审计 tombstone，并传播到：

- 当前 active profile；
- rollback window 内仍可恢复的 superseded profiles；
- staging/building profiles；
- embedding/retrieval/context cache；
- 对象存储或源系统的保留流程；
- dashboard/search projection。

撤回后的任何 profile 回滚都不能让已删除内容“复活”。因此 tombstone 是 space 级约束，检索查询除 profile 条件外还必须排除全局 withdrawn document revision。

### 12.3 Retention worker

使用现有 durable command/workflow 或宿主调度器执行：扫描到期 profile → 验证不是 active/rollback-pinned/legal-hold → 小批删除 chunks/index rows → 删除派生 artifact → 写审计。每批短事务、可重试、可中断，有 backlog/失败指标。不要在应用启动时同步清理整个语料库。

---

## 13. 安全模型与威胁控制

### 13.1 不变量

1. `RetrievalScope` 来自可信 runtime，不来自用户 query 或 LLM planner。
2. ACL 在 dense、lexical、sparse、exact、fetch、parent/neighbor expansion 每一步生效。
3. reranker 只重排输入 id，不能添加候选、文本、citation 或权限。
4. 文档、OCR、metadata、Provider response、网页和工具输出全部是不可信数据。
5. 检索证据不能变更系统指令、工具 allowlist、approval、side-effect policy 或 tenant。
6. source URI 只由批准的 resolver scheme/allowlist 解析，防止 SSRF 和路径穿越。
7. 原文、query、实体、token 和 API 响应默认不进入低敏 telemetry。

### 13.2 摄取安全

- magic bytes 与声明 MIME 双重校验；
- 文件大小、页数、解压比例、嵌套层数、图片像素和解析时间限制；
- parser/OCR 隔离，禁网络或仅 allowlist；
- SHA-256 完整性、来源、上传者、批准记录、parser version；
- 检测隐藏层、不可见 Unicode、零宽字符、异常重复、恶意 URI 和已知提示注入模式；
- 扫描是 defense in depth，不能把“没命中正则”当成可信；
- 可疑文档进入 quarantine，不能直接激活。

### 13.3 RAG poisoning 与间接提示注入

OWASP 明确把恶意文档、隐藏指令、跨租户召回、权限撤销滞后、缓存泄漏和来源篡改列为 RAG 需要单独测试的攻击面。对应控制：

- source allowlist + provenance + hash；
- evidence delimiter 和“只视为数据”标记；
- context 总大小上限，防止证据淹没系统提示；
- 生成后做 citation/claim/安全策略校验；
- 对带工具权限的高风险路径增加 action guard：根据原始用户意图和 trusted policy 审批，而不是相信 retrieved text；
- red-team dataset 覆盖跨 chunk 拼接注入、编码/Unicode 混淆、伪造 citation、诱导工具调用；
- 发生 poisoning 时能 quarantine 文档、失效 cache、查询受影响 trace/request id，并切回安全 profile。

### 13.4 医疗/中医药场景额外边界

- 明确知识问答、学术检索与个体诊疗建议的产品边界；
- 数据集区分古籍原文、现代指南、研究证据和用户自建内容，回答展示来源等级；
- 冲突证据不能被“多数投票”自动抹平，Evidence Bundle 保留不同来源；
- 高风险建议需要宿主侧免责声明、升级/人工审核和地区法规评估；
- 不在公共框架宣称临床有效性；领域专家审核属于发布证据，而非自动指标替代品。

---

## 14. 可观测性

### 14.1 Span 模型

建议在一个 retrieval root span 下建立：

```text
rag.retrieve
  rag.plan
  rag.embed_query
  rag.retrieve_dense
  rag.retrieve_lexical
  rag.retrieve_sparse        (optional)
  rag.retrieve_exact         (optional)
  rag.fuse
  rag.rerank
  rag.expand
  rag.assemble_evidence
```

摄取 root span：

```text
rag.ingest_document
  rag.resolve_source
  rag.extract
  rag.normalize
  rag.chunk
  rag.enrich                 (optional)
  rag.embed_documents
  rag.stage
  rag.validate
```

### 14.2 可记录属性

允许的低敏、低基数字段：

- operation outcome/error category/degraded stage；
- provider/model family 的注册 ID；
- profile version、retrieval policy version、mode；
- input count/token count、candidate count、dedup count、final evidence count；
- stage duration、retry count、cache hit、estimated cost；
- build document/chunk counts、backlog、activation result；
- 是否发生 refusal、fallback、truncation。

默认禁止：原始 query、document text、dense/sparse values、token 字符串、完整 URI、tenant/customer 名、permission id、医疗实体。调试采样必须由宿主显式开启、脱敏、限制保留期和访问权限。

### 14.3 Metrics

- retrieval requests / errors / degraded / refused；
- 各阶段 p50/p95/p99 latency；
- 每路 candidate count、empty rate、overlap、RRF/rerank rank movement；
- cache hit/miss、provider rate limit/retry/timeout；
- query/document embedding tokens 与估算成本；
- evidence token utilization、dedup、truncation；
- build throughput/backlog/failure、profile ready/activation/rollback；
- ACL rejection、quarantine 和 withdrawal propagation delay。

metrics label 只使用 allowlist 枚举，绝不能把 query、documentId、tenantId、profile UUID 直接作为 label，避免敏感泄漏和 cardinality 爆炸。

### 14.4 Langfuse 的边界

Langfuse/OTel 可以映射 retrieval、generation、score 和 dataset experiment，适合线上 trace 关联与调试；但它不是框架发布门禁的唯一事实源。zyblw-agent 的固定数据集、阈值、原始结果和 release gate 仍保存在可版本化的本地/CI/PostgreSQL 评测事实中。外部平台不可用时，核心检索和本地审计必须继续工作。

---

## 15. 评测体系

### 15.1 数据集设计

初始建立 100–300 条经过人工核验的领域查询，之后按失败样本持续增长。每条至少包含：

```text
case_id
query
query_type
tenant/permission fixture
knowledge_space/profile fixture
relevant chunks with graded relevance 0..3
supporting citations / required claims
forbidden document/chunk ids
answerable: yes/no/ambiguous
expected source diversity or conflict notes
tags: OCR/table/old text/alias/exact/comparison/multi-hop/security/long-tail
reviewer + review date + dataset version
```

数据分层：

- 典型事实问答；
- 专名/方剂/药材/异体字/别名；
- 精确出处、页码、原文查找；
- 比较、归纳、多证据问题；
- 无答案和证据冲突；
- PDF/OCR/表格/跨页；
- ACL、撤权、删除、cache isolation；
- poisoning/indirect prompt injection/citation tampering；
- regression：线上真实失败样本脱敏后固化。

训练/调参集和最终门禁集分开，避免对几十条黄金问题过拟合。

### 15.2 指标

#### Retrieval

- Recall@20、Recall@50；
- MRR@10；
- graded nDCG@10；
- Precision@k；
- 可选 MAP；
- source coverage、duplicate/redundancy；
- rerank lift：相关 chunk 的 rank 改善和坏降级比例；
- exact/alias/OCR/query-type 分桶结果。

#### Evidence 与答案

- citation retrieval：所需引用是否进入 Evidence Bundle；
- citation precision：引用是否真的支持对应主张；
- claim coverage/faithfulness；
- answer correctness（规则、专家或经校准 judge，多种信号交叉）；
- no-answer refusal precision/recall；
- conflict preservation 和不确定性表达。

LLM judge 不能成为唯一 oracle。至少用人工标注子集定期校准 judge 与专家一致性，并固定 judge model/prompt/version。

#### 安全

- cross-tenant / forbidden hit 必须为 0；
- stale permission、withdrawn document、cache leakage 必须为 0；
- reranker candidate injection 必须为 0；
- poisoned evidence 导致越权工具调用必须为 0；
- citation id/source tampering 必须被检测；
- malformed/nonfinite Provider response 必须 fail closed 或按公开策略降级。

#### 资源与稳定性

- end-to-end 和各阶段 p50/p95/p99；
- query/document tokens、provider calls、每千次查询和每百万 chunk 重建成本；
- DB CPU/IO/buffer/index size、HNSW build time；
- 同请求重复 N 次的 top-k overlap/rank stability；
- provider timeout/rate-limit 下的成功率和取消延迟；
- 大租户、强过滤、小权限集合下的 ANN recall。

### 15.3 消融顺序

一次只打开一个主要变量：

| 实验 | 配置 |
| --- | --- |
| A | Dense only |
| B | Dense + PostgreSQL FTS |
| C | B + weighted RRF |
| D | C + reranker |
| E | D + contextualized dense/lexical text |
| F | D/E + Qwen model sparse |
| G | 最优基线 + deterministic query planner |
| H | G + model-assisted rewrite（如仍有证据） |

每一步报告总体与 query-type 分桶、置信区间/重复波动、延迟和成本。若质量提升只来自某个小桶，可用路由按需启用，不必全局支付成本。

### 15.4 发布门禁

门禁分三类，不能用一个综合分互相抵消：

- **安全硬门禁**：跨租户、撤回内容、候选注入、非有限值、迁移一致性；任一失败即阻断；
- **质量门禁**：总体和关键桶不低于 baseline，核心指标达到预先登记阈值；
- **资源门禁**：p95/p99、成本、数据库容量、批量重建时间不越预算。

线上 canary 只作为补充证据。没有固定离线门禁时，不能用“看起来回答不错”批准全量切换。

---

## 16. 配置建议

### 16.1 Profile 配置示例

```hocon
zyblw-agent.rag.spaces.tcm-primary {
  active-policy = "hybrid-rerank-v1"

  embedding {
    provider = "qwen"
    model = "<explicit-model-id>"
    region = "beijing"
    dimension = 1024
    output = "dense"
    query-instruction-id = "tcm-retrieval-query-v1"
    max-batch-texts = 20
    max-batch-tokens = 131072
  }

  chunking {
    strategy = "document-structure-v2"
    tokenizer = "<provider-compatible-tokenizer-id>"
    child-max-tokens = 512
    neighbor-radius = 1
  }

  retrieval-policies.hybrid-rerank-v1 {
    dense-candidates = 60
    lexical-candidates = 60
    sparse-enabled = false
    fused-candidates = 80
    rerank-top-n = 8
    final-evidence-items = 8
    final-evidence-tokens = 6000
    rrf-k = 60
    dense-weight = 1.0
    lexical-weight = 1.0
  }
}
```

这只是首轮实验配置。实际实现应复用项目现有 config loader、secret reference、validation 和 runtime override 体系，避免创建第二套配置框架。model id 必须使用 Provider 当前有效的显式值，不能从本文复制占位符。

### 16.2 限额

服务端硬上限与 profile 建议值分离：

- max query chars/tokens；
- max subqueries；
- max candidates per branch / fused / rerank；
- max document bytes/pages/pixels；
- max chunk tokens、metadata keys/value size；
- max sparse NNZ；
- max evidence items/tokens；
- max ingestion parallelism、provider calls、retry duration。

所有越限返回 typed error 或明确裁剪记录；禁止默默改变请求。

---

## 17. 失败语义与降级矩阵

| 阶段 | 失败 | 默认处理 | 必须记录 |
| --- | --- | --- | --- |
| Query planner | timeout/schema invalid | 回退单查询确定性计划 | reason、latency、fallback |
| Query embedding | provider/identity/dimension failure | 检索失败；若 policy 明确可 lexical-only degraded | provider category、profile |
| Dense ANN | DB failure | 默认整体失败；显式 policy 可 lexical degraded | stage、query plan |
| Lexical | DB failure | 默认整体失败；显式 policy 可 dense degraded | stage |
| Sparse | unsupported/rate limit | 若 sparse 为 optional 则回退 dense+FTS | capability/fallback |
| Reranker | timeout/malformed/nonfinite | 使用 RRF 顺序或 fail closed，由 policy 决定 | degraded stage |
| Expansion | 某邻居不存在/撤权 | 丢弃该扩展，不影响 seed；不得越权补文本 | missing/forbidden count |
| Context budget | 超限 | 确定性裁剪并保留 truncation reason | tokens/items |
| Ingestion parser | partial/low confidence | fallback 或 quarantine | page/stage/parser |
| Embedding batch | 部分/乱序/malformed | 整批不入库；可按幂等批次重试 | batch id |
| Profile activation | census/gate/CAS failure | 不切换，旧 profile 保持 active | expected/actual revision |

降级必须出现在返回的 `EvidenceBundle.degradedStages` 和 telemetry 中。调用者可配置“允许降级回答”或“只接受完整策略”，但不能误报为完整成功。

---

## 18. 测试规范

### 18.1 单元与属性测试

- QueryNormalizer 幂等：`normalize(normalize(x)) == normalize(x)`；
- chunk 边界、token hard max、heading/page/bbox lineage；
- Profile canonical serialization/hash 稳定；
- RRF 去重、权重、tie-break、空分支；
- sparse entries 唯一/排序/范围/有限值/NNZ；
- ContextAssembler 在各种预算下确定性、citation 不漂移；
- planner validator 不允许扩权或超预算；
- cache key 覆盖 role/instruction/profile/permission fingerprint。

### 18.2 Provider contract tests

对 OpenAI-compatible、Qwen embedding、Cohere/Qwen rerank 共用 conformance suite：

- 正常、空输入、最大 batch、最大 token；
- response missing/duplicate/out-of-order index；
- dense dimension mismatch、NaN/Infinity；
- sparse negative/out-of-range/duplicate index、zero/nonfinite value、NNZ 超限；
- usage 缺失/异常；
- 400/401/403/404/429/5xx/timeout/network reset；
- retry-after、cancel during request/backoff；
- endpoint/secret redaction；
- 不支持 role/output/instruction 时在发请求前失败。

Live smoke 使用真实 Provider 但不替代 deterministic contract tests；凭据缺失时应明确 skip，不伪造通过。

### 18.3 PostgreSQL integration tests

- 每条召回分支的 tenant/permission prefilter；
- 强过滤下 HNSW 返回数量和 recall；
- dense/FTS/sparse/exact RRF 的稳定顺序；
- profile census、READY 门槛、CAS activation；
- 激活并发冲突；
- 请求 pin 旧 profile 与新请求读新 profile；
- 回滚后结果恢复；
- withdrawn tombstone 在 old/new/building profile 都生效；
- migration fresh install/checksum/rollback procedure；
- retention worker 不删除 active、legal hold、rollback-pinned profile；
- 若启用 RLS，连接池复用/取消/异常下无 session context 泄漏。

### 18.4 Failure injection

- parser/OCR 进程 crash、超时、输出截断；
- 第 N 个 embedding batch 失败；
- staging 成功但 ready 前进程终止；
- activation transaction 冲突；
- provider 限流持续超过 retry budget；
- DB connection 丢失、statement timeout；
- shutdown/cancel 时无 orphan fiber、permit、临时文件或半激活状态。

### 18.5 Security corpus

- 恶意/损坏/超大/压缩炸弹 PDF；
- 隐藏层、零宽字符、白色文字、Unicode 混淆；
- 文档内“忽略系统指令”、伪造 role/tool/citation；
- 多 chunk 协同注入；
- tenant A query 命中 tenant B；
- 撤权后 cache 与 neighbor fetch；
- planner 提议扩大 scope/读取任意 URI；
- reranker 返回未知 candidate id；
- 删除后回滚旧 profile 尝试复活内容。

---

## 19. 分阶段实施路线

每个 Phase 都是可合并、可发布、可回滚的纵向切片。不得跨 Phase 一次性重写。

### Phase 0：事实基线与领域评测

交付：

- 把现有 dense+FTS+RRF+rerank 配置固化为 baseline profile 描述；
- 建 100–300 条初始领域数据集，先完成核心 100 条；
- 补 query-type、graded relevance、no-answer、forbidden、安全样本；
- 记录当前质量、p95/p99、成本、索引大小和全量重建耗时；
- 建立 ablation runner 输出和 release evidence 格式。

验收：结果可重复；数据集版本化；现有生产行为不改变；安全硬门禁可在 CI 运行。

### Phase 1：Embedding v2 与 Qwen adapter

交付：

- 新 v2 ADT/SPI、capability validation、旧 SPI adapter/deprecation；
- Qwen native embedding dense adapter，query/document/instruction；
- Qwen native rerank adapter 和 instruction；
- provider conformance、redaction、timeout/retry/cancel tests；
- 仍只使用 1024 dense + PostgreSQL FTS，sparse 不上线。

验收：旧 consumer 编译通过；Qwen live smoke 可选通过；query/document 请求在 adapter 录制测试中可证；无敏感日志。

### Phase 2：Evidence Bundle 与文本表示

交付：

- `ChunkRepresentations`、transform identity/hash；
- `CandidateBudgets`、`RetrievalPlan`（先默认/确定性）；
- `ContextAssembler`、seed/expanded provenance、token budget/truncation；
- current `MemoryRagContextSourceResolver` 改为消费 Evidence Bundle；
- optional host `DocumentEnricher`，中医药 schema 在示例/宿主而非公共核心。

验收：引用只对应 displayText；预算确定性；ACL 在 expansion 重验；旧简单 API 保持可用。

### Phase 3：Knowledge Space/Profile 蓝绿索引

交付：

- public Profile/Space ADT 与 store；
- append-only V002 migration；
- stable ingestion key/checkpoints/census；
- 全空间 build → READY → CAS activate → rollback；
- source resolver、withdrawal propagation、retention worker；
- v1/v2 shadow comparison 与迁移 runbook。

验收：全量换 embedding identity 无混合活跃窗口；并发切换只有一个成功；in-flight request 版本一致；撤回内容无法通过回滚复活。

### Phase 4：可选 Qwen sparse 第三路召回

交付：

- sparse ADT/validation/persistence/query；
- pgvector sparse schema/index 与 NNZ capacity test；
- dense+FTS+sparse weighted RRF；
- sparse optional fallback 和 stage telemetry；
- D/E/F 消融报告。

启用门槛：安全不退化；关键桶 nDCG/Recall 有预先定义的显著净增益；p95、存储、索引构建和成本在预算内。否则能力保留为 experimental/off。

### Phase 5：确定性 Query Planner

交付：normalizer、宿主词典、exact/comparison/explanation 路由、最多 3 个 bounded subquery、validator/fallback、按 query type 的 policy。

验收：G 相比最优基线有稳定增益；planner 不可修改 trusted scope；成本/延迟可接受。

### Phase 6：模型辅助 rewrite/contextualization（条件）

只有 Phase 5 后仍有明确缺口才进入：typed output、缓存、超时、fallback、prompt/version、单独预算和 ablation。默认不进入所有查询路径。

### Future：需新 ADR

GraphRAG、agentic iterative retrieval、multimodal、late interaction、独立向量服务都需新的边界分析、故障模型、安全模型、模块理由与数据集证据。

---

## 20. Cursor 实施上下文包

为避免编码代理一次装入整个仓库，每个 Phase 使用独立上下文包。

### 20.1 每次固定提供

1. 根 `AGENTS.md`；
2. `.agents/skills/zyblw-agent-development/SKILL.md`；
3. 本文目标架构；
4. `docs/architecture.md`、`docs/maturity-and-roadmap.md`、`docs/compatibility.md`、`docs/releasing.md`；
5. 当前 Phase 的明确范围、非目标、验收命令和允许修改的文件列表；
6. `git status --short`，提醒现有用户改动不可覆盖。

### 20.2 Phase 1 建议源码上下文

- `modules/agent-rag/.../Rag.scala`
- `modules/agent-rag/.../EmbeddingGovernance.scala`
- `modules/agent-providers/.../OpenAICompatibleEmbeddingService.scala` 或实际 adapter 文件
- Provider endpoint/config loader 及对应 specs
- `modules/agent-rag/.../ModelReranker.scala`
- `modules/agent-rerank` adapter 与 specs
- `docs/embedding-governance.md`、`docs/reranker.md`、`docs/providers.md`

### 20.3 Phase 2 建议源码上下文

- `Rag.scala`、`RetrievalQuery.scala`、`RetrievalLineage.scala`
- `DefaultRetriever` 所在文件
- `MemoryRagContextSourceResolver.scala`
- `DocumentStructureChunker.scala`、`MarkdownStructureChunker.scala`
- `KnowledgeTools.scala`
- 对应 RAG/security/chunker specs

### 20.4 Phase 3 建议源码上下文

- `KnowledgeIndex.scala`、`KnowledgeReindexService.scala`、`KnowledgeIndexDirectory.scala`
- `PostgresPgVectorStore.scala`
- 当前 optional pgvector V001 migration（只读）
- migrations/integration specs
- `docs/database-schema.md`、`docs/database-migrations.md`、`docs/persistence.md`

### 20.5 Phase 4–6 建议上下文

- Phase 4：Embedding v2、Postgres store/migration、retriever/RRF、eval runner；
- Phase 5：`RetrievalQuery.scala`、RAG façade、typed config/validation、eval query-type fixtures；
- Phase 6：Provider/model runtime contract、contextualizer/rewrite cache、安全与成本 tests。

### 20.6 给 Cursor 的任务模板

```text
目标：只实施 RAG Target Architecture 的 Phase <N> / slice <X>。

先读：AGENTS.md、zyblw-agent-development skill、rag-runtime-target.md 的相关章节，
以及下面列出的源码和测试。先报告当前事实与拟修改文件，再编码。

范围：<明确列出>
非目标：不增加 artifact；不修改已发布 V001；不实现未来 Phase；不覆盖无关工作树修改。
兼容：保留现有公共 API/配置/迁移行为；若需破坏性变更先停下并写 ADR/迁移计划。
安全：tenant/permissions 必须在每条召回/fetch/expansion 前生效；Provider/LLM 输出不可信。
验证：先 narrow compile/test，再 module test，最后执行与改动风险相称的集成/consumer test。
交付：源码、测试、文档、变更日志、执行过的命令、未完成证据与回滚说明。
```

### 20.7 单切片完成定义

- 公共类型和错误语义有 Scaladoc/文档；
- happy path、边界、失败、取消、安全均有测试；
- 不增加无用抽象或依赖；
- 没有修改 V001 和用户无关改动；
- config/migration/consumer compatibility 已验证；
- telemetry 无敏感原文且 label 有界；
- maturity 文档只声明已由代码和测试证明的能力；
- 未跑的真实 Provider/宿主/Postgres 证据明确列为未完成，不能写成通过。

---

## 21. 需要提前做出的 ADR 决策

进入编码前建议把以下决定固化为一份 RAG evolution ADR，本文作为实施手册：

1. 采用 Knowledge Space + immutable Index Profile + CAS pointer；
2. Embedding v2 的兼容方式：新 trait 还是带默认方法的演进；
3. v2 PostgreSQL 采用不可变 profile chunk 单表还是 staging/active 双表；
4. sparse 的物理类型、dimension/NNZ 约束和 fallback policy；
5. 旧 v1 store 的 dual-read/shadow 和退役期限；
6. Evidence Bundle 是否作为新的公共稳定 ADT，还是先 internal/beta；
7. 领域 `DocumentEnricher` 的公共 SPI 稳定等级；
8. 哪些评测阈值是 release hard gate，哪些只告警。

建议的默认选择：1 采用；2 采用新 v2 trait + 旧 adapter；3 采用 immutable profile chunk + space pointer；4 sparse 初始 experimental/off；5 仅 shadow 不混合结果；6 先 Beta；7 Experimental；8 安全为硬门禁，质量/资源阈值在 Phase 0 数据出来后登记。

---

## 22. 验收清单

整个目标架构完成时，至少应能回答并用自动证据证明：

- 能否在不中断查询、不混合 embedding identity 的情况下切换整个知识空间？
- 请求是否从开始到结束固定使用同一 active profile？
- query/document/instruction/dense/sparse 是否由公共契约显式表达和验证？
- Qwen 不可用时，降级行为是否明确、可观测且不伪装完整成功？
- dense、FTS、sparse、exact、fetch、neighbor 是否全部前置同一 ACL？
- reranker 是否无法注入候选或非有限分数？
- citation 是否始终指向 displayText 和可验证 source locator？
- 文档撤回后，旧 profile、cache 和回滚是否都无法恢复它？
- 恶意 PDF 和间接提示注入是否进入 CI security corpus？
- 每项算法增强是否有独立消融、质量增益、延迟和成本报告？
- p95/p99、Provider 限流、DB 资源、重建吞吐是否达到登记预算？
- 对真实宿主没有跑过的证据是否仍诚实标记为未验证？

只有这些问题有可复现答案，RAG 能力才可以从 Beta 逐步提升成熟度。

---

## 23. 官方资料与设计依据

以下资料用于核对模型能力、数据库限制、检索算法、ZIO 运行语义、解析器能力、安全和可观测性；最终参数仍以项目固定数据集和实际部署容量测试为准。

- Alibaba Cloud Model Studio：[Qwen3 Embedding / Rerank 总览](https://help.aliyun.com/zh/model-studio/qwen3-7-text-embedding)、[文本 Embedding 指南](https://help.aliyun.com/zh/model-studio/embedding?disableWebsiteRedirect=true)、[同步 Embedding API](https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api)、[文本 Rerank API](https://help.aliyun.com/zh/model-studio/text-rerank-api)
- pgvector：[官方仓库与索引、过滤、稀疏向量限制](https://github.com/pgvector/pgvector)
- PostgreSQL：[全文检索](https://www.postgresql.org/docs/current/textsearch.html)、[Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- Elastic：[Hybrid Search](https://www.elastic.co/docs/solutions/search/hybrid-search)、[Reciprocal Rank Fusion](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion)
- Anthropic：[Contextual Retrieval 说明](https://www.anthropic.com/engineering/contextual-retrieval)、[Contextual Embeddings cookbook](https://platform.claude.com/cookbook/capabilities-contextual-embeddings-guide)
- ZIO：[ZStream](https://zio.dev/reference/stream/)、[Stream operations / mapZIOPar](https://zio.dev/reference/stream/zstream/operations/)、[Semaphore](https://zio.dev/reference/concurrency/semaphore/)、[Schedule](https://zio.dev/reference/schedule/)、[Scope](https://zio.dev/reference/resource/scope/)、[ZLayer](https://zio.dev/reference/contextual/zlayer/)
- OWASP：[RAG Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/RAG_Security_Cheat_Sheet.html)、[LLM Prompt Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)
- Docling：[官方文档](https://docling-project.github.io/docling/)、[PDF/OCR/Table pipeline options](https://docling-project.github.io/docling/reference/pipeline_options/)
- Langfuse：[Observability data model](https://langfuse.com/docs/observability/data-model)、[Observation types](https://langfuse.com/docs/observability/features/observation-types)、[Evaluation core concepts](https://langfuse.com/docs/evaluation/core-concepts)
