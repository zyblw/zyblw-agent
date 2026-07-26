package com.zyblw.agent.workspace

import com.zyblw.agent.core.*
import java.io.ByteArrayOutputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import zio.*

/** Workspace 内的相对路径。
  *
  * 构造器保留为公开 case class 便于业务配置和序列化，但会立即拒绝绝对路径、空段、`.`、`..`、NUL 和 Windows 分隔符。真正文件访问仍会在 `LocalWorkspace.resolve`
  * 再做根目录与 symlink 检查，不能只依赖该值类。
  */
final case class WorkspacePath(value: String):
  private val segments = value.split("/", -1).toList
  require(
    value.nonEmpty && !value.startsWith("/") && !value.contains('\\') && !value.contains('\u0000') &&
      segments.forall(segment => segment.nonEmpty && segment != "." && segment != ".."),
    "WorkspacePath 必须是无空段、`.`、`..` 和反斜杠的非空相对路径"
  )

/** 本地 Workspace 的确定性资源配额。
  *
  * @param maxFileBytes
  *   单文件上限
  * @param maxTotalBytes
  *   根目录内所有普通文件总上限
  * @param maxEntries
  *   list 单次最多返回的直接子项
  * @param allowDelete
  *   是否允许删除；高风险业务仍需 ToolMetadata 审批
  */
final case class WorkspacePolicy(
    maxFileBytes: Long = 8L * 1024L * 1024L,
    maxTotalBytes: Long = 128L * 1024L * 1024L,
    maxEntries: Int = 10000,
    allowDelete: Boolean = true
):
  require(maxFileBytes > 0L && maxTotalBytes >= maxFileBytes && maxEntries > 0, "Workspace 配额必须为正数")

/** 可被 Agent 工具使用的受限文件空间。 */
trait Workspace:
  /** 在根目录内读取一个普通文件。
    * @param path
    *   已验证的相对路径
    * @param maxBytes
    *   调用方针对本次上下文注入设置的更小上限
    */
  def read(path: WorkspacePath, maxBytes: Long): IO[AgentError, Chunk[Byte]]

  /** 原子写入字节。
    * @param overwrite
    *   false 时绝不覆盖既有路径
    */
  def write(path: WorkspacePath, bytes: Chunk[Byte], overwrite: Boolean): IO[AgentError, Unit]

  /** 列出目录直接子项，不递归读取正文。 */
  def list(path: WorkspacePath): IO[AgentError, Chunk[WorkspacePath]]

  /** 删除一个普通文件或空目录；不会递归删除目录树。 */
  def delete(path: WorkspacePath): IO[AgentError, Unit]

/** 防路径逃逸、拒绝 symlink 且带配额的本地 Workspace。
  *
  * 该实现适合受信业务文件与测试，不等于容器 Sandbox。它在每次操作前检查已有路径段没有符号链接，并用 `NOFOLLOW_LINKS` 打开属性；但通用 Path API 仍无法完全消除恶意本地进程参与的
  * symlink TOCTOU 竞态。 不可信代码必须在 OCI/微虚拟机内运行，并只挂载该目录。
  */
