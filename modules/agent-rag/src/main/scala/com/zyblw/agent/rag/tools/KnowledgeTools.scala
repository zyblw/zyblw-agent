package com.zyblw.agent.rag.tools

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import com.zyblw.agent.tools.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 开箱知识检索工具。tenant 与 permissions 只从 `ToolExecutionContext.runContext` 读取。 */
object KnowledgeTools:
  val SearchName: ToolName  = ToolName("knowledge_search")
  val FetchName: ToolName   = ToolName("knowledge_fetch")
  val OutlineName: ToolName = ToolName("knowledge_outline")

  /** 默认 `layer` 实际注册的工具。`knowledge_outline` 需要 `StructureStore`，由宿主另行注册并放行。 */
  val Allowed: Set[ToolName] = Set(SearchName, FetchName)

  /** 宿主写入的单文档范围。多文档使用 `scopeDocumentIds`，全库必须显式写 `documentScope=unrestricted`。 */
  val ScopeDocumentAttribute: String = DocumentScope.SingleAttribute

  val policyFragment: ToolPolicyConfig = ToolPolicyConfig(allowedTools = Allowed)

  /** 知识 Agent 的元数据。空 ContextSourceResolver 不能为这样的 Agent 调用模型。 */
  val contextSourceMetadata: (String, String) =
    com.zyblw.agent.context.ContextSourceRequirement.Attribute ->
      com.zyblw.agent.context.ContextSourceRequirement.Required

  /** 声明了知识工具却没有接入任何上下文来源时拒绝启动。 */
  def requireWiredResolver(resolver: com.zyblw.agent.context.ContextSourceResolver): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidConfiguration("知识问答装配了 Retriever 或知识工具，但 ContextSourceResolver 为空"))
      .when(resolver.sourceIds.isEmpty)
      .unit

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
      permissions: Option[List[String]] = None,
      recipe: Option[String] = None,
      strategy: Option[String] = None
  ) derives JsonCodec

  final case class FetchInput(
      chunkId: String = "",
      tenantId: Option[String] = None,
      permissions: Option[List[String]] = None,
      chunkIds: Option[List[String]] = None,
      expandNeighbors: Option[Boolean] = None
  ) derives JsonCodec

  final case class OutlineInput(
      documentId: String,
      maxChars: Option[Int] = None
  ) derives JsonCodec

  final case class OutlineOutput(
      documentId: String,
      title: Option[String],
      outlineText: String,
      totalSections: Int,
      foldedSections: Int,
      status: String,
      guidance: Option[String] = None
  ) derives JsonCodec

  /** 一次核对 1 到 6 个块。单个 chunkId 与 chunkIds 合并后去重。 */
  def fetchChunkIds(input: FetchInput): Either[String, Set[String]] =
    val ids = (
      input.chunkIds.getOrElse(Nil).iterator.map(_.trim).filter(_.nonEmpty) ++
        Option(input.chunkId).iterator.map(_.trim).filter(_.nonEmpty)
    ).toList.distinct
    if ids.isEmpty then Left("需要 chunkId，或 1 到 6 个 chunkIds")
    else if ids.length > 6 then Left("一次最多取 6 个 chunkId")
    else Right(ids.toSet)

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

  final case class ExcerptSource(
      documentId: String,
      chunkId: String,
      sourceUri: Option[String] = None
  ) derives JsonCodec

  final case class SearchOutput(
      status: String,
      excerpts: Chunk[String],
      citations: Chunk[CitationOut],
      evidenceStatus: String,
      acceptedCount: Int,
      profileId: Option[String] = None,
      knowledgeSpaceId: Option[String] = None,
      degradedStages: Chunk[String] = Chunk.empty,
      recipe: Option[String] = None,
      strategy: Option[String] = None,
      guidance: Option[String] = None,
      excerptSources: Chunk[ExcerptSource] = Chunk.empty
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
      "在已授权的知识库中检索可引用资料。返回短摘录与 chunkId；需要细读完整上下文时再调用 knowledge_fetch。不要填写 tenant 或 permissions。宿主限定资料时，documentIds 只能收窄，不能改到范围外。",
      searchSchema,
      None,
      knowledgeReadMetadata()
    ) { (input, context) =>
      for
        _         <- rejectCallerOverride(input.tenantId, input.permissions)
        scope     <- trustedScope(context)
        mode      <- parseMode(input.mode)
        hostScope <- hostScope(context, SearchName.value)
        filter    <- parseFilter(input, hostScope)
        recipe    <- ZIO.foreach(input.recipe)(v =>
          ZIO.fromEither(RetrievalRecipe.parse(v)).mapError(AgentError.ToolInputInvalid(SearchName.value, _))
        )
        strategy <- ZIO.foreach(input.strategy)(v =>
          ZIO
            .fromEither(RetrievalRecipe.parseStrategy(v))
            .mapError(AgentError.ToolInputInvalid(SearchName.value, _))
        )
        result <- rag
          .retrieve(RagQuery(input.query, scope, input.limit, mode, filter, recipe, strategy))
          .mapError(error => AgentError.ToolExecutionFailed(SearchName.value, error.message, error.retryable))
      yield toOutput(result, includeFullChunkText = false)
    }

  def fetchTool(rag: RagApplication): Tool[Any, FetchInput, AgentError, SearchOutput] =
    Tool.json[Any, FetchInput, AgentError, SearchOutput](
      FetchName,
      "按 chunkId 或 chunkIds 取回 1 到 6 个已授权知识块全文深入研读。支持设置 expandNeighbors=true 获取前后相邻块，解决跨块分切断问题。不要填写 tenant 或 permissions。",
      fetchSchema,
      None,
      knowledgeReadMetadata()
    ) { (input, context) =>
      for
        _         <- rejectCallerOverride(input.tenantId, input.permissions)
        scope     <- trustedScope(context)
        hostScope <- hostScope(context, FetchName.value)
        filter = hostScope match
          case DocumentScope.Unrestricted    => RetrievalFilter.empty
          case DocumentScope.Restricted(ids) => RetrievalFilter(documentIds = ids)
        chunkIds <- ZIO.fromEither(fetchChunkIds(input)).mapError { message =>
          AgentError.ToolInputInvalid(FetchName.value, message)
        }
        result <- rag
          .fetch(chunkIds, scope, filter)
          .mapError(error => AgentError.ToolExecutionFailed(FetchName.value, error.message, error.retryable))
        _              <- rejectOutsideDocumentScope(FetchName.value, result, hostScope)
        expandedResult <-
          if input.expandNeighbors.contains(true) then
            val neighborIds = result.hits.flatMap { hit =>
              hit.chunk.lineage.toList.flatMap(l => List(l.previousChunkId, l.nextChunkId).flatten)
            }.toSet -- chunkIds
            if neighborIds.nonEmpty then
              rag
                .fetch(neighborIds.take(4), scope, filter)
                .map(neighbors => mergePairedResults(result, neighbors))
                .catchAll(_ => ZIO.succeed(result))
            else ZIO.succeed(result)
          else ZIO.succeed(result)
      yield toOutput(expandedResult, includeFullChunkText = true, isFetch = true)
    }

  /** 成对合并两次检索/取回结果的 `(hit, citation)`，按 `(documentId, chunkId)` 去重并统一重编 `cite-1..N`，防止单列去重导致引用错位。 */
  def mergePairedResults(
      primary: RetrievalResult,
      secondary: RetrievalResult,
      overrideStatus: Option[RetrievalEvidenceStatus] = None
  ): RetrievalResult =
    val pairs = (primary.hits.zip(primary.citations) ++ secondary.hits.zip(secondary.citations))
      .distinctBy { case (hit, _) => hit.chunk.documentId -> hit.chunk.id }
    val combinedHits  = pairs.map(_._1)
    val combinedCites = pairs.zipWithIndex.map { case ((_, citation), index) =>
      citation.copy(id = s"cite-${index + 1}")
    }
    primary.copy(
      hits = combinedHits,
      citations = combinedCites,
      evidence = primary.evidence.copy(
        status = overrideStatus.getOrElse(primary.evidence.status),
        candidateCount = combinedHits.length,
        acceptedCount = combinedHits.length
      )
    )

  def outlineTool(
      store: StructureStore,
      spec: StructureBuildSpec = StructureBuildSpec(),
      rag: Option[RagApplication] = None
  ): Tool[Any, OutlineInput, AgentError, OutlineOutput] =
    Tool.json[Any, OutlineInput, AgentError, OutlineOutput](
      OutlineName,
      "查阅指定书籍或文献的结构化章节大纲（目录树）。用于快速宏观把握全书论述架构、定位特定病症或概念分布的篇章位置。不要填写 tenant 或 permissions。",
      outlineSchema,
      None,
      knowledgeReadMetadata()
    ) { (input, context) =>
      for
        scope     <- trustedScope(context)
        hostScope <- hostScope(context, OutlineName.value)
        _         <- hostScope match
          case DocumentScope.Unrestricted        => ZIO.unit
          case DocumentScope.Restricted(allowed) =>
            ZIO
              .fail(AgentError.ToolInputInvalid(OutlineName.value, "documentId 超出宿主限定的资料范围"))
              .unless(allowed.contains(input.documentId.trim))
        // Persistent structure stores require the active chunk Profile, just like search/fetch.
        pinned <- rag.fold[IO[RetrievalError, RetrievalScope]](ZIO.succeed(scope))(
          _.querySession(scope).map(_._2)
        )
        snapshotOpt <- store.getActive(input.documentId.trim, spec, pinned)
        limit  = input.maxChars.getOrElse(2000).max(200).min(10000)
        output = snapshotOpt match
          case None =>
            OutlineOutput(
              input.documentId,
              None,
              "未找到该文档的结构化目录（可能尚未生成大纲或为无结构文档）。",
              0,
              0,
              "not_found",
              Some("未找到该文档的结构化目录（可能尚未生成大纲或为无结构文档）。建议使用 knowledge_search 直接检索正文内容。")
            )
          case Some(snapshot) =>
            val rendered        = StructureOutline.render(snapshot, limit)
            val title           = snapshot.nodes.headOption.map(_.section.title).filter(_.trim.nonEmpty)
            val outlineGuidance =
              if rendered.folded > 0 then
                Some(
                  s"目录大纲已渲染，因篇幅限制折叠了 ${rendered.folded} 个深层子小节。若需查阅特定子小节，可使用对应小节标题作为 query 调用 knowledge_search 精准检索。"
                )
              else None
            OutlineOutput(
              input.documentId,
              title,
              rendered.text,
              snapshot.nodes.length,
              rendered.folded,
              "ok",
              outlineGuidance
            )
      yield output
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

  /** 解析宿主资料范围。空白、缺失和非法值都拒绝，不会退回全库。 */
  def hostScope(context: ToolExecutionContext, toolName: String): IO[AgentError, DocumentScope] =
    ZIO.fromEither(DocumentScope.parse(context.runContext.attributes)).mapError { message =>
      AgentError.ToolInputInvalid(toolName, message)
    }

  /** 模型给出的 documentIds 只能是宿主范围的子集。未给出时使用宿主范围，不能退回全库。 */
  def constrainDocumentIds(
      toolName: String,
      requested: Set[String],
      host: DocumentScope
  ): IO[AgentError, Set[String]] =
    val clean = requested.filter(_.trim.nonEmpty)
    host match
      case DocumentScope.Unrestricted =>
        ZIO.succeed(clean)
      case DocumentScope.Restricted(allowed) =>
        if clean.isEmpty || clean.subsetOf(allowed) then ZIO.succeed(if clean.isEmpty then allowed else clean)
        else ZIO.fail(AgentError.ToolInputInvalid(toolName, "documentIds 超出宿主限定的资料范围"))

  /** 限定范围时，查询未命中或命中范围外文档都拒绝，且不把范围外正文交给模型。 */
  def rejectOutsideDocumentScope(
      toolName: String,
      result: RetrievalResult,
      host: DocumentScope
  ): IO[AgentError, Unit] =
    host match
      case DocumentScope.Unrestricted        => ZIO.unit
      case DocumentScope.Restricted(allowed) =>
        val found = result.hits.map(_.chunk.documentId).toSet
        if result.hits.nonEmpty && found.subsetOf(allowed) then ZIO.unit
        else ZIO.fail(AgentError.ToolInputInvalid(toolName, "chunk 不在宿主限定的资料范围内"))

  private def parseFilter(input: SearchInput, host: DocumentScope): IO[AgentError, RetrievalFilter] =
    for
      documentIds <- constrainDocumentIds(
        SearchName.value,
        input.documentIds.fold(Set.empty[String])(_.iterator.map(_.trim).filter(_.nonEmpty).toSet),
        host
      )
      filter <- ZIO
        .attempt {
          RetrievalFilter(
            documentIds = documentIds,
            chunkIds = input.chunkIds.fold(Set.empty[String])(_.toSet),
            pages = input.pages.fold(Set.empty[Int])(_.toSet),
            headingPrefix = Chunk.fromIterable(input.headingPrefix.getOrElse(Nil)),
            metadataEquals = input.metadataEquals.getOrElse(Map.empty)
          )
        }
        .mapError(error => AgentError.ToolInputInvalid(SearchName.value, error.getMessage))
    yield filter

  def toSearchOutput(
      result: RetrievalResult,
      includeFullChunkText: Boolean,
      isFetch: Boolean = false
  ): SearchOutput =
    val seedCount = result.evidence.acceptedCount.max(0)
    val seedHits  = result.hits.take(seedCount)
    val seedCites = result.citations.take(seedCount)
    val citations = seedCites.zip(seedHits).map { case (citation, hit) =>
      citationOut(citation, hit)
    }
    val excerpts =
      if includeFullChunkText then result.hits.map(_.chunk.displayText)
      else result.hits.map(hit => ChunkContext.take(hit.chunk.displayText, 500))
    val status =
      if result.evidence.supportsGroundedAnswer then "ok" else "insufficient_evidence"
    val guidance =
      if result.hits.isEmpty || result.evidence.acceptedCount == 0 then
        if isFetch then Some("未找到指定的知识块（chunkId 可能已过期或不存在）。建议：调用 knowledge_search 重新检索以获取最新的有效 chunkId。")
        else
          Some(
            "未检索到直接匹配的证据。建议：1. 提炼或简化检索词，避免使用长问句或口语；2. 尝试换用别名、异名或相关条文关键词；3. 如指定了 documentIds，可检查是否包含目标书籍；4. 可尝试切换 mode 为 lexical 或 phrase 尝试原词匹配。"
          )
      else if !result.evidence.supportsGroundedAnswer then
        if isFetch then Some("细读获得的知识块置信度较低。建议核验上下文，或使用精准关键词重新检索。")
        else Some("检索到的证据置信度较低（低于接受门槛）。建议使用更精准的概念关键词重新检索，或调用 knowledge_fetch 校验相关块。")
      else None
    val excerptSources = result.hits.map(hit =>
      ExcerptSource(
        documentId = hit.chunk.documentId,
        chunkId = hit.chunk.id,
        sourceUri = hit.chunk.metadata.get("sourceUri").orElse(hit.chunk.metadata.get("uri"))
      )
    )
    SearchOutput(
      status,
      excerpts,
      citations,
      result.evidence.status.toString,
      result.evidence.acceptedCount,
      result.diagnostics.profileId,
      result.diagnostics.knowledgeSpaceId,
      result.diagnostics.degradedStages,
      result.diagnostics.recipe,
      result.diagnostics.strategy,
      guidance,
      excerptSources
    )

  private def toOutput(
      result: RetrievalResult,
      includeFullChunkText: Boolean,
      isFetch: Boolean = false
  ): SearchOutput =
    toSearchOutput(result, includeFullChunkText, isFetch)

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
      ChunkContext.take(citation.excerpt, 500),
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
      "query"    -> Json.Obj("type" -> Json.Str("string"), "description" -> Json.Str("检索关键词或精炼短语，避免冗长问句")),
      "recipe"   -> Json.Obj("type" -> Json.Str("string")),
      "strategy" -> Json.Obj("type" -> Json.Str("string")),
      "mode"     -> Json.Obj(
        "type"        -> Json.Str("string"),
        "description" -> Json.Str("检索模式: hybrid | vector | lexical | phrase")
      ),
      "documentIds" -> Json
        .Obj("type" -> Json.Str("array"), "items" -> Json.Obj("type" -> Json.Str("string"))),
      "chunkIds" -> Json.Obj("type" -> Json.Str("array"), "items" -> Json.Obj("type" -> Json.Str("string"))),
      "pages"    -> Json.Obj("type" -> Json.Str("array"), "items" -> Json.Obj("type" -> Json.Str("integer"))),
      "headingPrefix" -> Json
        .Obj("type" -> Json.Str("array"), "items" -> Json.Obj("type" -> Json.Str("string"))),
      "metadataEquals" -> Json
        .Obj("type" -> Json.Str("object"), "additionalProperties" -> Json.Obj("type" -> Json.Str("string"))),
      "limit" -> Json.Obj("type" -> Json.Str("integer"), "description" -> Json.Str("返回最大结果数"))
    ),
    "required" -> Json.Arr(Json.Str("query"))
  )

  private val fetchSchema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "chunkId"  -> Json.Obj("type" -> Json.Str("string"), "description" -> Json.Str("待细读的单个知识块 ID")),
      "chunkIds" -> Json.Obj(
        "type"        -> Json.Str("array"),
        "items"       -> Json.Obj("type" -> Json.Str("string")),
        "description" -> Json.Str("待细读的知识块 ID 列表（1 到 6 个）")
      ),
      "expandNeighbors" -> Json.Obj(
        "type"        -> Json.Str("boolean"),
        "description" -> Json.Str("是否扩展上下文读取邻近前后块，解决跨块分截断问题，默认为 false")
      )
    )
  )

  private val outlineSchema: Json.Obj = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj(
      "documentId" -> Json.Obj("type" -> Json.Str("string"), "description" -> Json.Str("要查阅目录大纲的书籍或文档 ID")),
      "maxChars"   -> Json.Obj("type" -> Json.Str("integer"), "description" -> Json.Str("目录大纲最大字符数，默认 2000"))
    ),
    "required" -> Json.Arr(Json.Str("documentId"))
  )
