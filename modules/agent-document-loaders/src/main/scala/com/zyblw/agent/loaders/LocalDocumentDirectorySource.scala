package com.zyblw.agent.loaders

import com.zyblw.agent.core.*
import com.zyblw.agent.rag.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*
import zio.json.*
import zio.*
import zio.stream.*

/** 受限本地文档目录的扫描配置。
  *
  * 该 Adapter 适合单机批量导入和离线摄取 worker。容器/多 Worker 生产环境应使用对象存储或消息队列的同等 Source Adapter， 不能假定所有实例共享本地磁盘。
  *
  * @param root
  *   允许扫描的唯一根目录
  * @param maxDepth
  *   最大递归深度
  * @param maxFiles
  *   单次扫描的文件数硬上限
  * @param maxFileBytes
  *   单文件声明大小上限；Loader 仍会对实际流重新限制
  * @param sourceUriPrefix
  *   用于引用的稳定非密密前缀，不使用真实 `file://` 绝对路径
  */
final case class LocalDocumentDirectoryConfig(
    root: Path,
    maxDepth: Int = 16,
    maxFiles: Int = 10_000,
    maxFileBytes: Long = 64L * 1024L * 1024L,
    sourceUriPrefix: String = "knowledge://local/",
    mediaTypes: Map[String, String] = LocalDocumentDirectoryConfig.defaultMediaTypes
):
  require(maxDepth >= 0 && maxDepth <= 128, "directory maxDepth 必须位于 0..128")
  require(maxFiles > 0 && maxFiles <= 1_000_000, "directory maxFiles 必须位于 1..1000000")
  require(maxFileBytes > 0, "directory maxFileBytes 必须为正数")
  require(sourceUriPrefix.matches("[A-Za-z][A-Za-z0-9+.-]*://.+/"), "sourceUriPrefix 必须是以 / 结尾的 URI 前缀")
  require(mediaTypes.nonEmpty, "directory mediaTypes 不能为空")
  require(
    mediaTypes.forall { case (extension, mediaType) =>
      extension.matches("[a-z0-9]{1,16}") && mediaType.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
    },
    "directory mediaTypes 扩展名或 MIME type 无效"
  )

object LocalDocumentDirectoryConfig:
  val defaultMediaTypes: Map[String, String] = Map(
    "pdf"  -> "application/pdf",
    "md"   -> "text/markdown",
    "txt"  -> "text/plain",
    "html" -> "text/html",
    "htm"  -> "text/html",
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "epub" -> "application/epub+zip"
  )

/** 把根目录下的受支持文件转成有背压的 `DocumentInput` 流。
  *
  * 扫描不跟随符号链接，每个内容流也在打开前重新校验真实路径仍位于根目录，缩小扫描后替换文件的 TOCTOU 窗口。
  */
