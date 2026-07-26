package com.zyblw.agent.memory

import com.zyblw.agent.core.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json

/** 用户长期记忆治理使用的稳定动作类型。
  *
  * 这里不使用任意字符串，是因为动作名最终会进入审计表、指标和合规导出；枚举可以避免不同调用方写出 `remove`、`delete`、`forget`
  * 等语义相同但无法聚合的值。读取动作与写入动作分开，便于部署时设置不同的 保留期和告警规则。
  */
enum MemoryAuditAction:
  /** 精确查看一条记忆。 */
  case Read

  /** 查看自己的记忆列表；审计不保存列表正文。 */
  case List

  /** 搜索自己的记忆；审计不保存搜索词。 */
  case Search

  /** 用户以 compare-and-set 方式纠正一条记忆。 */
  case Correct

  /** 用户删除一条记忆。 */
  case Delete

  /** 用户删除整个作用域中的记忆。 */
  case DeleteScope

  /** 后台保留期任务清理过期记忆。 */
  case RetentionPurge

/** 发起记忆操作的可信主体。
  *
  * `Authenticated` 必须由宿主认证中间件构造，不能从请求 JSON 或模型输出反序列化。`System` 只供框架自己的 retention worker
  * 使用，名称必须是部署时写死的低基数标识，不能放主机名、用户输入或工具参数。
  */
enum MemoryAuditActor:
  /** 已认证业务主体；只保留 tenantId/userId，刻意丢弃 scopes、attributes 和认证令牌。 */
  case Authenticated(tenantId: Option[String], userId: Option[String])

  /** 框架后台任务，例如 `memory-retention`。 */
  case System(name: String)

/** 不包含记忆正文和搜索词的不可变审计事实。
  *
  * @param auditId
  *   全局唯一审计 ID，可用于重试去重和问题追踪
  * @param action
  *   受控动作枚举
  * @param actor
  *   操作主体；生产 Adapter 会把它拆成低敏列
  * @param target
  *   被访问的权威 MemoryScope，不接受客户端自行拼接的 scopeKey
  * @param memoryKeyHash
  *   单条操作的 key SHA-256；列表、搜索和 scope 删除为空
  * @param expectedVersion
  *   纠正请求携带的 CAS 版本；其他动作为空
  * @param resultingVersion
  *   成功纠正后的版本；其他动作为空
  * @param affectedCount
  *   读取返回数量或写入实际影响数量
  * @param reasonCode
  *   稳定原因码，例如 retention 的 `expired`；不得写入原始错误或用户内容
  * @param occurredAt
  *   审计事实发生时间
  */
final case class MemoryAuditRecord(
    auditId: UUID,
    action: MemoryAuditAction,
    actor: MemoryAuditActor,
    target: MemoryScope,
    memoryKeyHash: Option[String],
    expectedVersion: Option[Long],
    resultingVersion: Option[Long],
    affectedCount: Long,
    reasonCode: Option[String],
    occurredAt: Instant
):
  require(affectedCount >= 0L, "Memory 审计 affectedCount 不能为负数")
  require(expectedVersion.forall(_ >= 0L) && resultingVersion.forall(_ > 0L), "Memory 审计版本必须为非负/正数")
  require(
    reasonCode.forall(value => value.nonEmpty && value.length <= 80),
    "Memory 审计 reasonCode 长度必须位于 1..80"
  )

/** 需要原子审计的治理型 Memory Repository。
  *
  * 普通 `MemoryStore` 仍负责模型提炼、上下文读取等内部能力；业务侧的用户纠正和删除必须走本接口。生产 PostgreSQL 实现保证数据变更与审计 INSERT
  * 位于同一数据库事务，因此不会出现“数据已删但审计丢失”。读取 没有数据变更，只要求成功读取后持久化审计；审计失败时服务不会把数据返回给调用方。
  */
trait MemoryGovernanceRepository:
  /** 写入一次成功读取的低敏审计事实。 */
  def recordRead(record: MemoryAuditRecord): IO[StoreError, Unit]

  /** 在同一原子边界中完成 CAS 纠正和审计。 */
  def correct(
      scope: MemoryScope,
      expectedVersion: Long,
      entry: MemoryEntry,
      audit: MemoryAuditRecord
  ): IO[StoreError, MemoryEntry]

  /** 在同一原子边界中完成单条 tombstone 和审计，返回实际删除数量 0 或 1。 */
  def delete(scope: MemoryScope, key: String, audit: MemoryAuditRecord): IO[StoreError, Long]

  /** 在同一原子边界中 tombstone 整个作用域并记录实际数量。 */
  def deleteScope(scope: MemoryScope, audit: MemoryAuditRecord): IO[StoreError, Long]

