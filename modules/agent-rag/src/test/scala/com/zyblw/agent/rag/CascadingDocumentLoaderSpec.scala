package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*
import zio.test.*

object ExtractionQualitySpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ExtractionQuality")(
    test("中文正文达到脚本密度门槛") {
      val quality = ExtractionQuality.assess("伤寒论曰太阳之为病脉浮头项强痛而恶寒。".repeat(3))
      assertTrue(quality.sufficient(ExtractionQualityPolicy()), quality.cidArtifacts == 0)
    },
    test("CID 伪影和空白扫描件不能当作可索引正文") {
      val cid    = ExtractionQuality.assess("(cid:12)(cid:18)(cid:32)".repeat(20))
      val blank  = ExtractionQuality.assess("\n\n   \n")
      val sparse = ExtractionQuality.assess("1\n2\n3\n")
      val policy = ExtractionQualityPolicy()
      assertTrue(
        !cid.sufficient(policy),
        !blank.sufficient(policy),
        !sparse.sufficient(policy),
        cid.cidArtifacts > 0
      )
    }
  )

object ExtractionOutlineSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ExtractedHeading")(
    test("没有结构时从 Markdown 标题恢复目录") {
      val document = SourceDocument(
        "doc-1",
        "# 伤寒论\n\n引言\n\n## 太阳病\n\n脉浮。\n",
        "knowledge://doc-1"
      )
      val outline = ExtractedHeading.from(document)
      assertTrue(
        outline == Chunk(ExtractedHeading(1, "伤寒论", None), ExtractedHeading(2, "太阳病", None))
      )
    }
  )

object CascadingDocumentLoaderSpec extends ZIOSpecDefault:

  private def pdfInput(bytes: Chunk[Byte], consumed: Ref[Int]): DocumentInput =
    DocumentInput(
      "scan-1",
      "knowledge://scan-1",
      "scan.pdf",
      "application/pdf",
      Some(bytes.length.toLong),
      Map("title" -> "影印本"),
      ZStream.fromZIO(consumed.update(_ + 1)).drain ++ ZStream.fromChunk(bytes)
    )

  private def loader(
      idValue: String,
      body: String,
      media: Set[String] = Set("application/pdf")
  ): DocumentLoader =
    new DocumentLoader:
      val id: String                                                     = idValue
      val supportedMediaTypes: Set[String]                               = media
      def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
        input.content.runDrain *>
          ZIO.succeed(SourceDocument(input.id, body, input.sourceUri, Map("stage" -> idValue)))

  private def failing(idValue: String): DocumentLoader =
    new DocumentLoader:
      val id: String                                                     = idValue
      val supportedMediaTypes: Set[String]                               = Set("application/pdf")
      def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
        input.content.runDrain *> ZIO.fail(AgentError.RetrievalFailed(s"$idValue 失败"))

  private def stage(
      kind: ExtractionStageKind,
      idValue: String,
      body: String
  ): ExtractionStage =
    ExtractionStage(kind, loader(idValue, body))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("CascadingDocumentLoader")(
    test("字节只收集一次，质量不足时回退到下一阶段") {
      val bytes = Chunk.fromArray("%PDF-scan".getBytes)
      for
        consumed <- Ref.make(0)
        cascade = CascadingDocumentLoader(
          Chunk(
            stage(ExtractionStageKind.TextLayer, "cheap-tika", "(cid:1)(cid:2)(cid:3)".repeat(30)),
            stage(
              ExtractionStageKind.LayoutOcr,
              "ocr-docling",
              "黄帝内经素问曰春三月此谓发陈天地俱生万物以荣。".repeat(3)
            )
          )
        )
        document <- cascade.load(pdfInput(bytes, consumed))
        seen     <- consumed.get
      yield assertTrue(
        seen == 1,
        document.metadata.get("extractionMethod").contains("ocr-docling"),
        document.metadata.get("extractionFallbackUsed").contains("true"),
        document.metadata.get("extractionMode").contains("auto"),
        document.text.contains("黄帝内经")
      )
    },
    test("廉价文本层足够时不调用后续阶段") {
      val bytes = Chunk.fromArray("%PDF-digital".getBytes)
      for
        consumed   <- Ref.make(0)
        laterCalls <- Ref.make(0)
        later = ExtractionStage.ocr(
          new DocumentLoader:
            val id: String                                                     = "should-not-run"
            val supportedMediaTypes: Set[String]                               = Set("application/pdf")
            def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
              laterCalls.update(_ + 1) *>
                ZIO.succeed(SourceDocument(input.id, "later", input.sourceUri))
        )
        cascade = CascadingDocumentLoader(
          Chunk(
            stage(
              ExtractionStageKind.TextLayer,
              "cheap-tika",
              "Treatise on Cold Damage describes Taiyang wind strike. ".repeat(4)
            ),
            later
          )
        )
        document <- cascade.load(pdfInput(bytes, consumed))
        skipped  <- laterCalls.get
      yield assertTrue(
        document.metadata.get("extractionMethod").contains("cheap-tika"),
        document.metadata.get("extractionFallbackUsed").contains("false"),
        skipped == 0
      )
    },
    test("全部阶段质量不足时 fail-closed 不索引") {
      val bytes = Chunk.fromArray("%PDF-empty".getBytes)
      for
        consumed <- Ref.make(0)
        cascade = CascadingDocumentLoader(
          Chunk(
            stage(ExtractionStageKind.TextLayer, "cheap", "\n\n"),
            ExtractionStage.ocr(failing("ocr"))
          )
        )
        result <- cascade.load(pdfInput(bytes, consumed)).exit
      yield assertTrue(result.isFailure)
    },
    test("强制 OCR 时跳过廉价文本层") {
      val bytes = Chunk.fromArray("%PDF-force".getBytes)
      for
        consumed <- Ref.make(0)
        cascade = CascadingDocumentLoader(
          Chunk(
            stage(
              ExtractionStageKind.TextLayer,
              "cheap-tika",
              "Treatise on Cold Damage describes Taiyang. ".repeat(4)
            ),
            stage(
              ExtractionStageKind.LayoutOcr,
              "ocr-docling",
              "黄帝内经素问曰春三月此谓发陈天地俱生万物以荣。".repeat(3)
            )
          )
        )
        input = pdfInput(bytes, consumed).copy(metadata = Map(ExtractionMode.MetadataKey -> "ocr"))
        document <- cascade.load(input)
      yield assertTrue(
        document.metadata.get("extractionMethod").contains("ocr-docling"),
        document.metadata.get("extractionMode").contains("ocr")
      )
    },
    test("强制视觉但未装配该阶段时 fail-closed") {
      val bytes = Chunk.fromArray("%PDF-novision".getBytes)
      for
        consumed <- Ref.make(0)
        cascade = CascadingDocumentLoader(
          Chunk(stage(ExtractionStageKind.TextLayer, "cheap-tika", "Treatise on Cold Damage. ".repeat(8)))
        )
        input = pdfInput(bytes, consumed).copy(metadata = Map(ExtractionMode.MetadataKey -> "vision"))
        result <- cascade.load(input).exit
      yield assertTrue(result.isFailure)
    }
  )
