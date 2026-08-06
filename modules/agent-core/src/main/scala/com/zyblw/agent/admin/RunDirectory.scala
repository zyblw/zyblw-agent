package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** Run 目录的排序游标。
  *
  * 管理台列表按 `(updatedAt DESC, runId DESC)` 稳定排序，因此翻页必须使用 keyset 游标而不是 OFFSET： Run 会在翻页过程中持续更新，OFFSET
  * 会让同一条记录重复出现或被跳过。游标本身不含租户、用户或正文， 可以安全地出现在 URL 中。
  *
  * 时间戳使用微秒而不是毫秒，理由见 [[CursorTime]]：游标精度低于排序列精度会让翻页静默丢行。
  *
  * @param updatedAtEpochMicro
  *   上一页最后一条记录的更新时间，精度与排序列一致
  * @param runId
  *   上一页最后一条记录的 Run ID，用于打破同一微秒的并列
  */
final case class RunDirectoryCursor(updatedAtEpochMicro: Long, runId: String) derives JsonCodec:
  /** 编码为可放入查询参数的不透明文本。 */
  def encoded: String = s"$updatedAtEpochMicro:$runId"

object RunDirectoryCursor:
  /** 解析客户端回传的游标；非法值返回安全校验错误而不是抛异常。 */
  def decode(value: String): Either[String, RunDirectoryCursor] =
    value.split(":", 2) match
      case Array(micros, runId) if runId.trim.nonEmpty =>
        micros.toLongOption.toRight(s"非法 Run 目录游标: $value").map(RunDirectoryCursor(_, runId.trim))
      case _ => Left(s"非法 Run 目录游标: $value")

/** Run 目录查询条件。
  *
  * 所有过滤维度都是低敏元数据；这里刻意没有“按用户输入全文搜索”，因为 Run 的输入正文属于业务数据， 让管理台在整个部署范围内全文检索提示词会把运维界面变成数据导出工具。
  *
  * @param tenantId
  *   限定租户；None 表示跨租户查看，调用方必须已经持有管理 scope
  * @param agentId
  *   限定 Agent
  * @param statuses
  *   限定状态集合；空集合表示不过滤
  * @param awaitingApprovalOnly
  *   只返回正在等待人工审批的 Run，管理台审批台使用
  * @param updatedAfterEpochMilli
  *   只返回该时间之后更新的 Run
  * @param updatedBeforeEpochMilli
  *   只返回该时间之前更新的 Run
  * @param cursor
  *   上一页返回的游标
  * @param limit
  *   单页条数，实现必须收敛到 [[RunDirectory.MaxLimit]]
  */
final case class RunDirectoryQuery(
    tenantId: Option[String] = None,
    agentId: Option[String] = None,
    statuses: Set[RunStatus] = Set.empty,
    awaitingApprovalOnly: Boolean = false,
    updatedAfterEpochMilli: Option[Long] = None,
    updatedBeforeEpochMilli: Option[Long] = None,
    cursor: Option[RunDirectoryCursor] = None,
    limit: Int = RunDirectory.DefaultLimit
):
  /** 返回收敛到合法范围的单页条数。 */
  def boundedLimit: Int = limit.max(1).min(RunDirectory.MaxLimit)

/** Run 目录列表项的低敏用量摘要。
  *
  * `estimatedCost` 使用字符串而不是 Double，与 `agent-zio-http` 的 `UsageView` 保持同一约定： 费用是 BigDecimal，序列化成 IEEE 754
  * 双精度会在跨语言客户端产生尾数误差。
  */
final case class RunDirectoryUsage(
    modelCalls: Int,
    toolCalls: Int,
    inputTokens: Long,
    outputTokens: Long,
    totalTokens: Long,
    cachedInputTokens: Long,
    reasoningOutputTokens: Long,
    estimatedCost: String
) derives JsonCodec

/** Run 目录列表项。
  *
  * 视图只包含元数据：没有用户输入、模型输出、工具参数或工具结果。管理台需要正文时应打开单个 Run 的 inspection 视图， 由那里的授权规则再判断一次。
  */
