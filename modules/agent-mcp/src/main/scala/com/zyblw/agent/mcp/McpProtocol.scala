package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** MCP 服务端的业务稳定标识；它用于工具命名空间、授权和遥测，不等同于一次 HTTP session id。 */
final case class McpServerId(value: String):
  require(value.trim.nonEmpty, "MCP server id must not be blank")

/** 初始化阶段协商出的 MCP 协议日期版本。 */
final case class McpProtocolVersion(value: String)

object McpProtocolVersion:
  /** 当前实现锁定的稳定规范版本。
    *
    * 这里故意不自动接受未知的新版本。MCP 版本会改变 transport/session 语义；静默接受意味着编译通过但 运行时可能违反安全要求。升级时应新增契约测试，再显式加入 `supported`。
    */
  val Stable2025_11_25: McpProtocolVersion = McpProtocolVersion("2025-11-25")

  /** 当前客户端真正通过契约测试的版本集合。 */
  val supported: Set[McpProtocolVersion] = Set(Stable2025_11_25)

/** MCP 实现自描述信息。名称与版本用于互操作诊断，不应当被当成可信身份。 */
final case class McpImplementation(
    name: String,
    version: String,
    title: Option[String] = None,
    description: Option[String] = None,
    websiteUrl: Option[String] = None
)

/** 客户端向服务端声明的能力。
  *
  * 默认值全部关闭，尤其不会默认开放 sampling 和 elicitation。远端 MCP 服务端属于外部信任域，不能因为 建立连接就自动获得调用本地模型或向用户索取信息的权力。
  *
  * @param roots
  *   是否允许服务端读取客户端公开的 root 列表
  * @param rootsListChanged
  *   root 变化时是否会发送通知；只有 `roots=true` 时才有意义
  * @param sampling
  *   是否允许服务端提出模型采样请求
  * @param samplingContext
  *   是否支持已软弃用的跨 server 上下文包含模式；默认关闭
  * @param samplingTools
  *   sampling 请求中是否允许附带工具
  * @param elicitationForm
  *   是否接受结构化表单式信息补充请求
  * @param elicitationUrl
  *   是否接受跳转到受信 URL 的带外信息补充请求
  * @param experimentalTasks
  *   是否启用 2025-11-25 的实验性 Tasks 能力
  */
final case class McpClientCapabilities(
    roots: Boolean = false,
    rootsListChanged: Boolean = false,
    sampling: Boolean = false,
    samplingContext: Boolean = false,
    samplingTools: Boolean = false,
    elicitationForm: Boolean = false,
    elicitationUrl: Boolean = false,
    experimentalTasks: Boolean = false
):
  /** 按 MCP 线协议生成开放能力对象，只发送明确启用的字段。 */
  private[mcp] def toJson: Json.Obj =
    McpJson.obj(
      Option.when(roots)(
        "roots" -> McpJson.obj(Option.when(rootsListChanged)("listChanged" -> Json.Bool(true)))
      ),
      Option.when(sampling)(
        "sampling" -> McpJson.obj(
          Option.when(samplingContext)("context" -> Json.Obj()),
          Option.when(samplingTools)("tools"     -> Json.Obj())
        )
      ),
      Option.when(elicitationForm || elicitationUrl)(
        "elicitation" -> McpJson.obj(
          Option.when(elicitationForm)("form" -> Json.Obj()),
          Option.when(elicitationUrl)("url"   -> Json.Obj())
        )
      ),
      Option.when(experimentalTasks)(
        "tasks" -> McpJson.obj(
          "list"   -> Json.Obj(),
          "cancel" -> Json.Obj()
        )
      )
    )

/** 初始化响应中经过归一化的服务端能力。
  *
  * `raw` 被保留用于未来扩展和诊断，但业务授权必须依据显式布尔字段，不能因为 raw 中出现一个相似名称就 自动放权。
  */
final case class McpServerCapabilities(
    tools: Boolean,
    toolsListChanged: Boolean,
    resources: Boolean,
    resourcesSubscribe: Boolean,
    resourcesListChanged: Boolean,
    prompts: Boolean,
    promptsListChanged: Boolean,
    logging: Boolean,
    completions: Boolean,
    experimentalTasks: Boolean,
    raw: Json.Obj
):
  /** 在调用可选能力前执行确定性门禁，避免把“不支持”误判为网络故障。 */
  def requireCapability(enabled: Boolean, name: String): IO[AgentError, Unit] =
    ZIO
      .unless(enabled)(
        ZIO.fail(
          AgentError.ExternalProtocolFailure(
            protocol = "mcp",
            operation = name,
            message = s"MCP server did not negotiate required capability: $name",
            code = Some("capability_not_negotiated")
          )
        )
      )
      .unit

