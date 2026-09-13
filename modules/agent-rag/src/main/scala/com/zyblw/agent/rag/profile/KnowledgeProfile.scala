package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import zio.*
import zio.json.*

opaque type KnowledgeSpaceId = String
object KnowledgeSpaceId:
  def apply(value: String): KnowledgeSpaceId =
    require(value.trim.nonEmpty && value.length <= 200, "KnowledgeSpaceId 长度必须位于 1..200")
    value.trim
  extension (id: KnowledgeSpaceId) def value: String = id

opaque type IndexProfileId = String
object IndexProfileId:
  def apply(value: String): IndexProfileId =
    require(value.trim.nonEmpty && value.length <= 200, "IndexProfileId 长度必须位于 1..200")
    value.trim
  extension (id: IndexProfileId) def value: String = id

final case class KnowledgeSpace(
    id: KnowledgeSpaceId,
    tenantId: TenantId,
    activeProfileId: Option[IndexProfileId],
    revision: Long,
    createdAt: Instant,
    updatedAt: Instant
):
  require(revision >= 0L, "KnowledgeSpace.revision 不能为负")

enum IndexProfileStatus:
  case Building, Ready, Active, Superseded, Failed, Retired, Cancelled

final case class DenseIndexIdentity(
    provider: String,
    model: String,
    dimension: Int,
    distance: String = "cosine"
) derives JsonCodec:
  require(provider.trim.nonEmpty && model.trim.nonEmpty, "dense identity 不能为空")
  require(dimension > 0, "dense dimension 必须为正数")

final case class SparseIndexIdentity(provider: String, model: String, dimension: Int) derives JsonCodec:
  require(dimension > 0, "sparse dimension 必须为正数")

final case class LexicalIndexIdentity(analyzer: String, strategyId: String) derives JsonCodec
final case class ChunkingIdentity(strategyId: String) derives JsonCodec
final case class TransformationIdentity(id: String, version: String) derives JsonCodec
final case class MetadataSchemaIdentity(id: String, version: String) derives JsonCodec
final case class FusionIdentity(strategy: String, version: String) derives JsonCodec

final case class IndexProfile(
    id: IndexProfileId,
    knowledgeSpaceId: KnowledgeSpaceId,
    tenantId: TenantId,
    version: Long,
    status: IndexProfileStatus,
    dense: DenseIndexIdentity,
    sparse: Option[SparseIndexIdentity],
    lexical: LexicalIndexIdentity,
    chunking: ChunkingIdentity,
    normalization: TransformationIdentity,
    contextualization: Option[TransformationIdentity],
    metadataSchema: MetadataSchemaIdentity,
    fusion: FusionIdentity,
    createdAt: Instant,
    readyAt: Option[Instant] = None,
    activatedAt: Option[Instant] = None,
    failureCode: Option[String] = None
):
  require(version > 0L, "IndexProfile.version 必须为正数")

final case class WithdrawnDocument(
    tenantId: TenantId,
    knowledgeSpaceId: KnowledgeSpaceId,
    documentId: String,
    documentRevisionId: String,
    withdrawnAt: Instant
)

final case class ProfileActivationAudit(
    tenantId: TenantId,
    knowledgeSpaceId: KnowledgeSpaceId,
    oldProfileId: Option[IndexProfileId],
    newProfileId: IndexProfileId,
    expectedRevision: Long,
    reason: String,
    activatedAt: Instant
)

object IndexProfileIds:
  /** Dense identity 与完整索引策略的稳定 Profile 标识；换模、切分或文本派生变化都必须得到不同 id。 */
  def fromIdentity(identity: DenseIndexIdentity, indexingStrategy: String): IndexProfileId =
    val raw            = s"${identity.provider}-${identity.model}-${identity.dimension}".toLowerCase
    val sanitized      = raw.replaceAll("[^a-z0-9._-]+", "-").take(180)
    val strategyDigest = MessageDigest
      .getInstance("SHA-256")
      .digest(indexingStrategy.getBytes(StandardCharsets.UTF_8))
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
      .take(12)
    IndexProfileId(s"${if sanitized.nonEmpty then sanitized else "default"}-$strategyDigest")

  def fromEmbedding(descriptor: EmbeddingProviderDescriptor, indexingStrategy: String): IndexProfileId =
    fromIdentity(
      DenseIndexIdentity(descriptor.provider, descriptor.model, descriptor.dimension),
      indexingStrategy
    )

