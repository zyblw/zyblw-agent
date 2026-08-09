# PDF RAG 生产流水线

> 状态：0.4 开发主线契约
> 事实来源：`agent-rag`、`agent-document-loaders`、`agent-postgres`、optional pgvector migrations 与真实 PostgreSQL Testcontainers

本指南回答一个具体问题：一批 PDF 从目录/对象存储进入框架后，如何变成可撤回、可授权、可追溯、可评测的
知识索引，以及 Agent 如何在需要时检索并给出带页码/bbox 的回答。

## 1. 总体流程

```text
业务授权 + 文档目录/对象存储
  -> DocumentInput(ZStream[Byte])
  -> 解析路由：数字 PDF / OCR / 选择性 VLM
  -> SourceDocument(Markdown + DocumentStructure)
  -> DocumentStructureChunker(block/parent/page/bbox/neighbor)
  -> GovernedEmbeddingService(cache/quota/model identity)
  -> KnowledgeIndexer(Building -> stage -> activate)
  -> PostgreSQL FTS + pgvector + lineage
  -> ACL 前置的 hybrid weighted RRF
  -> model/cross-encoder rerank
  -> 有界 parent/neighbor expansion
  -> citation(page/bbox/source) + Context budget
  -> Agent Tool / ContextSource -> 大模型回答
```

不要把它简化为“PDF 转文本后全部塞进向量库”。原文身份、版本、权限、页面几何、切分策略、Embedding 模型和 active 发布都是一等事实。

## 2. 框架与业务的责任边界

| 框架负责 | 业务负责 |
|---|---|
| Source/Loader/Chunker/Embedding/Store/Retriever SPI | 文档来源许可、tenant 和 ACL 映射 |
| 流式背压、取消、容量、超时、低敏错误 | 对象存储凭据、保留期、数据地域 |
| 结构谱系、版本索引和原子 active 切换 | 语料分类、领域 metadata、取消授权 |
| hybrid/rerank/扩展/citation 的安全顺序 | 实际模型 endpoint、Secret、质量/延迟/成本 SLO |
| 通用 eval 指标和发布门禁 | 真实问题集、金标证据、拒答规则 |

文档原始字节通常放在不可变对象存储/Artifact Store，数据库只保存稳定 URI、SHA-256、MIME、大小、权限和版本。不应在每个向量行重复保存
PDF 二进制或整份 Markdown。

## 3. 解析、OCR 与 VLM 路由

1. 数字 PDF：默认使用 Docling standard pipeline，`doOcr=true, forceOcr=false`；已有可用文本层时不强制 OCR。
2. 扫描 PDF：Docling 在无文本层的页面运行 OCR；OCR 语言必须按语料配置，并在真实中英文样本上评测。
3. 图表/复杂页：先用布局/OCR 得到页面元素；只对低置信页、图片或表格区域调用 VLM，不默认把整本 PDF 交给远程模型。
4. VLM 输出：保存 model/version/prompt hash/confidence 和页面 Artifact；它只是不可信内容，不能生成 tenant、ACL 或 authority。

`DoclingDocumentLoader` 同时请求 Markdown 和无损 JSON。JSON 投影为类型化 `DocumentBlock`，保留父节点、标题路径、页码、bbox 和 block ID；原始 Provider JSON
不进 metadata 和日志。

## 4. 切分和 Embedding

`DocumentStructureChunker` 优先按 block/章节切分，合并同父级相邻小块，只对超大单 block 使用 overlap。表格不应从行中间切开；当后续加入 token-aware
切分时，tokenizer 必须与 Embedding 模型对齐，切分策略变更必须提升 `strategyId` 并新建索引版本。

Embedding 不只是“调一个 API”：

- manifest 固化 provider/model/dimension/max batch/切分策略；
- `GovernedEmbeddingService` 用 tenant+model+dimension+text hash 做精确缓存，并以 request ID 幂等预留配额；
- 不同模型、维度或预处理策略不共用索引；
- 用真实业务评测比较多语言召回，不用排行榜代替自己的语料。

## 5. PostgreSQL / pgvector 设计

0.4 optional location 只有一份 fresh-install V001，固定在 `zyblw_agent_knowledge` schema 一次建立 manifest、staging、
active chunks、FTS/HNSW 和 parent/ordinal/previous/next/heading/page/origin/block 谱系，vector 类型显式来自 `public`。
staging/active 字段对称，因此 `activate` 可在同一短事务内发布向量和谱系；
正式 chunk 使用 tenant/document/chunk 复合身份，局部 ID 在另一文档复用不会覆盖或串联。