object McpServerCapabilities:
  /** 从开放 JSON 能力对象提取框架理解的稳定子集。 */
  private[mcp] def fromJson(value: Json): Either[AgentError, McpServerCapabilities] = value match
    case obj: Json.Obj =>
      def capability(name: String): Option[Json.Obj] =
        McpJson.field(obj, name).collect { case value: Json.Obj => value }
      def boolean(parent: Option[Json.Obj], name: String): Boolean =
        parent.flatMap(McpJson.field(_, name)).contains(Json.Bool(true))
      val tools     = capability("tools")
      val resources = capability("resources")
      val prompts   = capability("prompts")
      Right(
        McpServerCapabilities(
          tools = tools.nonEmpty,
          toolsListChanged = boolean(tools, "listChanged"),
          resources = resources.nonEmpty,
          resourcesSubscribe = boolean(resources, "subscribe"),
          resourcesListChanged = boolean(resources, "listChanged"),
          prompts = prompts.nonEmpty,
          promptsListChanged = boolean(prompts, "listChanged"),
          logging = capability("logging").nonEmpty,
          completions = capability("completions").nonEmpty,
          experimentalTasks = capability("tasks").nonEmpty,
          raw = obj
        )
      )
    case _ => Left(McpJson.protocolError("initialize", "capabilities must be a JSON object"))

/** 初始化成功后形成的不可变会话描述。 */
final case class McpSessionInfo(
    serverId: McpServerId,
    protocolVersion: McpProtocolVersion,
    serverInfo: McpImplementation,
    capabilities: McpServerCapabilities,
    instructions: Option[String]
)

/** MCP 工具的远端描述；本地风险等级和授权策略不从远端 annotations 推导。 */
final case class McpToolDescriptor(
    name: String,
    description: String,
    inputSchema: Json.Obj,
    outputSchema: Option[Json.Obj] = None,
    title: Option[String] = None,
    annotations: Option[Json.Obj] = None
)

/** MCP 资源目录项。真正读取到的正文由 `McpResourceContent` 表示。 */
final case class McpResource(
    uri: String,
    name: String,
    mediaType: Option[String],
    description: Option[String] = None,
    title: Option[String] = None,
    size: Option[Long] = None
)

/** 读取资源后的内容；文本与 base64 blob 被显式区分，调用方不能误把二进制直接注入模型上下文。 */
enum McpResourceContent:
  case Text(uri: String, text: String, mediaType: Option[String])
  case Blob(uri: String, base64: String, mediaType: Option[String])

/** MCP prompt 参数描述。`required` 表示调用 `prompts/get` 时是否必须提供。 */
final case class McpPromptArgument(name: String, description: Option[String], required: Boolean)

/** 服务端公开的 prompt 模板描述。 */
final case class McpPrompt(
    name: String,
    description: Option[String],
    arguments: Chunk[McpPromptArgument] = Chunk.empty,
    title: Option[String] = None
)

/** prompt 展开后的单条消息，正文保持 MCP 原始 ContentBlock，交由上层安全策略决定哪些类型可注入。 */
final case class McpPromptMessage(role: String, content: Json.Obj)

/** `prompts/get` 的完整结果。 */
final case class McpPromptResult(description: Option[String], messages: Chunk[McpPromptMessage])

/** MCP 2025-11-25 实验性 Task 状态。 */
enum McpTaskStatus:
  case Working, InputRequired, Completed, Failed, Cancelled

/** 实验性长任务的耐久状态投影。
  *
  * 时间字段解析为 `Instant`，避免调用方反复处理 ISO-8601；`ttl=None` 对应协议中的 null（无限保留）。
  */
final case class McpTask(
    taskId: String,
    status: McpTaskStatus,
    statusMessage: Option[String],
    createdAt: java.time.Instant,
    lastUpdatedAt: java.time.Instant,
    ttlMillis: Option[Long],
    pollIntervalMillis: Option[Long]
)

/** JSON-RPC 请求 id；协议允许字符串或数字，因此不能只建模为 Long。 */
final case class McpRequestId private (value: Json):
  /** 用作本地 pending map 键的无歧义表示，数字 1 与字符串 "1" 不会冲突。 */
  private[mcp] def key: String = value match
    case Json.Str(text)   => s"s:$text"
    case Json.Num(number) => s"n:${number.toString}"
    case _                => "invalid"

