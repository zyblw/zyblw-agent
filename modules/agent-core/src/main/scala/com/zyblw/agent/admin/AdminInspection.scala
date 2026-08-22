package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import com.zyblw.agent.harness.{Goal, GoalBudgetSnapshot, GoalId, HarnessStore, Plan, PlanId}
import com.zyblw.agent.memory.RunStore
import zio.*
import zio.json.*

/** 低敏组合指纹投影；不含指令正文或模型 prompt。 */
final case class AdminCompositionView(
    runId: String,
    fingerprintPrefix: String,
    profileId: String,
    modelRefPrefix: String,
    capturePolicy: String,
    sourceIds: List[String],
    environmentId: String,
    permissionFingerprintPrefix: String
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
    errorCategory: Option[String]
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

object RunInspectionAdmin:
  def fromStore(store: RunStore): RunInspectionAdmin = new RunInspectionAdmin:
    def composition(runId: RunId): IO[StoreError, Option[AdminCompositionView]] =
      loadState(store, runId).map(_.flatMap { state =>
        state.composition.map { fingerprint =>
          AdminCompositionView(
            runId.asString,
            fingerprint.value.take(16),
            fingerprint.profileId,
            fingerprint.modelRef.take(16),
            fingerprint.capturePolicy.toString,
            fingerprint.sourceIds.toList,
            fingerprint.executionEnvironmentId,
            fingerprint.permissionProfileFingerprint.take(16)
          )
        }
      })

    def modelCalls(runId: RunId, limit: Int): IO[StoreError, Chunk[AdminModelCallView]] =
      store.getModelCalls(runId).map { records =>
        Chunk.fromIterable(
          records
            .sortBy(_.updatedAtEpochMilli)
            .takeRight(math.max(limit, 0))
            .map { record =>
              AdminModelCallView(
                record.requestId.asString,
                record.status.toString,
                record.provider,
                record.model,
                record.capturePolicy.toString,
                record.fingerprint.take(16),
                record.usage.map(_.inputTokens).getOrElse(0L),
                record.usage.map(_.outputTokens).getOrElse(0L),
                record.errorCategory
              )
            }
        )
      }

    def approval(runId: RunId): IO[StoreError, Option[AdminApprovalSubjectView]] =
      loadState(store, runId).map(_.flatMap { state =>
        state.pendingApproval.map { request =>
          AdminApprovalSubjectView(
            request.id,
            request.toolCall.name,
            request.risk.toString,
            request.subject.map(_.environment.value).getOrElse("unknown"),
            request.subject.map(_.permissions.fingerprint.take(16)).getOrElse(""),
            request.subject.map(_.value.take(16))
          )
        }
      })

    private def loadState(store: RunStore, runId: RunId): IO[StoreError, Option[AgentState]] =
      store.load(runId).asSome.catchSome { case _: AgentError.RunNotFound =>
        ZIO.succeed(None)
      }