final case class RunSummaryView(
    runId: String,
    agentId: String,
    sessionId: String,
    threadId: Option[String],
    status: String,
    steps: Int,
    awaitingApproval: Boolean,
    pendingApprovalToolName: Option[String],
    pendingApprovalRisk: Option[String],
    tenantId: Option[String],
    userId: Option[String],
    usage: RunDirectoryUsage,
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long,
    stateVersion: Long,
    lastEventSequence: Long
) derives JsonCodec

object RunSummaryView:
  /** 内存与 PostgreSQL Adapter 共用的唯一投影，保证两种实现返回同一形状。 */
  def from(state: AgentState): RunSummaryView = RunSummaryView(
    runId = state.runId.asString,
    agentId = state.agentId.value,
    sessionId = state.sessionId.asString,
    threadId = state.threadId.map(_.value),
    status = state.status.toString,
    steps = state.budget.steps,
    awaitingApproval = state.pendingApproval.isDefined,
    pendingApprovalToolName = state.pendingApproval.map(_.toolCall.name),
    pendingApprovalRisk = state.pendingApproval.map(_.risk.toString),
    tenantId = state.runContext.tenantId,
    userId = state.runContext.userId,
    usage = RunDirectoryUsage(
      modelCalls = state.usage.modelCalls,
      toolCalls = state.usage.toolCalls,
      inputTokens = state.usage.inputTokens,
      outputTokens = state.usage.outputTokens,
      totalTokens = state.usage.totalTokens,
      cachedInputTokens = state.usage.cachedInputTokens,
      reasoningOutputTokens = state.usage.reasoningOutputTokens,
      estimatedCost = state.usage.estimatedCost.toString
    ),
    createdAtEpochMilli = state.createdAt.toEpochMilli,
    updatedAtEpochMilli = state.updatedAt.toEpochMilli,
    stateVersion = state.version.value,
    lastEventSequence = state.lastEventSequence
  )

/** 一页 Run 目录结果。 */
final case class RunDirectoryPage(
    items: Chunk[RunSummaryView],
    nextCursor: Option[String],
    hasMore: Boolean
) derives JsonCodec

/** 按状态维度聚合的部署总览，用于管理台首屏卡片。 */
final case class RunDirectoryOverview(
    capturedAtEpochMilli: Long,
    totalRuns: Long,
    countsByStatus: Map[String, Long],
    awaitingApproval: Long
) derives JsonCodec

/** 跨 Run 的管理面查询 SPI。
  *
  * 它与 `RunStore` 分开，因为职责不同：`RunStore` 是运行时的权威读写路径，只按 `runId` 定位；目录是管理台的只读投影， 需要扫描、过滤和分页。把扫描能力塞进 `RunStore`
  * 会让每个 Adapter 都被迫实现一套只有管理台使用的查询，也会诱使运行时 代码用扫描代替按键读取。
  *
  * 实现必须保证：结果按 `(updatedAt DESC, runId DESC)` 稳定排序，且 `limit` 被收敛到 [[RunDirectory.MaxLimit]]。
  */
trait RunDirectory:
  /** 按条件分页查询 Run 元数据。 */
  def list(query: RunDirectoryQuery): IO[StoreError, RunDirectoryPage]

  /** 读取按状态聚合的部署总览。 */
  def overview(tenantId: Option[String]): IO[StoreError, RunDirectoryOverview]

