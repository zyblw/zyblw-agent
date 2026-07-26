package com.zyblw.agent.mcp

import com.zyblw.agent.core.*
import com.zyblw.agent.workspace.{SandboxProcessSession, SandboxSessionCommand, SandboxSessionLauncher}
import java.io.*
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** stdio MCP 子进程配置。
  *
  * @param command
  *   可执行文件及参数列表；直接传给 `ProcessBuilder`，不会经过 shell 展开
  * @param workingDirectory
  *   可选工作目录，必须是已存在目录
  * @param environment
  *   显式传给子进程的环境变量；值可能是 secret，框架绝不记录
  * @param inheritParentEnvironment
  *   是否继承宿主全部环境；生产默认关闭以遵守最小权限
  * @param maxLineChars
  *   stdout/stderr 单行最大字符数，防止无界行耗尽堆内存
  * @param inboundCapacity
  *   服务端通知和反向请求的有界缓冲
  * @param shutdownGrace
  *   关闭 stdin 后等待进程自行退出的时间
  * @param terminateGrace
  *   发送 `destroy`（通常为 SIGTERM）后再次等待的时间
  */
final case class StdioMcpTransportConfig(
    command: Chunk[String],
    workingDirectory: Option[Path] = None,
    environment: Map[String, String] = Map.empty,
    inheritParentEnvironment: Boolean = false,
    maxLineChars: Int = 1024 * 1024,
    inboundCapacity: Int = 256,
    shutdownGrace: Duration = 5.seconds,
    terminateGrace: Duration = 5.seconds
):
  /** 在启动进程前验证所有静态约束。 */
  def validate: IO[AgentError, Unit] =
    val basic =
      command.nonEmpty && command.head.trim.nonEmpty && command.forall(!_.contains('\u0000')) &&
        maxLineChars > 0 && inboundCapacity > 0 && shutdownGrace > Duration.Zero && terminateGrace > Duration.Zero &&
        environment.keys.forall(key => key.nonEmpty && !key.contains('=') && !key.contains('\u0000')) &&
        environment.values.forall(!_.contains('\u0000'))
    for
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("Invalid MCP stdio transport configuration"))
        .unless(basic)
      _ <- ZIO.foreachDiscard(workingDirectory) { directory =>
        ZIO
          .attemptBlocking(Files.isDirectory(directory))
          .mapError(error =>
            AgentError.ExternalProtocolFailure(
              "mcp",
              "stdio/configure",
              "Cannot inspect MCP working directory",
              Some("working_directory_error"),
              cause = Some(error)
            )
          )
          .flatMap(isDirectory =>
            ZIO
              .fail(
                AgentError.InvalidConfiguration("MCP working directory does not exist or is not a directory")
              )
              .unless(isDirectory)
          )
      }
    yield ()

/** 容器化 stdio MCP 只需要的 framing 与背压配置。
  *
  * 进程环境、工作目录、终止宽限期和 OCI 资源限制由 `SandboxSessionLauncher` 统一治理，不能在 MCP 层再定义一套相互 冲突的策略。
  *
  * @param maxLineChars
  *   stdout/stderr 单行最大字符数
  * @param inboundCapacity
  *   服务端通知和反向请求的有界缓冲
  */
final case class SandboxedStdioMcpConfig(
    maxLineChars: Int = 1024 * 1024,
    inboundCapacity: Int = 256
):
  /** 在启动容器前拒绝无效 framing 配额。 */
  def validate: IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidConfiguration("Invalid sandboxed MCP stdio configuration"))
      .unless(maxLineChars > 0 && inboundCapacity > 0)
      .unit

/** MCP stdio transport。
  *
  * 所有 reader/watcher Fiber 都属于创建时的 `Scope`。调用 `close` 或 Scope 结束时，transport 会依次关闭 stdin、等待、发送
  * terminate、最后强制终止；不会遗留孤儿进程。
  */
