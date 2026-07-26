package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import zio.*
import zio.json.ast.Json
import zio.stream.*

/** MCP JSON-RPC transport 的统一契约。
  *
  * 传输实现负责 request id 关联、并发安全、协议超时和取消传播；上层 `McpClient` 负责 MCP 生命周期与能力 语义。这样 stdio 与 Streamable HTTP
  * 可以共享同一套初始化、分页和安全门禁，同时各自保留正确的资源 生命周期。
  */
trait McpTransport:
  /** 发送一个期待响应的请求。
    *
    * @param method
    *   MCP 方法名，例如 `tools/list`
    * @param params
    *   方法参数对象；没有参数时传空对象
    * @param timeout
    *   本次请求的硬超时；即使收到 progress 通知也不能无限延长
    * @return
    *   JSON-RPC `result` 字段，不包含 envelope
    *
    * 实现必须在调用 Fiber 被中断或超时时清理 pending 状态，并且除 `initialize` 外应尽力发送 `notifications/cancelled`。取消通知发送失败不能覆盖原始中断语义。
    */
  def request(method: String, params: Json.Obj, timeout: Duration): IO[AgentError, Json]

  /** 发送不期待响应的通知。 */
  def notify(method: String, params: Json.Obj = Json.Obj()): IO[AgentError, Unit]

  /** 响应服务端主动发来的反向请求。
    *
    * @param id
    *   必须原样回填服务端给出的 id
    * @param result
    *   `Right` 为成功结果，`Left` 为 JSON-RPC 错误
    */
  def respond(id: McpRequestId, result: Either[McpRpcError, Json]): IO[AgentError, Unit]

  /** 服务端主动通知与反向请求组成的背压流。 */
  def inbound: ZStream[Any, AgentError, McpInbound]

  /** 通知 HTTP transport 初始化协商已完成。
    *
    * stdio 可以保持空实现；Streamable HTTP 必须从此时开始发送 `MCP-Protocol-Version`，并携带初始化响应中 获得的 session id。该方法允许协议层不依赖具体
    * HTTP 实现。
    */
  def negotiated(version: McpProtocolVersion): IO[AgentError, Unit] = ZIO.succeed(version).unit

  /** 关闭底层进程、连接、reader Fiber 和队列；必须幂等。 */
  def close: UIO[Unit]

/** 脚本化 transport 中记录的一次调用，便于契约测试断言方法、参数和超时。 */
final case class RecordedMcpRequest(method: String, params: Json.Obj, timeout: Duration)

/** 无网络的确定性 MCP transport。
  *
  * 脚本值表示按方法排队的 `result` 或错误。它不是一个 mock client：真实 `DefaultMcpClient` 仍会执行 initialize、能力协商、分页和解析，因此可以捕获协议回归。
  */
final class ScriptedMcpTransport private (
    responses: Ref[Map[String, Chunk[Either[AgentError, Json]]]],
    recorded: Ref[Chunk[RecordedMcpRequest]],
    sentNotifications: Ref[Chunk[(String, Json.Obj)]],
    sentResponses: Ref[Chunk[(McpRequestId, Either[McpRpcError, Json])]],
    incoming: Queue[Take[AgentError, McpInbound]],
    negotiatedVersion: Ref[Option[McpProtocolVersion]],
    closed: Ref[Boolean]
) extends McpTransport:

  /** 取出该方法的下一份脚本结果；耗尽时以稳定协议错误失败。 */
  def request(method: String, params: Json.Obj, timeout: Duration): IO[AgentError, Json] =
    recorded.update(_ :+ RecordedMcpRequest(method, params, timeout)) *>
      responses.modify { current =>
        current.get(method).flatMap(_.headOption) match
          case Some(value) =>
            val remaining = current(method).drop(1)
            (value, current.updated(method, remaining))
          case None =>
            (
              Left(
                AgentError.ExternalProtocolFailure(
                  "mcp",
                  method,
                  s"scripted response exhausted for method: $method",
                  Some("script_exhausted")
                )
              ),
              current
            )
      }.absolve

  /** 记录通知而不伪造响应。 */
  def notify(method: String, params: Json.Obj): IO[AgentError, Unit] =
    sentNotifications.update(_ :+ (method -> params))

  /** 记录对反向请求的响应。 */
  def respond(id: McpRequestId, result: Either[McpRpcError, Json]): IO[AgentError, Unit] =
    sentResponses.update(_ :+ (id -> result))

  /** 测试可以通过 `offerInbound` 驱动的流。 */
  def inbound: ZStream[Any, AgentError, McpInbound] = ZStream.fromQueue(incoming).flattenTake

  /** 保存协商版本，使 HTTP header 行为也能在无网络测试中断言。 */
  override def negotiated(version: McpProtocolVersion): IO[AgentError, Unit] =
    negotiatedVersion.set(Some(version))

  /** 幂等结束流。 */
  def close: UIO[Unit] =
    closed
      .getAndSet(true)
      .flatMap(alreadyClosed => ZIO.unless(alreadyClosed)(incoming.offer(Take.end).unit))
      .unit

  /** 注入服务端主动消息。 */
  def offerInbound(value: McpInbound): UIO[Unit] = incoming.offer(Take.single(value)).unit

  /** 注入 transport 失败并终止入站流。 */
  def failInbound(error: AgentError): UIO[Unit] = incoming.offer(Take.fail(error)).unit

  /** 返回所有 request 调用。 */
  def requests: UIO[Chunk[RecordedMcpRequest]] = recorded.get

  /** 返回所有 notification。 */
  def notificationsSent: UIO[Chunk[(String, Json.Obj)]] = sentNotifications.get

  /** 返回对反向请求的所有响应。 */
  def responsesSent: UIO[Chunk[(McpRequestId, Either[McpRpcError, Json])]] = sentResponses.get

  /** 返回由客户端确认的协议版本。 */
  def version: UIO[Option[McpProtocolVersion]] = negotiatedVersion.get

  /** 判断 transport 是否已经执行关闭 finalizer。 */
  def isClosed: UIO[Boolean] = closed.get

object ScriptedMcpTransport:
  /** 创建脚本化 transport。
    *
    * @param script
    *   key 是 MCP method，value 是该 method 每次调用依次消费的结果
    * @param inboundCapacity
    *   服务端主动消息的有界缓冲；测试同样验证背压，而不是使用无界队列
    */
  def make(
      script: Map[String, Chunk[Either[AgentError, Json]]],
      inboundCapacity: Int = 32
  ): UIO[ScriptedMcpTransport] =
    for
      responses         <- Ref.make(script)
      recorded          <- Ref.make(Chunk.empty[RecordedMcpRequest])
      notifications     <- Ref.make(Chunk.empty[(String, Json.Obj)])
      sentResponses     <- Ref.make(Chunk.empty[(McpRequestId, Either[McpRpcError, Json])])
      incoming          <- Queue.bounded[Take[AgentError, McpInbound]](inboundCapacity.max(1))
      negotiatedVersion <- Ref.make(Option.empty[McpProtocolVersion])
      closed            <- Ref.make(false)
    yield ScriptedMcpTransport(
      responses,
      recorded,
      notifications,
      sentResponses,
      incoming,
      negotiatedVersion,
      closed
    )

  /** 兼容简单测试的成功脚本工厂。 */
  def successful(script: Map[String, Chunk[Json]]): UIO[ScriptedMcpTransport] =
    make(script.view.mapValues(_.map(Right(_))).toMap)
