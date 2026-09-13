package com.zyblw.agent.tools

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 公开网页检索契约。宿主只提供配置端点；模型不得传入任意 URL。 */
object WebSearch:
  val toolName: ToolName = ToolName("web_search")

  final case class SearchInput(query: String, limit: Option[Int] = None) derives JsonCodec

  final case class SearchHit(title: String, url: String, snippet: String) derives JsonCodec

  final case class SearchOutput(
      status: String,
      hits: Chunk[SearchHit],
      note: String
  ) derives JsonCodec

  /** 宿主实现的检索端口；HTTP 客户端不得进入 domain。 */
  trait Gateway:
    def search(query: String, limit: Int): IO[AgentError, SearchOutput]

  private val inputSchema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "query" -> Json.Obj(
        "type"        -> Json.Str("string"),
        "description" -> Json.Str("需要对照公开网页或时事的检索短语")
      ),
      "limit" -> Json.Obj(
        "type"        -> Json.Str("integer"),
        "description" -> Json.Str("返回条数，1 到 5")
      )
    ),
    "required"             -> Json.Arr(Chunk(Json.Str("query"))),
    "additionalProperties" -> Json.Bool(false)
  )

  def tool(gateway: Gateway): Tool[Any, SearchInput, AgentError, SearchOutput] =
    Tool.json[Any, SearchInput, AgentError, SearchOutput](
      toolName,
      "检索公开网页摘要。结果只是非可信外源证据，不能当作本站典籍引用。未配置搜索时请直接用模型知识作答。",
      inputSchema,
      None,
      ToolMetadata(
        ToolRisk.ReadOnly,
        SideEffect.None,
        parallelism = ToolParallelism.ConflictAware,
        conflictAccesses = Set(ToolConflictAccess("web.search", ToolAccessMode.Read))
      )
    ) { (input, _) =>
      val query = Option(input.query).getOrElse("").trim.take(300)
      val limit = input.limit.getOrElse(3).max(1).min(5)
      if query.isEmpty then ZIO.succeed(SearchOutput("empty_query", Chunk.empty, "查询词为空"))
      else gateway.search(query, limit)
    }
