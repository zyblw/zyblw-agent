package com.zyblw.agent.integrations.rerank

import com.zyblw.agent.rag.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** Cohere v2 Adapter 的真实 ZIO HTTP stub 契约。
  *
  * 覆盖 wire schema、Bearer、index 映射、计费单元、低敏失败、429 重试、响应上限、总超时和取消传播；不需要真实 API Key。
  */
object CohereRerankHttpSpec extends ZIOSpecDefault:

  /** 从测试请求 JSON 读取 query；解析失败返回固定标记，不把正文写入测试日志。 */
  private def queryOf(body: String): String =
    body
      .fromJson[Json]
      .toOption
      .flatMap {
        case Json.Obj(fields) =>
          fields.find(_._1 == "query").map(_._2).collect { case Json.Str(value) => value }
        case _ => None
      }
      .getOrElse("invalid")

  /** 构造 JSON 流响应，供取消和慢 Body 测试复用。 */
  private def jsonStream(stream: ZStream[Any, Throwable, Byte]): Response = Response(
    status = Status.Ok,
    headers = Headers(Header.ContentType(MediaType.application.json)),
    body = Body.fromStreamChunked(stream)
  )

  /** 单一 `/v2/rerank` stub 根据 query 触发不同协议路径。
    *
    * @param bodies
    *   保存请求 JSON，用于验证重试正文完全一致且没有业务 ID
    * @param auth
    *   保存 Authorization，确认 secret 不进入 body
    * @param clientNames
    *   保存低基数客户端标识
    * @param rateAttempts
    *   429 场景的服务端观察次数
    * @param cancelStarted
    *   无限 Body 已开始
    * @param cancelClosed
    *   客户端中断后服务端 Body finalizer 已执行
    */
  private def routes(
      bodies: Ref[Chunk[String]],
      auth: Ref[Chunk[String]],
      clientNames: Ref[Chunk[String]],
      rateAttempts: Ref[Int],
      cancelStarted: Promise[Nothing, Unit],
      cancelClosed: Promise[Nothing, Unit]
  ): Routes[Any, Response] = Routes(
    Method.POST / "v2" / "rerank" -> handler { (request: Request) =>
      request.body.asString
        .flatMap { body =>
          val query      = queryOf(body)
          val bearer     = request.header(Header.Authorization).map(_.renderedValue).getOrElse("")
          val clientName = request.rawHeader("X-Client-Name").getOrElse("")
          val responseEffect: UIO[Response] = query match
            case "rate-limit" =>
              rateAttempts.updateAndGet(_ + 1).map { attempt =>
                if attempt < 3 then
                  Response.json("""{"message":"temporary"}""").copy(status = Status.TooManyRequests)
                else Response.json(successJson)
              }
            case "unauthorized" =>
              ZIO.succeed(
                Response
                  .json("""{"message":"secret echoed by provider"}""")
                  .copy(status = Status.Unauthorized)
              )
            case "duplicate-index" =>
              ZIO.succeed(
                Response.json(
                  """{"results":[{"index":0,"relevance_score":0.9},{"index":0,"relevance_score":0.8}]}"""
                )
              )
            case "bad-score" =>
              ZIO.succeed(Response.json("""{"results":[{"index":0,"relevance_score":1.2}]}"""))
            case "bad-billing" =>
              ZIO.succeed(
                Response.json(
                  """{"results":[{"index":0,"relevance_score":0.9}],"meta":{"billed_units":{"search_units":-1}}}"""
                )
              )
            case "oversized" =>
              ZIO.succeed(Response.json(s"""{"results":[],"padding":"${"x" * 10000}"}"""))
            case "cancel" =>
              val stream =
                ZStream.fromZIO(cancelStarted.succeed(())).drain ++ ZStream.fromZIO(ZIO.never).drain
              ZIO.succeed(jsonStream(stream.ensuring(cancelClosed.succeed(()).unit)))
            case "slow" =>
              ZIO.succeed(
                jsonStream(
                  ZStream.fromZIO(ZIO.sleep(500.millis)).drain ++ ZStream.fromIterable(successJson.getBytes)
                )
              )
            case _ => ZIO.succeed(Response.json(successJson))
          bodies.update(_ :+ body) *>
            auth.update(_ :+ bearer) *>
            clientNames.update(_ :+ clientName) *>
            responseEffect
        }
        .mapError(error => Response.internalServerError(error.getClass.getSimpleName))
    }
  )

  /** 成功响应故意按 1、0 返回，验证 wire index 与 candidate ID 的确定映射。 */
  private val successJson =
    """{"id":"rerank-request-1","results":[{"index":1,"relevance_score":0.95},{"index":0,"relevance_score":0.40}],"meta":{"billed_units":{"search_units":1}}}"""

  /** 创建本机 Adapter；只有测试显式允许 HTTP。 */
  private def model(
      client: Client,
      port: Int,
      timeout: Duration = 2.seconds,
      responseLimit: Int = 4096,
      attempts: Int = 3
  ): CohereRerankModel = CohereRerankModel(
    client,
    CohereRerankConfig(
      baseUrl = s"http://127.0.0.1:$port",
      apiKey = "cohere-stub-secret",
      model = "rerank-stub-v1",
      maxCandidates = 10,
      maxQueryCodePoints = 100,
      maxDocumentCodePoints = 100,
      maxTokensPerDocument = 128,
      requestTimeout = timeout,
      maxResponseBytes = responseLimit,
      maxAttempts = attempts,
      initialBackoff = 10.millis,
      maxBackoff = 20.millis,
      clientName = Some("zyblw-test"),
      allowInsecureHttp = true
    )
  )

  /** 建立两个不含业务 document ID 的临时候选。 */
  private def request(query: String): RerankRequest = RerankRequest(
    query,
    Chunk(
      RerankCandidate("candidate-0", "first evidence", 1, 0.8),
      RerankCandidate("candidate-1", "second evidence", 2, 0.7)
    ),
    topN = 2
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Cohere v2 Rerank HTTP contract")(
    test("wire schema、Bearer、index 映射、request ID 与 search units 完整") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        auth          <- Ref.make(Chunk.empty[String])
        clientNames   <- Ref.make(Chunk.empty[String])
        rateAttempts  <- Ref.make(0)
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        observed      <- (for
          _ <- TestServer.addRoutes(
            routes(bodies, auth, clientNames, rateAttempts, cancelStarted, cancelClosed)
          )
          port    <- ZIO.serviceWithZIO[Server](_.port)
          client  <- ZIO.service[Client]
          result  <- model(client, port).score(request("normal"))
          sent    <- bodies.get
          headers <- auth.get.zip(clientNames.get)
        yield (result, sent, headers)).provide(Client.default, TestServer.default)
        (result, sent, (authorizations, names)) = observed
      yield assertTrue(
        result.scores == Chunk(RerankScore("candidate-1", 0.95), RerankScore("candidate-0", 0.40)),
        result.providerRequestId.contains("rerank-request-1"),
        result.usage.isEmpty,
        result.billing.contains(RerankBilling(1L)),
        sent.head.contains("\"documents\":[\"first evidence\",\"second evidence\"]"),
        sent.head.contains("\"top_n\":2"),
        sent.head.contains("\"max_tokens_per_doc\":128"),
        !sent.head.contains("candidate-0"),
        authorizations.head.contains("cohere-stub-secret"),
        names == Chunk("zyblw-test")
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("429 只做有界重试且每次复用完全相同正文") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        auth          <- Ref.make(Chunk.empty[String])
        clientNames   <- Ref.make(Chunk.empty[String])
        rateAttempts  <- Ref.make(0)
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        observed      <- (for
          _ <- TestServer.addRoutes(
            routes(bodies, auth, clientNames, rateAttempts, cancelStarted, cancelClosed)
          )
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          result <- model(client, port).score(request("rate-limit"))
          sent   <- bodies.get
          count  <- rateAttempts.get
        yield (result, sent, count)).provide(Client.default, TestServer.default)
      yield assertTrue(
        observed._1.scores.nonEmpty,
        observed._3 == 3,
        observed._2.length == 3,
        observed._2.distinct.length == 1
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("401 不重试且错误不包含 Provider 正文或 API Key") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        auth          <- Ref.make(Chunk.empty[String])
        clientNames   <- Ref.make(Chunk.empty[String])
        rateAttempts  <- Ref.make(0)
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        observed      <- (for
          _ <- TestServer.addRoutes(
            routes(bodies, auth, clientNames, rateAttempts, cancelStarted, cancelClosed)
          )
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          exit   <- model(client, port).score(request("unauthorized")).exit
          sent   <- bodies.get
        yield (exit, sent)).provide(Client.default, TestServer.default)
        message = observed._1.causeOption.flatMap(_.failureOption).map(_.message).getOrElse("")
      yield assertTrue(
        observed._1.isFailure,
        observed._2.length == 1,
        !message.contains("secret echoed"),
        !message.contains("cohere-stub-secret")
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("重复 index、越界分数、非法 billing 与超大响应均 fail-closed") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        auth          <- Ref.make(Chunk.empty[String])
        clientNames   <- Ref.make(Chunk.empty[String])
        rateAttempts  <- Ref.make(0)
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        exits         <- (for
          _ <- TestServer.addRoutes(
            routes(bodies, auth, clientNames, rateAttempts, cancelStarted, cancelClosed)
          )
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          adapter = model(client, port, responseLimit = 512, attempts = 1)
          duplicate <- adapter.score(request("duplicate-index")).exit
          score     <- adapter.score(request("bad-score")).exit
          billing   <- adapter.score(request("bad-billing")).exit
          oversized <- adapter.score(request("oversized")).exit
        yield (duplicate, score, billing, oversized)).provide(Client.default, TestServer.default)
      yield assertTrue(exits._1.isFailure, exits._2.isFailure, exits._3.isFailure, exits._4.isFailure)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("总超时形成可重试错误") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        auth          <- Ref.make(Chunk.empty[String])
        clientNames   <- Ref.make(Chunk.empty[String])
        rateAttempts  <- Ref.make(0)
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        observed      <- (for
          _ <- TestServer.addRoutes(
            routes(bodies, auth, clientNames, rateAttempts, cancelStarted, cancelClosed)
          )
          port    <- ZIO.serviceWithZIO[Server](_.port)
          client  <- ZIO.service[Client]
          timeout <- model(client, port, timeout = 100.millis, attempts = 1).score(request("slow")).exit
        yield timeout).provide(Client.default, TestServer.default)
        retryable = observed.causeOption.flatMap(_.failureOption).exists(_.retryable)
      yield assertTrue(observed.isFailure, retryable)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("调用 Fiber 取消会关闭独立服务端 Body") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        auth          <- Ref.make(Chunk.empty[String])
        clientNames   <- Ref.make(Chunk.empty[String])
        rateAttempts  <- Ref.make(0)
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        closed        <- (for
          _ <- TestServer.addRoutes(
            routes(bodies, auth, clientNames, rateAttempts, cancelStarted, cancelClosed)
          )
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          // 与“慢 Body 总超时”分离 Client、连接池和 Server Scope，确保本用例只测当前请求的取消传播。
          fiber <- model(client, port, timeout = 10.seconds, attempts = 1).score(request("cancel")).fork
          _ <- cancelStarted.await.timeoutFail(new RuntimeException("cancel body did not start"))(10.seconds)
          _ <- fiber.interrupt
          closed <- cancelClosed.await.timeout(10.seconds)
        yield closed).provide(Client.default, TestServer.default)
      yield assertTrue(closed.isDefined)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("生产配置拒绝明文 HTTP，toString 始终脱敏") {
      for
        client <- ZIO.service[Client]
        config = CohereRerankConfig(
          baseUrl = "http://127.0.0.1:1",
          apiKey = "must-not-leak",
          maxAttempts = 1
        )
        result <- CohereRerankModel(client, config).score(request("normal")).exit
      yield assertTrue(result.isFailure, !config.toString.contains("must-not-leak"))
    }.provide(Client.default)
  )