`vector(N)` 维度是 schema 契约。当前生产基线为 1024；选择其他模型时必须发布新的 minor 基线，不在运行时混存。大规模首次导入先批量加载、再建索引；
持续更新需监测 WAL、autovacuum、HNSW recall/latency 和连接池。

## 6. 检索、重排和扩展的正确顺序

1. 从已验签身份构造 `RetrievalScope(tenantId, permissions)`；
2. SQL 在计算向量距离和 FTS 排名前应用 `tenant_id` 和 `permissions <@ caller_permissions`；
3. 分别获取 vector/FTS 候选，用 weighted RRF 合并不可比分数；
4. 远程 cross-encoder/model reranker 只能重排已授权候选，返回后复核完整 chunk 身份、数量和有限分数；
5. rerank 之后再做相邻/同父级扩展，存储查询再次应用 ACL，并受 `maxAdditionalChunks` 约束；
6. 根据 token/context 预算去重、截断，产生带 source/page/bbox 的 citation；
7. 证据不足时拒答或请求用户补充，不用模型自信度代替证据门禁。

重排不应无条件开启。只有当固定评测集证明 Recall/NDCG/citation support 的收益超过延迟与成本时，才作为生产默认。

## 7. Agent 何时查询 RAG

可预测问答优先用固定两段式：每个用户问题先检索，再用证据生成。需要在多个知识库/工具中选择时，把受限 `knowledge_search` 暴露为类型化 Agent Tool；
Tool 的 query/topK/filter 必须校验，tenant/permissions 仍由运行时注入。模型可决定“查什么”，不能决定“以谁的权限查”。

## 8. 与领先开源方案的融合

- Docling：采用无损 `DoclingDocument`、hierarchical-first 与同 heading/caption 合并思路；Scala 侧保留 Provider-neutral ADT，不引入 Python 运行时。
- Unstructured：吸收 digital/OCR/hi-res 路由与元素类型；不把自动策略选择变成不可观测的默认。
- LlamaIndex/Haystack：吸收 auto-merging parent retrieval、sentence/neighbor window 和模块化 retriever；额外增加 ACL 前置和 reranker 之后复核。
- LangChain：保留 loader/splitter/embedder/store/retriever 可组合性；业务主路则用 `RagApplication` 减少手工 glue。
- RAGFlow：吸收 layout/OCR/表格、可视 chunk 和引用质量；当前框架优先完成后端契约，管理 UI 属于后续可选组件。
- pgvector：使用 HNSW cosine、过滤索引和 iterative scan；召回、延迟与内存参数必须用 `EXPLAIN ANALYZE` 和真实 corpus 调整。

## 9. 投产前的必要证据

- 真实 Docling 版本/digest 的数字 PDF、扫描 PDF、中英文、表格、公式和多栏 smoke；
- 密码 PDF、损坏 xref、zip bomb、超大页、极多页、恶意嵌入对象与 prompt injection corpus；
- 固定问题集的 Recall@K、MRR/NDCG、citation support、ACL leakage=0、拒答正确率；
- 解析/切分/Embedding/发布/检索/rerank 分阶段延迟、token、成本和失败分类；
- 数据库重启、worker kill、Embedding 超时、重复 ingestion、撤回与重建的恢复演练；
- 百万级 chunk 的 WAL、索引构建、vacuum、备份/恢复和容量曲线。

当前代码已实现契约级的 Markdown+JSON 解码、page/bbox/block lineage、结构切分、0.4 单文件基线原子发布、ACL 后相邻/同父级扩展和真实 pgvector
Testcontainer。上述质量/容量/敌对样本证据仍必须在业务语料与目标硬件上完成，不能由单元测试代替。

## 10. 一手设计参考

- [Docling document](https://docling-project.github.io/docling/concepts/docling_document/)
- [Docling chunking](https://docling-project.github.io/docling/concepts/chunking/)
- [Docling Serve REST API](https://docling-project.github.io/docling/usage/api_server/rest_api/)
- [Unstructured PDF partitioning](https://docs.unstructured.io/open-source/core-functionality/partitioning)
- [Haystack AutoMergingRetriever](https://docs.haystack.deepset.ai/docs/automergingretriever)
- [LlamaIndex Auto Merging Retriever](https://docs.llamaindex.ai/en/v0.10.17/examples/retrievers/auto_merging_retriever.html)
- [LangChain retrieval](https://docs.langchain.com/oss/python/langchain/retrieval)
- [pgvector](https://github.com/pgvector/pgvector)
- [RAGFlow](https://github.com/infiniflow/ragflow)
