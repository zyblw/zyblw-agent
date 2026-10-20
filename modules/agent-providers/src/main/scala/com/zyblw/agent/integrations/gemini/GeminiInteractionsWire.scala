package com.zyblw.agent.integrations.gemini

import com.zyblw.agent.artifacts.ProviderImagePolicy
import com.zyblw.agent.core.*
import com.zyblw.agent.integrations.ProviderBilling
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
    ProviderImagePolicy.requireBound(request).flatMap { _ =>
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
      else if request.settings.reasoningEffort.exists(
          !GeminiInteractionsDescriptor.value.capabilities.reasoningEfforts.contains(_)
        )
      then
        Left(
          AgentError.UnsupportedModelCapability(
            "gemini",
            "reasoning effort",
            s"请求档位=${request.settings.reasoningEffort.get}"
          )
        )
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
    yield ChatResponse(
      message,
      finish,
      ProviderBilling.reported(usage, json),
      stringField(json, "id"),
      usageReported = field(json, "usage").exists(u =>
        longField(u, "total_input_tokens").nonEmpty && longField(u, "total_output_tokens").nonEmpty
      )
    )

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
      case ContentPart.ImageArtifact(_, _, _) =>
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
      request.settings.reasoningEffort.map(value => "thinking_level" -> Json.Str(value.toString.toLowerCase)),
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

  /** Interactions reports visible output and thoughts separately; core output includes both exactly once. */
  private[gemini] def decodeUsage(value: Option[Json], location: String): IO[AgentError, TokenUsage] =
    value match
      case None       => ZIO.succeed(TokenUsage())
      case Some(json) =>
        def count(name: String, alias: Option[String] = None): Either[AgentError, Long] =
          val supplied = (field(json, name).toList ++ alias.toList.flatMap(field(json, _)))
          val parsed   = supplied.map {
            case Json.Num(n)
                if n.signum >= 0 && n.scale <= 0 && n.compareTo(
                  java.math.BigDecimal.valueOf(Long.MaxValue)
                ) <= 0 =>
              Some(n.longValue)
            case Json.Num(n)
                if n.signum >= 0 && n.stripTrailingZeros.scale <= 0 && n.compareTo(
                  java.math.BigDecimal.valueOf(Long.MaxValue)
                ) <= 0 =>
              Some(n.longValue)
            case _ => None
          }
          Either.cond(
            parsed.forall(_.nonEmpty) && parsed.flatten.distinct.size <= 1,
            parsed.flatten.headOption.getOrElse(0L),
            AgentError.InvalidModelResponse(s"$location.$name 无效或别名冲突")
          )
        def modalities(name: String): Either[AgentError, Option[Map[String, Long]]] =
          field(json, name) match
            case None                  => Right(None)
            case Some(Json.Arr(items)) =>
              val decoded = items.toList.map { item =>
                for
                  modality <- stringField(item, "modality")
                  tokens   <- field(item, "tokens").collect {
                    case Json.Num(n)
                        if n.signum >= 0 && n.stripTrailingZeros.scale <= 0 &&
                          n.compareTo(java.math.BigDecimal.valueOf(Long.MaxValue)) <= 0 =>
                      n.longValue
                  }
                yield modality.toLowerCase -> tokens
              }
              Either.cond(
                decoded.forall(_.nonEmpty) && decoded.flatten.map(_._1).distinct.size == items.size,
                Some(decoded.flatten.toMap),
                AgentError.InvalidModelResponse(s"$location.$name 无效")
              )
            case _ => Left(AgentError.InvalidModelResponse(s"$location.$name 无效"))
        val decoded = for
          input            <- count("total_input_tokens", Some("prompt_tokens"))
          visible          <- count("total_output_tokens", Some("completion_tokens"))
          thoughts         <- count("total_thought_tokens")
          cached           <- count("total_cached_tokens")
          tools            <- count("total_tool_use_tokens")
          total            <- count("total_tokens")
          inputModalities  <- modalities("input_tokens_by_modality")
          outputModalities <- modalities("output_tokens_by_modality")
          cachedModalities <- modalities("cached_tokens_by_modality")
          _                <- Either.cond(
            List(inputModalities -> input, outputModalities -> visible, cachedModalities -> cached)
              .forall((parts, total) => parts.forall(_.values.map(BigInt(_)).sum <= BigInt(total))),
            (),
            AgentError.InvalidModelResponse(s"$location 模态子集超过总量")
          )
          _ <- Either.cond(
            cached <= input && thoughts <= Long.MaxValue - visible &&
              input <= Long.MaxValue - visible - thoughts,
            (),
            AgentError.InvalidModelResponse(s"$location token 子集无效或总量溢出")
          )
        yield
          val inputAudio = inputModalities.flatMap { parts =>
            val audio = parts.getOrElse("audio", 0L)
            if audio == 0 || cached == 0 then Some(audio)
            else
              cachedModalities.flatMap(c =>
                Option.when(c.getOrElse("audio", 0L) <= audio)(audio - c.getOrElse("audio", 0L))
              )
          }
          val meters = Map("reasoning_tokens" -> thoughts.toString, "tool_use_tokens" -> tools.toString) ++
            inputAudio.map(n => "input_audio_tokens" -> n.toString).toMap ++
            outputModalities.map(m => "output_audio_tokens" -> m.getOrElse("audio", 0L).toString).toMap
          val issues = List(
            Option.when(field(json, "total_tokens").nonEmpty && total != input + visible + thoughts)(
              "供应商总 token 与输入、可见输出及推理分类不一致"
            ),
            Option.when(inputModalities.exists(_.getOrElse("audio", 0L) > 0) && inputAudio.isEmpty)(
              "供应商未报告音频缓存分摊，不能确定普通输入与音频用量"
            ),
            Option.when(
              (inputModalities.toList ++ outputModalities.toList).exists(_.getOrElse("image", 0L) > 0)
            )("供应商报告图片 token；当前适配器无法将其转换为图片张数")
          ).flatten
          TokenUsage(
            input,
            visible + thoughts,
            cachedInputTokens = cached,
            reasoningOutputTokens = thoughts,
            billing = Some(BillingUsage(meters = meters, issues = issues))
          )
        ZIO.fromEither(decoded)

  /** 工具结果优先保持单个 JSON 值；多内容块退化为按顺序排列的 JSON 数组。 */
  private def toolResultValue(message: AgentMessage): Json =
    val values = message.content.map {
      case ContentPart.Text(value)                  => Json.Str(value)
      case ContentPart.JsonValue(value)             => value
      case ContentPart.ImageUrl(url, _)             => Json.Str(url)
      case ContentPart.ImageArtifact(sha, media, _) => Json.Str(s"artifact:$media:$sha")
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
