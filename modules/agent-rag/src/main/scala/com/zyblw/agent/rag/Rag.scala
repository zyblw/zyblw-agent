package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import zio.*

/** Loader 交给切分器的正文表示。
  *
  * `Markdown` 表示标题、列表、表格、代码块等结构来自受控解析器；它仍然是不可信资料，不能因此提升为 Agent 指令。
  */
enum DocumentRepresentation:
  case PlainText, Markdown

final case class SourceDocument(
    id: String,
    text: String,
    sourceUri: String,
    metadata: Map[String, String] = Map.empty,
    representation: DocumentRepresentation = DocumentRepresentation.PlainText,
    /** 可选的页面/块/几何结构；Markdown 是人类可读正文，structure 是机器可追溯表示，二者不互相代替。 */
    structure: Option[DocumentStructure] = None
)
final case class DocumentChunk(
    id: String,
    documentId: String,
    sourceUri: String,
    tenantId: TenantId,
    permissions: Set[String],
    representations: ChunkRepresentations,
    metadata: Map[String, String] = Map.empty,
    knowledgeSpaceId: Option[KnowledgeSpaceId] = None,
    profileId: Option[IndexProfileId] = None,
    documentRevisionId: Option[String] = None,
    catalogVersion: Long = 1L,
    lineage: Option[ChunkLineage] = None
):
  /** 引用与展示文本。 */
  def displayText: String = representations.displayText

  /** 兼容切分器/测试读取；等于 displayText。 */
  def text: String = displayText

  def denseText: String = representations.denseText

  def lexicalText: String = representations.lexicalText

  def searchText: Option[String] = Some(representations.lexicalText)

  /** 管理面/目录使用的文档修订号，不是 embedding identity。 */
  def indexVersion: Long = catalogVersion

  def withLexical(lexicalText: String): DocumentChunk =
    copy(representations = ChunkRepresentations.of(displayText, denseText, lexicalText))

object DocumentChunk:
  def apply(
      id: String,
      documentId: String,
      text: String,
      sourceUri: String,
      tenantId: TenantId,
      permissions: Set[String]
  ): DocumentChunk =
    fromText(id, documentId, text, sourceUri, tenantId, permissions)

  /** 正文进入三份表示；lexical 可用 searchText 覆盖。 */
  def fromText(
      id: String,
      documentId: String,
      text: String,
      sourceUri: String,
      tenantId: TenantId,
      permissions: Set[String],
      metadata: Map[String, String] = Map.empty,
      searchText: Option[String] = None,
      indexVersion: Long = 1L,
      lineage: Option[ChunkLineage] = None
  ): DocumentChunk =
    new DocumentChunk(
      id,
      documentId,
      sourceUri,
      tenantId,
      permissions,
      ChunkRepresentations.of(text, text, searchText.getOrElse(text)),
      metadata,
      catalogVersion = indexVersion,
      lineage = lineage
    )
final case class Embedding(values: Chunk[Float]):
  require(values.nonEmpty, "Embedding 不能为空")

/** Embedding Provider 的静态能力描述。
  *
  * @param provider
  *   稳定 Provider ID，例如 `openai-embeddings` 或 `glm-embeddings`
  * @param model
  *   实际索引使用的模型名；变更模型通常需要重建整个索引版本
  * @param dimension
  *   每个向量的固定维度，必须与 pgvector `vector(N)` 一致
  * @param maxBatchSize
  *   Adapter 单次 HTTP 请求允许的最大文本数
  * @param supportsDimensions
  *   是否支持请求端显式缩短向量维度
  */
final case class EmbeddingProviderDescriptor(
    provider: String,
    model: String,
    dimension: Int,
    maxBatchSize: Int,
    supportsDimensions: Boolean
):
  require(provider.trim.nonEmpty, "Embedding provider 不能为空")
  require(model.trim.nonEmpty, "Embedding model 不能为空")
  require(dimension > 0, "Embedding dimension 必须为正数")
  require(maxBatchSize > 0, "Embedding maxBatchSize 必须为正数")

/** 一批 Embedding 的用量；兼容服务未返回 usage 时保持 None，而不是伪造零成本。 */
final case class EmbeddingUsage(inputTokens: Long, totalTokens: Long):
  require(inputTokens >= 0L && totalTokens >= 0L, "Embedding usage 不能为负数")

/** 一次逻辑 embed 调用的完整结果。
  *
  * @param embeddings
  *   与输入严格同序、同数量的向量
  * @param usage
  *   所有 HTTP 子批次 usage 的合计；任何子批次缺失时整体为 None
  * @param providerRequestIds
  *   厂商请求 ID，按子批次顺序保存，用于内部排障而不是用户输出
  */
final case class EmbeddingBatchResult(
    embeddings: Chunk[Embedding],
    usage: Option[EmbeddingUsage] = None,
    providerRequestIds: Chunk[String] = Chunk.empty
)

final case class IndexedChunk(
    chunk: DocumentChunk,
    embedding: Embedding,
    sparse: Option[SparseEmbedding] = None
)
final case class RetrievalScope(
    tenantId: TenantId,
    permissions: Set[String],
    /** 网络重试可复用的可选请求 ID；缺失时 Retriever 为本次调用生成随机 ID。 */
    requestId: Option[String] = None,
    knowledgeSpaceId: Option[KnowledgeSpaceId] = None,
    pinnedProfileId: Option[IndexProfileId] = None,
    runId: Option[RunId] = None,
    parentSpanId: Option[String] = None
):
  def spaceId: KnowledgeSpaceId = knowledgeSpaceId.getOrElse(KnowledgeSpaceId("default"))

  def withPinnedProfile(profileId: IndexProfileId): RetrievalScope =
    copy(pinnedProfileId = Some(profileId))

