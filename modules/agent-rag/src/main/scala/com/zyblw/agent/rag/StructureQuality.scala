package com.zyblw.agent.rag

import zio.Chunk

/** 结构质量门禁。文本可读不等于章节、段落和页界已经恢复。 */
final case class StructureQualityPolicy(
    maxTocHeadingRatio: Double = 0.35,
    minMedianParagraphChars: Int = 24,
    minHeadingDepth: Int = 1,
    maxDuplicateHeadingRatio: Double = 0.45,
    maxHeadingShare: Double = 0.55,
    minPageCoverage: Double = 0.5
):
  require(maxTocHeadingRatio >= 0.0 && maxTocHeadingRatio <= 1.0, "maxTocHeadingRatio 必须位于 0..1")
  require(minMedianParagraphChars >= 0, "minMedianParagraphChars 不能为负")
  require(minHeadingDepth >= 1, "minHeadingDepth 必须 >= 1")
  require(
    maxDuplicateHeadingRatio >= 0.0 && maxDuplicateHeadingRatio <= 1.0,
    "maxDuplicateHeadingRatio 必须位于 0..1"
  )
  require(maxHeadingShare >= 0.0 && maxHeadingShare <= 1.0, "maxHeadingShare 必须位于 0..1")
  require(minPageCoverage >= 0.0 && minPageCoverage <= 1.0, "minPageCoverage 必须位于 0..1")

final case class StructureQuality(
    blockCount: Int,
    headingCount: Int,
    paragraphCount: Int,
    tocStyleHeadings: Int,
    duplicateHeadings: Int,
    medianParagraphChars: Int,
    maxHeadingDepth: Int,
    pageCoverage: Double,
    bboxCoverage: Double
):
  def tocHeadingRatio: Double =
    if headingCount == 0 then 0.0 else tocStyleHeadings.toDouble / headingCount.toDouble

  def duplicateHeadingRatio: Double =
    if headingCount == 0 then 0.0 else duplicateHeadings.toDouble / headingCount.toDouble

  def headingShare: Double =
    if blockCount == 0 then 0.0 else headingCount.toDouble / blockCount.toDouble

  def sufficient(policy: StructureQualityPolicy): Boolean =
    blockCount == 0 || (
      tocHeadingRatio <= policy.maxTocHeadingRatio &&
        (paragraphCount == 0 || medianParagraphChars >= policy.minMedianParagraphChars) &&
        (headingCount == 0 || maxHeadingDepth >= policy.minHeadingDepth) &&
        duplicateHeadingRatio <= policy.maxDuplicateHeadingRatio &&
        headingShare <= policy.maxHeadingShare &&
        pageCoverage >= policy.minPageCoverage
    )

  def compact: String =
    f"blocks=$blockCount,headings=$headingCount,median=$medianParagraphChars,depth=$maxHeadingDepth,toc=$tocHeadingRatio%.2f,dup=$duplicateHeadingRatio%.2f,pages=$pageCoverage%.2f"

object StructureQuality:
  def assess(blocks: Chunk[DocumentBlock], pageCount: Option[Int] = None): StructureQuality =
    val headings = blocks.filter(block =>
      block.kind == DocumentBlockKind.Title || block.kind == DocumentBlockKind.SectionHeading
    )
    val paragraphs    = blocks.filter(_.kind == DocumentBlockKind.Paragraph)
    val titles        = headings.map(_.text.trim).filter(_.nonEmpty)
    val unique        = titles.distinct
    val pages         = blocks.flatMap(_.origins.map(_.pageNumber)).distinct
    val declaredPages = pageCount.filter(_ > 0).getOrElse(pages.length.max(1))
    val lengths       = paragraphs.map(_.text.trim.length).sorted
    val median        =
      if lengths.isEmpty then 0
      else lengths(lengths.length / 2)
    StructureQuality(
      blockCount = blocks.length,
      headingCount = headings.length,
      paragraphCount = paragraphs.length,
      tocStyleHeadings = headings.count(block => PdfTextStructure.isTocStyle(block.text)),
      duplicateHeadings = (titles.length - unique.length).max(0),
      medianParagraphChars = median,
      maxHeadingDepth = headings.map(_.headingPath.length).foldLeft(0)(_ max _),
      pageCoverage = pages.length.toDouble / declaredPages.toDouble,
      bboxCoverage =
        if blocks.isEmpty then 0.0
        else blocks.count(_.origins.exists(_.boundingBox.isDefined)).toDouble / blocks.length.toDouble
    )
