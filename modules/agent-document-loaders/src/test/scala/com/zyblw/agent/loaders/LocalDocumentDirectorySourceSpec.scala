package com.zyblw.agent.loaders

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

object LocalDocumentDirectorySourceSpec extends ZIOSpecDefault:

  private def deleteTree(path: java.nio.file.Path): UIO[Unit] =
    ZIO.attemptBlocking {
      val stream = Files.walk(path)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally stream.close()
    }.orDie

  def spec = suite("LocalDocumentDirectorySource")(
    test("确定性扫描受支持文件，内容保持流式并不暴露绝对路径") {
      ZIO
        .acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("zyblw-rag-source")))(deleteTree)
        .flatMap { root =>
          for
            _ <- ZIO.attemptBlocking {
              Files.createDirectories(root.resolve("books"))
              Files.writeString(root.resolve("books/guide.md"), "# Guide", StandardCharsets.UTF_8)
              Files.writeString(root.resolve("ignored.bin"), "secret", StandardCharsets.UTF_8)
            }
            inputs <- LocalDocumentDirectorySource(LocalDocumentDirectoryConfig(root)).inputs.runCollect
            bytes  <- inputs.head.content.runCollect
          yield assertTrue(
            inputs.length == 1,
            inputs.head.fileName == "guide.md",
            inputs.head.declaredMediaType == "text/markdown",
            inputs.head.sourceUri == "knowledge://local/books/guide.md",
            !inputs.head.sourceUri.contains(root.toString),
            new String(bytes.toArray, StandardCharsets.UTF_8) == "# Guide"
          )
        }
    },
    test("文件数超限时整体 fail-closed，不返回截断目录") {
      ZIO
        .acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("zyblw-rag-source-limit")))(deleteTree)
        .flatMap { root =>
          for
            _ <- ZIO.attemptBlocking {
              Files.writeString(root.resolve("a.md"), "a")
              Files.writeString(root.resolve("b.md"), "b")
            }
            exit <- LocalDocumentDirectorySource(
              LocalDocumentDirectoryConfig(root, maxFiles = 1)
            ).inputs.runCollect.exit
          yield assertTrue(exit.isFailure)
        }
    }
  )
