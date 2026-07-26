package com.zyblw.agent.http

import com.zyblw.agent.core.*
import com.zyblw.agent.http.contract.{AgentHttpProtocol, ErrorResponse}
import com.zyblw.agent.memory.*
import zio.*
import zio.http.*
import zio.http.codec.PathCodec.string
import zio.json.*
import zio.json.ast.Json

/** 列表请求只允许控制有界数量；身份和目标 scope 不出现在正文。 */
final case class MemoryListRequest(limit: Int = 50) derives JsonCodec

/** 搜索请求的 query 只进入 MemoryStore 参数，不会进入审计或错误响应。 */
final case class MemorySearchRequest(query: String, limit: Int = 20) derives JsonCodec

/** 用户纠正长期记忆的 HTTP DTO。
  *
  * key 来自 URL，DTO 故意没有 evidence/confidence/extractorVersion/sourceRunId/createdAt 字段，避免客户端伪造治理元数据。
  */
final case class MemoryCorrectionRequest(
    expectedVersion: Long,
    value: Json,
    importance: Double,
    kind: String,
    sensitivity: String,
    expiresAtEpochMilli: Option[Long] = None
) derives JsonCodec

/** 不暴露 sourceRunId 的用户治理视图；版本用于后续 CAS 纠正。 */
final case class MemoryView(
    key: String,
    value: Json,
    importance: Double,
    kind: String,
    confidence: Double,
    sensitivity: String,
    evidence: String,
    version: Long,
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long,
    expiresAtEpochMilli: Option[Long]
) derives JsonCodec

/** 删除 API 的稳定幂等结果。 */
final case class MemoryDeleteResult(affectedCount: Long) derives JsonCodec

/** 用户长期记忆治理的 ZIO HTTP Adapter。
  *
  * 所有路由都先通过 `AgentRequestContextResolver` 取得可信身份，再由 tenantId/userId 推导唯一 User scope；客户端没有 scope 参数，因此无法靠
  * URL/JSON 请求另一个用户。`MemoryGovernanceService` 会再次执行领域授权，HTTP 层只负责 DTO、 状态码和 JSON 编解码，不复制权限规则或直接调用
  * MemoryStore。
  *
  * 路由：
  *
  *   - `POST /api/v1/memory/list`：有界列出自己的 active 记忆；
  *   - `POST /api/v1/memory/search`：有界搜索自己的记忆；
  *   - `GET /api/v1/memory/{key}`：精确读取；
  *   - `PUT /api/v1/memory/{key}`：使用 expectedVersion 纠正；
  *   - `DELETE /api/v1/memory/{key}`：幂等删除单条；
  *   - `DELETE /api/v1/memory`：删除自己的全部长期记忆。
  *
  * Memory DTO 仍标记为 Beta：路径已经带主版本，避免无版本历史负担，但在纳入 `AgentHttpContract` 的稳定 OpenAPI 前，
  * 业务客户端应把它视为同版本下可独立演进的预发布子契约。
  */
