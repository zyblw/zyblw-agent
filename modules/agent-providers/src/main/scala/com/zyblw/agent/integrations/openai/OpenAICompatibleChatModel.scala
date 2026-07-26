package com.zyblw.agent.integrations.openai

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

final class OpenAICompatibleChatModel(client: Client, config: OpenAICompatibleConfig) extends ChatModel:
  val provider: String                        = config.compatibility.descriptor.id
  override val descriptor: ProviderDescriptor = config.compatibility.descriptor

  /** 发送非流式请求：能力校验、统一请求编码、HTTP 调用和统一响应解码均在此闭环。 */
  def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
    for
      json <- ZIO.fromEither(
        OpenAIWire.encodeRequest(
          request,
          config.defaultModel,
          config.compatibility,
          config.defaultOptions
        )
      )
      httpRequest = Request
        .post(config.chatCompletionsUrl, Body.fromString(json.toJson))
        .addHeader(Header.Authorization.Bearer(config.apiKey))
        .addHeader(Header.ContentType(MediaType.application.json))
      withOrganization = config.organization.fold(httpRequest)(value =>
        httpRequest.addHeader("OpenAI-Organization", value)
      )
      response <- client
        .batched(withOrganization)
        .timeoutFail(AgentError.ModelFailure(provider, "request timed out", retryable = true))(
          config.requestTimeout
        )
        .mapError {
          case error: AgentError => error
          case error => AgentError.ModelFailure(provider, error.getMessage, retryable = true, Some(error))
        }
      responseBody <- response.body.asString.mapError { error =>
        AgentError.ModelFailure(provider, error.getMessage, retryable = true, Some(error))
      }
      result <-
        (
          if response.status.code >= 200 && response.status.code < 300 then
            OpenAIWire.decodeResponse(responseBody, config.compatibility)
          else
            ZIO.fail(
              AgentError.ModelFailure(
                provider,
                s"HTTP ${response.status.code}: ${responseBody.take(2000)}",
                retryable = response.status.code == 429 || response.status.code >= 500
              )
            )
        )
    yield result.copy(
      metadata = result.metadata ++ Map(
        "provider" -> provider,
        "model"    -> request.settings.model.getOrElse(config.defaultModel)
      )
    )

  /** 使用 ZIO HTTP streaming 模式保持连接 Scope，并以 `utf8Decode` 处理跨网络 chunk 的 UTF-8 字符。
    */
  /** 发送流式请求。返回的 ZStream 直接消费 HTTP Body，消费者中断会沿 Scope 关闭连接； 原始 byte chunk 由 OpenAISse 处理 UTF-8、SSE 和 JSON
    * 的任意边界。
    */
  override def stream(request: ChatRequest): ZStream[Any, AgentError, ModelStreamEvent] =
    if !descriptor.capabilities.streaming then super.stream(request)
    else
      ZStream.unwrap {
        for
          json <- ZIO.fromEither(
            OpenAIWire.encodeRequest(
              request,
              config.defaultModel,
              config.compatibility,
              config.defaultOptions
            )
          )
          streamingJson <- ZIO.fromEither(OpenAISse.withStreaming(json))
          httpRequest = Request
            .post(config.chatCompletionsUrl, Body.fromString(streamingJson.toJson))
            .addHeader(Header.Authorization.Bearer(config.apiKey))
            .addHeader(Header.ContentType(MediaType.application.json))
            .addHeader(Header.Accept(MediaType.text.`event-stream`))
          withOrganization = config.organization.fold(httpRequest)(value =>
            httpRequest.addHeader("OpenAI-Organization", value)
          )
          stream = client
            .stream(withOrganization) { response =>
              if response.status.code >= 200 && response.status.code < 300 then
                OpenAISse.events(response.body.asStream, config.compatibility)
              else
                ZStream.fromZIO(
                  response.body.asString.flatMap(body =>
                    ZIO.fail(
                      AgentError.ModelFailure(
                        provider,
                        s"HTTP ${response.status.code}: ${body.take(2000)}",
                        retryable = response.status.code == 429 || response.status.code >= 500
                      )
                    )
                  )
                )
            }
            .mapError {
              case error: AgentError => error
              case error => AgentError.ModelFailure(provider, error.getMessage, retryable = true, Some(error))
            }
            .timeoutFail(AgentError.ModelFailure(provider, "stream timed out", retryable = true))(
              config.requestTimeout
            )
        yield stream
      }