/** 用户纠正记忆时允许提交的最小字段集合。
  *
  * 用户不能伪造 evidence、confidence、extractorVersion、sourceRunId、createdAt 或 version；这些字段由服务从当前 权威记录和固定策略生成。这样 API
  * 即使直接暴露本类型，也不会允许调用者把模型推断伪装成用户确认事实。
  *
  * @param key
  *   scope 内稳定记忆键，必须与当前记录一致
  * @param expectedVersion
  *   用户页面最后读到的版本；用于阻止覆盖并发修改或删除
  * @param value
  *   用户确认后的结构化值
  * @param importance
  *   上下文选择权重，范围 [0,1]
  * @param kind
  *   用户确认后的语义类别
  * @param sensitivity
  *   用户明确选择的敏感等级；敏感内容仍应由上层产品策略决定是否允许长期保存
  * @param expiresAtEpochMilli
  *   可选过期时间；Episodic 类型必须设置
  */
final case class MemoryCorrection(
    key: String,
    expectedVersion: Long,
    value: Json,
    importance: Double,
    kind: MemoryKind,
    sensitivity: MemorySensitivity,
    expiresAtEpochMilli: Option[Long]
):
  require(key.trim.nonEmpty && key.length <= 200, "Memory correction key 长度必须位于 1..200")
  require(expectedVersion > 0L, "Memory correction expectedVersion 必须大于零")
  require(
    java.lang.Double.isFinite(importance) && importance >= 0.0 && importance <= 1.0,
    "importance 必须位于 [0,1]"
  )

/** 面向业务 API 的长期记忆治理服务。
  *
  * 授权在领域服务而非 HTTP Handler 中完成，保证未来 CLI、消息消费者或 ZIO HTTP Endpoint 都不能绕过同一规则。 普通用户只能访问与认证上下文完全一致的 User
  * scope；Session scope 没有独立所有权仓储，所以默认拒绝普通用户； Tenant scope 需要显式租户级 scope；跨用户访问则需要 admin scope。
  *
  * @param store
  *   读取当前 active 记忆的通用 Store
  * @param repository
  *   提供事务性治理变更和审计的 Repository
  * @param policy
  *   复用自动记忆的值大小、情节过期和敏感治理约束
  */
