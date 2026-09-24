package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.time.Instant
import zio.*

/** 文档在一个租户、一个知识空间内的稳定主键。
  *
  * 版本号、active 指针、撤回和 retention 都按 `(tenant, space, document)` 计算；不同空间可以复用相同业务 documentId 而互不干扰。
  */
final case class KnowledgeDocumentKey(
    tenantId: TenantId,
    documentId: String,
    knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId.Default
):
  require(documentId.trim.nonEmpty && documentId.length <= 1000, "knowledge documentId 长度必须位于 1..1000")

/** 开始新索引版本时对当前 active 版本的乐观前置条件。
  *
  * `AnyVersion` 适合后台全量导入；用户编辑等 read-modify-write 流程应使用 `Exact`，避免旧页面覆盖新内容。
  */
enum ActiveVersionExpectation:
  /** 不检查当前版本。 */
  case AnyVersion

  /** 只有文档从未发布时才允许开始。 */
  case NoActiveVersion

  /** 当前 active 版本必须等于 version。 */
  case Exact(version: Long)

/** 索引构建生命周期；只有 `Ready` 且 active 的版本可以成为当前发布版本。 */
enum KnowledgeIndexStatus:
  case Building, Ready, Superseded, Failed, Retired

/** 新建索引版本的不可变请求。
  *
  * @param key
  *   租户、知识空间与业务文档键
  * @param ingestionId
  *   调用方生成的稳定幂等键；重试必须复用，输入改变必须换新键
  * @param sourceUri
  *   可进入引用结果的来源地址
  * @param lineage
  *   原件修订 → 解析产物 → 结构 → 正文的完整来源链；同一 ingestionId 下任何一层变化都必须失败
  * @param permissions
  *   检索授权标签，至少一个；来自认证业务层，绝不能来自模型输出
  * @param metadata
  *   不含密钥和原文的业务索引元数据
  * @param buildSpec
  *   固化本版本的完整构建规格；必须与目标 Profile 的规格一致
  * @param expectation
  *   对当前 active 版本的乐观条件
  * @param targetProfileId
  *   显式目标 Profile；用于并行重建或回滚前补齐 superseded Profile
  */
final case class BeginKnowledgeIndex(
    key: KnowledgeDocumentKey,
    ingestionId: String,
    sourceUri: String,
    lineage: DocumentLineage,
    permissions: Set[String],
    metadata: Map[String, String],
    buildSpec: IndexBuildSpec,
    expectation: ActiveVersionExpectation = ActiveVersionExpectation.AnyVersion,
    targetProfileId: Option[IndexProfileId] = None
):
  require(ingestionId.trim.nonEmpty && ingestionId.length <= 200, "ingestionId 长度必须位于 1..200")
  require(sourceUri.trim.nonEmpty, "sourceUri 不能为空")
  require(
    permissions.nonEmpty && permissions.size <= KnowledgeIndexer.MaxPermissions,
    s"索引权限标签数量必须位于 1..${KnowledgeIndexer.MaxPermissions}；租户内公开请使用显式标签"
  )
  require(permissions.forall(label => label.trim.nonEmpty && label.length <= 200), "权限标签长度必须位于 1..200")

/** 已被存储层分配版本号的构建句柄。
  *
  * 该值可以安全写入工作队列或 checkpoint；恢复 worker 通过相同 ingestionId 再次 `begin` 会取得同一版本。
  */
final case class KnowledgeIndexBuild(
    key: KnowledgeDocumentKey,
    version: Long,
    ingestionId: String,
    lineage: DocumentLineage,
    buildSpec: IndexBuildSpec,
    profileId: IndexProfileId,
    casActiveProfile: Boolean = false
):
  require(version > 0L, "knowledge index version 必须为正数")
  def knowledgeSpaceId: KnowledgeSpaceId = key.knowledgeSpaceId
  def contentHash: String                = lineage.textSha256
  def indexingStrategy: String           = buildSpec.indexingStrategy
  def embeddingDimension: Int            = buildSpec.embeddingDimension