object OpenAICompatibleChatModel:
  val layer: URLayer[Client & OpenAICompatibleConfig, ChatModel] =
    ZLayer.fromFunction(OpenAICompatibleChatModel.apply)

  /** 从共享 ZIO HTTP Client 和指定 Provider 配置构造 ChatModel Layer。 */
  def configured(config: OpenAICompatibleConfig): URLayer[Client, ChatModel] =
    ZLayer.succeed(config) >>> layer

private[openai] object OpenAIWire:
  final private case class FunctionCallDto(name: String, arguments: String) derives JsonDecoder
  final private case class ToolCallDto(id: String, function: FunctionCallDto) derives JsonDecoder
  final private case class MessageDto(
      content: Option[String],
      reasoning_content: Option[String],
      tool_calls: Option[Chunk[ToolCallDto]]
  ) derives JsonDecoder
  final private case class ChoiceDto(message: MessageDto, finish_reason: Option[String]) derives JsonDecoder
  final private case class PromptTokensDetailsDto(cached_tokens: Option[Long]) derives JsonDecoder
  final private case class CompletionTokensDetailsDto(reasoning_tokens: Option[Long]) derives JsonDecoder
  final private case class UsageDto(
      prompt_tokens: Long,
      completion_tokens: Long,
      prompt_tokens_details: Option[PromptTokensDetailsDto],
      completion_tokens_details: Option[CompletionTokensDetailsDto]
  ) derives JsonDecoder
  final private case class ResponseDto(id: Option[String], choices: Chunk[ChoiceDto], usage: Option[UsageDto])
      derives JsonDecoder

  private val reservedOptions = Set(
    "model",
    "messages",
    "tools",
    "tool_choice",
    "temperature",
    "max_tokens",
    "max_completion_tokens",
    "stream"
  )

  /** 把内部请求编码成 Provider JSON，并阻止请求级参数覆盖保留协议字段。 */
  def encodeRequest(
      request: ChatRequest,
      defaultModel: String,
      compatibility: OpenAICompatibility,
      defaultOptions: Map[String, Json] = Map.empty
  ): Either[AgentError, Json] =
    val provider          = compatibility.descriptor.id
    val unsupportedChoice = compatibility.toolChoiceMode match
      case ToolChoiceMode.Full     => None
      case ToolChoiceMode.AutoOnly =>
        Option.unless(request.settings.toolChoice == ToolChoice.Auto)("only auto is supported")
      case ToolChoiceMode.Omit =>
        Option.unless(request.settings.toolChoice == ToolChoice.Auto)("tool_choice is omitted")
    val options  = defaultOptions ++ request.settings.providerOptions
    val reserved = options.keySet.intersect(reservedOptions)

    if request.tools.nonEmpty && !compatibility.descriptor.capabilities.toolCalls then
      Left(AgentError.UnsupportedModelCapability(provider, "tool calls", "tools were supplied"))
    else if request.tools.nonEmpty && unsupportedChoice.nonEmpty then
      Left(AgentError.UnsupportedModelCapability(provider, "tool_choice", unsupportedChoice.get))
    else if reserved.nonEmpty then
      Left(
        AgentError.InvalidConfiguration(
          s"providerOptions cannot override reserved fields: ${reserved.toList.sorted.mkString(", ")}"
        )
      )
    else Right(encodeValidated(request, defaultModel, compatibility, options))

  /** 执行字段兼容校验并按“基础字段→默认选项→请求选项”合并。 */
  private def encodeValidated(
      request: ChatRequest,
      defaultModel: String,
      compatibility: OpenAICompatibility,
      options: Map[String, Json]
  ): Json =
    val required = List(
      "model"    -> Json.Str(request.settings.model.getOrElse(defaultModel)),
      "messages" -> arr(request.messages.map(message => encodeMessage(message, compatibility)))
    )
    val tools = Option.when(request.tools.nonEmpty)(
      "tools" -> arr(request.tools.map(tool => encodeTool(tool, compatibility)))
    )
    val temperature = request.settings.temperature.map(value => "temperature" -> Json.Num(value))
    val maxTokens   =
      request.settings.maxOutputTokens.map(value => compatibility.outputTokenField -> Json.Num(value))
    val toolChoice = Option.when(
      request.tools.nonEmpty && compatibility.toolChoiceMode != ToolChoiceMode.Omit
    )("tool_choice" -> encodeToolChoice(request.settings.toolChoice))
    obj(required ++ List(tools, temperature, maxTokens, toolChoice).flatten ++ options.toList.sortBy(_._1)*)

  /** 解码完整 JSON 响应，重建文本、reasoning、工具调用、usage 和结束原因。 */
  def decodeResponse(body: String, compatibility: OpenAICompatibility): IO[AgentError, ChatResponse] =
    for
      decoded <- ZIO
        .fromEither(body.fromJson[ResponseDto])
        .mapError(details => AgentError.InvalidModelResponse(details))
      choice <- ZIO
        .fromOption(decoded.choices.headOption)
        .orElseFail(AgentError.InvalidModelResponse("response contained no choices"))
      calls <- ZIO.foreach(Chunk.fromIterable(choice.message.tool_calls.getOrElse(Chunk.empty))) { call =>
        ZIO
          .fromEither(call.function.arguments.fromJson[Json])
          .mapError(details => AgentError.InvalidModelResponse(s"invalid tool arguments: $details"))
          .map(arguments => ToolCall(call.id, call.function.name, arguments))
      }
      baseMessage =
        if calls.nonEmpty then AgentMessage.assistantToolCalls(calls, choice.message.content.getOrElse(""))
        else AgentMessage.assistant(choice.message.content.getOrElse(""))
      message =
        (
          if compatibility.preserveReasoningContent then
            baseMessage.copy(
              metadata = choice.message.reasoning_content.fold(baseMessage.metadata)(value =>
                baseMessage.metadata.updated("reasoning_content", value)
              )
            )
          else baseMessage
        )
      usage <- decoded.usage.fold[IO[AgentError, TokenUsage]](ZIO.succeed(TokenUsage()))(value =>
        validatedUsage(
          value.prompt_tokens,
          value.completion_tokens,
          value.prompt_tokens_details.flatMap(_.cached_tokens).getOrElse(0L),
          value.completion_tokens_details.flatMap(_.reasoning_tokens).getOrElse(0L),
          "response.usage"
        )
      )
    yield ChatResponse(message, finishReason(choice.finish_reason), usage, decoded.id)

  /** Provider usage 必须是非负数；负 token 会绕过预算门禁，因此视为无效响应而不是自动归零。 */
  private def validatedUsage(
      input: Long,
      output: Long,
      cachedInput: Long,
      reasoningOutput: Long,
      location: String
  ): IO[AgentError, TokenUsage] =
    if input >= 0L && output >= 0L && cachedInput >= 0L && reasoningOutput >= 0L &&
      cachedInput <= input && reasoningOutput <= output
    then ZIO.succeed(TokenUsage(input, output, cachedInput, reasoningOutput))
    else
      ZIO.fail(
        AgentError.InvalidModelResponse(
          s"$location token 用量无效: input=$input, output=$output, cached_input=$cachedInput, reasoning_output=$reasoningOutput"
        )
      )

  /** 编码单条消息，并按 Provider 能力映射 developer 角色和 reasoning 字段。 */
  private def encodeMessage(message: AgentMessage, compatibility: OpenAICompatibility): Json =
    val content = message.content.map {
      case ContentPart.Text(value)      => value
      case ContentPart.JsonValue(value) => value.toJson
      case ContentPart.ImageUrl(url, _) => s"[image: $url]"
    }.mkString
    val base = List(
      "role"    -> Json.Str(roleName(message.role, compatibility)),
      "content" -> Json.Str(content)
    )
    val toolCallId = message.toolCallId.map(value => "tool_call_id" -> Json.Str(value))
    val name       = message.name.map(value => "name" -> Json.Str(value))
    val calls      = Option.when(message.toolCalls.nonEmpty)(
      "tool_calls" -> arr(message.toolCalls.map { call =>
        obj(
          "id"       -> Json.Str(call.id),
          "type"     -> Json.Str("function"),
          "function" -> obj(
            "name"      -> Json.Str(call.name),
            "arguments" -> Json.Str(call.arguments.toJson)
          )
        )
      })
    )
    val reasoning = Option
      .when(
        compatibility.preserveReasoningContent && message.role == MessageRole.Assistant
      )(message.metadata.get("reasoning_content"))
      .flatten
      .map(value => "reasoning_content" -> Json.Str(value))
    obj(base ++ List(toolCallId, name, calls, reasoning).flatten*)

  /** 编码 function tool；不支持 strict 时明确省略对应字段。 */
  private def encodeTool(tool: ToolDefinition, compatibility: OpenAICompatibility): Json =
    val strict = Option.when(compatibility.strictToolSchemaMode == StrictToolSchemaMode.Include)(
      "strict" -> Json.Bool(tool.strict)
    )
    obj(
      "type"     -> Json.Str("function"),
      "function" -> obj(
        List(
          "name"        -> Json.Str(tool.name),
          "description" -> Json.Str(tool.description),
          "parameters"  -> tool.inputSchema
        ) ++ strict.toList*
      )
    )

  /** 编码自动、禁用、必需或指定名称的 tool choice。 */
  private def encodeToolChoice(choice: ToolChoice): Json = choice match
    case ToolChoice.Auto           => Json.Str("auto")
    case ToolChoice.None           => Json.Str("none")
    case ToolChoice.Required       => Json.Str("required")
    case ToolChoice.Specific(name) =>
      obj("type" -> Json.Str("function"), "function" -> obj("name" -> Json.Str(name)))

  private[openai] def finishReason(value: Option[String]): FinishReason = value match
    case Some("stop")           => FinishReason.Stop
    case Some("tool_calls")     => FinishReason.ToolCalls
    case Some("length")         => FinishReason.Length
    case Some("content_filter") => FinishReason.ContentFilter
    case Some(other)            => FinishReason.Other(other)
    case None                   => FinishReason.Other("unknown")

  /** 把内部 MessageRole 映射为兼容协议角色字符串。 */
  private def roleName(role: MessageRole, compatibility: OpenAICompatibility): String = role match
    case MessageRole.System    => "system"
    case MessageRole.Developer =>
      compatibility.developerRoleMode match
        case DeveloperRoleMode.Native      => "developer"
        case DeveloperRoleMode.MapToSystem => "system"
    case MessageRole.User      => "user"
    case MessageRole.Assistant => "assistant"
    case MessageRole.Tool      => "tool"

  /** 构造 JSON 对象的小型纯函数，避免编解码代码充斥集合转换。 */
  private def obj(fields: (String, Json)*): Json.Obj = Json.Obj(Chunk.fromIterable(fields))

  /** 构造 JSON 数组的小型纯函数。 */
  private def arr(values: Chunk[Json]): Json.Arr = Json.Arr(values)

