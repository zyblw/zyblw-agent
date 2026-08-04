package com.zyblw.agent.rag

import zio.*
import zio.json.*

/** 原文坐标系原点。PDF 解析器常使用左上或左下两种原点，引用展示层必须显式转换，不能猜测。 */
enum DocumentCoordinateOrigin derives JsonCodec:
  case TopLeft, BottomLeft

/** 一个不可变的原文矩形。坐标保留解析器的页内单位，同时保存页宽高以便稳定归一化。 */
final case class DocumentBoundingBox(
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
    pageWidth: Option[Double] = None,
    pageHeight: Option[Double] = None,
    origin: DocumentCoordinateOrigin = DocumentCoordinateOrigin.TopLeft
) derives JsonCodec:
  private val coordinates = Chunk(left, top, right, bottom) ++ Chunk.fromIterable(pageWidth) ++ Chunk
    .fromIterable(pageHeight)
  require(coordinates.forall(value => !value.isNaN && !value.isInfinity), "bbox 坐标必须是有限数")
  require(right >= left, "bbox right 不能小于 left")
  require(pageWidth.forall(_ > 0.0) && pageHeight.forall(_ > 0.0), "bbox 页宽高必须为正数")

/** 一个 chunk 对原文的单个定位。一个 chunk 可以跨页，因此使用有序集合而不是单一 page 字段。 */
final case class DocumentOrigin(
    pageNumber: Int,
    boundingBox: Option[DocumentBoundingBox] = None,
    blockId: Option[String] = None
) derives JsonCodec:
  require(pageNumber > 0, "原文页码必须从 1 开始")
  require(blockId.forall(_.trim.nonEmpty), "原文 blockId 不能是空字符串")

/** 解析器恢复的文档元素类型。枚举是 Provider-neutral 的，Docling/Tika/未来 OCR Adapter 必须显式映射而不泄漏自身类型。 */
enum DocumentBlockKind:
  case Title, SectionHeading, Paragraph, ListItem, Table, Picture, Code, Formula, KeyValue, Other

/** 一个可回溯到原文的结构元素。
  *
  * @param id
  *   解析结果内稳定 ID，通常是 Docling `self_ref`
  * @param parentId
  *   可选父节点 ID；容器节点可以不带正文
  * @param ordinal
  *   文档阅读顺序，从 0 开始
  * @param text
  *   元素正文，始终按不可信外部数据处理
  * @param headingPath
  *   从文档根到当前元素的标题面包屑
  * @param origins
  *   页码、bbox 和原始 block 身份
  */
final case class DocumentBlock(
    id: String,
    parentId: Option[String],
    ordinal: Int,
    kind: DocumentBlockKind,
    text: String,
    headingPath: Chunk[String] = Chunk.empty,
    origins: Chunk[DocumentOrigin] = Chunk.empty
):
  require(id.trim.nonEmpty, "DocumentBlock.id 不能为空")
  require(parentId.forall(_.trim.nonEmpty), "DocumentBlock.parentId 不能为空")
  require(ordinal >= 0, "DocumentBlock.ordinal 不能为负数")
  require(text.trim.nonEmpty, "DocumentBlock.text 不能为空")

/** 解析器的无损结构投影。不保存 Provider 原始 JSON，只保存框架后续切分、引用与评测必需的类型化字段。 */
final case class DocumentStructure(
    schemaName: String,
    schemaVersion: Option[String],
    blocks: Chunk[DocumentBlock]
):
  require(schemaName.trim.nonEmpty, "DocumentStructure.schemaName 不能为空")
  require(schemaVersion.forall(_.trim.nonEmpty), "DocumentStructure.schemaVersion 不能为空")
  require(blocks.map(_.id).distinct.length == blocks.length, "DocumentStructure block ID 必须唯一")

/** 知识块的结构化谱系。
  *
  * `parentId` 标识同一结构章节，`previousChunkId`/`nextChunkId` 表示文档阅读顺序。它们只由受控切分器生成， 不允许模型或文档内容自行填写。
  */
final case class ChunkLineage(
    parentId: Option[String],
    ordinal: Int,
    previousChunkId: Option[String] = None,
    nextChunkId: Option[String] = None,
    headingPath: Chunk[String] = Chunk.empty,
    origins: Chunk[DocumentOrigin] = Chunk.empty,
    blockIds: Chunk[String] = Chunk.empty
):
  require(parentId.forall(_.trim.nonEmpty), "lineage parentId 不能是空字符串")
  require(ordinal >= 0, "lineage ordinal 不能为负数")
  require(previousChunkId.forall(_.trim.nonEmpty), "lineage previousChunkId 不能为空")
  require(nextChunkId.forall(_.trim.nonEmpty), "lineage nextChunkId 不能为空")
  require(headingPath.forall(_.trim.nonEmpty), "lineage headingPath 不能包含空标题")
  require(blockIds.forall(_.trim.nonEmpty), "lineage blockIds 不能包含空 ID")

  /** 去重且按原文顺序输出页码，用于引用和 SQL 索引。 */
  val pageNumbers: Chunk[Int] = origins.map(_.pageNumber).distinct

/** 在 rerank 之后执行的有界上下文扩展策略。
  *
  * @param neighborRadius
  *   每个命中向前/向后最多跟随的块数；当前存储 Adapter 支持 0 或 1
  * @param parentHitThreshold
  *   同一父节点至少出现多少个 rerank 命中才扩展同级块
  * @param maxSiblingsPerParent
  *   每个父节点最多补充的同级块
  * @param maxAdditionalChunks
  *   整次检索最多新增的块数，防止章节扩展吃满上下文
  * @param expandedScoreFactor
  *   扩展块继承种子分数时的折减系数
  */
final case class RetrievalExpansionConfig(
    neighborRadius: Int = 1,
    parentHitThreshold: Int = 2,
    maxSiblingsPerParent: Int = 4,
    maxAdditionalChunks: Int = 8,
    expandedScoreFactor: Double = 0.85
):
  require(neighborRadius >= 0 && neighborRadius <= 1, "neighborRadius 当前必须位于 0..1")
  require(parentHitThreshold >= 1, "parentHitThreshold 必须为正数")
  require(maxSiblingsPerParent >= 0 && maxSiblingsPerParent <= 50, "maxSiblingsPerParent 必须位于 0..50")
  require(maxAdditionalChunks >= 0 && maxAdditionalChunks <= 100, "maxAdditionalChunks 必须位于 0..100")
  require(expandedScoreFactor > 0.0 && expandedScoreFactor <= 1.0, "expandedScoreFactor 必须位于 (0, 1]")