/** 一份可查询的索引 manifest，不承载正文和向量。
  *
  * @param chunkSetSha256
  *   发布时由 Store 从暂存块计算的块集合摘要；Building/Failed 时为 None
  */
final case class KnowledgeIndexManifest(
    build: KnowledgeIndexBuild,
    sourceUri: String,
    permissions: Set[String],
    metadata: Map[String, String],
    status: KnowledgeIndexStatus,
    active: Boolean,
    chunkCount: Int,
    failureCode: Option[String],
    createdAt: Instant,
    updatedAt: Instant,
    checkpoint: IngestCheckpoint = IngestCheckpoint.Resolved,
    chunkSetSha256: Option[String] = None
):
  require(chunkCount >= 0, "chunkCount 不能为负数")
  require(!active || status == KnowledgeIndexStatus.Ready, "只有 Ready 索引可以 active")
  def isQuarantined: Boolean =
    checkpoint == IngestCheckpoint.Quarantined || failureCode.contains(KnowledgeIndexer.QuarantineFailureCode)

/** 知识索引的耐久发布协议。
  *
  * 向量生成在数据库事务之外完成；暂存批次可以幂等重放；`activate` 必须在一个短事务里校验块数、替换当前 文档快照并切换 manifest。这样 Provider 慢调用不会占用连接，崩溃也不会暴露半成品。
  */
trait KnowledgeIndexStore:
  /** 分配或幂等读取 Building 版本；同 ingestionId 的不可变字段不一致必须失败。 */
  def begin(request: BeginKnowledgeIndex): IO[RetrievalError, KnowledgeIndexBuild]

  /** 幂等写入一批暂存块；块必须属于 build 的租户、空间、文档、版本和向量维度。 */
  def stage(build: KnowledgeIndexBuild, chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit]

  /** 校验暂存块集合摘要并原子发布；重复激活同一 active Ready 版本应返回相同 manifest。
    *
    * @param expected
    *   索引器根据全部已嵌入块计算的块数与摘要；Store 必须从暂存表重新计算并逐字比较
    */
  def activate(
      build: KnowledgeIndexBuild,
      expected: ChunkSetDigest
  ): IO[RetrievalError, KnowledgeIndexManifest]

  /** 把仍处于 Building 的版本标记失败；failureCode 必须是稳定分类，不能放原文或 Provider 错误正文。 */
  def markFailed(build: KnowledgeIndexBuild, failureCode: String): IO[RetrievalError, Unit]

  /** 返回文档当前 active manifest；不存在时为 None。 */
  def active(key: KnowledgeDocumentKey): IO[RetrievalError, Option[KnowledgeIndexManifest]]

  /** 根据幂等键查找构建，供崩溃恢复的摄取 worker 继续暂存或发布。 */
  def find(key: KnowledgeDocumentKey, ingestionId: String): IO[RetrievalError, Option[KnowledgeIndexManifest]]

  /** 以 active version 乐观前置条件下线文档并删除正式检索块；相同版本重复调用必须幂等返回 Retired manifest。
    * @param key
    *   租户与文档身份
    * @param expectedActiveVersion
    *   调用方最后读取到的 active 版本，防止旧删除请求误删刚发布的新版本
    */
  def retire(
      key: KnowledgeDocumentKey,
      expectedActiveVersion: Long
  ): IO[RetrievalError, KnowledgeIndexManifest]

  /** 有界清理截止时间前的 Superseded/Failed/Retired manifest 和暂存块。 Ready/active 与 Building 绝不能被 retention 作业删除。
    * `legalHold` 中的文档不得进入 purge 候选。
    */
  def purgeInactive(
      updatedBefore: Instant,
      limit: Int,
      legalHold: Set[KnowledgeDocumentKey] = Set.empty
  ): IO[RetrievalError, Long]

  /** 写入当前摄入 checkpoint；不改变 Building/Ready 状态机。 */
  def setCheckpoint(build: KnowledgeIndexBuild, checkpoint: IngestCheckpoint): IO[RetrievalError, Unit] =
    val _ = (build, checkpoint)
    ZIO.unit

  /** 解析空间当前 active Profile；尚无指针时为 None。 */
  def resolveActiveProfile(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId
  ): IO[RetrievalError, Option[IndexProfileId]] =
    val _ = (tenantId, spaceId)
    ZIO.succeed(None)

  /** 空间级 CAS 切换 active Profile；`expectedRevision` 必须匹配当前 revision。 */
  def activateProfile(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId,
      expectedRevision: Long,
      reason: String,
      publication: Option[ProfilePublication] = None
  ): IO[RetrievalError, Long] =
    val _ = (tenantId, spaceId, profileId, expectedRevision, reason, publication)
    ZIO.fail(AgentError.RetrievalFailed("KnowledgeIndexStore 未实现 activateProfile"))

  /** 比较目标 Profile 与当前 active Profile 的逻辑语料，列出切换前需要补齐、更新或下线的文档。 */
  def profileCorpusDiff(
      tenantId: TenantId,
      spaceId: KnowledgeSpaceId,
      target: IndexProfileId
  ): IO[RetrievalError, ProfileCorpusDiff] =
    val _ = (tenantId, spaceId, target)
    ZIO.fail(AgentError.RetrievalFailed("KnowledgeIndexStore 未实现 profileCorpusDiff"))

  /** 撤回一个知识空间里一个文档的一个来源修订。
    *
    * 该键下从未出现过此修订时必须失败，防止调用方误传索引版本号等值而得到静默的空操作。 撤回写入墓碑并下线匹配的 active 版本；之后 `begin` 同一修订会失败。其他空间和其他修订保持可见。
    */
  def withdraw(key: KnowledgeDocumentKey, sourceRevisionId: String): IO[RetrievalError, Unit] =
    val _ = (key, sourceRevisionId)
    ZIO.fail(AgentError.RetrievalFailed("KnowledgeIndexStore 未实现 withdraw"))

