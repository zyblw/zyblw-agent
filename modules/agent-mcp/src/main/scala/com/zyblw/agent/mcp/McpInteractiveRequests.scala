package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import java.net.URI
import zio.*
import zio.json.ast.Json

/** sampling/createMessage 的类型化请求；消息正文保留开放 ContentBlock，但不会进入审批摘要或日志。 */
final case class McpSamplingRequest(
    messages: Chunk[Json.Obj],
    maxTokens: Long,
    systemPrompt: Option[String],
    includeContext: String,
    temperature: Option[Double],
    modelPreferences: Option[Json.Obj],
    tools: Chunk[Json.Obj],
    toolChoice: Option[Json.Obj],
    metadata: Option[Json.Obj]
)

/** sampling 服务返回的协议结果。 */
final case class McpSamplingResult(
    model: String,
    role: String,
    content: Json,
    stopReason: Option[String] = None
)

/** elicitation/create 的两种稳定模式。 */
enum McpElicitationRequest:
  /** 非敏感、顶层 primitive schema 表单。 */
  case Form(message: String, requestedSchema: Json.Obj)

  /** 带外 HTTPS 流程；框架只呈现 URL，不自动打开或携带业务 cookie。 */
  case Url(message: String, elicitationId: String, url: URI)

/** 用户对 elicitation 的动作。 */
enum McpElicitationAction:
  case Accept, Decline, Cancel

/** 表单接受时 content 才应非空；URL 模式通常只返回 action。 */
final case class McpElicitationResult(action: McpElicitationAction, content: Option[Json.Obj] = None)

/** 审批种类；稳定枚举便于 durable command 与 UI 路由。 */
enum McpInteractiveKind:
  case Sampling, ElicitationForm, ElicitationUrl

/** 进入人工审批系统的低敏摘要。
  *
  * 它刻意不包含 prompt、消息、表单说明和 URL path；审批 UI 若要展示完整请求，应从受权限保护的耐久命令 payload 读取，而不是从 telemetry 或普通日志读取。
  */
final case class McpInteractiveApprovalSummary(
    serverId: McpServerId,
    kind: McpInteractiveKind,
    maxTokens: Option[Long] = None,
    hasTools: Boolean = false,
    requestedFieldCount: Option[Int] = None,
    urlHost: Option[String] = None
)

/** 审批结果。Reject 表示明确拒绝，Cancel 表示没有做出授权决定。 */
enum McpInteractiveDecision:
  case Approve, Reject, Cancel

/** sampling/elicitation 的授权 SPI。
  *
  * 生产实现应把请求写入 durable command queue 并等待 resume；同步 UI callback 只能用于测试或本地工具。
  */
trait McpInteractiveApproval:
  def authorize(summary: McpInteractiveApprovalSummary): UIO[McpInteractiveDecision]

object McpInteractiveApproval:
  /** 安全默认：所有交互式反向请求都拒绝。 */
  val denyAll: McpInteractiveApproval = new McpInteractiveApproval:
    def authorize(summary: McpInteractiveApprovalSummary): UIO[McpInteractiveDecision] =
      ZIO.succeed(McpInteractiveDecision.Reject)

/** 实际执行模型采样的宿主 SPI；只会在 capability 和审批都通过后调用。 */
trait McpSamplingService:
  def createMessage(serverId: McpServerId, request: McpSamplingRequest): IO[AgentError, McpSamplingResult]

object McpSamplingService:
  /** 未配置服务时返回稳定拒绝，不会偷偷选择全局默认模型。 */
  val unavailable: McpSamplingService = new McpSamplingService:
    def createMessage(serverId: McpServerId, request: McpSamplingRequest): IO[AgentError, McpSamplingResult] =
      ZIO.fail(
        AgentError.ExternalProtocolFailure(
          "mcp",
          "sampling/createMessage",
          "MCP sampling service is not configured",
          Some("sampling_unavailable")
        )
      )

