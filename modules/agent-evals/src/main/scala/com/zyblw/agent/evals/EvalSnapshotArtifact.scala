package com.zyblw.agent.evals

import com.zyblw.agent.core.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import zio.*
import zio.json.*

/** CI 发布门禁读取低敏快照 artifact 时使用的容量与路径配置。
  *
  * 该 artifact 必须是前一阶段评测任务生成的 [[EvalSuiteSnapshot]] JSON，而不是含有问题、答案、引用正文、 `EvalGrade.details` 或 Provider
  * 原始交换的完整报告。把“生成评测结果”和“决定是否发布”拆成两个进程，可以让发布账号 只获得趋势仓库写权限，而不必同时获得模型密钥、业务数据集和调试 artifact 的读取权限。
  *
  * @param path
  *   待读取的单个 JSON 文件；必须是普通文件且不能是符号链接
  * @param maxBytes
  *   artifact 的 UTF-8 字节硬上限；读取前先检查，避免错误配置或恶意文件造成无界内存分配
  */
final case class EvalSnapshotArtifactConfig(
    path: Path,
    maxBytes: Int = 2 * 1024 * 1024
)

/** 严格、低敏的 [[EvalSuiteSnapshot]] artifact 读取器。
  *
  * 这里没有使用 `Files.readString`，因为发布门禁属于安全边界：
  *
  *   - `FileChannel` 使用 `NOFOLLOW_LINKS`，避免工作区中的符号链接把 CI 引向非预期文件；
  *   - 在分配数组之前读取文件大小并应用硬上限；
  *   - UTF-8 decoder 使用 `REPORT`，不允许替换字符悄悄改变 evaluationId、datasetVersion 或维度名；
  *   - JSON 解码后再次执行完整领域校验，而不是只相信 case class decoder；
  *   - 所有错误只返回稳定 code，不回显路径、文件内容、模型名或业务数据。
  */
