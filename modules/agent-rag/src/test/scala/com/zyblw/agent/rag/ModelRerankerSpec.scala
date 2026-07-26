package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

/** 验证模型 Reranker 的身份边界、Unicode 截断、稳定排序、故障策略和取消传播。 */
object ModelRerankerSpec extends ZIOSpecDefault:
  private val tenant = TenantId("tenant-a")

  /** 构造已通过检索授权的候选；score 模拟 hybrid RRF 小分数。 */
  private def hit(index: Int, text: String): RetrievalHit = RetrievalHit(
    DocumentChunk(s"chunk-$index", s"doc-$index", text, s"doc://$index", tenant, Set("read")),
    score = 0.03 - index.toDouble / 1000.0,
    signals = Map("vectorRank" -> (index + 1).toDouble)
  )

  private val descriptor = RerankerDescriptor(
    provider = "test-reranker",
    model = "cross-encoder-v1",
    maxCandidates = 3,
    maxQueryCodePoints = 2,
    maxDocumentCodePoints = 3
  )

  def spec = suite("ModelReranker")(
    test("按 code point 截断，乱序响应和同分结果仍按原始 rank 确定性排序") {
      for
        captured <- Ref.make(Option.empty[RerankRequest])
        model = new RerankerModel:
          val descriptor                                                        = ModelRerankerSpec.descriptor
          def score(request: RerankRequest): IO[RetrievalError, RerankResponse] =
            captured
              .set(Some(request))
              .as(
                RerankResponse(
                  Chunk(
                    RerankScore("candidate-1", 0.9),
                    RerankScore("candidate-0", 0.9)
                  )
                )
              )
        reranker = ModelReranker(model, ModelRerankerPolicy(maxCandidates = 3))
        result <- reranker.rerank(
          "甲😀乙",
          Chunk(
            hit(0, "甲😀乙丙"),
            hit(1, "丁戊己庚"),
            hit(2, "辛壬癸")
          ),
          2
        )
        request <- captured.get.someOrFailException
      yield assertTrue(
        request.query == "甲😀",
        request.candidates.map(_.text) == Chunk("甲😀乙", "丁戊己", "辛壬癸"),
        request.topN == 2,
        result.map(_.chunk.id) == Chunk("chunk-0", "chunk-1"),
        result.map(_.score) == Chunk(0.9, 0.9),
        result.head.signals("preRerankScore") == 0.03,
        result(1).signals("rerankRank") == 2.0
      )
    },
    test("未知和重复 candidateId 被 fail-closed 拒绝") {
      val model = new RerankerModel:
        val descriptor                                                        = ModelRerankerSpec.descriptor
        def score(request: RerankRequest): IO[RetrievalError, RerankResponse] =
          ZIO.succeed(
            RerankResponse(
              Chunk(
                RerankScore("candidate-0", 0.8),
                RerankScore("candidate-0", 0.7)
              )
            )
          )
      ModelReranker(model).rerank("查询", Chunk(hit(0, "候选")), 1).exit.map(exit => assertTrue(exit.isFailure))
    },
    test("FailOpen 只对 typed failure 回退原排序，Fiber interruption 继续传播") {
      for
        shouldBlock <- Ref.make(false)
        model = new RerankerModel:
          val descriptor                                                        = ModelRerankerSpec.descriptor
          def score(request: RerankRequest): IO[RetrievalError, RerankResponse] =
            shouldBlock.get.flatMap {
              case false => ZIO.fail(AgentError.RetrievalFailed("临时不可用", retryable = true))
              case true  => ZIO.never
            }
        reranker = ModelReranker(
          model,
          ModelRerankerPolicy(
            timeout = 1.hour,
            failureMode = RerankerFailureMode.FailOpen,
            maxCandidates = 3
          )
        )
        original = Chunk(hit(0, "甲"), hit(1, "乙"))
        fallback <- reranker.rerank("查询", original, 1)
        _        <- shouldBlock.set(true)
        fiber    <- reranker.rerank("查询", original, 1).fork
        exit     <- fiber.interrupt
      yield assertTrue(fallback == original.take(1), exit.isInterrupted)
    }
  )
