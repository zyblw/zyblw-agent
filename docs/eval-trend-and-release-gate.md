# 评测趋势仓库与 CI 发布门禁

> 状态：当前说明（模块稳定度见 [成熟度与路线](maturity-and-roadmap.md)）
>
> 最后核验：2026-07-29
>
> 事实来源：对应模块源码、测试与构建定义

## 1. 解决什么问题

`AgentEvalRunner`、`RagEvalRunner` 和 `ContextCompressionEvalRunner` 已经能够对一次运行生成确定性硬门禁，但单次报告不能回答：

- 新模型是否比当前生产模型退化；
- 新 Prompt 是否删除了某条历史用例或评分维度；
- 某次失败是否只是偶发，还是已经连续发生；
- CI 首次没有历史数据时，是否错误地把“没有基线”当成“没有回归”；
- 多个 CI 进程同时写 artifact 时，历史文件是否会交错或半写。

`EvalSuiteSnapshot`、`EvalReleaseGate`、`FileEvalTrendStore` 和 `PostgresEvalTrendStore`
把单次评分推进为可重复的发布制度：

```text
运行真实 Eval
  -> 投影低敏 EvalSuiteSnapshot
  -> 读取相同 suite/dataset/version 的最近成功基线
  -> 检查候选自身硬门禁
  -> 比较通过率、用例、维度和分数
  -> 通过、质量失败或显式 bootstrap 时耐久追加候选
  -> 未授权的首次通过候选不追加，防止下一次运行隐式取得基线
  -> CI 根据 EvalReleaseDecision.passed 放行或阻止
```

单次套件仍可能因模型随机性产生偶然绿灯。`AgentEvalRunner.runRepeated` 会在同一个
`maxParallelism` 边界内执行用例 × attempt，并生成 `AgentEvalReliabilityReport`。每个用例同时报告：

- 观察到的逐次 `successRate`；
- 以该成功率估算 k 次至少一次成功的 `estimatedPassAtK(k)`；
- 以该成功率估算连续 k 次全部成功的 `estimatedPassPowerK(k)`；
- 最严格的 `passedEveryTrial`。

`pass@k` 适合“允许多试几次、至少一次成功”的探索任务；面向用户且每次都应可靠的路径应重点看 `pass^k` 和
`passedEveryTrial`。当前估算假设试验近似独立同分布，尚未进入长期趋势 schema；小样本不能被宣传为统计保证。

```scala
val reliability =
  AgentEvalRunner(maxParallelism = 8).runRepeated(
    cases = agentCases,
    trialsPerCase = 5
  ) { (evalCase, attempt) =>
    runAgent(evalCase, attempt)
  }
```

## 2. 为什么不直接长期保存原始报告

单次报告可能含有：

- `AgentEvalCase.input`；
- RAG query、命中正文与引用 excerpt；
- `EvalGrade.details`；
- Context 压缩 attempt 的摘要哈希、Token、成本和匹配 ID；
- Provider 错误摘要。

这些信息适合受控、短保留期的单次调试 artifact，不适合无限期进入通用趋势系统。

趋势快照只保存：

- suite、dataset、harness、Provider、模型和构建版本；
- case ID 与 datasetVersion；
- 稳定 dimension；
- `passed` 与 0..1 分数；
- 开始/完成时间。

`EvalSuiteSnapshot.fromAgent`、`fromRag`、`fromContextCompression` 会主动删除 `EvalGrade.details`。快照校验还要求 ID
使用低风险稳定字符、数值有限且位于 0..1、用例/维度不重复、时间范围与数据集版本一致。

## 3. 创建快照