final class MemoryGovernanceService(
    store: MemoryStore,
    repository: MemoryGovernanceRepository,
    policy: MemoryGovernancePolicy
):
  import MemoryGovernanceService.*

  /** 精确查看一条记忆并记录审计。
    *
    * @param actor
    *   认证中间件提供的可信上下文
    * @param scope
    *   目标 scope；普通用户只能传自己的 User scope
    * @param key
    *   稳定记忆 key
    * @return
    *   active 且未过期的记忆；不存在返回 None
    */
  def get(actor: RunContext, scope: MemoryScope, key: String): IO[AgentError, Option[MemoryEntry]] =
    authorize(actor, scope, write = false) *>
      store.get(scope, key).flatMap { result =>
        auditRead(
          actor,
          scope,
          MemoryAuditAction.Read,
          keyHash = Some(hashKey(key)),
          result.fold(0L)(_ => 1L)
        ).as(result)
      }

  /** 有界列出自己的记忆；limit 会被限制在 1..MaxPageSize，避免治理页面成为无界数据导出接口。
    */
  def list(actor: RunContext, scope: MemoryScope, limit: Int): IO[AgentError, Chunk[MemoryEntry]] =
    for
      bounded <- validateLimit(limit)
      _       <- authorize(actor, scope, write = false)
      result  <- store.list(scope, bounded)
      _       <- auditRead(actor, scope, MemoryAuditAction.List, None, result.length.toLong)
    yield result

  /** 有界搜索自己的记忆。搜索词只用于 Store 查询，永不进入审计记录、错误 diagnostic 或日志。
    */
  def search(
      actor: RunContext,
      scope: MemoryScope,
      query: String,
      limit: Int
  ): IO[AgentError, Chunk[MemoryEntry]] =
    if query.trim.isEmpty then ZIO.fail(AgentError.MemoryPolicyRejected("search", "empty-query"))
    else
      for
        bounded <- validateLimit(limit)
        _       <- authorize(actor, scope, write = false)
        result  <- store.search(scope, query, bounded)
        _       <- auditRead(actor, scope, MemoryAuditAction.Search, None, result.length.toLong)
      yield result

  /** 用用户确认值 CAS 纠正一条现有记忆。
    *
    * 先读取当前记录是为了保留 createdAt/sourceRunId，并拒绝凭空创建新事实；真正并发安全仍由 Repository 的 expectedVersion 条件保证。读后到提交前若发生修改，事务性
    * Repository 会返回 MemoryVersionConflict。
    */
  def correct(
      actor: RunContext,
      scope: MemoryScope,
      correction: MemoryCorrection
  ): IO[AgentError, MemoryEntry] =
    for
      _       <- authorize(actor, scope, write = true)
      current <- store
        .get(scope, correction.key)
        .someOrFail(AgentError.MemoryNotFound(scope.diagnostic, correction.key))
      _ <- ZIO
        .fail(
          AgentError.MemoryVersionConflict(
            scope.diagnostic,
            correction.key,
            correction.expectedVersion,
            current.version
          )
        )
        .unless(current.version == correction.expectedVersion)
      now     <- Clock.instant
      _       <- validateCorrection(correction, current, now)
      auditId <- Random.nextUUID
      entry = current.copy(
        value = correction.value,
        importance = correction.importance,
        expiresAtEpochMilli = correction.expiresAtEpochMilli,
        kind = correction.kind,
        confidence = 1.0,
        sensitivity = correction.sensitivity,
        evidence = MemoryEvidence.UserStated,
        extractorVersion = UserCorrectionVersion,
        version = current.version,
        updatedAtEpochMilli = now.toEpochMilli
      )
      audit = MemoryAuditRecord(
        auditId,
        MemoryAuditAction.Correct,
        auditActor(actor),
        scope,
        Some(hashKey(correction.key)),
        Some(correction.expectedVersion),
        Some(correction.expectedVersion + 1L),
        1L,
        Some("user-confirmed"),
        now
      )
      result <- repository.correct(scope, correction.expectedVersion, entry, audit)
    yield result

  /** 删除一条记忆；不存在时保持幂等，但仍审计 affectedCount=0。 */
  def delete(actor: RunContext, scope: MemoryScope, key: String): IO[AgentError, Long] =
    for
      _       <- authorize(actor, scope, write = true)
      now     <- Clock.instant
      auditId <- Random.nextUUID
      audit = MemoryAuditRecord(
        auditId,
        MemoryAuditAction.Delete,
        auditActor(actor),
        scope,
        Some(hashKey(key)),
        None,
        None,
        0L,
        Some("user-requested"),
        now
      )
      affected <- repository.delete(scope, key, audit)
    yield affected

  /** 删除自己的整个 User scope；普通用户不能借此清空 Session 或 Tenant 共享记忆。 */
  def deleteScope(actor: RunContext, scope: MemoryScope): IO[AgentError, Long] =
    for
      _       <- authorize(actor, scope, write = true)
      now     <- Clock.instant
      auditId <- Random.nextUUID
      audit = MemoryAuditRecord(
        auditId,
        MemoryAuditAction.DeleteScope,
        auditActor(actor),
        scope,
        None,
        None,
        None,
        0L,
        Some("user-requested"),
        now
      )
      affected <- repository.deleteScope(scope, audit)
    yield affected

  /** 成功读取后写入低敏审计；失败时不把已读取正文返回给调用方。 */
  private def auditRead(
      actor: RunContext,
      scope: MemoryScope,
      action: MemoryAuditAction,
      keyHash: Option[String],
      affected: Long
  ): IO[StoreError, Unit] =
    Clock.instant.zipWith(Random.nextUUID)(_ -> _).flatMap { case (now, auditId) =>
      repository.recordRead(
        MemoryAuditRecord(
          auditId,
          action,
          auditActor(actor),
          scope,
          keyHash,
          None,
          None,
          affected,
          None,
          now
        )
      )
    }

  /** 授权规则默认拒绝；任何 admin 绕过都必须来自显式 scope。 */
  private def authorize(actor: RunContext, scope: MemoryScope, write: Boolean): IO[AgentError, Unit] =
    val adminScope = if write then ManageAdminScope else ReadAdminScope
    val admin      = actor.scopes.contains(adminScope) || actor.scopes.contains(ManageAdminScope)
    val allowed    = scope match
      case MemoryScope.User(tenantId, userId) =>
        admin || (actor.tenantId.contains(tenantId.value) && actor.userId.contains(userId.value))
      case MemoryScope.Tenant(tenantId) =>
        admin || (actor.tenantId.contains(tenantId.value) && actor.scopes.contains(
          if write then TenantManageScope else TenantReadScope
        ))
      case MemoryScope.Session(_) => admin
    if actor.tenantId.isEmpty && actor.userId.isEmpty then
      ZIO.fail(AgentError.MemoryAccessDenied(scope.diagnostic, "missing-authenticated-identity"))
    else if allowed then ZIO.unit
    else ZIO.fail(AgentError.MemoryAccessDenied(scope.diagnostic, if write then "manage" else "read"))

  /** 从认证上下文只投影低敏主体 ID；scopes/attributes 永不进入审计模型。 */
  private def auditActor(actor: RunContext): MemoryAuditActor =
    MemoryAuditActor.Authenticated(actor.tenantId, actor.userId)

  /** 统一限制治理页面查询规模。 */
  private def validateLimit(limit: Int): IO[AgentError, Int] =
    if limit >= 1 && limit <= MaxPageSize then ZIO.succeed(limit)
    else ZIO.fail(AgentError.MemoryPolicyRejected("page", s"limit-must-be-1..$MaxPageSize"))

  /** 复用生命周期核心约束，并额外要求 Episodic 纠正存在未来过期时间。 */
  private def validateCorrection(
      correction: MemoryCorrection,
      current: MemoryEntry,
      now: Instant
  ): IO[AgentError, Unit] =
    val jsonLength = correction.value.toJson.length
    if jsonLength > policy.maxValueCharacters then
      ZIO.fail(AgentError.MemoryPolicyRejected(correction.key, "value-too-large"))
    else if correction.kind == MemoryKind.Episodic && policy.requireEpisodicExpiry && correction.expiresAtEpochMilli.isEmpty
    then ZIO.fail(AgentError.MemoryPolicyRejected(correction.key, "episodic-expiry-required"))
    else if correction.expiresAtEpochMilli.exists(value =>
        value <= now.toEpochMilli || value <= current.createdAtEpochMilli
      )
    then ZIO.fail(AgentError.MemoryPolicyRejected(correction.key, "expiry-must-be-in-future"))
    else ZIO.unit

