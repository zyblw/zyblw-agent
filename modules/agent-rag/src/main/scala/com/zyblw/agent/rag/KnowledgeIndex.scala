package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import zio.*

/** 文档在一个租户内的稳定主键；不同租户可以复用相同业务 documentId。 */
final case class KnowledgeDocumentKey(tenantId: TenantId, documentId: String):
  require(documentId.trim.nonEmpty, "knowledge documentId 不能为空")

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
  *   租户与业务文档键
  * @param ingestionId
  *   调用方生成的稳定幂等键；重试必须复用，内容改变必须换新键
  * @param sourceUri
  *   可进入引用结果的来源地址
  * @param contentHash
  *   原始正文的 SHA-256，用于发现同一 ingestionId 被错误复用
  * @param permissions
  *   检索授权标签；来自认证业务层，绝不能来自模型输出
  * @param metadata
  *   不含密钥和原文的业务索引元数据
  * @param embedding
  *   固化本版本使用的 Provider、模型和维度
  * @param indexingStrategy
  *   切分、清洗和中文分词策略的稳定版本；算法变化必须更换该值
  * @param expectation
  *   对当前 active 版本的乐观条件
  */
final case class BeginKnowledgeIndex(
    key: KnowledgeDocumentKey,
    ingestionId: String,
    sourceUri: String,
    contentHash: String,
    permissions: Set[String],
    metadata: Map[String, String],
    embedding: EmbeddingProviderDescriptor,
    indexingStrategy: String,
    expectation: ActiveVersionExpectation = ActiveVersionExpectation.AnyVersion,
    knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId("default"),
    targetProfileId: Option[IndexProfileId] = None
):
  require(ingestionId.trim.nonEmpty, "ingestionId 不能为空")
  require(sourceUri.trim.nonEmpty, "sourceUri 不能为空")
  require(contentHash.matches("[0-9a-f]{64}"), "contentHash 必须是小写 SHA-256")
  require(indexingStrategy.trim.nonEmpty, "indexingStrategy 不能为空")

/** 已被存储层分配版本号的构建句柄。
  *
  * 该值可以安全写入工作队列或 checkpoint；恢复 worker 通过相同 ingestionId 再次 `begin` 会取得同一版本。
  */
final case class KnowledgeIndexBuild(
    key: KnowledgeDocumentKey,
    version: Long,
    ingestionId: String,
    contentHash: String,
    embedding: EmbeddingProviderDescriptor,
    indexingStrategy: String,
    knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId("default"),
    profileId: IndexProfileId = IndexProfileId("default"),
    casActiveProfile: Boolean = false
):
  require(version > 0L, "knowledge index version 必须为正数")

