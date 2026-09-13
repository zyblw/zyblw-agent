package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.test.*

object RagRuntimeEvalSpec extends ZIOSpecDefault:
  def spec = suite("RAG runtime eval skeleton")(
    test("版本化数据集覆盖 query-type / no-answer / forbidden / 注入") {
      val types = RagRuntimeFixtures.cases.map(_.queryType).toSet
      assertTrue(
        RagRuntimeFixtures.cases.forall(_.datasetVersion == RagRuntimeFixtures.DatasetVersion),
        types.contains(RagQueryType.Keyword),
        types.contains(RagQueryType.Explanation),
        types.contains(RagQueryType.NoAnswer),
        types.contains(RagQueryType.Forbidden),
        types.contains(RagQueryType.Injection),
        RagRuntimeFixtures.cases.forall(_.hostCalibrationDeferred)
      )
    },
    test("消融 A–D 默认只开放 dense+FTS+rerank") {
      val runner = RagAblationRunner()
      assertTrue(
        runner.enabledByDefault(AblationLane.CDenseFtsRerank),
        !runner.enabledByDefault(AblationLane.DPlusSparse),
        runner.lane(AblationLane.ADenseOnly)._1 == RetrievalMode.VectorOnly,
        !runner.lane(AblationLane.BDenseFts)._2,
        runner.lane(AblationLane.CDenseFtsRerank)._2,
        runner.lane(AblationLane.DPlusSparse)._3
      )
    },
    test("安全硬门禁拒绝跨租户、注入、非有限分和撤回泄漏") {
      val tenant = TenantId("t")
      val scope  = RetrievalScope(tenant, Set("read"))
      val ok     = RetrievalHit(DocumentChunk("a", "doc", "可见", "a.md", tenant, Set("read")), 0.4)
      val leak = RetrievalHit(DocumentChunk("b", "secret", "秘密", "b.md", TenantId("other"), Set("read")), 1.0)
      val nan  = RetrievalHit(DocumentChunk("c", "doc", "坏分", "c.md", tenant, Set("read")), Double.NaN)
      val withdrawn = RetrievalHit(DocumentChunk("d", "gone", "已删", "d.md", tenant, Set("read")), 0.9)
      val leakCache = RagSecurityHardGates.CachePermissionProbe("read", "admin", returnedHit = true)
      val keepCache = RagSecurityHardGates.CachePermissionProbe("read", "read", returnedHit = true)
      assertTrue(
        RagSecurityHardGates.passed(RagSecurityHardGates.evaluate(Chunk(ok), scope)),
        !RagSecurityHardGates.passed(RagSecurityHardGates.evaluate(Chunk(leak), scope)),
        !RagSecurityHardGates.passed(RagSecurityHardGates.evaluate(Chunk(nan), scope)),
        !RagSecurityHardGates.passed(RagSecurityHardGates.evaluate(Chunk(withdrawn), scope, Set("gone"))),
        !RagSecurityHardGates.passed(
          RagSecurityHardGates.evaluate(Chunk(ok), scope, cacheProbe = Some(leakCache))
        ),
        RagSecurityHardGates.passed(
          RagSecurityHardGates.evaluate(Chunk(ok), scope, cacheProbe = Some(keepCache))
        )
      )
    }
  )
