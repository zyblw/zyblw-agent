package com.zyblw.agent.integrations.gemini

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Gemini Interactions API 的纯 JSON 协议边界。
  *
  * 这个对象不依赖 HTTP，因此请求编码、响应解码、签名步骤重放和错误 envelope 都可以用 确定性单元测试覆盖。Provider 的网络生命周期只负责传输，不应该再复制一遍协议规则。
  */
private[gemini] object GeminiInteractionsWire:
  /** 保存上轮完整 Gemini steps 的消息元数据键。
    *
    * 无状态调用在工具结果回填时必须带回模型生成的原始 function-call/thought signatures；重新 根据框架 ToolCall 拼装会丢失签名。该字段属于 Provider
    * 私有持久化数据，遥测层不得展开。
    */
  val RawStepsMetadata = "gemini.interactions.steps"

  private val reservedOptions = Set(
    "model",
    "input",
    "system_instruction",
    "tools",
    "generation_config",
    "stream",
    "store",
    "background",
    "previous_interaction_id",
    "agent"
  )

  /** 只有已经完成框架安全评审的顶层选项允许透传，未知字段按 fail-closed 处理。 */
  private val allowedOptions = Set("response_format", "safety_settings", "service_tier", "labels")

  /** 把厂商无关的 ChatRequest 编码为 Gemini Interactions 请求。
    *
    * @param request
    *   Runtime 组装的消息、工具定义和模型参数
    * @param config
    *   部署级默认模型与安全扩展配置
    * @param streaming
    *   是否启用 typed SSE；该字段由调用路径决定，业务不能自行覆盖
    * @return
    *   可直接作为 HTTP JSON body 的对象，或一个可定位的配置错误
    */
  def encodeRequest(
      request: ChatRequest,
      config: GeminiInteractionsConfig,
      streaming: Boolean
  ): Either[AgentError, Json.Obj] =
    val options  = config.defaultOptions ++ request.settings.providerOptions
    val reserved = options.keySet.intersect(reservedOptions)
    val unknown  = options.keySet.diff(allowedOptions).diff(reservedOptions)
    if reserved.nonEmpty then
      Left(
        AgentError.InvalidConfiguration(
          s"providerOptions cannot override reserved Gemini fields: ${reserved.toList.sorted.mkString(", ")}"
        )
      )
    else if unknown.nonEmpty then
      Left(
        AgentError.InvalidConfiguration(
          s"Gemini providerOptions are not allow-listed: ${unknown.toList.sorted.mkString(", ")}"
        )
      )
    else if request.settings.toolChoice.isInstanceOf[ToolChoice.Specific] then
      Left(AgentError.UnsupportedModelCapability("gemini", "specific tool choice", "当前原生适配器尚未承诺该契约"))
    else
      sequence(request.messages.filterNot(isInstruction).map(encodeMessage)).map { encoded =>
        val steps    = encoded.flatten
        val required = List(
          "model"  -> Json.Str(request.settings.model.getOrElse(config.defaultModel)),
          "input"  -> Json.Arr(steps),
          "stream" -> Json.Bool(streaming),
          "store"  -> Json.Bool(false)
        )
        val system = instructionText(request.messages).map(value => "system_instruction" -> Json.Str(value))
        val tools  = Option.when(request.tools.nonEmpty)("tools" -> Json.Arr(request.tools.map(encodeTool)))
        val generation = generationConfig(request).map(value => "generation_config" -> value)
        obj(required ++ List(system, tools, generation).flatten ++ options.toList.sortBy(_._1)*)
      }

  /** 解码一个完整 interaction 响应。
    *
    * `requires_action` 被归一化为 ToolCalls；负 usage、缺失工具 ID/名称和非法 arguments 会以 `InvalidModelResponse` 失败，不能污染预算或让
    * Runtime 执行一个模糊动作。
    */
  def decodeResponse(body: String): IO[AgentError, ChatResponse] =
    ZIO
      .fromEither(body.fromJson[Json])
      .mapError(AgentError.InvalidModelResponse(_))
      .flatMap(decodeResponseJson)

  /** 从已经解析的 JSON 组装统一响应，供非流式和 SSE 终止状态共同复用。 */
  def decodeResponseJson(json: Json): IO[AgentError, ChatResponse] =
    for
      status <- ZIO.succeed(stringField(json, "status").getOrElse("unknown"))
      _      <- ZIO
        .fail(AgentError.InvalidModelResponse(s"Gemini interaction ended with status=$status"))
        .when(Set("failed", "cancelled").contains(status))
      steps = arrayField(json, "steps").getOrElse(Chunk.empty)
      calls <- ZIO.foreach(steps.filter(step => stringField(step, "type").contains("function_call")))(
        decodeFunctionCall
      )
      _ <- ZIO
        .fail(
          AgentError.InvalidModelResponse("Gemini requires_action response does not contain function_call")
        )
        .when(status == "requires_action" && calls.isEmpty)
      usage <- decodeUsage(field(json, "usage"), "response.usage")
      text = steps.flatMap(modelOutputText).mkString
      base =
        if calls.nonEmpty then AgentMessage.assistantToolCalls(calls, text) else AgentMessage.assistant(text)
      // 只有后续必须回填的工具回合才保存原始 steps；最终文本无需长期保存 thought signatures。
      message =
        if calls.nonEmpty then
          base.copy(metadata = base.metadata.updated(RawStepsMetadata, Json.Arr(steps).toJson))
        else base
      finish =
        if calls.nonEmpty || status == "requires_action" then FinishReason.ToolCalls
        else if status == "completed" then FinishReason.Stop
        else if status == "incomplete" || status == "budget_exceeded" then FinishReason.Length
        else FinishReason.Other(status)
    yield ChatResponse(message, finish, usage, stringField(json, "id"))

  /** 从 Google 标准错误 envelope 中只提取稳定 status，禁止把可能含敏感数据的 body 写入日志。 */
  def errorStatus(body: String): Option[String] =
    body
      .fromJson[Json]
      .toOption
      .flatMap(json => field(json, "error"))
      .flatMap(error => stringField(error, "status").orElse(stringField(error, "message").map(_ => "error")))

  /** 把一条框架消息转换成一个或多个 Interactions steps。 Assistant 优先原样重放 Provider steps；Tool 结果使用相同 call_id 完成关联。
    */
  private def encodeMessage(message: AgentMessage): Either[AgentError, Chunk[Json]] = message.role match
    case MessageRole.User =>
      encodeTextContent(message).map(content =>
        Chunk(obj("type" -> Json.Str("user_input"), "content" -> Json.Arr(content)))
      )
    case MessageRole.Tool =>
      message.toolCallId
        .toRight(AgentError.InvalidConfiguration("Gemini Tool message is missing toolCallId"))
        .map { callId =>
          val fields = List(
            "type"     -> Json.Str("function_result"),
            "call_id"  -> Json.Str(callId),
            "result"   -> toolResultValue(message),
            "is_error" -> Json.Bool(message.metadata.get("isError").contains("true"))
          ) ++ message.name.map(value => "name" -> Json.Str(value))
          Chunk(obj(fields*))
        }
    case MessageRole.Assistant =>
      rawSteps(message).flatMap {
        case Some(steps) => Right(steps)
        case None        =>
          encodeTextContent(message).map { content =>
            val output = Option.when(content.nonEmpty)(
              obj("type" -> Json.Str("model_output"), "content" -> Json.Arr(content))
            )
            val calls = message.toolCalls.map(call =>
              obj(
                "type"      -> Json.Str("function_call"),
                "id"        -> Json.Str(call.id),
                "name"      -> Json.Str(call.name),
                "arguments" -> call.arguments
              )
            )
            Chunk.fromIterable(output) ++ calls
          }
      }
    case MessageRole.System | MessageRole.Developer =>
      Left(AgentError.InvalidConfiguration("System/Developer messages must be encoded in system_instruction"))

  /** 将文本和 JSON 内容转换为 Interactions text content；尚未实现的图片输入显式拒绝。 */
  private def encodeTextContent(message: AgentMessage): Either[AgentError, Chunk[Json]] =
    sequence(message.content.map {
      case ContentPart.Text(value)      => Right(obj("type" -> Json.Str("text"), "text" -> Json.Str(value)))
      case ContentPart.JsonValue(value) =>
        Right(obj("type" -> Json.Str("text"), "text" -> Json.Str(value.toJson)))
      case ContentPart.ImageUrl(_, _) =>
        Left(AgentError.UnsupportedModelCapability("gemini", "vision", "Interactions 图片输入尚未实现统一契约"))
    })

  /** 合并 System 和 Developer 指令；标签让不具备原生 Developer role 的降级可审计。 */
  private def instructionText(messages: Chunk[AgentMessage]): Option[String] =
    val values = messages
      .collect {
        case message if message.role == MessageRole.System    => message.text
        case message if message.role == MessageRole.Developer => s"[developer]\n${message.text}"
      }
      .filter(_.nonEmpty)
    Option.when(values.nonEmpty)(values.mkString("\n\n"))

  /** 判断指令角色是否应从 input steps 提升到顶层字段。 */
  private def isInstruction(message: AgentMessage): Boolean =
    message.role == MessageRole.System || message.role == MessageRole.Developer

  /** Interactions 工具定义是扁平 function declaration，不使用 OpenAI 的 function 包装层。 */
  private def encodeTool(tool: ToolDefinition): Json =
    obj(
      "type"        -> Json.Str("function"),
      "name"        -> Json.Str(tool.name),
      "description" -> Json.Str(tool.description),
      "parameters"  -> tool.inputSchema
    )

  /** 生成参数只在至少存在一项时发出，避免无意义的空对象影响缓存键。 */
  private def generationConfig(request: ChatRequest): Option[Json.Obj] =
    val fields = List(
      request.settings.temperature.map(value => "temperature" -> Json.Num(value)),
      request.settings.maxOutputTokens.map(value => "max_output_tokens" -> Json.Num(value)),
      Option.when(request.tools.nonEmpty)("tool_choice" -> Json.Str(request.settings.toolChoice match
        case ToolChoice.Auto        => "auto"
        case ToolChoice.None        => "none"
        case ToolChoice.Required    => "any"
        case ToolChoice.Specific(_) => "specific"))
    ).flatten
    Option.when(fields.nonEmpty)(obj(fields*))

  /** 从消息元数据读取需要逐字回填的原始 steps；损坏数据不能悄悄降级为重建。 */
  private def rawSteps(message: AgentMessage): Either[AgentError, Option[Chunk[Json]]] =
    message.metadata.get(RawStepsMetadata) match
      case None        => Right(None)
      case Some(value) =>
        value
          .fromJson[Json]
          .left
          .map(details => AgentError.InvalidConfiguration(s"Invalid saved Gemini steps: $details"))
          .flatMap {
            case Json.Arr(values) => Right(Some(values))
            case _ => Left(AgentError.InvalidConfiguration("Saved Gemini steps must be an array"))
          }

  /** 解码 function_call，arguments 可为原生对象，也兼容服务端返回的 JSON 字符串。 */
  private def decodeFunctionCall(step: Json): IO[AgentError, ToolCall] =
    for
      id   <- requiredString(step, "id", "function_call")
      name <- requiredString(step, "name", "function_call")
      raw  <- ZIO
        .fromOption(field(step, "arguments"))
        .orElseFail(
          AgentError.InvalidModelResponse("function_call is missing arguments")
        )
      arguments <- raw match
        case Json.Str(value) =>
          ZIO
            .fromEither(value.fromJson[Json])
            .mapError(details =>
              AgentError.InvalidModelResponse(s"function_call arguments is not valid JSON: $details")
            )
        case value => ZIO.succeed(value)
    yield ToolCall(id, name, arguments)

  /** 从 model_output step 提取所有文本 content，其他模态不会被错误字符串化。 */
  private def modelOutputText(step: Json): Chunk[String] =
    if stringField(step, "type").contains("model_output") then
      arrayField(step, "content")
        .getOrElse(Chunk.empty)
        .flatMap(part =>
          Option.when(stringField(part, "type").contains("text"))(stringField(part, "text")).flatten
        )
    else Chunk.empty

  /** 校验 usage。总 thought/tool token 保留在厂商侧，框架硬预算使用输入和输出主计数。 */
  private def decodeUsage(value: Option[Json], location: String): IO[AgentError, TokenUsage] =
    value match
      case None       => ZIO.succeed(TokenUsage())
      case Some(json) =>
        val input  = longField(json, "total_input_tokens").getOrElse(0L)
        val output = longField(json, "total_output_tokens").getOrElse(0L)
        if input >= 0L && output >= 0L then ZIO.succeed(TokenUsage(input, output))
        else ZIO.fail(AgentError.InvalidModelResponse(s"$location 包含负 token: input=$input, output=$output"))

  /** 工具结果优先保持单个 JSON 值；多内容块退化为按顺序排列的 JSON 数组。 */
  private def toolResultValue(message: AgentMessage): Json =
    val values = message.content.map {
      case ContentPart.Text(value)      => Json.Str(value)
      case ContentPart.JsonValue(value) => value
      case ContentPart.ImageUrl(url, _) => Json.Str(url)
    }
    values.headOption.filter(_ => values.size == 1).getOrElse(Json.Arr(values))

  /** 读取必需字符串并把字段位置写入 typed protocol error。 */
  private def requiredString(json: Json, name: String, location: String): IO[AgentError, String] =
    ZIO
      .fromOption(stringField(json, name).filter(_.nonEmpty))
      .orElseFail(
        AgentError.InvalidModelResponse(s"$location is missing string field '$name'")
      )

  /** 安全读取 JSON object 字段。 */
  private[gemini] def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.find(_._1 == name).map(_._2)
    case _                => None

  /** 安全读取字符串字段。 */
  private[gemini] def stringField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(value) => value }

  /** 安全读取数组字段。 */
  private[gemini] def arrayField(json: Json, name: String): Option[Chunk[Json]] =
    field(json, name).collect { case Json.Arr(values) => values }

  /** 安全读取 Long 计数字段。 */
  private[gemini] def longField(json: Json, name: String): Option[Long] =
    field(json, name).collect { case Json.Num(value) => value.longValue }

  /** 安全读取 Int 索引字段。 */
  private[gemini] def intField(json: Json, name: String): Option[Int] =
    field(json, name).collect { case Json.Num(value) => value.intValue }

  /** 统一构造 JSON 对象，避免协议实现散落低层 AST 样板。 */
  private[gemini] def obj(fields: (String, Json)*): Json.Obj = Json.Obj(Chunk.fromIterable(fields))

  /** 翻转 Chunk[Either] 并保留第一项具体错误。 */
  private def sequence[A](values: Chunk[Either[AgentError, A]]): Either[AgentError, Chunk[A]] =
    values.foldLeft[Either[AgentError, Chunk[A]]](Right(Chunk.empty)) { (acc, value) =>
      for
        collected <- acc
        current   <- value
      yield collected :+ current
    }
