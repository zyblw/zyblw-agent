package com.zyblw.agent.rag

import com.zyblw.agent.admin.*
import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.test.*

/** 验证 RAG 管理面的授权视角、正文截断边界与异步摄入终态。
  *
  * 这些断言约束的是运维界面最容易出错的三件事：沙盒是否真的以被调查主体的权限执行、管理接口会不会变成全文导出 通道，以及后台摄入失败后管理台看到的是稳定分类还是 Provider 原文。全部使用内存 Store 与
  * `HashEmbedding`， 不需要 Docker、PostgreSQL 或真实 Provider。
  */
object KnowledgeAdminLiveSpec extends ZIOSpecDefault:

  private val tenant      = TenantId("tenant-a")
  private val permissions = Set("knowledge:read")

  /** 只做 UTF-8 解码的测试 Loader，使断言集中在管理面协议而不是某个解析器。 */
  private val textLoader: DocumentLoader = new DocumentLoader:
    val id                  = "test-text"
    val supportedMediaTypes = Set("text/plain")

    def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
      input.content.runCollect.map(bytes =>
        SourceDocument(input.id, String(bytes.toArray, StandardCharsets.UTF_8), input.sourceUri)
      )

  /** 绝不能进入失败码的解析器细节。 */
  private val LeakedDetail = "parser-stack-trace: token=sk-secret-value"

  /** 失败信息里带有 Provider 细节的 Loader。 */
  private val leakingLoader: DocumentLoader = new DocumentLoader:
    val id                  = "leaking-text"
    val supportedMediaTypes = Set("text/plain")

    def load(input: DocumentInput): IO[RetrievalError, SourceDocument] =
      val _ = input
      ZIO.fail(AgentError.RetrievalFailed(LeakedDetail))

  private val reranker: Reranker = new Reranker:
    def rerank(query: String, hits: Chunk[RetrievalHit], limit: Int): UIO[Chunk[RetrievalHit]] =
      val _ = query
      ZIO.succeed(hits.take(limit))

  final private case class Harness(
      admin: KnowledgeAdminService,
      indexer: KnowledgeIndexer,
      jobs: IngestionJobStore
  )

  /** 装配一套完整内存知识栈；后台摄入 Fiber 挂在测试自身的 Scope 上，测试结束即被回收。 */
  private def harness(
      loader: DocumentLoader = textLoader,
      chunkCharacters: Int = 4000
  ): ZIO[Scope, RetrievalError, Harness] =
    for
      store <- InMemoryKnowledgeIndexStore.make
      embeddings = HashEmbedding(16)
      indexer    = KnowledgeIndexer(SlidingWindowChunker(chunkCharacters, 0), embeddings, store)
      loaders <- DocumentLoaderRegistry.make(Chunk(loader))
      jobsEnv <- IngestionJobStore.inMemory.build
      jobs = jobsEnv.get[IngestionJobStore]
      admin <- KnowledgeAdminLive.make(
        directory = KnowledgeIndexDirectory.inMemory(store),
        store = store,
        embeddings = embeddings,
        vectors = store,
        reranker = reranker,
        ingestion = DocumentIngestionService(loaders, indexer),
        jobs = jobs
      )
    yield Harness(admin, indexer, jobs)

  /** 轮询到任务进入终态；后台 Fiber 与测试 Fiber 并发，不能假设提交后立刻完成。 */
  private def awaitTerminal(jobs: IngestionJobStore, jobId: String): IO[AgentError, IngestionJobView] =
    (ZIO.sleep(2.millis) *> jobs.get(jobId))
      .repeatUntil(_.exists(_.status.terminal))
      .map(_.get)
      .timeoutFail(AgentError.PersistenceFailure(s"摄入任务未到达终态: $jobId"))(30.seconds)

  private def submission(
      fileName: String,
      text: String,
      tenantId: String = tenant.value
  ): IngestionSubmission = IngestionSubmission(
    fileName = fileName,
    mediaType = "text/plain",
    tenantId = tenantId,
    permissions = permissions,
    content = Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8))
  )

  private def request(
      tenantId: String = tenant.value,
      granted: Set[String] = permissions,
      rerank: Boolean = true,
      expandContext: Boolean = true
  ): KnowledgeRetrievalRequest = KnowledgeRetrievalRequest(
    query = "正文",
    tenantId = tenantId,
    permissions = granted,
    limit = 5,
    rerank = rerank,
    expandContext = expandContext
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("KnowledgeAdminLive")(
    test("超长 chunk 正文按 code point 截断并标记 textTruncated") {
      // 第 2000 个 code point 是一个代理对；按 char 截断会把它切成半个字符，管理台只能渲染成替换字符。
      val text = "本" * 1999 + "🧪" + "本" * 500
      for
        harnessed <- harness()
        _         <- harnessed.indexer.index(
          SourceDocument("doc-long", text, "doc://long"),
          tenant,
          permissions,
          "ingestion-long"
        )
        _ <- harnessed.indexer.index(
          SourceDocument("doc-short", "短正文", "doc://short"),
          tenant,
          permissions,
          "ingestion-short"
        )
        result <- harnessed.admin.retrieve(request())
        long   <- ZIO
          .fromOption(result.hits.find(_.chunk.documentId == "doc-long"))
          .orElseFail(AgentError.RetrievalFailed("缺少长文档命中"))
        short <- ZIO
          .fromOption(result.hits.find(_.chunk.documentId == "doc-short"))
          .orElseFail(AgentError.RetrievalFailed("缺少短文档命中"))
      yield assertTrue(
        long.chunk.textTruncated,
        long.chunk.text.codePointCount(0, long.chunk.text.length) == KnowledgeAdminService.MaxChunkTextLength,
        long.chunk.text.endsWith("🧪"),
        !short.chunk.textTruncated,
        short.chunk.text == "短正文"
      )
    },
    test("检索沙盒使用请求指定的租户与权限，而不是任何环境默认值") {
      for
        harnessed <- harness()
        _         <- harnessed.indexer.index(
          SourceDocument("doc-1", "受限正文", "doc://1"),
          tenant,
          permissions,
          "ingestion-1"
        )
        granted     <- harnessed.admin.retrieve(request())
        missing     <- harnessed.admin.retrieve(request(granted = Set.empty))
        otherTenant <- harnessed.admin.retrieve(request(tenantId = "tenant-b"))
        blank       <- harnessed.admin.retrieve(request(tenantId = "   ")).exit
      yield assertTrue(
        granted.hits.map(_.chunk.tenantId).forall(_ == tenant.value),
        granted.hits.nonEmpty,
        missing.hits.isEmpty,
        otherTenant.hits.isEmpty,
        blank.isFailure
      )
    },
    test("沙盒如实上报重排与上下文扩展是否执行") {
      for
        harnessed <- harness()
        _         <- harnessed.indexer.index(
          SourceDocument("doc-1", "受限正文", "doc://1"),
          tenant,
          permissions,
          "ingestion-1"
        )
        both    <- harnessed.admin.retrieve(request())
        neither <- harnessed.admin.retrieve(request(rerank = false, expandContext = false))
      yield assertTrue(
        both.rerankApplied,
        both.contextExpanded,
        !neither.rerankApplied,
        !neither.contextExpanded,
        both.embeddingProvider == HashEmbedding(16).descriptor.provider,
        both.embeddingDimension == 16
      )
    },
    test("异步摄入立即返回 Queued，后台完成后任务与文档目录都反映终态") {
      for
        harnessed <- harness(chunkCharacters = 8)
        accepted  <- harnessed.admin.submitIngestion(submission("notes.txt", "第一段正文第二段正文"), "tenant-a/ops")
        finished  <- awaitTerminal(harnessed.jobs, accepted.jobId)
        stored    <- harnessed.admin.ingestionJob(accepted.jobId)
        listed    <- harnessed.admin.ingestionJobs(Some(tenant.value), 10)
        documents <- harnessed.admin.documents(Some(tenant.value), 50, None)
      yield assertTrue(
        accepted.status == IngestionJobStatus.Queued,
        accepted.progressPercent == 0,
        finished.status == IngestionJobStatus.Completed,
        finished.progressPercent == 100,
        finished.failureCode.isEmpty,
        finished.documentId.contains("notes.txt"),
        finished.indexVersion.contains(1L),
        finished.chunkCount.exists(_ > 0),
        stored.contains(finished),
        listed.map(_.jobId) == Chunk(accepted.jobId),
        documents.items.map(_.documentId) == Chunk("notes.txt"),
        documents.items.forall(_.active),
        documents.items.map(_.chunkCount) == Chunk(finished.chunkCount.getOrElse(-1))
      )
    },
    test("摄入失败只记录稳定失败码，不写入解析器原文") {
      for
        harnessed <- harness(loader = leakingLoader)
        accepted  <- harnessed.admin.submitIngestion(submission("broken.txt", "任意正文"), "tenant-a/ops")
        finished  <- awaitTerminal(harnessed.jobs, accepted.jobId)
      yield assertTrue(
        finished.status == IngestionJobStatus.Failed,
        finished.failureCode.contains(s"ingestion:${ErrorCategory.Validation.toString.toLowerCase}"),
        finished.failureCode.exists(code => !code.contains("token") && !code.contains("stack")),
        !finished.failureCode.exists(_.contains(LeakedDetail)),
        finished.chunkCount.isEmpty
      )
    },
    test("非法提交在登记任务之前 fail-closed") {
      for
        harnessed   <- harness()
        blankTenant <- harnessed.admin.submitIngestion(submission("a.txt", "正文", tenantId = " "), "ops").exit
        blankName   <- harnessed.admin.submitIngestion(submission("  ", "正文"), "ops").exit
        badMedia    <- harnessed.admin
          .submitIngestion(submission("a.txt", "正文").copy(mediaType = "TEXT PLAIN"), "ops")
          .exit
        emptyBody <- harnessed.admin.submitIngestion(submission("a.txt", ""), "ops").exit
        jobs      <- harnessed.admin.ingestionJobs(None, 10)
      yield assertTrue(
        blankTenant.isFailure,
        blankName.isFailure,
        badMedia.isFailure,
        emptyBody.isFailure,
        jobs.isEmpty
      )
    },
    test("退役沿用乐观 active 版本前置条件") {
      for
        harnessed <- harness()
        indexed   <- harnessed.indexer.index(
          SourceDocument("doc-1", "正文", "doc://1"),
          tenant,
          permissions,
          "ingestion-1"
        )
        stale   <- harnessed.admin.retire(tenant.value, "doc-1", indexed.manifest.build.version + 1L).exit
        retired <- harnessed.admin.retire(tenant.value, "doc-1", indexed.manifest.build.version).exit
        page    <- harnessed.admin.documents(Some(tenant.value), 50, None)
      yield assertTrue(
        stale.isFailure,
        retired.isSuccess,
        page.items.map(_.status) == Chunk(KnowledgeIndexStatus.Retired.toString),
        page.items.forall(!_.active)
      )
    },
    test("非法游标 fail-closed，而不是静默从第一页开始") {
      harness()
        .flatMap(_.admin.documents(None, 50, Some("not-a-cursor")).exit)
        .map(result => assertTrue(result.isFailure))
    }
  ) @@ TestAspect.withLiveClock
