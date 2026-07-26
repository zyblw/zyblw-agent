package com.zyblw.agent.integrations.openai

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** OpenAI Responses API 的原生 ZIO Provider。
  *
  * 这个实现没有复用 Chat Completions 的 wire DTO：Responses 的输出是 `Item` 列表，流式协议也是带 `type`
  * 的语义事件。保持两套状态机独立，能够在编译与契约测试层阻止协议字段相互污染。
  *
  * @param client
  *   由应用统一提供的 ZIO HTTP Client，可共享连接池、TLS 和客户端中间件
  * @param config
  *   Responses 协议配置；默认 `store=false`，由 zyblw-agent 自己持久化运行状态
  */
final class OpenAIResponsesChatModel(client: Client, config: OpenAIResponsesConfig) extends ModelProvider:
  val providerId: ProviderId                  = ProviderId(OpenAIResponsesDescriptor.value.id)
  override val descriptor: ProviderDescriptor = OpenAIResponsesDescriptor.value

  /** 执行一次非流式 Responses 请求。
    *
    * @param request
    *   Provider-neutral 请求；工具定义与历史消息会转换为 Responses input items
    * @return
    *   归一化的助手消息、工具调用、结束原因、用量及 OpenAI response id
    */
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    for
      json     <- ZIO.fromEither(OpenAIResponsesWire.encodeRequest(request, config, streaming = false))
      response <- client
        .batched(withHeaders(Request.post(config.responsesUrl, Body.fromString(json.toJson))))
        .timeoutFail(timeoutError)(config.requestTimeout)
        .mapError(mapTransportError)
      body    <- response.body.asString.mapError(mapTransportError)
      decoded <-
        if response.status.isSuccess then OpenAIResponsesWire.decodeResponse(body)
        else ZIO.fail(httpError(response.status.code, body))
    yield enrich(decoded, request)

  /** 执行 typed SSE 流式请求。
    *
    * ZIO HTTP 的 streaming 回调把连接生命周期绑定到返回的 ZStream Scope；调用方中断消费时， 中断会继续传播到 HTTP Body，而不是留下后台 socket 或失控
    * Fiber。
    *
    * @param request
    *   Provider-neutral 请求
    * @return
    *   文本增量、工具参数增量、usage 与唯一 Completed 事件组成的背压流
    */
  override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
    ZStream.unwrap {
      for
        json <- ZIO.fromEither(OpenAIResponsesWire.encodeRequest(request, config, streaming = true))
        httpRequest = withHeaders(Request.post(config.responsesUrl, Body.fromString(json.toJson)))
          .addHeader(Header.Accept(MediaType.text.`event-stream`))
        responseStream = client
          .stream(httpRequest) { response =>
            if response.status.isSuccess then OpenAIResponsesSse.events(response.body.asStream)
            else
              ZStream.fromZIO(
                response.body.asString.flatMap(body => ZIO.fail(httpError(response.status.code, body)))
              )
          }
          .mapError(mapTransportError)
          .timeoutFail(timeoutError)(config.requestTimeout)
          .map {
            case ModelStreamEvent.Completed(response) =>
              ModelStreamEvent.Completed(enrich(response, request))
            case event => event
          }
      yield responseStream
    }

  /** 为请求统一加入认证、JSON Content-Type、组织和项目标头。 */
  private def withHeaders(request: Request): Request =
    val authenticated = request
      .addHeader(Header.Authorization.Bearer(config.apiKey))
      .addHeader(Header.ContentType(MediaType.application.json))
    val withOrganization =
      config.organization.fold(authenticated)(value => authenticated.addHeader("OpenAI-Organization", value))
    config.project.fold(withOrganization)(value => withOrganization.addHeader("OpenAI-Project", value))

  /** 给归一化响应补充路由与模型元数据，便于 trace、成本统计和故障定位。 */
  private def enrich(response: ChatResponse, request: ChatRequest): ChatResponse =
    response.copy(
      metadata = response.metadata ++ Map(
        "provider" -> provider,
        "model"    -> request.settings.model.getOrElse(config.defaultModel),
        "protocol" -> descriptor.protocol
      )
    )

  /** 把网络库异常映射成稳定的 Provider typed error；已有 AgentError 不重复包装。 */
  private def mapTransportError(error: Throwable): AgentError = error match
    case value: AgentError => value
    case other => AgentError.ModelFailure(provider, other.getMessage, retryable = true, Some(other))

  /** 根据 HTTP 状态码判断是否允许可靠性层退避重试。 */
  private def httpError(status: Int, body: String): AgentError.ModelFailure =
    AgentError.ModelFailure(
      provider = provider,
      message = s"HTTP $status: ${body.take(2000)}",
      retryable = status == 408 || status == 409 || status == 429 || status >= 500
    )

  /** 单次请求或完整流超过部署预算时使用的统一错误。 */
  private def timeoutError: AgentError =
    AgentError.ModelFailure(provider, "request timed out", retryable = true)