```scala
import com.zyblw.agent.evals.*
import java.time.Instant

val metadata = EvalSnapshotMetadata(
  evaluationId = "ci-2026-07-17-001",
  suiteId = "tcm-learning-agent",
  datasetId = "tcm-learning-golden",
  datasetVersion = "dataset-v1",
  harnessVersion = "agent-prompt-v3",
  provider = Some("deepseek"),
  model = Some("deepseek-v4-flash"),
  pricingVersion = Some("deepseek-price-2026-07"),
  commitSha = Some("abcdef1234"),
  startedAt = Instant.parse("2026-07-17T00:00:00Z"),
  finishedAt = Instant.parse("2026-07-17T00:02:00Z")
)

val snapshot =
  EvalSuiteSnapshot.fromAgent(metadata, agentEvalReport)
```

同样可以使用：

```scala
EvalSuiteSnapshot.fromRag(metadata, ragEvalReport)
EvalSuiteSnapshot.fromContextCompression(metadata, contextCompressionReport)
```

`evaluationId` 表示一次逻辑评测执行。CI 因网络或 artifact 上传失败而重试同一次执行时，应复用该 ID；同 ID 同内容写入
幂等成功，同 ID 不同内容明确冲突。

## 4. 创建文件趋势仓库

```scala
import java.nio.file.Path

val config = FileEvalTrendStoreConfig(
  path = Path.of("target/eval-trends/tcm-learning.jsonl"),
  maxFileBytes = 64L * 1024L * 1024L,
  maxRecordBytes = 2 * 1024 * 1024
)

val store = FileEvalTrendStore.make(config)
```

父目录必须由应用或部署脚本预先创建。Store 不会自行创建任意目录，避免一个配置错误在未知位置创建文件。

生产 ZLayer：

```scala
val layer =
  FileEvalTrendStore.layer(config)
```

一个进程内应共享同一个 Layer/Store 实例。内部 `Semaphore` 负责 Fiber 级串行化，操作系统 `FileLock` 负责不同 CI 进程的
写互斥。

## 5. 创建 PostgreSQL 趋势仓库

多节点 CI、长期趋势和共享发布事实源应复用宿主 `DataSource`：

```scala
import com.zyblw.agent.persistence.postgres.*
import javax.sql.DataSource
import zio.*

val trendLayer: URLayer[DataSource, EvalTrendStore] =
  PostgresAgentPersistence.evalTrends

val configured: URLayer[DataSource, EvalTrendStore] =
  PostgresEvalTrendStore.configured(
    PostgresEvalTrendStoreConfig(
      maxSnapshotBytes = 2 * 1024 * 1024,
      maxHistoryLimit = 100000
    )
  )
```

Flyway V007 创建 `agent_eval_snapshots`。每行同时保存：

- `snapshot_payload TEXT`：保留应用生成的确定性 UTF-8 字节，供 SHA-256 校验；
- `snapshot_json JSONB`：供 SQL 分析和 dashboard 查询；
- `CHECK (snapshot_json = snapshot_payload::jsonb)`：保证两个表示语义一致；
- 完整 `kind + suiteId + datasetId + datasetVersion` 查询身份；
- `finished_epoch_second + finished_nano`：避免 `TIMESTAMPTZ` 微秒精度破坏 JVM `Instant` 的确定性顺序；
- `passed/pass_rate` 低敏派生列。

不能直接对 `snapshot_json::text` 计算应用侧 checksum：JSONB 会规范化空格和对象表示，取回的字节不保证与写入 JSON 文本相同。
真实 PostgreSQL 契约测试专门覆盖了这个边界。

`evaluation_id` 是不可变主键。并发进程通过 `INSERT ... ON CONFLICT DO NOTHING` 仲裁：

- 同 ID、同快照幂等成功；
- 同 ID、不同快照返回 `eval-trend:evaluation-id-conflict`；
- checksum、JSON、领域校验或冗余查询列不一致时 fail-closed；
- 发布门禁通过部分索引直接读取最近成功基线，不扫描完整历史。

发布账号建议只授予该表 `SELECT/INSERT`，不要允许普通 CI 更新或删除历史事实。

## 6. 文件可靠性语义

每条记录采用一行 JSON envelope：

