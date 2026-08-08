package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.ast.Json
import zio.test.*

object RunDirectorySpec extends ZIOSpecDefault:
  /** `RunId` 是 UUID，而目录按 runId 文本倒序打破同毫秒并列。这里用末位十六进制数字编码顺序， 使断言里的期望顺序可读且与真实的文本排序一致。 */
  private def runId(tag: Char): RunId = RunId(UUID.fromString(s"00000000-0000-0000-0000-00000000000$tag"))

  private def label(tag: Char): String = runId(tag).asString

  private val session = SessionId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"))

  private def state(
      tag: Char,
      updatedAtMilli: Long,
      status: RunStatus = RunStatus.Running,
      tenantId: Option[String] = Some("acme"),
      agentId: String = "support",
      pendingApproval: Option[ApprovalRequest] = None
  ): AgentState =
    val limits = RunLimits()
    AgentState(
      runId(tag),
      session,
      AgentId(agentId),
      status,
      Chunk.empty,
      Chunk.empty,
      UsageSummary(),
      BudgetState(limits, UsageSummary(), 0),
      pendingApproval,
      Instant.EPOCH,
      Instant.ofEpochMilli(updatedAtMilli),
      Version.initial,
      runContext = RunContext(tenantId = tenantId, userId = Some("u-1"))
    )

  private def approval(tag: Char): ApprovalRequest =
    ApprovalRequest(
      id = "approval-1",
      runId = runId(tag),
      toolCall = ToolCall("call-1", "delete_account", Json.Obj()),
      risk = ToolRisk.ApprovalWrite,
      reason = "需要人工确认",
      requestedAtEpochMilli = 0L
    )

  private def directory(states: AgentState*): RunDirectory =
    RunDirectory.fromSnapshots(ZIO.succeed(Chunk.fromIterable(states)))

  private def cursorOf(page: RunDirectoryPage): Task[RunDirectoryCursor] =
    ZIO
      .fromOption(page.nextCursor)
      .orElseFail(new RuntimeException("期望本页返回游标"))
      .flatMap(raw => ZIO.fromEither(RunDirectoryCursor.decode(raw)).mapError(new RuntimeException(_)))

  def spec = suite("RunDirectory.fromSnapshots")(
    test("按 (updatedAt DESC, runId DESC) 稳定排序") {
      val subject = directory(state('a', 100L), state('c', 300L), state('b', 200L))
      for page <- subject.list(RunDirectoryQuery())
      yield assertTrue(page.items.map(_.runId) == Chunk(label('c'), label('b'), label('a')))
    },
    test("同毫秒并列由 runId 倒序打破，排序保持确定") {
      val subject = directory(state('a', 100L), state('b', 100L), state('c', 100L))
      for page <- subject.list(RunDirectoryQuery())
      yield assertTrue(page.items.map(_.runId) == Chunk(label('c'), label('b'), label('a')))
    },
    test("keyset 游标翻页不重复也不跳过记录") {
      val subject = directory(
        state('a', 100L),
        state('b', 200L),
        state('c', 300L),
        state('d', 400L),
        state('e', 500L)
      )
      for
        first  <- subject.list(RunDirectoryQuery(limit = 2))
        c1     <- cursorOf(first)
        second <- subject.list(RunDirectoryQuery(limit = 2, cursor = Some(c1)))
        c2     <- cursorOf(second)
        third  <- subject.list(RunDirectoryQuery(limit = 2, cursor = Some(c2)))
        seen = first.items.map(_.runId) ++ second.items.map(_.runId) ++ third.items.map(_.runId)
      yield assertTrue(
        first.items.map(_.runId) == Chunk(label('e'), label('d')),
        second.items.map(_.runId) == Chunk(label('c'), label('b')),
        third.items.map(_.runId) == Chunk(label('a')),
        seen.distinct.length == 5,
        !third.hasMore,
        third.nextCursor.isEmpty
      )
    },
    test("同毫秒并列跨页时游标仍然严格前进") {
      val subject = directory(state('a', 100L), state('b', 100L), state('c', 100L))
      for
        first  <- subject.list(RunDirectoryQuery(limit = 2))
        cursor <- cursorOf(first)
        second <- subject.list(RunDirectoryQuery(limit = 2, cursor = Some(cursor)))
      yield assertTrue(
        first.items.map(_.runId) == Chunk(label('c'), label('b')),
        second.items.map(_.runId) == Chunk(label('a'))
      )
    },
    test("最后一页不返回游标，客户端据此停止翻页") {
      val subject = directory(state('a', 100L), state('b', 200L))
      for page <- subject.list(RunDirectoryQuery(limit = 5))
      yield assertTrue(page.nextCursor.isEmpty, !page.hasMore, page.items.length == 2)
    },
    test("单页条数收敛到硬上限，避免一次拉取大结果集") {
      assertTrue(
        RunDirectoryQuery(limit = 10_000).boundedLimit == RunDirectory.MaxLimit,
        RunDirectoryQuery(limit = 0).boundedLimit == 1,
        RunDirectoryQuery(limit = -5).boundedLimit == 1
      )
    },
    test("租户、Agent 与状态过滤可以叠加") {
      val subject = directory(
        state('a', 400L, tenantId = Some("acme"), agentId = "support"),
        state('b', 300L, tenantId = Some("other"), agentId = "support"),
        state('c', 200L, tenantId = Some("acme"), agentId = "billing"),
        state('d', 100L, tenantId = Some("acme"), agentId = "support", status = RunStatus.Failed)
      )
      for
        tenant   <- subject.list(RunDirectoryQuery(tenantId = Some("acme")))
        combined <- subject.list(
          RunDirectoryQuery(
            tenantId = Some("acme"),
            agentId = Some("support"),
            statuses = Set(RunStatus.Running)
          )
        )
      yield assertTrue(
        tenant.items.map(_.runId) == Chunk(label('a'), label('c'), label('d')),
        combined.items.map(_.runId) == Chunk(label('a'))
      )
    },
    test("空状态集合表示不过滤，而不是过滤掉全部") {
      val subject = directory(state('a', 100L), state('b', 200L, status = RunStatus.Failed))
      for page <- subject.list(RunDirectoryQuery(statuses = Set.empty))
      yield assertTrue(page.items.length == 2)
    },
    test("审批过滤只返回真正在等待人工审批的 Run") {
      val subject = directory(state('a', 200L), state('b', 100L, pendingApproval = Some(approval('b'))))
      for page <- subject.list(RunDirectoryQuery(awaitingApprovalOnly = true))
      yield assertTrue(
        page.items.map(_.runId) == Chunk(label('b')),
        page.items.head.pendingApprovalToolName.contains("delete_account"),
        page.items.head.pendingApprovalRisk.contains("ApprovalWrite")
      )
    },
    test("时间窗口过滤的两端都包含边界") {
      val subject = directory(state('a', 100L), state('b', 200L), state('c', 300L))
      for page <- subject.list(
          RunDirectoryQuery(updatedAfterEpochMilli = Some(100L), updatedBeforeEpochMilli = Some(200L))
        )
      yield assertTrue(page.items.map(_.runId) == Chunk(label('b'), label('a')))
    },
    test("列表项只含元数据，不携带消息正文、步骤记录或 Agent 定义") {
      val subject = directory(state('a', 100L))
      for page <- subject.list(RunDirectoryQuery())
      yield
        val item   = page.items.head
        val fields = item.productElementNames.toSet
        assertTrue(
          !fields.contains("messages"),
          !fields.contains("definition"),
          !fields.contains("metadata"),
          !fields.contains("contextSummary"),
          fields.contains("usage"),
          // steps 是计数而不是步骤记录；如果它变成 Chunk[AgentStep]，这个断言会失败。
          item.steps == 0
        )
    },
    test("审批视图只暴露工具名与风险等级，不暴露调用参数或审批理由") {
      val subject = directory(state('a', 100L, pendingApproval = Some(approval('a'))))
      for page <- subject.list(RunDirectoryQuery())
      yield
        val fields = page.items.head.productElementNames.toSet
        assertTrue(!fields.contains("pendingApprovalReason"), !fields.contains("pendingApprovalArguments"))
    },
    test("费用渲染为字符串，避免跨语言客户端的双精度尾数误差") {
      val subject = directory(state('a', 100L))
      for page <- subject.list(RunDirectoryQuery())
      yield assertTrue(page.items.head.usage.estimatedCost == BigDecimal(0).toString)
    },
    test("总览按状态聚合并单独统计待审批数") {
      val subject = directory(
        state('a', 400L, status = RunStatus.Running),
        state('b', 300L, status = RunStatus.Running, pendingApproval = Some(approval('b'))),
        state('c', 200L, status = RunStatus.Failed),
        state('d', 100L, status = RunStatus.Failed, tenantId = Some("other"))
      )
      for
        all    <- subject.overview(None)
        scoped <- subject.overview(Some("acme"))
      yield assertTrue(
        all.totalRuns == 4L,
        all.countsByStatus == Map("Running" -> 2L, "Failed" -> 2L),
        all.awaitingApproval == 1L,
        scoped.totalRuns == 3L,
        scoped.countsByStatus == Map("Running" -> 2L, "Failed" -> 1L)
      )
    },
    test("游标编码可往返，非法输入返回校验错误而不是抛异常") {
      val cursor = RunDirectoryCursor(1234L, label('a'))
      assertTrue(
        RunDirectoryCursor.decode(cursor.encoded).contains(cursor),
        RunDirectoryCursor.decode("not-a-cursor").isLeft,
        RunDirectoryCursor.decode("1234:").isLeft,
        RunDirectoryCursor.decode("abc:run-a").isLeft
      )
    },
    test("游标容许 runId 中出现冒号，不会在第二段被截断") {
      val cursor = RunDirectoryCursor(1234L, "run:with:colons")
      assertTrue(RunDirectoryCursor.decode(cursor.encoded).contains(cursor))
    },
    test("未接入耐久目录时空实现返回空页而不是报错") {
      (for
        subject  <- ZIO.service[RunDirectory]
        page     <- subject.list(RunDirectoryQuery())
        overview <- subject.overview(None)
      yield assertTrue(page.items.isEmpty, !page.hasMore, overview.totalRuns == 0L))
        .provide(RunDirectory.empty)
    }
  )
