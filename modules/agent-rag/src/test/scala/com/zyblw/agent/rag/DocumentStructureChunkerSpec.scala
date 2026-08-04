package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object DocumentStructureChunkerSpec extends ZIOSpecDefault:

  private val tenant = TenantId("tenant-a")
  private val scope  = Set("knowledge:read")

  def spec = suite("DocumentStructureChunker")(
    test("合并同父级相邻 block，并保留 bbox、页码、block ID 和阅读顺序") {
      val firstOrigin = DocumentOrigin(
        1,
        Some(DocumentBoundingBox(10, 20, 100, 40, Some(612), Some(792))),
        Some("#/texts/1")
      )
      val secondOrigin = DocumentOrigin(
        2,
        Some(DocumentBoundingBox(12, 30, 120, 55, Some(612), Some(792))),
        Some("#/texts/2")
      )
      val structure = DocumentStructure(
        "DoclingDocument",
        Some("1.8.0"),
        Chunk(
          DocumentBlock(
            "#/texts/1",
            Some("#/groups/0"),
            0,
            DocumentBlockKind.Paragraph,
            "第一段",
            Chunk("章"),
            Chunk(firstOrigin)
          ),
          DocumentBlock(
            "#/texts/2",
            Some("#/groups/0"),
            1,
            DocumentBlockKind.Paragraph,
            "第二段",
            Chunk("章"),
            Chunk(secondOrigin)
          ),
          DocumentBlock(
            "#/texts/3",
            Some("#/groups/1"),
            2,
            DocumentBlockKind.Paragraph,
            "另一节",
            Chunk("章", "节"),
            Chunk(DocumentOrigin(3))
          )
        )
      )
      val document = SourceDocument(
        "doc-1",
        "# 章\n\n第一段\n\n第二段\n\n## 节\n\n另一节",
        "knowledge://doc-1",
        representation = DocumentRepresentation.Markdown,
        structure = Some(structure)
      )
      for chunks <- DocumentStructureChunker().split(document, tenant, scope)
      yield assertTrue(
        chunks.length == 2,
        chunks.head.text.contains("第一段\n\n第二段"),
        chunks.head.lineage.exists(_.blockIds == Chunk("#/texts/1", "#/texts/2")),
        chunks.head.lineage.exists(_.origins == Chunk(firstOrigin, secondOrigin)),
        chunks.head.lineage.exists(_.pageNumbers == Chunk(1, 2)),
        chunks.head.lineage.flatMap(_.nextChunkId).contains(chunks(1).id),
        chunks(1).lineage.flatMap(_.previousChunkId).contains(chunks.head.id)
      )
    },
    test("缺少 structure 时降级到 Markdown 切分且不伪造 bbox") {
      val document = SourceDocument(
        "plain",
        "# 标题\n\n正文",
        "knowledge://plain",
        representation = DocumentRepresentation.Markdown
      )
      for chunks <- DocumentStructureChunker().split(document, tenant, scope)
      yield assertTrue(chunks.nonEmpty, chunks.forall(_.lineage.exists(_.origins.isEmpty)))
    }
  )
