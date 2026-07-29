package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*

/** `KnowledgeIndexStore` 的确定性内存实现。
  *
  * 它用于 ZIO Test、示例和无 PostgreSQL 的本地开发，所有状态迁移通过 `Ref.Synchronized` 原子完成。生产环境 仍应使用 PostgreSQL
  * Adapter，因为进程退出会丢失这里的 manifest、暂存块和发布快照。
  */
final class InMemoryKnowledgeIndexStore private (
    state: Ref.Synchronized[InMemoryKnowledgeIndexStore.State]
) extends KnowledgeIndexStore,
      VectorStore:

  /** 立即写入本地已发布快照。
    *
    * 该入口只用于直接测试 `VectorStore` SPI；正常知识摄取应使用 `KnowledgeIndexer` 的 Building→stage→activate 协议。相同
    * tenant/document/chunk ID 的条目被原子替换。
    */
  override def upsert(chunks: Chunk[IndexedChunk]): UIO[Unit] =
    state.update { current =>
      val updated = chunks
        .groupBy(item => KnowledgeDocumentKey(item.chunk.tenantId, item.chunk.documentId))
        .foldLeft(
          current.published
        ) { case (published, (key, incoming)) =>
          val incomingIds = incoming.map(_.chunk.id).toSet
          val retained    =
            published.getOrElse(key, Chunk.empty).filterNot(item => incomingIds.contains(item.chunk.id))
          published.updated(key, retained ++ incoming)
        }
      current.copy(published = updated)
    }

  /** 对当前 active 快照执行 tenant/permission 前置过滤，再计算 cosine。
    *
    * 该实现让本地开发与生产 PostgreSQL 使用同一套 Loader→Indexer→Retriever 接线，不再要求示例绕过正式知识发布协议。
    */
  override def search(
      query: Embedding,
      scope: RetrievalScope,
      limit: Int
  ): UIO[Chunk[RetrievalHit]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else
      state.get.map { current =>
        Chunk.fromIterable(
          current.published.valuesIterator
            .flatMap(_.iterator)
            .filter(item =>
              item.chunk.tenantId == scope.tenantId &&
                item.chunk.permissions.subsetOf(scope.permissions)
            )
            .map(item => RetrievalHit(item.chunk, cosine(query, item.embedding)))
            .toVector
            .sortBy(hit => (-hit.score, hit.chunk.id))
            .take(limit)
        )
      }

  /** 删除指定租户文档的本地发布快照。耐久业务应优先调用 `KnowledgeIndexStore.retire` 保留 manifest 状态。 */
  override def deleteByDocument(documentId: String, tenantId: TenantId): UIO[Unit] =
    state.update(current =>
      current.copy(published = current.published - KnowledgeDocumentKey(tenantId, documentId))
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
                val resumed = existing.copy(
                  status = KnowledgeIndexStatus.Building,
                  failureCode = None,
                  updatedAt = now
                )
                val key = existing.build.key -> existing.build.version
                ZIO.succeed(
                  existing.build -> current.copy(
                    manifests = current.manifests.updated(key, resumed),
                    // 失败可能发生在最后一个批次之前；重试从空暂存区开始，避免残留旧切分块。
                    staged = current.staged - key
                  )
                )
              case KnowledgeIndexStatus.Superseded =>
                ZIO.fail(
                  AgentError.RetrievalFailed(
                    s"knowledge ingestion 已被较新版本替代: ${request.key.documentId}"
                  )
                )
              case KnowledgeIndexStatus.Retired =>
                ZIO.fail(
                  AgentError.RetrievalFailed(
                    s"knowledge ingestion 已被下线: ${request.key.documentId}"
                  )
                )
              case _ => ZIO.succeed(existing.build -> current)
          case Some(_) =>
            ZIO.fail(
              AgentError.RetrievalFailed(
                s"knowledge ingestionId 已绑定不同请求: ${request.key.documentId}"
              )
            )
          case None =>
            val activeVersion = current.manifests.values
              .find(manifest => manifest.build.key == request.key && manifest.active)
              .map(_.build.version)
            if !matchesExpectation(request.expectation, activeVersion) then
              ZIO.fail(
                AgentError.RetrievalFailed(
                  s"knowledge active version 前置条件失败: ${request.key.documentId}"
                )
              )
            else
              val nextVersion = current.manifests.keysIterator
                .collect { case (key, version) if key == request.key => version }
                .maxOption
                .getOrElse(0L) + 1L
              val build = KnowledgeIndexBuild(
                request.key,
                nextVersion,
                request.ingestionId,
                request.contentHash,
                request.embedding,
                request.indexingStrategy
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
                updatedAt = now
              )
              val updated =
                current.copy(manifests = current.manifests.updated(request.key -> nextVersion, manifest))
              ZIO.succeed(build -> updated)
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
            chunk.indexVersion != build.version ||
            indexed.embedding.values.length != build.embedding.dimension
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

  /** 原子校验总数、废弃旧 active 版本并发布新快照。 */
  def activate(
      build: KnowledgeIndexBuild,
      expectedChunkCount: Int
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    if expectedChunkCount < 0 then ZIO.fail(AgentError.RetrievalFailed("expectedChunkCount 不能为负数"))
    else
      Clock.instant.flatMap { now =>
        state.modifyZIO { current =>
          val key = build.key -> build.version
          current.manifests.get(key) match
            case Some(manifest) if manifest.status == KnowledgeIndexStatus.Ready && manifest.active =>
              ZIO.succeed(manifest -> current)
            case Some(manifest) if manifest.status != KnowledgeIndexStatus.Building =>
              ZIO.fail(AgentError.RetrievalFailed(s"knowledge build 状态不允许发布: ${manifest.status}"))
            case None           => ZIO.fail(AgentError.RetrievalFailed("knowledge build 不存在"))
            case Some(manifest) =>
              val staged = current.staged.getOrElse(key, Map.empty)
              if staged.size != expectedChunkCount then
                ZIO.fail(
                  AgentError.RetrievalFailed(
                    s"knowledge staged chunk 数量 ${staged.size} != $expectedChunkCount"
                  )
                )
              else
                val superseded = current.manifests.map { case (manifestKey, value) =>
                  if value.build.key == build.key && value.active then
                    manifestKey -> value.copy(
                      status = KnowledgeIndexStatus.Superseded,
                      active = false,
                      updatedAt = now
                    )
                  else manifestKey -> value
                }
                val ready = manifest.copy(
                  status = KnowledgeIndexStatus.Ready,
                  active = true,
                  chunkCount = staged.size,
                  failureCode = None,
                  updatedAt = now
                )
                val updated = current.copy(
                  manifests = superseded.updated(key, ready),
                  staged = current.staged - key,
                  published = current.published
                    .updated(build.key, Chunk.fromIterable(staged.values.toList.sortBy(_.chunk.id)))
                )
                ZIO.succeed(ready -> updated)
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

  /** 查找当前 active manifest。 */
  def active(key: KnowledgeDocumentKey): UIO[Option[KnowledgeIndexManifest]] =
    state.get.map(_.manifests.values.find(manifest => manifest.build.key == key && manifest.active))

  /** 按业务幂等键查找任意状态 manifest。 */
  def find(key: KnowledgeDocumentKey, ingestionId: String): UIO[Option[KnowledgeIndexManifest]] =
    state.get.map(
      _.manifests.values.find(manifest =>
        manifest.build.key == key && manifest.build.ingestionId == ingestionId
      )
    )

  /** 原子执行乐观下线：只有调用方读到的版本仍是 active 时才移除发布快照。 相同版本已经 Retired 时幂等返回；这样命令确认前崩溃不会把重试变成错误。
    */
  def retire(
      key: KnowledgeDocumentKey,
      expectedActiveVersion: Long
  ): IO[RetrievalError, KnowledgeIndexManifest] =
    if expectedActiveVersion <= 0L then ZIO.fail(AgentError.RetrievalFailed("expectedActiveVersion 必须为正数"))
    else
      Clock.instant.flatMap { now =>
        state.modifyZIO { current =>
          val versionKey = key -> expectedActiveVersion
          val active = current.manifests.values.find(manifest => manifest.build.key == key && manifest.active)
          active match
            case Some(manifest) if manifest.build.version != expectedActiveVersion =>
              ZIO.fail(AgentError.RetrievalFailed("knowledge retire active version 前置条件失败"))
            case Some(manifest) =>
              val retired = manifest.copy(
                status = KnowledgeIndexStatus.Retired,
                active = false,
                updatedAt = now
              )
              ZIO.succeed(
                retired -> current.copy(
                  manifests = current.manifests.updated(versionKey, retired),
                  published = current.published - key
                )
              )
            case None =>
              current.manifests.get(versionKey) match
                case Some(manifest) if manifest.status == KnowledgeIndexStatus.Retired =>
                  ZIO.succeed(manifest -> current)
                case _ => ZIO.fail(AgentError.RetrievalFailed("knowledge retire 目标不是当前 active 版本"))
        }
      }

  /** 按 updatedAt/version 稳定选择非活动终态，模拟 PostgreSQL 的有界 retention。 */
  def purgeInactive(updatedBefore: Instant, limit: Int): UIO[Long] =
    if limit <= 0 then ZIO.succeed(0L)
    else
      state.modify { current =>
        val removable = current.manifests.iterator
          .filter { case (_, manifest) =>
            !manifest.active && manifest.updatedAt.isBefore(updatedBefore) && Set(
              KnowledgeIndexStatus.Superseded,
              KnowledgeIndexStatus.Failed,
              KnowledgeIndexStatus.Retired
            ).contains(manifest.status)
          }
          .toVector
          .sortBy { case ((key, version), manifest) =>
            (manifest.updatedAt, key.tenantId.value, key.documentId, version)
          }
          .take(limit)
          .map(_._1)
          .toSet
        removable.size.toLong -> current.copy(
          manifests = current.manifests.removedAll(removable),
          staged = current.staged.removedAll(removable)
        )
      }

  /** 返回某文档最近一次发布的确定性块快照，仅供测试断言和本地调试。 */
  def published(key: KnowledgeDocumentKey): UIO[Chunk[IndexedChunk]] =
    state.get.map(_.published.getOrElse(key, Chunk.empty))

  private def cosine(left: Embedding, right: Embedding): Double =
    if left.values.length != right.values.length then 0.0
    else
      val pairs = left.values.zip(right.values)
      val dot   = pairs.foldLeft(0.0)((sum, pair) => sum + pair._1.toDouble * pair._2.toDouble)
      val normL = math.sqrt(left.values.foldLeft(0.0)((sum, value) => sum + value.toDouble * value.toDouble))
      val normR = math.sqrt(right.values.foldLeft(0.0)((sum, value) => sum + value.toDouble * value.toDouble))
      if normL == 0.0 || normR == 0.0 then 0.0 else dot / (normL * normR)

  /** 比较幂等请求的所有不可变字段，防止复用 ingestionId 覆盖另一份内容。 */
  private def sameRequest(manifest: KnowledgeIndexManifest, request: BeginKnowledgeIndex): Boolean =
    manifest.build.contentHash == request.contentHash &&
      manifest.build.embedding == request.embedding &&
      manifest.build.indexingStrategy == request.indexingStrategy &&
      manifest.sourceUri == request.sourceUri &&
      manifest.permissions == request.permissions &&
      manifest.metadata == request.metadata

  /** 判断当前 active 版本是否满足调用方前置条件。 */
  private def matchesExpectation(expectation: ActiveVersionExpectation, active: Option[Long]): Boolean =
    expectation match
      case ActiveVersionExpectation.AnyVersion      => true
      case ActiveVersionExpectation.NoActiveVersion => active.isEmpty
      case ActiveVersionExpectation.Exact(version)  => active.contains(version)

object InMemoryKnowledgeIndexStore:
  /** 内部状态把 manifest、暂存块和已发布快照分开，模拟 PostgreSQL 三类表的可见性边界。 */
  final private case class State(
      manifests: Map[(KnowledgeDocumentKey, Long), KnowledgeIndexManifest] = Map.empty,
      staged: Map[(KnowledgeDocumentKey, Long), Map[String, IndexedChunk]] = Map.empty,
      published: Map[KnowledgeDocumentKey, Chunk[IndexedChunk]] = Map.empty
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
