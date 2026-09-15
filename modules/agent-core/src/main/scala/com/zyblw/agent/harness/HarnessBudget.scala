package com.zyblw.agent.harness

import com.zyblw.agent.core.*
import zio.json.*
import RunLimits.given

/** 一个 Goal 跨多个 Run 共用的硬预算。
  *
  * Run 内部仍由 [[RunLimits]] 在每次外部动作前执行硬限制；本策略只负责多个并发或重试 Run 之间的总额度。成本上限为 `None` 表示宿主尚未启用可信价格表，不能把它解释成零成本。
  */
final case class GoalBudgetPolicy(
    maxRuns: Long,
    maxModelCalls: Long,
    maxToolCalls: Long,
    maxInputTokens: Long,
    maxOutputTokens: Long,
    maxTotalTokens: Long,
    maxEstimatedCost: Option[BigDecimal] = None
) derives JsonCodec:
  require(maxRuns > 0L, "Goal budget maxRuns 必须为正数")
  require(maxModelCalls > 0L && maxToolCalls > 0L, "Goal budget 调用次数必须为正数")
  require(
    maxInputTokens > 0L && maxOutputTokens > 0L && maxTotalTokens > 0L,
    "Goal budget Token 上限必须为正数"
  )
  require(
    BigInt(maxTotalTokens) <= BigInt(maxInputTokens) + BigInt(maxOutputTokens),
    "Goal budget maxTotalTokens 不能大于输入与输出上限之和"
  )
  require(maxEstimatedCost.forall(_ > 0), "Goal budget 费用上限必须为正数")

/** 预算账本中的一组可加资源量。`totalTokens` 独立保存，因为预留值来自 Run 的总量硬上限，而不是输入/输出上限之和。 */
final case class GoalBudgetAmount(
    runs: Long = 0L,
    modelCalls: Long = 0L,
    toolCalls: Long = 0L,
    inputTokens: Long = 0L,
    outputTokens: Long = 0L,
    totalTokens: Long = 0L,
    estimatedCost: BigDecimal = BigDecimal(0)
) derives JsonCodec:
  require(
    List(runs, modelCalls, toolCalls, inputTokens, outputTokens, totalTokens).forall(_ >= 0L),
    "Goal budget 资源量不能为负数"
  )
  require(estimatedCost >= 0, "Goal budget 费用不能为负数")

  private[agent] def +(that: GoalBudgetAmount): GoalBudgetAmount =
    GoalBudgetAmount(
      Math.addExact(runs, that.runs),
      Math.addExact(modelCalls, that.modelCalls),
      Math.addExact(toolCalls, that.toolCalls),
      Math.addExact(inputTokens, that.inputTokens),
      Math.addExact(outputTokens, that.outputTokens),
      Math.addExact(totalTokens, that.totalTokens),
      estimatedCost + that.estimatedCost
    )

  private[agent] def -(that: GoalBudgetAmount): GoalBudgetAmount =
    GoalBudgetAmount(
      Math.subtractExact(runs, that.runs),
      Math.subtractExact(modelCalls, that.modelCalls),
      Math.subtractExact(toolCalls, that.toolCalls),
      Math.subtractExact(inputTokens, that.inputTokens),
      Math.subtractExact(outputTokens, that.outputTokens),
      Math.subtractExact(totalTokens, that.totalTokens),
      estimatedCost - that.estimatedCost
    )

