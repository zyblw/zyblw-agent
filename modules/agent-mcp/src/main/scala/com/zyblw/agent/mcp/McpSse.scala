package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

/** 一帧 Streamable HTTP SSE 事件。data 可能为空，用于服务端提供重连 cursor。 */
final private[mcp] case class McpSseEvent(id: Option[String], data: String, retry: Option[Duration])

/** MCP SSE 的增量解析器。 */
private[mcp] object McpSse:
  /** 正在累积的 SSE frame；连续 data 行按规范用换行连接。 */
  final private case class State(
      id: Option[String] = None,
      data: Chunk[String] = Chunk.empty,
      retry: Option[Duration] = None
  )

  /** 把任意 HTTP byte chunks 转为 SSE frame。
    *
    * `utf8Decode` 能处理中文跨 TCP chunk；空行结束 frame；注释行被忽略。`maxLineChars` 和 `maxEventChars` 分别限制单行与聚合
    * data，避免服务端通过永不结束的事件消耗内存。
    *
    * @param bytes
    *   ZIO HTTP response body
    * @param maxLineChars
    *   单行字符上限
    * @param maxEventChars
    *   一帧所有 data 行合计上限
    */
  def events(
      bytes: ZStream[Any, Throwable, Byte],
      maxLineChars: Int,
      maxEventChars: Int
  ): ZStream[Any, AgentError, McpSseEvent] =
    bytes
      .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
      .concat(ZStream.succeed(""))
      .mapError(error => transportError("sse/decode", "SSE UTF-8 decoding failed", "invalid_utf8", error))
      .mapZIO { line =>
        ZIO
          .fail(
            AgentError.ExternalProtocolFailure(
              "mcp",
              "sse/decode",
              "SSE line exceeded configured limit",
              Some("line_too_long")
            )
          )
          .when(line.length > maxLineChars)
          .as(line)
      }
      .mapAccumZIO(State()) { (state, line) =>
        if line.isEmpty then
          val event = Option.when(state.id.nonEmpty || state.data.nonEmpty || state.retry.nonEmpty)(
            McpSseEvent(state.id, state.data.mkString("\n"), state.retry)
          )
          ZIO.succeed(State() -> event)
        else if line.startsWith(":") then ZIO.succeed(state -> None)
        else
          val colon = line.indexOf(':')
          val name  = if colon < 0 then line else line.substring(0, colon)
          val raw   = if colon < 0 then "" else line.substring(colon + 1)
          val value = if raw.startsWith(" ") then raw.drop(1) else raw
          name match
            case "id" if !value.contains('\u0000') => ZIO.succeed(state.copy(id = Some(value)) -> None)
            case "data"                            =>
              val nextSize = state.data.foldLeft(0)(_ + _.length) + value.length
              if nextSize > maxEventChars then
                ZIO.fail(
                  AgentError.ExternalProtocolFailure(
                    "mcp",
                    "sse/decode",
                    "SSE event exceeded configured limit",
                    Some("event_too_large")
                  )
                )
              else ZIO.succeed(state.copy(data = state.data :+ value) -> None)
            case "retry" =>
              scala.util.Try(value.toLong).toOption.filter(_ >= 0L) match
                case Some(millis) => ZIO.succeed(state.copy(retry = Some(millis.millis)) -> None)
                case None         =>
                  ZIO.fail(
                    AgentError.ExternalProtocolFailure(
                      "mcp",
                      "sse/decode",
                      "SSE retry field must be a non-negative integer",
                      Some("invalid_retry")
                    )
                  )
            case _ => ZIO.succeed(state -> None)
      }
      .collectSome

  /** 形成不包含响应正文的 transport error。 */
  private def transportError(operation: String, message: String, code: String, cause: Throwable): AgentError =
    AgentError.ExternalProtocolFailure(
      "mcp",
      operation,
      message,
      Some(code),
      retryable = true,
      cause = Some(cause)
    )
