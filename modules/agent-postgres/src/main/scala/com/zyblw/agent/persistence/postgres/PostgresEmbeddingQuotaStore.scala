package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.sql.{Connection, SQLException, Timestamp}
import java.time.Instant
import javax.sql.DataSource
import zio.*

/** PostgreSQL 上的跨 Worker Embedding 硬配额与幂等预留实现。
  *
  * `agent_embedding_quota_windows` 的一行就是 `(tenant, windowDuration, windowStart)` 的锁粒度。每次 reserve 先创建窗口（幂等），再用
  * `SELECT ... FOR UPDATE` 串行化同窗口的检查和累加；requestId/hash 账本与计数在 同一个数据库事务提交。因此进程崩溃不会留下“已扣额度但没有幂等记录”或相反的半状态。
  *
  * 配额是 fail-closed 控制面：连接池耗尽、数据库切换或未知 SQL 错误都不会绕过限额调用付费 Provider。
  *
  * @param dataSource
  *   宿主统一管理的 PostgreSQL 连接池
  */
final class PostgresEmbeddingQuotaStore(dataSource: DataSource) extends EmbeddingQuotaStore:

  /** 数据库窗口复合键；`windowMillis` 必须参与身份，否则分钟/日策略会互相污染。 */
  final private case class WindowKey(tenantId: TenantId, windowMillis: Long, windowStart: Instant)

  /** 已持锁窗口中的三项当前计数。 */
  final private case class LockedUsage(usage: EmbeddingQuotaUsage)

  /** 幂等记录只需读取请求 hash；请求正文永远不会进入配额表。 */
  final private case class ExistingReservation(requestHash: String)

  /** 原子预留一次 Provider miss 所需额度。
    *
    * 处理顺序是：创建窗口 → 锁窗口 → 检查已有 requestId → 插入 reservation → 检查上限 → 累加。 如果另一事务在检查后抢先插入相同 requestId，`ON CONFLICT
    * DO NOTHING` 会把控制权转回幂等校验， 不会重复计费。若 hash 不同则拒绝，防止调用方错误复用幂等键。
    *
    * @param reservation
    *   可信租户、用途、requestId、请求指纹和确定性计数
    * @param policy
    *   当前租户采用的窗口与硬上限
    * @param now
    *   用于选择窗口的可信时刻
    * @return
    *   预留后的当前窗口用量；幂等重试返回现有用量
    */
  def reserve(
      reservation: EmbeddingQuotaReservation,
      policy: EmbeddingQuotaPolicy,
      now: Instant
  ): IO[RetrievalError, EmbeddingQuotaUsage] =
    validate(reservation, policy) *>
      withTransaction { connection =>
        val key = windowKey(reservation.context.tenantId, policy, now)
        for
          _        <- ensureWindow(connection, key)
          current  <- lockUsage(connection, key)
          existing <- findReservation(connection, reservation.context.tenantId, reservation.context.requestId)
          result   <- existing match
            case Some(value) => validateIdempotent(value, reservation).as(current.usage)
            case None        =>
              insertReservation(connection, key, reservation).flatMap {
                case true  => applyReservation(connection, key, current.usage, reservation, policy)
                case false =>
                  // 唯一索引冲突可能来自另一个刚提交的窗口；重新读取后仍必须验证 hash。
                  findReservation(connection, reservation.context.tenantId, reservation.context.requestId)
                    .someOrFail(AgentError.RetrievalFailed("Embedding requestId 冲突后记录不可见", retryable = true))
                    .flatMap(value => validateIdempotent(value, reservation))
                    .as(current.usage)
              }
        yield result
      }

  /** 查询 `now` 所在窗口；不存在的窗口返回零而不是创建数据库行。
    *
    * @param tenantId
    *   业务租户
    * @param policy
    *   用于计算精确窗口身份的策略
    * @param now
    *   查询时刻
    */
  def usage(
      tenantId: TenantId,
      policy: EmbeddingQuotaPolicy,
      now: Instant
  ): IO[RetrievalError, EmbeddingQuotaUsage] =
    val key = windowKey(tenantId, policy, now)
    withConnection { connection =>
      jdbc("read quota usage") {
        val statement = connection.prepareStatement(
          """SELECT requests, texts, characters
            |FROM agent_embedding_quota_windows
            |WHERE tenant_id = ? AND window_millis = ? AND window_start = ?""".stripMargin
        )
        try
          bindWindow(statement, key)
          val result = statement.executeQuery()
          if result.next() then EmbeddingQuotaUsage(result.getLong(1), result.getLong(2), result.getLong(3))
          else EmbeddingQuotaUsage()
        finally statement.close()
      }
    }

  /** 有界删除已经完整结束的窗口，外键 `ON DELETE CASCADE` 同步释放 requestId 幂等记录。
    *
    * 多个 maintenance worker 可以并发调用：候选窗口用稳定顺序和 `SKIP LOCKED` claim，每批事务保持很短。
    *
    * @param endedBefore
    *   窗口结束时间不晚于此时刻才可删除
    * @param limit
    *   单批最多删除的窗口数；非正数返回 0
    */
  def purgeWindows(endedBefore: Instant, limit: Int): IO[RetrievalError, Long] =
    if limit <= 0 then ZIO.succeed(0L)
    else
      withTransaction { connection =>
        jdbc("purge quota windows") {
          val statement = connection.prepareStatement(
            """WITH candidates AS (
            |  SELECT tenant_id, window_millis, window_start
            |  FROM agent_embedding_quota_windows
            |  WHERE window_start + window_millis * INTERVAL '1 millisecond' <= ?
            |  ORDER BY window_start, window_millis, tenant_id
            |  FOR UPDATE SKIP LOCKED
            |  LIMIT ?
            |)
            |DELETE FROM agent_embedding_quota_windows quota
            |USING candidates candidate
            |WHERE quota.tenant_id = candidate.tenant_id
            |  AND quota.window_millis = candidate.window_millis
            |  AND quota.window_start = candidate.window_start""".stripMargin
          )
          try
            statement.setTimestamp(1, Timestamp.from(endedBefore))
            statement.setInt(2, limit)
            statement.executeUpdate().toLong
          finally statement.close()
        }
      }

  /** 创建空窗口；并发创建由复合主键和 DO NOTHING 收敛为一行。 */
  private def ensureWindow(connection: Connection, key: WindowKey): IO[RetrievalError, Unit] =
    jdbc("ensure quota window") {
      val statement = connection.prepareStatement(
        """INSERT INTO agent_embedding_quota_windows(tenant_id, window_millis, window_start)
          |VALUES (?, ?, ?)
          |ON CONFLICT (tenant_id, window_millis, window_start) DO NOTHING""".stripMargin
      )
      try
        bindWindow(statement, key)
        statement.executeUpdate()
        ()
      finally statement.close()
    }

  /** 锁定窗口计数；调用者必须已经开启事务并先执行 `ensureWindow`。 */
  private def lockUsage(connection: Connection, key: WindowKey): IO[RetrievalError, LockedUsage] =
    jdbc("lock quota window") {
      val statement = connection.prepareStatement(
        """SELECT requests, texts, characters
          |FROM agent_embedding_quota_windows
          |WHERE tenant_id = ? AND window_millis = ? AND window_start = ?
          |FOR UPDATE""".stripMargin
      )
      try
        bindWindow(statement, key)
        val result = statement.executeQuery()
        if !result.next() then throw IllegalStateException("ensure 后 quota window 不存在")
        LockedUsage(EmbeddingQuotaUsage(result.getLong(1), result.getLong(2), result.getLong(3)))
      finally statement.close()
    }

  /** 查询租户级 requestId；它跨窗口唯一，直到所属窗口被 retention 清理。 */
  private def findReservation(
      connection: Connection,
      tenantId: TenantId,
      requestId: String
  ): IO[RetrievalError, Option[ExistingReservation]] =
    jdbc("read quota reservation") {
      val statement = connection.prepareStatement(
        "SELECT request_hash FROM agent_embedding_quota_reservations WHERE tenant_id = ? AND request_id = ?"
      )
      try
        statement.setString(1, tenantId.value)
        statement.setString(2, requestId)
        val result = statement.executeQuery()
        if result.next() then Some(ExistingReservation(result.getString(1))) else None
      finally statement.close()
    }

  /** 插入幂等账本；返回 false 表示另一事务已经占用该 tenant/requestId。 */
  private def insertReservation(
      connection: Connection,
      key: WindowKey,
      reservation: EmbeddingQuotaReservation
  ): IO[RetrievalError, Boolean] =
    jdbc("insert quota reservation") {
      val statement = connection.prepareStatement(
        """INSERT INTO agent_embedding_quota_reservations
          |(tenant_id, request_id, request_hash, purpose, window_millis, window_start, requests, texts, characters)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT (tenant_id, request_id) DO NOTHING""".stripMargin
      )
      try
        statement.setString(1, key.tenantId.value)
        statement.setString(2, reservation.context.requestId)
        statement.setString(3, reservation.requestHash)
        statement.setString(4, purposeName(reservation.context.purpose))
        statement.setLong(5, key.windowMillis)
        statement.setTimestamp(6, Timestamp.from(key.windowStart))
        statement.setLong(7, reservation.requests)
        statement.setLong(8, reservation.texts)
        statement.setLong(9, reservation.characters)
        statement.executeUpdate() == 1
      finally statement.close()
    }

  /** 检查硬上限后累加；检查失败会让上层事务同时回滚刚插入的 reservation。 */
  private def applyReservation(
      connection: Connection,
      key: WindowKey,
      current: EmbeddingQuotaUsage,
      reservation: EmbeddingQuotaReservation,
      policy: EmbeddingQuotaPolicy
  ): IO[RetrievalError, EmbeddingQuotaUsage] =
    firstExceeded(current, reservation, policy) match
      case Some((metric, limit)) => ZIO.fail(AgentError.EmbeddingQuotaExceeded(metric, limit))
      case None                  =>
        val next = EmbeddingQuotaUsage(
          current.requests + reservation.requests,
          current.texts + reservation.texts,
          current.characters + reservation.characters
        )
        jdbc("increment quota usage") {
          val statement = connection.prepareStatement(
            """UPDATE agent_embedding_quota_windows
              |SET requests = ?, texts = ?, characters = ?, updated_at = CURRENT_TIMESTAMP
              |WHERE tenant_id = ? AND window_millis = ? AND window_start = ?""".stripMargin
          )
          try
            statement.setLong(1, next.requests)
            statement.setLong(2, next.texts)
            statement.setLong(3, next.characters)
            statement.setString(4, key.tenantId.value)
            statement.setLong(5, key.windowMillis)
            statement.setTimestamp(6, Timestamp.from(key.windowStart))
            if statement.executeUpdate() != 1 then throw IllegalStateException("quota window 累加目标丢失")
            next
          finally statement.close()
        }

  /** 相同 requestId 只能重放同一 hash；不同 hash 是调用方协议错误。 */
  private def validateIdempotent(
      existing: ExistingReservation,
      reservation: EmbeddingQuotaReservation
  ): IO[RetrievalError, Unit] =
    if existing.requestHash == reservation.requestHash then ZIO.unit
    else ZIO.fail(AgentError.RetrievalFailed("Embedding requestId 已绑定不同请求"))

  /** 在数据库交互前检查字段长度，避免把 CHECK violation 当作运行时控制流。 */
  private def validate(
      reservation: EmbeddingQuotaReservation,
      policy: EmbeddingQuotaPolicy
  ): IO[RetrievalError, Unit] =
    val valid = reservation.context.tenantId.value.length <= 1000 &&
      reservation.context.requestId.trim.nonEmpty && reservation.context.requestId.length <= 500 &&
      reservation.requestHash.matches("[0-9a-f]{64}") && policy.window.toMillis > 0L
    if valid then ZIO.unit
    else ZIO.fail(AgentError.RetrievalFailed("Embedding quota reservation 不符合数据库契约"))

  /** 使用减法比较防止 `current + increment` 发生 Long 溢出后错误放行。 */
  private def firstExceeded(
      current: EmbeddingQuotaUsage,
      increment: EmbeddingQuotaReservation,
      policy: EmbeddingQuotaPolicy
  ): Option[(String, Long)] =
    if current.requests > policy.maxRequests - increment.requests then Some("requests" -> policy.maxRequests)
    else if current.texts > policy.maxTexts - increment.texts then Some("texts" -> policy.maxTexts)
    else if current.characters > policy.maxCharacters - increment.characters then
      Some("characters" -> policy.maxCharacters)
    else None

  /** 把时刻向下取整到固定毫秒窗口；`floorDiv` 对 epoch 之前时刻仍正确。 */
  private def windowKey(tenantId: TenantId, policy: EmbeddingQuotaPolicy, now: Instant): WindowKey =
    val millis = policy.window.toMillis
    WindowKey(tenantId, millis, Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli, millis) * millis))

  /** 为前三个 SQL 参数绑定统一窗口复合键。 */
  private def bindWindow(statement: java.sql.PreparedStatement, key: WindowKey): Unit =
    statement.setString(1, key.tenantId.value)
    statement.setLong(2, key.windowMillis)
    statement.setTimestamp(3, Timestamp.from(key.windowStart))

  /** 数据库存储稳定小写枚举，不依赖 Scala enum 默认 `toString`。 */
  private def purposeName(purpose: EmbeddingPurpose): String = purpose match
    case EmbeddingPurpose.Query    => "query"
    case EmbeddingPurpose.Indexing => "indexing"
    case EmbeddingPurpose.Memory   => "memory"

  /** 从宿主连接池按 Scope 借还连接；取消和失败都不会泄漏连接。 */
  private def withConnection[A](use: Connection => IO[RetrievalError, A]): IO[RetrievalError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(jdbc("acquire connection")(dataSource.getConnection))(connection =>
          ZIO.attemptBlocking(connection.close()).ignore
        )
        .flatMap(use)
    }

  /** 允许业务逻辑被取消，但 commit、rollback 与连接状态恢复不可被中断。 */
  private def withTransaction[A](use: Connection => IO[RetrievalError, A]): IO[RetrievalError, A] =
    withConnection { connection =>
      for
        previous <- jdbc("read auto commit")(connection.getAutoCommit)
        _        <- jdbc("begin transaction")(connection.setAutoCommit(false))
        result   <- ZIO
          .uninterruptibleMask { restore =>
            restore(use(connection)).exit.flatMap {
              case Exit.Success(value) => jdbc("commit transaction")(connection.commit()).as(value)
              case Exit.Failure(cause) =>
                jdbc("rollback transaction")(connection.rollback()).ignore *> ZIO.refailCause(cause)
            }
          }
          .ensuring(jdbc("restore auto commit")(connection.setAutoCommit(previous)).ignore)
      yield result
    }

  /** JDBC 阻塞调用只在 blocking executor 运行，并统一脱敏错误消息。 */
  private def jdbc[A](operation: String)(effect: => A): IO[RetrievalError, A] =
    ZIO.attemptBlocking(effect).mapError(error => databaseError(operation, error))

  /** 根据 SQLSTATE 判断基础设施错误是否适合由上层 Schedule 重试。 */
  private def databaseError(operation: String, error: Throwable): RetrievalError =
    val sqlState = error match
      case sql: SQLException => Option(sql.getSQLState).getOrElse("unknown")
      case _                 => "not-sql"
    val retryable = sqlState.startsWith("08") || sqlState.startsWith("40") || sqlState.startsWith("53") ||
      Set("57P01", "57P02", "57P03").contains(sqlState)
    AgentError.RetrievalFailed(s"PostgreSQL embedding quota $operation 失败 (sqlState=$sqlState)", retryable)

object PostgresEmbeddingQuotaStore:
  /** 以宿主 DataSource 构造生产配额 Store。 */
  val layer: URLayer[DataSource, EmbeddingQuotaStore] =
    ZLayer.fromFunction((dataSource: DataSource) =>
      PostgresEmbeddingQuotaStore(dataSource): EmbeddingQuotaStore
    )
