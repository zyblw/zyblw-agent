package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import com.zyblw.agent.tools.{ApprovalPolicy, ToolPolicyConfig}
import zio.*
import zio.test.*

object RuntimeSettingsSpec extends ZIOSpecDefault:
  private val baseline = ToolPolicyConfig(
    allowedTools = Set(ToolName("search"), ToolName("fetch")),
    deniedTools = Set.empty,
    approvalPolicy = ApprovalPolicy.RiskBased,
    maxCallsPerRun = 20,
    maxCallsPerStep = 3,
    maxParallelism = 4
  )

  private def service(
      topK: Int = 5,
      minimumScore: Double = 0.0,
      rerank: Boolean = false
  ): ZIO[RuntimeOverrideStore, StoreError, RuntimeSettingsService] =
    RuntimeSettingsService.make(baseline, topK, minimumScore, rerank)

  def spec = suite("RuntimeSettingsService")(
    test("空覆盖下生效值逐项等于部署基线") {
      (for
        settings <- service(topK = 7, minimumScore = 0.25, rerank = true)
        current  <- settings.effective
      yield assertTrue(
        current.toolPolicy == baseline,
        current.retrievalTopK == 7,
        current.retrievalMinimumScore == 0.25,
        current.rerankEnabled,
        current.overrideVersion == 0L
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("稀疏补丁只改写被设置的字段，其余保持基线") {
      (for
        settings <- service()
        _        <- settings.update(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(1)), "ops", "收紧单步并发")
        current  <- settings.effective
      yield assertTrue(
        current.toolPolicy.maxCallsPerStep == 1,
        current.toolPolicy.maxCallsPerRun == baseline.maxCallsPerRun,
        current.toolPolicy.allowedTools == baseline.allowedTools,
        current.toolPolicy.approvalPolicy == baseline.approvalPolicy
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("移除一项覆盖与从未设置过它完全等价") {
      (for
        settings <- service()
        first    <- settings.update(0L, RuntimeOverrides(toolMaxCallsPerRun = Some(3)), "ops", "临时收紧")
        narrowed <- settings.effective
        _        <- settings.update(first.overrideVersion, RuntimeOverrides.none, "ops", "恢复基线")
        restored <- settings.effective
      yield assertTrue(
        narrowed.toolPolicy.maxCallsPerRun == 3,
        restored.toolPolicy.maxCallsPerRun == baseline.maxCallsPerRun
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("陈旧 expectedVersion 返回乐观锁冲突且不改变生效配置") {
      (for
        settings <- service()
        _        <- settings.update(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(2)), "first", "先提交")
        conflict <- settings.update(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(9)), "second", "后提交").exit
        current  <- settings.effective
      yield assertTrue(
        conflict.isFailure,
        current.toolPolicy.maxCallsPerStep == 2
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("非法覆盖在写入存储之前被拒绝，版本不前进") {
      (for
        settings <- service()
        rejected <- settings.update(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(0)), "ops", "越界").exit
        view     <- settings.view
      yield assertTrue(rejected.isFailure, view.overrideVersion == 0L))
        .provide(RuntimeOverrideStore.inMemory)
    },
    test("白名单与黑名单交集被拒绝") {
      val overlapping = RuntimeOverrides(
        toolAllowedTools = Some(Set("search", "fetch")),
        toolDeniedTools = Some(Set("fetch"))
      )
      (for
        settings <- service()
        rejected <- settings.update(0L, overlapping, "ops", "自相矛盾").exit
      yield assertTrue(rejected.isFailure)).provide(RuntimeOverrideStore.inMemory)
    },
    test("空白名单表示禁用全部工具，而不是回退到基线") {
      (for
        settings <- service()
        _        <- settings.update(0L, RuntimeOverrides(toolAllowedTools = Some(Set.empty)), "ops", "全面停用")
        current  <- settings.effective
      yield assertTrue(current.toolPolicy.allowedTools.isEmpty)).provide(RuntimeOverrideStore.inMemory)
    },
    test("越界的检索基线被收敛而不是让服务构造失败") {
      (for
        settings <- service(topK = 5_000, minimumScore = 4.2)
        current  <- settings.effective
      yield assertTrue(current.retrievalTopK == 100, current.retrievalMinimumScore == 1.0))
        .provide(RuntimeOverrideStore.inMemory)
    },
    test("NaN 最低得分收敛为 0，不会静默清空全部检索结果") {
      (for
        settings <- service(minimumScore = Double.NaN)
        current  <- settings.effective
      yield assertTrue(current.retrievalMinimumScore == 0.0)).provide(RuntimeOverrideStore.inMemory)
    },
    test("策略源与缓存同步，无需重新解析服务即可读到新值") {
      (for
        settings <- service(topK = 5, rerank = false)
        tools      = settings.toolPolicySource
        retrieval  = settings.retrievalPolicySource
        beforeTopK = retrieval.current().topK
        _ <- settings.update(
          0L,
          RuntimeOverrides(
            toolMaxCallsPerStep = Some(1),
            retrievalTopK = Some(20),
            rerankEnabled = Some(true)
          ),
          "ops",
          "同时收紧工具与放宽检索"
        )
      yield assertTrue(
        beforeTopK == 5,
        tools.current().maxCallsPerStep == 1,
        retrieval.current().topK == 20,
        retrieval.current().rerankEnabled
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("refresh 让未参与写入的副本读到其它副本提交的覆盖") {
      (for
        writer <- service()
        reader <- service()
        _      <- writer.update(0L, RuntimeOverrides(toolMaxCallsPerRun = Some(2)), "replica-a", "收紧")
        stale  <- reader.effective
        _      <- reader.refresh
        fresh  <- reader.effective
      yield assertTrue(
        stale.toolPolicy.maxCallsPerRun == baseline.maxCallsPerRun,
        fresh.toolPolicy.maxCallsPerRun == 2
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("maxParallelism 标注为需重启且不接受覆盖") {
      (for
        settings <- service()
        view     <- settings.view
        field = view.fields.find(_.key == "toolMaxParallelism")
      yield assertTrue(
        field.exists(_.applies == RuntimeSettingApplies.Restart),
        field.exists(_.overrideValue.isEmpty),
        field.exists(_.effectiveValue == baseline.maxParallelism.toString)
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("配置视图同时给出基线、覆盖与生效值，并标注安全敏感项") {
      (for
        settings <- service()
        _    <- settings.update(0L, RuntimeOverrides(toolApprovalPolicy = Some("always")), "ops", "提高审批强度")
        view <- settings.view
        approval = view.fields.find(_.key == "toolApprovalPolicy")
      yield assertTrue(
        approval.exists(_.baselineValue == "risk-based"),
        approval.exists(_.overrideValue.contains("always")),
        approval.exists(_.effectiveValue == "always"),
        approval.exists(_.sensitive),
        approval.exists(_.applies == RuntimeSettingApplies.NextRun)
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("工具集合按字典序渲染，使基线与覆盖的比较不受迭代顺序影响") {
      (for
        settings <- service()
        _        <- settings
          .update(0L, RuntimeOverrides(toolAllowedTools = Some(Set("zeta", "alpha"))), "ops", "调整白名单")
        view <- settings.view
        allowed = view.fields.find(_.key == "toolAllowedTools")
      yield assertTrue(
        allowed.exists(_.effectiveValue == "alpha,zeta"),
        allowed.exists(_.baselineValue == "fetch,search")
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("审计历史按版本倒序保留每次写入的操作者与原因") {
      (for
        settings <- service()
        first    <- settings.update(0L, RuntimeOverrides(toolMaxCallsPerRun = Some(9)), "alice", "第一次")
        _        <- settings
          .update(first.overrideVersion, RuntimeOverrides(toolMaxCallsPerRun = Some(8)), "bob", "第二次")
        history <- settings.history(10)
      yield assertTrue(
        history.length == 3,
        history.head.version == 2L,
        history.head.updatedBy == "bob",
        history.head.reason == "第二次",
        history(1).updatedBy == "alice",
        history.last.version == 0L
      )).provide(RuntimeOverrideStore.inMemory)
    },
    test("变更原因在持久化前截断，不会让审计行无界增长") {
      val longReason = "原" * (RuntimeOverrideRecord.MaxReasonLength + 200)
      (for
        settings <- service()
        record   <- settings.update(0L, RuntimeOverrides(rerankEnabled = Some(true)), "ops", longReason)
      yield assertTrue(record.overrideReason.length == RuntimeOverrideRecord.MaxReasonLength))
        .provide(RuntimeOverrideStore.inMemory)
    },
    test("activeCount 只统计真正被设置的覆盖项") {
      val overrides = RuntimeOverrides(retrievalTopK = Some(9), rerankEnabled = Some(false))
      assertTrue(RuntimeOverrides.none.activeCount == 0, overrides.activeCount == 2)
    },
    test("审批策略的 wire 取值与领域枚举双向映射，未知取值 fail-closed") {
      assertTrue(
        ApprovalPolicy.values.forall(policy =>
          RuntimeOverrides.parseApprovalPolicy(RuntimeOverrides.renderApprovalPolicy(policy)).contains(policy)
        ),
        RuntimeOverrides.parseApprovalPolicy(" ALWAYS ").contains(ApprovalPolicy.Always),
        RuntimeOverrides.parseApprovalPolicy("maybe").isLeft
      )
    }
  )
