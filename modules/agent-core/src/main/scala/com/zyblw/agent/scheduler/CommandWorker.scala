package com.zyblw.agent.scheduler

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import java.time.Instant
import zio.*

/** 耐久命令 Worker 的调度参数。
  *
  * @param leaseDuration
  *   每次 claim/heartbeat 后的租约长度
  * @param heartbeatEvery
  *   心跳间隔，必须显著小于 leaseDuration
  * @param pollEvery
  *   暂无命令时的轮询间隔
  * @param retryDelay
  *   可重试错误重新排队前的基础延迟
  * @param maxAttempts
  *   一个人工重试周期内的最大自动 claim 次数
  */
final case class WorkerHostConfig(
    leaseDuration: Duration = 30.seconds,
    heartbeatEvery: Duration = 10.seconds,
    pollEvery: Duration = 500.millis,
    retryDelay: Duration = 5.seconds,
    maxAttempts: Int = 8
):
  require(leaseDuration > Duration.Zero, "leaseDuration 必须大于零")
  require(
    heartbeatEvery > Duration.Zero && heartbeatEvery < leaseDuration,
    "heartbeatEvery 必须在 0 与 leaseDuration 之间"
  )
  require(pollEvery > Duration.Zero && retryDelay >= Duration.Zero, "pollEvery 必须大于零且 retryDelay 不能为负")
  require(maxAttempts > 0, "maxAttempts 必须大于零")

/** 用 ZIO 结构化并发把命令执行 Fiber 与 heartbeat Fiber 绑定为同一个生命周期。
  *
  * heartbeat 因取消抢占、租约过期或 generation 变化而失败时，`raceFirst` 会中断 Runtime Fiber 及其模型流、工具子 Fiber 和 finalizer；Runtime
  * 随后即使迟到到达状态提交点，也会被数据库 fencing 再次拒绝。
  */
final class CommandLeaseSupervisor(store: RunCommandStore, config: WorkerHostConfig):
  /** 在持续心跳保护下执行一条命令。
    * @param lease
    *   完整 command/owner/token/generation 凭证
    * @param work
    *   Runtime 命令 effect
    * @return
    *   work 的结果；丢失租约时以 LeaseLost 失败
    */
  def supervise[A](lease: RunCommandLease)(work: IO[AgentError, A]): IO[AgentError, A] =
    work.raceFirst(heartbeatLoop(lease))

  /** 心跳循环不会正常结束；只有租约失败才会赢得 race。 */
  private def heartbeatLoop[A](lease: RunCommandLease): IO[AgentError, A] =
    (ZIO.sleep(config.heartbeatEvery) *> store.heartbeat(lease, config.leaseDuration).unit).forever

/** 负责“claim—heartbeat—execute—complete/requeue/dead-letter”的命令 worker。
  *
  * 永久业务错误不会自动热重试；只有 `AgentError.retryable=true` 才进入 abandon。LeaseLost 说明旧 worker 已失去权限， 此时绝不能再修改命令状态。
  *
  * @param owner
  *   当前部署实例唯一标识
  * @param store
  *   command dispatcher 与命令存储
  * @param supervisor
  *   heartbeat/抢占监督器
  * @param config
  *   调度参数
  * @param runOne
  *   执行完整租约命令的 Runtime 函数
  */
final class CommandWorker(
    owner: WorkerId,
    store: RunCommandStore,
    supervisor: CommandLeaseSupervisor,
    config: WorkerHostConfig,
    runOne: RunCommandLease => IO[AgentError, Unit]
):
  /** 持续处理命令，直到父 Scope 或应用被中断。 */
  def run: IO[AgentError, Nothing] = claimOnce.forever

  /** 执行一次 claim 周期。
    * @return
    *   true 表示领取并处理了一条命令；false 表示队列为空且已经完成 pollEvery 休眠
    */
  def claimOnce: IO[AgentError, Boolean] =
    store.claim(owner, config.leaseDuration, config.maxAttempts).flatMap {
      case None        => ZIO.sleep(config.pollEvery).as(false)
      case Some(lease) => process(lease).as(true)
    }

  /** 根据 typed retryable 决定自动重排队或永久 DeadLetter。 */
  private def process(lease: RunCommandLease): IO[AgentError, Unit] =
    supervisor.supervise(lease)(runOne(lease)).exit.flatMap {
      case Exit.Success(_)     => store.complete(lease)
      case Exit.Failure(cause) =>
        cause.failureOption match
          case Some(error: AgentError.LeaseLost) => ZIO.fail(error)
          case Some(error) if error.retryable    =>
            Clock.instant
              .flatMap(now => store.abandon(lease, plus(now, config.retryDelay), safeSummary(error)))
          case Some(error) => store.deadLetter(lease, safeSummary(error))
          case None        => ZIO.refailCause(cause)
    }

  /** 只保存稳定错误类别和 retryable 标志，不持久化 Provider 原文或用户内容。 */
  private def safeSummary(error: AgentError): String =
    s"category=${error.category},retryable=${error.retryable}"

  /** 集中完成 Instant 与 ZIO Duration 转换。 */
  private def plus(instant: Instant, duration: Duration): Instant = instant.plusMillis(duration.toMillis)
