package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import zio.*
import zio.json.*

/** 可进入长期趋势仓库的评测类型。
  *
  * 这里故意只保留框架当前具有确定性评分器的三类报告。未来增加安全红队、工作流或业务答案 Judge 时，应先为新类型建立 独立低敏投影，再扩展该枚举；不能把任意原始报告 JSON 直接塞入趋势文件。
  */
enum EvalSuiteKind derives JsonCodec:
  case Agent
  case Rag
  case ContextCompression

/** 一项已经脱敏的评分维度。
  *
  * @param name
  *   稳定、低基数的维度名，例如 `tool-selection` 或 `rag-ranking`
  * @param passed
  *   该维度的硬门禁是否通过
  * @param score
  *   0..1 的有限分数，只用于回归比较；发布仍必须同时检查 `passed`
  */
final case class EvalDimensionSnapshot(
    name: String,
    passed: Boolean,
    score: Double
) derives JsonCodec

/** 单个评测用例的低敏快照。
  *
  * `EvalGrade.details`、问题、文档正文、引用 excerpt、Provider 错误和 Context 摘要都不会进入该结构。CI artifact 即使被更广泛 的工程系统读取，也只能看到稳定
  * ID、版本、布尔门禁和数值。
  *
  * @param caseId
  *   数据集内稳定用例 ID；建议使用英文短横线形式
  * @param datasetVersion
  *   该用例所属的数据集版本
  * @param dimensions
  *   各个互不抵消的硬门禁维度
  */
final case class EvalCaseSnapshot(
    caseId: String,
    datasetVersion: String,
    dimensions: Chunk[EvalDimensionSnapshot]
) derives JsonCodec:
  /** 维度非空且全部通过时，用例才允许发布。 */
  def passed: Boolean = dimensions.nonEmpty && dimensions.forall(_.passed)

  /** 平均分只用于趋势，不会覆盖任何 `passed=false`。 */
  def averageScore: Double =
    if dimensions.isEmpty then 0.0 else dimensions.map(_.score).sum / dimensions.length.toDouble

/** 一次评测运行的版本身份。
  *
  * 这些字段回答“谁与谁可以比较”。不同 datasetVersion 的结果不能自动互为基线，否则标注变化可能被误判为模型退化； 不同 suiteId/datasetId
  * 也不能因为维度名称碰巧相同而混在一条趋势线上。
  *
  * @param evaluationId
  *   本次执行稳定唯一 ID；CI 重试同一次 artifact 时应复用
  * @param suiteId
  *   评测套件逻辑名称，例如 `tcm-learning-agent`
  * @param datasetId
  *   数据集逻辑名称，例如 `tcm-learning-golden`
  * @param datasetVersion
  *   数据集/标注版本
  * @param harnessVersion
  *   Agent Prompt、Tool bundle、Context 策略或 Runtime 的发布版本
  * @param provider
  *   可选 Provider 低基数标签
  * @param model
  *   可选模型 ID；不得包含控制字符
  * @param pricingVersion
  *   可选价格表版本
  * @param commitSha
  *   可选源码提交标识
  * @param startedAt
  *   本次评测开始时间
  * @param finishedAt
  *   本次评测完成时间
  */
final case class EvalSnapshotMetadata(
    evaluationId: String,
    suiteId: String,
    datasetId: String,
    datasetVersion: String,
    harnessVersion: String,
    provider: Option[String],
    model: Option[String],
    pricingVersion: Option[String],
    commitSha: Option[String],
    startedAt: Instant,
    finishedAt: Instant
) derives JsonCodec

/** 追加到趋势仓库的统一低敏快照。
  *
  * @param schemaVersion
  *   快照协议版本；当前固定为 1
  * @param kind
  *   原始评测报告类型
  * @param metadata
  *   可比较身份与构建版本
  * @param cases
  *   已脱敏的用例/维度数值
  */
final case class EvalSuiteSnapshot(
    schemaVersion: Int,
    kind: EvalSuiteKind,
    metadata: EvalSnapshotMetadata,
    cases: Chunk[EvalCaseSnapshot]
) derives JsonCodec:
  /** 空套件不能假绿。 */
  def passed: Boolean = cases.nonEmpty && cases.forall(_.passed)

  /** 用例硬门禁通过率。 */
  def passRate: Double =
    if cases.isEmpty then 0.0 else cases.count(_.passed).toDouble / cases.length.toDouble

