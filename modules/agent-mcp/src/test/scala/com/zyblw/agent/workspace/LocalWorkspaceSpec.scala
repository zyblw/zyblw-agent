package com.zyblw.agent.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import zio.*
import zio.test.*

/** `LocalWorkspace` 的安全回归测试。
  *
  * 这些测试刻意覆盖路径穿越、符号链接逃逸、覆盖策略和容量配额，而不只验证正常读写。Workspace 是模型与 宿主文件系统之间的信任边界；若只测试 happy
  * path，很容易在后续重构时无意重新引入目录逃逸漏洞。
  */
object LocalWorkspaceSpec extends ZIOSpecDefault:

  /** 创建测试专用目录，并保证测试失败或 Fiber 被中断时仍递归回收。 */
  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory(prefix)))(deleteRecursively)

  /** 不跟随符号链接地清理测试目录。
    *
    * 路径按深度倒序删除，确保先删文件再删父目录；符号链接本身作为一个目录项删除，不会触及其外部目标。
    */
  private def deleteRecursively(root: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
        val paths = Files.walk(root)
        try
          paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach { path =>
            Files.deleteIfExists(path); ()
          }
        finally paths.close()
    }.orDie

  /** 将 UTF-8 文本转换为 Workspace 使用的二进制 Chunk，避免测试依赖平台默认字符集。 */
  private def bytes(value: String): Chunk[Byte] = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))

  def spec = suite("LocalWorkspace")(
    test("原子写入、读取、列举和删除普通文件") {
      ZIO.scoped {
        for
          root <- temporaryDirectory("zyblw-workspace-")
          workspace = LocalWorkspace(root)
          file      = WorkspacePath("notes/result.txt")
          _         <- workspace.write(file, bytes("中医学习笔记"), overwrite = false)
          content   <- workspace.read(file, maxBytes = 1024)
          children  <- workspace.list(WorkspacePath("notes"))
          _         <- workspace.delete(file)
          exists    <- ZIO.attemptBlocking(Files.exists(root.resolve("notes/result.txt")))
          leftovers <- ZIO.attemptBlocking {
            val stream = Files.list(root.resolve("notes"))
            try stream.iterator().asScala.exists(_.getFileName.toString.startsWith(".zyblw-write-"))
            finally stream.close()
          }
        yield assertTrue(
          new String(content.toArray, StandardCharsets.UTF_8) == "中医学习笔记",
          children == Chunk(file),
          !exists,
          !leftovers
        )
      }
    },
    test("值对象在进入文件 API 前拒绝穿越、空段和 Windows 分隔符") {
      val attempts = List("../secret", "a/../../secret", "a//b", "a/./b", "/absolute", "a\\b", "")
      assertTrue(attempts.forall(value => Try(WorkspacePath(value)).isFailure))
    },
    test("根目录内部的符号链接不能读取根目录外文件") {
      ZIO.scoped {
        for
          root    <- temporaryDirectory("zyblw-workspace-")
          outside <- temporaryDirectory("zyblw-outside-")
          secret = outside.resolve("secret.txt")
          _    <- ZIO.attemptBlocking(Files.writeString(secret, "do-not-read", StandardCharsets.UTF_8))
          _    <- ZIO.attemptBlocking(Files.createSymbolicLink(root.resolve("escape"), outside))
          exit <- LocalWorkspace(root).read(WorkspacePath("escape/secret.txt"), 1024).exit
        yield assertTrue(exit.isFailure)
      }
    },
    test("单文件和总容量配额都采用默认拒绝语义") {
      ZIO.scoped {
        for
          root <- temporaryDirectory("zyblw-workspace-")
          workspace = LocalWorkspace(root, WorkspacePolicy(maxFileBytes = 4, maxTotalBytes = 6))
          tooLarge   <- workspace.write(WorkspacePath("large.bin"), bytes("12345"), overwrite = false).exit
          _          <- workspace.write(WorkspacePath("first.bin"), bytes("1234"), overwrite = false)
          totalLimit <- workspace.write(WorkspacePath("second.bin"), bytes("123"), overwrite = false).exit
        yield assertTrue(tooLarge.isFailure, totalLimit.isFailure)
      }
    },
    test("overwrite=false 不覆盖既有业务文件") {
      ZIO.scoped {
        for
          root <- temporaryDirectory("zyblw-workspace-")
          workspace = LocalWorkspace(root)
          path      = WorkspacePath("stable.txt")
          _         <- workspace.write(path, bytes("old"), overwrite = false)
          overwrite <- workspace.write(path, bytes("new"), overwrite = false).exit
          content   <- workspace.read(path, 32)
        yield assertTrue(
          overwrite.isFailure,
          new String(content.toArray, StandardCharsets.UTF_8) == "old"
        )
      }
    },
    test("默认 SandboxExecutor 明确拒绝宿主命令执行") {
      val command = SandboxCommand("java", Chunk("-version"), WorkspacePath("work"))
      for
        executor <- ZIO.service[SandboxExecutor].provide(SandboxExecutor.disabled)
        exit     <- executor.execute(command).exit
      yield assertTrue(exit.isFailure)
    }
  )
