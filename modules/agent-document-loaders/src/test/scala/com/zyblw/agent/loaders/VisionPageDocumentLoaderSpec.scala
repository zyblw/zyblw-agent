package com.zyblw.agent.loaders

import com.zyblw.agent.core.*
import com.zyblw.agent.model.*
import com.zyblw.agent.rag.*
import java.io.ByteArrayOutputStream
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import zio.*
import zio.test.*

object VisionPageDocumentLoaderSpec extends ZIOSpecDefault:

  final private class RecordingVisionModel(replies: Ref[Chunk[String]]) extends ChatModel:
    val provider: String                        = "vision-stub"
    override val descriptor: ProviderDescriptor =
      ProviderDescriptor(provider, provider, "stub", ModelCapabilities(vision = true))

    def complete(request: ChatRequest): IO[AgentError, ChatResponse] =
      val images = request.messages.flatMap(_.content.collect { case ContentPart.ImageUrl(url, _) => url })
      val reply  =
        if images.isEmpty then ""
        else if images.head.startsWith("data:image/jpeg;base64,") then "# 伤寒论\n\n太阳之为病，脉浮，头项强痛而恶寒。"
        else ""
      replies.update(_ :+ reply).as(ChatResponse(AgentMessage.assistant(reply), FinishReason.Stop))

  private def textPdf(pages: Int): Task[Chunk[Byte]] = ZIO.attempt {
    val document = PDDocument()
    val output   = ByteArrayOutputStream()
    try
      (1 to pages).foreach { index =>
        val page = PDPage()
        document.addPage(page)
        val content = PDPageContentStream(document, page)
        try
          content.beginText()
          content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12.0f)
          content.newLineAtOffset(72.0f, 720.0f)
          content.showText(s"Page $index treatise")
          content.endText()
        finally content.close()
      }
      document.save(output)
      Chunk.fromArray(output.toByteArray)
    finally
      document.close()
      output.close()
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("VisionPageDocumentLoader")(
    test("逐页光栅化后把视觉模型 Markdown 恢复为带页码的结构") {
      for
        replies <- Ref.make(Chunk.empty[String])
        bytes   <- textPdf(1)
        loader = VisionPageDocumentLoader(
          RecordingVisionModel(replies),
          VisionPageDocumentLoaderConfig(model = "qwen-vl-test", maxPages = 4)
        )
        document <- loader.load(
          DocumentInput.fromBytes(
            "neijing",
            "knowledge://neijing",
            "neijing.pdf",
            "application/pdf",
            bytes
          )
        )
        seen <- replies.get
      yield assertTrue(
        seen.length == 1,
        document.representation == DocumentRepresentation.Markdown,
        document.text.contains("伤寒论"),
        document.structure.exists(_.schemaName == "vision-page-markdown"),
        document.structure.exists(_.blocks.exists(_.origins.contains(DocumentOrigin(1)))),
        document.metadata.get("visionModel").contains("qwen-vl-test")
      )
    },
    test("超过 maxPages 时 fail-closed 而不是静默截断") {
      for
        replies <- Ref.make(Chunk.empty[String])
        bytes   <- textPdf(2)
        loader = VisionPageDocumentLoader(
          RecordingVisionModel(replies),
          VisionPageDocumentLoaderConfig(model = "qwen-vl-test", maxPages = 1)
        )
        result <- loader
          .load(
            DocumentInput.fromBytes(
              "long-scan",
              "knowledge://long-scan",
              "long.pdf",
              "application/pdf",
              bytes
            )
          )
          .exit
        seen <- replies.get
      yield assertTrue(result.isFailure, seen.isEmpty)
    }
  )
