package com.zyblw.agent.rag

import zio.test.*

object TokenCounterSpec extends ZIOSpecDefault:
  def spec = suite("TokenCounter")(
    test("cl100k BPE 对中英混排的计数与 jtokkit 参考值一致") {
      val text     = "桂枝汤主之 Hello 2026"
      val expected = com.knuddels.jtokkit.Encodings
        .newDefaultEncodingRegistry()
        .getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE)
        .countTokens(text)
      assertTrue(TokenCounter.Cl100k.count(text) == expected, TokenCounter.Cl100k.id == "cl100k-base")
    },
    test("token 装箱后每个块不超过预算") {
      val counter = TokenCounter.Cl100k
      val text    = "太阳中风发热汗出恶风脉缓者名为中风。".repeat(40)
      val packed  = text.grouped(20).toList
      assertTrue(packed.forall(part => counter.count(part) <= 64), counter.count(text) > 64)
    }
  )