object EvalSuiteSnapshot:
  val CurrentSchemaVersion = 1

  /** 对外公开统一快照校验入口。
    *
    * 文件、PostgreSQL、对象存储等 Adapter 都必须在写入前和反序列化后调用同一份语义校验，不能只相信 JSON decoder 或数据库 CHECK。公开这个方法能够避免各 Adapter
    * 复制一套逐渐漂移的校验规则。
    *
    * @param snapshot
    *   待验证的低敏快照
    * @return
    *   语义合法时完成；否则返回不含业务正文的稳定配置错误
    */
  def validate(snapshot: EvalSuiteSnapshot): IO[AgentError.InvalidConfiguration, Unit] =
    ZIO.fromEither(EvalSnapshotValidation.validate(snapshot))

  /** 从通用 Agent Eval 报告创建低敏快照。
    *
    * @param metadata
    *   本次运行的套件、数据集、Harness 与构建身份
    * @param report
    *   工具选择、引用、恢复和资源预算报告
    */
  def fromAgent(
      metadata: EvalSnapshotMetadata,
      report: AgentEvalSuiteReport
  ): IO[AgentError.InvalidConfiguration, EvalSuiteSnapshot] =
    build(
      EvalSuiteKind.Agent,
      metadata,
      report.reports.map(item => project(item.caseId, item.datasetVersion, item.grades))
    )

  /** 从 RAG Eval 报告创建低敏快照。
    *
    * query、命中正文和 citation excerpt 只存在于运行中的 Retriever 观测，不会进入此投影。
    */
  def fromRag(
      metadata: EvalSnapshotMetadata,
      report: RagEvalSuiteReport
  ): IO[AgentError.InvalidConfiguration, EvalSuiteSnapshot] =
    build(
      EvalSuiteKind.Rag,
      metadata,
      report.reports.map(item => project(item.caseId, item.datasetVersion, item.grades))
    )

  /** 从 Context 压缩 Eval 报告创建低敏快照。
    *
    * attempts 中的摘要哈希、Token、成本和匹配 ID 仍保留在单次评测 artifact；长期趋势只保存六个稳定 grade，进一步降低 多年保留数据的敏感度与基数。
    */
  def fromContextCompression(
      metadata: EvalSnapshotMetadata,
      report: ContextCompressionEvalSuiteReport
  ): IO[AgentError.InvalidConfiguration, EvalSuiteSnapshot] =
    build(
      EvalSuiteKind.ContextCompression,
      metadata,
      report.reports.map(item => project(item.caseId, item.datasetVersion, item.grades))
    )

  /** 删除 `EvalGrade.details`，只投影可长期保留的确定性数值。 */
  private def project(
      caseId: String,
      datasetVersion: String,
      grades: Chunk[EvalGrade]
  ): EvalCaseSnapshot =
    EvalCaseSnapshot(
      caseId,
      datasetVersion,
      grades.map(grade => EvalDimensionSnapshot(grade.dimension, grade.passed, grade.score))
    )

  /** 所有报告类型共用同一份严格快照校验。 */
  private def build(
      kind: EvalSuiteKind,
      metadata: EvalSnapshotMetadata,
      cases: Chunk[EvalCaseSnapshot]
  ): IO[AgentError.InvalidConfiguration, EvalSuiteSnapshot] =
    val snapshot = EvalSuiteSnapshot(CurrentSchemaVersion, kind, metadata, cases)
    validate(snapshot).as(snapshot)

/** 趋势查询的完整、类型化身份。
  *
  * `kind` 必须进入身份：即使两个团队误用了相同 suiteId/datasetId，Agent、RAG 与 Context Compression 也不能互相成为 发布基线。把四个字段封装为值对象还可以避免
  * Adapter 方法中多个 String 参数顺序写错。
  *
  * @param kind
  *   评测报告类型
  * @param suiteId
  *   套件逻辑名称
  * @param datasetId
  *   数据集逻辑名称
  * @param datasetVersion
  *   数据集/标注版本
  */
final case class EvalTrendIdentity(
    kind: EvalSuiteKind,
    suiteId: String,
    datasetId: String,
    datasetVersion: String
) derives JsonCodec

object EvalTrendIdentity:
  /** 从候选快照提取查询身份，保证发布门禁不会手工拼错参数。 */
  def from(snapshot: EvalSuiteSnapshot): EvalTrendIdentity =
    EvalTrendIdentity(
      snapshot.kind,
      snapshot.metadata.suiteId,
      snapshot.metadata.datasetId,
      snapshot.metadata.datasetVersion
    )

  /** 校验外部查询身份。
    *
    * Store 查询可能由 CLI、HTTP 或 CI 参数直接构造，不能依赖“之前一定创建过合法快照”这一隐含前提。
    */
  def validate(identity: EvalTrendIdentity): IO[AgentError.InvalidConfiguration, Unit] =
    val safe = List(identity.suiteId, identity.datasetId, identity.datasetVersion).forall(
      _.matches("[A-Za-z0-9._-]{1,160}")
    )
    ZIO
      .fail(AgentError.InvalidConfiguration("eval-trend:invalid-history-identity"))
      .when(!safe)
      .unit

/** 快照与趋势记录的确定性校验。
  *
  * 该校验同时用于“内存报告转快照”和“磁盘记录反序列化”，因此不能相信 case class 构造方或 JSON decoder 已经替我们 保证语义。错误只返回稳定 code，不包含文件路径、模型名或用户数据。
  */
