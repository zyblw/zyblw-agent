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
  /** 单空间部署使用的具名空间。它是 API 层的显式默认值；数据库列没有默认值，每次写入都必须带空间。 */
  val Default: KnowledgeSpaceId = "default"

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

object IndexProfileIds:
  /** 构建规格的稳定 Profile 标识：可读前缀加完整规格摘要前缀；任何影响向量或切块的配置变化都得到不同 id。 */
  def fromSpec(spec: IndexBuildSpec): IndexProfileId =
    val raw       = s"${spec.embeddingProvider}-${spec.embeddingModel}-${spec.embeddingDimension}".toLowerCase
    val sanitized = raw.replaceAll("[^a-z0-9._-]+", "-").take(160)
    IndexProfileId(s"${if sanitized.nonEmpty then sanitized else "profile"}-${spec.sha256.take(16)}")

enum ProfileWritePlan:
  /** 空间尚无 active 的增量 bootstrap：首份已通过文档质量门禁的内容发布时 CAS。
    *
    * 这是无旧 corpus 可比较的业务启动语义，不等同于带离线评测的全量 Profile 发布。批量初始化若要求“完整 corpus 评测后一次可见”，必须先建立显式 building Profile，再调用
    * `activateProfile`，不能逐文档走该计划。
    */
  case CreateAndActivate(profileId: IndexProfileId)

  /** 与当前 active 规格相同：写入现有 Profile，不改指针。 */
  case UseActive(profileId: IndexProfileId)

  /** 规格不同：写入 building Profile，禁止文档级 CAS。 */
  case BuildParallel(profileId: IndexProfileId)

object ProfileWritePlan:
  /** @param active
    *   当前 active Profile 及其规格摘要
    */
  def decide(active: Option[(IndexProfileId, String)], incoming: IndexBuildSpec): ProfileWritePlan =
    active match
      case None => ProfileWritePlan.CreateAndActivate(IndexProfileIds.fromSpec(incoming))
      case Some((id, specSha)) if specSha == incoming.sha256 => ProfileWritePlan.UseActive(id)
      case Some(_) => ProfileWritePlan.BuildParallel(IndexProfileIds.fromSpec(incoming))

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
      lineage: DocumentLineage,
      buildSpecSha256: String,
      secret: String = configuredSecret
  ): String =
    require(secret.trim.nonEmpty, "ingestion HMAC secret 不能为空")
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    val fields = Chunk(
      "v2",
      spaceId.value,
      profileId.value,
      documentId,
      lineage.sourceId,
      lineage.sourceRevisionId,
      lineage.sourceSha256,
      lineage.parserId,
      lineage.artifactSha256,
      lineage.structureSha256,
      lineage.textSha256,
      buildSpecSha256
    )
    mac
      .doFinal(fields.map(value => s"${value.length}:$value").mkString("\n").getBytes(StandardCharsets.UTF_8))
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

/** 受信宿主评测过的精确文档清单。
  *
  * 切换只在该清单与目标 Profile 中每份可发布文档完全一致时才有效；Store 还会在持有知识空间锁时比较目标与当前 active 的逻辑语料 （文档 ID + 来源修订），因此不完整的并行重建无法生效。
  */
final case class ProfileDocument(
    documentId: String,
    version: Long,
    sourceRevisionId: String,
    chunkSetSha256: String,
    chunkCount: Int
) derives JsonCodec
object ProfileDocument:
  def fromManifest(manifest: KnowledgeIndexManifest): ProfileDocument =
    ProfileDocument(
      manifest.build.key.documentId,
      manifest.build.version,
      manifest.build.lineage.sourceRevisionId,
      manifest.chunkSetSha256.getOrElse(""),
      manifest.chunkCount
    )

final case class ProfilePublication(
    documents: Chunk[ProfileDocument],
    evaluationId: String,
    evaluatedCensusSha256: String,
    qualityPassed: Boolean
):
  /** @param actual
    *   目标 Profile 的全部 manifest；每份文档只看最新版本，已下线文档不计入
    */
  def validate(actual: Chunk[KnowledgeIndexManifest]): Either[RetrievalError, Unit] =
    val current = ProfilePublication.latestByDocument(actual)
    val valid   = documents.nonEmpty && documents.length <= 100000 &&
      documents.map(_.documentId).distinct.length == documents.length &&
      documents.forall(d =>
        d.version > 0 && d.chunkCount > 0 && d.sourceRevisionId.trim.nonEmpty &&
          d.chunkSetSha256.matches("[0-9a-f]{64}")
      ) &&
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

  /** 版本号、块摘要是物理构建事实；蓝绿切换的语料连续性由稳定的文档身份与来源修订确定。 */
  def logicalCorpus(documents: Chunk[ProfileDocument]): Set[ProfileCorpusEntry] =
    documents.map(document => ProfileCorpusEntry(document.documentId, document.sourceRevisionId)).toSet

  /** 每份文档在该 Profile 中的最新版本；最新版本已下线的文档视为不在语料中。
    *
    * 较早的 Failed/Building 尝试已被更新版本取代，不再阻塞发布。
    */
  def latestByDocument(manifests: Chunk[KnowledgeIndexManifest]): Chunk[KnowledgeIndexManifest] =
    Chunk
      .fromIterable(manifests.groupBy(_.build.key.documentId).values.map(_.maxBy(_.build.version)))
      .filterNot(_.status == KnowledgeIndexStatus.Retired)
      .sortBy(_.build.key.documentId)

  /** 由两侧 manifest 计算切换前的语料差异。 */
  def corpusDiff(
      activeManifests: Chunk[KnowledgeIndexManifest],
      targetManifests: Chunk[KnowledgeIndexManifest]
  ): ProfileCorpusDiff =
    def entries(manifests: Chunk[KnowledgeIndexManifest]) =
      manifests
        .filter(m => m.status == KnowledgeIndexStatus.Ready && m.active)
        .map(m => ProfileCorpusEntry(m.build.key.documentId, m.build.lineage.sourceRevisionId))
    val target = latestByDocument(targetManifests)
    ProfileCorpusDiff.between(
      entries(activeManifests),
      entries(target),
      target.filterNot(m => m.status == KnowledgeIndexStatus.Ready && m.active).map(_.build.key.documentId)
    )
