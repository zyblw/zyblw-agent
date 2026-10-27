package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 长期记忆隔离域。
  *
  * Session 记忆只服务一次会话；User 必须同时绑定 tenant，防止相同 userId 跨租户串读；Tenant 只适合组织级共享事实。
  */
enum MemoryScope:
  case Session(sessionId: SessionId)
  case User(tenantId: TenantId, userId: UserId)
  case Tenant(tenantId: TenantId)

  /** 不含正文的稳定诊断标签，可用于内部错误和指标。 */
  def diagnostic: String = this match
    case MemoryScope.Session(sessionId)     => s"session:${sessionId.asString}"
    case MemoryScope.User(tenantId, userId) => s"user:${tenantId.value}:${userId.value}"
    case MemoryScope.Tenant(tenantId)       => s"tenant:${tenantId.value}"

/** 记忆的语义类别；支撑专属心智画像与认知长效记忆，Run State 和单纯聊天历史不应伪装成长期记忆。 */
enum MemoryKind:
  /** 志趣偏好：用户的学术流派倾向、研读志趣、审美与表达风格、交流语气偏好。 */
  case Preference

  /** 见解感悟：用户形成的核心认知、独到学术观点、读书心得、典籍顿悟与价值理念。 */
  case Semantic

  /** 近期探索：用户近期深入钻研的课题、阶段性阅读随感、未决学术疑问（带有时效性）。 */
  case Episodic

  /** 思维习惯：用户的认知推导路径、求索论证习惯、评论习惯与交流互动风格。 */
  case Procedural

/** 记忆内容的敏感级别，用于阻止模型推断的敏感信息被静默长期保存。 */
enum MemorySensitivity:
  case Public, Personal, Sensitive

/** 记忆证据来源；优先级由 `MemoryGovernancePolicy` 明确决定，不让 LLM 自行宣称可信。 */
enum MemoryEvidence:
  case UserStated, ToolObserved, Imported, ModelInferred

/** 一条已经进入 Store 的长期记忆。
  *
  * @param key
  *   scope 内稳定语义键，例如 `learning.preferred_classic`；不应把整句用户输入当 key
  * @param value
  *   结构化值；禁止保存 Provider 密钥和无需长期保留的完整对话
  * @param importance
  *   上下文选择权重，范围 [0,1]
  * @param sourceRunId
  *   产生或最近确认该事实的 Run，用于内部审计
  * @param createdAtEpochMilli
  *   首次创建时间
  * @param expiresAtEpochMilli
  *   可选失效时间；情节记忆和临时偏好应优先设置
  * @param kind
  *   语义类别
  * @param confidence
  *   证据置信度，范围 [0,1]
  * @param sensitivity
  *   敏感级别
  * @param evidence
  *   证据来源
  * @param extractorVersion
  *   提炼/导入策略版本；策略变化时便于重评
  * @param version
  *   Store 单调版本；写入请求可传 0，读取结果由 Store 填充
  * @param updatedAtEpochMilli
  *   最近确认或修改时间；写入 0 时 Store 使用当前时间
  */
final case class MemoryEntry(
    key: String,
    value: Json,
    importance: Double,
    sourceRunId: Option[RunId],
    createdAtEpochMilli: Long,
    expiresAtEpochMilli: Option[Long],
    kind: MemoryKind = MemoryKind.Semantic,
    confidence: Double = 1.0,
    sensitivity: MemorySensitivity = MemorySensitivity.Personal,
    evidence: MemoryEvidence = MemoryEvidence.UserStated,
    extractorVersion: String = "manual-v1",
    version: Long = 0L,
    updatedAtEpochMilli: Long = 0L
):
  require(key.trim.nonEmpty && key.length <= 200, "Memory key 长度必须位于 1..200")
  require(
    java.lang.Double.isFinite(importance) && importance >= 0.0 && importance <= 1.0,
    "importance 必须位于 [0,1]"
  )
  require(
    java.lang.Double.isFinite(confidence) && confidence >= 0.0 && confidence <= 1.0,
    "confidence 必须位于 [0,1]"
  )
  require(createdAtEpochMilli >= 0L && updatedAtEpochMilli >= 0L && version >= 0L, "Memory 时间与版本不能为负数")
  require(expiresAtEpochMilli.forall(_ > createdAtEpochMilli), "Memory 过期时间必须晚于创建时间")
  require(extractorVersion.trim.nonEmpty && extractorVersion.length <= 100, "extractorVersion 长度必须位于 1..100")

