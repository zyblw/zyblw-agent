package com.zyblw.agent.workspace

import com.zyblw.agent.core.*
import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import zio.*

/** OCI 容器的确定性资源上限。
  *
  * 这些参数最终转换为 Docker/Podman 兼容的 `run` 参数。限制值属于框架策略，而不是让模型随请求自由指定， 因而模型无法通过扩大内存、进程数或超时来自行提升权限。
  *
  * @param memoryBytes
  *   容器物理内存硬上限，同时用于 memory-swap，禁止额外 swap 配额
  * @param cpus
  *   可使用的 CPU 核额度，例如 `0.5` 表示半个核心
  * @param pids
  *   最大进程/线程数量，缓解 fork bomb
  * @param tmpfsBytes
  *   `/tmp` 内存文件系统上限
  * @param maxCommandTimeout
  *   单次命令允许的最大墙钟时间
  * @param maxCapturedOutputBytes
  *   stdout 与 stderr 合计最多保留的字节数；超出后仍排空管道但不再保留
  * @param noFileLimit
  *   容器进程可打开文件描述符上限
  */
final case class OciSandboxLimits(
    memoryBytes: Long = 512L * 1024L * 1024L,
    cpus: BigDecimal = BigDecimal("1.0"),
    pids: Int = 128,
    tmpfsBytes: Long = 64L * 1024L * 1024L,
    maxCommandTimeout: Duration = 5.minutes,
    maxCapturedOutputBytes: Int = 4 * 1024 * 1024,
    noFileLimit: Int = 1024
):
  require(memoryBytes >= 16L * 1024L * 1024L, "sandbox memoryBytes 不能小于 16 MiB")
  require(cpus >= BigDecimal("0.1") && cpus <= BigDecimal("64"), "sandbox cpus 必须在 0.1 到 64 之间")
  require(pids > 0 && pids <= 32768, "sandbox pids 必须在 1 到 32768 之间")
  require(tmpfsBytes > 0L && tmpfsBytes <= memoryBytes, "sandbox tmpfsBytes 必须为正且不超过 memoryBytes")
  require(maxCommandTimeout > Duration.Zero, "sandbox maxCommandTimeout 必须为正")
  require(maxCapturedOutputBytes > 0, "sandbox maxCapturedOutputBytes 必须为正")
  require(noFileLimit > 0, "sandbox noFileLimit 必须为正")

/** OCI Sandbox 的静态部署配置。
  *
  * @param runtimeExecutable
  *   Docker 或 Podman CLI 的绝对路径；要求绝对路径是为了不受宿主 PATH 注入影响
  * @param imageDigest
  *   带 `@sha256:` 的不可变镜像引用；框架拒绝 `latest` 等可漂移 tag
  * @param workspaceRoot
  *   唯一允许写入并挂载到 `/workspace` 的宿主目录
  * @param runtimeEnvironment
  *   仅供容器运行时 CLI 使用的宿主环境，例如经过部署系统注入的 HOME/DOCKER_HOST；默认不继承
  * @param containerUser
  *   容器内非 root 的数值 `uid:gid`
  * @param limits
  *   由宿主决定的资源硬限制
  * @param shutdownGrace
  *   超时/取消时发送普通终止后等待的时间
  */
final case class OciSandboxConfig(
    runtimeExecutable: Path,
    imageDigest: String,
    workspaceRoot: Path,
    runtimeEnvironment: Map[String, String] = Map.empty,
    containerUser: String = "65532:65532",
    limits: OciSandboxLimits = OciSandboxLimits(),
    shutdownGrace: Duration = 2.seconds
):
  private val digestPattern = raw".+@sha256:[0-9a-f]{64}".r
  private val userPattern   = raw"([1-9][0-9]*):([1-9][0-9]*)".r

  require(runtimeExecutable.isAbsolute, "runtimeExecutable 必须是绝对路径")
  require(digestPattern.matches(imageDigest), "imageDigest 必须是 sha256 固定镜像引用")
  require(workspaceRoot.isAbsolute, "workspaceRoot 必须是绝对路径")
  require(!workspaceRoot.toString.contains(','), "workspaceRoot 不能含逗号，以免改变 --mount 语义")
  require(userPattern.matches(containerUser), "containerUser 必须是非 root 数值 uid:gid")
  require(shutdownGrace > Duration.Zero, "shutdownGrace 必须为正")
  require(runtimeEnvironment.keys.forall(OciSandboxEnvironment.isValidHostKey), "runtimeEnvironment 含非法变量名")
  require(runtimeEnvironment.values.forall(!_.contains('\u0000')), "runtimeEnvironment 值不能含 NUL")

