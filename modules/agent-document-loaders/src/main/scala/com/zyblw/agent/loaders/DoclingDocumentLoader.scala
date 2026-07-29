package com.zyblw.agent.loaders

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.net.URI
import java.nio.charset.StandardCharsets
import scala.util.Try
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

enum DoclingTableMode(val wireValue: String):
  case Fast     extends DoclingTableMode("fast")
  case Accurate extends DoclingTableMode("accurate")

enum DoclingPdfBackend(val wireValue: String):
  case DoclingParse extends DoclingPdfBackend("docling_parse")
  case DlParseV2    extends DoclingPdfBackend("dlparse_v2")
  case DlParseV4    extends DoclingPdfBackend("dlparse_v4")

/** Docling Serve v1 PDF→Markdown Adapter 配置。
  *
  * @param baseUrl
  *   Docling Serve 根地址，Adapter 固定调用 `/v1/convert/file`
  * @param apiKey
  *   可选 `X-Api-Key`；只允许从 Secret Manager/环境注入，不进入错误、metadata 或 `toString`
  * @param maxInputBytes
  *   单 PDF 最大字节数；收集 multipart 前按 `max+1` 硬检查
  * @param maxResponseBytes
  *   完整 JSON 响应最大字节数
  * @param maxMarkdownCodePoints
  *   `md_content` 最大 Unicode code point 数
  * @param requestTimeout
  *   包括上传、转换和读取响应的总墙钟预算
  * @param doOcr
  *   是否让隔离的 Docling 服务对图片内容执行 OCR
  * @param forceOcr
  *   是否用 OCR 替换已有文本；默认 false，避免降低数字 PDF 的准确度
  * @param tableMode
  *   表格结构恢复模式
  * @param pdfBackend
  *   Docling PDF backend
  * @param ocrLanguages
  *   OCR 语言列表；为空时使用服务端默认
  * @param allowInsecureHttp
  *   仅允许本机测试或受控私网显式启用；默认要求 HTTPS
  */
final case class DoclingDocumentLoaderConfig(
    baseUrl: String,
    apiKey: Option[String] = None,
    maxInputBytes: Int = 32 * 1024 * 1024,
    maxResponseBytes: Int = 16 * 1024 * 1024,
    maxMarkdownCodePoints: Int = 2_000_000,
    requestTimeout: Duration = 5.minutes,
    doOcr: Boolean = true,
    forceOcr: Boolean = false,
    tableMode: DoclingTableMode = DoclingTableMode.Accurate,
    pdfBackend: DoclingPdfBackend = DoclingPdfBackend.DoclingParse,
    ocrLanguages: Chunk[String] = Chunk.empty,
    pageBreakPlaceholder: String = "<!-- page -->",
    allowInsecureHttp: Boolean = false
):
  require(baseUrl.trim.nonEmpty, "Docling baseUrl 不能为空")
  require(
    apiKey.forall(value =>
      value.nonEmpty && value.length <= 4096 && !value.exists(ch => ch == '\r' || ch == '\n')
    ),
    "Docling apiKey 无效"
  )
  require(maxInputBytes > 0 && maxResponseBytes > 0, "Docling input/response 上限必须为正数")
  require(maxMarkdownCodePoints > 0, "Docling Markdown 上限必须为正数")
  require(requestTimeout > Duration.Zero, "Docling requestTimeout 必须为正数")
  require(
    ocrLanguages.length <= 16 && ocrLanguages.forall(_.matches("[A-Za-z0-9_-]{1,32}")),
    "Docling OCR languages 数量或格式无效"
  )
  require(
    pageBreakPlaceholder.length <= 200 && !pageBreakPlaceholder.exists(ch => ch == '\u0000' || ch == '\r'),
    "Docling pageBreakPlaceholder 无效"
  )

  val convertFileUrl: String = s"${baseUrl.stripSuffix("/")}/v1/convert/file"

  private[loaders] def validateEndpoint: IO[RetrievalError, Unit] =
    ZIO
      .fromEither(Try(URI.create(convertFileUrl)).toEither)
      .mapError(_ => AgentError.RetrievalFailed("Docling endpoint 不是合法 URI"))
      .flatMap { uri =>
        val schemeAllowed = uri.getScheme == "https" || (allowInsecureHttp && uri.getScheme == "http")
        val valid = uri.isAbsolute && schemeAllowed && uri.getHost != null && uri.getUserInfo == null &&
          uri.getQuery == null && uri.getFragment == null
        ZIO
          .fail(AgentError.RetrievalFailed("Docling endpoint 违反 HTTPS/host/query 安全边界"))
          .unless(valid)
          .unit
      }

  override def toString: String =
    s"DoclingDocumentLoaderConfig(baseUrl=$baseUrl, apiKey=${apiKey.fold("<none>")(_ => "<redacted>")}, " +
      s"maxInputBytes=$maxInputBytes, maxResponseBytes=$maxResponseBytes, requestTimeout=$requestTimeout)"

