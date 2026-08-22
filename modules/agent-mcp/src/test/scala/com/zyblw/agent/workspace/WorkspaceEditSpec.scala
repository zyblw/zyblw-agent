package com.zyblw.agent.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

object WorkspaceEditSpec extends ZIOSpecDefault:
  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("zyblw-edit-"))) { root =>
      ZIO.attemptBlocking {
        if Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
          val paths = Files.walk(root)
          try
            paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach { path =>
              Files.deleteIfExists(path)
              ()
            }
          finally paths.close()
      }.orDie
    }

  private def bytes(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))

  def spec = suite("WorkspaceEdit")(
    test("inspect/validate/apply/rollback 保持基线哈希，穿越路径进不了 patch") {
      ZIO.scoped {
        for
          root <- temporaryDirectory
          workspace = LocalWorkspace(root)
          path      = WorkspacePath("notes/draft.txt")
          _         <- workspace.write(path, bytes("v1"), overwrite = false)
          inspected <- WorkspaceEdit.inspect(workspace, path, 1024)
          stale = WorkspaceEdit.validate(inspected, WorkspacePatch(path, "0" * 64, bytes("v2")))
          ok <- ZIO.fromEither(
            WorkspaceEdit.validate(inspected, WorkspacePatch(path, inspected.sha256, bytes("v2")))
          )
          applied  <- WorkspaceEdit.apply(workspace, inspected, ok)
          after    <- workspace.read(path, 1024)
          _        <- WorkspaceEdit.rollback(workspace, applied)
          restored <- workspace.read(path, 1024)
        yield assertTrue(
          stale == Left("workspace-edit-stale-base"),
          new String(after.toArray, StandardCharsets.UTF_8) == "v2",
          new String(restored.toArray, StandardCharsets.UTF_8) == "v1",
          scala.util.Try(WorkspacePath("../secret")).isFailure
        )
      }
    }
  )