final class StdioMcpTransport private (
    peer: McpJsonRpcPeer,
    stdin: Writer,
    processAlive: UIO[Boolean],
    shutdownProcess: UIO[Unit],
    closed: Ref[Boolean]
) extends McpTransport:

  /** 委托给介质无关 JSON-RPC peer；中断与超时会传播取消通知。 */
  def request(method: String, params: Json.Obj, timeout: Duration): IO[AgentError, Json] =
    peer.request(method, params, timeout)

  /** 发送单行 JSON-RPC 通知。 */
  def notify(method: String, params: Json.Obj): IO[AgentError, Unit] = peer.notify(method, params)

  /** 响应服务端主动请求。 */
  def respond(id: McpRequestId, result: Either[McpRpcError, Json]): IO[AgentError, Unit] =
    peer.respond(id, result)

  /** 服务端主动消息流。 */
  def inbound: ZStream[Any, AgentError, McpInbound] = peer.inbound

  /** 幂等关闭 transport。
    *
    * 关闭错误只记在内部日志，不覆盖业务 Fiber 已经获得的结果；进程清理属于 finalizer，必须做到尽力而为。
    */
  def close: UIO[Unit] =
    closed.getAndSet(true).flatMap { alreadyClosed =>
      ZIO
        .unless(alreadyClosed) {
          peer.close *>
            ZIO.attemptBlocking(stdin.close()).ignore *>
            shutdownProcess
        }
        .unit
    }

  /** 测试和健康检查使用的进程存活状态；不暴露 pid，避免把宿主细节作为业务身份。 */
  private[mcp] def isProcessAlive: UIO[Boolean] = processAlive

  /** 测试确认取消/完成后 pending 已被清理。 */
  private[mcp] def pendingCount: UIO[Int] = peer.pendingCount