private object EvalSnapshotValidation:
  private val safeId = "[A-Za-z0-9._-]{1,160}".r

  /** 校验一份完整快照，成功时不产生新值。 */
  def validate(snapshot: EvalSuiteSnapshot): Either[AgentError.InvalidConfiguration, Unit] =
    val metadata   = snapshot.metadata
    val caseIds    = snapshot.cases.map(_.caseId)
    val dimensions = snapshot.cases.flatMap(_.dimensions.map(_.name))
    for
      _ <- require(snapshot.schemaVersion == EvalSuiteSnapshot.CurrentSchemaVersion, "unsupported-schema")
      _ <- require(safe(metadata.evaluationId), "invalid-evaluation-id")
      _ <- require(safe(metadata.suiteId), "invalid-suite-id")
      _ <- require(safe(metadata.datasetId), "invalid-dataset-id")
      _ <- require(safe(metadata.datasetVersion), "invalid-dataset-version")
      _ <- require(safe(metadata.harnessVersion), "invalid-harness-version")
      _ <- require(metadata.finishedAt.compareTo(metadata.startedAt) >= 0, "invalid-time-range")
      _ <- require(metadata.provider.forall(lowSensitiveLabel(_, 120)), "invalid-provider")
      _ <- require(metadata.model.forall(lowSensitiveLabel(_, 240)), "invalid-model")
      _ <- require(metadata.pricingVersion.forall(safe), "invalid-pricing-version")
      _ <- require(metadata.commitSha.forall(safe), "invalid-commit-sha")
      _ <- require(snapshot.cases.nonEmpty, "empty-suite")
      _ <- require(caseIds.distinct.length == caseIds.length, "duplicate-case-id")
      _ <- require(snapshot.cases.forall(evalCase => safe(evalCase.caseId)), "invalid-case-id")
      _ <- require(
        snapshot.cases.forall(_.datasetVersion == metadata.datasetVersion),
        "dataset-version-mismatch"
      )
      _ <- require(snapshot.cases.forall(_.dimensions.nonEmpty), "empty-dimensions")
      _ <- require(
        snapshot.cases.forall(evalCase =>
          evalCase.dimensions.map(_.name).distinct.length == evalCase.dimensions.length
        ),
        "duplicate-dimension"
      )
      _ <- require(dimensions.forall(safe), "invalid-dimension")
      _ <- require(
        snapshot.cases.forall(
          _.dimensions.forall(dimension =>
            java.lang.Double.isFinite(dimension.score) && dimension.score >= 0.0 && dimension.score <= 1.0
          )
        ),
        "invalid-score"
      )
    yield ()

  private def safe(value: String): Boolean = safeId.matches(value)

  /** Provider/model 可包含 `/`、`:` 等合法厂商字符，但不能成为多行日志或携带无界正文。 */
  private def lowSensitiveLabel(value: String, maxCodePoints: Int): Boolean =
    value.nonEmpty &&
      value.codePointCount(0, value.length) <= maxCodePoints &&
      !value.exists(character => Character.isISOControl(character))

  private def require(condition: Boolean, code: String): Either[AgentError.InvalidConfiguration, Unit] =
    Either.cond(condition, (), invalid(code))

  private def invalid(code: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration(s"eval-trend:$code")

/** 回归比较策略。
  *
  * 默认策略 fail-closed：候选必须通过自己的全部硬门禁、不能删除基线用例/维度、不能降低通过率或任何维度分数，而且首次 建立基线必须由调用方显式开启。这样 CI
  * 配置错误不会悄悄把“没有历史”解释为“没有回归”。
  *
  * @param maxPassRateDrop
  *   允许的通过率最大下降，0 表示不可下降
  * @param maxDimensionScoreDrop
  *   单个维度允许的最大分数下降
  * @param requireCandidateHardGates
  *   候选自身是否必须全部通过
  * @param requireAllBaselineCases
  *   是否禁止删除基线用例
  * @param requireAllBaselineDimensions
  *   是否禁止删除基线维度
  * @param allowFirstPassingBaseline
  *   没有历史成功基线时，是否允许首个全通过候选建立基线
  */
final case class EvalRegressionPolicy(
    maxPassRateDrop: Double = 0.0,
    maxDimensionScoreDrop: Double = 0.0,
    requireCandidateHardGates: Boolean = true,
    requireAllBaselineCases: Boolean = true,
    requireAllBaselineDimensions: Boolean = true,
    allowFirstPassingBaseline: Boolean = false
):
  require(
    java.lang.Double.isFinite(maxPassRateDrop) && maxPassRateDrop >= 0.0 && maxPassRateDrop <= 1.0,
    "maxPassRateDrop 必须是 0..1 的有限数"
  )
  require(
    java.lang.Double.isFinite(maxDimensionScoreDrop) &&
      maxDimensionScoreDrop >= 0.0 &&
      maxDimensionScoreDrop <= 1.0,
    "maxDimensionScoreDrop 必须是 0..1 的有限数"
  )

/** 发布决策中使用的稳定、低基数问题码。 */
enum EvalRegressionIssueCode derives JsonCodec:
  case BaselineMissing
  case SnapshotIdentityMismatch
  case CandidateHardGateFailed
  case PassRateRegressed
  case BaselineCaseRemoved
  case CaseHardGateRegressed
  case BaselineDimensionRemoved
  case DimensionScoreRegressed

/** 一项低敏回归问题。
  *
  * @param code
  *   稳定问题类型
  * @param caseId
  *   可选用例 ID
  * @param dimension
  *   可选维度名
  * @param baselineValue
  *   可选基线数值
  * @param candidateValue
  *   可选候选数值
  */
final case class EvalRegressionIssue(
    code: EvalRegressionIssueCode,
    caseId: Option[String] = None,
    dimension: Option[String] = None,
    baselineValue: Option[Double] = None,
    candidateValue: Option[Double] = None
) derives JsonCodec

/** 一次发布门禁决策。
  *
  * `issues` 不包含 `EvalGrade.details` 或任何业务正文，适合直接保存为 CI artifact。决策通过不仅要求“比基线好”，还要求 候选自身满足当前硬门禁。
  */
final case class EvalReleaseDecision(
    candidateEvaluationId: String,
    baselineEvaluationId: Option[String],
    passed: Boolean,
    issues: Chunk[EvalRegressionIssue]
) derives JsonCodec

object EvalReleaseGate:
  /** 比较候选与可选成功基线。
    *
    * @param baseline
    *   同 suite/dataset/version 的最近成功快照；没有时按 bootstrap 策略处理
    * @param candidate
    *   本次候选快照
    * @param policy
    *   回归容忍度与结构完整性要求
    */
  def evaluate(
      baseline: Option[EvalSuiteSnapshot],
      candidate: EvalSuiteSnapshot,
      policy: EvalRegressionPolicy = EvalRegressionPolicy()
  ): EvalReleaseDecision =
    val candidateGate =
      Chunk.fromIterable(
        Option.when(policy.requireCandidateHardGates && !candidate.passed)(
          EvalRegressionIssue(EvalRegressionIssueCode.CandidateHardGateFailed)
        )
      )
    val issues = baseline match
      case None =>
        candidateGate ++ Chunk.fromIterable(
          Option.when(!policy.allowFirstPassingBaseline)(
            EvalRegressionIssue(EvalRegressionIssueCode.BaselineMissing)
          )
        )
      case Some(previous) =>
        if !sameIdentity(previous, candidate) then
          candidateGate :+ EvalRegressionIssue(EvalRegressionIssueCode.SnapshotIdentityMismatch)
        else candidateGate ++ compareCompatible(previous, candidate, policy)
    EvalReleaseDecision(
      candidate.metadata.evaluationId,
      baseline.map(_.metadata.evaluationId),
      issues.isEmpty,
      issues
    )

  /** 从趋势仓库选择最近一个成功基线、生成决策，并在安全语义允许时记录候选。
    *
    * 普通质量失败候选必须进入趋势，才能回答“某个版本连续失败了多少次”；但“候选自身通过、历史为空、调用方又没有显式 授权 bootstrap”是一个特殊的授权失败：此时不能把候选追加到只按
    * `snapshot.passed` 选择成功基线的 Store，否则下一次 CI 会把上一次被拒绝的候选当成合法基线，隐式绕过 `allowFirstPassingBaseline=false`。
    *
    * 因此本方法遵守以下规则：
    *
    *   - 有既有基线时，无论候选通过或回归都追加，保留完整质量趋势；
    *   - 显式允许 bootstrap 时，首个通过候选追加并成为后续基线；
    *   - 首个候选自身硬门禁失败时可以追加，因为 `latestPassing` 永远不会选择它；
    *   - 仅因 `BaselineMissing` 被拒绝、且候选自身通过时不追加，保证 bootstrap 是不可绕过的显式授权。
    *
    * 需要追加时，`append` 会在决策返回前完成并 `fsync` 或提交数据库事务，CI 不会先放行再丢失证据。
    *
    * @param store
    *   低敏趋势仓库
    * @param candidate
    *   已通过统一语义校验的候选快照；Store 写入时还会再次校验
    * @param policy
    *   回归容忍度与首次基线授权策略
    * @return
    *   低敏发布决策；`passed=false` 时调用方必须阻止发布
    */
  def evaluateAndAppend(
      store: EvalTrendStore,
      candidate: EvalSuiteSnapshot,
      policy: EvalRegressionPolicy = EvalRegressionPolicy()
  ): IO[AgentError, EvalReleaseDecision] =
    for
      baseline <- store.latestPassing(EvalTrendIdentity.from(candidate))
      decision = evaluate(baseline, candidate, policy)
      _ <- store
        .append(candidate)
        .unless(decision.issues.exists(_.code == EvalRegressionIssueCode.BaselineMissing))
    yield decision

  private def sameIdentity(left: EvalSuiteSnapshot, right: EvalSuiteSnapshot): Boolean =
    left.kind == right.kind &&
      left.metadata.suiteId == right.metadata.suiteId &&
      left.metadata.datasetId == right.metadata.datasetId &&
      left.metadata.datasetVersion == right.metadata.datasetVersion

  private def compareCompatible(
      baseline: EvalSuiteSnapshot,
      candidate: EvalSuiteSnapshot,
      policy: EvalRegressionPolicy
  ): Chunk[EvalRegressionIssue] =
    val candidateCases = candidate.cases.map(evalCase => evalCase.caseId -> evalCase).toMap
    val passRateIssue  =
      Option.when(candidate.passRate + policy.maxPassRateDrop < baseline.passRate)(
        EvalRegressionIssue(
          EvalRegressionIssueCode.PassRateRegressed,
          baselineValue = Some(baseline.passRate),
          candidateValue = Some(candidate.passRate)
        )
      )
    val caseIssues = baseline.cases.flatMap { previous =>
      candidateCases.get(previous.caseId) match
        case None =>
          Chunk.fromIterable(
            Option.when(policy.requireAllBaselineCases)(
              EvalRegressionIssue(
                EvalRegressionIssueCode.BaselineCaseRemoved,
                caseId = Some(previous.caseId)
              )
            )
          )
        case Some(current) =>
          val hardGateIssue =
            Option.when(previous.passed && !current.passed)(
              EvalRegressionIssue(
                EvalRegressionIssueCode.CaseHardGateRegressed,
                caseId = Some(previous.caseId),
                baselineValue = Some(1.0),
                candidateValue = Some(0.0)
              )
            )
          Chunk.fromIterable(hardGateIssue) ++ compareDimensions(previous, current, policy)
    }
    Chunk.fromIterable(passRateIssue) ++ caseIssues

  private def compareDimensions(
      baseline: EvalCaseSnapshot,
      candidate: EvalCaseSnapshot,
      policy: EvalRegressionPolicy
  ): Chunk[EvalRegressionIssue] =
    val candidateDimensions = candidate.dimensions.map(value => value.name -> value).toMap
    baseline.dimensions.flatMap { previous =>
      candidateDimensions.get(previous.name) match
        case None =>
          Chunk.fromIterable(
            Option.when(policy.requireAllBaselineDimensions)(
              EvalRegressionIssue(
                EvalRegressionIssueCode.BaselineDimensionRemoved,
                caseId = Some(baseline.caseId),
                dimension = Some(previous.name)
              )
            )
          )
        case Some(current) =>
          Chunk.fromIterable(
            Option.when(current.score + policy.maxDimensionScoreDrop < previous.score)(
              EvalRegressionIssue(
                EvalRegressionIssueCode.DimensionScoreRegressed,
                caseId = Some(baseline.caseId),
                dimension = Some(previous.name),
                baselineValue = Some(previous.score),
                candidateValue = Some(current.score)
              )
            )
          )
    }

/** 评测趋势持久化 SPI。
  *
  * PostgreSQL、对象存储或专用质量平台可实现同一接口。基础模块提供本地追加文件实现，适合 CI artifact、单节点预发布任务 和开发环境；生产多节点长期查询可在不改变发布门禁的前提下替换
  * Adapter。
  */
trait EvalTrendStore:
  /** 幂等追加一份快照。
    *
    * 相同 evaluationId 与相同内容重复写入应成功；相同 ID 绑定不同内容必须冲突。
    */
  def append(snapshot: EvalSuiteSnapshot): IO[AgentError, Unit]

  /** 返回相同完整身份下最近一个通过全部硬门禁的快照。
    *
    * 发布门禁只需要一个成功基线，因此 SPI 单独暴露该查询，避免 PostgreSQL Adapter 为选一行而读取成千上万条历史。
    *
    * @param identity
    *   包含 kind、suite、dataset 与 datasetVersion 的完整身份
    */
  def latestPassing(identity: EvalTrendIdentity): IO[AgentError, Option[EvalSuiteSnapshot]]

  /** 按比较身份读取时间升序历史。
    *
    * @param identity
    *   包含 kind、suite、dataset 与 datasetVersion 的完整身份
    * @param limit
    *   返回最近多少条；必须有界
    */
  def history(
      identity: EvalTrendIdentity,
      limit: Int
  ): IO[AgentError, Chunk[EvalSuiteSnapshot]]

/** 本地追加趋势文件配置。
  *
  * @param path
  *   记录文件；父目录必须由部署系统预先创建，且不能是符号链接
  * @param maxFileBytes
  *   单文件读取/追加硬上限；达到后应归档轮转
  * @param maxRecordBytes
  *   单次低敏快照硬上限
  */
final case class FileEvalTrendStoreConfig(
    path: Path,
    maxFileBytes: Long = 64L * 1024L * 1024L,
    maxRecordBytes: Int = 2 * 1024 * 1024
)

/** 带校验和与崩溃尾记录恢复的追加文件趋势仓库。
  *
  * 每条物理记录是单行 JSON envelope：
  *
  *   1. payload 使用 Base64 编码，避免 JSON 字符串中的换行破坏 framing；
  *   2. SHA-256 校验完整 payload，完整行被篡改时 fail-closed；
  *   3. 写入持有 ZIO Semaphore 与操作系统 FileLock，进程内 Fiber 和跨进程发布任务不会交错写；
  *   4. 每次追加调用 `FileChannel.force(true)`；
  *   5. 进程在最后一行写到一半时，下一次追加只截断“没有换行的尾部”，不会跳过中间损坏记录。
  *
  * 该实现没有把本地文件描述成数据库：它适合有界 CI/单节点趋势，达到 `maxFileBytes` 后必须归档或替换为外部 Store。
  */
final class FileEvalTrendStore private (
    config: FileEvalTrendStoreConfig,
    semaphore: Semaphore
) extends EvalTrendStore:
  import FileEvalTrendStore.*

  def append(snapshot: EvalSuiteSnapshot): IO[AgentError, Unit] =
    for
      _       <- EvalSuiteSnapshot.validate(snapshot)
      payload <- encodeSnapshot(snapshot, config.maxRecordBytes)
      _       <- semaphore.withPermit(appendLocked(snapshot, payload))
    yield ()

  def latestPassing(identity: EvalTrendIdentity): IO[AgentError, Option[EvalSuiteSnapshot]] =
    readMatching(identity).map(_.filter(_.passed).lastOption)

  def history(
      identity: EvalTrendIdentity,
      limit: Int
  ): IO[AgentError, Chunk[EvalSuiteSnapshot]] =
    if limit <= 0 || limit > 100000 then
      ZIO.fail(AgentError.InvalidConfiguration("eval-trend:invalid-history-limit"))
    else readMatching(identity).map(_.takeRight(limit))

  /** 读取并按完整身份过滤；文件实现需要扫描，而 PostgreSQL Adapter 会用复合索引直接定位。 */
  private def readMatching(identity: EvalTrendIdentity): IO[AgentError, Chunk[EvalSuiteSnapshot]] =
    EvalTrendIdentity.validate(identity) *>
      semaphore.withPermit {
        ZIO
          .attemptBlockingIO(Files.exists(config.path, LinkOption.NOFOLLOW_LINKS))
          .mapError(_ => persistence("exists-read-failed"))
          .flatMap {
            case false => ZIO.succeed(Chunk.empty)
            case true  =>
              withLockedChannel { channel =>
                readChannel(channel).flatMap(decodeRecords(_, config.maxRecordBytes)).map { decoded =>
                  val matching = decoded.snapshots.filter(snapshot =>
                    snapshot.kind == identity.kind &&
                      snapshot.metadata.suiteId == identity.suiteId &&
                      snapshot.metadata.datasetId == identity.datasetId &&
                      snapshot.metadata.datasetVersion == identity.datasetVersion
                  )
                  Chunk.fromIterable(
                    matching.sortBy(snapshot =>
                      (
                        snapshot.metadata.finishedAt.getEpochSecond,
                        snapshot.metadata.finishedAt.getNano,
                        snapshot.metadata.evaluationId
                      )
                    )
                  )
                }
              }
          }
      }

  /** 在文件锁内处理幂等、崩溃尾截断、容量检查、追加和 fsync。 */
  private def appendLocked(
      snapshot: EvalSuiteSnapshot,
      encoded: EncodedRecord
  ): IO[AgentError, Unit] =
    withLockedChannel { channel =>
      for
        bytes   <- readChannel(channel)
        decoded <- decodeRecords(bytes, config.maxRecordBytes)
        existing = decoded.snapshots.find(_.metadata.evaluationId == snapshot.metadata.evaluationId)
        _ <- existing match
          case Some(value) if value == snapshot => ZIO.unit
          case Some(_)                          =>
            ZIO.fail(AgentError.InvalidConfiguration("eval-trend:evaluation-id-conflict"))
          case None =>
            for
              _ <- truncateCrashTail(channel, decoded.completeBytes)
              nextSize = decoded.completeBytes.toLong + encoded.line.length.toLong
              _ <- ZIO
                .fail(AgentError.InvalidConfiguration("eval-trend:file-capacity-exceeded"))
                .when(nextSize > config.maxFileBytes)
              _ <- writeAndForce(channel, decoded.completeBytes, encoded.line)
            yield ()
      yield ()
    }

  /** 在 Scope 中打开 Channel 和独占 FileLock。
    *
    * `FileChannel.lock()` 属于可能阻塞的 JVM 操作，使用 `attemptBlockingInterrupt`；Fiber 取消时会中断等待锁的线程，已获得的 lock/channel
    * 则由 Scope finalizer 释放。
    */
  private def withLockedChannel[A](use: FileChannel => IO[AgentError, A]): IO[AgentError, A] =
    ZIO.scoped {
      for
        channel <- ZIO.acquireRelease(
          ZIO
            .attemptBlockingIO(
              FileChannel.open(
                config.path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
              )
            )
            .mapError(_ => persistence("open-failed"))
        )(value => ZIO.attemptBlocking(value.close()).ignore)
        _ <- ZIO.acquireRelease(
          ZIO
            .attemptBlockingInterrupt(channel.lock())
            .mapError(_ => persistence("lock-failed"))
        )(lock => ZIO.attemptBlocking(lock.release()).ignore)
        result <- use(channel)
      yield result
    }

  /** 在锁内读取完整文件；大小上限避免损坏文件造成无界内存分配。 */
  private def readChannel(channel: FileChannel): IO[AgentError, Array[Byte]] =
    ZIO
      .attemptBlockingIO {
        val size = channel.size()
        if size < 0L || size > config.maxFileBytes then throw FileTooLarge
        val buffer = ByteBuffer.allocate(size.toInt)
        channel.position(0L)
        while buffer.hasRemaining && channel.read(buffer) >= 0 do ()
        buffer.flip()
        val bytes = Array.ofDim[Byte](buffer.remaining())
        buffer.get(bytes)
        bytes
      }
      .mapError {
        case FileTooLarge => AgentError.InvalidConfiguration("eval-trend:file-too-large")
        case _            => persistence("read-failed")
      }

  /** 只截断未以换行结束的最后半条记录；完整行中的任何损坏已经由 decodeRecords 拒绝。 */
  private def truncateCrashTail(channel: FileChannel, completeBytes: Int): IO[AgentError, Unit] =
    ZIO
      .attemptBlockingIO {
        if channel.size() != completeBytes.toLong then
          channel.truncate(completeBytes.toLong)
          channel.force(true)
      }
      .mapError(_ => persistence("tail-truncate-failed"))

  /** 从精确尾部写入完整 framing 行并强制落盘。 */
  private def writeAndForce(
      channel: FileChannel,
      offset: Int,
      line: Array[Byte]
  ): IO[AgentError, Unit] =
    ZIO
      .attemptBlockingIO {
        channel.position(offset.toLong)
        val buffer = ByteBuffer.wrap(line)
        while buffer.hasRemaining do
          val _ = channel.write(buffer)
        channel.force(true)
      }
      .mapError(_ => persistence("append-failed"))

object FileEvalTrendStore:
  final private case class RecordEnvelope(
      schemaVersion: Int,
      payloadBase64: String,
      sha256: String
  ) derives JsonCodec

  final private case class EncodedRecord(line: Array[Byte])
  final private case class DecodedRecords(
      snapshots: Chunk[EvalSuiteSnapshot],
      completeBytes: Int
  )

  private case object FileTooLarge extends java.io.IOException("eval-trend-file-too-large")

  /** 创建共享 Store。
    *
    * 同一个文件在单个进程内应通过该值共享，而不是为每次请求重新创建实例；内部 Semaphore 提供 Fiber 级公平等待， FileLock 再处理独立 CI 进程之间的互斥。
    */
  def make(
      config: FileEvalTrendStoreConfig
  ): IO[AgentError.InvalidConfiguration, FileEvalTrendStore] =
    for
      _         <- validateConfig(config)
      semaphore <- Semaphore.make(1)
    yield new FileEvalTrendStore(config, semaphore)

  /** 供业务 ZLayer 图直接装配的构造器。 */
  def layer(
      config: FileEvalTrendStoreConfig
  ): ZLayer[Any, AgentError.InvalidConfiguration, EvalTrendStore] =
    ZLayer.fromZIO(make(config))

  /** 配置阶段拒绝符号链接、缺失父目录和不合理容量。 */
  private def validateConfig(
      config: FileEvalTrendStoreConfig
  ): IO[AgentError.InvalidConfiguration, Unit] =
    val parent = Option(config.path.toAbsolutePath.normalize.getParent)
    for
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("eval-trend:invalid-capacity"))
        .when(
          config.maxFileBytes <= 0L ||
            config.maxFileBytes > 1024L * 1024L * 1024L ||
            config.maxRecordBytes <= 0 ||
            config.maxRecordBytes.toLong > config.maxFileBytes
        )
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("eval-trend:missing-parent"))
        .when(parent.isEmpty)
      validParent <- ZIO
        .attemptBlocking(
          parent
            .exists(path => Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        )
        .orElseSucceed(false)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("eval-trend:invalid-parent"))
        .unless(validParent)
      safeTarget <- ZIO
        .attemptBlocking {
          !Files.exists(config.path, LinkOption.NOFOLLOW_LINKS) ||
          (Files.isRegularFile(config.path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(config.path))
        }
        .orElseSucceed(false)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("eval-trend:invalid-target"))
        .unless(safeTarget)
    yield ()

  /** 将快照编码为带 SHA-256 的单行 envelope。 */
  private def encodeSnapshot(
      snapshot: EvalSuiteSnapshot,
      maxRecordBytes: Int
  ): IO[AgentError, EncodedRecord] =
    ZIO
      .attempt {
        val payload  = snapshot.toJson.getBytes(StandardCharsets.UTF_8)
        val envelope = RecordEnvelope(
          schemaVersion = 1,
          payloadBase64 = Base64.getEncoder.encodeToString(payload),
          sha256 = sha256(payload)
        )
        val line = (envelope.toJson + "\n").getBytes(StandardCharsets.UTF_8)
        (payload.length, line)
      }
      .mapError(_ => AgentError.InvalidConfiguration("eval-trend:encode-failed"))
      .flatMap { case (payloadBytes, line) =>
        ZIO
          .fail(AgentError.InvalidConfiguration("eval-trend:record-too-large"))
          .when(payloadBytes > maxRecordBytes || line.length > maxRecordBytes)
          .as(EncodedRecord(line))
      }

  /** 解码所有完整记录。
    *
    * 文件最后没有换行的字节被视为崩溃半写尾部；除此之外，任意 JSON、Base64、checksum、快照语义或重复 ID 错误都
    * fail-closed。不能为了“尽量读出历史”跳过中间坏记录，否则基线可能被静默替换。
    */
  private def decodeRecords(
      bytes: Array[Byte],
      maxRecordBytes: Int
  ): IO[AgentError, DecodedRecords] =
    if bytes.isEmpty then ZIO.succeed(DecodedRecords(Chunk.empty, 0))
    else
      val lastNewline = bytes.lastIndexOf('\n'.toByte)
      if lastNewline < 0 then ZIO.succeed(DecodedRecords(Chunk.empty, 0))
      else
        val completeBytes = lastNewline + 1
        val complete      = java.util.Arrays.copyOfRange(bytes, 0, completeBytes)
        for
          text <- strictUtf8(complete)
          lines = text.split('\n').toList.filter(_.nonEmpty)
          _ <- ZIO
            .fail(AgentError.InvalidConfiguration("eval-trend:record-too-large"))
            .when(lines.exists(line => line.length + 1 > maxRecordBytes))
          snapshots <- ZIO.foreach(lines)(decodeLine)
          ids = snapshots.map(_.metadata.evaluationId)
          _ <- ZIO
            .fail(AgentError.InvalidConfiguration("eval-trend:duplicate-evaluation-id"))
            .when(ids.distinct.length != ids.length)
        yield DecodedRecords(Chunk.fromIterable(snapshots), completeBytes)

  /** 解码并校验一条完整物理记录。 */
  private def decodeLine(line: String): IO[AgentError, EvalSuiteSnapshot] =
    for
      envelopeEither <- ZIO
        .attempt(line.fromJson[RecordEnvelope])
        .mapError(_ => AgentError.InvalidConfiguration("eval-trend:invalid-envelope"))
      envelope <- ZIO
        .fromEither(envelopeEither)
        .mapError(_ => AgentError.InvalidConfiguration("eval-trend:invalid-envelope"))
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("eval-trend:unsupported-record-schema"))
        .unless(envelope.schemaVersion == 1)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("eval-trend:invalid-checksum"))
        .unless(envelope.sha256.matches("[a-f0-9]{64}"))
      payload <- ZIO
        .attempt(Base64.getDecoder.decode(envelope.payloadBase64))
        .mapError(_ => AgentError.InvalidConfiguration("eval-trend:invalid-base64"))
      actual = sha256(payload)
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("eval-trend:checksum-mismatch"))
        .unless(
          MessageDigest.isEqual(
            actual.getBytes(StandardCharsets.US_ASCII),
            envelope.sha256.getBytes(StandardCharsets.US_ASCII)
          )
        )
      json          <- strictUtf8(payload)
      decodedEither <- ZIO
        .attempt(json.fromJson[EvalSuiteSnapshot])
        .mapError(_ => AgentError.InvalidConfiguration("eval-trend:invalid-snapshot-json"))
      snapshot <- ZIO
        .fromEither(decodedEither)
        .mapError(_ => AgentError.InvalidConfiguration("eval-trend:invalid-snapshot-json"))
      _ <- EvalSuiteSnapshot.validate(snapshot)
    yield snapshot

  /** 严格 UTF-8；替换字符可能改变 ID、版本或校验语义，因此不能容错。 */
  private def strictUtf8(bytes: Array[Byte]): IO[AgentError.InvalidConfiguration, String] =
    ZIO
      .attempt {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      }
      .mapError(_ => AgentError.InvalidConfiguration("eval-trend:invalid-utf8"))

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def persistence(code: String): AgentError.PersistenceFailure =
    AgentError.PersistenceFailure(s"eval-trend:$code")