```text
schemaVersion + Base64(payload JSON) + SHA-256(payload)
```

追加过程：

1. 严格 UTF-8；
2. 校验现有每条完整记录的 JSON、Base64、SHA-256 和快照语义；
3. 相同 evaluationId 同内容幂等返回；
4. 如果最后一条没有换行，认为进程在写到一半时退出，只截断这一段尾部；
5. 完整行 checksum 错误或中间记录损坏时 fail-closed；
6. 在 FileLock 内追加；
7. 调用 `FileChannel.force(true)`。

它能够处理单文件 CI 趋势的常见崩溃窗口，但不是无限容量数据库。达到 `maxFileBytes` 后会拒绝继续追加，调用方必须归档
旧文件，或改用已经提供的 PostgreSQL Adapter；对象存储仍可通过相同 `EvalTrendStore` SPI 扩展。

## 7. 发布策略

默认策略：

```scala
val policy = EvalRegressionPolicy()
```

默认含义：

- 候选必须通过自己的全部硬门禁；
- 通过率不能下降；
- 不能删除基线用例；
- 不能删除基线维度；
- 任一维度分数不能下降；
- 没有成功基线时不能自动放行。

允许少量数值波动时：

```scala
val policy = EvalRegressionPolicy(
  maxPassRateDrop = 0.0,
  maxDimensionScoreDrop = 0.02
)
```

禁止通过设置较大容忍度绕过安全维度。工具越权、引用伪造、重复副作用、禁止内容和硬预算应保持 `passed=false`，而不是只
降低分数。

## 8. 首次建立基线与不可绕过语义

首次运行必须显式声明：

```scala
val bootstrapPolicy =
  EvalRegressionPolicy(allowFirstPassingBaseline = true)
```

即使开启 bootstrap，候选仍必须通过自己的全部硬门禁。建立成功后，后续 CI 应恢复默认
`allowFirstPassingBaseline=false`；不要永久开启，否则历史文件被误删时会重新建立基线并掩盖事故。

这里有一个容易被忽略但非常重要的耐久语义：

- 历史为空；
- 候选自身全部硬门禁通过；
- `allowFirstPassingBaseline=false`。

此时本次决策会返回 `BaselineMissing`，而且候选**不会追加**到趋势仓库。如果先追加该候选，Store 的
`latestPassing` 下一次会按 `snapshot.passed=true` 选择它，等于运行一次失败任务后就绕过了显式 bootstrap。

候选自身硬门禁失败则仍可留痕，因为它的 `snapshot.passed=false`，永远不会被选为成功基线。已有基线之后的质量回归
也继续追加，用于分析连续失败趋势。

## 9. 低敏 Snapshot artifact

正式发布任务不应重新读取原始问答、检索正文或完整 Eval 报告。上游评测任务应先做低敏投影：

```scala
import zio.json.*

for
  snapshot <- EvalSuiteSnapshot.fromAgent(metadata, agentEvalReport)
  _ <- EvalSnapshotArtifact.write(
         EvalSnapshotArtifactConfig(
           path = java.nio.file.Path.of("target/evals/candidate-snapshot.json"),
           maxBytes = 2 * 1024 * 1024
         ),
         snapshot
       )
yield ()
```

`EvalSnapshotArtifact.write` 会先执行领域校验和容量校验，再用同目录临时文件、`fsync` 和 `ATOMIC_MOVE` 替换目标；不会
让下一阶段读到半个 JSON，也不会在原子移动不受支持时静默降级。

正式 CLI 使用同一对象的 `load`：

- 目标必须是普通文件且不能是符号链接；
- `FileChannel` 以 `NOFOLLOW_LINKS` 打开；
- 读取前执行字节硬上限；
- 严格 UTF-8，不接受 replacement character；
- JSON 解码后重新执行完整 `EvalSuiteSnapshot` 领域校验；
- 错误不回显路径或文件正文。

