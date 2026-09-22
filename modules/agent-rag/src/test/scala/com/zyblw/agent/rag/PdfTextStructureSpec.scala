package com.zyblw.agent.rag

import zio.Chunk
import zio.test.*

object PdfTextStructureSpec extends ZIOSpecDefault:

  private def line(id: String, page: Int, text: String): DocumentBlock =
    DocumentBlock(
      id,
      None,
      0,
      DocumentBlockKind.Paragraph,
      text,
      origins = Chunk(DocumentOrigin(page, blockId = Some(id)))
    )

  def spec: Spec[TestEnvironment, Any] = suite("PdfTextStructure")(
    test("目录点线标题不会变成章节，正文标题会保留层级") {
      val blocks = Chunk(
        line("t1", 2, "第一章 总论 …… 3"),
        line("t2", 2, "第二章 辨证 …… 18"),
        line("t3", 2, "第三章 治法 …… 40"),
        line("t4", 2, "第四章 方剂 …… 66"),
        line("h1", 3, "第一章 总论"),
        line("p1", 3, "太阳之为病，脉浮，头项强痛而恶寒。"),
        line("p2", 3, "此为伤寒论开篇纲领。")
      )
      val normalized = PdfTextStructure.normalize(blocks)
      val outline    = PdfTextStructure.sectionPlan(normalized)
      assertTrue(
        outline.length == 1,
        outline.head.title == "第一章 总论",
        outline.head.pageNumber.contains(3),
        normalized.exists(_.text.contains("太阳之为病")),
        !normalized.exists(_.text.contains("……"))
      )
    },
    test("连续中文碎行会合并成段落") {
      val blocks = Chunk(
        line("a", 1, "伤寒一日太阳受之"),
        line("b", 1, "脉若静者为不传")
      )
      val normalized = PdfTextStructure.normalize(blocks)
      assertTrue(
        normalized.length == 1,
        normalized.head.text == "伤寒一日太阳受之脉若静者为不传"
      )
    },
    test("句末标点后的中文段落不会被粘成一段") {
      val blocks = Chunk(
        line("a", 1, "太阳之为病，脉浮，头项强痛而恶寒。"),
        line("b", 1, "此为伤寒论开篇纲领。")
      )
      val normalized = PdfTextStructure.normalize(blocks)
      assertTrue(
        normalized.length == 2,
        normalized.head.text.contains("恶寒"),
        normalized(1).text.contains("开篇纲领")
      )
    },
    test("结构质量能识别目录噪声和过短段落") {
      val noisy = Chunk(
        line("a", 1, "第一章 …… 1"),
        line("b", 1, "第二章 …… 2"),
        line("c", 1, "短")
      )
      val quality = StructureQuality.assess(PdfTextStructure.normalize(noisy), Some(1))
      assertTrue(!quality.sufficient(StructureQualityPolicy(minMedianParagraphChars = 24)))
    }
  )
