package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** 为换 tokenizer / embedding / 切分器准备的按文档原子重建。 */
final case class KnowledgeReindexRequest(
    tenantId: TenantId,
    permissions: Set[String],
    limit: Int = 32,
    afterDocumentId: Option[String] = None
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
  val unavailable: ULayer[KnowledgeSourceResolver] = ZLayer.succeed(
    new KnowledgeSourceResolver:
      def load(tenantId: TenantId, documentId: String): UIO[Option[DocumentInput]] =
        val _ = (tenantId, documentId)
        ZIO.succeed(None)
  )

final class KnowledgeReindexService(
    directory: KnowledgeIndexDirectory,
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
      page <- directory.list(Some(request.tenantId), request.limit, cursor)
      // 目录按版本列出；同一 documentId 只重建最新清单，避免二次扫描把历史版本再摄入一遍。
      targets = page.items.distinctBy(_.build.key.documentId)
      items <- ZIO
        .foreachPar(targets) { manifest =>
          reindexOne(request, manifest.build.key.documentId)
        }
        .withParallelism(parallelism)
    yield KnowledgeReindexReport(
      items,
      page.nextCursor.map(_.encoded),
      page.hasMore
    )

  private def reindexOne(
      request: KnowledgeReindexRequest,
      documentId: String
  ): UIO[KnowledgeReindexItem] =
    sources
      .load(request.tenantId, documentId)
      .flatMap {
        case None =>
          ZIO.succeed(KnowledgeReindexItem(documentId, KnowledgeReindexStatus.SourceUnavailable))
        case Some(input) =>
          val ingestionId =
            s"reindex-${documentId.take(80)}-${java.lang.Long.toHexString(java.lang.System.nanoTime())}"
          ingestion
            .ingestOne(
              DocumentIngestionRequest(
                input,
                request.tenantId,
                request.permissions,
                ingestionId,
                ActiveVersionExpectation.AnyVersion
              )
            )
            .as(KnowledgeReindexItem(documentId, KnowledgeReindexStatus.Reindexed))
      }
      .catchAll(error =>
        ZIO.succeed(KnowledgeReindexItem(documentId, KnowledgeReindexStatus.Failed, Some(error.message)))
      )

object KnowledgeReindexService:
  val layer: URLayer[
    KnowledgeIndexDirectory & DocumentIngestionService & KnowledgeSourceResolver,
    KnowledgeReindexService
  ] =
    ZLayer.fromFunction(KnowledgeReindexService(_, _, _))
