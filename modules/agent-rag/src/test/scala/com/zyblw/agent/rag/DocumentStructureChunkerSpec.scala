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
        chunks.head.text == "第一段\n\n第二段",
        !chunks.head.text.contains("章节："),
        chunks.head.denseText.contains("章节：章"),
        chunks.head.denseText.contains("类型：Paragraph"),
        !chunks.head.text.contains("# 章"),
        chunks(1).text == "另一节",
        chunks(1).denseText.contains("章节：章 > 节"),
        !chunks(1).text.contains("# 章"),
        !chunks(1).text.contains("## 节"),
        chunks.head.lineage.exists(_.headingPath == Chunk("章")),
        chunks(1).lineage.exists(_.headingPath == Chunk("章", "节")),
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
    },
    test("默认 strategyId 带 cl100k token 预算；更换计数器必须改身份") {
      val defaultId = DocumentStructureChunker().strategyId
      val tokenId   = DocumentStructureChunker(
        DocumentStructureChunkerConfig(maxTokens = Some(256), tokenCounter = TokenCounter.CjkApproximate)
      ).strategyId
      assertTrue(
        defaultId.contains("document-structure-v3"),
        defaultId.contains("tokens=512"),
        defaultId.contains("counter=cl100k-base"),
        tokenId.contains("tokens=256"),
        tokenId.contains("counter=cjk-approx-v1")
      )
    },
    test("CJK 近似 token 预算阻止把超长同级段落合并进同一块") {
      val long      = "太阳中风发热汗出恶风脉缓者名为中风。".repeat(20)
      val structure = DocumentStructure(
        "vision-page-markdown",
        Some("v1"),
        Chunk(
          DocumentBlock(
            "#/p/1",
            Some("root"),
            0,
            DocumentBlockKind.Paragraph,
            long,
            Chunk("辨太阳病"),
            Chunk(DocumentOrigin(1))
          ),
          DocumentBlock(
            "#/p/2",
            Some("root"),
            1,
            DocumentBlockKind.Paragraph,
            long,
            Chunk("辨太阳病"),
            Chunk(DocumentOrigin(2))
          )
        )
      )
      val document = SourceDocument(
        "token-doc",
        long,
        "knowledge://token-doc",
        representation = DocumentRepresentation.Markdown,
        structure = Some(structure)
      )
      val chunker = DocumentStructureChunker(
        DocumentStructureChunkerConfig(
          maxCharacters = 8000,
          maxTokens = Some(80),
          tokenCounter = TokenCounter.CjkApproximate
        )
      )
      for chunks <- chunker.split(document, tenant, scope)
      yield assertTrue(chunks.length >= 2)
    },
    test("超长表格按行切开，每段都重复表头") {
      val row       = "| 大椎 | " + "督脉".repeat(28) + " |"
      val table     = "| 穴 | 归经 |\n| --- | --- |\n" + row + "\n" + row
      val structure = DocumentStructure(
        "paddleocr-vl-1.6",
        None,
        Chunk(
          DocumentBlock(
            "p2-b1",
            Some("sec"),
            0,
            DocumentBlockKind.Table,
            table,
            Chunk("经络", "穴位"),
            Chunk(DocumentOrigin(2), DocumentOrigin(3))
          )
        )
      )
      val document = SourceDocument(
        "table-doc",
        table,
        "knowledge://table-doc",
        representation = DocumentRepresentation.Markdown,
        structure = Some(structure)
      )
      val chunker = DocumentStructureChunker(
        DocumentStructureChunkerConfig(maxCharacters = 128, overlapCharacters = 0, maxTokens = None)
      )
      for chunks <- chunker.split(document, tenant, scope)
      yield assertTrue(
        chunks.length >= 2,
        chunks.forall(_.text.contains("| 穴 | 归经 |")),
        chunks.forall(_.text.contains("| --- | --- |")),
        chunks.forall(_.lineage.exists(_.origins.map(_.pageNumber) == Chunk(2, 3)))
      )
    },
    test("表格和方剂不与正文合并，方剂按行切开") {
      val formula   = "桂枝 9g\n白芍 9g\n甘草 6g\n" + ("生姜 9g\n" * 40)
      val structure = DocumentStructure(
        "paddleocr-vl-1.6",
        None,
        Chunk(
          DocumentBlock("p", Some("sec"), 0, DocumentBlockKind.Paragraph, "先煎。", Chunk("桂枝汤")),
          DocumentBlock("f", Some("sec"), 1, DocumentBlockKind.Formula, formula, Chunk("桂枝汤")),
          DocumentBlock("k", Some("sec"), 2, DocumentBlockKind.KeyValue, "煎服：水煎温服", Chunk("桂枝汤"))
        )
      )
      val document = SourceDocument(
        "formula-doc",
        formula,
        "knowledge://formula-doc",
        Map("title" -> "伤寒论"),
        representation = DocumentRepresentation.Markdown,
        structure = Some(structure)
      )
      val chunker = DocumentStructureChunker(
        DocumentStructureChunkerConfig(maxCharacters = 180, overlapCharacters = 0, maxTokens = None)
      )
      for chunks <- chunker.split(document, tenant, scope)
      yield assertTrue(
        chunks.head.text == "先煎。",
        chunks.exists(chunk => chunk.text.contains("桂枝 9g") && chunk.text.contains("白芍 9g")),
        chunks.exists(_.text == "煎服：水煎温服"),
        chunks.filter(_.text.contains("桂枝 9g")).forall { chunk =>
          !chunk.text.contains("先煎") && !chunk.text.contains("煎服") && !chunk.displayText.contains("书名：")
        },
        chunks.filter(_.denseText.contains("类型：Formula")).forall(_.denseText.contains("书名：伤寒论")),
        chunks.filter(_.text.contains("桂枝 9g")).forall(chunk => !chunk.text.contains("桂枝 9g白芍"))
      )
    },
    test("方剂里只有超长的那一行按字切开") {
      val longLine  = "甘草" * 80
      val formula   = s"桂枝 9g\n$longLine\n白芍 9g"
      val structure = DocumentStructure(
        "paddleocr-vl-1.6",
        None,
        Chunk(
          DocumentBlock("f", Some("sec"), 0, DocumentBlockKind.Formula, formula, Chunk("桂枝汤"))
        )
      )
      val document = SourceDocument(
        "formula-split",
        formula,
        "knowledge://formula-split",
        representation = DocumentRepresentation.Markdown,
        structure = Some(structure)
      )
      val chunker = DocumentStructureChunker(
        DocumentStructureChunkerConfig(maxCharacters = 160, overlapCharacters = 0, maxTokens = None)
      )
      for chunks <- chunker.split(document, tenant, scope)
      yield assertTrue(
        chunks.exists(_.text == "桂枝 9g"),
        chunks.exists(_.text == "白芍 9g"),
        chunks.filter(_.text.contains("桂枝")).forall(_.text.contains("桂枝 9g")),
        chunks.filter(_.text.contains("白芍")).forall(_.text.contains("白芍 9g"))
      )
    }
  )
