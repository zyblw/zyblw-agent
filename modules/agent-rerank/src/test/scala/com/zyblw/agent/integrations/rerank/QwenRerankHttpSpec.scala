package com.zyblw.agent.integrations.rerank

import com.zyblw.agent.rag.*
import zio.*
import zio.http.*
import zio.test.*

object QwenRerankHttpSpec extends ZIOSpecDefault:
  private def request(query: String): RerankRequest =
    RerankRequest(
      query,
      Chunk(
        RerankCandidate("candidate-0", "第一段", 1, 0.8),
        RerankCandidate("candidate-1", "第二段", 2, 0.7)
      ),
      2
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Qwen Rerank HTTP contract")(
    test("真实 wire 结构按 index 映射候选，不发送业务身份") {
      for
        sent     <- Ref.make("")
        observed <- (for
          _ <- TestServer.addRoutes(
            Routes(
              Method.POST / "api" / "v1" / "services" / "rerank" / "text-rerank" / "text-rerank" -> handler {
                (incoming: Request) =>
                  (incoming.body.asString.flatMap(body => sent.set(body)) *>
                    ZIO.succeed(
                      Response.json(
                        """{"output":{"results":[{"index":1,"relevance_score":0.95},{"index":0,"relevance_score":0.4}]}}"""
                      )
                    ))
                    .mapError(error => Response.internalServerError(error.getClass.getSimpleName))
              }
            )
          )
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          model = QwenRerankModel(
            client,
            QwenRerankConfig("stub-secret", "rerank-stub", s"http://127.0.0.1:$port/api/v1")
          )
          result <- model.score(request("方剂比较"))
          body   <- sent.get
        yield result -> body).provide(Client.default, TestServer.default)
      yield assertTrue(
        observed._1.scores == Chunk(RerankScore("candidate-1", 0.95), RerankScore("candidate-0", 0.4)),
        observed._2.contains("\"documents\":[\"第一段\",\"第二段\"]"),
        !observed._2.contains("candidate-0")
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential,
    test("非法、空白、重复、过大响应与 HTTP 429/500 明确失败") {
      val responses = List(
        """{"output":{"results":[{"index":0,"relevance_score":1.2}]}}""",
        """{"output":{}}""",
        """{"output":{"results":[]}}""",
        """{"output":{"results":[{"index":0,"relevance_score":0.9},{"index":0,"relevance_score":0.8}]}}""",
        """{"output":{"results":[]},"padding":""" + ("x" * (256 * 1024)) + "\"}"
      )
      for
        next  <- Ref.make(0)
        exits <- (for
          _ <- TestServer.addRoutes(
            Routes(
              Method.POST / "api" / "v1" / "services" / "rerank" / "text-rerank" / "text-rerank" -> handler {
                (_: Request) =>
                  next.getAndUpdate(_ + 1).map { index =>
                    if index < responses.length then Response.json(responses(index))
                    else if index == responses.length then
                      Response.json("{}").copy(status = Status.TooManyRequests)
                    else Response.json("{}").copy(status = Status.InternalServerError)
                  }
              }
            )
          )
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          model = QwenRerankModel(
            client,
            QwenRerankConfig("stub-secret", "rerank-stub", s"http://127.0.0.1:$port/api/v1")
          )
          badScore    <- model.score(request("one")).exit
          missing     <- model.score(request("two")).exit
          empty       <- model.score(request("three")).exit
          duplicate   <- model.score(request("four")).exit
          oversized   <- model.score(request("five")).exit
          rateLimited <- model.score(request("six")).exit
          serverError <- model.score(request("seven")).exit
        yield (badScore, missing, empty, duplicate, oversized, rateLimited, serverError)).provide(
          Client.default,
          TestServer.default
        )
      yield assertTrue(
        exits._1.isFailure,
        exits._2.isFailure,
        exits._3.isFailure,
        exits._4.isFailure,
        exits._5.isFailure,
        exits._6.causeOption.flatMap(_.failureOption).exists(_.retryable),
        exits._7.causeOption.flatMap(_.failureOption).exists(_.retryable)
      )
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential
  )