/** 一次索引发布的结果。
  *
  * @param manifest
  *   已经 active 的正式 manifest
  * @param embeddingUsage
  *   Provider 返回的累计用量；兼容服务不返回时为 None
  */
final case class KnowledgeIndexResult(
    manifest: KnowledgeIndexManifest,
    embeddingUsage: Option[EmbeddingUsage],
    extraction: Option[ExtractionReport] = None,
    extractedMarkdown: Option[String] = None,
    extractedOutline: Chunk[ExtractedHeading] = Chunk.empty
)

/** 组合 Chunker、EmbeddingModel 与 KnowledgeIndexStore 的高层摄取服务。
  *
  * 业务项目只需提供原始文档、租户权限和幂等键，不需要自己拼接“切分—嵌入—暂存—发布”的事务时序。 Embedding 的 HTTP 并发由 Provider Adapter 限制，数据库暂存再按
  * `stageBatchSize` 分批，避免一个巨型 JDBC batch 长时间占用连接。
  *
  * @param chunker
  *   可替换的确定性切分器
  * @param embeddings
  *   固定模型/维度的 Embedding Provider
  * @param store
  *   支持版本暂存和原子发布的耐久 Store
  * @param stageBatchSize
  *   每次写入暂存表的最大块数
  */
final class KnowledgeIndexer(
    chunker: Chunker,
    embeddings: EmbeddingModel,
    store: KnowledgeIndexStore,
    stageBatchSize: Int = 200,
    indexingStrategy: String = "",
    lexical: LexicalProcessor = SimpleChineseLexicalProcessor,
    enricher: DocumentEnricher = DocumentEnricher.identity,
    qualityPolicy: ExtractionQualityPolicy = KnowledgeIndexer.DefaultQualityPolicy,
    hmacSecret: String = IngestionKeys.configuredSecret
):
  require(stageBatchSize > 0, "stageBatchSize 必须为正数")
  private val resolvedIndexingStrategy =
    Option(indexingStrategy.trim)
      .filter(_.nonEmpty)
      .getOrElse(s"${chunker.strategyId}:lexical=${lexical.strategyId}")
  require(resolvedIndexingStrategy.trim.nonEmpty, "Chunker strategyId 不能为空")

  /** 本索引器产出的完整构建规格；它决定 Profile 身份。 */
  def buildSpec: IndexBuildSpec =
    IndexBuildSpec.of(
      embeddings.descriptor.denseDescriptor,
      resolvedIndexingStrategy,
      tokenizerId = embeddings.capabilities.tokenizerId.getOrElse(IndexBuildSpec.UndeclaredTokenizer),
      enricher = IndexBuildSpec.enricherId(enricher.descriptor)
    )

  /** 为一份文档建立并发布新索引版本。
    *
    * @param document
    *   原始文档；正文只用于切分和计算摘要，不写入 manifest
    * @param tenantId
    *   可信租户 ID
    * @param permissions
    *   允许检索该文档所需的权限标签，至少一个
    * @param ingestionId
    *   业务重试必须复用的稳定幂等键；空字符串表示由谱系与构建规格推导
    * @param expectation
    *   对当前 active 版本的并发写前置条件
    * @param provenance
    *   可信控制面提供的原件修订与解析产物摘要
    * @return
    *   已发布 manifest 与 Embedding usage
    */
  def index(
      document: SourceDocument,
      tenantId: TenantId,
      permissions: Set[String],
      ingestionId: String,
      expectation: ActiveVersionExpectation = ActiveVersionExpectation.AnyVersion,
      knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId.Default,
      targetProfileId: Option[IndexProfileId] = None,
      provenance: IngestionProvenance = IngestionProvenance()
  ): IO[RetrievalError, KnowledgeIndexResult] =
    val key = KnowledgeDocumentKey(tenantId, document.id, knowledgeSpaceId)
    for
      _       <- ZIO.fromEither(ChunkEmbeddingAlignment.require(chunker.strategyId, embeddings.capabilities))
      _       <- ApprovedSourceResolver.validate(document.sourceUri)
      spec    <- ZIO.attempt(buildSpec).mapError(error => AgentError.RetrievalFailed(error.getMessage))
      lineage <- ZIO
        .attempt(DocumentLineage.derive(document, provenance))
        .mapError(error => AgentError.RetrievalFailed(s"文档谱系无效: ${error.getMessage}"))
      derivedKey = Option(ingestionId.trim).filter(_.nonEmpty).getOrElse {
        IngestionKeys.hmac(
          knowledgeSpaceId,
          targetProfileId.getOrElse(IndexProfileIds.fromSpec(spec)),
          document.id,
          lineage,
          spec.sha256,
          hmacSecret
        )
      }
      request <- ZIO
        .attempt(
          BeginKnowledgeIndex(
            key = key,
            ingestionId = derivedKey,
            sourceUri = document.sourceUri,
            lineage = lineage,
            permissions = permissions,
            metadata = document.metadata,
            buildSpec = spec,
            expectation = expectation,
            targetProfileId = targetProfileId
          )
        )
        .mapError(error => AgentError.RetrievalFailed(error.getMessage))
      build    <- store.begin(request)
      existing <- store.find(key, derivedKey)
      result   <- existing match
        // HTTP/worker 可能在发布成功后、确认命令前崩溃；幂等重试不能再次调用付费 Provider。
        case Some(manifest) if manifest.status == KnowledgeIndexStatus.Ready =>
          ZIO.succeed(KnowledgeIndexResult(manifest, None))
        case Some(manifest) if manifest.isQuarantined =>
          ZIO.fail(AgentError.RetrievalFailed("knowledge ingestion 已被隔离，不能激活"))
        case _ =>
          Ref.make(false).flatMap { quarantined =>
            ingestPipeline(document, tenantId, permissions, derivedKey, build, quarantined).onExit {
              case Exit.Success(_)     => ZIO.unit
              case Exit.Failure(cause) =>
                quarantined.get.flatMap { isQuarantine =>
                  val code = cause.failureOption match
                    case Some(_) if isQuarantine => KnowledgeIndexer.QuarantineFailureCode
                    case Some(error)             => error.category.toString
                    case None                    => "InterruptedOrDefect"
                  store.markFailed(build, code).ignore
                }
            }
          }
    yield result

  private def ingestPipeline(
      document: SourceDocument,
      tenantId: TenantId,
      permissions: Set[String],
      derivedKey: String,
      build: KnowledgeIndexBuild,
      quarantined: Ref[Boolean]
  ): IO[RetrievalError, KnowledgeIndexResult] =
    def checkpoint(value: IngestCheckpoint) = store.setCheckpoint(build, value)
    def quarantine(reason: String)          =
      quarantined.set(true) *> checkpoint(IngestCheckpoint.Quarantined) *>
        ZIO.fail(AgentError.RetrievalFailed(s"knowledge ingestion 已被隔离: $reason"))
    val embedBatchSize = math.min(stageBatchSize, embeddings.descriptor.denseDescriptor.maxBatchSize)
    def embedAndStage(batch: Chunk[DocumentChunk], ordinal: Int) =
      for
        detailed <- embeddings.embed(
          EmbeddingRequest(
            batch.map(_.denseText),
            EmbeddingInputRole.Document,
            EmbeddingOutputKind.Dense,
            context = EmbeddingRequestContext(
              tenantId,
              EmbeddingPurpose.Indexing,
              s"knowledge-index:${document.id}:$derivedKey:$ordinal",
              knowledgeSpaceId = Some(build.knowledgeSpaceId)
            )
          )
        )
        _ <- ZIO
          .fail(
            AgentError.RetrievalFailed(
              s"Embedding 输出数量 ${detailed.denseEmbeddings.length} != chunk 数量 ${batch.length}"
            )
          )
          .unless(detailed.denseEmbeddings.length == batch.length)
        _ <- store.stage(
          build,
          batch.zip(detailed.denseEmbeddings).map((chunk, vector) => IndexedChunk(chunk, vector))
        )
      yield detailed.usage
    for
      _ <- checkpoint(IngestCheckpoint.Resolved)
      _ <-
        if document.text.trim.isEmpty then quarantine("empty-text")
        else if !ExtractionQuality.assess(document.text).sufficient(qualityPolicy) then
          quarantine("extraction-quality")
        else ZIO.unit
      _      <- checkpoint(IngestCheckpoint.Extracted)
      _      <- checkpoint(IngestCheckpoint.Normalized)
      chunks <- chunker.split(document, tenantId, permissions)
      _      <- checkpoint(IngestCheckpoint.Chunked)
      _      <- quarantine("empty-chunks").when(chunks.isEmpty)
      _      <- ZIO
        .fail(AgentError.RetrievalFailed("chunk ID 不能为空或包含制表符/换行"))
        .unless(chunks.forall(chunk => ChunkSetDigest.validChunkId(chunk.id)))
      enrichment <- enricher.enrich(document, chunks)
      _          <- ZIO
        .fail(AgentError.RetrievalFailed("DocumentEnricher 不能改写权限或来源"))
        .when(
          enrichment.metadata.exists { case (k, _) =>
            KnowledgeIndexer.ForbiddenEnrichmentKeys.contains(k)
          }
        )
      _ <- checkpoint(IngestCheckpoint.Enriched)
      lexicalized = chunks.map { chunk =>
        chunk
          .withLexical(lexical.document(chunk.denseText))
          .copy(
            metadata = chunk.metadata ++ enrichment.metadata,
            catalogVersion = build.version,
            sourceRevisionId = Some(build.lineage.sourceRevisionId),
            knowledgeSpaceId = Some(build.knowledgeSpaceId),
            profileId = Some(build.profileId)
          )
      }
      digest <- ZIO
        .attempt(ChunkSetDigest.of(lexicalized))
        .mapError(error => AgentError.RetrievalFailed(s"chunk 集合无效: ${error.getMessage}"))
      usages <- ZIO.foreach(Chunk.fromIterable(lexicalized.grouped(embedBatchSize).toList).zipWithIndex) {
        case (batch, ordinal) => embedAndStage(batch, ordinal)
      }
      _     <- checkpoint(IngestCheckpoint.Embedded)
      _     <- checkpoint(IngestCheckpoint.Staged)
      _     <- checkpoint(IngestCheckpoint.Validated)
      _     <- store.activate(build, digest)
      _     <- checkpoint(IngestCheckpoint.Ready)
      ready <- store
        .find(build.key, derivedKey)
        .someOrFail(AgentError.RetrievalFailed("knowledge activate 后 manifest 丢失"))
    yield KnowledgeIndexResult(
      ready.copy(checkpoint = IngestCheckpoint.Ready),
      KnowledgeIndexer.sumUsage(usages)
    )