object StdioMcpTransport:
  /** 单行读取超限时使用的内部异常；消息不包含原始行。 */
  final private class LineTooLong(label: String) extends IOException(s"$label line exceeded configured limit")

  /** 在当前 Scope 中启动 MCP server 子进程并建立 transport。
    *
    * 使用示例： {{ ZIO.scoped { for transport <- StdioMcpTransport.scoped(
    * StdioMcpTransportConfig(Chunk("/usr/local/bin/my-mcp-server")) ) client <-
    * DefaultMcpClient.scoped(transport, clientConfig) tools <- client.listTools yield tools } }}
    *
    * @param config
    *   进程、环境隔离、消息上限和关闭时限
    * @return
    *   已启动 reader、stderr drainer 与 exit watcher 的 transport
    */
  def scoped(config: StdioMcpTransportConfig): ZIO[Scope, AgentError, StdioMcpTransport] =
    for
      _       <- config.validate
      process <- ZIO.acquireRelease(startProcess(config))(process => shutdownProcess(process, config).ignore)
      transport <- fromStreams(
        process.getOutputStream,
        process.getInputStream,
        process.getErrorStream,
        ZIO.succeed(process.isAlive),
        ZIO
          .attemptBlockingInterrupt(process.waitFor())
          .mapError(error =>
            AgentError.ExternalProtocolFailure(
              "mcp",
              "stdio/wait",
              "Failed while waiting for MCP stdio process",
              Some("process_wait_failed"),
              retryable = true,
              cause = Some(error)
            )
          ),
        shutdownProcess(process, config),
        config.maxLineChars,
        config.inboundCapacity
      )
    yield transport

  /** 在 OCI Sandbox 中启动 stdio MCP server。
    *
    * 使用示例： {{ ZIO.scoped { StdioMcpTransport.sandboxedScoped( SandboxSessionCommand( executable =
    * "/usr/local/bin/mcp-server", arguments = Chunk("--stdio"), workingDirectory = WorkspacePath("mcp") ) )
    * }.provide( SandboxProcessSessionFactory.live, OciSandboxExecutor.sessionLayer(ociConfig) ) }}
    *
    * @param command
    *   经过 Workspace/OCI 策略约束的容器内命令
    * @param config
    *   MCP 单行大小与反向消息背压配置
    * @return
    *   与普通 stdio transport 具有相同 JSON-RPC、取消和关闭语义的隔离 transport
    */
  def sandboxedScoped(
      command: SandboxSessionCommand,
      config: SandboxedStdioMcpConfig = SandboxedStdioMcpConfig()
  ): ZIO[Scope & SandboxSessionLauncher, AgentError, StdioMcpTransport] =
    for
      _         <- config.validate
      launcher  <- ZIO.service[SandboxSessionLauncher]
      session   <- launcher.open(command)
      transport <- fromSandboxSession(session, config)
    yield transport

  /** 将 Sandbox session 的原始管道适配为与本地 stdio 相同的 framing 实现。 */
  private def fromSandboxSession(
      session: SandboxProcessSession,
      config: SandboxedStdioMcpConfig
  ): ZIO[Scope, AgentError, StdioMcpTransport] =
    fromStreams(
      session.stdin,
      session.stdout,
      session.stderr,
      session.isAlive,
      session.awaitExit,
      session.close,
      config.maxLineChars,
      config.inboundCapacity
    )

  /** 本地子进程与 OCI 会话共享的唯一 stdio framing 构造器。
    *
    * 统一该层可避免容器路径与宿主路径在 UTF-8、行限制、反向请求、pending 清理和取消通知上产生协议漂移。
    */
  private def fromStreams(
      stdinStream: OutputStream,
      stdoutStream: InputStream,
      stderrStream: InputStream,
      processAlive: UIO[Boolean],
      awaitExit: IO[AgentError, Int],
      shutdown: UIO[Unit],
      maxLineChars: Int,
      inboundCapacity: Int
  ): ZIO[Scope, AgentError, StdioMcpTransport] =
    for
      stdin  <- ZIO.succeed(strictWriter(stdinStream))
      stdout <- ZIO.succeed(strictReader(stdoutStream))
      stderr <- ZIO.succeed(strictReader(stderrStream))
      lock   <- Semaphore.make(1L)
      closed <- Ref.make(false)
      send = (message: Json.Obj) => writeMessage(stdin, lock, message)
      peer <- McpJsonRpcPeer.make(send, inboundCapacity)
      transport = StdioMcpTransport(peer, stdin, processAlive, shutdown, closed)
      _ <- stdoutLoop(stdout, peer, shutdown, maxLineChars).forkScoped
      _ <- stderrLoop(stderr, peer, shutdown, maxLineChars).forkScoped
      _ <- exitLoop(awaitExit, peer).forkScoped
      _ <- ZIO.addFinalizer(transport.close)
    yield transport

  /** 启动不经过 shell 的子进程，并按策略清理/覆盖环境。 */
  private def startProcess(config: StdioMcpTransportConfig): IO[AgentError, Process] =
    ZIO
      .attemptBlocking {
        val builder = new java.lang.ProcessBuilder(config.command.toList*)
        config.workingDirectory.foreach(path => builder.directory(path.toFile))
        val environment = builder.environment()
        if !config.inheritParentEnvironment then environment.clear()
        config.environment.foreach { case (key, value) => environment.put(key, value) }
        builder.start()
      }
      .mapError(error =>
        AgentError.ExternalProtocolFailure(
          "mcp",
          "stdio/start",
          "Failed to start MCP server process",
          Some("process_start_failed"),
          retryable = false,
          cause = Some(error)
        )
      )

  /** UTF-8 解码器使用 REPORT，非法字节不会被替换字符悄悄吞掉。 */
  private def strictReader(stream: InputStream): Reader =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    new InputStreamReader(stream, decoder)

  /** stdout 编码固定为 UTF-8。 */
  private def strictWriter(stream: OutputStream): Writer =
    new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))

  /** 在阻塞线程上读取一行并实施字符上限。
    *
    * 不使用 `BufferedReader.readLine()`，因为后者会在找到换行符前无限扩容。EOF 前已有字符仍返回最后一行。
    */
  private def readBoundedLine(reader: Reader, maxChars: Int, label: String): IO[AgentError, Option[String]] =
    ZIO
      .attemptBlockingInterrupt {
        val builder = new StringBuilder(math.min(maxChars, 4096))
        var done    = false
        var eof     = false
        while !done do
          val next = reader.read()
          if next == -1 then
            eof = true
            done = true
          else if next == '\n' then done = true
          else
            builder.append(next.toChar)
            if builder.length > maxChars then throw LineTooLong(label)
        if eof && builder.isEmpty then None
        else
          val line = builder.result()
          Some(if line.endsWith("\r") then line.dropRight(1) else line)
      }
      .mapError(error =>
        AgentError.ExternalProtocolFailure(
          "mcp",
          s"stdio/$label",
          s"Failed to read MCP $label",
          Some(error match
            case _: LineTooLong => "line_too_long"
            case _              => "stream_read_failed"),
          cause = Some(error)
        )
      )

  /** 写入单个无内嵌换行的 JSON-RPC 消息；Semaphore 保证并发请求不会交叉写字节。 */
  private def writeMessage(stdin: Writer, lock: Semaphore, message: Json.Obj): IO[AgentError, Unit] =
    val encoded = message.toJson
    lock.withPermit(
      ZIO
        .attemptBlockingInterrupt {
          if encoded.indexOf('\n') >= 0 || encoded.indexOf('\r') >= 0 then
            throw IOException("encoded JSON-RPC message contains an embedded newline")
          stdin.write(encoded)
          stdin.write('\n')
          stdin.flush()
        }
        .mapError(error =>
          AgentError.ExternalProtocolFailure(
            "mcp",
            "stdio/write",
            "Failed to write MCP request",
            Some("stream_write_failed"),
            retryable = true,
            cause = Some(error)
          )
        )
    )

  /** stdout 只能包含协议消息；空行、非法 JSON 或 EOF 都会终止 peer。 */
  private def stdoutLoop(
      stdout: Reader,
      peer: McpJsonRpcPeer,
      shutdown: UIO[Unit],
      maxLineChars: Int
  ): UIO[Unit] =
    def loop: IO[AgentError, Unit] =
      readBoundedLine(stdout, maxLineChars, "stdout").flatMap {
        case None =>
          ZIO.fail(
            AgentError.ExternalProtocolFailure(
              "mcp",
              "stdio/stdout",
              "MCP server closed stdout",
              Some("unexpected_eof"),
              retryable = true
            )
          )
        case Some(line) if line.isEmpty =>
          ZIO.fail(
            McpJson.protocolError(
              "stdio/stdout",
              "MCP stdout contained an empty line",
              Some("invalid_stdout")
            )
          )
        case Some(line) =>
          ZIO.fromEither(McpJson.parseLine(line, "stdio/stdout")).flatMap(peer.accept) *> loop
      }
    loop.catchAll(error => peer.failAll(error) *> shutdown)

  /** stderr 始终被排空以防子进程阻塞，但内容不会进入日志、遥测或异常。 超限/非法 UTF-8 被视为失控子进程并终止，避免攻击者借 stderr 绕过内存上限。
    */
  private def stderrLoop(
      stderr: Reader,
      peer: McpJsonRpcPeer,
      shutdown: UIO[Unit],
      maxLineChars: Int
  ): UIO[Unit] =
    def loop: IO[AgentError, Unit] =
      readBoundedLine(stderr, maxLineChars, "stderr").flatMap {
        case None    => ZIO.unit
        case Some(_) => ZIO.logDebug("MCP server emitted a stderr line (content redacted)") *> loop
      }
    loop.catchAll(error => peer.failAll(error) *> shutdown)

  /** 子进程退出立即使所有 pending 失败；exit code 是安全的低敏诊断字段。 */
  private def exitLoop(awaitExit: IO[AgentError, Int], peer: McpJsonRpcPeer): UIO[Unit] =
    awaitExit.foldZIO(
      _ => ZIO.unit,
      exitCode =>
        peer.failAll(
          AgentError.ExternalProtocolFailure(
            "mcp",
            "stdio/exit",
            s"MCP server process exited with code $exitCode",
            Some("process_exited"),
            retryable = exitCode != 0
          )
        )
    )

  /** 按 MCP lifecycle 的 close-stdin -> terminate -> force-kill 顺序回收进程。 每一步均有硬时限，确保 finalizer 不会被失控子进程永久挂住。
    */
  private[mcp] def shutdownProcess(process: Process, config: StdioMcpTransportConfig): UIO[Unit] =
    def waitFor(duration: Duration): UIO[Boolean] =
      ZIO
        .attemptBlockingInterrupt(process.waitFor(duration.toMillis.max(1L), TimeUnit.MILLISECONDS))
        .orElseSucceed(false)
    (for
      _      <- ZIO.attemptBlocking(process.getOutputStream.close()).ignore
      exited <- waitFor(config.shutdownGrace)
      _      <- ZIO.unless(exited) {
        ZIO.attempt(process.destroy()).ignore *>
          waitFor(config.terminateGrace).flatMap(terminated =>
            ZIO.unless(terminated)(
              ZIO.attempt(process.destroyForcibly()).ignore *> waitFor(config.terminateGrace).unit
            )
          )
      }
    yield ()).uninterruptible
