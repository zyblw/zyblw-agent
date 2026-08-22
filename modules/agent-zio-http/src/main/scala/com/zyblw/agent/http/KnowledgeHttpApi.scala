package com.zyblw.agent.http

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** 稳定知识 HTTP 面。tenant 只来自 `AgentRequestContextResolver`，请求体与查询参数不得覆盖。 */
final class KnowledgeHttpApi(
    knowledge: KnowledgeService,
    contexts: AgentRequestContextResolver
):
  val routes: Routes[Any, Nothing] = (Routes(
    AgentHttpContract.postKnowledgeDocumentsPattern -> handler { (request: Request) =>
      respond {
        for
          actor      <- authorizeWrite(request)
          tenant     <- KnowledgeAuthorization.requireTenant(actor)
          submission <- parseIngestion(request, tenant, KnowledgeAuthorization.indexPermissions(actor.scopes))
          job        <- knowledge.submitIngestion(submission, actorLabel(actor))
        yield Response(status = Status.Accepted, body = Body.fromString(toIngestion(job).toJson))
          .addHeader(Header.ContentType(MediaType.application.json))
      }
    },
    AgentHttpContract.getKnowledgeDocumentsPattern -> handler { (request: Request) =>
      respond {
        for
          actor  <- authorizeRead(request)
          tenant <- KnowledgeAuthorization.requireTenant(actor)
          page   <- knowledge
            .documents(tenant, intParam(request, "limit", 50, 200), request.queryParam("cursor"))
        yield Response.json(toPage(page).toJson)
      }
    },
    AgentHttpContract.getKnowledgeDocumentPattern -> handler { (documentId: String, request: Request) =>
      respond {
        for
          actor  <- authorizeRead(request)
          tenant <- KnowledgeAuthorization.requireTenant(actor)
          view   <- knowledge
            .document(tenant, documentId)
            .someOrFail(AgentError.PersistenceFailure(s"知识文档不存在: $documentId"))
        yield Response.json(toSummary(view).toJson)
      }
    },
    AgentHttpContract.deleteKnowledgeDocumentPattern -> handler { (documentId: String, request: Request) =>
      respond {
        for
          actor  <- authorizeWrite(request)
          tenant <- KnowledgeAuthorization.requireTenant(actor)
          body   <- decodeJson[KnowledgeRetireBody](request)
          _      <- knowledge.retire(tenant, documentId, body.expectedActiveVersion)
        yield Response.status(Status.NoContent)
      }
    },
    AgentHttpContract.postKnowledgeSearchPattern -> handler { (request: Request) =>
      respond {
        for
          actor  <- authorizeRead(request)
          tenant <- KnowledgeAuthorization.requireTenant(actor)
          raw    <- HttpRequestBody.readJson(request)
          _      <- rejectCallerOverride(raw)
          body   <- ZIO
            .fromEither(raw.fromJson[KnowledgeSearchBody])
            .mapError(AgentError.InvalidConfiguration(_))
          result <- knowledge.search(
            tenant,
            actor.scopes,
            KnowledgeRetrievalRequest(
              query = body.query,
              tenantId = tenant,
              permissions = actor.scopes,
              limit = body.limit.getOrElse(5),
              mode = body.mode.getOrElse("hybrid"),
              documentIds = body.documentIds.toSet,
              chunkIds = body.chunkIds.toSet,
              pages = body.pages.toSet,
              headingPrefix = body.headingPrefix,
              metadataEquals = body.metadataEquals
            )
          )
        yield Response.json(toSearch(result).toJson)
      }
    },
    AgentHttpContract.getKnowledgeIngestionPattern -> handler { (jobId: String, request: Request) =>
      respond {
        for
          actor  <- authorizeRead(request)
          tenant <- KnowledgeAuthorization.requireTenant(actor)
          job    <- knowledge
            .ingestionJob(tenant, jobId)
            .someOrFail(AgentError.PersistenceFailure(s"摄入任务不存在: $jobId"))
        yield Response.json(toIngestion(job).toJson)
      }
    },
    AgentHttpContract.postKnowledgeReindexPattern -> handler { (request: Request) =>
      respond {
        for
          actor  <- authorizeAdmin(request)
          tenant <- KnowledgeAuthorization.requireTenant(actor)
          body   <- decodeOrDefault(request, KnowledgeReindexBody())
          report <- knowledge.reindex(
            tenant,
            KnowledgeAuthorization.indexPermissions(actor.scopes),
            body.afterDocumentId,
            body.limit
          )
        yield Response.json(
          KnowledgeReindexResponse(
            report.items
              .map(item => KnowledgeReindexItemHttp(item.documentId, item.status, item.detail))
              .toList,
            report.nextDocumentId,
            report.hasMore
          ).toJson
        )
      }
    }
  )) @@ HandlerAspect.addHeader(AgentHttpProtocol.ApiVersionHeader, AgentHttpProtocol.ApiVersionHeaderValue)

  private def authorizeRead(request: Request): IO[AgentError, RunContext] =
    contexts.resolve(request).tap(KnowledgeAuthorization.requireRead)

  private def authorizeWrite(request: Request): IO[AgentError, RunContext] =
    contexts.resolve(request).tap(KnowledgeAuthorization.requireWrite)

  private def authorizeAdmin(request: Request): IO[AgentError, RunContext] =
    contexts.resolve(request).tap(KnowledgeAuthorization.requireAdmin)

  private def rejectCallerOverride(raw: String): IO[AgentError, Unit] =
    ZIO
      .fromEither(raw.fromJson[Json])
      .mapError(AgentError.InvalidConfiguration(_))
      .flatMap {
        case Json.Obj(fields) =>
          val keys = fields.map(_._1).toSet
          ZIO
            .fail(AgentError.InvalidConfiguration("tenantId/permissions 不能出现在知识检索请求体"))
            .when(keys.contains("tenantId") || keys.contains("permissions"))
            .unit
        case _ => ZIO.unit
      }

  private def parseIngestion(
      request: Request,
      tenantId: String,
      permissions: Set[String]
  ): IO[AgentError, IngestionSubmission] =
    for
      fileName <- requiredParam(request, "fileName")
      _        <- validateText("fileName", fileName, 400)
      mediaType = request.queryParam("mediaType").map(_.trim).filter(_.nonEmpty)
      extractionMode <- validatedExtractionMode(request)
      content        <- HttpRequestBody.readBytes(request, KnowledgeAdminService.MaxUploadBytes.toLong)
      _              <- ZIO.fail(AgentError.InvalidConfiguration("摄入正文不能为空")).when(content.isEmpty)
    yield IngestionSubmission(
      fileName = fileName,
      mediaType = mediaType
        .orElse(request.header(Header.ContentType).map(_.mediaType.fullType))
        .getOrElse("application/octet-stream"),
      tenantId = tenantId,
      permissions = permissions,
      content = content,
      metadata = extractionMode.fold(Map.empty[String, String])(mode => Map("extractionMode" -> mode))
    )

  private def decodeJson[A: JsonDecoder](request: Request): IO[AgentError, A] =
    HttpRequestBody
      .readJson(request)
      .flatMap(value => ZIO.fromEither(value.fromJson[A]).mapError(AgentError.InvalidConfiguration(_)))

  private def decodeOrDefault[A: JsonDecoder](request: Request, default: => A): IO[AgentError, A] =
    HttpRequestBody
      .readJson(request)
      .flatMap(value =>
        if value.trim.isEmpty then ZIO.succeed(default)
        else ZIO.fromEither(value.fromJson[A]).mapError(AgentError.InvalidConfiguration(_))
      )

  private def requiredParam(request: Request, name: String): IO[AgentError, String] =
    ZIO
      .fromOption(request.queryParam(name).map(_.trim).filter(_.nonEmpty))
      .orElseFail(AgentError.InvalidConfiguration(s"缺少必填查询参数 $name"))

  private def intParam(request: Request, name: String, default: Int, max: Int): Int =
    request.queryParam(name).flatMap(_.trim.toIntOption).getOrElse(default).max(1).min(max)

  private def validateText(field: String, value: String, maxChars: Int): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidConfiguration(s"$field 不能超过 $maxChars 个字符"))
      .when(value.codePointCount(0, value.length) > maxChars)
      .unit

  private def validatedExtractionMode(request: Request): IO[AgentError, Option[String]] =
    request.queryParam("extractionMode").map(_.trim).filter(_.nonEmpty) match
      case None      => ZIO.succeed(None)
      case Some(raw) =>
        val normalized = raw.toLowerCase(java.util.Locale.ROOT)
        if Set("auto", "text", "ocr", "vision").contains(normalized) then ZIO.succeed(Some(normalized))
        else ZIO.fail(AgentError.InvalidConfiguration("extractionMode 无效，允许 auto|text|ocr|vision"))

  private def actorLabel(actor: RunContext): String =
    (actor.tenantId, actor.userId) match
      case (Some(tenant), Some(user)) => s"$tenant/$user"
      case (Some(tenant), None)       => s"$tenant/-"
      case (None, Some(user))         => s"-/$user"
      case (None, None)               => "anonymous"

  private def toSummary(view: KnowledgeDocumentView): KnowledgeDocumentSummary =
    KnowledgeDocumentSummary(
      view.documentId,
      view.indexVersion,
      view.sourceUri,
      view.status,
      view.active,
      view.chunkCount,
      view.permissions,
      view.indexingStrategy,
      view.updatedAtEpochMilli
    )

  private def toPage(page: KnowledgeDocumentPage): KnowledgeDocumentPageView =
    KnowledgeDocumentPageView(page.items.map(toSummary).toList, page.nextCursor, page.hasMore)

  private def toIngestion(job: IngestionJobView): KnowledgeIngestionAccepted =
    KnowledgeIngestionAccepted(job.jobId, job.status.toString, job.progressPercent, job.documentId)

  private def toSearch(result: KnowledgeSearchResult): KnowledgeSearchResponse =
    KnowledgeSearchResponse(
      result.citations
        .map(citation =>
          CitationView(
            citation.id,
            citation.sourceUri,
            citation.excerpt,
            citation.score,
            citation.pageNumbers
          )
        )
        .toList,
      RetrievalEvidenceView(
        result.evidenceStatus,
        result.candidateCount,
        result.acceptedCount,
        result.topAcceptedScore
      )
    )

  private def respond(effect: IO[AgentError, Response]): UIO[Response] =
    effect.catchAll(error => ZIO.succeed(HttpErrorResponse.from(error)))

object KnowledgeHttpApi:
  val layer: URLayer[KnowledgeService & AgentRequestContextResolver, KnowledgeHttpApi] =
    ZLayer.fromFunction(KnowledgeHttpApi.apply)
