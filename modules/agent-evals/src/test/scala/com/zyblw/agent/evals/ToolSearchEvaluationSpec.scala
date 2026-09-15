package com.zyblw.agent.evals

import zio.*
import zio.test.*

object ToolSearchEvaluationSpec extends ZIOSpecDefault:
  private val allowed = ToolSearchCatalog.fixtureCatalog.map(_.name).toSet
  private val catalog = ToolSearchCatalog.fixtureCatalog

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolSearchEvaluation")(
    test("300+ 固定工具集上按需目录降低 token 且召回目标工具") {
      val value  = ToolSearchEvaluation.evidence(catalog, allowed)
      val grades = ToolSearchEvaluation.grades(value)
      assertTrue(
        catalog.length >= 320,
        value.tokenReduction >= ToolSearchEvaluation.MinTokenReduction,
        value.targetRecall == 1.0,
        !value.permissionWidened,
        value.compositionNarrowed,
        grades.forall(_.passed)
      )
    },
    test("加载不能把冻结白名单之外的工具加入权限或轨迹") {
      val loaded =
        ToolSearchCatalog.load(Set("lookup_invoice"), Chunk("lookup_invoice", "shell_exec", "write_payment"))
      val rows =
        ToolSearchEvaluation.trajectoryTools(Chunk("lookup_invoice", "shell_exec"), Set("lookup_invoice"))
      assertTrue(
        loaded == Chunk("lookup_invoice"),
        rows.map(_.toolName) == Chunk("lookup_invoice"),
        !rows.exists(_.toolName == "shell_exec")
      )
    },
    test("全量目录 token 高于按需目录，未达标时不得扩 API") {
      val full   = ToolSearchCatalog.estimateTokens(catalog)
      val subset = ToolSearchCatalog.estimateTokens(ToolSearchCatalog.search(catalog, "发票 应收账款"))
      assertTrue(full > subset, subset <= full / 2)
    }
  )
