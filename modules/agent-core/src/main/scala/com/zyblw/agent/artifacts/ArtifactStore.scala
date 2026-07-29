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
  require(sha256.matches("[0-9a-f]{64}"), "Artifact sha256 必须是小写 SHA-256")

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

/** 用于管理大对象引用的 provider-neutral SPI。
  *
  * 每次 `save` 都是 append-only 的新版本；调用方可以稳定读取任意历史 version，或不传 version 读取当前最新值。Store 不接收 `RunContext`，
  * 因此授权必须留在宿主领域层，和 `MemoryStore` 一样不能由模型决定 scope。
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

  /** 有界列出每个名称的最新版本，按名称稳定排序；二进制正文永不出现在列表结果。 */
  def list(scope: ArtifactScope, limit: Int): IO[StoreError, Chunk[ArtifactDescriptor]]

object ArtifactStore:
  final private case class Stored(versions: Vector[Artifact])

  /** 可确定性测试的内存 Adapter。
    *
    * 它完整保留不可变版本、隔离、配额和内容哈希语义，但不适合进程重启后的耐久保存。生产对象存储/PostgreSQL 元数据 Adapter 必须保持 相同的 append-only 读取契约。
    */
  def inMemory(policy: ArtifactStorePolicy = ArtifactStorePolicy()): ULayer[ArtifactStore] =
    ZLayer.fromZIO {
      Ref.Synchronized.make(Map.empty[(ArtifactScope, ArtifactName), Stored]).map { ref =>
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
                  val existing = current.get(key)
                  if existing.isEmpty && current.keysIterator.count(
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
                    Right(descriptor) -> current.updated(key, stored)
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
              ref.get.map(_.get(scope -> name).flatMap { stored =>
                version match
                  case Some(value) => stored.versions.find(_.descriptor.version == value)
                  case None        => stored.versions.lastOption
              })

          def list(scope: ArtifactScope, limit: Int): UIO[Chunk[ArtifactDescriptor]] =
            if limit <= 0 then ZIO.succeed(Chunk.empty)
            else
              ref.get.map { all =>
                Chunk.fromIterable(
                  all.iterator
                    .collect {
                      case ((artifactScope, _), stored) if artifactScope == scope =>
                        stored.versions.last.descriptor
                    }
                    .toList
                    .sortBy(_.name.value)
                    .take(limit)
                )
              }
      }
    }

  private def validate(
      name: ArtifactName,
      input: ArtifactInput,
      policy: ArtifactStorePolicy
  ): IO[AgentError.ArtifactPolicyRejected, Unit] =
    val normalizedMediaType = input.mediaType.trim
    val validMediaType      =
      normalizedMediaType.length <= 127 &&
        normalizedMediaType.count(_ == '/') == 1 &&
        normalizedMediaType.indexOf('/') > 0 &&
        normalizedMediaType.lastIndexOf('/') < normalizedMediaType.length - 1 &&
        !normalizedMediaType.exists(_.isControl)
    val sensitiveKeys =
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