object RunDirectory:
  /** 未指定时的单页条数。 */
  val DefaultLimit: Int = 50

  /** 单页硬上限；管理台翻页优于一次拉取大结果集。 */
  val MaxLimit: Int = 200

  /** 尚未接入耐久目录时的显式空实现。
    *
    * 返回空页而不是抛错，是为了让只用内存 Store 的教程和单元测试也能挂载管理路由； 生产部署应使用 PostgreSQL Adapter，否则管理台列表会永远为空。
    */
  val empty: ULayer[RunDirectory] = ZLayer.succeed(new RunDirectory:
    def list(query: RunDirectoryQuery): IO[StoreError, RunDirectoryPage] =
      ZIO.succeed(RunDirectoryPage(Chunk.empty, None, hasMore = false))

    def overview(tenantId: Option[String]): IO[StoreError, RunDirectoryOverview] =
      Clock.instant.map(now => RunDirectoryOverview(now.toEpochMilli, 0L, Map.empty, 0L)))

  /** 从任意状态快照来源构造目录，过滤、排序与分页在内存中完成。
    *
    * 它适合测试、单进程开发和状态数量有限的嵌入式部署。数据量超出单机内存时必须改用把过滤下推到数据库的 Adapter， 否则每次翻页都会加载全部 Run。
    *
    * @param snapshots
    *   返回当前全部 Run 状态的读取效果
    */
  def fromSnapshots(snapshots: IO[StoreError, Chunk[AgentState]]): RunDirectory = new RunDirectory:
    def list(query: RunDirectoryQuery): IO[StoreError, RunDirectoryPage] =
      snapshots.map { states =>
        // 排序与游标都取自同一个微秒时间戳，而不是视图里的展示用毫秒字段；两者精度必须一致。
        val ordered = states
          .map(state => RunSummaryView.from(state) -> CursorTime.epochMicro(state.updatedAt))
          .filter((view, _) => matches(query)(view))
          .sorted(using descending)
        val page    = query.cursor.fold(ordered)(cursor => ordered.filter(after(cursor)))
        val window  = page.take(query.boundedLimit)
        val hasMore = page.length > window.length
        RunDirectoryPage(
          window.map(_._1),
          window.lastOption
            .filter(_ => hasMore)
            .map((view, micro) => RunDirectoryCursor(micro, view.runId).encoded),
          hasMore
        )
      }

    def overview(tenantId: Option[String]): IO[StoreError, RunDirectoryOverview] =
      for
        now    <- Clock.instant
        states <- snapshots
        views = states.map(RunSummaryView.from).filter(view => tenantId.forall(view.tenantId.contains))
      yield RunDirectoryOverview(
        capturedAtEpochMilli = now.toEpochMilli,
        totalRuns = views.length.toLong,
        countsByStatus = views.groupBy(_.status).map((status, group) => status -> group.length.toLong),
        awaitingApproval = views.count(_.awaitingApproval).toLong
      )

  /** ZIO 环境访问器：分页查询。 */
  def list(query: RunDirectoryQuery): ZIO[RunDirectory, StoreError, RunDirectoryPage] =
    ZIO.serviceWithZIO[RunDirectory](_.list(query))

  /** 与 SQL `ORDER BY updated_at DESC, run_id DESC` 等价的内存排序。 */
  private val descending: Ordering[(RunSummaryView, Long)] =
    Ordering.by[(RunSummaryView, Long), (Long, String)]((view, micro) => (micro, view.runId)).reverse

  /** keyset 游标判定：严格位于游标之后。 */
  private def after(cursor: RunDirectoryCursor)(entry: (RunSummaryView, Long)): Boolean =
    val (view, micro) = entry
    micro < cursor.updatedAtEpochMicro ||
    (micro == cursor.updatedAtEpochMicro && view.runId < cursor.runId)

  /** 与 PostgreSQL Adapter 语义一致的内存过滤。 */
  private def matches(query: RunDirectoryQuery)(view: RunSummaryView): Boolean =
    query.tenantId.forall(view.tenantId.contains) &&
      query.agentId.forall(_ == view.agentId) &&
      (query.statuses.isEmpty || query.statuses.map(_.toString).contains(view.status)) &&
      (!query.awaitingApprovalOnly || view.awaitingApproval) &&
      query.updatedAfterEpochMilli.forall(view.updatedAtEpochMilli >= _) &&
      query.updatedBeforeEpochMilli.forall(view.updatedAtEpochMilli <= _)