这样“运行模型/读取数据集”和“拥有发布事实源写权限”可以分给不同 CI 身份，降低模型密钥与业务数据的权限耦合。

## 10. 一次完整库级门禁

```scala
for
  store    <- FileEvalTrendStore.make(config)
  snapshot <- EvalSuiteSnapshot.fromAgent(metadata, agentEvalReport)
  decision <- EvalReleaseGate.evaluateAndAppend(
                store,
                snapshot,
                EvalRegressionPolicy()
              )
  _ <- ZIO
         .fail(AgentError.InvalidConfiguration("agent-eval-release-gate-failed"))
         .unless(decision.passed)
yield decision
```

`evaluateAndAppend`：

- 只选择相同 `kind + suiteId + datasetId + datasetVersion` 的历史；
- 通过 `EvalTrendStore.latestPassing(EvalTrendIdentity)` 直接读取一个成功基线，不为门禁加载整段趋势；
- 只用最近成功快照作为质量基线；
- 已有基线后的失败候选仍然追加，便于统计连续失败；
- 仅因 `BaselineMissing` 被拒绝的首次通过候选不追加，防止隐式授权；
- 先完成 `fsync` 再向调用方返回决策。

数据集升级时不会自动跨版本比较。正确流程是：

1. 保留旧 datasetVersion 的最后报告；
2. 人工审查新标注；
3. 对新版本显式 bootstrap；
4. 以后只在新版本内部比较；
5. 若需要跨版本分析，使用独立迁移/分析任务，不能让发布门禁猜测标注差异。

## 11. 正式 CI CLI

`agent-eval-cli` 是可独立运行的正式入口，不调用模型，只消费低敏 `EvalSuiteSnapshot` JSON。

### 11.1 文件模式

```bash
export ZYBLW_AGENT_EVAL_RELEASE_ARTIFACT_PATH=target/evals/candidate-snapshot.json
export ZYBLW_AGENT_EVAL_RELEASE_STORE_BACKEND=file
export ZYBLW_AGENT_EVAL_RELEASE_FILE_PATH=target/eval-trends/release.jsonl
export ZYBLW_AGENT_EVAL_RELEASE_POLICY_ALLOW_FIRST_PASSING_BASELINE=false

sbt "evalCli/runMain com.zyblw.agent.evals.cli.EvalReleaseGateCli"
```

artifact 和趋势文件的父目录必须由 CI 预先创建。框架不会因拼错路径而在未知位置自动创建目录。
相对路径按 CLI 子进程工作目录解析；使用 sbt 的 fork runner 或容器部署时，生产流水线建议传入绝对路径，避免模块工作目录
与仓库根目录不同。

### 11.2 PostgreSQL 模式

```bash
export ZYBLW_AGENT_EVAL_RELEASE_ARTIFACT_PATH=target/evals/candidate-snapshot.json
export ZYBLW_AGENT_EVAL_RELEASE_STORE_BACKEND=postgres
export ZYBLW_AGENT_EVAL_RELEASE_POSTGRES_JDBC_URL=jdbc:postgresql://db:5432/zyblw
export ZYBLW_AGENT_EVAL_RELEASE_POSTGRES_USER=zyblw_eval_release
export ZYBLW_AGENT_EVAL_RELEASE_POSTGRES_PASSWORD='由 Secret Manager 注入'

sbt "evalCli/runMain com.zyblw.agent.evals.cli.EvalReleaseGateCli"
```

密码使用 `Config.Secret`，禁止写进 JDBC URL。账号建议只授予 `agent_eval_snapshots` 的 `SELECT/INSERT`。CLI 是一次性短任务，
使用 `PGSimpleDataSource` 顺序执行一次基线查询和一次可选追加；常驻 Agent 服务仍应复用宿主统一监控的连接池。

### 11.3 稳定退出码

