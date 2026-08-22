# Agent Eval 数据集治理

> 状态：当前实现
>
> 最后核验：2026-08-21
>
> 事实来源：`AgentEvalDataset.scala`、`AgentEvalDatasetSpec.scala`

## 1. 为什么需要 provenance

版本号只能说明“名字不同”，不能证明本次运行使用的内容就是审查时的内容，也不能回答样本来自人工编写、合成、脱敏生产轨迹、
事故复盘还是人工分歧。发布评测如果直接接收 `Chunk[AgentEvalCase]`，很容易出现：

- 数据集改了但忘记推进版本；
- 评审结束后文件又被替换；
- 合成样本被误称为真实生产覆盖；
- 事故或生产样本没有完成脱敏和人工审查；
- CI 加载空集、重复 ID 或混合 datasetVersion 后仍开始调用 Provider。

LangSmith 的官方评测文档同样把数据集版本、人工策划样本、历史生产轨迹、合成数据和“把失败生产轨迹回流到离线数据集”作为不同环节；
zyblw-agent 吸收的是这些治理原则，而不是依赖其云端数据模型：

- <https://docs.langchain.com/langsmith/evaluation>
- <https://docs.langchain.com/langsmith/manage-datasets>

## 2. 当前类型

`AgentEvalDataset` 由两部分组成：

- `AgentEvalDatasetProvenance`：稳定数据集身份、来源类别、变更 ID、owner、审查状态、至少两名 reviewer、
  可选独立仲裁者、审查时间与内容 SHA-256；
- `Chunk[AgentEvalCase]`：受控、短保留期的实际用例。

来源使用低基数 `EvalDatasetSource`：

- `Curated`：人工策划；
- `Synthetic`：合成；
- `ProductionRedacted`：已脱敏生产样本；
- `IncidentRedacted`：已脱敏事故样本；
- `HumanDisagreementRedacted`：已脱敏人工分歧样本；
- `PublicOpenDataset`：固定 commit/tag、许可证、源 SHA-256 和确定性选择协议的公开数据集。

来源标签不是脱敏证明。生产、事故和人工分歧样本是否允许进入仓库或 CI，仍由宿主的数据治理、权限和保留策略决定。

## 3. 内容绑定与发布校验

`AgentEvalDataset.contentSha256(cases)` 对完整用例生成确定性 SHA-256：

- 字符串使用 UTF-8 长度前缀，避免简单连接产生歧义；
- Set 字段排序，因此构造顺序不改变摘要；
- 用例顺序保留，因此删除、插入或换序都会改变摘要；
- 输入、期望工具、禁止工具、引用、恢复要求和预算全部进入摘要；
- 金额使用规范十进制表示，不经 `Double`。

SHA-256 只做完整性绑定，不是加密或脱敏。数据集文件本身仍可能含业务输入，不能进入长期通用趋势仓库。

`validateForRelease` 在运行前统一验证：

- schema、dataset/change/owner/reviewer 身份只含低风险稳定字符；
- 来源非空、用例非空且不超过硬上限；
- `PublicOpenDataset` 必须有不可变 `EvalDatasetUpstream`；`main`/`master`/`latest`、带 query/token 的 URL 或摘要缺失均拒绝；
- case ID 唯一且全部 datasetVersion 与清单一致；
- schema v2 状态必须是 `Approved`，同时存在至少两名不同于 owner 的 reviewer 和 reviewedAt；
- 来源包含 `HumanDisagreementRedacted` 时必须声明不同于 owner/reviewer 的独立 adjudicator；
- 当前内容摘要必须与人工审查清单一致。

任一项失败都返回稳定的 `AgentError.InvalidConfiguration`，不包含输入正文。

`validateIntegrity` 可用于检查 Draft 的结构、来源锁和内容绑定，但不会跳过发布审查。仓库公开样本通过
`evaluation-datasets/prepare-public-eval-dataset.py` 生成到 `target/public-evals/`，再执行：

```bash
sbt "evals/runMain com.zyblw.agent.evals.AgentEvalDatasetVerifyCli target/public-evals/public-agent-safety-v1.json"
```

输出中的 `releaseApproved=false` 是未完成人工双审时的正确结果。公开数据提供真实 benchmark 样本，但不是业务数据。

## 4. 使用方式

```scala
val cases: Chunk[AgentEvalCase] = loadReviewedCases()

val provenance = AgentEvalDatasetProvenance(
  schemaVersion = AgentEvalDatasetProvenance.CurrentSchemaVersion,
  datasetId = "tcm-learning-golden",
  datasetVersion = "2026-08-v1",
  sources = Set(EvalDatasetSource.Curated, EvalDatasetSource.IncidentRedacted),
  changeId = "eval-change-42",
  ownerId = "eval-team",
  reviewStatus = EvalDatasetReviewStatus.Approved,
  reviewerId = Some("reviewer-7"),
  reviewedAt = Some(reviewedAt),
  contentSha256 = AgentEvalDataset.contentSha256(cases),
  reviewerIds = Set("reviewer-7", "reviewer-9"),
  adjudicatorId = Some("adjudicator-3")
)

val dataset = AgentEvalDataset(provenance, cases)

AgentEvalRunner(maxParallelism = 8).runRepeated(dataset, trialsPerCase = 10) {
  (evalCase, attempt) => runAgent(evalCase, attempt)
}
```

应由受控数据集构建任务生成 digest，由独立审查步骤把状态推进为 `Approved`。不要让同一个未受审脚本同时修改 cases、重算 digest 并声称
完成了人工审查。

## 5. 与趋势仓库的边界

`AgentEvalDataset` 是评测输入 artifact；`EvalSuiteSnapshot` 是长期低敏结果。前者不会自动写入 `EvalTrendStore`，后者不会保存：

- 输入或参考答案；
- reviewer 之外的人员资料；
- 工单、事故或生产轨迹正文；
- 每次 trial、工具参数或 `EvalGrade.details`。

评测完成后，使用 `EvalSuiteSnapshot.fromAgent` 或 `fromAgentReliability` 投影，再交给 `EvalReleaseGate`。趋势 metadata 的
`datasetId/datasetVersion` 必须与 provenance 一致；当前由调用方装配，后续只有在真实 CLI 消费者需要时再增加一体化命令，避免先造第二套管线。

## 6. 当前未解决

- 框架没有替业务生成“真实黄金数据集”；
- `Approved`、双 reviewer 与 adjudicator 只记录审查事实，不验证组织 RBAC、人员真实性或电子签名；
- SHA-256 不证明样本代表真实流量，也不证明 grader 正确；
- RAG 与 Context Compression 还没有复用同一 provenance envelope；应在出现共同 Loader/CI 消费者后抽取，而不是提前泛化；
- 下一阶段仍需把真实失败、事故和人工分歧持续回流，并用 transcript 抽样和双人标注校准 deterministic/LLM grader。
