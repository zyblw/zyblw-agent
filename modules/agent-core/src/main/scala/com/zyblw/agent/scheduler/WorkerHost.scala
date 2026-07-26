package com.zyblw.agent.scheduler

import com.zyblw.agent.core.*
import com.zyblw.agent.memory.*
import com.zyblw.agent.runtime.*
import zio.*

/** Durable Agent 的正式 worker 进程入口服务。
  *
  * `WorkerHost` 把此前分散的五段协议收敛成一条不可绕过的执行路径：
  *
  *   1. 从 `RunCommandStore` 原子 claim 一条类型化命令；
  *   2. 用 `CommandLeaseSupervisor` 启动 heartbeat；
  *   3. 把完整 command/owner/token/generation 交给 `LeaseAwareAgentRuntime`；
  *   4. Runtime 的每次 AgentState 保存都使用 `RunStore.commitFenced`；
  *   5. 成功后 fenced complete，失败后 fenced abandon，丢租约则中断且禁止旧 worker 改队列。
  *
  * 它刻意不继承 `ZIOAppDefault`：框架无法替业务决定 Provider、数据库连接池、工具依赖和配置来源。宿主应用应在 自己的 `ZIOApp` 中组装这些 Layer，然后把
  * `WorkerHost.run` 作为主 effect。
  *
  * @param worker
  *   已绑定 owner、调度参数、Runtime 和持久化存储的底层租约 worker
  */
final class WorkerHost private (worker: CommandWorker):
  /** 持续 claim 并执行任务，直到宿主 Scope/应用被中断。
    *
    * 队列为空会按 `pollEvery` 休眠，不会忙等；中断会沿结构化并发传播到 heartbeat、模型流和工具 Fiber。
    */
  def run: IO[AgentError, Nothing] = worker.run

  /** 只处理一个 claim 周期，供受控批处理、健康验证和确定性测试使用。
    * @return
    *   true 表示处理了一个队列条目，false 表示本轮队列为空
    */
  def claimOnce: IO[AgentError, Boolean] = worker.claimOnce

object WorkerHost:
  /** 从 ZIO 环境构造正式 Agent worker。
    *
    * @param owner
    *   部署实例唯一标识，建议使用“实例名 + 启动 UUID”
    * @param config
    *   租约、心跳、轮询、重试和最大尝试参数
    * @return
    *   依赖 durable queue 与 lease-aware Runtime 的 WorkerHost
    */
  def make(
      owner: WorkerId,
      config: WorkerHostConfig
  ): ZIO[RunCommandStore & LeaseAwareAgentRuntime, Nothing, WorkerHost] =
    for
      store   <- ZIO.service[RunCommandStore]
      runtime <- ZIO.service[LeaseAwareAgentRuntime]
      supervisor = CommandLeaseSupervisor(store, config)
      worker     = CommandWorker(
        owner,
        store,
        supervisor,
        config,
        lease => runtime.executeLeased(lease)
      )
    yield WorkerHost(worker)

  /** 固定 owner/config 后暴露为 Layer，便于宿主在启动依赖图中只请求 `WorkerHost`。
    * @param owner
    *   当前进程的稳定唯一标识
    * @param config
    *   worker 调度参数
    */
  def layer(
      owner: WorkerId,
      config: WorkerHostConfig
  ): URLayer[RunCommandStore & LeaseAwareAgentRuntime, WorkerHost] =
    ZLayer.fromZIO(make(owner, config))
