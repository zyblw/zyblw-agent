package com.zyblw.agent.rag

import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import zio.*

/** Embedding 调用用途；配额与观测可区分在线查询和离线索引。 */
enum EmbeddingPurpose:
  case Query, Indexing, Memory

/** 生产 Embedding 请求的可信治理上下文。
  * @param tenantId
  *   认证/业务层确定的租户，不能来自模型
  * @param purpose
  *   调用用途
  * @param requestId
  *   网络重试必须复用的稳定幂等 ID
  */
final case class EmbeddingRequestContext(tenantId: TenantId, purpose: EmbeddingPurpose, requestId: String):
  require(requestId.trim.nonEmpty && requestId.length <= 500, "Embedding requestId 长度必须位于 1..500")

/** 缓存键包含租户、用途、模型契约、算法版本和正文摘要，不保存原始正文。
  *
  * `purpose` 不能只用于配额。带 query/document instruction 的 embedding 模型会为相同正文按不同用途产生不同向量；若缓存键省略它， 查询向量可能错误复用索引向量。
  */
final case class EmbeddingCacheKey(
    tenantId: TenantId,
    purpose: EmbeddingPurpose,
    provider: String,
    model: String,
    dimension: Int,
    keyVersion: String,
    contentHash: String
)

/** 带绝对过期时间的缓存项。 */
final case class EmbeddingCacheEntry(key: EmbeddingCacheKey, embedding: Embedding, expiresAt: Instant)

/** 可替换缓存 SPI；生产 PostgreSQL/Redis 实现必须保持 tenantId 是主键的一部分。 */
trait EmbeddingCacheStore:
  /** 批量读取未过期向量；不存在项不返回。 */
  def get(keys: Chunk[EmbeddingCacheKey], now: Instant): IO[RetrievalError, Map[EmbeddingCacheKey, Embedding]]

  /** 幂等写入或刷新缓存项。 */
  def put(entries: Chunk[EmbeddingCacheEntry]): IO[RetrievalError, Unit]

  /** 分批删除已经过期的缓存项。
    *
    * @param now
    *   由调用方提供的判定时刻，便于使用 `TestClock` 做确定性测试
    * @param limit
    *   单次最多删除的行数；生产实现应避免一次清理持有大量行锁
    * @return
    *   实际删除数量；当返回值小于 `limit` 时，当前批次通常已经清空
    */
  def purgeExpired(now: Instant, limit: Int): IO[RetrievalError, Long]

object EmbeddingCacheStore:
  /** Ref.Synchronized 参考实现，用于测试和单进程部署。 */
  val inMemory: ULayer[EmbeddingCacheStore] = ZLayer.fromZIO {
    Ref.Synchronized.make(Map.empty[EmbeddingCacheKey, EmbeddingCacheEntry]).map { state =>
      new EmbeddingCacheStore:
        def get(keys: Chunk[EmbeddingCacheKey], now: Instant): UIO[Map[EmbeddingCacheKey, Embedding]] =
          state.modify { current =>
            val live = current.filter((_, entry) => entry.expiresAt.isAfter(now))
            keys.flatMap(key => live.get(key).map(entry => key -> entry.embedding)).toMap -> live
          }
        def put(entries: Chunk[EmbeddingCacheEntry]): UIO[Unit] =
          state.update(current => current ++ entries.map(entry => entry.key -> entry))
        def purgeExpired(now: Instant, limit: Int): UIO[Long] =
          if limit <= 0 then ZIO.succeed(0L)
          else
            state.modify { current =>
              val expired = current.valuesIterator
                .filter(entry => !entry.expiresAt.isAfter(now))
                .toVector
                .sortBy(entry => (entry.expiresAt, entry.key.tenantId.value, entry.key.contentHash))
                .take(limit)
                .map(_.key)
                .toSet
              expired.size.toLong -> current.removedAll(expired)
            }
    }
  }

/** 租户窗口配额；字符数是调用前可确定的硬门禁，不伪造 Provider token usage。 */
final case class EmbeddingQuotaPolicy(
    window: Duration = 1.day,
    maxRequests: Long = 10_000L,
    maxTexts: Long = 100_000L,
    maxCharacters: Long = 100_000_000L
):
  require(window > Duration.Zero, "Embedding 配额窗口必须为正")
  require(window.toMillis > 0L, "Embedding 配额窗口必须至少为 1 毫秒")
  require(maxRequests > 0L && maxTexts > 0L && maxCharacters > 0L, "Embedding 配额必须为正")

