package com.zyblw.agent.evals

import com.zyblw.agent.context.*
import com.zyblw.agent.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import zio.*
import zio.json.*

/** Context 压缩评测中的一条原始消息。
  *
  * 评测数据集不直接复用完整 `AgentMessage`，是因为 metadata、toolCalls 和图片地址可能意外携带生产标识或敏感信息。 这个受限 DTO
  * 只允许角色和文本，既足以覆盖长历史压缩，又让数据集更容易脱敏、审查和版本化。
  *
  * @param role
  *   原始消息角色；压缩器应保留事实含义，而不是把低优先级用户文本提升为系统指令
  * @param content
  *   已脱敏的消息正文；允许换行，但不能为空且受单条长度上限保护
  */
final case class ContextCompressionEvalSource(role: MessageRole, content: String) derives JsonCodec:
  require(content.nonEmpty, "Context 压缩评测消息不能为空")
  require(
    content.codePointCount(0, content.length) <= 100000,
    "Context 压缩评测单条消息不能超过 100000 个 Unicode code point"
  )

  /** 转换为框架统一消息。
    *
    * @return
    *   不携带 metadata、工具调用或外部地址的纯文本 `AgentMessage`
    */
  def toAgentMessage: AgentMessage = AgentMessage(role, Chunk(ContentPart.Text(content)))

/** 一条需要在压缩结果中机械检查的稳定期望。
  *
  * `id` 会进入 CI 报告，`value` 只用于进程内匹配，绝不会写入报告。这样既能定位“哪条证据丢了”，又不会因为失败报告 把用户正文、医疗资料或内部指令复制到日志平台。
  *
  * @param id
  *   数据集内稳定、安全的期望 ID，例如 `preferred-language`
  * @param value
  *   必须保留或禁止出现的原文片段；当前采用逐字包含判断，避免让另一个 LLM 决定硬事实
  */
final case class ContextCompressionEvalExpectation(id: String, value: String) derives JsonCodec:
  require(id.matches("[A-Za-z0-9._-]{1,120}"), "Context 压缩期望 ID 只能包含安全字符")
  require(value.nonEmpty, "Context 压缩期望值不能为空")
  require(value.codePointCount(0, value.length) <= 20000, "Context 压缩期望值不能超过 20000 个 Unicode code point")

/** 一条 Context 压缩用例的发布阈值。
  *
  * 质量、稳定性和资源消耗分别建模，不能用平均分抵消禁止内容泄漏或预算超限。成本使用货币最小精度的百万分之一 （microunit），避免 Double 舍入；例如 1 美元等于 1,000,000 个 USD
  * microunits。
  *
  * @param minRequiredEvidenceRecall
  *   每次重复运行都必须达到的事实证据最低召回率
  * @param minRequiredReferenceRecall
  *   每次重复运行都必须达到的来源引用最低召回率
  * @param minStabilityRate
  *   相同输出摘要哈希在全部重复运行中的最低占比
  * @param maxLatencyMillis
  *   单次压缩最大墙钟延迟
  * @param maxInputTokens
  *   单次压缩 Provider 输入 token 上限
  * @param maxOutputTokens
  *   单次压缩 Provider 输出 token 上限
  * @param maxModelCalls
  *   单次压缩（含修复重试）最多模型调用数
  * @param maxSummaryCodePoints
  *   压缩结果最大 Unicode code point 数
  * @param maxEstimatedCostMicrounits
  *   可选单次估算成本上限；设置后必须向 Runner 提供价格估算器
  */
final case class ContextCompressionEvalThresholds(
    minRequiredEvidenceRecall: Double = 1.0,
    minRequiredReferenceRecall: Double = 1.0,
    minStabilityRate: Double = 1.0,
    maxLatencyMillis: Long = 20000L,
    maxInputTokens: Long = 8000L,
    maxOutputTokens: Long = 2000L,
    maxModelCalls: Int = 2,
    maxSummaryCodePoints: Int = 8000,
    maxEstimatedCostMicrounits: Option[Long] = None
) derives JsonCodec:
  private val qualityValues =
    List(minRequiredEvidenceRecall, minRequiredReferenceRecall, minStabilityRate)

  require(
    qualityValues.forall(value => java.lang.Double.isFinite(value) && value >= 0.0 && value <= 1.0),
    "Context 压缩质量阈值必须是 0..1 的有限数"
  )
  require(maxLatencyMillis > 0L, "Context 压缩最大延迟必须为正数")
  require(maxInputTokens >= 0L && maxOutputTokens >= 0L, "Context 压缩 token 阈值不能为负数")
  require(maxModelCalls >= 0 && maxModelCalls <= 20, "Context 压缩模型调用阈值必须位于 0..20")
  require(maxSummaryCodePoints > 0 && maxSummaryCodePoints <= 1000000, "Context 压缩结果长度阈值必须位于 1..1000000")
  require(maxEstimatedCostMicrounits.forall(_ >= 0L), "Context 压缩成本阈值不能为负数")