/** 交给宿主进程运行器的低层请求。
  *
  * `arguments` 是已经展开的 argv，不经过 shell；`environment` 的值可能包含 secret，任何日志与异常都不得渲染它。
  */
final case class SandboxProcessRequest(
    arguments: Chunk[String],
    environment: Map[String, String],
    timeout: Duration,
    maxOutputBytes: Int,
    shutdownGrace: Duration
)

/** 将进程生命周期与 OCI 策略分离，便于用 ZIO Test 精确断言生成的安全参数。 */
trait SandboxProcessRunner:
  /** 启动、排空输出、等待并在超时或 Fiber 取消时终止一个不经 shell 的宿主进程。 */
  def run(request: SandboxProcessRequest): IO[AgentError, SandboxResult]

object SandboxProcessRunner:
  /** 使用 JDK Process API 的生产实现；构造过程无资源，因此是普通 ULayer。 */
  val live: ULayer[SandboxProcessRunner] = ZLayer.succeed(JdkSandboxProcessRunner())

/** 使用 JDK `ProcessBuilder` 执行 OCI CLI。
  *
  * stdout/stderr 由两个受 Scope 管理的阻塞 Fiber 并行排空，避免任一管道填满导致子进程死锁。输出共享同一个字节预算； 达到预算后仍继续读取并丢弃剩余字节。Scope 退出、超时和调用
  * Fiber 取消都会触发进程终止。
  */
final class JdkSandboxProcessRunner private () extends SandboxProcessRunner:
  def run(request: SandboxProcessRequest): IO[AgentError, SandboxResult] =
    if request.arguments.isEmpty || request.timeout <= Duration.Zero || request.maxOutputBytes <= 0 then
      ZIO.fail(AgentError.InvalidConfiguration("Invalid sandbox process request"))
    else
      ZIO.scoped {
        for
          process <- ZIO.acquireRelease(start(request))(process => terminate(process, request.shutdownGrace))
          budget = SharedOutputBudget(request.maxOutputBytes)
          stdout   <- drain(process.getInputStream, budget).forkScoped
          stderr   <- drain(process.getErrorStream, budget).forkScoped
          exitCode <- waitFor(process)
            .timeoutFail(processFailure("wait", "Sandbox command timed out", "timeout", retryable = true))(
              request.timeout
            )
            .onError(_ => terminate(process, request.shutdownGrace))
            .onInterrupt(terminate(process, request.shutdownGrace))
          out <- stdout.join
          err <- stderr.join
        yield SandboxResult(
          exitCode,
          new String(out, StandardCharsets.UTF_8),
          new String(err, StandardCharsets.UTF_8),
          budget.wasTruncated
        )
      }

  /** 清空父进程环境，只注入部署层显式给出的变量，然后启动 argv。 */
  private def start(request: SandboxProcessRequest): IO[AgentError, Process] =
    ZIO
      .attemptBlocking {
        val builder     = new ProcessBuilder(request.arguments.toList*)
        val environment = builder.environment()
        environment.clear()
        request.environment.foreach { case (key, value) => environment.put(key, value) }
        builder.start()
      }
      .mapError(error =>
        processFailure("start", "Failed to start OCI runtime", "process_start_failed", cause = Some(error))
      )

  /** `waitFor` 放在可中断阻塞线程池，调用 Fiber 取消时不会占住计算线程。 */
  private def waitFor(process: Process): IO[AgentError, Int] =
    ZIO
      .attemptBlockingInterrupt(process.waitFor())
      .mapError(error =>
        processFailure(
          "wait",
          "Failed while waiting for OCI runtime",
          "process_wait_failed",
          cause = Some(error)
        )
      )

  /** 持续排空一个进程管道。
    *
    * 读取到的字节先向共享预算申请保留额度，超出部分立即丢弃；因此恶意进程不能依靠无限输出耗尽 JVM 堆。
    */
  private def drain(stream: InputStream, budget: SharedOutputBudget): IO[AgentError, Array[Byte]] =
    ZIO
      .attemptBlockingInterrupt {
        val output = new ByteArrayOutputStream()
        val buffer = Array.ofDim[Byte](8192)
        try
          var read = stream.read(buffer)
          while read >= 0 do
            val accepted = budget.reserve(read)
            if accepted > 0 then output.write(buffer, 0, accepted)
            read = stream.read(buffer)
          output.toByteArray
        finally stream.close()
      }
      .mapError(error =>
        processFailure("drain", "Failed to drain sandbox output", "output_read_failed", cause = Some(error))
      )

  /** 先请求正常终止，宽限期后仍存活则强制杀死；本方法可安全重复调用。 */
  private def terminate(process: Process, grace: Duration): UIO[Unit] =
    ZIO.attemptBlocking {
      if process.isAlive then
        process.destroy()
        val millis = math.max(1L, grace.toMillis)
        if !process.waitFor(millis, TimeUnit.MILLISECONDS) then
          process.destroyForcibly()
          process.waitFor(millis, TimeUnit.MILLISECONDS)
          ()
    }.ignore

  /** 创建不含 argv、环境值和进程输出的低敏错误。 */
  private def processFailure(
      operation: String,
      message: String,
      code: String,
      retryable: Boolean = false,
      cause: Option[Throwable] = None
  ): AgentError =
    AgentError.ExternalProtocolFailure("oci-sandbox", operation, message, Some(code), retryable, cause)

