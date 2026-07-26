package com.zyblw.agent.evals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Comparator
import zio.*
import zio.json.*
import zio.test.*

/** 评测趋势与发布门禁测试。
  *
  * 测试重点不是“JSON 能否序列化”，而是生产语义：
  *
  *   - 原始问题、grade details 和秘密不能进入长期趋势；
  *   - 候选不能靠平均分掩盖硬门禁失败、删除用例或降低维度分数；
  *   - 并发 Fiber 追加不会交错记录，相同 evaluationId 保持幂等；
  *   - 最后半条崩溃记录可以恢复，但完整记录 checksum 损坏必须 fail-closed；
  *   - 同一 evaluationId 绑定不同快照时不能覆盖历史事实。
  */
object EvalTrendSpec extends ZIOSpecDefault:
  private val baseInstant   = Instant.parse("2026-07-17T00:00:00Z")
  private val agentIdentity =
    EvalTrendIdentity(EvalSuiteKind.Agent, "tcm-learning-agent", "tcm-learning-golden", "dataset-v1")

  /** 创建一份稳定低敏 metadata；测试通过序号控制历史顺序。 */
  private def metadata(
      evaluationId: String,
      sequence: Int,
      datasetVersion: String = "dataset-v1"
  ): EvalSnapshotMetadata =
    EvalSnapshotMetadata(
      evaluationId = evaluationId,
      suiteId = "tcm-learning-agent",
      datasetId = "tcm-learning-golden",
      datasetVersion = datasetVersion,
      harnessVersion = "harness-v1",
      provider = Some("stub"),
      model = Some("stub-model"),
      pricingVersion = Some("price-v1"),
      commitSha = Some("abcdef1234"),
      startedAt = baseInstant.plusSeconds(sequence.toLong),
      finishedAt = baseInstant.plusSeconds(sequence.toLong).plusMillis(100L)
    )

  /** 创建一条用例；调用方可以控制维度分数和硬门禁。 */
  private def evalCase(
      caseId: String,
      toolScore: Double = 1.0,
      toolPassed: Boolean = true,
      includeBudget: Boolean = true
  ): EvalCaseSnapshot =
    val dimensions =
      Chunk(EvalDimensionSnapshot("tool-selection", toolPassed, toolScore)) ++
        Chunk.fromIterable(
          Option.when(includeBudget)(EvalDimensionSnapshot("resource-budget", passed = true, score = 1.0))
        )
    EvalCaseSnapshot(caseId, "dataset-v1", dimensions)

  /** 直接构造已经满足快照语义的测试对象。 */
  private def snapshot(
      evaluationId: String,
      sequence: Int,
      cases: Chunk[EvalCaseSnapshot] = Chunk(evalCase("case-1"))
  ): EvalSuiteSnapshot =
    EvalSuiteSnapshot(
      EvalSuiteSnapshot.CurrentSchemaVersion,
      EvalSuiteKind.Agent,
      metadata(evaluationId, sequence),
      cases
    )

  /** 在临时目录中创建真实文件 Store。
    *
    * @param use
    *   获得 `(store, path)` 后执行测试；Scope 结束时删除文件和目录
    */
  private def withStore[A](
      use: (FileEvalTrendStore, Path) => ZIO[Any, Any, A]
  ): ZIO[Any, Any, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(
          ZIO.attemptBlockingIO(Files.createTempDirectory("eval-trend-spec-")).orDie
        ) { directory =>
          ZIO.attemptBlockingIO {
            val path = directory.resolve("trend.jsonl")
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
          }.orDie
        }
        .flatMap { directory =>
          val path = directory.resolve("trend.jsonl")
          FileEvalTrendStore
            .make(
              FileEvalTrendStoreConfig(path, maxFileBytes = 4L * 1024L * 1024L, maxRecordBytes = 256 * 1024)
            )
            .flatMap(store => use(store, path))
        }
    }

  /** 创建可包含多个 artifact/符号链接的临时目录，并在 Scope 结束时按“子项在前、父目录在后”递归删除。
    *
    * 测试辅助器也使用 `acquireRelease`，避免断言失败或 Fiber 中断后在开发机留下临时文件。
    */
  private def withTempDirectory[A](
      use: Path => ZIO[Any, Any, A]
  ): ZIO[Any, Any, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(
          ZIO.attemptBlockingIO(Files.createTempDirectory("eval-snapshot-artifact-spec-")).orDie
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

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Eval trend and release gate")(
    test("快照投影删除输入、grade details 和错误正文，只保留稳定评分") {
      val secret = "用户原始问题与密钥 sk-secret-must-not-persist"
      val suite  = AgentEvalSuiteReport(
        Chunk(
          AgentEvalReport(
            "case-1",
            "dataset-v1",
            Chunk(
              EvalGrade("tool-selection", passed = true, score = 1.0, details = secret),
              EvalGrade("resource-budget", passed = true, score = 1.0, details = "latency=10")
            )
          )
        )
      )
      for projected <- EvalSuiteSnapshot.fromAgent(metadata("eval-projection", 1), suite)
      yield
        val json = projected.toJson
        assertTrue(
          projected.passed,
          projected.cases.headOption.exists(_.dimensions.length == 2),
          !json.contains(secret),
          !json.contains("sk-secret"),
          !json.contains("details")
        )
    },
    test("默认发布策略拒绝硬门禁失败、删除基线用例/维度和分数下降") {
      val baseline = snapshot(
        "eval-baseline",
        1,
        Chunk(evalCase("case-1"), evalCase("case-2"))
      )
      val candidate = snapshot(
        "eval-candidate",
        2,
        Chunk(evalCase("case-1", toolScore = 0.7, toolPassed = false, includeBudget = false))
      )
      val decision = EvalReleaseGate.evaluate(Some(baseline), candidate)
      val codes    = decision.issues.map(_.code).toSet
      assertTrue(
        !decision.passed,
        codes.contains(EvalRegressionIssueCode.CandidateHardGateFailed),
        codes.contains(EvalRegressionIssueCode.PassRateRegressed),
        codes.contains(EvalRegressionIssueCode.BaselineCaseRemoved),
        codes.contains(EvalRegressionIssueCode.CaseHardGateRegressed),
        codes.contains(EvalRegressionIssueCode.BaselineDimensionRemoved),
        codes.contains(EvalRegressionIssueCode.DimensionScoreRegressed)
      )
    },
    test("首次建立基线必须显式开启，而且候选仍需通过自己的硬门禁") {
      val passing = snapshot("eval-first-pass", 1)
      val failing = snapshot(
        "eval-first-fail",
        2,
        Chunk(evalCase("case-1", toolScore = 0.0, toolPassed = false))
      )
      val strictDecision    = EvalReleaseGate.evaluate(None, passing)
      val bootstrapPolicy   = EvalRegressionPolicy(allowFirstPassingBaseline = true)
      val bootstrapDecision = EvalReleaseGate.evaluate(None, passing, bootstrapPolicy)
      val failedBootstrap   = EvalReleaseGate.evaluate(None, failing, bootstrapPolicy)
      assertTrue(
        !strictDecision.passed,
        strictDecision.issues.exists(_.code == EvalRegressionIssueCode.BaselineMissing),
        bootstrapDecision.passed,
        !failedBootstrap.passed,
        failedBootstrap.issues.exists(_.code == EvalRegressionIssueCode.CandidateHardGateFailed)
      )
    },
    test("未授权 bootstrap 的通过候选不会被追加，因此下一次运行仍不能隐式取得基线") {
      withStore { (store, _) =>
        val first  = snapshot("eval-unapproved-bootstrap-1", 1)
        val second = snapshot("eval-unapproved-bootstrap-2", 2)
        for
          firstDecision  <- EvalReleaseGate.evaluateAndAppend(store, first)
          firstHistory   <- store.history(agentIdentity, 10)
          secondDecision <- EvalReleaseGate.evaluateAndAppend(store, second)
          secondHistory  <- store.history(agentIdentity, 10)
          baseline       <- store.latestPassing(agentIdentity)
        yield assertTrue(
          !firstDecision.passed,
          firstDecision.issues.exists(_.code == EvalRegressionIssueCode.BaselineMissing),
          firstHistory.isEmpty,
          !secondDecision.passed,
          secondDecision.issues.exists(_.code == EvalRegressionIssueCode.BaselineMissing),
          secondHistory.isEmpty,
          baseline.isEmpty
        )
      }
    },
    test("未建立基线时，候选自身硬门禁失败仍可留痕且不会成为成功基线") {
      withStore { (store, _) =>
        val failed = snapshot(
          "eval-first-hard-gate-failure",
          1,
          Chunk(evalCase("case-1", toolScore = 0.0, toolPassed = false))
        )
        val bootstrap = EvalRegressionPolicy(allowFirstPassingBaseline = true)
        for
          decision <- EvalReleaseGate.evaluateAndAppend(store, failed, bootstrap)
          history  <- store.history(agentIdentity, 10)
          baseline <- store.latestPassing(agentIdentity)
        yield assertTrue(
          !decision.passed,
          decision.issues.exists(_.code == EvalRegressionIssueCode.CandidateHardGateFailed),
          history == Chunk(failed),
          baseline.isEmpty
        )
      }
    },
    test("低敏 artifact 读取器严格校验普通文件、UTF-8、容量和快照领域语义") {
      withTempDirectory { directory =>
        val validPath     = directory.resolve("valid.json")
        val invalidUtf8   = directory.resolve("invalid-utf8.json")
        val oversized     = directory.resolve("oversized.json")
        val invalidDomain = directory.resolve("invalid-domain.json")
        val symlink       = directory.resolve("snapshot-link.json")
        val written       = directory.resolve("written.json")
        val valid         = snapshot("eval-artifact-valid", 1)
        val invalid       = valid.copy(cases = Chunk.empty)
        for
          _ <- ZIO
            .attemptBlockingIO(
              Files.writeString(validPath, valid.toJson, StandardCharsets.UTF_8)
            )
            .orDie
          _ <- ZIO
            .attemptBlockingIO(
              Files.write(invalidUtf8, Array(0xc3.toByte, 0x28.toByte))
            )
            .orDie
          _ <- ZIO
            .attemptBlockingIO(
              Files.writeString(oversized, valid.toJson, StandardCharsets.UTF_8)
            )
            .orDie
          _ <- ZIO
            .attemptBlockingIO(
              Files.writeString(invalidDomain, invalid.toJson, StandardCharsets.UTF_8)
            )
            .orDie
          _          <- ZIO.attemptBlockingIO(Files.createSymbolicLink(symlink, validPath)).orDie
          _          <- EvalSnapshotArtifact.write(EvalSnapshotArtifactConfig(written), valid)
          loaded     <- EvalSnapshotArtifact.load(EvalSnapshotArtifactConfig(validPath))
          roundTrip  <- EvalSnapshotArtifact.load(EvalSnapshotArtifactConfig(written))
          utf8Result <- EvalSnapshotArtifact.load(EvalSnapshotArtifactConfig(invalidUtf8)).either
          sizeResult <- EvalSnapshotArtifact.load(EvalSnapshotArtifactConfig(oversized, maxBytes = 8)).either
          domainResult <- EvalSnapshotArtifact.load(EvalSnapshotArtifactConfig(invalidDomain)).either
          linkResult   <- EvalSnapshotArtifact.load(EvalSnapshotArtifactConfig(symlink)).either
        yield assertTrue(
          loaded == valid,
          roundTrip == valid,
          utf8Result.left.exists(_.message == "eval-release:invalid-artifact-utf8"),
          sizeResult.left.exists(_.message == "eval-release:artifact-too-large"),
          domainResult.left.exists(_.message == "eval-trend:empty-suite"),
          linkResult.left.exists(_.message == "eval-release:invalid-artifact-target"),
          List(utf8Result, sizeResult, domainResult, linkResult).forall(
            _.left.forall(error => !error.message.contains(directory.toString))
          )
        )
      }
    },
    test("并发追加、幂等重放和有界历史保持完整、确定且按完成时间排序") {
      withStore { (store, _) =>
        val values = Chunk.fromIterable((1 to 24).map(index => snapshot(f"eval-$index%02d", index)))
        for
          _       <- ZIO.foreachParDiscard(values)(store.append)
          _       <- store.append(values(10))
          history <- store.history(agentIdentity, 100)
          tail    <- store.history(agentIdentity, 5)
        yield assertTrue(
          history.length == 24,
          history.map(_.metadata.evaluationId) == values.map(_.metadata.evaluationId),
          tail.map(_.metadata.evaluationId) == values.takeRight(5).map(_.metadata.evaluationId)
        )
      }
    },
    test("最后半条崩溃记录会在下一次追加前截断，不污染既有完整记录") {
      withStore { (store, path) =>
        val first  = snapshot("eval-before-crash", 1)
        val second = snapshot("eval-after-crash", 2)
        for
          _ <- store.append(first)
          _ <- ZIO
            .attemptBlockingIO(
              Files.write(
                path,
                """{"schemaVersion":1,"payloadBase64":"partial""".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND
              )
            )
            .orDie
          _       <- store.append(second)
          history <- store.history(agentIdentity, 10)
          bytes   <- ZIO.attemptBlockingIO(Files.readAllBytes(path)).orDie
        yield assertTrue(
          history.map(_.metadata.evaluationId) == Chunk("eval-before-crash", "eval-after-crash"),
          bytes.nonEmpty,
          bytes.last == '\n'.toByte
        )
      }
    },
    test("完整记录 checksum 被篡改时 fail-closed，错误不包含文件内容") {
      withStore { (store, path) =>
        val value = snapshot("eval-tampered", 1)
        for
          _       <- store.append(value)
          content <- ZIO.attemptBlockingIO(Files.readString(path, StandardCharsets.UTF_8)).orDie
          tampered = content.replaceFirst("[a-f0-9]{64}", "0" * 64)
          _ <- ZIO
            .attemptBlockingIO(Files.writeString(path, tampered, StandardCharsets.UTF_8))
            .orDie
          result <- store.history(agentIdentity, 10).either
        yield assertTrue(
          result.left.exists(_.message == "eval-trend:checksum-mismatch"),
          result.left.forall(!_.message.contains("eval-tampered"))
        )
      }
    },
    test("同一 evaluationId 绑定不同内容时明确冲突，不能覆盖基线事实") {
      withStore { (store, _) =>
        val original = snapshot("eval-idempotency", 1)
        val changed  = snapshot(
          "eval-idempotency",
          2,
          Chunk(evalCase("case-1", toolScore = 0.5, toolPassed = false))
        ).copy(metadata = metadata("eval-idempotency", 1))
        for
          _       <- store.append(original)
          result  <- store.append(changed).either
          history <- store.history(agentIdentity, 10)
        yield assertTrue(
          result.left.exists(_.message == "eval-trend:evaluation-id-conflict"),
          history == Chunk(original)
        )
      }
    },
    test("evaluateAndAppend 只使用最近成功快照作为基线，但失败候选仍进入趋势") {
      withStore { (store, _) =>
        val first  = snapshot("eval-success-1", 1)
        val failed = snapshot(
          "eval-failed-2",
          2,
          Chunk(evalCase("case-1", toolScore = 0.0, toolPassed = false))
        )
        val recovered = snapshot("eval-success-3", 3)
        val bootstrap = EvalRegressionPolicy(allowFirstPassingBaseline = true)
        for
          firstDecision     <- EvalReleaseGate.evaluateAndAppend(store, first, bootstrap)
          failedDecision    <- EvalReleaseGate.evaluateAndAppend(store, failed)
          recoveredDecision <- EvalReleaseGate.evaluateAndAppend(store, recovered)
          history           <- store.history(agentIdentity, 10)
        yield assertTrue(
          firstDecision.passed,
          !failedDecision.passed,
          recoveredDecision.passed,
          recoveredDecision.baselineEvaluationId.contains(first.metadata.evaluationId),
          history.map(_.metadata.evaluationId) ==
            Chunk(first.metadata.evaluationId, failed.metadata.evaluationId, recovered.metadata.evaluationId)
        )
      }
    },
    test("完整身份包含 kind，RAG 快照不会被同名 Agent 套件选为基线") {
      withStore { (store, _) =>
        val agent       = snapshot("eval-agent-kind", 1)
        val rag         = snapshot("eval-rag-kind", 2).copy(kind = EvalSuiteKind.Rag)
        val ragIdentity = agentIdentity.copy(kind = EvalSuiteKind.Rag)
        for
          _             <- store.append(agent)
          _             <- store.append(rag)
          agentHistory  <- store.history(agentIdentity, 10)
          ragHistory    <- store.history(ragIdentity, 10)
          agentBaseline <- store.latestPassing(agentIdentity)
          ragBaseline   <- store.latestPassing(ragIdentity)
        yield assertTrue(
          agentHistory == Chunk(agent),
          ragHistory == Chunk(rag),
          agentBaseline.contains(agent),
          ragBaseline.contains(rag)
        )
      }
    }
  )
