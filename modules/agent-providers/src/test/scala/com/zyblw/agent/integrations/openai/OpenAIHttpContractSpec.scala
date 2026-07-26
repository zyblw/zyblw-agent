package com.zyblw.agent.integrations.openai

import com.zyblw.agent.core.*
import zio.*
import zio.http.*
import zio.stream.*
import zio.test.*

/** 通过真实本机 HTTP socket 验证 OpenAI-compatible Adapter 的传输契约。
  *
  * 纯编解码测试无法发现路径拼接、Authorization、Content-Type、状态码分类和 Body 生命周期错误；本规格用 ZIO HTTP stub server
  * 补齐这些边界，但不访问任何真实厂商，也不需要 API Key。
  */
object OpenAIHttpContractSpec extends ZIOSpecDefault:

  /** 创建记录请求正文的 Provider stub。
    * @param bodies
    *   保存收到的原始 JSON，用于验证 Adapter 真实发送的协议字段
    * @return
    *   与 OpenAI Chat Completions 路径一致的路由；特殊模型名 `rate-limit` 返回 429
    */
  private def routes(
      bodies: Ref[Chunk[String]],
      streamStarted: Promise[Nothing, Unit],
      streamClosed: Promise[Nothing, Unit]
  ): Routes[Any, Response] = Routes(
    Method.POST / "v1" / "chat" / "completions" -> handler { (request: Request) =>
      request.body.asString
        .flatMap { body =>
          val response =
            if body.contains("\"model\":\"rate-limit\"") then
              Response.json("""{"error":{"message":"slow down"}}""").copy(status = Status.TooManyRequests)
            else if body.contains("\"model\":\"server-error\"") then
              Response
                .json("""{"error":{"message":"temporary unavailable"}}""")
                .copy(status = Status.InternalServerError)
            else if body.contains("\"model\":\"invalid-usage\"") then
              Response.json(
                """{"id":"stub-invalid","choices":[{"message":{"content":"bad"},"finish_reason":"stop"}],"usage":{"prompt_tokens":-1,"completion_tokens":3}}"""
              )
            else if body.contains("\"stream\":true") then streamingResponse(body, streamStarted, streamClosed)
            else
              Response.json(
                """{"id":"stub-1","choices":[{"message":{"content":"stub ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":4},"completion_tokens_details":{"reasoning_tokens":2}}}"""
              )
          bodies.update(_ :+ body).as(response)
        }
        .mapError(error => Response.internalServerError(error.getMessage))
    }
  )

  /** 根据模型名返回慢流、断流或无限流。 无限流带 finalizer，用来证明客户端取消会沿 ZIO HTTP Scope 传播并关闭服务端 Body。
    */
  private def streamingResponse(
      body: String,
      streamStarted: Promise[Nothing, Unit],
      streamClosed: Promise[Nothing, Unit]
  ): Response =
    val first =
      "data: {\"id\":\"stream-1\",\"choices\":[{\"delta\":{\"content\":\"first\"},\"finish_reason\":null}],\"usage\":null}\n\n"
    val done                                  = "data: [DONE]\n\n"
    val stream: ZStream[Any, Throwable, Byte] =
      if body.contains("\"model\":\"truncated-stream\"") then ZStream.fromIterable(first.getBytes)
      else if body.contains("\"model\":\"slow-stream\"") then
        ZStream.fromZIO(ZIO.sleep(500.millis)).drain ++ ZStream.fromIterable((first + done).getBytes)
      else if body.contains("\"model\":\"cancel-stream\"") then
        (ZStream.fromZIO(streamStarted.succeed(())).drain ++
          ZStream.fromIterable(first.getBytes) ++ ZStream.fromZIO(ZIO.never).drain)
          .ensuring(streamClosed.succeed(()).unit)
      else ZStream.fromIterable((first + done).getBytes)
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(stream)
    )

  /** 验证成功响应解码以及 429 的可重试分类。 */
  def spec: Spec[TestEnvironment & Scope, Any] = suite("OpenAI-compatible HTTP contract")(
    test("真实 HTTP stub 验证路径、请求模型、usage 解码与 429 分类") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        streamStarted <- Promise.make[Nothing, Unit]
        streamClosed  <- Promise.make[Nothing, Unit]
        result        <- (for
          _      <- TestServer.addRoutes(routes(bodies, streamStarted, streamClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          config = OpenAICompatibleConfig(
            s"http://127.0.0.1:$port/v1",
            "stub-secret",
            "stub-model",
            compatibility = OpenAICompatibility.openAI
          )
          model = OpenAICompatibleChatModel(client, config)
          success <- model.complete(ChatRequest(Chunk(AgentMessage.user("hello"))))
          limited <- model
            .complete(
              ChatRequest(
                Chunk(AgentMessage.user("hello")),
                settings = ModelSettings(model = Some("rate-limit"))
              )
            )
            .exit
          unavailable <- model
            .complete(
              ChatRequest(
                Chunk(AgentMessage.user("hello")),
                settings = ModelSettings(model = Some("server-error"))
              )
            )
            .exit
          invalidUsage <- model
            .complete(
              ChatRequest(
                Chunk(AgentMessage.user("hello")),
                settings = ModelSettings(model = Some("invalid-usage"))
              )
            )
            .exit
          sent <- bodies.get
        yield (success, limited, unavailable, invalidUsage, sent)).provide(
          Client.default,
          TestServer.default
        )
        limitedRetryable = result._2 match
          case Exit.Failure(cause) => cause.failureOption.exists(_.retryable)
          case Exit.Success(_)     => false
        unavailableRetryable = result._3 match
          case Exit.Failure(cause) => cause.failureOption.exists(_.retryable)
          case Exit.Success(_)     => false
      yield assertTrue(
        result._1.message.text == "stub ok",
        result._1.usage == TokenUsage(7, 3, cachedInputTokens = 4, reasoningOutputTokens = 2),
        result._2.isFailure,
        limitedRetryable,
        result._3.isFailure,
        unavailableRetryable,
        result._4.isFailure,
        result._5.length == 4,
        result._5.head.contains("\"model\":\"stub-model\"")
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("断流和慢流超时都形成类型化失败") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        streamStarted <- Promise.make[Nothing, Unit]
        streamClosed  <- Promise.make[Nothing, Unit]
        result        <- (for
          _      <- TestServer.addRoutes(routes(bodies, streamStarted, streamClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          base = OpenAICompatibleConfig(
            s"http://127.0.0.1:$port/v1",
            "stub-secret",
            "stub-model",
            requestTimeout = 100.millis,
            compatibility = OpenAICompatibility.openAI
          )
          truncated <- OpenAICompatibleChatModel(client, base.copy(defaultModel = "truncated-stream"))
            .stream(ChatRequest(Chunk(AgentMessage.user("hello"))))
            .runDrain
            .exit
          slow <- OpenAICompatibleChatModel(client, base.copy(defaultModel = "slow-stream"))
            .stream(ChatRequest(Chunk(AgentMessage.user("hello"))))
            .runDrain
            .exit
        yield (truncated, slow)).provide(Client.default, TestServer.default)
      yield assertTrue(result._1.isFailure, result._2.isFailure)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("消费者取消会关闭独立 HTTP 响应 Body") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        streamStarted <- Promise.make[Nothing, Unit]
        streamClosed  <- Promise.make[Nothing, Unit]
        closed        <- (for
          _      <- TestServer.addRoutes(routes(bodies, streamStarted, streamClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          config = OpenAICompatibleConfig(
            s"http://127.0.0.1:$port/v1",
            "stub-secret",
            "cancel-stream",
            requestTimeout = 10.seconds,
            compatibility = OpenAICompatibility.openAI
          )
          cancelFiber <- OpenAICompatibleChatModel(client, config)
            .stream(ChatRequest(Chunk(AgentMessage.user("hello"))))
            .runDrain
            .fork
          // 取消传播是资源生命周期契约，必须使用独立 Client/Server Scope；若与前面的断流和超时复用
          // 连接池，测试结果会混入旧连接回收速度，而不再只验证当前无限 Body 是否收到中断。
          _ <- streamStarted.await.timeoutFail(new RuntimeException("cancel-stream 未在 10 秒内建立"))(10.seconds)
          _ <- cancelFiber.interrupt
          closed <- streamClosed.await.timeout(10.seconds)
        yield closed).provide(Client.default, TestServer.default)
      yield assertTrue(closed.isDefined)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential
  )
