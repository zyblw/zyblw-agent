package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import zio.*
import zio.json.*

/** 提炼器提出的记忆变更；真正持久化仍由确定性治理层决定。 */
enum MemoryMutation:
  /** 新建或更新结构化记忆。 */
  case Upsert(entry: MemoryEntry)

  /** 忘记一个稳定 key；evidence 说明删除意图来自哪里。 */
  case Delete(key: String, evidence: MemoryEvidence)

/** 提炼器输出的有序候选。
  *
  * @param ordinal
  *   在一次提炼结果中的稳定顺序；相同 key 的多个候选按此顺序执行
  * @param mutation
  *   提议写入或删除；LLM 不能绕过治理层直接调用 MemoryStore
  */
final case class MemoryCandidate(ordinal: Int, mutation: MemoryMutation):
  require(ordinal >= 0, "MemoryCandidate ordinal 不能为负数")

/** Memory 提炼 SPI。
  *
  * 真实 LLM 实现必须使用结构化输出并把 schema/Provider 故障映射为 `MemoryExtractionFailed`；本接口本身不授予 持久化权限，输出仍会经过
  * `MemoryGovernancePolicy`。
  */
trait MemoryExtractor:
  /** @param messages
    *   经过业务选择的有限消息窗口，不应默认传入整个 Session
    * @param sourceRunId
    *   记忆来源 Run，用于审计和冲突排查
    */
  def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): IO[StoreError, Chunk[MemoryCandidate]]

object MemoryExtractor:
  /** 测试、禁用自动记忆和仅人工写入场景的空提炼器。 */
  val none: ULayer[MemoryExtractor] = ZLayer.succeed(
    new MemoryExtractor:
      def extract(messages: Chunk[AgentMessage], sourceRunId: RunId): UIO[Chunk[MemoryCandidate]] =
        ZIO.succeed(Chunk.empty)
  )

/** 长期记忆的确定性治理策略。
  *
  * @param minimumModelConfidence
  *   模型推断内容允许持久化的最低置信度
  * @param maxValueCharacters
  *   结构化 JSON 的最大字符数
  * @param requireEpisodicExpiry
  *   是否强制情节记忆设置过期时间
  * @param allowModelInferredSensitive
  *   是否允许模型推断的 Sensitive 内容；生产默认禁止
  */
final case class MemoryGovernancePolicy(
    minimumModelConfidence: Double = 0.85,
    maxValueCharacters: Int = 4_000,
    requireEpisodicExpiry: Boolean = true,
    allowModelInferredSensitive: Boolean = false
):
  require(minimumModelConfidence >= 0.0 && minimumModelConfidence <= 1.0, "minimumModelConfidence 必须位于 [0,1]")
  require(maxValueCharacters > 0, "maxValueCharacters 必须为正数")

/** 一次治理批次的可审计摘要，不包含记忆正文。 */
final case class MemoryApplyReport(
    received: Int,
    written: Int,
    deleted: Int,
    ignored: Int,
    rejected: Chunk[(String, String)]
):
  require(received >= 0 && written >= 0 && deleted >= 0 && ignored >= 0, "Memory report 计数不能为负数")

/** 执行“提炼 → 校验 → 合并 → CAS 存储/忘记”的统一生命周期。
  *
  * 候选按 `(ordinal, key)` 稳定顺序处理。策略拒绝会进入报告；数据库连接错误和 CAS 冲突会让整个 effect 失败， 调度器应重新读取后重试，不能静默覆盖并发用户修改。
  */
