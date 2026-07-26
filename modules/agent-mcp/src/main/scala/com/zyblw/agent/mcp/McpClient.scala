package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.ast.Json
import zio.stream.*

/** MCP 客户端的资源与安全边界配置。
  *
  * @param serverId
  *   本地配置的稳定服务标识；不要直接使用远端自报的 `serverInfo.name` 作为授权主体
  * @param clientInfo
  *   发送给服务端的客户端实现信息
  * @param capabilities
  *   明确开放给服务端的反向能力；默认全部关闭
  * @param protocolVersion
  *   客户端请求的稳定协议版本
  * @param initializeTimeout
  *   初始化硬超时
  * @param requestTimeout
  *   普通请求硬超时
  * @param maxListPages
  *   防止恶意或错误服务端通过无限 cursor 消耗资源
  * @param maxListItems
  *   所有分页结果合并后的硬上限
  * @param notificationBuffer
  *   语义通知 Hub 的容量；慢订阅者会感受到背压/滑动策略，而不是无限占内存
  * @param maxInboundConcurrency
  *   sampling/elicitation 等反向请求的最大并发数
  */
final case class McpClientConfig(
    serverId: McpServerId,
    clientInfo: McpImplementation,
    capabilities: McpClientCapabilities = McpClientCapabilities(),
    protocolVersion: McpProtocolVersion = McpProtocolVersion.Stable2025_11_25,
    initializeTimeout: Duration = 20.seconds,
    requestTimeout: Duration = 60.seconds,
    maxListPages: Int = 100,
    maxListItems: Int = 10000,
    notificationBuffer: Int = 256,
    maxInboundConcurrency: Int = 4
):
  /** 在启动外部进程或网络请求前确定性拒绝危险配置。 */
  def validate: Either[AgentError, Unit] =
    Either.cond(
      McpProtocolVersion.supported.contains(protocolVersion) &&
        clientInfo.name.trim.nonEmpty && clientInfo.version.trim.nonEmpty &&
        initializeTimeout > Duration.Zero && requestTimeout > Duration.Zero &&
        maxListPages > 0 && maxListItems > 0 && notificationBuffer > 0 && maxInboundConcurrency > 0,
      (),
      AgentError.InvalidConfiguration("Invalid MCP client configuration")
    )

/** 服务端反向请求处理器。
  *
  * 这是 sampling、elicitation、roots 等高权限能力进入宿主应用的唯一入口。返回 `Left` 表示向远端发送 JSON-RPC 拒绝，而不是使 transport Fiber
  * 崩溃。实现不得把远端请求直接当成已授权动作。
  */
trait McpClientRequestHandler:
  /** @param serverId
    *   本地可信配置中的 MCP 服务标识
    * @param method
    *   反向请求方法
    * @param params
    *   未信任的请求参数
    * @return
    *   可返回给远端的结果或脱敏 JSON-RPC 错误
    */
  def handle(serverId: McpServerId, method: String, params: Json.Obj): UIO[Either[McpRpcError, Json]]

object McpClientRequestHandler:
  /** 安全默认处理器：只响应 ping，拒绝所有能触发本地模型、用户交互或文件系统访问的请求。
    *
    * 业务若要开放 sampling/elicitation，应提供连接 durable approval 的实现，并同时在 `McpClientCapabilities` 中声明相应能力。
    */
  val denyPrivileged: McpClientRequestHandler = new McpClientRequestHandler:
    def handle(serverId: McpServerId, method: String, params: Json.Obj): UIO[Either[McpRpcError, Json]] =
      ZIO.succeed {
        if method == "ping" then Right(Json.Obj())
        else Left(McpRpcError(-32601, s"Client method is not enabled: $method"))
      }

