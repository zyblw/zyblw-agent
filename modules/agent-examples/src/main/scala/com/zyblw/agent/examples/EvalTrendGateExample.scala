package com.zyblw.agent.examples

import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import java.nio.file.Files
import java.nio.file.Path
import zio.*
import zio.json.*

/** 可直接运行的低敏评测趋势与发布门禁示例。
  *
  * 本示例不访问 Provider 或数据库，专门演示如何把已经得到的 `AgentEvalSuiteReport`：
  *
  *   1. 投影为不含 input、details 和错误正文的 `EvalSuiteSnapshot`；
  *   2. 与同数据集版本的最近成功基线比较；
  *   3. 把通过、质量失败或显式 bootstrap 候选追加到带 checksum/fsync 的趋势文件；
  *   4. 让 CI 依据 `EvalReleaseDecision.passed` 退出。
  *
  * 示例永久允许“首个全通过快照建立基线”只是为了反复本地运行方便。正式 CI 应只在人工确认的初始化任务中开启一次， 后续发布任务使用默认
  * `allowFirstPassingBaseline=false`；正式流水线优先运行独立 `agent-eval-cli`。
  */
object EvalTrendGateExample extends ZIOAppDefault:
  private val trendPath    = Path.of("target", "eval-trends", "agent-eval-example.jsonl")
  private val artifactPath = Path.of("target", "evals", "agent-eval-example-snapshot.json")

  /** 构造一份不含业务正文的通过报告，模拟前一步真实 AgentEvalRunner 的输出。 */
  private val report = AgentEvalSuiteReport(
    Chunk(
      AgentEvalReport(
        caseId = "grounded-learning-answer",
        datasetVersion = "example-dataset-v1",
        grades = Chunk(
          EvalGrade("tool-selection", passed = true, score = 1.0, details = "不会进入长期趋势"),
          EvalGrade("citation-correctness", passed = true, score = 1.0, details = "不会进入长期趋势"),
          EvalGrade("recovery-correctness", passed = true, score = 1.0, details = "不会进入长期趋势"),
          EvalGrade("resource-budget", passed = true, score = 1.0, details = "不会进入长期趋势")
        )
      )
    )
  )

  /** 创建目录、生成本次低敏身份、执行比较并打印决策。
    *
    * evaluationId 使用 UUID，CI 环境更推荐使用“流水线 run ID + attempt”并在相同逻辑重试时保持稳定。
    */
  def run: ZIO[Any, Any, Any] =
    for
      _ <- ZIO
        .attemptBlockingIO(Files.createDirectories(trendPath.toAbsolutePath.normalize.getParent))
        .mapError(_ => AgentError.PersistenceFailure("eval-trend-example:create-directory-failed"))
      now <- Clock.instant
      id  <- Random.nextUUID
      metadata = EvalSnapshotMetadata(
        evaluationId = s"example-$id",
        suiteId = "agent-eval-example",
        datasetId = "agent-eval-example-dataset",
        datasetVersion = "example-dataset-v1",
        harnessVersion = "example-harness-v1",
        provider = Some("scripted"),
        model = Some("scripted-model"),
        pricingVersion = None,
        commitSha = None,
        startedAt = now,
        finishedAt = now
      )
      snapshot <- EvalSuiteSnapshot.fromAgent(metadata, report)
      _        <- ZIO
        .attemptBlockingIO(Files.createDirectories(artifactPath.toAbsolutePath.normalize.getParent))
        .mapError(_ => AgentError.PersistenceFailure("eval-trend-example:create-artifact-directory-failed"))
      _ <- EvalSnapshotArtifact.write(
        EvalSnapshotArtifactConfig(artifactPath, maxBytes = 256 * 1024),
        snapshot
      )
      store <- FileEvalTrendStore.make(
        FileEvalTrendStoreConfig(
          trendPath,
          maxFileBytes = 8L * 1024L * 1024L,
          maxRecordBytes = 256 * 1024
        )
      )
      decision <- EvalReleaseGate.evaluateAndAppend(
        store,
        snapshot,
        EvalRegressionPolicy(allowFirstPassingBaseline = true)
      )
      _ <- Console.printLine(decision.toJsonPretty)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("eval-trend-example:release-gate-failed"))
        .unless(decision.passed)
    yield ()