object GoalBudgetAmount:
  val zero: GoalBudgetAmount = GoalBudgetAmount()

  /** Run 的完整硬限制就是预留上界；不能另造一份会与 Runtime 漂移的精简 RunBudget。 */
  def reserved(limits: RunLimits): GoalBudgetAmount =
    GoalBudgetAmount(
      runs = 1L,
      modelCalls = limits.maxModelCalls.toLong,
      toolCalls = limits.maxToolCalls.toLong,
      inputTokens = limits.maxInputTokens,
      outputTokens = limits.maxOutputTokens,
      totalTokens = limits.maxTotalTokens,
      estimatedCost = limits.maxEstimatedCost.getOrElse(BigDecimal(0))
    )

  /** 把 Runtime 事实转换为账本资源量；损坏或负数 usage 必须由 Adapter 映射为类型化失败。 */
  private[agent] def consumed(usage: UsageSummary): Either[String, GoalBudgetAmount] =
    val counts = List(
      "modelCalls"            -> BigInt(usage.modelCalls),
      "toolCalls"             -> BigInt(usage.toolCalls),
      "inputTokens"           -> BigInt(usage.inputTokens),
      "outputTokens"          -> BigInt(usage.outputTokens),
      "cachedInputTokens"     -> BigInt(usage.cachedInputTokens),
      "cacheWriteInputTokens" -> BigInt(usage.cacheWriteInputTokens),
      "reasoningOutputTokens" -> BigInt(usage.reasoningOutputTokens)
    )
    counts
      .collectFirst { case (name, value) if value < 0 => s"$name 不能为负数" }
      .orElse(
        Option.when(
          BigInt(usage.cachedInputTokens) + BigInt(usage.cacheWriteInputTokens) > BigInt(usage.inputTokens)
        )("cache read/write token 之和不能大于 inputTokens")
      )
      .orElse(
        Option.when(usage.reasoningOutputTokens > usage.outputTokens)(
          "reasoningOutputTokens 不能大于 outputTokens"
        )
      )
      .orElse(Option.when(usage.estimatedCost < 0)("estimatedCost 不能为负数")) match
      case Some(error) => Left(error)
      case None        =>
        val total = BigInt(usage.inputTokens) + BigInt(usage.outputTokens)
        if total > BigInt(Long.MaxValue) then Left("totalTokens 超出 Long 范围")
        else
          Right(
            GoalBudgetAmount(
              runs = 1L,
              modelCalls = usage.modelCalls.toLong,
              toolCalls = usage.toolCalls.toLong,
              inputTokens = usage.inputTokens,
              outputTokens = usage.outputTokens,
              totalTokens = total.longValue,
              estimatedCost = usage.estimatedCost
            )
          )

enum GoalBudgetReservationStatus derives JsonCodec:
  case Reserved, Settled, Released, Exceeded

/** Reserved 扫描的排他游标；createdAt + UUID 在内存与 PostgreSQL 中提供稳定全序。 */
final case class GoalBudgetReservationCursor(createdAtEpochMilli: Long, runId: RunId) derives JsonCodec:
  require(createdAtEpochMilli >= 0L, "预算预留游标时间不能为负数")

object GoalBudgetReservationCursor:
  def from(reservation: GoalBudgetReservation): GoalBudgetReservationCursor =
    GoalBudgetReservationCursor(reservation.createdAtEpochMilli, reservation.runId)

/** 一个稳定 RunId 的预算状态。崩溃不会自动释放 Reserved；宿主必须恢复该 Run，或在确认没有副作用后显式释放。 */
final case class GoalBudgetReservation(
    goalId: GoalId,
    runId: RunId,
    limits: RunLimits,
    status: GoalBudgetReservationStatus,
    usage: Option[UsageSummary],
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long
) derives JsonCodec:
  require(createdAtEpochMilli >= 0L && updatedAtEpochMilli >= createdAtEpochMilli, "预算预留时间非法")
  require(
    (status, usage) match
      case (GoalBudgetReservationStatus.Reserved | GoalBudgetReservationStatus.Released, None)   => true
      case (GoalBudgetReservationStatus.Settled | GoalBudgetReservationStatus.Exceeded, Some(_)) => true
      case _                                                                                     => false,
    "预算预留状态与 usage 不一致"
  )