final class MemoryHttpApi(
    governance: MemoryGovernanceService,
    contexts: AgentRequestContextResolver
):

  /** 可直接与 `AgentHttpApi.routes` 使用 `++` 合并的独立路由集合。 */
  val routes: Routes[Any, Nothing] = (Routes(
    Method.POST / "api" / "v1" / "memory" / "list" -> handler { (request: Request) =>
      respond {
        for
          actor <- contexts.resolve(request)
          scope <- ownScope(actor)
          body  <- decodeOrDefault[MemoryListRequest](request, MemoryListRequest())
          items <- governance.list(actor, scope, body.limit)
        yield Response.json(items.map(toView).toList.toJson)
      }
    },
    Method.POST / "api" / "v1" / "memory" / "search" -> handler { (request: Request) =>
      respond {
        for
          actor <- contexts.resolve(request)
          scope <- ownScope(actor)
          body  <- decodeRequired[MemorySearchRequest](request)
          items <- governance.search(actor, scope, body.query, body.limit)
        yield Response.json(items.map(toView).toList.toJson)
      }
    },
    Method.GET / "api" / "v1" / "memory" / string("key") -> handler { (key: String, request: Request) =>
      respond {
        for
          actor <- contexts.resolve(request)
          scope <- ownScope(actor)
          value <- governance
            .get(actor, scope, key)
            .someOrFail(AgentError.MemoryNotFound(scope.diagnostic, key))
        yield Response.json(toView(value).toJson)
      }
    },
    Method.PUT / "api" / "v1" / "memory" / string("key") -> handler { (key: String, request: Request) =>
      respond {
        for
          actor       <- contexts.resolve(request)
          scope       <- ownScope(actor)
          body        <- decodeRequired[MemoryCorrectionRequest](request)
          kind        <- decodeKind(body.kind)
          sensitivity <- decodeSensitivity(body.sensitivity)
          value       <- governance.correct(
            actor,
            scope,
            MemoryCorrection(
              key,
              body.expectedVersion,
              body.value,
              body.importance,
              kind,
              sensitivity,
              body.expiresAtEpochMilli
            )
          )
        yield Response.json(toView(value).toJson)
      }
    },
    Method.DELETE / "api" / "v1" / "memory" / string("key") -> handler { (key: String, request: Request) =>
      respond {
        for
          actor    <- contexts.resolve(request)
          scope    <- ownScope(actor)
          affected <- governance.delete(actor, scope, key)
        yield Response.json(MemoryDeleteResult(affected).toJson)
      }
    },
    Method.DELETE / "api" / "v1" / "memory" -> handler { (request: Request) =>
      respond {
        for
          actor    <- contexts.resolve(request)
          scope    <- ownScope(actor)
          affected <- governance.deleteScope(actor, scope)
        yield Response.json(MemoryDeleteResult(affected).toJson)
      }
    }
  )) @@ HandlerAspect.addHeader(AgentHttpProtocol.ApiVersionHeader, AgentHttpProtocol.ApiVersionHeaderValue)

  /** 从可信认证上下文推导 User scope。缺少任一 ID 都拒绝；HTTP 正文和自定义 header 不能补齐该身份。
    */
  private def ownScope(actor: RunContext): IO[AgentError, MemoryScope] =
    ZIO
      .fromOption(for
        tenant <- actor.tenantId.map(TenantId(_))
        user   <- actor.userId.map(UserId(_))
      yield MemoryScope.User(tenant, user))
      .orElseFail(AgentError.MemoryAccessDenied("user:self", "missing-authenticated-identity"))

  /** 空正文使用默认 DTO；非空正文必须是合法 JSON。 */
  private def decodeOrDefault[A: JsonDecoder](request: Request, default: => A): IO[AgentError, A] =
    HttpRequestBody
      .readJson(request)
      .flatMap(value =>
        if value.trim.isEmpty then ZIO.succeed(default)
        else ZIO.fromEither(value.fromJson[A]).mapError(AgentError.InvalidConfiguration(_))
      )

  /** 必填 JSON 正文解码；解析详情只作为安全的 validation 错误返回。 */
  private def decodeRequired[A: JsonDecoder](request: Request): IO[AgentError, A] =
    HttpRequestBody
      .readJson(request)
      .flatMap(value => ZIO.fromEither(value.fromJson[A]).mapError(AgentError.InvalidConfiguration(_)))

  /** HTTP 字符串到领域枚举的 fail-closed 映射。 */
  private def decodeKind(value: String): IO[AgentError, MemoryKind] = value.trim.toLowerCase match
    case "preference" => ZIO.succeed(MemoryKind.Preference)
    case "semantic"   => ZIO.succeed(MemoryKind.Semantic)
    case "episodic"   => ZIO.succeed(MemoryKind.Episodic)
    case "procedural" => ZIO.succeed(MemoryKind.Procedural)
    case _            => ZIO.fail(AgentError.MemoryPolicyRejected("kind", "unknown-memory-kind"))

  /** HTTP 字符串到敏感等级的 fail-closed 映射。 */
  private def decodeSensitivity(value: String): IO[AgentError, MemorySensitivity] =
    value.trim.toLowerCase match
      case "public"    => ZIO.succeed(MemorySensitivity.Public)
      case "personal"  => ZIO.succeed(MemorySensitivity.Personal)
      case "sensitive" => ZIO.succeed(MemorySensitivity.Sensitive)
      case _ => ZIO.fail(AgentError.MemoryPolicyRejected("sensitivity", "unknown-memory-sensitivity"))

  /** 集中把领域错误转换为 Agent HTTP 已有的稳定状态与脱敏 JSON。 */
  private def respond(effect: IO[AgentError, Response]): UIO[Response] =
    effect.catchAll(error => ZIO.succeed(HttpErrorResponse.from(error)))

  /** 领域实体到用户治理 DTO 的唯一投影。 */
  private def toView(entry: MemoryEntry): MemoryView = MemoryView(
    entry.key,
    entry.value,
    entry.importance,
    entry.kind.toString,
    entry.confidence,
    entry.sensitivity.toString,
    entry.evidence.toString,
    entry.version,
    entry.createdAtEpochMilli,
    entry.updatedAtEpochMilli,
    entry.expiresAtEpochMilli
  )

object MemoryHttpApi:
  /** 从治理服务和现有认证上下文解析器装配，不要求第二套认证机制。 */
  val layer: URLayer[MemoryGovernanceService & AgentRequestContextResolver, MemoryHttpApi] =
    ZLayer.fromFunction(MemoryHttpApi.apply)

/** Agent HTTP 模块统一的 typed error → status/安全 JSON 映射。 */
private[http] object HttpErrorResponse:
  /** 把内部错误映射为稳定状态；只有 safeToExpose 错误会返回领域消息。 */
  def from(error: AgentError): Response =
    val status = error.category match
      case ErrorCategory.Authentication => Status.Unauthorized
      case ErrorCategory.Authorization  => Status.Forbidden
      case ErrorCategory.Persistence    => Status.NotFound
      case ErrorCategory.Conflict       => Status.Conflict
      case ErrorCategory.RateLimit      => Status.TooManyRequests
      case ErrorCategory.Unavailable    => Status.ServiceUnavailable
      case ErrorCategory.Unexpected     => Status.InternalServerError
      case _                            => Status.BadRequest
    val message = if error.safeToExpose then error.message else "智能体服务暂时无法完成请求"
    Response(status = status, body = Body.fromString(ErrorResponse(error.category.toString, message).toJson))
      .addHeader(Header.ContentType(MediaType.application.json))
