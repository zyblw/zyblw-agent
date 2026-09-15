package com.zyblw.agent.admin

import com.zyblw.agent.composition.{CompositionComparisonView, LiveComposition}
import com.zyblw.agent.core.*
import com.zyblw.agent.harness.{Goal, GoalBudgetSnapshot, GoalId, HarnessStore, Plan, PlanId}
import com.zyblw.agent.memory.RunStore
import zio.*
import zio.json.*

/** Run 冻结组合与当前进程组合的对照视图。
  *
  * 只给出冻结侧不足以排查漂移：运维真正要回答的是"现在这台机器和当时差在哪"。视图本体复用
  * [[com.zyblw.agent.composition.CompositionComparisonView]]，管理面与事故包因此不会各自维护一份会漂移的投影。
  */
final case class AdminCompositionView(
    runId: String,
    composition: CompositionComparisonView
) derives JsonCodec

/** 低敏 ModelCall 账本行；不含 CanonicalModelRequest。 */
final case class AdminModelCallView(
    requestId: String,
    status: String,
    provider: String,
    model: String,
    capturePolicy: String,
    fingerprintPrefix: String,
    inputTokens: Long,
    outputTokens: Long,
    errorCategory: Option[String],
    requestedProfile: Option[String] = None,
    routePolicyVersion: Option[String] = None,
    routeDecisionCodes: List[String] = Nil,
    estimatedRouteCost: Option[String] = None,
    pricingFingerprintPrefix: Option[String] = None,
    explicitModelPinned: Option[Boolean] = None
) derives JsonCodec

/** 低敏审批主体；只暴露摘要前缀和声明等级。 */
final case class AdminApprovalSubjectView(
    approvalId: String,
    toolName: String,
    risk: String,
    environmentId: String,
    permissionFingerprintPrefix: String,
    subjectFingerprintPrefix: Option[String]
) derives JsonCodec

/** 一次挂起记录的低敏投影；审批只是其中一种等待。 */
final case class AdminSuspensionRecordView(
    kind: String,
    createdAtEpochMilli: Long,
    deadlineEpochMilli: Option[Long],
    expiryOutcome: String,
    approval: Option[AdminApprovalSubjectView]
) derives JsonCodec

/** Run 是否存在、以及当前挂起（若有）。`record = None` 表示 Run 在跑或已终态，不是 404。 */
final case class AdminSuspensionView(
    runId: String,
    record: Option[AdminSuspensionRecordView]
) derives JsonCodec

/** 低敏 Harness 投影；不含 Goal 正文、Skill 正文或 Artifact bytes。 */
final case class AdminHarnessView(
    goalId: String,
    status: String,
    objectivePrefix: String,
    runId: Option[String],
    revision: Long,
    remainingRuns: Option[Long],
    remainingModelCalls: Option[Long],
    remainingToolCalls: Option[Long],
    remainingTotalTokens: Option[Long],
    planId: Option[String],
    todoStatuses: List[String]
) derives JsonCodec

/** 从 HarnessStore 读取 Goal/Plan/预算的低敏检查投影。 */
trait HarnessInspectionAdmin:
  def goal(goalId: GoalId): IO[StoreError, Option[AdminHarnessView]]
  def plan(planId: PlanId): IO[StoreError, Option[AdminHarnessView]]

object HarnessInspectionAdmin:
  def fromStore(store: HarnessStore): HarnessInspectionAdmin =
    new HarnessInspectionAdmin:
      def goal(goalId: GoalId): IO[StoreError, Option[AdminHarnessView]] =
        for
          current <- store.getGoal(goalId)
          budget  <- store.getGoalBudget(goalId)
        yield current.map(toView(_, None, budget))

      def plan(planId: PlanId): IO[StoreError, Option[AdminHarnessView]] =
        store.getPlan(planId).flatMap {
          case None       => ZIO.succeed(None)
          case Some(plan) =>
            for
              current <- store.getGoal(plan.goalId)
              budget  <- store.getGoalBudget(plan.goalId)
            yield current.map(toView(_, Some(plan), budget))
        }

      private def toView(
          goal: Goal,
          plan: Option[Plan],
          budget: Option[GoalBudgetSnapshot]
      ): AdminHarnessView =
        AdminHarnessView(
          goalId = goal.id.asString,
          status = goal.status.toString,
          objectivePrefix = goal.objective.take(48),
          runId = goal.runId.map(_.asString),
          revision = goal.revision,
          remainingRuns = budget.map(_.remainingRuns),
          remainingModelCalls = budget.map(_.remainingModelCalls),
          remainingToolCalls = budget.map(_.remainingToolCalls),
          remainingTotalTokens = budget.map(_.remainingTotalTokens),
          planId = plan.map(_.id.asString),
          todoStatuses = plan.toList.flatMap(_.todos.map(_.status.toString).toList)
        )

