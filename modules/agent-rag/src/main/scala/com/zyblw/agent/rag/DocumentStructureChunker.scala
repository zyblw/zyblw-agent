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
    strategyVersion: String = "document-structure-v1",
    /** 可选 token 装箱预算。未设置时保持历史 code point 行为，不改变 `strategyId`。 */
    maxTokens: Option[Int] = None,
    tokenCounter: TokenCounter = TokenCounter.CodePoints
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
    val drafts   = pack(structure.blocks.sortBy(_.ordinal))
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
      val rendered   = render(draft.headingPath, draft.text)
      val metadata   = document.metadata ++ Map(
        "chunkerId"       -> strategyId,
        "chunkOrdinal"    -> preparedChunk.ordinal.toString,
        "chunkContentSha" -> KnowledgeIndexer.sha256(draft.text),
        "contentFormat"   -> document.representation.toString.toLowerCase(java.util.Locale.ROOT)
      ) ++ Option.when(draft.headingPath.nonEmpty)("headingPath" -> draft.headingPath.mkString(" > "))
      DocumentChunk(
        id = preparedChunk.id,
        documentId = document.id,
        text = rendered,
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

  /** 按阅读顺序装箱；只有 parent 和 headingPath 均相同才允许合并。 */
  private def pack(blocks: Chunk[DocumentBlock]): Vector[Draft] =
    val result  = mutable.ArrayBuffer.empty[Draft]
    val pending = mutable.ArrayBuffer.empty[DocumentBlock]

    def pendingText: String = pending.map(_.text).mkString("\n\n")
    def flush(): Unit       =
      if pending.nonEmpty then
        result += fromBlocks(pending.toVector)
        pending.clear()

    blocks.foreach { block =>
      if !fits(block.headingPath, block.text) then
        flush()
        result ++= splitOversized(block)
      else if pending.isEmpty then pending += block
      else
        val sameContext =
          pending.last.parentId == block.parentId && pending.last.headingPath == block.headingPath
        val merged = pendingText + "\n\n" + block.text
        if config.mergePeers && sameContext && fits(block.headingPath, merged) then pending += block
        else
          flush()
          pending += block
    }
    flush()
    result.toVector

  private def fromBlocks(blocks: Vector[DocumentBlock]): Draft =
    Draft(
      blocks.head.parentId,
      blocks.head.headingPath,
      blocks.map(_.text).mkString("\n\n"),
      Chunk.fromIterable(blocks.flatMap(_.origins).distinct),
      Chunk.fromIterable(blocks.map(_.id))
    )

  private def splitOversized(block: DocumentBlock): Vector[Draft] =
    val size = availableBody(block.headingPath).max(1)
    val step = (size - config.overlapCharacters.min(size - 1)).max(1)
    Iterator
      .iterate(0)(_ + step)
      .takeWhile(_ < codePoints(block.text))
      .map { start =>
        val text = slice(block.text, start, (start + size).min(codePoints(block.text)))
        Draft(block.parentId, block.headingPath, text, block.origins, Chunk(block.id))
      }
      .toVector

  private def availableBody(path: Chunk[String]): Int =
    val headingBudget = codePoints(renderPrefix(path)) + 2
    val charBudget    = (config.maxCharacters - headingBudget).max(1)
    config.maxTokens.fold(charBudget)(limit => charBudget.min(limit).max(1))

  private def fits(path: Chunk[String], body: String): Boolean =
    val rendered = render(path, body)
    codePoints(rendered) <= config.maxCharacters &&
    config.maxTokens.forall(limit => config.tokenCounter.count(rendered) <= limit)

  private def render(path: Chunk[String], text: String): String =
    val prefix = renderPrefix(path)
    if prefix.isEmpty then text else s"$prefix\n\n$text"

  private def renderPrefix(path: Chunk[String]): String =
    val raw =
      path.zipWithIndex.map { case (title, index) => s"${"#" * (index + 1).min(6)} $title" }.mkString("\n")
    if codePoints(raw) <= config.maxCharacters / 3 then raw else slice(raw, 0, config.maxCharacters / 3)

  private def codePoints(value: String): Int = value.codePointCount(0, value.length)

  private def slice(value: String, start: Int, end: Int): String =
    value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end))

  final private case class Draft(
      parentId: Option[String],
      headingPath: Chunk[String],
      text: String,
      origins: Chunk[DocumentOrigin],
      blockIds: Chunk[String]
  )
  final private case class Prepared(id: String, draft: Draft, ordinal: Int)

object DocumentStructureChunker:
  val layer: ULayer[Chunker] = ZLayer.succeed(DocumentStructureChunker(): Chunker)

  def configured(config: DocumentStructureChunkerConfig): ULayer[Chunker] =
    ZLayer.succeed(DocumentStructureChunker(config): Chunker)
