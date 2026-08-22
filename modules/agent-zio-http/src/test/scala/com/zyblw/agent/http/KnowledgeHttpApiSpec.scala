package com.zyblw.agent.http

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.*
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

/** 稳定知识 HTTP 面：身份与 tenant 只来自 resolver，模型/客户端不得覆盖。 */
object KnowledgeHttpApiSpec extends ZIOSpecDefault:
  private val knowledge = URL.root / "api" / "v1" / "knowledge"

  private val contexts = new AgentRequestContextResolver:
    def resolve(request: Request): IO[AgentError, RunContext] = ZIO.succeed(
      RunContext(
        tenantId = request.rawHeader("X-Test-Tenant"),
        userId = request.rawHeader("X-Test-User"),
        scopes = request
          .rawHeader("X-Test-Scopes")
          .map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet)
          .getOrElse(Set.empty)
      )
    )

  final private class RecordingKnowledge(val calls: Ref[Chunk[String]]) extends KnowledgeService:
    def documents(tenantId: String, limit: Int, cursor: Option[String]) =
      calls.update(_ :+ s"documents:$tenantId").as(KnowledgeDocumentPage(Chunk.empty, None, hasMore = false))

    def document(tenantId: String, documentId: String) =
      calls.update(_ :+ s"document:$tenantId:$documentId").as(None)

    def retire(tenantId: String, documentId: String, expectedActiveVersion: Long) =
      calls.update(_ :+ s"retire:$tenantId:$documentId:$expectedActiveVersion").unit

    def submitIngestion(submission: IngestionSubmission, submittedBy: String) =
      calls
        .update(_ :+ s"ingest:${submission.tenantId}:${submission.fileName}:${submission.content.length}")
        .as(
          IngestionJobView(
            jobId = "job-1",
            tenantId = submission.tenantId,
            sourceUri = s"upload://${submission.fileName}",
            fileName = submission.fileName,
            mediaType = submission.mediaType,
            status = IngestionJobStatus.Queued,
            progressPercent = 0,
            documentId = None,
            indexVersion = None,
            chunkCount = None,
            failureCode = None,
            submittedBy = submittedBy,
            createdAtEpochMilli = 0L,
            updatedAtEpochMilli = 0L
          )
        )

    def ingestionJob(tenantId: String, jobId: String) =
      calls.update(_ :+ s"job:$tenantId:$jobId").as(None)

    def search(tenantId: String, permissions: Set[String], request: KnowledgeRetrievalRequest) =
      calls
        .update(_ :+ s"search:$tenantId:${request.mode}:${request.query}")
        .as(
          KnowledgeSearchResult(
            Chunk(KnowledgeCitationView("cite-1", "book://suwen", "阴阳者", 0.9, List(12))),
            "Supported",
            1,
            1,
            Some(0.9)
          )
        )

    def reindex(tenantId: String, permissions: Set[String], afterDocumentId: Option[String], limit: Int) =
      calls
        .update(_ :+ s"reindex:$tenantId")
        .as(KnowledgeReindexReportView(Chunk.empty, None, hasMore = false))

  private def api: UIO[(KnowledgeHttpApi, Ref[Chunk[String]])] =
    Ref.make(Chunk.empty[String]).map { calls =>
      KnowledgeHttpApi(new RecordingKnowledge(calls), contexts) -> calls
    }

  private def authed(request: Request, scopes: String*): Request =
    request
      .addHeader("X-Test-Tenant", "acme")
      .addHeader("X-Test-User", "reader-1")
      .addHeader("X-Test-Scopes", scopes.mkString(","))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeHttpApi")(
    test("缺少知识读 scope 时搜索返回 403 且不调用后端") {
      for
        tuple <- api
        (http, calls) = tuple
        response <- http.routes.runZIO(
          authed(Request.post(knowledge / "search", Body.fromString("""{"query":"阴阳"}""")))
        )
        recorded <- calls.get
      yield assertTrue(response.status == Status.Forbidden, recorded.isEmpty)
    },
    test("请求体出现 tenantId 时拒绝，不使用客户端租户") {
      for
        tuple <- api
        (http, calls) = tuple
        response <- http.routes.runZIO(
          authed(
            Request.post(
              knowledge / "search",
              Body.fromString("""{"query":"阴阳","tenantId":"other"}""")
            ),
            KnowledgeAuthorization.ReadScope
          )
        )
        recorded <- calls.get
      yield assertTrue(response.status == Status.BadRequest, recorded.isEmpty)
    },
    test("搜索只使用认证租户，并返回有界引用") {
      for
        tuple <- api
        (http, calls) = tuple
        response <- http.routes.runZIO(
          authed(
            Request.post(knowledge / "search", Body.fromString("""{"query":"阴阳","mode":"phrase"}""")),
            KnowledgeAuthorization.ReadScope
          )
        )
        body     <- response.body.asString
        view     <- ZIO.fromEither(body.fromJson[KnowledgeSearchResponse]).mapError(new RuntimeException(_))
        recorded <- calls.get
      yield assertTrue(
        response.status == Status.Ok,
        view.citations.head.pageNumbers == List(12),
        recorded == Chunk("search:acme:phrase:阴阳")
      )
    },
    test("上传从认证上下文取租户，忽略查询参数中的 tenantId") {
      for
        tuple <- api
        (http, calls) = tuple
        response <- http.routes.runZIO(
          authed(
            Request.post(
              (knowledge / "documents").addQueryParams("fileName=guide.md&tenantId=attacker"),
              Body.fromString("# 标题")
            ),
            KnowledgeAuthorization.WriteScope
          )
        )
        body <- response.body.asString
        job  <- ZIO.fromEither(body.fromJson[KnowledgeIngestionAccepted]).mapError(new RuntimeException(_))
        recorded <- calls.get
      yield assertTrue(
        response.status == Status.Accepted,
        job.jobId == "job-1",
        recorded == Chunk("ingest:acme:guide.md:8")
      )
    },
    test("重建要求 knowledge:admin") {
      for
        tuple <- api
        (http, calls) = tuple
        denied <- http.routes.runZIO(
          authed(
            Request.post(knowledge / "reindex", Body.fromString("{}")),
            KnowledgeAuthorization.WriteScope
          )
        )
        allowed <- http.routes.runZIO(
          authed(
            Request.post(knowledge / "reindex", Body.fromString("{}")),
            KnowledgeAuthorization.AdminScope
          )
        )
        recorded <- calls.get
      yield assertTrue(
        denied.status == Status.Forbidden,
        allowed.status == Status.Ok,
        recorded == Chunk("reindex:acme")
      )
    }
  )
