package com.zyblw.agent.integrations.openai

import com.zyblw.agent.rag.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** OpenAI-compatible Embedding Adapter 的真实 HTTP 契约测试。
  *
  * 测试使用本机 ZIO HTTP stub，不访问真实厂商。它刻意让 `data` 乱序，并覆盖分批、认证、usage、429、 维度漂移、超时和取消传播；这些行为仅靠 JSON fixture 无法证明。
  */
object OpenAICompatibleEmbeddingHttpSpec extends ZIOSpecDefault:

  /** 安全读取测试请求中的字符串 input 数组。 */
  private def inputs(body: String): Either[String, Chunk[String]] =
    body.fromJson[Json].flatMap {
      case Json.Obj(fields) =>
        fields.find(_._1 == "input").map(_._2) match
          case Some(Json.Arr(values)) =>
            values.foldLeft[Either[String, Chunk[String]]](Right(Chunk.empty)) {
              case (Right(decoded), Json.Str(value)) => Right(decoded :+ value)
              case (Right(_), _)                     => Left("input 包含非字符串")
              case (failure @ Left(_), _)            => failure
            }
          case _ => Left("缺少 input 数组")
      case _ => Left("请求根节点不是 object")
    }

  /** 根据输入生成乱序 `data`。
    *
    * 向量第一维写文本长度，便于断言逻辑输入顺序；第二维写批内 index，证明 Adapter 按 index 重排而不是按 Provider 返回顺序直接拼接。
    */
  private def successJson(
      values: Chunk[String],
      invalidUsage: Boolean = false,
      badDimension: Boolean = false
  ): String =
    val data = values.indices.reverse
      .map { index =>
        val vector =
          if badDimension then s"[${values(index).length}.0,$index.0]"
          else s"[${values(index).length}.0,$index.0,1.0]"
        s"""{"object":"embedding","index":$index,"embedding":$vector}"""
      }
      .mkString(",")
    val usage =
      if invalidUsage then "{\"prompt_tokens\":-1,\"total_tokens\":1}"
      else s"{\"prompt_tokens\":${values.length},\"total_tokens\":${values.length}}"
    s"""{"id":"embedding-stub-${values.length}","object":"list","data":[$data],"usage":$usage}"""

  /** 创建带 JSON Content-Type 的流式响应 Body，供慢响应和取消传播测试复用。 */
  private def jsonStream(stream: ZStream[Any, Throwable, Byte]): Response =
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(MediaType.application.json)),
      body = Body.fromStreamChunked(stream)
    )

  /** 构造 `/v1/embeddings` stub。
    *
    * @param bodies
    *   保存所有真实请求正文
    * @param authorization
    *   保存认证标头，确认 API Key 不进入 URL/body
    * @param cancelStarted
    *   取消用请求已经到达服务端时完成
    * @param cancelClosed
    *   客户端中断使服务端 Body finalizer 执行时完成
    */
  private def routes(
      bodies: Ref[Chunk[String]],
      authorization: Ref[Chunk[String]],
      cancelStarted: Promise[Nothing, Unit],
      cancelClosed: Promise[Nothing, Unit]
  ): Routes[Any, Response] = Routes(
    Method.POST / "v1" / "embeddings" -> handler { (request: Request) =>
      request.body.asString
        .flatMap { body =>
          val auth     = request.header(Header.Authorization).map(_.renderedValue).getOrElse("")
          val response = inputs(body) match
            case Left(error) => Response.text(error).copy(status = Status.BadRequest)
            case Right(values) if values.contains("rate-limit") =>
              Response.json("""{"error":{"type":"rate_limit_error"}}""").copy(status = Status.TooManyRequests)
            case Right(values) if values.contains("slow") =>
              jsonStream(
                ZStream.fromZIO(ZIO.sleep(500.millis)).drain ++
                  ZStream.fromIterable(successJson(values).getBytes)
              )
            case Right(values) if values.contains("cancel") =>
              val stream =
                ZStream.fromZIO(cancelStarted.succeed(())).drain ++ ZStream.fromZIO(ZIO.never).drain
              jsonStream(stream.ensuring(cancelClosed.succeed(()).unit))
            case Right(values) if values.contains("invalid-usage") =>
              Response.json(successJson(values, invalidUsage = true))
            case Right(values) if values.contains("bad-dimension") =>
              Response.json(successJson(values, badDimension = true))
            case Right(values) => Response.json(successJson(values))
          bodies.update(_ :+ body) *> authorization.update(_ :+ auth) as response
        }
        .mapError(error => Response.internalServerError(error.getMessage))
    }
  )

  /** 创建测试 Adapter；每批最多两条，从而让五条输入稳定拆成三批。 */
  private def service(
      client: Client,
      port: Int,
      timeout: Duration = 2.seconds
  ): OpenAICompatibleEmbeddingService =
    OpenAICompatibleEmbeddingService(
      client,
      OpenAICompatibleEmbeddingConfig(
        providerId = "embedding-stub",
        baseUrl = s"http://127.0.0.1:$port/v1",
        apiKey = "stub-secret",
        model = "stub-embedding",
        dimension = 3,
        maxBatchSize = 2,
        maxParallelBatches = 2,
        maxCharactersPerText = 100,
        maxCharactersPerBatch = 100,
        requestTimeout = timeout
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OpenAI-compatible Embedding HTTP contract")(
    test("分批请求、乱序 data、usage 聚合、维度字段和认证均满足契约") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        authorization <- Ref.make(Chunk.empty[String])
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        result        <- (for
          _      <- TestServer.addRoutes(routes(bodies, authorization, cancelStarted, cancelClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          result <- service(client, port).embedDetailed(Chunk("甲", "乙乙", "丙丙丙", "丁丁丁丁", "戊戊戊戊戊"))
          sent   <- bodies.get
          auth   <- authorization.get
        yield (result, sent, auth)).provide(Client.default, TestServer.default)
        firstDimensions = result._1.embeddings.map(_.values.head)
      yield assertTrue(
        firstDimensions == Chunk(1.0f, 2.0f, 3.0f, 4.0f, 5.0f),
        result._1.usage.contains(EmbeddingUsage(5L, 5L)),
        result._1.providerRequestIds.length == 3,
        result._2.length == 3,
        result._2.forall(_.contains("\"encoding_format\":\"float\"")),
        result._2.forall(_.contains("\"dimensions\":3")),
        result._3.forall(_.contains("stub-secret"))
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("429、非法 usage 和响应维度漂移均被类型化拒绝") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        authorization <- Ref.make(Chunk.empty[String])
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        exits         <- (for
          _      <- TestServer.addRoutes(routes(bodies, authorization, cancelStarted, cancelClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          adapter = service(client, port)
          limited <- adapter.embed(Chunk("rate-limit")).exit
          usage   <- adapter.embed(Chunk("invalid-usage")).exit
          drift   <- adapter.embed(Chunk("bad-dimension")).exit
        yield (limited, usage, drift)).provide(Client.default, TestServer.default)
        limitedRetryable = exits._1 match
          case Exit.Failure(cause) => cause.failureOption.exists(_.retryable)
          case Exit.Success(_)     => false
      yield assertTrue(exits._1.isFailure, limitedRetryable, exits._2.isFailure, exits._3.isFailure)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("慢 Body 超时被类型化拒绝且标记可重试") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        authorization <- Ref.make(Chunk.empty[String])
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        timeout       <- (for
          _      <- TestServer.addRoutes(routes(bodies, authorization, cancelStarted, cancelClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          exit   <- service(client, port, 100.millis).embed(Chunk("slow")).exit
        yield exit).provide(Client.default, TestServer.default)
        retryable = timeout match
          case Exit.Failure(cause) => cause.failureOption.exists(_.retryable)
          case Exit.Success(_)     => false
      yield assertTrue(timeout.isFailure, retryable)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    // 取消传播必须使用自己的 Client：超时场景会在响应写回之前放弃一个在途请求，而被放弃的连接是否留在
    // 连接池里由 Client 的回收时机决定。复用同一个 Client 时，取消请求可能被发到那条连接上并随之丢失，
    // 使断言在服务端从未收到请求的情况下超时。
    test("Fiber 取消会关闭服务端响应流") {
      for
        bodies        <- Ref.make(Chunk.empty[String])
        authorization <- Ref.make(Chunk.empty[String])
        cancelStarted <- Promise.make[Nothing, Unit]
        cancelClosed  <- Promise.make[Nothing, Unit]
        closed        <- (for
          _      <- TestServer.addRoutes(routes(bodies, authorization, cancelStarted, cancelClosed))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          fiber  <- service(client, port, 5.seconds).embed(Chunk("cancel")).fork
          _      <- cancelStarted.await.timeoutFail(new RuntimeException("cancel request did not start"))(
            5.seconds
          )
          _      <- fiber.interrupt
          closed <- cancelClosed.await.timeout(5.seconds)
        yield closed).provide(Client.default, TestServer.default)
      yield assertTrue(closed.isDefined)
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential
  )
