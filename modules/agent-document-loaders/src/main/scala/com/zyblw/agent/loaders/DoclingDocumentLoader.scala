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
    maxStructuredBlocks: Int = 200_000,
    maxStructuredOrigins: Int = 1_000_000,
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
  require(maxStructuredBlocks > 0 && maxStructuredOrigins > 0, "Docling structure 上限必须为正数")
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
  override val id: String = "docling-serve-v1-markdown-json"

  override val supportedMediaTypes: Set[String] = Set("application/pdf")

  def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
    for
      _     <- config.validateEndpoint
      _     <- rejectDeclaredOversize(input)
      bytes <- input.content.take(config.maxInputBytes.toLong + 1L).runCollect
      _     <- ZIO
        .fail(AgentError.RetrievalFailed(s"PDF 输入超过 Docling 字节上限 ${config.maxInputBytes}"))
        .when(bytes.length > config.maxInputBytes)
      converted <- convert(input, bytes)
    yield SourceDocument(
      input.id,
      converted.markdown,
      input.sourceUri,
      Map(
        "detectedMediaType"  -> "application/pdf",
        "contentConverterId" -> id,
        "pageBreakMarker"    -> config.pageBreakPlaceholder,
        "structureSchema"    -> converted.structure.schemaName
      ),
      DocumentRepresentation.Markdown,
      Some(converted.structure)
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

  private def convert(input: DocumentInput, bytes: Chunk[Byte]): IO[RetrievalError, ConvertedDocument] =
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
        FormField.simpleField("to_formats", "json"),
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
      converted <-
        if response._1.isSuccess then decodeDocument(response._2)
        else ZIO.fail(httpError(response._1.code))
    yield converted

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

  /** 同时解码人类可读 Markdown 与无损 DoclingDocument JSON。只有 Markdown 的响应会 fail-closed， 因为它无法满足页码/bbox/父子引用的生产契约。
    */
  private def decodeDocument(body: String): IO[RetrievalError, ConvertedDocument] =
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
      structureJson <- ZIO
        .fromOption(field(document, "json_content"))
        .orElseFail(AgentError.RetrievalFailed("Docling 响应缺少 json_content"))
      structure <- decodeStructure(structureJson)
    yield ConvertedDocument(markdown, structure)

  /** 把 DoclingDocument 投影为 Provider-neutral block ADT。原始 JSON 不进 metadata/日志，避免数据放大和隐私泄漏。 */
  private def decodeStructure(value: Json): IO[RetrievalError, DocumentStructure] =
    val parsed = value match
      case objectValue: Json.Obj => Right(objectValue: Json)
      case Json.Str(raw)         => raw.fromJson[Json]
      case _                     => Left("json_content 不是 object/string")
    ZIO
      .fromEither(parsed)
      .mapError(_ => AgentError.RetrievalFailed("Docling json_content 不是合法 DoclingDocument JSON"))
      .flatMap { json =>
        val schemaName    = stringField(json, "schema_name").getOrElse("")
        val schemaVersion = stringField(json, "version").orElse(stringField(json, "schema_version"))
        val sizes         = pageSizes(json)
        val nodes         = Chunk("texts", "tables", "pictures", "key_value_items")
          .flatMap(name => arrayField(json, name))
          .flatMap(value => decodeNode(value, sizes))
          .sortBy(node => (node.origins.headOption.fold(Int.MaxValue)(_.pageNumber), node.orderHint, node.id))
        val nodeById = nodes.map(node => node.id -> node).toMap
        val blocks   = nodes.zipWithIndex.map { case (node, ordinal) =>
          DocumentBlock(
            id = node.id,
            parentId = node.parentId,
            ordinal = ordinal,
            kind = blockKind(node.label),
            text = node.text,
            headingPath = headingPath(node, nodeById),
            origins = node.origins
          )
        }
        val originCount = blocks.foldLeft(0L)((total, block) => total + block.origins.length)
        if schemaName != "DoclingDocument" then
          ZIO.fail(AgentError.RetrievalFailed("Docling json_content schema_name 不受支持"))
        else if blocks.isEmpty then ZIO.fail(AgentError.RetrievalFailed("Docling json_content 没有可索引 block"))
        else if blocks.length > config.maxStructuredBlocks || originCount > config.maxStructuredOrigins then
          ZIO.fail(AgentError.RetrievalFailed("Docling structure 超过 block/origin 上限"))
        else
          ZIO
            .attempt(DocumentStructure(schemaName, schemaVersion, blocks))
            .mapError(_ => AgentError.RetrievalFailed("Docling structure 身份或字段契约无效"))
      }

  private def decodeNode(json: Json, pageSizes: Map[Int, (Double, Double)]): Option[RawNode] =
    val id       = stringField(json, "self_ref")
    val label    = stringField(json, "label").getOrElse("other")
    val text     = stringField(json, "text").orElse(stringField(json, "orig")).orElse(tableText(json))
    val parentId = field(json, "parent").flatMap(value => stringField(value, "$ref"))
    for
      nodeId   <- id.filter(_.trim.nonEmpty)
      nodeText <- text.map(normalize).filter(_.nonEmpty)
    yield RawNode(
      nodeId,
      parentId,
      label,
      nodeText,
      arrayField(json, "prov").flatMap(origin(nodeId, _, pageSizes)),
      orderHint(json)
    )

  /** 表格节点通常没有顶层 text，使用 table_cells 的行列位置恢复一份确定性文本。 */
  private def tableText(json: Json): Option[String] =
    val cells = Chunk
      .fromIterable(field(json, "data"))
      .flatMap(data => arrayField(data, "table_cells"))
      .flatMap { cell =>
        stringField(cell, "text").map(text =>
          (
            intField(cell, "start_row_offset_idx").getOrElse(0),
            intField(cell, "start_col_offset_idx").getOrElse(0),
            text
          )
        )
      }
      .sortBy(value => (value._1, value._2))
    Option.when(cells.nonEmpty)(
      cells.groupBy(_._1).toVector.sortBy(_._1).map(_._2.map(_._3).mkString(" | ")).mkString("\n")
    )

  private def origin(
      blockId: String,
      json: Json,
      pageSizes: Map[Int, (Double, Double)]
  ): Option[DocumentOrigin] =
    intField(json, "page_no").filter(_ > 0).map { page =>
      val bbox = field(json, "bbox").flatMap { value =>
        for
          left   <- doubleField(value, "l")
          top    <- doubleField(value, "t")
          right  <- doubleField(value, "r")
          bottom <- doubleField(value, "b")
          coordinateOrigin = stringField(value, "coord_origin").map(
            _.toLowerCase(java.util.Locale.ROOT)
          ) match
            case Some(name) if name.contains("bottom") => DocumentCoordinateOrigin.BottomLeft
            case _                                     => DocumentCoordinateOrigin.TopLeft
          size = pageSizes.get(page)
          box <- Try(
            DocumentBoundingBox(
              left,
              top,
              right,
              bottom,
              size.map(_._1),
              size.map(_._2),
              coordinateOrigin
            )
          ).toOption
        yield box
      }
      DocumentOrigin(page, bbox, Some(blockId))
    }

  private def orderHint(json: Json): Int =
    arrayField(json, "prov").headOption
      .flatMap(value => field(value, "charspan"))
      .flatMap {
        case Json.Arr(values) => values.headOption.collect { case Json.Num(number) => number.intValue }
        case value            => intField(value, "start")
      }
      .getOrElse(Int.MaxValue)

  /** Docling `pages` 是以页号为 key 的 object；页宽高让 bbox 可在不同渲染尺寸下稳定归一化。 */
  private def pageSizes(json: Json): Map[Int, (Double, Double)] =
    field(json, "pages") match
      case Some(Json.Obj(pages)) =>
        pages.flatMap { case (key, value) =>
          val page = key.toIntOption.orElse(intField(value, "page_no"))
          val size = field(value, "size")
          for
            pageNumber <- page
            sizeValue  <- size
            width      <- doubleField(sizeValue, "width")
            height     <- doubleField(sizeValue, "height")
            if pageNumber > 0 && width > 0.0 && height > 0.0
          yield pageNumber -> (width -> height)
        }.toMap
      case _ => Map.empty

  private def headingPath(node: RawNode, nodes: Map[String, RawNode]): Chunk[String] =
    def loop(current: Option[String], visited: Set[String], remaining: Int): List[String] =
      if remaining == 0 then Nil
      else
        current.flatMap(nodes.get) match
          case Some(parent) if !visited.contains(parent.id) =>
            val prefix = loop(parent.parentId, visited + parent.id, remaining - 1)
            if isHeading(parent.label) then prefix :+ parent.text else prefix
          case _ => Nil
    val ancestors = loop(node.parentId, Set(node.id), 64)
    val all       = if isHeading(node.label) then ancestors :+ node.text else ancestors
    Chunk.fromIterable(all.map(value => value.take(300)).filter(_.nonEmpty))

  private def blockKind(label: String): DocumentBlockKind =
    label.toLowerCase(java.util.Locale.ROOT) match
      case "title"                        => DocumentBlockKind.Title
      case "section_header" | "heading"   => DocumentBlockKind.SectionHeading
      case "paragraph" | "text"           => DocumentBlockKind.Paragraph
      case "list_item"                    => DocumentBlockKind.ListItem
      case "table"                        => DocumentBlockKind.Table
      case "picture" | "chart"            => DocumentBlockKind.Picture
      case "code"                         => DocumentBlockKind.Code
      case "formula"                      => DocumentBlockKind.Formula
      case "key_value_area" | "key_value" => DocumentBlockKind.KeyValue
      case _                              => DocumentBlockKind.Other

  private def isHeading(label: String): Boolean =
    val normalized = label.toLowerCase(java.util.Locale.ROOT)
    normalized == "title" || normalized == "section_header" || normalized == "heading"

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

  private def arrayField(json: Json, name: String): Chunk[Json] =
    field(json, name).collect { case Json.Arr(values) => values }.getOrElse(Chunk.empty)

  private def intField(json: Json, name: String): Option[Int] =
    field(json, name).collect { case Json.Num(value) => Try(value.intValueExact()).toOption }.flatten

  private def doubleField(json: Json, name: String): Option[Double] =
    field(json, name).collect { case Json.Num(value) => value.doubleValue }

  final private case class ConvertedDocument(markdown: String, structure: DocumentStructure)
  final private case class RawNode(
      id: String,
      parentId: Option[String],
      label: String,
      text: String,
      origins: Chunk[DocumentOrigin],
      orderHint: Int
  )

object DoclingDocumentLoader:
  def configured(config: DoclingDocumentLoaderConfig): URLayer[Client, DocumentLoader] =
    ZLayer.fromFunction((client: Client) => DoclingDocumentLoader(client, config): DocumentLoader)
