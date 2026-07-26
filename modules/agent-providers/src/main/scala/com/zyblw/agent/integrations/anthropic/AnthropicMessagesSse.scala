package com.zyblw.agent.integrations.anthropic

import com.zyblw.agent.core.*
import com.zyblw.agent.model.ModelStreamEvent
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** Anthropic Messages typed SSE 的不可变状态机。 */
private[anthropic] object AnthropicMessagesSse:
  private val EndOfStream = "\u0000zyblw-agent-anthropic-sse-eof\u0000"

  /** 单个 content block 的增量状态；不同 kind 只使用与自身相关的字段。 */
  final private case class PartialBlock(
      kind: String,
      text: String = "",
      id: String = "",
      name: String = "",
      arguments: String = "",
      thinking: String = "",
      signature: String = ""
  )

  /** 整条 Message 流的可恢复纯状态，不持有 socket、Fiber 或可变 builder。 */
  final private case class State(
      responseId: Option[String] = None,
      blocks: Map[Int, PartialBlock] = Map.empty,
      completedCalls: Map[Int, ToolCall] = Map.empty,
      inputTokens: Long = 0L,
      outputTokens: Long = 0L,
      stopReason: Option[String] = None,
      started: Boolean = false,
      terminal: Boolean = false
  )

  /** 把任意 HTTP byte chunks 转成框架语义事件。
    *
    * `utf8Decode >>> splitLines` 处理中文字符跨 TCP chunk；空行聚合 SSE frame；EOF sentinel 确保 message_stop 前断流不会被当成正常完成。
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
      .mapError(error => AgentError.InvalidModelResponse(s"Anthropic SSE UTF-8 解码失败: ${error.getMessage}"))

    frames
      .concat(ZStream.succeed(EndOfStream))
      .mapAccumZIO(State())(decodeFrame)
      .mapConcatChunk(identity)

  /** 解析一帧 JSON；终止前 EOF 显式失败，禁止输出残缺答案。 */
  private def decodeFrame(state: State, frame: String): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    if frame == EndOfStream then
      if state.terminal then ZIO.succeed(state -> Chunk.empty)
      else ZIO.fail(AgentError.InvalidModelResponse("Anthropic Provider 流在 message_stop 前中断"))
    else
      ZIO
        .fromEither(frame.fromJson[Json])
        .mapError(AgentError.InvalidModelResponse(_))
        .flatMap(event => decodeEvent(state, event))

  /** 按 Anthropic `type` 分派语义事件；ping 和未知向前兼容事件安全忽略。 */
  private def decodeEvent(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    AnthropicMessagesWire.stringField(event, "type") match
      case Some("message_start")       => messageStart(state, event)
      case Some("content_block_start") => blockStart(state, event)
      case Some("content_block_delta") => blockDelta(state, event)
      case Some("content_block_stop")  => blockStop(state, event)
      case Some("message_delta")       => messageDelta(state, event)
      case Some("message_stop")        => messageStop(state)
      case Some("error")               => streamError(event)
      case Some("ping")                => ZIO.succeed(state -> Chunk.empty)
      case _                           => ZIO.succeed(state -> Chunk.empty)

  /** 读取 response ID 和初始 input usage，并且只发送一次 ResponseStarted。 */
  private def messageStart(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val message = AnthropicMessagesWire.field(event, "message").getOrElse(Json.Obj())
    val id      = AnthropicMessagesWire.stringField(message, "id").orElse(state.responseId)
    val usage   = AnthropicMessagesWire.field(message, "usage")
    val input = usage.flatMap(AnthropicMessagesWire.longField(_, "input_tokens")).getOrElse(state.inputTokens)
    if input < 0L then ZIO.fail(AgentError.InvalidModelResponse("message_start usage.input_tokens 不能为负数"))
    else
      val next    = state.copy(responseId = id, inputTokens = input, started = true)
      val emitted = if state.started then Chunk.empty else Chunk(ModelStreamEvent.ResponseStarted(id))
      ZIO.succeed(next -> emitted)

  /** 建立 text/tool_use/thinking block；tool_use 立即发出稳定 ID 与名称。 */
  private def blockStart(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val index   = AnthropicMessagesWire.intField(event, "index").getOrElse(state.blocks.size)
    val block   = AnthropicMessagesWire.field(event, "content_block").getOrElse(Json.Obj())
    val kind    = AnthropicMessagesWire.stringField(block, "type").getOrElse("unknown")
    val partial = kind match
      case "text" => PartialBlock(kind, text = AnthropicMessagesWire.stringField(block, "text").getOrElse(""))
      case "tool_use" =>
        PartialBlock(
          kind,
          id = AnthropicMessagesWire.stringField(block, "id").getOrElse(s"tool-$index"),
          name = AnthropicMessagesWire.stringField(block, "name").getOrElse("")
        )
      case "thinking" =>
        PartialBlock(
          kind,
          thinking = AnthropicMessagesWire.stringField(block, "thinking").getOrElse(""),
          signature = AnthropicMessagesWire.stringField(block, "signature").getOrElse("")
        )
      case _ => PartialBlock(kind)
    val next    = ensureStarted(state).copy(blocks = ensureStarted(state).blocks.updated(index, partial))
    val started = Option
      .when(kind == "tool_use" && partial.name.nonEmpty)(
        ModelStreamEvent.ToolCallStarted(partial.id, partial.name)
      )
      .toList
    val prefix =
      if state.started then Chunk.empty else Chunk(ModelStreamEvent.ResponseStarted(state.responseId))
    ZIO.succeed(next -> (prefix ++ Chunk.fromIterable(started)))

  /** 追加文本、工具 JSON、thinking 或 signature 增量，保持 Provider 原始顺序。 */
  private def blockDelta(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val index     = AnthropicMessagesWire.intField(event, "index").getOrElse(0)
    val delta     = AnthropicMessagesWire.field(event, "delta").getOrElse(Json.Obj())
    val deltaType = AnthropicMessagesWire.stringField(delta, "type").getOrElse("")
    val previous  = state.blocks.getOrElse(index, PartialBlock("unknown"))
    deltaType match
      case "text_delta" =>
        val value = AnthropicMessagesWire.stringField(delta, "text").getOrElse("")
        val next  = previous.copy(kind = "text", text = previous.text + value)
        ZIO.succeed(
          state.copy(blocks = state.blocks.updated(index, next)) -> Chunk(ModelStreamEvent.TextDelta(value))
        )
      case "input_json_delta" =>
        val value = AnthropicMessagesWire.stringField(delta, "partial_json").getOrElse("")
        val next  = previous.copy(kind = "tool_use", arguments = previous.arguments + value)
        ZIO.succeed(
          state.copy(blocks = state.blocks.updated(index, next)) ->
            Chunk(ModelStreamEvent.ToolCallDelta(next.id, Option.when(next.name.nonEmpty)(next.name), value))
        )
      case "thinking_delta" =>
        val value = AnthropicMessagesWire.stringField(delta, "thinking").getOrElse("")
        val next  = previous.copy(kind = "thinking", thinking = previous.thinking + value)
        ZIO.succeed(
          state.copy(blocks = state.blocks.updated(index, next)) -> Chunk(
            ModelStreamEvent.ReasoningDelta(value)
          )
        )
      case "signature_delta" =>
        val value = AnthropicMessagesWire.stringField(delta, "signature").getOrElse("")
        val next  = previous.copy(kind = "thinking", signature = previous.signature + value)
        ZIO.succeed(state.copy(blocks = state.blocks.updated(index, next)) -> Chunk.empty)
      case _ => ZIO.succeed(state -> Chunk.empty)

  /** tool_use block 完成时校验累积 JSON，并恰好发送一次 ToolCallCompleted。 */
  private def blockStop(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val index = AnthropicMessagesWire.intField(event, "index").getOrElse(0)
    state.blocks.get(index) match
      case Some(block) if block.kind == "tool_use" && !state.completedCalls.contains(index) =>
        val encoded = Option.when(block.arguments.nonEmpty)(block.arguments).getOrElse("{}")
        ZIO
          .fromEither(encoded.fromJson[Json])
          .mapError(details => AgentError.InvalidModelResponse(s"Anthropic 流式工具参数不是合法 JSON: $details"))
          .map { arguments =>
            val call = ToolCall(block.id, block.name, arguments)
            state.copy(completedCalls = state.completedCalls.updated(index, call)) -> Chunk(
              ModelStreamEvent.ToolCallCompleted(call)
            )
          }
      case _ => ZIO.succeed(state -> Chunk.empty)

  /** message_delta 提供累计 output usage 与 stop_reason；负 usage 立即失败。 */
  private def messageDelta(state: State, event: Json): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    val delta  = AnthropicMessagesWire.field(event, "delta").getOrElse(Json.Obj())
    val usage  = AnthropicMessagesWire.field(event, "usage")
    val output =
      usage.flatMap(AnthropicMessagesWire.longField(_, "output_tokens")).getOrElse(state.outputTokens)
    val stop = AnthropicMessagesWire.stringField(delta, "stop_reason").orElse(state.stopReason)
    if output < 0L then ZIO.fail(AgentError.InvalidModelResponse("message_delta usage.output_tokens 不能为负数"))
    else
      val next       = state.copy(outputTokens = output, stopReason = stop)
      val usageEvent = ModelStreamEvent.UsageUpdated(TokenUsage(next.inputTokens, next.outputTokens))
      ZIO.succeed(next -> Chunk(usageEvent))

  /** 组装最终助手消息、原始 blocks、usage 和唯一 Completed。 */
  private def messageStop(state: State): IO[AgentError, (State, Chunk[ModelStreamEvent])] =
    if state.terminal then ZIO.fail(AgentError.InvalidModelResponse("Anthropic 流重复发送 message_stop"))
    else
      val calls = Chunk.fromIterable(state.completedCalls.toList.sortBy(_._1).map(_._2))
      val text  = state.blocks.toList
        .sortBy(_._1)
        .collect { case (_, block) if block.kind == "text" => block.text }
        .mkString
      val rawBlocks = state.blocks.toList.sortBy(_._1).flatMap { case (index, block) =>
        rawBlock(index, block, state.completedCalls)
      }
      val base =
        if calls.nonEmpty then AgentMessage.assistantToolCalls(calls, text) else AgentMessage.assistant(text)
      val message = base.copy(metadata =
        base.metadata.updated(
          AnthropicMessagesWire.RawContentBlocksMetadata,
          Json.Arr(Chunk.fromIterable(rawBlocks)).toJson
        )
      )
      val usage    = TokenUsage(state.inputTokens, state.outputTokens)
      val response = ChatResponse(
        message,
        AnthropicMessagesWire.finishReason(state.stopReason, calls.nonEmpty),
        usage,
        state.responseId
      )
      ZIO.succeed(state.copy(terminal = true) -> Chunk(ModelStreamEvent.Completed(response)))

  /** 将累积 block 重建为下一轮可原样回填的 Anthropic content block。 */
  private def rawBlock(index: Int, block: PartialBlock, calls: Map[Int, ToolCall]): Option[Json] =
    block.kind match
      case "text" =>
        Some(AnthropicMessagesWire.obj("type" -> Json.Str("text"), "text" -> Json.Str(block.text)))
      case "tool_use" =>
        calls
          .get(index)
          .map(call =>
            AnthropicMessagesWire.obj(
              "type"  -> Json.Str("tool_use"),
              "id"    -> Json.Str(call.id),
              "name"  -> Json.Str(call.name),
              "input" -> call.arguments
            )
          )
      case "thinking" =>
        Some(
          AnthropicMessagesWire.obj(
            "type"      -> Json.Str("thinking"),
            "thinking"  -> Json.Str(block.thinking),
            "signature" -> Json.Str(block.signature)
          )
        )
      case _ => None

  /** 把 Anthropic 流内 error event 映射为 typed failure；overloaded_error 允许重试。 */
  private def streamError(event: Json): IO[AgentError, Nothing] =
    val error = AnthropicMessagesWire.field(event, "error").getOrElse(event)
    val kind  = AnthropicMessagesWire.stringField(error, "type").getOrElse("stream_error")
    ZIO.fail(
      AgentError.ModelFailure(
        "anthropic",
        s"Anthropic stream error: $kind",
        retryable = kind == "overloaded_error"
      )
    )

  /** 首个 content 事件早于 message_start 时补充 started 状态，兼容代理转发差异。 */
  private def ensureStarted(state: State): State = state.copy(started = true)
