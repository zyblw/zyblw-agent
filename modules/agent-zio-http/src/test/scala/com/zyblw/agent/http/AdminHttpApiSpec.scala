package com.zyblw.agent.http

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.AgentHttpProtocol
import com.zyblw.agent.tools.{ApprovalPolicy, ToolPolicyConfig}
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import java.time.Instant
import java.util.UUID

/** 直接执行 Routes 验证管理面的协议与授权边界。
  *
  * 重点不是各适配器的查询语义（它们在 core、rag、evals 与 postgres 的测试里覆盖），而是 HTTP 层本身： scope 是否
  * fail-closed、付费操作是否被写权限意外放行、未装配能力是否真的不挂载路由， 以及错误是否映射为稳定状态码而不泄漏请求正文。
  */
object AdminHttpApiSpec extends ZIOSpecDefault:
  private val admin = URL.root / "api" / "v1" / "admin"

  /** 模拟“认证中间件已验签并写入安全上下文”；scope 从测试 header 读取，生产不能直接信任它。 */
  private val contexts = new AgentRequestContextResolver:
    def resolve(request: Request): IO[AgentError, RunContext] = ZIO.succeed(
      RunContext(
        tenantId = Some("acme"),
        userId = Some("operator-1"),
        scopes = request
          .rawHeader("X-Test-Scopes")
          .toSet
          .flatMap(_.split(",").toSet)
          .map(_.trim)
          .filter(_.nonEmpty),
        attributes = Map("authorization" -> "Bearer never-audited")
      )
    )

  private def withScopes(request: Request, scopes: String*): Request =
    if scopes.isEmpty then request else request.addHeader("X-Test-Scopes", scopes.mkString(","))

  private val baseline = ToolPolicyConfig(approvalPolicy = ApprovalPolicy.RiskBased, maxCallsPerStep = 3)

  private val streamRunId     = RunId(UUID.randomUUID())
  private val streamSessionId = SessionId(UUID.randomUUID())
  private val streamAt        = Instant.parse("2026-08-06T00:00:00Z").toEpochMilli
  private val streamCreated   = PersistedAgentEvent(
    EventId(UUID.randomUUID()),
    streamRunId,
    0L,
    AgentEvent.RunCreated(streamRunId, streamSessionId, streamAt),
    streamAt
  )
  private val streamSecret    = "business-output-must-not-leak"
  private val streamCompleted = PersistedAgentEvent(
    EventId(UUID.randomUUID()),
    streamRunId,
    1L,
    AgentEvent.RunCompleted(
      streamRunId,
      AgentMessage.assistant(streamSecret),
      UsageSummary(inputTokens = 3L, outputTokens = 2L),
      streamAt + 1L
    ),
    streamAt + 1L
  )

  /** 一个只记录被调用次数的知识适配器；用于断言未授权请求根本没到达适配器。 */
  final private class RecordingKnowledge(val calls: Ref[Chunk[String]]) extends KnowledgeAdminService:
    def documents(
        tenantId: Option[String],
        limit: Int,
        cursor: Option[String]
    ): IO[AgentError, KnowledgeDocumentPage] =
      calls.update(_ :+ "documents").as(KnowledgeDocumentPage(Chunk.empty, None, hasMore = false))

    def retrieve(request: KnowledgeRetrievalRequest): IO[AgentError, KnowledgeRetrievalResult] =
      calls
        .update(_ :+ "retrieve")
        .as(
          KnowledgeRetrievalResult(
            elapsedMillis = 1L,
            hits = Chunk.empty,
            citations = Chunk.empty,
            embeddingProvider = "test",
            embeddingModel = "test-embed",
            embeddingDimension = 8,
            rerankApplied = request.rerank,
            contextExpanded = request.expandContext
          )
        )

    def retire(tenantId: String, documentId: String, expectedActiveVersion: Long): IO[AgentError, Unit] =
      calls.update(_ :+ "retire").unit

    def submitIngestion(
        submission: IngestionSubmission,
        submittedBy: String
    ): IO[AgentError, IngestionJobView] =
      calls
        .update(_ :+ s"ingest:${submission.fileName}:${submission.content.length}:$submittedBy")
        .as(
          IngestionJobView(
            jobId = "job-1",
            tenantId = submission.tenantId,
            sourceUri = s"admin-upload://${submission.fileName}",
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

    def ingestionJob(jobId: String): IO[AgentError, Option[IngestionJobView]] =
      calls.update(_ :+ "ingestionJob").as(None)

    def ingestionJobs(tenantId: Option[String], limit: Int): IO[AgentError, Chunk[IngestionJobView]] =
      calls.update(_ :+ "ingestionJobs").as(Chunk.empty)

  /** 探活会向 Provider 发一次真实付费调用；这个假 Key 用于断言它不会出现在任何响应正文里。 */
  private val fakeProviderKey = "sk-probe-must-not-leak-9c1f"

  private val option = ModelOptionView(
    provider = "primary",
    model = "primary-large",
    displayName = "Primary",
    protocol = "test",
    capabilities = ModelCapabilitiesView(
      toolCalls = true,
      parallelToolCalls = false,
      strictToolSchema = false,
      specificToolChoice = false,
      vision = false,
      thinking = false,
      streaming = false,
      usageReporting = true,
      maxInputTokens = None,
      maxOutputTokens = None
    ),
    isDefaultProvider = true,
    declaredModel = true,
    credential = ModelCredentialStatus(present = true, reference = "env:PRIMARY_API_KEY"),
    price = None
  )

  /** 与 `RecordingModels` 共享同一份目录，使配置写入路径的校验与模型页看到的选项一致。 */
  private val modelCatalog: ModelCatalog = new ModelCatalog:
    def options: UIO[Chunk[ModelOptionView]] = ZIO.succeed(Chunk(option))
    def defaultProvider: UIO[String]         = ZIO.succeed("primary")

  /** 记录调用次数的模型适配器；用于断言未授权请求根本没到达 Provider。 */
  final private class RecordingModels(val calls: Ref[Chunk[String]]) extends ModelAdminService:
    def catalog: IO[AgentError, ModelCatalogView] =
      calls
        .update(_ :+ "catalog")
        .as(
          ModelCatalogView(
            options = Chunk(option),
            defaultProvider = "primary",
            effectiveProvider = None,
            effectiveModel = None,
            embedding = None,
            priceCurrency = None,
            pricedOptionCount = 0
          )
        )

    def probe(request: ModelProbeRequest): IO[AgentError, ModelProbeResult] =
      calls
        .update(_ :+ s"probe:${request.provider}")
        .as(
          ModelProbeResult(
            provider = request.provider,
            model = request.model.getOrElse("primary-large"),
            succeeded = true,
            latencyMillis = 12L,
            inputTokens = 3L,
            outputTokens = 1L,
            failureCode = None
          )
        )

  /** 预检后返回一条低敏耐久事件；调用记录用于证明未授权请求不会打开数据库轮询流。 */
  final private class RecordingRunEvents(val calls: Ref[Chunk[String]]) extends RunEventAdminService:
    def open(
        runId: RunId,
        afterSequence: Long
    ): IO[AgentError, ZStream[Any, AgentError, PersistedAgentEvent]] =
      calls.update(_ :+ s"stream:${runId.asString}:$afterSequence").flatMap { _ =>
        if runId != streamRunId then ZIO.fail(AgentError.PersistenceFailure("Run 不存在"))
        else if afterSequence > 1L then ZIO.fail(AgentError.InvalidConfiguration("事件游标超过最后序号"))
        else
          ZIO.succeed(
            if afterSequence < 0L then ZStream.fromIterable(List(streamCreated, streamCompleted))
            else if afterSequence == 0L then ZStream.succeed(streamCompleted)
            else ZStream.empty
          )
      }

  /** 全部能力都装配的 API，附带知识与模型适配器的调用记录。 */
  private def fullApi: UIO[(AdminHttpApi, Ref[Chunk[String]])] =
    for
      calls    <- Ref.make(Chunk.empty[String])
      settings <- RuntimeSettingsService
        .make(baseline, catalog = modelCatalog)
        .provide(RuntimeOverrideStore.inMemory)
        .orDie
      knowledge = new RecordingKnowledge(calls)
    yield (
      AdminHttpApi(
        AdminCapabilities(
          runs = Some(RunDirectory.fromSnapshots(ZIO.succeed(Chunk.empty))),
          runEvents = Some(new RecordingRunEvents(calls)),
          config = Some(settings),
          ops = None,
          knowledge = Some(knowledge),
          evals = None,
          models = Some(new RecordingModels(calls)),
          observability = ObservabilityLinks(langfuseBaseUrl = Some("https://langfuse.example.com"))
        ),
        contexts
      ),
      calls
    )

  /** 只装配能力声明的 API；其余能力全部缺失。 */
  private val bareApi: AdminHttpApi = AdminHttpApi(AdminCapabilities(), contexts)

  private def configUpdate(expectedVersion: Long, overrides: RuntimeOverrides, reason: String = "测试"): Body =
    Body.fromString(RuntimeConfigUpdateRequest(expectedVersion, overrides, reason).toJson)

  private val retrieveBody: Body =
    Body.fromString(KnowledgeRetrieveRequest(query = "问诊要点", tenantId = "acme").toJson)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AdminHttpApi")(
    suite("授权")(
      test("缺少任何管理 scope 的读取请求返回 403") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(Request.get(admin / "runs"))
          body     <- response.body.asString
        yield assertTrue(response.status == Status.Forbidden, !body.contains("never-audited"))
      },
      test("写权限蕴含读权限，只持有写 scope 也能读取配置") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(Request.get(admin / "config"), AdminAuthorization.WriteScope)
          )
        yield assertTrue(response.status == Status.Ok)
      },
      test("只持有读权限时配置写入返回 403 且不改变生效配置") {
        for
          tuple <- fullApi
          (api, _) = tuple
          rejected <- api.routes.runZIO(
            withScopes(
              Request
                .put(admin / "config", configUpdate(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(1)))),
              AdminAuthorization.ReadScope
            )
          )
          after <- api.routes.runZIO(withScopes(Request.get(admin / "config"), AdminAuthorization.ReadScope))
          body  <- after.body.asString
          view  <- ZIO.fromEither(body.fromJson[RuntimeConfigView]).mapError(new RuntimeException(_))
        yield assertTrue(rejected.status == Status.Forbidden, view.overrideVersion == 0L)
      },
      test("写权限不蕴含付费调试权限：检索沙盒对纯写 scope 返回 403 且不调用适配器") {
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.post(admin / "knowledge" / "retrieve", retrieveBody),
              AdminAuthorization.WriteScope,
              AdminAuthorization.ReadScope
            )
          )
          recorded <- calls.get
        yield assertTrue(response.status == Status.Forbidden, recorded.isEmpty)
      },
      test("写权限不蕴含付费调试权限：文档摄入对纯写 scope 返回 403 且不读取正文") {
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.post(
                (admin / "knowledge" / "ingestions").addQueryParams("fileName=a.md&tenantId=acme"),
                Body.fromString("# 标题")
              ),
              AdminAuthorization.WriteScope
            )
          )
          recorded <- calls.get
        yield assertTrue(response.status == Status.Forbidden, recorded.isEmpty)
      },
      test("持有调试权限时检索沙盒放行，并以请求给出的租户视角执行") {
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.post(admin / "knowledge" / "retrieve", retrieveBody),
              AdminAuthorization.DebugScope
            )
          )
          recorded <- calls.get
        yield assertTrue(response.status == Status.Ok, recorded == Chunk("retrieve"))
      },
      test("调试权限不蕴含读权限：只持有 debug 时列出索引清单返回 403") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(Request.get(admin / "knowledge" / "documents"), AdminAuthorization.DebugScope)
          )
        yield assertTrue(response.status == Status.Forbidden)
      },
      test("业务侧 scope 不会被误认为管理 scope") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(Request.get(admin / "runs"), "ordinary-user", "agent:run:read")
          )
        yield assertTrue(response.status == Status.Forbidden)
      },
      test("Run 事件流缺少管理读权限时不会打开底层耐久流") {
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(
            Request.get(admin / "runs" / streamRunId.asString / "events" / "stream")
          )
          recorded <- calls.get
        yield assertTrue(response.status == Status.Forbidden, recorded.isEmpty)
      },
      test("写权限不蕴含付费调试权限：模型探活对纯写 scope 返回 403 且不触达 Provider") {
        // 探活会向 Provider 发一次真实调用并产生费用，因此与检索沙盒同级，不能被写权限蕴含。
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.post(admin / "models" / "probe", Body.fromString(ModelProbeRequest("primary").toJson)),
              AdminAuthorization.WriteScope,
              AdminAuthorization.ReadScope
            )
          )
          recorded <- calls.get
        yield assertTrue(response.status == Status.Forbidden, recorded.isEmpty)
      },
      test("持有调试权限时模型探活放行") {
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.post(admin / "models" / "probe", Body.fromString(ModelProbeRequest("primary").toJson)),
              AdminAuthorization.DebugScope
            )
          )
          body     <- response.body.asString
          result   <- ZIO.fromEither(body.fromJson[ModelProbeResult]).mapError(new RuntimeException(_))
          recorded <- calls.get
        yield assertTrue(
          response.status == Status.Ok,
          result.succeeded,
          recorded == Chunk("probe:primary"),
          // 探活结果不含模型输出正文，也不含任何凭据。
          !body.contains(fakeProviderKey)
        )
      },
      test("模型目录只需读权限，且响应不含任何凭据值") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(Request.get(admin / "models"), AdminAuthorization.ReadScope)
          )
          body <- response.body.asString
          view <- ZIO.fromEither(body.fromJson[ModelCatalogView]).mapError(new RuntimeException(_))
        yield assertTrue(
          response.status == Status.Ok,
          view.defaultProvider == "primary",
          view.options.length == 1,
          view.options.head.credential.reference == "env:PRIMARY_API_KEY",
          !body.contains(fakeProviderKey)
        )
      },
      test("模型目录不被调试权限蕴含，只持有 debug 时返回 403") {
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(
            withScopes(Request.get(admin / "models"), AdminAuthorization.DebugScope)
          )
          recorded <- calls.get
        yield assertTrue(response.status == Status.Forbidden, recorded.isEmpty)
      },
      test("授权错误不回显已授予的 scope 集合") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(withScopes(Request.get(admin / "runs"), "secret-internal-scope"))
          body     <- response.body.asString
        yield assertTrue(response.status == Status.Forbidden, !body.contains("secret-internal-scope"))
      }
    ),
    suite("Run SSE")(
      test("管理读权限可以读取低敏事件并携带恢复序号") {
        val request = withScopes(
          Request
            .get(admin / "runs" / streamRunId.asString / "events" / "stream")
            .addHeader("Last-Event-ID", "-1"),
          AdminAuthorization.ReadScope
        )
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(request)
          body     <- response.body.asString
          recorded <- calls.get
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("id: 0"),
          body.contains("event: RunCreated"),
          body.contains("\"sequence\":0"),
          body.contains("event: RunCompleted"),
          !body.contains(streamSecret),
          !body.contains("\"output\":"),
          !body.contains("\"message\":"),
          !body.contains("\"messages\":"),
          !body.contains("\"attributes\":"),
          response.rawHeader("Cache-Control").contains("no-cache, no-transform"),
          response.rawHeader("X-Accel-Buffering").contains("no"),
          recorded == Chunk(s"stream:${streamRunId.asString}:-1")
        )
      },
      test("非法恢复游标在创建 SSE 响应前返回 400") {
        val request = withScopes(
          Request
            .get(admin / "runs" / streamRunId.asString / "events" / "stream")
            .addHeader("Last-Event-ID", "not-a-sequence"),
          AdminAuthorization.ReadScope
        )
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(request)
          body     <- response.body.asString
          recorded <- calls.get
        yield assertTrue(
          response.status == Status.BadRequest,
          body.contains("Last-Event-ID"),
          recorded.isEmpty
        )
      }
    ),
    suite("能力探测")(
      test("能力声明如实报告已装配能力与观测深链") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(Request.get(admin / "capabilities"), AdminAuthorization.ReadScope)
          )
          body <- response.body.asString
          view <- ZIO.fromEither(body.fromJson[AdminCapabilitiesView]).mapError(new RuntimeException(_))
        yield assertTrue(
          response.status == Status.Ok,
          view.apiVersion == AgentHttpProtocol.MajorVersion,
          view.runDirectory,
          view.runEventStream,
          view.runtimeConfig,
          view.knowledge,
          view.models,
          !view.queueOps,
          !view.evalTrends,
          view.observability.langfuseBaseUrl.contains("https://langfuse.example.com")
        )
      },
      test("能力探测本身也要求读权限") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(Request.get(admin / "capabilities"))
        yield assertTrue(response.status == Status.Forbidden)
      },
      test("未装配的能力不挂载路由，请求自然得到 404 而不是 501") {
        for
          queue <- bareApi.routes.runZIO(
            withScopes(Request.get(admin / "ops" / "queue"), AdminAuthorization.ReadScope)
          )
          runs <- bareApi.routes.runZIO(withScopes(Request.get(admin / "runs"), AdminAuthorization.ReadScope))
          evals <- bareApi.routes.runZIO(
            withScopes(Request.get(admin / "evals" / "suites"), AdminAuthorization.ReadScope)
          )
          models <- bareApi.routes.runZIO(
            withScopes(Request.get(admin / "models"), AdminAuthorization.ReadScope)
          )
          runEvents <- bareApi.routes.runZIO(
            withScopes(
              Request.get(admin / "runs" / streamRunId.asString / "events" / "stream"),
              AdminAuthorization.ReadScope
            )
          )
          capabilities <- bareApi.routes.runZIO(
            withScopes(Request.get(admin / "capabilities"), AdminAuthorization.ReadScope)
          )
          body <- capabilities.body.asString
          view <- ZIO.fromEither(body.fromJson[AdminCapabilitiesView]).mapError(new RuntimeException(_))
        yield assertTrue(
          queue.status == Status.NotFound,
          runs.status == Status.NotFound,
          evals.status == Status.NotFound,
          models.status == Status.NotFound,
          runEvents.status == Status.NotFound,
          capabilities.status == Status.Ok,
          !view.runDirectory,
          !view.runEventStream,
          !view.queueOps,
          !view.evalTrends,
          !view.knowledge,
          !view.runtimeConfig,
          !view.models
        )
      },
      test("所有管理响应都带上 API 版本响应头") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(Request.get(admin / "capabilities"), AdminAuthorization.ReadScope)
          )
        yield assertTrue(
          response
            .rawHeader(AgentHttpProtocol.ApiVersionHeader)
            .contains(AgentHttpProtocol.ApiVersionHeaderValue)
        )
      }
    ),
    suite("配置写入")(
      test("陈旧 expectedVersion 映射为 409，让管理台重新加载而不是覆盖他人改动") {
        for
          tuple <- fullApi
          (api, _) = tuple
          first <- api.routes.runZIO(
            withScopes(
              Request
                .put(admin / "config", configUpdate(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(2)))),
              AdminAuthorization.WriteScope
            )
          )
          conflict <- api.routes.runZIO(
            withScopes(
              Request
                .put(admin / "config", configUpdate(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(9)))),
              AdminAuthorization.WriteScope
            )
          )
        yield assertTrue(first.status == Status.Ok, conflict.status == Status.Conflict)
      },
      test("越界覆盖映射为 400，且审计记录操作者身份而不是原始 token") {
        for
          tuple <- fullApi
          (api, _) = tuple
          rejected <- api.routes.runZIO(
            withScopes(
              Request
                .put(admin / "config", configUpdate(0L, RuntimeOverrides(toolMaxCallsPerStep = Some(0)))),
              AdminAuthorization.WriteScope
            )
          )
          accepted <- api.routes.runZIO(
            withScopes(
              Request.put(admin / "config", configUpdate(0L, RuntimeOverrides(rerankEnabled = Some(true)))),
              AdminAuthorization.WriteScope
            )
          )
          body <- accepted.body.asString
          view <- ZIO.fromEither(body.fromJson[RuntimeConfigView]).mapError(new RuntimeException(_))
        yield assertTrue(
          rejected.status == Status.BadRequest,
          view.overrideUpdatedBy == "acme/operator-1",
          !body.contains("never-audited")
        )
      },
      test("指向未注册模型的覆盖返回 400，已注册组合写入成功") {
        // 模型切换复用配置写入路径，因此这条边界必须在 HTTP 层也成立：一份指向未注册 Provider 的覆盖一旦落库，
        // 每次进程重启都会重新加载它，把一次下拉框错误变成持续的全线 ProviderNotFound。
        for
          tuple <- fullApi
          (api, _) = tuple
          rejected <- api.routes.runZIO(
            withScopes(
              Request
                .put(admin / "config", configUpdate(0L, RuntimeOverrides(modelProvider = Some("ghost")))),
              AdminAuthorization.WriteScope
            )
          )
          accepted <- api.routes.runZIO(
            withScopes(
              Request.put(
                admin / "config",
                configUpdate(
                  0L,
                  RuntimeOverrides(modelProvider = Some("primary"), modelName = Some("primary-large"))
                )
              ),
              AdminAuthorization.WriteScope
            )
          )
          body <- accepted.body.asString
          view <- ZIO.fromEither(body.fromJson[RuntimeConfigView]).mapError(new RuntimeException(_))
        yield assertTrue(
          rejected.status == Status.BadRequest,
          accepted.status == Status.Ok,
          view.overrides.modelName.contains("primary-large")
        )
      },
      test("超长变更原因在写入存储之前返回 400") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.put(
                admin / "config",
                configUpdate(0L, RuntimeOverrides.none, "原" * (RuntimeOverrideRecord.MaxReasonLength + 1))
              ),
              AdminAuthorization.WriteScope
            )
          )
        yield assertTrue(response.status == Status.BadRequest)
      },
      test("非法 JSON 正文返回 400 且不回显请求内容") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.put(admin / "config", Body.fromString("""{"expectedVersion":"不是数字"}""")),
              AdminAuthorization.WriteScope
            )
          )
          body <- response.body.asString
        yield assertTrue(response.status == Status.BadRequest, !body.contains("不是数字"))
      }
    ),
    suite("参数解析")(
      test("未知 Run 状态 fail-closed 返回 400，不静默忽略过滤条件") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.get((admin / "runs").addQueryParams("status=NotAStatus")),
              AdminAuthorization.ReadScope
            )
          )
        yield assertTrue(response.status == Status.BadRequest)
      },
      test("非法游标返回 400，而不是静默从头开始翻页") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.get((admin / "runs").addQueryParams("cursor=nonsense")),
              AdminAuthorization.ReadScope
            )
          )
        yield assertTrue(response.status == Status.BadRequest)
      },
      test("非法分页参数收敛到默认值，不为一个拼错的 limit 拒绝整个请求") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.get((admin / "runs").addQueryParams("limit=abc")),
              AdminAuthorization.ReadScope
            )
          )
        yield assertTrue(response.status == Status.Ok)
      },
      test("摄入缺少必填元数据或正文为空时返回 400") {
        for
          tuple <- fullApi
          (api, calls) = tuple
          missing <- api.routes.runZIO(
            withScopes(
              Request.post(
                (admin / "knowledge" / "ingestions").addQueryParams("tenantId=acme"),
                Body.fromString("x")
              ),
              AdminAuthorization.DebugScope
            )
          )
          blank <- api.routes.runZIO(
            withScopes(
              Request.post(
                (admin / "knowledge" / "ingestions").addQueryParams("fileName=a.md&tenantId=acme"),
                Body.empty
              ),
              AdminAuthorization.DebugScope
            )
          )
          recorded <- calls.get
        yield assertTrue(
          missing.status == Status.BadRequest,
          blank.status == Status.BadRequest,
          recorded.isEmpty
        )
      },
      test("摄入以原始字节提交并返回 202 与任务 ID，供管理台轮询进度") {
        for
          tuple <- fullApi
          (api, calls) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.post(
                (admin / "knowledge" / "ingestions").addQueryParams("fileName=guide.md&tenantId=acme"),
                Body.fromString("# 标题")
              ),
              AdminAuthorization.DebugScope
            )
          )
          body     <- response.body.asString
          job      <- ZIO.fromEither(body.fromJson[IngestionJobView]).mapError(new RuntimeException(_))
          recorded <- calls.get
        yield assertTrue(
          response.status == Status.Accepted,
          job.jobId == "job-1",
          job.status == IngestionJobStatus.Queued,
          recorded.exists(_.startsWith("ingest:guide.md:")),
          recorded.exists(_.endsWith(":acme/operator-1"))
        )
      },
      test("不存在的摄入任务返回 404 而不是 200 加空正文") {
        for
          tuple <- fullApi
          (api, _) = tuple
          response <- api.routes.runZIO(
            withScopes(
              Request.get(admin / "knowledge" / "ingestions" / "missing"),
              AdminAuthorization.ReadScope
            )
          )
        yield assertTrue(response.status == Status.NotFound)
      }
    )
  )
