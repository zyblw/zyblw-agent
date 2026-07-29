package com.zyblw.agent.observability.otlp

import com.zyblw.agent.core.*
import com.zyblw.agent.evals.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

/** Langfuse Score 客户端的本机 HTTP 契约与安全投影测试。
  *
  * 测试不访问 Langfuse Cloud，也不使用真实 key；本机 stub 只验证 endpoint、Basic Auth、完整幂等 payload、重试分类、 取消传播和正文不泄漏。Eval 投影另用
  * Recording client 证明不会上传 input、答案和 grade details。
  */
object LangfuseScoreClientSpec extends ZIOSpecDefault:
  /** 测试服务器按 Score name 注入 429、401、大响应和无限 Body，其余请求返回成功。 */
  private def routes(
      requests: Ref[Chunk[(String, String)]],
      attempts: Ref[Int],
      cancelClosed: Promise[Nothing, Unit]
  ): Routes[Any, Response] = Routes(
    Method.POST / "api" / "public" / "scores" -> handler { (request: Request) =>
      request.body.asString
        .flatMap { body =>
          val authorization = request.header(Header.Authorization).map(_.renderedValue).getOrElse("")
          for
            _       <- requests.update(_ :+ (authorization -> body))
            attempt <- attempts.updateAndGet(_ + 1)
          yield
            if body.contains("retry_score") && attempt == 1 then
              Response
                .json("""{"message":"temporary-sensitive-body"}""")
                .copy(status = Status.TooManyRequests)
            else if body.contains("unauthorized_score") then
              Response.json("""{"message":"credential-sensitive-body"}""").copy(status = Status.Unauthorized)
            else if body.contains("oversized_score") then Response.text("x" * 2048)
            else if body.contains("cancel_score") then
              val stream = ZStream.fromZIO(ZIO.never).drain.ensuring(cancelClosed.succeed(()).unit)
              Response(body = Body.fromStreamChunked(stream))
            else Response.json("""{"id":"accepted"}""")
        }
        .mapError(error => Response.internalServerError(error.getClass.getSimpleName))
    }
  )

  /** 为本机 HTTP stub 创建配置；测试明确允许明文 loopback。 */
  private def config(port: Int, maxAttempts: Int = 3, maxResponseBytes: Int = 1024): LangfuseScoresConfig =
    LangfuseScoresConfig(
      host = s"http://127.0.0.1:$port",
      publicKey = "pk-test",
      secretKey = "sk-test-secret",
      requestTimeout = 2.seconds,
      maxResponseBytes = maxResponseBytes,
      maxAttempts = maxAttempts,
      initialBackoff = 1.millis,
      maxBackoff = 2.millis,
      allowInsecureHttp = true
    )

  /** 固定 timestamp 是 Langfuse 幂等覆盖契约的一部分。 */
  private val evaluatedAt = Instant.parse("2026-07-15T00:00:00Z")

  /** 构造 trace 级 Numeric Score。 */
  private def numericScore(name: String, id: String = "score-id"): LangfuseScore =
    LangfuseScore(
      id,
      name,
      evaluatedAt,
      LangfuseScoreTarget.Trace("123e4567-e89b-12d3-a456-426614174000"),
      LangfuseScoreValue.Numeric(0.9)
    )

  def spec = suite("Langfuse Score client")(
    test("POST 完整 Numeric Score 并使用 Basic Auth，配置和回执不泄漏 secret") {
      for
        requests     <- Ref.make(Chunk.empty[(String, String)])
        attempts     <- Ref.make(0)
        cancelClosed <- Promise.make[Nothing, Unit]
        result       <- (for
          port   <- Server.install(routes(requests, attempts, cancelClosed))
          client <- ZIO.service[Client]
          scoreClient = ZioHttpLangfuseScoreClient(client, config(port))
          receipt  <- scoreClient.publish(numericScore("correctness"))
          recorded <- requests.get
        yield receipt -> recorded).provide(Client.default, Server.defaultWith(_.onAnyOpenPort))
        (authorization, body) = result._2.head
        parsed                = body.fromJson[Json].toOption
      yield assertTrue(
        result._1.id == "score-id",
        authorization.startsWith("Basic "),
        !authorization.contains("sk-test-secret"),
        parsed.contains(
          Json.Obj(
            "id"        -> Json.Str("score-id"),
            "name"      -> Json.Str("correctness"),
            "timestamp" -> Json.Str("2026-07-15T00:00:00Z"),
            "dataType"  -> Json.Str("NUMERIC"),
            "value"     -> Json.Num(BigDecimal("0.9")),
            "traceId"   -> Json.Str("123e4567-e89b-12d3-a456-426614174000")
          )
        ),
        !config(0).toString.contains("sk-test-secret")
      )
    },
    test("429 使用完全相同的 id/name/timestamp/body 安全重试，401 不重试且错误不含响应正文") {
      for
        requests     <- Ref.make(Chunk.empty[(String, String)])
        attempts     <- Ref.make(0)
        cancelClosed <- Promise.make[Nothing, Unit]
        result       <- (for
          port   <- Server.install(routes(requests, attempts, cancelClosed))
          client <- ZIO.service[Client]
          scoreClient = ZioHttpLangfuseScoreClient(client, config(port))
          retryReceipt  <- scoreClient.publish(numericScore("retry_score", "retry-id"))
          retryRequests <- requests.get
          _             <- attempts.set(0)
          _             <- requests.set(Chunk.empty)
          unauthorized  <- scoreClient.publish(numericScore("unauthorized_score", "unauthorized-id")).exit
          unauthorizedRequests <- requests.get
        yield (retryReceipt, retryRequests, unauthorized, unauthorizedRequests))
          .provide(Client.default, Server.defaultWith(_.onAnyOpenPort))
        unauthorizedMessage = result._3.causeOption.flatMap(_.failureOption).map(_.message).getOrElse("")
      yield assertTrue(
        result._1.id == "retry-id",
        result._2.size == 2,
        result._2.map(_._2).distinct.size == 1,
        result._4.size == 1,
        result._3.isFailure,
        !unauthorizedMessage.contains("credential-sensitive-body"),
        !unauthorizedMessage.contains("sk-test-secret")
      )
    },
    test("响应大小有硬上限，调用 Fiber 取消会关闭无限 HTTP Body") {
      for
        requests     <- Ref.make(Chunk.empty[(String, String)])
        attempts     <- Ref.make(0)
        cancelClosed <- Promise.make[Nothing, Unit]
        result       <- (for
          port   <- Server.install(routes(requests, attempts, cancelClosed))
          client <- ZIO.service[Client]
          scoreClient = ZioHttpLangfuseScoreClient(
            client,
            config(port, maxAttempts = 1, maxResponseBytes = 32)
          )
          oversized <- scoreClient.publish(numericScore("oversized_score")).exit
          fiber     <- scoreClient.publish(numericScore("cancel_score")).fork
          _         <- ZIO.sleep(50.millis)
          cancelled <- fiber.interrupt
          closed    <- cancelClosed.await.timeout(2.seconds)
        yield (oversized, cancelled, closed)).provide(
          Client.default,
          Server.defaultWith(_.onAnyOpenPort)
        )
      yield assertTrue(result._1.isFailure, result._2.isInterrupted, result._3.isDefined)
    },
    test("默认拒绝自由文本、comment、NaN 和白名单外名称，校验失败前不发送 HTTP") {
      val restrictive = LangfuseScoresConfig(
        "https://langfuse.example",
        "pk",
        "sk",
        allowedScoreNames = Set("approved")
      )
      val text = LangfuseScore(
        "text-id",
        "approved",
        evaluatedAt,
        LangfuseScoreTarget.Trace("trace"),
        LangfuseScoreValue.Text("可能包含病历正文")
      )
      val comment = numericScore("approved").copy(comment = Some("原始评审解释"))
      val nan     = numericScore("approved").copy(value = LangfuseScoreValue.Numeric(Double.NaN))
      val unknown = numericScore("not-approved")
      ZIO.foreach(Chunk(text, comment, nan, unknown))(LangfuseScoreWire.encode(_, restrictive).exit).map {
        exits =>
          // wire 校验发生在 Client.batched 之前；全部失败即证明这些 payload 不可能进入网络层。
          assertTrue(exits.forall(_.isFailure))
      }
    },
    test("Eval 投影使用稳定哈希 ID 和固定低基数名称，不上传 details 或业务用例名") {
      for
        recorded <- Ref.make(Chunk.empty[LangfuseScore])
        client = new LangfuseScoreClient:
          def publish(score: LangfuseScore): IO[AgentError, LangfuseScoreReceipt] =
            recorded.update(_ :+ score).as(LangfuseScoreReceipt(score.id))
        publisher = LangfuseEvalScorePublisher(client)
        runId     = RunId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
        report    = AgentEvalReport(
          "病例-敏感用例名",
          "v1",
          Chunk(
            EvalGrade("tool-selection", passed = true, 1.0, "敏感工具细节"),
            EvalGrade("citation-correctness", passed = false, 0.5, "敏感引用正文"),
            EvalGrade("recovery-correctness", passed = true, 1.0, "内部恢复细节"),
            EvalGrade("resource-budget", passed = true, 1.0, "成本详情")
          )
        )
        _      <- publisher.publishAt(runId, report, evaluatedAt)
        _      <- publisher.publishAt(runId, report, evaluatedAt)
        scores <- recorded.get
        first    = scores.take(5)
        second   = scores.drop(5)
        rendered = scores.toString
      yield assertTrue(
        scores.size == 10,
        first.map(_.id) == second.map(_.id),
        first.map(_.timestamp).forall(_ == evaluatedAt),
        first.map(_.name).toSet == Set(
          "agent_eval_tool_selection",
          "agent_eval_citation_correctness",
          "agent_eval_recovery_correctness",
          "agent_eval_resource_budget",
          "agent_eval_case_passed"
        ),
        first.forall(_.comment.isEmpty),
        !rendered.contains("病例-敏感用例名"),
        !rendered.contains("敏感引用正文")
      )
    },
    test("Context 压缩 Eval 只上传固定六维 Score 和布尔门禁，不上传摘要观测或 details") {
      for
        recorded <- Ref.make(Chunk.empty[LangfuseScore])
        client = new LangfuseScoreClient:
          def publish(score: LangfuseScore): IO[AgentError, LangfuseScoreReceipt] =
            recorded.update(_ :+ score).as(LangfuseScoreReceipt(score.id))
        publisher = LangfuseEvalScorePublisher(client)
        runId     = RunId(UUID.fromString("223e4567-e89b-12d3-a456-426614174000"))
        report    = ContextCompressionEvalReport(
          caseId = "含敏感业务命名的压缩用例",
          datasetVersion = "private-v1",
          attempts = Chunk.empty,
          grades = Chunk(
            EvalGrade("context-compression-completion", passed = true, 1.0, "Provider 原始失败正文"),
            EvalGrade("context-compression-evidence-retention", passed = true, 1.0, "用户关键约束原文"),
            EvalGrade("context-compression-reference-retention", passed = true, 1.0, "私有知识库 URI"),
            EvalGrade("context-compression-forbidden-content", passed = true, 1.0, "提示注入诱饵"),
            EvalGrade("context-compression-stability", passed = true, 1.0, "摘要 SHA 与版本"),
            EvalGrade("context-compression-resource-budget", passed = false, 0.0, "内部模型价格")
          )
        )
        _      <- publisher.publishAt(runId, report, evaluatedAt)
        _      <- publisher.publishAt(runId, report, evaluatedAt)
        scores <- recorded.get
        first    = scores.take(7)
        second   = scores.drop(7)
        rendered = scores.toString
      yield assertTrue(
        scores.length == 14,
        first.map(_.id) == second.map(_.id),
        first.map(_.name).toSet == Set(
          "context_compression_eval_completion",
          "context_compression_eval_evidence_retention",
          "context_compression_eval_reference_retention",
          "context_compression_eval_forbidden_content",
          "context_compression_eval_stability",
          "context_compression_eval_resource_budget",
          "context_compression_eval_case_passed"
        ),
        first.lastOption.exists(_.value == LangfuseScoreValue.BooleanValue(false)),
        first.forall(_.comment.isEmpty),
        !rendered.contains(report.caseId),
        !rendered.contains("用户关键约束原文"),
        !rendered.contains("内部模型价格")
      )
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
