package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL 耐久控制命令队列。
  *
  * `agent_run_commands` 保存不可变命令正文、幂等键和重试审计；`agent_run_dispatch` 为每个 Run 提供一个串行租约槽。 claim 使用
  * `FOR UPDATE ... SKIP LOCKED` 锁定 dispatcher 与候选命令，不同 Run 可以横向并行，同 Run 永远只有一个
  * current_command_id。任何事务中都不执行模型、工具或第三方 HTTP。
  *
  * @param dataSource
  *   宿主提供的有界连接池；生产环境必须配置 connection/statement timeout
  */
final class PostgresRunCommandStore(dataSource: DataSource) extends RunCommandStore:

  /** 幂等提交命令并唤醒对应 dispatcher。
    *
    * Cancel 是特殊控制命令：若该 Run 正被其他命令执行，本事务会把旧命令重新排队并清空旧租约。旧 worker 随后的 heartbeat 或 AgentState 提交会立即因 fencing
    * 失败，从而让高优先级 Cancel 尽快被下一次 claim 领取。
    */
  def submit(
      runId: RunId,
      payload: RunCommandPayload,
      idempotencyKey: String,
      priority: Int,
      availableAt: Instant
  ): IO[StoreError, RunCommandRecord] =
    validateIdempotencyKey(idempotencyKey) *> CommandId.random.flatMap { commandId =>
      withTransaction { connection =>
        ZIO
          .attemptBlocking {
            val nowRecord =
              insertCommand(connection, commandId, runId, payload, idempotencyKey, priority, availableAt)
            val (record, inserted) =
              nowRecord.getOrElse(loadByIdempotencyKey(connection, runId, idempotencyKey) -> false)
            if record.payload != payload then throw IdempotencyMismatch(runId, idempotencyKey)

            ensureDispatch(connection, runId)
            if inserted then
              payload match
                case RunCommandPayload.Cancel(_) => preemptForCancel(connection, runId)
                case _                           => wakeDispatch(connection, runId)
            record
          }
          .mapError {
            case IdempotencyMismatch(id, key) => AgentError.CommandIdempotencyConflict(id, key)
            case sql: java.sql.SQLException if sql.getSQLState == "23503" => AgentError.RunNotFound(runId)
            case error => databaseError("提交 Agent 控制命令失败", error)
          }
      }
    }

  /** 原子 claim：先回收过期 dispatcher、淘汰耗尽命令，再锁定一个 dispatcher 与一条候选命令。 排序固定为 priority DESC、availableAt ASC、createdAt
    * ASC、commandId ASC，便于审计和重复测试。
    */
  def claim(
      owner: WorkerId,
      leaseDuration: Duration,
      maxAttempts: Int
  ): IO[StoreError, Option[RunCommandLease]] =
    validatePolicy(leaseDuration, maxAttempts) *> LeaseToken.random.flatMap { token =>
      withTransaction { connection =>
        ZIO
          .attemptBlocking {
            reclaimExpired(connection)
            deadLetterExhausted(connection, maxAttempts)
            normalizeDispatchers(connection)

            val select = connection.prepareStatement(
              """SELECT c.command_id, c.run_id, c.command_type, c.payload::text, c.idempotency_key, c.status,
              |c.priority, c.available_at, c.attempt, c.manual_retry_count, c.last_failure, c.created_at, c.updated_at
              |FROM agent_run_commands c
              |JOIN agent_run_dispatch d ON d.run_id = c.run_id
              |WHERE c.status = 'Queued' AND c.available_at <= CURRENT_TIMESTAMP
              |  AND d.status IN ('Idle', 'Queued')
              |ORDER BY c.priority DESC, c.available_at ASC, c.created_at ASC, c.command_id ASC
              |FOR UPDATE OF d, c SKIP LOCKED
              |LIMIT 1""".stripMargin
            )
            val selected =
              try
                val result = select.executeQuery()
                Option.when(result.next())(decodeRecord(result))
              finally select.close()

            selected.map { record =>
              val updateCommand = connection.prepareStatement(
                """UPDATE agent_run_commands SET status = 'Leased', attempt = attempt + 1, updated_at = CURRENT_TIMESTAMP
                |WHERE command_id = ?::uuid
                |RETURNING command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
                |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at""".stripMargin
              )
              val claimedRecord =
                try
                  updateCommand.setString(1, record.commandId.asString)
                  val result = updateCommand.executeQuery()
                  if result.next() then decodeRecord(result) else throw MissingCommand(record.commandId)
                finally updateCommand.close()

              val updateDispatch = connection.prepareStatement(
                """UPDATE agent_run_dispatch SET status = 'Leased', current_command_id = ?::uuid,
                |lease_owner = ?, lease_token = ?::uuid, generation = generation + 1,
                |claimed_at = CURRENT_TIMESTAMP, heartbeat_at = CURRENT_TIMESTAMP,
                |lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), updated_at = CURRENT_TIMESTAMP
                |WHERE run_id = ?::uuid
                |RETURNING generation, claimed_at, lease_expires_at""".stripMargin
              )
              try
                updateDispatch.setString(1, claimedRecord.commandId.asString)
                updateDispatch.setString(2, owner.value)
                updateDispatch.setString(3, token.value)
                updateDispatch.setLong(4, leaseDuration.toMillis)
                updateDispatch.setString(5, claimedRecord.runId.asString)
                val result = updateDispatch.executeQuery()
                if !result.next() then throw MissingDispatch(claimedRecord.runId)
                RunCommandLease(
                  claimedRecord,
                  owner,
                  token,
                  result.getLong("generation"),
                  instant(result, "claimed_at"),
                  instant(result, "lease_expires_at")
                )
              finally updateDispatch.close()
            }
          }
          .mapError(error => databaseError("claim Agent 控制命令失败", error))
      }
    }

  /** 只允许当前、未过期、commandId 完全匹配的租约续期。 */
  def heartbeat(lease: RunCommandLease, leaseDuration: Duration): IO[StoreError, RunCommandLease] =
    validateDuration(leaseDuration) *> withConnection { connection =>
      ZIO
        .attemptBlocking {
          val statement = connection.prepareStatement(
            """UPDATE agent_run_dispatch SET heartbeat_at = CURRENT_TIMESTAMP,
            |lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), updated_at = CURRENT_TIMESTAMP
            |WHERE run_id = ?::uuid AND status = 'Leased' AND current_command_id = ?::uuid
            |  AND lease_owner = ? AND lease_token = ?::uuid AND generation = ?
            |  AND lease_expires_at > CURRENT_TIMESTAMP
            |RETURNING lease_expires_at""".stripMargin
          )
          try
            statement.setLong(1, leaseDuration.toMillis)
            bindFence(statement, 2, lease)
            val result = statement.executeQuery()
            if result.next() then lease.copy(expiresAt = instant(result, "lease_expires_at"))
            else throw LostLease
          finally statement.close()
        }
        .mapError {
          case LostLease => lost(lease, "heartbeat 被取消抢占、租约过期或 generation 已变化")
          case error     => databaseError("续租 Agent 控制命令失败", error)
        }
    }

  /** fenced 完成命令并释放 dispatcher。 Cancel 完成后同 Run 其余 Queued 命令统一变为 Superseded，防止取消后旧 Recover 再次唤醒 Run。
    */
  def complete(lease: RunCommandLease): IO[StoreError, Unit] = withTransaction { connection =>
    ZIO
      .attemptBlocking {
        lockFence(connection, lease)
        updateCommandStatus(connection, lease.commandId, RunCommandStatus.Completed, None, None)
        lease.command.payload match
          case RunCommandPayload.Cancel(_) =>
            val supersede = connection.prepareStatement(
              """UPDATE agent_run_commands SET status = 'Superseded', updated_at = CURRENT_TIMESTAMP
              |WHERE run_id = ?::uuid AND command_id <> ?::uuid AND status = 'Queued'""".stripMargin
            )
            try
              supersede.setString(1, lease.runId.asString)
              supersede.setString(2, lease.commandId.asString)
              supersede.executeUpdate()
            finally supersede.close()
          case _ => ()
        releaseDispatch(connection, lease.runId)
      }
      .mapError {
        case LostLease => lost(lease, "迟到完成被 command fencing 拒绝")
        case error     => databaseError("完成 Agent 控制命令失败", error)
      }
  }

  /** 可重试错误重新排队；错误摘要最多 512 字符。 */
  def abandon(lease: RunCommandLease, retryAt: Instant, safeReason: String): IO[StoreError, Unit] =
    releaseCommand(lease, RunCommandStatus.Queued, Some(retryAt), safeReason)

  /** 永久错误进入 DeadLetter，必须经过显式 retry 才能再次 claim。 */
  def deadLetter(lease: RunCommandLease, safeReason: String): IO[StoreError, Unit] =
    releaseCommand(lease, RunCommandStatus.DeadLetter, None, safeReason)

  /** 人工重试 DeadLetter 命令。
    *
    * attempt 重置为零，只代表新的自动重试窗口；manual_retry_count 单调递增，保留真实人工介入历史。
    */
  def retry(commandId: CommandId, availableAt: Instant): IO[StoreError, RunCommandRecord] =
    withTransaction { connection =>
      ZIO
        .attemptBlocking {
          val select = connection.prepareStatement(
            """SELECT command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
          |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at
          |FROM agent_run_commands WHERE command_id = ?::uuid FOR UPDATE""".stripMargin
          )
          val existing =
            try
              select.setString(1, commandId.asString)
              val result = select.executeQuery()
              if result.next() then decodeRecord(result) else throw MissingCommand(commandId)
            finally select.close()
          if existing.status != RunCommandStatus.DeadLetter then throw InvalidRetry(existing)

          val update = connection.prepareStatement(
            """UPDATE agent_run_commands SET status = 'Queued', available_at = CASE WHEN ? = 0 THEN CURRENT_TIMESTAMP ELSE ? END,
          |attempt = 0, manual_retry_count = manual_retry_count + 1, last_failure = NULL, updated_at = CURRENT_TIMESTAMP
          |WHERE command_id = ?::uuid
          |RETURNING command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
          |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at""".stripMargin
          )
          val retried =
            try
              update.setLong(1, availableAt.toEpochMilli)
              setInstant(update, 2, availableAt)
              update.setString(3, commandId.asString)
              val result = update.executeQuery()
              if result.next() then decodeRecord(result) else throw MissingCommand(commandId)
            finally update.close()
          ensureDispatch(connection, retried.runId)
          wakeDispatch(connection, retried.runId)
          retried
        }
        .mapError {
          case MissingCommand(id)   => AgentError.CommandNotFound(id)
          case InvalidRetry(record) =>
            AgentError.InvalidCommandTransition(record.commandId, record.status.toString, "retry")
          case error => databaseError("人工重试 Agent 控制命令失败", error)
        }
    }

  /** 按命令 ID 查询完整审计记录。 */
  def get(commandId: CommandId): IO[StoreError, RunCommandRecord] = withConnection { connection =>
    ZIO.attemptBlocking(loadCommand(connection, commandId)).mapError {
      case MissingCommand(id) => AgentError.CommandNotFound(id)
      case error              => databaseError("读取 Agent 控制命令失败", error)
    }
  }

  /** 按创建时间和 commandId 返回一个 Run 的全部命令。 */
  def list(runId: RunId): IO[StoreError, Chunk[RunCommandRecord]] = withConnection { connection =>
    ZIO
      .attemptBlocking {
        val statement = connection.prepareStatement(
          """SELECT command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
          |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at
          |FROM agent_run_commands WHERE run_id = ?::uuid ORDER BY created_at ASC, command_id ASC""".stripMargin
        )
        try
          statement.setString(1, runId.asString)
          val result  = statement.executeQuery()
          val builder = ChunkBuilder.make[RunCommandRecord]()
          while result.next() do builder += decodeRecord(result)
          builder.result()
        finally statement.close()
      }
      .mapError(error => databaseError("查询 Run 控制命令失败", error))
  }

  /** 插入命令；冲突时返回 None，由调用者读取并比较原 payload。 */
  private def insertCommand(
      connection: Connection,
      commandId: CommandId,
      runId: RunId,
      payload: RunCommandPayload,
      idempotencyKey: String,
      priority: Int,
      availableAt: Instant
  ): Option[(RunCommandRecord, Boolean)] =
    val statement = connection.prepareStatement(
      """INSERT INTO agent_run_commands
        |(command_id, run_id, command_type, payload, idempotency_key, status, priority, available_at)
        |VALUES (?::uuid, ?::uuid, ?, ?::jsonb, ?, 'Queued', ?, CASE WHEN ? = 0 THEN CURRENT_TIMESTAMP ELSE ? END)
        |ON CONFLICT (run_id, idempotency_key) DO NOTHING
        |RETURNING command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
        |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at""".stripMargin
    )
    try
      statement.setString(1, commandId.asString)
      statement.setString(2, runId.asString)
      statement.setString(3, payload.commandType)
      statement.setString(4, payload.toJson)
      statement.setString(5, idempotencyKey)
      statement.setInt(6, priority)
      statement.setLong(7, availableAt.toEpochMilli)
      setInstant(statement, 8, availableAt)
      val result = statement.executeQuery()
      Option.when(result.next())(decodeRecord(result) -> true)
    finally statement.close()

  /** 保证每个 Run 都有且只有一个 dispatcher。 */
  private def ensureDispatch(connection: Connection, runId: RunId): Unit =
    val statement = connection.prepareStatement(
      "INSERT INTO agent_run_dispatch(run_id, status) VALUES (?::uuid, 'Queued') ON CONFLICT (run_id) DO NOTHING"
    )
    try
      statement.setString(1, runId.asString)
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 非抢占命令只唤醒空闲 dispatcher，不能覆盖当前租约。 */
  private def wakeDispatch(connection: Connection, runId: RunId): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_run_dispatch SET status = CASE WHEN status = 'Leased' THEN 'Leased' ELSE 'Queued' END,
        |updated_at = CURRENT_TIMESTAMP WHERE run_id = ?::uuid""".stripMargin
    )
    try
      statement.setString(1, runId.asString)
      val _ = statement.executeUpdate()
    finally statement.close()

  /** Cancel 原子撤销旧租约，并把被抢占命令放回队列。 */
  private def preemptForCancel(connection: Connection, runId: RunId): Unit =
    val lock = connection.prepareStatement(
      "SELECT current_command_id, status FROM agent_run_dispatch WHERE run_id = ?::uuid FOR UPDATE"
    )
    val current =
      try
        lock.setString(1, runId.asString)
        val result = lock.executeQuery()
        if result.next() && result.getString("status") == "Leased" then
          Option(result.getString("current_command_id"))
        else None
      finally lock.close()
    current.foreach { commandId =>
      val requeue = connection.prepareStatement(
        """UPDATE agent_run_commands SET status = 'Queued', last_failure = 'preempted-by-cancel', updated_at = CURRENT_TIMESTAMP
          |WHERE command_id = ?::uuid AND status = 'Leased'""".stripMargin
      )
      try
        requeue.setString(1, commandId)
        requeue.executeUpdate()
      finally requeue.close()
    }
    val revoke = connection.prepareStatement(
      """UPDATE agent_run_dispatch SET status = 'Queued', current_command_id = NULL, lease_owner = NULL,
        |lease_token = NULL, claimed_at = NULL, lease_expires_at = NULL, heartbeat_at = NULL,
        |updated_at = CURRENT_TIMESTAMP WHERE run_id = ?::uuid""".stripMargin
    )
    try
      revoke.setString(1, runId.asString)
      val _ = revoke.executeUpdate()
    finally revoke.close()

  /** 回收全部已过期 dispatcher；只涉及数据库短事务。 */
  private def reclaimExpired(connection: Connection): Unit =
    val commands = connection.prepareStatement(
      """UPDATE agent_run_commands c SET status = 'Queued', last_failure = 'lease-expired-and-reclaimed',
        |updated_at = CURRENT_TIMESTAMP FROM agent_run_dispatch d
        |WHERE d.current_command_id = c.command_id AND d.status = 'Leased'
        |  AND d.lease_expires_at <= CURRENT_TIMESTAMP AND c.status = 'Leased'""".stripMargin
    )
    try commands.executeUpdate()
    finally commands.close()
    val dispatch = connection.prepareStatement(
      """UPDATE agent_run_dispatch SET status = 'Queued', current_command_id = NULL, lease_owner = NULL,
        |lease_token = NULL, claimed_at = NULL, lease_expires_at = NULL, heartbeat_at = NULL,
        |updated_at = CURRENT_TIMESTAMP WHERE status = 'Leased' AND lease_expires_at <= CURRENT_TIMESTAMP""".stripMargin
    )
    try
      val _ = dispatch.executeUpdate()
    finally dispatch.close()

  /** 将本轮自动尝试耗尽的命令转入 DeadLetter。 */
  private def deadLetterExhausted(connection: Connection, maxAttempts: Int): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_run_commands SET status = 'DeadLetter', last_failure = 'max-attempts-exceeded',
        |updated_at = CURRENT_TIMESTAMP WHERE status = 'Queued' AND available_at <= CURRENT_TIMESTAMP AND attempt >= ?""".stripMargin
    )
    try
      statement.setInt(1, maxAttempts)
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 没有 Queued 命令的非 Leased dispatcher 归一化为 Idle。 */
  private def normalizeDispatchers(connection: Connection): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_run_dispatch d SET status = CASE WHEN EXISTS (
        |  SELECT 1 FROM agent_run_commands c WHERE c.run_id = d.run_id AND c.status = 'Queued'
        |) THEN 'Queued' ELSE 'Idle' END, updated_at = CURRENT_TIMESTAMP
        |WHERE d.status <> 'Leased'""".stripMargin
    )
    try
      val _ = statement.executeUpdate()
    finally statement.close()

  /** 在事务内锁定并验证 dispatcher fencing。 */
  private def lockFence(connection: Connection, lease: RunCommandLease): Unit =
    val statement = connection.prepareStatement(
      """SELECT 1 FROM agent_run_dispatch WHERE run_id = ?::uuid AND status = 'Leased'
        |AND current_command_id = ?::uuid AND lease_owner = ? AND lease_token = ?::uuid
        |AND generation = ? AND lease_expires_at > CURRENT_TIMESTAMP FOR UPDATE""".stripMargin
    )
    try
      bindFence(statement, 1, lease)
      val result = statement.executeQuery()
      if !result.next() then throw LostLease
    finally statement.close()

  /** fenced 释放命令的共同事务。 */
  private def releaseCommand(
      lease: RunCommandLease,
      status: RunCommandStatus,
      availableAt: Option[Instant],
      safeReason: String
  ): IO[StoreError, Unit] = withTransaction { connection =>
    ZIO
      .attemptBlocking {
        lockFence(connection, lease)
        updateCommandStatus(connection, lease.commandId, status, availableAt, Some(safeReason.take(512)))
        releaseDispatch(connection, lease.runId)
      }
      .mapError {
        case LostLease => lost(lease, s"${status.toString} 释放被 command fencing 拒绝")
        case error     => databaseError("释放 Agent 控制命令失败", error)
      }
  }

  /** 更新命令状态；availableAt/reason 为 None 时保持或清空对应字段。 */
  private def updateCommandStatus(
      connection: Connection,
      commandId: CommandId,
      status: RunCommandStatus,
      availableAt: Option[Instant],
      reason: Option[String]
  ): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_run_commands SET status = ?, available_at = COALESCE(?, available_at),
        |last_failure = ?, updated_at = CURRENT_TIMESTAMP WHERE command_id = ?::uuid""".stripMargin
    )
    try
      statement.setString(1, status.toString)
      availableAt match
        case Some(value) => setInstant(statement, 2, value)
        case None        => statement.setObject(2, null)
      statement.setString(3, reason.orNull)
      statement.setString(4, commandId.asString)
      if statement.executeUpdate() != 1 then throw MissingCommand(commandId)
    finally statement.close()

  /** 根据是否仍有 Queued 命令把 dispatcher 释放为 Queued 或 Idle。 */
  private def releaseDispatch(connection: Connection, runId: RunId): Unit =
    val statement = connection.prepareStatement(
      """UPDATE agent_run_dispatch d SET status = CASE WHEN EXISTS (
        |  SELECT 1 FROM agent_run_commands c WHERE c.run_id = d.run_id AND c.status = 'Queued'
        |) THEN 'Queued' ELSE 'Idle' END, current_command_id = NULL, lease_owner = NULL,
        |lease_token = NULL, claimed_at = NULL, lease_expires_at = NULL, heartbeat_at = NULL,
        |updated_at = CURRENT_TIMESTAMP WHERE run_id = ?::uuid""".stripMargin
    )
    try
      statement.setString(1, runId.asString)
      if statement.executeUpdate() != 1 then throw MissingDispatch(runId)
    finally statement.close()

  /** fencing 参数顺序固定，防止某个 UPDATE 漏掉 commandId 或 generation。 */
  private def bindFence(statement: PreparedStatement, start: Int, lease: RunCommandLease): Unit =
    statement.setString(start, lease.runId.asString)
    statement.setString(start + 1, lease.commandId.asString)
    statement.setString(start + 2, lease.owner.value)
    statement.setString(start + 3, lease.token.value)
    statement.setLong(start + 4, lease.generation)

  /** 读取命令行并执行 zio-json 解码；损坏 payload 属于持久化失败而非可忽略数据。 */
  private def decodeRecord(result: ResultSet): RunCommandRecord =
    val commandId = CommandId
      .fromString(result.getString("command_id"))
      .fold(error => throw IllegalStateException(error), identity)
    val runId =
      RunId.fromString(result.getString("run_id")).fold(error => throw IllegalStateException(error), identity)
    val payload = result
      .getString("payload")
      .fromJson[RunCommandPayload]
      .fold(error => throw IllegalStateException(error), identity)
    RunCommandRecord(
      commandId,
      runId,
      payload,
      result.getString("idempotency_key"),
      RunCommandStatus.valueOf(result.getString("status")),
      result.getInt("priority"),
      instant(result, "available_at"),
      result.getInt("attempt"),
      result.getInt("manual_retry_count"),
      Option(result.getString("last_failure")),
      instant(result, "created_at"),
      instant(result, "updated_at")
    )

  /** 按业务幂等键读取原命令，用于冲突后的正文一致性比较。 */
  private def loadByIdempotencyKey(connection: Connection, runId: RunId, key: String): RunCommandRecord =
    val statement = connection.prepareStatement(
      """SELECT command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
        |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at
        |FROM agent_run_commands WHERE run_id = ?::uuid AND idempotency_key = ?""".stripMargin
    )
    try
      statement.setString(1, runId.asString)
      statement.setString(2, key)
      val result = statement.executeQuery()
      if result.next() then decodeRecord(result) else throw IllegalStateException("幂等冲突后未找到原命令")
    finally statement.close()

  /** 按 ID 读取一条命令。 */
  private def loadCommand(connection: Connection, commandId: CommandId): RunCommandRecord =
    val statement = connection.prepareStatement(
      """SELECT command_id, run_id, command_type, payload::text, idempotency_key, status, priority,
        |available_at, attempt, manual_retry_count, last_failure, created_at, updated_at
        |FROM agent_run_commands WHERE command_id = ?::uuid""".stripMargin
    )
    try
      statement.setString(1, commandId.asString)
      val result = statement.executeQuery()
      if result.next() then decodeRecord(result) else throw MissingCommand(commandId)
    finally statement.close()

  /** JDBC 事务只包裹队列状态变化，失败回滚，成功提交。 */
  private def withTransaction[A](use: Connection => Task[A]): IO[StoreError, A] = withConnection { connection =>
    ZIO.attemptBlocking(connection.setAutoCommit(false)) *>
      use(connection)
        .tapBoth(
          _ => ZIO.attemptBlocking(connection.rollback()).orDie,
          _ => ZIO.attemptBlocking(connection.commit()).orDie
        )
        .ensuring(ZIO.attemptBlocking(connection.setAutoCommit(true)).orDie)
  }

  /** 从宿主连接池借还连接；阻塞 JDBC 不占用 ZIO 计算线程。 */
  private def withConnection[A](use: Connection => Task[A]): IO[StoreError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(
            ZIO
              .attemptBlocking(dataSource.getConnection)
              .mapError(error => AgentError.PersistenceFailure("获取数据库连接失败", Some(error)))
          )(connection => ZIO.attemptBlocking(connection.close()).orDie)
          .flatMap(use)
      }
      .mapError {
        case error: StoreError => error
        case error             => AgentError.PersistenceFailure(error.getMessage, Some(error))
      }

  /** PostgreSQL SQLSTATE 分类；约束错误默认不可重试，连接/序列化/死锁/取消可重试。 */
  private def databaseError(operation: String, error: Throwable): StoreError = error match
    case sql: java.sql.SQLException =>
      val state     = Option(sql.getSQLState).getOrElse("unknown")
      val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
      AgentError.DatabaseFailure(operation, state, retryable, Some(sql))
    case other => AgentError.PersistenceFailure(operation, Some(other))

  /** TIMESTAMPTZ 写入统一使用 OffsetDateTime UTC。 */
  private def setInstant(statement: PreparedStatement, index: Int, value: Instant): Unit =
    statement.setObject(index, value.atOffset(ZoneOffset.UTC))

  /** TIMESTAMPTZ 解码为绝对时间点。 */
  private def instant(result: ResultSet, column: String): Instant =
    result.getObject(column, classOf[OffsetDateTime]).toInstant

  /** 校验非空业务幂等键。 */
  private def validateIdempotencyKey(value: String): IO[StoreError, Unit] =
    if value.trim.nonEmpty then ZIO.unit else ZIO.fail(AgentError.PersistenceFailure("命令幂等键不能为空"))

  /** 校验 claim 参数。 */
  private def validatePolicy(duration: Duration, maxAttempts: Int): IO[StoreError, Unit] =
    validateDuration(
      duration
    ) *> (if maxAttempts > 0 then ZIO.unit else ZIO.fail(AgentError.PersistenceFailure("maxAttempts 必须大于零")))

  /** 校验租约时长。 */
  private def validateDuration(duration: Duration): IO[StoreError, Unit] =
    if duration > Duration.Zero then ZIO.unit
    else ZIO.fail(AgentError.PersistenceFailure("leaseDuration 必须大于零"))

  /** 构造稳定 LeaseLost。 */
  private def lost(lease: RunCommandLease, reason: String): AgentError.LeaseLost =
    AgentError.LeaseLost(lease.runId, lease.owner.value, lease.generation, reason)

private case object LostLease                                           extends RuntimeException
final private case class MissingCommand(commandId: CommandId)           extends RuntimeException
final private case class MissingDispatch(runId: RunId)                  extends RuntimeException
final private case class IdempotencyMismatch(runId: RunId, key: String) extends RuntimeException
final private case class InvalidRetry(record: RunCommandRecord)         extends RuntimeException

object PostgresRunCommandStore:
  /** 生产 ZLayer；宿主提供池化 DataSource。 */
  val layer: URLayer[DataSource, RunCommandStore] = ZLayer.fromFunction(PostgresRunCommandStore.apply)
