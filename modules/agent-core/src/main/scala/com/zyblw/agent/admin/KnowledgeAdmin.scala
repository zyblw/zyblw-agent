package com.zyblw.agent.admin

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 知识索引版本的管理视图。
  *
  * 字段与 `KnowledgeIndexManifest` 对应，但保持 provider-neutral：`agent-core` 不认识 `agent-rag` 的类型， 由 RAG 适配器负责投影。这样管理面
  * HTTP 层可以只依赖 core，而不会把向量检索依赖强加给所有 HTTP 用户。
  */
final case class KnowledgeDocumentView(
    tenantId: String,
    documentId: String,
    indexVersion: Long,
    ingestionId: String,
    sourceUri: String,
    contentHash: String,
    status: String,
    active: Boolean,
    chunkCount: Int,
    permissions: List[String],
    embeddingProvider: String,
    embeddingModel: String,
    embeddingDimension: Int,
    indexingStrategy: String,
    failureCode: Option[String],
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long
) derives JsonCodec

/** 一页知识索引清单。 */
final case class KnowledgeDocumentPage(
    items: Chunk[KnowledgeDocumentView],
    nextCursor: Option[String],
    hasMore: Boolean
) derives JsonCodec

/** 页面内几何位置，供管理台在 PDF 上绘制高亮框。 */
final case class KnowledgeOriginView(
    pageNumber: Int,
    blockId: Option[String],
    left: Option[Double],
    top: Option[Double],
    right: Option[Double],
    bottom: Option[Double],
    pageWidth: Option[Double],
    pageHeight: Option[Double]
) derives JsonCodec

/** 命中 chunk 的结构谱系与定位信息。 */
final case class KnowledgeChunkView(
    chunkId: String,
    documentId: String,
    sourceUri: String,
    tenantId: String,
    permissions: List[String],
    indexVersion: Long,
    text: String,
    textTruncated: Boolean,
    headingPath: List[String],
    parentId: Option[String],
    previousChunkId: Option[String],
    nextChunkId: Option[String],
    ordinal: Option[Int],
    origins: List[KnowledgeOriginView]
) derives JsonCodec

/** 单条检索命中及其可解释信号。
  *
  * `signals` 是检索链各阶段留下的原始分数（向量余弦、全文 rank、RRF 名次、重排分数等）。管理台据此判断 一次不理想的召回是向量不准、分词不对，还是重排把正确结果压了下去。
  */
final case class KnowledgeRetrievalHitView(
    chunk: KnowledgeChunkView,
    score: Double,
    signals: Map[String, Double]
) derives JsonCodec

/** 检索沙盒返回的引用。 */
final case class KnowledgeCitationView(
    id: String,
    sourceUri: String,
    excerpt: String,
    score: Double,
    pageNumbers: List[Int]
) derives JsonCodec

/** 检索调试请求。
  *
  * `tenantId` 与 `permissions` 必须由管理台显式给出而不是从操作者身份推导：沙盒的价值正是“以某个业务主体的 权限视角复现一次检索”，用管理员自己的权限去查会让 ACL 问题永远无法复现。
  *
  * @param query
  *   检索查询
  * @param tenantId
  *   要模拟的租户
  * @param permissions
  *   要模拟的授权集合
  * @param limit
  *   返回条数
  * @param rerank
  *   是否执行重排阶段
  * @param expandContext
  *   是否执行相邻/父级上下文扩展
  */
final case class KnowledgeRetrievalRequest(
    query: String,
    tenantId: String,
    permissions: Set[String] = Set.empty,
    limit: Int = 5,
    rerank: Boolean = true,
    expandContext: Boolean = true
)

/** 检索调试结果，包含足以复现和解释本次召回的全部低敏信息。 */
final case class KnowledgeRetrievalResult(
    elapsedMillis: Long,
    hits: Chunk[KnowledgeRetrievalHitView],
    citations: Chunk[KnowledgeCitationView],
    embeddingProvider: String,
    embeddingModel: String,
    embeddingDimension: Int,
    rerankApplied: Boolean,
    contextExpanded: Boolean
) derives JsonCodec

/** 异步摄入任务的生命周期。
  *
  * 阶段与 `KnowledgeIndexStore` 的 begin→stage→activate 协议对齐，让管理台的进度条对应真实的索引状态机， 而不是一个凭时间推进的假动画。
  */
enum IngestionJobStatus derives JsonCodec:
  case Queued, Loading, Chunking, Embedding, Staging, Activating, Completed, Failed

  /** 返回供管理台展示的粗粒度进度百分比。 */
  def progressPercent: Int = this match
    case Queued     => 0
    case Loading    => 15
    case Chunking   => 35
    case Embedding  => 60
    case Staging    => 80
    case Activating => 95
    case Completed  => 100
    case Failed     => 100

  /** 任务是否已经到达终态。 */
  def terminal: Boolean = this == Completed || this == Failed

/** 摄入任务的管理视图。 */
final case class IngestionJobView(
    jobId: String,
    tenantId: String,
    sourceUri: String,
    fileName: String,
    mediaType: String,
    status: IngestionJobStatus,
    progressPercent: Int,
    documentId: Option[String],
    indexVersion: Option[Long],
    chunkCount: Option[Int],
    failureCode: Option[String],
    submittedBy: String,
    createdAtEpochMilli: Long,
    updatedAtEpochMilli: Long
) derives JsonCodec

