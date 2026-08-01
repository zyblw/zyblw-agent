package com.zyblw.agent.workflow

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.WorkerId
import java.time.Instant
import zio.*

/** Durable Workflow 唤醒执行面的有界调度参数。
  *
  * @param leaseDuration
  *   一次 wakeup claim 的租约长度；进程失联后其它实例只能在该时间之后重领
  * @param heartbeatEvery
  *   恢复执行期间的续租间隔，必须严格小于 leaseDuration
  * @param pollEvery
  *   当前没有可领取 wakeup 时的轮询间隔
  * @param retryDelay
  *   可重试业务/基础设施错误释放 wakeup 后的最短再次领取延迟；实际延迟不会短于节点 execution lease
  * @param expireBatchSize
  *   每轮最多原子决议的到期 wait 数，防止单个 Worker 长时间垄断数据库
  */
final case class WorkflowWakeWorkerConfig(
    leaseDuration: Duration = 30.seconds,
    heartbeatEvery: Duration = 10.seconds,
    pollEvery: Duration = 500.millis,
    retryDelay: Duration = 5.seconds,
    expireBatchSize: Int = 100
):
  require(leaseDuration.toMillis > 0L, "Workflow wake leaseDuration 必须至少为 1 毫秒")
  require(
    heartbeatEvery > Duration.Zero && heartbeatEvery < leaseDuration,
    "Workflow wake heartbeatEvery 必须在 0 与 leaseDuration 之间"
  )
  require(pollEvery.toMillis > 0L, "Workflow wake pollEvery 必须至少为 1 毫秒")
  require(retryDelay.toMillis > 0L, "Workflow wake retryDelay 必须至少为 1 毫秒")
  require(expireBatchSize >= 1 && expireBatchSize <= 500, "Workflow wake expireBatchSize 必须位于 1..500")

/** 一次 poll 的低敏结果；只用于测试、指标与容量判断，不包含 Run、Session、payload 或 lease token。 */
final case class WorkflowWakeCycle(expired: Int, claimed: Boolean, completed: Boolean, abandoned: Boolean):
  require(expired >= 0, "Workflow wake expired 计数不能为负数")
  require(!completed || claimed, "Workflow wake completed 必须来自本轮 claim")
  require(!abandoned || claimed, "Workflow wake abandoned 必须来自本轮 claim")
  require(!(completed && abandoned), "Workflow wake 不能同时 completed 与 abandoned")

/** Workflow wake worker 的低敏观测 SPI。
  *
  * 接口故意只暴露计数、错误类别和 retryable；不得向通用日志或指标写入 runId、sessionId、signal payload、owner 或 fencing token。需要逐 Run
  * 审计时应读取经过授权的 Workflow timeline。
  */
trait WorkflowWakeObserver:
  /** 一轮正常结束，包括没有可领取任务的空闲轮询。 */
  def cycle(result: WorkflowWakeCycle): UIO[Unit]

  /** 当前 Worker 在执行或回写阶段发现租约已经被抢占；旧 Fiber 已停止写入。 */
  def leaseLost(): UIO[Unit]

  /** 一次可重试失败已经安全释放并延迟重新领取；只暴露稳定错误类别。 */
  def abandoned(category: ErrorCategory): UIO[Unit]

  /** 永久错误导致 Worker 退出，或 retryable 周期错误即将退避。 */
  def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit]

object WorkflowWakeObserver:
  /** 默认不产生观测开销。 */
  val noop: ULayer[WorkflowWakeObserver] = ZLayer.succeed(new WorkflowWakeObserver:
    def cycle(result: WorkflowWakeCycle): UIO[Unit]                    = ZIO.unit
    def leaseLost(): UIO[Unit]                                         = ZIO.unit
    def abandoned(category: ErrorCategory): UIO[Unit]                  = ZIO.unit
    def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] = ZIO.unit)

  /** 只记录低基数控制信息的结构化日志实现。 */
  val logging: ULayer[WorkflowWakeObserver] = ZLayer.succeed(new WorkflowWakeObserver:
    def cycle(result: WorkflowWakeCycle): UIO[Unit] =
      ZIO.logDebug(
        s"workflow-wake cycle expired=${result.expired} claimed=${result.claimed} " +
          s"completed=${result.completed} abandoned=${result.abandoned}"
      )
    def leaseLost(): UIO[Unit]                        = ZIO.logWarning("workflow-wake lease-lost")
    def abandoned(category: ErrorCategory): UIO[Unit] =
      ZIO.logWarning(s"workflow-wake abandoned category=$category")
    def failed(category: ErrorCategory, retryable: Boolean): UIO[Unit] =
      ZIO.logError(s"workflow-wake failed category=$category retryable=$retryable"))

/** 在一个结构化并发生命周期内绑定 Workflow 恢复 Fiber 与 wakeup heartbeat Fiber。
  *
  * heartbeat 失败会通过 `raceFirst` 中断恢复及其节点/Agent 子 Fiber；即使外部副作用忽略中断并迟到返回，Store 在 checkpoint commit 时仍会再次校验
  * owner/token/generation 和当前数据库租约。
  */