object EvalSnapshotArtifact:
  private case object ArtifactTooLarge extends java.io.IOException("eval-snapshot-artifact-too-large")

  /** 从受控文件加载一份低敏评测快照。
    *
    * @param config
    *   路径与容量限制
    * @return
    *   合法、可直接交给 [[EvalReleaseGate.evaluateAndAppend]] 的快照
    */
  def load(
      config: EvalSnapshotArtifactConfig
  ): IO[AgentError.InvalidConfiguration, EvalSuiteSnapshot] =
    for
      _       <- validateCapacity(config)
      _       <- validateReadableTarget(config.path)
      bytes   <- readBounded(config)
      json    <- strictUtf8(bytes)
      decoded <- decode(json)
      _       <- EvalSuiteSnapshot.validate(decoded)
    yield decoded

  /** 以“同目录临时文件 + fsync + 原子替换”写出低敏快照 artifact。
    *
    * @param config
    *   目标路径与容量限制；父目录必须由部署系统预先创建
    * @param snapshot
    *   已经投影掉原始输入和 grade details 的低敏快照
    *
    * 写入不会自行创建目录，也不会直接覆盖目标文件的部分字节。临时文件与目标位于同一目录，只有完整写入并 `FileChannel.force(true)` 后才使用 `ATOMIC_MOVE`
    * 替换目标；进程崩溃时调用方只会观察到旧完整版本或新完整版本。
    */
  def write(
      config: EvalSnapshotArtifactConfig,
      snapshot: EvalSuiteSnapshot
  ): IO[AgentError.InvalidConfiguration, Unit] =
    for
      _      <- validateCapacity(config)
      _      <- EvalSuiteSnapshot.validate(snapshot)
      bytes  <- encode(snapshot, config.maxBytes)
      parent <- validateWritableTarget(config.path)
      random <- Random.nextUUID
      filename  = config.path.getFileName.toString
      temporary = parent.resolve(s".$filename.$random.tmp")
      _ <- writeAtomically(temporary, config.path, bytes)
    yield ()

  /** 容量限制同时用于读取和写入，且限制在单个 CI artifact 的合理范围。 */
  private def validateCapacity(
      config: EvalSnapshotArtifactConfig
  ): IO[AgentError.InvalidConfiguration, Unit] =
    ZIO
      .fail(invalid("invalid-artifact-capacity"))
      .when(config.maxBytes <= 0 || config.maxBytes > 16 * 1024 * 1024)
      .unit

  /** 读取阶段拒绝缺失文件、目录、设备文件和符号链接。
    *
    * 真正打开时仍使用 `NOFOLLOW_LINKS`，防止校验与打开之间发生链接替换。
    */
  private def validateReadableTarget(path: Path): IO[AgentError.InvalidConfiguration, Unit] =
    for
      safeTarget <- ZIO
        .attemptBlocking {
          Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
          !Files.isSymbolicLink(path)
        }
        .orElseSucceed(false)
      _ <- ZIO.fail(invalid("invalid-artifact-target")).unless(safeTarget)
    yield ()

  /** 写入阶段要求父目录真实存在且不是符号链接，目标则只能不存在或是普通非链接文件。
    *
    * @return
    *   已规范化的父目录，供临时文件放在同一文件系统中以获得原子 rename
    */
  private def validateWritableTarget(path: Path): IO[AgentError.InvalidConfiguration, Path] =
    val normalized = path.toAbsolutePath.normalize
    Option(normalized.getParent) match
      case None         => ZIO.fail(invalid("artifact-parent-missing"))
      case Some(parent) =>
        for
          safeParent <- ZIO
            .attemptBlocking {
              Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) &&
              !Files.isSymbolicLink(parent)
            }
            .orElseSucceed(false)
          _          <- ZIO.fail(invalid("artifact-parent-invalid")).unless(safeParent)
          safeTarget <- ZIO
            .attemptBlocking {
              !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) ||
              (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(normalized))
            }
            .orElseSucceed(false)
          _ <- ZIO.fail(invalid("invalid-artifact-target")).unless(safeTarget)
        yield parent

  /** 在 `Scope` 中打开并关闭文件描述符，在 blocking executor 上完成有界读取。
    *
    * `FileChannel.size` 与实际读取共用同一个已打开文件，避免先 `Files.size`、后重新按路径打开造成明显的 TOCTOU 窗口。
    */
  private def readBounded(
      config: EvalSnapshotArtifactConfig
  ): IO[AgentError.InvalidConfiguration, Array[Byte]] =
    ZIO.scoped {
      for
        channel <- ZIO.acquireRelease(
          ZIO
            .attemptBlockingIO(
              FileChannel.open(
                config.path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
              )
            )
            .mapError(_ => invalid("artifact-open-failed"))
        )(value => ZIO.attemptBlocking(value.close()).ignore)
        bytes <- ZIO
          .attemptBlockingIO {
            val size = channel.size()
            if size < 0L || size > config.maxBytes.toLong || size > Int.MaxValue.toLong then
              throw ArtifactTooLarge
            val buffer = ByteBuffer.allocate(size.toInt)
            while buffer.hasRemaining && channel.read(buffer) >= 0 do ()
            if buffer.hasRemaining then throw java.io.IOException("eval-snapshot-artifact-short-read")
            buffer.array()
          }
          .mapError {
            case ArtifactTooLarge => invalid("artifact-too-large")
            case _                => invalid("artifact-read-failed")
          }
      yield bytes
    }

  /** 将领域对象编码为 UTF-8，并在触碰文件系统前应用写入硬上限。 */
  private def encode(
      snapshot: EvalSuiteSnapshot,
      maxBytes: Int
  ): IO[AgentError.InvalidConfiguration, Array[Byte]] =
    ZIO
      .attempt(snapshot.toJson.getBytes(StandardCharsets.UTF_8))
      .mapError(_ => invalid("artifact-encode-failed"))
      .flatMap(bytes =>
        ZIO
          .fail(invalid("artifact-too-large"))
          .when(bytes.length > maxBytes)
          .as(bytes)
      )

  /** 完整写入临时文件后原子替换目标。
    *
    * 不在 `ATOMIC_MOVE` 不可用时静默降级到普通 move：发布 artifact 若可能被另一阶段并发读取，非原子替换会重新引入半写 窗口。失败或 Fiber 中断时 `ensuring`
    * 会尽力删除临时文件。
    */
  private def writeAtomically(
      temporary: Path,
      target: Path,
      bytes: Array[Byte]
  ): IO[AgentError.InvalidConfiguration, Unit] =
    (
      ZIO.scoped {
        ZIO
          .acquireRelease(
            ZIO
              .attemptBlockingIO(
                FileChannel.open(
                  temporary,
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS
                )
              )
              .mapError(_ => invalid("artifact-temporary-open-failed"))
          )(channel => ZIO.attemptBlocking(channel.close()).ignore)
          .flatMap(channel =>
            ZIO
              .attemptBlockingIO {
                val buffer = ByteBuffer.wrap(bytes)
                while buffer.hasRemaining do
                  val _ = channel.write(buffer)
                channel.force(true)
              }
              .mapError(_ => invalid("artifact-write-failed"))
          )
      } *>
        ZIO
          .attemptBlockingIO(
            Files.move(
              temporary,
              target.toAbsolutePath.normalize,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING
            )
          )
          .mapError(_ => invalid("artifact-atomic-move-failed"))
          .unit
    ).ensuring(
      ZIO.attemptBlockingIO(Files.deleteIfExists(temporary)).ignore
    )

  /** 严格解码 UTF-8；任何坏字节都拒绝，而不是插入 Unicode replacement character。 */
  private def strictUtf8(bytes: Array[Byte]): IO[AgentError.InvalidConfiguration, String] =
    ZIO
      .attempt {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      }
      .mapError(_ => invalid("invalid-artifact-utf8"))

  /** JSON 解析错误不回显 decoder 原文，避免 CI 日志意外带出 artifact 片段。 */
  private def decode(json: String): IO[AgentError.InvalidConfiguration, EvalSuiteSnapshot] =
    ZIO
      .fromEither(json.fromJson[EvalSuiteSnapshot])
      .mapError(_ => invalid("invalid-artifact-json"))

  /** 统一构造不会泄露路径或正文的稳定错误。 */
  private def invalid(code: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration(s"eval-release:$code")
