package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import zio.*
import zio.json.ast.Json
import zio.stream.*

/** 一条等待 JSON-RPC response 的本地请求。operation 只用于安全错误分类。 */
final private case class PendingMcpRequest(operation: String, promise: Promise[AgentError, Json])

/** 与具体介质无关的 JSON-RPC 对等端状态机。
  *
  * stdio 与 Streamable HTTP 都需要处理并发 request id、反向请求、通知、超时和晚到响应。把这些逻辑集中在 一个 ZIO 状态机中，可以避免两个 transport 在取消和
  * pending 清理上产生不同语义。
  */
final private[mcp] class McpJsonRpcPeer private (
    send: Json.Obj => IO[AgentError, Unit],
    nextId: Ref[Long],
    pending: Ref.Synchronized[Map[String, PendingMcpRequest]],
    incoming: Queue[Take[AgentError, McpInbound]],
    closed: Ref[Option[AgentError]]
):

  /** 创建请求、注册 Promise、发送并等待响应。
    *
    * pending 在成功、协议错误、超时、发送失败和 Fiber 中断五条路径都会清理。超时和中断会尽力发送取消 通知，但 `initialize` 按规范禁止取消。
    */
  def request(method: String, params: Json.Obj, timeout: Duration): IO[AgentError, Json] =
    for
      _       <- ensureOpen(method)
      idValue <- nextId.updateAndGet(_ + 1L)
      id = McpRequestId.numeric(idValue)
      promise <- Promise.make[AgentError, Json]
      _       <- pending.update(_.updated(id.key, PendingMcpRequest(method, promise)))
      timeoutError = AgentError.ExternalProtocolFailure(
        "mcp",
        method,
        s"MCP request timed out after ${timeout.render}",
        Some("request_timeout"),
        retryable = true
      )
      cancel = ZIO
        .when(method != "initialize")(
          notify(
            "notifications/cancelled",
            McpJson.obj(
              "requestId" -> id.value,
              "reason"    -> Json.Str("client request cancelled or timed out")
            )
          ).ignore
        )
        .unit
      result <- (send(McpJson.request(id, method, params)) *> promise.await)
        .timeoutFail(timeoutError)(timeout)
        .tapError {
          case AgentError.ExternalProtocolFailure(_, _, _, Some("request_timeout"), _, _) => cancel
          case _                                                                          => ZIO.unit
        }
        .onInterrupt(cancel)
        .ensuring(pending.update(_ - id.key))
    yield result

  /** 发送通知。 */
  def notify(method: String, params: Json.Obj): IO[AgentError, Unit] =
    ensureOpen(method) *> send(McpJson.notification(method, params))

  /** 响应服务端反向请求。 */
  def respond(id: McpRequestId, result: Either[McpRpcError, Json]): IO[AgentError, Unit] =
    ensureOpen("jsonrpc/respond") *> send(result.fold(McpJson.failure(id, _), McpJson.success(id, _)))

  /** 服务端主动消息的有界流。 */
  def inbound: ZStream[Any, AgentError, McpInbound] = ZStream.fromQueue(incoming).flattenTake

  /** 接收并分类一个已经通过 UTF-8/JSON 解析的 envelope。
    *
    * 响应通过 request id 完成对应 Promise；晚到或未知响应被安全丢弃。反向请求和通知进入有界队列并形成 背压，防止服务端用通知洪泛无限占用客户端内存。
    */
  def accept(message: Json.Obj): IO[AgentError, Unit] =
    for
      _           <- ZIO.fromEither(McpJson.validateVersion(message, "jsonrpc/receive"))
      maybeMethod <- ZIO.fromEither(McpJson.optionalString(message, "method", "jsonrpc/receive"))
      _           <- maybeMethod match
        case Some(method) => acceptInboundMethod(message, method)
        case None         => acceptResponse(message)
    yield ()

  /** 使所有 pending 请求失败，并以相同失败终止入站流；第一次失败获胜。 */
  def failAll(error: AgentError): UIO[Unit] =
    closed
      .modify {
        case Some(existing) => false -> Some(existing)
        case None           => true  -> Some(error)
      }
      .flatMap { first =>
        ZIO
          .when(first) {
            for
              requests <- pending.getAndSet(Map.empty)
              _        <- ZIO.foreachDiscard(requests.values)(_.promise.fail(error))
              // 终止信号优先于尚未消费的普通通知，避免 bounded queue 已满时清理 Fiber 永久阻塞。
              _ <- incoming.takeAll
              _ <- incoming.offer(Take.fail(error))
            yield ()
          }
          .unit
      }

  /** 正常关闭：pending 收到 closed 错误，入站订阅者收到流结束。 */
  def close: UIO[Unit] =
    val error = AgentError.ExternalProtocolFailure(
      "mcp",
      "transport/close",
      "MCP transport is closed",
      Some("transport_closed")
    )
    closed
      .modify {
        case Some(existing) => false -> Some(existing)
        case None           => true  -> Some(error)
      }
      .flatMap { first =>
        ZIO
          .when(first) {
            for
              requests <- pending.getAndSet(Map.empty)
              _        <- ZIO.foreachDiscard(requests.values)(_.promise.fail(error))
              _        <- incoming.takeAll
              _        <- incoming.offer(Take.end)
            yield ()
          }
          .unit
      }

  /** 返回 pending 数量，只用于 transport 契约测试和健康诊断。 */
  private[mcp] def pendingCount: UIO[Int] = pending.get.map(_.size)

  /** 判断某个 id 是否仍“尚未获得响应”。
    *
    * Promise 完成到 request Fiber 执行 ensuring 删除 map 之间存在一个很短的窗口；HTTP replay 若只检查 map 是否含 key，会在响应已经到达后继续
    * GET。这里同时检查 `Promise.isDone`，消除该竞态。
    */
  private[mcp] def isPending(id: McpRequestId): UIO[Boolean] =
    pending.get.flatMap {
      case current if current.get(id.key).isEmpty => ZIO.succeed(false)
      case current                                => current(id.key).promise.isDone.map(done => !done)
    }

  /** 关闭后禁止新消息。 */
  private def ensureOpen(operation: String): IO[AgentError, Unit] =
    closed.get.flatMap {
      case None        => ZIO.unit
      case Some(error) =>
        ZIO.fail(error match
          case value: AgentError.ExternalProtocolFailure => value.copy(operation = operation)
          case other                                     => other)
    }

  /** 分类 method 消息，并要求 params 是对象。 */
  private def acceptInboundMethod(message: Json.Obj, method: String): IO[AgentError, Unit] =
    val params = McpJson.field(message, "params") match
      case None | Some(Json.Null) => Right(Json.Obj())
      case Some(value: Json.Obj)  => Right(value)
      case Some(_) => Left(McpJson.protocolError("jsonrpc/receive", "params must be an object"))
    ZIO.fromEither(params).flatMap { parsed =>
      McpJson.field(message, "id") match
        case None        => incoming.offer(Take.single(McpInbound.Notification(method, parsed))).unit
        case Some(value) =>
          ZIO
            .fromEither(McpRequestId.fromJson(value))
            .flatMap(id => incoming.offer(Take.single(McpInbound.Request(id, method, parsed))).unit)
    }

  /** 完成 result/error response；两者必须恰好出现一个。 */
  private def acceptResponse(message: Json.Obj): IO[AgentError, Unit] =
    for
      rawId <- ZIO.fromEither(McpJson.required(message, "id", "jsonrpc/receive"))
      id    <- ZIO.fromEither(McpRequestId.fromJson(rawId))
      result = McpJson.field(message, "result")
      error  = McpJson.field(message, "error")
      completion <- (result, error) match
        case (Some(value), None)           => ZIO.succeed(Right(value))
        case (None, Some(value: Json.Obj)) =>
          pending.get.map(_.get(id.key).map(_.operation).getOrElse("jsonrpc/response")).map { operation =>
            Left(McpJson.remoteFailure(operation, value))
          }
        case _ =>
          ZIO.fail(
            McpJson.protocolError(
              "jsonrpc/receive",
              "response must contain exactly one of result or error"
            )
          )
      current <- pending.get.map(_.get(id.key))
      _       <- current match
        case None          => ZIO.unit
        case Some(waiting) => waiting.promise.complete(ZIO.fromEither(completion)).unit
    yield ()

object McpJsonRpcPeer:
  /** 创建一个使用有界入站队列的 peer。 */
  def make(send: Json.Obj => IO[AgentError, Unit], inboundCapacity: Int): UIO[McpJsonRpcPeer] =
    for
      nextId   <- Ref.make(0L)
      pending  <- Ref.Synchronized.make(Map.empty[String, PendingMcpRequest])
      incoming <- Queue.bounded[Take[AgentError, McpInbound]](inboundCapacity.max(1))
      closed   <- Ref.make(Option.empty[AgentError])
    yield McpJsonRpcPeer(send, nextId, pending, incoming, closed)
