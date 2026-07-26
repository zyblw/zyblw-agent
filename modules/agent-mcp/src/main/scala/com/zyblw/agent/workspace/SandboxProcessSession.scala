package com.zyblw.agent.workspace

import com.zyblw.agent.core.*
import java.io.{InputStream, OutputStream}
import java.util.concurrent.TimeUnit
import zio.*

/** 需要双向长连接的 Sandbox 命令。
  *
  * 与一次性 `SandboxCommand` 不同，会话没有固定墙钟超时和结果缓冲；其生命周期完全属于调用方 `Scope`，协议层必须自行 限制消息大小、请求超时和空闲时间。典型用途是 stdio MCP
  * server，而不是普通 shell 命令。
  *
  * @param executable
  *   容器内绝对可执行路径
  * @param arguments
  *   不经 shell 的参数列表
  * @param workingDirectory
  *   Workspace 内已存在且不含 symlink 的目录
  * @param environment
  *   显式传入容器的业务环境变量；值不得写入日志
  */
final case class SandboxSessionCommand(
    executable: String,
    arguments: Chunk[String],
    workingDirectory: WorkspacePath,
    environment: Map[String, String] = Map.empty
)

/** 已完成 OCI 策略编译、可以交给宿主 Process API 的会话请求。 */
final case class SandboxProcessSessionRequest(
    arguments: Chunk[String],
    environment: Map[String, String],
    shutdownGrace: Duration
)

/** 一个受 `Scope` 管理的双向子进程。
  *
  * 原始 stream 只提供给 framing protocol Adapter，例如 MCP stdio。Adapter 必须固定字符集、限制单帧大小、并发排空 stderr，并且不能把 stderr
  * 原文直接写入 telemetry。
  */
trait SandboxProcessSession:
  /** 写往容器进程 stdin 的字节流；并发写入必须由协议层串行化。 */
  def stdin: OutputStream

  /** 容器进程 stdout；协议层必须把它视为纯协议通道。 */
  def stdout: InputStream

  /** 容器进程 stderr；必须持续排空，但默认不记录正文。 */
  def stderr: InputStream

  /** 等待进程退出；调用 Fiber 取消不会吞掉 ZIO interruption。 */
  def awaitExit: IO[AgentError, Int]

  /** 低敏健康状态，不暴露宿主 PID。 */
  def isAlive: UIO[Boolean]

  /** 幂等关闭 stdin、请求终止，并在宽限期后强制结束。 */
  def close: UIO[Unit]

/** 低层会话进程工厂；独立 SPI 使 OCI 参数测试不必真的依赖 Docker。 */
trait SandboxProcessSessionFactory:
  /** 在当前 Scope 中启动会话，Scope 结束时一定回收进程。 */
  def open(request: SandboxProcessSessionRequest): ZIO[Scope, AgentError, SandboxProcessSession]

object SandboxProcessSessionFactory:
  /** 基于 JDK ProcessBuilder 的生产实现。 */
  val live: ULayer[SandboxProcessSessionFactory] = ZLayer.succeed(JdkSandboxProcessSessionFactory())

/** 经过 OCI 策略治理的长生命周期进程入口。 */
trait SandboxSessionLauncher:
  /** 启动双向会话；未持有返回值所在 Scope 就不能继续使用其 stream。 */
  def open(command: SandboxSessionCommand): ZIO[Scope, AgentError, SandboxProcessSession]

/** JDK ProcessBuilder 会话工厂；不会继承宿主环境，也不会经过 shell。 */
final class JdkSandboxProcessSessionFactory private () extends SandboxProcessSessionFactory:
  def open(request: SandboxProcessSessionRequest): ZIO[Scope, AgentError, SandboxProcessSession] =
    if request.arguments.isEmpty || request.shutdownGrace <= Duration.Zero then
      ZIO.fail(AgentError.InvalidConfiguration("Invalid sandbox session process request"))
    else
      for
        process <- ZIO.acquireRelease(start(request))(process => terminate(process, request.shutdownGrace))
        closed  <- Ref.make(false)
      yield JdkSandboxProcessSession(process, request.shutdownGrace, closed)

  /** 清空父环境后启动绝对 runtime argv；错误中不包含参数和环境值。 */
  private def start(request: SandboxProcessSessionRequest): IO[AgentError, Process] =
    ZIO
      .attemptBlocking {
        val builder     = new ProcessBuilder(request.arguments.toList*)
        val environment = builder.environment()
        environment.clear()
        request.environment.foreach { case (key, value) => environment.put(key, value) }
        builder.start()
      }
      .mapError(error =>
        AgentError.ExternalProtocolFailure(
          "oci-sandbox",
          "session/start",
          "Failed to start sandbox process session",
          Some("process_start_failed"),
          cause = Some(error)
        )
      )

  /** close-stdin -> terminate -> force-kill 的有界回收序列。
    *
    * 该方法可在显式 close、Scope finalizer 和协议故障路径重复调用；`Process` 的终止操作天然允许重复检查。
    */
  private def terminate(process: Process, grace: Duration): UIO[Unit] =
    val millis = math.max(1L, grace.toMillis)
    ZIO
      .attemptBlocking {
        try process.getOutputStream.close()
        catch case _: Throwable => ()
        if process.isAlive && !process.waitFor(millis, TimeUnit.MILLISECONDS) then
          process.destroy()
          if !process.waitFor(millis, TimeUnit.MILLISECONDS) then
            process.destroyForcibly()
            process.waitFor(millis, TimeUnit.MILLISECONDS)
            ()
      }
      .ignore
      .uninterruptible

object JdkSandboxProcessSessionFactory:
  /** 工厂自身不持有资源，真实进程资源由每次 `open` 的 Scope 持有。 */
  def apply(): JdkSandboxProcessSessionFactory = new JdkSandboxProcessSessionFactory()

/** JDK Process 的 scoped 适配器。 */
final private case class JdkSandboxProcessSession(
    process: Process,
    shutdownGrace: Duration,
    closed: Ref[Boolean]
) extends SandboxProcessSession:
  def stdin: OutputStream = process.getOutputStream
  def stdout: InputStream = process.getInputStream
  def stderr: InputStream = process.getErrorStream

  def awaitExit: IO[AgentError, Int] =
    ZIO
      .attemptBlockingInterrupt(process.waitFor())
      .mapError(error =>
        AgentError.ExternalProtocolFailure(
          "oci-sandbox",
          "session/wait",
          "Failed while waiting for sandbox process session",
          Some("process_wait_failed"),
          cause = Some(error)
        )
      )

  def isAlive: UIO[Boolean] = ZIO.succeed(process.isAlive)

  def close: UIO[Unit] =
    closed
      .getAndSet(true)
      .flatMap { alreadyClosed =>
        ZIO
          .unless(alreadyClosed) {
            val millis = math.max(1L, shutdownGrace.toMillis)
            ZIO.attemptBlocking {
              try process.getOutputStream.close()
              catch case _: Throwable => ()
              if process.isAlive && !process.waitFor(millis, TimeUnit.MILLISECONDS) then
                process.destroy()
                if !process.waitFor(millis, TimeUnit.MILLISECONDS) then
                  process.destroyForcibly()
                  process.waitFor(millis, TimeUnit.MILLISECONDS)
                  ()
            }.ignore
          }
          .unit
      }
      .uninterruptible
