package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import zio.*
import zio.json.*

/** 评测样本来源；生产与事故样本必须在进入数据集前完成脱敏。 */
enum EvalDatasetSource derives JsonCodec:
  case Curated
  case Synthetic
  case ProductionRedacted
  case IncidentRedacted
  case HumanDisagreementRedacted
  case PublicOpenDataset

/** 外部公开数据集的可审计来源。revision 必须固定为 commit、tag 或不可变数据集版本，不能使用 `main`/`latest`。 */
final case class EvalDatasetUpstream(
    id: String,
    revision: String,
    license: String,
    url: String,
    sourceSha256: String,
    selectionProtocol: String
) derives JsonCodec

/** 数据集是否已经通过宿主团队的人工变更审查。 */
enum EvalDatasetReviewStatus derives JsonCodec:
  case Draft
  case Approved

/** Agent 评测数据集的低基数治理清单。
  *
  * 这里只记录稳定身份、来源类别、变更审查身份与内容摘要，不记录工单正文、人名、输入或答案。框架验证清单完整性，但不会把 `Approved` 当成权限授予；批准者身份和审查流程由宿主组织负责。
  */
final case class AgentEvalDatasetProvenance(
    schemaVersion: Int,
    datasetId: String,
    datasetVersion: String,
    sources: Set[EvalDatasetSource],
    changeId: String,
    ownerId: String,
    reviewStatus: EvalDatasetReviewStatus,
    /** v1 单审字段，只用于读取历史清单；v2 发布门禁要求 reviewerIds。 */
    reviewerId: Option[String],
    reviewedAt: Option[Instant],
    contentSha256: String,
    /** 至少两名独立审查者的低敏组织身份。框架只验证声明完整性，不替代宿主 RBAC/电子签名。 */
    reviewerIds: Set[String] = Set.empty,
    /** 存在人工分歧样本时的仲裁者；必须独立于 owner 与 reviewer。 */
    adjudicatorId: Option[String] = None,
    /** 公开数据集的固定版本、许可证和选择协议；不保存访问 token。 */
    upstreams: Chunk[EvalDatasetUpstream] = Chunk.empty
) derives JsonCodec

object AgentEvalDatasetProvenance:
  val CurrentSchemaVersion = 2

/** 带 provenance 和内容绑定的 Agent 评测数据集。
  *
  * 数据集本身可能包含敏感的已脱敏业务输入，只能作为受控、短保留期 artifact；长期趋势仍只保存 `EvalSuiteSnapshot`。
  */
final case class AgentEvalDataset(
    provenance: AgentEvalDatasetProvenance,
    cases: Chunk[AgentEvalCase]
) derives JsonCodec:
  /** 发布评测前 fail-closed 校验人工审查、身份、版本、唯一性和内容摘要。 */
  def validateForRelease: IO[AgentError.InvalidConfiguration, Unit] =
    ZIO.fromEither(AgentEvalDataset.validateForRelease(this))