/** 一条可版本化、可重复执行的 Context 压缩评测用例。
  *
  * `requiredEvidence` 用于目标、约束、决定、未完成事项等事实；`requiredReferences` 单独记录来源 URI、文档 ID 或 artifact
  * 引用；`forbiddenEvidence` 用于提示注入诱饵、已撤销决定和不应被摘要继承的敏感内容。三者分开后，报告能够 明确指出是“事实丢失”“引用丢失”还是“危险内容进入摘要”。
  *
  * @param id
  *   数据集内稳定唯一 ID
  * @param datasetVersion
  *   数据集/标注版本，修改输入或期望后必须推进
  * @param sources
  *   已脱敏的原始历史消息
  * @param requiredEvidence
  *   每次运行都应逐字保留的关键事实
  * @param forbiddenEvidence
  *   压缩结果绝不能包含的诱饵或已撤销内容
  * @param requiredReferences
  *   每次运行都应保留的规范化来源或 artifact 标识
  * @param targetTokens
  *   传给 `ContextCompressor` 的摘要目标 token
  * @param maxModelCallsPerAttempt
  *   传给压缩器的每次最大模型调用数，包含修复重试
  * @param repetitions
  *   同一输入重复运行次数，用于发现非确定性退化
  * @param thresholds
  *   本用例的质量与资源发布门禁
  */
final case class ContextCompressionEvalCase(
    id: String,
    datasetVersion: String,
    sources: Chunk[ContextCompressionEvalSource],
    requiredEvidence: Chunk[ContextCompressionEvalExpectation] = Chunk.empty,
    forbiddenEvidence: Chunk[ContextCompressionEvalExpectation] = Chunk.empty,
    requiredReferences: Chunk[ContextCompressionEvalExpectation] = Chunk.empty,
    targetTokens: Long = 1024L,
    maxModelCallsPerAttempt: Int = 2,
    repetitions: Int = 3,
    thresholds: ContextCompressionEvalThresholds = ContextCompressionEvalThresholds()
) derives JsonCodec:
  private val expectations = requiredEvidence ++ forbiddenEvidence ++ requiredReferences
  private val sourceText   = sources.map(_.content)
  private val ids          = expectations.map(_.id)

  require(id.matches("[A-Za-z0-9._-]{1,120}"), "Context 压缩用例 ID 只能包含安全字符")
  require(datasetVersion.matches("[A-Za-z0-9._-]{1,120}"), "Context 压缩数据集版本只能包含安全字符")
  require(sources.nonEmpty && sources.length <= 1000, "Context 压缩用例必须包含 1..1000 条消息")
  require(targetTokens > 0L && targetTokens <= 1000000L, "Context 压缩目标 token 必须位于 1..1000000")
  require(maxModelCallsPerAttempt >= 0 && maxModelCallsPerAttempt <= 20, "每次压缩模型调用预算必须位于 0..20")
  require(repetitions >= 1 && repetitions <= 20, "Context 压缩重复次数必须位于 1..20")
  require(ids.distinct.length == ids.length, "同一 Context 压缩用例中的期望 ID 必须唯一")
  require(
    requiredEvidence.map(_.value).toSet.intersect(forbiddenEvidence.map(_.value).toSet).isEmpty,
    "同一证据不能同时标记为必需和禁止"
  )
  require(
    expectations.forall(expectation => sourceText.exists(_.contains(expectation.value))),
    "每条 Context 压缩期望值都必须真实存在于输入消息中"
  )

/** 一次压缩尝试的安全状态。
  *
  * 失败时只保存稳定错误分类和 retryable，不保存 `AgentError.message`，因为错误正文可能包含 Provider 返回片段或原始输入。
  */
enum ContextCompressionEvalAttemptStatus derives JsonCodec:
  case Succeeded
  case Failed(category: String, retryable: Boolean)

/** 一次重复运行的脱敏观测值。
  *
  * @param attempt
  *   从 1 开始的重复序号
  * @param status
  *   成功或脱敏后的错误分类
  * @param matchedRequiredEvidenceIds
  *   命中的必需证据 ID，不包含证据正文
  * @param matchedForbiddenEvidenceIds
  *   命中的禁止证据 ID
  * @param matchedReferenceIds
  *   命中的必需引用 ID
  * @param outputDigest
  *   压缩消息规范 JSON 的 SHA-256；用于稳定性比较，不用于恢复原文
  * @param outputCodePoints
  *   压缩结果可见内容长度
  * @param latencyMillis
  *   单次墙钟延迟
  * @param usage
  *   压缩器报告的模型 token
  * @param modelCalls
  *   压缩器报告的实际模型调用次数
  * @param compressorVersion
  *   压缩器协议/Prompt 版本
  * @param estimatedCostMicrounits
  *   可选成本估算
  * @param costCurrency
  *   成本币种，例如 USD
  * @param pricingVersion
  *   生成成本估算的价格表版本
  */
