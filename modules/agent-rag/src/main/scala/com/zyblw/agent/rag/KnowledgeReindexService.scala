package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 为换 tokenizer / embedding / 切分器准备的按文档原子重建。 */
final case class KnowledgeReindexRequest(
    tenantId: TenantId,
    permissions: Set[String],
    limit: Int = 32,
    afterDocumentId: Option[String] = None,
    knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId.Default,
    targetProfileId: Option[IndexProfileId] = None
):
  require(limit > 0 && limit <= 200, "reindex limit 必须位于 1..200")

enum KnowledgeReindexStatus:
  case Reindexed, Skipped, SourceUnavailable, Failed

final case class KnowledgeReindexItem(
    documentId: String,
    status: KnowledgeReindexStatus,
    detail: Option[String] = None
)

final case class KnowledgeReindexReport(
    items: Chunk[KnowledgeReindexItem],
    nextDocumentId: Option[String],
    hasMore: Boolean
)

trait KnowledgeSourceResolver:
  def load(tenantId: TenantId, documentId: String): IO[RetrievalError, Option[DocumentInput]]

object KnowledgeSourceResolver:
  /** 解析器可选提供的来源修订号；缺失时重建沿用原构建的来源修订。 */
  val SourceRevisionMetadata: String = "knowledge.sourceRevision"

  val unavailable: ULayer[KnowledgeSourceResolver] = ZLayer.succeed(
    new KnowledgeSourceResolver:
      def load(tenantId: TenantId, documentId: String): UIO[Option[DocumentInput]] =
        val _ = (tenantId, documentId)
        ZIO.succeed(None)
  )

final class KnowledgeReindexService(
    directory: KnowledgeIndexDirectory,
    store: KnowledgeIndexStore,
    ingestion: DocumentIngestionService,
    sources: KnowledgeSourceResolver,
    parallelism: Int = 2
):
  require(parallelism > 0 && parallelism <= 8, "reindex parallelism 必须位于 1..8")

  def reindex(request: KnowledgeReindexRequest): IO[RetrievalError, KnowledgeReindexReport] =
    for
      cursor <- ZIO.foreach(request.afterDocumentId) { encoded =>
        ZIO.fromEither(KnowledgeIndexCursor.decode(encoded)).mapError(AgentError.RetrievalFailed(_))
      }
      activeProfile <- store.resolveActiveProfile(request.tenantId, request.knowledgeSpaceId)
      page          <- directory.list(Some(request.tenantId), request.limit, cursor)
      // 只从请求开始时的 active Profile 枚举权威 corpus；失败构建、历史版本和并行 Profile 都不能成为重建来源。
      targets = page.items
        .filter(manifest =>
          manifest.build.knowledgeSpaceId == request.knowledgeSpaceId &&
            activeProfile.contains(manifest.build.profileId) &&
            manifest.status == KnowledgeIndexStatus.Ready &&
            manifest.active
        )
        .distinctBy(_.build.key.documentId)
      items <- ZIO
        .foreachPar(targets)(manifest => reindexOne(request, manifest))
        .withParallelism(parallelism)
    yield KnowledgeReindexReport(
      items,
      page.nextCursor.map(_.encoded),
      page.hasMore
    )

  private def reindexOne(
      request: KnowledgeReindexRequest,
      manifest: KnowledgeIndexManifest
  ): UIO[KnowledgeReindexItem] =
    val documentId = manifest.build.key.documentId
    sources
      .load(request.tenantId, documentId)
      .flatMap {
        case None =>
          ZIO.succeed(KnowledgeReindexItem(documentId, KnowledgeReindexStatus.SourceUnavailable))
        case Some(input) =>
          // 默认保留原来源修订，使新 Profile 与 active Profile 的逻辑语料可比较；解析器给出新修订时视为内容更新。
          val source = input.metadata
            .get(KnowledgeSourceResolver.SourceRevisionMetadata)
            .map(_.trim)
            .filter(_.nonEmpty)
            .filterNot(_ == manifest.build.lineage.sourceRevisionId)
            .fold(SourceRevision.of(manifest.build.lineage))(revision =>
              SourceRevision(manifest.build.lineage.sourceId, revision)
            )
          ingestion
            .ingestOne(
              DocumentIngestionRequest(
                input,
                request.tenantId,
                manifest.permissions,
                "",
                ActiveVersionExpectation.AnyVersion,
                request.knowledgeSpaceId,
                request.targetProfileId,
                Some(source)
              )
            )
            .map {
              case DocumentIngestionOutcome.Indexed(_, _) =>
                KnowledgeReindexItem(documentId, KnowledgeReindexStatus.Reindexed)
              case DocumentIngestionOutcome.Failed(_, _, _, code, _) =>
                KnowledgeReindexItem(documentId, KnowledgeReindexStatus.Failed, Some(code))
            }
      }
      .catchAll(error =>
        ZIO.succeed(KnowledgeReindexItem(documentId, KnowledgeReindexStatus.Failed, Some(error.message)))
      )

object KnowledgeReindexService:
  val layer: URLayer[
    KnowledgeIndexDirectory & KnowledgeIndexStore & DocumentIngestionService & KnowledgeSourceResolver,
    KnowledgeReindexService
  ] =
    ZLayer.fromFunction(KnowledgeReindexService(_, _, _, _))
