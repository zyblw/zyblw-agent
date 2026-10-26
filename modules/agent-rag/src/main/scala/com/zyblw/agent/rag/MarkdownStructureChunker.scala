package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import scala.collection.mutable
import zio.*

/** Markdown 结构切分策略。
  *
  * @param maxCharacters
  *   每块（含标题路径）的 Unicode code point 硬上限
  * @param overlapCharacters
  *   只有单个段落/代码行本身超限时使用的滑动 overlap；正常结构块不会被机械重复
  * @param maxHeadingDepth
  *   进入标题路径的最大 ATX heading 深度
  * @param strategyId
  *   写入 chunk metadata 的稳定算法版本；行为改变时必须提升
  */
final case class MarkdownStructureChunkerConfig(
    maxCharacters: Int = 1200,
    overlapCharacters: Int = 120,
    maxHeadingDepth: Int = 6,
    strategyId: String = "markdown-structure-v3"
):
  require(maxCharacters >= 128, "Markdown chunk maxCharacters 必须至少为 128")
  require(
    overlapCharacters >= 0 && overlapCharacters < maxCharacters,
    "Markdown chunk overlapCharacters 必须位于 0..<maxCharacters"
  )
  require(maxHeadingDepth >= 1 && maxHeadingDepth <= 6, "Markdown maxHeadingDepth 必须位于 1..6")
  require(strategyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"), "Markdown strategyId 格式无效")

/** 标题、段落、表格与 fenced code 感知的确定性 Chunker。
  *
  * 设计重点不是“猜语义”，而是先保住解析器已经恢复的文档结构：
  *
  *   - fenced code 内的 `#` 不会被误判为标题；
  *   - 正常大小的段落、列表、表格和代码块不会被字符窗口从中间切开；
  *   - 完整标题路径留在 lineage；`displayText` 只保留正文，dense/lexical 另带书名和章节前缀；
  *   - chunk ID 来自 `document + heading path + exact body` 的 SHA-256，而不是全局序号，因此前面章节插入内容不会让后面所有 ID 漂移；
  *   - metadata 保存标题路径、原始行号、正文 hash 与稳定策略版本，便于引用、评测和重建。
  *
  * PDF 到 Markdown 的版面恢复属于 `DocumentLoader` Adapter；本类只处理已经得到的 Markdown/文本，不引入 PDF、OCR 或模型依赖。
  */
final class MarkdownStructureChunker(
    config: MarkdownStructureChunkerConfig = MarkdownStructureChunkerConfig()
) extends Chunker:

  override val strategyId: String =
    s"${config.strategyId}:max=${config.maxCharacters}:overlap=${config.overlapCharacters}:depth=${config.maxHeadingDepth}"

  def split(
      document: SourceDocument,
      tenantId: TenantId,
      permissions: Set[String]
  ): UIO[Chunk[DocumentChunk]] =
    ZIO.succeed {
      val normalized      = normalize(document.text)
      val pageBreakMarker = document.metadata.get("pageBreakMarker").map(_.trim).filter(_.nonEmpty)
      val pageByLine      = pageNumbersByLine(normalized, pageBreakMarker)
      val chunkingText    = removePageBreakMarkers(normalized, pageBreakMarker)
      if normalized.trim.isEmpty then Chunk.empty
      else
        val title       = document.metadata.get("title").map(_.trim).filter(_.nonEmpty)
        val drafts      = parseSections(chunkingText).flatMap(section => chunkSection(title, section))
        val occurrences = mutable.HashMap.empty[String, Int]
        val prepared    = drafts.zipWithIndex.map { case (draft, ordinal) =>
          val identityHash = KnowledgeIndexer.sha256(
            s"${document.id}\u0000${draft.headingPath.mkString("\u001f")}\u0000${draft.body}"
          )
          val duplicateIndex = occurrences.getOrElse(identityHash, 0)
          occurrences.update(identityHash, duplicateIndex + 1)
          val duplicateSuffix = if duplicateIndex == 0 then "" else s"-${duplicateIndex + 1}"
          val chunkId         = s"${document.id.take(160)}-${identityHash.take(24)}$duplicateSuffix"
          PreparedChunk(chunkId, draft, ordinal)
        }
        Chunk.fromIterable(prepared.zipWithIndex.map { case (preparedChunk, index) =>
          val draft       = preparedChunk.draft
          val ordinal     = preparedChunk.ordinal
          val chunkId     = preparedChunk.id
          val headingPath = takeCodePoints(draft.headingPath.mkString(" > "), 1000)
          val parentHash  = KnowledgeIndexer.sha256(
            s"${document.id}\u0000section\u0000${draft.headingPath.mkString("\u001f")}"
          )
          val pages = pageBreakMarker.fold(Vector.empty[Int])(_ =>
            pageByLine
              .slice((draft.startLine - 1).max(0), draft.endLine.min(pageByLine.length))
              .distinct
          )
          val origins       = Chunk.fromIterable(pages.map(page => DocumentOrigin(page)))
          val chunkMetadata = document.metadata ++ Map(
            "chunkerId"       -> strategyId,
            "chunkOrdinal"    -> ordinal.toString,
            "chunkStartLine"  -> draft.startLine.toString,
            "chunkEndLine"    -> draft.endLine.toString,
            "chunkContentSha" -> KnowledgeIndexer.sha256(draft.body),
            "contentFormat"   -> document.representation.toString.toLowerCase(java.util.Locale.ROOT)
          ) ++ Option.when(headingPath.nonEmpty)("headingPath" -> headingPath)
          DocumentChunk.fromText(
            id = chunkId,
            documentId = document.id,
            text = draft.body,
            denseText = Some(
              ChunkContext.dense(
                draft.body,
                title,
                draft.headingPath,
                draft.kind,
                config.maxCharacters / 3
              )
            ),
            sourceUri = document.sourceUri,
            tenantId = tenantId,
            permissions = permissions,
            metadata = chunkMetadata,
            lineage = Some(
              ChunkLineage(
                parentId = Some(s"${document.id.take(160)}-section-${parentHash.take(24)}"),
                ordinal = ordinal,
                previousChunkId = prepared.lift(index - 1).map(_.id),
                nextChunkId = prepared.lift(index + 1).map(_.id),
                headingPath = Chunk.fromIterable(draft.headingPath),
                origins = origins,
                blockIds = Chunk(s"markdown-lines:${draft.startLine}-${draft.endLine}")
              )
            )
          )
        })
    }

  /** 页分隔符所在行属于前一页；分隔符之后的第一行进入下一页。 */
  private def pageNumbersByLine(markdown: String, marker: Option[String]): Vector[Int] =
    var page = 1
    markdown
      .split("\n", -1)
      .iterator
      .map { line =>
        val current = page
        if marker.exists(value => line.trim == value) then page += 1
        current
      }
      .toVector

  /** 分隔符只是定位信号，不应进入 Embedding 或最终回答。以空行替换可保持原始行号稳定。 */
  private def removePageBreakMarkers(markdown: String, marker: Option[String]): String =
    marker match
      case None        => markdown
      case Some(value) =>
        markdown.split("\n", -1).map(line => if line.trim == value then "" else line).mkString("\n")

  /** 把 ATX headings 分解为章节。代码围栏内的 heading-like 行保持普通正文。 */
  private def parseSections(markdown: String): Vector[Section] =
    val parsed   = MarkdownStructureChunker.parseRawUnits(markdown, config.maxHeadingDepth)
    val sections = mutable.ArrayBuffer.empty[Section]
    val blocks   = mutable.ArrayBuffer.empty[Block]
    var stack    = Vector.empty[(Int, String)]

    def headingPath: Vector[String] = stack.map(_._2)

    def flushSection(): Unit =
      if blocks.nonEmpty then sections += Section(headingPath, blocks.toVector)
      blocks.clear()

    parsed.foreach {
      case Left((level, title, _)) =>
        flushSection()
        val prefix     = stack.takeWhile(_._1 < level)
        val clampedLvl = prefix.lastOption.fold(1)(_._1 + 1).min(level).max(1)
        stack = prefix :+ (clampedLvl -> takeCodePoints(title, 300))
      case Right(block) =>
        blocks += Block(block.text, block.startLine, block.endLine, block.kind)
    }
    flushSection()
    sections.toVector

  /** 尽量以完整 block 装箱。表格单独成块；只有单个 block 大于可用空间时才进入硬切分。 */
  private def chunkSection(title: Option[String], section: Section): Vector[DraftChunk] =
    val proseBudget = bodyBudget(title, section.headingPath, None)
    val result      = mutable.ArrayBuffer.empty[DraftChunk]
    val pending     = mutable.ArrayBuffer.empty[Block]

    def pendingSize: Int =
      pending.iterator.map(block => codePointLength(block.text)).sum + (pending.size - 1).max(0) * 2

    def flushPending(): Unit =
      if pending.nonEmpty then
        result += DraftChunk(
          section.headingPath,
          None,
          pending.map(_.text).mkString("\n\n"),
          pending.head.startLine,
          pending.last.endLine
        )
        pending.clear()

    section.blocks.foreach { block =>
      if block.kind == BlockKind.Table then
        flushPending()
        val budget = bodyBudget(title, section.headingPath, Some("Table"))
        if codePointLength(block.text) <= budget then
          result += DraftChunk(section.headingPath, Some("Table"), block.text, block.startLine, block.endLine)
        else result ++= splitOversized(section.headingPath, Some("Table"), block, budget)
      else
        val blockSize = codePointLength(block.text)
        if blockSize > proseBudget then
          flushPending()
          result ++= splitOversized(section.headingPath, None, block, proseBudget)
        else if pending.isEmpty || pendingSize + 2 + blockSize <= proseBudget then pending += block
        else
          flushPending()
          pending += block
    }
    flushPending()
    result.toVector

  private def bodyBudget(title: Option[String], path: Vector[String], kind: Option[String]): Int =
    val head      = ChunkContext.prefix(title, path, kind, config.maxCharacters / 3)
    val separator = if head.isEmpty then 0 else 2
    (config.maxCharacters - codePointLength(head) - separator).max(1)

  /** 超大结构块优先按完整行切；超长单行才使用 code-point-safe 滑窗。 */
  private def splitOversized(
      headingPath: Vector[String],
      kind: Option[String],
      block: Block,
      maxBodySize: Int
  ): Vector[DraftChunk] =
    val lines  = block.text.split("\n", -1).toVector
    val result = mutable.ArrayBuffer.empty[DraftChunk]
    val group  = mutable.ArrayBuffer.empty[(String, Int)]

    def groupSize: Int =
      group.iterator.map(value => codePointLength(value._1)).sum + (group.size - 1).max(0)

    def flushGroup(): Unit =
      if group.nonEmpty then
        result += DraftChunk(headingPath, kind, group.map(_._1).mkString("\n"), group.head._2, group.last._2)
        group.clear()

    lines.zipWithIndex.foreach { case (line, lineOffset) =>
      val lineNumber = block.startLine + lineOffset
      if codePointLength(line) > maxBodySize then
        flushGroup()
        slidingSlices(line, maxBodySize).foreach(piece =>
          result += DraftChunk(headingPath, kind, piece, lineNumber, lineNumber)
        )
      else if group.isEmpty || groupSize + 1 + codePointLength(line) <= maxBodySize then
        group += line -> lineNumber
      else
        flushGroup()
        group += line -> lineNumber
    }
    flushGroup()
    result.toVector

  /** 使用 Unicode code point 而不是 UTF-16 索引，避免在 emoji/扩展汉字代理对中间切断。 */
  private def slidingSlices(value: String, size: Int): Vector[String] =
    val total = codePointLength(value)
    val step  = (size - config.overlapCharacters.min(size - 1)).max(1)
    Iterator
      .iterate(0)(_ + step)
      .takeWhile(_ < total)
      .map(start => sliceCodePoints(value, start, (start + size).min(total)))
      .toVector

  private def normalize(value: String): String =
    MarkdownStructureChunker.normalizeText(value)

  private def codePointLength(value: String): Int = value.codePointCount(0, value.length)

  private def sliceCodePoints(value: String, start: Int, end: Int): String =
    val startOffset = value.offsetByCodePoints(0, start)
    val endOffset   = value.offsetByCodePoints(0, end)
    value.substring(startOffset, endOffset)

  private def takeCodePoints(value: String, limit: Int): String =
    if codePointLength(value) <= limit then value else sliceCodePoints(value, 0, limit)

  private type BlockKind = MarkdownStructureChunker.BlockKind
  private val BlockKind = MarkdownStructureChunker.BlockKind

  final private case class Block(text: String, startLine: Int, endLine: Int, kind: BlockKind)
  final private case class Section(headingPath: Vector[String], blocks: Vector[Block])
  final private case class DraftChunk(
      headingPath: Vector[String],
      kind: Option[String],
      body: String,
      startLine: Int,
      endLine: Int
  )
  final private case class PreparedChunk(id: String, draft: DraftChunk, ordinal: Int)

object MarkdownStructureChunker:
  val layer: ULayer[Chunker] = ZLayer.succeed(MarkdownStructureChunker(): Chunker)

  def configured(config: MarkdownStructureChunkerConfig): ULayer[Chunker] =
    ZLayer.succeed(MarkdownStructureChunker(config): Chunker)

  private[rag] enum BlockKind:
    case Prose, Table, Code

  final private[rag] case class RawBlock(
      text: String,
      startLine: Int,
      endLine: Int,
      kind: BlockKind
  )

  private val IntroHeadingPattern =
    """^(?:引言|前言|导言|导读|绪论|序言)(?:[：:\s—\-|｜].*)?$""".r
  private val MajorHeadingPattern =
    """^(?:[一二三四五六七八九十百零〇]+[、.．]|第[一二三四五六七八九十百零〇\d]+[章节部分篇回])\s*\S+.*$""".r
  private val PlainMajorLinePattern =
    """^[一二三四五六七八九十百零〇]+、[^\n。！？；]{2,60}$""".r
  private val SubHeadingPattern =
    """^[（(][一二三四五六七八九十百零〇\d]+[）)]\s*\S+.*$""".r
  private val ArabicHeadingPattern =
    """^\d+[、.．]\s*\S+.*$""".r
  private val PlainArabicLinePattern =
    """^\d+\.\s+[^\n*。！？；]{2,50}$""".r
  private val AppendixOrConclusionPattern =
    """^(?:附[录记]?[：:\s|｜].*|结语(?:[：:\s—\-|｜].*)?|总结(?:[：:\s—\-|｜].*)?|小结(?:[：:\s—\-|｜].*)?|主要文献.*|参考文献.*)$""".r
  private val WeChatPromoLinkLine =
    """^\s*\[[^\]]*https?://mp\.weixin\.qq\.com/s/[^\]]*\]\((?:<https?://mp\.weixin\.qq\.com/s/[^>]+>|https?://mp\.weixin\.qq\.com/s/[^)\s]+)(?:\s+"[^"]*")?\)\s*$""".r
  private val PromoHeadingPattern =
    """^(?:往期回顾|往期推荐|热门阅读|延伸阅读|相关阅读|推荐阅读)$""".r

  /** 从 Markdown 文档提取规范的章节树与结构块，保留 Table/Code/Paragraph 类型并归一化失衡标题层级。 */
  def extractStructure(
      document: SourceDocument,
      maxHeadingDepth: Int = 6
  ): Option[DocumentStructure] =
    val normalized      = normalizeText(document.text)
    val pageBreakMarker = document.metadata.get("pageBreakMarker").map(_.trim).filter(_.nonEmpty)
    val pageByLine      = pageNumbersByLine(normalized, pageBreakMarker)
    val chunkingText    = removePageBreakMarkers(normalized, pageBreakMarker)
    if chunkingText.trim.isEmpty then None
    else
      val units     = parseRawUnits(chunkingText, maxHeadingDepth)
      val rawBlocks = units.collect { case Right(b) => b }
      if rawBlocks.isEmpty then None
      else
        val docTitle = document.metadata
          .get("title")
          .map(_.trim)
          .filter(_.nonEmpty)
          .getOrElse(document.id)
          .take(300)
        val sections = mutable.ArrayBuffer.empty[DocumentSection]
        val blocks   = mutable.ArrayBuffer.empty[DocumentBlock]
        var stack    = Vector.empty[(Int, String, String)] // (level, id, title)
        var secSeq   = 0
        var blkSeq   = 0

        def pageForRange(startLine: Int, endLine: Int): Vector[Int] =
          pageBreakMarker.fold(Vector.empty[Int])(_ =>
            pageByLine
              .slice((startLine - 1).max(0), endLine.min(pageByLine.length))
              .distinct
          )

        def openSection(targetLevel: Int, rawTitle: String, startLine: Int): String =
          val cleanTitle = takeCodePoints(rawTitle.trim, 300)
          val prefix     = stack.takeWhile(_._1 < targetLevel)
          val level      = prefix.lastOption.fold(1)(_._1 + 1).min(targetLevel).max(1)
          val parentId   = prefix.lastOption.map(_._2)
          val id         = s"md-sec-$secSeq"
          secSeq += 1
          val pages = pageForRange(startLine, startLine)
          sections += DocumentSection(
            id = id,
            parentId = parentId,
            ordinal = sections.length,
            level = level,
            title = if cleanTitle.nonEmpty then cleanTitle else docTitle,
            pageStart = pages.headOption,
            pageEnd = pages.lastOption
          )
          stack = prefix :+ (level, id, if cleanTitle.nonEmpty then cleanTitle else docTitle)
          id

        val hasExplicitHeadings = units.exists(_.isLeft)
        val firstUnitIsBlock    = units.headOption.exists(_.isRight)
        val firstHeadingMinLvl  = units.collect { case Left((lvl, _, _)) => lvl }.minOption.getOrElse(1)

        if !hasExplicitHeadings || firstUnitIsBlock then
          val _ = openSection(1, docTitle, 1)

        units.foreach {
          case Left((rawLvl, title, lineNum)) =>
            val shiftedLvl =
              if firstUnitIsBlock && firstHeadingMinLvl == 1 then math.min(6, rawLvl + 1)
              else rawLvl
            val _ = openSection(shiftedLvl, title, lineNum)
          case Right(raw) =>
            val currentSecId = stack.lastOption.map(_._2).getOrElse(openSection(1, docTitle, raw.startLine))
            val path         = Chunk.fromIterable(stack.map(_._3))
            val pages        = pageForRange(raw.startLine, raw.endLine)
            val origins      = Chunk.fromIterable(pages.map(p => DocumentOrigin(p)))
            val docKind      = raw.kind match
              case BlockKind.Table => DocumentBlockKind.Table
              case BlockKind.Code  => DocumentBlockKind.Code
              case BlockKind.Prose => DocumentBlockKind.Paragraph
            val blockId = s"md-blk-$blkSeq"
            blkSeq += 1
            blocks += DocumentBlock(
              id = blockId,
              parentId = Some(currentSecId),
              ordinal = blocks.length,
              kind = docKind,
              text = raw.text,
              headingPath = path,
              origins = origins
            )
        }

        val prunedSections = pruneAndRangeSections(sections.toVector, blocks.toVector)
        if prunedSections.isEmpty || blocks.isEmpty then None
        else
          Some(
            DocumentStructure(
              schemaName = "markdown-sections",
              schemaVersion = Some("1"),
              blocks = Chunk.fromIterable(blocks),
              sections = Chunk.fromIterable(prunedSections)
            )
          )

  private def pruneAndRangeSections(
      sections: Vector[DocumentSection],
      blocks: Vector[DocumentBlock]
  ): Vector[DocumentSection] =
    val directBlocks = blocks.groupBy(_.parentId)
    val childrenById = sections.groupBy(_.parentId)

    def hasSubtreeBlocks(id: String, seen: Set[String]): Boolean =
      if seen.contains(id) then false
      else
        directBlocks.get(Some(id)).exists(_.nonEmpty) ||
        childrenById.getOrElse(Some(id), Vector.empty).exists(c => hasSubtreeBlocks(c.id, seen + id))

    val kept         = sections.filter(s => hasSubtreeBlocks(s.id, Set.empty))
    val keptChildren = kept.groupBy(_.parentId)

    def pagesOf(id: String, seen: Set[String]): List[Int] =
      if seen.contains(id) then Nil
      else
        val own = directBlocks.getOrElse(Some(id), Vector.empty).flatMap(_.origins.map(_.pageNumber)).toList
        val nested =
          keptChildren.getOrElse(Some(id), Vector.empty).flatMap(c => pagesOf(c.id, seen + id)).toList
        own ++ nested

    kept.zipWithIndex.map { case (section, idx) =>
      val pages = pagesOf(section.id, Set.empty)
      val start = (section.pageStart.toList ++ pages).minOption
      val end   = (section.pageEnd.toList ++ pages).maxOption.orElse(start)
      section.copy(ordinal = idx, pageStart = start, pageEnd = end)
    }

  private[rag] def parseRawUnits(
      markdown: String,
      maxHeadingDepth: Int
  ): Vector[Either[(Int, String, Int), RawBlock]] =
    val lines       = markdown.split("\n", -1).toVector
    val hasMajorAtx = lines.exists { line =>
      heading(line.trim, maxHeadingDepth).exists((_, t) =>
        MajorHeadingPattern.matches(t.trim) || SubHeadingPattern.matches(t.trim)
      )
    }
    val hasArabicAtx = lines.exists { line =>
      heading(line.trim, maxHeadingDepth).exists((_, t) => ArabicHeadingPattern.matches(t.trim))
    }
    val rawUnits     = mutable.ArrayBuffer.empty[Either[(Int, String, Int), RawBlock]]
    val currentLines = mutable.ArrayBuffer.empty[(String, Int)]
    var fence        = Option.empty[String]
    var inPromoSec   = false

    def flushBlock(): Unit =
      if currentLines.nonEmpty then
        val nonEmptyLines = currentLines.map(_._1).filter(_.trim.nonEmpty)
        val filteredLines = nonEmptyLines.filterNot(line => WeChatPromoLinkLine.matches(line.trim))
        val text          = currentLines
          .map(_._1)
          .filterNot(line => WeChatPromoLinkLine.matches(line.trim))
          .mkString("\n")
          .trim
        if text.nonEmpty && filteredLines.nonEmpty && !inPromoSec then
          rawUnits += Right(
            RawBlock(
              text,
              currentLines.head._2,
              currentLines.last._2,
              blockKind(currentLines.map(_._1))
            )
          )
        currentLines.clear()

    lines.zipWithIndex.foreach { case (line, zeroBasedLine) =>
      val lineNumber = zeroBasedLine + 1
      val trimmed    = line.trim
      fence match
        case Some(marker) =>
          currentLines += line -> lineNumber
          if trimmed.startsWith(marker) then fence = None
        case None =>
          fenceMarker(trimmed) match
            case Some(marker) =>
              currentLines += line -> lineNumber
              fence = Some(marker)
            case None =>
              val prevEmpty       = zeroBasedLine == 0 || lines(zeroBasedLine - 1).trim.isEmpty
              val nextEmpty       = zeroBasedLine + 1 >= lines.length || lines(zeroBasedLine + 1).trim.isEmpty
              val detectedHeading = heading(trimmed, maxHeadingDepth).orElse {
                if hasMajorAtx && currentLines.isEmpty && prevEmpty && nextEmpty && PlainMajorLinePattern
                    .matches(trimmed)
                then Some(2 -> trimmed)
                else if hasArabicAtx && currentLines.isEmpty && prevEmpty && nextEmpty && PlainArabicLinePattern
                    .matches(trimmed)
                then Some(3 -> trimmed)
                else None
              }
              detectedHeading match
                case Some((level, title)) =>
                  flushBlock()
                  if PromoHeadingPattern.matches(title.trim) then inPromoSec = true
                  else
                    inPromoSec = false
                    rawUnits += Left((level, title.trim, lineNumber))
                case None if trimmed.isEmpty => flushBlock()
                case None                    => currentLines += line -> lineNumber
    }
    flushBlock()
    normalizeHeadingUnits(rawUnits.toVector)

  /** 当文章同时包含中文主章号（一、二、三…）但井号深度失衡（如 `# 引言` 后跟 `## 一、` 或 `## 二、` 后跟 `### 三、`）时， 将同级结构序号对齐到一致的层级。普通无序号 Markdown
    * 保持原井号不变。
    */
  private def normalizeHeadingUnits(
      units: Vector[Either[(Int, String, Int), RawBlock]]
  ): Vector[Either[(Int, String, Int), RawBlock]] =
    val headings = units.collect { case Left(h) => h }
    val majors   = headings.filter((_, title, _) => MajorHeadingPattern.matches(title))
    if majors.isEmpty then units
    else
      val firstIsDocTitle = headings.headOption.exists { case (lvl, title, _) =>
        lvl == 1 &&
        !IntroHeadingPattern.matches(title) &&
        !MajorHeadingPattern.matches(title) &&
        !SubHeadingPattern.matches(title) &&
        !ArabicHeadingPattern.matches(title) &&
        !AppendixOrConclusionPattern.matches(title) &&
        majors.forall(_._1 >= 2)
      }
      val hasIntroH1 = headings.headOption.exists { case (lvl, title, _) =>
        lvl == 1 && IntroHeadingPattern.matches(title)
      }
      val majorLevels        = majors.map(_._1).distinct
      val hasParenSubs       = headings.exists((_, t, _) => SubHeadingPattern.matches(t))
      val arabicOffset       = if hasParenSubs then 2 else 1
      val needsNormalization =
        hasIntroH1 || majorLevels.length > 1 ||
          headings.exists((_, t, _) =>
            AppendixOrConclusionPattern.matches(t) || ArabicHeadingPattern.matches(t)
          )
      if !needsNormalization then units
      else
        val baseMajor          = if firstIsDocTitle then 2 else 1
        var seenMajor          = false
        var currentMajorRawLvl = majors.head._1
        units.map {
          case Right(block)             => Right(block)
          case Left((lvl, title, line)) =>
            val normLvl =
              if firstIsDocTitle && line == headings.head._3 then 1
              else if IntroHeadingPattern.matches(title) then baseMajor
              else if MajorHeadingPattern.matches(title) then
                seenMajor = true
                currentMajorRawLvl = lvl
                baseMajor
              else if AppendixOrConclusionPattern.matches(title) && seenMajor then baseMajor
              else if SubHeadingPattern.matches(title) then math.min(6, baseMajor + 1)
              else if ArabicHeadingPattern.matches(title) then math.min(6, baseMajor + arabicOffset)
              else if seenMajor && lvl > currentMajorRawLvl then
                math.min(6, baseMajor + (lvl - currentMajorRawLvl))
              else lvl
            Left((normLvl, title, line))
        }

  private def normalizeText(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")

  private def pageNumbersByLine(markdown: String, marker: Option[String]): Vector[Int] =
    var page = 1
    markdown
      .split("\n", -1)
      .iterator
      .map { line =>
        val current = page
        if marker.exists(value => line.trim == value) then page += 1
        current
      }
      .toVector

  private def removePageBreakMarkers(markdown: String, marker: Option[String]): String =
    marker match
      case None        => markdown
      case Some(value) =>
        markdown.split("\n", -1).map(line => if line.trim == value then "" else line).mkString("\n")

  private def heading(value: String, maxHeadingDepth: Int): Option[(Int, String)] =
    val hashes = value.takeWhile(_ == '#').length
    if hashes >= 1 && hashes <= maxHeadingDepth && value.drop(hashes).startsWith(" ") then
      val title = value
        .drop(hashes)
        .trim
        .reverse
        .dropWhile(_ == '#')
        .reverse
        .trim
      Option.when(title.nonEmpty)(hashes -> title)
    else None

  private def fenceMarker(value: String): Option[String] =
    if value.startsWith("```") then Some("```")
    else if value.startsWith("~~~") then Some("~~~")
    else None

  private def blockKind(lines: Iterable[String]): BlockKind =
    val values = lines.iterator.map(_.trim).toVector
    if values.headOption.exists(value => fenceMarker(value).nonEmpty) then BlockKind.Code
    else if values.length >= 2 && values.head.contains("|") && values(1).matches("\\|?\\s*:?-{3,}.*") then
      BlockKind.Table
    else BlockKind.Prose

  private def takeCodePoints(value: String, limit: Int): String =
    val len = value.codePointCount(0, value.length)
    if len <= limit then value
    else value.substring(0, value.offsetByCodePoints(0, limit))
