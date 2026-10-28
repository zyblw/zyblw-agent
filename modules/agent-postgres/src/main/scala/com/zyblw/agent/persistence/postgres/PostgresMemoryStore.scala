package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import javax.sql.DataSource
import zio.*
import zio.json.*
import zio.json.ast.Json

/** PostgreSQL 长期记忆 Store。
  *
  * 每个查询都同时绑定 `scope_kind + scope_key`，User scope 的 canonical key 还包含 tenant，防止 userId 在多租户部署 中串读。删除把
  * value/search_text 清空为 tombstone 并递增版本；迟到提炼 worker 只有持有该新版本才能通过 CAS 复活内容。JDBC 连接来自宿主共享池，事务和 SQL 都不包含模型调用。
  *
  * @param dataSource
  *   宿主提供的共享 PostgreSQL DataSource
  * @param maxValueCharacters
  *   单条 JSON 文本上限，防止把完整聊天或工具巨型结果误存为长期记忆
  */
final class PostgresMemoryStore(dataSource: DataSource, maxValueCharacters: Int = 20_000)
    extends MemoryStore,
      MemoryGovernanceRepository:
  require(maxValueCharacters > 0, "maxValueCharacters 必须为正数")

  /** 无条件 upsert，数据库负责原子递增 version；后台并发提炼应改用 compareAndSet。 */
  def put(scope: MemoryScope, entry: MemoryEntry): IO[StoreError, Unit] =
    Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
      validateValue(entry) *> withConnection { connection =>
        jdbc("upsert memory") {
          val sql =
            """INSERT INTO zyblw_agent_core.agent_memories
              |(scope_kind, scope_key, tenant_id, user_id, session_id, memory_key, value_json, search_text,
              | memory_kind, importance, confidence, sensitivity, evidence, extractor_version, source_run_id,
              | version, status, created_at, updated_at, expires_at, deleted_at)
              |VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'active', ?, ?, ?, NULL)
              |ON CONFLICT (scope_kind, scope_key, memory_key) DO UPDATE SET
              |value_json = EXCLUDED.value_json, search_text = EXCLUDED.search_text,
              |memory_kind = EXCLUDED.memory_kind, importance = EXCLUDED.importance,
              |confidence = EXCLUDED.confidence, sensitivity = EXCLUDED.sensitivity,
              |evidence = EXCLUDED.evidence, extractor_version = EXCLUDED.extractor_version,
              |source_run_id = EXCLUDED.source_run_id, version = agent_memories.version + 1,
              |status = 'active', updated_at = EXCLUDED.updated_at, expires_at = EXCLUDED.expires_at,
              |deleted_at = NULL""".stripMargin
          val statement = connection.prepareStatement(sql)
          try
            bindEntry(statement, scope, entry, now)
            statement.executeUpdate()
            ()
          finally statement.close()
        }
      }
    }

  /** 使用单条 INSERT 或 UPDATE 完成 compare-and-set。
    *
    * expectedVersion=0 只允许首次创建；正版本既可更新 active，也可在用户明确确认后从 tombstone 恢复。失败时额外 读取当前版本，仅用于返回 typed
    * conflict，不会覆盖较新值。
    */
  def compareAndSet(
      scope: MemoryScope,
      expectedVersion: Long,
      entry: MemoryEntry
  ): IO[StoreError, MemoryEntry] =
    compareAndSet(scope, expectedVersion, entry, None)

  /** 在同一数据库事务与作用域级咨询锁内原子校验删除屏障与 expectedVersion。 */
  override def compareAndSet(
      scope: MemoryScope,
      expectedVersion: Long,
      entry: MemoryEntry,
      expectedBarrier: Option[MemoryDeletionBarrier]
  ): IO[StoreError, MemoryEntry] =
    if expectedVersion < 0L then
      ZIO.fail(AgentError.MemoryPolicyRejected(entry.key, "negative-expected-version"))
    else
      Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
        validateValue(entry) *> withTransaction { connection =>
          val db = scopeDb(scope)
          lockScope(connection, db) *>
            ZIO.foreachDiscard(expectedBarrier) { captured =>
              readBarrierInConnection(connection, scope, Some(entry.key)).flatMap { latest =>
                if latest.hasDeletedSince(captured, entry.key) then
                  ZIO.fail(
                    AgentError.MemoryVersionConflict(
                      scope.diagnostic,
                      entry.key,
                      expectedVersion,
                      latest.conflictVersion(entry.key, expectedVersion)
                    )
                  )
                else ZIO.unit
              }
            } *> {
              val write =
                if expectedVersion == 0L then insertFirst(connection, scope, entry, now)
                else updateExpected(connection, scope, expectedVersion, entry, now)
              write.flatMap {
                case Some(updated) => ZIO.succeed(updated)
                case None          =>
                  currentVersion(connection, scope, entry.key).flatMap { actual =>
                    ZIO.fail(
                      AgentError.MemoryVersionConflict(scope.diagnostic, entry.key, expectedVersion, actual)
                    )
                  }
              }
            }
        }
      }

  /** 精确读取 active 且未过期内容。 */
  def get(scope: MemoryScope, key: String): IO[StoreError, Option[MemoryEntry]] =
    if key.trim == MemoryStore.ScopeBarrierKey then ZIO.none
    else
      withConnection { connection =>
        selectEntries(
          connection,
          s"""$entrySelect
             |WHERE scope_kind = ? AND scope_key = ? AND memory_key = ? AND memory_key <> ? AND status = 'active'
             |  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)""".stripMargin,
          statement =>
            val db = scopeDb(scope)
            statement.setString(1, db.kind)
            statement.setString(2, db.key)
            statement.setString(3, key)
            statement.setString(4, MemoryStore.ScopeBarrierKey)
          ,
          limitOne = true
        ).map(_.headOption)
      }

  /** scope 内混合 PostgreSQL FTS 与安全 substring 匹配。
    *
    * `websearch_to_tsquery` 接受普通用户文本且不会抛语法错误；substring 分支保证未分词中文仍可按连续词命中。 排序先看是否命中，再看 FTS
    * rank、importance、confidence 和稳定 key。
    */
  def search(scope: MemoryScope, query: String, limit: Int): IO[StoreError, Chunk[MemoryEntry]] =
    if limit <= 0 || query.trim.isEmpty then ZIO.succeed(Chunk.empty)
    else
      withConnection { connection =>
        selectEntries(
          connection,
          s"""WITH q AS (SELECT websearch_to_tsquery('simple', ?) AS value)
           |$entrySelect CROSS JOIN q
           |WHERE scope_kind = ? AND scope_key = ? AND memory_key <> ? AND status = 'active'
           |  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
           |  AND (search_vector @@ q.value OR position(lower(?) in lower(search_text)) > 0)
           |ORDER BY (search_vector @@ q.value) DESC,
           |         ts_rank_cd(search_vector, q.value, 32) DESC,
           |         importance DESC, confidence DESC, memory_key ASC
           |LIMIT ?""".stripMargin,
          statement =>
            val db = scopeDb(scope)
            statement.setString(1, query.trim)
            statement.setString(2, db.kind)
            statement.setString(3, db.key)
            statement.setString(4, MemoryStore.ScopeBarrierKey)
            statement.setString(5, query.trim)
            statement.setInt(6, limit)
          ,
          limitOne = false
        )
      }

  /** 为用户治理页面列出最近 active、未过期记忆。 */
  def list(scope: MemoryScope, limit: Int): IO[StoreError, Chunk[MemoryEntry]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else
      withConnection { connection =>
        selectEntries(
          connection,
          s"""$entrySelect
           |WHERE scope_kind = ? AND scope_key = ? AND memory_key <> ? AND status = 'active'
           |  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
           |ORDER BY updated_at DESC, memory_key ASC
           |LIMIT ?""".stripMargin,
          statement =>
            val db = scopeDb(scope)
            statement.setString(1, db.kind)
            statement.setString(2, db.key)
            statement.setString(3, MemoryStore.ScopeBarrierKey)
            statement.setInt(4, limit)
          ,
          limitOne = false
        )
      }

  /** 单条 tombstone；重复删除不会反复增加版本，对尚未落库的 key 预置版本 1 的 tombstone。 */
  def delete(scope: MemoryScope, key: String): IO[StoreError, Unit] =
    withTransaction(connection => tombstoneOne(connection, scope, key).unit)

  /** 原子清空一个用户/session/tenant scope 的所有 active 内容，推进 scope 删除屏障并返回新删除数量。 */
  def deleteScope(scope: MemoryScope): IO[StoreError, Long] =
    withTransaction(connection => tombstoneScope(connection, scope))

  /** 读取当前作用域的删除屏障快照（包含 scope 级清空代数与各 key 的 tombstone/active 版本）。 */
  override def currentBarrier(scope: MemoryScope): IO[StoreError, MemoryDeletionBarrier] =
    withConnection(connection => readBarrierInConnection(connection, scope, None))

  private def readBarrierInConnection(
      connection: Connection,
      scope: MemoryScope,
      targetKey: Option[String]
  ): IO[StoreError, MemoryDeletionBarrier] =
    jdbc("read memory deletion barrier") {
      val sql = targetKey match
        case Some(_) =>
          """SELECT memory_key, version, status,
            |       CASE WHEN memory_key = ? THEN value_json::text ELSE NULL END
            |FROM zyblw_agent_core.agent_memories
            |WHERE scope_kind = ? AND scope_key = ? AND memory_key IN (?, ?)""".stripMargin
        case None =>
          """SELECT memory_key, version, status,
            |       CASE WHEN memory_key = ? THEN value_json::text ELSE NULL END
            |FROM zyblw_agent_core.agent_memories
            |WHERE scope_kind = ? AND scope_key = ?""".stripMargin
      val statement = connection.prepareStatement(sql)
      try
        val db = scopeDb(scope)
        statement.setString(1, MemoryStore.ScopeBarrierKey)
        statement.setString(2, db.kind)
        statement.setString(3, db.key)
        targetKey.foreach { key =>
          statement.setString(4, MemoryStore.ScopeBarrierKey)
          statement.setString(5, key)
        }
        val result          = statement.executeQuery()
        var scopeGeneration = 0L
        val tombstones      = scala.collection.mutable.Map.empty[String, Long]
        val actives         = scala.collection.mutable.Map.empty[String, Long]
        while result.next() do
          val memoryKey = result.getString(1)
          val version   = result.getLong(2)
          val status    = result.getString(3)
          val valueJson = Option(result.getString(4))
          if memoryKey == MemoryStore.ScopeBarrierKey then
            valueJson.flatMap(_.fromJson[PostgresScopeBarrierMeta].toOption) match
              case Some(meta) =>
                scopeGeneration = meta.scopeGeneration
                meta.tombstones.foreach { case (k, v) =>
                  tombstones.update(k, Math.max(tombstones.getOrElse(k, 0L), v))
                }
              case None =>
                scopeGeneration = version
          else if status == "deleted" then
            tombstones.update(memoryKey, Math.max(tombstones.getOrElse(memoryKey, 0L), version))
          else if status == "active" then actives.update(memoryKey, version)
        MemoryDeletionBarrier(
          scopeGeneration = scopeGeneration,
          tombstoneVersions = tombstones.toMap,
          activeVersions = actives.toMap
        )
      finally statement.close()
    }

  /** 多 worker 安全地分批清理过期内容。 CTE 先以 `FOR UPDATE SKIP LOCKED` 领取稳定顺序的行，再在同一语句中 tombstone，避免清理任务互相等待。
    */
  def purgeExpired(nowEpochMilli: Long, limit: Int): IO[StoreError, Long] =
    if limit <= 0 then ZIO.succeed(0L)
    else
      withConnection { connection =>
        jdbc("purge expired memory") {
          val statement = connection.prepareStatement(
            """WITH expired AS (
            |  SELECT scope_kind, scope_key, memory_key
            |  FROM zyblw_agent_core.agent_memories
            |  WHERE status = 'active' AND memory_key <> ? AND expires_at IS NOT NULL AND expires_at <= ?
            |  ORDER BY expires_at, scope_kind, scope_key, memory_key
            |  FOR UPDATE SKIP LOCKED
            |  LIMIT ?
            |)
            |UPDATE zyblw_agent_core.agent_memories m
            |SET value_json = NULL, search_text = NULL, status = 'deleted', version = m.version + 1,
            |    updated_at = ?, deleted_at = ?
            |FROM expired e
            |WHERE m.scope_kind = e.scope_kind AND m.scope_key = e.scope_key AND m.memory_key = e.memory_key""".stripMargin
          )
          try
            val now = Instant.ofEpochMilli(nowEpochMilli)
            statement.setString(1, MemoryStore.ScopeBarrierKey)
            setInstant(statement, 2, now)
            statement.setInt(3, limit)
            setInstant(statement, 4, now)
            setInstant(statement, 5, now)
            statement.executeUpdate().toLong
          finally statement.close()
        }
      }

  /** 持久化成功读取的低敏审计事实。
    *
    * 查询词、记忆 JSON 和原始 key 根本不在 `MemoryAuditRecord` 中，因此这里不存在“调用方忘记脱敏”的可选路径。 auditId
    * 冲突时保持幂等，适合上层在连接结果不确定时使用同一审计事实重试。
    */
  def recordRead(record: MemoryAuditRecord): IO[StoreError, Unit] =
    withConnection(connection => insertAudit(connection, record))

  /** 在同一 JDBC 事务中完成 CAS 纠正与审计 INSERT。
    *
    * 若 CAS 冲突、审计约束失败、Fiber 被取消或数据库连接中断，事务都会回滚；调用方不会看到“纠正成功但审计丢失”。
    */
  def correct(
      scope: MemoryScope,
      expectedVersion: Long,
      entry: MemoryEntry,
      audit: MemoryAuditRecord
  ): IO[StoreError, MemoryEntry] =
    if audit.action != MemoryAuditAction.Correct then
      ZIO.fail(AgentError.MemoryPolicyRejected(entry.key, "invalid-correct-audit-action"))
    else
      Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
        validateValue(entry) *> withTransaction { connection =>
          lockScope(connection, scopeDb(scope)) *> {
            val write =
              if expectedVersion == 0L then insertOrReviveForGovernance(connection, scope, entry, now)
              else updateExpected(connection, scope, expectedVersion, entry, now)
            write.flatMap {
              case Some(updated) =>
                insertAudit(
                  connection,
                  audit.copy(resultingVersion = Some(updated.version), affectedCount = 1L)
                ).as(updated)
              case None =>
                currentVersion(connection, scope, entry.key).flatMap { actual =>
                  ZIO.fail(
                    AgentError.MemoryVersionConflict(scope.diagnostic, entry.key, expectedVersion, actual)
                  )
                }
            }
          }
        }
      }

  /** 单条 tombstone 与审计共享事务；不存在时审计 affectedCount=0，重复请求保持幂等业务语义。 */
  def delete(
      scope: MemoryScope,
      key: String,
      audit: MemoryAuditRecord
  ): IO[StoreError, Long] =
    if audit.action != MemoryAuditAction.Delete then
      ZIO.fail(AgentError.MemoryPolicyRejected(key, "invalid-delete-audit-action"))
    else
      withTransaction { connection =>
        tombstoneOne(connection, scope, key).flatMap { affected =>
          insertAudit(connection, audit.copy(affectedCount = affected)).as(affected)
        }
      }

  /** Scope tombstone 与审计共享事务，适合“删除我的全部记忆”隐私操作。 */
  def deleteScope(
      scope: MemoryScope,
      audit: MemoryAuditRecord
  ): IO[StoreError, Long] =
    if audit.action != MemoryAuditAction.DeleteScope then
      ZIO.fail(AgentError.MemoryPolicyRejected("scope", "invalid-delete-scope-audit-action"))
    else
      withTransaction { connection =>
        tombstoneScope(connection, scope).flatMap { affected =>
          insertAudit(connection, audit.copy(affectedCount = affected)).as(affected)
        }
      }

  /** 复用单条删除 SQL，但允许治理事务在同一 Connection 上继续插入审计。 */
  private def tombstoneOne(connection: Connection, scope: MemoryScope, key: String): IO[StoreError, Long] =
    if key.trim == MemoryStore.ScopeBarrierKey then
      ZIO.fail(AgentError.MemoryPolicyRejected(key, "reserved-memory-key"))
    else if key.trim.isEmpty || key.length > 200 then ZIO.succeed(0L)
    else
      val db = scopeDb(scope)
      lockScope(connection, db) *>
        jdbc("governed delete memory") {
          val statement = connection.prepareStatement(
            """UPDATE zyblw_agent_core.agent_memories
              |SET value_json = NULL, search_text = NULL, status = 'deleted', version = version + 1,
              |    updated_at = CURRENT_TIMESTAMP, deleted_at = CURRENT_TIMESTAMP
              |WHERE scope_kind = ? AND scope_key = ? AND memory_key = ? AND status = 'active'
              |RETURNING version""".stripMargin
          )
          val updatedVersionOpt =
            try
              statement.setString(1, db.kind)
              statement.setString(2, db.key)
              statement.setString(3, key)
              val rs = statement.executeQuery()
              if rs.next() then Some(rs.getLong(1)) else None
            finally statement.close()
          val (affected, deletedVersion) = updatedVersionOpt match
            case Some(ver) => 1L -> ver
            case None      =>
              val insertTombstone = connection.prepareStatement(
                """INSERT INTO zyblw_agent_core.agent_memories
                  |(scope_kind, scope_key, tenant_id, user_id, session_id, memory_key, value_json, search_text,
                  | memory_kind, importance, confidence, sensitivity, evidence, extractor_version, source_run_id,
                  | version, status, created_at, updated_at, expires_at, deleted_at)
                  |VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, 'semantic', 0.0, 1.0, 'personal', 'user_stated', 'tombstone-v1',
                  |        NULL, 1, 'deleted', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)
                  |ON CONFLICT (scope_kind, scope_key, memory_key) DO NOTHING""".stripMargin
              )
              try
                bindScopeColumns(insertTombstone, db)
                insertTombstone.setString(6, key)
                insertTombstone.executeUpdate()
                ()
              finally insertTombstone.close()
              val readVer = connection.prepareStatement(
                """SELECT version FROM zyblw_agent_core.agent_memories
                  |WHERE scope_kind = ? AND scope_key = ? AND memory_key = ?""".stripMargin
              )
              val ver =
                try
                  readVer.setString(1, db.kind)
                  readVer.setString(2, db.key)
                  readVer.setString(3, key)
                  val rs = readVer.executeQuery()
                  if rs.next() then rs.getLong(1) else 1L
                finally readVer.close()
              0L -> ver
          upsertBarrierMetaUnsafe(
            connection,
            db,
            Map(key -> deletedVersion),
            incrementScopeGeneration = false
          )
          affected
        }

  /** 复用 scope 删除 SQL，推进 scope 级删除屏障并返回实际转为 tombstone 的行数。 */
  private def tombstoneScope(connection: Connection, scope: MemoryScope): IO[StoreError, Long] =
    val db = scopeDb(scope)
    lockScope(connection, db) *>
      jdbc("governed delete memory scope") {
        val statement = connection.prepareStatement(
          """UPDATE zyblw_agent_core.agent_memories
            |SET value_json = NULL, search_text = NULL, status = 'deleted', version = version + 1,
            |    updated_at = CURRENT_TIMESTAMP, deleted_at = CURRENT_TIMESTAMP
            |WHERE scope_kind = ? AND scope_key = ? AND status = 'active' AND memory_key <> ?
            |RETURNING memory_key, version""".stripMargin
        )
        val deletedKeys = scala.collection.mutable.Map.empty[String, Long]
        try
          statement.setString(1, db.kind)
          statement.setString(2, db.key)
          statement.setString(3, MemoryStore.ScopeBarrierKey)
          val rs = statement.executeQuery()
          while rs.next() do deletedKeys.update(rs.getString(1), rs.getLong(2))
        finally statement.close()
        upsertBarrierMetaUnsafe(connection, db, deletedKeys.toMap, incrementScopeGeneration = true)
        deletedKeys.size.toLong
      }

  private def lockScope(connection: Connection, db: MemoryScopeDb): IO[StoreError, Unit] =
    jdbc("lock memory scope") {
      val statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))")
      try
        statement.setString(1, db.kind)
        statement.setString(2, db.key)
        val rs = statement.executeQuery()
        rs.close()
      finally statement.close()
    }

  private def upsertBarrierMetaUnsafe(
      connection: Connection,
      db: MemoryScopeDb,
      additionalTombstones: Map[String, Long],
      incrementScopeGeneration: Boolean
  ): Unit =
    val selectStmt = connection.prepareStatement(
      """SELECT version, value_json::text
        |FROM zyblw_agent_core.agent_memories
        |WHERE scope_kind = ? AND scope_key = ? AND memory_key = ?""".stripMargin
    )
    val existingMeta =
      try
        selectStmt.setString(1, db.kind)
        selectStmt.setString(2, db.key)
        selectStmt.setString(3, MemoryStore.ScopeBarrierKey)
        val rs = selectStmt.executeQuery()
        if rs.next() then
          val ver     = rs.getLong(1)
          val rawJson = Option(rs.getString(2))
          rawJson
            .flatMap(_.fromJson[PostgresScopeBarrierMeta].toOption)
            .getOrElse(PostgresScopeBarrierMeta(ver, Map.empty))
        else PostgresScopeBarrierMeta(0L, Map.empty)
      finally selectStmt.close()

    val nextGeneration =
      if incrementScopeGeneration then existingMeta.scopeGeneration + 1L
      else existingMeta.scopeGeneration
    val mergedTombstones =
      additionalTombstones.foldLeft(existingMeta.tombstones) { case (acc, (k, v)) =>
        acc.updated(k, Math.max(acc.getOrElse(k, 0L), v))
      }
    val nextMetaJson = PostgresScopeBarrierMeta(nextGeneration, mergedTombstones).toJson
    val rowVersion   = Math.max(1L, nextGeneration)

    val barrierStatement = connection.prepareStatement(
      """INSERT INTO zyblw_agent_core.agent_memories
        |(scope_kind, scope_key, tenant_id, user_id, session_id, memory_key, value_json, search_text,
        | memory_kind, importance, confidence, sensitivity, evidence, extractor_version, source_run_id,
        | version, status, created_at, updated_at, expires_at, deleted_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, '', 'semantic', 0.0, 1.0, 'personal', 'user_stated', 'tombstone-v1',
        |        NULL, ?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL)
        |ON CONFLICT (scope_kind, scope_key, memory_key) DO UPDATE SET
        |value_json = EXCLUDED.value_json, search_text = '', status = 'active',
        |version = GREATEST(zyblw_agent_core.agent_memories.version + 1, EXCLUDED.version),
        |updated_at = CURRENT_TIMESTAMP, deleted_at = NULL""".stripMargin
    )
    try
      bindScopeColumns(barrierStatement, db)
      barrierStatement.setString(6, MemoryStore.ScopeBarrierKey)
      barrierStatement.setString(7, nextMetaJson)
      barrierStatement.setLong(8, rowVersion)
      barrierStatement.executeUpdate()
      ()
    finally barrierStatement.close()

  private def bindScopeColumns(statement: PreparedStatement, db: MemoryScopeDb): Unit =
    statement.setString(1, db.kind)
    statement.setString(2, db.key)
    db.tenantId.fold(statement.setNull(3, java.sql.Types.VARCHAR))(statement.setString(3, _))
    db.userId.fold(statement.setNull(4, java.sql.Types.VARCHAR))(statement.setString(4, _))
    db.sessionId.fold(statement.setNull(5, java.sql.Types.OTHER))(statement.setObject(5, _))

  /** 插入一条不含正文的审计事实。
    *
    * actor 与 target 都拆成结构化列，后续可以按 tenant/user/action 建索引，而不需要解析 JSON。System actor 只保存 固定任务名；Authenticated
    * actor 只保存认证上下文中的 tenant/user，scopes 和 attributes 均不会持久化。
    */
  private def insertAudit(connection: Connection, record: MemoryAuditRecord): IO[StoreError, Unit] =
    jdbc("insert memory audit") {
      val statement = connection.prepareStatement(
        """INSERT INTO zyblw_agent_core.agent_memory_audit
          |(audit_id, action, actor_kind, actor_tenant_id, actor_user_id, actor_system_name,
          | target_scope_kind, target_scope_key, target_tenant_id, target_user_id, target_session_id,
          | memory_key_hash, expected_version, resulting_version, affected_count, reason_code, occurred_at)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT (audit_id) DO NOTHING""".stripMargin
      )
      try
        val target = scopeDb(record.target)
        statement.setObject(1, record.auditId)
        statement.setString(2, encodeAuditAction(record.action))
        record.actor match
          case MemoryAuditActor.Authenticated(tenantId, userId) =>
            statement.setString(3, "authenticated")
            setNullableString(statement, 4, tenantId)
            setNullableString(statement, 5, userId)
            statement.setNull(6, java.sql.Types.VARCHAR)
          case MemoryAuditActor.System(name) =>
            statement.setString(3, "system")
            statement.setNull(4, java.sql.Types.VARCHAR)
            statement.setNull(5, java.sql.Types.VARCHAR)
            statement.setString(6, name)
        statement.setString(7, target.kind)
        statement.setString(8, target.key)
        setNullableString(statement, 9, target.tenantId)
        setNullableString(statement, 10, target.userId)
        target.sessionId.fold(statement.setNull(11, java.sql.Types.OTHER))(statement.setObject(11, _))
        setNullableString(statement, 12, record.memoryKeyHash)
        record.expectedVersion.fold(statement.setNull(13, java.sql.Types.BIGINT))(statement.setLong(13, _))
        record.resultingVersion.fold(statement.setNull(14, java.sql.Types.BIGINT))(statement.setLong(14, _))
        statement.setLong(15, record.affectedCount)
        setNullableString(statement, 16, record.reasonCode)
        setInstant(statement, 17, record.occurredAt)
        statement.executeUpdate()
        ()
      finally statement.close()
    }

  /** MemoryAuditAction 的数据库稳定编码。 */
  private def encodeAuditAction(action: MemoryAuditAction): String = action match
    case MemoryAuditAction.Read           => "read"
    case MemoryAuditAction.List           => "list"
    case MemoryAuditAction.Search         => "search"
    case MemoryAuditAction.Correct        => "correct"
    case MemoryAuditAction.Delete         => "delete"
    case MemoryAuditAction.DeleteScope    => "delete_scope"
    case MemoryAuditAction.RetentionPurge => "retention_purge"
    case MemoryAuditAction.Export         => "export"

  /** 首次 CAS 创建；冲突返回 None，由调用方查询实际版本。 */
  private def insertFirst(
      connection: Connection,
      scope: MemoryScope,
      entry: MemoryEntry,
      now: Long
  ): IO[StoreError, Option[MemoryEntry]] = jdbc("insert first memory") {
    val statement = connection.prepareStatement(
      s"""INSERT INTO zyblw_agent_core.agent_memories
         |(scope_kind, scope_key, tenant_id, user_id, session_id, memory_key, value_json, search_text,
         | memory_kind, importance, confidence, sensitivity, evidence, extractor_version, source_run_id,
         | version, status, created_at, updated_at, expires_at, deleted_at)
         |VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'active', ?, ?, ?, NULL)
         |ON CONFLICT DO NOTHING
         |RETURNING $returningColumns""".stripMargin
    )
    try
      bindEntry(statement, scope, entry, now)
      val result = statement.executeQuery()
      if result.next() then Some(readEntry(result)) else None
    finally statement.close()
  }

  /** 用户在治理界面显式创建记忆 (expectedVersion = 0)：若不存在则新建，若原先已删除或已过期则重新激活并递增版本；若当前仍为未过期的 active 则返回 None 触发版本冲突。 */
  private def insertOrReviveForGovernance(
      connection: Connection,
      scope: MemoryScope,
      entry: MemoryEntry,
      now: Long
  ): IO[StoreError, Option[MemoryEntry]] = jdbc("insert or revive governed memory") {
    val statement = connection.prepareStatement(
      s"""INSERT INTO zyblw_agent_core.agent_memories
         |(scope_kind, scope_key, tenant_id, user_id, session_id, memory_key, value_json, search_text,
         | memory_kind, importance, confidence, sensitivity, evidence, extractor_version, source_run_id,
         | version, status, created_at, updated_at, expires_at, deleted_at)
         |VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'active', ?, ?, ?, NULL)
         |ON CONFLICT (scope_kind, scope_key, memory_key) DO UPDATE SET
         |value_json = EXCLUDED.value_json, search_text = EXCLUDED.search_text,
         |memory_kind = EXCLUDED.memory_kind, importance = EXCLUDED.importance,
         |confidence = EXCLUDED.confidence, sensitivity = EXCLUDED.sensitivity,
         |evidence = EXCLUDED.evidence, extractor_version = EXCLUDED.extractor_version,
         |source_run_id = EXCLUDED.source_run_id, version = zyblw_agent_core.agent_memories.version + 1,
         |status = 'active', created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at,
         |expires_at = EXCLUDED.expires_at, deleted_at = NULL
         |WHERE zyblw_agent_core.agent_memories.status = 'deleted'
         |   OR (zyblw_agent_core.agent_memories.expires_at IS NOT NULL AND zyblw_agent_core.agent_memories.expires_at <= EXCLUDED.updated_at)
         |RETURNING $returningColumns""".stripMargin
    )
    try
      bindEntry(statement, scope, entry, now)
      val result = statement.executeQuery()
      if result.next() then Some(readEntry(result)) else None
    finally statement.close()
  }

  /** 正版本 CAS 更新；WHERE version 是 fencing token。 */
  private def updateExpected(
      connection: Connection,
      scope: MemoryScope,
      expectedVersion: Long,
      entry: MemoryEntry,
      now: Long
  ): IO[StoreError, Option[MemoryEntry]] = jdbc("compare and set memory") {
    val statement = connection.prepareStatement(
      s"""UPDATE zyblw_agent_core.agent_memories SET
         |value_json = ?::jsonb, search_text = ?, memory_kind = ?, importance = ?, confidence = ?,
         |sensitivity = ?, evidence = ?, extractor_version = ?, source_run_id = ?, version = version + 1,
         |status = 'active', updated_at = ?, expires_at = ?, deleted_at = NULL
         |WHERE scope_kind = ? AND scope_key = ? AND memory_key = ? AND version = ?
         |RETURNING $returningColumns""".stripMargin
    )
    try
      val db      = scopeDb(scope)
      val updated = updateEpoch(entry, now)
      statement.setString(1, entry.value.toJson)
      statement.setString(2, searchText(entry))
      statement.setString(3, encodeKind(entry.kind))
      statement.setDouble(4, entry.importance)
      statement.setDouble(5, entry.confidence)
      statement.setString(6, encodeSensitivity(entry.sensitivity))
      statement.setString(7, encodeEvidence(entry.evidence))
      statement.setString(8, entry.extractorVersion)
      entry.sourceRunId match
        case Some(runId) => statement.setObject(9, runId.value)
        case None        => statement.setNull(9, java.sql.Types.OTHER)
      setInstant(statement, 10, Instant.ofEpochMilli(updated))
      entry.expiresAtEpochMilli match
        case Some(value) => setInstant(statement, 11, Instant.ofEpochMilli(value))
        case None        => statement.setNull(11, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
      statement.setString(12, db.kind)
      statement.setString(13, db.key)
      statement.setString(14, entry.key)
      statement.setLong(15, expectedVersion)
      val result = statement.executeQuery()
      if result.next() then Some(readEntry(result)) else None
    finally statement.close()
  }

  /** 读取 active 或 tombstone 的当前版本；不存在返回 0。 */
  private def currentVersion(connection: Connection, scope: MemoryScope, key: String): IO[StoreError, Long] =
    jdbc("read memory version") {
      val statement = connection.prepareStatement(
        "SELECT version FROM zyblw_agent_core.agent_memories WHERE scope_kind = ? AND scope_key = ? AND memory_key = ?"
      )
      try
        val db = scopeDb(scope)
        statement.setString(1, db.kind)
        statement.setString(2, db.key)
        statement.setString(3, key)
        val result = statement.executeQuery()
        if result.next() then result.getLong(1) else 0L
      finally statement.close()
    }

  /** 集中执行 MemoryEntry 查询与解码，避免不同 API 的列顺序漂移。 */
  private def selectEntries(
      connection: Connection,
      sql: String,
      bind: PreparedStatement => Unit,
      limitOne: Boolean
  ): IO[StoreError, Chunk[MemoryEntry]] = jdbc("select memory") {
    val statement = connection.prepareStatement(sql)
    try
      bind(statement)
      if limitOne then statement.setMaxRows(1)
      val result  = statement.executeQuery()
      val builder = ChunkBuilder.make[MemoryEntry]()
      while result.next() do builder += readEntry(result)
      builder.result()
    finally statement.close()
  }

  /** 插入参数的唯一绑定顺序，供 put 与首次 CAS 共用。 */
  private def bindEntry(
      statement: PreparedStatement,
      scope: MemoryScope,
      entry: MemoryEntry,
      now: Long
  ): Unit =
    val db      = scopeDb(scope)
    val updated = updateEpoch(entry, now)
    statement.setString(1, db.kind)
    statement.setString(2, db.key)
    db.tenantId.fold(statement.setNull(3, java.sql.Types.VARCHAR))(statement.setString(3, _))
    db.userId.fold(statement.setNull(4, java.sql.Types.VARCHAR))(statement.setString(4, _))
    db.sessionId.fold(statement.setNull(5, java.sql.Types.OTHER))(statement.setObject(5, _))
    statement.setString(6, entry.key)
    statement.setString(7, entry.value.toJson)
    statement.setString(8, searchText(entry))
    statement.setString(9, encodeKind(entry.kind))
    statement.setDouble(10, entry.importance)
    statement.setDouble(11, entry.confidence)
    statement.setString(12, encodeSensitivity(entry.sensitivity))
    statement.setString(13, encodeEvidence(entry.evidence))
    statement.setString(14, entry.extractorVersion)
    entry.sourceRunId.fold(statement.setNull(15, java.sql.Types.OTHER))(runId =>
      statement.setObject(15, runId.value)
    )
    setInstant(statement, 16, Instant.ofEpochMilli(entry.createdAtEpochMilli))
    setInstant(statement, 17, Instant.ofEpochMilli(updated))
    entry.expiresAtEpochMilli match
      case Some(value) => setInstant(statement, 18, Instant.ofEpochMilli(value))
      case None        => statement.setNull(18, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)

  /** ResultSet 解码包含枚举和 JSON 的 fail-closed 校验。 */
  private def readEntry(result: ResultSet): MemoryEntry =
    val value = result
      .getString(2)
      .fromJson[Json]
      .fold(
        error => throw IllegalStateException(s"memory value JSON 解码失败: $error"),
        identity
      )
    MemoryEntry(
      key = result.getString(1),
      value = value,
      importance = result.getDouble(3),
      sourceRunId = Option(result.getObject(4, classOf[UUID])).map(RunId(_)),
      createdAtEpochMilli = result.getObject(5, classOf[java.time.OffsetDateTime]).toInstant.toEpochMilli,
      expiresAtEpochMilli =
        Option(result.getObject(6, classOf[java.time.OffsetDateTime])).map(_.toInstant.toEpochMilli),
      kind = decodeKind(result.getString(7)),
      confidence = result.getDouble(8),
      sensitivity = decodeSensitivity(result.getString(9)),
      evidence = decodeEvidence(result.getString(10)),
      extractorVersion = result.getString(11),
      version = result.getLong(12),
      updatedAtEpochMilli = result.getObject(13, classOf[java.time.OffsetDateTime]).toInstant.toEpochMilli
    )

  /** JSON 长度与保留 key 防线；正文和完整对话不应进入长期记忆，且不允许业务写入保留屏障键。 */
  private def validateValue(entry: MemoryEntry): IO[StoreError, Unit] =
    if entry.key.trim == MemoryStore.ScopeBarrierKey then
      ZIO.fail(AgentError.MemoryPolicyRejected(entry.key, "reserved-memory-key"))
    else
      val length = entry.value.toJson.length
      if length <= maxValueCharacters then ZIO.unit
      else ZIO.fail(AgentError.MemoryPolicyRejected(entry.key, "value-too-large"))

  /** search_text 只含 key 与 JSON 值；删除时数据库将其清空。 */
  private def searchText(entry: MemoryEntry): String = s"${entry.key} ${entry.value.toJson}"

  /** updatedAt=0 表示让 Store 使用当前时间。 */
  private def updateEpoch(entry: MemoryEntry, now: Long): Long =
    if entry.updatedAtEpochMilli == 0L then now else entry.updatedAtEpochMilli

  /** 将 opaque scope 编码为无歧义 canonical key，并保留治理查询列。 */
  private def scopeDb(scope: MemoryScope): MemoryScopeDb = scope match
    case MemoryScope.Session(sessionId) =>
      MemoryScopeDb("session", sessionId.asString, None, None, Some(sessionId.value))
    case MemoryScope.User(tenantId, userId) =>
      val tenant = tenantId.value
      MemoryScopeDb(
        "user",
        s"${tenant.length}:$tenant:${userId.value}",
        Some(tenant),
        Some(userId.value),
        None
      )
    case MemoryScope.Tenant(tenantId) =>
      MemoryScopeDb("tenant", tenantId.value, Some(tenantId.value), None, None)

  /** MemoryKind 的稳定数据库编码。 */
  private def encodeKind(value: MemoryKind): String = value match
    case MemoryKind.Preference => "preference"
    case MemoryKind.Semantic   => "semantic"
    case MemoryKind.Episodic   => "episodic"
    case MemoryKind.Procedural => "procedural"

  /** 拒绝未知数据库枚举值。 */
  private def decodeKind(value: String): MemoryKind = value match
    case "preference" => MemoryKind.Preference
    case "semantic"   => MemoryKind.Semantic
    case "episodic"   => MemoryKind.Episodic
    case "procedural" => MemoryKind.Procedural
    case other        => throw IllegalStateException(s"未知 memory_kind: $other")

  /** MemorySensitivity 的稳定数据库编码。 */
  private def encodeSensitivity(value: MemorySensitivity): String = value.toString.toLowerCase
  private def decodeSensitivity(value: String): MemorySensitivity = value match
    case "public"    => MemorySensitivity.Public
    case "personal"  => MemorySensitivity.Personal
    case "sensitive" => MemorySensitivity.Sensitive
    case other       => throw IllegalStateException(s"未知 memory sensitivity: $other")

  /** MemoryEvidence 的稳定数据库编码。 */
  private def encodeEvidence(value: MemoryEvidence): String = value match
    case MemoryEvidence.UserStated    => "user_stated"
    case MemoryEvidence.ToolObserved  => "tool_observed"
    case MemoryEvidence.Imported      => "imported"
    case MemoryEvidence.ModelInferred => "model_inferred"

  private def decodeEvidence(value: String): MemoryEvidence = value match
    case "user_stated"    => MemoryEvidence.UserStated
    case "tool_observed"  => MemoryEvidence.ToolObserved
    case "imported"       => MemoryEvidence.Imported
    case "model_inferred" => MemoryEvidence.ModelInferred
    case other            => throw IllegalStateException(s"未知 memory evidence: $other")

  /** Scope 安全地借还 JDBC 连接，并保留 StoreError 类型。 */
  private def withConnection[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(
          ZIO
            .attemptBlocking(dataSource.getConnection)
            .mapError(error => AgentError.PersistenceFailure("获取 Memory 数据库连接失败", Some(error)))
        )(connection => ZIO.attemptBlocking(connection.close()).orDie)
        .flatMap(use)
    }

  /** 在单连接事务中执行治理变更。
    *
    * `uninterruptibleMask` 只保护 begin/commit/rollback 收尾，真正 SQL effect 使用 restore 保持可取消；若取消发生在 mutation 与
    * audit 之间，Exit 分支会先 rollback，再把原始中断 Cause 传播出去。
    */
  private def withTransaction[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    withConnection { connection =>
      for
        previous <- jdbc("read memory auto commit")(connection.getAutoCommit)
        _        <- jdbc("begin memory governance transaction")(connection.setAutoCommit(false))
        value    <- ZIO
          .uninterruptibleMask { restore =>
            restore(use(connection)).exit.flatMap {
              case Exit.Success(result) =>
                jdbc("commit memory governance transaction")(connection.commit())
                  .tapError(_ => jdbc("rollback memory governance transaction")(connection.rollback()).ignore)
                  .as(result)
              case Exit.Failure(cause) =>
                jdbc("rollback memory governance transaction")(connection.rollback()).ignore *> ZIO
                  .refailCause(
                    cause
                  )
            }
          }
          .ensuring(jdbc("restore memory auto commit")(connection.setAutoCommit(previous)).ignore)
      yield value
    }

  /** 在 blocking executor 执行 JDBC，并按 SQLSTATE 分类是否可重试。 */
  private def jdbc[A](operation: String)(effect: => A): IO[StoreError, A] =
    ZIO.attemptBlocking(effect).mapError(error => databaseError(operation, error))

  private def databaseError(operation: String, error: Throwable): StoreError = error match
    case sql: SQLException =>
      val state     = Option(sql.getSQLState).getOrElse("unknown")
      val retryable = state.startsWith("08") || state == "40001" || state == "40P01" || state == "57014"
      AgentError.DatabaseFailure(operation, state, retryable, Some(sql))
    case other => AgentError.PersistenceFailure(operation, Some(other))

  /** 以 UTC OffsetDateTime 写 PostgreSQL TIMESTAMPTZ。 */
  private def setInstant(statement: PreparedStatement, index: Int, value: Instant): Unit =
    statement.setObject(index, value.atOffset(ZoneOffset.UTC))

  /** JDBC nullable TEXT 的集中绑定，避免 Option.fold 在多个审计字段中重复。 */
  private def setNullableString(statement: PreparedStatement, index: Int, value: Option[String]): Unit =
    value.fold(statement.setNull(index, java.sql.Types.VARCHAR))(statement.setString(index, _))

  /** SELECT/RETURNING 列顺序的唯一来源。 */
  private val returningColumns =
    "memory_key, value_json::text, importance, source_run_id, created_at, expires_at, memory_kind, confidence, sensitivity, evidence, extractor_version, version, updated_at"

  private val entrySelect = s"SELECT $returningColumns FROM zyblw_agent_core.agent_memories"

/** JDBC 所需的 canonical scope 与可审计治理列。 */
final private case class MemoryScopeDb(
    kind: String,
    key: String,
    tenantId: Option[String],
    userId: Option[String],
    sessionId: Option[UUID]
)

/** 持久化在 `__scope_barrier__` 行中的作用域删除代数与各 key 历史最高删除版本。 */
final private case class PostgresScopeBarrierMeta(
    scopeGeneration: Long,
    tombstones: Map[String, Long] = Map.empty
) derives JsonCodec

object PostgresMemoryStore:
  /** 使用默认单条值上限构造 Layer。 */
  val layer: URLayer[DataSource, MemoryStore] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresMemoryStore(dataSource))

  /** 业务希望使用更严格上限时的显式 Layer。 */
  def configured(maxValueCharacters: Int): URLayer[DataSource, MemoryStore] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresMemoryStore(dataSource, maxValueCharacters))

  /** 同时暴露通用 MemoryStore 与事务性治理 Repository。
    *
    * 业务接入用户查看/纠正/删除 API 时应选择本 Layer；只做内部 Context 注入的服务可以继续使用 `layer`。
    */
  val governanceLayer: URLayer[DataSource, MemoryStore & MemoryGovernanceRepository] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresMemoryStore(dataSource))
