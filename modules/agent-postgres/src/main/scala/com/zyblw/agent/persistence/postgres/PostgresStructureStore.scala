package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.sql.Connection
import javax.sql.DataSource
import zio.*
import zio.json.*

/** 单语句原子保存/读取。模型或 Embedding 调用不会进入数据库事务。 */
final class PostgresStructureStore(dataSource: DataSource) extends StructureStore:
  private val table = s"${KnowledgeSql.Schema}.agent_knowledge_structures"

  def put(snapshot: StructureSnapshot): IO[RetrievalError, Unit] =
    ZIO.fromEither(snapshot.validate).mapError(AgentError.RetrievalFailed(_)) *> connection { c =>
      val json = snapshot.toJson
      require(
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16 * 1024 * 1024,
        "structure size limit"
      )
      val b   = snapshot.binding
      val sql = s"""INSERT INTO $table AS existing
        |(tenant_id, knowledge_space_id, profile_id, document_id, index_version, structure_profile_id, generation, chunk_set_sha256, snapshot)
        |SELECT d.tenant_id, d.knowledge_space_id, d.profile_id, d.document_id, d.index_version, ?, ?, ?, ?::jsonb
        |FROM ${KnowledgeSql.Documents} d
        |WHERE d.tenant_id = ? AND d.knowledge_space_id = ? AND d.profile_id = ? AND d.document_id = ? AND d.index_version = ?
        |AND d.ingestion_id = ? AND d.source_revision_id = ? AND d.structure_sha256 = ?
        |AND (d.status = 'building' OR (d.status = 'ready' AND d.active AND d.chunk_set_sha256 = ?))
        |ON CONFLICT (tenant_id, knowledge_space_id, profile_id, document_id, index_version, structure_profile_id)
        |DO UPDATE SET generation = existing.generation WHERE existing.generation = EXCLUDED.generation
        |RETURNING generation""".stripMargin
      val statement = c.prepareStatement(sql)
      try
        statement.setString(1, snapshot.spec.profileId)
        statement.setString(2, snapshot.generation)
        statement.setString(3, b.chunkSetSha256)
        statement.setString(4, json)
        statement.setString(5, b.tenantId)
        statement.setString(6, b.spaceId)
        statement.setString(7, b.chunkProfileId)
        statement.setString(8, b.documentId)
        statement.setLong(9, b.indexVersion)
        statement.setString(10, b.ingestionId)
        statement.setString(11, b.sourceRevisionId)
        statement.setString(12, b.structureSha256)
        statement.setString(13, b.chunkSetSha256)
        val result = statement.executeQuery()
        try require(result.next(), "structure manifest/generation conflict")
        finally result.close()
      finally statement.close()
    }

  def get(
      documentId: String,
      indexVersion: Long,
      spec: StructureBuildSpec,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    read(documentId, indexVersion, spec, scope, false)

  override def getForBuild(
      base: StructureSnapshot,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    read(base.binding.documentId, base.binding.indexVersion, base.spec, scope, true)

  private def read(
      documentId: String,
      indexVersion: Long,
      spec: StructureBuildSpec,
      scope: RetrievalScope,
      allowBuilding: Boolean
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    scope.pinnedProfileId match
      case None          => ZIO.succeed(None)
      case Some(profile) =>
        connection { c =>
          val sql =
            s"""SELECT s.snapshot::text, s.generation, d.ingestion_id, d.source_revision_id, d.structure_sha256, s.chunk_set_sha256
          |FROM $table s JOIN ${KnowledgeSql.Documents} d
          |USING (tenant_id, knowledge_space_id, profile_id, document_id, index_version)
          |WHERE s.tenant_id = ? AND s.knowledge_space_id = ? AND s.profile_id = ? AND s.document_id = ?
          |AND s.index_version = ? AND s.structure_profile_id = ?
          |AND d.permissions <@ ?::text[] AND ((d.status = 'ready' AND d.active AND d.chunk_set_sha256 = s.chunk_set_sha256)
          |OR (${if allowBuilding then "TRUE" else "FALSE"} AND d.status = 'building'))""".stripMargin
          val statement   = c.prepareStatement(sql)
          val permissions = c.createArrayOf("text", scope.permissions.toArray.map(_.asInstanceOf[Object]))
          try
            statement.setString(1, scope.tenantId.value)
            statement.setString(2, scope.spaceId.value)
            statement.setString(3, profile.value)
            statement.setString(4, documentId)
            statement.setLong(5, indexVersion)
            statement.setString(6, spec.profileId)
            statement.setArray(7, permissions)
            val result = statement.executeQuery()
            try
              if !result.next() then None
              else
                val snapshot = result
                  .getString(1)
                  .fromJson[StructureSnapshot]
                  .fold(_ => throw IllegalStateException("invalid structure JSON"), identity)
                val binding = StructureBinding(
                  scope.tenantId.value,
                  scope.spaceId.value,
                  documentId,
                  profile.value,
                  indexVersion,
                  result.getString(3),
                  result.getString(4),
                  result.getString(5),
                  result.getString(6)
                )
                require(
                  snapshot.binding == binding && snapshot.spec == spec && snapshot.generation == result
                    .getString(2) && snapshot.validate.isRight,
                  "structure persisted identity mismatch"
                )
                Some(snapshot)
            finally result.close()
          finally
            statement.close()
            permissions.free()
        }

  override def getActive(
      documentId: String,
      spec: StructureBuildSpec,
      scope: RetrievalScope
  ): IO[RetrievalError, Option[StructureSnapshot]] =
    scope.pinnedProfileId match
      case None          => ZIO.succeed(None)
      case Some(profile) =>
        connection { c =>
          val statement = c.prepareStatement(
            s"SELECT index_version FROM ${KnowledgeSql.Documents} WHERE tenant_id = ? AND knowledge_space_id = ? AND profile_id = ? AND document_id = ? AND active AND status = 'ready' AND permissions <@ ?::text[]"
          )
          val permissions = c.createArrayOf("text", scope.permissions.toArray.map(_.asInstanceOf[Object]))
          try
            statement.setString(1, scope.tenantId.value)
            statement.setString(2, scope.spaceId.value)
            statement.setString(3, profile.value)
            statement.setString(4, documentId)
            statement.setArray(5, permissions)
            val result = statement.executeQuery()
            try if result.next() then Some(result.getLong(1)) else None
            finally result.close()
          finally
            statement.close()
            permissions.free()
        }.flatMap {
          case Some(version) => get(documentId, version, spec, scope)
          case None          => ZIO.succeed(None)
        }

  private def connection[A](use: Connection => A): IO[RetrievalError, A] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(ZIO.attemptBlocking(dataSource.getConnection))(c =>
            ZIO.attemptBlocking(c.close()).ignore
          )
          .flatMap(c => ZIO.attemptBlocking(use(c)))
      }
      .mapError(error => KnowledgeManifestRow.databaseError("structure store", error))

object PostgresStructureStore:
  val layer: URLayer[DataSource, StructureStore] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresStructureStore(dataSource))