final class WorkflowWakeSupervisor[S](
    store: WorkflowExecutionStore[S],
    config: WorkflowWakeWorkerConfig
):
  /** 在持续心跳保护下执行一次已领取恢复。 */
  def supervise[R, A](lease: WorkflowWakeupLease)(work: ZIO[R, AgentError, A]): ZIO[R, AgentError, A] =
    work.raceFirst(heartbeatLoop(lease))

  /** 循环可以继续使用最初的 lease 值，因为 Store 以 owner/token/generation 识别同一租约，并以权威当前到期时间判定有效性。 */
  private def heartbeatLoop[A](lease: WorkflowWakeupLease): IO[AgentError, A] =
    (ZIO.sleep(config.heartbeatEvery) *>
      store.heartbeatWakeup(lease, config.leaseDuration).unit).forever

/** 把 Signaled/TimedOut wait 推进为下一 checkpoint 的多实例安全 Worker。
  *
  * wait 行本身同时是 durable wake command，因此不存在“决议等待成功、另写队列表失败”的双写窗口。每轮先批量决议到期 timer，再只领取一个与当前 Engine
  * workflow/version 匹配的 wait：成功恢复时 wait 消费与 checkpoint 原子提交；retryable 失败时显式释放并延迟；永久错误使 Worker
  * fail-fast，由宿主监督并报警。租约丢失属于正常抢占，旧 Worker 停止写入并继续服务其它任务。
  *
  * @param owner
  *   当前进程启动时生成、部署内唯一的可信 Worker ID
  * @param store
  *   execution/checkpoint/wait 的统一耐久 Store
  * @param engine
  *   只处理其绑定 workflowId 与 definitionVersion 的 Workflow Engine
  * @param observer
  *   不暴露业务身份和正文的观测出口
  * @param config
  *   租约、心跳、轮询、退避与 timer 批次参数
  */
final class WorkflowWakeWorker[R, S](
    owner: WorkerId,
    store: WorkflowExecutionStore[S],
    engine: WorkflowEngine[R, S],
    observer: WorkflowWakeObserver,
    config: WorkflowWakeWorkerConfig
):
  private val supervisor          = WorkflowWakeSupervisor(store, config)
  private val effectiveRetryDelay = engine.executionLeaseDuration.fold(config.retryDelay) { executionLease =>
    if executionLease > config.retryDelay then executionLease else config.retryDelay
  }

  /** 执行一次“决议到期 wait—领取一个 wakeup—恢复或释放”的完整周期；本方法本身不 sleep，便于确定性测试。 */
  def runOnce: ZIO[R, AgentError, WorkflowWakeCycle] =
    for
      expired <- store.expireDue(config.expireBatchSize)
      claimed <- store.claimWakeups(
        engine.workflowId,
        engine.definitionVersion,
        owner,
        config.leaseDuration,
        limit = 1
      )
      result <- claimed.headOption match
        case None        => ZIO.succeed(WorkflowWakeCycle(expired.length, false, false, false))
        case Some(lease) => process(lease, expired.length)
      _ <- observer.cycle(result)
    yield result

  /** 持续运行直到父 Fiber/Scope 取消。
    *
    * 空闲轮询等待 pollEvery；retryable 周期错误在观测后等待 retryDelay 再继续，避免数据库/Provider 故障时热循环；永久错误保留失败并交给宿主监督。
    */
  def run: ZIO[R, AgentError, Nothing] =
    runOnce
      .foldZIO(
        error =>
          observer.failed(error.category, error.retryable) *>
            (if error.retryable then ZIO.sleep(config.retryDelay) else ZIO.fail(error)),
        result => ZIO.whenDiscard(!result.claimed)(ZIO.sleep(config.pollEvery))
      )
      .forever

  /** 将后台 Worker Fiber 绑定到调用方 Scope；Scope 关闭会中断 poll、heartbeat 和正在恢复的节点。 */
  def startScoped: ZIO[R & Scope, Nothing, Fiber.Runtime[AgentError, Nothing]] = run.forkScoped

  private def process(lease: WorkflowWakeupLease, expired: Int): ZIO[R, AgentError, WorkflowWakeCycle] =
    val context = WorkflowContext(lease.key.runId, lease.record.sessionId)
    supervisor
      .supervise(lease)(engine.resumeClaimed(context, lease).runDrain)
      .exit
      .flatMap {
        case Exit.Success(_) =>
          ZIO.succeed(WorkflowWakeCycle(expired, claimed = true, completed = true, abandoned = false))
        case Exit.Failure(cause) =>
          cause.failureOption match
            case Some(_: AgentError.LeaseLost) =>
              observer.leaseLost().as(WorkflowWakeCycle(expired, true, false, false))
            case Some(error) if error.retryable =>
              Clock.instant
                .flatMap(now =>
                  store
                    .abandonWakeup(lease, plus(now, effectiveRetryDelay))
                    .mapError[AgentError](identity)
                )
                .tap(_ => observer.abandoned(error.category))
                .as(WorkflowWakeCycle(expired, true, false, true))
                .catchSome { case _: AgentError.LeaseLost =>
                  observer.leaseLost().as(WorkflowWakeCycle(expired, true, false, false))
                }
            case Some(error) => ZIO.fail(error)
            case None        => ZIO.refailCause(cause)
      }

  private def plus(instant: Instant, duration: Duration): Instant =
    instant.plusMillis(duration.toMillis)