object JdkSandboxProcessRunner:
  /** 公开工厂避免调用方依赖实现类的构造细节。 */
  def apply(): JdkSandboxProcessRunner = new JdkSandboxProcessRunner()

/** stdout/stderr 共享的线程安全保留预算。 */
final private class SharedOutputBudget private (limit: Int):
  private var retained  = 0
  private var truncated = false

  /** 返回本批字节允许写入结果缓冲区的前缀长度。 */
  def reserve(offered: Int): Int = synchronized {
    val remaining = limit - retained
    val accepted  = math.max(0, math.min(remaining, offered))
    retained += accepted
    if accepted < offered then truncated = true
    accepted
  }

  /** 是否至少有一个输出字节因达到总预算而被丢弃。 */
  def wasTruncated: Boolean = synchronized(truncated)

private object SharedOutputBudget:
  /** 创建 stdout/stderr 共享的预算计数器。 */
  def apply(limit: Int): SharedOutputBudget = new SharedOutputBudget(limit)

/** OCI 环境变量白名单规则。 */
private object OciSandboxEnvironment:
  private val keyPattern     = raw"[A-Za-z_][A-Za-z0-9_]*".r
  private val forbiddenExact = Set("PATH", "HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")

  /** 宿主 runtime 环境只验证语法；它由可信部署配置提供，可以设置 DOCKER_HOST 等运行时参数。 */
  def isValidHostKey(key: String): Boolean = keyPattern.matches(key) && !key.contains('\u0000')

  /** 容器业务变量还必须拒绝可能改变 Docker/Podman CLI 自身行为的名称。
    *
    * 业务值通过 `--env KEY` 从 CLI 环境传入容器，因此同名变量会先出现在 CLI 进程环境中；禁止运行时保留前缀可避免 模型借环境变量切换 daemon、认证目录或连接目标。
    */
  def isSafeContainerKey(key: String): Boolean =
    isValidHostKey(key) &&
      !forbiddenExact.contains(key) &&
      !key.startsWith("DOCKER_") &&
      !key.startsWith("CONTAINER_") &&
      !key.startsWith("PODMAN_") &&
      !key.startsWith("XDG_")

/** 默认断网的 OCI SandboxExecutor。
  *
  * 当前稳定实现只支持 `--network none`。网络白名单不能靠一个配置字符串诚实实现；后续若需要联网，应增加独立出口代理、 DNS/IP 重绑定防护和目标审计，而不是把容器直接接入默认 bridge
  * 网络。
  */
