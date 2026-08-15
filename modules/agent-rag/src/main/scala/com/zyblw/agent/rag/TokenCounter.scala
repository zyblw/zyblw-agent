package com.zyblw.agent.rag

/** 切分装箱使用的 token 预算计数器。
  *
  * 默认仍按 Unicode code point，以保持已发布 `strategyId`。近似 CJK 计数器不是 Embedding tokenizer 的对齐实现；启用后必须出现在 `strategyId`
  * 中并新建索引版本。
  */
trait TokenCounter:
  def id: String
  def count(text: String): Int

object TokenCounter:

  /** 与历史 `DocumentStructureChunker` 行为一致：一个 code point 计 1。 */
  val CodePoints: TokenCounter = new TokenCounter:
    val id: String               = "codepoints"
    def count(text: String): Int = text.codePointCount(0, text.length)

  /** 汉字/假名/谚文约 1 token，拉丁字母约 4 字符 1 token。只用于装箱预算，不是模型 tokenizer。 */
  val CjkApproximate: TokenCounter = new TokenCounter:
    val id: String = "cjk-approx-v1"

    def count(text: String): Int =
      if text.trim.isEmpty then 0
      else
        var tokens = 0
        var latin  = 0
        var index  = 0
        while index < text.length do
          val cp = text.codePointAt(index)
          if isCjkUnit(cp) then
            if latin > 0 then
              tokens += (latin + 3) / 4
              latin = 0
            tokens += 1
          else if !Character.isWhitespace(cp) then latin += 1
          index += Character.charCount(cp)
        if latin > 0 then tokens += (latin + 3) / 4
        tokens.max(1)

    private def isCjkUnit(cp: Int): Boolean =
      Character.UnicodeScript.of(cp) match
        case Character.UnicodeScript.HAN | Character.UnicodeScript.HIRAGANA |
            Character.UnicodeScript.KATAKANA | Character.UnicodeScript.HANGUL =>
          true
        case _ => false
