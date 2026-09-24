package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import scala.collection.mutable
import zio.*

/** 针对 Docling/OCR 结构文档的确定性切分配置。
  *
  * @param maxCharacters
  *   每个 embedding 文本的 Unicode code point 硬上限
  * @param overlapCharacters
  *   只在单个原始 block 本身超限时使用
  * @param mergePeers
  *   是否合并相邻、同父级且同标题路径的小 block
  */
final case class DocumentStructureChunkerConfig(
    maxCharacters: Int = 1200,
    overlapCharacters: Int = 120,
    mergePeers: Boolean = true,
    strategyVersion: String = "document-structure-v3",
    /** token 装箱预算。默认计数器是 cl100k，只适用于声明了同一 tokenizer 的 Embedding；`maxCharacters` 是硬性安全上限。 */
    maxTokens: Option[Int] = Some(512),
    tokenCounter: TokenCounter = TokenCounter.Cl100k
):
  require(maxCharacters >= 128, "structure chunk maxCharacters 必须至少为 128")
  require(overlapCharacters >= 0 && overlapCharacters < maxCharacters, "structure chunk overlap 无效")
  require(strategyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"), "strategyVersion 格式无效")
  require(maxTokens.forall(_ >= 32), "structure chunk maxTokens 必须至少为 32")

/** 直接基于结构 block 切分，保留页码、bbox、block ID、父级与阅读顺序。
  *
  * 实现遵循 Docling HybridChunker 的两个核心原则：先尊重文档结构，再合并同标题/同父级的相邻小块；只有单元素超过硬上限时才机械切分。 当 Loader 没有返回 structure
  * 时，显式降级到 `MarkdownStructureChunker`，不伪造 bbox。
  */
final class DocumentStructureChunker(
    config: DocumentStructureChunkerConfig = DocumentStructureChunkerConfig(),
    fallback: Chunker = MarkdownStructureChunker()
) extends Chunker:

  override val strategyId: String =
    val base =
      s"${config.strategyVersion}:max=${config.maxCharacters}:overlap=${config.overlapCharacters}:merge=${config.mergePeers}"
    config.maxTokens.fold(base)(limit => s"$base:tokens=$limit:counter=${config.tokenCounter.id}")

  def split(
      document: SourceDocument,
      tenantId: TenantId,
      permissions: Set[String]
  ): UIO[Chunk[DocumentChunk]] =
    document.structure match
      case None            => fallback.split(document, tenantId, permissions)
      case Some(structure) => ZIO.succeed(build(document, structure, tenantId, permissions))

  private def build(
      document: SourceDocument,
      structure: DocumentStructure,
      tenantId: TenantId,
      permissions: Set[String]
  ): Chunk[DocumentChunk] =
    val title    = document.metadata.get("title").map(_.trim).filter(_.nonEmpty)
    val drafts   = pack(structure.blocks.sortBy(_.ordinal), title)
    val prepared = drafts.zipWithIndex.map { case (draft, ordinal) =>
      val identity = KnowledgeIndexer.sha256(
        s"${document.id}\u0000${draft.blockIds.mkString("\u001f")}\u0000${draft.text}"
      )
      Prepared(s"${document.id.take(160)}-${identity.take(24)}", draft, ordinal)
    }
    Chunk.fromIterable(prepared.zipWithIndex.map { case (preparedChunk, index) =>
      val draft      = preparedChunk.draft
      val parentSeed = draft.parentId.getOrElse("root")
      val parentHash = KnowledgeIndexer.sha256(s"${document.id}\u0000structure-parent\u0000$parentSeed")
      val metadata   = document.metadata ++ Map(
        "chunkerId"       -> strategyId,
        "chunkOrdinal"    -> preparedChunk.ordinal.toString,
        "chunkContentSha" -> KnowledgeIndexer.sha256(draft.text),
        "contentFormat"   -> document.representation.toString.toLowerCase(java.util.Locale.ROOT)
      ) ++ Option.when(draft.headingPath.nonEmpty)("headingPath" -> draft.headingPath.mkString(" > "))
      DocumentChunk.fromText(
        id = preparedChunk.id,
        documentId = document.id,
        text = draft.text,
        denseText = Some(dense(title, draft.headingPath, draft.kind, draft.text)),
        sourceUri = document.sourceUri,
        tenantId = tenantId,
        permissions = permissions,
        metadata = metadata,
        lineage = Some(
          ChunkLineage(
            parentId = Some(s"${document.id.take(160)}-parent-${parentHash.take(24)}"),
            ordinal = preparedChunk.ordinal,
            previousChunkId = prepared.lift(index - 1).map(_.id),
            nextChunkId = prepared.lift(index + 1).map(_.id),
            headingPath = draft.headingPath,
            origins = draft.origins,
            blockIds = draft.blockIds
          )
        )
      )
    })

  /** 按阅读顺序装箱。表格、方剂和键值块不与正文合并；其余块只在同父级、同标题时合并。 */
  private def pack(blocks: Chunk[DocumentBlock], title: Option[String]): Vector[Draft] =
    val result  = mutable.ArrayBuffer.empty[Draft]
    val pending = mutable.ArrayBuffer.empty[DocumentBlock]

    def pendingText: String = pending.map(_.text).mkString("\n\n")
    def flush(): Unit       =
      if pending.nonEmpty then
        result += fromBlocks(pending.toVector)
        pending.clear()

    blocks.foreach { block =>
      if atomic(block.kind) then
        flush()
        if fits(title, block.headingPath, Some(block.kind), block.text) then
          result += fromBlocks(Vector(block))
        else result ++= splitOversized(title, block)
      else if !fits(title, block.headingPath, Some(block.kind), block.text) then
        flush()
        result ++= splitOversized(title, block)
      else if pending.isEmpty then pending += block
      else
        val sameContext =
          pending.last.parentId == block.parentId && pending.last.headingPath == block.headingPath
        val merged     = pendingText + "\n\n" + block.text
        val mergedKind = uniformKind(pending.toVector :+ block)
        if config.mergePeers && sameContext && fits(title, block.headingPath, mergedKind, merged) then
          pending += block
        else
          flush()
          pending += block
    }
    flush()
    result.toVector

  private def atomic(kind: DocumentBlockKind): Boolean =
    kind == DocumentBlockKind.Table || kind == DocumentBlockKind.Formula || kind == DocumentBlockKind.KeyValue

  private def uniformKind(blocks: Seq[DocumentBlock]): Option[DocumentBlockKind] =
    val kinds = blocks.map(_.kind).distinct
    if kinds.size == 1 then Some(kinds.head) else None

  private def fromBlocks(blocks: Vector[DocumentBlock]): Draft =
    Draft(
      blocks.head.parentId,
      blocks.head.headingPath,
      uniformKind(blocks),
      blocks.map(_.text).mkString("\n\n"),
      Chunk.fromIterable(blocks.flatMap(_.origins).distinct),
      Chunk.fromIterable(blocks.map(_.id))
    )

  private def splitOversized(title: Option[String], block: DocumentBlock): Vector[Draft] =
    block.kind match
      case DocumentBlockKind.Table                                => splitTable(title, block)
      case DocumentBlockKind.Formula | DocumentBlockKind.KeyValue =>
        val lines = splitLines(title, block)
        if lines.nonEmpty then lines else splitCharacters(title, block)
      case _ => splitCharacters(title, block)

  /** 表格按行组切开，每段重复表头，不从单元格中间断开。 */
  private def splitTable(title: Option[String], block: DocumentBlock): Vector[Draft] =
    val lines  = block.text.split("\n", -1).toVector
    val header =
      if lines.length >= 2 && lines(1).trim.startsWith("|") && lines(1).contains("---") then
        Some(lines(0) + "\n" + lines(1))
      else None
    val rows = header.fold(lines)(_ => lines.drop(2)).filter(_.trim.nonEmpty)
    if header.isEmpty || rows.isEmpty then splitCharacters(title, block)
    else
      val built         = Vector.newBuilder[Draft]
      val pending       = mutable.ArrayBuffer.empty[String]
      def flush(): Unit =
        if pending.nonEmpty then
          val text = header.get + "\n" + pending.mkString("\n")
          built += draftOf(block, text)
          pending.clear()
      rows.foreach { row =>
        val candidate = header.get + "\n" + (pending.toVector :+ row).mkString("\n")
        if pending.nonEmpty && !fits(title, block.headingPath, Some(block.kind), candidate) then flush()
        if fits(title, block.headingPath, Some(block.kind), header.get + "\n" + row) then pending += row
        else built ++= splitCharacters(title, block.copy(text = header.get + "\n" + row))
      }
      flush()
      val result = built.result()
      if result.nonEmpty then result else splitCharacters(title, block)

  /** 方剂和键值按完整行切开。只有放不进预算的那一行才按字切开。 */
  private def splitLines(title: Option[String], block: DocumentBlock): Vector[Draft] =
    val lines         = block.text.split("\n", -1).toVector
    val built         = Vector.newBuilder[Draft]
    val pending       = mutable.ArrayBuffer.empty[String]
    def flush(): Unit =
      if pending.nonEmpty then
        built += draftOf(block, pending.mkString("\n"))
        pending.clear()
    lines.foreach { line =>
      if line.isEmpty && pending.isEmpty then ()
      else if !fits(title, block.headingPath, Some(block.kind), line) then
        flush()
        if line.nonEmpty then built ++= splitCharacters(title, block.copy(text = line))
      else
        val candidate = if pending.isEmpty then line else pending.mkString("\n") + "\n" + line
        if pending.nonEmpty && !fits(title, block.headingPath, Some(block.kind), candidate) then flush()
        pending += line
    }
    flush()
    built.result()

  private def splitCharacters(title: Option[String], block: DocumentBlock): Vector[Draft] =
    val size = availableBody(title, block.headingPath, Some(block.kind)).max(1)
    val step = (size - config.overlapCharacters.min(size - 1)).max(1)
    Iterator
      .iterate(0)(_ + step)
      .takeWhile(_ < codePoints(block.text))
      .map { start =>
        val text = slice(block.text, start, (start + size).min(codePoints(block.text)))
        draftOf(block, text)
      }
      .toVector

  private def draftOf(block: DocumentBlock, text: String): Draft =
    Draft(block.parentId, block.headingPath, Some(block.kind), text, block.origins, Chunk(block.id))

  private def availableBody(
      title: Option[String],
      path: Chunk[String],
      kind: Option[DocumentBlockKind]
  ): Int =
    val head       = ChunkContext.prefix(title, path.toSeq, kind.map(_.toString), config.maxCharacters / 3)
    val separator  = if head.isEmpty then 0 else 2
    val charBudget = (config.maxCharacters - codePoints(head) - separator).max(1)
    config.maxTokens.fold(charBudget)(limit => charBudget.min(limit).max(1))

  private def fits(
      title: Option[String],
      path: Chunk[String],
      kind: Option[DocumentBlockKind],
      body: String
  ): Boolean =
    val rendered = dense(title, path, kind, body)
    codePoints(rendered) <= config.maxCharacters &&
    config.maxTokens.forall(limit => config.tokenCounter.count(rendered) <= limit)

  private def dense(
      title: Option[String],
      path: Chunk[String],
      kind: Option[DocumentBlockKind],
      body: String
  ): String =
    ChunkContext.dense(body, title, path.toSeq, kind.map(_.toString), config.maxCharacters / 3)

  private def codePoints(value: String): Int = value.codePointCount(0, value.length)

  private def slice(value: String, start: Int, end: Int): String =
    value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end))

  final private case class Draft(
      parentId: Option[String],
      headingPath: Chunk[String],
      kind: Option[DocumentBlockKind],
      text: String,
      origins: Chunk[DocumentOrigin],
      blockIds: Chunk[String]
  )
  final private case class Prepared(id: String, draft: Draft, ordinal: Int)

object DocumentStructureChunker:
  val layer: ULayer[Chunker] = ZLayer.succeed(DocumentStructureChunker(): Chunker)

  /** 按 Embedding 声明的 tokenizer 构造切分器。未声明时仍使用 cl100k，并依赖索引对齐检查拒绝假装对齐的 live 模型。 */
  val alignedLayer: ZLayer[EmbeddingModel, RetrievalError, Chunker] =
    ZLayer.fromZIO {
      ZIO.serviceWithZIO[EmbeddingModel] { model =>
        model.capabilities.tokenizerId match
          case None | Some(ChunkEmbeddingAlignment.TestTokenizerId) =>
            ZIO.succeed(DocumentStructureChunker(): Chunker)
          case Some(id) =>
            ZIO
              .fromOption(TokenCounter.get(id))
              .orElseFail(AgentError.RetrievalFailed(s"未知 Embedding tokenizer: $id"))
              .map(counter =>
                DocumentStructureChunker(DocumentStructureChunkerConfig(tokenCounter = counter))
              )
      }
    }

  def configured(config: DocumentStructureChunkerConfig): ULayer[Chunker] =
    ZLayer.succeed(DocumentStructureChunker(config): Chunker)
