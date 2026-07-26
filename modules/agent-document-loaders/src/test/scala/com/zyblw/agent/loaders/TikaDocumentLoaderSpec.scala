package com.zyblw.agent.loaders

import com.zyblw.agent.rag.*
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import zio.*
import zio.stream.*
import zio.test.*

/** 对 Apache Tika 适配器执行真实格式契约测试。
  *
  * 测试夹具全部在内存生成，不依赖开发机文件路径、网络或外部 OCR。这样 CI 可以稳定验证 HTML、PDF、EPUB、 MIME 防伪、输入上限和“声明超限时不读取流”等生产边界。
  */
object TikaDocumentLoaderSpec extends ZIOSpecDefault:

  /** 把 UTF-8 字符串转换为 ZIO Chunk，避免各测试重复处理 Java byte array。 */
  private def utf8(value: String): Chunk[Byte] = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))

  /** 建立一页含标准 ASCII 文本的 PDF；标准字体无需读取宿主字体文件。 */
  private def pdfFixture: Task[Chunk[Byte]] = ZIO.attempt {
    val document = PDDocument()
    val output   = ByteArrayOutputStream()
    try
      val page = PDPage()
      document.addPage(page)
      val content = PDPageContentStream(document, page)
      try
        content.beginText()
        content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12.0f)
        content.newLineAtOffset(72.0f, 720.0f)
        content.showText("Treatise on Cold Damage")
        content.endText()
      finally content.close()
      document.save(output)
      Chunk.fromArray(output.toByteArray)
    finally
      document.close()
      output.close()
  }

  /** 建立最小但结构完整的 EPUB：容器文件指向 OPF，OPF 再声明 XHTML spine。 这能验证 Tika 的真实 EPUB parser，而不是把 `.epub` 当普通 ZIP 或 HTML
    * 测试。
    */
  private def epubFixture: Task[Chunk[Byte]] = ZIO.attempt {
    val output                                 = ByteArrayOutputStream()
    val zip                                    = ZipOutputStream(output, StandardCharsets.UTF_8)
    def add(path: String, value: String): Unit =
      zip.putNextEntry(ZipEntry(path))
      zip.write(value.getBytes(StandardCharsets.UTF_8))
      zip.closeEntry()
    try
      add("mimetype", "application/epub+zip")
      add(
        "META-INF/container.xml",
        """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
      )
      add(
        "OEBPS/content.opf",
        """<?xml version="1.0"?><package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">fixture</dc:identifier><dc:title>EPUB Fixture</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>"""
      )
      add(
        "OEBPS/chapter.xhtml",
        """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body><p>Materia Medica chapter</p></body></html>"""
      )
      zip.finish()
      Chunk.fromArray(output.toByteArray)
    finally
      zip.close()
      output.close()
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TikaDocumentLoader")(
    test("提取纯文本与 HTML 可见正文且保留低敏探测元数据") {
      val loader = TikaDocumentLoader()
      for
        plain <- loader.load(
          DocumentInput.fromBytes(
            "plain",
            "knowledge://plain",
            "plain.txt",
            "text/plain",
            utf8("伤寒论学习笔记")
          )
        )
        html <- loader.load(
          DocumentInput.fromBytes(
            "html",
            "knowledge://html",
            "page.html",
            "text/html",
            utf8(
              "<html><head><title>可信标题</title><script>ignoreSecret()</script></head><body><h1>六经辨证</h1></body></html>"
            )
          )
        )
      yield assertTrue(
        plain.text.contains("伤寒论学习笔记"),
        plain.metadata("detectedMediaType") == "text/plain",
        html.text.contains("六经辨证"),
        !html.text.contains("ignoreSecret"),
        html.metadata.get("title").contains("可信标题")
      )
    },
    test("真实 PDF 与 EPUB 均由格式解析器提取正文") {
      val loader = TikaDocumentLoader()
      for
        pdfBytes  <- pdfFixture
        epubBytes <- epubFixture
        pdf       <- loader.load(
          DocumentInput.fromBytes(
            "pdf",
            "knowledge://pdf",
            "book.pdf",
            "application/pdf",
            pdfBytes
          )
        )
        epub <- loader.load(
          DocumentInput.fromBytes(
            "epub",
            "knowledge://epub",
            "book.epub",
            "application/epub+zip",
            epubBytes
          )
        )
      yield assertTrue(
        pdf.text.contains("Treatise on Cold Damage"),
        pdf.metadata("detectedMediaType") == "application/pdf",
        epub.text.contains("Materia Medica chapter"),
        epub.metadata("detectedMediaType") == "application/epub+zip"
      )
    },
    test("声明长度超限时在消费一次性正文流之前失败") {
      val loader = TikaDocumentLoader(TikaDocumentLoaderConfig(maxInputBytes = 4))
      for
        consumed <- Ref.make(false)
        input = DocumentInput(
          "oversize-declared",
          "knowledge://oversize-declared",
          "large.txt",
          "text/plain",
          Some(5L),
          Map.empty,
          ZStream.fromZIO(consumed.set(true).as('x'.toByte))
        )
        result <- loader.load(input).exit
        opened <- consumed.get
      yield assertTrue(result.isFailure, !opened)
    },
    test("未知长度输入超过实际字节上限时失败且不静默截断") {
      val loader = TikaDocumentLoader(TikaDocumentLoaderConfig(maxInputBytes = 4))
      val input  = DocumentInput(
        "oversize-stream",
        "knowledge://oversize-stream",
        "large.txt",
        "text/plain",
        None,
        Map.empty,
        ZStream.fromChunk(utf8("12345"))
      )
      loader.load(input).exit.map(result => assertTrue(result.isFailure))
    },
    test("声明 PDF 但正文不是 PDF 时 fail-closed") {
      val loader = TikaDocumentLoader()
      loader
        .load(
          DocumentInput.fromBytes(
            "spoofed",
            "knowledge://spoofed",
            "spoofed.pdf",
            "application/pdf",
            utf8("not a pdf")
          )
        )
        .exit
        .map(result => assertTrue(result.isFailure))
    }
  )