final case class ContextCompressionEvalAttempt(
    attempt: Int,
    status: ContextCompressionEvalAttemptStatus,
    matchedRequiredEvidenceIds: Set[String],
    matchedForbiddenEvidenceIds: Set[String],
    matchedReferenceIds: Set[String],
    outputDigest: Option[String],
    outputCodePoints: Int,
    latencyMillis: Long,
    usage: TokenUsage,
    modelCalls: Int,
    compressorVersion: Option[String],
    estimatedCostMicrounits: Option[Long],
    costCurrency: Option[String],
    pricingVersion: Option[String]
) derives JsonCodec:
  require(attempt > 0, "Context 压缩尝试序号必须为正数")
  require(outputCodePoints >= 0 && latencyMillis >= 0L && modelCalls >= 0, "Context 压缩观测值不能为负数")
  require(usage.inputTokens >= 0L && usage.outputTokens >= 0L, "Context 压缩 token 不能为负数")
  require(estimatedCostMicrounits.forall(_ >= 0L), "Context 压缩成本不能为负数")
  require(outputDigest.forall(_.matches("[a-f0-9]{64}")), "Context 压缩摘要哈希必须是 SHA-256")
  require(costCurrency.forall(_.matches("[A-Z]{3,10}")), "Context 压缩成本币种必须是 3..10 位大写字母")
  require(pricingVersion.forall(_.matches("[A-Za-z0-9._-]{1,120}")), "Context 压缩价格版本只能包含安全字符")
  require(
    List(estimatedCostMicrounits, costCurrency, pricingVersion).count(_.nonEmpty) == 0 ||
      List(estimatedCostMicrounits, costCurrency, pricingVersion).forall(_.nonEmpty),
    "Context 压缩成本、币种和价格版本必须同时存在或同时缺失"
  )

  /** 是否成功产生了可评分压缩结果。 */
  def succeeded: Boolean = status == ContextCompressionEvalAttemptStatus.Succeeded

/** 单条 Context 压缩用例报告。
  *
  * @param caseId
  *   用例稳定 ID
  * @param datasetVersion
  *   数据集版本
  * @param attempts
  *   各次脱敏观测值
  * @param grades
  *   完成、证据、引用、禁止内容、稳定性和资源六类门禁
  */
final case class ContextCompressionEvalReport(
    caseId: String,
    datasetVersion: String,
    attempts: Chunk[ContextCompressionEvalAttempt],
    grades: Chunk[EvalGrade]
) derives JsonCodec:
  /** 所有硬门禁都通过才允许发布。 */
  def passed: Boolean = grades.nonEmpty && grades.forall(_.passed)

  /** 平均分只用于趋势观察，不能覆盖任何硬门禁失败。 */
  def averageScore: Double =
    if grades.isEmpty then 0.0 else grades.map(_.score).sum / grades.length.toDouble

/** 一次 Context 压缩数据集的聚合报告。 */
final case class ContextCompressionEvalSuiteReport(reports: Chunk[ContextCompressionEvalReport])
    derives JsonCodec:
  /** 空数据集不能制造假绿。 */
  def passed: Boolean = reports.nonEmpty && reports.forall(_.passed)

  /** 通过率用于趋势图；CI 放行仍必须读取 `passed`。 */
  def passRate: Double =
    if reports.isEmpty then 0.0 else reports.count(_.passed).toDouble / reports.length.toDouble

/** 压缩模型价格估算边界。
  *
  * 价格不属于 Provider 响应的稳定事实，因此由部署环境显式提供带版本的价格表。返回 `None` 表示未配置价格；当用例设置 成本门禁时，未配置价格会安全失败，而不会把未知成本当成零。
  */
trait ContextCompressionCostEstimator:
  /** 根据一次压缩的 token 用量估算成本。
    *
    * @param usage
    *   Provider 返回或可信计数器生成的输入/输出 token
    * @return
    *   可选 `(microunits, currency, pricingVersion)`；不得返回负数
    */
  def estimate(usage: TokenUsage): UIO[Option[(Long, String, String)]]

