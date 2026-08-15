package com.zyblw.agent.loaders

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.io.ByteArrayInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.{AutoDetectParser, ParseContext}
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.sax.BodyContentHandler
import zio.*

/** Apache Tika 文档解析的资源与安全上限。
  *
  * @param maxInputBytes
  *   单文件压缩/原始输入最大字节数；在创建解析器前从 ZStream 强制检查
  * @param maxExtractedCodePoints
  *   最多保留的提取文本 code point 数
  * @param parseTimeout
  *   单文件解析墙钟预算；超时返回可重试 RetrievalError 并中断 blocking Fiber
  * @param allowOcr
  *   是否允许 Tika 调用本机 Tesseract；默认关闭，生产 OCR 应优先使用受控 OCI Adapter
  * @param requireDetectedTypeMatch
  *   是否要求 Tika 检测类型与业务声明兼容，防止伪装扩展名进入错误解析器
  */
final case class TikaDocumentLoaderConfig(
    maxInputBytes: Int = 32 * 1024 * 1024,
    maxExtractedCodePoints: Int = 2_000_000,
    parseTimeout: Duration = 30.seconds,
    allowOcr: Boolean = false,
    requireDetectedTypeMatch: Boolean = true,
    /** 允许业务装配把 PDF 交给隔离 Docling，同时继续用 Tika 解析 Markdown/EPUB。 只能选择框架审核过的类型，避免调用方用本参数扩大解析攻击面。
      */
    enabledMediaTypes: Set[String] = TikaDocumentLoader.SupportedMediaTypes
):
  require(maxInputBytes > 0, "Tika maxInputBytes 必须为正数")
  require(maxExtractedCodePoints > 0, "Tika maxExtractedCodePoints 必须为正数")
  require(parseTimeout > Duration.Zero, "Tika parseTimeout 必须为正数")
  require(enabledMediaTypes.nonEmpty, "Tika enabledMediaTypes 不能为空")
  require(
    enabledMediaTypes.subsetOf(TikaDocumentLoader.SupportedMediaTypes),
    "Tika enabledMediaTypes 包含未审核的 MIME type"
  )

/** 使用 Apache Tika 3.x 解析文本、Markdown、HTML、PDF 和 EPUB 的可选 Loader。
  *
  * 这个实现把输入总量、输出总量、MIME 检测、超时和 OCR 开关都放在框架控制面，而不是相信文件扩展名或文档内部 metadata。解析在 blocking executor 上执行；InputStream 在
  * finally 中关闭。Tika/PDFBox/压缩格式解析器仍属于复杂 攻击面，因此公开上传的任意文件应在独立 OCI 解析服务中运行；本实现适合受控知识库与可信运营导入，并为未来远程 Sandbox
  * Loader 保持同一个 `DocumentLoader` 契约。
  *
  * 文档正文始终是 untrusted data。该 Loader 不解析文档内“指令”为框架配置，也只放行 title/author/content-type 三类 低敏 metadata，最终还会经过
  * `DocumentLoaderRegistry` 的身份和大小复核。
  *
  * @param config
  *   输入、输出、时间和 OCR 策略
  */
