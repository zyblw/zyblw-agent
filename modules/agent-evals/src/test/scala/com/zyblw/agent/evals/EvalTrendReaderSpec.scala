package com.zyblw.agent.evals

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import java.time.Instant
import zio.*
import zio.test.*

/** 验证管理面趋势适配器的投影语义与 fail-closed 边界。
  *
  * 关键断言是"分数与门禁必须分开投影"：一个维度可以平均分很高而仍有个别用例硬门禁失败，把两者合成一个数字会让 管理台把一次真实的发布阻塞画成一条平滑上升的曲线。
  */
object EvalTrendReaderSpec extends ZIOSpecDefault:

  private val identity =
    EvalTrendIdentity(EvalSuiteKind.Rag, "tcm-retrieval", "tcm-golden", "2026-01")

  /** 只在内存里保存快照的最小 Store，使断言集中在投影而不是文件 framing。 */
  private def testStore(initial: Chunk[EvalSuiteSnapshot]): UIO[EvalTrendStore] =
    Ref.make(initial).map { state =>
      new EvalTrendStore:
        def append(snapshot: EvalSuiteSnapshot): IO[AgentError, Unit] = state.update(_ :+ snapshot)

        def latestPassing(target: EvalTrendIdentity): IO[AgentError, Option[EvalSuiteSnapshot]] =
          matching(target).map(_.filter(_.passed).lastOption)

        def history(target: EvalTrendIdentity, limit: Int): IO[AgentError, Chunk[EvalSuiteSnapshot]] =
          matching(target).map(_.takeRight(limit))

        private def matching(target: EvalTrendIdentity): UIO[Chunk[EvalSuiteSnapshot]] =
          state.get.map(_.filter(snapshot => EvalTrendIdentity.from(snapshot) == target))
    }

  private def metadata(evaluationId: String, finishedAtEpochSecond: Long): EvalSnapshotMetadata =
    EvalSnapshotMetadata(
      evaluationId = evaluationId,
      suiteId = identity.suiteId,
      datasetId = identity.datasetId,
      datasetVersion = identity.datasetVersion,
      harnessVersion = "harness-1",
      provider = Some("test-provider"),
      model = Some("test-model"),
      pricingVersion = None,
      commitSha = Some("abc123"),
      startedAt = Instant.ofEpochSecond(finishedAtEpochSecond - 1L),
      finishedAt = Instant.ofEpochSecond(finishedAtEpochSecond)
    )

  /** 构造两个用例：`ranking` 全部通过，`citation` 在第二个用例上硬门禁失败但分数并不低。 */
  private def snapshot(evaluationId: String, finishedAtEpochSecond: Long): EvalSuiteSnapshot =
    EvalSuiteSnapshot(
      schemaVersion = EvalSuiteSnapshot.CurrentSchemaVersion,
      kind = identity.kind,
      metadata = metadata(evaluationId, finishedAtEpochSecond),
      cases = Chunk(
        EvalCaseSnapshot(
          "case-1",
          identity.datasetVersion,
          Chunk(
            EvalDimensionSnapshot("ranking", passed = true, score = 1.0),
            EvalDimensionSnapshot("citation", passed = true, score = 0.5)
          )
        ),
        EvalCaseSnapshot(
          "case-2",
          identity.datasetVersion,
          Chunk(
            EvalDimensionSnapshot("ranking", passed = true, score = 0.5),
            EvalDimensionSnapshot("citation", passed = false, score = 0.25)
          )
        )
      )
    )

  private val view = EvalTrendReaderLive.identityView(identity)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("EvalTrendReaderLive")(
    test("维度分数与维度门禁分别投影，通过率来自用例硬门禁") {
      for
        store  <- testStore(Chunk(snapshot("eval-1", 1_000L), snapshot("eval-2", 2_000L)))
        reader <- EvalTrendReaderLive.make(store, Chunk(identity))
        series <- reader.history(view, 10)
        point  <- ZIO
          .fromOption(series.points.lastOption)
          .orElseFail(AgentError.InvalidConfiguration("趋势线缺少数据点"))
      yield assertTrue(
        series.identity == view,
        series.points.map(_.evaluationId) == Chunk("eval-1", "eval-2"),
        point.dimensionScores == Map("ranking" -> 0.75, "citation" -> 0.375),
        point.dimensionGates == Map("ranking" -> true, "citation" -> false),
        !point.passed,
        point.passRate == 0.5,
        point.caseCount == 2,
        point.finishedAtEpochMilli == 2_000_000L,
        point.commitSha.contains("abc123"),
        point.harnessVersion == "harness-1"
      )
    },
    test("跟踪的趋势线来自部署声明，重复声明被去重") {
      for
        store  <- testStore(Chunk.empty)
        reader <- EvalTrendReaderLive.make(store, Chunk(identity, identity))
        suites <- reader.suites
      yield assertTrue(
        suites == Chunk(view),
        suites.map(_.kind) == Chunk("Rag"),
        suites.map(_.key) == Chunk("Rag/tcm-retrieval/tcm-golden/2026-01")
      )
    },
    test("未知 suite kind 被拒绝，而不是返回空趋势线") {
      for
        store     <- testStore(Chunk(snapshot("eval-1", 1_000L)))
        reader    <- EvalTrendReaderLive.make(store, Chunk(identity))
        unknown   <- reader.history(view.copy(kind = "SecurityRedTeam"), 10).exit
        canonical <- reader.history(view.copy(kind = "rag"), 10)
      yield assertTrue(
        unknown.isFailure,
        canonical.identity.kind == "Rag",
        canonical.points.length == 1
      )
    },
    test("单条趋势线的返回条数被收敛到硬上限") {
      for
        store  <- testStore(Chunk(snapshot("eval-1", 1_000L), snapshot("eval-2", 2_000L)))
        reader <- EvalTrendReaderLive.make(store, Chunk(identity))
        one    <- reader.history(view, 1)
        zero   <- reader.history(view, 0)
      yield assertTrue(
        one.points.map(_.evaluationId) == Chunk("eval-2"),
        zero.points.length == 1
      )
    },
    test("部署声明里的非法身份在装配阶段 fail-closed") {
      for
        store   <- testStore(Chunk.empty)
        invalid <- EvalTrendReaderLive
          .make(store, Chunk(identity.copy(suiteId = "非法 suite id")))
          .exit
      yield assertTrue(invalid.isFailure)
    }
  )
