package com.zyblw.agent.evals.cli

import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import zio.*
import zio.json.*
import zio.test.*

/** 正式评测发布 CLI 的配置、退出码、Secret 脱敏和 bootstrap 契约测试。
  *
  * 测试直接调用纯 ZIO 程序，不执行 `System.exit`；这样可以确定性验证所有分支，同时保留真正 CLI 最外层的标准进程语义。
  */
object EvalReleaseGateCliSpec extends ZIOSpecDefault:
  private val baseInstant = Instant.parse("2026-07-17T00:00:00Z")

  /** 创建一个自身全部硬门禁通过、且不含业务正文的候选快照。 */
  private def snapshot(evaluationId: String): EvalSuiteSnapshot =
    EvalSuiteSnapshot(
      EvalSuiteSnapshot.CurrentSchemaVersion,
      EvalSuiteKind.Agent,
      EvalSnapshotMetadata(
        evaluationId = evaluationId,
        suiteId = "cli-agent-suite",
        datasetId = "cli-golden-dataset",
        datasetVersion = "dataset-v1",
        harnessVersion = "harness-v1",
        provider = Some("stub"),
        model = Some("stub-model"),
        pricingVersion = None,
        commitSha = Some("abcdef1234"),
        startedAt = baseInstant,
        finishedAt = baseInstant.plusSeconds(1)
      ),
      Chunk(
        EvalCaseSnapshot(
          "case-1",
          "dataset-v1",
          Chunk(
            EvalDimensionSnapshot("tool-selection", passed = true, score = 1.0),
            EvalDimensionSnapshot("citation-correctness", passed = true, score = 1.0)
          )
        )
      )
    )

  /** 创建并可靠清理 CLI 测试目录。 */
  private def withTempDirectory[A](
      use: Path => ZIO[Any, Any, A]
  ): ZIO[Any, Any, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(
          ZIO.attemptBlockingIO(Files.createTempDirectory("eval-release-cli-spec-")).orDie
        ) { directory =>
          ZIO.attemptBlockingIO {
            val stream = Files.walk(directory)
            try
              stream
                .sorted(Comparator.reverseOrder())
                .forEach(path =>
                  val _ = Files.deleteIfExists(path)
                )
            finally stream.close()
          }.orDie
        }
        .flatMap(use)
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("EvalReleaseGateCli")(
    test("ZIO Config 文件模式使用 fail-closed 默认值并支持部署参数覆盖") {
      withTempDirectory { directory =>
        val values = Map(
          "zyblw.agent.eval.release.artifact.path"         -> directory.resolve("snapshot.json").toString,
          "zyblw.agent.eval.release.artifact.max_bytes"    -> "1048576",
          "zyblw.agent.eval.release.store.backend"         -> "file",
          "zyblw.agent.eval.release.file.path"             -> directory.resolve("trend.jsonl").toString,
          "zyblw.agent.eval.release.file.max_file_bytes"   -> "8388608",
          "zyblw.agent.eval.release.file.max_record_bytes" -> "262144",
          "zyblw.agent.eval.release.policy.max_dimension_score_drop"     -> "0.02",
          "zyblw.agent.eval.release.policy.require_all_baseline_cases"   -> "true",
          "zyblw.agent.eval.release.policy.allow_first_passing_baseline" -> "false"
        )
        EvalReleaseGateCliConfig
          .load()
          .provide(configProvider(values))
          .map { config =>
            assertTrue(
              config.backend == EvalReleaseStoreBackend.File,
              config.artifact.maxBytes == 1048576,
              config.fileStore.exists(_.maxFileBytes == 8388608L),
              config.fileStore.exists(_.maxRecordBytes == 262144),
              config.postgresStore.isEmpty,
              config.policy.maxPassRateDrop == 0.0,
              config.policy.maxDimensionScoreDrop == 0.02,
              config.policy.requireCandidateHardGates,
              !config.policy.allowFirstPassingBaseline
            )
          }
      }
    },
    test("PostgreSQL 密码保持 Config.Secret，配置摘要和错误都不泄露") {
      val secret      = "postgres-secret-must-not-leak"
      val validValues = Map(
        "zyblw.agent.eval.release.artifact.path"     -> "/tmp/candidate.json",
        "zyblw.agent.eval.release.store.backend"     -> "postgres",
        "zyblw.agent.eval.release.postgres.jdbc_url" -> "jdbc:postgresql://localhost:5432/zyblw",
        "zyblw.agent.eval.release.postgres.user"     -> "zyblw_eval_release",
        "zyblw.agent.eval.release.postgres.password" -> secret,
        "zyblw.agent.eval.release.postgres.connect_timeout_seconds" -> "7"
      )
      val invalidValues = validValues.updated(
        "zyblw.agent.eval.release.postgres.jdbc_url",
        s"jdbc:postgresql://localhost:5432/zyblw?password=$secret"
      )
      for
        loaded <- EvalReleaseGateCliConfig.load().provide(configProvider(validValues))
        failed <- EvalReleaseGateCliConfig.load().provide(configProvider(invalidValues)).exit
      yield assertTrue(
        loaded.backend == EvalReleaseStoreBackend.Postgres,
        loaded.postgresStore.exists(_.connectTimeoutSeconds == 7),
        loaded.postgresStore.exists(_.password.stringValue == secret),
        !loaded.toString.contains(secret),
        failed.isFailure,
        !failed.toString.contains(secret)
      )
    },
    test("未授权首基线稳定退出 2 且不写趋势，显式 bootstrap 后退出 0") {
      withTempDirectory { directory =>
        val artifactPath = directory.resolve("candidate.json")
        val trendPath    = directory.resolve("trend.jsonl")
        val candidate    = snapshot("cli-eval-1")
        val baseConfig   = EvalReleaseGateCliConfig(
          artifact = EvalSnapshotArtifactConfig(artifactPath, 256 * 1024),
          backend = EvalReleaseStoreBackend.File,
          fileStore = Some(
            FileEvalTrendStoreConfig(
              trendPath,
              maxFileBytes = 4L * 1024L * 1024L,
              maxRecordBytes = 256 * 1024
            )
          ),
          postgresStore = None,
          policy = EvalRegressionPolicy()
        )
        val identity = EvalTrendIdentity.from(candidate)
        for
          _ <- ZIO
            .attemptBlockingIO(Files.writeString(artifactPath, candidate.toJson, StandardCharsets.UTF_8))
            .orDie
          rejected          <- EvalReleaseGateCliProgram.run(baseConfig)
          existsAfterReject <- ZIO.attemptBlockingIO(Files.exists(trendPath)).orDie
          bootstrapped      <- EvalReleaseGateCliProgram.run(
            baseConfig.copy(
              policy = EvalRegressionPolicy(allowFirstPassingBaseline = true)
            )
          )
          store    <- FileEvalTrendStore.make(baseConfig.fileStore.get)
          history  <- store.history(identity, 10)
          repeated <- EvalReleaseGateCliProgram.run(baseConfig)
        yield assertTrue(
          rejected.output.status == EvalReleaseCliStatus.Rejected,
          rejected.exitCode == ExitCode(EvalReleaseCliResult.RejectedExitCode),
          rejected.output.decision.exists(_.issues.exists(_.code == EvalRegressionIssueCode.BaselineMissing)),
          !existsAfterReject,
          bootstrapped.output.status == EvalReleaseCliStatus.Passed,
          bootstrapped.exitCode == ExitCode.success,
          history == Chunk(candidate),
          repeated.output.status == EvalReleaseCliStatus.Passed,
          repeated.output.decision.flatMap(_.baselineEvaluationId).contains(candidate.metadata.evaluationId)
        )
      }
    },
    test("typed error 分类为稳定退出码，输出不会回显任意基础设施正文") {
      val secret = "jdbc:postgresql://db/zyblw?password=must-not-leak"
      val result = EvalReleaseCliResult.fromError(
        AgentError.PersistenceFailure(s"连接失败: $secret")
      )
      val json = result.output.toJson
      assertTrue(
        result.output.status == EvalReleaseCliStatus.Error,
        result.exitCode == ExitCode(EvalReleaseCliResult.InfrastructureExitCode),
        result.output.errorCode.contains("agent-error:persistence"),
        !json.contains(secret),
        result.output.decision.isEmpty
      )
    }
  )

  /** 使用 Fiber-local ConfigProvider，避免并行测试修改全局环境变量。 */
  private def configProvider(values: Map[String, String]): ULayer[Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(values))