/** 一个原子快照：`reserved` 是尚未结算的最坏额度，`consumed` 是已结算的真实 usage。 */
final case class GoalBudgetSnapshot(
    goalId: GoalId,
    policy: GoalBudgetPolicy,
    reserved: GoalBudgetAmount = GoalBudgetAmount.zero,
    consumed: GoalBudgetAmount = GoalBudgetAmount.zero,
    updatedAtEpochMilli: Long = 0L
) derives JsonCodec:
  require(updatedAtEpochMilli >= 0L, "Goal budget 更新时间不能为负数")

  def remainingRuns: Long        = remaining(policy.maxRuns, reserved.runs, consumed.runs)
  def remainingModelCalls: Long  = remaining(policy.maxModelCalls, reserved.modelCalls, consumed.modelCalls)
  def remainingToolCalls: Long   = remaining(policy.maxToolCalls, reserved.toolCalls, consumed.toolCalls)
  def remainingInputTokens: Long =
    remaining(policy.maxInputTokens, reserved.inputTokens, consumed.inputTokens)
  def remainingOutputTokens: Long =
    remaining(policy.maxOutputTokens, reserved.outputTokens, consumed.outputTokens)
  def remainingTotalTokens: Long =
    remaining(policy.maxTotalTokens, reserved.totalTokens, consumed.totalTokens)
  def remainingEstimatedCost: Option[BigDecimal] =
    policy.maxEstimatedCost.map(limit => (limit - reserved.estimatedCost - consumed.estimatedCost).max(0))

  /** 在锁住该 Goal 预算行后验证最坏额度；返回的 amount 必须与预留记录一起原子写入。 */
  private[agent] def validateReservation(limits: RunLimits): Either[StoreError, GoalBudgetAmount] =
    val requested  = GoalBudgetAmount.reserved(limits)
    val dimensions = List(
      ("runs", requested.runs.toString, remainingRuns.toString, requested.runs <= remainingRuns),
      (
        "modelCalls",
        requested.modelCalls.toString,
        remainingModelCalls.toString,
        requested.modelCalls <= remainingModelCalls
      ),
      (
        "toolCalls",
        requested.toolCalls.toString,
        remainingToolCalls.toString,
        requested.toolCalls <= remainingToolCalls
      ),
      (
        "inputTokens",
        requested.inputTokens.toString,
        remainingInputTokens.toString,
        requested.inputTokens <= remainingInputTokens
      ),
      (
        "outputTokens",
        requested.outputTokens.toString,
        remainingOutputTokens.toString,
        requested.outputTokens <= remainingOutputTokens
      ),
      (
        "totalTokens",
        requested.totalTokens.toString,
        remainingTotalTokens.toString,
        requested.totalTokens <= remainingTotalTokens
      )
    )
    dimensions
      .collectFirst { case (name, value, left, false) => exceeded(name, value, left) }
      .orElse {
        policy.maxEstimatedCost.flatMap { _ =>
          (limits.maxEstimatedCost, remainingEstimatedCost) match
            case (None, Some(left)) => Some(exceeded("estimatedCost", "unbounded", left.toString))
            case (Some(value), Some(left)) if value > left =>
              Some(exceeded("estimatedCost", value.toString, left.toString))
            case _ => None
        }
      }
      .toLeft(requested)

  private[agent] def reserve(amount: GoalBudgetAmount, now: Long): GoalBudgetSnapshot =
    copy(reserved = reserved + amount, updatedAtEpochMilli = now)

  private[agent] def settle(
      allocation: GoalBudgetAmount,
      actual: GoalBudgetAmount,
      now: Long
  ): GoalBudgetSnapshot =
    copy(reserved = reserved - allocation, consumed = consumed + actual, updatedAtEpochMilli = now)

  private[agent] def release(allocation: GoalBudgetAmount, now: Long): GoalBudgetSnapshot =
    copy(reserved = reserved - allocation, updatedAtEpochMilli = now)

  private def exceeded(dimension: String, requested: String, left: String): StoreError =
    AgentError.HarnessBudgetExceeded(goalId.asString, dimension, requested, left)

  private def remaining(limit: Long, held: Long, used: Long): Long =
    if held >= limit || used >= limit - held then 0L else limit - held - used

object GoalBudgetReservation:
  /** 真实 usage 超过 Run 预留时仍必须落账，并显式标成 Exceeded，不能回滚并隐藏事实。 */
  private[agent] def settledStatus(
      limits: RunLimits,
      actual: GoalBudgetAmount
  ): GoalBudgetReservationStatus =
    val allocation = GoalBudgetAmount.reserved(limits)
    val exceeded   =
      actual.modelCalls > allocation.modelCalls ||
        actual.toolCalls > allocation.toolCalls ||
        actual.inputTokens > allocation.inputTokens ||
        actual.outputTokens > allocation.outputTokens ||
        actual.totalTokens > allocation.totalTokens ||
        limits.maxEstimatedCost.exists(actual.estimatedCost > _)
    if exceeded then GoalBudgetReservationStatus.Exceeded else GoalBudgetReservationStatus.Settled
