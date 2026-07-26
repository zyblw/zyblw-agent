# Reranker 契约、治理与 Retriever 安全边界

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 为什么重排独立于 VectorStore

Hybrid FTS + pgvector 的职责是从大规模索引中快速召回候选；cross-encoder 或厂商 rerank 模型的职责是对较小候选集做更精细的 query-document 相关性判断。两者的延迟、费用和失败语义不同，不应塞进一个数据库查询或一个模糊 Provider 接口。

框架保留两层窄契约：

- `RerankerModel`：厂商 Adapter，只负责受限请求和类型化响应；
- `ModelReranker`：框架治理层，负责候选上限、Unicode 截断、总超时、故障策略、身份验证和稳定排序。

`DefaultRetriever` 的路径固定为：

```text
query → tenant-scoped Embedding → authorized hybrid candidates
      → governed reranker → post-rerank trust validation → citations
```

## 2. 类型化 Provider 契约

真实 Adapter 必须声明 `RerankerDescriptor`：

```scala
val descriptor = RerankerDescriptor(
  provider = "business-cross-encoder",
  model = "reranker-v1",
  maxCandidates = 50,
  maxQueryCodePoints = 2_048,
  maxDocumentCodePoints = 4_096
)
```

Provider 只看到本次请求内的 `candidate-N`，不会收到 tenantId、documentId 或 sourceUri。它返回的 `RerankScore.relevance` 必须是有限的 `[0, 1]` 分数；若厂商返回 logit，Adapter 应按厂商协议在边界内归一化。响应允许少于 `topN`，但不能返回未知 ID、重复 ID或超过 `topN`。

`usage` 和厂商 request ID 都是可选事实。厂商不返回 usage 时必须保持 `None`，不能为了图表完整伪造为零。按检索
单元计费的厂商使用 `RerankBilling.searchUnits`，绝不能把 search unit 塞进 token usage。

## 3. ZIO 运行语义

```scala
val rerankerLayer: URLayer[RerankerModel, Reranker] =
  ModelReranker.configured(
    ModelRerankerPolicy(
      timeout = 3.seconds,
      failureMode = RerankerFailureMode.FailClosed,
      maxCandidates = 50
    )
  )
```

- 候选数取策略上限与 Provider 能力的较小值；
- query 和文档按 Unicode code point 截断，不会切断 surrogate pair；
- `timeoutFail` 给一次完整调用设置硬预算，并中断底层 Fiber；
- `catchAll` 只处理 typed failure，ZIO interruption 不会被 FailOpen 吞掉；
- 同分结果按原始候选 rank 排序，响应数组顺序不会制造结果漂移。

普通站内搜索可根据业务评测选择 `FailOpen`，回退到 hybrid 原排序。要求引用质量硬门禁的中医学习问答应默认 `FailClosed`，避免重排服务故障时静默降低依据质量。

## 4. Reranker 不是授权边界

远端 Reranker 或自定义实现不能被信任为权限执行器。VectorStore 必须先按 tenant 和 permission 过滤；重排完成后，`DefaultRetriever` 还会重新验证：

1. 每个 DocumentChunk 必须完整存在于原候选集；
2. tenant 必须等于 RetrievalScope；
3. 文档权限必须是调用者权限的子集；
4. 结果不能重复、不能超过 limit；
5. score 和 signals 不能包含 NaN/Infinity。

Reranker 只能修改顺序、score 和 signals，不能注入新文档。这个二次校验也覆盖“业务自定义 Reranker 编程错误”与远端服务失陷。

## 5. 分数与引用

模型 relevance 成为最终 `RetrievalHit.score`，原 hybrid 分数和排名不会丢失，而是写入：

- `preRerankScore`
- `preRerankRank`
- `rerankScore`
- `rerankRank`

因此 eval 可以同时判断“召回是否找到正确块”和“重排是否把正确块推到前面”。Citation 始终在重排和信任校验后生成，保持 hits/citations 同序。

## 6. 当前边界

当前已经完成 Provider-neutral SPI、治理层、Retriever 二次安全校验和确定性测试，覆盖 Unicode 截断、乱序/同分响应、重复 ID、FailOpen/FailClosed 与取消传播。

`zyblw-agent-rerank` 已提供 Cohere v2 `/rerank` 原生 Adapter：

```scala
import com.zyblw.agent.integrations.rerank.*

val cohereModel: URLayer[zio.http.Client, RerankerModel] =
  CohereRerankModel.configured(
    CohereRerankConfig(
      baseUrl = "https://api.cohere.com",
      apiKey = sys.env("COHERE_API_KEY"),
      model = "rerank-v4.0-pro",
      maxCandidates = 50,
      requestTimeout = 5.seconds
    )
  )

val governed: ZLayer[zio.http.Client, Nothing, Reranker] =
  cohereModel >>> ModelReranker.configured(
    ModelRerankerPolicy(
      timeout = 6.seconds,
      failureMode = RerankerFailureMode.FailClosed,
      maxCandidates = 50
    )
  )
```

Adapter 固定使用 `/v2/rerank`、Bearer、`documents/top_n/max_tokens_per_doc`，把响应 index 映射回临时
`candidate-N`。它不发送 tenantId、documentId、sourceUri 和 metadata；响应 request ID 只接受安全字符。生产 endpoint
必须是 HTTPS 且不能包含 user-info/query/fragment。本地 stub 只有显式 `allowInsecureHttp=true` 才能使用 HTTP。

HTTP Body 在 ZIO HTTP streaming Scope 内最多读取 `maxResponseBytes + 1`；总 timeout 或调用 Fiber 中断会关闭连接。
408/409/425/429/5xx 和 transport failure 因 Rerank 是只读计算而进行有界指数退避，认证/schema 4xx 不重试。错误响应
正文会被排空但不会进入 typed error、日志或 trace。官方返回的 `meta.billed_units.search_units` 映射为
`RerankBilling`，token usage 保持 `None`。

真实 stub 契约已经覆盖 wire schema、认证、乱序 index、search units、429 相同正文重试、401 不重试、重复 index、
越界 relevance、非法 billing、超大响应、慢 Body 与取消 finalizer。

仍未实现 Jina、Voyage 或自建 cross-encoder Adapter，也未完成跨厂商 RerankerContract 汇总报告。Cohere 模型是否提升
中医语料质量仍必须由 recall@k、MRR/NDCG、引用支持率、延迟和 search-unit 成本数据集证明，不能仅凭“接入成功”宣称改善。

协议与运行语义以 [Cohere v2 Rerank API](https://docs.cohere.com/v2/reference/rerank)、
[Cohere Rerank 模型说明](https://docs.cohere.com/v2/docs/rerank) 和
[ZIO HTTP Client](https://ziohttp.com/reference/client/) 为准。