/** 作用域删除屏障快照。
  *
  * 提炼任务在捕获消息窗口时读取此快照；若任务完成提炼准备落库时，`scopeGeneration` 已递增（用户执行了 `deleteScope`） 或目标 key 的 tombstone
  * 版本高于捕获前快照（用户执行了单条 `delete`），治理层将拒绝迟到写入复活记忆。
  *
  * @param scopeGeneration
  *   `deleteScope` 单调递增代数
  * @param tombstoneVersions
  *   当前处于 deleted 状态各 key 的 tombstone 版本
  * @param activeVersions
  *   当前处于 active 状态各 key 的版本
  */
final case class MemoryDeletionBarrier(
    scopeGeneration: Long = 0L,
    tombstoneVersions: Map[String, Long] = Map.empty,
    activeVersions: Map[String, Long] = Map.empty
) derives JsonCodec:
  /** 判断自捕获 `captured` 快照以来，目标 key 或整个作用域是否发生过删除。 */
  def hasDeletedSince(captured: MemoryDeletionBarrier, key: String): Boolean =
    scopeGeneration > captured.scopeGeneration ||
      tombstoneVersions.get(key).exists { latestTombstoneVersion =>
        latestTombstoneVersion > captured.tombstoneVersions.getOrElse(key, 0L)
      }

  /** 生成用于 CAS 失败报告的冲突版本号。 */
  def conflictVersion(key: String, expectedVersion: Long): Long =
    activeVersions
      .get(key)
      .orElse(tombstoneVersions.get(key))
      .getOrElse(Math.max(1L, scopeGeneration))
      .max(expectedVersion + 1L)

object MemoryDeletionBarrier:
  val empty: MemoryDeletionBarrier = MemoryDeletionBarrier()

/** 长期记忆 Store。
  *
  * `put` 是后台导入/管理员使用的无条件 upsert；并发提炼必须使用 `compareAndSet`，用户删除后迟到 worker 也只有在 持有新 tombstone version
  * 时才能重新创建。`search/list` 永远不返回 tombstone 或已经过期的内容。
  */