| 退出码 | 含义 | CI 处理 |
|---:|---|---|
| `0` | 候选通过 | 允许进入下一发布阶段 |
| `2` | 质量门禁拒绝 | 阻止发布，读取 `decision.issues` |
| `3` | 配置、artifact、schema 或输入错误 | 修复流水线，不归因于模型质量 |
| `4` | 文件系统/PostgreSQL 等基础设施错误 | 阻止发布，可按平台策略重试 |
| `5` | 其他已建模框架错误 | 阻止发布并人工排查 |

进程只输出单行低敏 JSON。`Passed/Rejected` 写 stdout，`Error` 写 stderr。Defect 不会被捕获后伪装成回归，而由 ZIO runtime
保留 Cause 并以普通异常状态退出。

### 11.4 首次 bootstrap

只在人工审查过的初始化任务中临时设置：

```bash
export ZYBLW_AGENT_EVAL_RELEASE_POLICY_ALLOW_FIRST_PASSING_BASELINE=true
```

成功建立后必须恢复 `false`。该开关不能作为所有环境的永久默认值。

## 12. 手工查询历史

```scala
val identity = EvalTrendIdentity(
  EvalSuiteKind.Agent,
  suiteId = "tcm-learning-agent",
  datasetId = "tcm-learning-golden",
  datasetVersion = "dataset-v1"
)

for
  latest  <- store.latestPassing(identity)
  history <- store.history(identity, limit = 100)
yield (latest, history)
```

`kind` 是身份的一部分。即使 Agent 与 RAG 因配置错误使用相同 suite/dataset 名称，也不会互相成为基线。

## 13. 决策问题码

| code | 含义 |
|---|---|
| `BaselineMissing` | 没有成功基线且未允许首次建立 |
| `SnapshotIdentityMismatch` | 比较了不同 suite/dataset/version |
| `CandidateHardGateFailed` | 候选自身存在硬门禁失败 |
| `PassRateRegressed` | 套件通过率下降 |
| `BaselineCaseRemoved` | 候选删除了历史用例 |
| `CaseHardGateRegressed` | 历史通过用例在候选中失败 |
| `BaselineDimensionRemoved` | 候选删除了历史评分维度 |
| `DimensionScoreRegressed` | 某一维度超过容忍度下降 |

问题记录只有稳定 ID 和数值，没有 `EvalGrade.details`。

## 14. 当前边界

- 已完成 Agent/RAG/Context Compression 三类报告的统一低敏快照；
- 已完成 fail-closed 基线比较、不可绕过的显式 bootstrap、已有基线后的失败候选留痕；
- 已完成带 checksum、文件锁、fsync、并发幂等和崩溃尾恢复的本地 Store；
- 已完成 PostgreSQL Adapter、完整身份复合索引、成功基线部分索引、不可变并发幂等和读取完整性校验；
- 已完成正式 `agent-eval-cli`、ZIO Config/Secret、严格 artifact 读取、文件/PostgreSQL 切换和稳定退出码；
- 已用 PostgreSQL 16 Testcontainers 覆盖正式 Flyway、跨 Store 并发、kind 隔离、JSONB 规范化、篡改和 ID 冲突；
- 尚未提供对象存储 Adapter、Web dashboard 和跨数据集版本统计；
- 尚未替业务建立真实中医黄金数据集，也没有部署账号的长期 Provider 基线；
- Langfuse Scores 可展示维度趋势，但当前框架不会从 Langfuse 反向读取并把第三方状态作为唯一发布事实源。

发布事实源应由 CI/受控评测任务持有，Langfuse、OpenTelemetry 或 dashboard 是观测出口，不是替代本地硬门禁的授权系统。

本地教学示例可直接运行：

```bash
sbt "examples/runMain com.zyblw.agent.examples.EvalTrendGateExample"
```

示例会把低敏记录写到 `target/eval-trends/agent-eval-example.jsonl`，不会访问公网或消耗模型额度。
正式 CI 应使用第 11 节的 `agent-eval-cli`，而不是永久允许 bootstrap 的教学示例。