/** 已完成 initialize/initialized 生命周期的 MCP 客户端。 */
trait McpClient:
  /** 协商结果和服务端能力快照。 */
  def session: McpSessionInfo

  /** 远端服务器的本地稳定标识。 */
  final def serverId: McpServerId = session.serverId

  /** 初始化协商后的版本。 */
  final def protocolVersion: McpProtocolVersion = session.protocolVersion

  /** 列出所有分页工具。 */
  def listTools: IO[AgentError, Chunk[McpToolDescriptor]]

  /** 调用远端工具；结果仍需经过本地 ToolExecutor、审批和 guardrail。 */
  def callTool(name: String, arguments: Json): IO[AgentError, ToolResult]

  /** 列出所有分页资源。 */
  def listResources: IO[AgentError, Chunk[McpResource]]

  /** 读取指定 URI 的资源内容。 */
  def readResource(uri: String): IO[AgentError, Chunk[McpResourceContent]]

  /** 订阅一个资源的变化通知。 */
  def subscribeResource(uri: String): IO[AgentError, Unit]

  /** 取消资源订阅。 */
  def unsubscribeResource(uri: String): IO[AgentError, Unit]

  /** 列出所有分页 prompt 模板。 */
  def listPrompts: IO[AgentError, Chunk[McpPrompt]]

  /** 展开一个 prompt 模板。 */
  def getPrompt(name: String, arguments: Map[String, String] = Map.empty): IO[AgentError, McpPromptResult]

  /** 服务端通知的安全语义流。 */
  def notifications: ZStream[Any, AgentError, McpNotification]

  /** 实验性：列出服务端当前授权上下文可见的耐久 Tasks。 */
  def listTasks: IO[AgentError, Chunk[McpTask]]

  /** 实验性：读取单个 Task 的最新状态。 */
  def getTask(taskId: String): IO[AgentError, McpTask]

  /** 实验性：读取已完成 Task 的原始业务结果。 */
  def taskResult(taskId: String): IO[AgentError, Json]

  /** 实验性：请求取消 Task，并返回服务端确认后的状态。 */
  def cancelTask(taskId: String): IO[AgentError, McpTask]

  /** 主动关闭；通常由 `Scope` 自动执行。 */
  def close: UIO[Unit]

/** MCP 工具适配层。远端声明只定义模型可见 schema，本地 metadata 才定义权限与副作用策略。 */
final class McpRegisteredTool(
    client: McpClient,
    descriptor: McpToolDescriptor,
    val metadata: ToolMetadata
) extends RegisteredTool:
  /** 注册到本地工具系统的定义；output schema 同样被保留用于结构化校验。 */
  val definition: ToolDefinition =
    ToolDefinition(descriptor.name, descriptor.description, descriptor.inputSchema, descriptor.outputSchema)

  /** 通过已初始化 MCP client 执行远端调用。
    *
    * `context` 仍由外层 ToolExecutor 用于授权、审批、审计和幂等；此适配器不会把该上下文发送给不可信服务端。
    */
  def invoke(arguments: Json, context: ToolExecutionContext): IO[AgentError, ToolResult] =
    client.callTool(descriptor.name, arguments)

