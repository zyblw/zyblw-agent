package com.zyblw.agent.rag

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import zio.*

/** `KnowledgeAdminService` 的 RAG 实现。
  *
  * 管理面 SPI 定义在 `agent-core`，实现留在这里：只有 `agent-rag` 同时认识 `KnowledgeIndexStore`、检索链和
  * `DocumentIngestionService`。因此 `agent-core` 不会为了一个运维界面反向依赖向量检索，HTTP 层也不会因为挂载 管理路由而被迫引入 RAG 依赖。所有
  * `KnowledgeIndexManifest`、`RetrievalHit`、`ChunkLineage` 到 provider-neutral 视图的投影都收敛在本文件，避免同一份映射在
  * HTTP、PostgreSQL 和管理台之间出现三个逐渐漂移的副本。
  *
  * 检索沙盒不复用宿主装配的 `Retriever`，而是用宿主已装配的 Embedding、VectorStore 与 Reranker 现场组合一个
  * `DefaultRetriever`。原因是沙盒的价值在于逐次回答"关掉重排会怎样"，而 `Retriever` SPI 没有 per-call 开关； 如果用固定装配的实例执行，请求里的
  * `rerank`/`expandContext` 就只能变成两个骗人的展示字段。现场组合让 `rerankApplied`/`contextExpanded`
  * 与真实执行严格一致，同时仍然沿用框架的权限、数量与有限值校验。
  *
  * 摄入是异步的：HTTP 请求只登记任务并立刻返回，真实加载、切分、向量化在后台 Fiber 执行。后台 Fiber 挂在 Layer 构造时的 Scope 上而不是请求 Scope 上（请求会立即结束），并由
  * Semaphore 限制并发，避免一批上传同时打满 Embedding Provider 的额度与连接。
  */