object ContextCompressionCostEstimator:
  /** 未配置价格表的默认实现。仅当数据集没有成本门禁时适用。 */
  val unpriced: ContextCompressionCostEstimator = new ContextCompressionCostEstimator:
    def estimate(usage: TokenUsage): UIO[Option[(Long, String, String)]] =
      val _ = usage
      ZIO.none

  /** 创建固定 token 单价估算器。
    *
    * @param currency
    *   ISO 风格币种代码，例如 USD 或 CNY
    * @param pricingVersion
    *   价格表版本；模型或价格变化时必须更新
    * @param inputCostPerMillionTokensMicrounits
    *   一百万输入 token 的成本，单位为货币 microunit
    * @param outputCostPerMillionTokensMicrounits
    *   一百万输出 token 的成本，单位为货币 microunit
    */
  def fixedTokenPrice(
      currency: String,
      pricingVersion: String,
      inputCostPerMillionTokensMicrounits: Long,
      outputCostPerMillionTokensMicrounits: Long
  ): ContextCompressionCostEstimator =
    require(currency.matches("[A-Z]{3,10}"), "成本币种必须是 3..10 位大写字母")
    require(pricingVersion.matches("[A-Za-z0-9._-]{1,120}"), "价格版本只能包含安全字符")
    require(
      inputCostPerMillionTokensMicrounits >= 0L && outputCostPerMillionTokensMicrounits >= 0L,
      "token 单价不能为负数"
    )
    new ContextCompressionCostEstimator:
      def estimate(usage: TokenUsage): UIO[Option[(Long, String, String)]] =
        val input  = costFor(usage.inputTokens, inputCostPerMillionTokensMicrounits)
        val output = costFor(usage.outputTokens, outputCostPerMillionTokensMicrounits)
        ZIO.some((saturatingAdd(input, output), currency, pricingVersion))

  /** 使用 BigInt 避免 `tokens * rate` 在长上下文压力测试中溢出，并向上取整防止系统性低估。 */
  private def costFor(tokens: Long, rate: Long): Long =
    if tokens <= 0L || rate == 0L then 0L
    else
      val value = (BigInt(tokens) * BigInt(rate) + BigInt(999999L)) / BigInt(1000000L)
      if value > BigInt(Long.MaxValue) then Long.MaxValue else value.toLong

  /** 成本相加采用饱和语义；极端输入宁可触发预算失败，也不能因 Long 回绕变成负成本。 */
  private def saturatingAdd(left: Long, right: Long): Long =
    if Long.MaxValue - left < right then Long.MaxValue else left + right

/** Context 压缩确定性评分器。
  *
  * 这里不使用 LLM-as-a-judge：期望证据是否逐字保留、禁止文本是否泄漏、输出是否稳定以及资源是否超限都能机械判断。 语义等价改写可在业务数据集上增加独立 Judge
  * 维度，但不能替代这些安全和预算硬门禁。
  */
