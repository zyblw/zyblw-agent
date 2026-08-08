package com.zyblw.agent.http

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.*
import zio.*
import zio.http.*
import zio.http.codec.PathCodec.string
import zio.json.*
import zio.stream.ZStream

/** 写入运行时配置覆盖的请求体。
  *
  * `expectedVersion` 是必填的：管理台必须先读到当前版本才能提交修改，这样两个管理员同时编辑时后提交的一方会收到 409 而不是静默覆盖对方。
  */
final case class RuntimeConfigUpdateRequest(
    expectedVersion: Long,
    overrides: RuntimeOverrides,
    reason: String
) derives JsonCodec

/** 退役某个知识索引版本的请求体。 */
final case class KnowledgeRetireRequest(tenantId: String, expectedActiveVersion: Long) derives JsonCodec

/** 检索沙盒请求体。 */
final case class KnowledgeRetrieveRequest(
    query: String,
    tenantId: String,
    permissions: List[String] = Nil,
    limit: Int = 5,
    rerank: Boolean = true,
    expandContext: Boolean = true
) derives JsonCodec

/** 管理台专用的低敏事件信封。
  *
  * 不能直接复用业务 `RunEventView` 的完整 JSON：业务投影允许携带最终输出和安全消息，而跨租户管理 scope 不应因此 获得业务正文。这里显式保留结构化状态、计数与工具名称，同时删除
  * `output` / `message`。
  */
final case class AdminApprovalEventView(
    approvalId: String,
    toolName: String,
    risk: String,
    requestedAtEpochMilli: Long
) derives JsonCodec

final case class AdminRunEventView(
    eventId: String,
    runId: String,
    sequence: Long,
    eventType: String,
    atEpochMilli: Long,
    status: Option[String],
    step: Option[Int],
    category: Option[String],
    stage: Option[String],
    stateVersion: Option[Long],
    usage: Option[UsageView],
    approval: Option[AdminApprovalEventView],
    tool: Option[ToolProgressView],
    context: Option[ContextUsageView]
) derives JsonCodec

object AdminRunEventView:
  def from(persisted: PersistedAgentEvent): AdminRunEventView =
    val event = AgentHttpProjection.event(persisted)
    AdminRunEventView(
      eventId = event.eventId,
      runId = event.runId,
      sequence = event.sequence,
      eventType = event.eventType,
      atEpochMilli = event.atEpochMilli,
      status = event.status,
      step = event.step,
      category = event.category,
      stage = event.stage,
      stateVersion = event.stateVersion,
      usage = event.usage,
      approval = event.approval.map(approval =>
        AdminApprovalEventView(
          approvalId = approval.approvalId,
          toolName = approval.toolName,
          risk = approval.risk,
          requestedAtEpochMilli = approval.requestedAtEpochMilli
        )
      ),
      tool = event.tool,
      context = event.context
    )

/** 管理面已装配能力的声明。
  *
  * 前端据此决定显示哪些页签。没有它，管理台只能靠对每个端点发一次请求看是不是 404 来推断能力，那既慢又会在日志里 制造一批无意义的错误。
  */
final case class AdminCapabilitiesView(
    apiVersion: Int,
    runDirectory: Boolean,
    runEventStream: Boolean,
    runtimeConfig: Boolean,
    queueOps: Boolean,
    knowledge: Boolean,
    evalTrends: Boolean,
    models: Boolean,
    observability: ObservabilityLinks
) derives JsonCodec

/** 宿主注入的管理面能力集合。
  *
  * 每项能力都是 `Option`，未提供的能力不会挂载路由，请求会自然得到 404。这比“挂载路由再返回 501”更好： 管理台的能力探测有唯一事实来源（`GET
  * /api/v1/admin/capabilities`），而不是散落在每个端点的运行时错误里。
  *
  * @param runs
  *   跨 Run 目录查询；生产需要 PostgreSQL Adapter
  * @param runEvents
  *   单 Run 的低敏耐久 SSE。它复用 Runtime 的权威事件事实源，但只在管理读权限通过后打开
  * @param config
  *   运行时配置覆盖读写
  * @param ops
  *   队列快照与死信重排
  * @param knowledge
  *   RAG 文档清单、检索沙盒与异步摄入
  * @param evals
  *   评测趋势只读
  * @param models
  *   已注册模型目录与连通性探活。模型的**切换**不在这里，而是通过 `config` 的覆盖写入完成，因此只装配 `models` 而不装配 `config`
  *   会得到一个只能看不能改的模型页——这是有意的组合，适合不希望运维改动模型的部署
  * @param observability
  *   外部观测系统深链配置
  */
