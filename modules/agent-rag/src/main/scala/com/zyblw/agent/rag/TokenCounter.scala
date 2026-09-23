package com.zyblw.agent.rag

/** 切分装箱使用的 token 预算计数器。
  *
  * 生产默认对齐 Embedding 模型 tokenizer（jtokkit BPE）。`CodePoints` 与 `CjkApproximate` 只供测试或显式回退；计数器 `id` 必须进入
  * `strategyId`，更换后只能通过新知识索引版本生效。
  */
trait TokenCounter:
  def id: String
  def count(text: String): Int

object TokenCounter:

  /** 仅测试或显式回退：一个 Unicode code point 计 1。 */
  val CodePoints: TokenCounter = new TokenCounter:
    val id: String               = "codepoints"
    def count(text: String): Int = text.codePointCount(0, text.length)

  /** OpenAI-compatible BPE。`cl100k_base` 覆盖 text-embedding-3；`o200k_base` 覆盖更新的 4o 系列。 */
  def Bpe(encodingName: String): TokenCounter =
    val encoding = encodingName match
      case "cl100k_base" =>
        com.knuddels.jtokkit.Encodings
          .newDefaultEncodingRegistry()
          .getEncoding(
            com.knuddels.jtokkit.api.EncodingType.CL100K_BASE
          )
      case "o200k_base" =>
        com.knuddels.jtokkit.Encodings
          .newDefaultEncodingRegistry()
          .getEncoding(
            com.knuddels.jtokkit.api.EncodingType.O200K_BASE
          )
      case other =>
        throw IllegalArgumentException(s"不支持的 BPE encoding: $other")
    new TokenCounter:
      val id: String               = encodingName.replace('_', '-')
      def count(text: String): Int = encoding.countTokens(text)

  val Cl100k: TokenCounter = Bpe("cl100k_base")
  val O200k: TokenCounter  = Bpe("o200k_base")

  def get(id: String): Option[TokenCounter] =
    id match
      case "cl100k-base"   => Some(Cl100k)
      case "o200k-base"    => Some(O200k)
      case "cjk-approx-v1" => Some(CjkApproximate)
      case "codepoints"    => Some(CodePoints)
      case _               => None

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
