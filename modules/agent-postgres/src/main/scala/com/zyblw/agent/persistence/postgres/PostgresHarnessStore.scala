package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.artifacts.ArtifactReference
import com.zyblw.agent.core.*
import com.zyblw.agent.harness.*
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Types}
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import javax.sql.DataSource
import zio.*
import zio.json.*
import zio.json.ast.Json

/** PostgreSQL HarnessStore。CAS 语义与内存实现相同；事务内不调用模型。 */
final class PostgresHarnessStore(dataSource: DataSource) extends HarnessStore:
  private val budgetColumns =
    """goal_id, max_runs, max_model_calls, max_tool_calls, max_input_tokens,
      |max_output_tokens, max_total_tokens, max_estimated_cost,
      |reserved_runs, reserved_model_calls, reserved_tool_calls, reserved_input_tokens,
      |reserved_output_tokens, reserved_total_tokens, reserved_estimated_cost,
      |consumed_runs, consumed_model_calls, consumed_tool_calls, consumed_input_tokens,
      |consumed_output_tokens, consumed_total_tokens, consumed_estimated_cost, updated_at""".stripMargin

  def getGoal(id: GoalId): IO[StoreError, Option[Goal]] =
    withConnection { connection =>
      jdbc("get harness goal") {
        val statement =
          connection.prepareStatement(
            """SELECT goal_id, thread_id, objective, status, run_id, revision, updated_at,
              |       artifacts_json::text
              |FROM harness_goals WHERE goal_id = ?::uuid""".stripMargin
          )
        try
          statement.setObject(1, UUID.fromString(id.asString))
          val result = statement.executeQuery()
          try if result.next() then Some(readGoal(result)) else None
          finally result.close()
        finally statement.close()
      }
    }

  def saveGoal(expectedRevision: Long, goal: Goal): IO[StoreError, Goal] =
    Clock.instant.flatMap { now =>
      withConnection { connection =>
        if expectedRevision == 0L then insertGoal(connection, goal, now)
        else updateGoal(connection, expectedRevision, goal, now)
      }
    }

  def getPlan(id: PlanId): IO[StoreError, Option[Plan]] =
    withConnection { connection =>
      jdbc("get harness plan") {
        val statement =
          connection.prepareStatement(
            """SELECT plan_id, goal_id, summary, todos_json::text, revision, updated_at,
              |       artifacts_json::text
              |FROM harness_plans WHERE plan_id = ?::uuid""".stripMargin
          )
        try
          statement.setObject(1, UUID.fromString(id.asString))
          val result = statement.executeQuery()
          try if result.next() then Some(readPlan(result)) else None
          finally result.close()
        finally statement.close()
      }
    }

  def savePlan(expectedRevision: Long, plan: Plan): IO[StoreError, Plan] =
    Clock.instant.flatMap { now =>
      withConnection { connection =>
        getGoalExists(connection, plan.goalId).flatMap {
          case false => ZIO.fail(AgentError.HarnessNotFound("goal", plan.goalId.asString))
          case true  =>
            if expectedRevision == 0L then insertPlan(connection, plan, now)
            else updatePlan(connection, expectedRevision, plan, now)
        }
      }
    }

  def getSkill(id: String, version: String): IO[StoreError, Option[SkillDescriptor]] =
    withConnection(connection => loadSkill(connection, id, version))

  def saveSkill(skill: SkillDescriptor): IO[StoreError, SkillDescriptor] =
    withConnection { connection =>
      insertSkill(connection, skill).flatMap {
        case true  => ZIO.succeed(skill)
        case false =>
          loadSkill(connection, skill.id, skill.version).flatMap {
            case Some(existing) if existing.fingerprint == skill.fingerprint => ZIO.succeed(existing)
            case Some(_) => ZIO.fail(AgentError.SkillFingerprintConflict(skill.id, skill.version))
            case None    => ZIO.fail(AgentError.HarnessNotFound("skill", skill.sourceId))
          }
      }
    }

  def appendInteraction(input: InteractionInput): IO[StoreError, InteractionInput] =
    Clock.instant.flatMap { now =>
      withTransaction { connection =>
        lockGoal(connection, input.goalId).flatMap {
          case false => ZIO.fail(AgentError.HarnessNotFound("goal", input.goalId.asString))
          case true  =>
            nextInteractionSequence(connection, input.goalId).flatMap { sequence =>
              insertInteraction(connection, input, sequence, now)
            }
        }
      }
    }

  def listInteractions(
      goalId: GoalId,
      beforeSequence: Option[Long],
      limit: Int
  ): IO[StoreError, Chunk[InteractionInput]] =
    HarnessStore.validateInteractionPage(limit) *> withConnection { connection =>
      jdbc("list harness interactions") {
        val sql = beforeSequence match
          case Some(_) =>
            """SELECT interaction_id, goal_id, run_id, kind, body, sequence, created_at
              |FROM harness_interactions
              |WHERE goal_id = ?::uuid AND sequence < ?
              |ORDER BY sequence DESC LIMIT ?""".stripMargin
          case None =>
            """SELECT interaction_id, goal_id, run_id, kind, body, sequence, created_at
              |FROM harness_interactions
              |WHERE goal_id = ?::uuid
              |ORDER BY sequence DESC LIMIT ?""".stripMargin
        val statement = connection.prepareStatement(sql)
        try
          statement.setObject(1, UUID.fromString(goalId.asString))
          beforeSequence match
            case Some(sequence) =>
              statement.setLong(2, sequence)
              statement.setInt(3, limit)
            case None => statement.setInt(2, limit)
          val result = statement.executeQuery()
          try
            val buffer = scala.collection.mutable.ArrayBuffer.empty[InteractionInput]
            while result.next() do buffer += readInteraction(result)
            Chunk.fromIterable(buffer.reverse)
          finally result.close()
        finally statement.close()
      }
    }

  def configureGoalBudget(
      goalId: GoalId,
      policy: GoalBudgetPolicy
  ): IO[StoreError, GoalBudgetSnapshot] =
    Clock.instant.flatMap { now =>
      withTransaction { connection =>
        lockGoal(connection, goalId).flatMap {
          case false => ZIO.fail(AgentError.HarnessNotFound("goal", goalId.asString))
          case true  =>
            insertGoalBudget(connection, goalId, policy, now) *>
              loadGoalBudget(connection, goalId, forUpdate = true).flatMap {
                case Some(existing) if existing.policy == policy => ZIO.succeed(existing)
                case Some(_) => ZIO.fail(AgentError.HarnessBudgetPolicyConflict(goalId.asString))
                case None    => ZIO.fail(AgentError.HarnessBudgetNotConfigured(goalId.asString))
              }
        }
      }
    }

  def getGoalBudget(goalId: GoalId): IO[StoreError, Option[GoalBudgetSnapshot]] =
    withConnection(loadGoalBudget(_, goalId, forUpdate = false))

  def getGoalBudgetReservation(
      goalId: GoalId,
      runId: RunId
  ): IO[StoreError, Option[GoalBudgetReservation]] =
    withConnection(loadBudgetReservation(_, runId, forUpdate = false).map(_.filter(_.goalId == goalId)))

  def reserveGoalBudget(
      goalId: GoalId,
      runId: RunId,
      limits: RunLimits
  ): IO[StoreError, GoalBudgetReservation] =
    Clock.instant.flatMap { now =>
      withTransaction(reserveGoalBudgetInTransaction(_, goalId, runId, limits, now))
    }

  /** 供异步 Start Adapter 在其既有事务中复用；不能在此提交或取得第二条连接。 */
  private[postgres] def reserveGoalBudgetInTransaction(
      connection: Connection,
      goalId: GoalId,
      runId: RunId,
      limits: RunLimits,
      now: Instant
  ): IO[StoreError, GoalBudgetReservation] =
    requireLockedBudget(connection, goalId).flatMap { snapshot =>
      loadBudgetReservation(connection, runId, forUpdate = true).flatMap {
        case Some(existing) if existing.goalId == goalId && existing.limits == limits =>
          ZIO.succeed(existing)
        case Some(existing) => budgetReservationConflict(goalId, runId, existing.status)
        case None           =>
          ZIO.fromEither(snapshot.validateReservation(limits)).flatMap { amount =>
            val reservation = GoalBudgetReservation(
              goalId,
              runId,
              limits,
              GoalBudgetReservationStatus.Reserved,
              None,
              now.toEpochMilli,
              now.toEpochMilli
            )
            insertBudgetReservation(connection, reservation).flatMap {
              case true =>
                updateGoalBudget(connection, snapshot.reserve(amount, now.toEpochMilli), now)
                  .as(reservation)
              case false =>
                loadBudgetReservation(connection, runId, forUpdate = true).flatMap {
                  case Some(existing) if existing.goalId == goalId && existing.limits == limits =>
                    ZIO.succeed(existing)
                  case Some(existing) => budgetReservationConflict(goalId, runId, existing.status)
                  case None           =>
                    ZIO.fail(AgentError.PersistenceFailure("预算预留冲突后无法读取既有记录"))
                }
            }
          }
      }
    }

  /** 幂等 Start 冲突路径必须证明既有 Run 已绑定同一 Goal/limits，不能只比较 HTTP 请求正文。 */
  private[postgres] def verifyGoalBudgetAdmission(
      connection: Connection,
      goalId: GoalId,
      runId: RunId,
      limits: RunLimits
  ): IO[StoreError, Unit] =
    loadBudgetReservation(connection, runId, forUpdate = true).flatMap {
      case Some(existing) if existing.goalId == goalId && existing.limits == limits => ZIO.unit
      case Some(existing) => budgetReservationConflict(goalId, runId, existing.status)
      case None           =>
        ZIO.fail(
          AgentError.PersistenceFailure(s"Harness Run ${runId.asString} 缺少预算预留")
        )
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
        Clock.instant.flatMap { now =>
          withTransaction { connection =>
            requireLockedBudget(connection, goalId).flatMap { snapshot =>
              requireLockedReservation(connection, goalId, runId).flatMap { reservation =>
                reservation.status match
                  case GoalBudgetReservationStatus.Reserved =>
                    val status  = GoalBudgetReservation.settledStatus(reservation.limits, actual)
                    val settled = reservation.copy(
                      status = status,
                      usage = Some(usage),
                      updatedAtEpochMilli = now.toEpochMilli
                    )
                    updateGoalBudget(
                      connection,
                      snapshot
                        .settle(GoalBudgetAmount.reserved(reservation.limits), actual, now.toEpochMilli),
                      now
                    ) *> updateBudgetReservation(connection, settled, now).as(settled)
                  case GoalBudgetReservationStatus.Settled | GoalBudgetReservationStatus.Exceeded
                      if reservation.usage.contains(usage) =>
                    ZIO.succeed(reservation)
                  case status =>
                    ZIO.fail(
                      AgentError.HarnessBudgetConflict(
                        goalId.asString,
                        runId.asString,
                        status.toString,
                        "该预留不能用当前 usage 再次结算"
                      )
                    )
              }
            }
          }
        }
      }

  def releaseGoalBudget(goalId: GoalId, runId: RunId): IO[StoreError, GoalBudgetReservation] =
    Clock.instant.flatMap { now =>
      withTransaction { connection =>
        requireLockedBudget(connection, goalId).flatMap { snapshot =>
          requireLockedReservation(connection, goalId, runId).flatMap { reservation =>
            reservation.status match
              case GoalBudgetReservationStatus.Reserved =>
                val released = reservation.copy(
                  status = GoalBudgetReservationStatus.Released,
                  updatedAtEpochMilli = now.toEpochMilli
                )
                updateGoalBudget(
                  connection,
                  snapshot.release(GoalBudgetAmount.reserved(reservation.limits), now.toEpochMilli),
                  now
                ) *> updateBudgetReservation(connection, released, now).as(released)
              case GoalBudgetReservationStatus.Released => ZIO.succeed(reservation)
              case status                               =>
                ZIO.fail(
                  AgentError.HarnessBudgetConflict(
                    goalId.asString,
                    runId.asString,
                    status.toString,
                    "已结算预留不能释放"
                  )
                )
          }
        }
      }
    }

  def listGoalBudgetReservations(
      status: GoalBudgetReservationStatus,
      after: Option[GoalBudgetReservationCursor],
      limit: Int
  ): IO[StoreError, Chunk[GoalBudgetReservation]] =
    HarnessStore.validateBudgetReservationPage(limit) *> withConnection { connection =>
      jdbc("list harness budget reservations") {
        val cursorFilter = after.fold("")(_ => " AND (created_at, run_id) > (?, ?::uuid)")
        val statement    = connection.prepareStatement(
          s"""SELECT run_id, goal_id, limits_json::text, status, usage_json::text, created_at, updated_at
             |FROM harness_budget_reservations
             |WHERE status = ?$cursorFilter
             |ORDER BY created_at, run_id LIMIT ?""".stripMargin
        )
        try
          statement.setString(1, status.toString)
          after match
            case Some(cursor) =>
              setInstant(statement, 2, Instant.ofEpochMilli(cursor.createdAtEpochMilli))
              statement.setObject(3, UUID.fromString(cursor.runId.asString))
              statement.setInt(4, limit)
            case None => statement.setInt(2, limit)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[GoalBudgetReservation]()
          try
            while result.next() do builder += readBudgetReservation(result)
            builder.result()
          finally result.close()
        finally statement.close()
      }
    }

  private def insertGoal(connection: Connection, goal: Goal, now: Instant): IO[StoreError, Goal] =
    val written = goal.copy(revision = 1L, updatedAtEpochMilli = now.toEpochMilli)
    jdbc("insert harness goal") {
      val statement = connection.prepareStatement(
        """INSERT INTO harness_goals
          |(goal_id, thread_id, objective, status, run_id, revision, updated_at, artifacts_json)
          |VALUES (?::uuid, ?, ?, ?, ?::uuid, ?, ?, ?::jsonb)""".stripMargin
      )
      try
        bindGoal(statement, written, now)
        statement.executeUpdate()
        written
      finally statement.close()
    }.catchSome { case AgentError.DatabaseFailure(_, "23505", _, _) =>
      currentGoalRevision(connection, goal.id).flatMap { actual =>
        ZIO.fail(AgentError.HarnessRevisionConflict("goal", 0L, actual))
      }
    }

  private def insertGoalBudget(
      connection: Connection,
      goalId: GoalId,
      policy: GoalBudgetPolicy,
      now: Instant
  ): IO[StoreError, Unit] =
    jdbc("insert harness goal budget") {
      val statement = connection.prepareStatement(
        """INSERT INTO harness_goal_budgets
          |(goal_id, max_runs, max_model_calls, max_tool_calls, max_input_tokens,
          | max_output_tokens, max_total_tokens, max_estimated_cost, created_at, updated_at)
          |VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT (goal_id) DO NOTHING""".stripMargin
      )
      try
        statement.setObject(1, UUID.fromString(goalId.asString))
        statement.setLong(2, policy.maxRuns)
        statement.setLong(3, policy.maxModelCalls)
        statement.setLong(4, policy.maxToolCalls)
        statement.setLong(5, policy.maxInputTokens)
        statement.setLong(6, policy.maxOutputTokens)
        statement.setLong(7, policy.maxTotalTokens)
        policy.maxEstimatedCost.fold(statement.setNull(8, Types.NUMERIC))(value =>
          statement.setBigDecimal(8, value.bigDecimal)
        )
        setInstant(statement, 9, now)
        setInstant(statement, 10, now)
        statement.executeUpdate()
        ()
      finally statement.close()
    }

  private def loadGoalBudget(
      connection: Connection,
      goalId: GoalId,
      forUpdate: Boolean
  ): IO[StoreError, Option[GoalBudgetSnapshot]] =
    jdbc("get harness goal budget") {
      val suffix    = if forUpdate then " FOR UPDATE" else ""
      val statement = connection.prepareStatement(
        s"SELECT $budgetColumns FROM harness_goal_budgets WHERE goal_id = ?::uuid$suffix"
      )
      try
        statement.setObject(1, UUID.fromString(goalId.asString))
        val result = statement.executeQuery()
        try if result.next() then Some(readGoalBudget(result)) else None
        finally result.close()
      finally statement.close()
    }

  private def requireLockedBudget(
      connection: Connection,
      goalId: GoalId
  ): IO[StoreError, GoalBudgetSnapshot] =
    loadGoalBudget(connection, goalId, forUpdate = true).flatMap {
      case Some(snapshot) => ZIO.succeed(snapshot)
      case None           =>
        getGoalExists(connection, goalId).flatMap {
          case true  => ZIO.fail(AgentError.HarnessBudgetNotConfigured(goalId.asString))
          case false => ZIO.fail(AgentError.HarnessNotFound("goal", goalId.asString))
        }
    }

  private def updateGoalBudget(
      connection: Connection,
      snapshot: GoalBudgetSnapshot,
      now: Instant
  ): IO[StoreError, Unit] =
    jdbc("update harness goal budget") {
      val statement = connection.prepareStatement(
        """UPDATE harness_goal_budgets SET
          |reserved_runs = ?, reserved_model_calls = ?, reserved_tool_calls = ?,
          |reserved_input_tokens = ?, reserved_output_tokens = ?, reserved_total_tokens = ?,
          |reserved_estimated_cost = ?, consumed_runs = ?, consumed_model_calls = ?,
          |consumed_tool_calls = ?, consumed_input_tokens = ?, consumed_output_tokens = ?,
          |consumed_total_tokens = ?, consumed_estimated_cost = ?, updated_at = ?
          |WHERE goal_id = ?::uuid""".stripMargin
      )
      try
        bindBudgetAmount(statement, 1, snapshot.reserved)
        bindBudgetAmount(statement, 8, snapshot.consumed)
        setInstant(statement, 15, now)
        statement.setObject(16, UUID.fromString(snapshot.goalId.asString))
        if statement.executeUpdate() != 1 then throw IllegalStateException("更新 Harness Goal budget 时记录消失")
      finally statement.close()
    }

  private def insertBudgetReservation(
      connection: Connection,
      reservation: GoalBudgetReservation
  ): IO[StoreError, Boolean] =
    jdbc("insert harness budget reservation") {
      val statement = connection.prepareStatement(
        """INSERT INTO harness_budget_reservations
          |(run_id, goal_id, limits_json, status, usage_json, created_at, updated_at)
          |VALUES (?::uuid, ?::uuid, ?::jsonb, ?, NULL, ?, ?)
          |ON CONFLICT (run_id) DO NOTHING""".stripMargin
      )
      try
        statement.setObject(1, UUID.fromString(reservation.runId.asString))
        statement.setObject(2, UUID.fromString(reservation.goalId.asString))
        statement.setString(3, reservation.limits.toJson)
        statement.setString(4, reservation.status.toString)
        setInstant(statement, 5, Instant.ofEpochMilli(reservation.createdAtEpochMilli))
        setInstant(statement, 6, Instant.ofEpochMilli(reservation.updatedAtEpochMilli))
        statement.executeUpdate() == 1
      finally statement.close()
    }

  private def loadBudgetReservation(
      connection: Connection,
      runId: RunId,
      forUpdate: Boolean
  ): IO[StoreError, Option[GoalBudgetReservation]] =
    jdbc("get harness budget reservation") {
      val suffix    = if forUpdate then " FOR UPDATE" else ""
      val statement = connection.prepareStatement(
        s"""SELECT run_id, goal_id, limits_json::text, status, usage_json::text, created_at, updated_at
           |FROM harness_budget_reservations WHERE run_id = ?::uuid$suffix""".stripMargin
      )
      try
        statement.setObject(1, UUID.fromString(runId.asString))
        val result = statement.executeQuery()
        try if result.next() then Some(readBudgetReservation(result)) else None
        finally result.close()
      finally statement.close()
    }

  private def requireLockedReservation(
      connection: Connection,
      goalId: GoalId,
      runId: RunId
  ): IO[StoreError, GoalBudgetReservation] =
    loadBudgetReservation(connection, runId, forUpdate = true).flatMap {
      case Some(reservation) if reservation.goalId == goalId => ZIO.succeed(reservation)
      case Some(reservation) => budgetReservationConflict(goalId, runId, reservation.status)
      case None              => ZIO.fail(AgentError.HarnessNotFound("budget reservation", runId.asString))
    }

  private def updateBudgetReservation(
      connection: Connection,
      reservation: GoalBudgetReservation,
      now: Instant
  ): IO[StoreError, Unit] =
    jdbc("update harness budget reservation") {
      val statement = connection.prepareStatement(
        """UPDATE harness_budget_reservations
          |SET status = ?, usage_json = ?::jsonb, updated_at = ?
          |WHERE run_id = ?::uuid AND status = 'Reserved'""".stripMargin
      )
      try
        statement.setString(1, reservation.status.toString)
        reservation.usage.fold(statement.setNull(2, Types.VARCHAR))(value =>
          statement.setString(2, value.toJson)
        )
        setInstant(statement, 3, now)
        statement.setObject(4, UUID.fromString(reservation.runId.asString))
        if statement.executeUpdate() != 1 then
          throw IllegalStateException("更新 Harness budget reservation 时状态漂移")
      finally statement.close()
    }

  private def budgetReservationConflict[A](
      goalId: GoalId,
      runId: RunId,
      status: GoalBudgetReservationStatus
  ): IO[StoreError, A] =
    ZIO.fail(
      AgentError.HarnessBudgetConflict(
        goalId.asString,
        runId.asString,
        status.toString,
        "同一 RunId 已绑定不同 Goal 或 RunLimits"
      )
    )

  private def updateGoal(
      connection: Connection,
      expectedRevision: Long,
      goal: Goal,
      now: Instant
  ): IO[StoreError, Goal] =
    val written = goal.copy(revision = expectedRevision + 1L, updatedAtEpochMilli = now.toEpochMilli)
    jdbc("update harness goal") {
      val statement = connection.prepareStatement(
        """UPDATE harness_goals
          |SET thread_id = ?, objective = ?, status = ?, run_id = ?::uuid,
          |    revision = ?, updated_at = ?, artifacts_json = ?::jsonb
          |WHERE goal_id = ?::uuid AND revision = ?""".stripMargin
      )
      try
        statement.setString(1, written.threadId.value)
        statement.setString(2, written.objective)
        statement.setString(3, written.status.toString)
        written.runId.fold(statement.setNull(4, Types.OTHER))(id =>
          statement.setObject(4, UUID.fromString(id.asString))
        )
        statement.setLong(5, written.revision)
        setInstant(statement, 6, now)
        statement.setString(7, written.artifacts.toJson)
        statement.setObject(8, UUID.fromString(written.id.asString))
        statement.setLong(9, expectedRevision)
        statement.executeUpdate()
      finally statement.close()
    }.flatMap {
      case 1 => ZIO.succeed(written)
      case _ =>
        currentGoalRevision(connection, goal.id).flatMap { actual =>
          if actual == 0L then ZIO.fail(AgentError.HarnessNotFound("goal", goal.id.asString))
          else ZIO.fail(AgentError.HarnessRevisionConflict("goal", expectedRevision, actual))
        }
    }

  private def insertPlan(connection: Connection, plan: Plan, now: Instant): IO[StoreError, Plan] =
    val written = plan.copy(revision = 1L, updatedAtEpochMilli = now.toEpochMilli)
    jdbc("insert harness plan") {
      val statement = connection.prepareStatement(
        """INSERT INTO harness_plans
          |(plan_id, goal_id, summary, todos_json, revision, updated_at, artifacts_json)
          |VALUES (?::uuid, ?::uuid, ?, ?::jsonb, ?, ?, ?::jsonb)""".stripMargin
      )
      try
        statement.setObject(1, UUID.fromString(written.id.asString))
        statement.setObject(2, UUID.fromString(written.goalId.asString))
        statement.setString(3, written.summary)
        statement.setString(4, written.todos.toJson)
        statement.setLong(5, written.revision)
        setInstant(statement, 6, now)
        statement.setString(7, written.artifacts.toJson)
        statement.executeUpdate()
        written
      finally statement.close()
    }.catchSome {
      case AgentError.DatabaseFailure(_, "23503", _, _) =>
        ZIO.fail(AgentError.HarnessNotFound("goal", plan.goalId.asString))
      case AgentError.DatabaseFailure(_, "23505", _, _) =>
        currentPlanRevision(connection, plan.id).flatMap { actual =>
          ZIO.fail(AgentError.HarnessRevisionConflict("plan", 0L, actual))
        }
    }

  private def updatePlan(
      connection: Connection,
      expectedRevision: Long,
      plan: Plan,
      now: Instant
  ): IO[StoreError, Plan] =
    val written = plan.copy(revision = expectedRevision + 1L, updatedAtEpochMilli = now.toEpochMilli)
    jdbc("update harness plan") {
      val statement = connection.prepareStatement(
        """UPDATE harness_plans
          |SET goal_id = ?::uuid, summary = ?, todos_json = ?::jsonb, revision = ?,
          |    updated_at = ?, artifacts_json = ?::jsonb
          |WHERE plan_id = ?::uuid AND revision = ?""".stripMargin
      )
      try
        statement.setObject(1, UUID.fromString(written.goalId.asString))
        statement.setString(2, written.summary)
        statement.setString(3, written.todos.toJson)
        statement.setLong(4, written.revision)
        setInstant(statement, 5, now)
        statement.setString(6, written.artifacts.toJson)
        statement.setObject(7, UUID.fromString(written.id.asString))
        statement.setLong(8, expectedRevision)
        statement.executeUpdate()
      finally statement.close()
    }.flatMap {
      case 1 => ZIO.succeed(written)
      case _ =>
        currentPlanRevision(connection, plan.id).flatMap { actual =>
          if actual == 0L then ZIO.fail(AgentError.HarnessNotFound("plan", plan.id.asString))
          else ZIO.fail(AgentError.HarnessRevisionConflict("plan", expectedRevision, actual))
        }
    }

  private def insertSkill(connection: Connection, skill: SkillDescriptor): IO[StoreError, Boolean] =
    jdbc("insert harness skill") {
      val statement = connection.prepareStatement(
        """INSERT INTO harness_skills
          |(skill_id, skill_version, source, trust, body, fingerprint)
          |VALUES (?, ?, ?, ?, ?, ?)
          |ON CONFLICT (skill_id, skill_version) DO NOTHING""".stripMargin
      )
      try
        statement.setString(1, skill.id)
        statement.setString(2, skill.version)
        statement.setString(3, skill.source)
        statement.setString(4, skill.trust.toString)
        statement.setString(5, skill.body)
        statement.setString(6, skill.fingerprint)
        statement.executeUpdate() == 1
      finally statement.close()
    }

  private def loadSkill(
      connection: Connection,
      id: String,
      version: String
  ): IO[StoreError, Option[SkillDescriptor]] =
    jdbc("get harness skill") {
      val statement = connection.prepareStatement(
        """SELECT skill_id, skill_version, source, trust, body, fingerprint
          |FROM harness_skills WHERE skill_id = ? AND skill_version = ?""".stripMargin
      )
      try
        statement.setString(1, id)
        statement.setString(2, version)
        val result = statement.executeQuery()
        try if result.next() then Some(readSkill(result)) else None
        finally result.close()
      finally statement.close()
    }

  private def lockGoal(connection: Connection, id: GoalId): IO[StoreError, Boolean] =
    jdbc("lock harness goal") {
      val statement =
        connection.prepareStatement("SELECT 1 FROM harness_goals WHERE goal_id = ?::uuid FOR UPDATE")
      try
        statement.setObject(1, UUID.fromString(id.asString))
        val result = statement.executeQuery()
        try result.next()
        finally result.close()
      finally statement.close()
    }

  private def nextInteractionSequence(connection: Connection, goalId: GoalId): IO[StoreError, Long] =
    jdbc("next harness interaction sequence") {
      val statement = connection.prepareStatement(
        "SELECT COALESCE(MAX(sequence), 0) FROM harness_interactions WHERE goal_id = ?::uuid"
      )
      try
        statement.setObject(1, UUID.fromString(goalId.asString))
        val result = statement.executeQuery()
        try
          result.next()
          result.getLong(1) + 1L
        finally result.close()
      finally statement.close()
    }

  private def insertInteraction(
      connection: Connection,
      input: InteractionInput,
      sequence: Long,
      now: Instant
  ): IO[StoreError, InteractionInput] =
    val written = input.copy(sequence = sequence, createdAtEpochMilli = now.toEpochMilli)
    jdbc("insert harness interaction") {
      val statement = connection.prepareStatement(
        """INSERT INTO harness_interactions
          |(interaction_id, goal_id, run_id, kind, body, sequence, created_at)
          |VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)""".stripMargin
      )
      try
        statement.setObject(1, UUID.fromString(written.id.asString))
        statement.setObject(2, UUID.fromString(written.goalId.asString))
        written.runId.fold(statement.setNull(3, Types.OTHER))(id =>
          statement.setObject(3, UUID.fromString(id.asString))
        )
        statement.setString(4, written.kind.toString)
        statement.setString(5, written.body)
        statement.setLong(6, written.sequence)
        setInstant(statement, 7, now)
        statement.executeUpdate()
        written
      finally statement.close()
    }

  private def readInteraction(result: ResultSet): InteractionInput =
    val runId = Option(result.getObject("run_id", classOf[UUID])).map(RunId(_))
    InteractionInput(
      InteractionId(result.getObject("interaction_id", classOf[UUID])),
      GoalId(result.getObject("goal_id", classOf[UUID])),
      InteractionKind.valueOf(result.getString("kind")),
      result.getString("body"),
      runId,
      result.getLong("sequence"),
      result.getTimestamp("created_at").toInstant.toEpochMilli
    )

  private def getGoalExists(connection: Connection, id: GoalId): IO[StoreError, Boolean] =
    jdbc("exists harness goal") {
      val statement =
        connection.prepareStatement("SELECT 1 FROM harness_goals WHERE goal_id = ?::uuid")
      try
        statement.setObject(1, UUID.fromString(id.asString))
        val result = statement.executeQuery()
        try result.next()
        finally result.close()
      finally statement.close()
    }

  private def currentGoalRevision(connection: Connection, id: GoalId): IO[StoreError, Long] =
    jdbc("current harness goal revision") {
      val statement =
        connection.prepareStatement("SELECT revision FROM harness_goals WHERE goal_id = ?::uuid")
      try
        statement.setObject(1, UUID.fromString(id.asString))
        val result = statement.executeQuery()
        try if result.next() then result.getLong(1) else 0L
        finally result.close()
      finally statement.close()
    }

  private def currentPlanRevision(connection: Connection, id: PlanId): IO[StoreError, Long] =
    jdbc("current harness plan revision") {
      val statement =
        connection.prepareStatement("SELECT revision FROM harness_plans WHERE plan_id = ?::uuid")
      try
        statement.setObject(1, UUID.fromString(id.asString))
        val result = statement.executeQuery()
        try if result.next() then result.getLong(1) else 0L
        finally result.close()
      finally statement.close()
    }

  private def bindGoal(statement: PreparedStatement, goal: Goal, now: Instant): Unit =
    statement.setObject(1, UUID.fromString(goal.id.asString))
    statement.setString(2, goal.threadId.value)
    statement.setString(3, goal.objective)
    statement.setString(4, goal.status.toString)
    goal.runId.fold(statement.setNull(5, Types.OTHER))(id =>
      statement.setObject(5, UUID.fromString(id.asString))
    )
    statement.setLong(6, goal.revision)
    setInstant(statement, 7, now)
    statement.setString(8, goal.artifacts.toJson)

  private def readGoal(result: ResultSet): Goal =
    val runId = Option(result.getObject("run_id", classOf[UUID])).map(RunId(_))
    Goal(
      GoalId(result.getObject("goal_id", classOf[UUID])),
      ThreadId(result.getString("thread_id")),
      result.getString("objective"),
      GoalStatus.valueOf(result.getString("status")),
      runId,
      result.getLong("revision"),
      result.getTimestamp("updated_at").toInstant.toEpochMilli,
      decodeArtifacts(result.getString("artifacts_json"), "harness goal artifacts_json")
    )

  private def readPlan(result: ResultSet): Plan =
    val todos = result
      .getString("todos_json")
      .fromJson[Chunk[TodoItem]]
      .fold(error => throw IllegalStateException(s"harness plan todos_json 损坏: $error"), identity)
    Plan(
      PlanId(result.getObject("plan_id", classOf[UUID])),
      GoalId(result.getObject("goal_id", classOf[UUID])),
      result.getString("summary"),
      todos,
      result.getLong("revision"),
      result.getTimestamp("updated_at").toInstant.toEpochMilli,
      decodeArtifacts(result.getString("artifacts_json"), "harness plan artifacts_json")
    )

  private def decodeArtifacts(json: String, label: String): Chunk[ArtifactReference] =
    json
      .fromJson[Chunk[ArtifactReference]]
      .fold(error => throw IllegalStateException(s"$label 损坏: $error"), identity)

  private def readSkill(result: ResultSet): SkillDescriptor =
    SkillDescriptor(
      result.getString("skill_id"),
      result.getString("skill_version"),
      result.getString("source"),
      SkillTrust.valueOf(result.getString("trust")),
      result.getString("body"),
      result.getString("fingerprint")
    )

  private def readGoalBudget(result: ResultSet): GoalBudgetSnapshot =
    val goalId = GoalId(result.getObject("goal_id", classOf[UUID]))
    val policy = GoalBudgetPolicy(
      result.getLong("max_runs"),
      result.getLong("max_model_calls"),
      result.getLong("max_tool_calls"),
      result.getLong("max_input_tokens"),
      result.getLong("max_output_tokens"),
      result.getLong("max_total_tokens"),
      Option(result.getBigDecimal("max_estimated_cost")).map(BigDecimal(_))
    )
    GoalBudgetSnapshot(
      goalId,
      policy,
      readBudgetAmount(result, "reserved"),
      readBudgetAmount(result, "consumed"),
      result.getTimestamp("updated_at").toInstant.toEpochMilli
    )

  private def readBudgetAmount(result: ResultSet, prefix: String): GoalBudgetAmount =
    GoalBudgetAmount(
      result.getLong(s"${prefix}_runs"),
      result.getLong(s"${prefix}_model_calls"),
      result.getLong(s"${prefix}_tool_calls"),
      result.getLong(s"${prefix}_input_tokens"),
      result.getLong(s"${prefix}_output_tokens"),
      result.getLong(s"${prefix}_total_tokens"),
      BigDecimal(result.getBigDecimal(s"${prefix}_estimated_cost"))
    )

  private def readBudgetReservation(result: ResultSet): GoalBudgetReservation =
    val limits = decodeRequiredObject[RunLimits](
      result.getString("limits_json"),
      "harness budget limits_json",
      Set(
        "maxSteps",
        "maxModelCalls",
        "maxToolCalls",
        "maxRepeatedActions",
        "maxInputTokens",
        "maxOutputTokens",
        "maxTotalTokens",
        "maxEstimatedCost",
        "maxDuration"
      )
    )
    val usage = Option(result.getString("usage_json")).map(
      decodeRequiredObject[UsageSummary](
        _,
        "harness budget usage_json",
        Set(
          "modelCalls",
          "toolCalls",
          "inputTokens",
          "outputTokens",
          "cachedInputTokens",
          "reasoningOutputTokens",
          "estimatedCost"
        )
      )
    )
    GoalBudgetReservation(
      GoalId(result.getObject("goal_id", classOf[UUID])),
      RunId(result.getObject("run_id", classOf[UUID])),
      limits,
      GoalBudgetReservationStatus.valueOf(result.getString("status")),
      usage,
      result.getTimestamp("created_at").toInstant.toEpochMilli,
      result.getTimestamp("updated_at").toInstant.toEpochMilli
    )

  /** 带默认参数的 case class 会把 `{}` 解码成宽松默认值；耐久事实必须先证明所有当前字段真实存在。 */
  private def decodeRequiredObject[A: JsonDecoder](
      json: String,
      label: String,
      requiredFields: Set[String]
  ): A =
    val decoded = for
      ast    <- json.fromJson[Json]
      fields <- ast match
        case Json.Obj(values) => Right(values.map(_._1).toSet)
        case _                => Left("必须是 JSON object")
      missing = requiredFields -- fields
      _     <- Either.cond(missing.isEmpty, (), s"缺少字段: ${missing.toList.sorted.mkString(",")}")
      value <- json.fromJson[A]
    yield value
    decoded.fold(error => throw IllegalStateException(s"$label 损坏: $error"), identity)

  /** GoalBudgetAmount 的七列顺序固定，避免 reserved/consumed 两套绑定逐渐漂移。 */
  private def bindBudgetAmount(
      statement: PreparedStatement,
      start: Int,
      amount: GoalBudgetAmount
  ): Unit =
    statement.setLong(start, amount.runs)
    statement.setLong(start + 1, amount.modelCalls)
    statement.setLong(start + 2, amount.toolCalls)
    statement.setLong(start + 3, amount.inputTokens)
    statement.setLong(start + 4, amount.outputTokens)
    statement.setLong(start + 5, amount.totalTokens)
    statement.setBigDecimal(start + 6, amount.estimatedCost.bigDecimal)

  private def withConnection[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(
          ZIO
            .attemptBlocking(dataSource.getConnection)
            .mapError(error => AgentError.PersistenceFailure("获取 Harness 数据库连接失败", Some(error)))
        )(connection => ZIO.attemptBlocking(connection.close()).orDie)
        .flatMap(use)
    }

  private def withTransaction[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    withConnection { connection =>
      for
        previous <- jdbc("read auto commit")(connection.getAutoCommit)
        _        <- jdbc("begin harness transaction")(connection.setAutoCommit(false))
        result   <- ZIO
          .uninterruptibleMask { restore =>
            restore(use(connection)).exit.flatMap {
              case Exit.Success(value) => jdbc("commit harness transaction")(connection.commit()).as(value)
              case Exit.Failure(cause) =>
                jdbc("rollback harness transaction")(connection.rollback()).ignore *> ZIO.refailCause(cause)
            }
          }
          .ensuring(jdbc("restore auto commit")(connection.setAutoCommit(previous)).ignore)
      yield result
    }

  private def jdbc[A](operation: String)(effect: => A): IO[StoreError, A] =
    ZIO.attemptBlocking(effect).mapError(error => databaseError(operation, error))

  private def databaseError(operation: String, error: Throwable): StoreError = error match
    case sql: SQLException =>
      val state     = Option(sql.getSQLState).getOrElse("unknown")
      val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
      AgentError.DatabaseFailure(operation, state, retryable, Some(sql))
    case other => AgentError.PersistenceFailure(operation, Some(other))

  private def setInstant(statement: PreparedStatement, index: Int, value: Instant): Unit =
    statement.setObject(index, value.atOffset(ZoneOffset.UTC))

object PostgresHarnessStore:
  val layer: URLayer[DataSource, HarnessStore] =
    ZLayer.fromFunction(PostgresHarnessStore(_))