/** 通过独立 Docling Serve v1 把 PDF 转成结构化 Markdown。
  *
  * 该实现有意放在可选 `agent-document-loaders` artifact，而不是 `agent-rag`：PDF layout/OCR/模型权重、容器资源和网络协议
  * 都是外围基础设施边界。框架负责请求治理、超时、容量、低敏错误、身份保持和输出表示；业务负责部署受信任的 Docling 镜像、Secret、网络策略以及从认证上下文生成 tenant/permission。
  *
  * 同步转换可能已经在服务端消耗大量计算，因此 Adapter 不做透明自动重试。瞬时错误仍带 `retryable=true`，由带幂等任务 ID 的摄取 worker 决定是否重新提交。
  */
final class DoclingDocumentLoader(client: Client, config: DoclingDocumentLoaderConfig) extends DocumentLoader:
  override val id: String = "docling-serve-v1-markdown"

  override val supportedMediaTypes: Set[String] = Set("application/pdf")

  def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
    for
      _     <- config.validateEndpoint
      _     <- rejectDeclaredOversize(input)
      bytes <- input.content.take(config.maxInputBytes.toLong + 1L).runCollect
      _     <- ZIO
        .fail(AgentError.RetrievalFailed(s"PDF 输入超过 Docling 字节上限 ${config.maxInputBytes}"))
        .when(bytes.length > config.maxInputBytes)
      markdown <- convert(input, bytes)
    yield SourceDocument(
      input.id,
      markdown,
      input.sourceUri,
      Map(
        "detectedMediaType"  -> "application/pdf",
        "contentConverterId" -> id,
        "pageBreakMarker"    -> config.pageBreakPlaceholder
      ),
      DocumentRepresentation.Markdown
    )

  private def rejectDeclaredOversize(input: DocumentInput): IO[RetrievalError, Unit] =
    input.declaredLength match
      case Some(length) if length > config.maxInputBytes.toLong =>
        ZIO.fail(
          AgentError.RetrievalFailed(
            s"PDF 声明长度 $length 超过 Docling 字节上限 ${config.maxInputBytes}"
          )
        )
      case _ => ZIO.unit

  private def convert(input: DocumentInput, bytes: Chunk[Byte]): IO[RetrievalError, String] =
    for
      boundaryId <- Random.nextUUID.map(value => s"zyblw-docling-${value.toString}")
      boundary = Boundary(boundaryId)
      fields   = Chunk(
        FormField.binaryField(
          "files",
          bytes,
          MediaType.application.pdf,
          filename = Some(input.fileName)
        ),
        FormField.simpleField("from_formats", "pdf"),
        FormField.simpleField("to_formats", "md"),
        FormField.simpleField("image_export_mode", "placeholder"),
        FormField.simpleField("do_ocr", config.doOcr.toString),
        FormField.simpleField("force_ocr", config.forceOcr.toString),
        FormField.simpleField("pdf_backend", config.pdfBackend.wireValue),
        FormField.simpleField("table_mode", config.tableMode.wireValue),
        FormField.simpleField("abort_on_error", "true"),
        FormField.simpleField("include_images", "false"),
        FormField.simpleField("md_page_break_placeholder", config.pageBreakPlaceholder)
      ) ++ config.ocrLanguages.map(language => FormField.simpleField("ocr_lang", language))
      form    = Form(fields)
      request = Request
        .post(config.convertFileUrl, Body.fromMultipartForm(form, boundary))
        .addHeader("accept", "application/json")
      authenticated = config.apiKey.fold(request)(value => request.addHeader("X-Api-Key", value))
      response <- client
        .stream(authenticated) { raw =>
          ZStream.fromZIO(readBounded(raw).map(body => raw.status -> body))
        }
        .runHead
        .someOrFail(AgentError.RetrievalFailed("Docling 响应流为空", retryable = true))
        .mapError(mapTransportError)
        .timeoutFail(AgentError.RetrievalFailed("Docling 转换超时", retryable = true))(config.requestTimeout)
      markdown <-
        if response._1.isSuccess then decodeMarkdown(response._2)
        else ZIO.fail(httpError(response._1.code))
    yield markdown

  private def readBounded(response: Response): IO[RetrievalError, String] =
    response.body.asStream
      .take(config.maxResponseBytes.toLong + 1L)
      .runCollect
      .mapError(mapTransportError)
      .flatMap { bytes =>
        if bytes.length > config.maxResponseBytes then
          ZIO.fail(AgentError.RetrievalFailed("Docling 响应超过配置字节上限"))
        else ZIO.succeed(String(bytes.toArray, StandardCharsets.UTF_8))
      }

  private def decodeMarkdown(body: String): IO[RetrievalError, String] =
    for
      json <- ZIO
        .fromEither(body.fromJson[Json])
        .mapError(_ => AgentError.RetrievalFailed("Docling 响应不是合法 JSON"))
      status <- ZIO
        .fromOption(stringField(json, "status"))
        .orElseFail(AgentError.RetrievalFailed("Docling 响应缺少 status"))
      _ <- ZIO
        .fail(AgentError.RetrievalFailed(s"Docling 转换状态不是 success: ${safeStatus(status)}"))
        .unless(status == "success")
      document <- ZIO
        .fromOption(field(json, "document"))
        .orElseFail(AgentError.RetrievalFailed("Docling 响应缺少 document"))
      markdown <- ZIO
        .fromOption(stringField(document, "md_content").map(normalize).filter(_.nonEmpty))
        .orElseFail(AgentError.RetrievalFailed("Docling 响应缺少非空 md_content"))
      codePoints = markdown.codePointCount(0, markdown.length)
      _ <- ZIO
        .fail(
          AgentError.RetrievalFailed(
            s"Docling Markdown 长度 $codePoints 超过上限 ${config.maxMarkdownCodePoints}"
          )
        )
        .when(codePoints > config.maxMarkdownCodePoints)
    yield markdown

  private def normalize(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "").trim

  private def safeStatus(value: String): String =
    if value.matches("[a-z_]{1,40}") then value else "invalid"

  private def mapTransportError(error: Throwable): RetrievalError = error match
    case known: RetrievalError => known
    case other                 =>
      AgentError.RetrievalFailed(
        s"Docling transport failure: ${other.getClass.getSimpleName}",
        retryable = true
      )

  private def httpError(status: Int): RetrievalError = AgentError.RetrievalFailed(
    s"Docling HTTP $status",
    retryable = status == 408 || status == 409 || status == 425 || status == 429 || status >= 500
  )

  private def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.find(_._1 == name).map(_._2)
    case _                => None

  private def stringField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(value) => value }

object DoclingDocumentLoader:
  def configured(config: DoclingDocumentLoaderConfig): URLayer[Client, DocumentLoader] =
    ZLayer.fromFunction((client: Client) => DoclingDocumentLoader(client, config): DocumentLoader)