/** 生产 MCP client 的默认实现。 */
final private class DefaultMcpClient(
    val session: McpSessionInfo,
    transport: McpTransport,
    config: McpClientConfig,
    notificationHub: Hub[McpNotification]
) extends McpClient:

  def listTools: IO[AgentError, Chunk[McpToolDescriptor]] =
    session.capabilities.requireCapability(session.capabilities.tools, "tools/list") *>
      paginate("tools/list", "tools", parseTool)

  def callTool(name: String, arguments: Json): IO[AgentError, ToolResult] =
    for
      _              <- session.capabilities.requireCapability(session.capabilities.tools, "tools/call")
      argumentObject <- arguments match
        case value: Json.Obj => ZIO.succeed(value)
        case _ => ZIO.fail(AgentError.ToolInputInvalid(name, "MCP tool arguments must be a JSON object"))
      result <- transport.request(
        "tools/call",
        McpJson.obj("name" -> Json.Str(name), "arguments" -> argumentObject),
        config.requestTimeout
      )
      parsed <- parseToolResult(name, result)
    yield parsed

  def listResources: IO[AgentError, Chunk[McpResource]] =
    session.capabilities.requireCapability(session.capabilities.resources, "resources/list") *>
      paginate("resources/list", "resources", parseResource)

  def readResource(uri: String): IO[AgentError, Chunk[McpResourceContent]] =
    session.capabilities.requireCapability(session.capabilities.resources, "resources/read") *>
      transport
        .request("resources/read", McpJson.obj("uri" -> Json.Str(uri)), config.requestTimeout)
        .flatMap(result => ZIO.fromEither(parseResourceContents(result)))

  def subscribeResource(uri: String): IO[AgentError, Unit] =
    session.capabilities.requireCapability(session.capabilities.resourcesSubscribe, "resources/subscribe") *>
      transport
        .request("resources/subscribe", McpJson.obj("uri" -> Json.Str(uri)), config.requestTimeout)
        .unit

  def unsubscribeResource(uri: String): IO[AgentError, Unit] =
    session.capabilities.requireCapability(
      session.capabilities.resourcesSubscribe,
      "resources/unsubscribe"
    ) *>
      transport
        .request("resources/unsubscribe", McpJson.obj("uri" -> Json.Str(uri)), config.requestTimeout)
        .unit

  def listPrompts: IO[AgentError, Chunk[McpPrompt]] =
    session.capabilities.requireCapability(session.capabilities.prompts, "prompts/list") *>
      paginate("prompts/list", "prompts", parsePrompt)

  def getPrompt(name: String, arguments: Map[String, String]): IO[AgentError, McpPromptResult] =
    val argumentJson = Json.Obj(
      Chunk.fromIterable(arguments.toList.sortBy(_._1).map { case (key, value) => key -> Json.Str(value) })
    )
    session.capabilities.requireCapability(session.capabilities.prompts, "prompts/get") *>
      transport
        .request(
          "prompts/get",
          McpJson.obj("name" -> Json.Str(name), "arguments" -> argumentJson),
          config.requestTimeout
        )
        .flatMap(result => ZIO.fromEither(parsePromptResult(result)))

  def notifications: ZStream[Any, AgentError, McpNotification] = ZStream.fromHub(notificationHub)

  def listTasks: IO[AgentError, Chunk[McpTask]] =
    session.capabilities.requireCapability(session.capabilities.experimentalTasks, "tasks/list") *>
      paginate("tasks/list", "tasks", parseTask)

  def getTask(taskId: String): IO[AgentError, McpTask] =
    taskOperation("tasks/get", taskId).flatMap(value => ZIO.fromEither(parseTask(value)))

  def taskResult(taskId: String): IO[AgentError, Json] =
    taskOperation("tasks/result", taskId)

  def cancelTask(taskId: String): IO[AgentError, McpTask] =
    taskOperation("tasks/cancel", taskId).flatMap(value => ZIO.fromEither(parseTask(value)))

  def close: UIO[Unit] = transport.close

  /** 对实验 Task 方法统一执行 capability gate 和 taskId 非空校验。 */
  private def taskOperation(method: String, taskId: String): IO[AgentError, Json] =
    session.capabilities.requireCapability(session.capabilities.experimentalTasks, method) *>
      ZIO
        .fail(McpJson.protocolError(method, "taskId must not be blank", Some("invalid_task_id")))
        .when(taskId.trim.isEmpty) *>
      transport.request(method, McpJson.obj("taskId" -> Json.Str(taskId)), config.requestTimeout)

  /** 执行 cursor 分页并检测 cursor 环、页数和条目上限。
    *
    * @param method
    *   MCP list 方法
    * @param arrayField
    *   每页结果数组字段
    * @param decode
    *   单条记录的严格解析器
    */
  private def paginate[A](
      method: String,
      arrayField: String,
      decode: Json => Either[AgentError, A]
  ): IO[AgentError, Chunk[A]] =
    def loop(cursor: Option[String], seen: Set[String], page: Int, acc: Chunk[A]): IO[AgentError, Chunk[A]] =
      if page >= config.maxListPages then
        ZIO.fail(
          McpJson
            .protocolError(method, s"pagination exceeded ${config.maxListPages} pages", Some("page_limit"))
        )
      else
        val params = McpJson.obj(cursor.map(value => "cursor" -> Json.Str(value)))
        transport.request(method, params, config.requestTimeout).flatMap { raw =>
          ZIO.fromEither(parsePage(raw, arrayField, method, decode)).flatMap { case (values, next) =>
            val merged = acc ++ values
            if merged.length > config.maxListItems then
              ZIO.fail(
                McpJson.protocolError(
                  method,
                  s"pagination exceeded ${config.maxListItems} items",
                  Some("item_limit")
                )
              )
            else
              next match
                case None                                => ZIO.succeed(merged)
                case Some(value) if seen.contains(value) =>
                  ZIO.fail(
                    McpJson.protocolError(method, "server repeated a pagination cursor", Some("cursor_cycle"))
                  )
                case Some(value) => loop(Some(value), seen + value, page + 1, merged)
          }
        }
    loop(None, Set.empty, 0, Chunk.empty)

  /** 解析通用 list 结果页。 */
  private def parsePage[A](
      raw: Json,
      arrayField: String,
      operation: String,
      decode: Json => Either[AgentError, A]
  ): Either[AgentError, (Chunk[A], Option[String])] = raw match
    case obj: Json.Obj =>
      for
        values <- McpJson.requiredArray(obj, arrayField, operation)
        parsed <- values.foldLeft[Either[AgentError, Chunk[A]]](Right(Chunk.empty)) { (state, value) =>
          for
            accumulated <- state
            item        <- decode(value)
          yield accumulated :+ item
        }
        cursor <- McpJson.optionalString(obj, "nextCursor", operation)
      yield parsed -> cursor
    case _ => Left(McpJson.protocolError(operation, "result must be an object"))

  /** 严格解析工具目录项。 */
  private def parseTool(value: Json): Either[AgentError, McpToolDescriptor] = value match
    case obj: Json.Obj =>
      for
        name        <- McpJson.requiredString(obj, "name", "tools/list")
        description <- McpJson.optionalString(obj, "description", "tools/list")
        title       <- McpJson.optionalString(obj, "title", "tools/list")
        input       <- McpJson.requiredObject(obj, "inputSchema", "tools/list")
        _           <- Either.cond(
          McpJson.field(input, "type").contains(Json.Str("object")),
          (),
          McpJson.protocolError("tools/list", s"tool '$name' inputSchema must declare type=object")
        )
        output <- McpJson.field(obj, "outputSchema") match
          case None | Some(Json.Null) => Right(None)
          case Some(value: Json.Obj)  => Right(Some(value))
          case Some(_)                =>
            Left(McpJson.protocolError("tools/list", s"tool '$name' outputSchema must be an object"))
        annotations <- McpJson.field(obj, "annotations") match
          case None | Some(Json.Null) => Right(None)
          case Some(value: Json.Obj)  => Right(Some(value))
          case Some(_)                =>
            Left(McpJson.protocolError("tools/list", s"tool '$name' annotations must be an object"))
      yield McpToolDescriptor(name, description.getOrElse(""), input, output, title, annotations)
    case _ => Left(McpJson.protocolError("tools/list", "tool entry must be an object"))

  /** 将 MCP CallToolResult 完整保存在 ToolResult.value 中。 */
  private def parseToolResult(name: String, value: Json): IO[AgentError, ToolResult] = value match
    case obj: Json.Obj =>
      ZIO.fromEither {
        for
          content <- McpJson.requiredArray(obj, "content", "tools/call")
          _       <- content.foldLeft[Either[AgentError, Unit]](Right(())) { (state, item) =>
            state.flatMap(_ =>
              Either.cond(
                item.isInstanceOf[Json.Obj],
                (),
                McpJson.protocolError("tools/call", "content entries must be objects")
              )
            )
          }
          structured <- McpJson.field(obj, "structuredContent") match
            case None | Some(Json.Null) => Right(None)
            case Some(value: Json.Obj)  => Right(Some(value))
            case Some(_) => Left(McpJson.protocolError("tools/call", "structuredContent must be an object"))
          isError <- McpJson.optionalBoolean(obj, "isError", "tools/call")
        yield
          val result = McpJson.obj(
            Some("content" -> Json.Arr(content)),
            structured.map("structuredContent" -> _)
          )
          ToolResult(result, isError.getOrElse(false), Map("protocol" -> "mcp", "serverId" -> serverId.value))
      }
    case _ => ZIO.fail(McpJson.protocolError("tools/call", s"tool '$name' result must be an object"))

  /** 解析资源目录项。 */
  private def parseResource(value: Json): Either[AgentError, McpResource] = value match
    case obj: Json.Obj =>
      for
        uri         <- McpJson.requiredString(obj, "uri", "resources/list")
        name        <- McpJson.requiredString(obj, "name", "resources/list")
        mediaType   <- McpJson.optionalString(obj, "mimeType", "resources/list")
        description <- McpJson.optionalString(obj, "description", "resources/list")
        title       <- McpJson.optionalString(obj, "title", "resources/list")
        size        <- McpJson.optionalLong(obj, "size", "resources/list")
      yield McpResource(uri, name, mediaType, description, title, size)
    case _ => Left(McpJson.protocolError("resources/list", "resource entry must be an object"))

  /** 解析资源读取结果，并要求每项在 text/blob 之间二选一。 */
  private def parseResourceContents(value: Json): Either[AgentError, Chunk[McpResourceContent]] = value match
    case obj: Json.Obj =>
      McpJson
        .requiredArray(obj, "contents", "resources/read")
        .flatMap(
          _.foldLeft[Either[AgentError, Chunk[McpResourceContent]]](Right(Chunk.empty)) { (state, item) =>
            for
              accumulated <- state
              content     <- item match
                case entry: Json.Obj =>
                  for
                    uri       <- McpJson.requiredString(entry, "uri", "resources/read")
                    mediaType <- McpJson.optionalString(entry, "mimeType", "resources/read")
                    result    <- (McpJson.field(entry, "text"), McpJson.field(entry, "blob")) match
                      case (Some(Json.Str(text)), None) =>
                        Right(McpResourceContent.Text(uri, text, mediaType))
                      case (None, Some(Json.Str(blob))) =>
                        Right(McpResourceContent.Blob(uri, blob, mediaType))
                      case _ =>
                        Left(
                          McpJson.protocolError(
                            "resources/read",
                            "resource content must contain exactly one string field: text or blob"
                          )
                        )
                  yield result
                case _ => Left(McpJson.protocolError("resources/read", "resource content must be an object"))
            yield accumulated :+ content
          }
        )
    case _ => Left(McpJson.protocolError("resources/read", "result must be an object"))

  /** 解析 prompt 目录项。 */
  private def parsePrompt(value: Json): Either[AgentError, McpPrompt] = value match
    case obj: Json.Obj =>
      for
        name        <- McpJson.requiredString(obj, "name", "prompts/list")
        description <- McpJson.optionalString(obj, "description", "prompts/list")
        title       <- McpJson.optionalString(obj, "title", "prompts/list")
        arguments   <- McpJson.field(obj, "arguments") match
          case None | Some(Json.Null) => Right(Chunk.empty)
          case Some(Json.Arr(values)) =>
            values.foldLeft[Either[AgentError, Chunk[McpPromptArgument]]](Right(Chunk.empty)) { (state, item) =>
              for
                accumulated <- state
                argument    <- item match
                  case entry: Json.Obj =>
                    for
                      argumentName        <- McpJson.requiredString(entry, "name", "prompts/list")
                      argumentDescription <- McpJson.optionalString(entry, "description", "prompts/list")
                      required            <- McpJson.optionalBoolean(entry, "required", "prompts/list")
                    yield McpPromptArgument(argumentName, argumentDescription, required.getOrElse(false))
                  case _ => Left(McpJson.protocolError("prompts/list", "prompt argument must be an object"))
              yield accumulated :+ argument
            }
          case Some(_) => Left(McpJson.protocolError("prompts/list", "arguments must be an array"))
      yield McpPrompt(name, description, arguments, title)
    case _ => Left(McpJson.protocolError("prompts/list", "prompt entry must be an object"))

  /** 解析展开后的 prompt；content 保持开放 ContentBlock 对象。 */
  private def parsePromptResult(value: Json): Either[AgentError, McpPromptResult] = value match
    case obj: Json.Obj =>
      for
        description <- McpJson.optionalString(obj, "description", "prompts/get")
        rawMessages <- McpJson.requiredArray(obj, "messages", "prompts/get")
        messages    <- rawMessages.foldLeft[Either[AgentError, Chunk[McpPromptMessage]]](Right(Chunk.empty)) {
          (state, item) =>
            for
              accumulated <- state
              message     <- item match
                case entry: Json.Obj =>
                  for
                    role <- McpJson.requiredString(entry, "role", "prompts/get")
                    _    <- Either.cond(
                      role == "user" || role == "assistant",
                      (),
                      McpJson.protocolError("prompts/get", s"invalid prompt role: $role")
                    )
                    content <- McpJson.requiredObject(entry, "content", "prompts/get")
                  yield McpPromptMessage(role, content)
                case _ => Left(McpJson.protocolError("prompts/get", "prompt message must be an object"))
            yield accumulated :+ message
        }
      yield McpPromptResult(description, messages)
    case _ => Left(McpJson.protocolError("prompts/get", "result must be an object"))

  /** 严格解析实验性 Task 状态；未知状态和非法时间不会悄悄降级。 */
  private def parseTask(value: Json): Either[AgentError, McpTask] = value match
    case obj: Json.Obj =>
      for
        taskId     <- McpJson.requiredString(obj, "taskId", "tasks")
        statusText <- McpJson.requiredString(obj, "status", "tasks")
        status     <- statusText match
          case "working"        => Right(McpTaskStatus.Working)
          case "input_required" => Right(McpTaskStatus.InputRequired)
          case "completed"      => Right(McpTaskStatus.Completed)
          case "failed"         => Right(McpTaskStatus.Failed)
          case "cancelled"      => Right(McpTaskStatus.Cancelled)
          case other            => Left(McpJson.protocolError("tasks", s"unknown task status: $other"))
        statusMessage <- McpJson.optionalString(obj, "statusMessage", "tasks")
        createdText   <- McpJson.requiredString(obj, "createdAt", "tasks")
        updatedText   <- McpJson.requiredString(obj, "lastUpdatedAt", "tasks")
        created       <- scala.util
          .Try(java.time.Instant.parse(createdText))
          .toEither
          .left
          .map(_ => McpJson.protocolError("tasks", "createdAt must be ISO-8601"))
        updated <- scala.util
          .Try(java.time.Instant.parse(updatedText))
          .toEither
          .left
          .map(_ => McpJson.protocolError("tasks", "lastUpdatedAt must be ISO-8601"))
        ttl  <- McpJson.optionalLong(obj, "ttl", "tasks")
        poll <- McpJson.optionalLong(obj, "pollInterval", "tasks")
        _    <- Either.cond(
          ttl.forall(_ >= 0L) && poll.forall(_ >= 0L),
          (),
          McpJson.protocolError("tasks", "ttl/pollInterval must be non-negative")
        )
      yield McpTask(taskId, status, statusMessage, created, updated, ttl, poll)
    case _ => Left(McpJson.protocolError("tasks", "task result must be an object"))

