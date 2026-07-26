package com.zyblw.agent.integrations.anthropic

import com.zyblw.agent.core.*
import com.zyblw.agent.testkit.*
import java.net.ServerSocket
import zio.*
import zio.http.*
import zio.stream.*
import zio.test.*

/** Anthropic Messages 的本机 HTTP ProviderContract 2.0。
  *
  * 纯 wire test 无法证明 headers、状态分类、慢流、断流和取消会正确穿过 ZIO HTTP Scope， 因此这里使用真实本机 socket，但全部响应都是确定性假数据，不消耗模型额度。
  */
object AnthropicMessagesHttpContractSpec extends ZIOSpecDefault:
  /** 向操作系统申请空闲端口，避免并行测试固定端口冲突。 */
  private def freePort: Task[Int] = ZIO.attemptBlocking {
    val socket = ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  /** 根据请求 model 选择正常、故障或取消场景，并记录必需 headers。 */
  private def routes(
      apiKeys: Ref[Chunk[Boolean]],
      versions: Ref[Chunk[Boolean]],
      cancelClosed: Promise[Nothing, Unit]
  ): Routes[Any, Response] = Routes(
    Method.POST / "v1" / "messages" -> handler { (request: Request) =>
      request.body.asString
        .flatMap { body =>
          // 测试只记录“必需 header 是否存在且值正确”，不把测试密钥复制到诊断对象。
          val renderedHeaders = request.headers.toString
          val keyPresent      =
            renderedHeaders.toLowerCase.contains("x-api-key") && renderedHeaders.contains("stub-secret")
          val versionPresent = renderedHeaders.toLowerCase.contains("anthropic-version") && renderedHeaders
            .contains("2023-06-01")
          val response =
            if body.contains("\"model\":\"http-429\"") then error(Status.TooManyRequests, "rate_limit_error")
            else if body.contains("\"model\":\"http-500\"") then
              error(Status.InternalServerError, "api_error")
            else if body.contains("\"model\":\"invalid-usage\"") then invalidUsage
            else if body.contains("\"model\":\"truncated-stream\"") then truncatedStream
            else if body.contains("\"model\":\"slow-stream\"") then slowStream
            else if body.contains("\"model\":\"cancel-stream\"") then cancelStream(cancelClosed)
            else if body.contains("\"stream\":true") then normalStream
            else normalComplete
          apiKeys.update(_ :+ keyPresent) *> versions.update(_ :+ versionPresent) as response
        }
        .mapError(error => Response.internalServerError(error.getMessage))
    }
  )

  /** 正常非流式响应。 */
  private def normalComplete: Response = Response.json(
    """{"id":"msg-http","type":"message","role":"assistant","content":[{"type":"text","text":"stub ok"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}"""
  )

  /** 正常 SSE，明确包含 message_start、usage、message_stop。 */
  private def normalStream: Response = sse(
    """data: {"type":"message_start","message":{"id":"msg-stream","content":[],"usage":{"input_tokens":6,"output_tokens":0}}}
      |
      |data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
      |
      |data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"stub stream"}}
      |
      |data: {"type":"content_block_stop","index":0}
      |
      |data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}
      |
      |data: {"type":"message_stop"}
      |
      |""".stripMargin
  )

  /** 返回 Anthropic 标准错误 envelope。 */
  private def error(status: Status, kind: String): Response =
    Response.json(s"""{"type":"error","error":{"type":"$kind","message":"stub"}}""").copy(status = status)

  /** 负 token 必须被 Adapter 拒绝，不能绕过预算。 */
  private def invalidUsage: Response = Response.json(
    """{"id":"bad","content":[{"type":"text","text":"bad"}],"stop_reason":"end_turn","usage":{"input_tokens":-1,"output_tokens":2}}"""
  )

  /** 只有 message_start，没有 message_stop，用于验证断流。 */
  private def truncatedStream: Response = sse(
    """data: {"type":"message_start","message":{"id":"truncated","content":[],"usage":{"input_tokens":1,"output_tokens":0}}}
      |
      |""".stripMargin
  )

  /** 在两个合法事件之间暂停，验证 Parser 不依赖“网络一次性返回全部内容”。 */
  private def slowStream: Response =
    val first =
      "data: {\"type\":\"message_start\",\"message\":{\"id\":\"slow\",\"content\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}}\n\n"
    val rest =
      "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
    val body = ZStream.fromIterable(first.getBytes) ++
      ZStream.fromZIO(ZIO.sleep(100.millis)).drain ++
      ZStream.fromIterable(rest.getBytes)
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(body)
    )

  /** 无限流 finalizer 完成 Promise，证明客户端中断真正关闭服务端 Body。 */
  private def cancelStream(closed: Promise[Nothing, Unit]): Response =
    val first =
      "data: {\"type\":\"message_start\",\"message\":{\"id\":\"cancel\",\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
    val body = (ZStream.fromIterable(first.getBytes) ++ ZStream.fromZIO(ZIO.never).drain)
      .ensuring(closed.succeed(()).unit)
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(body)
    )

  /** 把完整文本包装成 event-stream Response。 */
  private def sse(value: String): Response =
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(ZStream.fromIterable(value.getBytes))
    )

  /** 构造指定模型的请求；工具结果回填确保成功契约覆盖第二轮调用。 */
  private def request(model: String): ChatRequest =
    ChatRequest(
      Chunk(
        AgentMessage.user("hello"),
        AgentMessage.assistantToolCalls(Chunk(ToolCall("call-1", "lookup", zio.json.ast.Json.Obj()))),
        AgentMessage.tool("call-1", "lookup", ToolResult(zio.json.ast.Json.Obj()))
      ),
      settings = ModelSettings(model = Some(model))
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Anthropic HTTP ProviderContract 2.0")(
    test("成功、429/500、负 usage、断流、取消和脱敏 cassette 全部通过统一门禁") {
      for
        port          <- freePort
        apiKeys       <- Ref.make(Chunk.empty[Boolean])
        versions      <- Ref.make(Chunk.empty[Boolean])
        cancelClosed  <- Promise.make[Nothing, Unit]
        cancelStarted <- Promise.make[Nothing, Unit]
        result        <- (for
          _      <- Server.serve(routes(apiKeys, versions, cancelClosed)).forkScoped
          _      <- ZIO.sleep(100.millis)
          client <- ZIO.service[Client]
          model = AnthropicMessagesChatModel(
            client,
            AnthropicMessagesConfig(
              s"http://127.0.0.1:$port/v1",
              "stub-secret",
              "normal",
              requestTimeout = 5.seconds
            )
          )
          cassette <- ProviderCassette.inMemory(ProviderCassettePolicy.Redacted)
          suite    <- ProviderContract.verifySuite(
            model,
            request("normal"),
            Chunk(
              ProviderFailureProbe(
                "429",
                ErrorCategory.Unavailable,
                true,
                model.complete(request("http-429"))
              ),
              ProviderFailureProbe(
                "500",
                ErrorCategory.Unavailable,
                true,
                model.complete(request("http-500"))
              ),
              ProviderFailureProbe(
                "invalid-usage",
                ErrorCategory.Validation,
                false,
                model.complete(request("invalid-usage"))
              ),
              ProviderFailureProbe(
                "truncated",
                ErrorCategory.Validation,
                false,
                model.stream(request("truncated-stream")).runDrain
              )
            ),
            Some(
              ProviderCancellationProbe(
                "cancel",
                model.stream(request("cancel-stream")).tap(_ => cancelStarted.succeed(()).unit).runDrain,
                cancelClosed.isDone,
                cancelStarted.await
              )
            ),
            cassette
          )
          recorded    <- cassette.entries
          keys        <- apiKeys.get
          apiVersions <- versions.get
        yield (suite, recorded, keys, apiVersions)).provideSome[Scope](
          Client.default,
          Server.defaultWithPort(port)
        )
      yield assertTrue(
        result._1.passed,
        result._2.size == 2,
        result._2.forall(_.model == "<redacted>"),
        result._3.forall(identity),
        result._4.forall(identity)
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("慢流在超时预算内正常完成") {
      for
        port         <- freePort
        apiKeys      <- Ref.make(Chunk.empty[Boolean])
        versions     <- Ref.make(Chunk.empty[Boolean])
        cancelClosed <- Promise.make[Nothing, Unit]
        response     <- (for
          _      <- Server.serve(routes(apiKeys, versions, cancelClosed)).forkScoped
          _      <- ZIO.sleep(100.millis)
          client <- ZIO.service[Client]
          model = AnthropicMessagesChatModel(
            client,
            AnthropicMessagesConfig(
              s"http://127.0.0.1:$port/v1",
              "stub-secret",
              "slow-stream",
              requestTimeout = 2.seconds
            )
          )
          events <- model.stream(request("slow-stream")).runCollect
        yield events.collectFirst { case com.zyblw.agent.model.ModelStreamEvent.Completed(value) => value })
          .provideSome[Scope](Client.default, Server.defaultWithPort(port))
      yield assertTrue(response.exists(_.usage == TokenUsage(2, 1)))
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential
  )
