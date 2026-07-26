package com.zyblw.agent.http

import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.AgentHttpProtocol
import com.zyblw.agent.memory.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 直接执行 Routes 验证 Memory 治理 HTTP 契约。
  *
  * 测试重点是身份只来自 resolver、HTTP 不绕过领域授权、CAS 冲突映射为 409，以及响应/审计都不泄漏查询词和认证 attributes；无需启动真实端口即可覆盖这些纯协议边界。
  */
object MemoryHttpApiSpec extends ZIOSpecDefault:

  /** Memory 目前属于 v1 下的 Beta 子契约，路径版本仍与稳定 Run API 对齐。 */
  private val memory = URL.root / "api" / "v1" / "memory"

  /** 测试 resolver 模拟“认证中间件已经验签后写入安全上下文”；生产不能直接信任这些 header。 */
  private val contexts = new AgentRequestContextResolver:
    def resolve(request: Request): IO[AgentError, RunContext] = ZIO.succeed(
      RunContext(
        tenantId = request.rawHeader("X-Test-Tenant"),
        userId = request.rawHeader("X-Test-User"),
        scopes = Set("ordinary-user"),
        attributes = Map("authorization" -> "Bearer never-audited")
      )
    )

  private val scope = MemoryScope.User(TenantId("tenant-a"), UserId("user-a"))

  /** 给请求附加可信测试身份。 */
  private def authenticated(request: Request): Request = request
    .addHeader("X-Test-Tenant", "tenant-a")
    .addHeader("X-Test-User", "user-a")

  /** 创建每个测试独享的 Store、审计 Repository 和 API。 */
  private def fixture: UIO[(MemoryStore, InMemoryMemoryGovernanceRepository, MemoryHttpApi)] =
    ZIO
      .service[MemoryStore]
      .flatMap { store =>
        InMemoryMemoryGovernanceRepository.make(store).map { repository =>
          val service = MemoryGovernanceService(store, repository, MemoryGovernancePolicy())
          (store, repository, MemoryHttpApi(service, contexts))
        }
      }
      .provide(MemoryStore.inMemory)

  /** 创建一条可被治理 API 读取的用户偏好。 */
  private def entry(value: String): MemoryEntry = MemoryEntry(
    "learning.preferred_classic",
    Json.Str(value),
    0.8,
    None,
    0L,
    None,
    kind = MemoryKind.Preference,
    evidence = MemoryEvidence.UserStated,
    extractorVersion = "http-test-v1"
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryHttpApi")(
    test("列表和精确读取只从认证上下文推导自己的 User scope，缺少身份返回 403") {
      for
        tuple <- fixture
        (store, _, api) = tuple
        _            <- store.put(scope, entry("伤寒论"))
        listResponse <- api.routes.runZIO(
          authenticated(
            Request.post(
              memory / "list",
              Body.fromString(MemoryListRequest(20).toJson)
            )
          )
        )
        listBody    <- listResponse.body.asString
        getResponse <- api.routes.runZIO(
          authenticated(
            Request.get(
              memory / "learning.preferred_classic"
            )
          )
        )
        getBody <- getResponse.body.asString
        denied  <- api.routes.runZIO(Request.post(memory / "list", Body.empty))
      yield assertTrue(
        listResponse.status == Status.Ok,
        listBody.contains("伤寒论"),
        listBody.contains("learning.preferred_classic"),
        getResponse.status == Status.Ok,
        getBody.contains("UserStated"),
        denied.status == Status.Forbidden,
        listResponse
          .rawHeader(AgentHttpProtocol.ApiVersionHeader)
          .contains(AgentHttpProtocol.ApiVersionHeaderValue)
      )
    },
    test("PUT 纠正使用 URL key 和 expectedVersion，陈旧版本返回 409") {
      for
        tuple <- fixture
        (store, _, api) = tuple
        created <- store.compareAndSet(scope, 0L, entry("伤寒论"))
        body = MemoryCorrectionRequest(
          created.version,
          Json.Str("黄帝内经"),
          0.95,
          "preference",
          "personal"
        ).toJson
        first <- api.routes.runZIO(
          authenticated(
            Request.put(
              memory / created.key,
              Body.fromString(body)
            )
          )
        )
        firstBody <- first.body.asString
        stale     <- api.routes.runZIO(
          authenticated(
            Request.put(
              memory / created.key,
              Body.fromString(body)
            )
          )
        )
        staleBody <- stale.body.asString
      yield assertTrue(
        first.status == Status.Ok,
        firstBody.contains("黄帝内经"),
        firstBody.contains(s"\"version\":${created.version + 1L}"),
        stale.status == Status.Conflict,
        staleBody.contains("\"category\":\"Conflict\""),
        !staleBody.contains(created.key)
      )
    },
    test("搜索 query 不进入审计或错误响应，删除单条和全部返回实际数量") {
      val privateQuery = "不应进入审计的搜索词"
      for
        tuple <- fixture
        (store, repository, api) = tuple
        _      <- store.put(scope, entry(privateQuery))
        search <- api.routes.runZIO(
          authenticated(
            Request.post(
              memory / "search",
              Body.fromString(MemorySearchRequest(privateQuery, 20).toJson)
            )
          )
        )
        searchBody <- search.body.asString
        deleted    <- api.routes.runZIO(
          authenticated(
            Request.delete(
              memory / "learning.preferred_classic"
            )
          )
        )
        deletedBody  <- deleted.body.asString
        deletedAgain <- api.routes.runZIO(
          authenticated(
            Request.delete(
              memory / "learning.preferred_classic"
            )
          )
        )
        deletedAgainBody <- deletedAgain.body.asString
        all              <- api.routes.runZIO(authenticated(Request.delete(memory)))
        audits           <- repository.records
      yield assertTrue(
        search.status == Status.Ok,
        searchBody.contains(privateQuery),
        deleted.status == Status.Ok,
        deletedBody.contains("\"affectedCount\":1"),
        deletedAgainBody.contains("\"affectedCount\":0"),
        all.status == Status.Ok,
        !audits.mkString.contains(privateQuery),
        !audits.mkString.contains("Bearer")
      )
    },
    test("非法 kind、过大分页和不存在 key 分别映射为 400/404，响应不回显请求正文") {
      val secret = "private-invalid-payload"
      for
        tuple <- fixture
        (_, _, api) = tuple
        invalidKind <- api.routes.runZIO(
          authenticated(
            Request.put(
              memory / "missing",
              Body.fromString(
                MemoryCorrectionRequest(
                  1L,
                  Json.Str(secret),
                  0.5,
                  "diagnosis",
                  "personal"
                ).toJson
              )
            )
          )
        )
        invalidKindBody <- invalidKind.body.asString
        tooLarge        <- api.routes.runZIO(
          authenticated(
            Request.post(
              memory / "list",
              Body.fromString(MemoryListRequest(201).toJson)
            )
          )
        )
        missing <- api.routes.runZIO(authenticated(Request.get(memory / "missing")))
      yield assertTrue(
        invalidKind.status == Status.BadRequest,
        !invalidKindBody.contains(secret),
        tooLarge.status == Status.BadRequest,
        missing.status == Status.NotFound
      )
    },
    test("旧的无版本 Memory 路径不会被意外保留") {
      for
        tuple <- fixture
        (_, _, api) = tuple
        response <- api.routes.runZIO(authenticated(Request.get(URL.root / "memory" / "missing")))
      yield assertTrue(response.status == Status.NotFound)
    }
  )
