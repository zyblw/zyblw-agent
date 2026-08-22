package com.zyblw.agent.artifacts

import java.nio.file.Files
import zio.*
import zio.test.*

object ArtifactBlobStoreSpec extends ZIOSpecDefault:
  private val payload = Chunk.fromArray("artifact-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8))
  private val digest  = ArtifactBlobStore.digest(payload)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ArtifactBlobStore")(
    test("内存存储按 digest 去重，错误 digest 被拒绝") {
      (for
        store <- ZIO.service[ArtifactBlobStore]
        _     <- store.put(digest, payload)
        got   <- store.get(digest)
        bad   <- store.put("0" * 64, payload).exit
        path  <- store.get("../secret").exit
      yield assertTrue(
        got.contains(payload),
        bad.isFailure,
        path.isFailure
      )).provide(ArtifactBlobStore.inMemory)
    },
    test("本地目录按 digest 分片写入，且不能用相对路径逃逸") {
      ZIO.scoped {
        for
          root <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("zyblw-blob-"))) { path =>
            ZIO.attemptBlocking {
              Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach { child =>
                Files.deleteIfExists(child)
                ()
              }
            }.orDie
          }
          store = LocalArtifactBlobStore(root)
          _      <- store.put(digest, payload)
          got    <- store.get(digest)
          escape <- store.get("../secret").exit
          stored = ArtifactBlobStore.objectPath(root.toAbsolutePath.normalize, digest)
        yield assertTrue(
          got.contains(payload),
          escape.isFailure,
          stored.normalize.startsWith(root.toAbsolutePath.normalize)
        )
      }
    }
  )