object DefaultMcpClient:
  /** 完成 MCP 初始化并在当前 `Scope` 中启动反向请求 dispatcher。
    *
    * 获取成功后：
    *   1. `initialize` 已完成并验证版本；
    *   2. transport 已获知协商版本；
    *   3. `notifications/initialized` 已发送；
    *   4. 入站请求 dispatcher 已启动。
    *
    * Scope 结束会先中断 dispatcher，再幂等关闭 transport，因此调用方不需要手写 finally。
    */
  def scoped(
      transport: McpTransport,
      config: McpClientConfig,
      requestHandler: McpClientRequestHandler = McpClientRequestHandler.denyPrivileged
  ): ZIO[Scope, AgentError, McpClient] =
    for
      _           <- ZIO.fromEither(config.validate)
      session     <- initialize(transport, config).onError(_ => transport.close)
      hub         <- Hub.sliding[McpNotification](config.notificationBuffer)
      concurrency <- Semaphore.make(config.maxInboundConcurrency.toLong)
      client = DefaultMcpClient(session, transport, config, hub)
      _ <- dispatchInbound(transport, session, config, requestHandler, hub, concurrency)
        .catchAllCause(cause =>
          if cause.isInterrupted then ZIO.unit
          else ZIO.logWarningCause("MCP inbound dispatcher stopped", cause)
        )
        .forkScoped
      _ <- ZIO.addFinalizer(client.close)
    yield client

  /** 执行 initialize -> negotiated -> initialized 三步握手。 */
  private def initialize(transport: McpTransport, config: McpClientConfig): IO[AgentError, McpSessionInfo] =
    val params = McpJson.obj(
      "protocolVersion" -> Json.Str(config.protocolVersion.value),
      "capabilities"    -> config.capabilities.toJson,
      "clientInfo"      -> McpJson.implementation(config.clientInfo)
    )
    for
      raw     <- transport.request("initialize", params, config.initializeTimeout)
      session <- ZIO.fromEither(parseInitialize(config.serverId, raw))
      _       <- ZIO
        .fail(
          McpJson.protocolError(
            "initialize",
            s"unsupported negotiated protocol version: ${session.protocolVersion.value}",
            Some("unsupported_version")
          )
        )
        .unless(McpProtocolVersion.supported.contains(session.protocolVersion))
      _ <- transport.negotiated(session.protocolVersion)
      _ <- transport.notify("notifications/initialized")
    yield session

  /** 解析初始化结果，远端自报 identity 不覆盖本地 serverId。 */
  private def parseInitialize(serverId: McpServerId, value: Json): Either[AgentError, McpSessionInfo] =
    value match
      case obj: Json.Obj =>
        for
          versionText  <- McpJson.requiredString(obj, "protocolVersion", "initialize")
          capabilities <- McpJson
            .required(obj, "capabilities", "initialize")
            .flatMap(McpServerCapabilities.fromJson)
          serverInfo <- McpJson
            .required(obj, "serverInfo", "initialize")
            .flatMap(McpJson.parseImplementation(_, "initialize"))
          instructions <- McpJson.optionalString(obj, "instructions", "initialize")
        yield McpSessionInfo(
          serverId,
          McpProtocolVersion(versionText),
          serverInfo,
          capabilities,
          instructions
        )
      case _ => Left(McpJson.protocolError("initialize", "result must be an object"))

  /** 单一消费者读取 transport 入站流；通知投影到 Hub，反向请求受 Semaphore 限流并独立运行。 处理器 defect 不会被包装成协议错误，但其 Fiber 会留下 cause
    * 日志且响应内部错误。
    */
  private def dispatchInbound(
      transport: McpTransport,
      session: McpSessionInfo,
      config: McpClientConfig,
      handler: McpClientRequestHandler,
      hub: Hub[McpNotification],
      concurrency: Semaphore
  ): ZIO[Scope, AgentError, Unit] =
    transport.inbound.runForeach {
      case McpInbound.Notification(method, params) => hub.publish(projectNotification(method, params)).unit
      case McpInbound.Request(id, method, params)  =>
        val capabilityCheck = validateInboundCapability(config.capabilities, method)
        concurrency
          .withPermit {
            capabilityCheck match
              case Left(error) => transport.respond(id, Left(error)).ignore
              case Right(())   =>
                handler.handle(session.serverId, method, params).flatMap(transport.respond(id, _)).ignore
          }
          .forkScoped
          .unit
    }

  /** 反向请求必须同时通过能力声明；handler 本身不能绕过 capability negotiation。 */
  private def validateInboundCapability(
      capabilities: McpClientCapabilities,
      method: String
  ): Either[McpRpcError, Unit] =
    val enabled = method match
      case "ping"                   => true
      case "roots/list"             => capabilities.roots
      case "sampling/createMessage" => capabilities.sampling
      case "elicitation/create"     => capabilities.elicitationForm || capabilities.elicitationUrl
      case _                        => false
    Either.cond(enabled, (), McpRpcError(-32601, s"Client capability is not negotiated for method: $method"))

  /** 把任意通知收敛成不泄露 payload 的语义事件。 */
  private def projectNotification(method: String, params: Json.Obj): McpNotification = method match
    case "notifications/tools/list_changed"     => McpNotification.ToolsChanged
    case "notifications/resources/list_changed" => McpNotification.ResourcesChanged
    case "notifications/prompts/list_changed"   => McpNotification.PromptsChanged
    case "notifications/resources/updated"      =>
      McpNotification.ResourceUpdated(
        McpJson.field(params, "uri").collect { case Json.Str(value) => value }.getOrElse("")
      )
    case "notifications/progress" =>
      val token = McpJson.field(params, "progressToken") match
        case Some(Json.Str(value)) => value
        case Some(Json.Num(value)) => value.toString
        case _                     => "unknown"
      val completed =
        McpJson.field(params, "progress").collect { case Json.Num(value) => value.doubleValue }.getOrElse(0d)
      val total   = McpJson.field(params, "total").collect { case Json.Num(value) => value.doubleValue }
      val message = McpJson.field(params, "message").collect { case Json.Str(value) => value }
      McpNotification.Progress(token, completed, total, message)
    case "notifications/tasks/status" =>
      val taskId = McpJson.field(params, "taskId").collect { case Json.Str(value) => value }.getOrElse("")
      val status =
        McpJson.field(params, "status").collect { case Json.Str(value) => value }.getOrElse("unknown")
      McpNotification.TaskStatusChanged(taskId, status)
    case "notifications/message" =>
      val level =
        McpJson.field(params, "level").collect { case Json.Str(value) => value }.getOrElse("unknown")
      val logger = McpJson.field(params, "logger").collect { case Json.Str(value) => value }
      McpNotification.ServerLog(level, logger)
    case other => McpNotification.Unknown(other)