object KnowledgeIndexer:
  val QuarantineFailureCode: String = "ingestion.quarantine"

  /** 单个文档或块允许的权限标签上限；与数据库 CHECK 一致。 */
  val MaxPermissions: Int = 256

  /** 所有子批次都返回 usage 时才汇总；任一缺失则整体未知。 */
  def sumUsage(usages: Chunk[Option[EmbeddingUsage]]): Option[EmbeddingUsage] =
    if usages.isEmpty || usages.exists(_.isEmpty) then None
    else
      Some(
        usages.flatten.foldLeft(EmbeddingUsage(0L, 0L))((acc, next) =>
          EmbeddingUsage(acc.inputTokens + next.inputTokens, acc.totalTokens + next.totalTokens)
        )
      )

  val ForbiddenEnrichmentKeys: Set[String] =
    Set("tenantId", "tenant_id", "permissions", "sourceUri", "source_uri")

  /** 摄入默认质量门：拒绝空正文与无字母/表意文字的扫描垃圾，短测试文档仍可通过。 */
  val DefaultQualityPolicy: ExtractionQualityPolicy =
    ExtractionQualityPolicy(minScriptCodePoints = 1, minScriptDensity = 0.0)

  /** 摄入运行时写入的 checkpoint 不参与 ingestionId 不可变字段比较。 */
  def requestMetadata(metadata: Map[String, String]): Map[String, String] =
    metadata.filterNot((key, _) => key.startsWith("ingest."))

  /** 对 UTF-8 正文计算稳定小写 SHA-256；用于幂等冲突检测，不用作认证签名。 */
  def sha256(text: String): String = KnowledgeDigest.sha256(text)

  /** 从三个可替换服务和显式 batch 配置构造 Layer。 */
  def layer(
      stageBatchSize: Int = 200,
      indexingStrategy: String = ""
  ): URLayer[Chunker & EmbeddingModel & KnowledgeIndexStore, KnowledgeIndexer] =
    ZLayer.fromFunction((chunker: Chunker, embeddings: EmbeddingModel, store: KnowledgeIndexStore) =>
      KnowledgeIndexer(chunker, embeddings, store, stageBatchSize, indexingStrategy)
    )

  /** 显式装配 lexical 策略的生产入口；未提供时保留 simple Chinese 默认工作点。 */
  def withLexical(
      stageBatchSize: Int = 200,
      indexingStrategy: String = ""
  ): URLayer[Chunker & EmbeddingModel & KnowledgeIndexStore & LexicalProcessor, KnowledgeIndexer] =
    ZLayer.fromFunction(
      (
          chunker: Chunker,
          embeddings: EmbeddingModel,
          store: KnowledgeIndexStore,
          lexical: LexicalProcessor
      ) => KnowledgeIndexer(chunker, embeddings, store, stageBatchSize, indexingStrategy, lexical)
    )
