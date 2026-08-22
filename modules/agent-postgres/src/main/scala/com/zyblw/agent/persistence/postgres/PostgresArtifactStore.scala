package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.artifacts.*
import com.zyblw.agent.core.*
import java.sql.{Connection, ResultSet, SQLException, Types}
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL ArtifactStore。版本 append-only，删除/保留期与内存实现共享同一组不变量。
  *
  * 配置 `ArtifactBlobStore` 时 PostgreSQL 只保存元数据，`bytes` 列为 NULL，正文按 sha256 外置。
  */
final class PostgresArtifactStore(
    dataSource: DataSource,
    policy: ArtifactStorePolicy = ArtifactStorePolicy(),
    blobs: Option[ArtifactBlobStore] = None
) extends ArtifactStore:

  def save(
      scope: ArtifactScope,
      name: ArtifactName,
      input: ArtifactInput
  ): IO[StoreError, ArtifactDescriptor] =
    ArtifactStore.validatePublic(name, input, policy) *>
      Clock.instant.flatMap { now =>
        val digest = ArtifactStore.digestPublic(input.bytes)
        persistBlob(digest, input.bytes) *>
          withTransaction { connection =>
            jdbc("save artifact") {
              val (kind, key) = scopeColumns(scope)
              val current     = latestVersion(connection, kind, key, name.value)
              if current.isEmpty && countNames(connection, kind, key) >= policy.maxArtifactsPerScope then
                throw Reject(name.value, "scope-artifact-limit")
              val nextVersion = current.getOrElse(0L) + 1L
              val descriptor  = ArtifactDescriptor(
                scope,
                name,
                nextVersion,
                input.mediaType.trim.toLowerCase(java.util.Locale.ROOT),
                input.bytes.length.toLong,
                digest,
                now,
                input.metadata
              )
              upsertArtifact(connection, kind, key, name.value, nextVersion, now, current.isEmpty)
              insertVersion(connection, descriptor, input.bytes)
              insertAudit(connection, ArtifactAuditAction.Save, scope, name, Some(nextVersion), "append", now)
              descriptor
            }.catchSome {
              case AgentError.PersistenceFailure(message, _) if message.startsWith("reject:") =>
                ZIO.fail(AgentError.ArtifactPolicyRejected(name.value, message.stripPrefix("reject:")))
            }
          }
      }

  def read(
      scope: ArtifactScope,
      name: ArtifactName,
      version: Option[Long]
  ): IO[StoreError, Option[Artifact]] =
    if version.exists(_ <= 0L) then ZIO.fail(AgentError.ArtifactPolicyRejected(name.value, "invalid-version"))
    else
      Clock.instant.flatMap { now =>
        withTransaction { connection =>
          jdbc("read artifact") {
            val (kind, key) = scopeColumns(scope)
            val found       = load(connection, kind, key, name.value, version)
            insertAudit(
              connection,
              ArtifactAuditAction.Read,
              scope,
              name,
              found.map(_.descriptor.version),
              if found.isEmpty then "miss" else "hit",
              now
            )
            found
          }.flatMap(resolveArtifact)
        }
      }

  def list(scope: ArtifactScope, limit: Int): IO[StoreError, Chunk[ArtifactDescriptor]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else
      withConnection { connection =>
        jdbc("list artifacts") {
          val (kind, key) = scopeColumns(scope)
          val statement   = connection.prepareStatement(
            """SELECT v.scope_kind, v.scope_key, v.name, v.version, v.media_type, v.byte_size,
              |       v.sha256, v.metadata_json::text, v.created_at
              |FROM agent_artifact_versions v
              |JOIN agent_artifacts a
              |  ON a.scope_kind = v.scope_kind AND a.scope_key = v.scope_key AND a.name = v.name
              | AND a.latest_version = v.version
              |WHERE v.scope_kind = ? AND v.scope_key = ?
              |ORDER BY v.name
              |LIMIT ?""".stripMargin
          )
          try
            statement.setString(1, kind)
            statement.setString(2, key)
            statement.setInt(3, limit)
            val result = statement.executeQuery()
            try Chunk.fromIterator(Iterator.continually(result).takeWhile(_.next()).map(readDescriptor))
            finally result.close()
          finally statement.close()
        }
      }

  def delete(scope: ArtifactScope, name: ArtifactName, version: Long): IO[StoreError, Long] =
    if version <= 0L then ZIO.fail(AgentError.ArtifactPolicyRejected(name.value, "invalid-version"))
    else
      Clock.instant.flatMap { now =>
        withTransaction { connection =>
          jdbc("delete artifact version") {
            val (kind, key) = scopeColumns(scope)
            latestVersion(connection, kind, key, name.value) match
              case Some(latest) if latest == version =>
                throw Reject(name.value, "cannot-delete-latest")
              case _ =>
                val deleted = deleteVersion(connection, kind, key, name.value, version)
                insertAudit(
                  connection,
                  ArtifactAuditAction.Delete,
                  scope,
                  name,
                  Some(version),
                  if deleted == 0 then "missing" else "user-requested",
                  now
                )
                deleted.toLong
          }.catchSome {
            case AgentError.PersistenceFailure(message, _) if message.startsWith("reject:") =>
              ZIO.fail(AgentError.ArtifactPolicyRejected(name.value, message.stripPrefix("reject:")))
          }
        }
      }

  def purgeExpired(cutoff: Instant, limit: Int): IO[StoreError, Long] =
    if limit <= 0 then ZIO.succeed(0L)
    else
      Clock.instant.flatMap { now =>
        withTransaction { connection =>
          jdbc("purge expired artifacts") {
            val statement = connection.prepareStatement(
              """SELECT v.scope_kind, v.scope_key, v.name, v.version
                |FROM agent_artifact_versions v
                |JOIN agent_artifacts a
                |  ON a.scope_kind = v.scope_kind AND a.scope_key = v.scope_key AND a.name = v.name
                |WHERE v.version <> a.latest_version AND v.created_at <= ?
                |ORDER BY v.created_at, v.scope_kind, v.scope_key, v.name, v.version
                |LIMIT ?""".stripMargin
            )
            val victims =
              try
                statement.setObject(1, cutoff.atOffset(ZoneOffset.UTC))
                statement.setInt(2, limit)
                val result = statement.executeQuery()
                try
                  Iterator
                    .continually(result)
                    .takeWhile(_.next())
                    .map { row =>
                      (
                        row.getString("scope_kind"),
                        row.getString("scope_key"),
                        row.getString("name"),
                        row.getLong("version")
                      )
                    }
                    .toList
                finally result.close()
              finally statement.close()
            victims.foreach { case (kind, key, name, version) =>
              deleteVersion(connection, kind, key, name, version)
              val scope = decodeScope(kind, key)
              insertAudit(
                connection,
                ArtifactAuditAction.Purge,
                scope,
                ArtifactName(name),
                Some(version),
                "expired",
                now
              )
            }
            victims.length.toLong
          }
        }
      }

  def audits(limit: Int): IO[StoreError, Chunk[ArtifactAuditRecord]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else
      withConnection { connection =>
        jdbc("list artifact audits") {
          val statement = connection.prepareStatement(
            """SELECT action, scope_kind, scope_key, name_hash, version, reason_code, occurred_at
              |FROM agent_artifact_audit
              |ORDER BY occurred_at DESC, audit_id DESC
              |LIMIT ?""".stripMargin
          )
          try
            statement.setInt(1, limit)
            val result = statement.executeQuery()
            try
              Chunk.fromIterator(
                Iterator
                  .continually(result)
                  .takeWhile(_.next())
                  .map { row =>
                    ArtifactAuditRecord(
                      decodeAction(row.getString("action")),
                      decodeScope(row.getString("scope_kind"), row.getString("scope_key")),
                      row.getString("name_hash"),
                      Option(row.getObject("version")).map(_ => row.getLong("version")),
                      row.getString("reason_code"),
                      row.getTimestamp("occurred_at").toInstant
                    )
                  }
              )
            finally result.close()
          finally statement.close()
        }
      }

  private def upsertArtifact(
      connection: Connection,
      kind: String,
      key: String,
      name: String,
      version: Long,
      now: Instant,
      insert: Boolean
  ): Unit =
    val sql =
      if insert then
        """INSERT INTO agent_artifacts(scope_kind, scope_key, name, latest_version, created_at, updated_at)
          |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin
      else """UPDATE agent_artifacts SET latest_version = ?, updated_at = ?
          |WHERE scope_kind = ? AND scope_key = ? AND name = ?""".stripMargin
    val statement = connection.prepareStatement(sql)
    try
      if insert then
        statement.setString(1, kind)
        statement.setString(2, key)
        statement.setString(3, name)
        statement.setLong(4, version)
        statement.setObject(5, now.atOffset(ZoneOffset.UTC))
        statement.setObject(6, now.atOffset(ZoneOffset.UTC))
      else
        statement.setLong(1, version)
        statement.setObject(2, now.atOffset(ZoneOffset.UTC))
        statement.setString(3, kind)
        statement.setString(4, key)
        statement.setString(5, name)
      val _ = statement.executeUpdate()
    finally statement.close()

  private def insertVersion(
      connection: Connection,
      descriptor: ArtifactDescriptor,
      bytes: Chunk[Byte]
  ): Unit =
    val statement = connection.prepareStatement(
      """INSERT INTO agent_artifact_versions(
        |  scope_kind, scope_key, name, version, media_type, byte_size, sha256, metadata_json, bytes, created_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""".stripMargin
    )
    try
      val (kind, key) = scopeColumns(descriptor.scope)
      statement.setString(1, kind)
      statement.setString(2, key)
      statement.setString(3, descriptor.name.value)
      statement.setLong(4, descriptor.version)
      statement.setString(5, descriptor.mediaType)
      statement.setLong(6, descriptor.byteSize)
      statement.setString(7, descriptor.sha256)
      statement.setString(8, descriptor.metadata.toJson)
      if blobs.isDefined then statement.setNull(9, Types.BINARY)
      else statement.setBytes(9, bytes.toArray)
      statement.setObject(10, descriptor.createdAt.atOffset(ZoneOffset.UTC))
      val _ = statement.executeUpdate()
    finally statement.close()

  private def insertAudit(
      connection: Connection,
      action: ArtifactAuditAction,
      scope: ArtifactScope,
      name: ArtifactName,
      version: Option[Long],
      reason: String,
      now: Instant
  ): Unit =
    val statement = connection.prepareStatement(
      """INSERT INTO agent_artifact_audit(
        |  audit_id, action, scope_kind, scope_key, name_hash, version, reason_code, occurred_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try
      val (kind, key) = scopeColumns(scope)
      statement.setObject(1, UUID.randomUUID())
      statement.setString(2, encodeAction(action))
      statement.setString(3, kind)
      statement.setString(4, key)
      statement.setString(5, ArtifactStore.hashName(name))
      version match
        case Some(value) => statement.setLong(6, value)
        case None        => statement.setNull(6, Types.BIGINT)
      statement.setString(7, reason)
      statement.setObject(8, now.atOffset(ZoneOffset.UTC))
      val _ = statement.executeUpdate()
    finally statement.close()

  private def latestVersion(connection: Connection, kind: String, key: String, name: String): Option[Long] =
    val statement = connection.prepareStatement(
      "SELECT latest_version FROM agent_artifacts WHERE scope_kind = ? AND scope_key = ? AND name = ?"
    )
    try
      statement.setString(1, kind)
      statement.setString(2, key)
      statement.setString(3, name)
      val result = statement.executeQuery()
      try if result.next() then Some(result.getLong(1)) else None
      finally result.close()
    finally statement.close()

  private def countNames(connection: Connection, kind: String, key: String): Int =
    val statement = connection.prepareStatement(
      "SELECT count(*) FROM agent_artifacts WHERE scope_kind = ? AND scope_key = ?"
    )
    try
      statement.setString(1, kind)
      statement.setString(2, key)
      val result = statement.executeQuery()
      try if result.next() then result.getInt(1) else 0
      finally result.close()
    finally statement.close()

  private def load(
      connection: Connection,
      kind: String,
      key: String,
      name: String,
      version: Option[Long]
  ): Option[LoadedVersion] =
    val sql = version match
      case Some(_) =>
        """SELECT scope_kind, scope_key, name, version, media_type, byte_size, sha256,
          |       metadata_json::text, bytes, created_at
          |FROM agent_artifact_versions
          |WHERE scope_kind = ? AND scope_key = ? AND name = ? AND version = ?""".stripMargin
      case None =>
        """SELECT v.scope_kind, v.scope_key, v.name, v.version, v.media_type, v.byte_size, v.sha256,
          |       v.metadata_json::text, v.bytes, v.created_at
          |FROM agent_artifact_versions v
          |JOIN agent_artifacts a
          |  ON a.scope_kind = v.scope_kind AND a.scope_key = v.scope_key AND a.name = v.name
          | AND a.latest_version = v.version
          |WHERE v.scope_kind = ? AND v.scope_key = ? AND v.name = ?""".stripMargin
    val statement = connection.prepareStatement(sql)
    try
      statement.setString(1, kind)
      statement.setString(2, key)
      statement.setString(3, name)
      version.foreach(statement.setLong(4, _))
      val result = statement.executeQuery()
      try
        if result.next() then
          val descriptor = readDescriptor(result)
          Some(LoadedVersion(descriptor, Option(result.getBytes("bytes"))))
        else None
      finally result.close()
    finally statement.close()

  private def deleteVersion(
      connection: Connection,
      kind: String,
      key: String,
      name: String,
      version: Long
  ): Int =
    val statement = connection.prepareStatement(
      "DELETE FROM agent_artifact_versions WHERE scope_kind = ? AND scope_key = ? AND name = ? AND version = ?"
    )
    try
      statement.setString(1, kind)
      statement.setString(2, key)
      statement.setString(3, name)
      statement.setLong(4, version)
      statement.executeUpdate()
    finally statement.close()

  private def readDescriptor(result: ResultSet): ArtifactDescriptor =
    ArtifactDescriptor(
      decodeScope(result.getString("scope_kind"), result.getString("scope_key")),
      ArtifactName(result.getString("name")),
      result.getLong("version"),
      result.getString("media_type"),
      result.getLong("byte_size"),
      result.getString("sha256"),
      result.getTimestamp("created_at").toInstant,
      result.getString("metadata_json").fromJson[Map[String, String]].getOrElse(Map.empty)
    )

  /** User scope 使用与 MemoryStore 相同的长度前缀编码。PostgreSQL TEXT 不能保存 NUL，因此禁止 `\u0000` 分隔。 */
  private def scopeColumns(scope: ArtifactScope): (String, String) = scope match
    case ArtifactScope.Session(sessionId)     => "session" -> sessionId.asString
    case ArtifactScope.User(tenantId, userId) =>
      val tenant = tenantId.value
      "user" -> s"${tenant.length}:$tenant:${userId.value}"

  private def decodeScope(kind: String, key: String): ArtifactScope = kind match
    case "session" => ArtifactScope.Session(SessionId(UUID.fromString(key)))
    case "user"    =>
      val separator = key.indexOf(':')
      if separator <= 0 then throw IllegalStateException(s"malformed user artifact scope: $kind")
      val length = key.substring(0, separator).toInt
      val tenant = key.substring(separator + 1, separator + 1 + length)
      val user   = key.substring(separator + 1 + length + 1)
      ArtifactScope.User(TenantId(tenant), UserId(user))
    case other => throw IllegalStateException(s"unknown artifact scope: $other")

  private def encodeAction(action: ArtifactAuditAction): String = action match
    case ArtifactAuditAction.Save   => "save"
    case ArtifactAuditAction.Read   => "read"
    case ArtifactAuditAction.Delete => "delete"
    case ArtifactAuditAction.Purge  => "purge"

  private def decodeAction(value: String): ArtifactAuditAction = value match
    case "save"   => ArtifactAuditAction.Save
    case "read"   => ArtifactAuditAction.Read
    case "delete" => ArtifactAuditAction.Delete
    case "purge"  => ArtifactAuditAction.Purge
    case other    => throw IllegalStateException(s"unknown artifact audit action: $other")

  private def withConnection[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(
          ZIO
            .attemptBlocking(dataSource.getConnection)
            .mapError(error => AgentError.PersistenceFailure("获取 Artifact 数据库连接失败", Some(error)))
        )(connection => ZIO.attemptBlocking(connection.close()).orDie)
        .flatMap(use)
    }

  private def withTransaction[A](use: Connection => IO[StoreError, A]): IO[StoreError, A] =
    withConnection { connection =>
      for
        previous <- jdbc("read auto commit")(connection.getAutoCommit)
        _        <- jdbc("begin artifact transaction")(connection.setAutoCommit(false))
        result   <- ZIO
          .uninterruptibleMask { restore =>
            restore(use(connection)).exit.flatMap {
              case Exit.Success(value) => jdbc("commit artifact transaction")(connection.commit()).as(value)
              case Exit.Failure(cause) =>
                jdbc("rollback artifact transaction")(connection.rollback()).ignore *> ZIO.refailCause(cause)
            }
          }
          .ensuring(jdbc("restore auto commit")(connection.setAutoCommit(previous)).ignore)
      yield result
    }

  private def persistBlob(digest: String, bytes: Chunk[Byte]): IO[StoreError, Unit] =
    blobs match
      case Some(store) => store.put(digest, bytes)
      case None        => ZIO.unit

  private def resolveArtifact(loaded: Option[LoadedVersion]): IO[StoreError, Option[Artifact]] =
    loaded match
      case None          => ZIO.none
      case Some(version) =>
        version.inlineBytes match
          case Some(bytes) => ZIO.some(Artifact(version.descriptor, Chunk.fromArray(bytes)))
          case None        =>
            blobs match
              case Some(store) =>
                store.get(version.descriptor.sha256).flatMap {
                  case Some(bytes) => ZIO.some(Artifact(version.descriptor, bytes))
                  case None        =>
                    ZIO.fail(AgentError.PersistenceFailure("artifact-blob-missing"))
                }
              case None =>
                ZIO.fail(AgentError.PersistenceFailure("artifact-bytes-missing"))

  private def jdbc[A](operation: String)(effect: => A): IO[StoreError, A] =
    ZIO.attemptBlocking(effect).mapError {
      case Reject(name, reason) => AgentError.ArtifactPolicyRejected(name, reason)
      case sql: SQLException    =>
        val state = Option(sql.getSQLState).getOrElse("unknown")
        AgentError.DatabaseFailure(operation, state, state.startsWith("08"), Some(sql))
      case other => AgentError.PersistenceFailure(operation, Some(other))
    }

  final private case class LoadedVersion(descriptor: ArtifactDescriptor, inlineBytes: Option[Array[Byte]])

  final private case class Reject(name: String, reason: String) extends RuntimeException(s"reject:$reason")

object PostgresArtifactStore:
  val layer: URLayer[DataSource, ArtifactStore] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresArtifactStore(dataSource))

  val layerWithBlobs: URLayer[DataSource & ArtifactBlobStore, ArtifactStore] =
    ZLayer {
      for
        dataSource <- ZIO.service[DataSource]
        blobs      <- ZIO.service[ArtifactBlobStore]
      yield PostgresArtifactStore(dataSource, ArtifactStorePolicy(), Some(blobs))
    }