trait MemoryStore:
  /** 无条件创建或更新并递增版本；不适合基于旧快照的 read-modify-write。 */
  def put(scope: MemoryScope, entry: MemoryEntry): IO[StoreError, Unit]

  /** 只有当前版本等于 expectedVersion 时更新；首次创建使用 expectedVersion=0。 */
  def compareAndSet(
      scope: MemoryScope,
      expectedVersion: Long,
      entry: MemoryEntry
  ): IO[StoreError, MemoryEntry]

  /** 原子检查删除屏障与 expectedVersion 并写入；默认实现兼容仅覆写三参 `compareAndSet` 的测试包装器。 */
  def compareAndSet(
      scope: MemoryScope,
      expectedVersion: Long,
      entry: MemoryEntry,
      expectedBarrier: Option[MemoryDeletionBarrier]
  ): IO[StoreError, MemoryEntry] =
    if entry.key.trim == MemoryStore.ScopeBarrierKey then
      ZIO.fail(AgentError.MemoryPolicyRejected(entry.key, "reserved-memory-key"))
    else
      expectedBarrier match
        case None          => compareAndSet(scope, expectedVersion, entry)
        case Some(barrier) =>
          currentBarrier(scope).flatMap { before =>
            if before.hasDeletedSince(barrier, entry.key) then
              ZIO.fail(
                AgentError.MemoryVersionConflict(
                  scope.diagnostic,
                  entry.key,
                  expectedVersion,
                  before.conflictVersion(entry.key, expectedVersion)
                )
              )
            else
              compareAndSet(scope, expectedVersion, entry).flatMap { written =>
                currentBarrier(scope).flatMap { after =>
                  if after.hasDeletedSince(barrier, entry.key) then
                    val conflictError = AgentError.MemoryVersionConflict(
                      scope.diagnostic,
                      entry.key,
                      expectedVersion,
                      after.conflictVersion(entry.key, expectedVersion)
                    )
                    val stillMatchesWrittenVersion =
                      after.activeVersions.get(entry.key).forall(_ == written.version) &&
                        after.tombstoneVersions.getOrElse(entry.key, 0L) < written.version
                    val compensate =
                      if !stillMatchesWrittenVersion then ZIO.unit
                      else
                        get(scope, entry.key).flatMap {
                          case Some(current) if current.version == written.version =>
                            delete(scope, entry.key)
                          case _ =>
                            ZIO.unit
                        }
                    compensate *> ZIO.fail(conflictError)
                  else ZIO.succeed(written)
                }
              }
          }

  /** 精确读取 active 且未过期的记忆。 */
  def get(scope: MemoryScope, key: String): IO[StoreError, Option[MemoryEntry]]

  /** 在 scope 内按 query 搜索，结果按相关性、importance、confidence 和 key 稳定排序。 */
  def search(scope: MemoryScope, query: String, limit: Int): IO[StoreError, Chunk[MemoryEntry]]

  /** 无 query 分页前的最小列表能力，按更新时间倒序、key 升序返回。 */
  def list(scope: MemoryScope, limit: Int): IO[StoreError, Chunk[MemoryEntry]]

  /** 将单条内容清空为 tombstone 并递增版本；重复删除保持幂等。 */
  def delete(scope: MemoryScope, key: String): IO[StoreError, Unit]

  /** 用户“删除我的记忆”使用的 scope 级 tombstone；返回本次新删除数量。 */
  def deleteScope(scope: MemoryScope): IO[StoreError, Long]

  /** 把到期 active 行转换为 tombstone，限制单批数量以避免长期锁表。 */
  def purgeExpired(nowEpochMilli: Long, limit: Int): IO[StoreError, Long]

  /** 读取当前作用域的删除屏障快照。 */
  def currentBarrier(@scala.annotation.unused scope: MemoryScope): IO[StoreError, MemoryDeletionBarrier] =
    ZIO.succeed(MemoryDeletionBarrier.empty)

