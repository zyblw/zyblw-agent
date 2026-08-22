package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*
import java.security.MessageDigest
import java.time.Instant
import zio.*
import zio.json.*

/** Artifact 的可信隔离域。
  *
  * Session 与 User 两种域刻意复用长期 Memory 的隔离语义：用户级 Artifact 必须同时带 tenant，避免同名用户跨租户读写。此类型只描述 存储键；HTTP、CLI 或 Tool
  * Adapter 仍必须从已经认证的 `RunContext`/`AgentState` 推导它，不能接受模型或请求正文自报的域。
  */
enum ArtifactScope derives JsonCodec:
  case Session(sessionId: SessionId)
  case User(tenantId: TenantId, userId: UserId)

  /** 不含 Artifact 名称或正文的稳定诊断标签。 */
  def diagnostic: String = this match
    case ArtifactScope.Session(sessionId)     => s"session:${sessionId.asString}"
    case ArtifactScope.User(tenantId, userId) => s"user:${tenantId.value}:${userId.value}"

/** 交给 Artifact Store 的二进制与应用私有 metadata。
  *
  * Artifact 适合报告、图片、音频和其它不应塞进 `AgentState` 或模型 Context 的大对象。metadata 也可能含业务数据，不得自动投影到 Prompt、 telemetry 或公开
  * HTTP 响应。
  */
final case class ArtifactInput(
    bytes: Chunk[Byte],
    mediaType: String,
    metadata: Map[String, String] = Map.empty
)

/** 可安全作为工具结果、数据库引用或审计索引保存的 Artifact 描述符；刻意不携带二进制正文。 */
final case class ArtifactDescriptor(
    scope: ArtifactScope,
    name: ArtifactName,
    version: Long,
    mediaType: String,
    byteSize: Long,
    sha256: String,
    createdAt: Instant,
    metadata: Map[String, String]
) derives JsonCodec:
  require(version > 0L && byteSize >= 0L, "Artifact version 必须为正，byteSize 不能为负")
  require(ArtifactReference.validMediaType(mediaType), "Artifact mediaType 必须是有界 type/subtype")
  require(sha256.matches("[0-9a-f]{64}"), "Artifact sha256 必须是小写 SHA-256")

  /** 删除私有 metadata 与时间，只保留可耐久引用的不可变内容身份。 */
  def reference: ArtifactReference = ArtifactReference.fromDescriptor(this)

/** Artifact 的低敏、不可变引用。
  *
  * 引用不携带二进制、私有 metadata 或创建时间，也不授予读取权限。调用方仍必须从已认证上下文推导并授权 scope；sha256/大小/mediaType 用于读取后验证名称与版本没有被错误 Adapter
  * 重新绑定。
  */
final case class ArtifactReference(
    scope: ArtifactScope,
    name: ArtifactName,
    version: Long,
    mediaType: String,
    byteSize: Long,
    sha256: String
) derives JsonCodec:
  require(version > 0L && byteSize >= 0L, "ArtifactReference version 必须为正，byteSize 不能为负")
  require(ArtifactReference.validMediaType(mediaType), "ArtifactReference mediaType 必须是有界 type/subtype")
  require(sha256.matches("[0-9a-f]{64}"), "ArtifactReference sha256 必须是小写 SHA-256")

  /** 精确比较读取结果；metadata 与 createdAt 不属于引用身份。 */
  def matches(descriptor: ArtifactDescriptor): Boolean =
    scope == descriptor.scope &&
      name == descriptor.name &&
      version == descriptor.version &&
      mediaType == descriptor.mediaType &&
      byteSize == descriptor.byteSize &&
      sha256 == descriptor.sha256

object ArtifactReference:
  def fromDescriptor(descriptor: ArtifactDescriptor): ArtifactReference =
    ArtifactReference(
      descriptor.scope,
      descriptor.name,
      descriptor.version,
      descriptor.mediaType,
      descriptor.byteSize,
      descriptor.sha256
    )

  private[artifacts] def validMediaType(value: String): Boolean =
    value.length <= 127 &&
      value.count(_ == '/') == 1 &&
      value.indexOf('/') > 0 &&
      value.lastIndexOf('/') < value.length - 1 &&
      !value.exists(_.isControl)

/** 读取时才出现的二进制内容。它没有 JSON codec，防止框架把大对象意外嵌进运行状态或 HTTP 事件。 */
final case class Artifact(descriptor: ArtifactDescriptor, bytes: Chunk[Byte])

