package com.zyblw.agent.harness

import com.zyblw.agent.core.*
import zio.*

/** Goal / Plan / Skill / Interaction / Budget 的耐久任务状态。不是第二套 AgentRuntime，也不领取 Worker。 */
trait HarnessStore:
  def getGoal(id: GoalId): IO[StoreError, Option[Goal]]

  /** 以 `expectedRevision` 做 CAS。创建时传 0；成功后 revision 为 expected+1。 */
  def saveGoal(expectedRevision: Long, goal: Goal): IO[StoreError, Goal]

  def getPlan(id: PlanId): IO[StoreError, Option[Plan]]

  /** 计划必须引用已存在的 Goal。CAS 语义与 [[saveGoal]] 相同。 */
  def savePlan(expectedRevision: Long, plan: Plan): IO[StoreError, Plan]

  def getSkill(id: String, version: String): IO[StoreError, Option[SkillDescriptor]]

  /** 同一 `id@version` 指纹相同则幂等复用；指纹不同则冲突。Skill 正文不可原地改写。 */
  def saveSkill(skill: SkillDescriptor): IO[StoreError, SkillDescriptor]

  /** 向已有 Goal 追加 Steer/FollowUp/UserMessage。只增不改写，不是 RunCommand。 */
  def appendInteraction(input: InteractionInput): IO[StoreError, InteractionInput]

  /** 读取 sequence 严格小于游标的最近一页，并按 sequence 升序返回。`None` 表示从最新记录开始。 */
  def listInteractions(
      goalId: GoalId,
      beforeSequence: Option[Long] = None,
      limit: Int = 64
  ): IO[StoreError, Chunk[InteractionInput]]

  /** 为已有 Goal 配置不可变的任务级总预算；相同策略幂等，任何不同策略均拒绝覆盖。 */
  def configureGoalBudget(goalId: GoalId, policy: GoalBudgetPolicy): IO[StoreError, GoalBudgetSnapshot]

  /** 读取 Goal 的预算计数器快照；尚未配置时返回 None。 */
  def getGoalBudget(goalId: GoalId): IO[StoreError, Option[GoalBudgetSnapshot]]

  /** 读取某个稳定 RunId 的预算状态，用于崩溃恢复和人工对账。 */
  def getGoalBudgetReservation(
      goalId: GoalId,
      runId: RunId
  ): IO[StoreError, Option[GoalBudgetReservation]]

  /** 在 Run 创建前按完整 RunLimits 预留最坏额度；同一 RunId + limits 幂等。 */
  def reserveGoalBudget(
      goalId: GoalId,
      runId: RunId,
      limits: RunLimits
  ): IO[StoreError, GoalBudgetReservation]

  /** 以 Runtime 的完整 UsageSummary 结算。超过预留仍会记录事实，并返回 Exceeded 状态。 */
  def settleGoalBudget(
      goalId: GoalId,
      runId: RunId,
      usage: UsageSummary
  ): IO[StoreError, GoalBudgetReservation]

  /** 仅释放尚未结算的预留；崩溃不会依靠 TTL 自动释放。 */
  def releaseGoalBudget(goalId: GoalId, runId: RunId): IO[StoreError, GoalBudgetReservation]

  /** 按 createdAt/runId 排他游标读取指定状态，供有界恢复对账；不返回 Goal/Run 正文。 */
  def listGoalBudgetReservations(
      status: GoalBudgetReservationStatus,
      after: Option[GoalBudgetReservationCursor] = None,
      limit: Int = 64
  ): IO[StoreError, Chunk[GoalBudgetReservation]]