object AgentEvalDataset:
  private val SafeId       = "[A-Za-z0-9._-]{1,160}".r
  private val Sha256       = "[0-9a-f]{64}".r
  private val MaximumCases = 100000

  /** 为一组用例生成确定性 SHA-256。集合字段排序，字符串使用 UTF-8 长度前缀，避免连接歧义。 */
  def contentSha256(cases: Chunk[AgentEvalCase]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    updateInt(digest, cases.length)
    cases.foreach { evalCase =>
      updateString(digest, evalCase.id)
      updateString(digest, evalCase.datasetVersion)
      updateString(digest, evalCase.input)
      updateStrings(digest, evalCase.expectedTools)
      updateStrings(digest, evalCase.forbiddenTools)
      updateStrings(digest, evalCase.expectedCitationIds)
      updateString(digest, evalCase.requireRecovery.toString)
      updateString(digest, evalCase.budget.maxLatencyMillis.toString)
      updateString(digest, evalCase.budget.maxTotalTokens.toString)
      updateString(digest, evalCase.budget.maxEstimatedCost.bigDecimal.stripTrailingZeros.toPlainString)
      updateStrings(digest, evalCase.expectedOutcomeLabels)
    }
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

  /** 纯验证实现，便于 CLI、Loader 和 Runner 复用完全相同的规则。 */
  def validateForRelease(dataset: AgentEvalDataset): Either[AgentError.InvalidConfiguration, Unit] =
    for
      _ <- validateIntegrity(dataset)
      provenance = dataset.provenance
      _ <- require(
        provenance.reviewStatus == EvalDatasetReviewStatus.Approved &&
          provenance.reviewerIds.size >= 2 &&
          provenance.reviewerIds.forall(safe) &&
          !provenance.reviewerIds.contains(provenance.ownerId) &&
          provenance.reviewedAt.nonEmpty,
        "review-not-approved"
      )
      _ <- require(
        !provenance.sources.contains(EvalDatasetSource.HumanDisagreementRedacted) ||
          provenance.adjudicatorId.exists(id =>
            safe(id) && id != provenance.ownerId && !provenance.reviewerIds.contains(id)
          ),
        "missing-independent-adjudicator"
      )
    yield ()

  /** 验证结构、固定来源和内容摘要，但不把 Draft 冒充为发布批准。 */
  def validateIntegrity(dataset: AgentEvalDataset): Either[AgentError.InvalidConfiguration, Unit] =
    val provenance = dataset.provenance
    val caseIds    = dataset.cases.map(_.id)
    for
      _ <- require(
        provenance.schemaVersion == AgentEvalDatasetProvenance.CurrentSchemaVersion,
        "unsupported-schema"
      )
      _ <- require(
        List(
          provenance.datasetId,
          provenance.datasetVersion,
          provenance.changeId,
          provenance.ownerId
        ).forall(safe),
        "invalid-provenance-identity"
      )
      _ <- require(provenance.sources.nonEmpty, "missing-source")
      _ <- require(Sha256.matches(provenance.contentSha256), "invalid-content-sha256")
      _ <- require(
        !provenance.sources.contains(EvalDatasetSource.PublicOpenDataset) ||
          (provenance.upstreams.nonEmpty &&
            provenance.upstreams.map(_.id).distinct.length == provenance.upstreams.length &&
            provenance.upstreams.forall(validUpstream)),
        "invalid-public-upstream"
      )
      _ <- require(dataset.cases.nonEmpty, "empty-dataset")
      _ <- require(dataset.cases.length <= MaximumCases, "dataset-too-large")
      _ <- require(caseIds.distinct.length == caseIds.length, "duplicate-case-id")
      _ <- require(dataset.cases.forall(evalCase => safe(evalCase.id)), "invalid-case-id")
      _ <- require(
        dataset.cases.forall(_.datasetVersion == provenance.datasetVersion),
        "dataset-version-mismatch"
      )
      _ <- require(
        MessageDigest.isEqual(
          provenance.contentSha256.getBytes(StandardCharsets.US_ASCII),
          contentSha256(dataset.cases).getBytes(StandardCharsets.US_ASCII)
        ),
        "content-digest-mismatch"
      )
    yield ()

  private def safe(value: String): Boolean = SafeId.matches(value)

  private def validUpstream(upstream: EvalDatasetUpstream): Boolean =
    val immutableRevision =
      safe(upstream.revision) && upstream.revision != "main" && upstream.revision != "master" &&
        upstream.revision != "latest"
    val validUrl = scala.util
      .Try(URI.create(upstream.url))
      .toOption
      .exists(uri =>
        uri.isAbsolute && uri.getScheme == "https" && uri.getHost != null && uri.getUserInfo == null &&
          uri.getRawQuery == null && uri.getRawFragment == null
      )
    safe(upstream.id) && immutableRevision && safe(upstream.license) && validUrl &&
    Sha256.matches(upstream.sourceSha256) &&
    upstream.selectionProtocol.matches("[A-Za-z0-9._-]{1,160}")

  private def require(condition: Boolean, code: String): Either[AgentError.InvalidConfiguration, Unit] =
    Either.cond(condition, (), AgentError.InvalidConfiguration(s"agent-eval-dataset:$code"))

  private def updateStrings(digest: MessageDigest, values: Set[String]): Unit =
    val ordered = values.toList.sorted
    updateInt(digest, ordered.length)
    ordered.foreach(updateString(digest, _))

  private def updateString(digest: MessageDigest, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    updateInt(digest, bytes.length)
    digest.update(bytes)

  private def updateInt(digest: MessageDigest, value: Int): Unit =
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array())