object ContextCompressionEvalGrader:
  /** 生成六个互不抵消的发布门禁。 */
  def grade(
      evalCase: ContextCompressionEvalCase,
      attempts: Chunk[ContextCompressionEvalAttempt]
  ): ContextCompressionEvalReport =
    ContextCompressionEvalReport(
      evalCase.id,
      evalCase.datasetVersion,
      attempts,
      Chunk(
        completionGrade(evalCase, attempts),
        evidenceGrade(evalCase, attempts),
        referenceGrade(evalCase, attempts),
        forbiddenGrade(attempts),
        stabilityGrade(evalCase, attempts),
        resourceGrade(evalCase, attempts)
      )
    )

  /** 重复次数不足或任何一次 typed failure 都不能视为完成。 */
  private def completionGrade(
      evalCase: ContextCompressionEvalCase,
      attempts: Chunk[ContextCompressionEvalAttempt]
  ): EvalGrade =
    val succeeded = attempts.count(_.succeeded)
    val failures  = attempts
      .flatMap { attempt =>
        attempt.status match
          case ContextCompressionEvalAttemptStatus.Failed(category, _) => Some(category)
          case ContextCompressionEvalAttemptStatus.Succeeded           => None
      }
      .groupMapReduce(identity)(_ => 1)(_ + _)
    val passed = attempts.length == evalCase.repetitions && succeeded == evalCase.repetitions
    EvalGrade(
      "context-compression-completion",
      passed,
      succeeded.toDouble / evalCase.repetitions.toDouble,
      s"succeeded=$succeeded/${evalCase.repetitions};failureCategories=${safeCounts(failures)}"
    )

  /** 每次运行都按必需证据 ID 计算召回率，取最差一次作为发布分数。 */
  private def evidenceGrade(
      evalCase: ContextCompressionEvalCase,
      attempts: Chunk[ContextCompressionEvalAttempt]
  ): EvalGrade =
    val expectedIds       = evalCase.requiredEvidence.map(_.id).toSet
    val recalls           = attempts.map(attempt => recall(expectedIds, attempt.matchedRequiredEvidenceIds))
    val score             = if recalls.isEmpty then 0.0 else recalls.min
    val retainedEveryTime =
      attempts.map(_.matchedRequiredEvidenceIds).reduceOption(_ intersect _).getOrElse(Set.empty)
    val missing = expectedIds.diff(retainedEveryTime)
    val passed  = attempts.length == evalCase.repetitions &&
      score >= evalCase.thresholds.minRequiredEvidenceRecall
    EvalGrade(
      "context-compression-evidence-retention",
      passed,
      score,
      s"minRecall=$score/${evalCase.thresholds.minRequiredEvidenceRecall};notRetainedEveryTime=${safeIds(missing)}"
    )

  /** 引用与普通事实分开评分，避免摘要保留结论却丢失可追溯来源。 */
  private def referenceGrade(
      evalCase: ContextCompressionEvalCase,
      attempts: Chunk[ContextCompressionEvalAttempt]
  ): EvalGrade =
    val expectedIds       = evalCase.requiredReferences.map(_.id).toSet
    val recalls           = attempts.map(attempt => recall(expectedIds, attempt.matchedReferenceIds))
    val score             = if recalls.isEmpty then 0.0 else recalls.min
    val retainedEveryTime =
      attempts.map(_.matchedReferenceIds).reduceOption(_ intersect _).getOrElse(Set.empty)
    val missing = expectedIds.diff(retainedEveryTime)
    val passed  = attempts.length == evalCase.repetitions &&
      score >= evalCase.thresholds.minRequiredReferenceRecall
    EvalGrade(
      "context-compression-reference-retention",
      passed,
      score,
      s"minRecall=$score/${evalCase.thresholds.minRequiredReferenceRecall};notRetainedEveryTime=${safeIds(missing)}"
    )

  /** 任意重复运行命中禁止内容都立即失败，不允许被其他高分平均掉。 */
  private def forbiddenGrade(attempts: Chunk[ContextCompressionEvalAttempt]): EvalGrade =
    val hits = attempts.flatMap(_.matchedForbiddenEvidenceIds).toSet
    EvalGrade(
      "context-compression-forbidden-content",
      hits.isEmpty,
      if hits.isEmpty then 1.0 else 0.0,
      s"matched=${safeIds(hits)}"
    )

  /** 稳定性同时检查输出摘要哈希和 compressorVersion。
    *
    * 相同输入的重复输出不必永远逐字一致，因此阈值可低于 1；但版本漂移会使同一次回归不可解释，始终作为硬失败。
    */
  private def stabilityGrade(
      evalCase: ContextCompressionEvalCase,
      attempts: Chunk[ContextCompressionEvalAttempt]
  ): EvalGrade =
    val digests      = attempts.flatMap(_.outputDigest)
    val mostFrequent = digests.groupMapReduce(identity)(_ => 1)(_ + _).values.maxOption.getOrElse(0)
    val rate         = mostFrequent.toDouble / evalCase.repetitions.toDouble
    val versions     = attempts.flatMap(_.compressorVersion).distinct
    val passed       = attempts.length == evalCase.repetitions &&
      rate >= evalCase.thresholds.minStabilityRate &&
      versions.length == 1
    EvalGrade(
      "context-compression-stability",
      passed,
      rate,
      s"stableRate=$rate/${evalCase.thresholds.minStabilityRate};versions=${versions.length};distinctDigests=${digests.distinct.length}"
    )

  /** 延迟、输入/输出 token、模型调用、摘要长度和可选成本任一超限都令资源门禁失败。 */
  private def resourceGrade(
      evalCase: ContextCompressionEvalCase,
      attempts: Chunk[ContextCompressionEvalAttempt]
  ): EvalGrade =
    val limits     = evalCase.thresholds
    val maxLatency = attempts.map(_.latencyMillis).maxOption.getOrElse(0L)
    val maxInput   = attempts.map(_.usage.inputTokens).maxOption.getOrElse(0L)
    val maxOutput  = attempts.map(_.usage.outputTokens).maxOption.getOrElse(0L)
    val maxCalls   = attempts.map(_.modelCalls).maxOption.getOrElse(0)
    val maxLength  = attempts.map(_.outputCodePoints).maxOption.getOrElse(0)
    val costs      = attempts.flatMap(_.estimatedCostMicrounits)
    val costPassed = limits.maxEstimatedCostMicrounits match
      case None        => true
      case Some(limit) => costs.length == attempts.length && costs.forall(_ <= limit)
    val maxCost = costs.maxOption
    val passed  =
      maxLatency <= limits.maxLatencyMillis &&
        maxInput <= limits.maxInputTokens &&
        maxOutput <= limits.maxOutputTokens &&
        maxCalls <= limits.maxModelCalls &&
        maxCalls <= evalCase.maxModelCallsPerAttempt &&
        maxLength <= limits.maxSummaryCodePoints &&
        costPassed
    EvalGrade(
      "context-compression-resource-budget",
      passed,
      if passed then 1.0 else 0.0,
      s"latency=$maxLatency/${limits.maxLatencyMillis};input=$maxInput/${limits.maxInputTokens};" +
        s"output=$maxOutput/${limits.maxOutputTokens};calls=$maxCalls/${limits.maxModelCalls};" +
        s"codePoints=$maxLength/${limits.maxSummaryCodePoints};cost=${maxCost.fold("unpriced")(_.toString)}/" +
        limits.maxEstimatedCostMicrounits.fold("disabled")(_.toString)
    )

  /** 没有期望时得满分；否则只计算交集，防止观测值中的未知 ID 抬高分数。 */
  private def recall(expected: Set[String], actual: Set[String]): Double =
    if expected.isEmpty then 1.0 else expected.intersect(actual).size.toDouble / expected.size.toDouble

  /** 报告最多列出 20 个稳定 ID，避免异常数据集制造无界日志。 */
  private def safeIds(ids: Set[String]): String =
    val ordered = ids.toList.sorted
    val visible = ordered.take(20).mkString(",")
    if ordered.length <= 20 then visible else s"$visible,+${ordered.length - 20}"

  /** 失败分类属于低基数枚举；排序后输出便于快照测试和 CI diff。 */
  private def safeCounts(counts: Map[String, Int]): String =
    counts.toList.sortBy(_._1).map((name, count) => s"$name:$count").mkString(",")