object MemoryGovernanceService:
  /** 跨用户读取记忆所需的管理员 scope。 */
  val ReadAdminScope: String = "agent:memory:read:admin"

  /** 跨用户纠正/删除记忆所需的管理员 scope。 */
  val ManageAdminScope: String = "agent:memory:manage:admin"

  /** 同租户读取 Tenant scope 记忆所需的 scope。 */
  val TenantReadScope: String = "agent:memory:tenant:read"

  /** 同租户修改 Tenant scope 记忆所需的 scope。 */
  val TenantManageScope: String = "agent:memory:tenant:manage"

  /** 治理接口单次最大返回数量。 */
  val MaxPageSize: Int = 200

  /** 用户纠正写入的固定策略版本。 */
  val UserCorrectionVersion: String = "user-correction-v1"

  /** 从 ZIO 环境装配治理服务。 */
  val layer
      : URLayer[MemoryStore & MemoryGovernanceRepository & MemoryGovernancePolicy, MemoryGovernanceService] =
    ZLayer.fromFunction(MemoryGovernanceService.apply)

  /** 对 key 计算固定小写 SHA-256；审计表不保存可能具有业务含义的原 key。 */
  def hashKey(key: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(key.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

/** TestKit/单进程开发使用的治理 Repository。
  *
  * Semaphore 把“MemoryStore 变更 + 内存审计追加”串行化；审计 Ref 本身不会失败，所以成功返回时两者一定同时可见。 生产环境必须使用数据库事务实现，不能把本实现当作跨进程一致性保证。
  */
final class InMemoryMemoryGovernanceRepository private (
    store: MemoryStore,
    audits: Ref[Chunk[MemoryAuditRecord]],
    gate: Semaphore
) extends MemoryGovernanceRepository:
  /** 读取当前审计事实，供确定性测试断言；业务 API 不应直接暴露全部记录。 */
  def records: UIO[Chunk[MemoryAuditRecord]] = audits.get

  def recordRead(record: MemoryAuditRecord): UIO[Unit] = audits.update(_ :+ record)

  def correct(
      scope: MemoryScope,
      expectedVersion: Long,
      entry: MemoryEntry,
      audit: MemoryAuditRecord
  ): IO[StoreError, MemoryEntry] = gate.withPermit {
    store.compareAndSet(scope, expectedVersion, entry).flatMap { updated =>
      audits.update(_ :+ audit.copy(resultingVersion = Some(updated.version), affectedCount = 1L)).as(updated)
    }
  }

  def delete(scope: MemoryScope, key: String, audit: MemoryAuditRecord): IO[StoreError, Long] =
    gate.withPermit {
      store.get(scope, key).flatMap { before =>
        store.delete(scope, key) *> audits
          .update(_ :+ audit.copy(affectedCount = before.fold(0L)(_ => 1L)))
          .as(before.fold(0L)(_ => 1L))
      }
    }

  def deleteScope(scope: MemoryScope, audit: MemoryAuditRecord): IO[StoreError, Long] = gate.withPermit {
    store.deleteScope(scope).flatMap(count => audits.update(_ :+ audit.copy(affectedCount = count)).as(count))
  }

object InMemoryMemoryGovernanceRepository:
  /** 为给定 MemoryStore 创建共享审计 Ref 和互斥边界。 */
  def make(store: MemoryStore): UIO[InMemoryMemoryGovernanceRepository] =
    for
      audits <- Ref.make(Chunk.empty[MemoryAuditRecord])
      gate   <- Semaphore.make(1L)
    yield InMemoryMemoryGovernanceRepository(store, audits, gate)