/** 一个检索命中及其可解释的排名信号。
  *
  * @param chunk
  *   已经过 tenant/permission 过滤的资料块
  * @param score
  *   最终统一排序分数；hybrid 实现通常使用加权 RRF
  * @param signals
  *   可观测的子信号，例如 `vectorScore`、`textScore`、`vectorRank`、`textRank`
  */
final case class RetrievalHit(chunk: DocumentChunk, score: Double, signals: Map[String, Double] = Map.empty)
final case class Citation(
    id: String,
    sourceUri: String,
    excerpt: String,
    score: Double,
    /** 从 1 开始的原文页码；纯文本来源可为空。 */
    pageNumbers: Chunk[Int] = Chunk.empty,
    /** 用于 PDF 高亮的可选页内几何信息。 */
    origins: Chunk[DocumentOrigin] = Chunk.empty
)

/** 检索证据是否足以进入回答上下文。
  *
  * 这不是模型置信度，也不声称答案为真；它只说明经过授权、重排和已配置最低分门槛后，是否还有可引用的资料。
  */
enum RetrievalEvidenceStatus:
  /** 默认值供自定义 Retriever 的旧构造调用过渡；生产 Retriever 应显式给出实际状态。 */
  case NotEvaluated

  /** 至少一个 seed 命中通过最低分门槛。 */
  case Supported

  /** 授权范围内没有任何候选。 */
  case NoCandidates

  /** 有候选，但重排后没有接受的 seed。 */
  case NoAcceptedHits

  /** 接受的 seed 全部低于当前 minimumScore。 */
  case BelowMinimumScore

final case class RetrievalEvidence(
    status: RetrievalEvidenceStatus = RetrievalEvidenceStatus.NotEvaluated,
    candidateCount: Int = 0,
    acceptedCount: Int = 0,
    topAcceptedScore: Option[Double] = None,
    minimumScore: Double = 0.0
):
  require(candidateCount >= 0 && acceptedCount >= 0 && acceptedCount <= candidateCount, "检索证据数量无效")
  require(minimumScore.isFinite, "检索证据最低分必须是有限数")
  require(topAcceptedScore.forall(java.lang.Double.isFinite), "检索证据最高分必须是有限数")

  /** 仅在明确没有足够证据时阻止资料注入；`NotEvaluated` 保持对旧自定义 Retriever 的兼容。 */
  def supportsGroundedAnswer: Boolean = status match
    case RetrievalEvidenceStatus.NoCandidates | RetrievalEvidenceStatus.NoAcceptedHits |
        RetrievalEvidenceStatus.BelowMinimumScore =>
      false
    case RetrievalEvidenceStatus.NotEvaluated | RetrievalEvidenceStatus.Supported => true

final case class RetrievalResult(
    hits: Chunk[RetrievalHit],
    citations: Chunk[Citation],
    evidence: RetrievalEvidence = RetrievalEvidence(),
    diagnostics: RetrievalDiagnostics = RetrievalDiagnostics()
)

trait Chunker:
  /** 能完整区分算法及其影响输出参数的稳定标识；索引 manifest 默认使用它阻止错误重放。 */
  def strategyId: String

  /** 切分时绑定 tenant 和权限，确保权限过滤可在相似度计算之前发生。 */
  def split(document: SourceDocument, tenantId: TenantId, permissions: Set[String]): UIO[Chunk[DocumentChunk]]

/** 按字符窗口确定性切分，保留 overlap；生产可替换为 token/语义切分器。 */
final class SlidingWindowChunker(maxCharacters: Int = 1200, overlap: Int = 120) extends Chunker:
  require(maxCharacters > 0 && overlap >= 0 && overlap < maxCharacters)

  override val strategyId: String = s"sliding-window-v1:max=$maxCharacters:overlap=$overlap"

  /** 按滑动字符窗口切分；overlap 保留跨边界上下文，空文档返回空 Chunk。 */
  def split(
      document: SourceDocument,
      tenantId: TenantId,
      permissions: Set[String]
  ): UIO[Chunk[DocumentChunk]] =
    ZIO.succeed {
      val step   = maxCharacters - overlap
      val chunks = Iterator.iterate(0)(_ + step).takeWhile(_ < document.text.length).zipWithIndex.map {
        case (start, index) =>
          DocumentChunk.fromText(
            id = s"${document.id}-$index",
            documentId = document.id,
            text = document.text.slice(start, (start + maxCharacters).min(document.text.length)),
            sourceUri = document.sourceUri,
            tenantId = tenantId,
            permissions = permissions,
            metadata = document.metadata
          )
      }
      Chunk.fromIterable(chunks.toList)
    }

/** 本地测试模型的默认 dense 能力。 */
object EmbeddingDefaults:
  def denseCapabilities(dimension: Int, maxBatch: Int = Int.MaxValue): EmbeddingCapabilities =
    EmbeddingCapabilities(
      inputRoles = Set(EmbeddingInputRole.Query, EmbeddingInputRole.Document),
      outputs = Set(EmbeddingOutputKind.Dense),
      minDenseDimension = dimension,
      maxDenseDimension = dimension,
      defaultDenseDimension = dimension,
      maxTextsPerRequest = maxBatch
    )

