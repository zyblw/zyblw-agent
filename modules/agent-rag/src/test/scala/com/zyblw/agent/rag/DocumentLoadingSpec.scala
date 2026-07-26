package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*
import zio.test.*

/** 验证文档加载信任边界与批量摄取并发语义。
  *
  * 这些测试不依赖具体 PDF/HTML 解析器；它们约束所有未来 Loader 都必须遵守的框架协议：MIME 所有权唯一、 输入身份不可漂移、业务 metadata 优先、并行结果确定性、失败隔离以及取消传播。
  */
object DocumentLoadingSpec extends ZIOSpecDefault:

  /** 建立一个固定两维向量的测试服务，使测试只关注 Loader 与 Indexer 的组合协议。 */
  private val embeddings: EmbeddingService = new EmbeddingService:
    val dimension: Int                                   = 2
    override val descriptor: EmbeddingProviderDescriptor =
      EmbeddingProviderDescriptor("document-loading-test", "v1", 2, 100, supportsDimensions = false)

    /** 输出与输入严格同序同数量，避免把 Provider 行为混入本套测试。 */
    def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
      ZIO.succeed(texts.map(text => Embedding(Chunk(text.length.toFloat, 1.0f))))

  /** 用 UTF-8 小文本创建输入，便于每个测试明确控制 ID、MIME 和可信 metadata。 */
  private def input(
      id: String,
      mediaType: String = "text/plain",
      metadata: Map[String, String] = Map.empty
  ): DocumentInput = DocumentInput.fromBytes(
    id,
    s"knowledge://$id",
    s"$id.txt",
    mediaType,
    Chunk.fromArray(s"正文-$id".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
    metadata
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Document loading")(
    test("同一 MIME 只能有一个确定实现") {
      val first = new DocumentLoader:
        val id                         = "first"
        val supportedMediaTypes        = Set("text/plain")
        def load(input: DocumentInput) = ZIO.succeed(SourceDocument(input.id, "first", input.sourceUri))
      val second = new DocumentLoader:
        val id                         = "second"
        val supportedMediaTypes        = Set("text/plain")
        def load(input: DocumentInput) = ZIO.succeed(SourceDocument(input.id, "second", input.sourceUri))
      DocumentLoaderRegistry.make(Chunk(first, second)).exit.map(result => assertTrue(result.isFailure))
    },
    test("Loader 不能改变身份，解析 metadata 不能覆盖业务可信字段") {
      val drifting = new DocumentLoader:
        val id                         = "drifting"
        val supportedMediaTypes        = Set("text/plain")
        def load(input: DocumentInput) =
          ZIO.succeed(SourceDocument("another-id", "正文", input.sourceUri, Map("title" -> "解析标题")))
      val conforming = new DocumentLoader:
        val id                         = "conforming"
        val supportedMediaTypes        = Set("text/markdown")
        def load(input: DocumentInput) =
          ZIO.succeed(
            SourceDocument(input.id, "正文", input.sourceUri, Map("title" -> "解析标题", "author" -> "作者"))
          )
      for
        registry <- DocumentLoaderRegistry.make(Chunk(drifting, conforming))
        drift    <- registry.load(input("drift")).exit
        loaded   <- registry.load(input("valid", "text/markdown", Map("title" -> "业务标题")))
      yield assertTrue(
        drift.isFailure,
        loaded.metadata("title") == "业务标题",
        loaded.metadata("author") == "作者",
        loaded.metadata("loaderId") == "conforming",
        loaded.metadata("contentTrust") == "untrusted"
      )
    },
    test("并行摄取即使完成先后不同，输出仍按输入顺序且 Continue 隔离单文档失败") {
      for
        fastCompleted <- Promise.make[Nothing, Unit]
        loader = new DocumentLoader:
          val id                                                             = "ordered"
          val supportedMediaTypes                                            = Set("text/plain")
          def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
            input.id match
              case "slow" => fastCompleted.await.as(SourceDocument(input.id, "slow body", input.sourceUri))
              case "fast" =>
                fastCompleted.succeed(()).as(SourceDocument(input.id, "fast body", input.sourceUri))
              case _ => ZIO.fail(AgentError.RetrievalFailed("测试解析失败"))
        registry <- DocumentLoaderRegistry.make(Chunk(loader))
        store    <- InMemoryKnowledgeIndexStore.make
        service = DocumentIngestionService(
          registry,
          KnowledgeIndexer(SlidingWindowChunker(100, 0), embeddings, store),
          maxParallelism = 3,
          failureMode = DocumentIngestionFailureMode.Continue
        )
        requests = Chunk("slow", "fast", "broken").map(id =>
          DocumentIngestionRequest(input(id), TenantId("tenant-a"), Set("knowledge:read"), s"ingest-$id")
        )
        outcomes <- service.ingest(ZStream.fromChunk(requests)).runCollect
      yield assertTrue(
        outcomes.map {
          case DocumentIngestionOutcome.Indexed(id, _)   => id
          case DocumentIngestionOutcome.Failed(id, _, _) => id
        } == Chunk("slow", "fast", "broken"),
        outcomes(2).isInstanceOf[DocumentIngestionOutcome.Failed]
      )
    },
    test("取消消费流会中断尚未完成的 Loader Fiber") {
      for
        started     <- Promise.make[Nothing, Unit]
        interrupted <- Promise.make[Nothing, Unit]
        loader = new DocumentLoader:
          val id                                                             = "cancellable"
          val supportedMediaTypes                                            = Set("text/plain")
          def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
            (started.succeed(()) *> ZIO.never)
              .onInterrupt(interrupted.succeed(()).unit)
        registry <- DocumentLoaderRegistry.make(Chunk(loader))
        store    <- InMemoryKnowledgeIndexStore.make
        service = DocumentIngestionService(
          registry,
          KnowledgeIndexer(SlidingWindowChunker(100, 0), embeddings, store),
          maxParallelism = 1
        )
        fiber <- service
          .ingest(
            ZStream.succeed(
              DocumentIngestionRequest(
                input("cancel"),
                TenantId("tenant-a"),
                Set("read"),
                "ingest-cancel"
              )
            )
          )
          .runHead
          .fork
        _          <- started.await
        _          <- fiber.interrupt
        propagated <- interrupted.isDone
      yield assertTrue(propagated)
    },
    test("FailFast 保留类型化错误，不把失败伪装成普通结果") {
      val loader = new DocumentLoader:
        val id                         = "fail-fast"
        val supportedMediaTypes        = Set("text/plain")
        def load(input: DocumentInput) = ZIO.fail(AgentError.RetrievalFailed("预期失败"))
      for
        registry <- DocumentLoaderRegistry.make(Chunk(loader))
        store    <- InMemoryKnowledgeIndexStore.make
        service = DocumentIngestionService(
          registry,
          KnowledgeIndexer(SlidingWindowChunker(100, 0), embeddings, store),
          failureMode = DocumentIngestionFailureMode.FailFast
        )
        result <- service
          .ingest(
            ZStream.succeed(
              DocumentIngestionRequest(
                input("failed"),
                TenantId("tenant-a"),
                Set("read"),
                "ingest-failed"
              )
            )
          )
          .runCollect
          .exit
      yield assertTrue(result.isFailure)
    }
  )