final case class AdminCapabilities(
    runs: Option[RunDirectory] = None,
    runEvents: Option[RunEventAdminService] = None,
    config: Option[RuntimeSettingsService] = None,
    ops: Option[OpsAdminService] = None,
    knowledge: Option[KnowledgeAdminService] = None,
    evals: Option[EvalTrendReader] = None,
    models: Option[ModelAdminService] = None,
    observability: ObservabilityLinks = ObservabilityLinks()
)

/** 管理台的 ZIO HTTP Adapter。
  *
  * 路由全部挂在 `/api/v1/admin` 下，与业务 Run/Memory API 共享同一主版本和同一套错误映射，但在契约上是独立的 **Beta** 子面：它服务于运维界面，会随管理台功能演进，不进入
  * `AgentHttpContract` 的稳定 OpenAPI 承诺。
  *
  * 授权与业务路由不同。业务 Run 端点用“归属即可读”，管理端点看到的是跨租户聚合，因此一律要求显式管理 scope：
  *
  *   - 读取类要求 `agent:admin:read`（`agent:admin:write` 蕴含它）；
  *   - 改变部署行为的写入要求 `agent:admin:write`；
  *   - 会产生真实 Provider 费用的检索沙盒与文档摄入要求 `agent:admin:debug`，且不被写权限蕴含。
  *
  * 与 `AgentHttpApi` 一样，身份来自宿主的 `AgentRequestContextResolver`，框架不自带认证中间件。
  */
