package com.zyblw.agent.integrations.gemini

import com.zyblw.agent.core.*
import com.zyblw.agent.model.ModelStreamEvent
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** Gemini Interactions typed SSE 的不可变状态机。
  *
  * 网络 byte chunk、UTF-8 字符、SSE frame、Provider step 和框架语义事件是五个不同层次。本实现 逐层转换，并在终止事件之前断流时失败，防止半截答案被上层误当成一次成功运行。
  */
private[gemini] object GeminiInteractionsSse:
  private val EndOfStream = "\u0000zyblw-agent-gemini-sse-eof\u0000"

  /** 一条尚未完成的 step；只记录框架需要校验、流式输出或无状态回填的字段。 */
  final private case class PartialStep(
      kind: String,
      id: String = "",
      name: String = "",
      text: String = "",
      arguments: String = "",
      signature: Option[String] = None,
      original: Json = Json.Obj()
  )

  /** 一条流的纯状态。
    *
    * Map 使用 Provider index 作为键，最终按 index 排序，从而在并发 function-call 流下仍产生 确定性工具结果顺序；`terminal` 保证恰好一次 Completed。
    */
  final private case class State(
      responseId: Option[String] = None,
      steps: Map[Int, PartialStep] = Map.empty,
      completedCalls: Map[Int, ToolCall] = Map.empty,
      usage: TokenUsage = TokenUsage(),
      status: String = "in_progress",
      started: Boolean = false,
      terminal: Boolean = false
  )

  /** 将任意分块的 HTTP body 转换为带背压的模型事件流。
    *
    * @param bytes
    *   ZIO HTTP response body；下游取消拉取时 Scope 会关闭底层连接
    * @return
    *   文本、工具调用、usage 和唯一 Completed，或 typed protocol error
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
      .mapError(error => AgentError.InvalidModelResponse(s"Gemini SSE UTF-8 解码失败: ${error.getMessage}"))

    frames
      .concat(ZStream.succeed(EndOfStream))
      .mapAccumZIO(State())(decodeFrame)
      .mapConcatChunk(identity)

  /** 解析一个 data frame；终止事件前 EOF 是可恢复的 Provider 失败，而不是正常结束。 */
  private def decodeFrame(state: State, frame: String): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    if frame == EndOfStream then
      if state.terminal then ZIO.succeed(state -> Chunk.empty)
      else ZIO.fail(AgentError.InvalidModelResponse("Gemini Provider 流在 interaction 终止事件前中断"))
    else if frame == "[DONE]" then
      if state.terminal then ZIO.succeed(state -> Chunk.empty)
      else
        ZIO.fail(AgentError.InvalidModelResponse("Gemini 在 interaction.completed/requires_action 前发送 [DONE]"))
    else
      ZIO
        .fromEither(frame.fromJson[Json])
        .mapError(AgentError.InvalidModelResponse(_))
        .flatMap(event => decodeEvent(state, event))

  /** 使用 2026 steps schema 的 `event_type` 分派；未知事件为向前兼容而忽略。 */
  private def decodeEvent(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    GeminiInteractionsWire
      .stringField(event, "event_type")
      .orElse(GeminiInteractionsWire.stringField(event, "type")) match
      case Some("interaction.created")         => interactionCreated(state, event)
      case Some("interaction.in_progress")     => ZIO.succeed(ensureStarted(state) -> startPrefix(state))
      case Some("step.start")                  => stepStart(state, event)
      case Some("step.delta")                  => stepDelta(state, event)
      case Some("step.stop")                   => stepStop(state, event)
      case Some("interaction.completed")       => terminal(state, event, "completed")
      case Some("interaction.requires_action") => terminal(state, event, "requires_action")
      case Some("interaction.failed")          => streamFailure(event, retryable = false)
      case Some("interaction.cancelled")       => streamFailure(event, retryable = false)
      case Some("error")                       => streamFailure(event, retryable = true)
      case _                                   => ZIO.succeed(state -> Chunk.empty)

  /** 读取 interaction ID，并且只在整条流中发送一次 ResponseStarted。 */
  private def interactionCreated(
      state: State,
      event: Json
  ): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val interaction = GeminiInteractionsWire.field(event, "interaction").getOrElse(Json.Obj())
    val id          = GeminiInteractionsWire
      .stringField(interaction, "id")
      .orElse(GeminiInteractionsWire.stringField(event, "interaction_id"))
      .orElse(state.responseId)
    val next    = state.copy(responseId = id, started = true)
    val emitted = if state.started then Chunk.empty else Chunk(ModelStreamEvent.ResponseStarted(id))
    ZIO.succeed(next -> emitted)

  /** 建立 step，并立即暴露稳定工具 ID/名称以及 model_output 中已有的首段文本。 */
  private def stepStart(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val index       = GeminiInteractionsWire.intField(event, "index").getOrElse(state.steps.size)
    val step        = GeminiInteractionsWire.field(event, "step").getOrElse(Json.Obj())
    val kind        = GeminiInteractionsWire.stringField(step, "type").getOrElse("unknown")
    val initialText = if kind == "model_output" then modelOutputText(step) else ""
    val partial     = PartialStep(
      kind = kind,
      id = GeminiInteractionsWire.stringField(step, "id").getOrElse(""),
      name = GeminiInteractionsWire.stringField(step, "name").getOrElse(""),
      text = initialText,
      arguments = argumentsText(GeminiInteractionsWire.field(step, "arguments")),
      signature = GeminiInteractionsWire.stringField(step, "signature"),
      original = step
    )
    val base      = ensureStarted(state).copy(steps = ensureStarted(state).steps.updated(index, partial))
    val toolEvent = Option.when(kind == "function_call" && partial.id.nonEmpty && partial.name.nonEmpty)(
      ModelStreamEvent.ToolCallStarted(partial.id, partial.name)
    )
    val textEvent = Option.when(initialText.nonEmpty)(ModelStreamEvent.TextDelta(initialText))
    ZIO.succeed(base -> (startPrefix(state) ++ Chunk.fromIterable(List(toolEvent, textEvent).flatten)))

  /** 追加 text 或 arguments_delta；工具参数只增量传递，直到 step.stop 才解析执行。 */
  private def stepDelta(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val index    = GeminiInteractionsWire.intField(event, "index").getOrElse(0)
    val delta    = GeminiInteractionsWire.field(event, "delta").getOrElse(Json.Obj())
    val kind     = GeminiInteractionsWire.stringField(delta, "type").getOrElse("")
    val previous = state.steps.getOrElse(index, PartialStep("unknown"))
    kind match
      case "text" =>
        val value = GeminiInteractionsWire.stringField(delta, "text").getOrElse("")
        val next  = previous.copy(kind = "model_output", text = previous.text + value)
        ZIO.succeed(
          state.copy(steps = state.steps.updated(index, next)) -> Chunk(ModelStreamEvent.TextDelta(value))
        )
      case "arguments_delta" =>
        val value = GeminiInteractionsWire.stringField(delta, "arguments").getOrElse("")
        val next  = previous.copy(kind = "function_call", arguments = previous.arguments + value)
        ZIO.succeed(
          state.copy(steps = state.steps.updated(index, next)) ->
            Chunk(ModelStreamEvent.ToolCallDelta(next.id, Option.when(next.name.nonEmpty)(next.name), value))
        )
      case _ => ZIO.succeed(state -> Chunk.empty)

  /** function_call step 结束时校验累积 JSON，并且每个 index 只发送一次 ToolCallCompleted。 */
  private def stepStop(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val index = GeminiInteractionsWire.intField(event, "index").getOrElse(0)
    state.steps.get(index) match
      case Some(step) if step.kind == "function_call" && !state.completedCalls.contains(index) =>
        for
          _ <- ZIO
            .fail(AgentError.InvalidModelResponse("Gemini function_call 缺少 id 或 name"))
            .when(step.id.isEmpty || step.name.isEmpty)
          encoded = Option.when(step.arguments.nonEmpty)(step.arguments).getOrElse("{}")
          arguments <- ZIO
            .fromEither(encoded.fromJson[Json])
            .mapError(details => AgentError.InvalidModelResponse(s"Gemini 流式工具参数不是合法 JSON: $details"))
          call = ToolCall(step.id, step.name, arguments)
        yield state.copy(completedCalls = state.completedCalls.updated(index, call)) ->
          Chunk(ModelStreamEvent.ToolCallCompleted(call))
      case _ => ZIO.succeed(state -> Chunk.empty)

  /** 处理 completed/requires_action 终止事件。
    *
    * 终止 payload 可能只含 ID/status/usage，所以最终消息从累积 steps 构造；usage 同时兼容稳定版 `total_*` 字段与迁移期
    * `prompt_tokens/completion_tokens` 字段。
    */
  private def terminal(
      state: State,
      event: Json,
      status: String
  ): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    if state.terminal then ZIO.fail(AgentError.InvalidModelResponse("Gemini 流重复发送 interaction 终止事件"))
    else
      val interaction = GeminiInteractionsWire.field(event, "interaction").getOrElse(Json.Obj())
      val usageJson   = GeminiInteractionsWire
        .field(interaction, "usage")
        .orElse(GeminiInteractionsWire.field(event, "usage"))
      for
        usage <- decodeUsage(usageJson, state.usage)
        calls <- completeUnstoppedCalls(state)
        text = state.steps.toList
          .sortBy(_._1)
          .collect { case (_, step) if step.kind == "model_output" => step.text }
          .mkString
        rawSteps = state.steps.toList.sortBy(_._1).flatMap { case (index, step) =>
          rawStep(index, step, calls)
        }
        orderedCalls = Chunk.fromIterable(calls.toList.sortBy(_._1).map(_._2))
        base         =
          if orderedCalls.nonEmpty then AgentMessage.assistantToolCalls(orderedCalls, text)
          else AgentMessage.assistant(text)
        message =
          if orderedCalls.nonEmpty then
            base.copy(metadata =
              base.metadata.updated(
                GeminiInteractionsWire.RawStepsMetadata,
                Json.Arr(Chunk.fromIterable(rawSteps)).toJson
              )
            )
          else base
        responseId = GeminiInteractionsWire.stringField(interaction, "id").orElse(state.responseId)
        response   = ChatResponse(
          message,
          if orderedCalls.nonEmpty || status == "requires_action" then FinishReason.ToolCalls
          else FinishReason.Stop,
          usage,
          responseId
        )
        usageEvent = Option.when(usage.totalTokens > 0L)(ModelStreamEvent.UsageUpdated(usage))
      yield state.copy(
        responseId = responseId,
        completedCalls = calls,
        usage = usage,
        status = status,
        terminal = true
      ) ->
        (Chunk.fromIterable(usageEvent) :+ ModelStreamEvent.Completed(response))

  /** 终止事件早于 step.stop 时仍校验所有完整 function call，覆盖真实网络边界竞态。 */
  private def completeUnstoppedCalls(state: State): IO[AgentError, Map[Int, ToolCall]] =
    ZIO.foldLeft(state.steps.toList.sortBy(_._1))(state.completedCalls) { case (calls, (index, step)) =>
      if step.kind != "function_call" || calls.contains(index) then ZIO.succeed(calls)
      else
        val encoded = Option.when(step.arguments.nonEmpty)(step.arguments).getOrElse("{}")
        for
          _ <- ZIO
            .fail(AgentError.InvalidModelResponse("Gemini function_call 缺少 id 或 name"))
            .when(step.id.isEmpty || step.name.isEmpty)
          arguments <- ZIO
            .fromEither(encoded.fromJson[Json])
            .mapError(details => AgentError.InvalidModelResponse(s"Gemini 流式工具参数不是合法 JSON: $details"))
        yield calls.updated(index, ToolCall(step.id, step.name, arguments))
    }

  /** 将流式累积状态重建为下一轮可以原样回填的 Interactions step。 */
  private def rawStep(index: Int, step: PartialStep, calls: Map[Int, ToolCall]): Option[Json] =
    step.kind match
      case "model_output" =>
        Some(
          GeminiInteractionsWire.obj(
            "type"    -> Json.Str("model_output"),
            "content" -> Json.Arr(
              Chunk(GeminiInteractionsWire.obj("type" -> Json.Str("text"), "text" -> Json.Str(step.text)))
            )
          )
        )
      case "function_call" =>
        calls.get(index).map { call =>
          val fields = List(
            "type"      -> Json.Str("function_call"),
            "id"        -> Json.Str(call.id),
            "name"      -> Json.Str(call.name),
            "arguments" -> call.arguments
          ) ++ step.signature.map(value => "signature" -> Json.Str(value))
          GeminiInteractionsWire.obj(fields*)
        }
      // thought 及未来未知签名 step 必须保持 Provider 原始 JSON，不能暴露为普通文本。
      case "thought" => Some(step.original)
      case _         => None

  /** 从 model_output step 读取 step.start 已携带的首段文本。 */
  private def modelOutputText(step: Json): String =
    GeminiInteractionsWire
      .arrayField(step, "content")
      .getOrElse(Chunk.empty)
      .flatMap(part =>
        Option
          .when(GeminiInteractionsWire.stringField(part, "type").contains("text"))(
            GeminiInteractionsWire.stringField(part, "text")
          )
          .flatten
      )
      .mkString

  /** 将 unary arguments 对象规范为流式累积字符串。 */
  private def argumentsText(value: Option[Json]): String = value match
    case None                 => ""
    case Some(Json.Str(text)) => text
    case Some(json)           => json.toJson

  /** 校验终止 usage；负 token 永远不能被归零后继续。 */
  private def decodeUsage(value: Option[Json], fallback: TokenUsage): IO[AgentError, TokenUsage] = value match
    case None       => ZIO.succeed(fallback)
    case Some(json) =>
      val input = GeminiInteractionsWire
        .longField(json, "total_input_tokens")
        .orElse(GeminiInteractionsWire.longField(json, "prompt_tokens"))
        .getOrElse(fallback.inputTokens)
      val output = GeminiInteractionsWire
        .longField(json, "total_output_tokens")
        .orElse(GeminiInteractionsWire.longField(json, "completion_tokens"))
        .getOrElse(fallback.outputTokens)
      if input >= 0L && output >= 0L then ZIO.succeed(TokenUsage(input, output))
      else
        ZIO.fail(
          AgentError.InvalidModelResponse(s"Gemini stream usage 包含负 token: input=$input, output=$output")
        )

  /** Provider 的流内错误只保留稳定类别，不把完整事件写入错误消息。 */
  private def streamFailure(event: Json, retryable: Boolean): IO[AgentError, Nothing] =
    val error  = GeminiInteractionsWire.field(event, "error").getOrElse(event)
    val status = GeminiInteractionsWire.stringField(error, "status").getOrElse("stream_error")
    ZIO.fail(AgentError.ModelFailure("gemini", s"Gemini stream error: $status", retryable))

  /** 首个 step 早于 interaction.created 时补发统一开始事件。 */
  private def ensureStarted(state: State): State = state.copy(started = true)

  /** 根据旧状态判断是否需要补发 ResponseStarted，避免重复。 */
  private def startPrefix(state: State): Chunk[ModelStreamEvent] =
    if state.started then Chunk.empty else Chunk(ModelStreamEvent.ResponseStarted(state.responseId))