final class TikaDocumentLoader(config: TikaDocumentLoaderConfig = TikaDocumentLoaderConfig())
    extends DocumentLoader:
  override val id: String = "apache-tika-3.3.1"

  override val supportedMediaTypes: Set[String] = config.enabledMediaTypes

  /** 先有界收集一次输入，再在可中断 blocking Fiber 中解析。
    *
    * Tika 的多数随机访问解析器本身需要完整文件，因此这里的 ZStream 背压作用于网络/对象存储读取和多文件调度， 单文件仍会被收集到最多
    * `maxInputBytes`。超出一字节就失败，不做静默截断，因为截断 PDF/EPUB 会产生误导正文。
    */
  def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
    rejectDeclaredOversize(input) *>
      input.content.take(config.maxInputBytes.toLong + 1L).runCollect.flatMap { bytes =>
        if bytes.length > config.maxInputBytes then
          ZIO.fail(
            AgentError.RetrievalFailed(
              s"文档输入超过 Tika 字节上限 ${config.maxInputBytes}"
            )
          )
        else parse(input, bytes.toArray)
      }

  /** 上游已知长度超限时不打开/消费正文流，节省对象存储流量和解析资源。 */
  private def rejectDeclaredOversize(input: DocumentInput): IO[RetrievalError, Unit] =
    input.declaredLength match
      case Some(length) if length > config.maxInputBytes.toLong =>
        ZIO.fail(
          AgentError.RetrievalFailed(
            s"文档声明长度 $length 超过 Tika 字节上限 ${config.maxInputBytes}"
          )
        )
      case _ => ZIO.unit

  /** 执行一次受限解析；错误消息不包含文件正文、路径、metadata 或解析器原始异常文本。 */
  private def parse(input: DocumentInput, bytes: Array[Byte]): IO[RetrievalError, SourceDocument] =
    ZIO
      .attemptBlockingInterrupt(parseBlocking(input, bytes))
      .mapError { error =>
        if causeChain(error).exists(_.getClass.getSimpleName.contains("WriteLimitReached")) then
          AgentError.RetrievalFailed(
            s"文档提取正文超过 Tika 字符上限 ${config.maxExtractedCodePoints}"
          )
        else AgentError.RetrievalFailed("Apache Tika 文档解析失败")
      }
      .timeoutFail(AgentError.RetrievalFailed("Apache Tika 文档解析超时", retryable = true))(config.parseTimeout)
      .flatMap(validateParsed(input, _))

  /** 真正的 Java 解析临界区。
    *
    * `BodyContentHandler(max+1)` 允许调用后准确识别越界。默认在 ParseContext 同时关闭通用 Tesseract OCR 和 PDF OCR， 防止部署机器是否安装
    * tesseract 悄悄改变延迟、资源和数据处理语义。
    */
  private def parseBlocking(input: DocumentInput, bytes: Array[Byte]): ParsedDocument =
    val parser   = AutoDetectParser()
    val metadata = Metadata()
    metadata.set("resourceName", input.fileName)
    // 不把 declaredMediaType 预写入 Content-Type：AutoDetectParser 会把该字段当成强提示，
    // 从而可能直接相信调用方声明，失去内容嗅探与声明/实测类型交叉校验的价值。
    val context = ParseContext()
    val ocr     = TesseractOCRConfig()
    ocr.setSkipOcr(!config.allowOcr)
    context.set(classOf[TesseractOCRConfig], ocr)
    if !config.allowOcr then
      val pdf = PDFParserConfig()
      pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR)
      context.set(classOf[PDFParserConfig], pdf)
    val handler = BodyContentHandler(config.maxExtractedCodePoints + 1)
    val stream  = ByteArrayInputStream(bytes)
    try
      parser.parse(stream, handler, metadata, context)
      ParsedDocument(
        normalize(handler.toString),
        normalizeMediaType(Option(metadata.get("Content-Type")).getOrElse(input.declaredMediaType)),
        safeMetadata(metadata)
      )
    finally stream.close()

  /** 检查类型兼容、非空正文和 code point 上限后建立 SourceDocument。 */
  private def validateParsed(
      input: DocumentInput,
      parsed: ParsedDocument
  ): IO[RetrievalError, SourceDocument] =
    val count = parsed.text.codePointCount(0, parsed.text.length)
    if config.requireDetectedTypeMatch && !compatible(input.declaredMediaType, parsed.detectedMediaType) then
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"文档 MIME 检测不匹配: declared=${input.declaredMediaType},detected=${parsed.detectedMediaType}"
        )
      )
    else if parsed.text.isEmpty then ZIO.fail(AgentError.RetrievalFailed("Apache Tika 未提取到正文"))
    else if count > config.maxExtractedCodePoints then
      ZIO.fail(
        AgentError.RetrievalFailed(
          s"文档提取正文 $count 超过 Tika 字符上限 ${config.maxExtractedCodePoints}"
        )
      )
    else
      val representation =
        if input.declaredMediaType == "text/markdown" then DocumentRepresentation.Markdown
        else DocumentRepresentation.PlainText
      ZIO.succeed(SourceDocument(input.id, parsed.text, input.sourceUri, parsed.metadata, representation))

  /** MIME 参数不参与比较；只允许少量有明确语义的等价检测结果。 */
  private def compatible(declared: String, detected: String): Boolean =
    declared == detected || ((declared -> detected) match
      case ("text/markdown", "text/plain")        => true
      case ("text/html", "application/xhtml+xml") => true
      case ("application/xhtml+xml", "text/html") => true
      case _                                      => false)

  /** 统一换行、移除 NUL 和外围空白；不会把 HTML/PDF 内文字解释成系统指令。 */
  private def normalize(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "").trim

  /** Tika 可能附带 charset 等参数；治理层只保存规范小写 MIME 主值。 */
  private def normalizeMediaType(value: String): String =
    value.takeWhile(_ != ';').trim.toLowerCase(java.util.Locale.ROOT)

  /** 只提取白名单元数据，并为每个值设置局部上限。未知键可能包含正文、文件路径或应用专有字段，不进入索引 metadata。
    */
  private def safeMetadata(metadata: Metadata): Map[String, String] =
    def first(keys: String*): Option[String] = keys.iterator
      .map(key => Option(metadata.get(key)).map(normalize).filter(_.nonEmpty).map(_.take(1000)))
      .collectFirst { case Some(value) => value }
    Map(
      "detectedMediaType" -> normalizeMediaType(
        Option(metadata.get("Content-Type")).getOrElse("application/octet-stream")
      )
    ) ++
      first("title", "dc:title").map("title" -> _) ++
      first("Author", "creator", "dc:creator").map("author" -> _)

  /** 展开异常 cause 链只用于分类；不会把异常 message 写入 AgentError。 */
  private def causeChain(error: Throwable): List[Throwable] =
    Iterator
      .iterate(Option(error))(_.flatMap(value => Option(value.getCause)))
      .takeWhile(_.nonEmpty)
      .flatten
      .take(16)
      .toList

  /** 解析器内部结果，尚未通过业务声明 MIME 和输出上限验证。 */
  final private case class ParsedDocument(
      text: String,
      detectedMediaType: String,
      metadata: Map[String, String]
  )

object TikaDocumentLoader:
  val SupportedMediaTypes: Set[String] = Set(
    "text/plain",
    "text/markdown",
    "text/html",
    "application/xhtml+xml",
    "application/pdf",
    "application/epub+zip"
  )

  /** 使用默认严格配置构造 Loader Layer。 */
  val layer: ULayer[DocumentLoader] = ZLayer.succeed(TikaDocumentLoader(): DocumentLoader)

  /** 使用业务显式配置构造 Loader Layer。 */
  def configured(config: TikaDocumentLoaderConfig): ULayer[DocumentLoader] =
    ZLayer.succeed(TikaDocumentLoader(config): DocumentLoader)
