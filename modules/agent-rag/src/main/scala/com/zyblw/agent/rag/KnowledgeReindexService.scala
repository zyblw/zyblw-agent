package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 为换 tokenizer / embedding / 切分器准备的按文档原子重建。 */
final case class KnowledgeReindexRequest(
    tenantId: TenantId,
    permissions: Set[String],
    limit: Int = 32,
    afterDocumentId: Option[String] = None,
    knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId("default"),
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
  /** Resolver-provided stable source revision/hash used to make a reindex retry idempotent. */
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
          val target = request.targetProfileId
            .map(_.value)
            .getOrElse(manifest.build.profileId.value)
          val sourceRevision = input.metadata
            .get(KnowledgeSourceResolver.SourceRevisionMetadata)
            .orElse(input.metadata.get("contentHash"))
            .filter(_.trim.nonEmpty)
            .getOrElse(manifest.build.contentHash)
          val ingestionId = s"reindex-${IngestionKeys.sha256(
              s"${request.knowledgeSpaceId.value}\n$target\n$documentId\n$sourceRevision"
            )}"
          ingestion
            .ingestOne(
              DocumentIngestionRequest(
                input,
                request.tenantId,
                manifest.permissions,
                ingestionId,
                ActiveVersionExpectation.AnyVersion,
                request.knowledgeSpaceId,
                request.targetProfileId
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
