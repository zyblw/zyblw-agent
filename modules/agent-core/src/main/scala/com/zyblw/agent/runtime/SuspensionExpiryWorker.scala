package com.zyblw.agent.runtime

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.{SuspensionLease, SuspensionStore, WorkerId}
import zio.*

/** 挂起到期 Worker 的有界调度参数；形状对齐 [[com.zyblw.agent.workflow.WorkflowWakeWorkerConfig]]。 */
final case class SuspensionExpiryWorkerConfig(
    leaseDuration: Duration = 30.seconds,
    heartbeatEvery: Duration = 10.seconds,
    pollEvery: Duration = 500.millis,
    retryDelay: Duration = 5.seconds,
    expireBatchSize: Int = 100
):
  require(leaseDuration.toMillis > 0L, "Suspension expiry leaseDuration 必须至少为 1 毫秒")
  require(
    heartbeatEvery > Duration.Zero && heartbeatEvery < leaseDuration,
    "Suspension expiry heartbeatEvery 必须在 0 与 leaseDuration 之间"
  )
  require(pollEvery.toMillis > 0L, "Suspension expiry pollEvery 必须至少为 1 毫秒")
  require(retryDelay.toMillis > 0L, "Suspension expiry retryDelay 必须至少为 1 毫秒")
  require(expireBatchSize >= 1 && expireBatchSize <= 500, "Suspension expiry expireBatchSize 必须位于 1..500")

/** 一次 poll 的低敏结果；不含 runId、lease token 或挂起正文。 */
final case class SuspensionExpiryCycle(
    expired: Int,
    claimed: Boolean,
    completed: Boolean,
    abandoned: Boolean
):
  require(expired >= 0, "Suspension expiry expired 计数不能为负数")
  require(!completed || claimed, "Suspension expiry completed 必须来自本轮 claim")
  require(!abandoned || claimed, "Suspension expiry abandoned 必须来自本轮 claim")
  require(!(completed && abandoned), "Suspension expiry 不能同时 completed 与 abandoned")

trait SuspensionExpiryObserver:
  def cycle(result: SuspensionExpiryCycle): UIO[Unit]
  def leaseLost(): UIO[Unit]
  def abandoned(category: ErrorCategory): UIO[Unit]
  def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit]

object SuspensionExpiryObserver:
  val noop: ULayer[SuspensionExpiryObserver] = ZLayer.succeed(new SuspensionExpiryObserver:
    def cycle(result: SuspensionExpiryCycle): UIO[Unit]                = ZIO.unit
    def leaseLost(): UIO[Unit]                                         = ZIO.unit
    def abandoned(category: ErrorCategory): UIO[Unit]                  = ZIO.unit
    def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] = ZIO.unit)

/** 把到期挂起推进为失败或按默认值恢复的多实例安全 Worker。
  *
  * 每轮先批量把到期 Pending 标为 Expired，再领取一条租约。决议经 [[RunCommitter]] 写回 `AgentState`：Worker 自己不改状态机，
  * 因此"索引已到期、权威状态仍在等待"只会存在于领取到提交之间的短暂窗口，崩溃后由下一轮 claim 重放。
  */
final class SuspensionExpiryWorker(
    owner: WorkerId,
    store: com.zyblw.agent.memory.RunStore,
    suspensions: SuspensionStore,
    committer: RunCommitter,
    observer: SuspensionExpiryObserver,
    config: SuspensionExpiryWorkerConfig
):
  /** 执行一次“决议到期—领取一个—提交或释放”的完整周期；本方法本身不 sleep。 */
  def runOnce: IO[AgentError, SuspensionExpiryCycle] =
    for
      expired <- suspensions.expireDue(config.expireBatchSize)
      claimed <- suspensions.claimExpired(owner, config.leaseDuration, limit = 1)
      result  <- claimed.headOption match
        case None        => ZIO.succeed(SuspensionExpiryCycle(expired.length, false, false, false))
        case Some(lease) => process(lease, expired.length)
      _ <- observer.cycle(result)
    yield result

  def run: IO[AgentError, Nothing] =
    runOnce
      .foldZIO(
        error =>
          observer.failed(error.category, error.retryable) *>
            (if error.retryable then ZIO.sleep(config.retryDelay) else ZIO.fail(error)),
        result => ZIO.whenDiscard(!result.claimed)(ZIO.sleep(config.pollEvery))
      )
      .forever

  def startScoped: ZIO[Scope, Nothing, Fiber.Runtime[AgentError, Nothing]] = run.forkScoped

  private def process(lease: SuspensionLease, expired: Int): IO[AgentError, SuspensionExpiryCycle] =
    supervise(lease)(applyExpiry(lease)).exit.flatMap {
      case Exit.Success(_) =>
        ZIO.succeed(SuspensionExpiryCycle(expired, claimed = true, completed = true, abandoned = false))
      case Exit.Failure(cause) =>
        cause.failureOption match
          case Some(_: AgentError.LeaseLost) =>
            observer.leaseLost().as(SuspensionExpiryCycle(expired, true, false, false))
          case Some(error) if error.retryable =>
            Clock.instant
              .flatMap(now =>
                suspensions
                  .abandon(lease, now.plusMillis(config.retryDelay.toMillis))
                  .mapError[AgentError](identity)
              )
              .tap(_ => observer.abandoned(error.category))
              .as(SuspensionExpiryCycle(expired, true, false, true))
              .catchSome { case _: AgentError.LeaseLost =>
                observer.leaseLost().as(SuspensionExpiryCycle(expired, true, false, false))
              }
          case Some(error) => ZIO.fail(error)
          case None        => ZIO.refailCause(cause)
    }

  private def supervise[A](lease: SuspensionLease)(work: IO[AgentError, A]): IO[AgentError, A] =
    work.raceFirst(heartbeatLoop(lease))

  private def heartbeatLoop[A](lease: SuspensionLease): IO[AgentError, A] =
    (ZIO.sleep(config.heartbeatEvery) *> suspensions.heartbeat(lease, config.leaseDuration).unit).forever

  /** 读取权威状态，按内核决议提交，再释放索引行。
    *
    * 状态已经没有挂起（人先批了、Run 已终态）时视为成功空操作：索引行仍要 resolve，避免同一 Run 被反复领取。
    */
  private def applyExpiry(lease: SuspensionLease): IO[AgentError, Unit] =
    for
      state <- store.load(lease.runId)
      now   <- RunClock.instant
      _     <- state.suspension match
        case None                                                => ZIO.unit
        case Some(_) if AgentKernel.terminalStatus(state.status) => ZIO.unit
        case Some(_)                                             =>
          ZIO.fromEither(AgentKernel.suspensionExpiry(state, now)).flatMap {
            case AgentKernel.SuspensionExpiryAction.Resume(transition) =>
              committer.commitTransition(state, transition).unit
            case AgentKernel.SuspensionExpiryAction.FailRun(kind) =>
              val error = AgentError.SuspensionExpired(state.runId, kind)
              ZIO.foreachDiscard(AgentKernel.fail(state, error, now))(committer.commitTransition(state, _))
          }
      _ <- suspensions.resolve(lease)
    yield ()
