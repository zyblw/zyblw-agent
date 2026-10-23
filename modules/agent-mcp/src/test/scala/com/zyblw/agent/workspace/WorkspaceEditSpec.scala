package com.zyblw.agent.workspace

import com.zyblw.agent.artifacts.ArtifactBlobStore
import com.zyblw.agent.core.AgentError
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
    },
    test("inspect 后文件被外部修改时，旧 patch 与过期回滚均被拒绝且不覆盖新内容") {
      ZIO.scoped {
        for
          root <- temporaryDirectory
          workspace = LocalWorkspace(root)
          path      = WorkspacePath("notes/concurrent.txt")
          _           <- workspace.write(path, bytes("v1"), overwrite = false)
          inspectedV1 <- WorkspaceEdit.inspect(workspace, path, 1024)
          patchFromV1 = WorkspacePatch(path, inspectedV1.sha256, bytes("stale-overwrite"))
          _           <- workspace.write(path, bytes("v2"), overwrite = true)
          staleApply  <- WorkspaceEdit.apply(workspace, inspectedV1, patchFromV1).exit
          afterStale  <- workspace.read(path, 1024)
          inspectedV2 <- WorkspaceEdit.inspect(workspace, path, 1024)
          appliedV2   <- WorkspaceEdit.apply(
            workspace,
            inspectedV2,
            WorkspacePatch(path, inspectedV2.sha256, bytes("v3-applied"))
          )
          _             <- workspace.write(path, bytes("v4-external-newer"), overwrite = true)
          staleRollback <- WorkspaceEdit.rollback(workspace, appliedV2).exit
          afterRollback <- workspace.read(path, 1024)
        yield assertTrue(
          staleApply == Exit.fail(AgentError.PermissionDenied("workspace.edit", "workspace-edit-stale-base")),
          new String(afterStale.toArray, StandardCharsets.UTF_8) == "v2",
          staleRollback == Exit.fail(
            AgentError.PermissionDenied("workspace.edit", "workspace-edit-stale-base")
          ),
          new String(afterRollback.toArray, StandardCharsets.UTF_8) == "v4-external-newer"
        )
      }
    },
    test("基于同一基线的并发 apply 只有一个成功，失败方不会覆盖胜出写入") {
      ZIO.scoped {
        for
          root <- temporaryDirectory
          workspace = LocalWorkspace(root)
          path      = WorkspacePath("notes/race.txt")
          _         <- workspace.write(path, bytes("v1"), overwrite = false)
          inspected <- WorkspaceEdit.inspect(workspace, path, 1024)
          patchA = WorkspacePatch(path, inspected.sha256, bytes("winner-a"))
          patchB = WorkspacePatch(path, inspected.sha256, bytes("winner-b"))
          results <- ZIO
            .foreachPar(Chunk(patchA, patchB))(patch => WorkspaceEdit.apply(workspace, inspected, patch).exit)
            .withParallelism(2)
          finalBytes <- workspace.read(path, 1024)
          finalText = new String(finalBytes.toArray, StandardCharsets.UTF_8)
          successes = results.collect { case Exit.Success(applied) => applied }
          failures  = results.collect { case Exit.Failure(cause) => cause.failureOption }
        yield assertTrue(
          successes.length == 1,
          failures == Chunk(Some(AgentError.PermissionDenied("workspace.edit", "workspace-edit-stale-base"))),
          (finalText == "winner-a" || finalText == "winner-b"),
          ArtifactBlobStore.digest(finalBytes) == successes.head.nextSha256
        )
      }
    }
  )