trait VectorStore:
  /** 插入或更新带向量的文档块。 */
  def upsert(chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit]

  /** 在指定租户/权限 scope 内搜索并限制返回数量。 */
  def search(query: Embedding, scope: RetrievalScope, limit: Int): IO[RetrievalError, Chunk[RetrievalHit]]

  /** 使用原始 query 与向量执行 hybrid search。
    *
    * 默认实现提供明确的纯向量策略；PostgreSQL 等支持 FTS 的 Adapter 应覆盖。这样 Retriever 不通过 运行时类型判断或 Provider 特例选择检索策略。
    */
  def searchHybrid(
      queryText: String,
      query: Embedding,
      scope: RetrievalScope,
      limit: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    searchFiltered(RetrievalMode.Hybrid, queryText, query, scope, RetrievalFilter.empty, limit)

  /** 授权之后、排序之前应用结构化过滤的统一检索入口。
    *
    * `DefaultRetriever` 只调用本方法。默认实现取更大候选池再内存过滤，且不得再回调 `searchHybrid`，以免与 `searchHybrid` 的默认委托形成递归。覆盖了
    * `searchHybrid` 的自定义 Store 必须同时覆盖本方法。生产 Adapter 必须在 SQL 中下推过滤。
    */
  def searchFiltered(
      mode: RetrievalMode,
      queryText: String,
      query: Embedding,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      limit: Int,
      sparseQuery: Option[SparseEmbedding] = None
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    val _    = (mode, queryText, sparseQuery)
    val pool = math.min(math.max(limit, 1).toLong * 8L, Int.MaxValue.toLong).toInt
    search(query, scope, pool).map(_.filter(hit => filter.matches(hit.chunk)).take(limit.max(0)))

  /** 按 chunk ID 精确再识别；必须再次应用 tenant/permission，跨租户 ID 不得命中。 */
  def fetchChunks(
      chunkIds: Set[String],
      scope: RetrievalScope
  ): IO[RetrievalError, Chunk[DocumentChunk]] =
    val _ = (chunkIds, scope)
    ZIO.succeed(Chunk.empty)

  /** 在读取正文之前应用文档范围。未覆盖时先调用两参数版本再丢弃范围外的块；生产 Store 应覆盖并在查询中下推。 */
  def fetchChunks(
      chunkIds: Set[String],
      scope: RetrievalScope,
      filter: RetrievalFilter
  ): IO[RetrievalError, Chunk[DocumentChunk]] =
    fetchChunks(chunkIds, scope).map(_.filter(filter.matches))

  /** 在 rerank 之后根据受控谱系补充相邻块和同父级块。
    *
    * 默认实现不扩展，使纯向量的自定义 Store 仍可以最小实现。生产 Store 必须在读取扩展候选时再次应用 tenant/permission 条件，不得信任 seed metadata。
    */
  def expandContext(
      seeds: Chunk[RetrievalHit],
      scope: RetrievalScope,
      config: RetrievalExpansionConfig
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    val _ = (seeds, scope, config)
    ZIO.succeed(Chunk.empty)

  /** 查询向量身份必须与该租户 pinned/active Profile 一致；空库视为通过。 */
  def assertEmbeddingIdentity(
      tenantId: TenantId,
      descriptor: EmbeddingProviderDescriptor,
      spaceId: KnowledgeSpaceId = KnowledgeSpaceId("default"),
      profileId: Option[IndexProfileId] = None
  ): IO[RetrievalError, Unit] =
    val _ = (tenantId, descriptor, spaceId, profileId)
    ZIO.unit

  /** 读取空间当前 active Profile；尚无指针时为 None。 */
  def resolveActiveProfile(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId
  ): IO[RetrievalError, Option[IndexProfileId]] =
    val _ = (tenantId, spaceId)
    ZIO.succeed(None)

  /** 删除租户内某原始文档的全部块。 */
  def deleteByDocument(documentId: String, tenantId: TenantId): IO[RetrievalError, Unit]

/** 测试和本地开发向量库。权限和 tenant 过滤在相似度计算之前执行，防止越权数据进入候选集。
  */
final class InMemoryVectorStore private (
    state: Ref.Synchronized[Map[(TenantId, String, String), IndexedChunk]]
) extends VectorStore:
  /** 以 tenant/document/chunk 复合身份原子 upsert；局部 chunk ID 在另一文档中复用不会互相覆盖。 */
  def upsert(chunks: Chunk[IndexedChunk]): UIO[Unit] =
    state.update(current =>
      current ++ chunks.map(item => (item.chunk.tenantId, item.chunk.documentId, item.chunk.id) -> item)
    )

  /** 先过滤 tenant/permission，再计算 cosine，防止未授权内容进入候选集。 */
  def search(query: Embedding, scope: RetrievalScope, limit: Int): IO[RetrievalError, Chunk[RetrievalHit]] =
    searchFiltered(RetrievalMode.VectorOnly, "", query, scope, RetrievalFilter.empty, limit)

  override def searchFiltered(
      mode: RetrievalMode,
      queryText: String,
      query: Embedding,
      scope: RetrievalScope,
      filter: RetrievalFilter,
      limit: Int,
      sparseQuery: Option[SparseEmbedding] = None
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    if limit <= 0 then ZIO.succeed(Chunk.empty)
    else
      state.get.map { all =>
        val authorized = all.valuesIterator.filter { item =>
          item.chunk.tenantId == scope.tenantId &&
          item.chunk.permissions.subsetOf(scope.permissions) &&
          filter.matches(item.chunk)
        }
        Chunk.fromIterable(RetrievalScoring.rank(mode, queryText, query, authorized, sparseQuery).take(limit))
      }

  override def fetchChunks(
      chunkIds: Set[String],
      scope: RetrievalScope
  ): UIO[Chunk[DocumentChunk]] =
    fetchChunks(chunkIds, scope, RetrievalFilter.empty)

  override def fetchChunks(
      chunkIds: Set[String],
      scope: RetrievalScope,
      filter: RetrievalFilter
  ): UIO[Chunk[DocumentChunk]] =
    if chunkIds.isEmpty then ZIO.succeed(Chunk.empty)
    else
      state.get.map { all =>
        Chunk.fromIterable(
          all.valuesIterator
            .map(_.chunk)
            .filter(chunk =>
              chunkIds.contains(chunk.id) &&
                chunk.tenantId == scope.tenantId &&
                chunk.permissions.subsetOf(scope.permissions) &&
                filter.matches(chunk)
            )
            .toVector
            .sortBy(_.id)
        )
      }

  /** 内存实现与 PostgreSQL Adapter 共享相同语义：先授权，再选相邻/同父级，最后做全局有界截断。 */
  override def expandContext(
      seeds: Chunk[RetrievalHit],
      scope: RetrievalScope,
      config: RetrievalExpansionConfig
  ): UIO[Chunk[RetrievalHit]] =
    if seeds.isEmpty || config.maxAdditionalChunks == 0 then ZIO.succeed(Chunk.empty)
    else
      state.get.map { all =>
        val seedKeys   = seeds.map(hit => hit.chunk.documentId -> hit.chunk.id).toSet
        val authorized = all.valuesIterator
          .map(_.chunk)
          .filter(chunk =>
            !seedKeys.contains(chunk.documentId -> chunk.id) && chunk.tenantId == scope.tenantId &&
              chunk.permissions.subsetOf(scope.permissions)
          )
          .toVector
        val neighborScores: Map[(String, String), Double] =
          if config.neighborRadius == 0 then Map.empty[(String, String), Double]
          else
            seeds
              .flatMap { seed =>
                val ids = seed.chunk.lineage.fold(Chunk.empty[String])(lineage =>
                  Chunk.fromIterable(lineage.previousChunkId) ++ Chunk.fromIterable(lineage.nextChunkId)
                )
                ids.map(id => (seed.chunk.documentId -> id) -> seed.score)
              }
              .toList
              .groupMapReduce(_._1)(_._2)(math.max)
        val parentScores = seeds
          .flatMap(hit =>
            hit.chunk.lineage
              .flatMap(_.parentId)
              .map(parentId => (hit.chunk.documentId -> parentId) -> hit.score)
          )
          .groupBy(_._1)
          .collect {
            case (parentKey, values) if values.length >= config.parentHitThreshold =>
              parentKey -> values.map(_._2).max
          }
        val siblingsByParent = authorized
          .flatMap(chunk =>
            chunk.lineage.flatMap(_.parentId).map(parentId => (chunk.documentId -> parentId) -> chunk)
          )
          .groupBy(_._1)
          .view
          .mapValues(values => values.map(_._2).sortBy(_.lineage.fold(Int.MaxValue)(_.ordinal)))
          .toMap
        val neighborHits = authorized
          .flatMap(chunk =>
            neighborScores
              .get(chunk.documentId -> chunk.id)
              .map(score => expandedHit(chunk, score, "neighbor", config))
          )
          .sortBy(hit => (-hit.score, hit.chunk.lineage.fold(Int.MaxValue)(_.ordinal), hit.chunk.id))
        val neighborKeys = neighborHits.map(hit => hit.chunk.documentId -> hit.chunk.id).toSet
        val siblingHits  = parentScores.toVector.sortBy(_._1).flatMap { case (parentKey, score) =>
          siblingsByParent
            .getOrElse(parentKey, Vector.empty)
            .filterNot(chunk => neighborKeys.contains(chunk.documentId -> chunk.id))
            .take(config.maxSiblingsPerParent)
            .map(chunk => expandedHit(chunk, score, "parentSibling", config))
        }
        Chunk.fromIterable(
          (neighborHits ++ siblingHits)
            .distinctBy(hit => hit.chunk.documentId -> hit.chunk.id)
            .take(config.maxAdditionalChunks)
        )
      }

  private def expandedHit(
      chunk: DocumentChunk,
      seedScore: Double,
      reason: String,
      config: RetrievalExpansionConfig
  ): RetrievalHit =
    RetrievalHit(
      chunk,
      seedScore * config.expandedScoreFactor,
      Map("contextExpanded" -> 1.0, s"context.$reason" -> 1.0)
    )

  /** 按 tenantId+documentId 删除条目。 */
  def deleteByDocument(documentId: String, tenantId: TenantId): UIO[Unit] =
    state.update(
      _.filterNot((_, item) => item.chunk.documentId == documentId && item.chunk.tenantId == tenantId)
    )

object InMemoryVectorStore:
  val layer: ULayer[VectorStore] =
    ZLayer.fromZIO(
      Ref.Synchronized
        .make(Map.empty[(TenantId, String, String), IndexedChunk])
        .map(InMemoryVectorStore(_))
    )

trait Reranker:
  /** 根据 query 重排候选并截取 limit；实现可接 cross-encoder。 */
  def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): IO[RetrievalError, Chunk[RetrievalHit]]

object Reranker:
  val identity: ULayer[Reranker] = ZLayer.succeed(
    new Reranker:
      def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): UIO[Chunk[RetrievalHit]] =
        ZIO.succeed(hits.take(limit))
  )

trait Retriever:
  /** 完成 query embedding、权限检索、rerank 和引用组装。 */
  def retrieve(query: String, scope: RetrievalScope, limit: Int): IO[RetrievalError, RetrievalResult] =
    retrieve(RetrievalRequest(query, scope, limit))

  def retrieve(request: RetrievalRequest): IO[RetrievalError, RetrievalResult]

  /** 按 chunkId 精确再识别；默认实现拒绝，避免自定义 Retriever 静默返回空而伪装成“没有这段”。 */
  def fetch(chunkIds: Set[String], scope: RetrievalScope): IO[RetrievalError, RetrievalResult] =
    val _ = (chunkIds, scope)
    ZIO.fail(AgentError.RetrievalFailed("Retriever 未实现 fetchChunks"))

  /** 取回前施加文档范围。未覆盖时先调用两参数 `fetch`，再丢弃范围外的块。 */
  def fetch(
      chunkIds: Set[String],
      scope: RetrievalScope,
      filter: RetrievalFilter
  ): IO[RetrievalError, RetrievalResult] =
    fetch(chunkIds, scope).map { result =>
      val hits = result.hits.filter(hit => filter.matches(hit.chunk))
      result.copy(hits = hits, citations = result.citations.take(hits.length))
    }

final class DefaultRetriever(
    embeddings: EmbeddingModel,
    vectors: VectorStore,
    reranker: Reranker,
    expansion: RetrievalExpansionConfig = RetrievalExpansionConfig(),
    policies: RetrievalPolicySource = RetrievalPolicySource.default,
    lexical: LexicalProcessor = SimpleChineseLexicalProcessor,
    budgets: CandidateBudgets = CandidateBudgets(),
    sparseEnabled: Boolean = false,
    assist: QueryAssist = QueryAssist.disabled,
    assistConfig: QueryAssistConfig = QueryAssistConfig(),
    telemetry: Option[com.zyblw.agent.observability.AgentOperationTelemetry] = None
) extends Retriever:
  /** 把单 query 编码后搜索并重排，最终引用保留 source 与 metadata。 */
  def retrieve(request: RetrievalRequest): IO[RetrievalError, RetrievalResult] =
    val query = request.text
    val scope = request.scope
    val limit = request.limit
    if limit <= 0 then
      ZIO.succeed(
        RetrievalResult(
          Chunk.empty,
          Chunk.empty,
          RetrievalEvidence(RetrievalEvidenceStatus.NoAcceptedHits)
        )
      )
    else if query.trim.isEmpty then ZIO.fail(AgentError.RetrievalFailed("Retrieval query 不能为空"))
    else
      // 单次检索内只读取一次工作点，避免同一次调用的重排开关和阈值来自不同版本的覆盖。
      val policy = policies.current()
      val plan   = DeterministicQueryPlanner.plan(
        query,
        request.mode,
        budgets.copy(rerankSeeds = limit, perBranch = math.max(budgets.perBranch, limit)),
        sparseEnabled
      )
      val wantSparse =
        plan.includeSparse && embeddings.capabilities.outputs.contains(EmbeddingOutputKind.DenseAndSparse)
      val searchMode =
        request.mode // Explicit caller modes are authoritative; planning never replaces a branch.
      for
        resolvedProfile <- scope.pinnedProfileId.fold(
          vectors.resolveActiveProfile(scope.tenantId, scope.spaceId)
        )(id => ZIO.succeed(Some(id)))
        pinned      = resolvedProfile.getOrElse(IndexProfileId("default"))
        pinnedScope = scope.withPinnedProfile(pinned)
        requestId <- pinnedScope.requestId.fold(Random.nextUUID.map(_.toString))(ZIO.succeed(_))
        rewritten <-
          if assistConfig.enabled then
            assist
              .rewrite(plan.subqueries.head, assistConfig)
              .timeoutFail(AgentError.RetrievalFailed("query assist 超时"))(assistConfig.timeout)
              .flatMap(value =>
                if value.rewritten.codePointCount(0, value.rewritten.length) <= 4000 then
                  ZIO.succeed(Some(value.rewritten))
                else ZIO.fail(AgentError.RetrievalFailed("Query rewrite exceeds input limit"))
              )
              .catchAll(_ => ZIO.succeed(None))
          else ZIO.succeed(None)
        subqueries = rewritten.fold(plan.subqueries)(value => Chunk(value))
        traceRun <- scope.runId.fold(RunId.random)(ZIO.succeed(_))
        detailed <- observe(traceRun, scope, "embed")(
          embeddings.embed(
            EmbeddingRequest(
              subqueries,
              EmbeddingInputRole.Query,
              if wantSparse then EmbeddingOutputKind.DenseAndSparse else EmbeddingOutputKind.Dense,
              context = EmbeddingRequestContext(
                pinnedScope.tenantId,
                EmbeddingPurpose.Query,
                requestId,
                knowledgeSpaceId = Some(pinnedScope.spaceId),
                permissionFingerprint = pinnedScope.permissions.toList.sorted.mkString(",")
              )
            )
          )
        )(_.items.length.toLong)
        _ <- ZIO
          .fail(
            AgentError.RetrievalFailed(
              "Query embedding response violates count, identity or dimension contract"
            )
          )
          .unless(
            detailed.items.length == subqueries.length &&
              detailed.descriptor.provider == embeddings.descriptor.provider &&
              detailed.descriptor.model == embeddings.descriptor.model &&
              detailed.items
                .forall(_.dense.exists(_.values.length == embeddings.capabilities.defaultDenseDimension))
          )
        queryEmbedding <- ZIO
          .fromOption(detailed.denseEmbeddings.headOption)
          .orElseFail(AgentError.RetrievalFailed("Embedding provider 返回空结果"))
        _ <- vectors.assertEmbeddingIdentity(
          pinnedScope.tenantId,
          embeddings.descriptor.denseDescriptor,
          pinnedScope.spaceId,
          Some(pinned)
        )
        candidateLimit = plan.budgets.perBranch
        lexicalQuery   =
          if searchMode == RetrievalMode.Phrase then subqueries.head else lexical.query(subqueries.head)
        first <- observe(traceRun, scope, "hybrid_search")(
          vectors.searchFiltered(
            searchMode,
            lexicalQuery,
            queryEmbedding,
            pinnedScope,
            request.filter,
            candidateLimit,
            sparseQuery = if wantSparse then detailed.items.headOption.flatMap(_.sparse) else None
          )
        )(_.length.toLong)
        rest <- ZIO.foreach(subqueries.zip(detailed.denseEmbeddings).drop(1)) { case (subquery, embedding) =>
          vectors.searchFiltered(
            searchMode,
            if searchMode == RetrievalMode.Phrase then subquery else lexical.query(subquery),
            embedding,
            pinnedScope,
            request.filter,
            candidateLimit,
            sparseQuery = None
          )
        }
        candidates = DefaultRetriever.mergeHits(first +: rest, plan.budgets.fusion)
        // 关闭重排时直接截断候选池。这里不能跳过后续校验：截断结果同样要满足数量、去重和权限契约，
        // 而 searchFiltered 来自存储 Adapter，与 reranker 一样位于信任边界之外。
        reranked <-
          if policy.rerankEnabled then
            observe(traceRun, scope, "rerank")(reranker.rerank(query, candidates, limit))(_.length.toLong)
          else ZIO.succeed(candidates.take(limit))
        // Reranker 可能是远端或业务自定义实现；即使它失陷，也不能注入候选集外或未授权文档。
        validated <- validateReranked(candidates, reranked, pinnedScope, limit)
        // 阈值只作用于 seed 命中。RRF fused score 只排序；余弦/词法决定是否接受。
        // 扩展块按 expandedScoreFactor 主动降分，不得再用同一阈值筛掉它们。
        hits     = validated.filter(hit => DefaultRetriever.acceptsSeed(hit, policy.minimumScore))
        evidence = RetrievalEvidence(
          status =
            if candidates.isEmpty then RetrievalEvidenceStatus.NoCandidates
            else if validated.isEmpty then RetrievalEvidenceStatus.NoAcceptedHits
            else if hits.isEmpty then RetrievalEvidenceStatus.BelowMinimumScore
            else RetrievalEvidenceStatus.Supported,
          candidateCount = candidates.length,
          acceptedCount = hits.length,
          topAcceptedScore = hits.map(DefaultRetriever.relevanceScore).maxOption,
          minimumScore = policy.minimumScore
        )
        expanded <- observe(traceRun, scope, "expand")(vectors.expandContext(hits, pinnedScope, expansion))(
          _.length.toLong
        )
        context <- validateExpanded(hits, expanded, pinnedScope, expansion.maxAdditionalChunks)
        bundle  <- observe(traceRun, scope, "assemble")(
          ZIO.succeed(
            ContextAssembler.assemble(
              hits,
              context.filterNot(hit =>
                hits.exists(seed =>
                  seed.chunk.documentId == hit.chunk.documentId && seed.chunk.id == hit.chunk.id
                )
              ),
              evidence,
              plan.budgets,
              profileId = Some(pinned),
              knowledgeSpaceId = Some(pinnedScope.spaceId),
              degradedStages = Chunk.fromIterable(
                Option.when(assistConfig.enabled && rewritten.isEmpty)("rewrite-fallback") ++
                  Option.when(plan.includeSparse && !wantSparse)("sparse-disabled") ++
                  Option
                    .when(reranked.exists(_.signals.get("rerankFallback").contains(1.0)))("rerank-fallback")
              )
            )
          )
        )(
          _.items
            .count(item =>
              item.decision == EvidenceDecision.KeptSeed || item.decision == EvidenceDecision.KeptExpanded
            )
            .toLong
        )
      yield bundle.toRetrievalResult

  private def observe[A](runId: RunId, scope: RetrievalScope, stage: String)(effect: IO[RetrievalError, A])(
      count: A => Long
  ): IO[RetrievalError, A] =
    telemetry.fold(effect)(_.retrieval(runId, stage, scope.parentSpanId)(effect)(count))

  override def fetch(chunkIds: Set[String], scope: RetrievalScope): IO[RetrievalError, RetrievalResult] =
    fetch(chunkIds, scope, RetrievalFilter.empty)

  override def fetch(
      chunkIds: Set[String],
      scope: RetrievalScope,
      filter: RetrievalFilter
  ): IO[RetrievalError, RetrievalResult] =
    if chunkIds.isEmpty then
      ZIO.succeed(
        RetrievalResult(
          Chunk.empty,
          Chunk.empty,
          RetrievalEvidence(RetrievalEvidenceStatus.NoAcceptedHits)
        )
      )
    else
      val pin = scope.pinnedProfileId.fold(vectors.resolveActiveProfile(scope.tenantId, scope.spaceId))(id =>
        ZIO.succeed(Some(id))
      )
      pin.flatMap { resolved =>
        val pinned      = resolved.getOrElse(IndexProfileId("default"))
        val pinnedScope = scope.withPinnedProfile(pinned)
        vectors.fetchChunks(chunkIds, pinnedScope, filter).map { chunks =>
          val hits = chunks.zipWithIndex.map { case (chunk, index) =>
            RetrievalHit(chunk, 1.0d, Map("fetch" -> 1.0d, "ordinal" -> index.toDouble))
          }
          val citations = hits.zipWithIndex.map { case (hit, index) =>
            val origins = hit.chunk.lineage.fold(Chunk.empty[DocumentOrigin])(_.origins)
            Citation(
              s"cite-${index + 1}",
              hit.chunk.sourceUri,
              hit.chunk.displayText.take(500),
              hit.score,
              origins.map(_.pageNumber).distinct,
              origins
            )
          }
          RetrievalResult(
            hits,
            citations,
            RetrievalEvidence(
              if hits.isEmpty then RetrievalEvidenceStatus.NoAcceptedHits
              else RetrievalEvidenceStatus.Supported,
              candidateCount = hits.length,
              acceptedCount = hits.length,
              topAcceptedScore = hits.map(_.score).maxOption
            ),
            RetrievalDiagnostics(
              profileId = Some(pinned.value),
              knowledgeSpaceId = Some(pinnedScope.spaceId.value),
              selections = hits.map(hit =>
                EvidenceSelection(
                  hit.chunk.documentId,
                  hit.chunk.id,
                  hit.chunk.lineage.flatMap(_.seedChunkId).getOrElse(hit.chunk.id),
                  EvidenceDecision.KeptSeed
                )
              )
            )
          )
        }
      }

  /** 在 Reranker 信任边界之后重新验证身份、授权、数量和数值。
    *
    * Reranker 只能改变候选顺序、score 和 signals，不能制造新 DocumentChunk。这里使用完整不可变 chunk 相等性而不是 只比较易碰撞的 chunkId，并拒绝重复项与
    * NaN/Infinity，防止后续阈值和排序被非有限数绕过。
    */
  private def validateReranked(
      candidates: Chunk[RetrievalHit],
      hits: Chunk[RetrievalHit],
      scope: RetrievalScope,
      limit: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    val candidateChunks = candidates.map(_.chunk).toSet
    val chunks          = hits.map(_.chunk)
    val valid = hits.length <= limit && chunks.distinct.length == chunks.length && hits.forall { hit =>
      candidateChunks.contains(hit.chunk) &&
      hit.chunk.tenantId == scope.tenantId &&
      hit.chunk.permissions.subsetOf(scope.permissions) &&
      java.lang.Double.isFinite(hit.score) &&
      hit.signals.values.forall(java.lang.Double.isFinite)
    }
    if valid then ZIO.succeed(hits)
    else ZIO.fail(AgentError.RetrievalFailed("Reranker 输出违反候选身份、权限、数量或有限值契约"))

  /** 扩展候选来自存储 Adapter 而不是 reranker 候选集，因此单独复核授权、重复、数量和数值边界。 */
  private def validateExpanded(
      seeds: Chunk[RetrievalHit],
      expanded: Chunk[RetrievalHit],
      scope: RetrievalScope,
      maxAdditionalChunks: Int
  ): IO[RetrievalError, Chunk[RetrievalHit]] =
    val seedKeys     = seeds.map(hit => hit.chunk.documentId -> hit.chunk.id).toSet
    val expandedKeys = expanded.map(hit => hit.chunk.documentId -> hit.chunk.id)
    val valid        =
      expanded.length <= maxAdditionalChunks && expandedKeys.distinct.length == expandedKeys.length &&
        expandedKeys.forall(key => !seedKeys.contains(key)) && expanded.forall { hit =>
          hit.chunk.tenantId == scope.tenantId && hit.chunk.permissions.subsetOf(scope.permissions) &&
          java.lang.Double.isFinite(hit.score) && hit.signals.values.forall(java.lang.Double.isFinite)
        }
    if valid then ZIO.succeed(seeds ++ expanded)
    else ZIO.fail(AgentError.RetrievalFailed("上下文扩展输出违反权限、数量或有限值契约"))

object DefaultRetriever:
  /** 词法命中直接接受；仅向量近邻必须过余弦阈值。RRF fused score 不参与门槛。 */
  def acceptsSeed(hit: RetrievalHit, minimumScore: Double): Boolean =
    hasLexicalSupport(hit) || relevanceScore(hit) >= minimumScore

  def relevanceScore(hit: RetrievalHit): Double =
    hit.signals.get("vectorScore").getOrElse {
      if hasLexicalSupport(hit) && hit.signals.contains("textScore") then hit.signals("textScore")
      else if hasLexicalSupport(hit) then hit.score
      else hit.score
    }

  private def hasLexicalSupport(hit: RetrievalHit): Boolean =
    hit.signals.get("textScore").exists(_ > 0.0) || hit.signals.get("textRank").exists(_ > 0.0)

  def mergeHits(groups: Chunk[Chunk[RetrievalHit]], limit: Int): Chunk[RetrievalHit] =
    Chunk.fromIterable(
      groups.flatten
        .groupBy(hit => hit.chunk.documentId -> hit.chunk.id)
        .values
        .map(_.maxBy(hit => (hit.score, hit.chunk.documentId, hit.chunk.id)))
        .toVector
        .sortBy(hit => (-hit.score, hit.chunk.documentId, hit.chunk.id))
        .take(limit.max(0))
    )

  val layer: URLayer[EmbeddingModel & VectorStore & Reranker, Retriever] =
    ZLayer.fromFunction((embeddings: EmbeddingModel, vectors: VectorStore, reranker: Reranker) =>
      DefaultRetriever(embeddings, vectors, reranker)
    )

  val observedLayer: URLayer[
    EmbeddingModel & VectorStore & Reranker & RetrievalPolicySource &
      com.zyblw.agent.observability.AgentOperationTelemetry,
    Retriever
  ] =
    ZLayer.fromFunction(
      (
          model: EmbeddingModel,
          store: VectorStore,
          reranker: Reranker,
          policy: RetrievalPolicySource,
          telemetry: com.zyblw.agent.observability.AgentOperationTelemetry
      ) => DefaultRetriever(model, store, reranker, policies = policy, telemetry = Some(telemetry)): Retriever
    )

  /** 接入运行时覆盖的装配；宿主提供由 `RuntimeSettingsService` 支撑的解析器后，管理台调整 topK、 最低得分与重排开关即可在下一次检索生效。
    */
  val governedLayer: URLayer[EmbeddingModel & VectorStore & Reranker & RetrievalPolicySource, Retriever] =
    ZLayer.fromFunction(
      (
          embeddings: EmbeddingModel,
          vectors: VectorStore,
          reranker: Reranker,
          policies: RetrievalPolicySource
      ) => DefaultRetriever(embeddings, vectors, reranker, RetrievalExpansionConfig(), policies)
    )

  /** 让宿主以 ZLayer 明确替换中文 baseline，例如接入经过评测的领域词典 tokenizer。 */
  val lexicalLayer: URLayer[EmbeddingModel & VectorStore & Reranker & LexicalProcessor, Retriever] =
    ZLayer.fromFunction(
      (embeddings: EmbeddingModel, vectors: VectorStore, reranker: Reranker, lexical: LexicalProcessor) =>
        DefaultRetriever(embeddings, vectors, reranker, lexical = lexical)
    )

  val governedLexicalLayer: URLayer[
    EmbeddingModel & VectorStore & Reranker & RetrievalPolicySource & LexicalProcessor,
    Retriever
  ] =
    ZLayer.fromFunction(
      (
          embeddings: EmbeddingModel,
          vectors: VectorStore,
          reranker: Reranker,
          policies: RetrievalPolicySource,
          lexical: LexicalProcessor
      ) => DefaultRetriever(embeddings, vectors, reranker, RetrievalExpansionConfig(), policies, lexical)
    )

/** 确定性测试 embedding，不应用于真实语义检索。 */
final class HashEmbedding(val dimension: Int = 64) extends EmbeddingModel:
  override val capabilities: EmbeddingCapabilities       = EmbeddingDefaults.denseCapabilities(dimension)
  override val descriptor: EmbeddingProviderDescriptorV2 =
    EmbeddingProviderDescriptorV2("hash", s"hash-$dimension", capabilities)

  def embed(request: EmbeddingRequest): IO[RetrievalError, EmbeddingResponse] =
    validate(request) *> ZIO.succeed(
      EmbeddingResponse(
        request.texts.map(text => EmbeddingItem(Some(hashOne(text)), None)),
        descriptor
      )
    )

  private def hashOne(text: String): Embedding =
    val values = Array.fill[Float](dimension)(0.0f)
    text.codePoints().toArray.zipWithIndex.foreach { case (point, index) =>
      val slot = Math.floorMod(point * 31 + index, dimension)
      values(slot) = values(slot) + 1.0f
    }
    Embedding(Chunk.fromArray(values))

object EmbeddingModelOps:
  def testContext(
      tenantId: TenantId = TenantId("test"),
      purpose: EmbeddingPurpose = EmbeddingPurpose.Indexing,
      requestId: String = "test-embed"
  ): EmbeddingRequestContext = EmbeddingRequestContext(tenantId, purpose, requestId)

  def embedTexts(
      model: EmbeddingModel,
      texts: Chunk[String],
      role: EmbeddingInputRole = EmbeddingInputRole.Document,
      context: EmbeddingRequestContext = testContext()
  ): IO[RetrievalError, Chunk[Embedding]] =
    if texts.isEmpty then ZIO.succeed(Chunk.empty)
    else
      model
        .embed(EmbeddingRequest(texts, role, EmbeddingOutputKind.Dense, context = context))
        .map(_.denseEmbeddings)