final class MemoryLifecycle(
    extractor: MemoryExtractor,
    store: MemoryStore,
    policy: MemoryGovernancePolicy
):

  /** 从有限消息窗口提炼并应用候选。 */
  def capture(
      scope: MemoryScope,
      messages: Chunk[AgentMessage],
      sourceRunId: RunId
  ): IO[StoreError, MemoryApplyReport] =
    extractor.extract(messages, sourceRunId).flatMap(applyCandidates(scope, _))

  /** 直接应用已经结构化的候选，便于导入工具与确定性测试复用同一治理。 */
  def applyCandidates(
      scope: MemoryScope,
      candidates: Chunk[MemoryCandidate]
  ): IO[StoreError, MemoryApplyReport] =
    val ordered = candidates.sortBy(candidate => candidate.ordinal -> candidateKey(candidate.mutation))
    ZIO.foldLeft(ordered)(MemoryApplyReport(candidates.length, 0, 0, 0, Chunk.empty)) { (report, candidate) =>
      candidate.mutation match
        case MemoryMutation.Delete(key, evidence) =>
          validateDelete(key, evidence) match
            case Some(reason) => ZIO.succeed(report.copy(rejected = report.rejected :+ (key -> reason)))
            case None         => store.delete(scope, key).as(report.copy(deleted = report.deleted + 1))
        case MemoryMutation.Upsert(incoming) =>
          validateEntry(incoming) match
            case Some(reason) =>
              ZIO.succeed(report.copy(rejected = report.rejected :+ (incoming.key -> reason)))
            case None =>
              store.get(scope, incoming.key).flatMap {
                case None =>
                  store.compareAndSet(scope, 0L, incoming).as(report.copy(written = report.written + 1))
                case Some(existing) =>
                  merge(existing, incoming) match
                    case None         => ZIO.succeed(report.copy(ignored = report.ignored + 1))
                    case Some(merged) =>
                      store
                        .compareAndSet(scope, existing.version, merged)
                        .as(report.copy(written = report.written + 1))
              }
    }

  /** 返回稳定拒绝码；不把正文写入错误或遥测。 */
  private def validateEntry(entry: MemoryEntry): Option[String] =
    if entry.key.exists(_.isWhitespace) then Some("key-contains-whitespace")
    else if entry.value.toJson.length > policy.maxValueCharacters then Some("value-too-large")
    else if containsCredentialShape(entry.value.toJson) then Some("credential-shaped-content")
    else if entry.kind == MemoryKind.Episodic && policy.requireEpisodicExpiry && entry.expiresAtEpochMilli.isEmpty
    then Some("episodic-expiry-required")
    else if entry.evidence == MemoryEvidence.ModelInferred && entry.confidence < policy.minimumModelConfidence
    then Some("model-confidence-too-low")
    else if entry.evidence == MemoryEvidence.ModelInferred && entry.sensitivity == MemorySensitivity.Sensitive &&
      !policy.allowModelInferredSensitive
    then Some("model-sensitive-memory-forbidden")
    else None

  /** 删除必须来自明确用户意图或可信工具事件，模型推断不能自行遗忘用户数据。 */
  private def validateDelete(key: String, evidence: MemoryEvidence): Option[String] =
    if key.trim.isEmpty then Some("empty-delete-key")
    else
      evidence match
        case MemoryEvidence.UserStated | MemoryEvidence.ToolObserved => None
        case _                                                       => Some("delete-evidence-not-authorized")

  /** 确定性合并：证据等级优先，其次 confidence，再其次更新时间。 新候选胜出时保留原 createdAt，并取较高 importance；旧事实更强时返回 None 避免无意义版本增长。
    */
  private def merge(existing: MemoryEntry, incoming: MemoryEntry): Option[MemoryEntry] =
    val existingRank = evidenceRank(existing.evidence)
    val incomingRank = evidenceRank(incoming.evidence)
    val incomingWins = incomingRank > existingRank ||
      (incomingRank == existingRank && incoming.confidence > existing.confidence) ||
      (incomingRank == existingRank && incoming.confidence == existing.confidence &&
        incoming.updatedAtEpochMilli > existing.updatedAtEpochMilli)
    Option.when(incomingWins)(
      incoming.copy(
        createdAtEpochMilli = existing.createdAtEpochMilli,
        importance = Math.max(existing.importance, incoming.importance),
        version = existing.version
      )
    )

  /** 用户明确陈述 > 工具观察 > 受控导入 > 模型推断。 */
  private def evidenceRank(value: MemoryEvidence): Int = value match
    case MemoryEvidence.UserStated    => 4
    case MemoryEvidence.ToolObserved  => 3
    case MemoryEvidence.Imported      => 2
    case MemoryEvidence.ModelInferred => 1

  /** 候选排序用稳定 key。 */
  private def candidateKey(value: MemoryMutation): String = value match
    case MemoryMutation.Upsert(entry)  => entry.key
    case MemoryMutation.Delete(key, _) => key

  /** 粗粒度本地 secret 防线；真正 Secret Scanner 仍应在业务输入边界执行。 */
  private def containsCredentialShape(value: String): Boolean =
    val normalized = value.toLowerCase
    normalized.contains("api_key") || normalized.contains("password") || normalized.contains("bearer ") ||
    normalized.matches(".*sk-[a-z0-9_-]{12,}.*")

object MemoryLifecycle:
  /** 从提炼器、Store 与显式策略装配。 */
  val layer: URLayer[MemoryExtractor & MemoryStore & MemoryGovernancePolicy, MemoryLifecycle] =
    ZLayer.fromFunction(MemoryLifecycle.apply)

  /** 使用固定治理策略减少业务 ZLayer 样板。 */
  def configured(policy: MemoryGovernancePolicy): URLayer[MemoryExtractor & MemoryStore, MemoryLifecycle] =
    ZLayer.succeed(policy) >>> layer