/** Artifact Store 的不可变对象限制。
  *
  * 配额按当前 scope 中不同名称计数；同名 `save` 创建新版本而不会消耗额外名称配额。物理删除、保留期和对象存储生命周期属于 Adapter 的后续 治理能力，本 SPI
  * 不把“隐藏旧版本”误称为安全删除。
  */
final case class ArtifactStorePolicy(
    maxArtifactBytes: Long = 16L * 1024L * 1024L,
    maxArtifactsPerScope: Int = 1_000,
    maxMetadataEntries: Int = 16,
    maxMetadataKeyCharacters: Int = 100,
    maxMetadataValueCharacters: Int = 500
):
  require(maxArtifactBytes > 0L, "Artifact maxArtifactBytes 必须大于零")
  require(maxArtifactsPerScope > 0, "Artifact maxArtifactsPerScope 必须大于零")
  require(maxMetadataEntries >= 0, "Artifact maxMetadataEntries 不能为负")
  require(maxMetadataKeyCharacters > 0 && maxMetadataValueCharacters > 0, "Artifact metadata 长度限制必须大于零")

/** Artifact 治理动作；审计行不得保存二进制或 metadata 正文。 */
enum ArtifactAuditAction:
  case Save, Read, Delete, Purge

/** 低敏 Artifact 审计事实。名称只保存 SHA-256，避免把业务文件名送进运维表。 */
final case class ArtifactAuditRecord(
    action: ArtifactAuditAction,
    scope: ArtifactScope,
    nameHash: String,
    version: Option[Long],
    reasonCode: String,
    occurredAt: Instant
):
  require(nameHash.matches("[0-9a-f]{64}"), "Artifact 审计 nameHash 必须是 SHA-256")
  require(reasonCode.nonEmpty && reasonCode.length <= 80, "Artifact 审计 reasonCode 长度必须位于 1..80")

/** 用于管理大对象引用的 provider-neutral SPI。
  *
  * 每次 `save` 都是 append-only 的新版本；调用方可以稳定读取任意历史 version，或不传 version 读取当前最新值。Store 不接收 `RunContext`，
  * 因此授权必须留在宿主领域层，和 `MemoryStore` 一样不能由模型决定 scope。
  *
  * 物理删除与保留期清理必须写低敏审计。默认实现拒绝删除，迫使生产 Adapter 显式实现而不是静默 no-op。
  */
trait ArtifactStore:
  /** 在 scope/name 下保存一个不可变版本，并返回不携带正文的描述符。 */
  def save(scope: ArtifactScope, name: ArtifactName, input: ArtifactInput): IO[StoreError, ArtifactDescriptor]

  /** 读取指定版本；`version=None` 返回最新版本；不存在时返回 None 而不把探测失败伪装成异常。 */
  def read(
      scope: ArtifactScope,
      name: ArtifactName,
      version: Option[Long] = None
  ): IO[StoreError, Option[Artifact]]

  /** 按不可变引用读取并复核 descriptor；不存在仍返回 None，内容身份漂移则 fail-closed。 */
  final def read(reference: ArtifactReference): IO[StoreError, Option[Artifact]] =
    read(reference.scope, reference.name, Some(reference.version)).flatMap {
      case Some(artifact) if reference.matches(artifact.descriptor) => ZIO.some(artifact)
      case Some(_) => ZIO.fail(AgentError.ArtifactPolicyRejected(reference.name.value, "reference-mismatch"))
      case None    => ZIO.none
    }

  /** 有界列出每个名称的最新版本，按名称稳定排序；二进制正文永不出现在列表结果。 */
  def list(scope: ArtifactScope, limit: Int): IO[StoreError, Chunk[ArtifactDescriptor]]

  /** 删除指定历史版本；禁止删除当前最新版本，避免把“覆盖”伪装成删除。 */
  def delete(scope: ArtifactScope, name: ArtifactName, version: Long): IO[StoreError, Long]

  /** 删除 cutoff 之前的历史版本，但始终保留每个名称的最新版本。 */
  def purgeExpired(cutoff: Instant, limit: Int): IO[StoreError, Long]

  /** 读取低敏审计，供 conformance 与值班核对；生产 HTTP 不得直接暴露全部记录。 */
  def audits(limit: Int): IO[StoreError, Chunk[ArtifactAuditRecord]]