/** 一份可查询的索引 manifest，不承载正文和向量。 */
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
    checkpoint: IngestCheckpoint = IngestCheckpoint.Resolved
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

  /** 幂等写入一批暂存块；块必须属于 build 的租户、文档、版本和向量维度。 */
  def stage(build: KnowledgeIndexBuild, chunks: Chunk[IndexedChunk]): IO[RetrievalError, Unit]

  /** 校验暂存块总数并原子发布；重复激活同一 active Ready 版本应返回相同 manifest。 */
  def activate(
      build: KnowledgeIndexBuild,
      expectedChunkCount: Int
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
    * `excludeDocumentIds` 用于 legal-hold，这些文档不得进入 purge 候选。
    */
  def purgeInactive(
      updatedBefore: Instant,
      limit: Int,
      excludeDocumentIds: Set[String] = Set.empty
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

  /** Space 级 tombstone；回滚不得复活该文档修订。 */
  def withdraw(
      key: KnowledgeDocumentKey,
      documentRevisionId: String
  ): IO[RetrievalError, Unit] =
    val _ = documentRevisionId
    retire(key, expectedActiveVersion = 1L).unit

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

  /** 为一份文档建立并发布新索引版本。
    *
    * @param document
    *   原始文档；正文只用于切分和计算 hash，不写入 manifest
    * @param tenantId
    *   可信租户 ID
    * @param permissions
    *   允许检索该文档所需的权限标签
    * @param ingestionId
    *   业务重试必须复用的稳定幂等键
    * @param expectation
    *   对当前 active 版本的并发写前置条件
    * @return
    *   已发布 manifest 与 Embedding usage
    */
  def index(
      document: SourceDocument,
      tenantId: TenantId,
      permissions: Set[String],
      ingestionId: String,
      expectation: ActiveVersionExpectation = ActiveVersionExpectation.AnyVersion,
      knowledgeSpaceId: KnowledgeSpaceId = KnowledgeSpaceId("default"),
      targetProfileId: Option[IndexProfileId] = None
  ): IO[RetrievalError, KnowledgeIndexResult] =
    val key         = KnowledgeDocumentKey(tenantId, document.id)
    val contentHash = KnowledgeIndexer.sha256(document.text)
    val mediaType   =
      document.metadata.getOrElse("mediaType", document.metadata.getOrElse("contentType", "text/plain"))
    val artifact   = SourceArtifactRef(document.sourceUri, mediaType, contentHash)
    val derivedKey =
      if ingestionId.trim.nonEmpty then ingestionId
      else
        IngestionKeys.hmac(
          knowledgeSpaceId,
          targetProfileId.getOrElse(
            IndexProfileIds.fromEmbedding(embeddings.descriptor.denseDescriptor, resolvedIndexingStrategy)
          ),
          document.id,
          contentHash,
          contentHash,
          hmacSecret
        )
    val request = BeginKnowledgeIndex(
      key = key,
      ingestionId = derivedKey,
      sourceUri = document.sourceUri,
      contentHash = contentHash,
      permissions = permissions,
      metadata = document.metadata ++ Map(
        "source.uri"           -> artifact.uri,
        "source.contentSha256" -> artifact.contentSha256,
        "source.mediaType"     -> artifact.mediaType
      ),
      embedding = embeddings.descriptor.denseDescriptor,
      indexingStrategy = resolvedIndexingStrategy,
      expectation = expectation,
      knowledgeSpaceId = knowledgeSpaceId,
      targetProfileId = targetProfileId
    )
    for
      _        <- ZIO.fromEither(ChunkEmbeddingAlignment.require(chunker.strategyId, embeddings.capabilities))
      _        <- ApprovedSourceResolver.validate(document.sourceUri)
      build    <- store.begin(request)
      existing <- store.find(key, derivedKey)
      result   <- existing match
        // HTTP/worker 可能在发布成功后、确认命令前崩溃；幂等重试不能再次调用付费 Provider。
        case Some(manifest) if manifest.status == KnowledgeIndexStatus.Ready =>
          ZIO.succeed(KnowledgeIndexResult(manifest, None))
        case Some(manifest) if manifest.isQuarantined =>
          ZIO.fail(AgentError.RetrievalFailed("knowledge ingestion 已被隔离，不能激活"))
        case _ =>
          ingestPipeline(document, tenantId, permissions, contentHash, derivedKey, build).onExit {
            case Exit.Success(_)     => ZIO.unit
            case Exit.Failure(cause) =>
              val code = cause.failureOption match
                case Some(error) if error.message.contains("隔离") => KnowledgeIndexer.QuarantineFailureCode
                case Some(error)                                 => error.category.toString
                case None                                        => "InterruptedOrDefect"
              store.markFailed(build, code).ignore
          }
    yield result

  private def ingestPipeline(
      document: SourceDocument,
      tenantId: TenantId,
      permissions: Set[String],
      contentHash: String,
      derivedKey: String,
      build: KnowledgeIndexBuild
  ): IO[RetrievalError, KnowledgeIndexResult] =
    def checkpoint(value: IngestCheckpoint) = store.setCheckpoint(build, value)
    def quarantine(reason: String)          =
      checkpoint(IngestCheckpoint.Quarantined) *>
        ZIO.fail(AgentError.RetrievalFailed(s"knowledge ingestion 已被隔离: $reason"))
    for
      _ <- checkpoint(IngestCheckpoint.Resolved)
      _ <-
        if document.text.trim.isEmpty then quarantine("empty-text")
        else if !ExtractionQuality.assess(document.text).sufficient(qualityPolicy) then
          quarantine("extraction-quality")
        else ZIO.unit
      _          <- checkpoint(IngestCheckpoint.Extracted)
      _          <- checkpoint(IngestCheckpoint.Normalized)
      chunks     <- chunker.split(document, tenantId, permissions)
      _          <- checkpoint(IngestCheckpoint.Chunked)
      _          <- quarantine("empty-chunks").when(chunks.isEmpty)
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
          .withLexical(lexical.document(chunk.displayText))
          .copy(
            metadata = chunk.metadata ++ enrichment.metadata,
            catalogVersion = build.version,
            documentRevisionId = Some(contentHash),
            knowledgeSpaceId = Some(build.knowledgeSpaceId),
            profileId = Some(build.profileId)
          )
      }
      detailed <- embeddings.embed(
        EmbeddingRequest(
          lexicalized.map(_.denseText),
          EmbeddingInputRole.Document,
          EmbeddingOutputKind.Dense,
          context = EmbeddingRequestContext(
            tenantId,
            EmbeddingPurpose.Indexing,
            s"knowledge-index:${document.id}:$derivedKey",
            knowledgeSpaceId = Some(build.knowledgeSpaceId)
          )
        )
      )
      _ <- checkpoint(IngestCheckpoint.Embedded)
      _ <- ZIO
        .fail(
          AgentError.RetrievalFailed(
            s"Embedding 输出数量 ${detailed.denseEmbeddings.length} != chunk 数量 ${lexicalized.length}"
          )
        )
        .unless(detailed.denseEmbeddings.length == lexicalized.length)
      indexed = lexicalized.zip(detailed.denseEmbeddings).map { case (chunk, vector) =>
        IndexedChunk(chunk, vector)
      }
      batches = indexed.grouped(stageBatchSize).toList
      _     <- ZIO.foreachDiscard(batches)(batch => store.stage(build, batch))
      _     <- checkpoint(IngestCheckpoint.Staged)
      _     <- checkpoint(IngestCheckpoint.Validated)
      _     <- store.activate(build, indexed.length)
      _     <- checkpoint(IngestCheckpoint.Ready)
      ready <- store
        .find(build.key, derivedKey)
        .someOrFail(AgentError.RetrievalFailed("knowledge activate 后 manifest 丢失"))
    yield KnowledgeIndexResult(ready.copy(checkpoint = IngestCheckpoint.Ready), detailed.usage)

object KnowledgeIndexer:
  val QuarantineFailureCode: String = "ingestion.quarantine"

  val ForbiddenEnrichmentKeys: Set[String] =
    Set("tenantId", "tenant_id", "permissions", "sourceUri", "source_uri")

  /** 摄入默认质量门：拒绝空正文与无字母/表意文字的扫描垃圾，短测试文档仍可通过。 */
  val DefaultQualityPolicy: ExtractionQualityPolicy =
    ExtractionQualityPolicy(minScriptCodePoints = 1, minScriptDensity = 0.0)

  /** 摄入运行时写入的 checkpoint 不参与 ingestionId 不可变字段比较。 */
  def requestMetadata(metadata: Map[String, String]): Map[String, String] =
    metadata.filterNot((key, _) => key.startsWith("ingest."))

  /** 对 UTF-8 正文计算稳定小写 SHA-256；用于幂等冲突检测，不用作认证签名。 */
  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

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
