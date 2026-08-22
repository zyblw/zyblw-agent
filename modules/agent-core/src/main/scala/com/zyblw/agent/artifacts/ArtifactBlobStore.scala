package com.zyblw.agent.artifacts

import com.zyblw.agent.core.*
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import zio.*

/** 内容寻址对象存储。PostgreSQL 只应保存 metadata；大二进制走本 SPI。 */
trait ArtifactBlobStore:
  def put(sha256: String, bytes: Chunk[Byte]): IO[StoreError, Unit]
  def get(sha256: String): IO[StoreError, Option[Chunk[Byte]]]

final class LocalArtifactBlobStore(root: Path) extends ArtifactBlobStore:
  private val base                                                  = root.toAbsolutePath.normalize
  def put(sha256: String, bytes: Chunk[Byte]): IO[StoreError, Unit] =
    for
      digest <- ArtifactBlobStore.requireDigest(sha256)
      _      <- ZIO
        .fail(AgentError.ArtifactPolicyRejected("blob", "digest-mismatch"))
        .unless(ArtifactBlobStore.digest(bytes) == digest)
      path = ArtifactBlobStore.objectPath(base, digest)
      _ <- ZIO
        .attemptBlockingInterrupt {
          Files.createDirectories(path.getParent)
          val tmp = path.resolveSibling(s".${digest}.tmp")
          Files.write(tmp, bytes.toArray, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
          try Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          catch
            case _: java.nio.file.AtomicMoveNotSupportedException =>
              Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        .mapError(error =>
          AgentError.PersistenceFailure(s"artifact blob write failed: ${error.getClass.getSimpleName}")
        )
    yield ()

  def get(sha256: String): IO[StoreError, Option[Chunk[Byte]]] =
    ArtifactBlobStore.requireDigest(sha256).flatMap { digest =>
      val path = ArtifactBlobStore.objectPath(base, digest)
      ZIO
        .attemptBlockingInterrupt {
          if Files
              .exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          then Some(Chunk.fromArray(Files.readAllBytes(path)))
          else None
        }
        .mapError(error =>
          AgentError.PersistenceFailure(s"artifact blob read failed: ${error.getClass.getSimpleName}")
        )
    }

object ArtifactBlobStore:
  def digest(bytes: Chunk[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes.toArray)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  def requireDigest(sha256: String): IO[StoreError, String] =
    ZIO
      .fail(AgentError.ArtifactPolicyRejected("blob", "invalid-content-digest"))
      .unless(sha256.matches("[0-9a-f]{64}"))
      .as(sha256)

  def objectPath(root: Path, digest: String): Path =
    root.resolve(digest.take(2)).resolve(digest.drop(2))

  /** 进程内内容寻址；相同 digest 只保留一份。 */
  def inMemory: ULayer[ArtifactBlobStore] =
    ZLayer.fromZIO {
      Ref.Synchronized.make(Map.empty[String, Chunk[Byte]]).map { ref =>
        new ArtifactBlobStore:
          def put(sha256: String, bytes: Chunk[Byte]): IO[StoreError, Unit] =
            requireDigest(sha256).flatMap { digest =>
              ZIO
                .fail(AgentError.ArtifactPolicyRejected("blob", "digest-mismatch"))
                .unless(ArtifactBlobStore.digest(bytes) == digest) *>
                ref.update(_.updated(digest, bytes)).unit
            }

          def get(sha256: String): IO[StoreError, Option[Chunk[Byte]]] =
            requireDigest(sha256).flatMap(digest => ref.get.map(_.get(digest)))
      }
    }

  /** 根目录下按 digest 前两字节分片存储；拒绝非 SHA-256 键。 */
  def localDirectory(root: Path): ULayer[ArtifactBlobStore] =
    ZLayer.succeed(LocalArtifactBlobStore(root))