final class OciSandboxExecutor(
    config: OciSandboxConfig,
    runner: SandboxProcessRunner,
    sessionFactory: SandboxProcessSessionFactory = JdkSandboxProcessSessionFactory()
) extends SandboxExecutor,
      SandboxSessionLauncher:

  /** 校验命令与工作区后，构造 Docker/Podman 兼容的安全 argv 并执行。
    *
    * @param command
    *   模型经 Tool 权限与审批层批准后的命令；executable 必须是容器内绝对路径
    * @return
    *   退出码和受总预算限制的 UTF-8 stdout/stderr
    */
  def execute(command: SandboxCommand): IO[AgentError, SandboxResult] =
    for
      _       <- validateCommand(command)
      root    <- validateWorkspaceRoot
      workdir <- validateWorkingDirectory(root, command.workingDirectory)
      name    <- Random.nextUUID.map(uuid => s"zyblw-agent-${uuid.toString}")
      request = buildRequest(command, root, workdir, name)
      result <- runner.run(request)
    yield result

  /** 以同一 OCI 安全策略启动双向长生命周期进程。
    *
    * 与 `execute` 不同，本方法不收集 stdout/stderr，也不设置命令墙钟超时；MCP 等 framing protocol 在返回的 Scope 内 自己管理请求超时和消息上限。Scope
    * 结束仍会强制回收容器进程。
    */
  def open(command: SandboxSessionCommand): ZIO[Scope, AgentError, SandboxProcessSession] =
    for
      _       <- validateInvocation(command.executable, command.arguments, command.environment)
      root    <- validateWorkspaceRoot
      workdir <- validateWorkingDirectory(root, command.workingDirectory)
      name    <- Random.nextUUID.map(uuid => s"zyblw-agent-${uuid.toString}")
      arguments = buildArguments(
        command.executable,
        command.arguments,
        command.environment,
        root,
        workdir,
        name
      )
      session <- sessionFactory.open(
        SandboxProcessSessionRequest(
          arguments,
          config.runtimeEnvironment ++ command.environment,
          config.shutdownGrace
        )
      )
    yield session

  /** 检查所有请求级预算和环境变量，失败时不会启动容器运行时。 */
  private def validateCommand(command: SandboxCommand): IO[AgentError, Unit] =
    val budgetsValid =
      command.timeout > Duration.Zero && command.timeout <= config.limits.maxCommandTimeout &&
        command.maxOutputBytes > 0 && command.maxOutputBytes <= config.limits.maxCapturedOutputBytes
    validateInvocation(command.executable, command.arguments, command.environment) *>
      ZIO
        .fail(AgentError.InvalidConfiguration("Sandbox command violates configured budget policy"))
        .unless(budgetsValid)
        .unit

  /** 一次性命令和长连接会话共享的 executable、argv 与环境策略。 */
  private def validateInvocation(
      executable: String,
      arguments: Chunk[String],
      environment: Map[String, String]
  ): IO[AgentError, Unit] =
    val executableValid  = executable.startsWith("/") && !executable.contains('\u0000')
    val argumentsValid   = arguments.forall(!_.contains('\u0000'))
    val environmentValid = environment.forall { case (key, value) =>
      OciSandboxEnvironment.isSafeContainerKey(key) && !value.contains('\u0000')
    }
    ZIO
      .fail(AgentError.InvalidConfiguration("Sandbox invocation violates executable or environment policy"))
      .unless(executableValid && argumentsValid && environmentValid)
      .unit

  /** 确认运行时可执行、挂载根目录真实存在且根本身不是 symlink。
    *
    * 容器挂载由宿主 daemon 解析；若允许 symlink 根，部署检查与 daemon 实际挂载对象可能不一致。
    */
  private def validateWorkspaceRoot: IO[AgentError, Path] =
    ZIO
      .attemptBlocking {
        val runtime = config.runtimeExecutable.toAbsolutePath.normalize()
        val root    = config.workspaceRoot.toAbsolutePath.normalize()
        if !Files.isRegularFile(runtime, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(runtime) then
          throw IllegalArgumentException("OCI runtime executable is unavailable")
        if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root) then
          throw IllegalArgumentException("Sandbox workspace root is unavailable or symbolic")
        root
      }
      .mapError(error =>
        AgentError.ExternalProtocolFailure(
          "oci-sandbox",
          "validate",
          "OCI runtime or workspace validation failed",
          Some("invalid_runtime_environment"),
          cause = Some(error)
        )
      )

  /** 拒绝工作目录中的 symlink，并返回容器内固定 `/workspace/...` 路径。 */
  private def validateWorkingDirectory(root: Path, path: WorkspacePath): IO[AgentError, String] =
    ZIO
      .attemptBlocking {
        val host = root.resolve(path.value).normalize()
        if !host.startsWith(root) || !Files.isDirectory(host, LinkOption.NOFOLLOW_LINKS) then
          throw IllegalArgumentException("Sandbox working directory does not exist")
        var current = root
        root.relativize(host).iterator().asScala.foreach { segment =>
          current = current.resolve(segment)
          if Files.isSymbolicLink(current) then
            throw SecurityException("Sandbox working directory contains symlink")
        }
        s"/workspace/${path.value}"
      }
      .mapError {
        case error: SecurityException => AgentError.PermissionDenied("sandbox.workdir", error.getMessage)
        case error                    =>
          AgentError.ExternalProtocolFailure(
            "oci-sandbox",
            "workdir",
            "Sandbox working directory validation failed",
            Some("invalid_working_directory"),
            cause = Some(error)
          )
      }

  /** 生成不可变、安全默认值的 OCI CLI argv。
    *
    * 环境值不会进入 argv：只生成 `--env KEY`，值放入 ProcessBuilder environment。这样进程列表和错误不会泄露 secret。
    */
  private def buildRequest(
      command: SandboxCommand,
      root: Path,
      containerWorkdir: String,
      containerName: String
  ): SandboxProcessRequest =
    SandboxProcessRequest(
      arguments = buildArguments(
        command.executable,
        command.arguments,
        command.environment,
        root,
        containerWorkdir,
        containerName
      ),
      environment = config.runtimeEnvironment ++ command.environment,
      timeout = command.timeout,
      maxOutputBytes = command.maxOutputBytes,
      shutdownGrace = config.shutdownGrace
    )

  /** 一次性命令与双向会话共享的 OCI hardening 参数编译器。 */
  private def buildArguments(
      executable: String,
      arguments: Chunk[String],
      environment: Map[String, String],
      root: Path,
      containerWorkdir: String,
      containerName: String
  ): Chunk[String] =
    val limits               = config.limits
    val environmentArguments = Chunk.fromIterable(
      environment.keys.toList.sorted.flatMap(key => List("--env", key))
    )
    val fixed = Chunk(
      config.runtimeExecutable.toAbsolutePath.normalize().toString,
      "run",
      "--rm",
      "--pull",
      "never",
      "--name",
      containerName,
      "--network",
      "none",
      "--ipc",
      "none",
      "--read-only",
      "--cap-drop",
      "ALL",
      "--security-opt",
      "no-new-privileges=true",
      "--pids-limit",
      limits.pids.toString,
      "--memory",
      limits.memoryBytes.toString,
      "--memory-swap",
      limits.memoryBytes.toString,
      "--cpus",
      limits.cpus.bigDecimal.stripTrailingZeros.toPlainString,
      "--ulimit",
      s"nofile=${limits.noFileLimit}:${limits.noFileLimit}",
      "--tmpfs",
      s"/tmp:rw,noexec,nosuid,nodev,size=${limits.tmpfsBytes}",
      "--mount",
      s"type=bind,src=${root.toString},dst=/workspace,rw",
      "--workdir",
      containerWorkdir,
      "--user",
      config.containerUser,
      "--init"
    )
    fixed ++ environmentArguments ++ Chunk(config.imageDigest, executable) ++ arguments

object OciSandboxExecutor:
  /** 依赖注入层：测试可提供记录请求的 runner，生产提供 `SandboxProcessRunner.live`。 */
  def layer(config: OciSandboxConfig): URLayer[SandboxProcessRunner, SandboxExecutor] =
    ZLayer.fromFunction((runner: SandboxProcessRunner) => OciSandboxExecutor(config, runner))

  /** 长连接会话 Layer。
    *
    * 单独暴露是为了让普通业务只获得 `SandboxExecutor`，只有 MCP 等确有需要的模块才持有更强的双向进程 capability。
    */
  def sessionLayer(config: OciSandboxConfig): URLayer[SandboxProcessSessionFactory, SandboxSessionLauncher] =
    ZLayer.fromFunction((factory: SandboxProcessSessionFactory) =>
      OciSandboxExecutor(config, JdkSandboxProcessRunner(), factory): SandboxSessionLauncher
    )