object ArtifactStore:
  final private case class Stored(versions: Vector[Artifact])
  final private case class MemoryState(
      artifacts: Map[(ArtifactScope, ArtifactName), Stored],
      audits: Vector[ArtifactAuditRecord]
  )

  /** 可确定性测试的内存 Adapter。
    *
    * 它完整保留不可变版本、隔离、配额和内容哈希语义，但不适合进程重启后的耐久保存。生产对象存储/PostgreSQL 元数据 Adapter 必须保持 相同的 append-only 读取契约。
    */
  def inMemory(policy: ArtifactStorePolicy = ArtifactStorePolicy()): ULayer[ArtifactStore] =
    ZLayer.fromZIO {
      Ref.Synchronized.make(MemoryState(Map.empty, Vector.empty)).map { ref =>
        new ArtifactStore:
          def save(
              scope: ArtifactScope,
              name: ArtifactName,
              input: ArtifactInput
          ): IO[StoreError, ArtifactDescriptor] =
            validate(name, input, policy) *>
              Clock.instant.flatMap { now =>
                ref.modify { current =>
                  val key      = scope -> name
                  val existing = current.artifacts.get(key)
                  if existing.isEmpty && current.artifacts.keysIterator.count(
                      _._1 == scope
                    ) >= policy.maxArtifactsPerScope
                  then Left(AgentError.ArtifactPolicyRejected(name.value, "scope-artifact-limit")) -> current
                  else
                    val nextVersion = existing.fold(1L)(_.versions.last.descriptor.version + 1L)
                    val descriptor  = ArtifactDescriptor(
                      scope = scope,
                      name = name,
                      version = nextVersion,
                      mediaType = input.mediaType.trim.toLowerCase(java.util.Locale.ROOT),
                      byteSize = input.bytes.length.toLong,
                      sha256 = digest(input.bytes),
                      createdAt = now,
                      metadata = input.metadata
                    )
                    val artifact = Artifact(descriptor, input.bytes)
                    val stored   = Stored(existing.fold(Vector(artifact))(_.versions :+ artifact))
                    val audit    = ArtifactAuditRecord(
                      ArtifactAuditAction.Save,
                      scope,
                      hashName(name),
                      Some(nextVersion),
                      "append",
                      now
                    )
                    Right(descriptor) -> current.copy(
                      artifacts = current.artifacts.updated(key, stored),
                      audits = current.audits :+ audit
                    )
                }.absolve
              }

          def read(
              scope: ArtifactScope,
              name: ArtifactName,
              version: Option[Long]
          ): IO[StoreError, Option[Artifact]] =
            ZIO
              .fail(AgentError.ArtifactPolicyRejected(name.value, "invalid-version"))
              .when(version.exists(_ <= 0L)) *>
              Clock.instant.flatMap { now =>
                ref.modify { current =>
                  val found = current.artifacts.get(scope -> name).flatMap { stored =>
                    version match
                      case Some(value) => stored.versions.find(_.descriptor.version == value)
                      case None        => stored.versions.lastOption
                  }
                  val audit = ArtifactAuditRecord(
                    ArtifactAuditAction.Read,
                    scope,
                    hashName(name),
                    found.map(_.descriptor.version),
                    if found.isEmpty then "miss" else "hit",
                    now
                  )
                  found -> current.copy(audits = current.audits :+ audit)
                }
              }

          def list(scope: ArtifactScope, limit: Int): UIO[Chunk[ArtifactDescriptor]] =
            if limit <= 0 then ZIO.succeed(Chunk.empty)
            else
              ref.get.map { state =>
                Chunk.fromIterable(
                  state.artifacts.iterator
                    .collect {
                      case ((artifactScope, _), stored) if artifactScope == scope =>
                        stored.versions.last.descriptor
                    }
                    .toList
                    .sortBy(_.name.value)
                    .take(limit)
                )
              }

          def delete(scope: ArtifactScope, name: ArtifactName, version: Long): IO[StoreError, Long] =
            if version <= 0L then ZIO.fail(AgentError.ArtifactPolicyRejected(name.value, "invalid-version"))
            else
              Clock.instant.flatMap { now =>
                ref.modify { current =>
                  current.artifacts.get(scope -> name) match
                    case None =>
                      Right(0L) -> current.copy(audits =
                        current.audits :+ ArtifactAuditRecord(
                          ArtifactAuditAction.Delete,
                          scope,
                          hashName(name),
                          Some(version),
                          "missing",
                          now
                        )
                      )
                    case Some(stored) if stored.versions.last.descriptor.version == version =>
                      Left(AgentError.ArtifactPolicyRejected(name.value, "cannot-delete-latest")) -> current
                    case Some(stored) =>
                      val remaining = stored.versions.filterNot(_.descriptor.version == version)
                      if remaining.length == stored.versions.length then
                        Right(0L) -> current.copy(audits =
                          current.audits :+ ArtifactAuditRecord(
                            ArtifactAuditAction.Delete,
                            scope,
                            hashName(name),
                            Some(version),
                            "missing",
                            now
                          )
                        )
                      else
                        val next = current.copy(
                          artifacts = current.artifacts.updated(scope -> name, Stored(remaining)),
                          audits = current.audits :+ ArtifactAuditRecord(
                            ArtifactAuditAction.Delete,
                            scope,
                            hashName(name),
                            Some(version),
                            "user-requested",
                            now
                          )
                        )
                        Right(1L) -> next
                }.absolve
              }

          def purgeExpired(cutoff: Instant, limit: Int): IO[StoreError, Long] =
            if limit <= 0 then ZIO.succeed(0L)
            else
              Clock.instant.flatMap { now =>
                ref.modify { current =>
                  val expired = current.artifacts.toList
                    .flatMap { case (key, stored) =>
                      val latest = stored.versions.last.descriptor.version
                      stored.versions.collect {
                        case artifact
                            if artifact.descriptor.version != latest &&
                              !artifact.descriptor.createdAt.isAfter(cutoff) =>
                          key -> artifact.descriptor.version
                      }
                    }
                    .take(limit)
                  val nextArtifacts = expired.foldLeft(current.artifacts) { case (acc, (key, version)) =>
                    acc.get(key) match
                      case None         => acc
                      case Some(stored) =>
                        acc.updated(key, Stored(stored.versions.filterNot(_.descriptor.version == version)))
                  }
                  val audits = expired.map { case (key, version) =>
                    ArtifactAuditRecord(
                      ArtifactAuditAction.Purge,
                      key._1,
                      hashName(key._2),
                      Some(version),
                      "expired",
                      now
                    )
                  }
                  expired.length.toLong -> current.copy(
                    artifacts = nextArtifacts,
                    audits = current.audits ++ audits
                  )
                }
              }

          def audits(limit: Int): UIO[Chunk[ArtifactAuditRecord]] =
            ref.get.map(state => Chunk.fromIterable(state.audits.takeRight(math.max(limit, 0))))
      }
    }

  /** Adapter 与内存实现共用同一份输入治理，避免 PostgreSQL 静默接受内存会拒绝的对象。 */
  def validatePublic(
      name: ArtifactName,
      input: ArtifactInput,
      policy: ArtifactStorePolicy
  ): IO[AgentError.ArtifactPolicyRejected, Unit] = validate(name, input, policy)

  def digestPublic(bytes: Chunk[Byte]): String = digest(bytes)

  private def validate(
      name: ArtifactName,
      input: ArtifactInput,
      policy: ArtifactStorePolicy
  ): IO[AgentError.ArtifactPolicyRejected, Unit] =
    val normalizedMediaType = input.mediaType.trim
    val validMediaType      = ArtifactReference.validMediaType(normalizedMediaType)
    val sensitiveKeys       =
      Set("api_key", "apikey", "authorization", "password", "secret", "access_token", "refresh_token")
    val invalidMetadata = input.metadata.size > policy.maxMetadataEntries || input.metadata.exists {
      case (key, value) =>
        key.trim.isEmpty ||
        key.length > policy.maxMetadataKeyCharacters ||
        value.length > policy.maxMetadataValueCharacters ||
        key.exists(_.isControl) ||
        value.exists(_.isControl) ||
        sensitiveKeys.contains(key.trim.toLowerCase(java.util.Locale.ROOT))
    }
    val reason =
      Option
        .when(input.bytes.length.toLong > policy.maxArtifactBytes)("artifact-too-large")
        .orElse(Option.when(!validMediaType)("invalid-media-type"))
        .orElse(Option.when(invalidMetadata)("invalid-metadata"))
    ZIO.fail(AgentError.ArtifactPolicyRejected(name.value, reason.get)).when(reason.nonEmpty).unit

  private def digest(bytes: Chunk[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes.toArray)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  def hashName(name: ArtifactName): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(name.value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