/** 把 elicitation 接入业务 UI/耐久审批的宿主 SPI。 */
trait McpElicitationService:
  def elicit(serverId: McpServerId, request: McpElicitationRequest): IO[AgentError, McpElicitationResult]

object McpElicitationService:
  /** 未配置 UI 时明确 decline。 */
  val decline: McpElicitationService = new McpElicitationService:
    def elicit(serverId: McpServerId, request: McpElicitationRequest): IO[AgentError, McpElicitationResult] =
      ZIO.succeed(McpElicitationResult(McpElicitationAction.Decline))

/** 经过 capability、严格解析和人工审批的反向请求处理器。
  *
  * DefaultMcpClient 仍会在此处理器之前再做一次 capability gate，形成纵深防御。该类负责协议字段级能力， 例如 sampling 请求带 tools 时必须额外声明
  * `sampling.tools`。
  */
final class GovernedMcpClientRequestHandler(
    capabilities: McpClientCapabilities,
    approval: McpInteractiveApproval,
    sampling: McpSamplingService,
    elicitation: McpElicitationService
) extends McpClientRequestHandler:

  def handle(serverId: McpServerId, method: String, params: Json.Obj): UIO[Either[McpRpcError, Json]] =
    val effect = method match
      case "ping"                   => ZIO.succeed(Json.Obj())
      case "sampling/createMessage" => handleSampling(serverId, params)
      case "elicitation/create"     => handleElicitation(serverId, params)
      case other                    =>
        ZIO.fail(
          AgentError.ExternalProtocolFailure(
            "mcp",
            other,
            "Client method is not implemented",
            Some("method_not_found")
          )
        )
    effect.either.map(_.left.map(toRpcError))

  /** sampling 先解析与子能力检查，再审批，最后才进入本地模型服务。 */
  private def handleSampling(serverId: McpServerId, params: Json.Obj): IO[AgentError, Json] =
    for
      request <- ZIO.fromEither(parseSampling(params))
      _       <- ZIO
        .fail(
          McpJson.protocolError(
            "sampling/createMessage",
            "sampling tools were supplied without sampling.tools capability",
            Some("capability_not_negotiated")
          )
        )
        .when(request.tools.nonEmpty && !capabilities.samplingTools)
      _ <- ZIO
        .fail(
          McpJson.protocolError(
            "sampling/createMessage",
            "cross-server context was requested without sampling.context capability",
            Some("capability_not_negotiated")
          )
        )
        .when(request.includeContext != "none" && !capabilities.samplingContext)
      decision <- approval.authorize(
        McpInteractiveApprovalSummary(
          serverId,
          McpInteractiveKind.Sampling,
          maxTokens = Some(request.maxTokens),
          hasTools = request.tools.nonEmpty
        )
      )
      _ <- decision match
        case McpInteractiveDecision.Approve => ZIO.unit
        case _                              =>
          ZIO.fail(
            McpJson.protocolError(
              "sampling/createMessage",
              "User did not approve MCP sampling",
              Some("user_rejected")
            )
          )
      result <- sampling.createMessage(serverId, request)
      _      <- ZIO
        .fail(McpJson.protocolError("sampling/createMessage", "sampling result model must not be blank"))
        .when(result.model.trim.isEmpty)
      _ <- ZIO
        .fail(
          McpJson.protocolError("sampling/createMessage", "sampling result role must be user or assistant")
        )
        .unless(result.role == "user" || result.role == "assistant")
    yield McpJson.obj(
      Some("model"   -> Json.Str(result.model)),
      Some("role"    -> Json.Str(result.role)),
      Some("content" -> result.content),
      result.stopReason.map("stopReason" -> Json.Str(_))
    )

  /** elicitation 的拒绝/取消返回协议 action；只有 Approve 才调用 UI 服务。 */
  private def handleElicitation(serverId: McpServerId, params: Json.Obj): IO[AgentError, Json] =
    for
      request <- ZIO.fromEither(parseElicitation(params))
      summary = request match
        case McpElicitationRequest.Form(_, schema) =>
          val count = McpJson
            .field(schema, "properties")
            .collect { case value: Json.Obj => value.fields.length }
            .getOrElse(0)
          McpInteractiveApprovalSummary(
            serverId,
            McpInteractiveKind.ElicitationForm,
            requestedFieldCount = Some(count)
          )
        case McpElicitationRequest.Url(_, _, url) =>
          McpInteractiveApprovalSummary(
            serverId,
            McpInteractiveKind.ElicitationUrl,
            urlHost = Option(url.getHost)
          )
      decision <- approval.authorize(summary)
      result   <- decision match
        case McpInteractiveDecision.Approve => elicitation.elicit(serverId, request)
        case McpInteractiveDecision.Reject  => ZIO.succeed(McpElicitationResult(McpElicitationAction.Decline))
        case McpInteractiveDecision.Cancel  => ZIO.succeed(McpElicitationResult(McpElicitationAction.Cancel))
      _ <- ZIO
        .fail(McpJson.protocolError("elicitation/create", "content is only valid for an accepted form"))
        .when(
          result.content.nonEmpty && (result.action != McpElicitationAction.Accept || request
            .isInstanceOf[McpElicitationRequest.Url])
        )
      _ <- ZIO.foreachDiscard(result.content)(content => ZIO.fromEither(validateElicitationContent(content)))
    yield McpJson.obj(
      Some("action" -> Json.Str(result.action match
        case McpElicitationAction.Accept  => "accept"
        case McpElicitationAction.Decline => "decline"
        case McpElicitationAction.Cancel  => "cancel")),
      result.content.map("content" -> _)
    )

  /** 严格解析 sampling 的稳定字段；开放 metadata/modelPreferences 仍要求对象。 */
  private def parseSampling(params: Json.Obj): Either[AgentError, McpSamplingRequest] =
    for
      rawMessages <- McpJson.requiredArray(params, "messages", "sampling/createMessage")
      messages    <- rawMessages.foldLeft[Either[AgentError, Chunk[Json.Obj]]](Right(Chunk.empty)) {
        (state, value) =>
          for
            accumulated <- state
            message     <- value match
              case obj: Json.Obj => Right(obj)
              case _             =>
                Left(McpJson.protocolError("sampling/createMessage", "messages entries must be objects"))
          yield accumulated :+ message
      }
      maxTokens <- McpJson.required(params, "maxTokens", "sampling/createMessage").flatMap {
        case Json.Num(value) =>
          scala.util
            .Try(value.longValueExact())
            .toEither
            .left
            .map(_ => McpJson.protocolError("sampling/createMessage", "maxTokens must be an integer"))
        case _ => Left(McpJson.protocolError("sampling/createMessage", "maxTokens must be a number"))
      }
      _ <- Either.cond(
        maxTokens > 0L,
        (),
        McpJson.protocolError("sampling/createMessage", "maxTokens must be positive")
      )
      systemPrompt   <- McpJson.optionalString(params, "systemPrompt", "sampling/createMessage")
      includeContext <- McpJson.optionalString(params, "includeContext", "sampling/createMessage")
      context = includeContext.getOrElse("none")
      _ <- Either.cond(
        Set("none", "thisServer", "allServers").contains(context),
        (),
        McpJson.protocolError("sampling/createMessage", "invalid includeContext")
      )
      temperature <- McpJson.field(params, "temperature") match
        case None | Some(Json.Null) => Right(None)
        case Some(Json.Num(value))  => Right(Some(value.doubleValue))
        case Some(_) => Left(McpJson.protocolError("sampling/createMessage", "temperature must be a number"))
      preferences <- optionalObject(params, "modelPreferences", "sampling/createMessage")
      tools       <- McpJson.field(params, "tools") match
        case None | Some(Json.Null) => Right(Chunk.empty)
        case Some(Json.Arr(values)) =>
          values.foldLeft[Either[AgentError, Chunk[Json.Obj]]](Right(Chunk.empty)) { (state, value) =>
            for
              accumulated <- state
              tool        <- value match
                case obj: Json.Obj => Right(obj)
                case _             =>
                  Left(McpJson.protocolError("sampling/createMessage", "tools entries must be objects"))
            yield accumulated :+ tool
          }
        case Some(_) => Left(McpJson.protocolError("sampling/createMessage", "tools must be an array"))
      toolChoice <- optionalObject(params, "toolChoice", "sampling/createMessage")
      metadata   <- optionalObject(params, "metadata", "sampling/createMessage")
    yield McpSamplingRequest(
      messages,
      maxTokens,
      systemPrompt,
      context,
      temperature,
      preferences,
      tools,
      toolChoice,
      metadata
    )

  /** 解析 form/url，并对 URL 强制 HTTPS 与无 user-info。 */
  private def parseElicitation(params: Json.Obj): Either[AgentError, McpElicitationRequest] =
    for
      message <- McpJson.requiredString(params, "message", "elicitation/create")
      mode    <- McpJson.optionalString(params, "mode", "elicitation/create")
      result  <- mode.getOrElse("form") match
        case "form" =>
          McpJson.requiredObject(params, "requestedSchema", "elicitation/create").flatMap { schema =>
            for
              _ <- Either.cond(
                McpJson.field(schema, "type").contains(Json.Str("object")),
                (),
                McpJson.protocolError("elicitation/create", "requestedSchema must declare type=object")
              )
              _ <- McpJson.requiredObject(schema, "properties", "elicitation/create")
            yield McpElicitationRequest.Form(message, schema)
          }
        case "url" =>
          for
            id     <- McpJson.requiredString(params, "elicitationId", "elicitation/create")
            rawUrl <- McpJson.requiredString(params, "url", "elicitation/create")
            url    <- scala.util
              .Try(URI.create(rawUrl))
              .toEither
              .left
              .map(_ => McpJson.protocolError("elicitation/create", "elicitation URL is invalid"))
            _ <- Either.cond(
              url.isAbsolute && url.getScheme == "https" && url.getHost != null && url.getUserInfo == null,
              (),
              McpJson.protocolError(
                "elicitation/create",
                "elicitation URL must be absolute HTTPS without user-info"
              )
            )
          yield McpElicitationRequest.Url(message, id, url)
        case other => Left(McpJson.protocolError("elicitation/create", s"unknown elicitation mode: $other"))
    yield result

  /** 读取可选对象字段。 */
  private def optionalObject(
      value: Json.Obj,
      name: String,
      operation: String
  ): Either[AgentError, Option[Json.Obj]] =
    McpJson.field(value, name) match
      case None | Some(Json.Null) => Right(None)
      case Some(obj: Json.Obj)    => Right(Some(obj))
      case Some(_) => Left(McpJson.protocolError(operation, s"field '$name' must be an object"))

  /** ElicitResult content 只允许 string/number/boolean/string[]，禁止嵌套对象。 */
  private def validateElicitationContent(content: Json.Obj): Either[AgentError, Unit] =
    content.fields.foldLeft[Either[AgentError, Unit]](Right(())) { case (state, (_, value)) =>
      state.flatMap { _ =>
        val valid = value match
          case Json.Str(_) | Json.Num(_) | Json.Bool(_) => true
          case Json.Arr(values)                         => values.forall(_.isInstanceOf[Json.Str])
          case _                                        => false
        Either.cond(
          valid,
          (),
          McpJson.protocolError("elicitation/create", "elicitation content contains unsupported value")
        )
      }
    }

  /** AgentError 到 JSON-RPC error 的安全映射；未标记 safeToExpose 的 message 不返回远端。 */
  private def toRpcError(error: AgentError): McpRpcError =
    val code = error match
      case AgentError.ExternalProtocolFailure(_, _, _, Some("method_not_found"), _, _) => -32601
      case _                                                                           => -32602
    McpRpcError(code, if error.safeToExpose then error.message else "Client rejected MCP request")
