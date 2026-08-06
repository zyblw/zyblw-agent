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
    text: String,
    sourceUri: String,
    tenantId: TenantId,
    permissions: Set[String],
    metadata: Map[String, String] = Map.empty,
    /** 可选的全文检索文本。中文部署可在摄取阶段使用受控分词器生成空格分隔 lexeme；模型不可填写。 `None` 时存储实现使用原始 `text`。
      */
    searchText: Option[String] = None,
    /** 所属知识索引版本；普通内存用例默认 1，耐久索引发布时由 `KnowledgeIndexer` 覆盖。 */
    indexVersion: Long = 1L,
    /** 受控切分器生成的父子、相邻、页码与 bbox 谱系；纯文本或旧切分器可为 None。 */
    lineage: Option[ChunkLineage] = None
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

final case class IndexedChunk(chunk: DocumentChunk, embedding: Embedding)
final case class RetrievalScope(
    tenantId: TenantId,
    permissions: Set[String],
    /** 网络重试可复用的可选请求 ID；缺失时 Retriever 为本次调用生成随机 ID。 */
    requestId: Option[String] = None
)

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
final case class RetrievalResult(hits: Chunk[RetrievalHit], citations: Chunk[Citation])

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
          DocumentChunk(
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

trait EmbeddingService:
  /** 返回固定向量维度；同一索引内所有向量必须一致。 */
  def dimension: Int

  /** Provider、模型、维度与批量能力；最小自定义实现可使用框架生成的本地描述。 */
  def descriptor: EmbeddingProviderDescriptor =
    EmbeddingProviderDescriptor(
      "custom",
      getClass.getSimpleName,
      dimension,
      Int.MaxValue,
      supportsDimensions = false
    )

  /** 批量编码文本，输出数量必须与输入数量一致。 */
  def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]]

  /** 返回包含 usage/request ID 的详细结果。纯本地实现可只返回向量；真实 HTTP Adapter 应覆盖并报告 Provider usage。
    * @param texts
    *   输入顺序是输出位置契约的一部分，不能按并行完成顺序返回
    */
  def embedDetailed(texts: Chunk[String]): IO[RetrievalError, EmbeddingBatchResult] =
    embed(texts).map(EmbeddingBatchResult(_))

  /** 带可信租户、用途和幂等请求 ID 的生产调用入口。 原始 Provider 默认直通；`GovernedEmbeddingService` 覆盖它以实施缓存和原子配额。
    */
  def embedScoped(
      context: EmbeddingRequestContext,
      texts: Chunk[String]
  ): IO[RetrievalError, EmbeddingBatchResult] =
    val _ = context
    embedDetailed(texts)

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
    // 显式消费参数，表明默认实现有意忽略全文信号，而不是遗漏实现。
    val _ = queryText
    search(query, scope, limit)

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
    state.get.map { all =>
      val authorized = all.valuesIterator.filter { item =>
        item.chunk.tenantId == scope.tenantId && item.chunk.permissions.subsetOf(scope.permissions)
      }
      Chunk.fromIterable(
        authorized
          .map(item => RetrievalHit(item.chunk, cosine(query, item.embedding)))
          .toList
          .sortBy(hit => -hit.score)
          .take(limit.max(0))
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

  /** 计算余弦相似度；维度不一致或零向量返回零，避免 NaN。 */
  private def cosine(left: Embedding, right: Embedding): Double =
    val pairs = left.values.zip(right.values)
    val dot   = pairs.foldLeft(0.0)((sum, pair) => sum + pair._1.toDouble * pair._2.toDouble)
    val normL = math.sqrt(left.values.foldLeft(0.0)((sum, value) => sum + value.toDouble * value.toDouble))
    val normR = math.sqrt(right.values.foldLeft(0.0)((sum, value) => sum + value.toDouble * value.toDouble))
    if normL == 0.0 || normR == 0.0 then 0.0 else dot / (normL * normR)

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
  def retrieve(query: String, scope: RetrievalScope, limit: Int): IO[RetrievalError, RetrievalResult]

final class DefaultRetriever(
    embeddings: EmbeddingService,
    vectors: VectorStore,
    reranker: Reranker,
    expansion: RetrievalExpansionConfig = RetrievalExpansionConfig(),
    policies: RetrievalPolicySource = RetrievalPolicySource.default
) extends Retriever:
  /** 把单 query 编码后搜索并重排，最终引用保留 source 与 metadata。 */
  def retrieve(query: String, scope: RetrievalScope, limit: Int): IO[RetrievalError, RetrievalResult] =
    if limit <= 0 then ZIO.succeed(RetrievalResult(Chunk.empty, Chunk.empty))
    else if query.trim.isEmpty then ZIO.fail(AgentError.RetrievalFailed("Retrieval query 不能为空"))
    else
      // 单次检索内只读取一次工作点，避免同一次调用的重排开关和阈值来自不同版本的覆盖。
      val policy = policies.current()
      for
        requestId <- scope.requestId.fold(Random.nextUUID.map(_.toString))(ZIO.succeed(_))
        detailed  <- embeddings.embedScoped(
          EmbeddingRequestContext(scope.tenantId, EmbeddingPurpose.Query, requestId),
          Chunk(query)
        )
        queryEmbedding <- ZIO
          .fromOption(detailed.embeddings.headOption)
          .orElseFail(AgentError.RetrievalFailed("Embedding provider 返回空结果"))
        // 候选池放大三倍供 reranker 选择；Long 中间值防止外部错误 limit 造成 Int 溢出。
        candidateLimit = Math.min(limit.toLong * 3L, Int.MaxValue.toLong).toInt
        candidates <- vectors.searchHybrid(query, queryEmbedding, scope, candidateLimit)
        // 关闭重排时直接截断候选池。这里不能跳过后续校验：截断结果同样要满足数量、去重和权限契约，
        // 而 searchHybrid 来自存储 Adapter，与 reranker 一样位于信任边界之外。
        reranked <-
          if policy.rerankEnabled then reranker.rerank(query, candidates, limit)
          else ZIO.succeed(candidates.take(limit))
        // Reranker 可能是远端或业务自定义实现；即使它失陷，也不能注入候选集外或未授权文档。
        validated <- validateReranked(candidates, reranked, scope, limit)
        // 阈值只作用于 seed 命中。上下文扩展块按 expandedScoreFactor 主动降分，
        // 用同一个阈值筛掉它们会让"提高阈值"意外地同时关闭上下文扩展。
        hits = validated.filter(_.score >= policy.minimumScore)
        expanded <- vectors.expandContext(hits, scope, expansion)
        context  <- validateExpanded(hits, expanded, scope, expansion.maxAdditionalChunks)
        citations = context.zipWithIndex.map { case (hit, index) =>
          val origins = hit.chunk.lineage.fold(Chunk.empty[DocumentOrigin])(_.origins)
          Citation(
            s"cite-${index + 1}",
            hit.chunk.sourceUri,
            hit.chunk.text.take(500),
            hit.score,
            origins.map(_.pageNumber).distinct,
            origins
          )
        }
      yield RetrievalResult(context, citations)

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
  val layer: URLayer[EmbeddingService & VectorStore & Reranker, Retriever] =
    ZLayer.fromFunction((embeddings: EmbeddingService, vectors: VectorStore, reranker: Reranker) =>
      DefaultRetriever(embeddings, vectors, reranker)
    )

  /** 接入运行时覆盖的装配；宿主提供由 `RuntimeSettingsService` 支撑的解析器后，管理台调整 topK、 最低得分与重排开关即可在下一次检索生效。
    */
  val governedLayer: URLayer[EmbeddingService & VectorStore & Reranker & RetrievalPolicySource, Retriever] =
    ZLayer.fromFunction(
      (
          embeddings: EmbeddingService,
          vectors: VectorStore,
          reranker: Reranker,
          policies: RetrievalPolicySource
      ) => DefaultRetriever(embeddings, vectors, reranker, RetrievalExpansionConfig(), policies)
    )

/** 确定性测试 embedding，不应用于真实语义检索。 */
final class HashEmbedding(val dimension: Int = 64) extends EmbeddingService:
  /** 确定性哈希向量，仅供测试/示例，不代表语义 embedding 质量。 */
  def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
    ZIO.succeed(texts.map { text =>
      val values = Array.fill[Float](dimension)(0.0f)
      text.codePoints().toArray.zipWithIndex.foreach { case (point, index) =>
        val slot = Math.floorMod(point * 31 + index, dimension)
        values(slot) = values(slot) + 1.0f
      }
      Embedding(Chunk.fromArray(values))
    })