object McpRequestId:
  /** 为客户端发出的请求创建单调递增数字 id。 */
  def numeric(value: Long): McpRequestId = McpRequestId(Json.Num(BigDecimal(value)))

  /** 严格解析远端 id；null、对象和布尔值都不是合法的 MCP RequestId。 */
  private[mcp] def fromJson(value: Json): Either[AgentError, McpRequestId] = value match
    case Json.Str(_) | Json.Num(_) => Right(McpRequestId(value))
    case _ => Left(McpJson.protocolError("jsonrpc/decode", "request id must be a string or number"))

/** JSON-RPC 错误体；`data` 被保留用于调用方诊断，但框架日志不得默认输出它。 */
final case class McpRpcError(code: Int, message: String, data: Option[Json] = None)

/** 传输层交给客户端宿主处理的服务端主动消息。 */
enum McpInbound:
  /** 不需要响应的通知。 */
  case Notification(method: String, params: Json.Obj)

  /** 服务端反向请求，例如 ping、sampling 或 elicitation；宿主必须显式响应或拒绝。 */
  case Request(id: McpRequestId, method: String, params: Json.Obj)

/** 对常用通知进行语义化后的安全事件。未知通知仍可从 `McpClient.inbound` 读取。 */
enum McpNotification:
  case ToolsChanged
  case ResourcesChanged
  case ResourceUpdated(uri: String)
  case PromptsChanged
  case Progress(token: String, completed: Double, total: Option[Double], message: Option[String])
  case TaskStatusChanged(taskId: String, status: String)

  /** 服务端日志通知的安全投影；故意不携带可能含密钥或用户数据的 `data` 字段。 */
  case ServerLog(level: String, logger: Option[String])

  /** 当前客户端尚未语义化的通知；只暴露方法名，不传播任意 payload。 */
  case Unknown(method: String)

/** MCP 线协议的最小 JSON-RPC 编解码器。
  *
  * 它使用 `Json` AST 而不是封闭 DTO，是因为 MCP 的 capabilities、`_meta` 和结果对象都明确允许扩展字段。 解码器仍严格检查 JSON-RPC envelope
  * 和框架实际依赖的字段，从而兼顾前向兼容与 fail-closed。
  */
