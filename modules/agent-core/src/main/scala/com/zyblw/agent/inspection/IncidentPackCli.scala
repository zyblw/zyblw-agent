package com.zyblw.agent.inspection

import java.nio.file.{Files, Path}
import zio.*
import zio.json.*

/** Inspector CLI 的可测试核心：把事故包 JSON 再编码并做密钥子串检查。 */
object IncidentPackCli:
  val LeakExitCode: Int          = 2
  val ConfigurationExitCode: Int = 3

  final case class Result(json: Either[String, String], exitCode: Int)

  def encode(packJson: String, forbidden: Chunk[String] = Chunk.empty): Result =
    packJson.fromJson[IncidentPack] match
      case Left(_) =>
        Result(Left("incident-pack-invalid-json"), ConfigurationExitCode)
      case Right(pack) =>
        IncidentPack.encode(pack, forbidden) match
          case Left(reason) => Result(Left(reason), LeakExitCode)
          case Right(json)  => Result(Right(json), 0)

  /** 从文件或 stdin 读取事故包。核心可测，不在这里 `System.exit`。 */
  def run(
      args: Chunk[String],
      stdin: String,
      forbidden: Chunk[String] = Chunk.empty,
      readFile: String => Either[String, String] = defaultReadFile
  ): Result =
    val source = args.headOption.filter(path => path.nonEmpty && path != "-") match
      case Some(path) => readFile(path)
      case None       => Right(stdin)
    source match
      case Left(_)     => Result(Left("incident-pack-unreadable"), ConfigurationExitCode)
      case Right(json) => encode(json, forbidden)

  private def defaultReadFile(path: String): Either[String, String] =
    scala.util.Try(Files.readString(Path.of(path))).toEither.left.map(_ => "unreadable")

/** `sbt "core/runMain com.zyblw.agent.inspection.IncidentPackCliApp"` */
object IncidentPackCliApp extends ZIOAppDefault:
  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for
      args  <- getArgs
      stdin <- Console.readLine.orElseSucceed("")
      result = IncidentPackCli.run(args, stdin)
      _ <- result.json.fold(err => Console.printLineError(err), json => Console.printLine(json))
      _ <- exit(ExitCode(result.exitCode))
    yield ()
