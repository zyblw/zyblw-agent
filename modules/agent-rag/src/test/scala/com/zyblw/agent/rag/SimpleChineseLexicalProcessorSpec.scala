package com.zyblw.agent.rag

import zio.test.*
import zio.*

/** 中文 FTS 派生表示的确定性契约；真实词典型 Adapter 应复用此 SPI 而不是绕过索引/查询对称性。 */
object SimpleChineseLexicalProcessorSpec extends ZIOSpecDefault:
  def spec = suite("SimpleChineseLexicalProcessor")(
    test("连续中文生成单字与重叠二元词，并保留英文和数字") {
      val lexical = SimpleChineseLexicalProcessor.document("桂枝汤 2026 Qwen3")
      assertTrue(
        lexical == "桂 枝 汤 桂枝 枝汤 2026 qwen3",
        SimpleChineseLexicalProcessor.query("桂枝汤") == "桂 枝 汤 桂枝 枝汤"
      )
    },
    test("标点与空白不进入 FTS token，结果不依赖系统默认 Locale") {
      assertTrue(
        SimpleChineseLexicalProcessor.document("阴阳，ABC！") == "阴 阳 阴阳 abc",
        SimpleChineseLexicalProcessor.document("  ").isEmpty
      )
    }
  )
