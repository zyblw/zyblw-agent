# RAG 评测与发布门禁

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-22
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 不能只测“最终回答看起来不错”

RAG 系统至少有三个相互独立的失败面：检索没有找到正确资料、找到了但排名太低、回答引用与实际命中不一致。另有租户
越权、撤回文档泄漏、NaN 分数和尾延迟等生产风险。用一个平均分或一次 LLM-as-a-judge 会掩盖这些硬事实。

`agent-evals` 的 `RagEvalGrader` 因此分别生成四个门禁：

| 维度 | 指标或约束 |
|---|---|
| `rag-ranking` | Recall@K、Precision@K、MRR、二元 NDCG@K |
| `rag-citation-support` | required source 覆盖、引用 ID 唯一、excerpt 能由同来源命中正文机械支持 |
| `rag-authorization-and-integrity` | tenant、permissions、forbidden IDs、重复 chunk、NaN/Infinity |
| `rag-latency` | 单次端到端检索延迟 |

只有四个维度全部通过，`RagEvalReport.passed` 才为 true。授权失败不能被高召回率平均掉。

## 2. 数据集字段

```scala
val evalCase = RagEvalCase(
  id = "shanghan-taiyang-001",
  datasetVersion = "tcm-rag-2026-07-v1",
  query = "已脱敏的学习问题",
  scope = RetrievalScope(
    TenantId("eval-tenant"),
    Set("knowledge:read"),
    requestId = Some("eval-shanghan-taiyang-001")
  ),
  expectedRelevantChunkIds = Set("shanghan-42", "shanghan-43"),
  forbiddenChunkIds = Set("withdrawn-9", "cross-tenant-canary"),
  requiredCitationSourceUris = Set("book://shanghan/chapter-2"),
  limit = 5,
  thresholds = RagEvalThresholds(
    minRecallAtK = 1.0,
    minPrecisionAtK = 0.4,
    minMrr = 0.5,
    minNdcg = 0.8,
    minCitationSupport = 1.0,
    maxLatencyMillis = 1500
  )
)
```

数据集必须版本化。更换 Embedding、分词、chunking、RRF 权重、reranker、权限逻辑或标注时，应保留旧基线并产生新版本，
不能覆盖结果后只展示最新一次成功。生产问题和资料必须脱敏；报告只保存 case ID、版本、数值与计数，不保存 query、正文
和引用 excerpt。

## 3. 执行真实 Retriever

```scala
val report = RagEvalRunner(maxParallelism = 4)
  .runRetriever(retriever, Chunk(evalCase))

report.flatMap { suite =>
  ZIO.fail(new RuntimeException("RAG 发布门禁失败")).unless(suite.passed)
}
```

Runner 使用单调时钟测量延迟，`foreachPar` 有界并发且最终报告保持数据集顺序。并发数必须同时低于 Embedding、reranker 和
数据库连接池的安全容量。空数据集明确不通过，防止 CI 因数据文件未加载而出现假绿。

`run(cases)(execute)` 可注入 stub、固定延迟、429、断流或数据库故障；适合离线 cassette 和故障注入。确定性指标不使用
LLM Judge。自然语言答案是否被引用真正蕴含，可在 Agent 级 eval 中额外加入人工评分或 Judge，但不能替代授权、引用存在性
和排名指标。

## 4. 中医业务数据集建议

第一版不要追求海量问题，先建立可人工复核的分层集合：

1. 典籍原文定位：答案来源明确，适合严格 Recall/MRR 与引用支持；
2. 同义表达和古今术语：验证 Embedding 与全文检索互补；
3. 跨章节问题：验证多片段召回和 NDCG；
4. 权限诱饵：其他租户、撤回资料、无权限私有笔记；
5. 间接 prompt injection 文档：允许作为资料命中，但完整 Agent eval 必须证明它不能改变指令或触发工具；
6. 无答案问题：应在答案层拒绝编造；当前 `RagEvalCase` 要求至少一个相关片段，因此无答案集应使用单独的答案评测类型；
7. Provider/数据库故障：超时、429、5xx、连接池饱和和 reranker fail-open/fail-closed 策略。

引用 excerpt 与命中正文相同只证明“引用有机械来源”，不证明医疗结论正确。涉及症状、方药、剂量、诊断或调理建议时，
仍必须经过中医安全策略、免责声明、可靠来源和必要的人工复核。

## 5. 当前边界

框架已经提供确定性指标、授权与完整性硬门禁、真实 Retriever runner 和回归测试；`EvalSuiteSnapshot.fromRag`、
`EvalReleaseGate`、本地 `FileEvalTrendStore` 与生产 `PostgresEvalTrendStore` 已能保存低敏历史、比较最近成功基线并
阻止删除用例/维度或分数退化。

仍未提供真实业务数据集仓库、对象存储趋势 Adapter、人工标注 UI、答案蕴含 Judge、输入扰动/重复运行稳定性
和 RAG 成本价格表。文件 Store 适合 CI artifact 和单节点预发布任务；PostgreSQL Store 支撑共享事实，但仍不等于完整
趋势可视化平台。完整使用方式见
[评测趋势仓库与 CI 发布门禁](eval-trend-and-release-gate.md)。