/** 并发执行 Context 压缩数据集的 ZIO Harness。
  *
  * 用例之间有界并行，同一用例的重复尝试按顺序运行。后者是有意设计：稳定性测试不应因为同一模型账户瞬时并发、Provider 排队或共享限流器制造额外噪声。typed `ContextError`
  * 会转成失败观测并继续执行数据集；Defect 与 Fiber interruption 仍保留 ZIO 原生语义，不会被伪装成普通评分失败。
  *
  * @param maxParallelism
  *   最大并发用例数，应小于 Provider 限流器和连接池安全上限
  * @param costEstimator
  *   带版本价格表；没有成本门禁时可使用默认未定价实现
  */
final class ContextCompressionEvalRunner(
    maxParallelism: Int,
    costEstimator: ContextCompressionCostEstimator = ContextCompressionCostEstimator.unpriced
):
  require(maxParallelism > 0 && maxParallelism <= 256, "Context compression eval maxParallelism 必须位于 1..256")

  /** 对每条用例执行指定次数并保持数据集输入顺序。
    *
    * @param compressor
    *   被测确定性或模型辅助 `ContextCompressor`
    * @param cases
    *   版本化、已脱敏的数据集
    * @return
    *   不因单条 typed failure 中断的安全聚合报告
    */
  def run(
      compressor: ContextCompressor,
      cases: Chunk[ContextCompressionEvalCase]
  ): UIO[ContextCompressionEvalSuiteReport] =
    ZIO
      .foreachPar(cases)(runCase(compressor, _))
      .withParallelism(maxParallelism)
      .map(ContextCompressionEvalSuiteReport(_))

  /** 同一用例顺序重复执行，完成后统一评分。 */
  private def runCase(
      compressor: ContextCompressor,
      evalCase: ContextCompressionEvalCase
  ): UIO[ContextCompressionEvalReport] =
    ZIO
      .foreach(1 to evalCase.repetitions)(attempt => runAttempt(compressor, evalCase, attempt))
      .map(attempts => ContextCompressionEvalGrader.grade(evalCase, Chunk.fromIterable(attempts)))

  /** 测量一次真实压缩调用，并将正文转换为只含 ID、计数和哈希的安全观测值。 */
  private def runAttempt(
      compressor: ContextCompressor,
      evalCase: ContextCompressionEvalCase,
      attempt: Int
  ): UIO[ContextCompressionEvalAttempt] =
    for
      started <- Clock.nanoTime
      result  <- compressor
        .compress(
          evalCase.sources.map(_.toAgentMessage),
          evalCase.targetTokens,
          evalCase.maxModelCallsPerAttempt
        )
        // maxLatencyMillis 同时是发布 SLO 和主动 Fiber 超时。只做事后计时会让失联 Provider、代理或自定义
        // compressor 永久占用 CI worker；timeout 会中断底层 ZIO HTTP 请求，并把结果转成可重试 typed failure。
        .timeoutFail(
          AgentError.ContextCompressionFailed("context-compression-eval-timeout", retryable = true)
        )(Duration.fromMillis(evalCase.thresholds.maxLatencyMillis))
        .either
      ended <- Clock.nanoTime
      latency = math.max(0L, (ended - started) / 1000000L)
      observation <- result match
        case Left(error) =>
          ZIO.succeed(failedAttempt(attempt, error, latency))
        case Right(value) =>
          successfulAttempt(evalCase, attempt, value, latency)
    yield observation

  /** 成功结果在进程内完成逐字匹配；报告只保存期望 ID。 */
  private def successfulAttempt(
      evalCase: ContextCompressionEvalCase,
      attempt: Int,
      result: ContextCompressionResult,
      latencyMillis: Long
  ): UIO[ContextCompressionEvalAttempt] =
    val visible = renderVisibleContent(result.message)
    for estimate <- costEstimator.estimate(result.usage)
    yield
      val (cost, currency, pricingVersion) = estimate match
        case Some((microunits, valueCurrency, valueVersion)) =>
          (Some(microunits), Some(valueCurrency), Some(valueVersion))
        case None => (None, None, None)
      ContextCompressionEvalAttempt(
        attempt = attempt,
        status = ContextCompressionEvalAttemptStatus.Succeeded,
        matchedRequiredEvidenceIds = matched(evalCase.requiredEvidence, visible),
        matchedForbiddenEvidenceIds = matched(evalCase.forbiddenEvidence, visible),
        matchedReferenceIds = matched(evalCase.requiredReferences, visible),
        outputDigest = Some(sha256(result.message.toJson)),
        outputCodePoints = visible.codePointCount(0, visible.length),
        latencyMillis = latencyMillis,
        usage = result.usage,
        modelCalls = result.modelCalls,
        compressorVersion = Some(result.compressorVersion),
        estimatedCostMicrounits = cost,
        costCurrency = currency,
        pricingVersion = pricingVersion
      )

  /** typed failure 只投影稳定分类，原始错误消息不会进入评测报告。 */
  private def failedAttempt(
      attempt: Int,
      error: ContextError,
      latencyMillis: Long
  ): ContextCompressionEvalAttempt =
    ContextCompressionEvalAttempt(
      attempt = attempt,
      status = ContextCompressionEvalAttemptStatus.Failed(error.category.toString, error.retryable),
      matchedRequiredEvidenceIds = Set.empty,
      matchedForbiddenEvidenceIds = Set.empty,
      matchedReferenceIds = Set.empty,
      outputDigest = None,
      outputCodePoints = 0,
      latencyMillis = latencyMillis,
      usage = TokenUsage(),
      modelCalls = 0,
      compressorVersion = None,
      estimatedCostMicrounits = None,
      costCurrency = None,
      pricingVersion = None
    )

  /** 逐字匹配只在内存中执行，结果返回稳定 ID。 */
  private def matched(
      expectations: Chunk[ContextCompressionEvalExpectation],
      output: String
  ): Set[String] =
    expectations.collect { case expectation if output.contains(expectation.value) => expectation.id }.toSet

  /** 把所有可见内容转成稳定字符串用于匹配和长度统计。
    *
    * JSON、图片地址和工具参数也可能占用上下文，因此不能像 `AgentMessage.text` 一样忽略它们。该字符串只在当前 Fiber 中存在，不写入报告。
    */
  private def renderVisibleContent(message: AgentMessage): String =
    val content = message.content.map {
      case ContentPart.Text(value)           => value
      case ContentPart.JsonValue(value)      => value.toJson
      case ContentPart.ImageUrl(url, detail) => s"$url:${detail.getOrElse("")}"
    }
    val calls = message.toolCalls.map(call => s"${call.id}:${call.name}:${call.arguments.toJson}")
    (content ++ calls).mkString("\n")

  /** 对消息规范 JSON 计算 SHA-256，供重复运行稳定性比较。 */
  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