private[mcp] object McpJson:
  val EmptyObject: Json.Obj = Json.Obj()

  /** 构造对象并自动忽略 `None` 字段，避免在线上发送显式 null。 */
  def obj(fields: Option[(String, Json)]*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields.flatten))

  /** 构造全部必填字段的对象。 */
  def obj(first: (String, Json), rest: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(first +: rest))

  /** 读取对象字段。 */
  def field(value: Json.Obj, name: String): Option[Json] = value.fields.find(_._1 == name).map(_._2)

  /** 要求字段存在并返回值。 */
  def required(value: Json.Obj, name: String, operation: String): Either[AgentError, Json] =
    field(value, name).toRight(protocolError(operation, s"missing required field: $name"))

  /** 要求字符串字段存在。 */
  def requiredString(value: Json.Obj, name: String, operation: String): Either[AgentError, String] =
    required(value, name, operation).flatMap {
      case Json.Str(text) => Right(text)
      case _              => Left(protocolError(operation, s"field '$name' must be a string"))
    }

  /** 读取可选字符串；存在但类型错误时拒绝，而不是悄悄当成缺失。 */
  def optionalString(value: Json.Obj, name: String, operation: String): Either[AgentError, Option[String]] =
    field(value, name) match
      case None | Some(Json.Null) => Right(None)
      case Some(Json.Str(text))   => Right(Some(text))
      case Some(_)                => Left(protocolError(operation, s"field '$name' must be a string"))

  /** 读取可选布尔值。 */
  def optionalBoolean(value: Json.Obj, name: String, operation: String): Either[AgentError, Option[Boolean]] =
    field(value, name) match
      case None | Some(Json.Null) => Right(None)
      case Some(Json.Bool(value)) => Right(Some(value))
      case Some(_)                => Left(protocolError(operation, s"field '$name' must be a boolean"))

  /** 读取可选整数并防止小数或越界值被截断。 */
  def optionalLong(value: Json.Obj, name: String, operation: String): Either[AgentError, Option[Long]] =
    field(value, name) match
      case None | Some(Json.Null) => Right(None)
      case Some(Json.Num(number)) =>
        scala.util
          .Try(number.longValueExact())
          .toEither
          .left
          .map(_ => protocolError(operation, s"field '$name' must be an exact 64-bit integer"))
          .map(Some(_))
      case Some(_) => Left(protocolError(operation, s"field '$name' must be a number"))

  /** 读取数组并把类型错误提升为协议失败。 */
  def requiredArray(value: Json.Obj, name: String, operation: String): Either[AgentError, Chunk[Json]] =
    required(value, name, operation).flatMap {
      case Json.Arr(values) => Right(values)
      case _                => Left(protocolError(operation, s"field '$name' must be an array"))
    }

  /** 读取对象。 */
  def requiredObject(value: Json.Obj, name: String, operation: String): Either[AgentError, Json.Obj] =
    required(value, name, operation).flatMap {
      case obj: Json.Obj => Right(obj)
      case _             => Left(protocolError(operation, s"field '$name' must be an object"))
    }

  /** 创建统一且不携带原始 payload 的协议错误。 */
  def protocolError(operation: String, message: String, code: Option[String] = None): AgentError =
    AgentError.ExternalProtocolFailure("mcp", operation, message, code)

  /** 编码客户端请求。 */
  def request(id: McpRequestId, method: String, params: Json.Obj): Json.Obj =
    obj(
      "jsonrpc" -> Json.Str("2.0"),
      "id"      -> id.value,
      "method"  -> Json.Str(method),
      "params"  -> params
    )

  /** 编码客户端通知。 */
  def notification(method: String, params: Json.Obj): Json.Obj =
    obj(
      "jsonrpc" -> Json.Str("2.0"),
      "method"  -> Json.Str(method),
      "params"  -> params
    )

  /** 编码对服务端反向请求的成功响应。 */
  def success(id: McpRequestId, result: Json): Json.Obj =
    obj("jsonrpc" -> Json.Str("2.0"), "id" -> id.value, "result" -> result)

  /** 编码对服务端反向请求的失败响应。错误 data 不进入普通日志。 */
  def failure(id: McpRequestId, error: McpRpcError): Json.Obj =
    val body = obj(
      Some("code"    -> Json.Num(BigDecimal(error.code))),
      Some("message" -> Json.Str(error.message)),
      error.data.map("data" -> _)
    )
    obj("jsonrpc" -> Json.Str("2.0"), "id" -> id.value, "error" -> body)

  /** 验证所有线协议消息共有的 JSON-RPC 版本字段。 */
  def validateVersion(value: Json.Obj, operation: String): Either[AgentError, Unit] =
    requiredString(value, "jsonrpc", operation).flatMap { version =>
      Either.cond(version == "2.0", (), protocolError(operation, s"unsupported JSON-RPC version: $version"))
    }

  /** 把 zio-json 解析错误转换成不包含原始消息正文的安全协议错误。 */
  def parseLine(line: String, operation: String): Either[AgentError, Json.Obj] =
    line
      .fromJson[Json]
      .left
      .map(_ => protocolError(operation, "invalid JSON payload"))
      .flatMap {
        case obj: Json.Obj => validateVersion(obj, operation).map(_ => obj)
        case _             => Left(protocolError(operation, "JSON-RPC message must be an object"))
      }

  /** 将实现信息编码为 initialize 参数。 */
  def implementation(value: McpImplementation): Json.Obj =
    obj(
      Some("name"    -> Json.Str(value.name)),
      Some("version" -> Json.Str(value.version)),
      value.title.map("title" -> Json.Str(_)),
      value.description.map("description" -> Json.Str(_)),
      value.websiteUrl.map("websiteUrl" -> Json.Str(_))
    )

  /** 严格解析服务端实现信息。 */
  def parseImplementation(value: Json, operation: String): Either[AgentError, McpImplementation] = value match
    case obj: Json.Obj =>
      for
        name        <- requiredString(obj, "name", operation)
        version     <- requiredString(obj, "version", operation)
        title       <- optionalString(obj, "title", operation)
        description <- optionalString(obj, "description", operation)
        websiteUrl  <- optionalString(obj, "websiteUrl", operation)
      yield McpImplementation(name, version, title, description, websiteUrl)
    case _ => Left(protocolError(operation, "serverInfo must be an object"))

  /** 将 JSON-RPC error response 转为统一错误；远端 data 不拼入 message。 */
  def remoteFailure(operation: String, error: Json.Obj): AgentError =
    val code    = field(error, "code").collect { case Json.Num(value) => value.intValue }.getOrElse(-32000)
    val message =
      field(error, "message").collect { case Json.Str(value) => value }.getOrElse("MCP remote error")
    AgentError.ExternalProtocolFailure(
      protocol = "mcp",
      operation = operation,
      message = message,
      code = Some(code.toString),
      retryable = code == -32001 || code == -32002
    )
