package com.zyblw.agent.persistence.postgres

import com.zyblw.agent.admin.CursorTime
import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Timestamp}
import javax.sql.DataSource
import zio.*
import zio.json.*

/** PostgreSQL/pgvector 知识索引版本库。
  *
  * 慢速 Embedding 调用发生在 `KnowledgeIndexer` 中，不占用这里的数据库事务。`stage` 使用可重放 upsert，`activate` 通过知识空间与文档级 advisory
  * transaction lock 把“校验块集合摘要、废弃旧版本、替换正式块、切 active manifest”放进同一短事务，因此查询只能看到旧完整版本或新完整版本。
  *
  * 文档键为 `(tenant, space, document)`；Profile 写入规则与内存实现一致：active/building 可写，superseded 只接受显式
  * `targetProfileId`，终止状态封存，构建规格摘要必须与 Profile 一致（数据库复合外键同样强制）。
  *
  * @param dataSource
  *   宿主共享连接池；框架不创建隐藏连接池
  * @param dimension
  *   optional pgvector migration 的固定 `vector(N)` 维度
  */
final class PostgresKnowledgeIndexStore(dataSource: DataSource, dimension: Int) extends KnowledgeIndexStore:
  import KnowledgeSql.*
  require(dimension > 0, "PostgreSQL knowledge dimension 必须为正数")

  /** 分配文档版本；同一 ingestionId 的失败版本会清空暂存区并恢复 Building，已被替代或下线的旧 ingestion 不允许复活。 */
  def begin(request: BeginKnowledgeIndex): IO[RetrievalError, KnowledgeIndexBuild] =
    if request.buildSpec.embeddingDimension != dimension then
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"Embedding 维度 ${request.buildSpec.embeddingDimension} != PostgreSQL 索引维度 $dimension"
        )
      )
    else
      withTransaction { connection =>
        for
          _        <- lockSpace(connection, request.key.tenantId, request.key.knowledgeSpaceId)
          _        <- lockDocument(connection, request.key)
          existing <- selectByIngestion(connection, request.key, request.ingestionId, forUpdate = true)
          build    <- existing match
            case Some(manifest) if !sameRequest(manifest, request) =>
              ZIO.fail(
                AgentError.RetrievalFailed(s"knowledge ingestionId 已绑定不同请求: ${request.key.documentId}")
              )
            case Some(manifest) if manifest.status == KnowledgeIndexStatus.Superseded =>
              ZIO.fail(AgentError.RetrievalFailed(s"knowledge ingestion 已被较新版本替代: ${request.key.documentId}"))
            case Some(manifest) if manifest.status == KnowledgeIndexStatus.Retired =>
              ZIO.fail(AgentError.RetrievalFailed(s"knowledge ingestion 已被下线: ${request.key.documentId}"))
            case Some(manifest) if manifest.status == KnowledgeIndexStatus.Failed =>
              ensureWritableProfile(
                connection,
                request.key,
                manifest.build.profileId,
                request.buildSpec,
                explicit = true
              ) *>
                clearStaging(connection, manifest.build) *>
                updateStatus(connection, manifest.build, "building", active = false, None) *>
                ZIO.succeed(manifest.build)
            case Some(manifest) => ZIO.succeed(manifest.build)
            case None           =>
              isWithdrawn(connection, request.key, request.lineage.sourceRevisionId).flatMap { withdrawn =>
                if withdrawn then
                  ZIO.fail(
                    AgentError.RetrievalFailed(
                      s"knowledge 来源修订已撤回，不能重新索引: ${request.key.documentId}@${request.lineage.sourceRevisionId}"
                    )
                  )
                else createBuild(connection, request)
              }
        yield build
      }

  /** 幂等写入一个暂存批次；相同 chunkId 的重试覆盖暂存值，不会累计重复行。 */
  def stage(build: KnowledgeIndexBuild, chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit] =
    validateStagedChunks(build, chunks) *> withTransaction { connection =>
      for
        manifest <- selectExact(connection, build, forUpdate = true)
          .someOrFail(AgentError.RetrievalFailed("knowledge build 不存在"))
        _ <- ensureSameBuild(manifest.build, build)
        _ <- ZIO
          .fail(AgentError.RetrievalFailed(s"knowledge build 状态不允许暂存: ${manifest.status}"))
          .unless(manifest.status == KnowledgeIndexStatus.Building)
        _ <- insertStaging(connection, build, chunks)
        _ <- touchBuild(connection, build)
      yield ()
    }

  /** 原子发布一个完整版本；暂存块集合摘要必须与 `expected` 逐字一致。 */
  def activate(
      build: KnowledgeIndexBuild,
      expected: ChunkSetDigest
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    withTransaction { connection =>
      for
        _        <- lockSpace(connection, build.key.tenantId, build.knowledgeSpaceId)
        _        <- lockDocument(connection, build.key)
        manifest <- selectExact(connection, build, forUpdate = true)
          .someOrFail(AgentError.RetrievalFailed("knowledge build 不存在"))
        _      <- ensureSameBuild(manifest.build, build)
        result <-
          if manifest.status == KnowledgeIndexStatus.Ready && manifest.active then ZIO.succeed(manifest)
          else if manifest.status != KnowledgeIndexStatus.Building then
            ZIO.fail(AgentError.RetrievalFailed(s"knowledge build 状态不允许发布: ${manifest.status}"))
          else
            for
              withdrawn <- isWithdrawn(connection, build.key, build.lineage.sourceRevisionId)
              _         <- ZIO.fail(AgentError.RetrievalFailed("knowledge 来源修订已撤回，不能发布")).when(withdrawn)
              _         <- ensureWritableProfile(
                connection,
                build.key,
                build.profileId,
                build.buildSpec,
                explicit = true
              )
              ready <- publishBuilding(connection, build, expected)
            yield ready
      yield result
    }

  /** 只把仍为 Building 的版本标记失败；迟到清理不能覆盖已经 Ready 的版本。 */
  def markFailed(build: KnowledgeIndexBuild, failureCode: String): IO[RetrievalError, Unit] =
    val safeCode = if failureCode.matches("[A-Za-z0-9_.-]{1,64}") then failureCode else "unknown"
    withConnection { connection =>
      update(
        connection,
        "mark failed",
        s"""UPDATE $Documents
           |SET status = 'failed', active = FALSE, failure_code = ?, updated_at = now()
           |WHERE $VersionKey AND status = 'building'""".stripMargin
      ) { statement =>
        statement.setString(1, safeCode)
        bindVersionKey(statement, 2, build.key, build.version)
      }.unit
    }

  /** 文档在空间 active Profile 中的 active manifest；唯一部分索引保证至多一行。 */
  def active(key: KnowledgeDocumentKey): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    withConnection(connection => selectActiveManifest(connection, key, forUpdate = false))

  /** 按幂等键读取任意状态 manifest，供 worker 崩溃恢复。 */
  def find(
      key: KnowledgeDocumentKey,
      ingestionId: String
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    withConnection(connection => selectByIngestion(connection, key, ingestionId, forUpdate = false))

  /** 乐观下线：前置版本匹配时，文档在空间内全部 Profile 的 active 版本及其正式块一起下线。
    *
    * 前置版本取 active Profile 中的版本；文档只存在于并行 Profile 时取其最新 active 版本。相同版本已 Retired 时幂等返回。
    */
  def retire(
      key: KnowledgeDocumentKey,
      expectedActiveVersion: Long
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    if expectedActiveVersion <= 0L then ZIO.fail(AgentError.RetrievalFailed("expectedActiveVersion 必须为正数"))
    else
      withTransaction { connection =>
        for
          _       <- lockDocument(connection, key)
          primary <- selectActiveManifest(connection, key, forUpdate = true)
          copies  <- selectMany(
            connection,
            s"$ManifestSelect WHERE $DocumentKeyD AND d.active FOR UPDATE OF d"
          )(
            bindDocumentKey(_, 1, key)
          )
          governing = primary.orElse(copies.maxByOption(_.build.version))
          retired <- governing match
            case Some(manifest) if manifest.build.version != expectedActiveVersion =>
              ZIO.fail(AgentError.RetrievalFailed("knowledge retire active version 前置条件失败"))
            case Some(_) =>
              for
                _ <- update(
                  connection,
                  "delete published chunks",
                  s"DELETE FROM $Chunks WHERE $DocumentKey"
                )(bindDocumentKey(_, 1, key))
                _ <- update(
                  connection,
                  "retire manifests",
                  s"""UPDATE $Documents SET status = 'retired', active = FALSE, updated_at = now()
                     |WHERE $DocumentKey AND active""".stripMargin
                )(bindDocumentKey(_, 1, key))
                value <- selectVersion(connection, key, expectedActiveVersion, forUpdate = false)
                  .someOrFail(AgentError.RetrievalFailed("retire 后 manifest 丢失"))
              yield value
            case None =>
              selectVersion(connection, key, expectedActiveVersion, forUpdate = true).flatMap {
                case Some(manifest) if manifest.status == KnowledgeIndexStatus.Retired =>
                  ZIO.succeed(manifest)
                case _ => ZIO.fail(AgentError.RetrievalFailed("knowledge retire 目标不是当前 active 版本"))
              }
        yield retired
      }

  /** 使用稳定顺序和 SKIP LOCKED 有界删除非活动终态 manifest；staging/chunks 由外键级联清理。 */
  def purgeInactive(
      updatedBefore: java.time.Instant,
      limit: Int,
      legalHold: Set[KnowledgeDocumentKey] = Set.empty
  ): IO[RetrievalError, Long] =
    if limit <= 0 then ZIO.succeed(0L)
    else
      withTransaction { connection =>
        val held = legalHold.toVector
        update(
          connection,
          "purge inactive manifests",
          s"""WITH held AS (
             |  SELECT * FROM unnest(?::text[], ?::text[], ?::text[]) AS h(tenant_id, knowledge_space_id, document_id)
             |), candidates AS (
             |  SELECT d.tenant_id, d.knowledge_space_id, d.document_id, d.index_version
             |  FROM $Documents d
             |  WHERE d.active = FALSE
             |    AND d.status IN ('superseded', 'failed', 'retired')
             |    AND d.updated_at < ?
             |    AND NOT EXISTS (
             |      SELECT 1 FROM held h
             |      WHERE h.tenant_id = d.tenant_id AND h.knowledge_space_id = d.knowledge_space_id
             |        AND h.document_id = d.document_id
             |    )
             |  ORDER BY d.updated_at, d.tenant_id, d.knowledge_space_id, d.document_id, d.index_version
             |  FOR UPDATE SKIP LOCKED
             |  LIMIT ?
             |)
             |DELETE FROM $Documents document
             |USING candidates candidate
             |WHERE document.tenant_id = candidate.tenant_id
             |  AND document.knowledge_space_id = candidate.knowledge_space_id
             |  AND document.document_id = candidate.document_id
             |  AND document.index_version = candidate.index_version""".stripMargin
        ) { statement =>
          statement.setArray(1, textArray(connection, held.map(_.tenantId.value)))
          statement.setArray(2, textArray(connection, held.map(_.knowledgeSpaceId.value)))
          statement.setArray(3, textArray(connection, held.map(_.documentId)))
          statement.setTimestamp(4, Timestamp.from(updatedBefore))
          statement.setInt(5, limit)
        }.map(_.toLong)
      }

  override def setCheckpoint(
      build: KnowledgeIndexBuild,
      checkpoint: IngestCheckpoint
  ): IO[RetrievalError, Unit] =
    withConnection { connection =>
      update(
        connection,
        "set ingest checkpoint",
        s"""UPDATE $Documents
           |SET metadata = metadata || jsonb_build_object('ingest.checkpoint', ?::text), updated_at = now()
           |WHERE $VersionKey""".stripMargin
      ) { statement =>
        statement.setString(1, checkpoint.toString)
        bindVersionKey(statement, 2, build.key, build.version)
      }.unit
    }

  override def resolveActiveProfile(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId
  ): IO[RetrievalError, Option[IndexProfileId]] =
    withConnection(connection =>
      readSpace(connection, tenantId, spaceId, forUpdate = false).map(_.flatMap(_._1))
    )

  override def activateProfile(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId,
      expectedRevision: Long,
      reason: String,
      publication: Option[ProfilePublication] = None
  ): IO[RetrievalError, Long] =
    for
      gate <- ZIO
        .fromOption(publication)
        .orElseFail(AgentError.RetrievalFailed("Profile publication evidence is required"))
      _ <- ZIO
        .fail(AgentError.RetrievalFailed("Invalid profile activation reason"))
        .unless(reason.matches("[A-Za-z0-9._:-]{1,160}"))
      revision <- withTransaction { connection =>
        for
          _     <- lockSpace(connection, tenantId, spaceId)
          space <- readSpace(connection, tenantId, spaceId, forUpdate = true)
            .someOrFail(AgentError.RetrievalFailed("knowledge space does not exist"))
          (activeProfileId, actualRevision) = space
          _ <- ZIO
            .fail(AgentError.RetrievalFailed("knowledge space profile CAS 失败"))
            .unless(actualRevision == expectedRevision)
          target <- readProfile(connection, tenantId, spaceId, profileId)
            .someOrFail(AgentError.RetrievalFailed("activateProfile 目标 Profile 不存在"))
          _ <- ZIO
            .fail(AgentError.RetrievalFailed(s"activateProfile 目标 Profile 已终止: ${target._1}"))
            .when(SealedProfileStatuses.contains(target._1))
          documents <- profileManifests(connection, tenantId, spaceId, profileId, forUpdate = true)
          _         <- ZIO.fromEither(gate.validate(documents))
          _         <- activeProfileId.filter(_ != profileId) match
            case Some(activeId) =>
              profileManifests(connection, tenantId, spaceId, activeId, forUpdate = true).flatMap { active =>
                val diff = ProfilePublication.corpusDiff(active, documents)
                ZIO
                  .fail(
                    AgentError.RetrievalFailed(
                      s"Target profile corpus does not match the active profile (missing=${diff.missing.length}, " +
                        s"stale=${diff.stale.length}, extra=${diff.extra.length}, notReady=${diff.notReady.length})"
                    )
                  )
                  .unless(diff.isEmpty)
              }
            case None => ZIO.unit
          _ <- insertAudit(
            connection,
            tenantId,
            spaceId,
            activeProfileId,
            profileId,
            expectedRevision,
            reason,
            Some(gate)
          )
          _ <- ZIO.foreachDiscard(activeProfileId.filter(_ != profileId)) { previous =>
            update(
              connection,
              "supersede previous profile",
              s"""UPDATE $Profiles SET status = 'superseded'
                 |WHERE tenant_id = ? AND knowledge_space_id = ? AND profile_id = ? AND status = 'active'""".stripMargin
            )(bindProfileKey(_, 1, tenantId, spaceId, previous)).flatMap(count =>
              ZIO
                .fail(AgentError.RetrievalFailed("Active profile state changed during cutover"))
                .when(count != 1)
            )
          }
          _ <- update(
            connection,
            "record profile publication",
            s"""UPDATE $Profiles
               |SET status = 'active', activated_at = now(), failure_code = NULL,
               |    publication_evaluation_id = ?, publication_census_sha256 = ?, publication_document_count = ?
               |WHERE tenant_id = ? AND knowledge_space_id = ? AND profile_id = ?
               |  AND status IN ('building', 'active', 'superseded')""".stripMargin
          ) { statement =>
            statement.setString(1, gate.evaluationId)
            statement.setString(2, gate.evaluatedCensusSha256)
            statement.setInt(3, gate.documents.length)
            bindProfileKey(statement, 4, tenantId, spaceId, profileId)
          }.flatMap(count =>
            ZIO.fail(AgentError.RetrievalFailed("Profile is not publishable")).when(count != 1)
          )
          _ <- casSpacePointer(connection, tenantId, spaceId, profileId, expectedRevision)
        yield expectedRevision + 1L
      }
    yield revision

  override def profileCorpusDiff(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      target: IndexProfileId
  ): IO[RetrievalError, ProfileCorpusDiff] =
    withConnection { connection =>
      for
        space  <- readSpace(connection, tenantId, spaceId, forUpdate = false)
        active <- space.flatMap(_._1) match
          case Some(activeId) => profileManifests(connection, tenantId, spaceId, activeId, forUpdate = false)
          case None           => ZIO.succeed(Chunk.empty)
        targetManifests <- profileManifests(connection, tenantId, spaceId, target, forUpdate = false)
      yield ProfilePublication.corpusDiff(active, targetManifests)
    }

  /** 撤回一个来源修订：写墓碑、删除该修订的正式块、下线其全部构建。该键下从未出现过此修订时失败。 */
  override def withdraw(key: KnowledgeDocumentKey, sourceRevisionId: String): IO[RetrievalError, Unit] =
    Option(sourceRevisionId).map(_.trim).filter(_.nonEmpty) match
      case None           => ZIO.fail(AgentError.RetrievalFailed("sourceRevisionId 不能为空"))
      case Some(revision) =>
        withTransaction { connection =>
          def bindRevision(statement: PreparedStatement): Unit =
            val next = bindDocumentKey(statement, 1, key)
            statement.setString(next, revision)
          for
            _     <- lockDocument(connection, key)
            known <- query(
              connection,
              "check withdrawn revision",
              s"SELECT EXISTS (SELECT 1 FROM $Documents WHERE $DocumentKey AND source_revision_id = ?)"
            )(bindRevision)(result => result.next() && result.getBoolean(1))
            _ <- ZIO
              .fail(AgentError.RetrievalFailed(s"knowledge withdraw 来源修订不存在: ${key.documentId}@$revision"))
              .unless(known)
            _ <- update(
              connection,
              "insert withdrawal tombstone",
              s"""INSERT INTO $Withdrawn (tenant_id, knowledge_space_id, document_id, source_revision_id)
                 |VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""".stripMargin
            )(bindRevision)
            _ <- update(
              connection,
              "delete withdrawn chunks",
              s"DELETE FROM $Chunks WHERE $DocumentKey AND source_revision_id = ?"
            )(bindRevision)
            _ <- update(
              connection,
              "delete withdrawn staging",
              s"""DELETE FROM $Staging s USING $Documents d
                 |WHERE s.tenant_id = d.tenant_id AND s.knowledge_space_id = d.knowledge_space_id
                 |  AND s.document_id = d.document_id AND s.index_version = d.index_version
                 |  AND $DocumentKeyD AND d.source_revision_id = ?""".stripMargin
            )(bindRevision)
            _ <- update(
              connection,
              "retire withdrawn builds",
              s"""UPDATE $Documents
                 |SET status = 'retired', active = FALSE, failure_code = NULL, updated_at = now()
                 |WHERE $DocumentKey AND source_revision_id = ? AND status IN ('building', 'ready', 'failed')""".stripMargin
            )(bindRevision)
          yield ()
        }

  /** 在文档级 advisory lock 下校验 active 前置条件、决定写入 Profile 并插入 Building manifest。 */
  private def createBuild(
      connection: Connection,
      request: BeginKnowledgeIndex
  ): IO[RetrievalError, KnowledgeIndexBuild] =
    for
      current <- selectActiveManifest(connection, request.key, forUpdate = false).map(_.map(_.build.version))
      _       <- ZIO
        .fail(AgentError.RetrievalFailed(s"knowledge active version 前置条件失败: ${request.key.documentId}"))
        .unless(matchesExpectation(request.expectation, current))
      assignment <- assignWriteProfile(connection, request)
      next       <- query(
        connection,
        "next version",
        s"SELECT COALESCE(max(index_version), 0) + 1 FROM $Documents WHERE $DocumentKey"
      )(bindDocumentKey(_, 1, request.key)) { result =>
        if !result.next() then throw IllegalStateException("next version returned no row")
        result.getLong(1)
      }
      build = KnowledgeIndexBuild(
        request.key,
        next,
        request.ingestionId,
        request.lineage,
        request.buildSpec,
        assignment._1,
        assignment._2
      )
      _ <- update(
        connection,
        "insert manifest",
        s"""INSERT INTO $Documents
           |(tenant_id, knowledge_space_id, profile_id, document_id, index_version, ingestion_id, source_uri,
           | source_id, source_revision_id, source_sha256, source_media_type, parser_id, artifact_sha256,
           | structure_sha256, text_sha256, build_spec_sha256, permissions, metadata, status, active, chunk_count)
           |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'building', FALSE, 0)""".stripMargin
      ) { statement =>
        val lineage = request.lineage
        statement.setString(1, request.key.tenantId.value)
        statement.setString(2, request.key.knowledgeSpaceId.value)
        statement.setString(3, assignment._1.value)
        statement.setString(4, request.key.documentId)
        statement.setLong(5, next)
        statement.setString(6, request.ingestionId)
        statement.setString(7, request.sourceUri)
        statement.setString(8, lineage.sourceId)
        statement.setString(9, lineage.sourceRevisionId)
        statement.setString(10, lineage.sourceSha256)
        statement.setString(11, lineage.sourceMediaType)
        statement.setString(12, lineage.parserId)
        statement.setString(13, lineage.artifactSha256)
        statement.setString(14, lineage.structureSha256)
        statement.setString(15, lineage.textSha256)
        statement.setString(16, request.buildSpec.sha256)
        statement.setArray(17, textArray(connection, request.permissions.toVector.sorted))
        statement.setString(
          18,
          (request.metadata + ("ingest.casActiveProfile" -> assignment._2.toString)).toJson
        )
      }
    yield build

  /** 按空间指针与 incoming 规格决定写入哪个 Profile；必要时创建 building Profile。返回 (profileId, casActiveProfile)。 */
  private def assignWriteProfile(
      connection: Connection,
      request: BeginKnowledgeIndex
  ): IO[RetrievalError, (IndexProfileId, Boolean)] =
    val tenant = request.key.tenantId
    val space  = request.key.knowledgeSpaceId
    for
      _ <- update(
        connection,
        "ensure knowledge space",
        s"""INSERT INTO $Spaces (tenant_id, knowledge_space_id, active_profile_id, revision)
           |VALUES (?, ?, NULL, 0) ON CONFLICT (tenant_id, knowledge_space_id) DO NOTHING""".stripMargin
      ) { statement =>
        statement.setString(1, tenant.value)
        statement.setString(2, space.value)
      }
      activeId   <- readSpace(connection, tenant, space, forUpdate = false).map(_.flatMap(_._1))
      activeSpec <- ZIO.foreach(activeId)(id => readProfile(connection, tenant, space, id).map(_.map(_._2)))
      plan = request.targetProfileId match
        case Some(forced) if activeId.isEmpty          => ProfileWritePlan.CreateAndActivate(forced)
        case Some(forced) if activeId.contains(forced) => ProfileWritePlan.UseActive(forced)
        case Some(forced)                              => ProfileWritePlan.BuildParallel(forced)
        case None                                      =>
          ProfileWritePlan.decide(
            activeId.map(id => id -> activeSpec.flatten.getOrElse("")),
            request.buildSpec
          )
      assignment = plan match
        case ProfileWritePlan.CreateAndActivate(id) => id -> true
        case ProfileWritePlan.UseActive(id)         => id -> false
        case ProfileWritePlan.BuildParallel(id)     => id -> false
      _ <- ensureWritableProfile(
        connection,
        request.key,
        assignment._1,
        request.buildSpec,
        request.targetProfileId.nonEmpty
      )
      _ <- update(
        connection,
        "create building profile",
        s"""INSERT INTO $Profiles
           |(tenant_id, knowledge_space_id, profile_id, profile_version, status, build_spec, build_spec_sha256)
           |SELECT ?, ?, ?, COALESCE(max(profile_version), 0) + 1, 'building', ?::jsonb, ?
           |FROM $Profiles WHERE tenant_id = ? AND knowledge_space_id = ?
           |ON CONFLICT (tenant_id, knowledge_space_id, profile_id) DO NOTHING""".stripMargin
      ) { statement =>
        bindProfileKey(statement, 1, tenant, space, assignment._1)
        statement.setString(4, request.buildSpec.toJson)
        statement.setString(5, request.buildSpec.sha256)
        statement.setString(6, tenant.value)
        statement.setString(7, space.value)
      }
    yield assignment

  /** Profile 写入规则：active/building 可写；superseded 仅接受显式目标；终止状态封存；规格必须一致。 */
  private def ensureWritableProfile(
      connection: Connection,
      key: KnowledgeDocumentKey,
      profileId: IndexProfileId,
      spec: IndexBuildSpec,
      explicit: Boolean
  ): IO[RetrievalError, Unit] =
    readProfile(connection, key.tenantId, key.knowledgeSpaceId, profileId).flatMap {
      case None                                         => ZIO.unit
      case Some((_, specSha)) if specSha != spec.sha256 =>
        ZIO.fail(AgentError.RetrievalFailed(s"Profile 构建规格不一致: ${profileId.value}"))
      case Some((status, _)) if SealedProfileStatuses(status) =>
        ZIO.fail(AgentError.RetrievalFailed(s"Profile 已终止，不能写入: ${profileId.value}"))
      case Some(("superseded", _)) if !explicit =>
        ZIO.fail(
          AgentError.RetrievalFailed(
            s"Profile 已被替代；补齐回滚目标必须显式指定 targetProfileId: ${profileId.value}"
          )
        )
      case Some(_) => ZIO.unit
    }

  /** 校验暂存块集合摘要并执行正式快照替换；调用方已经持有空间锁、文档锁和 manifest 行锁。 */
  private def publishBuilding(
      connection: Connection,
      build: KnowledgeIndexBuild,
      expected: ChunkSetDigest
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    for
      actual <- query(
        connection,
        "digest staging",
        s"""SELECT count(*),
           |       encode(sha256(convert_to(COALESCE(string_agg(
           |         chunk_id || E'\\t' || display_sha256 || E'\\t' || dense_sha256 || E'\\t' || lexical_sha256,
           |         E'\\n' ORDER BY chunk_id COLLATE "C"), ''), 'UTF8')), 'hex')
           |FROM $Staging WHERE $VersionKey""".stripMargin
      )(bindVersionKey(_, 1, build.key, build.version)) { result =>
        if !result.next() then throw IllegalStateException("digest staging returned no row")
        result.getInt(1) -> result.getString(2)
      }
      _ <- ZIO
        .fail(
          AgentError.RetrievalFailed(
            s"knowledge staged chunk 集合不匹配: 暂存 ${actual._1} 块，期望 ${expected.count} 块"
          )
        )
        .unless(actual._1 == expected.count && actual._2 == expected.sha256)
      _ <- update(
        connection,
        "supersede active manifest",
        s"""UPDATE $Documents SET status = 'superseded', active = FALSE, updated_at = now()
           |WHERE $DocumentKey AND profile_id = ? AND active AND index_version <> ?""".stripMargin
      ) { statement =>
        val next = bindDocumentKey(statement, 1, build.key)
        statement.setString(next, build.profileId.value)
        statement.setLong(next + 1, build.version)
      }
      _ <- update(
        connection,
        "delete old published chunks",
        s"DELETE FROM $Chunks WHERE $DocumentKey AND profile_id = ?"
      ) { statement =>
        val next = bindDocumentKey(statement, 1, build.key)
        statement.setString(next, build.profileId.value)
      }
      inserted <- update(
        connection,
        "publish staged chunks",
        s"""INSERT INTO $Chunks
           |(tenant_id, knowledge_space_id, profile_id, document_id, source_revision_id, chunk_id, index_version,
           | chunk_text, search_text, dense_text, display_sha256, dense_sha256, lexical_sha256,
           | source_uri, permissions, metadata, embedding, sparse_embedding, parent_id, lineage_ordinal,
           | previous_chunk_id, next_chunk_id, heading_path, page_numbers, origins, block_ids)
           |SELECT s.tenant_id, s.knowledge_space_id, s.profile_id, s.document_id, d.source_revision_id, s.chunk_id,
           |       s.index_version, s.chunk_text, s.search_text, s.dense_text,
           |       s.display_sha256, s.dense_sha256, s.lexical_sha256,
           |       s.source_uri, s.permissions, s.metadata, s.embedding, s.sparse_embedding, s.parent_id, s.lineage_ordinal,
           |       s.previous_chunk_id, s.next_chunk_id, s.heading_path, s.page_numbers, s.origins, s.block_ids
           |FROM $Staging s
           |JOIN $Documents d
           |  ON d.tenant_id = s.tenant_id AND d.knowledge_space_id = s.knowledge_space_id
           | AND d.profile_id = s.profile_id AND d.document_id = s.document_id AND d.index_version = s.index_version
           |WHERE s.tenant_id = ? AND s.knowledge_space_id = ? AND s.document_id = ? AND s.index_version = ?""".stripMargin
      )(bindVersionKey(_, 1, build.key, build.version))
      _ <- ZIO
        .fail(AgentError.RetrievalFailed(s"published chunk count $inserted != ${expected.count}"))
        .unless(inserted == expected.count)
      _ <- update(
        connection,
        "mark manifest ready",
        s"""UPDATE $Documents
           |SET status = 'ready', active = TRUE, failure_code = NULL, chunk_count = ?, chunk_set_sha256 = ?,
           |    updated_at = now()
           |WHERE $VersionKey""".stripMargin
      ) { statement =>
        statement.setInt(1, expected.count)
        statement.setString(2, expected.sha256)
        bindVersionKey(statement, 3, build.key, build.version)
      }.flatMap(count =>
        ZIO.fail(AgentError.RetrievalFailed("manifest update affected no row")).when(count != 1)
      )
      _     <- clearStaging(connection, build)
      _     <- bootstrapActiveProfile(connection, build)
      ready <- selectExact(connection, build, forUpdate = false)
        .someOrFail(AgentError.RetrievalFailed("发布后 manifest 丢失"))
    yield ready

  /** 空间尚无 active Profile 时，首个带 CAS 标记的发布原子激活其 Profile。
    *
    * 并发 bootstrap 的第二个文档会看到指针已存在，直接作为普通文档发布，而不是尝试再次激活。
    */
  private def bootstrapActiveProfile(
      connection: Connection,
      build: KnowledgeIndexBuild
  ): IO[RetrievalError, Unit] =
    if !build.casActiveProfile then ZIO.unit
    else
      readSpace(connection, build.key.tenantId, build.knowledgeSpaceId, forUpdate = true).flatMap {
        case Some((None, revision)) =>
          for
            count <- update(
              connection,
              "bootstrap profile status",
              s"""UPDATE $Profiles SET status = 'active', activated_at = now()
                 |WHERE tenant_id = ? AND knowledge_space_id = ? AND profile_id = ? AND status = 'building'""".stripMargin
            )(bindProfileKey(_, 1, build.key.tenantId, build.knowledgeSpaceId, build.profileId))
            _ <- ZIO.fail(AgentError.RetrievalFailed("Initial profile is not publishable")).when(count != 1)
            _ <- insertAudit(
              connection,
              build.key.tenantId,
              build.knowledgeSpaceId,
              None,
              build.profileId,
              revision,
              "ingest-bootstrap",
              None
            )
            _ <- casSpacePointer(
              connection,
              build.key.tenantId,
              build.knowledgeSpaceId,
              build.profileId,
              revision
            )
          yield ()
        case _ => ZIO.unit
      }

  private def casSpacePointer(
      connection: Connection,
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId,
      expectedRevision: Long
  ): IO[RetrievalError, Unit] =
    update(
      connection,
      "cas space pointer",
      s"""UPDATE $Spaces SET active_profile_id = ?, revision = revision + 1, updated_at = now()
         |WHERE tenant_id = ? AND knowledge_space_id = ? AND revision = ?""".stripMargin
    ) { statement =>
      statement.setString(1, profileId.value)
      statement.setString(2, tenantId.value)
      statement.setString(3, spaceId.value)
      statement.setLong(4, expectedRevision)
    }.flatMap(count =>
      ZIO.fail(AgentError.RetrievalFailed("knowledge space profile CAS 失败")).when(count != 1)
    ).unit

  private def insertAudit(
      connection: Connection,
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      oldProfile: Option[IndexProfileId],
      newProfile: IndexProfileId,
      expectedRevision: Long,
      reason: String,
      publication: Option[ProfilePublication]
  ): IO[RetrievalError, Unit] =
    update(
      connection,
      "insert activation audit",
      s"""INSERT INTO $Audit
         |(tenant_id, knowledge_space_id, old_profile_id, new_profile_id, expected_space_revision, reason,
         | evaluation_id, evaluated_census_sha256, document_count)
         |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    ) { statement =>
      statement.setString(1, tenantId.value)
      statement.setString(2, spaceId.value)
      statement.setString(3, oldProfile.map(_.value).orNull)
      statement.setString(4, newProfile.value)
      statement.setLong(5, expectedRevision)
      statement.setString(6, reason)
      statement.setString(7, publication.map(_.evaluationId).orNull)
      statement.setString(8, publication.map(_.evaluatedCensusSha256).orNull)
      publication.fold(statement.setNull(9, java.sql.Types.INTEGER))(value =>
        statement.setInt(9, value.documents.length)
      )
    }.unit

  /** 批量 upsert 暂存向量；调用时 manifest 已在同一事务内锁定。 */
  private def insertStaging(
      connection: Connection,
      build: KnowledgeIndexBuild,
      chunks: Chunk[IndexedChunk]
  ): IO[RetrievalError, Unit] =
    if chunks.isEmpty then ZIO.unit
    else
      jdbc("stage chunks") {
        val statement = connection.prepareStatement(
          s"""INSERT INTO $Staging
             |(tenant_id, knowledge_space_id, profile_id, document_id, index_version, chunk_id, chunk_text, search_text,
             | source_uri, permissions, metadata, embedding, sparse_embedding, parent_id, lineage_ordinal,
             | previous_chunk_id, next_chunk_id, heading_path, page_numbers, origins, block_ids,
             | dense_text, display_sha256, dense_sha256, lexical_sha256)
             |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::public.vector, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
             |ON CONFLICT (tenant_id, knowledge_space_id, document_id, index_version, chunk_id) DO UPDATE SET
             |chunk_text = EXCLUDED.chunk_text,
             |search_text = EXCLUDED.search_text,
             |dense_text = EXCLUDED.dense_text,
             |display_sha256 = EXCLUDED.display_sha256,
             |dense_sha256 = EXCLUDED.dense_sha256,
             |lexical_sha256 = EXCLUDED.lexical_sha256,
             |source_uri = EXCLUDED.source_uri,
             |permissions = EXCLUDED.permissions,
             |metadata = EXCLUDED.metadata,
             |embedding = EXCLUDED.embedding,
             |sparse_embedding = EXCLUDED.sparse_embedding,
             |parent_id = EXCLUDED.parent_id,
             |lineage_ordinal = EXCLUDED.lineage_ordinal,
             |previous_chunk_id = EXCLUDED.previous_chunk_id,
             |next_chunk_id = EXCLUDED.next_chunk_id,
             |heading_path = EXCLUDED.heading_path,
             |page_numbers = EXCLUDED.page_numbers,
             |origins = EXCLUDED.origins,
             |block_ids = EXCLUDED.block_ids,
             |updated_at = now()""".stripMargin
        )
        try
          chunks.foreach { indexed =>
            val chunk = indexed.chunk
            statement.setString(1, build.key.tenantId.value)
            statement.setString(2, build.knowledgeSpaceId.value)
            statement.setString(3, build.profileId.value)
            statement.setString(4, build.key.documentId)
            statement.setLong(5, build.version)
            statement.setString(6, chunk.id)
            statement.setString(7, chunk.displayText)
            statement.setString(8, chunk.searchText.getOrElse(chunk.displayText))
            statement.setString(9, chunk.sourceUri)
            statement.setArray(10, textArray(connection, chunk.permissions.toVector.sorted))
            statement.setString(11, chunk.metadata.toJson)
            statement.setString(12, vectorLiteral(indexed.embedding))
            statement.setString(13, indexed.sparse.map(encodeSparse).orNull)
            bindLineage(statement, connection, 14, chunk.lineage)
            bindRepresentations(statement, 22, chunk)
            statement.addBatch()
          }
          statement.executeBatch()
          ()
        finally statement.close()
      }

  /** 在进入数据库前验证所有暂存块，避免半个 JDBC batch 才发现归属或维度错误。 */
  private def validateStagedChunks(
      build: KnowledgeIndexBuild,
      chunks: Chunk[IndexedChunk]
  ): IO[RetrievalError, Unit] =
    chunks.find { indexed =>
      val chunk = indexed.chunk
      chunk.tenantId != build.key.tenantId ||
      chunk.documentId != build.key.documentId ||
      chunk.catalogVersion != build.version ||
      !ChunkSetDigest.validChunkId(chunk.id) ||
      chunk.permissions.isEmpty || chunk.permissions.size > KnowledgeIndexer.MaxPermissions ||
      indexed.embedding.values.length != dimension
    } match
      case Some(value) =>
        ZIO.fail(AgentError.RetrievalFailed(s"knowledge staged chunk 契约不匹配: ${value.chunk.id}"))
      case None => ZIO.unit

  private def clearStaging(connection: Connection, build: KnowledgeIndexBuild): IO[RetrievalError, Unit] =
    update(connection, "clear staging", s"DELETE FROM $Staging WHERE $VersionKey")(
      bindVersionKey(_, 1, build.key, build.version)
    ).unit

  private def updateStatus(
      connection: Connection,
      build: KnowledgeIndexBuild,
      status: String,
      active: Boolean,
      failureCode: Option[String]
  ): IO[RetrievalError, Unit] =
    update(
      connection,
      "update manifest status",
      s"""UPDATE $Documents
         |SET status = ?, active = ?, failure_code = ?, chunk_count = 0, chunk_set_sha256 = NULL, updated_at = now()
         |WHERE $VersionKey""".stripMargin
    ) { statement =>
      statement.setString(1, status)
      statement.setBoolean(2, active)
      statement.setString(3, failureCode.orNull)
      bindVersionKey(statement, 4, build.key, build.version)
    }.flatMap(count =>
      ZIO.fail(AgentError.RetrievalFailed("manifest update affected no row")).when(count != 1)
    ).unit

  private def touchBuild(connection: Connection, build: KnowledgeIndexBuild): IO[RetrievalError, Unit] =
    update(
      connection,
      "touch build",
      s"UPDATE $Documents SET updated_at = now() WHERE $VersionKey AND status = 'building'"
    )(bindVersionKey(_, 1, build.key, build.version))
      .flatMap(count =>
        ZIO.fail(AgentError.RetrievalFailed("building manifest touch affected no row")).when(count != 1)
      )
      .unit

  private def lockSpace(
      connection: Connection,
      tenant: TenantId,
      space: KnowledgeSpaceId
  ): IO[RetrievalError, Unit] =
    advisoryLock(connection, s"knowledge-space:${tenant.value.length}:${tenant.value}:${space.value}")

  /** 文档锁 key 使用长度前缀避免简单字符串拼接碰撞。 */
  private def lockDocument(connection: Connection, key: KnowledgeDocumentKey): IO[RetrievalError, Unit] =
    val tenant = key.tenantId.value
    val space  = key.knowledgeSpaceId.value
    advisoryLock(
      connection,
      s"knowledge-document:${tenant.length}:$tenant:${space.length}:$space:${key.documentId}"
    )

  private def advisoryLock(connection: Connection, key: String): IO[RetrievalError, Unit] =
    jdbc("advisory lock") {
      val statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
      try
        statement.setString(1, key)
        statement.execute()
        ()
      finally statement.close()
    }

  private def isWithdrawn(
      connection: Connection,
      key: KnowledgeDocumentKey,
      revision: String
  ): IO[RetrievalError, Boolean] =
    query(
      connection,
      "check tombstone",
      s"SELECT EXISTS (SELECT 1 FROM $Withdrawn WHERE $DocumentKey AND source_revision_id = ?)"
    ) { statement =>
      val next = bindDocumentKey(statement, 1, key)
      statement.setString(next, revision)
    }(result => result.next() && result.getBoolean(1))

  /** 空间 active 指针与 revision；空间不存在时为 None。 */
  private def readSpace(
      connection: Connection,
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[(Option[IndexProfileId], Long)]] =
    query(
      connection,
      "read knowledge space",
      s"SELECT active_profile_id, revision FROM $Spaces WHERE tenant_id = ? AND knowledge_space_id = ?${
          if forUpdate then " FOR UPDATE" else ""
        }"
    ) { statement =>
      statement.setString(1, tenantId.value)
      statement.setString(2, spaceId.value)
    } { result =>
      if result.next() then
        Some(Option(result.getString(1)).filter(_.trim.nonEmpty).map(IndexProfileId(_)) -> result.getLong(2))
      else None
    }

  /** Profile 状态与构建规格摘要。 */
  private def readProfile(
      connection: Connection,
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId
  ): IO[RetrievalError, Option[(String, String)]] =
    query(
      connection,
      "read profile",
      s"SELECT status, build_spec_sha256 FROM $Profiles WHERE tenant_id = ? AND knowledge_space_id = ? AND profile_id = ?"
    )(bindProfileKey(_, 1, tenantId, spaceId, profileId)) { result =>
      if result.next() then Some(result.getString(1) -> result.getString(2)) else None
    }

  private def profileManifests(
      connection: Connection,
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId,
      forUpdate: Boolean
  ): IO[RetrievalError, Chunk[KnowledgeIndexManifest]] =
    selectMany(
      connection,
      s"""$ManifestSelect
         |WHERE d.tenant_id = ? AND d.knowledge_space_id = ? AND d.profile_id = ?${
          if forUpdate then " FOR UPDATE OF d" else ""
        }""".stripMargin
    )(bindProfileKey(_, 1, tenantId, spaceId, profileId))

  private def selectByIngestion(
      connection: Connection,
      key: KnowledgeDocumentKey,
      ingestionId: String,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    selectOne(
      connection,
      s"$ManifestSelect WHERE $DocumentKeyD AND d.ingestion_id = ?${
          if forUpdate then " FOR UPDATE OF d" else ""
        }"
    ) { statement =>
      val next = bindDocumentKey(statement, 1, key)
      statement.setString(next, ingestionId)
    }

  private def selectExact(
      connection: Connection,
      build: KnowledgeIndexBuild,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    selectVersion(connection, build.key, build.version, forUpdate)

  private def selectVersion(
      connection: Connection,
      key: KnowledgeDocumentKey,
      version: Long,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    selectOne(
      connection,
      s"$ManifestSelect WHERE $DocumentKeyD AND d.index_version = ?${
          if forUpdate then " FOR UPDATE OF d" else ""
        }"
    ) { statement =>
      val next = bindDocumentKey(statement, 1, key)
      statement.setLong(next, version)
    }

  /** 文档在空间 active Profile 中的 active manifest。 */
  private def selectActiveManifest(
      connection: Connection,
      key: KnowledgeDocumentKey,
      forUpdate: Boolean
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    selectOne(
      connection,
      s"""$ManifestSelect
         |JOIN $Spaces s
         |  ON s.tenant_id = d.tenant_id AND s.knowledge_space_id = d.knowledge_space_id
         | AND s.active_profile_id = d.profile_id
         |WHERE $DocumentKeyD AND d.active${if forUpdate then " FOR UPDATE OF d" else ""}""".stripMargin
    )(bindDocumentKey(_, 1, key))

  private def selectOne(connection: Connection, sql: String)(
      bind: PreparedStatement => Any
  ): IO[RetrievalError, Option[KnowledgeIndexManifest]] =
    selectMany(connection, sql)(bind).flatMap { rows =>
      if rows.length > 1 then ZIO.fail(AgentError.RetrievalFailed("knowledge manifest 查询返回多行"))
      else ZIO.succeed(rows.headOption)
    }

  private def selectMany(connection: Connection, sql: String)(
      bind: PreparedStatement => Any
  ): IO[RetrievalError, Chunk[KnowledgeIndexManifest]] =
    query(connection, "select manifest", sql)(bind) { result =>
      val rows = ChunkBuilder.make[KnowledgeIndexManifest]()
      while result.next() do rows += KnowledgeManifestRow.decode(result)
      rows.result()
    }

  /** 同 ingestionId 的所有不可变字段都必须一致。 */
  private def sameRequest(manifest: KnowledgeIndexManifest, request: BeginKnowledgeIndex): Boolean =
    request.targetProfileId.forall(_ == manifest.build.profileId) &&
      manifest.build.lineage == request.lineage &&
      manifest.build.buildSpec.sha256 == request.buildSpec.sha256 &&
      manifest.sourceUri == request.sourceUri &&
      manifest.permissions == request.permissions &&
      KnowledgeIndexer.requestMetadata(manifest.metadata) == KnowledgeIndexer.requestMetadata(
        request.metadata
      )

  /** 防止调用方用同版本号但不同内容伪造 build。 */
  private def ensureSameBuild(
      actual: KnowledgeIndexBuild,
      supplied: KnowledgeIndexBuild
  ): IO[RetrievalError, Unit] =
    if actual.copy(casActiveProfile = supplied.casActiveProfile) == supplied then ZIO.unit
    else ZIO.fail(AgentError.RetrievalFailed("knowledge build 内容与存储不一致"))

  private def matchesExpectation(expectation: ActiveVersionExpectation, active: Option[Long]): Boolean =
    expectation match
      case ActiveVersionExpectation.AnyVersion      => true
      case ActiveVersionExpectation.NoActiveVersion => active.isEmpty
      case ActiveVersionExpectation.Exact(version)  => active.contains(version)

  private def query[A](connection: Connection, operation: String, sql: String)(
      bind: PreparedStatement => Any
  )(
      read: ResultSet => A
  ): IO[RetrievalError, A] =
    jdbc(operation) {
      val statement = connection.prepareStatement(sql)
      try
        bind(statement)
        val result = statement.executeQuery()
        try read(result)
        finally result.close()
      finally statement.close()
    }

  private def update(connection: Connection, operation: String, sql: String)(
      bind: PreparedStatement => Any
  ): IO[RetrievalError, Int] =
    jdbc(operation) {
      val statement = connection.prepareStatement(sql)
      try
        bind(statement)
        statement.executeUpdate()
      finally statement.close()
    }

  /** 从宿主连接池按 Scope 借还连接；关闭失败不覆盖主要业务结果。 */
  private def withConnection[A](use: Connection => IO[RetrievalError, A]): IO[RetrievalError, A] =
    ZIO.scoped {
      ZIO
        .acquireRelease(jdbc("acquire connection")(dataSource.getConnection))(connection =>
          ZIO.attemptBlocking(connection.close()).ignore
        )
        .flatMap(use)
    }

  /** 运行可中断事务：业务阶段可被取消，commit/rollback 与 autoCommit 恢复位于不可中断边界。 */
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

  /** 在 blocking executor 执行 JDBC，并仅暴露 SQLSTATE 分类，不记录 SQL 参数或正文。 */
  private def jdbc[A](operation: String)(effect: => A): IO[RetrievalError, A] =
    ZIO.attemptBlocking(effect).mapError(error => KnowledgeManifestRow.databaseError(operation, error))

object PostgresKnowledgeIndexStore:
  /** 构造与 optional migration 固定维度一致的 Store Layer。 */
  def layer(dimension: Int): URLayer[DataSource, KnowledgeIndexStore] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresKnowledgeIndexStore(dataSource, dimension))

/** 知识表名、键谓词与块列绑定的唯一来源；Store 与 VectorStore 共用。 */
private[postgres] object KnowledgeSql:
  val Schema: String    = "zyblw_agent_knowledge"
  val Spaces: String    = s"$Schema.agent_knowledge_spaces"
  val Profiles: String  = s"$Schema.agent_knowledge_profiles"
  val Documents: String = s"$Schema.agent_knowledge_profile_documents"
  val Staging: String   = s"$Schema.agent_knowledge_profile_chunk_staging"
  val Chunks: String    = s"$Schema.agent_knowledge_profile_chunks"
  val Audit: String     = s"$Schema.agent_knowledge_profile_activation_audit"
  val Withdrawn: String = s"$Schema.agent_knowledge_withdrawn"

  val SealedProfileStatuses: Set[String] = Set("failed", "retired", "cancelled")

  /** 未加表别名的文档键谓词；三个参数依次为 tenant、space、document。 */
  val DocumentKey: String  = "tenant_id = ? AND knowledge_space_id = ? AND document_id = ?"
  val DocumentKeyD: String = "d.tenant_id = ? AND d.knowledge_space_id = ? AND d.document_id = ?"
  val VersionKey: String   = s"$DocumentKey AND index_version = ?"

  val ManifestSelect: String = KnowledgeManifestRow.Select

  def bindDocumentKey(statement: PreparedStatement, start: Int, key: KnowledgeDocumentKey): Int =
    statement.setString(start, key.tenantId.value)
    statement.setString(start + 1, key.knowledgeSpaceId.value)
    statement.setString(start + 2, key.documentId)
    start + 3

  def bindVersionKey(
      statement: PreparedStatement,
      start: Int,
      key: KnowledgeDocumentKey,
      version: Long
  ): Int =
    val next = bindDocumentKey(statement, start, key)
    statement.setLong(next, version)
    next + 1

  def bindProfileKey(
      statement: PreparedStatement,
      start: Int,
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId
  ): Unit =
    statement.setString(start, tenantId.value)
    statement.setString(start + 1, spaceId.value)
    statement.setString(start + 2, profileId.value)

  def textArray(connection: Connection, values: Iterable[String]): java.sql.Array =
    connection.createArrayOf("text", values.toArray[AnyRef])

  /** 以固定列顺序绑定可选谱系。空谱系写 SQL NULL/空数组，不伪造页码或父子关系。 */
  def bindLineage(
      statement: PreparedStatement,
      connection: Connection,
      start: Int,
      lineage: Option[ChunkLineage]
  ): Unit =
    statement.setString(start, lineage.flatMap(_.parentId).orNull)
    statement.setObject(start + 1, lineage.map(value => Int.box(value.ordinal)).orNull)
    statement.setString(start + 2, lineage.flatMap(_.previousChunkId).orNull)
    statement.setString(start + 3, lineage.flatMap(_.nextChunkId).orNull)
    statement.setArray(start + 4, textArray(connection, lineage.fold(Chunk.empty[String])(_.headingPath)))
    statement.setArray(
      start + 5,
      connection.createArrayOf(
        "integer",
        lineage.fold(Chunk.empty[Int])(_.pageNumbers).map(Int.box).toArray[AnyRef]
      )
    )
    statement.setString(start + 6, lineage.fold(Chunk.empty[DocumentOrigin])(_.origins).toJson)
    statement.setArray(start + 7, textArray(connection, lineage.fold(Chunk.empty[String])(_.blockIds)))

  /** dense 与 display 相同则写 NULL，查询端回读为 chunk_text；三个摘要总是写入。 */
  def bindRepresentations(statement: PreparedStatement, start: Int, chunk: DocumentChunk): Unit =
    val representations = chunk.representations
    val distinct        = chunk.denseText != chunk.displayText || chunk.lexicalText != chunk.displayText
    statement.setString(start, if distinct then chunk.denseText else null)
    statement.setString(start + 1, representations.displaySha256)
    statement.setString(start + 2, representations.denseSha256)
    statement.setString(start + 3, representations.lexicalSha256)

  /** sparse 载荷格式 `dimension|index:value,...`，与 `PostgresPgVectorStore` 的解析一致。 */
  def encodeSparse(sparse: SparseEmbedding): String =
    s"${sparse.dimension}|${sparse.entries.map(entry => s"${entry.index}:${entry.value}").mkString(",")}"

  /** 将 Float 向量编码为 pgvector 的受控文本输入格式。 */
  def vectorLiteral(embedding: Embedding): String = embedding.values.mkString("[", ",", "]")

/** manifest 行的 SELECT 列表、解码与 SQLSTATE 分类。
  *
  * 版本库与管理面目录读的是同一组列，因此共用一份投影；构建规格从所属 Profile 读取并与文档行的规格摘要比对。
  */
private object KnowledgeManifestRow:
  /** ResultSet 列顺序的唯一来源；修改 migration 字段时必须同步更新 [[decode]]。文档表别名为 `d`，Profile 表别名为 `p`。 */
  val Select: String =
    """SELECT d.tenant_id, d.knowledge_space_id, d.document_id, d.index_version, d.profile_id, d.ingestion_id,
      |       d.source_uri, d.source_id, d.source_revision_id, d.source_sha256, d.source_media_type, d.parser_id,
      |       d.artifact_sha256, d.structure_sha256, d.text_sha256, d.build_spec_sha256, p.build_spec::text,
      |       d.permissions, d.metadata::text, d.status, d.active, d.chunk_count, d.failure_code,
      |       d.created_at, d.updated_at, d.chunk_set_sha256
      |FROM zyblw_agent_knowledge.agent_knowledge_profile_documents d
      |JOIN zyblw_agent_knowledge.agent_knowledge_profiles p
      |  ON p.tenant_id = d.tenant_id AND p.knowledge_space_id = d.knowledge_space_id AND p.profile_id = d.profile_id""".stripMargin

  /** 08/40/53 与数据库重启 SQLSTATE 可重试；约束和协议错误保持不可重试。 */
  def databaseError(operation: String, error: Throwable): RetrievalError =
    error match
      case retrieval: AgentError.RetrievalFailed => retrieval
      case _                                     =>
        val sqlState = error match
          case sql: SQLException => Option(sql.getSQLState).getOrElse("unknown")
          case _                 => "not-sql"
        val retryable = sqlState.startsWith("08") || sqlState.startsWith("40") || sqlState.startsWith("53") ||
          Set("57P01", "57P02", "57P03").contains(sqlState)
        AgentError.RetrievalFailed(s"PostgreSQL knowledge $operation 失败 (sqlState=$sqlState)", retryable)

  /** 把数据库行解码为类型化 manifest；未知状态、脏 metadata 或规格摘要不一致会作为协议错误失败。 */
  def decode(result: ResultSet): KnowledgeIndexManifest =
    val key = KnowledgeDocumentKey(
      TenantId(result.getString(1)),
      result.getString(3),
      KnowledgeSpaceId(result.getString(2))
    )
    val lineage = DocumentLineage(
      sourceId = result.getString(8),
      sourceRevisionId = result.getString(9),
      sourceSha256 = result.getString(10),
      sourceMediaType = result.getString(11),
      parserId = result.getString(12),
      artifactSha256 = result.getString(13),
      structureSha256 = result.getString(14),
      textSha256 = result.getString(15)
    )
    val spec = result
      .getString(17)
      .fromJson[IndexBuildSpec]
      .fold(error => throw IllegalStateException(s"knowledge profile build_spec 解码失败: $error"), identity)
    if spec.sha256 != result.getString(16) then
      throw IllegalStateException("knowledge profile build_spec 与摘要不一致")
    val metadata = result
      .getString(19)
      .fromJson[Map[String, String]]
      .fold(error => throw IllegalStateException(s"knowledge manifest metadata 解码失败: $error"), identity)
    val checkpoint = metadata
      .get("ingest.checkpoint")
      .flatMap(name => IngestCheckpoint.values.find(_.toString == name))
      .getOrElse(IngestCheckpoint.Resolved)
    val build = KnowledgeIndexBuild(
      key,
      result.getLong(4),
      result.getString(6),
      lineage,
      spec,
      IndexProfileId(result.getString(5)),
      casActiveProfile = metadata.get("ingest.casActiveProfile").contains("true")
    )
    val permissions = result.getArray(18).getArray.asInstanceOf[Array[AnyRef]].iterator.map(_.toString).toSet
    val status      = result.getString(20) match
      case "building"   => KnowledgeIndexStatus.Building
      case "ready"      => KnowledgeIndexStatus.Ready
      case "superseded" => KnowledgeIndexStatus.Superseded
      case "failed"     => KnowledgeIndexStatus.Failed
      case "retired"    => KnowledgeIndexStatus.Retired
      case other        => throw IllegalStateException(s"未知 knowledge status: $other")
    KnowledgeIndexManifest(
      build,
      result.getString(7),
      permissions,
      metadata,
      status,
      result.getBoolean(21),
      result.getInt(22),
      Option(result.getString(23)),
      result.getTimestamp(24).toInstant,
      result.getTimestamp(25).toInstant,
      checkpoint,
      Option(result.getString(26))
    )

/** 知识索引清单目录的 PostgreSQL 实现。
  *
  * 过滤与 keyset 条件全部下推到 SQL，因此管理台翻页不会把整个知识库的清单加载进堆。排序固定为
  * `(updated_at DESC, knowledge_space_id DESC, document_id DESC, index_version DESC)`，字符串使用 `COLLATE "C"`，与
  * `KnowledgeIndexDirectory` 契约及内存实现的字节序一致，因此同一个游标在两种实现下含义相同。
  *
  * @param dataSource
  *   宿主共享连接池；框架不创建隐藏连接池
  */
final class PostgresKnowledgeIndexDirectory(dataSource: DataSource) extends KnowledgeIndexDirectory:
  def list(
      tenantId: Option[TenantId],
      limit: Int,
      cursor: Option[KnowledgeIndexCursor]
  ): IO[RetrievalError, KnowledgeIndexPage] =
    val bounded    = KnowledgeIndexDirectory.boundedLimit(limit)
    val conditions = Chunk.fromIterable(tenantId.map(_ => "d.tenant_id = ?")) ++
      // 行值比较让 PostgreSQL 直接在复合索引上定位游标位置；拆成 OR 条件通常退化为顺序扫描。
      Chunk.fromIterable(
        cursor.map(_ =>
          """(d.updated_at, d.knowledge_space_id COLLATE "C", d.document_id COLLATE "C", d.index_version)
            | < (?, ? COLLATE "C", ? COLLATE "C", ?)""".stripMargin
        )
      )
    val whereSql = if conditions.isEmpty then "" else conditions.mkString(" WHERE ", " AND ", "")
    val sql      =
      s"""${KnowledgeManifestRow.Select}$whereSql
         |ORDER BY d.updated_at DESC, d.knowledge_space_id COLLATE "C" DESC, d.document_id COLLATE "C" DESC,
         |         d.index_version DESC
         |LIMIT ?""".stripMargin

    ZIO
      .scoped {
        ZIO
          .acquireRelease(ZIO.attemptBlocking(dataSource.getConnection))(connection =>
            ZIO.attemptBlocking(connection.close()).ignore
          )
          .flatMap { connection =>
            ZIO.attemptBlocking {
              val statement = connection.prepareStatement(sql)
              try
                var index       = 0
                def next(): Int = { index += 1; index }
                tenantId.foreach(value => statement.setString(next(), value.value))
                cursor.foreach { value =>
                  statement.setTimestamp(
                    next(),
                    Timestamp.from(CursorTime.toInstant(value.updatedAtEpochMicro))
                  )
                  statement.setString(next(), value.knowledgeSpaceId.value)
                  statement.setString(next(), value.documentId)
                  statement.setLong(next(), value.indexVersion)
                }
                // 多取一条用于判定 hasMore，避免额外一次 COUNT 查询。
                statement.setInt(next(), bounded + 1)
                val result  = statement.executeQuery()
                val builder = ChunkBuilder.make[KnowledgeIndexManifest]()
                while result.next() do builder += KnowledgeManifestRow.decode(result)
                builder.result()
              finally statement.close()
            }
          }
      }
      .mapError(KnowledgeManifestRow.databaseError("list manifests", _))
      .map { rows =>
        val window  = rows.take(bounded)
        val hasMore = rows.length > window.length
        KnowledgeIndexPage(
          window,
          window.lastOption
            .filter(_ => hasMore)
            .map(last =>
              KnowledgeIndexCursor(
                CursorTime.epochMicro(last.updatedAt),
                last.build.knowledgeSpaceId,
                last.build.key.documentId,
                last.build.version
              )
            ),
          hasMore
        )
      }

object PostgresKnowledgeIndexDirectory:
  val layer: URLayer[DataSource, KnowledgeIndexDirectory] =
    ZLayer.fromFunction((dataSource: DataSource) => PostgresKnowledgeIndexDirectory(dataSource))