enum ProfileWritePlan:
  /** 空间尚无 active 的增量 bootstrap：首份已通过文档质量门禁的内容发布时 CAS。
    *
    * 这是无旧 corpus 可比较的业务启动语义，不等同于带离线评测的全量 Profile 发布。批量初始化若要求“完整 corpus 评测后一次可见”，必须先建立显式 building Profile，再调用
    * `activateProfile`，不能逐文档走该计划。
    */
  case CreateAndActivate(profileId: IndexProfileId)

  /** 与当前 active identity 相同：写入现有 Profile，不改指针。 */
  case UseActive(profileId: IndexProfileId)

  /** identity 不同：写入 building Profile，禁止文档级 CAS。 */
  case BuildParallel(profileId: IndexProfileId)

object ProfileWritePlan:
  def decide(
      active: Option[(IndexProfileId, DenseIndexIdentity, String)],
      incoming: DenseIndexIdentity,
      indexingStrategy: String
  ): ProfileWritePlan =
    active match
      case None =>
        ProfileWritePlan.CreateAndActivate(IndexProfileIds.fromIdentity(incoming, indexingStrategy))
      case Some((id, existing, activeStrategy))
          if existing.provider == incoming.provider &&
            existing.model == incoming.model &&
            existing.dimension == incoming.dimension &&
            activeStrategy == indexingStrategy =>
        ProfileWritePlan.UseActive(id)
      case Some(_) =>
        ProfileWritePlan.BuildParallel(IndexProfileIds.fromIdentity(incoming, indexingStrategy))

object IngestionKeys:
  /** 测试与未配置环境的固定密钥；生产必须设置 `ZYBLW_RAG_INGESTION_HMAC_SECRET`。 */
  val TestSecret: String = "zyblw-rag-ingestion-test-v1"

  def configuredSecret: String =
    sys.env.get("ZYBLW_RAG_INGESTION_HMAC_SECRET").map(_.trim).filter(_.nonEmpty).getOrElse(TestSecret)

  /** 可推导幂等键；同一输入与同一密钥重试必须得到同一键。 */
  def hmac(
      spaceId: KnowledgeSpaceId,
      profileId: IndexProfileId,
      documentId: String,
      documentRevisionId: String,
      sourceSha256: String,
      secret: String = configuredSecret
  ): String =
    require(secret.trim.nonEmpty, "ingestion HMAC secret 不能为空")
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac
      .doFinal(
        s"${spaceId.value}\n${profileId.value}\n$documentId\n$documentRevisionId\n$sourceSha256"
          .getBytes(StandardCharsets.UTF_8)
      )
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

/** Exact document census evaluated by the trusted host.
  *
  * A profile cutover is valid only when this census matches every publishable document in the target profile.
  * The store additionally compares its logical corpus with the currently active profile while holding the
  * knowledge-space lock, so a partial parallel rebuild cannot become active.
  */
final case class ProfileDocument(documentId: String, version: Long, contentHash: String, chunkCount: Int)
    derives JsonCodec
object ProfileDocument:
  def fromManifest(manifest: KnowledgeIndexManifest): ProfileDocument =
    ProfileDocument(
      manifest.build.key.documentId,
      manifest.build.version,
      manifest.build.contentHash,
      manifest.chunkCount
    )

final case class ProfilePublication(
    documents: Chunk[ProfileDocument],
    evaluationId: String,
    evaluatedCensusSha256: String,
    qualityPassed: Boolean
):
  def validate(actual: Chunk[KnowledgeIndexManifest]): Either[RetrievalError, Unit] =
    val current = actual.filter(m =>
      m.active || m.status == KnowledgeIndexStatus.Building || m.status == KnowledgeIndexStatus.Failed
    )
    val valid = documents.nonEmpty && documents.length <= 100000 &&
      documents.map(_.documentId).distinct.length == documents.length &&
      documents.forall(d => d.version > 0 && d.chunkCount > 0 && d.contentHash.matches("[0-9a-f]{64}")) &&
      evaluationId.matches("[A-Za-z0-9._:-]{1,200}") && qualityPassed &&
      evaluatedCensusSha256 == ProfilePublication.digest(documents) &&
      current.forall(m => m.status == KnowledgeIndexStatus.Ready && m.active) &&
      current.map(ProfileDocument.fromManifest).toSet == documents.toSet
    Either.cond(
      valid,
      (),
      AgentError.RetrievalFailed("Profile publication requires a complete, evaluated document census")
    )

object ProfilePublication:
  def digest(documents: Chunk[ProfileDocument]): String =
    IngestionKeys.sha256(documents.sortBy(_.documentId).toJson)

  /** Versions and chunk counts are physical-build facts. Corpus continuity across a blue/green cutover is
    * established by stable document identity and source content hash.
    */
  def logicalCorpus(documents: Chunk[ProfileDocument]): Set[(String, String)] =
    documents.map(document => document.documentId -> document.contentHash).toSet
