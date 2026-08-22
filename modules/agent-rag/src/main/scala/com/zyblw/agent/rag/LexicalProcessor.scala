package com.zyblw.agent.rag

import zio.*

/** 摄取与查询共用的 lexical 表示策略。
  *
  * `chunk.text` 仍是引用和上下文使用的权威证据正文；本 SPI 只生成交给 PostgreSQL FTS 的派生表示，绝不接受模型 生成的 search text。索引与查询必须使用同一
  * `strategyId`，因此策略变更必须触发新 knowledge index 版本。
  */
trait LexicalProcessor:
  def strategyId: String
  def document(text: String): String
  def query(text: String): String

object LexicalProcessor:
  /** 不改变既有非中文部署的词法表示。 */
  val identity: ULayer[LexicalProcessor] = ZLayer.succeed(
    new LexicalProcessor:
      val strategyId: String             = "identity-v1"
      def document(text: String): String = text
      def query(text: String): String    = text
  )

  /** 适用于中文、英文、数字混排语料的无外部词典默认实现。
    *
    * 连续汉字产生重叠二元词（并保留单字），让 PostgreSQL `simple` FTS 能检索“阴阳”“桂枝汤”等未被空白 分隔的短语；连续拉丁/数字则保留为小写
    * token。它不是医学分词器，也不替代业务可选的 jieba、HanLP 或模型 tokenizer Adapter；这些重依赖实现应以相同 SPI 独立接入。
    */
  val chinese: ULayer[LexicalProcessor] = ZLayer.succeed(SimpleChineseLexicalProcessor)

/** 纯函数、确定性且无外部词典的中文 lexical baseline。
  *
  * v2 在 v1 bigram 之上增加 NFKC、有界繁简归一和停用词过滤。不引入 zhparser/pg_jieba，以保证 Testcontainers 与业务部署可复现；短语再识别走
  * `RetrievalMode.Phrase`。
  */
object SimpleChineseLexicalProcessor extends LexicalProcessor:
  override val strategyId: String = "simple-cjk-bigram-v2"

  override def document(text: String): String = tokenize(text)
  override def query(text: String): String    = tokenize(text)

  private val Stopwords: Set[String] =
    Set("的", "了", "是", "在", "和", "与", "或", "也", "就", "都", "而", "及", "其", "这", "那", "之", "为", "以")

  private val TraditionalToSimplified: Map[Int, Int] =
    Map(
      '證'.toInt -> '证'.toInt,
      '發'.toInt -> '发'.toInt,
      '熱'.toInt -> '热'.toInt,
      '風'.toInt -> '风'.toInt,
      '脈'.toInt -> '脉'.toInt,
      '陽'.toInt -> '阳'.toInt,
      '陰'.toInt -> '阴'.toInt,
      '為'.toInt -> '为'.toInt,
      '與'.toInt -> '与'.toInt,
      '無'.toInt -> '无'.toInt,
      '對'.toInt -> '对'.toInt,
      '經'.toInt -> '经'.toInt,
      '條'.toInt -> '条'.toInt,
      '論'.toInt -> '论'.toInt,
      '傷'.toInt -> '伤'.toInt,
      '漢'.toInt -> '汉'.toInt
    )

  private def normalize(text: String): String =
    val nfkc = java.text.Normalizer.normalize(Option(text).getOrElse(""), java.text.Normalizer.Form.NFKC)
    val out  = new java.lang.StringBuilder(nfkc.length)
    var i    = 0
    while i < nfkc.length do
      val cp     = nfkc.codePointAt(i)
      val mapped = TraditionalToSimplified.getOrElse(cp, cp)
      out.appendCodePoint(mapped)
      i += Character.charCount(cp)
    out.toString

  private def tokenize(text: String): String =
    val tokens      = Vector.newBuilder[String]
    val currentHan  = new StringBuilder
    val currentWord = new StringBuilder

    def flushHan(): Unit =
      if currentHan.nonEmpty then
        val points = currentHan.toString.codePoints().toArray
        // 单字能召回，二元词为中文短查询提供最小的词序/短语辨别力；不保存原始输入外的任何信息。
        points.foreach(point => tokens += String(Character.toChars(point)))
        points
          .sliding(2)
          .foreach(pair =>
            tokens += pair.map(point => new java.lang.String(Character.toChars(point))).mkString
          )
        currentHan.clear()

    def flushWord(): Unit =
      if currentWord.nonEmpty then
        tokens += currentWord.toString.toLowerCase(java.util.Locale.ROOT)
        currentWord.clear()

    normalize(text).codePoints().forEach { point =>
      if isHan(point) then
        flushWord()
        currentHan.appendAll(Character.toChars(point))
      else if Character.isLetterOrDigit(point) then
        flushHan()
        currentWord.appendAll(Character.toChars(point))
      else
        flushHan()
        flushWord()
    }
    flushHan()
    flushWord()
    tokens.result().filterNot(Stopwords.contains).mkString(" ")

  private def isHan(codePoint: Int): Boolean =
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