/** 提交一次摄入所需的输入。
  *
  * 正文以字节形式提交，框架不假设它来自本地文件系统：管理台上传的文件、对象存储拉取的对象和 CI 生成的文档 走同一条路径，避免出现一个只能读服务器本地磁盘的管理接口。
  */
final case class IngestionSubmission(
    fileName: String,
    mediaType: String,
    tenantId: String,
    permissions: Set[String],
    content: Chunk[Byte],
    metadata: Map[String, String] = Map.empty
)

/** 摄入任务的耐久存储 SPI。 */
trait IngestionJobStore:
  /** 登记一个新任务并返回初始视图。 */
  def create(job: IngestionJobView): IO[StoreError, IngestionJobView]

  /** 推进任务状态；实现必须同时更新 `progressPercent` 与 `updatedAt`。 */
  def transition(
      jobId: String,
      status: IngestionJobStatus,
      documentId: Option[String] = None,
      indexVersion: Option[Long] = None,
      chunkCount: Option[Int] = None,
      failureCode: Option[String] = None
  ): IO[StoreError, IngestionJobView]

  /** 按 ID 读取任务。 */
  def get(jobId: String): IO[StoreError, Option[IngestionJobView]]

  /** 按创建时间倒序列出任务。 */
  def list(tenantId: Option[String], limit: Int): IO[StoreError, Chunk[IngestionJobView]]

object IngestionJobStore:
  /** 单页任务条数上限。 */
  val MaxLimit: Int = 100

  /** 单进程实现。
    *
    * 进程重启后未完成的任务会丢失状态，因此多副本或需要审计摄入历史的部署必须使用 PostgreSQL Adapter。
    */
  val inMemory: ULayer[IngestionJobStore] = ZLayer.fromZIO {
    Ref.Synchronized.make(Map.empty[String, IngestionJobView]).map { state =>
      new IngestionJobStore:
        def create(job: IngestionJobView): IO[StoreError, IngestionJobView] =
          state.modify(current => job -> current.updated(job.jobId, job))

        def transition(
            jobId: String,
            status: IngestionJobStatus,
            documentId: Option[String],
            indexVersion: Option[Long],
            chunkCount: Option[Int],
            failureCode: Option[String]
        ): IO[StoreError, IngestionJobView] =
          Clock.instant.flatMap { now =>
            state.modifyZIO { current =>
              current.get(jobId) match
                case None      => ZIO.fail(AgentError.PersistenceFailure(s"摄入任务不存在: $jobId"))
                case Some(job) =>
                  val next = job.copy(
                    status = status,
                    progressPercent = status.progressPercent,
                    documentId = documentId.orElse(job.documentId),
                    indexVersion = indexVersion.orElse(job.indexVersion),
                    chunkCount = chunkCount.orElse(job.chunkCount),
                    failureCode = failureCode.orElse(job.failureCode),
                    updatedAtEpochMilli = now.toEpochMilli
                  )
                  ZIO.succeed(next -> current.updated(jobId, next))
            }
          }

        def get(jobId: String): IO[StoreError, Option[IngestionJobView]] = state.get.map(_.get(jobId))

        def list(tenantId: Option[String], limit: Int): IO[StoreError, Chunk[IngestionJobView]] =
          state.get.map(current =>
            Chunk.fromIterable(
              current.values.toVector
                .filter(job => tenantId.forall(_ == job.tenantId))
                .sortBy(job => (-job.createdAtEpochMilli, job.jobId))
                .take(limit.max(1).min(MaxLimit))
            )
          )
    }
  }

/** RAG 管理面 SPI。
  *
  * 实现位于 `agent-rag`，因为只有那里同时认识 `KnowledgeIndexStore`、`Retriever` 和 `DocumentIngestionService`。 HTTP 层通过这个
  * trait 使用它们，从而不需要依赖向量检索模块。
  */
trait KnowledgeAdminService:
  /** 分页列出知识索引版本清单。 */
  def documents(
      tenantId: Option[String],
      limit: Int,
      cursor: Option[String]
  ): IO[AgentError, KnowledgeDocumentPage]

  /** 以指定租户与权限视角执行一次真实检索。
    *
    * 该操作会调用 Embedding Provider 并产生真实费用，因此 HTTP 层必须要求 [[AdminAuthorization.DebugScope]]。
    */
  def retrieve(request: KnowledgeRetrievalRequest): IO[AgentError, KnowledgeRetrievalResult]

  /** 退役一个租户下某文档当前 Active 的索引版本。 */
  def retire(tenantId: String, documentId: String, expectedActiveVersion: Long): IO[AgentError, Unit]

  /** 提交异步摄入任务并立即返回任务视图；实际加载、切分、向量化在后台执行。 */
  def submitIngestion(submission: IngestionSubmission, submittedBy: String): IO[AgentError, IngestionJobView]

  /** 查询单个摄入任务。 */
  def ingestionJob(jobId: String): IO[AgentError, Option[IngestionJobView]]

  /** 列出摄入任务。 */
  def ingestionJobs(tenantId: Option[String], limit: Int): IO[AgentError, Chunk[IngestionJobView]]

object KnowledgeAdminService:
  /** 检索沙盒返回的 chunk 正文截断长度。
    *
    * 管理台需要看到足够判断相关性的正文，但检索沙盒不应变成绕过业务授权的全文导出接口。
    */
  val MaxChunkTextLength: Int = 2000

  /** 单次检索沙盒的返回条数上限。 */
  val MaxRetrievalLimit: Int = 50

  /** 单个上传文档的字节上限，防止管理接口成为内存放大器。 */
  val MaxUploadBytes: Int = 32 * 1024 * 1024