final class KnowledgeAdminLive private (
    directory: KnowledgeIndexDirectory,
    store: KnowledgeIndexStore,
    embeddings: EmbeddingService,
    vectors: VectorStore,
    reranker: Reranker,
    ingestion: DocumentIngestionService,
    jobs: IngestionJobStore,
    policies: RetrievalPolicySource,
    expansion: RetrievalExpansionConfig,
    permits: Semaphore,
    backgroundScope: Scope
) extends KnowledgeAdminService:
  import KnowledgeAdminLive.*

  def documents(
      tenantId: Option[String],
      limit: Int,
      cursor: Option[String]
  ): IO[AgentError, KnowledgeDocumentPage] =
    for
      tenant  <- ZIO.foreach(tenantId.map(_.trim).filter(_.nonEmpty))(tenantOf)
      decoded <- ZIO.foreach(cursor.map(_.trim).filter(_.nonEmpty))(value =>
        ZIO.fromEither(KnowledgeIndexCursor.decode(value)).mapError(AgentError.InvalidConfiguration(_))
      )
      page <- directory.list(tenant, limit, decoded)
    yield KnowledgeDocumentPage(
      page.items.map(documentView),
      page.nextCursor.map(_.encoded),
      page.hasMore
    )

  /** 以请求指定的租户与权限视角执行一次真实检索。
    *
    * 沙盒作用域完全来自请求参数，绝不来自操作者身份：管理员通常持有超集权限，用他自己的视角查询会让 ACL 配置 错误永远无法复现。HTTP 层已经要求
    * `agent:admin:debug`，因此"以他人视角检索"这一能力本身是被显式授权的。
    */
  def retrieve(request: KnowledgeRetrievalRequest): IO[AgentError, KnowledgeRetrievalResult] =
    // 一次请求内只读取一次工作点，避免开关与阈值来自不同版本的运行时覆盖。
    val policy          = policies.current()
    val rerankApplied   = policy.rerankEnabled && request.rerank
    val expansionConfig = if request.expandContext then expansion else NoExpansion
    val contextExpanded = expansionConfig.maxAdditionalChunks > 0
    val sandbox         = DefaultRetriever(
      embeddings,
      vectors,
      reranker,
      expansionConfig,
      RetrievalPolicySource.static(policy.copy(rerankEnabled = rerankApplied))
    )
    val descriptor = embeddings.descriptor
    for
      tenant <- tenantOf(request.tenantId)
      limit = request.limit.max(1).min(KnowledgeAdminService.MaxRetrievalLimit)
      started  <- Clock.nanoTime
      result   <- sandbox.retrieve(request.query, RetrievalScope(tenant, request.permissions), limit)
      finished <- Clock.nanoTime
    yield KnowledgeRetrievalResult(
      elapsedMillis = (finished - started) / 1_000_000L,
      hits = result.hits.map(hitView),
      citations = result.citations.map(citationView),
      embeddingProvider = descriptor.provider,
      embeddingModel = descriptor.model,
      embeddingDimension = descriptor.dimension,
      rerankApplied = rerankApplied,
      contextExpanded = contextExpanded
    )

  def retire(tenantId: String, documentId: String, expectedActiveVersion: Long): IO[AgentError, Unit] =
    for
      tenant   <- tenantOf(tenantId)
      document <- documentIdOf(documentId)
      _        <- store.retire(KnowledgeDocumentKey(tenant, document), expectedActiveVersion)
    yield ()

  /** 登记摄入任务并把真实工作交给后台 Fiber。
    *
    * 文档身份取上传文件名：同名文件再次上传会成为同一份文档的新索引版本，而不是在知识库里堆出一份内容重复、 检索时互相竞争的孤立文档。幂等键使用本次任务 ID，因此重新上传总是建立新版本，不会撞上
    * `KnowledgeIndexer` 的"同 ingestionId 内容漂移"保护。
    */
  def submitIngestion(
      submission: IngestionSubmission,
      submittedBy: String
  ): IO[AgentError, IngestionJobView] =
    val fileName = submission.fileName.trim
    for
      tenant <- tenantOf(submission.tenantId)
      // 租户与文件名同时受限，派生的 sourceUri 才一定落在 `DocumentInput` 的身份边界内；否则越界会成为后台
      // Fiber 里的 defect，任务将永远停在 Queued。
      _ <- reject(s"tenantId 不能超过 $MaxTenantLength 个字符").when(tenant.value.length > MaxTenantLength)
      _ <- reject(s"摄入文件名长度必须位于 1..$MaxFileNameLength")
        .when(fileName.isEmpty || fileName.length > MaxFileNameLength || fileName.contains('\u0000'))
      mediaType <- mediaTypeOf(submission.mediaType)
      _         <- reject("摄入正文不能为空").when(submission.content.isEmpty)
      _         <- reject(s"摄入正文不能超过 ${KnowledgeAdminService.MaxUploadBytes} 字节")
        .when(submission.content.length > KnowledgeAdminService.MaxUploadBytes)
      jobId <- Random.nextUUID.map(_.toString)
      now   <- Clock.instant
      view = IngestionJobView(
        jobId = jobId,
        // 记录规范化后的租户，使任务列表的过滤值与真实索引所属租户一致。
        tenantId = tenant.value,
        sourceUri = s"$UploadUriScheme://${tenant.value}/$fileName",
        fileName = fileName,
        mediaType = mediaType,
        status = IngestionJobStatus.Queued,
        progressPercent = IngestionJobStatus.Queued.progressPercent,
        documentId = None,
        indexVersion = None,
        chunkCount = None,
        failureCode = None,
        submittedBy = submittedBy.take(MaxActorLength),
        createdAtEpochMilli = now.toEpochMilli,
        updatedAtEpochMilli = now.toEpochMilli
      )
      created <- jobs.create(view)
      _       <- ingest(created, tenant, submission).forkIn(backgroundScope)
    yield created

  def ingestionJob(jobId: String): IO[AgentError, Option[IngestionJobView]] = jobs.get(jobId)

  def ingestionJobs(tenantId: Option[String], limit: Int): IO[AgentError, Chunk[IngestionJobView]] =
    jobs.list(tenantId.map(_.trim).filter(_.nonEmpty), limit)

  /** 在有界并发下执行一次真实摄入并把结果写回任务存储。
    *
    * 状态只在能被观察到的边界推进：`Loading` 表示已经取得并发许可并开始加载，终态来自 `DocumentIngestionService`
    * 的实际结果。`Chunking`/`Embedding`/`Staging`/`Activating` 需要摄入服务暴露阶段事件才能如实上报，这里不会
    * 按时间猜一个进度条——一个匀速前进却与真实状态无关的百分比，会让运维在真正卡住时得出错误结论。
    */
  private def ingest(job: IngestionJobView, tenant: TenantId, submission: IngestionSubmission): UIO[Unit] =
    permits.withPermit {
      // 输入构造在效果内完成：`DocumentInput` 用 `require` 守卫身份边界，如果在 Fiber 外抛出，任务会永远停在
      // Queued 而没有任何终态可查。这里把它降级为一个稳定的校验失败。
      val request = ZIO
        .attempt(
          DocumentIngestionRequest(
            input = DocumentInput.fromBytes(
              id = job.fileName,
              sourceUri = job.sourceUri,
              fileName = job.fileName,
              declaredMediaType = job.mediaType,
              bytes = submission.content,
              metadata = submission.metadata
            ),
            tenantId = tenant,
            permissions = submission.permissions,
            ingestionId = job.jobId
          )
        )
        .mapError(_ => AgentError.RetrievalFailed("ingestion-input-rejected"))
      for
        _    <- advance(job.jobId, IngestionJobStatus.Loading)
        exit <- request.flatMap(ingestion.ingestOne).exit
        // Layer Scope 关闭会中断本 Fiber；终态写入设为不可中断，让优雅停机后的任务列表仍然有确定结论。
        _ <- complete(job.jobId, exit).uninterruptible
      yield ()
    }

  /** 推进非终态；写入失败只记录稳定日志，不改变摄入本身的成败。 */
  private def advance(jobId: String, status: IngestionJobStatus): UIO[Unit] =
    jobs
      .transition(jobId, status)
      .unit
      .catchAll(error =>
        ZIO.logWarning(s"摄入任务状态写入失败: job=$jobId, status=$status, category=${error.category}")
      )

  /** 写入终态。失败码只使用框架稳定分类，绝不写入 Provider 消息、解析器异常或文档正文。 */
  private def complete(jobId: String, exit: Exit[RetrievalError, DocumentIngestionOutcome]): UIO[Unit] =
    val transition = exit match
      case Exit.Success(DocumentIngestionOutcome.Indexed(documentId, result)) =>
        jobs.transition(
          jobId,
          IngestionJobStatus.Completed,
          documentId = Some(documentId),
          indexVersion = Some(result.manifest.build.version),
          chunkCount = Some(result.manifest.chunkCount)
        )
      case Exit.Success(DocumentIngestionOutcome.Failed(documentId, category, _)) =>
        jobs.transition(
          jobId,
          IngestionJobStatus.Failed,
          documentId = Some(documentId),
          failureCode = Some(failureCode(category))
        )
      case Exit.Failure(cause) =>
        jobs.transition(
          jobId,
          IngestionJobStatus.Failed,
          failureCode =
            Some(cause.failureOption.map(error => failureCode(error.category)).getOrElse(UnknownFailure))
        )
    transition.unit.catchAll(error => ZIO.logWarning(s"摄入任务终态写入失败: job=$jobId, category=${error.category}"))

