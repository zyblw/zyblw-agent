package com.zyblw.agent.rag

import zio.*
import zio.json.*

/** 整书目录的紧凑文本，供模型一次读完再选章（PageIndex 式推理导航）。
  *
  * 节点用短引用 `s1..sN` 表示，模型只能从 `refs` 里选；引用按原书顺序编号，折叠层级时编号不变。目录和提示只用于导航，从不作为引用证据。
  *
  * @param shownDepth
  *   实际展开到的相对层级（根为 0）
  * @param folded
  *   因预算被折叠、没有单独列出的节点数
  */
final case class StructureOutline(
    text: String,
    refs: Map[String, String],
    shownDepth: Int,
    folded: Int
):
  def nodeId(ref: String): Option[String] = refs.get(ref.trim)

object StructureOutline:
  val MaxTitleCodePoints: Int = 60

  final private case class Selection(sections: Option[List[String]] = None, ids: Option[List[String]] = None)
      derives JsonCodec

  /** 依次尝试：全部层级带提示 → 前两层带提示 → 全部层级无提示 → 逐层折叠；取第一个不超过 `maxChars` 的版本。 */
  def render(snapshot: StructureSnapshot, maxChars: Int, hintCodePoints: Int = 40): StructureOutline =
    require(maxChars > 0, "outline maxChars 必须为正数")
    val ordered = snapshot.nodes.sortBy(_.section.ordinal)
    val refs    = ordered.zipWithIndex.map((node, index) => node.section.id -> s"s${index + 1}").toMap
    val depths  = ordered.map(node => node.section.id -> snapshot.ancestors(node.section.id).length).toMap
    val deepest = depths.values.maxOption.getOrElse(0)
    val hidden  = ordered.map(node => node.section.id -> descendants(snapshot, node.section.id)).toMap

    def attempt(depth: Int, hintDepth: Int): (String, Int) =
      val visible = ordered.filter(node => depths(node.section.id) <= depth)
      val lines   = visible.map { node =>
        val level = depths(node.section.id)
        val id    = node.section.id
        val fold  = if level == depth && hidden(id) > 0 then s" (+${hidden(id)})" else ""
        val hint  =
          if level <= hintDepth then
            navigationHint(node).map(h => " — " + take(h, hintCodePoints)).getOrElse("")
          else ""
        "  " * level + refs(id) + " " + take(clean(node.section.title), MaxTitleCodePoints) +
          pages(node.section) + fold + hint
      }
      (lines.mkString("\n"), ordered.length - visible.length)

    val attempts =
      Chunk((deepest, deepest), (deepest, 1), (deepest, -1)) ++
        Chunk.fromIterable((deepest - 1 to 0 by -1).map(depth => (depth, -1)))
    val chosen = attempts.iterator
      .map((depth, hintDepth) => (depth, attempt(depth, hintDepth)))
      .find(_._2._1.length <= maxChars)
    chosen match
      case Some((depth, (text, folded))) =>
        StructureOutline(text, refs.map(_.swap), depth, folded)
      case None =>
        val (text, _) = attempt(0, -1)
        val kept      = text.linesIterator.foldLeft(Vector.empty[String]) { (acc, line) =>
          if (acc :+ line).mkString("\n").length <= maxChars then acc :+ line else acc
        }
        val shown = kept.map(_.trim.takeWhile(_ != ' ')).toSet
        StructureOutline(
          kept.mkString("\n"),
          refs.map(_.swap).filter((ref, _) => shown.contains(ref)),
          0,
          ordered.length - kept.length
        )

  /** 模型回复 `{"sections":["s3","s12"]}`（也接受 `ids`）。目录外的引用直接丢弃，空列表表示目录里没有相关章节。 */
  def parseSelection(raw: String, outline: StructureOutline, maxSelect: Int): Either[String, Chunk[String]] =
    val objects = jsonObjects(Option(raw).getOrElse(""))
    if objects.isEmpty then Left("outline selection JSON missing")
    else
      objects.reverseIterator
        .map(decodeSelection(_, outline, maxSelect))
        .collectFirst { case Right(refs) => refs }
        .toRight(
          decodeSelection(objects.last, outline, maxSelect).swap
            .getOrElse("outline selection JSON invalid")
        )

  private def decodeSelection(
      json: String,
      outline: StructureOutline,
      maxSelect: Int
  ): Either[String, Chunk[String]] =
    json
        .fromJson[Selection]
        .left
        .map(_ => "outline selection JSON invalid")
        .flatMap { selection =>
          selection.sections.orElse(selection.ids) match
            case None       => Left("outline selection field missing")
            case Some(refs) =>
              val resolved = refs.flatMap(outline.nodeId).distinct
              if refs.nonEmpty && resolved.isEmpty then Left("outline selection outside outline")
              else Right(Chunk.fromIterable(resolved.take(maxSelect.max(0))))
        }

  /** 规则摘要形如「标题：包含 N 处论述。主要涵盖：……」，只取正文开头；模型摘要原样使用。 */
  def navigationHint(node: StructureNode): Option[String] =
    node.summary
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { summary =>
        val marker = summary.indexOf(NodeSummarizer.DeterministicLeadMarker)
        if marker >= 0 then summary.substring(marker + NodeSummarizer.DeterministicLeadMarker.length)
        else if summary.startsWith(node.section.title) then
          summary.drop(node.section.title.length).dropWhile(c => c == '：' || c == ':' || c.isWhitespace)
        else summary
      }
      .map(clean)
      .filter(_.nonEmpty)

  /** 思考标签和说明文字里的花括号不并进正式 JSON；从后往前取能单独解码的对象。 */
  private def jsonObjects(raw: String): List[String] =
    val closed = "(?s)<think>.*?</think>".r.replaceAllIn(raw, "")
    val open = closed.indexOf("<think>")
    val text = (if open >= 0 then closed.substring(0, open) else closed)
      .replace("```json", "")
      .replace("```", "")
    val found = scala.collection.mutable.ListBuffer.empty[String]
    var index = 0
    while index < text.length do
      if text.charAt(index) == '{' then
        val end = endOfObject(text, index)
        if end > index then
          found += text.substring(index, end)
          index = end
        else index += 1
      else index += 1
    found.toList

  private def endOfObject(text: String, start: Int): Int =
    var depth = 0
    var index = start
    var inString = false
    var escape = false
    var end = -1
    while index < text.length && end < 0 do
      val char = text.charAt(index)
      if inString then
        if escape then escape = false
        else if char == '\\' then escape = true
        else if char == '"' then inString = false
      else
        char match
          case '"' => inString = true
          case '{' => depth += 1
          case '}' =>
            depth -= 1
            if depth == 0 then end = index + 1
          case _ => ()
      index += 1
    end

  private def descendants(snapshot: StructureSnapshot, nodeId: String): Int =
    snapshot
      .children(nodeId)
      .foldLeft(0)((count, child) => count + 1 + descendants(snapshot, child.section.id))

  private def pages(section: DocumentSection): String =
    section.pageStart.fold("") { start =>
      section.pageEnd.filter(_ > start).fold(s" [p$start]")(end => s" [p$start-$end]")
    }

  private def clean(text: String): String = text.replaceAll("\\s+", " ").trim

  private def take(text: String, codePoints: Int): String =
    if text.codePointCount(0, text.length) <= codePoints then text
    else text.substring(0, text.offsetByCodePoints(0, codePoints)) + "…"
