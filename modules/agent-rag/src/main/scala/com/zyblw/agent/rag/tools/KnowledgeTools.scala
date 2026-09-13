package com.zyblw.agent.rag.tools

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 开箱知识检索工具。tenant 与 permissions 只从 `ToolExecutionContext.runContext` 读取。 */
object KnowledgeTools:
  val SearchName: ToolName   = ToolName("knowledge_search")
  val FetchName: ToolName    = ToolName("knowledge_fetch")
  val Allowed: Set[ToolName] = Set(SearchName, FetchName)

  val policyFragment: ToolPolicyConfig = ToolPolicyConfig(allowedTools = Allowed)

  final case class SearchInput(
      query: String,
      mode: Option[String] = None,
      documentIds: Option[List[String]] = None,
      chunkIds: Option[List[String]] = None,
      pages: Option[List[Int]] = None,
      headingPrefix: Option[List[String]] = None,
      metadataEquals: Option[Map[String, String]] = None,
      limit: Option[Int] = None,
      tenantId: Option[String] = None,
      permissions: Option[List[String]] = None
  ) derives JsonCodec

  final case class FetchInput(
      chunkId: String,
      tenantId: Option[String] = None,
      permissions: Option[List[String]] = None
  ) derives JsonCodec

  final case class CitationOut(
      id: String,
      sourceUri: String,
      excerpt: String,
      score: Double,
      pageNumbers: Chunk[Int],
      chunkId: String,
      documentId: String,
      sourceKind: Option[String] = None,
      title: Option[String] = None,
      headingPath: List[String] = Nil,
      vectorScore: Option[Double] = None
  ) derives JsonCodec

  val KnowledgeIndexRead: Set[ToolConflictAccess] =
    Set(ToolConflictAccess("knowledge.index", ToolAccessMode.Read))

  def knowledgeReadMetadata(scopes: Set[String] = Set("knowledge:read")): ToolMetadata =
    ToolMetadata(
      ToolRisk.UserScopedRead,
      SideEffect.None,
      requiredScopes = scopes,
      parallelism = ToolParallelism.ConflictAware,
      conflictAccesses = KnowledgeIndexRead
    )

  final case class SearchOutput(
      status: String,
      excerpts: Chunk[String],
      citations: Chunk[CitationOut],
      evidenceStatus: String,
      acceptedCount: Int,
      profileId: Option[String] = None,
      knowledgeSpaceId: Option[String] = None,
      degradedStages: Chunk[String] = Chunk.empty
  ) derives JsonCodec

  def layer: ZLayer[RagApplication, AgentError, Chunk[RegisteredTool]] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[RagApplication](registered))

  def registered(rag: RagApplication): IO[AgentError, Chunk[RegisteredTool]] =
    for
      search <- RegisteredTool.make(searchTool(rag))
      fetch  <- RegisteredTool.make(fetchTool(rag))
    yield Chunk(search, fetch)

  def searchTool(rag: RagApplication): Tool[Any, SearchInput, AgentError, SearchOutput] =
    Tool.json[Any, SearchInput, AgentError, SearchOutput](
      SearchName,
      "在已授权的知识库中检索可引用资料。返回短摘录与 chunkId，细讲时再 knowledge_fetch。不要填写 tenant 或 permissions。",
      searchSchema,
      None,
      knowledgeReadMetadata()
    ) { (input, context) =>
      for
        _      <- rejectCallerOverride(input.tenantId, input.permissions)
        scope  <- trustedScope(context)
        mode   <- parseMode(input.mode)
        filter <- parseFilter(input)
        result <- rag
          .retrieve(RagQuery(input.query, scope, input.limit, mode, filter))
          .mapError(error => AgentError.ToolExecutionFailed(SearchName.value, error.message, error.retryable))
      yield toOutput(result, includeFullChunkText = false)
    }

  def fetchTool(rag: RagApplication): Tool[Any, FetchInput, AgentError, SearchOutput] =
    Tool.json[Any, FetchInput, AgentError, SearchOutput](
      FetchName,
      "按 chunkId 精确取回已授权知识块全文（切分上限内），用于细讲。不要填写 tenant 或 permissions。",
      fetchSchema,
      None,
      knowledgeReadMetadata()
    ) { (input, context) =>
      for
        _      <- rejectCallerOverride(input.tenantId, input.permissions)
        scope  <- trustedScope(context)
        result <- rag
          .fetch(Set(input.chunkId), scope)
          .mapError(error => AgentError.ToolExecutionFailed(FetchName.value, error.message, error.retryable))
      yield toOutput(result, includeFullChunkText = true)
    }

  private def rejectCallerOverride(
      tenantId: Option[String],
      permissions: Option[List[String]]
  ): IO[AgentError, Unit] =
    if tenantId.nonEmpty || permissions.nonEmpty then
      ZIO.fail(AgentError.ToolInputInvalid(SearchName.value, "tenantId/permissions 不能由模型参数提供"))
    else ZIO.unit

  private def trustedScope(context: ToolExecutionContext): IO[AgentError, RetrievalScope] =
    context.runContext.tenantId match
      case Some(tenant) if tenant.trim.nonEmpty =>
        ZIO.succeed(
          RetrievalScope(
            TenantId(tenant),
            context.runContext.scopes,
            runId = Some(context.runId),
            parentSpanId = Some(com.zyblw.agent.observability.TelemetrySpanIdentity.tool(context.callId))
          )
        )
      case _ =>
        ZIO.fail(AgentError.ToolExecutionFailed(SearchName.value, "缺少可信 tenant，拒绝检索"))

  private def parseMode(value: Option[String]): IO[AgentError, RetrievalMode] =
    value.map(_.trim).filter(_.nonEmpty) match
      case None | Some("hybrid") | Some("Hybrid") => ZIO.succeed(RetrievalMode.Hybrid)
      case Some("vector") | Some("VectorOnly")    => ZIO.succeed(RetrievalMode.VectorOnly)
      case Some("lexical") | Some("LexicalOnly")  => ZIO.succeed(RetrievalMode.LexicalOnly)
      case Some("phrase") | Some("Phrase")        => ZIO.succeed(RetrievalMode.Phrase)
      case Some(other)                            =>
        ZIO.fail(AgentError.ToolInputInvalid(SearchName.value, s"不支持的检索模式: $other"))

  private def parseFilter(input: SearchInput): IO[AgentError, RetrievalFilter] =
    ZIO
      .attempt {
        RetrievalFilter(
          documentIds = input.documentIds.fold(Set.empty[String])(_.toSet),
          chunkIds = input.chunkIds.fold(Set.empty[String])(_.toSet),
          pages = input.pages.fold(Set.empty[Int])(_.toSet),
          headingPrefix = Chunk.fromIterable(input.headingPrefix.getOrElse(Nil)),
          metadataEquals = input.metadataEquals.getOrElse(Map.empty)
        )
      }
      .mapError(error => AgentError.ToolInputInvalid(SearchName.value, error.getMessage))

  def toSearchOutput(result: RetrievalResult, includeFullChunkText: Boolean): SearchOutput =
    val seedCount = result.evidence.acceptedCount.max(0)
    val seedHits  = result.hits.take(seedCount)
    val seedCites = result.citations.take(seedCount)
    val citations = seedCites.zip(seedHits).map { case (citation, hit) =>
      citationOut(citation, hit)
    }
    val excerpts =
      if includeFullChunkText then result.hits.map(_.chunk.displayText)
      else result.hits.map(_.chunk.displayText.take(500))
    val status =
      if result.evidence.supportsGroundedAnswer then "ok" else "insufficient_evidence"
    SearchOutput(
      status,
      excerpts,
      citations,
      result.evidence.status.toString,
      result.evidence.acceptedCount,
      result.diagnostics.profileId,
      result.diagnostics.knowledgeSpaceId,
      result.diagnostics.degradedStages
    )

  private def toOutput(result: RetrievalResult, includeFullChunkText: Boolean): SearchOutput =
    toSearchOutput(result, includeFullChunkText)

  private def headingPathOf(chunk: DocumentChunk): List[String] =
    val fromLineage = chunk.lineage.toList.flatMap(_.headingPath.toList)
    if fromLineage.nonEmpty then fromLineage
    else
      chunk.metadata
        .get("headingPath")
        .toList
        .flatMap(_.split(" > ").iterator.map(_.trim).filter(_.nonEmpty).toList)

  private def citationOut(citation: Citation, hit: RetrievalHit): CitationOut =
    CitationOut(
      citation.id,
      citation.sourceUri,
      citation.excerpt.take(500),
      citation.score,
      citation.pageNumbers,
      hit.chunk.id,
      hit.chunk.documentId,
      RunCitation.publicSourceKind(hit.chunk.metadata.get("sourceType")),
      title = hit.chunk.metadata.get("title").filter(_.trim.nonEmpty),
      headingPath = headingPathOf(hit.chunk),
      vectorScore = hit.signals
        .get("vectorScore")
        .orElse(
          Option.when(!hit.signals.contains("textScore") && !hit.signals.contains("textRank"))(hit.score)
        )
    )

  private val searchSchema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "query"          -> Json.Obj("type" -> Json.Str("string")),
      "mode"           -> Json.Obj("type" -> Json.Str("string")),
      "documentIds"    -> Json.Obj("type" -> Json.Str("array")),
      "chunkIds"       -> Json.Obj("type" -> Json.Str("array")),
      "pages"          -> Json.Obj("type" -> Json.Str("array")),
      "headingPrefix"  -> Json.Obj("type" -> Json.Str("array")),
      "metadataEquals" -> Json.Obj("type" -> Json.Str("object")),
      "limit"          -> Json.Obj("type" -> Json.Str("integer"))
    ),
    "required" -> Json.Arr(Json.Str("query"))
  )

  private val fetchSchema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj("chunkId" -> Json.Obj("type" -> Json.Str("string"))),
    "required"   -> Json.Arr(Json.Str("chunkId"))
  )
