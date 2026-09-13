package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import zio.*
import zio.test.*

object WebSearchSpec extends ZIOSpecDefault:
  def spec = suite("WebSearch")(
    test("未配置网关时宿主可返回 unavailable，且空查询被拒绝") {
      val gateway = new WebSearch.Gateway:
        def search(query: String, limit: Int) =
          ZIO.succeed(WebSearch.SearchOutput("unavailable", Chunk.empty, "未配置"))
      val registered = WebSearch.tool(gateway)
      val context    = ToolExecutionContext(
        RunId(java.util.UUID.randomUUID()),
        ThreadId("web-search-test"),
        "call-1",
        RunContext()
      )
      for
        empty <- registered.execute(WebSearch.SearchInput("  "), context)
        ok    <- registered.execute(WebSearch.SearchInput("阴阳", Some(9)), context)
      yield assertTrue(
        registered.name.value == "web_search",
        empty.status == "empty_query",
        ok.status == "unavailable",
        ok.hits.isEmpty
      )
    }
  )
