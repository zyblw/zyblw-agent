package com.zyblw.agent.integrations.openai

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import zio.*
import zio.http.*
import zio.stream.*
import zio.test.*

/** OpenAI Responses Adapter 的真实本机 HTTP 契约测试。
  *
  * Wire 单测无法发现 URL 拼接、认证标头、HTTP 状态分类、SSE Body Scope 和取消传播问题，因此这里 使用 ZIO HTTP stub server
  * 验证完整传输链路，同时不消耗真实模型额度。
  */
object OpenAIResponsesHttpContractSpec extends ZIOSpecDefault:
  /** 构造 `/v1/responses` stub 路由。
    *
    * @param bodies
    *   记录收到的请求 JSON，供测试验证 store 与 stream 字段
    * @param authorization
    *   记录 Authorization 标头，确保密钥只通过 header 发送
    * @param streamClosed
    *   无限流关闭时完成的 Promise，用于证明消费者取消传播到服务端 Body
    */
  private def routes(
      bodies: Ref[Chunk[String]],
      authorization: Ref[Chunk[String]],
      streamClosed: Promise[Nothing, Unit]
  ): Routes[Any, Response] = Routes(
    Method.POST / "v1" / "responses" -> handler { (request: Request) =>
      request.body.asString
        .flatMap { body =>
          val auth     = request.header(Header.Authorization).map(_.renderedValue).getOrElse("")
          val response =
            if body.contains("\"model\":\"rate-limit\"") then
              Response.json("""{"error":{"message":"slow down"}}""").copy(status = Status.TooManyRequests)
            else if body.contains("\"model\":\"cancel-stream\"") then cancelStream(streamClosed)
            else if body.contains("\"stream\":true") then normalStream
            else
              Response.json(
                """{"id":"resp-http","status":"completed","output":[{"id":"msg-http","type":"message","role":"assistant","content":[{"type":"output_text","text":"stub ok","annotations":[]}]}],"usage":{"input_tokens":5,"output_tokens":2}}"""
              )
          bodies.update(_ :+ body) *> authorization.update(_ :+ auth) as response
        }
        .mapError(error => Response.internalServerError(error.getMessage))
    }
  )

  /** 返回一个正常结束的 Responses typed SSE。 */
  private def normalStream: Response =
    val payload =
      """data: {"type":"response.created","response":{"id":"resp-stream-http","status":"in_progress","output":[]}}
        |
        |data: {"type":"response.output_text.delta","response_id":"resp-stream-http","delta":"stub stream"}
        |
        |data: {"type":"response.completed","response":{"id":"resp-stream-http","status":"completed","output":[{"id":"msg","type":"message","role":"assistant","content":[{"type":"output_text","text":"stub stream","annotations":[]}]}],"usage":{"input_tokens":6,"output_tokens":3}}}
        |
        |data: [DONE]
        |
        |""".stripMargin
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(ZStream.fromIterable(payload.getBytes))
    )

  /** 返回首个事件后永不结束的流；Scope 关闭时通过 Promise 暴露 finalizer 已执行。 */
  private def cancelStream(streamClosed: Promise[Nothing, Unit]): Response =
    val first =
      "data: {\"type\":\"response.output_text.delta\",\"response_id\":\"cancel\",\"delta\":\"first\"}\n\n"
    val body = (ZStream.fromIterable(first.getBytes) ++ ZStream.fromZIO(ZIO.never).drain)
      .ensuring(streamClosed.succeed(()).unit)
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.text.`event-stream`)),
      body = Body.fromStreamChunked(body)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OpenAI Responses HTTP contract")(
    test("验证原生路径、认证、store=false、完整响应、typed SSE 与 429 分类") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        authorization <- Ref.make(Chunk.empty[String])
        streamClosed  <- Promise.make[Nothing, Unit]
        result        <- (for
          _      <- TestServer.addRoutes(routes(bodies, authorization, streamClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          config = OpenAIResponsesConfig(s"http://127.0.0.1:$port/v1", "stub-secret", "stub-model")
          model  = OpenAIResponsesChatModel(client, config)
          complete <- model.complete(ChatRequest(Chunk(AgentMessage.user("hello"))))
          streamed <- model.stream(ChatRequest(Chunk(AgentMessage.user("hello")))).runCollect
          limited  <- model
            .complete(
              ChatRequest(
                Chunk(AgentMessage.user("hello")),
                settings = ModelSettings(model = Some("rate-limit"))
              )
            )
            .exit
          sent <- bodies.get
          auth <- authorization.get
        yield (complete, streamed, limited, sent, auth)).provide(
          Client.default,
          TestServer.default
        )
        retryable = result._3 match
          case Exit.Failure(cause) => cause.failureOption.exists(_.retryable)
          case Exit.Success(_)     => false
        streamCompleted = result._2.collectFirst { case ModelStreamEvent.Completed(response) => response }
      yield assertTrue(
        result._1.message.text == "stub ok",
        result._1.usage == TokenUsage(5, 2),
        streamCompleted.exists(_.message.text == "stub stream"),
        streamCompleted.exists(_.usage == TokenUsage(6, 3)),
        result._3.isFailure,
        retryable,
        result._4.length == 3,
        result._4.head.contains("\"store\":false"),
        result._4(1).contains("\"stream\":true"),
        result._5.forall(_.contains("stub-secret"))
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("消费者取消会关闭 Responses HTTP Body，而不是遗留后台连接") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        authorization <- Ref.make(Chunk.empty[String])
        streamClosed  <- Promise.make[Nothing, Unit]
        closed        <- (for
          _      <- TestServer.addRoutes(routes(bodies, authorization, streamClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          config = OpenAIResponsesConfig(
            s"http://127.0.0.1:$port/v1",
            "stub-secret",
            "cancel-stream",
            requestTimeout = 5.seconds
          )
          firstEvent <- Promise.make[Nothing, Unit]
          fiber      <- OpenAIResponsesChatModel(client, config)
            .stream(ChatRequest(Chunk(AgentMessage.user("hello"))))
            .tap(_ => firstEvent.succeed(()).unit)
            .runDrain
            .fork
          _      <- firstEvent.await.timeoutFail(new RuntimeException("stub stream did not start"))(2.seconds)
          _      <- fiber.interrupt
          closed <- streamClosed.await.timeout(2.seconds)
        yield closed).provide(Client.default, TestServer.default)
      yield assertTrue(closed.isDefined)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential
  )
