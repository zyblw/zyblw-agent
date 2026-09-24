package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*

/** `KnowledgeIndexStore` 的确定性内存实现。
  *
  * 它用于 ZIO Test、示例和无 PostgreSQL 的本地开发，所有状态迁移通过 `Ref.Synchronized` 原子完成。语义与 PostgreSQL Adapter
  * 一致：文档键含知识空间；active 与 building Profile 可写，superseded Profile 只接受显式 `targetProfileId` 的补齐写入；
  * 发布比较块集合摘要；撤回按来源修订生效。进程退出会丢失全部状态，生产环境应使用 PostgreSQL。
  */
final class InMemoryKnowledgeIndexStore private (
    state: Ref.Synchronized[InMemoryKnowledgeIndexStore.State]
) extends KnowledgeIndexStore,
      VectorStore:
  import InMemoryKnowledgeIndexStore.*

  /** 立即写入本地已发布快照。
    *
    * 该入口只用于直接测试 `VectorStore` SPI；正常知识摄取应使用 `KnowledgeIndexer` 的 Building→stage→activate 协议。相同
    * tenant/space/document/chunk ID 的条目被原子替换。
    */
  override def upsert(chunks: Chunk[IndexedChunk]): UIO[Unit] =
    state.update { current =>
      val updated = chunks
        .groupBy(item =>
          KnowledgeDocumentKey(
            item.chunk.tenantId,
            item.chunk.documentId,
            item.chunk.knowledgeSpaceId.getOrElse(KnowledgeSpaceId.Default)
          )
        )
        .foldLeft(current.published) { case (published, (key, incoming)) =>
          val incomingIds = incoming.map(_.chunk.id).toSet
          val retained    =
            published.getOrElse(key, Chunk.empty).filterNot(item => incomingIds.contains(item.chunk.id))
          published.updated(key, retained ++ incoming)
        }
      current.copy(published = updated)
    }

  /** 对当前 active 快照执行 tenant/space/profile/permission 前置过滤，再计算 cosine。 */
  override def search(
      query: Embedding,
      scope: RetrievalScope,
      limit: Int
  ): UIO[Chunk[RetrievalHit]] =
    searchFiltered(RetrievalMode.VectorOnly, "", query, scope, RetrievalFilter.empty, limit)

  override def searchFiltered(
      mode: RetrievalMode,
      queryText: String,
      query: Embedding,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      limit: Int,
      sparseQuery: Option[SparseEmbedding] = None
  ): UIO[Chunk[RetrievalHit]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else
      state.get.map { current =>
        val pin        = current.pinnedProfile(scope)
        val authorized = current.visible(scope, pin).filter(item => filter.matches(item.chunk))
        Chunk.fromIterable(RetrievalScoring.rank(mode, queryText, query, authorized, sparseQuery).take(limit))
      }

  override def fetchChunks(
      chunkIds: Set[String],
      scope: RetrievalScope
  ): UIO[Chunk[DocumentChunk]] =
    fetchChunks(chunkIds, scope, RetrievalFilter.empty)

  override def fetchChunks(
      chunkIds: Set[String],
      scope: RetrievalScope,
      filter: RetrievalFilter
  ): UIO[Chunk[DocumentChunk]] =
    if chunkIds.isEmpty then ZIO.succeed(Chunk.empty)
    else
      state.get.map { current =>
        Chunk.fromIterable(
          current
            .visible(scope, current.pinnedProfile(scope))
            .map(_.chunk)
            .filter(chunk => chunkIds.contains(chunk.id) && filter.matches(chunk))
            .toVector
            .sortBy(_.id)
        )
      }

  /** 删除指定租户文档在全部空间中的本地发布快照。耐久业务应优先调用 `KnowledgeIndexStore.retire` 保留 manifest 状态。 */
  override def deleteByDocument(documentId: String, tenantId: TenantId): UIO[Unit] =
    state.update(current =>
      current.copy(published =
        current.published.filterNot((key, _) => key.tenantId == tenantId && key.documentId == documentId)
      )
    )

  /** 分配新版本，或对同一幂等请求返回原构建句柄。 */
  def begin(request: BeginKnowledgeIndex): IO[RetrievalError, KnowledgeIndexBuild] =
    Clock.instant.flatMap { now =>
      state.modifyZIO { current =>
        current.manifests.values.find(manifest =>
          manifest.build.key == request.key && manifest.build.ingestionId == request.ingestionId
        ) match
          case Some(existing) if sameRequest(existing, request) =>
            existing.status match
              case KnowledgeIndexStatus.Failed =>
                val resumed =
                  existing.copy(status = KnowledgeIndexStatus.Building, failureCode = None, updatedAt = now)
                val key = existing.build.key -> existing.build.version
                current
                  .writableProfile(
                    existing.build.key,
                    existing.build.profileId,
                    request.buildSpec,
                    explicit = true
                  )
                  .as(
                    existing.build -> current.copy(
                      manifests = current.manifests.updated(key, resumed),
                      // 失败可能发生在最后一个批次之前；重试从空暂存区开始，避免残留旧切分块。
                      staged = current.staged - key
                    )
                  )
              case KnowledgeIndexStatus.Superseded =>
                ZIO.fail(
                  AgentError.RetrievalFailed(s"knowledge ingestion 已被较新版本替代: ${request.key.documentId}")
                )
              case KnowledgeIndexStatus.Retired =>
                ZIO.fail(AgentError.RetrievalFailed(s"knowledge ingestion 已被下线: ${request.key.documentId}"))
              case _ => ZIO.succeed(existing.build -> current)
          case Some(_) =>
            ZIO.fail(AgentError.RetrievalFailed(s"knowledge ingestionId 已绑定不同请求: ${request.key.documentId}"))
          case None
              if current.withdrawn.contains(Withdrawal(request.key, request.lineage.sourceRevisionId)) =>
            ZIO.fail(
              AgentError.RetrievalFailed(
                s"knowledge 来源修订已撤回，不能重新索引: ${request.key.documentId}@${request.lineage.sourceRevisionId}"
              )
            )
          case None =>
            val spaceKey      = request.key.tenantId -> request.key.knowledgeSpaceId
            val space         = current.spaces.getOrElse(spaceKey, SpaceRecord())
            val activeVersion = current.activeManifest(request.key).map(_.build.version)
            if !matchesExpectation(request.expectation, activeVersion) then
              ZIO.fail(
                AgentError.RetrievalFailed(s"knowledge active version 前置条件失败: ${request.key.documentId}")
              )
            else
              val plan = request.targetProfileId match
                case Some(forced) if space.activeProfileId.isEmpty =>
                  ProfileWritePlan.CreateAndActivate(forced)
                case Some(forced) if space.activeProfileId.contains(forced) =>
                  ProfileWritePlan.UseActive(forced)
                case Some(forced) => ProfileWritePlan.BuildParallel(forced)
                case None         =>
                  ProfileWritePlan.decide(
                    space.activeProfileId.map(id =>
                      id -> current.profiles.get(ProfileKey(request.key, id)).fold("")(_.spec.sha256)
                    ),
                    request.buildSpec
                  )
              val (writeProfile, cas) = plan match
                case ProfileWritePlan.CreateAndActivate(id) => id -> true
                case ProfileWritePlan.UseActive(id)         => id -> false
                case ProfileWritePlan.BuildParallel(id)     => id -> false
              current
                .writableProfile(
                  request.key,
                  writeProfile,
                  request.buildSpec,
                  request.targetProfileId.nonEmpty
                )
                .map { _ =>
                  val nextVersion = current.manifests.keysIterator
                    .collect { case (key, version) if key == request.key => version }
                    .maxOption
                    .getOrElse(0L) + 1L
                  val build = KnowledgeIndexBuild(
                    request.key,
                    nextVersion,
                    request.ingestionId,
                    request.lineage,
                    request.buildSpec,
                    writeProfile,
                    cas
                  )
                  val manifest = KnowledgeIndexManifest(
                    build,
                    request.sourceUri,
                    request.permissions,
                    request.metadata,
                    KnowledgeIndexStatus.Building,
                    active = false,
                    chunkCount = 0,
                    failureCode = None,
                    createdAt = now,
                    updatedAt = now,
                    checkpoint = IngestCheckpoint.Resolved
                  )
                  val profileKey = ProfileKey(request.key, writeProfile)
                  build -> current.copy(
                    manifests = current.manifests.updated(request.key -> nextVersion, manifest),
                    profiles = current.profiles.updatedWith(profileKey)(
                      _.orElse(Some(ProfileRecord(request.buildSpec, IndexProfileStatus.Building)))
                    ),
                    spaces = current.spaces.updated(spaceKey, space)
                  )
                }
      }
    }

  /** 校验归属与维度后幂等合并暂存块；相同 chunkId 的重放采用最后一次值。 */
  def stage(build: KnowledgeIndexBuild, chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit] =
    state.modifyZIO { current =>
      current.manifests.get(build.key -> build.version) match
        case None => ZIO.fail(AgentError.RetrievalFailed("knowledge build 不存在"))
        case Some(manifest) if manifest.status != KnowledgeIndexStatus.Building =>
          ZIO.fail(AgentError.RetrievalFailed(s"knowledge build 状态不允许暂存: ${manifest.status}"))
        case Some(_) =>
          val invalid = chunks.find { indexed =>
            val chunk = indexed.chunk
            chunk.tenantId != build.key.tenantId ||
            chunk.documentId != build.key.documentId ||
            chunk.catalogVersion != build.version ||
            !ChunkSetDigest.validChunkId(chunk.id) ||
            chunk.permissions.isEmpty ||
            indexed.embedding.values.length != build.embeddingDimension
          }
          invalid match
            case Some(value) =>
              ZIO.fail(AgentError.RetrievalFailed(s"knowledge staged chunk 契约不匹配: ${value.chunk.id}"))
            case None =>
              val key      = build.key -> build.version
              val existing = current.staged.getOrElse(key, Map.empty)
              val merged   = existing ++ chunks.map(value => value.chunk.id -> value)
              ZIO.succeed(() -> current.copy(staged = current.staged.updated(key, merged)))
    }

  /** 原子校验块集合摘要、废弃同 Profile 的旧 active 版本并发布新快照。 */
  def activate(
      build: KnowledgeIndexBuild,
      expected: ChunkSetDigest
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    Clock.instant.flatMap { now =>
      state.modifyZIO { current =>
        val key = build.key -> build.version
        current.manifests.get(key) match
          case Some(manifest) if manifest.status == KnowledgeIndexStatus.Ready && manifest.active =>
            ZIO.succeed(manifest -> current)
          case Some(manifest) if manifest.status != KnowledgeIndexStatus.Building =>
            ZIO.fail(AgentError.RetrievalFailed(s"knowledge build 状态不允许发布: ${manifest.status}"))
          case None => ZIO.fail(AgentError.RetrievalFailed("knowledge build 不存在"))
          case Some(_) if current.withdrawn.contains(Withdrawal(build.key, build.lineage.sourceRevisionId)) =>
            ZIO.fail(AgentError.RetrievalFailed("knowledge 来源修订已撤回，不能发布"))
          case Some(manifest) =>
            val staged = Chunk.fromIterable(current.staged.getOrElse(key, Map.empty).values)
            val actual = if staged.isEmpty then None else Some(ChunkSetDigest.of(staged.map(_.chunk)))
            if !actual.contains(expected) then
              ZIO.fail(
                AgentError.RetrievalFailed(
                  s"knowledge staged chunk 集合不匹配: 暂存 ${staged.length} 块，期望 ${expected.count} 块"
                )
              )
            else
              current.writableProfile(build.key, build.profileId, build.buildSpec, explicit = true).map { _ =>
                val superseded = current.manifests.map { case (manifestKey, value) =>
                  if value.build.key == build.key && value.build.profileId == build.profileId && value.active
                  then
                    manifestKey -> value.copy(
                      status = KnowledgeIndexStatus.Superseded,
                      active = false,
                      updatedAt = now
                    )
                  else manifestKey -> value
                }
                val stamped = staged
                  .map(indexed =>
                    indexed.copy(chunk =
                      indexed.chunk.copy(
                        knowledgeSpaceId = Some(build.knowledgeSpaceId),
                        profileId = Some(build.profileId),
                        sourceRevisionId = Some(build.lineage.sourceRevisionId)
                      )
                    )
                  )
                  .sortBy(_.chunk.id)
                val retainedPublished =
                  current.published
                    .getOrElse(build.key, Chunk.empty)
                    .filterNot(_.chunk.profileId.contains(build.profileId))
                val spaceKey   = build.key.tenantId -> build.knowledgeSpaceId
                val space      = current.spaces.getOrElse(spaceKey, SpaceRecord())
                val profileKey = ProfileKey(build.key, build.profileId)
                val bootstrap  = build.casActiveProfile && space.activeProfileId.isEmpty
                val nextSpace  =
                  if bootstrap then
                    space.copy(activeProfileId = Some(build.profileId), revision = space.revision + 1L)
                  else space
                val ready = manifest.copy(
                  status = KnowledgeIndexStatus.Ready,
                  active = true,
                  chunkCount = staged.length,
                  failureCode = None,
                  updatedAt = now,
                  checkpoint = IngestCheckpoint.Ready,
                  chunkSetSha256 = Some(expected.sha256)
                )
                ready -> current.copy(
                  manifests = superseded.updated(key, ready),
                  staged = current.staged - key,
                  published = current.published.updated(build.key, retainedPublished ++ stamped),
                  spaces = current.spaces.updated(spaceKey, nextSpace),
                  profiles =
                    if bootstrap then
                      current.profiles
                        .updatedWith(profileKey)(_.map(_.copy(status = IndexProfileStatus.Active)))
                    else current.profiles
                )
              }
      }
    }

  /** 只允许 Building → Failed；已 Ready 的版本不会被迟到的失败清理覆盖。 */
  def markFailed(build: KnowledgeIndexBuild, failureCode: String): IO[RetrievalError, Unit] =
    Clock.instant.flatMap { now =>
      state.update { current =>
        val key = build.key -> build.version
        current.manifests.get(key) match
          case Some(manifest) if manifest.status == KnowledgeIndexStatus.Building =>
            current.copy(
              manifests = current.manifests.updated(
                key,
                manifest.copy(
                  status = KnowledgeIndexStatus.Failed,
                  active = false,
                  failureCode = Some(failureCode.take(64)),
                  updatedAt = now
                )
              )
            )
          case _ => current
      }
    }

  /** 查找文档在空间 active Profile 中的 active manifest。 */
  def active(key: KnowledgeDocumentKey): UIO[Option[KnowledgeIndexManifest]] =
    state.get.map(_.activeManifest(key))

  /** 按业务幂等键查找任意状态 manifest。 */
  def find(key: KnowledgeDocumentKey, ingestionId: String): UIO[Option[KnowledgeIndexManifest]] =
    state.get.map(
      _.manifests.values.find(manifest =>
        manifest.build.key == key && manifest.build.ingestionId == ingestionId
      )
    )

  /** 原子执行乐观下线：前置版本匹配时，文档在空间内全部 Profile 的 active 版本一起下线。
    *
    * 前置版本取 active Profile 中的版本；文档只存在于并行 Profile 时取其最新 active 版本。相同版本已经 Retired 时幂等返回。
    */
  def retire(
      key: KnowledgeDocumentKey,
      expectedActiveVersion: Long
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    if expectedActiveVersion <= 0L then ZIO.fail(AgentError.RetrievalFailed("expectedActiveVersion 必须为正数"))
    else
      Clock.instant.flatMap { now =>
        state.modifyZIO { current =>
          val activeCopies = current.manifests.values.filter(m => m.build.key == key && m.active).toVector
          val governing    = current.activeManifest(key).orElse(activeCopies.maxByOption(_.build.version))
          governing match
            case Some(manifest) if manifest.build.version != expectedActiveVersion =>
              ZIO.fail(AgentError.RetrievalFailed("knowledge retire active version 前置条件失败"))
            case Some(manifest) =>
              val retiredKeys = activeCopies.map(m => m.build.key -> m.build.version).toSet
              val manifests   = current.manifests.map { case (manifestKey, value) =>
                if retiredKeys.contains(manifestKey) then
                  manifestKey -> value.copy(
                    status = KnowledgeIndexStatus.Retired,
                    active = false,
                    updatedAt = now
                  )
                else manifestKey -> value
              }
              ZIO.succeed(
                manifests(key -> manifest.build.version) -> current.copy(
                  manifests = manifests,
                  published = current.published - key
                )
              )
            case None =>
              current.manifests.get(key -> expectedActiveVersion) match
                case Some(manifest) if manifest.status == KnowledgeIndexStatus.Retired =>
                  ZIO.succeed(manifest -> current)
                case _ => ZIO.fail(AgentError.RetrievalFailed("knowledge retire 目标不是当前 active 版本"))
        }
      }

  /** 按 updatedAt/version 稳定选择非活动终态，模拟 PostgreSQL 的有界 retention。 */
  def purgeInactive(
      updatedBefore: Instant,
      limit: Int,
      legalHold: Set[KnowledgeDocumentKey] = Set.empty
  ): UIO[Long] =
    if limit <= 0 then ZIO.succeed(0L)
    else
      state.modify { current =>
        val removable = current.manifests.iterator
          .filter { case ((key, _), manifest) =>
            !manifest.active &&
            !legalHold.contains(key) &&
            manifest.updatedAt.isBefore(updatedBefore) && Set(
              KnowledgeIndexStatus.Superseded,
              KnowledgeIndexStatus.Failed,
              KnowledgeIndexStatus.Retired
            ).contains(manifest.status)
          }
          .toVector
          .sortBy { case ((key, version), manifest) =>
            (manifest.updatedAt, key.tenantId.value, key.knowledgeSpaceId.value, key.documentId, version)
          }
          .take(limit)
          .map(_._1)
          .toSet
        removable.size.toLong -> current.copy(
          manifests = current.manifests.removedAll(removable),
          staged = current.staged.removedAll(removable)
        )
      }

  override def setCheckpoint(build: KnowledgeIndexBuild, checkpoint: IngestCheckpoint): UIO[Unit] =
    Clock.instant.flatMap { now =>
      state.update { current =>
        val key = build.key -> build.version
        current.manifests.get(key) match
          case Some(manifest) =>
            current.copy(
              manifests = current.manifests.updated(
                key,
                manifest.copy(
                  checkpoint = checkpoint,
                  metadata = manifest.metadata.updated("ingest.checkpoint", checkpoint.toString),
                  updatedAt = now
                )
              )
            )
          case None => current
      }
    }

  override def resolveActiveProfile(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId
  ): UIO[Option[IndexProfileId]] =
    state.get.map(_.activeProfile(tenantId, spaceId))

  override def activateProfile(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId,
      expectedRevision: Long,
      reason: String,
      publication: Option[ProfilePublication] = None
  ): IO[RetrievalError, Long] =
    if !reason.matches("[A-Za-z0-9._:-]{1,160}") then
      ZIO.fail(AgentError.RetrievalFailed("Invalid profile activation reason"))
    else
      state.modifyZIO { current =>
        val spaceKey   = tenantId -> spaceId
        val space      = current.spaces.getOrElse(spaceKey, SpaceRecord())
        val profileKey = ProfileKey(tenantId, spaceId, profileId)
        val gate       = publication
          .toRight(AgentError.RetrievalFailed("Profile publication evidence is required"))
          .flatMap(_.validate(current.profileManifests(tenantId, spaceId, profileId)))
        val corpusMatches =
          space.activeProfileId.forall(_ == profileId) ||
            current.corpusDiff(tenantId, spaceId, profileId).isEmpty
        current.profiles.get(profileKey) match
          case None => ZIO.fail(AgentError.RetrievalFailed("activateProfile 目标 Profile 不存在"))
          case Some(record) if SealedStatuses.contains(record.status) =>
            ZIO.fail(AgentError.RetrievalFailed(s"activateProfile 目标 Profile 已终止: ${record.status}"))
          case Some(_) if gate.isLeft    => ZIO.fail(gate.swap.toOption.get)
          case Some(_) if !corpusMatches =>
            ZIO.fail(AgentError.RetrievalFailed("Target profile corpus does not match the active profile"))
          case Some(_) if space.revision != expectedRevision =>
            ZIO.fail(AgentError.RetrievalFailed("knowledge space profile CAS 失败"))
          case Some(record) =>
            val next     = space.copy(activeProfileId = Some(profileId), revision = space.revision + 1L)
            val previous =
              space.activeProfileId.filterNot(_ == profileId).map(ProfileKey(tenantId, spaceId, _))
            val profiles = previous
              .foldLeft(current.profiles)((acc, key) =>
                acc.updatedWith(key)(_.map(_.copy(status = IndexProfileStatus.Superseded)))
              )
              .updated(
                profileKey,
                record.copy(
                  status = IndexProfileStatus.Active,
                  publicationCensusSha256 = publication.map(_.evaluatedCensusSha256)
                )
              )
            ZIO.succeed(
              next.revision -> current.copy(
                spaces = current.spaces.updated(spaceKey, next),
                profiles = profiles
              )
            )
      }

  override def profileCorpusDiff(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      target: IndexProfileId
  ): UIO[ProfileCorpusDiff] =
    state.get.map(_.corpusDiff(tenantId, spaceId, target))

  override def withdraw(key: KnowledgeDocumentKey, sourceRevisionId: String): IO[RetrievalError, Unit] =
    Option(sourceRevisionId).map(_.trim).filter(_.nonEmpty) match
      case None           => ZIO.fail(AgentError.RetrievalFailed("sourceRevisionId 不能为空"))
      case Some(revision) =>
        Clock.instant.flatMap { now =>
          state.modifyZIO { current =>
            val matching = current.manifests.filter { case ((manifestKey, _), manifest) =>
              manifestKey == key && manifest.build.lineage.sourceRevisionId == revision
            }
            if matching.isEmpty then
              ZIO.fail(AgentError.RetrievalFailed(s"knowledge withdraw 来源修订不存在: ${key.documentId}@$revision"))
            else
              val hidden = matching.collect {
                case (manifestKey, manifest)
                    if manifest.status != KnowledgeIndexStatus.Retired &&
                      manifest.status != KnowledgeIndexStatus.Superseded =>
                  manifestKey -> manifest.copy(
                    status = KnowledgeIndexStatus.Retired,
                    active = false,
                    updatedAt = now
                  )
              }
              val hiddenProfiles = hidden.values
                .filter(m => matching(m.build.key -> m.build.version).active)
                .map(_.build.profileId)
                .toSet
              ZIO.succeed(
                () -> current.copy(
                  manifests = current.manifests ++ hidden,
                  staged = current.staged.removedAll(hidden.keys),
                  published = current.published.updatedWith(key)(
                    _.map(_.filterNot(_.chunk.profileId.exists(hiddenProfiles.contains))).filter(_.nonEmpty)
                  ),
                  withdrawn = current.withdrawn + Withdrawal(key, revision),
                  cacheEpoch = current.cacheEpoch + 1L
                )
              )
          }
        }

  /** 返回某文档最近一次发布的确定性块快照，仅供测试断言和本地调试。 */
  def published(key: KnowledgeDocumentKey): UIO[Chunk[IndexedChunk]] =
    state.get.map(_.published.getOrElse(key, Chunk.empty))

  /** 返回全部索引清单的快照，供 `KnowledgeIndexDirectory` 做管理面投影。
    *
    * 该能力只出现在具体内存实现上，不进入 `KnowledgeIndexStore` trait：清单枚举只被管理列表需要。返回值只含 manifest，不含暂存块、已发布块和向量。
    */
  def manifests: UIO[Chunk[KnowledgeIndexManifest]] =
    state.get.map(current => Chunk.fromIterable(current.manifests.values))

  /** 比较幂等请求的所有不可变字段，防止复用 ingestionId 覆盖另一份内容。 */
  private def sameRequest(manifest: KnowledgeIndexManifest, request: BeginKnowledgeIndex): Boolean =
    request.targetProfileId.forall(_ == manifest.build.profileId) &&
      manifest.build.lineage == request.lineage &&
      manifest.build.buildSpec.sha256 == request.buildSpec.sha256 &&
      manifest.sourceUri == request.sourceUri &&
      manifest.permissions == request.permissions &&
      KnowledgeIndexer.requestMetadata(manifest.metadata) == KnowledgeIndexer.requestMetadata(
        request.metadata
      )

  /** 判断当前 active 版本是否满足调用方前置条件。 */
  private def matchesExpectation(expectation: ActiveVersionExpectation, active: Option[Long]): Boolean =
    expectation match
      case ActiveVersionExpectation.AnyVersion      => true
      case ActiveVersionExpectation.NoActiveVersion => active.isEmpty
      case ActiveVersionExpectation.Exact(version)  => active.contains(version)

object InMemoryKnowledgeIndexStore:
  /** 终止状态的 Profile 永远不再接受写入或激活。 */
  val SealedStatuses: Set[IndexProfileStatus] =
    Set(IndexProfileStatus.Failed, IndexProfileStatus.Retired, IndexProfileStatus.Cancelled)

  final private case class SpaceRecord(activeProfileId: Option[IndexProfileId] = None, revision: Long = 0L)

  final private case class ProfileKey(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId
  )
  private object ProfileKey:
    def apply(key: KnowledgeDocumentKey, profileId: IndexProfileId): ProfileKey =
      ProfileKey(key.tenantId, key.knowledgeSpaceId, profileId)

  final private case class ProfileRecord(
      spec: IndexBuildSpec,
      status: IndexProfileStatus,
      publicationCensusSha256: Option[String] = None
  )

  /** 与 `agent_knowledge_withdrawn` 同一粒度：租户、空间、文档、来源修订。 */
  final private case class Withdrawal(key: KnowledgeDocumentKey, sourceRevisionId: String)

  /** 内部状态把 manifest、暂存块和已发布快照分开，模拟 PostgreSQL 三类表的可见性边界。 */
  final private case class State(
      manifests: Map[(KnowledgeDocumentKey, Long), KnowledgeIndexManifest] = Map.empty,
      staged: Map[(KnowledgeDocumentKey, Long), Map[String, IndexedChunk]] = Map.empty,
      published: Map[KnowledgeDocumentKey, Chunk[IndexedChunk]] = Map.empty,
      withdrawn: Set[Withdrawal] = Set.empty,
      cacheEpoch: Long = 0L,
      spaces: Map[(TenantId, KnowledgeSpaceId), SpaceRecord] = Map.empty,
      profiles: Map[ProfileKey, ProfileRecord] = Map.empty
  ):
    def activeProfile(tenantId: TenantId, spaceId: KnowledgeSpaceId): Option[IndexProfileId] =
      spaces.get(tenantId -> spaceId).flatMap(_.activeProfileId)

    def activeManifest(key: KnowledgeDocumentKey): Option[KnowledgeIndexManifest] =
      activeProfile(key.tenantId, key.knowledgeSpaceId).flatMap(profile =>
        manifests.values.find(m => m.build.key == key && m.active && m.build.profileId == profile)
      )

    def profileManifests(
        tenantId: TenantId,
        spaceId: KnowledgeSpaceId,
        profileId: IndexProfileId
    ): Chunk[KnowledgeIndexManifest] =
      Chunk.fromIterable(
        manifests.values.filter(m =>
          m.build.key.tenantId == tenantId && m.build.knowledgeSpaceId == spaceId && m.build.profileId == profileId
        )
      )

    def corpusDiff(tenantId: TenantId, spaceId: KnowledgeSpaceId, target: IndexProfileId): ProfileCorpusDiff =
      val active = activeProfile(tenantId, spaceId).fold(Chunk.empty[KnowledgeIndexManifest])(
        profileManifests(tenantId, spaceId, _)
      )
      ProfilePublication.corpusDiff(active, profileManifests(tenantId, spaceId, target))

    /** Profile 写入规则：active/building 可写；superseded 仅接受显式目标；终止状态封存；规格必须一致。 */
    def writableProfile(
        key: KnowledgeDocumentKey,
        profileId: IndexProfileId,
        spec: IndexBuildSpec,
        explicit: Boolean
    ): IO[RetrievalError, Unit] =
      profiles.get(ProfileKey(key, profileId)) match
        case None                                              => ZIO.unit
        case Some(record) if record.spec.sha256 != spec.sha256 =>
          ZIO.fail(AgentError.RetrievalFailed(s"Profile 构建规格不一致: ${profileId.value}"))
        case Some(record) if SealedStatuses.contains(record.status) =>
          ZIO.fail(AgentError.RetrievalFailed(s"Profile 已终止，不能写入: ${profileId.value}"))
        case Some(record) if record.status == IndexProfileStatus.Superseded && !explicit =>
          ZIO.fail(
            AgentError.RetrievalFailed(
              s"Profile 已被替代；补齐回滚目标必须显式指定 targetProfileId: ${profileId.value}"
            )
          )
        case Some(_) => ZIO.unit

    /** 未钉住 Profile 且空间尚无 active 时为 None；带 Profile 的块此时不可见。 */
    def pinnedProfile(scope: RetrievalScope): Option[IndexProfileId] =
      scope.pinnedProfileId.orElse(activeProfile(scope.tenantId, scope.spaceId))

    def visible(scope: RetrievalScope, pin: Option[IndexProfileId]): Iterator[IndexedChunk] =
      published.iterator
        .filter((key, _) => key.tenantId == scope.tenantId && key.knowledgeSpaceId == scope.spaceId)
        .flatMap(_._2.iterator)
        .filter(item =>
          item.chunk.permissions.subsetOf(scope.permissions) &&
            item.chunk.profileId.forall(profile => pin.contains(profile)) &&
            !isWithdrawn(item.chunk)
        )

    def isWithdrawn(chunk: DocumentChunk): Boolean =
      chunk.sourceRevisionId.exists(revision =>
        withdrawn.contains(
          Withdrawal(
            KnowledgeDocumentKey(
              chunk.tenantId,
              chunk.documentId,
              chunk.knowledgeSpaceId.getOrElse(KnowledgeSpaceId.Default)
            ),
            revision
          )
        )
      )

  /** 创建可直接在测试中检查 `published` 的具体实现。 */
  def make: UIO[InMemoryKnowledgeIndexStore] =
    Ref.Synchronized.make(State()).map(InMemoryKnowledgeIndexStore(_))

  /** 以接口类型暴露的标准 ZLayer。 */
  val layer: ULayer[KnowledgeIndexStore] = ZLayer.fromZIO(make)

  /** 本地开发的推荐同源组合层。
    *
    * 同一个实例同时承担版本化知识发布和查询，确保 `KnowledgeIndexer` 激活的新版本立即成为 `Retriever` 的唯一可见快照。生产环境使用
    * `PostgresAgentPersistence.knowledge` 获得相同服务形状。
    */
  val knowledge: ULayer[KnowledgeIndexStore & VectorStore] =
    ZLayer.fromZIOEnvironment(
      make.map(store => ZEnvironment[KnowledgeIndexStore](store).add[VectorStore](store))
    )
