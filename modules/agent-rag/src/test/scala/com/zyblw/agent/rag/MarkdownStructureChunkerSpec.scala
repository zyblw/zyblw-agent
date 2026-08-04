package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object MarkdownStructureChunkerSpec extends ZIOSpecDefault:

  private val tenant = TenantId("tenant-a")
  private val scope  = Set("knowledge:read")

  private def document(text: String): SourceDocument =
    SourceDocument(
      "guide",
      text,
      "knowledge://guide",
      Map("title" -> "Guide"),
      DocumentRepresentation.Markdown
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MarkdownStructureChunker")(
    test("保留标题层级、行号和表格结构") {
      val markdown =
        """# ZIO
          |
          |概览段落。
          |
          |## Runtime
          |
          || 能力 | 说明 |
          || --- | --- |
          || Fiber | 并发 |
          |""".stripMargin
      for chunks <- MarkdownStructureChunker().split(document(markdown), tenant, scope)
      yield assertTrue(
        chunks.length == 2,
        chunks.head.metadata.get("headingPath").contains("ZIO"),
        chunks(1).metadata.get("headingPath").contains("ZIO > Runtime"),
        chunks(1).text.contains("# ZIO\n## Runtime"),
        chunks(1).text.contains("| 能力 | 说明 |\n| --- | --- |"),
        chunks.forall(
          _.metadata("chunkerId") == "markdown-structure-v1:max=1200:overlap=120:depth=6"
        ),
        chunks.forall(_.metadata("contentFormat") == "markdown")
      )
    },
    test("fenced code 中的井号不是标题") {
      val markdown =
        """# Root
          |
          |```scala
          |# not-a-heading
          |val answer = 42
          |```
          |
          |正文
          |""".stripMargin
      for chunks <- MarkdownStructureChunker().split(document(markdown), tenant, scope)
      yield assertTrue(
        chunks.length == 1,
        chunks.head.metadata.get("headingPath").contains("Root"),
        chunks.head.text.contains("# not-a-heading"),
        !chunks.head.metadata("headingPath").contains("not-a-heading")
      )
    },
    test("超长 Unicode 单行按 code point 有界切分并保留 overlap") {
      val text    = "# Emoji\n\n" + ("诊断🧪" * 100)
      val chunker = MarkdownStructureChunker(
        MarkdownStructureChunkerConfig(maxCharacters = 128, overlapCharacters = 16)
      )
      for chunks <- chunker.split(document(text), tenant, scope)
      yield assertTrue(
        chunks.length > 1,
        chunks.forall(chunk => chunk.text.codePointCount(0, chunk.text.length) <= 128),
        chunks.forall(!_.text.contains('\uFFFD')),
        chunks.map(_.id).distinct.length == chunks.length
      )
    },
    test("前置章节变化不会让后续相同章节的内容寻址 ID 漂移") {
      val before =
        """# First
          |
          |旧内容
          |
          |# Stable
          |
          |不会变化的知识。
          |""".stripMargin
      val after =
        """# First
          |
          |这里插入了大量新内容，但不应改变后续稳定章节的身份。
          |
          |# Stable
          |
          |不会变化的知识。
          |""".stripMargin
      val chunker = MarkdownStructureChunker()
      for
        oldChunks <- chunker.split(document(before), tenant, scope)
        newChunks <- chunker.split(document(after), tenant, scope)
        oldStable = oldChunks.find(_.metadata.get("headingPath").contains("Stable"))
        newStable = newChunks.find(_.metadata.get("headingPath").contains("Stable"))
      yield assertTrue(
        oldStable.nonEmpty,
        newStable.nonEmpty,
        oldStable.map(_.id) == newStable.map(_.id),
        oldStable.map(_.metadata("chunkContentSha")) == newStable.map(_.metadata("chunkContentSha"))
      )
    },
    test("相同标题和正文的重复章节仍获得不同且确定的 ID") {
      val markdown =
        """# Same
          |
          |重复正文
          |
          |# Same
          |
          |重复正文
          |""".stripMargin
      val chunker = MarkdownStructureChunker()
      for
        first  <- chunker.split(document(markdown), tenant, scope)
        second <- chunker.split(document(markdown), tenant, scope)
      yield assertTrue(
        first.length == 2,
        first.map(_.id).distinct.length == 2,
        first.map(_.id) == second.map(_.id)
      )
    },
    test("PDF 页分隔符不进入 embedding 文本，同时生成页码、父级和相邻谱系") {
      val markdown =
        """# 第一章
          |
          |第一页内容。
          |
          |<!-- page -->
          |
          |第二页内容。
          |
          |## 子节
          |
          |第三段内容。
          |""".stripMargin
      val pdf = SourceDocument(
        "pdf-guide",
        markdown,
        "knowledge://pdf-guide",
        Map("pageBreakMarker" -> "<!-- page -->"),
        DocumentRepresentation.Markdown
      )
      for chunks <- MarkdownStructureChunker(
          MarkdownStructureChunkerConfig(maxCharacters = 128, overlapCharacters = 16)
        ).split(pdf, tenant, scope)
      yield assertTrue(
        chunks.length >= 2,
        chunks.forall(!_.text.contains("<!-- page -->")),
        chunks.forall(_.lineage.nonEmpty),
        chunks.head.lineage.exists(_.origins.map(_.pageNumber).contains(1)),
        chunks.exists(_.lineage.exists(_.origins.map(_.pageNumber).contains(2))),
        chunks.head.lineage.flatMap(_.nextChunkId).contains(chunks(1).id),
        chunks(1).lineage.flatMap(_.previousChunkId).contains(chunks.head.id),
        chunks.forall(_.lineage.flatMap(_.parentId).nonEmpty)
      )
    }
  )