object OpenAIResponsesChatModel:
  /** 从共享 Client 与环境中的配置构建 Provider Layer。 */
  val layer: URLayer[Client & OpenAIResponsesConfig, ChatModel] =
    ZLayer.fromFunction(OpenAIResponsesChatModel.apply)

  /** 把确定配置转成只依赖 Client 的 Layer。
    *
    * @param config
    *   已完成密钥和模型校验的 Responses 配置
    */
  def configured(config: OpenAIResponsesConfig): URLayer[Client, ChatModel] =
    ZLayer.succeed(config) >>> layer

/** Responses JSON 编解码边界；保持为纯函数/typed effect，便于无网络契约测试。 */
private[openai] object OpenAIResponsesWire:
  private val RawOutputItemsMetadata = "openai.responses.output_items"

  final private case class InputTokensDetailsDto(cached_tokens: Option[Long]) derives JsonDecoder
  final private case class OutputTokensDetailsDto(reasoning_tokens: Option[Long]) derives JsonDecoder
  final private case class UsageDto(
      input_tokens: Long,
      output_tokens: Long,
      input_tokens_details: Option[InputTokensDetailsDto],
      output_tokens_details: Option[OutputTokensDetailsDto]
  ) derives JsonDecoder
  final private case class IncompleteDetailsDto(reason: Option[String]) derives JsonDecoder
  final private case class ResponseDto(
      id: Option[String],
      status: Option[String],
      output: Chunk[Json],
      usage: Option[UsageDto],
      incomplete_details: Option[IncompleteDetailsDto],
      error: Option[Json]
  ) derives JsonDecoder

  private val reservedOptions = Set(
    "model",
    "input",
    "tools",
    "tool_choice",
    "temperature",
    "max_output_tokens",
    "parallel_tool_calls",
    "store",
    "stream"
  )

  /** 把框架请求编码成 Responses 创建请求。
    *
    * @param request
    *   厂商无关消息、工具与生成参数
    * @param config
    *   部署级默认模型、存储策略和额外参数
    * @param streaming
    *   是否请求 typed SSE；该字段由调用路径控制，不能被 providerOptions 覆盖
    * @return
    *   保留字段冲突时返回 InvalidConfiguration，否则返回完整 JSON object
    */
  def encodeRequest(
      request: ChatRequest,
      config: OpenAIResponsesConfig,
      streaming: Boolean
  ): Either[AgentError, Json.Obj] =
    val options  = config.defaultOptions ++ request.settings.providerOptions
    val reserved = options.keySet.intersect(reservedOptions)
    if reserved.nonEmpty then
      Left(
        AgentError.InvalidConfiguration(
          s"providerOptions cannot override reserved Responses fields: ${reserved.toList.sorted.mkString(", ")}"
        )
      )
    else
      sequence(request.messages.map(encodeMessage)).map { encodedMessages =>
        val required = List(
          "model"               -> Json.Str(request.settings.model.getOrElse(config.defaultModel)),
          "input"               -> Json.Arr(encodedMessages.flatten),
          "store"               -> Json.Bool(config.store),
          "stream"              -> Json.Bool(streaming),
          "parallel_tool_calls" -> Json.Bool(config.parallelToolCalls)
        )
        val tools = Option.when(request.tools.nonEmpty)(
          "tools" -> Json.Arr(request.tools.map(encodeTool))
        )
        val toolChoice = Option.when(request.tools.nonEmpty)(
          "tool_choice" -> encodeToolChoice(request.settings.toolChoice)
        )
        val temperature = request.settings.temperature.map(value => "temperature" -> Json.Num(value))
        val maxTokens = request.settings.maxOutputTokens.map(value => "max_output_tokens" -> Json.Num(value))
        obj(
          required ++ List(tools, toolChoice, temperature, maxTokens).flatten ++ options.toList.sortBy(_._1)*
        )
      }

  /** 解码非流式 Response JSON。
    *
    * @param body
    *   OpenAI 返回的完整 JSON 字符串
    * @return
    *   Provider-neutral ChatResponse；负 usage、非法工具参数或失败状态都会显式失败
    */
  def decodeResponse(body: String): IO[AgentError, ChatResponse] =
    ZIO
      .fromEither(body.fromJson[Json])
      .mapError(details => AgentError.InvalidModelResponse(details))
      .flatMap(decodeResponseJson)

  /** 解码已经由 SSE 外层解析出的 `response` 对象。 保存完整 output items 是为了让 reasoning item 在下一轮工具回填时能原样重放。
    */
  def decodeResponseJson(json: Json): IO[AgentError, ChatResponse] =
    for
      dto <- ZIO
        .fromEither(json.toJson.fromJson[ResponseDto])
        .mapError(details => AgentError.InvalidModelResponse(details))
      _ <- dto.error match
        case Some(error) =>
          ZIO.fail(AgentError.InvalidModelResponse(s"Responses API failure: ${error.toJson.take(1000)}"))
        case None => ZIO.unit
      calls <- ZIO.foreach(dto.output.filter(item => stringField(item, "type").contains("function_call"))) {
        item => decodeToolCall(item)
      }
      text = dto.output.flatMap(outputText).mkString
      raw  = Json.Arr(dto.output).toJson
      base =
        if calls.nonEmpty then AgentMessage.assistantToolCalls(calls, text) else AgentMessage.assistant(text)
      message = base.copy(metadata = base.metadata.updated(RawOutputItemsMetadata, raw))
      usage <- dto.usage match
        case None => ZIO.succeed(TokenUsage())
        case Some(value)
            if value.input_tokens >= 0L && value.output_tokens >= 0L &&
              value.input_tokens_details
                .flatMap(_.cached_tokens)
                .forall(token => token >= 0L && token <= value.input_tokens) &&
              value.output_tokens_details
                .flatMap(_.reasoning_tokens)
                .forall(token => token >= 0L && token <= value.output_tokens) =>
          ZIO.succeed(
            TokenUsage(
              value.input_tokens,
              value.output_tokens,
              value.input_tokens_details.flatMap(_.cached_tokens).getOrElse(0L),
              value.output_tokens_details.flatMap(_.reasoning_tokens).getOrElse(0L)
            )
          )
        case Some(value) =>
          ZIO.fail(
            AgentError.InvalidModelResponse(
              s"response.usage token 用量无效: input=${value.input_tokens}, output=${value.output_tokens}"
            )
          )
      reason = finishReason(dto.status, dto.incomplete_details.flatMap(_.reason), calls.nonEmpty)
    yield ChatResponse(message, reason, usage, dto.id)

  /** 把一条框架消息扩展为零到多个 Responses input item。 */
  private def encodeMessage(message: AgentMessage): Either[AgentError, Chunk[Json]] = message.role match
    case MessageRole.Tool =>
      message.toolCallId match
        case None         => Left(AgentError.InvalidConfiguration("Tool message is missing toolCallId"))
        case Some(callId) =>
          Right(
            Chunk(
              obj(
                "type"    -> Json.Str("function_call_output"),
                "call_id" -> Json.Str(callId),
                "output"  -> Json.Str(toolOutput(message))
              )
            )
          )
    case MessageRole.Assistant =>
      decodeRawOutputItems(message).map {
        case Some(items) => items
        case None        =>
          val textItem = Option.when(message.content.nonEmpty && message.text.nonEmpty)(
            encodeRoleMessage(message)
          )
          val calls = message.toolCalls.map(call =>
            obj(
              "type"      -> Json.Str("function_call"),
              "call_id"   -> Json.Str(call.id),
              "name"      -> Json.Str(call.name),
              "arguments" -> Json.Str(call.arguments.toJson)
            )
          )
          Chunk.fromIterable(textItem) ++ calls
      }
    case _ => Right(Chunk(encodeRoleMessage(message)))

  /** 读取上轮 Response 保存的原始 output items。 reasoning 模型要求工具结果回填时同时带回 reasoning items，因此不能只重建可见文本和 function call。
    */
  private def decodeRawOutputItems(message: AgentMessage): Either[AgentError, Option[Chunk[Json]]] =
    message.metadata.get(RawOutputItemsMetadata) match
      case None        => Right(None)
      case Some(value) =>
        value
          .fromJson[Json]
          .left
          .map(details => AgentError.InvalidConfiguration(s"Invalid saved Responses output items: $details"))
          .flatMap {
            case Json.Arr(items) => Right(Some(items))
            case _ => Left(AgentError.InvalidConfiguration("Saved Responses output items must be an array"))
          }

  /** 编码普通角色消息，并为图片使用 Responses 原生 input_image 内容块。 */
  private def encodeRoleMessage(message: AgentMessage): Json =
    val role = message.role match
      case MessageRole.System    => "system"
      case MessageRole.Developer => "developer"
      case MessageRole.User      => "user"
      case MessageRole.Assistant => "assistant"
      case MessageRole.Tool      => "tool"
    val content = message.content.map {
      case ContentPart.Text(value) =>
        obj(
          "type" -> Json.Str(if message.role == MessageRole.Assistant then "output_text" else "input_text"),
          "text" -> Json.Str(value)
        )
      case ContentPart.JsonValue(value) =>
        obj(
          "type" -> Json.Str(if message.role == MessageRole.Assistant then "output_text" else "input_text"),
          "text" -> Json.Str(value.toJson)
        )
      case ContentPart.ImageUrl(url, detail) =>
        obj(
          List(
            "type"      -> Json.Str("input_image"),
            "image_url" -> Json.Str(url)
          ) ++ detail.map(value => "detail" -> Json.Str(value)).toList*
        )
    }
    obj("type" -> Json.Str("message"), "role" -> Json.Str(role), "content" -> Json.Arr(content))

  /** Responses 的 function tool 字段位于工具对象顶层，不使用 Chat Completions 的 `function` 包装层。 */
  private def encodeTool(tool: ToolDefinition): Json =
    obj(
      "type"        -> Json.Str("function"),
      "name"        -> Json.Str(tool.name),
      "description" -> Json.Str(tool.description),
      "parameters"  -> tool.inputSchema,
      "strict"      -> Json.Bool(tool.strict)
    )

  /** 把统一 ToolChoice 映射为 Responses 原生形状。 */
  private def encodeToolChoice(choice: ToolChoice): Json = choice match
    case ToolChoice.Auto           => Json.Str("auto")
    case ToolChoice.None           => Json.Str("none")
    case ToolChoice.Required       => Json.Str("required")
    case ToolChoice.Specific(name) => obj("type" -> Json.Str("function"), "name" -> Json.Str(name))

  /** 从 function_call output item 解码稳定 call_id、名称和 JSON 参数。 */
  private def decodeToolCall(item: Json): IO[AgentError, ToolCall] =
    for
      callId    <- requiredString(item, "call_id", "function_call")
      name      <- requiredString(item, "name", "function_call")
      encoded   <- requiredString(item, "arguments", "function_call")
      arguments <- ZIO
        .fromEither(encoded.fromJson[Json])
        .mapError(details => AgentError.InvalidModelResponse(s"invalid tool arguments: $details"))
    yield ToolCall(callId, name, arguments)

  /** 提取 message item 中的所有 output_text；拒绝块不伪装成正常文本。 */
  private def outputText(item: Json): Chunk[String] =
    if !stringField(item, "type").contains("message") then Chunk.empty
    else
      arrayField(item, "content").getOrElse(Chunk.empty).flatMap { part =>
        Option.when(stringField(part, "type").contains("output_text"))(stringField(part, "text")).flatten
      }

  /** 根据 Responses 生命周期状态映射框架结束原因。 */
  private def finishReason(
      status: Option[String],
      incompleteReason: Option[String],
      hasCalls: Boolean
  ): FinishReason =
    if hasCalls then FinishReason.ToolCalls
    else
      status match
        case Some("completed")  => FinishReason.Stop
        case Some("incomplete") =>
          incompleteReason match
            case Some("max_output_tokens") => FinishReason.Length
            case Some("content_filter")    => FinishReason.ContentFilter
            case Some(other)               => FinishReason.Other(other)
            case None                      => FinishReason.Other("incomplete")
        case Some(other) => FinishReason.Other(other)
        case None        => FinishReason.Other("unknown")

  /** 工具结果通常是单个 JSON 块；多块时保持原顺序拼接为模型可读字符串。 */
  private def toolOutput(message: AgentMessage): String =
    message.content.map {
      case ContentPart.Text(value)      => value
      case ContentPart.JsonValue(value) => value.toJson
      case ContentPart.ImageUrl(url, _) => url
    }.mkString

  /** 从 JSON object 读取必需字符串，并生成带位置的协议错误。 */
  private def requiredString(json: Json, name: String, location: String): IO[AgentError, String] =
    ZIO
      .fromOption(stringField(json, name))
      .orElseFail(
        AgentError.InvalidModelResponse(s"$location is missing string field '$name'")
      )

  /** 安全读取 JSON object 的字符串字段。 */
  private[openai] def stringField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(value) => value }

  /** 安全读取 JSON object 的整数索引；非整数值返回 None。 */
  private[openai] def intField(json: Json, name: String): Option[Int] =
    field(json, name).collect { case Json.Num(value) => value.intValue }

  /** 安全读取 JSON object 的数组字段。 */
  private def arrayField(json: Json, name: String): Option[Chunk[Json]] =
    field(json, name).collect { case Json.Arr(values) => values }

  /** 安全读取任意 JSON object 字段。 */
  private[openai] def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.find(_._1 == name).map(_._2)
    case _                => None

  /** 把一组 Either 顺序翻转为 Either + Chunk，保留首个带类型的配置错误。 */
  private def sequence[A](values: Chunk[Either[AgentError, A]]): Either[AgentError, Chunk[A]] =
    values.foldLeft[Either[AgentError, Chunk[A]]](Right(Chunk.empty)) { (acc, value) =>
      for
        collected <- acc
        current   <- value
      yield collected :+ current
    }

  /** 构造 JSON object 的小型纯函数，减少协议代码中的集合样板。 */
  private[openai] def obj(fields: (String, Json)*): Json.Obj = Json.Obj(Chunk.fromIterable(fields))