final class AdminHttpApi(
    capabilities: AdminCapabilities,
    contexts: AgentRequestContextResolver
):
  import AdminHttpApi.*

  /** 可与 `AgentHttpApi.routes` 使用 `++` 合并的管理面路由。 */
  val routes: Routes[Any, Nothing] =
    (metaRoutes ++ runRoutes ++ runEventRoutes ++ configRoutes ++ opsRoutes ++ knowledgeRoutes ++ evalRoutes ++
      modelRoutes) @@
      HandlerAspect.addHeader(AgentHttpProtocol.ApiVersionHeader, AgentHttpProtocol.ApiVersionHeaderValue)

  /** 能力声明与观测深链；只要求读权限，因为它不暴露任何业务数据。 */
  private def metaRoutes: Routes[Any, Nothing] = Routes(
    Method.GET / "api" / "v1" / "admin" / "capabilities" -> handler { (request: Request) =>
      respond {
        authorizeRead(request).as(
          Response.json(
            AdminCapabilitiesView(
              apiVersion = AgentHttpProtocol.MajorVersion,
              runDirectory = capabilities.runs.isDefined,
              runEventStream = capabilities.runEvents.isDefined,
              runtimeConfig = capabilities.config.isDefined,
              queueOps = capabilities.ops.isDefined,
              knowledge = capabilities.knowledge.isDefined,
              evalTrends = capabilities.evals.isDefined,
              models = capabilities.models.isDefined,
              observability = capabilities.observability
            ).toJson
          )
        )
      }
    }
  )

  /** Run 目录：列表与状态聚合。 */
  private def runRoutes: Routes[Any, Nothing] = capabilities.runs.fold(Routes.empty) { directory =>
    Routes(
      Method.GET / "api" / "v1" / "admin" / "runs" -> handler { (request: Request) =>
        respond {
          for
            _     <- authorizeRead(request)
            query <- parseRunQuery(request)
            page  <- directory.list(query)
          yield Response.json(page.toJson)
        }
      },
      Method.GET / "api" / "v1" / "admin" / "runs" / "overview" -> handler { (request: Request) =>
        respond {
          for
            _        <- authorizeRead(request)
            overview <- directory.overview(request.queryParam("tenantId").map(_.trim).filter(_.nonEmpty))
          yield Response.json(overview.toJson)
        }
      }
    )
  }

  /** 单 Run 低敏耐久事件流。
    *
    * 该路由留在 `/admin` 子面，避免 Dashboard 绕过 ADR 0017 的管理 API 边界去调用业务 Run 路由。事件投影与业务 SSE 共用同一
    * allow-list，不包含消息、Prompt、工具参数/结果或 Provider 正文。
    */
  private def runEventRoutes: Routes[Any, Nothing] = capabilities.runEvents.fold(Routes.empty) { eventService =>
    Routes(
      Method.GET / "api" / "v1" / "admin" / "runs" / string("runId") / "events" / "stream" ->
        handler { (runId: String, request: Request) =>
          respond {
            for
              _      <- authorizeRead(request)
              parsed <- ZIO.fromEither(RunId.fromString(runId)).mapError(AgentError.InvalidConfiguration(_))
              cursor <- parseLastEventId(request)
              stream <- eventService.open(parsed, cursor)
            yield durableEventResponse(stream)
          }
        }
    )
  }

  /** 把已预检的耐久事件流投影为可恢复 SSE；中途故障只能作为安全的 `stream_error` 发送。 */
  private def durableEventResponse(stream: ZStream[Any, AgentError, PersistedAgentEvent]): Response =
    val publicEvents = stream
      .map { persisted =>
        val publicEvent = AdminRunEventView.from(persisted)
        ServerSentEvent(
          data = publicEvent.toJson,
          eventType = Some(publicEvent.eventType),
          id = Some(persisted.sequence.toString)
        )
      }
      .catchAll { error =>
        val message = if error.safeToExpose then error.message else "智能体服务暂时无法完成请求"
        ZStream.succeed(
          ServerSentEvent(
            data = ErrorResponse(error.category.toString, message).toJson,
            eventType = Some("stream_error")
          )
        )
      }
    val heartbeats = ZStream.repeatZIO(ZIO.sleep(15.seconds).as(ServerSentEvent.heartbeat))
    Response
      .fromServerSentEvents(publicEvents.mergeHaltLeft(heartbeats))
      .addHeader("Cache-Control", "no-cache, no-transform")
      .addHeader("X-Accel-Buffering", "no")

  /** 解析 fetch/EventSource 的恢复游标；缺失表示从头读取。 */
  private def parseLastEventId(request: Request): IO[AgentError, Long] =
    request.rawHeader("Last-Event-ID").map(_.trim).filter(_.nonEmpty) match
      case None        => ZIO.succeed(-1L)
      case Some(value) =>
        ZIO
          .attempt(value.toLong)
          .mapError(_ => AgentError.InvalidConfiguration("Last-Event-ID 必须是事件 sequence 整数"))
          .flatMap(sequence =>
            if sequence >= -1L then ZIO.succeed(sequence)
            else ZIO.fail(AgentError.InvalidConfiguration("Last-Event-ID 不能小于 -1"))
          )

  /** 运行时配置覆盖：读取、CAS 写入与变更历史。 */
  private def configRoutes: Routes[Any, Nothing] = capabilities.config.fold(Routes.empty) { settings =>
    Routes(
      Method.GET / "api" / "v1" / "admin" / "config" -> handler { (request: Request) =>
        respond {
          for
            _    <- authorizeRead(request)
            view <- settings.view
          yield Response.json(view.toJson)
        }
      },
      Method.PUT / "api" / "v1" / "admin" / "config" -> handler { (request: Request) =>
        respond {
          for
            actor <- authorizeWrite(request)
            body  <- decodeJson[RuntimeConfigUpdateRequest](request)
            _     <- validateText("reason", body.reason, RuntimeOverrideRecord.MaxReasonLength)
            view  <- settings.update(body.expectedVersion, body.overrides, actorLabel(actor), body.reason)
          yield Response.json(view.toJson)
        }
      },
      Method.GET / "api" / "v1" / "admin" / "config" / "history" -> handler { (request: Request) =>
        respond {
          for
            _ <- authorizeRead(request)
            limit = intParam(request, "limit", 20, RuntimeOverrideStore.MaxHistoryLimit)
            records <- settings.history(limit)
          yield Response.json(records.toList.toJson)
        }
      }
    )
  }

  /** 队列运维：快照、死信清单与人工重排。 */
  private def opsRoutes: Routes[Any, Nothing] = capabilities.ops.fold(Routes.empty) { ops =>
    Routes(
      Method.GET / "api" / "v1" / "admin" / "ops" / "queue" -> handler { (request: Request) =>
        respond {
          for
            _        <- authorizeRead(request)
            snapshot <- ops.queueSnapshot
          yield Response.json(snapshot.toJson)
        }
      },
      Method.GET / "api" / "v1" / "admin" / "ops" / "dead-letters" -> handler { (request: Request) =>
        respond {
          for
            _ <- authorizeRead(request)
            limit = intParam(request, "limit", 50, OpsAdminService.MaxDeadLetterLimit)
            items <- ops.deadLetters(limit)
          yield Response.json(items.toList.toJson)
        }
      },
      Method.POST / "api" / "v1" / "admin" / "ops" / "dead-letters" / string("commandId") / "retry" ->
        handler { (commandId: String, request: Request) =>
          respond {
            for
              _      <- authorizeWrite(request)
              result <- ops.retryDeadLetter(commandId)
            yield Response.json(result.toJson)
          }
        }
    )
  }

  /** 知识库：索引清单、检索沙盒、退役与异步摄入。 */
  private def knowledgeRoutes: Routes[Any, Nothing] = capabilities.knowledge.fold(Routes.empty) { knowledge =>
    Routes(
      Method.GET / "api" / "v1" / "admin" / "knowledge" / "documents" -> handler { (request: Request) =>
        respond {
          for
            _    <- authorizeRead(request)
            page <- knowledge.documents(
              request.queryParam("tenantId").map(_.trim).filter(_.nonEmpty),
              intParam(request, "limit", 50, 200),
              request.queryParam("cursor").map(_.trim).filter(_.nonEmpty)
            )
          yield Response.json(page.toJson)
        }
      },
      Method.POST / "api" / "v1" / "admin" / "knowledge" / "retrieve" -> handler { (request: Request) =>
        respond {
          for
            _      <- authorizeDebug(request)
            body   <- decodeJson[KnowledgeRetrieveRequest](request)
            _      <- validateText("query", body.query, MaxQueryChars)
            _      <- validateText("tenantId", body.tenantId, MaxTenantChars)
            result <- knowledge.retrieve(
              KnowledgeRetrievalRequest(
                query = body.query,
                tenantId = body.tenantId,
                permissions = body.permissions.toSet,
                limit = body.limit.max(1).min(KnowledgeAdminService.MaxRetrievalLimit),
                rerank = body.rerank,
                expandContext = body.expandContext
              )
            )
          yield Response.json(result.toJson)
        }
      },
      Method.POST / "api" / "v1" / "admin" / "knowledge" / "documents" / string("documentId") / "retire" ->
        handler { (documentId: String, request: Request) =>
          respond {
            for
              _    <- authorizeWrite(request)
              body <- decodeJson[KnowledgeRetireRequest](request)
              _    <- validateText("tenantId", body.tenantId, MaxTenantChars)
              _    <- knowledge.retire(body.tenantId, documentId, body.expectedActiveVersion)
            yield Response.status(Status.NoContent)
          }
        },
      Method.POST / "api" / "v1" / "admin" / "knowledge" / "ingestions" -> handler { (request: Request) =>
        respond {
          for
            actor      <- authorizeDebug(request)
            submission <- parseIngestion(request)
            job        <- knowledge.submitIngestion(submission, actorLabel(actor))
          yield Response(status = Status.Accepted, body = Body.fromString(job.toJson))
            .addHeader(Header.ContentType(MediaType.application.json))
        }
      },
      Method.GET / "api" / "v1" / "admin" / "knowledge" / "ingestions" -> handler { (request: Request) =>
        respond {
          for
            _    <- authorizeRead(request)
            jobs <- knowledge.ingestionJobs(
              request.queryParam("tenantId").map(_.trim).filter(_.nonEmpty),
              intParam(request, "limit", 50, IngestionJobStore.MaxLimit)
            )
          yield Response.json(jobs.toList.toJson)
        }
      },
      Method.GET / "api" / "v1" / "admin" / "knowledge" / "ingestions" / string("jobId") ->
        handler { (jobId: String, request: Request) =>
          respond {
            for
              _   <- authorizeRead(request)
              job <- knowledge
                .ingestionJob(jobId)
                .someOrFail(AgentError.PersistenceFailure(s"摄入任务不存在: $jobId"))
            yield Response.json(job.toJson)
          }
        }
    )
  }

  /** 评测趋势：跟踪的套件与历史数据点。 */
  private def evalRoutes: Routes[Any, Nothing] = capabilities.evals.fold(Routes.empty) { evals =>
    Routes(
      Method.GET / "api" / "v1" / "admin" / "evals" / "suites" -> handler { (request: Request) =>
        respond {
          for
            _      <- authorizeRead(request)
            suites <- evals.suites
          yield Response.json(suites.toList.toJson)
        }
      },
      Method.GET / "api" / "v1" / "admin" / "evals" / "trend" -> handler { (request: Request) =>
        respond {
          for
            _        <- authorizeRead(request)
            identity <- parseSuiteIdentity(request)
            series <- evals.history(identity, intParam(request, "limit", 50, EvalTrendReader.MaxHistoryLimit))
          yield Response.json(series.toJson)
        }
      }
    )
  }

  /** 模型治理：已注册目录（读）与连通性探活（debug）。
    *
    * 探活要求 `agent:admin:debug` 而不是读权限：它会向 Provider 发一次真实调用并产生真实费用，与检索沙盒同类。 只读目录本身不触网，因此归在读权限下。
    */
  private def modelRoutes: Routes[Any, Nothing] = capabilities.models.fold(Routes.empty) { models =>
    Routes(
      Method.GET / "api" / "v1" / "admin" / "models" -> handler { (request: Request) =>
        respond {
          for
            _       <- authorizeRead(request)
            catalog <- models.catalog
          yield Response.json(catalog.toJson)
        }
      },
      Method.POST / "api" / "v1" / "admin" / "models" / "probe" -> handler { (request: Request) =>
        respond {
          for
            _      <- authorizeDebug(request)
            body   <- decodeJson[ModelProbeRequest](request)
            _      <- validateText("provider", body.provider, MaxProviderChars)
            _      <- ZIO.foreachDiscard(body.model)(validateText("model", _, MaxProviderChars))
            result <- models.probe(body)
          yield Response.json(result.toJson)
        }
      }
    )
  }

  /** 解析 Run 目录查询参数；非法状态名 fail-closed，不静默忽略。 */
  private def parseRunQuery(request: Request): IO[AgentError, RunDirectoryQuery] =
    for
      statuses <- ZIO.foreach(
        request
          .queryParams("status")
          .flatMap(_.split(",").toSeq)
          .map(_.trim)
          .filter(_.nonEmpty)
      )(parseRunStatus)
      cursor <- ZIO.foreach(request.queryParam("cursor").map(_.trim).filter(_.nonEmpty))(value =>
        ZIO.fromEither(RunDirectoryCursor.decode(value)).mapError(AgentError.InvalidConfiguration(_))
      )
    yield RunDirectoryQuery(
      tenantId = request.queryParam("tenantId").map(_.trim).filter(_.nonEmpty),
      agentId = request.queryParam("agentId").map(_.trim).filter(_.nonEmpty),
      statuses = statuses.toSet,
      awaitingApprovalOnly = request.queryParam("awaitingApproval").contains("true"),
      updatedAfterEpochMilli = longParam(request, "updatedAfter"),
      updatedBeforeEpochMilli = longParam(request, "updatedBefore"),
      cursor = cursor,
      limit = intParam(request, "limit", RunDirectory.DefaultLimit, RunDirectory.MaxLimit)
    )

  /** 解析评测趋势身份；四个字段都必填，缺一不可比较。 */
  private def parseSuiteIdentity(request: Request): IO[AgentError, EvalSuiteIdentityView] =
    for
      kind           <- requiredParam(request, "kind")
      suiteId        <- requiredParam(request, "suiteId")
      datasetId      <- requiredParam(request, "datasetId")
      datasetVersion <- requiredParam(request, "datasetVersion")
    yield EvalSuiteIdentityView(kind, suiteId, datasetId, datasetVersion)

  /** 从原始正文与查询参数解析摄入提交。
    *
    * 元数据放在查询参数、文件字节作为原始正文，避免为一次上传引入 multipart 解析或 Base64 膨胀。
    */
  private def parseIngestion(request: Request): IO[AgentError, IngestionSubmission] =
    for
      fileName <- requiredParam(request, "fileName")
      tenantId <- requiredParam(request, "tenantId")
      _        <- validateText("fileName", fileName, MaxFileNameChars)
      _        <- validateText("tenantId", tenantId, MaxTenantChars)
      mediaType = request.queryParam("mediaType").map(_.trim).filter(_.nonEmpty)
      content <- HttpRequestBody.readBytes(request, KnowledgeAdminService.MaxUploadBytes.toLong)
      _       <- ZIO
        .fail(AgentError.InvalidConfiguration("摄入正文不能为空"))
        .when(content.isEmpty)
    yield IngestionSubmission(
      fileName = fileName,
      mediaType = mediaType
        .orElse(request.header(Header.ContentType).map(_.mediaType.fullType))
        .getOrElse("application/octet-stream"),
      tenantId = tenantId,
      permissions = request
        .queryParams("permissions")
        .flatMap(_.split(",").toSeq)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toSet,
      content = content
    )

  /** 校验读权限并返回操作者。 */
  private def authorizeRead(request: Request): IO[AgentError, RunContext] =
    contexts.resolve(request).tap(AdminAuthorization.requireRead)

  /** 校验写权限并返回操作者。 */
  private def authorizeWrite(request: Request): IO[AgentError, RunContext] =
    contexts.resolve(request).tap(AdminAuthorization.requireWrite)

  /** 校验付费调试权限并返回操作者。 */
  private def authorizeDebug(request: Request): IO[AgentError, RunContext] =
    contexts.resolve(request).tap(AdminAuthorization.requireDebug)

  /** 必填 JSON 正文解码。 */
  private def decodeJson[A: JsonDecoder](request: Request): IO[AgentError, A] =
    HttpRequestBody
      .readJson(request)
      .flatMap(value => ZIO.fromEither(value.fromJson[A]).mapError(AgentError.InvalidConfiguration(_)))

  /** 集中把领域错误映射为已有的稳定状态码与脱敏 JSON。 */
  private def respond(effect: IO[AgentError, Response]): UIO[Response] =
    effect.catchAll(error => ZIO.succeed(HttpErrorResponse.from(error)))

