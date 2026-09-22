package com.zyblw.agent.rag

import zio.Chunk

/** 数字 PDF 文本层的确定性结构恢复：识别目录噪声、恢复章节层级、把碎行重排成段落。
  *
  * 视觉/OCR 结果同样可以走这里做二次规整。模型输出只作为输入数据，本对象不执行任何远程调用。
  */
object PdfTextStructure:
  private val TocLeaders      = """[.…·•˙]{2,}|\.{3,}""".r
  private val TrailingPage    = """[\s　]+[0-9０-９ivxlcdmIVXLCDM]{1,5}\s*$""".r
  private val Chapter         = """^第[一二三四五六七八九十百千万0-9０-９]+[章节篇卷部].*""".r
  private val EnglishChapter  = """(?i)^(?:chapter|part|section)\s+[0-9ivxlcdm]+(?:\b|[.:：]).*""".r
  private val NumberedSection = """^[一二三四五六七八九十百千]+[、.].+""".r
  private val ParenSection    = """^[（(][一二三四五六七八九十0-9]+[）)].+""".r
  private val DecimalSection  = """^\d{1,2}(?:\.\d{1,2}){0,3}[\s.、].+""".r
  private val SentenceEnd     = """[。！？；…:.!?;]$""".r
  private val HeaderFooter    = """^(?:—+\s*)?\d{1,4}(?:\s*—+)?$|^第\s*\d+\s*页$""".r

  final case class HeadingHint(level: Int, title: String, tocStyle: Boolean)

  def isTocStyle(text: String): Boolean =
    val line = text.trim
    line.nonEmpty && (TocLeaders.findFirstIn(line).isDefined ||
      (TrailingPage.findFirstIn(line).isDefined && line.length <= 80 && !looksLikeBodySentence(line)))

  def looksLikeHeaderFooter(text: String): Boolean =
    val line = text.trim
    line.nonEmpty && (HeaderFooter.matches(line) || (line.length <= 4 && line.forall(_.isDigit)))

  def headingHint(text: String): Option[HeadingHint] =
    val line = normalizeTitle(text)
    if line.isEmpty || line.length > 80 || looksLikeHeaderFooter(line) then None
    else if Chapter.matches(line) || EnglishChapter.matches(line) then
      Some(
        HeadingHint(
          if line.contains("节") || line.toLowerCase.contains("section") then 2 else 1,
          line,
          isTocStyle(text)
        )
      )
    else if NumberedSection.matches(line) then Some(HeadingHint(2, line, isTocStyle(text)))
    else if ParenSection.matches(line) || DecimalSection.matches(line) then
      Some(HeadingHint(3, line, isTocStyle(text)))
    else None

  def normalizeTitle(text: String): String =
    TrailingPage
      .replaceFirstIn(TocLeaders.replaceAllIn(text.replace('\u3000', ' ').trim, ""), "")
      .replaceAll("\\s+", " ")
      .trim

  /** 把逐行块重排为可读段落，并丢掉目录页上的伪章节。 */
  def normalize(blocks: Chunk[DocumentBlock]): Chunk[DocumentBlock] =
    val tocPages = detectTocPages(blocks)
    val prepared = blocks.zipWithIndex.flatMap { case (block, index) =>
      val text = block.text.trim
      if text.isEmpty then None
      else if looksLikeHeaderFooter(text) then
        Some(block.copy(kind = DocumentBlockKind.Other, headingPath = Chunk.empty, ordinal = index))
      else
        val hint      = headingHint(text)
        val onTocPage = block.origins.headOption.exists(origin => tocPages.contains(origin.pageNumber))
        if hint.exists(_.tocStyle) || (onTocPage && hint.isDefined) then None
        else if hint.exists(!_.tocStyle) then
          val level = hint.map(_.level).getOrElse(1)
          Some(
            block.copy(
              kind = if level == 1 then DocumentBlockKind.Title else DocumentBlockKind.SectionHeading,
              text = hint.map(_.title).getOrElse(text),
              headingPath = Chunk(hint.map(_.title).getOrElse(text)),
              ordinal = index
            )
          )
        else Some(block.copy(kind = DocumentBlockKind.Paragraph, ordinal = index))
    }
    val merged = mergeParagraphs(prepared)
    numberAndPath(merged)

  def sectionPlan(blocks: Chunk[DocumentBlock]): Chunk[ExtractedHeading] =
    Chunk.fromIterable(
      blocks
        .collect {
          case block
              if block.kind == DocumentBlockKind.Title || block.kind == DocumentBlockKind.SectionHeading =>
            ExtractedHeading(
              level =
                if block.kind == DocumentBlockKind.Title then 1
                else math.max(1, block.headingPath.length).min(6),
              title = block.text.trim.take(300),
              pageNumber = block.origins.headOption.map(_.pageNumber),
              blockId = Some(block.id)
            )
        }
        .filter(heading => heading.title.nonEmpty && !isTocStyle(heading.title))
    )

  private def detectTocPages(blocks: Chunk[DocumentBlock]): Set[Int] =
    blocks
      .flatMap { block =>
        block.origins.headOption.map(_.pageNumber -> isTocStyle(block.text))
      }
      .groupBy(_._1)
      .collect {
        case (page, rows)
            if rows.count(_._2) >= 4 && rows.count(_._2).toDouble / rows.length.toDouble >= 0.4 =>
          page
      }
      .toSet

  private def mergeParagraphs(blocks: Chunk[DocumentBlock]): Chunk[DocumentBlock] =
    val merged = scala.collection.mutable.ArrayBuffer.empty[DocumentBlock]
    blocks.foreach { block =>
      merged.lastOption match
        case Some(previous)
            if previous.kind == DocumentBlockKind.Paragraph &&
              block.kind == DocumentBlockKind.Paragraph &&
              sameOrAdjacentPage(previous, block) &&
              shouldJoin(previous.text, block.text) =>
          merged.update(
            merged.length - 1,
            previous.copy(
              text = joinText(previous.text, block.text),
              origins = previous.origins ++ block.origins.filterNot(origin =>
                previous.origins.exists(existing =>
                  existing.pageNumber == origin.pageNumber && existing.blockId == origin.blockId
                )
              )
            )
          )
        case _ => merged += block
    }
    Chunk.fromIterable(merged)

  private def shouldJoin(previous: String, next: String): Boolean =
    val left  = previous.trim
    val right = next.trim
    left.nonEmpty && right.nonEmpty &&
    !headingHint(right).exists(!_.tocStyle) &&
    (SentenceEnd.findFirstIn(left).isEmpty || startsLikeContinuation(right))

  private def startsLikeContinuation(text: String): Boolean =
    text.nonEmpty && text.head.isLower && headingHint(text).isEmpty

  private def joinText(previous: String, next: String): String =
    val left  = previous.trim
    val right = next.trim
    if left.lastOption.exists(ch => Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) then
      left + right
    else s"$left $right"

  private def sameOrAdjacentPage(left: DocumentBlock, right: DocumentBlock): Boolean =
    val leftPage  = left.origins.headOption.map(_.pageNumber)
    val rightPage = right.origins.headOption.map(_.pageNumber)
    (leftPage, rightPage) match
      case (Some(a), Some(b)) => math.abs(a - b) <= 1
      case _                  => true

  private def numberAndPath(blocks: Chunk[DocumentBlock]): Chunk[DocumentBlock] =
    var path       = Chunk.empty[String]
    var headingIds = Chunk.empty[String]
    Chunk.fromIterable(blocks.zipWithIndex.map { case (block, index) =>
      if block.kind == DocumentBlockKind.Title || block.kind == DocumentBlockKind.SectionHeading then
        val level =
          if block.kind == DocumentBlockKind.Title then 1
          else math.max(1, headingHint(block.text).map(_.level).getOrElse(2))
        path = path.take(level - 1) :+ block.text
        val parent = headingIds.take(level - 1).lastOption
        headingIds = headingIds.take(level - 1) :+ block.id
        block.copy(ordinal = index, headingPath = path, parentId = parent)
      else block.copy(ordinal = index, headingPath = path, parentId = headingIds.lastOption)
    })

  private def looksLikeBodySentence(text: String): Boolean =
    text.length > 40 || SentenceEnd.findFirstIn(text.trim).isDefined