/** Context 压缩评测数据集加载器。
  *
  * 文件边界采用“先检查普通文件和大小，再严格 UTF-8 解码，再解析 JSON”的顺序。错误只返回稳定码，不回显 JSON parser 的上下文片段，避免格式错误时把数据集正文写入日志。
  */
object ContextCompressionEvalDataset:
  /** 默认数据集最大 4 MiB；更大的生产数据集应按业务域拆分并分别回归。 */
  val DefaultMaxBytes: Long = 4L * 1024L * 1024L

  /** 从 JSON 数组文件加载并验证数据集。
    *
    * @param path
    *   数据集文件；符号链接会被拒绝，避免 CI 误读工作区外文件
    * @param maxBytes
    *   文件大小硬上限，必须位于 1 Byte..64 MiB
    * @return
    *   非空且用例 ID 唯一的数据集
    */
  def load(
      path: Path,
      maxBytes: Long = DefaultMaxBytes
  ): IO[AgentError.InvalidConfiguration, Chunk[ContextCompressionEvalCase]] =
    for
      _         <- validateMaxBytes(maxBytes)
      isRegular <- ZIO
        .attemptBlocking(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        .orElseSucceed(false)
      _     <- ZIO.fail(invalid("not-regular-file")).unless(isRegular)
      size  <- ZIO.attemptBlockingIO(Files.size(path)).mapError(_ => invalid("size-read-failed"))
      _     <- ZIO.fail(invalid("empty-dataset")).when(size <= 0L)
      _     <- ZIO.fail(invalid("dataset-too-large")).when(size > maxBytes)
      bytes <- ZIO.attemptBlockingIO(Files.readAllBytes(path)).mapError(_ => invalid("dataset-read-failed"))
      cases <- decode(bytes, maxBytes)
    yield cases

  /** 从 classpath 或 JAR 资源加载数据集。
    *
    * @param resourceName
    *   相对 classpath 资源名；禁止绝对路径、反斜杠和 `..` 路径穿越
    * @param classLoader
    *   资源所属 classloader；应用打包或插件隔离时可显式传入
    * @param maxBytes
    *   解压后读取的字节硬上限
    */
  def loadResource(
      resourceName: String,
      classLoader: ClassLoader = Thread.currentThread().getContextClassLoader,
      maxBytes: Long = DefaultMaxBytes
  ): IO[AgentError.InvalidConfiguration, Chunk[ContextCompressionEvalCase]] =
    for
      _ <- validateMaxBytes(maxBytes)
      _ <- ZIO
        .fail(invalid("invalid-resource-name"))
        .unless(
          resourceName.nonEmpty &&
            !resourceName.startsWith("/") &&
            !resourceName.contains("\\") &&
            !resourceName.split('/').contains("..") &&
            resourceName.matches("[A-Za-z0-9._/-]{1,500}")
        )
      cases <- ZIO.acquireReleaseWith(
        ZIO
          .attempt(Option(classLoader.getResourceAsStream(resourceName)))
          .mapError(_ => invalid("resource-open-failed"))
          .someOrFail(invalid("resource-not-found"))
      )(stream => ZIO.attemptBlockingIO(stream.close()).orDie) { stream =>
        ZIO
          .attemptBlockingIO(stream.readNBytes((maxBytes + 1L).toInt))
          .mapError(_ => invalid("resource-read-failed"))
          .flatMap(bytes => decode(bytes, maxBytes))
      }
    yield cases

  /** 从已经读取的字节解析数据集。
    *
    * 该入口适合对象存储、加密配置或测试资源；调用方仍不能绕过大小、UTF-8、非空和唯一 ID 验证。
    *
    * @param bytes
    *   完整 JSON 数组字节
    * @param maxBytes
    *   允许的最大字节数
    */
  def decode(
      bytes: Array[Byte],
      maxBytes: Long = DefaultMaxBytes
  ): IO[AgentError.InvalidConfiguration, Chunk[ContextCompressionEvalCase]] =
    for
      _       <- validateMaxBytes(maxBytes)
      _       <- ZIO.fail(invalid("empty-dataset")).when(bytes.isEmpty)
      _       <- ZIO.fail(invalid("dataset-too-large")).when(bytes.length.toLong > maxBytes)
      text    <- strictUtf8(bytes)
      decoded <- ZIO
        .attempt(text.fromJson[Chunk[ContextCompressionEvalCase]])
        .mapError(_ => invalid("invalid-json"))
      cases <- ZIO.fromEither(decoded).mapError(_ => invalid("invalid-json"))
      _     <- validate(cases)
    yield cases

  /** 所有入口共用同一大小范围，确保 `readNBytes(max + 1)` 可以安全转换为 Int。 */
  private def validateMaxBytes(maxBytes: Long): IO[AgentError.InvalidConfiguration, Unit] =
    ZIO
      .fail(invalid("invalid-max-bytes"))
      .when(maxBytes <= 0L || maxBytes > 64L * 1024L * 1024L)
      .unit

  /** 严格拒绝畸形 UTF-8，不能让替换字符改变期望匹配含义。 */
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
      .mapError(_ => invalid("invalid-utf8"))

  /** 空数据集和重复 ID 都是配置错误，不能被 CI 当成零条通过。 */
  private def validate(
      cases: Chunk[ContextCompressionEvalCase]
  ): IO[AgentError.InvalidConfiguration, Unit] =
    val ids = cases.map(_.id)
    ZIO.fail(invalid("empty-dataset")).when(cases.isEmpty) *>
      ZIO.fail(invalid("duplicate-case-id")).when(ids.distinct.length != ids.length).unit

  /** 统一构造不会包含文件路径、JSON 正文或 parser 片段的安全错误。 */
  private def invalid(code: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration(s"context-compression-eval:$code")
