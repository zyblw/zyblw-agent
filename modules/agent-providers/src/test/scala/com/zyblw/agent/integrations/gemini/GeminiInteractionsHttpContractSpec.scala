package com.zyblw.agent.integrations.gemini

import com.zyblw.agent.core.*
import com.zyblw.agent.model.ModelStreamEvent
import com.zyblw.agent.testkit.*
import java.net.ServerSocket
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** Gemini Interactions 的本机 HTTP ProviderContract 2.0。
  *
  * Wire 测试覆盖 JSON/SSE 状态机，本测试用真实本机 socket 证明 header、HTTP 分类、慢流、断流、 ZIO Fiber 取消和脱敏 cassette 能穿过实际
  * Client/Server Scope。
  */
object GeminiInteractionsHttpContractSpec extends ZIOSpecDefault:
  /** 让操作系统分配测试端口，避免并行 CI 中硬编码端口冲突。 */
  private def freePort: Task[Int] = ZIO.attemptBlocking {
    val socket = ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  /** 根据请求 model 选择确定性 stub 场景，并只记录认证/版本是否正确。 */
  private def routes(
      apiKeys: Ref[Chunk[Boolean]],
      revisions: Ref[Chunk[Boolean]],
      stores: Ref[Chunk[Boolean]],
      cancelClosed: Promise[Nothing, Unit]
  ): Routes[Any, Response] = Routes(
    Method.POST / "v1" / "interactions" -> handler { (request: Request) =>
      request.body.asString
        .flatMap { body =>
          val headers    = request.headers.toString
          val keyPresent = headers.toLowerCase.contains("x-goog-api-key") && headers.contains("stub-secret")
          val revisionPresent = headers.toLowerCase.contains("api-revision") && headers.contains("2026-05-20")
          val stateless       = body.contains("\"store\":false") && !body.contains("previous_interaction_id")
          val response        =
            if body.contains("\"model\":\"http-429\"") then
              error(Status.TooManyRequests, "RESOURCE_EXHAUSTED")
            else if body.contains("\"model\":\"http-500\"") then error(Status.InternalServerError, "INTERNAL")
            else if body.contains("\"model\":\"invalid-usage\"") then invalidUsage
            else if body.contains("\"model\":\"truncated-stream\"") then truncatedStream
            else if body.contains("\"model\":\"slow-stream\"") then slowStream
            else if body.contains("\"model\":\"cancel-stream\"") then cancelStream(cancelClosed)
            else if body.contains("\"stream\":true") then normalStream
            else normalComplete
          apiKeys.update(_ :+ keyPresent) *>
            revisions.update(_ :+ revisionPresent) *>
            stores.update(_ :+ stateless) as response
        }
        .mapError(error => Response.internalServerError(error.getMessage))
    }
  )

  /** 正常非流式 interaction。 */
  private def normalComplete: Response = Response.json(
    """{"id":"int-http","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"stub ok"}]}],"usage":{"total_input_tokens":5,"total_output_tokens":2,"total_tokens":7}}"""
  )

  /** 正常 typed SSE，明确发送 created、step 和 completed。 */
  private def normalStream: Response = sse(
    """data: {"interaction":{"id":"int-stream","status":"in_progress"},"event_type":"interaction.created"}
      |
      |data: {"index":0,"step":{"type":"model_output","content":[]},"event_type":"step.start"}
      |
      |data: {"index":0,"delta":{"type":"text","text":"stub stream"},"event_type":"step.delta"}
      |
      |data: {"index":0,"event_type":"step.stop"}
      |
      |data: {"interaction":{"id":"int-stream","status":"completed","usage":{"total_input_tokens":6,"total_output_tokens":3}},"event_type":"interaction.completed"}
      |
      |""".stripMargin
  )

  /** Google 风格错误 envelope；敏感 message 不应进入 Adapter 错误文本。 */
  private def error(status: Status, kind: String): Response =
    Response
      .json(s"""{"error":{"code":${status.code},"status":"$kind","message":"sensitive stub body"}}""")
      .copy(status = status)

  /** 负 token 用于验证预算数据 fail-closed。 */
  private def invalidUsage: Response = Response.json(
    """{"id":"bad","status":"completed","steps":[],"usage":{"total_input_tokens":-1,"total_output_tokens":2}}"""
  )

  /** created 后直接 EOF；Adapter 必须报告截断而不是输出 Completed。 */
  private def truncatedStream: Response = sse(
    """data: {"interaction":{"id":"truncated","status":"in_progress"},"event_type":"interaction.created"}
      |
      |""".stripMargin
  )

  /** 在合法事件之间暂停，证明 parser 不依赖 body 一次到齐。 */
  private def slowStream: Response =
    val first =
      "data: {\"interaction\":{\"id\":\"slow\",\"status\":\"in_progress\"},\"event_type\":\"interaction.created\"}\n\n"
    val rest =
      "data: {\"index\":0,\"step\":{\"type\":\"model_output\",\"content\":[]},\"event_type\":\"step.start\"}\n\n" +
        "data: {\"index\":0,\"delta\":{\"type\":\"text\",\"text\":\"slow\"},\"event_type\":\"step.delta\"}\n\n" +
        "data: {\"index\":0,\"event_type\":\"step.stop\"}\n\n" +
        "data: {\"interaction\":{\"id\":\"slow\",\"status\":\"completed\",\"usage\":{\"total_input_tokens\":2,\"total_output_tokens\":1}},\"event_type\":\"interaction.completed\"}\n\n"
    val body = ZStream.fromIterable(first.getBytes) ++
      ZStream.fromZIO(ZIO.sleep(100.millis)).drain ++
      ZStream.fromIterable(rest.getBytes)
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(body)
    )

  /** 无限 body 的 finalizer 完成 Promise，用来验证客户端取消真正关闭服务端流。 */
  private def cancelStream(closed: Promise[Nothing, Unit]): Response =
    val first =
      "data: {\"interaction\":{\"id\":\"cancel\",\"status\":\"in_progress\"},\"event_type\":\"interaction.created\"}\n\n"
    val body = (ZStream.fromIterable(first.getBytes) ++ ZStream.fromZIO(ZIO.never).drain)
      .ensuring(closed.succeed(()).unit)
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(body)
    )

  /** 将字符串作为 event-stream 返回。 */
  private def sse(value: String): Response =
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(ZStream.fromIterable(value.getBytes))
    )

  /** 构造含工具结果回填的成功请求，确保契约不是只测第一轮纯文本。 */
  private def request(model: String): ChatRequest =
    ChatRequest(
      Chunk(
        AgentMessage.user("hello"),
        AgentMessage.assistantToolCalls(Chunk(ToolCall("call-1", "lookup", Json.Obj()))),
        AgentMessage.tool("call-1", "lookup", ToolResult(Json.Obj()))
      ),
      settings = ModelSettings(model = Some(model))
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Gemini HTTP ProviderContract 2.0")(
    test("成功、429/500、负 usage、断流、取消、无状态和 cassette 脱敏通过统一门禁") {
      for
        port          <- freePort
        apiKeys       <- Ref.make(Chunk.empty[Boolean])
        revisions     <- Ref.make(Chunk.empty[Boolean])
        stores        <- Ref.make(Chunk.empty[Boolean])
        cancelClosed  <- Promise.make[Nothing, Unit]
        cancelStarted <- Promise.make[Nothing, Unit]
        result        <- (for
          _      <- Server.serve(routes(apiKeys, revisions, stores, cancelClosed)).forkScoped
          _      <- ZIO.sleep(100.millis)
          client <- ZIO.service[Client]
          model = GeminiInteractionsChatModel(
            client,
            GeminiInteractionsConfig(
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
          recorded  <- cassette.entries
          keys      <- apiKeys.get
          revs      <- revisions.get
          stateless <- stores.get
        yield (suite, recorded, keys, revs, stateless)).provideSome[Scope](
          Client.default,
          Server.defaultWithPort(port)
        )
      yield assertTrue(
        result._1.passed,
        result._2.size == 2,
        result._2.forall(_.model == "<redacted>"),
        result._3.forall(identity),
        result._4.forall(identity),
        result._5.forall(identity)
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("慢流在总超时预算内完成并报告 usage") {
      for
        port         <- freePort
        apiKeys      <- Ref.make(Chunk.empty[Boolean])
        revisions    <- Ref.make(Chunk.empty[Boolean])
        stores       <- Ref.make(Chunk.empty[Boolean])
        cancelClosed <- Promise.make[Nothing, Unit]
        response     <- (for
          _      <- Server.serve(routes(apiKeys, revisions, stores, cancelClosed)).forkScoped
          _      <- ZIO.sleep(100.millis)
          client <- ZIO.service[Client]
          model = GeminiInteractionsChatModel(
            client,
            GeminiInteractionsConfig(
              s"http://127.0.0.1:$port/v1",
              "stub-secret",
              "slow-stream",
              requestTimeout = 2.seconds
            )
          )
          events <- model.stream(request("slow-stream")).runCollect
        yield events.collectFirst { case ModelStreamEvent.Completed(value) => value })
          .provideSome[Scope](Client.default, Server.defaultWithPort(port))
      yield assertTrue(response.exists(_.usage == TokenUsage(2, 1)))
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential
  )