/** 一次只针对 Provider miss 的配额预留。 */
final case class EmbeddingQuotaReservation(
    context: EmbeddingRequestContext,
    requestHash: String,
    requests: Long,
    texts: Long,
    characters: Long
):
  require(requestHash.matches("[0-9a-f]{64}"), "Embedding requestHash 必须是小写 SHA-256")
  require(requests > 0L && texts > 0L && characters > 0L, "Embedding 配额预留计数必须为正")

/** 当前窗口已消费的确定性计数。 */
final case class EmbeddingQuotaUsage(requests: Long = 0L, texts: Long = 0L, characters: Long = 0L):
  require(requests >= 0L && texts >= 0L && characters >= 0L, "Embedding 配额用量不能为负数")

/** 原子配额 Store；reserve 必须让同 requestId+hash 幂等，不同 hash 冲突。 */
trait EmbeddingQuotaStore:
  /** 原子预留当前窗口额度；同一个 requestId/hash 的网络重试不得重复计费。 */
  def reserve(
      reservation: EmbeddingQuotaReservation,
      policy: EmbeddingQuotaPolicy,
      now: Instant
  ): IO[RetrievalError, EmbeddingQuotaUsage]

  /** 查询租户在 `now` 所属策略窗口中的确定性用量。 */
  def usage(
      tenantId: TenantId,
      policy: EmbeddingQuotaPolicy,
      now: Instant
  ): IO[RetrievalError, EmbeddingQuotaUsage]

  /** 删除结束时间不晚于 `endedBefore` 的窗口及其幂等预留。
    *
    * 清理必须同时删除 reservation；否则永久保留 requestId 会让未来合法请求发生伪冲突。
    */
  def purgeWindows(endedBefore: Instant, limit: Int): IO[RetrievalError, Long]

object EmbeddingQuotaStore:
  /** 窗口身份包含窗口长度，避免同租户的分钟配额与日配额错误共享计数。 */
  final private case class WindowKey(tenantId: TenantId, windowMillis: Long, windowStartMillis: Long)

  final private case class State(
      usage: Map[WindowKey, EmbeddingQuotaUsage] = Map.empty,
      reservations: Map[(TenantId, String), (String, WindowKey)] = Map.empty
  )

  /** 并发原子参考实现；同一窗口内检查与累加发生在一个同步 Ref 临界区。 */
  val inMemory: ULayer[EmbeddingQuotaStore] = ZLayer.fromZIO {
    Ref.Synchronized.make(State()).map { state =>
      new EmbeddingQuotaStore:
        def reserve(reservation: EmbeddingQuotaReservation, policy: EmbeddingQuotaPolicy, now: Instant) =
          state.modifyZIO { current =>
            val key   = windowKey(reservation.context.tenantId, policy, now)
            val idKey = reservation.context.tenantId -> reservation.context.requestId
            current.reservations.get(idKey) match
              case Some((hash, _)) if hash == reservation.requestHash =>
                ZIO.succeed(current.usage.getOrElse(key, EmbeddingQuotaUsage()) -> current)
              case Some(_) => ZIO.fail(AgentError.RetrievalFailed("Embedding requestId 已绑定不同请求"))
              case None    =>
                val previous = current.usage.getOrElse(key, EmbeddingQuotaUsage())
                // 用“当前值 > 上限 - 增量”比较，既表达硬上限，也避免 Long 加法溢出后绕过配额。
                val exceeded = firstExceeded(previous, reservation, policy)
                exceeded match
                  case Some((metric, limit)) => ZIO.fail(AgentError.EmbeddingQuotaExceeded(metric, limit))
                  case None                  =>
                    val next = EmbeddingQuotaUsage(
                      previous.requests + reservation.requests,
                      previous.texts + reservation.texts,
                      previous.characters + reservation.characters
                    )
                    ZIO.succeed(
                      next -> current.copy(
                        usage = current.usage.updated(key, next),
                        reservations = current.reservations.updated(idKey, reservation.requestHash -> key)
                      )
                    )
          }
        def usage(tenantId: TenantId, policy: EmbeddingQuotaPolicy, now: Instant) =
          val key = windowKey(tenantId, policy, now)
          state.get.map(_.usage.getOrElse(key, EmbeddingQuotaUsage()))
        def purgeWindows(endedBefore: Instant, limit: Int): UIO[Long] =
          if limit <= 0 then ZIO.succeed(0L)
          else
            state.modify { current =>
              val cutoff  = endedBefore.toEpochMilli
              val removed = current.usage.keysIterator
                .filter(key => key.windowStartMillis <= cutoff - key.windowMillis)
                .toVector
                .sortBy(key => (key.windowStartMillis, key.windowMillis, key.tenantId.value))
                .take(limit)
                .toSet
              val reservations = current.reservations.filterNot((_, value) => removed.contains(value._2))
              removed.size.toLong -> current.copy(current.usage.removedAll(removed), reservations)
            }
    }
  }

  /** 将任意 Instant 向下取整到策略窗口起点；`floorDiv` 对 1970 年前时间也保持数学语义。 */
  private def windowKey(tenantId: TenantId, policy: EmbeddingQuotaPolicy, now: Instant): WindowKey =
    val millis = policy.window.toMillis
    WindowKey(tenantId, millis, Math.floorDiv(now.toEpochMilli, millis) * millis)

  /** 按固定优先级返回第一个超限维度，使并发失败结果可以稳定断言。 */
  private def firstExceeded(
      current: EmbeddingQuotaUsage,
      increment: EmbeddingQuotaReservation,
      policy: EmbeddingQuotaPolicy
  ): Option[(String, Long)] =
    if current.requests > policy.maxRequests - increment.requests then Some("requests" -> policy.maxRequests)
    else if current.texts > policy.maxTexts - increment.texts then Some("texts" -> policy.maxTexts)
    else if current.characters > policy.maxCharacters - increment.characters then
      Some("characters" -> policy.maxCharacters)
    else None

