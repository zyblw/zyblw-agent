package com.zyblw.agent.evals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import zio.*
import zio.json.*

/** 只验证数据集结构、固定来源和内容摘要；不会把 Draft 改成 Approved。 */
object AgentEvalDatasetVerifyCli extends ZIOAppDefault:
  private val MaximumBytes = 32L * 1024L * 1024L

  def run =
    getArgs.flatMap {
      case Chunk(rawPath) =>
        for
          dataset <- read(Path.of(rawPath))
          _       <- ZIO.fromEither(AgentEvalDataset.validateIntegrity(dataset))
          release = AgentEvalDataset.validateForRelease(dataset).isRight
          _ <- Console.printLine(
            s"""{"integrity":"valid","releaseApproved":$release,"cases":${dataset.cases.length},""" +
              s""""contentSha256":"${dataset.provenance.contentSha256}"}"""
          )
        yield ()
      case _ => ZIO.fail(IllegalArgumentException("usage: AgentEvalDatasetVerifyCli <dataset.json>"))
    }

  private def read(path: Path): Task[AgentEvalDataset] =
    ZIO
      .attemptBlocking {
        if Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
          throw IllegalArgumentException("dataset must be a regular non-symlink file")
        val size = Files.size(path)
        if size <= 0L || size > MaximumBytes then
          throw IllegalArgumentException("dataset size is outside allowed bounds")
        val bytes = Files.readAllBytes(path)
        if bytes.length.toLong != size then throw IllegalArgumentException("dataset changed while reading")
        new String(bytes, StandardCharsets.UTF_8)
      }
      .flatMap(raw =>
        ZIO
          .fromEither(raw.fromJson[AgentEvalDataset])
          .mapError(_ => IllegalArgumentException("dataset JSON does not match AgentEvalDataset"))
      )
