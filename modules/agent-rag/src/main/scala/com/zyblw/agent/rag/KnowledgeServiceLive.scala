package com.zyblw.agent.rag

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import zio.*

/** 业务知识面：tenant 由 HTTP/宿主传入，检索走带 mode/filter 的管理检索，重建委托 `KnowledgeReindexService`。 */
final class KnowledgeServiceLive(
    admin: KnowledgeAdminService,
    store: KnowledgeIndexStore,
    reindexService: KnowledgeReindexService
) extends KnowledgeService:

  def documents(
      tenantId: String,
      limit: Int,
      cursor: Option[String]
  ): IO[AgentError, KnowledgeDocumentPage] =
    admin.documents(Some(tenantId), limit, cursor)

  def document(tenantId: String, documentId: String): IO[AgentError, Option[KnowledgeDocumentView]] =
    for
      tenant <- ZIO.fromEither(TenantId.fromString(tenantId)).mapError(AgentError.InvalidConfiguration(_))
      active <- store.active(KnowledgeDocumentKey(tenant, documentId))
    yield active.map(KnowledgeAdminLive.documentView)

  def retire(tenantId: String, documentId: String, expectedActiveVersion: Long): IO[AgentError, Unit] =
    admin.retire(tenantId, documentId, expectedActiveVersion)

  def submitIngestion(
      submission: IngestionSubmission,
      submittedBy: String
  ): IO[AgentError, IngestionJobView] =
    admin.submitIngestion(submission, submittedBy)

  def ingestionJob(tenantId: String, jobId: String): IO[AgentError, Option[IngestionJobView]] =
    admin.ingestionJob(jobId).map(_.filter(_.tenantId == tenantId))

  def search(
      tenantId: String,
      permissions: Set[String],
      request: KnowledgeRetrievalRequest
  ): IO[AgentError, KnowledgeSearchResult] =
    admin
      .retrieve(request.copy(tenantId = tenantId, permissions = permissions))
      .map { result =>
        KnowledgeSearchResult(
          result.citations,
          if result.citations.isEmpty then "NoAcceptedHits" else "Supported",
          result.hits.length,
          result.citations.length,
          result.citations.map(_.score).maxOption
        )
      }

  def reindex(
      tenantId: String,
      permissions: Set[String],
      afterDocumentId: Option[String],
      limit: Int
  ): IO[AgentError, KnowledgeReindexReportView] =
    for
      tenant <- ZIO.fromEither(TenantId.fromString(tenantId)).mapError(AgentError.InvalidConfiguration(_))
      report <- reindexService.reindex(
        KnowledgeReindexRequest(
          tenant,
          KnowledgeAuthorization.indexPermissions(permissions),
          limit,
          afterDocumentId
        )
      )
    yield KnowledgeReindexReportView(
      report.items.map(item => KnowledgeReindexItemView(item.documentId, item.status.toString, item.detail)),
      report.nextDocumentId,
      report.hasMore
    )

object KnowledgeServiceLive:
  val layer
      : URLayer[KnowledgeAdminService & KnowledgeIndexStore & KnowledgeReindexService, KnowledgeService] =
    ZLayer.fromFunction(KnowledgeServiceLive(_, _, _))
