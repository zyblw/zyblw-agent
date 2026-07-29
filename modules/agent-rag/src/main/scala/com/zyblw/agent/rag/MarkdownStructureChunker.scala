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
    strategyId: String = "markdown-structure-v1"
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
  *   - 每块正文前重建完整标题路径，让 Embedding 与最终 Context 都能看见章节语境；
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
      val normalized = normalize(document.text)
      if normalized.trim.isEmpty then Chunk.empty
      else
        val drafts      = parseSections(normalized).flatMap(chunkSection)
        val occurrences = mutable.HashMap.empty[String, Int]
        Chunk.fromIterable(drafts.zipWithIndex.map { case (draft, ordinal) =>
          val identityHash = KnowledgeIndexer.sha256(
            s"${document.id}\u0000${draft.headingPath.mkString("\u001f")}\u0000${draft.body}"
          )
          val duplicateIndex = occurrences.getOrElse(identityHash, 0)
          occurrences.update(identityHash, duplicateIndex + 1)
          val duplicateSuffix = if duplicateIndex == 0 then "" else s"-${duplicateIndex + 1}"
          val chunkId         = s"${document.id.take(160)}-${identityHash.take(24)}$duplicateSuffix"
          val headingPath     = takeCodePoints(draft.headingPath.mkString(" > "), 1000)
          val chunkMetadata   = document.metadata ++ Map(
            "chunkerId"       -> strategyId,
            "chunkOrdinal"    -> ordinal.toString,
            "chunkStartLine"  -> draft.startLine.toString,
            "chunkEndLine"    -> draft.endLine.toString,
            "chunkContentSha" -> KnowledgeIndexer.sha256(draft.body),
            "contentFormat"   -> document.representation.toString.toLowerCase(java.util.Locale.ROOT)
          ) ++ Option.when(headingPath.nonEmpty)("headingPath" -> headingPath)
          DocumentChunk(
            id = chunkId,
            documentId = document.id,
            text = render(draft.headingPath, draft.body),
            sourceUri = document.sourceUri,
            tenantId = tenantId,
            permissions = permissions,
            metadata = chunkMetadata
          )
        })
    }

  /** 把 ATX headings 分解为章节。代码围栏内的 heading-like 行保持普通正文。 */
  private def parseSections(markdown: String): Vector[Section] =
    val sections              = mutable.ArrayBuffer.empty[Section]
    val blocks                = mutable.ArrayBuffer.empty[Block]
    val currentLines          = mutable.ArrayBuffer.empty[(String, Int)]
    var headingPath           = Vector.empty[String]
    var fence: Option[String] = None

    def flushBlock(): Unit =
      if currentLines.nonEmpty then
        val text = currentLines.map(_._1).mkString("\n").trim
        if text.nonEmpty then
          blocks += Block(text, currentLines.head._2, currentLines.last._2, blockKind(currentLines.map(_._1)))
        currentLines.clear()

    def flushSection(): Unit =
      flushBlock()
      if blocks.nonEmpty then sections += Section(headingPath, blocks.toVector)
      blocks.clear()

    markdown.split("\n", -1).iterator.zipWithIndex.foreach { case (line, zeroBasedLine) =>
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
              heading(trimmed) match
                case Some((level, title)) =>
                  flushSection()
                  headingPath = headingPath.take(level - 1) :+ takeCodePoints(title, 300)
                case None if trimmed.isEmpty => flushBlock()
                case None                    => currentLines += line -> lineNumber
    }
    flushSection()
    sections.toVector

  /** 尽量以完整 block 装箱；只有单个 block 大于可用空间时才进入硬切分。 */
  private def chunkSection(section: Section): Vector[DraftChunk] =
    val prefix      = renderHeadingPrefix(section.headingPath)
    val prefixSize  = codePointLength(prefix)
    val separator   = if prefix.isEmpty then 0 else 2
    val maxBodySize = (config.maxCharacters - prefixSize - separator).max(1)
    val result      = mutable.ArrayBuffer.empty[DraftChunk]
    val pending     = mutable.ArrayBuffer.empty[Block]

    def pendingSize: Int =
      pending.iterator.map(block => codePointLength(block.text)).sum + (pending.size - 1).max(0) * 2

    def flushPending(): Unit =
      if pending.nonEmpty then
        result += DraftChunk(
          section.headingPath,
          pending.map(_.text).mkString("\n\n"),
          pending.head.startLine,
          pending.last.endLine
        )
        pending.clear()

    section.blocks.foreach { block =>
      val blockSize = codePointLength(block.text)
      if blockSize > maxBodySize then
        flushPending()
        result ++= splitOversized(section.headingPath, block, maxBodySize)
      else if pending.isEmpty || pendingSize + 2 + blockSize <= maxBodySize then pending += block
      else
        flushPending()
        pending += block
    }
    flushPending()
    result.toVector

  /** 超大结构块优先按完整行切；超长单行才使用 code-point-safe 滑窗。 */
  private def splitOversized(
      headingPath: Vector[String],
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
        result += DraftChunk(headingPath, group.map(_._1).mkString("\n"), group.head._2, group.last._2)
        group.clear()

    lines.zipWithIndex.foreach { case (line, lineOffset) =>
      val lineNumber = block.startLine + lineOffset
      if codePointLength(line) > maxBodySize then
        flushGroup()
        slidingSlices(line, maxBodySize).foreach(piece =>
          result += DraftChunk(headingPath, piece, lineNumber, lineNumber)
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

  /** 标题路径最多占块预算三分之一；超长标题不会挤掉全部正文。 */
  private def renderHeadingPrefix(path: Vector[String]): String =
    val raw = path
      .take(config.maxHeadingDepth)
      .zipWithIndex
      .map { case (title, index) =>
        s"${"#" * (index + 1)} $title"
      }
      .mkString("\n")
    takeCodePoints(raw, config.maxCharacters / 3)

  private def render(path: Vector[String], body: String): String =
    val prefix = renderHeadingPrefix(path)
    if prefix.isEmpty then body else s"$prefix\n\n$body"

  private def normalize(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")

  private def heading(value: String): Option[(Int, String)] =
    val hashes = value.takeWhile(_ == '#').length
    if hashes >= 1 && hashes <= config.maxHeadingDepth && value.drop(hashes).startsWith(" ") then
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

  private def codePointLength(value: String): Int = value.codePointCount(0, value.length)

  private def sliceCodePoints(value: String, start: Int, end: Int): String =
    val startOffset = value.offsetByCodePoints(0, start)
    val endOffset   = value.offsetByCodePoints(0, end)
    value.substring(startOffset, endOffset)

  private def takeCodePoints(value: String, limit: Int): String =
    if codePointLength(value) <= limit then value else sliceCodePoints(value, 0, limit)

  private enum BlockKind:
    case Prose, Table, Code

  final private case class Block(text: String, startLine: Int, endLine: Int, kind: BlockKind)
  final private case class Section(headingPath: Vector[String], blocks: Vector[Block])
  final private case class DraftChunk(
      headingPath: Vector[String],
      body: String,
      startLine: Int,
      endLine: Int
  )

object MarkdownStructureChunker:
  val layer: ULayer[Chunker] = ZLayer.succeed(MarkdownStructureChunker(): Chunker)

  def configured(config: MarkdownStructureChunkerConfig): ULayer[Chunker] =
    ZLayer.succeed(MarkdownStructureChunker(config): Chunker)