object MemoryStore:
  /** 内部用于持久化记录 `deleteScope` 代数的保留 sentinel key，永不作为业务记忆返回。 */
  val ScopeBarrierKey: String = "__scope_barrier__"

  /** 内存实现也保留 tombstone/version 及历史最大删除版本，确保单键删后重建不会抹除删除历史。 */
  final private case class Stored(
      entry: Option[MemoryEntry],
      version: Long,
      deletedAtEpochMilli: Option[Long],
      lastDeletedVersion: Long = 0L
  )

  val inMemory: ULayer[MemoryStore] = ZLayer.fromZIO {
    Ref.Synchronized.make(Map.empty[(MemoryScope, String), Stored]).map { ref =>
      new MemoryStore:
        /** 无条件 upsert，并使用 TestClock/Live Clock 生成更新时间。 */
        def put(scope: MemoryScope, entry: MemoryEntry): IO[StoreError, Unit] =
          if entry.key.trim == MemoryStore.ScopeBarrierKey then
            ZIO.fail(AgentError.MemoryPolicyRejected(entry.key, "reserved-memory-key"))
          else
            Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
              ref.update { current =>
                val key         = scope -> entry.key
                val existing    = current.get(key)
                val version     = existing.fold(1L)(_.version + 1L)
                val lastDeleted = existing.fold(0L)(_.lastDeletedVersion)
                current.updated(
                  key,
                  Stored(Some(normalize(entry, version, now)), version, None, lastDeleted)
                )
              }
            }

        /** 原子检查版本并写入，冲突返回 typed StoreError。 */
        def compareAndSet(scope: MemoryScope, expectedVersion: Long, entry: MemoryEntry)
            : IO[StoreError, MemoryEntry] =
          compareAndSet(scope, expectedVersion, entry, None)

        /** 在同一互斥临界区内原子校验删除屏障与 expectedVersion。 */
        override def compareAndSet(
            scope: MemoryScope,
            expectedVersion: Long,
            entry: MemoryEntry,
            expectedBarrier: Option[MemoryDeletionBarrier]
        ): IO[StoreError, MemoryEntry] =
          if entry.key.trim == MemoryStore.ScopeBarrierKey then
            ZIO.fail(AgentError.MemoryPolicyRejected(entry.key, "reserved-memory-key"))
          else if expectedVersion < 0L then
            ZIO.fail(AgentError.MemoryPolicyRejected(entry.key, "negative-expected-version"))
          else
            Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
              ref.modifyZIO { current =>
                val key      = scope -> entry.key
                val existing = current.get(key)
                val actual   = existing.fold(0L)(_.version)
                val barrier  = snapshotBarrier(current, scope)
                if expectedBarrier.exists(captured => barrier.hasDeletedSince(captured, entry.key)) then
                  ZIO.fail(
                    AgentError.MemoryVersionConflict(
                      scope.diagnostic,
                      entry.key,
                      expectedVersion,
                      barrier.conflictVersion(entry.key, expectedVersion)
                    )
                  )
                else if actual != expectedVersion then
                  ZIO.fail(
                    AgentError.MemoryVersionConflict(scope.diagnostic, entry.key, expectedVersion, actual)
                  )
                else
                  val next        = actual + 1L
                  val lastDeleted = existing.fold(0L)(_.lastDeletedVersion)
                  val updated     = normalize(entry, next, now)
                  ZIO.succeed(
                    updated -> current.updated(key, Stored(Some(updated), next, None, lastDeleted))
                  )
              }
            }

        /** 精确读取并在读取时排除过期内容与保留 key。 */
        def get(scope: MemoryScope, key: String): UIO[Option[MemoryEntry]] =
          if key.trim == MemoryStore.ScopeBarrierKey then ZIO.none
          else
            Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).zipWith(ref.get) { (now, current) =>
              current.get(scope -> key).flatMap(_.entry).filter(_.expiresAtEpochMilli.forall(_ > now))
            }

        /** 确定性 substring 搜索；生产 PostgreSQL 实现另外使用全文向量。 */
        def search(scope: MemoryScope, query: String, limit: Int): UIO[Chunk[MemoryEntry]] =
          if limit <= 0 || query.trim.isEmpty then ZIO.succeed(Chunk.empty)
          else
            Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).zipWith(ref.get) { (now, all) =>
              val normalizedQuery = query.trim.toLowerCase
              val matches         = activeEntries(all, scope, now).filter { entry =>
                entry.key.toLowerCase.contains(normalizedQuery) || entry.value.toJson.toLowerCase
                  .contains(normalizedQuery)
              }
              Chunk.fromIterable(
                matches.sortBy(entry => (-entry.importance, -entry.confidence, entry.key)).take(limit)
              )
            }

        /** 按更新时间倒序列出 active 内容。 */
        def list(scope: MemoryScope, limit: Int): UIO[Chunk[MemoryEntry]] =
          if limit <= 0 then ZIO.succeed(Chunk.empty)
          else
            Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).zipWith(ref.get) { (now, all) =>
              Chunk.fromIterable(
                activeEntries(all, scope, now)
                  .sortBy(entry => (-entry.updatedAtEpochMilli, entry.key))
                  .take(limit)
              )
            }

        /** 删除保留版本 tombstone，不让旧 expectedVersion 的 worker 复活内容。 */
        def delete(scope: MemoryScope, key: String): IO[StoreError, Unit] =
          if key.trim == MemoryStore.ScopeBarrierKey then
            ZIO.fail(AgentError.MemoryPolicyRejected(key, "reserved-memory-key"))
          else if key.trim.isEmpty || key.length > 200 then ZIO.unit
          else
            Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
              ref.update { current =>
                val storageKey = scope -> key
                current.get(storageKey) match
                  case Some(stored) if stored.entry.nonEmpty =>
                    val nextVer = stored.version + 1L
                    current.updated(storageKey, Stored(None, nextVer, Some(now), nextVer))
                  case Some(_) =>
                    current
                  case None =>
                    current.updated(storageKey, Stored(None, 1L, Some(now), 1L))
              }
            }

        /** 原子 tombstone 一个 scope 的所有 active 行，并推进 scope 级删除屏障代数。 */
        def deleteScope(scope: MemoryScope): UIO[Long] =
          Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
            ref.modify { current =>
              var deleted = 0L
              val updated = current.map { case (key @ (entryScope, entryKey), stored) =>
                if entryScope == scope && entryKey != MemoryStore.ScopeBarrierKey && stored.entry.nonEmpty
                then
                  deleted += 1L
                  val nextVer = stored.version + 1L
                  key -> Stored(None, nextVer, Some(now), nextVer)
                else key -> stored
              }
              val barrierKey  = scope -> MemoryStore.ScopeBarrierKey
              val nextBarrier = updated.get(barrierKey).fold(1L)(_.version + 1L)
              deleted -> updated.updated(barrierKey, Stored(None, nextBarrier, Some(now), nextBarrier))
            }
          }

        /** 读取当前作用域的删除屏障快照。 */
        override def currentBarrier(scope: MemoryScope): UIO[MemoryDeletionBarrier] =
          ref.get.map(snapshotBarrier(_, scope))

        private def snapshotBarrier(
            current: Map[(MemoryScope, String), Stored],
            scope: MemoryScope
        ): MemoryDeletionBarrier =
          val scopeGeneration =
            current.get(scope -> MemoryStore.ScopeBarrierKey).fold(0L)(_.version)
          val tombstones = current.iterator.collect {
            case ((entryScope, key), stored)
                if entryScope == scope && key != MemoryStore.ScopeBarrierKey &&
                  (stored.entry.isEmpty || stored.lastDeletedVersion > 0L) =>
              val deletedVer =
                if stored.entry.isEmpty then Math.max(stored.version, stored.lastDeletedVersion)
                else stored.lastDeletedVersion
              key -> deletedVer
          }.toMap
          val actives = current.iterator.collect {
            case ((entryScope, key), Stored(Some(_), version, _, _))
                if entryScope == scope && key != MemoryStore.ScopeBarrierKey =>
              key -> version
          }.toMap
          MemoryDeletionBarrier(
            scopeGeneration = scopeGeneration,
            tombstoneVersions = tombstones,
            activeVersions = actives
          )

        /** 每批只 tombstone limit 条到期行，并按 scope/key 排序保证测试确定。 */
        def purgeExpired(nowEpochMilli: Long, limit: Int): UIO[Long] =
          if limit <= 0 then ZIO.succeed(0L)
          else
            ref.modify { current =>
              val keys = current.iterator
                .collect {
                  case (key @ (_, entryKey), Stored(Some(entry), _, _, _))
                      if entryKey != MemoryStore.ScopeBarrierKey && entry.expiresAtEpochMilli.exists(
                        _ <= nowEpochMilli
                      ) =>
                    key
                }
                .toList
                .sortBy { case (scope, key) => scope.diagnostic -> key }
                .take(limit)
              val updated = keys.foldLeft(current) { (acc, key) =>
                val stored  = acc(key)
                val nextVer = stored.version + 1L
                acc.updated(key, Stored(None, nextVer, Some(nowEpochMilli), nextVer))
              }
              keys.length.toLong -> updated
            }

        /** 填充 Store 管理的版本和更新时间，同时保留首次 createdAt。 */
        private def normalize(entry: MemoryEntry, version: Long, now: Long): MemoryEntry =
          entry.copy(
            version = version,
            updatedAtEpochMilli = if entry.updatedAtEpochMilli == 0L then now else entry.updatedAtEpochMilli
          )

        /** 提取指定 scope 当前 active、未过期内容。 */
        private def activeEntries(
            all: Map[(MemoryScope, String), Stored],
            scope: MemoryScope,
            now: Long
        ): List[MemoryEntry] =
          all.iterator.collect {
            case ((entryScope, entryKey), Stored(Some(entry), _, _, _))
                if entryScope == scope && entryKey != MemoryStore.ScopeBarrierKey && entry.expiresAtEpochMilli
                  .forall(_ > now) =>
              entry
          }.toList
    }
  }