final class LocalWorkspace(root: Path, policy: WorkspacePolicy = WorkspacePolicy()) extends Workspace:
  private val normalizedRoot = root.toAbsolutePath.normalize()

  def read(path: WorkspacePath, maxBytes: Long): IO[AgentError, Chunk[Byte]] =
    val limit = math.min(maxBytes, policy.maxFileBytes)
    if limit <= 0L then ZIO.fail(AgentError.InvalidConfiguration("workspace read maxBytes must be positive"))
    else
      resolve(path, requireExisting = true).flatMap { target =>
        blocking("read", s"读取 workspace 文件失败") {
          val attributes =
            Files.readAttributes(target, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          if !attributes.isRegularFile then throw SecurityException("workspace 只允许读取普通文件")
          if attributes.size() > limit then throw IllegalArgumentException(s"workspace 文件超过 $limit bytes")
          val input  = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)
          val output = new ByteArrayOutputStream(math.min(attributes.size(), limit).toInt)
          try
            val buffer = Array.ofDim[Byte](8192)
            var total  = 0L
            var read   = input.read(buffer)
            while read >= 0 do
              total += read
              if total > limit then throw IllegalArgumentException(s"workspace 文件超过 $limit bytes")
              output.write(buffer, 0, read)
              read = input.read(buffer)
            Chunk.fromArray(output.toByteArray)
          finally input.close()
        }
      }

  def write(path: WorkspacePath, bytes: Chunk[Byte], overwrite: Boolean): IO[AgentError, Unit] =
    if bytes.length.toLong > policy.maxFileBytes then
      ZIO.fail(AgentError.PermissionDenied("workspace.write", s"单文件超过 ${policy.maxFileBytes} bytes"))
    else
      resolve(path, requireExisting = false).flatMap { target =>
        blocking("write", "写入 workspace 文件失败") {
          val parent = Option(target.getParent).getOrElse(throw SecurityException("workspace 目标没有父目录"))
          Files.createDirectories(parent)
          verifyNoSymlink(parent)
          if Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
            if Files.isSymbolicLink(target) then throw SecurityException("workspace 目标是符号链接")
            if !overwrite then throw FileAlreadyExistsException(target.toString)
          val existingSize =
            if Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) then Files.size(target) else 0L
          val projected = totalBytes() - existingSize + bytes.length.toLong
          if projected > policy.maxTotalBytes then throw IllegalArgumentException("workspace 总容量配额已耗尽")
          val temporary = Files.createTempFile(parent, ".zyblw-write-", ".tmp")
          try
            Files.write(temporary, bytes.toArray, StandardOpenOption.TRUNCATE_EXISTING)
            val options =
              if overwrite then Array(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
              else Array(StandardCopyOption.ATOMIC_MOVE)
            Files.move(temporary, target, options*)
            ()
          finally
            Files.deleteIfExists(temporary)
            ()
        }
      }

  def list(path: WorkspacePath): IO[AgentError, Chunk[WorkspacePath]] =
    resolve(path, requireExisting = true).flatMap { target =>
      blocking("list", "列出 workspace 目录失败") {
        if !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) then
          throw IllegalArgumentException("workspace 路径不是目录")
        val stream = Files.list(target)
        try
          val values = stream.limit(policy.maxEntries.toLong + 1L).iterator().asScala.toList
          if values.length > policy.maxEntries then throw IllegalArgumentException("workspace 目录条目超过上限")
          Chunk.fromIterable(
            values.map(item =>
              WorkspacePath(normalizedRoot.relativize(item).iterator().asScala.mkString("/"))
            )
          )
        finally stream.close()
      }
    }

  def delete(path: WorkspacePath): IO[AgentError, Unit] =
    if !policy.allowDelete then
      ZIO.fail(AgentError.PermissionDenied("workspace.delete", "workspace policy 禁止删除"))
    else
      resolve(path, requireExisting = true).flatMap { target =>
        blocking("delete", "删除 workspace 路径失败") {
          if Files.isSymbolicLink(target) then throw SecurityException("不能删除 workspace 符号链接")
          Files.delete(target)
          ()
        }
      }

  /** 完成词法根目录检查和全部已存在路径段的 symlink 检查。
    * @param requireExisting
    *   true 时目标必须存在；write 的最终文件可以尚不存在
    */
  private def resolve(path: WorkspacePath, requireExisting: Boolean): IO[AgentError, Path] =
    blocking("resolve", "解析 workspace 路径失败") {
      Files.createDirectories(normalizedRoot)
      verifyNoSymlink(normalizedRoot)
      val target = normalizedRoot.resolve(path.value).normalize()
      if !target.startsWith(normalizedRoot) then throw SecurityException("路径逃逸 workspace")
      verifyNoSymlink(if requireExisting then target else Option(target.getParent).getOrElse(normalizedRoot))
      if requireExisting && !Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
        throw NoSuchFileException(target.toString)
      target
    }

  /** 从 root 到目标逐段拒绝 symlink；目标必须位于 normalizedRoot 内。 */
  private def verifyNoSymlink(target: Path): Unit =
    if !target.normalize().startsWith(normalizedRoot) then throw SecurityException("路径逃逸 workspace")
    var current = normalizedRoot
    if Files.isSymbolicLink(current) then throw SecurityException("workspace 根目录不能是符号链接")
    val relative = normalizedRoot.relativize(target.normalize())
    relative.iterator().asScala.foreach { segment =>
      current = current.resolve(segment)
      if Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current) then
        throw SecurityException("workspace 路径包含符号链接")
    }

  /** 统计普通文件字节数；不跟随 symlink，遇到 symlink 直接拒绝。 */
  private def totalBytes(): Long =
    val stream = Files.walk(normalizedRoot)
    try
      stream.iterator().asScala.foldLeft(0L) { (total, path) =>
        if Files.isSymbolicLink(path) then throw SecurityException("workspace 包含符号链接")
        else if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
          Math.addExact(total, Files.size(path))
        else total
      }
    finally stream.close()

  /** 将 Java I/O 异常映射成不包含文件正文的 typed protocol failure。 */
  private def blocking[A](operation: String, message: String)(effect: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(effect).mapError {
      case error: SecurityException => AgentError.PermissionDenied(s"workspace.$operation", error.getMessage)
      case error                    =>
        AgentError.ExternalProtocolFailure(
          "workspace",
          operation,
          message,
          Some(error.getClass.getSimpleName),
          cause = Some(error)
        )
    }

/** 在 Sandbox 内执行的命令。executable/arguments 不经过 shell。
  *
  * @param workingDirectory
  *   Workspace 内工作目录
  * @param environment
  *   只注入明确列出的变量；实现不得默认转发宿主全部环境
  * @param timeout
  *   本次命令硬超时
  * @param maxOutputBytes
  *   stdout+stderr 合计保留上限
  */
final case class SandboxCommand(
    executable: String,
    arguments: Chunk[String],
    workingDirectory: WorkspacePath,
    environment: Map[String, String] = Map.empty,
    timeout: Duration = 30.seconds,
    maxOutputBytes: Int = 1024 * 1024
)

/** Sandbox 的低敏执行结果；truncated 表示输出超过保留上限但已继续排空。 */
final case class SandboxResult(exitCode: Int, stdout: String, stderr: String, truncated: Boolean)

/** 不可信命令执行边界。 */
trait SandboxExecutor:
  /** 在隔离实现中执行受限命令。 */
  def execute(command: SandboxCommand): IO[AgentError, SandboxResult]

object SandboxExecutor:
  /** 默认关闭执行能力，宿主必须显式提供受控实现。 */
  val disabled: ULayer[SandboxExecutor] = ZLayer.succeed(
    new SandboxExecutor:
      def execute(command: SandboxCommand): IO[AgentError, SandboxResult] =
        ZIO.fail(AgentError.PermissionDenied(command.executable, "沙箱执行未启用"))
  )
