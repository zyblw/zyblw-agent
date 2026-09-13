# ADR 0027：RAG Retrieval Runtime 绿场基线

> 状态：**Accepted**
> 日期：2026-09-05
> 核验：单元/eval 门禁、Postgres 集成与本机平台重摄取问答已通过；独立 live Provider HTTP 合同测试未作为 CI 门禁，不得写成已上线。
> 影响：`agent-rag`、`agent-postgres` 知识 schema、`agent-providers` embedding、`agent-evals`、平台知识投影
> 手册：[rag-runtime-target.md](rag-runtime-target.md)

## Context

0.9 知识基线按文档版本激活，租户级 `assertEmbeddingIdentity` 在换模时制造混合窗口。`EmbeddingService.embed(texts)` 无法表达 query/document、instruction 与 sparse。`DocumentChunk.text` 同时承担展示与检索。当前无真实生产知识数据，宿主从空库重装。

## Decision

1. 破坏性全新安装：仓库内知识 V001 **替换**为 Space / Profile / census / chunks / audit / withdrawn。删除旧三表与逐文档 activate。
2. 唯一 embedding SPI 为 `EmbeddingModel.embed(EmbeddingRequest)`。删除 `EmbeddingService`。
3. Chunk 只存 `ChunkRepresentations`；citation 只引用 `displayText`。
4. 摄入走可推导 `ingestionKey` 与 checkpoint；查询输出 `EvidenceBundle`。
5. 默认检索：1024 dense + PostgreSQL FTS + RRF + rerank。Sparse、LLM rewrite、contextualizer 默认关闭。
6. 已发布 Maven `0.9.0` tag 冻结；当前开发线按下一 minor 空库基线声明。

## Alternatives

- 追加 V002 并 dual-read：否决。无生产数据，双路径会冻结混合 identity。
- 给旧 `embed(texts)` 加默认方法：否决。第三方实现会静默丢 query/document。

## Consequences

- 平台从 `zyblw_app` 重建索引；旧知识行丢弃。
- 安全硬门禁进 CI；质量阈值由版本化数据集登记。
- GraphRAG / agentic / multimodal 需新 ADR。