object AdminHttpApi:
  /** 检索沙盒查询的最大字符数。 */
  private val MaxQueryChars: Int = 4000

  /** 租户标识的最大字符数。 */
  private val MaxTenantChars: Int = 200

  /** Provider 与模型名的最大字符数；两者都是路由标识而不是自由文本。 */
  private val MaxProviderChars: Int = 200

  /** 上传文件名的最大字符数。 */
  private val MaxFileNameChars: Int = 400

  /** 从治理服务与既有认证上下文解析器装配。 */
  val layer: URLayer[AdminCapabilities & AgentRequestContextResolver, AdminHttpApi] =
    ZLayer.fromFunction(AdminHttpApi.apply)

  /** 解析并收敛整数查询参数；非法值退回默认值而不是报错。
    *
    * 分页参数与业务语义无关，为一个拼错的 `limit` 返回 400 只会让管理台更难用；越界值收敛到合法区间即可。
    */
  private def intParam(request: Request, name: String, default: Int, max: Int): Int =
    request.queryParam(name).flatMap(_.trim.toIntOption).getOrElse(default).max(1).min(max)

  /** 解析可选时间戳查询参数。 */
  private def longParam(request: Request, name: String): Option[Long] =
    request.queryParam(name).flatMap(_.trim.toLongOption)

  /** 读取必填查询参数；缺失时返回安全校验错误。 */
  private def requiredParam(request: Request, name: String): IO[AgentError, String] =
    ZIO
      .fromOption(request.queryParam(name).map(_.trim).filter(_.nonEmpty))
      .orElseFail(AgentError.InvalidConfiguration(s"缺少必填查询参数 $name"))

  /** 校验文本长度上限，避免管理接口成为无界输入入口。 */
  private def validateText(field: String, value: String, maxChars: Int): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidConfiguration(s"$field 不能超过 $maxChars 个字符"))
      .when(value.codePointCount(0, value.length) > maxChars)
      .unit

  /** 解析 Run 状态查询值；未知状态 fail-closed。 */
  private def parseRunStatus(value: String): IO[AgentError, RunStatus] =
    ZIO
      .fromOption(RunStatus.values.find(_.toString.equalsIgnoreCase(value)))
      .orElseFail(AgentError.InvalidConfiguration(s"未知 Run 状态: $value"))

  /** 构造进入审计记录的操作者标签。
    *
    * 标签只包含租户与用户 ID，不包含 scope 集合或请求属性：审计要能回答“谁改的”，不需要复制一份授权快照。
    */
  private def actorLabel(actor: RunContext): String =
    (actor.tenantId, actor.userId) match
      case (Some(tenant), Some(user)) => s"$tenant/$user"
      case (Some(tenant), None)       => s"$tenant/-"
      case (None, Some(user))         => s"-/$user"
      case (None, None)               => "anonymous"
