package com.zyblw.agent.core

import zio.json.*
import zio.test.*

object RunCitationSpec extends ZIOSpecDefault:
  def spec = suite("RunCitation")(
    test("缺省 JSON 不含 sourceKind，旧快照仍可解码") {
      val citation = RunCitation("cite-1", "book://suwen", "阴阳者", 0.9)
      val decoded  = """{"id":"cite-1","sourceUri":"book://suwen","excerpt":"阴阳者","score":0.9}"""
        .fromJson[RunCitation]
      assertTrue(
        citation.sourceKind.isEmpty,
        decoded == Right(citation),
        RunCitation.publicSourceKind(Some("book")).contains("book"),
        RunCitation.publicSourceKind(Some("catalog_role")).isEmpty
      )
    },
    test("拒绝非 allowlist 的 sourceKind") {
      val thrown = scala.util.Try(
        RunCitation("cite-1", "book://suwen", "阴阳者", 0.9, sourceKind = Some("catalog_role"))
      )
      assertTrue(thrown.isFailure)
    }
  )