/** Responses typed SSE 的不可变增量状态机。 */
private[openai] object OpenAIResponsesSse:
  private val EndOfStream = "\u0000zyblw-agent-responses-sse-eof\u0000"

  final private case class PartialTool(itemId: String, callId: String, name: String, arguments: String)
  final private case class State(
      responseId: Option[String] = None,
      tools: Map[Int, PartialTool] = Map.empty,
      completedCalls: Set[String] = Set.empty,
      started: Boolean = false,
      terminal: Boolean = false
  )

  /** 把任意 HTTP byte chunk 转换成 Provider-neutral 语义事件。
    *
    * @param bytes
    *   ZIO HTTP Body 字节流；UTF-8 字符和 SSE frame 可以跨任意网络 chunk
    * @return
    *   遵守下游背压的事件流；终止事件之前断流会显式失败
    */
  def events(bytes: ZStream[Any, Throwable, Byte]): ZStream[Any, AgentError, ModelStreamEvent] =
    val frames = bytes
      .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
      .concat(ZStream.succeed(""))
      .mapAccum(Chunk.empty[String]) { (dataLines, line) =>
        if line.isEmpty then (Chunk.empty, Option.when(dataLines.nonEmpty)(dataLines.mkString("\n")))
        else if line.startsWith("data:") then (dataLines :+ line.drop(5).stripLeading(), None)
        else (dataLines, None)
      }
      .collectSome
      .mapError(error => AgentError.InvalidModelResponse(s"Responses SSE UTF-8 解码失败: ${error.getMessage}"))

    frames
      .concat(ZStream.succeed(EndOfStream))
      .mapAccumZIO(State())(decodeFrame)
      .mapConcatChunk(identity)

  /** 解码一个完整 data frame，并以不可变 State 返回下一状态及零到多个事件。 */
  private def decodeFrame(state: State, frame: String): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    if frame == EndOfStream then
      if state.terminal then ZIO.succeed(state -> Chunk.empty)
      else ZIO.fail(AgentError.InvalidModelResponse("Responses Provider 流在终止事件前中断"))
    else if frame == "[DONE]" then
      if state.terminal then ZIO.succeed(state -> Chunk.empty)
      else ZIO.fail(AgentError.InvalidModelResponse("Responses Provider 在 response.completed 前发送 [DONE]"))
    else
      ZIO
        .fromEither(frame.fromJson[Json])
        .mapError(details => AgentError.InvalidModelResponse(details))
        .flatMap(json => decodeEvent(state, json))

  /** 按 `type` 分派 Responses 语义事件；未知事件安全忽略，完整响应仍由 completed 校验。 */
  private def decodeEvent(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    OpenAIResponsesWire.stringField(event, "type") match
      case Some("response.created") =>
        val response   = OpenAIResponsesWire.field(event, "response").getOrElse(event)
        val responseId = OpenAIResponsesWire.stringField(response, "id").orElse(state.responseId)
        val emitted    =
          if state.started then Chunk.empty else Chunk(ModelStreamEvent.ResponseStarted(responseId))
        ZIO.succeed(state.copy(responseId = responseId, started = true) -> emitted)
      case Some("response.output_text.delta") =>
        val delta = OpenAIResponsesWire.stringField(event, "delta").getOrElse("")
        ZIO.succeed(
          startIfNeeded(
            state,
            OpenAIResponsesWire.stringField(event, "response_id"),
            ModelStreamEvent.TextDelta(delta)
          )
        )
      case Some("response.reasoning_summary_text.delta") =>
        val delta = OpenAIResponsesWire.stringField(event, "delta").getOrElse("")
        ZIO.succeed(
          startIfNeeded(
            state,
            OpenAIResponsesWire.stringField(event, "response_id"),
            ModelStreamEvent.ReasoningDelta(delta)
          )
        )
      case Some("response.output_item.added")                       => decodeToolStarted(state, event)
      case Some("response.function_call_arguments.delta")           => decodeToolDelta(state, event)
      case Some("response.function_call_arguments.done")            => decodeToolDone(state, event)
      case Some("response.completed") | Some("response.incomplete") => complete(state, event)
      case Some("response.failed")                                  =>
        val response = OpenAIResponsesWire.field(event, "response").getOrElse(event)
        ZIO.fail(AgentError.InvalidModelResponse(s"Responses stream failed: ${response.toJson.take(1000)}"))
      case Some("error") =>
        ZIO.fail(AgentError.InvalidModelResponse(s"Responses stream error: ${event.toJson.take(1000)}"))
      case _ => ZIO.succeed(state -> Chunk.empty)

  /** 从 output_item.added 建立工具累积槽，并发出稳定 call_id 的 ToolCallStarted。 */
  private def decodeToolStarted(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val item = OpenAIResponsesWire.field(event, "item").getOrElse(Json.Obj())
    if !OpenAIResponsesWire.stringField(item, "type").contains("function_call") then
      ZIO.succeed(state -> Chunk.empty)
    else
      val index        = OpenAIResponsesWire.intField(event, "output_index").getOrElse(state.tools.size)
      val itemId       = OpenAIResponsesWire.stringField(item, "id").getOrElse(s"item-$index")
      val callId       = OpenAIResponsesWire.stringField(item, "call_id").getOrElse(itemId)
      val name         = OpenAIResponsesWire.stringField(item, "name").getOrElse("")
      val args         = OpenAIResponsesWire.stringField(item, "arguments").getOrElse("")
      val partial      = PartialTool(itemId, callId, name, args)
      val startedState = ensureStarted(state, OpenAIResponsesWire.stringField(event, "response_id"))
      val startEvent   = Option.when(name.nonEmpty)(ModelStreamEvent.ToolCallStarted(callId, name)).toList
      ZIO.succeed(
        startedState.copy(tools = startedState.tools.updated(index, partial)) -> Chunk.fromIterable(
          startEvent
        )
      )

  /** 把 function_call_arguments.delta 追加到对应 output index，保持模型生成参数的原始顺序。 */
  private def decodeToolDelta(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val index    = OpenAIResponsesWire.intField(event, "output_index").getOrElse(0)
    val itemId   = OpenAIResponsesWire.stringField(event, "item_id").getOrElse(s"item-$index")
    val delta    = OpenAIResponsesWire.stringField(event, "delta").getOrElse("")
    val previous = state.tools.getOrElse(index, PartialTool(itemId, itemId, "", ""))
    val next     = previous.copy(arguments = previous.arguments + delta)
    val started  = ensureStarted(state, OpenAIResponsesWire.stringField(event, "response_id"))
    val emitted  = Option
      .when(delta.nonEmpty)(
        ModelStreamEvent.ToolCallDelta(next.callId, Option.when(next.name.nonEmpty)(next.name), delta)
      )
      .toList
    ZIO.succeed(started.copy(tools = started.tools.updated(index, next)) -> Chunk.fromIterable(emitted))

  /** 校验完整工具参数并发出 ToolCallCompleted；同时兼容 item 包装和顶层 done 字段。 */
  private def decodeToolDone(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val index    = OpenAIResponsesWire.intField(event, "output_index").getOrElse(0)
    val item     = OpenAIResponsesWire.field(event, "item").getOrElse(event)
    val previous = state.tools.get(index)
    val itemId   =
      OpenAIResponsesWire.stringField(item, "id").orElse(previous.map(_.itemId)).getOrElse(s"item-$index")
    val callId =
      OpenAIResponsesWire.stringField(item, "call_id").orElse(previous.map(_.callId)).getOrElse(itemId)
    val name = OpenAIResponsesWire.stringField(item, "name").orElse(previous.map(_.name)).getOrElse("")
    val args =
      OpenAIResponsesWire.stringField(item, "arguments").orElse(previous.map(_.arguments)).getOrElse("")
    for
      arguments <- ZIO
        .fromEither(args.fromJson[Json])
        .mapError(details => AgentError.InvalidModelResponse(s"流式工具参数不是合法 JSON: $details"))
      call    = ToolCall(callId, name, arguments)
      next    = PartialTool(itemId, callId, name, args)
      started = ensureStarted(state, OpenAIResponsesWire.stringField(event, "response_id"))
    yield started.copy(
      tools = started.tools.updated(index, next),
      completedCalls = started.completedCalls + callId
    ) -> Chunk(ModelStreamEvent.ToolCallCompleted(call))

  /** 使用终止事件内的完整 response 作为最终权威结果，并补发缺失的工具完成事件。 */
  private def complete(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val response = OpenAIResponsesWire.field(event, "response").getOrElse(event)
    OpenAIResponsesWire.decodeResponseJson(response).map { decoded =>
      val started      = ensureStarted(state, decoded.providerRequestId)
      val startedEvent =
        if state.started then Chunk.empty
        else Chunk(ModelStreamEvent.ResponseStarted(decoded.providerRequestId))
      val missingCalls = decoded.message.toolCalls
        .filterNot(call => started.completedCalls.contains(call.id))
        .map(ModelStreamEvent.ToolCallCompleted(_))
      val usageEvent =
        Option.when(decoded.usage != TokenUsage())(ModelStreamEvent.UsageUpdated(decoded.usage)).toList
      val events =
        startedEvent ++ missingCalls ++ Chunk.fromIterable(usageEvent) :+ ModelStreamEvent.Completed(decoded)
      started.copy(terminal = true) -> events
    }

  /** 若流尚未收到 response.created，则在首个可见增量前补发 ResponseStarted。 */
  private def startIfNeeded(
      state: State,
      responseId: Option[String],
      event: ModelStreamEvent
  ): (State, Chunk[ModelStreamEvent]) =
    val next   = ensureStarted(state, responseId)
    val prefix =
      if state.started then Chunk.empty else Chunk(ModelStreamEvent.ResponseStarted(next.responseId))
    next -> (prefix :+ event)

  /** 更新 started 与 responseId，但不产生事件；事件是否需要补发由调用点决定。 */
  private def ensureStarted(state: State, responseId: Option[String]): State =
    state.copy(responseId = responseId.orElse(state.responseId), started = true)