/** OpenAI-compatible SSE 的增量状态机，独立于 HTTP，便于任意分块契约测试。 */
private[openai] object OpenAISse:
  /** 内部 EOF 标记，不是 Provider 协议内容；用于区分正常 [DONE] 与网络断流。 */
  private val EndOfStream = "\u0000zyblw-agent-sse-eof\u0000"
  final private case class FunctionDelta(name: Option[String], arguments: Option[String]) derives JsonDecoder
  final private case class ToolDelta(index: Int, id: Option[String], function: Option[FunctionDelta])
      derives JsonDecoder
  final private case class Delta(
      content: Option[String],
      reasoning_content: Option[String],
      tool_calls: Option[Chunk[ToolDelta]]
  ) derives JsonDecoder
  final private case class Choice(delta: Delta, finish_reason: Option[String]) derives JsonDecoder
  final private case class PromptTokensDetails(cached_tokens: Option[Long]) derives JsonDecoder
  final private case class CompletionTokensDetails(reasoning_tokens: Option[Long]) derives JsonDecoder
  final private case class Usage(
      prompt_tokens: Long,
      completion_tokens: Long,
      prompt_tokens_details: Option[PromptTokensDetails],
      completion_tokens_details: Option[CompletionTokensDetails]
  ) derives JsonDecoder
  final private case class ChunkDto(id: Option[String], choices: Chunk[Choice], usage: Option[Usage])
      derives JsonDecoder
  final private case class PartialTool(id: String, name: String, arguments: String)
  final private case class State(
      requestId: Option[String] = None,
      text: String = "",
      reasoning: String = "",
      tools: Map[Int, PartialTool] = Map.empty,
      usage: TokenUsage = TokenUsage(),
      finish: FinishReason = FinishReason.Other("streaming"),
      started: Boolean = false,
      completed: Boolean = false
  )

  /** 为请求加入 stream=true 与流式 usage 选项。 */
  def withStreaming(json: Json): Either[AgentError, Json] = json match
    case Json.Obj(fields) =>
      Right(
        Json.Obj(
          fields ++ Chunk(
            "stream"         -> Json.Bool(true),
            "stream_options" -> Json.Obj("include_usage" -> Json.Bool(true))
          )
        )
      )
    case _ => Left(AgentError.InvalidConfiguration("OpenAI 请求必须是 JSON object"))

  /** 从任意 Byte chunk 解析 SSE；UTF-8 解码器会缓存不完整的多字节字符。 */
  /** 把任意 HTTP 字节分块转换为 Provider-neutral ModelStreamEvent。 */
  def events(
      bytes: ZStream[Any, Throwable, Byte],
      compatibility: OpenAICompatibility
  ): ZStream[Any, AgentError, ModelStreamEvent] =
    val frames = bytes
      .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
      .concat(ZStream.succeed(""))
      .mapAccum(Chunk.empty[String]) { (dataLines, line) =>
        if line.isEmpty then (Chunk.empty, Option.when(dataLines.nonEmpty)(dataLines.mkString("\n")))
        else if line.startsWith("data:") then (dataLines :+ line.drop(5).stripLeading(), None)
        else (dataLines, None)
      }
      .collectSome
      .mapError(error => AgentError.InvalidModelResponse(s"SSE UTF-8 解码失败: ${error.getMessage}"))

    frames
      .concat(ZStream.succeed(EndOfStream))
      .mapAccumZIO(State())((state, frame) => decodeFrame(state, frame, compatibility))
      .mapConcatChunk(identity)

  /** 解码一条完整 SSE frame，并更新不可变累积状态。 */
  private def decodeFrame(
      state: State,
      frame: String,
      compatibility: OpenAICompatibility
  ): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    if frame == EndOfStream then
      if state.completed then ZIO.succeed(state -> Chunk.empty)
      else ZIO.fail(AgentError.InvalidModelResponse("Provider 流在 [DONE] 前中断"))
    else if frame == "[DONE]" then finish(state, compatibility)
    else
      ZIO
        .fromEither(providerError(frame).flatMap(_ => frame.fromJson[ChunkDto]))
        .mapError(details => AgentError.InvalidModelResponse(details))
        .flatMap { dto =>
          val startedEvents =
            if state.started then Chunk.empty else Chunk(ModelStreamEvent.ResponseStarted(dto.id))
          val withId = state.copy(requestId = dto.id.orElse(state.requestId), started = true)
          validateUsage(dto.usage).flatMap { validUsage =>
            val withUsage = validUsage.fold(withId)(usage => withId.copy(usage = usage))
            dto.choices.headOption match
              case None =>
                val usageEvents = validUsage
                  .fold(Chunk.empty[ModelStreamEvent])(usage => Chunk(ModelStreamEvent.UsageUpdated(usage)))
                ZIO.succeed(withUsage -> (startedEvents ++ usageEvents))
              case Some(choice) =>
                val textDelta      = choice.delta.content.getOrElse("")
                val reasoningDelta = choice.delta.reasoning_content.getOrElse("")
                val textEvents = Option.when(textDelta.nonEmpty)(ModelStreamEvent.TextDelta(textDelta)).toList
                val reasoningEvents =
                  Option.when(reasoningDelta.nonEmpty)(ModelStreamEvent.ReasoningDelta(reasoningDelta)).toList
                val (updatedTools, toolEvents) = choice.delta.tool_calls
                  .getOrElse(Chunk.empty)
                  .foldLeft(
                    withUsage.tools -> Chunk.empty[ModelStreamEvent]
                  ) { case ((tools, events), delta) =>
                    val previous = tools.get(delta.index)
                    val id       = delta.id.orElse(previous.map(_.id)).getOrElse(s"tool-${delta.index}")
                    val name     = delta.function.flatMap(_.name).orElse(previous.map(_.name)).getOrElse("")
                    val fragment = delta.function.flatMap(_.arguments).getOrElse("")
                    val next     = PartialTool(id, name, previous.fold("")(_.arguments) + fragment)
                    val started  = Option
                      .when(previous.isEmpty && name.nonEmpty)(ModelStreamEvent.ToolCallStarted(id, name))
                      .toList
                    val changed = Option
                      .when(fragment.nonEmpty)(
                        ModelStreamEvent.ToolCallDelta(id, Option.when(name.nonEmpty)(name), fragment)
                      )
                      .toList
                    (tools.updated(delta.index, next), events ++ Chunk.fromIterable(started ++ changed))
                  }
                val next = withUsage.copy(
                  text = withUsage.text + textDelta,
                  reasoning = withUsage.reasoning + reasoningDelta,
                  tools = updatedTools,
                  finish =
                    choice.finish_reason.fold(withUsage.finish)(value => OpenAIWire.finishReason(Some(value)))
                )
                ZIO.succeed(
                  next -> (startedEvents ++ Chunk.fromIterable(textEvents ++ reasoningEvents) ++ toolEvents)
                )
          }
        }

  /** 校验 SSE usage，防止负值污染累计预算；None 表示 Provider 尚未发送 usage。 */
  private def validateUsage(usage: Option[Usage]): IO[AgentError, Option[TokenUsage]] = usage match
    case None => ZIO.none
    case Some(value)
        if value.prompt_tokens >= 0L && value.completion_tokens >= 0L &&
          value.prompt_tokens_details
            .flatMap(_.cached_tokens)
            .forall(token => token >= 0L && token <= value.prompt_tokens) &&
          value.completion_tokens_details
            .flatMap(_.reasoning_tokens)
            .forall(token => token >= 0L && token <= value.completion_tokens) =>
      ZIO.some(
        TokenUsage(
          value.prompt_tokens,
          value.completion_tokens,
          value.prompt_tokens_details.flatMap(_.cached_tokens).getOrElse(0L),
          value.completion_tokens_details.flatMap(_.reasoning_tokens).getOrElse(0L)
        )
      )
    case Some(value) =>
      ZIO.fail(
        AgentError.InvalidModelResponse(
          s"stream.usage token 用量无效: input=${value.prompt_tokens}, output=${value.completion_tokens}"
        )
      )

  /** 在 DONE 或网络结束时校验完整性并生成唯一 Completed。 */
  private def finish(
      state: State,
      compatibility: OpenAICompatibility
  ): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    if state.completed then ZIO.succeed(state -> Chunk.empty)
    else if state.text.isEmpty && state.tools.isEmpty then ZIO.fail(AgentError.InvalidModelResponse("模型流为空"))
    else
      ZIO
        .foreach(Chunk.fromIterable(state.tools.toList.sortBy(_._1).map(_._2))) { tool =>
          ZIO
            .fromEither(tool.arguments.fromJson[Json])
            .mapError(details => AgentError.InvalidModelResponse(s"流式工具参数不是合法 JSON: $details"))
            .map(arguments => ToolCall(tool.id, tool.name, arguments))
        }
        .map { calls =>
          val base =
            if calls.nonEmpty then AgentMessage.assistantToolCalls(calls, state.text)
            else AgentMessage.assistant(state.text)
          val message =
            if compatibility.preserveReasoningContent && state.reasoning.nonEmpty then
              base.copy(metadata = base.metadata.updated("reasoning_content", state.reasoning))
            else base
          val response       = ChatResponse(message, state.finish, state.usage, state.requestId)
          val completedTools = calls.map(ModelStreamEvent.ToolCallCompleted(_))
          state.copy(completed = true) -> (completedTools :+ ModelStreamEvent.Completed(response))
        }

  /** 检查成功 HTTP 流中是否嵌入 Provider error 对象。 */
  private def providerError(frame: String): Either[String, Unit] =
    frame.fromJson[Json] match
      case Right(Json.Obj(fields)) =>
        fields.collectFirst { case ("error", value) => value } match
          case Some(value) => Left(s"Provider SSE error: ${value.toJson.take(1000)}")
          case None        => Right(())
      case Right(_)      => Right(())
      case Left(details) => Left(details)
