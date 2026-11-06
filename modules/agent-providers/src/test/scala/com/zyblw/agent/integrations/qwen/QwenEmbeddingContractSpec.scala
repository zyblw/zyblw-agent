package com.zyblw.agent.integrations.qwen

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object QwenEmbeddingContractSpec extends ZIOSpecDefault:
  def spec = suite("Qwen embedding contract")(
    test("未启用 sparse 时能力矩阵只声明 Dense，且请求 Sparse 会失败") {
      val context = EmbeddingRequestContext(TenantId("t"), EmbeddingPurpose.Query, "qwen-1")
      ZIO
        .serviceWithZIO[Client] { client =>
          val model =
            QwenEmbeddingModel(client, QwenEmbeddingConfig(apiKey = "test-key", model = "text-embedding-v4"))
          val declared =
            val chunker = DocumentStructureChunker(
              DocumentStructureChunkerConfig(tokenCounter = TokenCounter.CjkApproximate)
            )
            assertTrue(
              model.capabilities.outputs == Set(EmbeddingOutputKind.Dense),
              !model.capabilities.reportsUsage,
              model.capabilities.tokenizerId.contains(TokenCounter.CjkApproximate.id),
              ChunkEmbeddingAlignment.require(chunker.strategyId, model.capabilities).isRight,
              scala.util
                .Try(
                  QwenEmbeddingConfig(
                    apiKey = "test-key",
                    model = "text-embedding-v4",
                    tokenizerId = "test-hash"
                  )
                )
                .isFailure
            )
          model
            .embed(
              EmbeddingRequest(
                Chunk("桂枝汤"),
                EmbeddingInputRole.Query,
                EmbeddingOutputKind.Sparse,
                context = context
              )
            )
            .flip
            .map(error => declared && assertTrue(error.getMessage.contains("不支持 output")))
        }
        .provide(Client.default)
    },
    test("compatible-mode 根地址改写成同一工作空间的原生 /api/v1") {
      assertTrue(
        QwenEmbeddingConfig.nativeApiRoot(
          "https://ws.example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
        ) == "https://ws.example.cn-beijing.maas.aliyuncs.com/api/v1",
        QwenEmbeddingConfig.nativeApiRoot(
          "https://ws.example.cn-beijing.maas.aliyuncs.com/api/v1/"
        ) == "https://ws.example.cn-beijing.maas.aliyuncs.com/api/v1"
      )
    },
    test("超过单次上限时按 text_type 分批") {
      val context = EmbeddingRequestContext(TenantId("t"), EmbeddingPurpose.Indexing, "qwen-batch")
      for
        bodies <- Ref.make(Chunk.empty[String])
        result <- (for
          _      <- TestServer.addRoutes(nativeRoutes(bodies))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          model = QwenEmbeddingModel(
            client,
            QwenEmbeddingConfig(
              apiKey = "test-key",
              model = "qwen3.7-text-embedding",
              dimension = 3,
              baseUrl = Some(s"http://127.0.0.1:$port/api/v1"),
              maxBatchTexts = 2
            )
          )
          embedded <- model.embed(
            EmbeddingRequest(Chunk("甲", "乙", "丙"), EmbeddingInputRole.Query, context = context)
          )
          sent <- bodies.get
        yield (embedded, sent)).provide(Client.default, TestServer.default)
      yield assertTrue(
        result._1.denseEmbeddings.length == 3,
        result._1.denseEmbeddings.forall(_.values.length == 3),
        result._2.length == 2,
        result._2.forall(_.contains("\"text_type\":\"query\"")),
        result._2.head.contains("甲"),
        result._2.last.contains("丙"),
        !result._2.last.contains("甲")
      )
    } @@ TestAspect.sequential,
    test("乱序返回 text_index 时按输入顺序重排，重复或越界索引直接拒绝") {
      val context           = EmbeddingRequestContext(TenantId("t"), EmbeddingPurpose.Indexing, "qwen-order")
      val outOfOrderPayload =
        """{"output":{"embeddings":[{"text_index":2,"embedding":[3.0,0.0,0.0]},{"text_index":0,"embedding":[1.0,0.0,0.0]},{"text_index":1,"embedding":[2.0,0.0,0.0]}]}}"""
      val duplicateIndexPayload =
        """{"output":{"embeddings":[{"text_index":0,"embedding":[1.0,0.0,0.0]},{"text_index":0,"embedding":[2.0,0.0,0.0]}]}}"""
      for
        ordered <- (for
          _      <- TestServer.addRoutes(fixedJsonRoute(outOfOrderPayload))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          model = QwenEmbeddingModel(
            client,
            QwenEmbeddingConfig(
              apiKey = "test-key",
              model = "qwen3.7-text-embedding",
              dimension = 3,
              baseUrl = Some(s"http://127.0.0.1:$port/api/v1"),
              maxBatchTexts = 10
            )
          )
          res <- model.embed(
            EmbeddingRequest(Chunk("甲", "乙", "丙"), EmbeddingInputRole.Document, context = context)
          )
        yield res).provide(Client.default, TestServer.default)
        dupErr <- (for
          _      <- TestServer.addRoutes(fixedJsonRoute(duplicateIndexPayload))
          port   <- ZIO.serviceWithZIO[Server](_.port)
          client <- ZIO.service[Client]
          model = QwenEmbeddingModel(
            client,
            QwenEmbeddingConfig(
              apiKey = "test-key",
              model = "qwen3.7-text-embedding",
              dimension = 3,
              baseUrl = Some(s"http://127.0.0.1:$port/api/v1"),
              maxBatchTexts = 10
            )
          )
          err <- model
            .embed(
              EmbeddingRequest(Chunk("甲", "乙"), EmbeddingInputRole.Document, context = context)
            )
            .flip
        yield err).provide(Client.default, TestServer.default)
      yield assertTrue(
        ordered.denseEmbeddings.map(_.values.head) == Chunk(1.0f, 2.0f, 3.0f),
        dupErr.getMessage.contains("text_index")
      )
    } @@ TestAspect.sequential
  )

  private def fixedJsonRoute(payload: String): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "v1" / "services" / "embeddings" / "text-embedding" / "text-embedding" ->
        handler((_: Request) => ZIO.succeed(Response.json(payload)))
    )

  private def nativeRoutes(bodies: Ref[Chunk[String]]): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "v1" / "services" / "embeddings" / "text-embedding" / "text-embedding" ->
        handler { (request: Request) =>
          request.body.asString
            .flatMap { body =>
              val count = body
                .fromJson[Json]
                .toOption
                .flatMap {
                  case Json.Obj(fields) =>
                    fields
                      .find(_._1 == "input")
                      .map(_._2)
                      .collect { case Json.Obj(input) =>
                        input.find(_._1 == "texts").map(_._2).collect { case Json.Arr(values) =>
                          values.length
                        }
                      }
                      .flatten
                  case _ => None
                }
                .getOrElse(0)
              val vectors = (0 until count)
                .map(index => s"""{"text_index":$index,"embedding":[${index + 1}.0,0.0,0.0]}""")
                .mkString(",")
              bodies.update(_ :+ body).as(Response.json(s"""{"output":{"embeddings":[$vectors]}}"""))
            }
            .orElseFail(Response.internalServerError("body"))
        }
    )
