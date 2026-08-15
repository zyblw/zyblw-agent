package com.zyblw.agent.rag

import zio.Chunk

/** 解析阶段的成本档。宿主按档装配，级联按 `ExtractionMode` 选择子集。 */
enum ExtractionStageKind:
  case TextLayer, LayoutOcr, Vision

  def wireValue: String = this match
    case TextLayer => "text"
    case LayoutOcr => "ocr"
    case Vision    => "vision"

/** 一个可观察的解析阶段：成本档 + 具体 Loader。 */
final case class ExtractionStage(kind: ExtractionStageKind, loader: DocumentLoader):
  def id: String                       = loader.id
  def supportedMediaTypes: Set[String] = loader.supportedMediaTypes
  def load(input: DocumentInput)       = loader.load(input)

object ExtractionStage:
  def text(loader: DocumentLoader): ExtractionStage   = ExtractionStage(ExtractionStageKind.TextLayer, loader)
  def ocr(loader: DocumentLoader): ExtractionStage    = ExtractionStage(ExtractionStageKind.LayoutOcr, loader)
  def vision(loader: DocumentLoader): ExtractionStage = ExtractionStage(ExtractionStageKind.Vision, loader)

/** 业务层选择的提取策略。默认 `Auto`：按质量自动从廉价文本层升到 OCR/视觉。 */
enum ExtractionMode:
  case Auto, TextLayer, LayoutOcr, Vision

  def wireValue: String = this match
    case Auto      => "auto"
    case TextLayer => "text"
    case LayoutOcr => "ocr"
    case Vision    => "vision"

object ExtractionMode:
  val MetadataKey: String = "extractionMode"

  def parse(raw: String): Either[String, ExtractionMode] =
    raw.trim.toLowerCase(java.util.Locale.ROOT) match
      case "auto" | "" => Right(Auto)
      case "text"      => Right(TextLayer)
      case "ocr"       => Right(LayoutOcr)
      case "vision"    => Right(Vision)
      case other       => Left(s"extractionMode 无效: $other，允许 auto|text|ocr|vision")

  def fromMetadata(metadata: Map[String, String]): Either[String, ExtractionMode] =
    parse(metadata.getOrElse(MetadataKey, Auto.wireValue))

/** 一次成功或失败摄入的低敏提取摘要。不含正文。 */
final case class ExtractionReport(
    mode: String,
    method: String,
    fallbackUsed: Boolean,
    quality: String,
    pageCount: Option[Int]
)

/** 从结构或 Markdown 标题恢复的目录项。不含正文。 */
final case class ExtractedHeading(level: Int, title: String, pageNumber: Option[Int])

object ExtractedHeading:
  private val MarkdownHeading = """^(#{1,6})\s+(.+?)\s*$""".r

  def from(document: SourceDocument): Chunk[ExtractedHeading] =
    val structured = document.structure.toList
      .flatMap(_.blocks)
      .collect {
        case block
            if block.kind == DocumentBlockKind.Title || block.kind == DocumentBlockKind.SectionHeading =>
          ExtractedHeading(
            level =
              if block.kind == DocumentBlockKind.Title then 1 else math.max(1, block.headingPath.length),
            title = block.text.trim.take(300),
            pageNumber = block.origins.headOption.map(_.pageNumber)
          )
      }
      .filter(_.title.nonEmpty)
    if structured.nonEmpty then Chunk.fromIterable(structured)
    else
      Chunk.fromIterable(
        document.text.linesIterator
          .collect { case MarkdownHeading(marks, title) =>
            ExtractedHeading(marks.length, title.trim.take(300), None)
          }
          .filter(_.title.nonEmpty)
          .toList
      )

object ExtractionReport:
  def from(document: SourceDocument): ExtractionReport =
    ExtractionReport(
      mode = document.metadata.getOrElse(ExtractionMode.MetadataKey, ExtractionMode.Auto.wireValue),
      method =
        document.metadata.getOrElse("extractionMethod", document.metadata.getOrElse("loaderId", "unknown")),
      fallbackUsed = document.metadata.get("extractionFallbackUsed").contains("true"),
      quality = document.metadata.getOrElse("extractionQuality", ""),
      pageCount =
        document.metadata.get("pageCount").flatMap(_.toIntOption).orElse(pageCountFromStructure(document))
    )

  private def pageCountFromStructure(document: SourceDocument): Option[Int] =
    document.structure
      .map(_.blocks.flatMap(_.origins.map(_.pageNumber)).foldLeft(0)(_ max _))
      .filter(_ > 0)
