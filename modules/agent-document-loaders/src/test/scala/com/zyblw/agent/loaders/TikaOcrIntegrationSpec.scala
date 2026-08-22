package com.zyblw.agent.loaders

import com.zyblw.agent.rag.*
import java.io.ByteArrayOutputStream
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage}
import zio.*
import zio.test.*

/** 真实 OCR 路径的环境门禁测试。默认跳过；`RUN_OCR_INTEGRATION=1` 时执行。 */
object TikaOcrIntegrationSpec extends ZIOSpecDefault:
  private val enabled = sys.env.get("RUN_OCR_INTEGRATION").contains("1")

  private def manyPagePdf(pages: Int): Task[Chunk[Byte]] = ZIO.attempt {
    val document = PDDocument()
    val output   = ByteArrayOutputStream()
    try
      (1 to pages).foreach(_ => document.addPage(PDPage()))
      document.save(output)
      Chunk.fromArray(output.toByteArray)
    finally
      document.close()
      output.close()
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Tika OCR integration")(
    test("开启 OCR 时超大页数 PDF 仍受输入字节上限拦截") {
      for
        bytes <- manyPagePdf(60)
        loader = TikaDocumentLoader(TikaDocumentLoaderConfig(allowOcr = true, maxInputBytes = 8 * 1024))
        result <- loader
          .load(
            DocumentInput.fromBytes(
              "scan.pdf",
              "memory://scan.pdf",
              "scan.pdf",
              "application/pdf",
              bytes
            )
          )
          .exit
      yield assertTrue(result.isFailure)
    }
  ).when(enabled)