object KnowledgeAdminLive:
  /** 默认后台摄入并发；与 `DocumentIngestionService` 的默认并发一致，避免管理上传成为额度放大器。 */
  val DefaultMaxConcurrentIngestions: Int = 2

  /** 管理台上传的稳定来源前缀；不含签名、令牌或宿主文件系统路径。 */
  val UploadUriScheme: String = "admin-upload"

  /** 上传文件名与派生文档 ID 的长度上限；同时满足 `DocumentInput` 的身份边界。 */
  private val MaxFileNameLength: Int = 400

  /** 租户标识长度上限；与管理面 HTTP 层的租户参数上限一致。 */
  private val MaxTenantLength: Int = 200

  /** 审计中操作者标签的长度上限。 */
  private val MaxActorLength: Int = 200

  /** 请求关闭上下文扩展时使用的显式零扩展策略。 */
  private val NoExpansion: RetrievalExpansionConfig =
    RetrievalExpansionConfig(neighborRadius = 0, maxAdditionalChunks = 0)

  /** 中断或 defect 导致无法归类时的稳定失败码。 */
  private val UnknownFailure: String = "ingestion:interrupted-or-defect"

  /** 创建服务；后台 Fiber 绑定到调用方 Scope，随应用而不是随 HTTP 请求存活。
    *
    * @param maxConcurrentIngestions
    *   同时执行的后台摄入数上限
    */
  def make(
      directory: KnowledgeIndexDirectory,
      store: KnowledgeIndexStore,
      embeddings: EmbeddingService,
      vectors: VectorStore,
      reranker: Reranker,
      ingestion: DocumentIngestionService,
      jobs: IngestionJobStore,
      policies: RetrievalPolicySource = RetrievalPolicySource.default,
      expansion: RetrievalExpansionConfig = RetrievalExpansionConfig(),
      maxConcurrentIngestions: Int = DefaultMaxConcurrentIngestions
  ): URIO[Scope, KnowledgeAdminService] =
    for
      _       <- ZIO.dieMessage("maxConcurrentIngestions 必须为正数").when(maxConcurrentIngestions <= 0)
      permits <- Semaphore.make(maxConcurrentIngestions.toLong)
      scope   <- ZIO.scope
    yield new KnowledgeAdminLive(
      directory,
      store,
      embeddings,
      vectors,
      reranker,
      ingestion,
      jobs,
      policies,
      expansion,
      permits,
      scope
    )

  /** 标准装配。
    *
    * 使用 `ZLayer.scoped` 是必需的而不是风格选择：后台摄入 Fiber 必须挂在应用级 Scope 上。挂在 HTTP 请求 Scope 上 会让任务在响应写出的同一刻被中断，管理台永远只能看到
    * `Queued`。
    */
  def layer(
      expansion: RetrievalExpansionConfig = RetrievalExpansionConfig(),
      maxConcurrentIngestions: Int = DefaultMaxConcurrentIngestions
  ): URLayer[
    KnowledgeIndexDirectory & KnowledgeIndexStore & EmbeddingService & VectorStore & Reranker &
      DocumentIngestionService & IngestionJobStore & RetrievalPolicySource,
    KnowledgeAdminService
  ] = ZLayer.scoped {
    for
      directory  <- ZIO.service[KnowledgeIndexDirectory]
      store      <- ZIO.service[KnowledgeIndexStore]
      embeddings <- ZIO.service[EmbeddingService]
      vectors    <- ZIO.service[VectorStore]
      reranker   <- ZIO.service[Reranker]
      ingestion  <- ZIO.service[DocumentIngestionService]
      jobs       <- ZIO.service[IngestionJobStore]
      policies   <- ZIO.service[RetrievalPolicySource]
      service    <- make(
        directory,
        store,
        embeddings,
        vectors,
        reranker,
        ingestion,
        jobs,
        policies,
        expansion,
        maxConcurrentIngestions
      )
    yield service
  }

  /** 投影一份索引清单为管理视图；权限标签排序输出，使前端 diff 稳定。 */
  def documentView(manifest: KnowledgeIndexManifest): KnowledgeDocumentView = KnowledgeDocumentView(
    tenantId = manifest.build.key.tenantId.value,
    documentId = manifest.build.key.documentId,
    indexVersion = manifest.build.version,
    ingestionId = manifest.build.ingestionId,
    sourceUri = manifest.sourceUri,
    contentHash = manifest.build.contentHash,
    status = manifest.status.toString,
    active = manifest.active,
    chunkCount = manifest.chunkCount,
    permissions = manifest.permissions.toList.sorted,
    embeddingProvider = manifest.build.embedding.provider,
    embeddingModel = manifest.build.embedding.model,
    embeddingDimension = manifest.build.embedding.dimension,
    indexingStrategy = manifest.build.indexingStrategy,
    failureCode = manifest.failureCode,
    createdAtEpochMilli = manifest.createdAt.toEpochMilli,
    updatedAtEpochMilli = manifest.updatedAt.toEpochMilli
  )

  /** 投影一条命中及其全部可解释信号。 */
  def hitView(hit: RetrievalHit): KnowledgeRetrievalHitView =
    KnowledgeRetrievalHitView(chunkView(hit.chunk), hit.score, hit.signals)

  /** 投影引用；excerpt 沿用检索链已经截断的长度。 */
  def citationView(citation: Citation): KnowledgeCitationView = KnowledgeCitationView(
    id = citation.id,
    sourceUri = citation.sourceUri,
    excerpt = truncate(citation.excerpt)._1,
    score = citation.score,
    pageNumbers = citation.pageNumbers.toList
  )

  /** 投影命中块的谱系与定位信息，并按管理面上限截断正文。 */
  def chunkView(chunk: DocumentChunk): KnowledgeChunkView =
    val (text, truncated) = truncate(chunk.text)
    KnowledgeChunkView(
      chunkId = chunk.id,
      documentId = chunk.documentId,
      sourceUri = chunk.sourceUri,
      tenantId = chunk.tenantId.value,
      permissions = chunk.permissions.toList.sorted,
      indexVersion = chunk.indexVersion,
      text = text,
      textTruncated = truncated,
      headingPath = chunk.lineage.fold(List.empty[String])(_.headingPath.toList),
      parentId = chunk.lineage.flatMap(_.parentId),
      previousChunkId = chunk.lineage.flatMap(_.previousChunkId),
      nextChunkId = chunk.lineage.flatMap(_.nextChunkId),
      ordinal = chunk.lineage.map(_.ordinal),
      origins = chunk.lineage.fold(List.empty[KnowledgeOriginView])(_.origins.map(originView).toList)
    )

  /** 投影页内定位；bbox 缺失的纯文本来源保留页码即可。 */
  private def originView(origin: DocumentOrigin): KnowledgeOriginView = KnowledgeOriginView(
    pageNumber = origin.pageNumber,
    blockId = origin.blockId,
    left = origin.boundingBox.map(_.left),
    top = origin.boundingBox.map(_.top),
    right = origin.boundingBox.map(_.right),
    bottom = origin.boundingBox.map(_.bottom),
    pageWidth = origin.boundingBox.flatMap(_.pageWidth),
    pageHeight = origin.boundingBox.flatMap(_.pageHeight)
  )

  /** 按 code point 截断到管理面上限，并报告是否发生截断。
    *
    * 使用 code point 而不是 `String.take`，避免把一个 emoji 或罕用汉字的代理对切成两半——管理台会把半个代理对 渲染成替换字符，让运维以为文档解析出了乱码。
    */
  private def truncate(text: String): (String, Boolean) =
    val limit = KnowledgeAdminService.MaxChunkTextLength
    if text.codePointCount(0, text.length) <= limit then text -> false
    else text.substring(0, text.offsetByCodePoints(0, limit)) -> true

  /** 把摄入失败映射为稳定、低基数的失败码。 */
  private def failureCode(category: ErrorCategory): String = s"ingestion:${category.toString.toLowerCase}"

  /** 解析租户；空白或非法值 fail-closed，不构造出一个越权的空租户。 */
  private def tenantOf(value: String): IO[AgentError, TenantId] =
    ZIO.fromEither(TenantId.fromString(value)).mapError(AgentError.InvalidConfiguration(_))

  /** 解析业务文档 ID。 */
  private def documentIdOf(value: String): IO[AgentError, String] =
    val trimmed = value.trim
    ZIO
      .fromOption(Option.when(trimmed.nonEmpty && trimmed.length <= MaxFileNameLength)(trimmed))
      .orElseFail(AgentError.InvalidConfiguration(s"documentId 长度必须位于 1..$MaxFileNameLength"))

  /** 规范化并校验 MIME type；`DocumentInput` 只接受小写、不带参数的形式。 */
  private def mediaTypeOf(value: String): IO[AgentError, String] =
    val normalized = value.takeWhile(_ != ';').trim.toLowerCase
    ZIO
      .fail(AgentError.InvalidConfiguration(s"非法 media type: $value"))
      .unless(normalized.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*"))
      .as(normalized)

  private def reject(message: String): IO[AgentError, Unit] =
    ZIO.fail(AgentError.InvalidConfiguration(message))