enum CacheFailureMode:
  case FailOpen, FailClosed

/** 缓存 TTL、键版本和缓存故障策略。配额 Store 始终 fail-closed。 */
final case class GovernedEmbeddingConfig(
    cacheTtl: Duration = 7.days,
    cacheKeyVersion: String = "exact-utf8-v1",
    cacheFailureMode: CacheFailureMode = CacheFailureMode.FailOpen
):
  require(cacheTtl > Duration.Zero && cacheKeyVersion.trim.nonEmpty, "Embedding cache 配置无效")

/** 租户隔离、请求内去重、缓存和原子配额组成的生产 Embedding 门面。 原始 `embed/embedDetailed` 被显式拒绝，保证使用本门面时无法遗漏可信 scope。
  */
final class GovernedEmbeddingService(
    delegate: EmbeddingService,
    cache: EmbeddingCacheStore,
    quota: EmbeddingQuotaStore,
    quotaPolicy: EmbeddingQuotaPolicy,
    config: GovernedEmbeddingConfig
) extends EmbeddingService:
  override val dimension: Int                                           = delegate.dimension
  override val descriptor: EmbeddingProviderDescriptor                  = delegate.descriptor
  def embed(texts: Chunk[String]): IO[RetrievalError, Chunk[Embedding]] =
    ZIO.fail(AgentError.RetrievalFailed("GovernedEmbeddingService 必须使用 embedScoped 并提供租户上下文"))
  override def embedDetailed(texts: Chunk[String]): IO[RetrievalError, EmbeddingBatchResult] =
    ZIO.fail(AgentError.RetrievalFailed("GovernedEmbeddingService 必须使用 embedScoped 并提供租户上下文"))

  override def embedScoped(
      context: EmbeddingRequestContext,
      texts: Chunk[String]
  ): IO[RetrievalError, EmbeddingBatchResult] =
    if texts.isEmpty then ZIO.succeed(EmbeddingBatchResult(Chunk.empty))
    else if texts.exists(_.trim.isEmpty) then ZIO.fail(AgentError.RetrievalFailed("Embedding 文本不能为空"))
    else
      for
        now <- Clock.instant
        keyed  = texts.zipWithIndex.map { case (text, index) => (index, text, key(context, text)) }
        unique = Chunk.fromIterable(keyed.groupBy(_._3).values.map(_.minBy(_._1))).sortBy(_._1)
        cached <- cacheRead(unique.map(_._3), now)
        misses = unique.filterNot(item => cached.contains(item._3))
        generated <-
          if misses.isEmpty then ZIO.succeed(EmbeddingBatchResult(Chunk.empty))
          else reserveAndEmbed(context, misses, now)
        fresh = misses.map(_._3).zip(generated.embeddings).toMap
        _ <- cacheWrite(Chunk.fromIterable(fresh.map { case (cacheKey, embedding) =>
          EmbeddingCacheEntry(cacheKey, embedding, now.plusMillis(config.cacheTtl.toMillis))
        }))
        all = cached ++ fresh
        ordered <- ZIO.foreach(keyed)(item =>
          ZIO.fromOption(all.get(item._3)).orElseFail(AgentError.RetrievalFailed("Embedding 缓存重组缺失结果"))
        )
      yield generated.copy(embeddings = ordered)

  private def reserveAndEmbed(
      context: EmbeddingRequestContext,
      misses: Chunk[(Int, String, EmbeddingCacheKey)],
      now: Instant
  ): IO[RetrievalError, EmbeddingBatchResult] =
    val texts = misses.map(_._2)
    // 幂等指纹同时绑定用途和模型契约；同 requestId 即使正文相同，也不能跨用途/模型复用旧配额预留。
    val hash = sha256(
      s"${context.purpose}|${descriptor.provider}|${descriptor.model}|$dimension|${misses.map(_._3.contentHash).mkString("\n")}"
    )
    val characters  = texts.foldLeft(0L)((sum, text) => sum + text.codePointCount(0, text.length).toLong)
    val reservation = EmbeddingQuotaReservation(context, hash, 1L, texts.length.toLong, characters)
    quota.reserve(reservation, quotaPolicy, now) *>
      // 让 Provider 看见可信 purpose；例如带 instruction 的 Qwen/OpenAI-compatible Adapter 可据此分别格式化 query 与 document。
      delegate.embedScoped(context, texts).flatMap { result =>
        if result.embeddings.length != texts.length then
          ZIO.fail(AgentError.RetrievalFailed("Embedding Provider 输出数量与去重后输入不一致"))
        else if result.embeddings.exists(_.values.length != dimension) then
          ZIO.fail(AgentError.RetrievalFailed("Embedding Provider 输出维度漂移"))
        else ZIO.succeed(result)
      }

  private def cacheRead(keys: Chunk[EmbeddingCacheKey], now: Instant) =
    cache
      .get(keys, now)
      .catchAll(error =>
        if config.cacheFailureMode == CacheFailureMode.FailOpen then ZIO.succeed(Map.empty)
        else ZIO.fail(error)
      )

  private def cacheWrite(entries: Chunk[EmbeddingCacheEntry]) =
    cache
      .put(entries)
      .catchAll(error =>
        if config.cacheFailureMode == CacheFailureMode.FailOpen then ZIO.unit else ZIO.fail(error)
      )

  private def key(context: EmbeddingRequestContext, text: String): EmbeddingCacheKey =
    EmbeddingCacheKey(
      context.tenantId,
      context.purpose,
      descriptor.provider,
      descriptor.model,
      dimension,
      config.cacheKeyVersion,
      sha256(text)
    )

  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

object GovernedEmbeddingService:
  /** 从原始 Provider、缓存、配额 Store 和显式策略装配治理门面。 */
  def layer(
      quotaPolicy: EmbeddingQuotaPolicy = EmbeddingQuotaPolicy(),
      config: GovernedEmbeddingConfig = GovernedEmbeddingConfig()
  ): URLayer[EmbeddingService & EmbeddingCacheStore & EmbeddingQuotaStore, EmbeddingService] =
    ZLayer.fromFunction(
      (delegate: EmbeddingService, cache: EmbeddingCacheStore, quota: EmbeddingQuotaStore) =>
        GovernedEmbeddingService(delegate, cache, quota, quotaPolicy, config): EmbeddingService
    )
