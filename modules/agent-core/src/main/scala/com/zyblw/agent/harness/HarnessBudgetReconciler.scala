package com.zyblw.agent.harness

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.RunStore
import zio.*

/** 一次有界对账报告。失败只暴露稳定 RunId，不携带数据库、模型或用户正文。 */
final case class HarnessBudgetReconciliationReport(
    scanned: Int,
    settled: Int,
    pending: Int,
    failedRunIds: Chunk[RunId],
    nextCursor: Option[GoalBudgetReservationCursor],
    wrapped: Boolean
):
  require(scanned >= 0 && settled >= 0 && pending >= 0, "预算对账计数不能为负数")
  require(settled + pending + failedRunIds.length == scanned, "预算对账分类必须覆盖全部扫描记录")

/** 修复“Run 终态已提交、预算仍为 Reserved”的崩溃窗口。
  *
  * 服务不执行或恢复 Agent，不猜测释放非终态 Run。每次只读取一页并保存进程内游标；到末尾后从头循环，因此大量长运行不会永久遮挡后续终态。 多实例可以同时运行，`settleGoalBudget`
  * 的行锁与幂等语义负责去重。
  */
final class HarnessBudgetReconciler private (
    harness: HarnessStore,
    runs: RunStore,
    cursor: Ref.Synchronized[Option[GoalBudgetReservationCursor]]
):
  def reconcileNext(limit: Int = 64): IO[StoreError, HarnessBudgetReconciliationReport] =
    cursor.modifyZIO { current =>
      page(current, limit).flatMap { case (reservations, wrapped) =>
        ZIO.foreach(reservations)(reconcileOne).map { outcomes =>
          val next   = reservations.lastOption.map(GoalBudgetReservationCursor.from)
          val report = HarnessBudgetReconciliationReport(
            scanned = reservations.length,
            settled = outcomes.count(_ == ReconcileOutcome.Settled),
            pending = outcomes.count(_ == ReconcileOutcome.Pending),
            failedRunIds = reservations.zip(outcomes).collect { case (reservation, ReconcileOutcome.Failed) =>
              reservation.runId
            },
            nextCursor = next,
            wrapped = wrapped
          )
          report -> next
        }
      }
    }

  private def page(
      after: Option[GoalBudgetReservationCursor],
      limit: Int
  ): IO[StoreError, (Chunk[GoalBudgetReservation], Boolean)] =
    harness
      .listGoalBudgetReservations(GoalBudgetReservationStatus.Reserved, after, limit)
      .flatMap { first =>
        if first.nonEmpty || after.isEmpty then ZIO.succeed(first -> false)
        else
          harness
            .listGoalBudgetReservations(GoalBudgetReservationStatus.Reserved, None, limit)
            .map(_ -> true)
      }

  private def reconcileOne(reservation: GoalBudgetReservation): UIO[ReconcileOutcome] =
    runs.load(reservation.runId).either.flatMap {
      case Right(state) if RunStatus.isTerminal(state.status) =>
        harness
          .settleGoalBudget(reservation.goalId, reservation.runId, state.usage)
          .as(ReconcileOutcome.Settled)
          .catchAll(_ => ZIO.succeed(ReconcileOutcome.Failed))
      case Right(_) => ZIO.succeed(ReconcileOutcome.Pending)
      case Left(_)  => ZIO.succeed(ReconcileOutcome.Failed)
    }

  private enum ReconcileOutcome:
    case Settled, Pending, Failed

object HarnessBudgetReconciler:
  def make(harness: HarnessStore, runs: RunStore): UIO[HarnessBudgetReconciler] =
    Ref.Synchronized
      .make(Option.empty[GoalBudgetReservationCursor])
      .map(
        HarnessBudgetReconciler(harness, runs, _)
      )

  val layer: URLayer[HarnessStore & RunStore, HarnessBudgetReconciler] = ZLayer.fromZIO {
    for
      harness <- ZIO.service[HarnessStore]
      runs    <- ZIO.service[RunStore]
      service <- make(harness, runs)
    yield service
  }