object HarnessStore:
  /** 进程内 Adapter，完整保留 CAS；进程退出即丢失。生产使用 `PostgresHarnessStore`。 */
  val inMemory: ULayer[HarnessStore] = ZLayer.fromZIO {
    for
      goals        <- Ref.Synchronized.make(Map.empty[GoalId, Goal])
      plans        <- Ref.Synchronized.make(Map.empty[PlanId, Plan])
      skills       <- Ref.Synchronized.make(Map.empty[String, SkillDescriptor])
      interactions <- Ref.Synchronized.make(Map.empty[GoalId, Chunk[InteractionInput]])
      budgets      <- Ref.Synchronized.make(InMemoryBudgets())
    yield new HarnessStore:
      def getGoal(id: GoalId): IO[StoreError, Option[Goal]] = goals.get.map(_.get(id))

      def saveGoal(expectedRevision: Long, goal: Goal): IO[StoreError, Goal] =
        now.flatMap { millis =>
          goals.modify { current =>
            current.get(goal.id) match
              case None if expectedRevision == 0L =>
                val written = goal.copy(revision = 1L, updatedAtEpochMilli = millis)
                Right(written) -> current.updated(goal.id, written)
              case None =>
                Left(AgentError.HarnessNotFound("goal", goal.id.asString)) -> current
              case Some(existing) if existing.revision != expectedRevision =>
                Left(
                  AgentError.HarnessRevisionConflict("goal", expectedRevision, existing.revision)
                ) -> current
              case Some(_) =>
                val written = goal.copy(revision = expectedRevision + 1L, updatedAtEpochMilli = millis)
                Right(written) -> current.updated(goal.id, written)
          }.absolve
        }

      def getPlan(id: PlanId): IO[StoreError, Option[Plan]] = plans.get.map(_.get(id))

      def savePlan(expectedRevision: Long, plan: Plan): IO[StoreError, Plan] =
        getGoal(plan.goalId).flatMap {
          case None    => ZIO.fail(AgentError.HarnessNotFound("goal", plan.goalId.asString))
          case Some(_) =>
            now.flatMap { millis =>
              plans.modify { current =>
                current.get(plan.id) match
                  case None if expectedRevision == 0L =>
                    val written = plan.copy(revision = 1L, updatedAtEpochMilli = millis)
                    Right(written) -> current.updated(plan.id, written)
                  case None =>
                    Left(AgentError.HarnessNotFound("plan", plan.id.asString)) -> current
                  case Some(existing) if existing.revision != expectedRevision =>
                    Left(
                      AgentError.HarnessRevisionConflict("plan", expectedRevision, existing.revision)
                    ) -> current
                  case Some(_) =>
                    val written = plan.copy(revision = expectedRevision + 1L, updatedAtEpochMilli = millis)
                    Right(written) -> current.updated(plan.id, written)
              }.absolve
            }
        }

      def getSkill(id: String, version: String): IO[StoreError, Option[SkillDescriptor]] =
        skills.get.map(_.get(s"$id@$version"))

      def saveSkill(skill: SkillDescriptor): IO[StoreError, SkillDescriptor] =
        skills.modify { current =>
          val key = skill.sourceId
          current.get(key) match
            case None =>
              Right(skill) -> current.updated(key, skill)
            case Some(existing) if existing.fingerprint == skill.fingerprint =>
              Right(existing) -> current
            case Some(_) =>
              Left(AgentError.SkillFingerprintConflict(skill.id, skill.version)) -> current
        }.absolve

      def appendInteraction(input: InteractionInput): IO[StoreError, InteractionInput] =
        getGoal(input.goalId).flatMap {
          case None    => ZIO.fail(AgentError.HarnessNotFound("goal", input.goalId.asString))
          case Some(_) =>
            now.flatMap { millis =>
              interactions.modify { current =>
                val existing = current.getOrElse(input.goalId, Chunk.empty)
                val written  = input.copy(sequence = existing.length + 1L, createdAtEpochMilli = millis)
                Right(written) -> current.updated(input.goalId, existing :+ written)
              }.absolve
            }
        }

      def listInteractions(
          goalId: GoalId,
          beforeSequence: Option[Long],
          limit: Int
      ): IO[StoreError, Chunk[InteractionInput]] =
        validateInteractionPage(limit) *>
          interactions.get.map(
            _.getOrElse(goalId, Chunk.empty)
              .filter(input => beforeSequence.forall(input.sequence < _))
              .takeRight(limit)
          )

      def configureGoalBudget(
          goalId: GoalId,
          policy: GoalBudgetPolicy
      ): IO[StoreError, GoalBudgetSnapshot] =
        getGoal(goalId).flatMap {
          case None    => ZIO.fail(AgentError.HarnessNotFound("goal", goalId.asString))
          case Some(_) =>
            now.flatMap { millis =>
              budgets.modify { current =>
                current.snapshots.get(goalId) match
                  case None =>
                    val snapshot = GoalBudgetSnapshot(goalId, policy, updatedAtEpochMilli = millis)
                    Right(snapshot) -> current.copy(
                      snapshots = current.snapshots.updated(goalId, snapshot)
                    )
                  case Some(existing) if existing.policy == policy => Right(existing) -> current
                  case Some(_) => Left(AgentError.HarnessBudgetPolicyConflict(goalId.asString)) -> current
              }.absolve
            }
        }

      def getGoalBudget(goalId: GoalId): IO[StoreError, Option[GoalBudgetSnapshot]] =
        budgets.get.map(_.snapshots.get(goalId))

      def getGoalBudgetReservation(
          goalId: GoalId,
          runId: RunId
      ): IO[StoreError, Option[GoalBudgetReservation]] =
        budgets.get.map(_.reservations.get(runId).filter(_.goalId == goalId))

      def reserveGoalBudget(
          goalId: GoalId,
          runId: RunId,
          limits: RunLimits
      ): IO[StoreError, GoalBudgetReservation] =
        getGoal(goalId).flatMap {
          case None    => ZIO.fail(AgentError.HarnessNotFound("goal", goalId.asString))
          case Some(_) =>
            now.flatMap { millis =>
              budgets.modify { current =>
                current.reservations.get(runId) match
                  case Some(existing) if existing.goalId == goalId && existing.limits == limits =>
                    Right(existing) -> current
                  case Some(existing) =>
                    Left(
                      AgentError.HarnessBudgetConflict(
                        goalId.asString,
                        runId.asString,
                        existing.status.toString,
                        "同一 RunId 已绑定不同 Goal 或 RunLimits"
                      )
                    ) -> current
                  case None =>
                    current.snapshots.get(goalId) match
                      case None => Left(AgentError.HarnessBudgetNotConfigured(goalId.asString)) -> current
                      case Some(snapshot) =>
                        snapshot.validateReservation(limits) match
                          case Left(error)   => Left(error) -> current
                          case Right(amount) =>
                            val reservation = GoalBudgetReservation(
                              goalId,
                              runId,
                              limits,
                              GoalBudgetReservationStatus.Reserved,
                              None,
                              millis,
                              millis
                            )
                            val updated = snapshot.reserve(amount, millis)
                            Right(reservation) -> current.copy(
                              snapshots = current.snapshots.updated(goalId, updated),
                              reservations = current.reservations.updated(runId, reservation)
                            )
              }.absolve
            }
        }

      def settleGoalBudget(
          goalId: GoalId,
          runId: RunId,
          usage: UsageSummary
      ): IO[StoreError, GoalBudgetReservation] =
        ZIO
          .fromEither(GoalBudgetAmount.consumed(usage))
          .mapError(reason => AgentError.HarnessBudgetUsageInvalid(goalId.asString, runId.asString, reason))
          .flatMap { actual =>
            now.flatMap { millis =>
              budgets.modify { current =>
                budgetAndReservation(current, goalId, runId) match
                  case Left(error)                    => Left(error) -> current
                  case Right((snapshot, reservation)) =>
                    reservation.status match
                      case GoalBudgetReservationStatus.Reserved =>
                        val allocation = GoalBudgetAmount.reserved(reservation.limits)
                        val status     = GoalBudgetReservation.settledStatus(reservation.limits, actual)
                        val settled    = reservation.copy(
                          status = status,
                          usage = Some(usage),
                          updatedAtEpochMilli = millis
                        )
                        Right(settled) -> current.copy(
                          snapshots = current.snapshots.updated(
                            goalId,
                            snapshot.settle(allocation, actual, millis)
                          ),
                          reservations = current.reservations.updated(runId, settled)
                        )
                      case GoalBudgetReservationStatus.Settled | GoalBudgetReservationStatus.Exceeded
                          if reservation.usage.contains(usage) =>
                        Right(reservation) -> current
                      case status =>
                        Left(
                          AgentError.HarnessBudgetConflict(
                            goalId.asString,
                            runId.asString,
                            status.toString,
                            "该预留不能用当前 usage 再次结算"
                          )
                        ) -> current
              }.absolve
            }
          }

      def releaseGoalBudget(goalId: GoalId, runId: RunId): IO[StoreError, GoalBudgetReservation] =
        now.flatMap { millis =>
          budgets.modify { current =>
            budgetAndReservation(current, goalId, runId) match
              case Left(error)                    => Left(error) -> current
              case Right((snapshot, reservation)) =>
                reservation.status match
                  case GoalBudgetReservationStatus.Reserved =>
                    val released = reservation.copy(
                      status = GoalBudgetReservationStatus.Released,
                      updatedAtEpochMilli = millis
                    )
                    Right(released) -> current.copy(
                      snapshots = current.snapshots.updated(
                        goalId,
                        snapshot.release(GoalBudgetAmount.reserved(reservation.limits), millis)
                      ),
                      reservations = current.reservations.updated(runId, released)
                    )
                  case GoalBudgetReservationStatus.Released => Right(reservation) -> current
                  case status                               =>
                    Left(
                      AgentError.HarnessBudgetConflict(
                        goalId.asString,
                        runId.asString,
                        status.toString,
                        "已结算预留不能释放"
                      )
                    ) -> current
          }.absolve
        }

      def listGoalBudgetReservations(
          status: GoalBudgetReservationStatus,
          after: Option[GoalBudgetReservationCursor],
          limit: Int
      ): IO[StoreError, Chunk[GoalBudgetReservation]] =
        validateBudgetReservationPage(limit) *>
          budgets.get.map { current =>
            Chunk.fromIterable(
              current.reservations.valuesIterator
                .filter(_.status == status)
                .filter { reservation =>
                  after.forall(cursor =>
                    reservation.createdAtEpochMilli > cursor.createdAtEpochMilli ||
                      (reservation.createdAtEpochMilli == cursor.createdAtEpochMilli &&
                        reservation.runId.asString > cursor.runId.asString)
                  )
                }
                .toList
                .sortBy(item => item.createdAtEpochMilli -> item.runId.asString)
                .take(limit)
            )
          }
  }

  final private case class InMemoryBudgets(
      snapshots: Map[GoalId, GoalBudgetSnapshot] = Map.empty,
      reservations: Map[RunId, GoalBudgetReservation] = Map.empty
  )

  private def budgetAndReservation(
      current: InMemoryBudgets,
      goalId: GoalId,
      runId: RunId
  ): Either[StoreError, (GoalBudgetSnapshot, GoalBudgetReservation)] =
    for
      snapshot <- current.snapshots
        .get(goalId)
        .toRight(AgentError.HarnessBudgetNotConfigured(goalId.asString))
      reservation <- current.reservations
        .get(runId)
        .filter(_.goalId == goalId)
        .toRight(AgentError.HarnessNotFound("budget reservation", runId.asString))
    yield snapshot -> reservation

  val MaxInteractionPageSize       = 512
  val MaxBudgetReservationPageSize = 512

  def validateInteractionPage(limit: Int): IO[StoreError, Unit] =
    if limit > 0 && limit <= MaxInteractionPageSize then ZIO.unit
    else
      ZIO.fail(
        AgentError.PersistenceFailure(s"Interaction 查询 limit 必须在 1 到 $MaxInteractionPageSize 之间")
      )

  def validateBudgetReservationPage(limit: Int): IO[StoreError, Unit] =
    if limit > 0 && limit <= MaxBudgetReservationPageSize then ZIO.unit
    else
      ZIO.fail(
        AgentError.PersistenceFailure(
          s"Budget reservation 查询 limit 必须在 1 到 $MaxBudgetReservationPageSize 之间"
        )
      )

  private def now: UIO[Long] = Clock.instant.map(_.toEpochMilli)