/** 从 RunStore 读取管理面检查投影。 */
trait RunInspectionAdmin:
  def composition(runId: RunId): IO[StoreError, Option[AdminCompositionView]]
  def modelCalls(runId: RunId, limit: Int): IO[StoreError, Chunk[AdminModelCallView]]
  def approval(runId: RunId): IO[StoreError, Option[AdminApprovalSubjectView]]
  def suspension(runId: RunId): IO[StoreError, Option[AdminSuspensionView]]

object RunInspectionAdmin:
  /** @param live
    *   现场组合读出口。宿主没有装配时 `composition` 只返回冻结侧，`drift` 留空而不是伪造"兼容"。
    */
  def fromStore(store: RunStore, live: Option[LiveComposition] = None): RunInspectionAdmin =
    new RunInspectionAdmin:
      def composition(runId: RunId): IO[StoreError, Option[AdminCompositionView]] =
        loadState(store, runId).map(_.map { state =>
          AdminCompositionView(
            runId.asString,
            CompositionComparisonView.of(state.composition, live.map(_.freeze(state.definition)))
          )
        })

      def modelCalls(runId: RunId, limit: Int): IO[StoreError, Chunk[AdminModelCallView]] =
        store.getModelCalls(runId).map { records =>
          Chunk.fromIterable(
            records
              .sortBy(_.updatedAtEpochMilli)
              .takeRight(math.max(limit, 0))
              .map { record =>
                val route = record.routeDecision
                AdminModelCallView(
                  record.requestId.asString,
                  record.status.toString,
                  record.provider,
                  record.model,
                  record.capturePolicy.toString,
                  record.fingerprint.take(16),
                  record.usage.map(_.inputTokens).getOrElse(0L),
                  record.usage.map(_.outputTokens).getOrElse(0L),
                  record.errorCategory,
                  requestedProfile = route.map(_.requirement.profile.toString),
                  routePolicyVersion = route.map(_.policyVersion),
                  routeDecisionCodes = route.fold(List.empty[String])(_.decisionCodes.toList),
                  estimatedRouteCost = route.flatMap(_.estimatedCost).map(_.toString),
                  pricingFingerprintPrefix = route.map(_.pricingFingerprint.take(16)),
                  explicitModelPinned = route.map(_.explicitModelPinned)
                )
              }
          )
        }

      def approval(runId: RunId): IO[StoreError, Option[AdminApprovalSubjectView]] =
        suspension(runId).map(_.flatMap(_.record.flatMap(_.approval)))

      def suspension(runId: RunId): IO[StoreError, Option[AdminSuspensionView]] =
        loadState(store, runId).map(_.map { state =>
          AdminSuspensionView(runId.asString, state.suspension.map(toSuspensionRecord))
        })

      private def loadState(store: RunStore, runId: RunId): IO[StoreError, Option[AgentState]] =
        store.load(runId).asSome.catchSome { case _: AgentError.RunNotFound =>
          ZIO.succeed(None)
        }

  private def toApproval(request: ApprovalRequest): AdminApprovalSubjectView =
    AdminApprovalSubjectView(
      request.id,
      request.toolCall.name,
      request.risk.toString,
      request.subject.map(_.environment.value).getOrElse("unknown"),
      request.subject.map(_.permissions.fingerprint.take(16)).getOrElse(""),
      request.subject.map(_.value.take(16))
    )

  private def toSuspensionRecord(record: SuspensionRecord): AdminSuspensionRecordView =
    AdminSuspensionRecordView(
      kind = record.kind.kind,
      createdAtEpochMilli = record.createdAt.toEpochMilli,
      deadlineEpochMilli = record.deadline.map(_.toEpochMilli),
      expiryOutcome = record.expiryOutcome.toString,
      approval = record.kind.approvalRequest.map(toApproval)
    )
