package com.zyblw.agent.examples.knowledge

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import zio.*

/** 无网络可运行结构检索闭环；生产替换两个 Store 和 EmbeddingModel，不更换检索流水线。 */
object StructuralRagExample extends ZIOAppDefault:
  def run = for
    index      <- InMemoryKnowledgeIndexStore.make
    structures <- InMemoryStructureStore.make(index)
    section = DocumentSection("chapter-1", None, 0, 1, "安装指南", Some(1), Some(1))
    block   = DocumentBlock(
      "paragraph-1",
      Some(section.id),
      0,
      DocumentBlockKind.Paragraph,
      "安装之前需要创建独立数据库。",
      Chunk(section.title),
      Chunk(DocumentOrigin(1))
    )
    document = SourceDocument(
      "manual",
      block.text,
      "book://manual/installation",
      structure = Some(DocumentStructure("example", Some("1"), Chunk(block), Chunk(section)))
    )
    model   = EmbeddingModel.stub()
    indexer = KnowledgeIndexer(
      DocumentStructureChunker(),
      model,
      index,
      structureIndexing = Some(StructureIndexing(structures, StructurePublicationPolicy.Required))
    )
    published <- indexer.index(document, TenantId("demo"), Set("read"), "manual-v1")
    reranker = new Reranker:
      def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int) = ZIO.succeed(hits.take(limit))
    retriever = DefaultRetriever(model, index, reranker, structural = Some(StructuralRetrieval(structures)))
    result <- retriever.retrieve(
      RetrievalRequest(
        "安装数据库",
        RetrievalScope(TenantId("demo"), Set("read")),
        3,
        recipe = Some(RetrievalRecipe.BookGrounded)
      )
    )
    _ <- ZIO
      .fail(new IllegalStateException("Missing original evidence"))
      .unless(result.hits.nonEmpty && result.citations.nonEmpty)
    _ <- Console.printLine(s"structure=${published.structureStatus}; citations=${result.citations.length}")
    _ <- Console.printLine(result.hits.map(_.chunk.displayText).mkString("\n"))
  yield ()