final class LocalDocumentDirectorySource(config: LocalDocumentDirectoryConfig):

  def inputs: ZStream[Any, RetrievalError, DocumentInput] =
    ZStream.unwrap(scan.map(paths => ZStream.fromIterable(paths).mapZIO(toInput)))

  /** 按摄入时的稳定 documentId 回读单个文件，供重建使用。 */
  def loadById(documentId: String): IO[RetrievalError, Option[DocumentInput]] =
    directoryExists.flatMap {
      case false => ZIO.succeed(None)
      case true  =>
        scan.flatMap { paths =>
          ZIO
            .foreach(paths) { path =>
              identityOf(path).map(identity => path -> s"local-$identity")
            }
            .flatMap { pairs =>
              ZIO.foreach(pairs.collectFirst { case (path, id) if id == documentId => path })(toInput)
            }
        }
    }

  private def directoryExists: UIO[Boolean] =
    ZIO.attemptBlocking(Files.isDirectory(config.root, LinkOption.NOFOLLOW_LINKS)).orElseSucceed(false)

  private def scan: IO[RetrievalError, Vector[Path]] =
    ZIO
      .attemptBlocking {
        val root = config.root.toRealPath()
        if !Files.isDirectory(config.root, LinkOption.NOFOLLOW_LINKS) then
          throw IllegalArgumentException("root 不是普通目录")
        val stream = Files.walk(root, config.maxDepth)
        try
          val paths = stream
            .iterator()
            .asScala
            .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            .filter(path => mediaType(path).nonEmpty)
            .take(config.maxFiles + 1)
            .toVector
            .sortBy(path => root.relativize(path).toString)
          if paths.length > config.maxFiles then throw IllegalArgumentException("目录文件数超过上限")
          paths.filterNot { path =>
            val name  = path.getFileName.toString
            val lower = name.toLowerCase(java.util.Locale.ROOT)
            (lower.endsWith(".md") || lower.endsWith(".pdf")) &&
            Files.isRegularFile(
              path.resolveSibling(name.substring(0, name.lastIndexOf('.')) + ".paddle.json"),
              LinkOption.NOFOLLOW_LINKS
            )
          }
        finally stream.close()
      }
      .mapError(lowSensitiveError("directory scan"))

  private def toInput(path: Path): IO[RetrievalError, DocumentInput] =
    ZIO
      .attemptBlocking {
        val root     = config.root.toRealPath()
        val resolved = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        if !resolved.startsWith(root) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS) then
          throw IllegalArgumentException("文件逃逸根目录或不是普通文件")
        val length = Files.size(resolved)
        if length > config.maxFileBytes then throw IllegalArgumentException("文件超过大小上限")
        val relative       = root.relativize(resolved).iterator().asScala.map(_.toString).mkString("/")
        val sourceRelative =
          if mediaType(resolved).contains(PaddleOcrDocumentLoader.MediaType) &&
            Files.isRegularFile(
              resolved.resolveSibling(
                resolved.getFileName.toString.dropRight(".paddle.json".length) + ".pdf"
              ),
              LinkOption.NOFOLLOW_LINKS
            )
          then relative.dropRight(".paddle.json".length) + ".pdf"
          else relative
        val encoded = sourceRelative
          .split("/", -1)
          .map(segment => URLEncoder.encode(segment, StandardCharsets.UTF_8))
          .mkString("/")
        val logicalRelative =
          if mediaType(resolved).contains(PaddleOcrDocumentLoader.MediaType) then
            relative.dropRight(".paddle.json".length) + ".pdf"
          else relative
        val identity = contentIdentity(logicalRelative)
        val media    = mediaType(resolved).getOrElse(throw IllegalArgumentException("文件类型不受支持"))
        val paddle   = media == PaddleOcrDocumentLoader.MediaType
        val paired   = if paddle then Some(paddleArtifact(resolved, root, s"local-$identity")) else None
        DocumentInput(
          id = s"local-$identity",
          sourceUri = s"${config.sourceUriPrefix}$encoded",
          fileName = resolved.getFileName.toString,
          declaredMediaType = media,
          declaredLength = paired.map(_._1.length.toLong).orElse(Some(length)),
          metadata = Map("sourceKind" -> "local-directory"),
          sourceRevision = paired.flatMap(_._2),
          content = paired.fold(
            ZStream.fromPath(resolved).mapError(error => lowSensitiveError("directory read")(error))
          )(pair => ZStream.fromChunk(Chunk.fromArray(pair._1)))
        )
      }
      .mapError {
        case known: RetrievalError => known
        case other                 => lowSensitiveError("directory input")(other)
      }

  private def paddleArtifact(
      path: Path,
      root: Path,
      documentId: String
  ): (Array[Byte], Option[SourceRevision]) =
    def checkedRead(file: Path, limit: Long): Array[Byte] =
      val real = file.toRealPath()
      require(
        real.startsWith(root) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
        "Paddle sidecar escapes root"
      )
      val stream = Files.newInputStream(real)
      try
        val bytes = stream.readNBytes((limit + 1).min(Int.MaxValue).toInt)
        require(bytes.length <= limit, "Paddle sidecar size limit")
        bytes
      finally stream.close()
    def utf8(bytes: Array[Byte]): String =
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString
    val stem     = path.getFileName.toString.dropRight(".paddle.json".length)
    val md       = path.resolveSibling(stem + ".md")
    val pdf      = path.resolveSibling(stem + ".pdf")
    val markdown = Option.when(Files.exists(md, LinkOption.NOFOLLOW_LINKS))(
      utf8(checkedRead(md, PaddleOcrVlDocument.MaxMarkdownChars.toLong))
    )
    val original =
      Option.when(Files.exists(pdf, LinkOption.NOFOLLOW_LINKS))(checkedRead(pdf, config.maxFileBytes))
    val pages = original.map { bytes =>
      val doc = org.apache.pdfbox.Loader.loadPDF(bytes)
      try doc.getNumberOfPages
      finally doc.close()
    }
    val digest = original.map(bytes =>
      java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
    )
    val artifact = PaddleOcrArtifact(
      utf8(checkedRead(path, PaddleOcrVlDocument.MaxJsonChars.toLong)),
      markdown,
      pages,
      digest
    )
    val bytes = artifact.toJson.getBytes(StandardCharsets.UTF_8)
    require(bytes.length <= PaddleOcrDocumentLoader.MaxBytes, "Paddle envelope limit")
    (bytes, digest.map(hash => SourceRevision(documentId, hash, Some(hash), Some("application/pdf"))))

  private def identityOf(path: Path): IO[RetrievalError, String] =
    ZIO
      .attemptBlocking {
        val root     = config.root.toRealPath()
        val resolved = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val relative = root.relativize(resolved).iterator().asScala.map(_.toString).mkString("/")
        contentIdentity(
          if mediaType(resolved).contains(PaddleOcrDocumentLoader.MediaType) then
            relative.dropRight(".paddle.json".length) + ".pdf"
          else relative
        )
      }
      .mapError(lowSensitiveError("directory identity"))

  private def contentIdentity(relative: String): String =
    KnowledgeIndexer.sha256(relative).take(32)

  private def mediaType(path: Path): Option[String] =
    val name  = path.getFileName.toString.toLowerCase(java.util.Locale.ROOT)
    val index = name.lastIndexOf('.')
    if name.endsWith(".paddle.json") then Some(PaddleOcrDocumentLoader.MediaType)
    else
      Option
        .when(index >= 0 && index < name.length - 1)(name.substring(index + 1))
        .flatMap(config.mediaTypes.get)

  private def lowSensitiveError(operation: String)(error: Throwable): RetrievalError =
    AgentError.RetrievalFailed(s"$operation 失败: ${error.getClass.getSimpleName}", retryable = false)

object LocalDocumentDirectorySource:
  def configured(config: LocalDocumentDirectoryConfig): ULayer[LocalDocumentDirectorySource] =
    ZLayer.succeed(LocalDocumentDirectorySource(config))
