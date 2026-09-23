package com.zyblw.agent.rag

/** 离线写入 dense/lexical 的确定性章节前缀。引用仍只用正文。 */
object ChunkContext:
  def dense(
      body: String,
      title: Option[String],
      headingPath: Seq[String],
      kind: Option[String],
      maxPrefixCodePoints: Int
  ): String =
    val head = prefix(title, headingPath, kind, maxPrefixCodePoints)
    if head.isEmpty then body else s"$head\n\n$body"

  def prefix(
      title: Option[String],
      headingPath: Seq[String],
      kind: Option[String],
      maxPrefixCodePoints: Int
  ): String =
    val chapters = headingPath.map(_.trim).filter(_.nonEmpty)
    val lines    = List(
      title.map(_.trim).filter(_.nonEmpty).map(value => s"书名：$value"),
      Option.when(chapters.nonEmpty)(s"章节：${chapters.mkString(" > ")}"),
      kind.map(_.trim).filter(_.nonEmpty).map(value => s"类型：$value")
    ).flatten
    take(lines.mkString("\n"), maxPrefixCodePoints.max(0))

  private def take(value: String, limit: Int): String =
    if limit <= 0 || value.isEmpty then ""
    else
      val count = value.codePointCount(0, value.length)
      if count <= limit then value
      else value.substring(0, value.offsetByCodePoints(0, limit))
