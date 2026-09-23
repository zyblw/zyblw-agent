package com.zyblw.agent.rag

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

import scala.util.Try

/** PaddleOCR-VL 的版面结果解码。
  *
  * 同时接受两种已经验证过的 JSON：官网/自建 `/layout-parsing` 的页数组（`prunedResult.parsing_res_list`， bbox 为
  * `[left, top, right, bottom]`），以及百度智能云 `parse_result_url` 对象（`pages[].layouts`， `position` 为
  * `[x, y, width, height]`，`page_num` 从 0 起）。页码、阅读顺序和坐标以 JSON 为准； 可选 Markdown 只按标题文本纠正层级。页眉、页脚和页码不进入正文。不调用
  * `PdfTextStructure.normalize`。
  */
object PaddleOcrVlDocument:
  val Method              = "paddleocr-vl-1.6"
  val MaxJsonChars        = 12_000_000
  val MaxMarkdownChars    = 4_000_000
  private val MaxBlocks   = 20_000
  private val MaxText     = 100_000
  private val HeadingLine = """^(#{1,6})[ \t]+(.+?)\s*$""".r
  private val ImageOnly   =
    """(?s)^\s*(?:<img\b[^>]*>|!\[[^\]]*\]\([^)]+\)|<div[^>]*>\s*<img\b[^>]*>\s*</div>)\s*$""".r

  private val NoiseLabels = Set(
    "number",
    "header",
    "footer",
    "header_image",
    "footer_image",
    "formula_number"
  )

  final case class Section(
      id: String,
      parentId: Option[String],
      ordinal: Int,
      level: Int,
      title: String,
      pageStart: Option[Int],
      pageEnd: Option[Int]
  )

  /** 插图待收存。临时地址过期时，用页码和左上角坐标从原件裁出。 */
  final case class PendingFigure(
      blockId: String,
      sourceUrl: String,
      pageNumber: Int,
      left: Double,
      top: Double,
      right: Double,
      bottom: Double
  )

  final case class Parsed(
      pageCount: Int,
      sections: Chunk[Section],
      blocks: Chunk[DocumentBlock],
      figures: Chunk[PendingFigure] = Chunk.empty
  )

  def decode(jsonText: String, markdown: Option[String] = None): Either[String, Parsed] =
    val json    = Option(jsonText).map(_.stripPrefix("\uFEFF").trim).getOrElse("")
    val outline = markdown.map(_.stripPrefix("\uFEFF")).filter(_.trim.nonEmpty)
    if json.isEmpty then Left("需要 PaddleOCR 的 JSON 结果")
    else if json.length > MaxJsonChars then Left("JSON 超过可导入大小")
    else if outline.exists(_.length > MaxMarkdownChars) then Left("Markdown 超过可导入大小")
    else
      json
        .fromJson[Json]
        .left
        .map(_ => "JSON 无法解析")
        .flatMap { root =>
          val pages = extractPages(root)
          if pages.isEmpty then Left("JSON 里没有可识别的 PaddleOCR 页面")
          else Right(assemble(pages, outline))
        }
        .flatMap { parsed =>
          if parsed.sections.isEmpty && parsed.blocks.isEmpty then Left("没有可导入的正文")
          else Right(parsed)
        }

  final private case class RawPage(
      number: Int,
      width: Option[Double],
      height: Option[Double],
      blocks: Vector[RawBlock]
  )
  final private case class RawBlock(
      order: Int,
      label: String,
      text: String,
      bbox: Option[(Double, Double, Double, Double)],
      sourceId: Option[String],
      levelHint: Option[Int],
      sourceUrl: Option[String] = None
  )

  private def assemble(pages: Vector[RawPage], markdown: Option[String]): Parsed =
    val headings      = markdown.fold(Vector.empty[(Int, String)])(text =>
      markdownHeadings(text, jsonTitles(pages), continuationTexts(pages))
    )
    var headingCursor = 0
    var stack         = List.empty[(Int, String, String)]
    var sections      = Vector.empty[Section]
    var blocks        = Vector.empty[DocumentBlock]
    var figures       = Vector.empty[PendingFigure]
    var current       = Option.empty[String]
    var ordinal       = 0

    def openSection(level: Int, title: String, pageNumber: Int): String =
      val parent = stack.dropWhile(_._1 >= level).headOption
      val id     = s"p${pageNumber}-h$ordinal"
      stack = (level, id, title) :: stack.dropWhile(_._1 >= level)
      current = Some(id)
      sections = sections :+ Section(
        id,
        parent.map(_._2),
        sections.length,
        level,
        title,
        Some(pageNumber),
        Some(pageNumber)
      )
      ordinal += 1
      id

    def appendBlock(page: RawPage, raw: RawBlock, kind: DocumentBlockKind, text: String): Unit =
      val sectionId = current.getOrElse(openSection(1, "正文", page.number))
      val path      = Chunk.fromIterable(stack.reverseIterator.map(_._3).toList).filter(_.nonEmpty)
      val origin    = DocumentOrigin(
        page.number,
        boundingBox = raw.bbox.map { case (left, top, right, bottom) =>
          DocumentBoundingBox(left, top, right, bottom, pageWidth = page.width, pageHeight = page.height)
        },
        blockId = raw.sourceId
      )
      blocks = blocks :+ DocumentBlock(
        id = s"p${page.number}-b$ordinal",
        parentId = Some(sectionId),
        ordinal = blocks.length,
        kind = kind,
        text = text,
        headingPath = path,
        origins = Chunk(origin)
      )
      ordinal += 1

    def rememberFigure(pageNumber: Int, blockId: String, raw: RawBlock): Unit =
      val url                        = httpsUrl(raw.sourceUrl).getOrElse("")
      val box                        = raw.bbox.getOrElse((0.0, 0.0, 0.0, 0.0))
      val (left, top, right, bottom) = box
      val croppable                  = right - left >= 8 && bottom - top >= 8
      if url.nonEmpty || croppable then
        figures = figures :+ PendingFigure(blockId, url, pageNumber, left, top, right, bottom)

    pages.foreach { page =>
      val prepared = pairFigures(page.blocks)
      var index    = 0
      while index < prepared.length do
        val raw      = prepared(index)
        var advance  = 1
        if ordinal < MaxBlocks && !NoiseLabels.contains(raw.label) then
          val absorbed = prepared.lift(index + 1).flatMap(next => titleContinuation(raw, next, headings))
          val source   =
            absorbed match
              case Some(rest) =>
                advance = 2
                raw.copy(text = joinTitle(raw.text, rest))
              case None => raw
          val (hashLevel, title) = splitHeading(source.text)
          if source.label == "content" && title.nonEmpty && !ImageOnly.matches(title) then
            if !stack.headOption.exists(_._3 == "目录") then
              val level = stack.headOption.fold(1)(parent => math.min(6, parent._1 + 1))
              openSection(level, "目录", page.number): Unit
            appendBlock(page, source, DocumentBlockKind.Paragraph, clampText(title))
          else
            classify(source.label, title) match
              case None                     => ()
              case Some(Left(defaultLevel)) =>
                val cleaned = clampTitle(title)
                if cleaned.nonEmpty then
                  val level = resolveLevel(
                    defaultLevel,
                    hashLevel,
                    source.levelHint,
                    cleaned,
                    headings,
                    () => headingCursor,
                    next => headingCursor = next
                  )
                  openSection(level, cleaned, page.number): Unit
              case Some(Right(kind)) =>
                val text = clampText(title)
                if text.nonEmpty && !ImageOnly.matches(text) then
                  val blockId = s"p${page.number}-b$ordinal"
                  if kind == DocumentBlockKind.Picture then rememberFigure(page.number, blockId, source)
                  appendBlock(page, source, kind, text)
        index += advance
    }

    val ranged = fillRanges(sections, blocks)
    Parsed(
      pages.map(_.number).foldLeft(pages.length)(_ max _),
      Chunk.fromIterable(ranged),
      Chunk.fromIterable(blocks),
      Chunk.fromIterable(figures)
    )

  private def resolveLevel(
      defaultLevel: Int,
      hashLevel: Option[Int],
      hint: Option[Int],
      title: String,
      headings: Vector[(Int, String)],
      cursor: () => Int,
      setCursor: Int => Unit
  ): Int =
    val matched = matchHeading(title, headings, cursor(), setCursor)
    clampLevel(
      matched
        .orElse(hashLevel)
        .orElse(hint)
        .orElse(inferHeadingLevel(title))
        .getOrElse(defaultLevel)
    )

  private def matchHeading(
      title: String,
      headings: Vector[(Int, String)],
      cursor: Int,
      setCursor: Int => Unit
  ): Option[Int] =
    val key = normalizeTitle(title)
    if key.length < 2 then None
    else
      val exact = headings.indexWhere(
        (level, heading) => level > 0 && normalizeTitle(heading) == key,
        cursor
      )
      val relaxedKey = relaxedTitle(title)
      val found      =
        if exact >= 0 then exact
        else
          headings.indexWhere(
            (level, heading) => level > 0 && relaxedKey.length >= 2 && relaxedTitle(heading) == relaxedKey,
            cursor
          )
      if found < 0 then None
      else
        setCursor(found + 1)
        Some(headings(found)._1)

  /** Markdown 标题仍是层级事实源。遇到网页 JSON 与 Markdown 只差句号、括号或 OCR 空格时做宽松匹配； Markdown 确实缺失时，再按常见中文目录编号恢复最小可用层级，避免所有
    * paragraph_title 都坍成二级。
    */
  private def inferHeadingLevel(title: String): Option[Int] =
    val value = splitHeading(title)._2.trim
    if value.matches("""^第[一二三四五六七八九十百零〇\d]+章(?:\s|[:：、.]|$).*""") then Some(2)
    else if value.matches("""^第[一二三四五六七八九十百零〇\d]+节(?:\s|[:：、.]|$).*""") then Some(3)
    else if value.matches("""^[一二三四五六七八九十百零〇]+[、.．]\s*.*""") then Some(4)
    else if value.matches("""^[（(][一二三四五六七八九十百零〇\d]+[）)]\s*.*""") then Some(5)
    else if value.matches("""^\d+[、.．]\s*.*""") then Some(5)
    else None

  /** 图片块吃掉紧随的图注，正文只留说明文字，下载地址留在 sourceUrl。 */
  private def pairFigures(blocks: Vector[RawBlock]): Vector[RawBlock] =
    val result = Vector.newBuilder[RawBlock]
    var index  = 0
    while index < blocks.length do
      val block = blocks(index)
      if block.label == "image" || block.label == "chart" then
        val caption = blocks.lift(index + 1).filter(_.label == "figure_title")
        val text    = caption.map(item => plainText(item.text)).filter(_.nonEmpty).getOrElse("插图")
        result += block.copy(
          label = "image",
          text = text,
          sourceUrl = block.sourceUrl.orElse(httpsUrl(Some(imageSrc(block.text))))
        )
        index += (if caption.isDefined then 2 else 1)
      else if looksHtml(block.text) then
        result += block.copy(text = plainText(block.text))
        index += 1
      else
        result += block
        index += 1
    result.result()

  private val ImgSrc = """(?s)src\s*=\s*["']([^"']+)["']""".r

  private def imageMap(markdown: Json): Map[String, String] =
    field(markdown, "images") match
      case Some(Json.Obj(fields)) =>
        fields.collect { case (key, Json.Str(value)) if value.startsWith("https://") => key -> value }.toMap
      case _ => Map.empty

  private def imageSrc(text: String): String =
    ImgSrc.findFirstMatchIn(text).map(_.group(1).trim).getOrElse("")

  private def looksHtml(text: String): Boolean =
    text.contains('<') && text.contains('>')

  private def plainText(value: String): String =
    value
      .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
      .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
      .replaceAll("(?s)<[^>]+>", " ")
      .replace("&nbsp;", " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replaceAll("\\s+", " ")
      .trim

  private def httpsUrl(value: Option[String]): Option[String] =
    value.map(_.trim).filter(url => url.startsWith("https://") && url.length <= 2000)

  /** 标题返回层级，正文返回块类型；噪声和空图片返回 None。 */
  private def classify(label: String, text: String): Option[Either[Int, DocumentBlockKind]] =
    label match
      case "doc_title" | "title"      => Some(Left(1))
      case "paragraph_title"          => Some(Left(2))
      case "table"                    => Some(Right(DocumentBlockKind.Table))
      case "formula"                  => Some(Right(DocumentBlockKind.Formula))
      case "list"                     => Some(Right(DocumentBlockKind.ListItem))
      case "image" | "chart" | "seal" =>
        if text.nonEmpty && !ImageOnly.matches(text) then
          Some(Right(if label == "seal" then DocumentBlockKind.Other else DocumentBlockKind.Picture))
        else None
      case "figure_title" | "text" | "vertical_text" | "footnote" | "vision_footnote" | "aside_text" =>
        Some(Right(DocumentBlockKind.Paragraph))
      case _ =>
        if text.nonEmpty then Some(Right(DocumentBlockKind.Paragraph)) else None

  private def fillRanges(sections: Vector[Section], blocks: Vector[DocumentBlock]): Vector[Section] =
    val own                                               = blocks.groupBy(_.parentId)
    val children                                          = sections.groupBy(_.parentId)
    def pagesOf(id: String, seen: Set[String]): List[Int] =
      if seen.contains(id) then Nil
      else
        val direct = own.getOrElse(Some(id), Vector.empty).flatMap(_.origins.map(_.pageNumber))
        val nested = children.getOrElse(Some(id), Vector.empty).flatMap(child => pagesOf(child.id, seen + id))
        direct.toList ++ nested
    sections.map { section =>
      val pages = pagesOf(section.id, Set.empty)
      val start = (section.pageStart.toList ++ pages).minOption
      val end   = (section.pageEnd.toList ++ pages).maxOption.orElse(start)
      section.copy(pageStart = start, pageEnd = end)
    }

  private def extractPages(root: Json): Vector[RawPage] =
    unwrap(root) match
      case Json.Arr(values) =>
        values.zipWithIndex.flatMap { case (page, index) => layoutPage(page, index) }.toVector
      case obj: Json.Obj if arrayField(obj, "pages").exists(looksLikeBaiduPages) =>
        val pages     = arrayField(obj, "pages").getOrElse(Chunk.empty)
        val zeroBased = pages.flatMap(page => intField(page, "page_num")).minOption.contains(0)
        pages.zipWithIndex.flatMap { case (page, index) => baiduPage(page, index, zeroBased) }.toVector
      case obj: Json.Obj =>
        arrayField(obj, "layoutParsingResults")
          .orElse(arrayField(obj, "layout_parsing_results"))
          .map(values =>
            values.zipWithIndex.flatMap { case (page, index) => layoutPage(page, index) }.toVector
          )
          .orElse(layoutPage(obj, 0).map(page => Vector(page)))
          .getOrElse(Vector.empty)
      case _ => Vector.empty

  private def unwrap(json: Json): Json =
    json match
      case obj: Json.Obj =>
        field(obj, "result").collect { case nested: Json.Obj => nested }.getOrElse(obj)
      case other => other

  private def looksLikeBaiduPages(pages: Chunk[Json]): Boolean =
    pages.headOption.exists(page =>
      arrayField(page, "layouts").isDefined || intField(page, "page_num").isDefined
    )

  private def layoutPage(json: Json, index: Int): Option[RawPage] =
    val pruned = field(json, "prunedResult").orElse(field(json, "pruned_result")).getOrElse(json)
    val blocks = arrayField(pruned, "parsing_res_list").getOrElse(Chunk.empty)
    if blocks.isEmpty && field(json, "prunedResult").isEmpty && field(
        json,
        "pruned_result"
      ).isEmpty && arrayField(json, "parsing_res_list").isEmpty
    then None
    else
      val number = intField(json, "page_index")
        .orElse(intField(pruned, "page_index"))
        .map(normalizePageNumber(_, index))
        .getOrElse(index + 1)
      val width  = doubleField(json, "width").orElse(doubleField(pruned, "width")).filter(_ > 0)
      val height = doubleField(json, "height").orElse(doubleField(pruned, "height")).filter(_ > 0)
      val images = imageMap(field(json, "markdown").getOrElse(json))
      Some(
        RawPage(
          number,
          width,
          height,
          blocks.zipWithIndex
            .map { case (block, order) =>
              val content = stringField(block, "block_content").getOrElse("").trim
              val src     = imageSrc(content)
              RawBlock(
                intField(block, "block_order").getOrElse(order),
                stringField(block, "block_label").getOrElse("text"),
                content,
                box4(field(block, "block_bbox")),
                stringField(block, "block_id").map(id => s"p$number-$id"),
                None,
                httpsUrl(images.get(src).orElse(Option.when(src.startsWith("https://"))(src)))
              )
            }
            .sortBy(_.order)
            .toVector
        )
      )

  private def baiduPage(json: Json, index: Int, zeroBased: Boolean): Option[RawPage] =
    val layouts = arrayField(json, "layouts").getOrElse(Chunk.empty)
    val tables  = arrayField(json, "tables").getOrElse(Chunk.empty)
    val images  = arrayField(json, "images").getOrElse(Chunk.empty)
    val meta    = field(json, "meta")
    val width   = meta.flatMap(value => doubleField(value, "page_width")).filter(_ > 0)
    val height  = meta.flatMap(value => doubleField(value, "page_height")).filter(_ > 0)
    val number  = intField(json, "page_num")
      .map(value => if zeroBased then value + 1 else normalizePageNumber(value, index))
      .getOrElse(index + 1)
    if layouts.isEmpty && stringField(json, "text").isEmpty then None
    else
      val blocks =
        if layouts.isEmpty then
          Vector(RawBlock(0, "text", stringField(json, "text").getOrElse("").trim, None, None, None))
        else
          layouts.zipWithIndex.map { case (layout, order) =>
            val id        = stringField(layout, "layout_id")
            val tableText = id.flatMap(layoutId =>
              tables
                .collectFirst {
                  case table if stringField(table, "layout_id").contains(layoutId) =>
                    stringField(table, "markdown").getOrElse("").trim
                }
                .filter(_.nonEmpty)
            )
            val description = id.flatMap(layoutId =>
              images
                .collectFirst {
                  case image if stringField(image, "layout_id").contains(layoutId) =>
                    stringField(image, "image_description").getOrElse("").trim
                }
                .filter(_.nonEmpty)
            )
            val text = tableText.orElse(description).getOrElse(stringField(layout, "text").getOrElse("")).trim
            val remote = id.flatMap(layoutId =>
              images.collectFirst {
                case image if stringField(image, "layout_id").contains(layoutId) =>
                  stringField(image, "data_url").getOrElse("").trim
              }
            )
            RawBlock(
              order,
              stringField(layout, "type").getOrElse("text"),
              text,
              positionBox(field(layout, "position")),
              id.map(value => s"p$number-$value"),
              stringField(layout, "sub_type").flatMap(levelFromSubtype),
              httpsUrl(remote)
            )
          }.toVector
      Some(RawPage(number, width, height, blocks))

  private def normalizePageNumber(value: Int, index: Int): Int =
    if value > 0 then value else index + 1

  private def levelFromSubtype(value: String): Option[Int] =
    val digits = value.filter(_.isDigit)
    digits.toIntOption.filter(level => level >= 1 && level <= 6)

  private def box4(json: Option[Json]): Option[(Double, Double, Double, Double)] =
    json.flatMap(numbers).collect {
      case values if values.length >= 4 && values(2) >= values(0) =>
        (values(0), values(1), values(2), values(3))
    }

  private def positionBox(json: Option[Json]): Option[(Double, Double, Double, Double)] =
    json.flatMap(numbers).collect {
      case values if values.length >= 4 && values(2) >= 0 && values(3) >= 0 =>
        (values(0), values(1), values(0) + values(2), values(1) + values(3))
    }

  private def numbers(json: Json): Option[Chunk[Double]] =
    json match
      case Json.Arr(values) =>
        val parsed = values.flatMap(asDouble)
        Option.when(parsed.length == values.length && parsed.nonEmpty)(parsed)
      case _ => None

  private def asDouble(json: Json): Option[Double] =
    json match
      case Json.Num(value) =>
        val number = value.doubleValue
        Option.when(!number.isNaN && !number.isInfinity)(number)
      case _ => None

  private def jsonTitles(pages: Vector[RawPage]): Set[String] =
    pages
      .flatMap(_.blocks)
      .iterator
      .filter(block =>
        block.label == "doc_title" || block.label == "paragraph_title" || block.label == "title"
      )
      .map(block => normalizeTitle(splitHeading(block.text)._2))
      .filter(_.nonEmpty)
      .toSet

  /** 紧跟标题的短文本。编号小节和带句读的正文不并入标题。 */
  private def continuationLine(text: String): Option[String] =
    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    if lines.length != 1 then None
    else
      val line   = lines.head
      val points = line.codePointCount(0, line.length)
      if points == 0 || points > 24 || line.startsWith("#") || line.startsWith("|") then None
      else if line.exists(ch => "。！？；，、".contains(ch)) || inferHeadingLevel(line).nonEmpty then None
      else Some(line)

  private def continuationTexts(pages: Vector[RawPage]): Set[String] =
    pages
      .flatMap(_.blocks)
      .iterator
      .filter(_.label == "text")
      .flatMap(block => continuationLine(splitHeading(block.text)._2))
      .map(normalizeTitle)
      .toSet

  /** 标题块后面单独的短 text，且 Markdown 已经把这两行看成同一个标题时，才并进标题。 */
  private def titleContinuation(
      current: RawBlock,
      next: RawBlock,
      headings: Vector[(Int, String)]
  ): Option[String] =
    val (_, title) = splitHeading(current.text)
    val heading    = classify(current.label, title).exists(_.isLeft)
    if !heading || next.label != "text" then None
    else
      continuationLine(splitHeading(next.text)._2).filter { line =>
        val joined = normalizeTitle(title.trim + line)
        val spaced = normalizeTitle(title.trim + " " + line)
        headings.exists { case (_, headingText) =>
          val key = normalizeTitle(headingText)
          key == joined || key == spaced
        }
      }

  private def joinTitle(raw: String, rest: String): String =
    val lines    = raw.split("\n", -1)
    var replaced = false
    lines
      .map { line =>
        if !replaced && line.trim.nonEmpty then
          replaced = true
          line.trim match
            case HeadingLine(marks, title) => s"${"#".repeat(marks.length)} ${title.trim}$rest"
            case other                     => other + rest
        else line
      }
      .mkString("\n")

  /** 折行标题合成一条。允许中间有空行。后半截要么被 JSON 标题盖住，要么就是下一块短文本。 */
  private def markdownHeadings(
      markdown: String,
      titles: Set[String],
      continuations: Set[String]
  ): Vector[(Int, String)] =
    val lines  = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1).toVector
    val result = Vector.newBuilder[(Int, String)]
    var index  = 0
    while index < lines.length do
      lines(index).trim match
        case HeadingLine(marks, title) if title.trim.nonEmpty =>
          val continuation = nextContent(lines, index + 1).filter { line =>
            continuationLine(line).nonEmpty && headingContinuation(title, line, titles, continuations)
          }
          result += marks.length -> continuation.fold(title.trim)(rest => s"${title.trim}$rest")
          index = continuation match
            case Some(rest) =>
              lines.indexWhere(line => line.trim == rest, index + 1) match
                case found if found >= 0 => found + 1
                case _                   => index + 1
            case None => index + 1
        case _ => index += 1
    result.result()

  private def nextContent(lines: Vector[String], from: Int): Option[String] =
    lines.iterator.drop(from).map(_.trim).find(_.nonEmpty)

  private def headingContinuation(
      heading: String,
      line: String,
      titles: Set[String],
      continuations: Set[String]
  ): Boolean =
    val joined = normalizeTitle(heading + line)
    val spaced = normalizeTitle(heading + " " + line)
    continuations.contains(normalizeTitle(line)) || titles.exists(title => title == joined || title == spaced)

  private def splitHeading(text: String): (Option[Int], String) =
    text.linesIterator.map(_.trim).find(_.nonEmpty) match
      case Some(HeadingLine(marks, title)) if text.linesIterator.count(_.trim.nonEmpty) == 1 =>
        Some(marks.length) -> title.trim
      case _ => None -> text.trim

  private def normalizeTitle(value: String): String =
    splitHeading(value)._2.replace('\u3000', ' ').replaceAll("\\s+", "").toLowerCase

  private def relaxedTitle(value: String): String =
    normalizeTitle(value)
      .replaceAll("""[\p{P}\p{S}]""", "")

  private def clampLevel(level: Int): Int = math.max(1, math.min(6, level))

  private def clampTitle(value: String): String =
    value.replaceAll("\\s+", " ").trim.take(300)

  private def clampText(value: String): String =
    if value.length <= MaxText then value else value.take(MaxText)

  private def field(json: Json, name: String): Option[Json] =
    json match
      case Json.Obj(fields) => fields.find(_._1 == name).map(_._2)
      case _                => None

  private def stringField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(value) => value }

  private def arrayField(json: Json, name: String): Option[Chunk[Json]] =
    field(json, name).collect { case Json.Arr(values) => values }

  private def intField(json: Json, name: String): Option[Int] =
    field(json, name).collect { case Json.Num(value) => Try(value.intValue).toOption }.flatten

  private def doubleField(json: Json, name: String): Option[Double] =
    field(json, name).flatMap(asDouble)
