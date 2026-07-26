package com.zyblw.agent.integrations.anthropic

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Anthropic Messages 的纯 JSON 编解码边界；不依赖 HTTP，便于确定性 wire test。 */
private[anthropic] object AnthropicMessagesWire:
  /** 保存上轮完整 assistant content blocks，以便 thinking/signature 和 tool_use 在工具回填时原样重放。 */
  val RawContentBlocksMetadata = "anthropic.messages.content_blocks"

  final private case class UsageDto(input_tokens: Long, output_tokens: Long) derives JsonDecoder
  final private case class ResponseDto(
      id: Option[String],
      content: Chunk[Json],
      stop_reason: Option[String],
      usage: Option[UsageDto]
  ) derives JsonDecoder
  final private case class EncodedMessage(role: String, content: Chunk[Json])

  private val reservedOptions =
    Set("model", "messages", "system", "tools", "tool_choice", "temperature", "max_tokens", "stream")

  /** 把 Provider-neutral 请求编码为 Anthropic Messages JSON。
    *
    * System 与 Developer 消息进入顶层 `system`；Developer 使用显式标签，避免伪装成普通 user。 Tool 消息编码为 user content 中的
    * `tool_result`，assistant 工具调用编码为 `tool_use`。
    *
    * @param request
    *   框架请求
    * @param config
    *   默认模型、最大 token 与扩展选项
    * @param streaming
    *   是否请求 SSE；调用方不能通过 providerOptions 篡改
    */
  def encodeRequest(
      request: ChatRequest,
      config: AnthropicMessagesConfig,
      streaming: Boolean
  ): Either[AgentError, Json.Obj] =
    val options   = config.defaultOptions ++ request.settings.providerOptions
    val conflicts = options.keySet.intersect(reservedOptions)
    if conflicts.nonEmpty then
      Left(
        AgentError.InvalidConfiguration(
          s"providerOptions cannot override reserved Anthropic fields: ${conflicts.toList.sorted.mkString(", ")}"
        )
      )
    else
      sequence(request.messages.filterNot(isInstruction).map(encodeMessage)).map { encoded =>
        val messages = mergeAdjacent(encoded).map(message =>
          obj("role" -> Json.Str(message.role), "content" -> Json.Arr(message.content))
        )
        val required = List(
          "model"      -> Json.Str(request.settings.model.getOrElse(config.defaultModel)),
          "max_tokens" -> Json.Num(request.settings.maxOutputTokens.getOrElse(config.defaultMaxTokens)),
          "messages"   -> Json.Arr(messages),
          "stream"     -> Json.Bool(streaming)
        )
        val system = instructionText(request.messages).map(value => "system" -> Json.Str(value))
        val tools  = Option.when(request.tools.nonEmpty)(
          "tools" -> Json.Arr(request.tools.map(encodeTool))
        )
        val toolChoice = Option.when(request.tools.nonEmpty)(
          "tool_choice" -> encodeToolChoice(request.settings.toolChoice)
        )
        val temperature = request.settings.temperature.map(value => "temperature" -> Json.Num(value))
        obj(required ++ List(system, tools, toolChoice, temperature).flatten ++ options.toList.sortBy(_._1)*)
      }

  /** 解码非流式 Messages response，并拒绝负 usage 或非法 tool input。 */
  def decodeResponse(body: String): IO[AgentError, ChatResponse] =
    for
      json     <- ZIO.fromEither(body.fromJson[Json]).mapError(AgentError.InvalidModelResponse(_))
      response <- decodeResponseJson(json)
    yield response

  /** 解码已经解析的完整 response JSON，供 SSE message_start/终止逻辑复用。 */
  def decodeResponseJson(json: Json): IO[AgentError, ChatResponse] =
    for
      dto   <- ZIO.fromEither(json.toJson.fromJson[ResponseDto]).mapError(AgentError.InvalidModelResponse(_))
      calls <- ZIO
        .foreach(dto.content.filter(block => stringField(block, "type").contains("tool_use")))(decodeToolUse)
      usage <- dto.usage match
        case None        => ZIO.succeed(TokenUsage())
        case Some(value) => validatedUsage(value.input_tokens, value.output_tokens, "response.usage")
      text = dto.content
        .flatMap(block =>
          Option.when(stringField(block, "type").contains("text"))(stringField(block, "text")).flatten
        )
        .mkString
      base =
        if calls.nonEmpty then AgentMessage.assistantToolCalls(calls, text) else AgentMessage.assistant(text)
      message = base.copy(metadata =
        base.metadata.updated(RawContentBlocksMetadata, Json.Arr(dto.content).toJson)
      )
    yield ChatResponse(message, finishReason(dto.stop_reason, calls.nonEmpty), usage, dto.id)

  /** 从标准错误 envelope 中只提取 `error.type`，避免完整 body 进入日志。 */
  def errorType(body: String): Option[String] =
    body
      .fromJson[Json]
      .toOption
      .flatMap(json => field(json, "error"))
      .flatMap(error => stringField(error, "type"))

  /** 普通消息编码；Tool 必须包含 toolCallId，Assistant 优先重放原始 content blocks。 */
  private def encodeMessage(message: AgentMessage): Either[AgentError, EncodedMessage] = message.role match
    case MessageRole.Tool =>
      message.toolCallId
        .toRight(AgentError.InvalidConfiguration("Anthropic Tool message is missing toolCallId"))
        .map { callId =>
          val isError = message.metadata.get("isError").contains("true")
          EncodedMessage(
            "user",
            Chunk(
              obj(
                "type"        -> Json.Str("tool_result"),
                "tool_use_id" -> Json.Str(callId),
                "content"     -> Json.Str(contentAsText(message)),
                "is_error"    -> Json.Bool(isError)
              )
            )
          )
        }
    case MessageRole.Assistant =>
      rawContent(message).map { raw =>
        val blocks = raw.getOrElse(
          encodeContent(message) ++ message.toolCalls.map(call =>
            obj(
              "type"  -> Json.Str("tool_use"),
              "id"    -> Json.Str(call.id),
              "name"  -> Json.Str(call.name),
              "input" -> call.arguments
            )
          )
        )
        EncodedMessage("assistant", blocks)
      }
    case MessageRole.User                           => Right(EncodedMessage("user", encodeContent(message)))
    case MessageRole.System | MessageRole.Developer =>
      Left(AgentError.InvalidConfiguration("System/Developer messages must be encoded in top-level system"))

  /** 把连续相同角色合并；这是 Anthropic 对话交替协议的规范化步骤。 */
  private def mergeAdjacent(messages: Chunk[EncodedMessage]): Chunk[EncodedMessage] =
    messages.foldLeft(Chunk.empty[EncodedMessage]) { (result, next) =>
      result.lastOption match
        case Some(previous) if previous.role == next.role =>
          result.dropRight(1) :+ previous.copy(content = previous.content ++ next.content)
        case _ => result :+ next
    }

  /** 将 System/Developer 指令按原顺序合并；标签让降级行为在 prompt 中可见。 */
  private def instructionText(messages: Chunk[AgentMessage]): Option[String] =
    val values = messages
      .collect {
        case message if message.role == MessageRole.System    => message.text
        case message if message.role == MessageRole.Developer => s"[developer]\n${message.text}"
      }
      .filter(_.nonEmpty)
    Option.when(values.nonEmpty)(values.mkString("\n\n"))

  /** 判断消息是否应从 Messages 数组提升到顶层 system。 */
  private def isInstruction(message: AgentMessage): Boolean =
    message.role == MessageRole.System || message.role == MessageRole.Developer

  /** 编码文本、JSON 和图片 content parts。 */
  private def encodeContent(message: AgentMessage): Chunk[Json] =
    message.content.map {
      case ContentPart.Text(value)      => obj("type" -> Json.Str("text"), "text" -> Json.Str(value))
      case ContentPart.JsonValue(value) => obj("type" -> Json.Str("text"), "text" -> Json.Str(value.toJson))
      case ContentPart.ImageUrl(url, _) =>
        obj(
          "type"   -> Json.Str("image"),
          "source" -> obj("type" -> Json.Str("url"), "url" -> Json.Str(url))
        )
    }

  /** 工具定义使用 Anthropic 顶层 `input_schema`，不带 OpenAI function 包装。 */
  private def encodeTool(tool: ToolDefinition): Json =
    obj(
      "name"         -> Json.Str(tool.name),
      "description"  -> Json.Str(tool.description),
      "input_schema" -> tool.inputSchema
    )

  /** 映射统一 ToolChoice 到 Anthropic 原生 object。 */
  private def encodeToolChoice(choice: ToolChoice): Json = choice match
    case ToolChoice.Auto           => obj("type" -> Json.Str("auto"))
    case ToolChoice.None           => obj("type" -> Json.Str("none"))
    case ToolChoice.Required       => obj("type" -> Json.Str("any"))
    case ToolChoice.Specific(name) => obj("type" -> Json.Str("tool"), "name" -> Json.Str(name))

  /** 从 assistant metadata 中读取上轮原始 blocks；非法 JSON 视为配置/持久化损坏。 */
  private def rawContent(message: AgentMessage): Either[AgentError, Option[Chunk[Json]]] =
    message.metadata.get(RawContentBlocksMetadata) match
      case None        => Right(None)
      case Some(value) =>
        value
          .fromJson[Json]
          .left
          .map(details =>
            AgentError.InvalidConfiguration(s"Invalid saved Anthropic content blocks: $details")
          )
          .flatMap {
            case Json.Arr(values) => Right(Some(values))
            case _ => Left(AgentError.InvalidConfiguration("Saved Anthropic content blocks must be an array"))
          }

  /** 解码一个 tool_use block；input 在 Anthropic 协议中已经是 JSON，而不是 JSON 字符串。 */
  private def decodeToolUse(block: Json): IO[AgentError, ToolCall] =
    for
      id    <- requiredString(block, "id", "tool_use")
      name  <- requiredString(block, "name", "tool_use")
      input <- ZIO
        .fromOption(field(block, "input"))
        .orElseFail(
          AgentError.InvalidModelResponse("tool_use is missing input")
        )
    yield ToolCall(id, name, input)

  /** 按 Anthropic stop_reason 映射统一结束原因；存在工具调用时工具语义优先。 */
  private[anthropic] def finishReason(reason: Option[String], hasCalls: Boolean): FinishReason =
    if hasCalls || reason.contains("tool_use") then FinishReason.ToolCalls
    else
      reason match
        case Some("end_turn") | Some("stop_sequence") => FinishReason.Stop
        case Some("max_tokens")                       => FinishReason.Length
        case Some("refusal")                          => FinishReason.ContentFilter
        case Some(other)                              => FinishReason.Other(other)
        case None                                     => FinishReason.Other("unknown")

  /** usage 参与硬预算，负值不能自动归零。 */
  private[anthropic] def validatedUsage(
      input: Long,
      output: Long,
      location: String
  ): IO[AgentError, TokenUsage] =
    if input >= 0L && output >= 0L then ZIO.succeed(TokenUsage(input, output))
    else ZIO.fail(AgentError.InvalidModelResponse(s"$location 包含负 token: input=$input, output=$output"))

  /** 工具结果的多内容块按原顺序转成字符串，JSON 保持结构表示。 */
  private def contentAsText(message: AgentMessage): String =
    message.content.map {
      case ContentPart.Text(value)      => value
      case ContentPart.JsonValue(value) => value.toJson
      case ContentPart.ImageUrl(url, _) => url
    }.mkString

  /** 读取必需字符串字段并返回带位置的 typed error。 */
  private def requiredString(json: Json, name: String, location: String): IO[AgentError, String] =
    ZIO
      .fromOption(stringField(json, name))
      .orElseFail(
        AgentError.InvalidModelResponse(s"$location is missing string field '$name'")
      )

  /** 安全读取 JSON object 字符串字段。 */
  private[anthropic] def stringField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(value) => value }

  /** 安全读取 JSON object 数值字段并转为 Long。 */
  private[anthropic] def longField(json: Json, name: String): Option[Long] =
    field(json, name).collect { case Json.Num(value) => value.longValue }

  /** 安全读取 JSON object Int 字段。 */
  private[anthropic] def intField(json: Json, name: String): Option[Int] =
    field(json, name).collect { case Json.Num(value) => value.intValue }

  /** 安全读取任意字段。 */
  private[anthropic] def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.find(_._1 == name).map(_._2)
    case _                => None

  /** 构造 JSON object，减少协议 DTO 样板。 */
  private[anthropic] def obj(fields: (String, Json)*): Json.Obj = Json.Obj(Chunk.fromIterable(fields))

  /** 把 Chunk[Either] 翻转为 Either[Chunk]，保留首个具体错误。 */
  private def sequence[A](values: Chunk[Either[AgentError, A]]): Either[AgentError, Chunk[A]] =
    values.foldLeft[Either[AgentError, Chunk[A]]](Right(Chunk.empty)) { (acc, value) =>
      for
        collected <- acc
        current   <- value
      yield collected :+ current
    }
